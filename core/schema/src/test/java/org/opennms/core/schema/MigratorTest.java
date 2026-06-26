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
package org.opennms.core.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Before;
import org.junit.Test;

//This is test Migrator not directly doing operation on DB
public class MigratorTest {
    private Migrator migrator;

    @Before
    public void setup() {
        migrator = new Migrator();
    }


    @Test
    public void testGetUserForONMSDBWithHostname() {
        String userName = "opennms@test-env-onms";
        Migrator migrator = new Migrator();
        migrator.setDatabaseUser(userName);
        assertEquals("opennms",  migrator.getUserForONMSDB());
    }


    @Test
    public void testGetUserForONMSDBWithoutHostname() {
        String userName = "opennms";
        Migrator migrator = new Migrator();
        migrator.setDatabaseUser(userName);
        assertEquals("opennms",  migrator.getUserForONMSDB());
    }

    // 3.1 Below the minimum is rejected; at/above the minimum with no ceiling is accepted.
    @Test
    public void testVersionBelowMinimumIsRejected() {
        assertThrows(MigrationException.class,
                () -> Migrator.checkDatabaseVersionInRange(13.0f, 14.0f, null));
    }

    @Test
    public void testVersionAtMinimumWithNoCeilingIsAccepted() throws MigrationException {
        Migrator.checkDatabaseVersionInRange(14.0f, 14.0f, null);
    }

    // 3.2 With no ceiling, a new major such as PG 18 is accepted.
    @Test
    public void testNewMajorWithNoCeilingIsAccepted() throws MigrationException {
        Migrator.checkDatabaseVersionInRange(18.0f, 14.0f, null);
    }

    // 3.3 With an opt-in ceiling of 19.0: 18 accepted, 19 rejected.
    @Test
    public void testVersionBelowCeilingIsAccepted() throws MigrationException {
        Migrator.checkDatabaseVersionInRange(18.0f, 14.0f, 19.0f);
    }

    @Test
    public void testVersionAtCeilingIsRejected() {
        assertThrows(MigrationException.class,
                () -> Migrator.checkDatabaseVersionInRange(19.0f, 14.0f, 19.0f));
    }

    // 3.3 The opt-in ceiling is read from the system property; unset means no limit.
    @Test
    public void testCeilingUnsetWhenPropertyAbsent() {
        final String previous = System.getProperty("opennms.postgresql.maxVersion");
        System.clearProperty("opennms.postgresql.maxVersion");
        try {
            assertEquals(null, Migrator.parseOptionalMaxVersion());
        } finally {
            if (previous != null) {
                System.setProperty("opennms.postgresql.maxVersion", previous);
            }
        }
    }

    @Test
    public void testCeilingParsedWhenPropertySet() {
        final String previous = System.getProperty("opennms.postgresql.maxVersion");
        System.setProperty("opennms.postgresql.maxVersion", "19.0");
        try {
            assertEquals(Float.valueOf(19.0f), Migrator.parseOptionalMaxVersion());
        } finally {
            if (previous != null) {
                System.setProperty("opennms.postgresql.maxVersion", previous);
            } else {
                System.clearProperty("opennms.postgresql.maxVersion");
            }
        }
    }

    // 3.4 Disabling validation skips the version checks entirely (no datasource touched).
    @Test
    public void testValidationDisabledSkipsVersionCheck() throws MigrationException {
        migrator.setValidateDatabaseVersion(false);
        migrator.validateDatabaseVersion();
    }

}
