/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.enlinkd;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opennms.netmgt.config.EnhancedLinkdConfig;
import org.opennms.netmgt.daemon.AbstractServiceDaemon;
import org.opennms.netmgt.enlinkd.api.ReloadableTopologyDaemon;
import org.opennms.netmgt.enlinkd.common.SchedulableNodeCollectorGroup;
import org.opennms.netmgt.enlinkd.common.TopologyUpdater;
import org.opennms.netmgt.enlinkd.service.api.BridgeTopologyService;
import org.opennms.netmgt.enlinkd.service.api.CdpTopologyService;
import org.opennms.netmgt.enlinkd.service.api.IpNetToMediaTopologyService;
import org.opennms.netmgt.enlinkd.service.api.IsisTopologyService;
import org.opennms.netmgt.enlinkd.service.api.LldpTopologyService;
import org.opennms.netmgt.enlinkd.service.api.Node;
import org.opennms.netmgt.enlinkd.service.api.NodeTopologyService;
import org.opennms.netmgt.enlinkd.service.api.OspfTopologyService;
import org.opennms.netmgt.enlinkd.service.api.ProtocolSupported;
import org.opennms.netmgt.scheduler.LegacyPriorityExecutor;
import org.opennms.netmgt.scheduler.LegacyScheduler;
import org.opennms.netmgt.scheduler.Schedulable;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * EnhancedLinkd class.
 * </p>
 *
 * @author antonio
 * @author ranger
 * @version $Id: $
 */
public class EnhancedLinkd extends AbstractServiceDaemon implements ReloadableTopologyDaemon {

    private final static Logger LOG = LoggerFactory.getLogger(EnhancedLinkd.class);
    /**
     * The log4j category used to log messages.
     */
    private static final String LOG_PREFIX = "enlinkd";

    /**
     * threads scheduler
     */
    private LegacyScheduler m_scheduler;

    /**
     * threads priority executor
     */
    private LegacyPriorityExecutor m_executor;

    // --- All dependencies injected via constructor ---

    private final EnhancedLinkdConfig m_linkdConfig;
    private final NodeTopologyService m_queryMgr;
    private final BridgeTopologyService m_bridgeTopologyService;
    private final CdpTopologyService m_cdpTopologyService;
    private final IsisTopologyService m_isisTopologyService;
    private final IpNetToMediaTopologyService m_ipNetToMediaTopologyService;
    private final LldpTopologyService m_lldpTopologyService;
    private final OspfTopologyService m_ospfTopologyService;
    private final LocationAwareSnmpClient m_locationAwareSnmpClient;

    private final TopologyUpdaterRegistry m_registry;

    private final List<SchedulableNodeCollectorGroup> m_groups = new ArrayList<>();

    /**
     * Constructor for EnhancedLinkd — all dependencies injected explicitly.
     */
    public EnhancedLinkd(
            EnhancedLinkdConfig linkdConfig,
            NodeTopologyService queryMgr,
            BridgeTopologyService bridgeTopologyService,
            CdpTopologyService cdpTopologyService,
            IsisTopologyService isisTopologyService,
            IpNetToMediaTopologyService ipNetToMediaTopologyService,
            LldpTopologyService lldpTopologyService,
            OspfTopologyService ospfTopologyService,
            LocationAwareSnmpClient locationAwareSnmpClient,
            TopologyUpdaterRegistry registry) {
        super(LOG_PREFIX);
        m_linkdConfig = linkdConfig;
        m_queryMgr = queryMgr;
        m_bridgeTopologyService = bridgeTopologyService;
        m_cdpTopologyService = cdpTopologyService;
        m_isisTopologyService = isisTopologyService;
        m_ipNetToMediaTopologyService = ipNetToMediaTopologyService;
        m_lldpTopologyService = lldpTopologyService;
        m_ospfTopologyService = ospfTopologyService;
        m_locationAwareSnmpClient = locationAwareSnmpClient;
        m_registry = registry;
    }

