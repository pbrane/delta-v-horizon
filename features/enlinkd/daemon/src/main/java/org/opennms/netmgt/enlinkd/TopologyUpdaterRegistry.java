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

import java.util.EnumMap;
import java.util.Map;

import org.opennms.netmgt.enlinkd.common.TopologyUpdater;
import org.opennms.netmgt.enlinkd.service.api.ProtocolSupported;

/**
 * Registry for topology updaters keyed by {@link ProtocolSupported}.
 *
 * <p>Replaces the 10 individual updater fields in {@link EnhancedLinkd} with a
 * single map-based lookup. Also holds the {@link DiscoveryBridgeDomains}
 * instance which is coupled to the BRIDGE updater lifecycle.</p>
 */
public class TopologyUpdaterRegistry {

    private final Map<ProtocolSupported, TopologyUpdater> updaters = new EnumMap<>(ProtocolSupported.class);
    private DiscoveryBridgeDomains discoveryBridgeDomains;

    public TopologyUpdater get(ProtocolSupported protocol) {
        return updaters.get(protocol);
    }

    @SuppressWarnings("unchecked")
    public <T extends TopologyUpdater> T get(ProtocolSupported protocol, Class<T> type) {
        return (T) updaters.get(protocol);
    }

    public void put(ProtocolSupported protocol, TopologyUpdater updater) {
        updaters.put(protocol, updater);
    }

    public DiscoveryBridgeDomains getDiscoveryBridgeDomains() {
        return discoveryBridgeDomains;
    }

    public void setDiscoveryBridgeDomains(DiscoveryBridgeDomains discoveryBridgeDomains) {
        this.discoveryBridgeDomains = discoveryBridgeDomains;
    }
}
