# Provisiond Shared Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `NoOpEntityScopeProvider` and `ServiceDetectorRegistry` beans to `daemon-common` so all Spring Boot daemons have provisioning infrastructure available via component scan.

**Architecture:** New `DaemonProvisioningConfiguration` class in daemon-common provides `@ConditionalOnMissingBean` defaults for `EntityScopeProvider` (NoOp) and `ServiceDetectorRegistry` (empty SPI-based). Discovery's duplicated beans and classes are removed.

**Tech Stack:** Spring Boot 4.0.3, Spring Framework 7, Java 21

**Spec:** `docs/superpowers/specs/2026-03-16-provisiond-shared-infrastructure-design.md`

---

## Chunk 1: Shared Infrastructure in daemon-common

### Task 1: Add dependencies to daemon-common POM

`LocalServiceDetectorRegistry` imports interfaces from `opennms-detector-registry` and `opennms-provision-api`. These are `provided` scope in `daemon-loader-shared`, so they won't resolve transitively. We must add them explicitly with ServiceMix exclusions.

**Background:**
- `core/daemon-common/pom.xml:160-201` — existing `opennms-config` dependency shows the exclusion pattern for ServiceMix Spring 4.2.x bundles
- `opennms-detector-registry` depends on `core.soa` which pulls `spring-dependencies`
- `opennms-provision-api` depends on `mina-dependencies`, `netty-dependencies`, `opennms-model`
- `core/mate/api` depends on `opennms-dao-api` (already in daemon-common), `scv.api`, `guava`

**Files:**
- Modify: `core/daemon-common/pom.xml`

- [ ] **Step 1: Add four new dependencies to daemon-common POM**

Add these after the existing `<!-- Tracing API -->` block (around line 280), before `<!-- Test -->`:

```xml
        <!-- MATE API (EntityScopeProvider interface for NoOpEntityScopeProvider) -->
        <dependency>
            <groupId>org.opennms.core.mate</groupId>
            <artifactId>org.opennms.core.mate.api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Detector Registry (ServiceDetectorRegistry interface, LocalServiceDetectorRegistry) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon-loader-shared</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-detector-registry</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <!-- core.soa pulls spring-dependencies (ServiceMix Spring 4.2.x) -->
                <exclusion><groupId>org.opennms.core</groupId><artifactId>org.opennms.core.soa</artifactId></exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-provision-api</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.opennms.dependencies</groupId><artifactId>mina-dependencies</artifactId></exclusion>
                <exclusion><groupId>org.opennms.dependencies</groupId><artifactId>netty-dependencies</artifactId></exclusion>
                <!-- opennms-model already on classpath with proper exclusions -->
                <exclusion><groupId>org.opennms</groupId><artifactId>opennms-model</artifactId></exclusion>
            </exclusions>
        </dependency>
```

- [ ] **Step 2: Verify no Spring 4.2.x leaks**

Run: `./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-common '-Dincludes=org.apache.servicemix.bundles:*' 2>&1 | grep servicemix`
Expected: Only the `servicemix.bundles.kafka` line (already present from `ipc.rpc.kafka`), NO `spring-beans`, `spring-context`, `spring-core`, `spring-tx` from ServiceMix.

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-common/pom.xml
git commit -m "build: add mate-api, detector-registry, provision-api deps to daemon-common

Required for shared DaemonProvisioningConfiguration. Excludes core.soa
(spring-dependencies), mina/netty-dependencies, and duplicate opennms-model."
```

---

### Task 2: Create NoOpEntityScopeProvider in daemon-common

**Background:**
- `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProvider.java` — existing implementation to port
- `core/mate/api/src/main/java/org/opennms/core/mate/api/EntityScopeProvider.java` — interface with 6 methods
- Package convention: all daemon-common classes are in `org.opennms.core.daemon.common`

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/NoOpEntityScopeProvider.java`
- Create: `core/daemon-common/src/test/java/org/opennms/core/daemon/common/NoOpEntityScopeProviderTest.java`

