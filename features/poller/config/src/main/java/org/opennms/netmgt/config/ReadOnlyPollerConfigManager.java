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
import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;

import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.netmgt.config.poller.PollerConfiguration;
import org.opennms.netmgt.filter.api.FilterDao;

/**
 * Reads an up-to-date copy of poller-configuration.xml without affecting
 * the global singleton in {@link PollerConfigFactory}.
 *
 * <p>Uses a no-op FilterDao since read-only config doesn't evaluate filters
 * at construction time — only when explicitly queried.</p>
 */
public class ReadOnlyPollerConfigManager extends PollerConfigManager {

    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = XmlMapper.builder().defaultUseWrapper(false).build();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private ReadOnlyPollerConfigManager(final PollerConfiguration config, final FilterDao filterDao) {
        super(config, filterDao);
    }

    /**
     * Create a read-only config manager from the default poller-configuration.xml.
     * Requires a FilterDao for filter rule evaluation.
     */
    public static ReadOnlyPollerConfigManager create(final FilterDao filterDao) throws IOException {
        final File cfgFile = ConfigFileConstants.getFile(ConfigFileConstants.POLLER_CONFIG_FILE_NAME);
        final PollerConfiguration config = XML_MAPPER.readValue(cfgFile, PollerConfiguration.class);
        return new ReadOnlyPollerConfigManager(config, filterDao);
    }

    @Override
    protected void saveXml(String xml) throws IOException {
        // read-only — no-op
    }
}
