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
package org.opennms.netmgt.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;

import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.netmgt.config.poller.PollerConfiguration;
import org.opennms.netmgt.filter.api.FilterDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton factory for the OpenNMS Poller configuration.
 *
 * <p>Loads poller-configuration.xml and exposes a {@link PollerConfig} interface
 * for the Poller daemon. Uses Jackson XmlMapper for XML parsing and constructor-injected
 * {@link FilterDao} for filter rule validation and IP address resolution.</p>
 *
 * <p>Daemon-boot modules create instances via the
 * {@link #PollerConfigFactory(long, PollerConfiguration, FilterDao)} constructor
 * and register them via {@link #setInstance(PollerConfig)}.</p>
 */
public final class PollerConfigFactory extends PollerConfigManager {
    private static final Logger LOG = LoggerFactory.getLogger(PollerConfigFactory.class);

    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = XmlMapper.builder().defaultUseWrapper(false).build();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static PollerConfig m_singleton = null;
    private static boolean m_loaded = false;
    private long m_currentVersion = -1L;
    private static File m_pollerConfigFile;

    /**
     * Constructor accepting a pre-parsed configuration and injected FilterDao.
     *
     * @param currentVersion file modification timestamp for change detection
     * @param config         the parsed PollerConfiguration (from Jackson XmlMapper)
     * @param filterDao      the FilterDao for evaluating filter rules
     */
    public PollerConfigFactory(final long currentVersion, final PollerConfiguration config, final FilterDao filterDao) {
        super(config, filterDao);
        m_currentVersion = currentVersion;
    }

    private static File getPollerConfigFile() throws IOException {
        if (m_pollerConfigFile == null) {
            m_pollerConfigFile = ConfigFileConstants.getFile(ConfigFileConstants.POLLER_CONFIG_FILE_NAME);
        }
        return m_pollerConfigFile;
    }

    public static void setPollerConfigFile(final File pollerConfigFile) throws IOException {
        m_pollerConfigFile = pollerConfigFile;
    }

    /**
     * Validates filter rules and ds-name lengths for each package/service.
     *
     * @param config    the configuration to validate
     * @param filterDao the FilterDao to use for rule validation
     */
    public static void validate(final PollerConfiguration config, final FilterDao filterDao) {
        for (final org.opennms.netmgt.config.poller.Package pollerPackage : config.getPackages()) {
            filterDao.validateRule(pollerPackage.getFilter().getContent());
            for (final org.opennms.netmgt.config.poller.Service service : pollerPackage.getServices()) {
                for (final org.opennms.netmgt.config.poller.Parameter parm : service.getParameters()) {
                    if (parm.getKey().equals("ds-name")) {
                        if (parm.getValue().length() > ConfigFileConstants.RRD_DS_MAX_SIZE) {
                            throw new IllegalStateException(String.format("ds-name '%s' in service '%s' (poller package '%s') is greater than %d characters",
                                    parm.getValue(), service.getName(), pollerPackage.getName(), ConfigFileConstants.RRD_DS_MAX_SIZE)
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * Return the singleton instance of this factory.
     *
     * @return The current factory instance.
     * @throws IllegalStateException if the factory has not been initialized.
     */
    public static synchronized PollerConfig getInstance() {
        if (!m_loaded) {
            throw new IllegalStateException("The factory has not been initialized");
        }
        return m_singleton;
    }

    /**
     * Set the singleton instance. Called by daemon-boot after constructing
     * with Jackson XmlMapper + FilterDao.
     *
     * @param instance the PollerConfig to register as singleton
     */
    public static synchronized void setInstance(final PollerConfig instance) {
        m_singleton = instance;
        m_loaded = true;
    }

    @Override
    protected void saveXml(final String xml) throws IOException {
        if (xml != null) {
            getWriteLock().lock();
            try {
                final long timestamp = System.currentTimeMillis();
                final File cfgFile = getPollerConfigFile();
                LOG.debug("saveXml: saving config file at {}: {}", timestamp, cfgFile.getPath());
                final Writer fileWriter = new OutputStreamWriter(new FileOutputStream(cfgFile), StandardCharsets.UTF_8);
                fileWriter.write(xml);
                fileWriter.flush();
                fileWriter.close();
                LOG.debug("saveXml: finished saving config file: {}", cfgFile.getPath());
            } finally {
                getWriteLock().unlock();
            }
        }
    }

    @Override
    public void update() throws IOException {
        getWriteLock().lock();
        try {
            final File cfgFile = getPollerConfigFile();
            if (cfgFile.lastModified() > m_currentVersion) {
                m_currentVersion = cfgFile.lastModified();
                LOG.debug("update: reloading config file: {}", cfgFile.getPath());
                final PollerConfiguration config = XML_MAPPER.readValue(cfgFile, PollerConfiguration.class);
                validate(config, m_filterDao);
                m_config = config;
                LOG.debug("update: finished reloading config file: {}", cfgFile.getPath());
            }
        } finally {
            getWriteLock().unlock();
        }
        super.update();
    }
}