- [ ] **Step 1: Create the shared NoOpEntityScopeProvider**

```java
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
package org.opennms.core.daemon.common;

import java.net.InetAddress;

import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.Scope;

/**
 * No-op {@link EntityScopeProvider} for Spring Boot daemon containers.
 *
 * <p>Returns empty scopes for all entity types. MATE variable interpolation
 * (e.g., {@code ${scv:credentials:apikey}}) is disabled.</p>
 *
 * <p>Provided as a {@code @ConditionalOnMissingBean} default by
 * {@link DaemonProvisioningConfiguration}. Daemons that need real MATE
 * support (Provisiond, Thresholding, Pollerd, etc.) can override by
 * defining their own {@code EntityScopeProvider} bean.</p>
 */
public class NoOpEntityScopeProvider implements EntityScopeProvider {

    private static final Scope EMPTY = EmptyScope.EMPTY;

    @Override public Scope getScopeForScv() { return EMPTY; }
    @Override public Scope getScopeForEnv() { return EMPTY; }
    @Override public Scope getScopeForNode(Integer nodeId) { return EMPTY; }
    @Override public Scope getScopeForInterface(Integer nodeId, String ipAddress) { return EMPTY; }
    @Override public Scope getScopeForInterfaceByIfIndex(Integer nodeId, int ifIndex) { return EMPTY; }
    @Override public Scope getScopeForService(Integer nodeId, InetAddress ipAddress, String serviceName) { return EMPTY; }
}
```

- [ ] **Step 2: Create the test**

```java
package org.opennms.core.daemon.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.opennms.core.mate.api.Scope;
import org.junit.jupiter.api.Test;

class NoOpEntityScopeProviderTest {

    private final NoOpEntityScopeProvider provider = new NoOpEntityScopeProvider();

    @Test
    void getScopeForNode_returnsEmptyScope() {
        Scope scope = provider.getScopeForNode(42);
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }

    @Test
    void getScopeForScv_returnsEmptyScope() {
        Scope scope = provider.getScopeForScv();
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }

    @Test
    void getScopeForEnv_returnsEmptyScope() {
        Scope scope = provider.getScopeForEnv();
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }

    @Test
    void getScopeForInterface_returnsEmptyScope() {
        Scope scope = provider.getScopeForInterface(1, "192.168.1.1");
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }

    @Test
    void getScopeForInterfaceByIfIndex_returnsEmptyScope() {
        Scope scope = provider.getScopeForInterfaceByIfIndex(1, 5);
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }

    @Test
    void getScopeForService_returnsEmptyScope() throws Exception {
        Scope scope = provider.getScopeForService(1, InetAddress.getByName("192.168.1.1"), "ICMP");
        assertThat(scope).isNotNull();
        assertThat(scope.keys()).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./compile.pl --projects :org.opennms.core.daemon-common -am verify 2>&1 | tail -10`
Expected: `BUILD SUCCESS` with test results showing 6 passed tests

- [ ] **Step 4: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/NoOpEntityScopeProvider.java \
        core/daemon-common/src/test/java/org/opennms/core/daemon/common/NoOpEntityScopeProviderTest.java
git commit -m "feat: add shared NoOpEntityScopeProvider to daemon-common

Default EntityScopeProvider for all Spring Boot daemons. Returns empty
scopes for all MATE methods. Daemons needing real MATE support override
via @ConditionalOnMissingBean."
```

---

### Task 3: Create DaemonProvisioningConfiguration

**Background:**
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaEventTransportConfiguration.java` — example of shared `@Configuration` in daemon-common
- `core/daemon-loader-shared/src/main/java/org/opennms/core/daemon/loader/LocalServiceDetectorRegistry.java` — `ServiceLoader`-based detector registry
- All 5 daemon applications have `scanBasePackages = { "org.opennms.core.daemon.common", ... }` — the new `@Configuration` will be auto-discovered

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java`

- [ ] **Step 1: Create the configuration class**

```java
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
package org.opennms.core.daemon.common;

