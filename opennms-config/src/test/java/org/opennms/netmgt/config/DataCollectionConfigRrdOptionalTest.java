/*
 * Copyright 2026 The OpenNMS Group, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */

package org.opennms.netmgt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.Test;
import org.opennms.netmgt.rrd.RrdRepository;
import org.springframework.core.io.ByteArrayResource;

/**
 * Verifies that {@link DefaultDataCollectionConfigDao} tolerates a datacollection
 * config with no RRDtool configuration (no {@code rrdRepository} attribute and no
 * {@code <rrd>} block) — permitted for non-RRD (e.g. Kafka-first) deployments — and
 * still builds a valid {@link RrdRepository} with safe defaults, without regressing
 * the behaviour for a config that does declare RRD.
 */
public class DataCollectionConfigRrdOptionalTest {

    /** No rrdRepository attribute, and the snmp-collection has no <rrd> block. */
    private static final String RRD_FREE =
            "<datacollection-config xmlns=\"http://xmlns.opennms.org/xsd/config/datacollection\">\n" +
            "  <snmp-collection name=\"default\" snmpStorageFlag=\"select\"/>\n" +
            "</datacollection-config>\n";

    /** Conventional config with rrdRepository + <rrd> — must be unaffected. */
    private static final String WITH_RRD =
            "<datacollection-config xmlns=\"http://xmlns.opennms.org/xsd/config/datacollection\"\n" +
            "    rrdRepository=\"/var/lib/opennms/rrd/snmp\">\n" +
            "  <snmp-collection name=\"default\" snmpStorageFlag=\"select\">\n" +
            "    <rrd step=\"600\">\n" +
            "      <rra>RRA:AVERAGE:0.5:1:2016</rra>\n" +
            "    </rrd>\n" +
            "  </snmp-collection>\n" +
            "</datacollection-config>\n";

    private static DefaultDataCollectionConfigDao load(final String xml) {
        final DefaultDataCollectionConfigDao dao = new DefaultDataCollectionConfigDao();
        dao.setConfigResource(new ByteArrayResource(xml.getBytes()));
        // blank config dir so no extra config files are pulled in
        dao.setConfigDirectory("");
        dao.afterPropertiesSet();
        return dao;
    }

    @Test
    public void buildsValidRepositoryFromRrdFreeConfig() {
        final DefaultDataCollectionConfigDao dao = load(RRD_FREE);

        final RrdRepository repo = dao.getRrdRepository("default");
        assertNotNull("getRrdRepository must not be null for an RRD-free config", repo);
        assertNotNull("base directory must be non-null", repo.getRrdBaseDir());

        assertEquals("default step (300) when <rrd> is absent", 300, dao.getStep("default"));

        final List<String> rras = dao.getRRAList("default");
        assertNotNull("RRA list must be empty, never null", rras);
        assertTrue("RRA list must be empty when <rrd> is absent", rras.isEmpty());

        assertNotNull("getRrdPath must return a non-null sentinel when rrdRepository is absent",
                dao.getRrdPath());
        // the consumed invariant: the base-dir leaf is always "snmp"
        assertEquals("snmp", repo.getRrdBaseDir().getName());
        assertEquals("heartBeat is 2 * step", 600, repo.getHeartBeat());
    }

    @Test
    public void rrdPathDefaultsToRelativeSnmpWhenBaseDirUnset() {
        final String saved = System.getProperty("rrd.base.dir");
        System.clearProperty("rrd.base.dir");
        try {
            final DefaultDataCollectionConfigDao dao = load(RRD_FREE);
            // must be the relative "snmp", never the filesystem root "/snmp"
            // (new File("", "snmp") resolves to "/snmp")
            assertEquals("snmp", dao.getRrdPath());
            assertFalse("must not resolve to the filesystem root",
                    new File(dao.getRrdPath()).isAbsolute());
        } finally {
            if (saved == null) {
                System.clearProperty("rrd.base.dir");
            } else {
                System.setProperty("rrd.base.dir", saved);
            }
        }
    }

    @Test
    public void unknownCollectionKeepsNotFoundSentinel() {
        final DefaultDataCollectionConfigDao dao = load(RRD_FREE);
        // collection-not-found must stay distinct from found-but-no-<rrd>
        assertEquals(-1, dao.getStep("does-not-exist"));
    }

    @Test
    public void rrdConfiguredCollectionIsUnaffected() {
        final DefaultDataCollectionConfigDao dao = load(WITH_RRD);
        assertEquals("declared step is used verbatim", 600, dao.getStep("default"));
        final List<String> rras = dao.getRRAList("default");
        assertEquals(1, rras.size());
        assertEquals("RRA:AVERAGE:0.5:1:2016", rras.get(0));

        final RrdRepository repo = dao.getRrdRepository("default");
        assertEquals("declared rrdRepository path is used", "/var/lib/opennms/rrd/snmp",
                repo.getRrdBaseDir().getPath());
        assertEquals(600, repo.getStep());
        assertEquals(1200, repo.getHeartBeat());
    }
}