    /**
     * <p>
     * onInit
     * </p>
     */
    protected void onInit() {
        try {
            LOG.info("init: Creating EnhancedLinkd scheduler");
            m_scheduler = new LegacyScheduler("EnhancedLinkd", m_linkdConfig.getThreads());
        } catch (RuntimeException e) {
            LOG.error("init: Failed to create EnhancedLinkd scheduler", e);
            throw e;
        }

        try {
            LOG.info("init: Creating EnhancedLinkd executor");
            m_executor = new LegacyPriorityExecutor("EnhancedLinkd", m_linkdConfig.getExecutorThreads(), m_linkdConfig.getExecutorQueueSize());
        } catch (RuntimeException e) {
            LOG.error("init: Failed to create EnhancedLinkd executor", e);
            throw e;
        }
        LOG.debug("init: Loading Bridge Topology.....");
        m_bridgeTopologyService.load();
        LOG.debug("init: Bridge Topology loaded.");

        schedule(true);
    }

    private void schedule(boolean init) {
        if (init) {
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.NODES));
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.NETWORKROUTER));
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.USERDEFINED));
        }

        if (m_linkdConfig.useCdpDiscovery()) {
            NodeCollectionGroupCdp nodeCollectionGroupCdp = new NodeCollectionGroupCdp(m_linkdConfig.getCdpRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getCdpPriority(), m_queryMgr, m_locationAwareSnmpClient, m_cdpTopologyService);
            nodeCollectionGroupCdp.setScheduler(m_scheduler);
            nodeCollectionGroupCdp.schedule();
            m_groups.add(nodeCollectionGroupCdp);
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.CDP));
        } else {
            m_cdpTopologyService.deletePersistedData();
        }

        if (m_linkdConfig.useLldpDiscovery()) {
            NodeCollectionGroupLldp nodeCollectionGroupLldp = new NodeCollectionGroupLldp(m_linkdConfig.getLldpRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getLldpPriority(), m_queryMgr, m_locationAwareSnmpClient, m_lldpTopologyService);
            nodeCollectionGroupLldp.setScheduler(m_scheduler);
            nodeCollectionGroupLldp.schedule();
            m_groups.add(nodeCollectionGroupLldp);
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.LLDP));
       } else {
            m_lldpTopologyService.deletePersistedData();
        }

        if (m_linkdConfig.useIsisDiscovery()) {
            NodeCollectionGroupIsis nodeCollectionGroupIsis = new NodeCollectionGroupIsis(m_linkdConfig.getIsisRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getIsisPriority(), m_queryMgr, m_locationAwareSnmpClient, m_isisTopologyService);
            nodeCollectionGroupIsis.setScheduler(m_scheduler);
            nodeCollectionGroupIsis.schedule();
            m_groups.add(nodeCollectionGroupIsis);
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.ISIS));
        } else {
            m_isisTopologyService.deletePersistedData();
        }

        if (m_linkdConfig.useOspfDiscovery()) {
            NodeCollectionGroupOspf nodeCollectionGroupOspf = new NodeCollectionGroupOspf(m_linkdConfig.getOspfRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getOspfPriority(), m_queryMgr, m_locationAwareSnmpClient, m_ospfTopologyService);
            nodeCollectionGroupOspf.setScheduler(m_scheduler);
            nodeCollectionGroupOspf.schedule();
            m_groups.add(nodeCollectionGroupOspf);
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.OSPF));
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.OSPFAREA));
        } else {
            m_ospfTopologyService.deletePersistedData();
        }

        if (m_linkdConfig.useBridgeDiscovery()) {
            NodeCollectionGroupIpNetToMedia nodeCollectionGroupIpNetToMedia = new NodeCollectionGroupIpNetToMedia(m_linkdConfig.getBridgeRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getBridgePriority(), m_queryMgr, m_locationAwareSnmpClient, m_ipNetToMediaTopologyService);
            nodeCollectionGroupIpNetToMedia.setScheduler(m_scheduler);
            nodeCollectionGroupIpNetToMedia.schedule();
            m_groups.add(nodeCollectionGroupIpNetToMedia);
            NodeCollectionGroupBridge nodeCollectionGroupBridge = new NodeCollectionGroupBridge(m_linkdConfig.getBridgeRescanInterval(), m_linkdConfig.getInitialSleepTime(), m_executor, m_linkdConfig.getBridgePriority(), m_queryMgr, m_locationAwareSnmpClient, m_bridgeTopologyService, m_linkdConfig.getMaxBft(), m_linkdConfig.disableBridgeVlanDiscovery());
            nodeCollectionGroupBridge.setScheduler(m_scheduler);
            nodeCollectionGroupBridge.schedule();
            m_groups.add(nodeCollectionGroupBridge);
            scheduleDiscoveryBridgeDomain();
            scheduleAndRegisterOnmsTopologyUpdater(m_registry.get(ProtocolSupported.BRIDGE));
        } else {
            m_bridgeTopologyService.deletePersistedData();
        }
    }

    public void scheduleAndRegisterOnmsTopologyUpdater(TopologyUpdater onmsTopologyUpdater) {
        onmsTopologyUpdater.setScheduler(m_scheduler);
        onmsTopologyUpdater.setPollInterval(m_linkdConfig.getTopologyInterval());
        onmsTopologyUpdater.setInitialSleepTime(0L);
        LOG.info("scheduleOnmsTopologyUpdater: Scheduling {}",
                 onmsTopologyUpdater.getInfo());
        onmsTopologyUpdater.schedule();
        onmsTopologyUpdater.register();
    }

    public void scheduleDiscoveryBridgeDomain() {
            var dbd = m_registry.getDiscoveryBridgeDomains();
            dbd.setScheduler(m_scheduler);
            dbd.setPollInterval(m_linkdConfig.getBridgeTopologyInterval());
            dbd.setInitialSleepTime(m_linkdConfig.getBridgeTopologyInterval()+m_linkdConfig.getInitialSleepTime());
            dbd.setMaxthreads(m_linkdConfig.getDiscoveryBridgeThreads());
            LOG.info("scheduleDiscoveryBridgeDomain: Scheduling {}",
                     dbd.getInfo());
            dbd.schedule();
    }

    /**
     * <p>
     * onStart
     * </p>
     */
    protected synchronized void onStart() {

        // start the scheduler
        //
        m_scheduler.start();

        m_executor.start();

    }

    /**
     * <p>
     * onStop
     * </p>
     */
    protected synchronized void onStop() {
              // Stop the scheduler
        m_scheduler.stop();
        m_scheduler = null;
        m_executor.stop();
    }

    /**
     * <p>
     * onPause
     * </p>
     */
    protected synchronized void onPause() {
        m_scheduler.pause();
        m_executor.pause();
    }

    /**
     * <p>
     * onResume
     * </p>
     */
    protected synchronized void onResume() {
        m_scheduler.resume();
        m_executor.resume();
    }

    public boolean execSingleSnmpCollection(final int nodeId) {
        final Node node = m_queryMgr.getSnmpNode(nodeId);
        if (node == null) {
            return false;
        }
        for (SchedulableNodeCollectorGroup group: m_groups) {
            m_executor.addPriorityReadyRunnable(group.getNodeCollector(node, 0));
        }
        return true;
    }

    public boolean runSingleSnmpCollection(final String nodeId, String proto) {
        final Node node = m_queryMgr.getSnmpNode(nodeId);
        if (node == null) {
            return false;
        }
        boolean runned = false;
        for (SchedulableNodeCollectorGroup group: m_groups) {
            if (group.getProtocolSupported().name().equalsIgnoreCase(proto)) {
                group.getNodeCollector(node, 0).collect();
                runned = true;
            }
        }
        return runned;
    }

    public boolean runSingleSnmpCollection(final int nodeId) {
        final Node node = m_queryMgr.getSnmpNode(nodeId);
        if (node == null) {
            return false;
        }
        for (SchedulableNodeCollectorGroup group: m_groups) {
            group.getNodeCollector(node, 0).collect();
        }
       return true;
    }

    public void runDiscoveryBridgeDomains() {
            m_registry.getDiscoveryBridgeDomains().runSchedulable();
    }

    private boolean isEnabled(ProtocolSupported proto) {
        return switch (proto) {
            case CDP -> m_linkdConfig.useCdpDiscovery();
            case LLDP -> m_linkdConfig.useLldpDiscovery();
            case ISIS -> m_linkdConfig.useIsisDiscovery();
            case OSPF, OSPFAREA -> m_linkdConfig.useOspfDiscovery();
            case BRIDGE -> m_linkdConfig.useBridgeDiscovery();
            case NODES, USERDEFINED, NETWORKROUTER -> true;
        };
    }

    public void forceTopologyUpdaterRun(ProtocolSupported proto) {
        TopologyUpdater updater = m_registry.get(proto);
        if (updater != null && isEnabled(proto)) {
            updater.forceRun();
        }
    }

    public void runTopologyUpdater(ProtocolSupported proto) {
        TopologyUpdater updater = m_registry.get(proto);
        if (updater != null && isEnabled(proto)) {
            updater.runSchedulable();
        }
    }

    public void addNode() {
        m_queryMgr.updatesAvailable();
    }

    void deleteNode(int nodeid) {
        LOG.info("deleteNode: deleting LinkableNode for node {}",
                        nodeid);
        m_bridgeTopologyService.delete(nodeid);
        m_cdpTopologyService.delete(nodeid);
        m_isisTopologyService.delete(nodeid);
        m_lldpTopologyService.delete(nodeid);
        m_ospfTopologyService.delete(nodeid);
        m_ipNetToMediaTopologyService.delete(nodeid);

        m_queryMgr.updatesAvailable();

    }

    void suspendNodeCollection(final int nodeid) {
        LOG.info("suspendNodeCollection: suspend collection LinkableNode for node {}",
                        nodeid);
        m_groups.forEach(g -> g.suspend(nodeid));
    }

    void wakeUpNodeCollection(int nodeid) {
        LOG.info("wakeUpNodeCollection: wakeUp collection LinkableNode for node {}",
                nodeid);
        m_groups.forEach(g -> g.wakeUp(nodeid));
    }

    public NodeTopologyService getQueryManager() {
        return m_queryMgr;
    }

    public LegacyScheduler getScheduler() {
        return m_scheduler;
    }

    public void setScheduler(LegacyScheduler scheduler) {
        m_scheduler = scheduler;
    }

    public EnhancedLinkdConfig getLinkdConfig() {
        return m_linkdConfig;
    }

    public String getSource() {
        return "enlinkd";
    }

    public BridgeTopologyService getBridgeTopologyService() {
        return m_bridgeTopologyService;
    }

    public CdpTopologyService getCdpTopologyService() {
        return m_cdpTopologyService;
    }

    public IsisTopologyService getIsisTopologyService() {
        return m_isisTopologyService;
    }

    public LldpTopologyService getLldpTopologyService() {
        return m_lldpTopologyService;
    }

    public OspfTopologyService getOspfTopologyService() {
        return m_ospfTopologyService;
    }

    public IpNetToMediaTopologyService getIpNetToMediaTopologyService() {
        return m_ipNetToMediaTopologyService;
    }

    public TopologyUpdaterRegistry getRegistry() {
        return m_registry;
    }

    public NodesOnmsTopologyUpdater getNodesTopologyUpdater() {
        return m_registry.get(ProtocolSupported.NODES, NodesOnmsTopologyUpdater.class);
    }
    public NetworkRouterTopologyUpdater getNetworkRouterTopologyUpdater() {
        return m_registry.get(ProtocolSupported.NETWORKROUTER, NetworkRouterTopologyUpdater.class);
    }
    public CdpOnmsTopologyUpdater getCdpTopologyUpdater() {
        return m_registry.get(ProtocolSupported.CDP, CdpOnmsTopologyUpdater.class);
    }
    public LldpOnmsTopologyUpdater getLldpTopologyUpdater() {
        return m_registry.get(ProtocolSupported.LLDP, LldpOnmsTopologyUpdater.class);
    }
    public IsisOnmsTopologyUpdater getIsisTopologyUpdater() {
        return m_registry.get(ProtocolSupported.ISIS, IsisOnmsTopologyUpdater.class);
    }
    public BridgeOnmsTopologyUpdater getBridgeTopologyUpdater() {
        return m_registry.get(ProtocolSupported.BRIDGE, BridgeOnmsTopologyUpdater.class);
    }
    public OspfOnmsTopologyUpdater getOspfTopologyUpdater() {
        return m_registry.get(ProtocolSupported.OSPF, OspfOnmsTopologyUpdater.class);
    }
    public OspfAreaOnmsTopologyUpdater getOspfAreaTopologyUpdater() {
        return m_registry.get(ProtocolSupported.OSPFAREA, OspfAreaOnmsTopologyUpdater.class);
    }

    @Override
    public void reload() {
        LOG.info("reload: reload enlinkd daemon service");

        m_groups.forEach(Schedulable::unschedule);
        m_groups.clear();

        reloadUpdater(ProtocolSupported.OSPF, OspfOnmsTopologyUpdater.class, OspfOnmsTopologyUpdater::clone);
        reloadUpdater(ProtocolSupported.OSPFAREA, OspfAreaOnmsTopologyUpdater.class, OspfAreaOnmsTopologyUpdater::clone);
        reloadUpdater(ProtocolSupported.LLDP, LldpOnmsTopologyUpdater.class, LldpOnmsTopologyUpdater::clone);
        reloadUpdater(ProtocolSupported.ISIS, IsisOnmsTopologyUpdater.class, IsisOnmsTopologyUpdater::clone);
        reloadUpdater(ProtocolSupported.CDP, CdpOnmsTopologyUpdater.class, CdpOnmsTopologyUpdater::clone);

        var bridgeUpdater = m_registry.get(ProtocolSupported.BRIDGE, BridgeOnmsTopologyUpdater.class);
        if (bridgeUpdater != null && bridgeUpdater.isRegistered()) {
            bridgeUpdater.unschedule();
            bridgeUpdater.unregister();
            m_registry.put(ProtocolSupported.BRIDGE, BridgeOnmsTopologyUpdater.clone(bridgeUpdater));
            m_registry.getDiscoveryBridgeDomains().unschedule();
            m_registry.setDiscoveryBridgeDomains(
                    DiscoveryBridgeDomains.clone(m_registry.getDiscoveryBridgeDomains()));
        }

        schedule(false);
    }

    private <T extends TopologyUpdater> void reloadUpdater(
            ProtocolSupported proto, Class<T> type, java.util.function.UnaryOperator<T> cloner) {
        T updater = m_registry.get(proto, type);
        if (updater != null && updater.isRegistered()) {
            updater.unschedule();
            updater.unregister();
            m_registry.put(proto, cloner.apply(updater));
        }
    }

    @Override
    public boolean reloadConfig() {
        LOG.info("reloadConfig: reload enlinkd configuration file and daemon service");
        try {
            m_linkdConfig.reload();
        } catch (IOException e) {
            LOG.error("reloadConfig: cannot reload config: {}", e.getMessage());
            return false;
        }
        reload();
        return true;
    }

    @Override
    public void reloadTopology() {
        LOG.info("reloadTopology: reload enlinkd topology updaters");
        LOG.debug("reloadTopology: Loading Bridge Topology.....");
        m_bridgeTopologyService.load();
        LOG.debug("reloadTopology: Bridge Topology Loaded");
        for (ProtocolSupported protocol :ProtocolSupported.values()) {
            forceTopologyUpdaterRun(protocol);
            runTopologyUpdater(protocol);
        }
    }
}