import org.opennms.core.daemon.loader.LocalServiceDetectorRegistry;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.provision.detector.registry.api.ServiceDetectorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared provisioning infrastructure for all Spring Boot daemons.
 *
 * <p>Provides default beans that satisfy autowired dependencies in
 * provisioning-related classes (detector clients, import jobs, etc.).
 * Both beans use {@code @ConditionalOnMissingBean} so any daemon can
 * override with a real implementation.</p>
 *
 * <ul>
 *   <li>{@link NoOpEntityScopeProvider} — disables MATE variable interpolation.
 *       Daemons needing real MATE (Provisiond, Thresholding, Pollerd) override
 *       with a bean backed by database DAOs.</li>
 *   <li>{@link LocalServiceDetectorRegistry} — SPI-based detector discovery.
 *       Returns empty unless detector factory JARs are on the classpath.</li>
 * </ul>
 */
@Configuration
public class DaemonProvisioningConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityScopeProvider.class)
    public EntityScopeProvider entityScopeProvider() {
        return new NoOpEntityScopeProvider();
    }

    @Bean
    @ConditionalOnMissingBean(ServiceDetectorRegistry.class)
    public ServiceDetectorRegistry serviceDetectorRegistry() {
        return new LocalServiceDetectorRegistry();
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java
git commit -m "feat: add DaemonProvisioningConfiguration with shared provisioning beans

@ConditionalOnMissingBean defaults for EntityScopeProvider (NoOp) and
ServiceDetectorRegistry (SPI-based, empty unless detector JARs present).
Auto-discovered by all daemons via component scan."
```

---

## Chunk 2: Discovery Cleanup and Verification

### Task 4: Remove duplicated beans and classes from Discovery

**Background:**
- `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryBootConfiguration.java:92-100` — `entityScopeProvider()` and `serviceDetectorRegistry()` beans to remove
- `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProvider.java` — delete (replaced by daemon-common version)
- `core/daemon-boot-discovery/src/test/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProviderTest.java` — delete (moved to daemon-common)

**Files:**
- Delete: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProvider.java`
- Delete: `core/daemon-boot-discovery/src/test/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProviderTest.java`
- Modify: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryBootConfiguration.java`

- [ ] **Step 1: Delete NoOpEntityScopeProvider from Discovery**

```bash
rm core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProvider.java
rm core/daemon-boot-discovery/src/test/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProviderTest.java
```

- [ ] **Step 2: Remove duplicate beans from DiscoveryBootConfiguration**

Remove the `entityScopeProvider()` and `serviceDetectorRegistry()` bean methods (lines 92-100) and the unused imports for `NoOpEntityScopeProvider`, `LocalServiceDetectorRegistry`, `EntityScopeProvider`, and `ServiceDetectorRegistry`.

The `// -- Detector RPC --` section should go from:

```java
    // -- Detector RPC --

    @Bean
    public EntityScopeProvider entityScopeProvider() {
        return new NoOpEntityScopeProvider();
    }

    @Bean
    public ServiceDetectorRegistry serviceDetectorRegistry() {
        return new LocalServiceDetectorRegistry();
    }

    @Bean(name = "scanExecutor")
```

To:

```java
    // -- Detector RPC --

    @Bean(name = "scanExecutor")
```

Remove these imports (no longer needed directly):
- `org.opennms.core.daemon.loader.LocalServiceDetectorRegistry`
- `org.opennms.core.mate.api.EntityScopeProvider`
- `org.opennms.netmgt.provision.detector.registry.api.ServiceDetectorRegistry`

- [ ] **Step 3: Remove direct deps from Discovery POM if now transitive**

Check if `core/mate/api` and `core/daemon-loader-shared` in `core/daemon-boot-discovery/pom.xml` are now transitive via daemon-common. If so, remove the direct declarations to avoid duplication.

Run: `./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-boot-discovery '-Dincludes=org.opennms.core.mate:*,org.opennms.core:org.opennms.core.daemon-loader-shared' 2>&1 | grep -E "mate|daemon-loader-shared"`
Expected: Both show as transitive via daemon-common. If confirmed, remove the direct `<dependency>` entries from `core/daemon-boot-discovery/pom.xml`.

After removing, re-verify no ServiceMix Spring artifacts leaked back in:
Run: `./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-boot-discovery '-Dincludes=org.apache.servicemix.bundles:org.apache.servicemix.bundles.spring-*' 2>&1 | grep servicemix`
Expected: No `spring-beans`, `spring-context`, `spring-core`, or `spring-tx` from ServiceMix.

- [ ] **Step 4: Verify Discovery compiles and tests pass**

Run: `./compile.pl --projects :org.opennms.core.daemon-boot-discovery -am verify 2>&1 | tail -10`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add -A core/daemon-boot-discovery/
git commit -m "refactor: remove duplicated provisioning beans from Discovery

EntityScopeProvider and ServiceDetectorRegistry now provided by shared
DaemonProvisioningConfiguration in daemon-common. Deleted Discovery's
local NoOpEntityScopeProvider and its test."
```

---

### Task 5: Verify all 5 daemons compile and no bean conflicts

**Files:**
- No changes — verification only

- [ ] **Step 1: Compile all 5 daemons**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-trapd,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-discovery -am install 2>&1 | tail -15`
Expected: `BUILD SUCCESS` with all 5 daemon-boot modules listed as SUCCESS

- [ ] **Step 2: Verify no Spring 4.2.x artifacts leaked into any daemon**

Run for each daemon (example for Trapd):
```bash
./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-boot-trapd '-Dincludes=org.apache.servicemix.bundles:org.apache.servicemix.bundles.spring-*' 2>&1 | grep servicemix
```
Expected: No `spring-beans`, `spring-context`, `spring-core`, or `spring-tx` from ServiceMix. Only `servicemix.bundles.kafka` is acceptable.

Repeat for `:org.opennms.core.daemon-boot-alarmd` (most sensitive — JPA/Hibernate).

- [ ] **Step 3: Commit (tag verification)**

No code changes — verification only. Proceed to Task 6.

---

### Task 6: Create PR

- [ ] **Step 1: Create feature branch and push**

```bash
git checkout -b refactor/shared-provisioning-infrastructure
git push -u delta-v refactor/shared-provisioning-infrastructure
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --repo pbrane/delta-v --base develop \
  --title "refactor: shared provisioning infrastructure in daemon-common" \
  --body "$(cat <<'EOF'
## Summary

- **DaemonProvisioningConfiguration** — new shared `@Configuration` in `daemon-common` with `@ConditionalOnMissingBean` defaults:
  - `NoOpEntityScopeProvider` — disables MATE variable interpolation; overridable by daemons needing real MATE (Provisiond, Thresholding, Pollerd, Measurements, AssetTopo, ALEC)
  - `LocalServiceDetectorRegistry` — SPI-based detector discovery; empty unless detector factory JARs on classpath
- **Discovery cleanup** — removed duplicated `NoOpEntityScopeProvider` class and `entityScopeProvider()`/`serviceDetectorRegistry()` beans from `DiscoveryBootConfiguration`
- **Dependencies** — added `core.mate.api`, `daemon-loader-shared`, `opennms-detector-registry`, `opennms-provision-api` to daemon-common with ServiceMix exclusions

Prepares shared infrastructure for Provisiond Spring Boot 4 migration.

## Test plan

- [x] All 5 daemons compile
- [x] daemon-common unit tests pass (NoOpEntityScopeProviderTest)
- [x] No Spring 4.2.x ServiceMix artifacts on any daemon classpath
- [ ] Docker rebuild and verify all daemons start
- [ ] E2E: `test-minion-e2e.sh` passes (no regressions)
EOF
)"
```
