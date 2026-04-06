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
package org.opennms.netmgt.filter;

import org.opennms.netmgt.filter.api.FilterDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>FilterDaoFactory class.</p>
 *
 * Static singleton holder for FilterDao. Daemon-boot modules call
 * {@link #setInstance(FilterDao)} with a Spring-constructed JdbcFilterDao
 * during startup.
 *
 * @author <a href="mailto:dj@opennms.org">DJ Gregor</a>
 */
public class FilterDaoFactory {
    private static final Logger LOG = LoggerFactory.getLogger(FilterDaoFactory.class);
    private static FilterDao m_filterDao;

    private FilterDaoFactory() {
    }

    /**
     * <p>getInstance</p>
     *
     * @return a {@link org.opennms.netmgt.filter.api.FilterDao} object.
     */
    public static FilterDao getInstance() {
        if (m_filterDao == null) {
            throw new IllegalStateException("FilterDaoFactory not initialized — call setInstance() first");
        }
        return m_filterDao;
    }

    /**
     * <p>setInstance</p>
     *
     * @param filterDao a {@link org.opennms.netmgt.filter.api.FilterDao} object.
     */
    public static void setInstance(final FilterDao filterDao) {
        LOG.debug("setInstance({})", filterDao);
        m_filterDao = filterDao;
    }
}
