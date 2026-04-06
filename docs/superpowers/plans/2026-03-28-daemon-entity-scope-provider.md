# DaemonEntityScopeProvider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `NoOpEntityScopeProvider` with a real `DaemonEntityScopeProvider` that resolves MATE metadata expressions (`${node:label}`, `${scv:alias:password}`, etc.) in daemons with database access.

**Architecture:** Constructor-injected `DaemonEntityScopeProvider` in `daemon-common`, conditionally wired when `NodeDao` is present. Uses `core/mate/api` scope composition classes. JCEKS `SecureCredentialsVault` bean for credential resolution. Daemons without DAOs fall back to `NoOpEntityScopeProvider`.

**Tech Stack:** Java 17, Spring Boot 4, JPA, JCEKS keystore

**Spec:** `docs/superpowers/specs/2026-03-28-daemon-entity-scope-provider-design.md`

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `core/daemon-common/pom.xml` | Modify | Add SCV JCEKS and geohash dependencies |
| `core/daemon-common/.../DaemonEntityScopeProvider.java` | Create | Real EntityScopeProvider with constructor injection |
| `core/daemon-common/.../DaemonProvisioningConfiguration.java` | Modify | Conditional bean wiring, SCV bean |
| `core/daemon-common/.../DaemonEntityScopeProviderTest.java` | Create | Unit test with mocked DAOs |

---

### Task 1: Add Dependencies to daemon-common pom.xml

**Files:**
- Modify: `core/daemon-common/pom.xml`

- [ ] **Step 1: Add SCV API and JCEKS dependencies**

After the existing `org.opennms.core.mate.api` dependency block (around line 319), add:

```xml
        <!-- SCV API + JCEKS implementation (for DaemonEntityScopeProvider) -->
        <dependency>
            <groupId>org.opennms.features.scv</groupId>
            <artifactId>org.opennms.features.scv.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.scv</groupId>
            <artifactId>org.opennms.features.scv.jceks-impl</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Geohash (for DaemonEntityScopeProvider node geohash computation) -->
        <dependency>
            <groupId>ch.hsr</groupId>
            <artifactId>geohash</artifactId>
            <version>${geohashVersion}</version>
        </dependency>
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /Users/david/development/src/opennms/delta-v
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/pom.xml
git commit -m "$(cat <<'EOF'
chore: add SCV and geohash dependencies to daemon-common

Needed by DaemonEntityScopeProvider for Secure Credentials Vault
resolution and node geohash computation.
EOF
)"
```

---

### Task 2: Create DaemonEntityScopeProvider

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonEntityScopeProvider.java`

- [ ] **Step 1: Create the DaemonEntityScopeProvider class**

Create the file with the full implementation. This is a constructor-injected rewrite of the upstream `EntityScopeProviderImpl` (at `core/mate/model/src/main/java/org/opennms/core/mate/model/EntityScopeProviderImpl.java`).

Read the upstream `EntityScopeProviderImpl` file and replicate ALL scope mappings exactly (all node attributes, all 48+ asset fields, all interface attributes, all service attributes, the geohash computation, the metadata transform, etc.). The only differences from upstream are:

1. **Package:** `org.opennms.core.daemon.common` (not `org.opennms.core.mate.model`)
2. **Constructor injection:** All 6 dependencies via constructor (not `@Autowired` field injection)
3. **No setters:** Remove all setter methods (not needed with constructor injection)
4. **No `@Autowired` annotations:** Fields are `private final`

The constructor signature:

```java
public DaemonEntityScopeProvider(
        NodeDao nodeDao,
        IpInterfaceDao ipInterfaceDao,
        SnmpInterfaceDao snmpInterfaceDao,
        MonitoredServiceDao monitoredServiceDao,
        SessionUtils sessionUtils,
        SecureCredentialsVault scv) {
    this.nodeDao = Objects.requireNonNull(nodeDao);
    this.ipInterfaceDao = Objects.requireNonNull(ipInterfaceDao);
    this.snmpInterfaceDao = Objects.requireNonNull(snmpInterfaceDao);
    this.monitoredServiceDao = Objects.requireNonNull(monitoredServiceDao);
    this.sessionUtils = Objects.requireNonNull(sessionUtils);
    this.scv = Objects.requireNonNull(scv);
}
```

All method bodies (`getScopeForScv`, `getScopeForEnv`, `getScopeForNode`, `getScopeForInterface`, `getScopeForInterfaceByIfIndex`, `getScopeForService`, `getNodeCriteria`, `getNodeGeoHash`, `transform`, `mapIpInterfaceKeys`) must be copied exactly from the upstream implementation. Do not skip any scope mappings.

- [ ] **Step 2: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonEntityScopeProvider.java
git commit -m "$(cat <<'EOF'
feat: add DaemonEntityScopeProvider with constructor injection

Replaces NoOpEntityScopeProvider in daemons with database access.
Constructor-injected rewrite of the upstream EntityScopeProviderImpl
using the same scope composition from core/mate/api. Resolves MATE
expressions for node, interface, service, asset, SCV, and env scopes.
EOF
)"
```

---

### Task 3: Update DaemonProvisioningConfiguration Bean Wiring

**Files:**
- Modify: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java`

- [ ] **Step 1: Add imports**

Add these imports to the file:

```java
import org.opennms.features.scv.api.SecureCredentialsVault;
import org.opennms.features.scv.jceks.JCEKSSecureCredentialsVault;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.dao.api.SnmpInterfaceDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
```

- [ ] **Step 2: Replace the entityScopeProvider bean method**

Replace the existing `entityScopeProvider()` method with two methods — the real provider (conditional on NodeDao) and the no-op fallback:

```java
    /**
     * Real {@link EntityScopeProvider} backed by database DAOs.
     *
     * <p>Created only when {@link NodeDao} is present in the context,
     * which indicates the daemon has JPA database access. Resolves MATE
     * expressions like {@code ${node:label}}, {@code ${scv:alias:password}},
     * {@code ${asset:region}}, etc.</p>
     */
    @Bean
    @ConditionalOnBean(NodeDao.class)
    public EntityScopeProvider entityScopeProvider(
            NodeDao nodeDao,
            IpInterfaceDao ipInterfaceDao,
            SnmpInterfaceDao snmpInterfaceDao,
            MonitoredServiceDao monitoredServiceDao,
            SessionUtils sessionUtils,
            SecureCredentialsVault scv) {
        return new DaemonEntityScopeProvider(nodeDao, ipInterfaceDao,
                snmpInterfaceDao, monitoredServiceDao, sessionUtils, scv);
    }

    /**
     * No-op fallback for daemons without database access.
     */
    @Bean
    @ConditionalOnMissingBean(EntityScopeProvider.class)
    public EntityScopeProvider noOpEntityScopeProvider() {
        return new NoOpEntityScopeProvider();
    }
```

- [ ] **Step 3: Add SecureCredentialsVault bean**

Add this bean method to the class:

```java
    /**
     * JCEKS-based Secure Credentials Vault for MATE {@code ${scv:...}} expressions.
     *
     * <p>Reads credentials from {@code ${opennms.home}/etc/scv.jce}.
     * If the keystore does not exist, an empty vault is created on first access.</p>
     */
    @Bean
    @ConditionalOnMissingBean(SecureCredentialsVault.class)
    public SecureCredentialsVault secureCredentialsVault(
            @Value("${opennms.home:/opt/deltav}") String opennmsHome) {
        return new JCEKSSecureCredentialsVault(opennmsHome + "/etc/scv.jce", "notReallyASecret");
    }
```

- [ ] **Step 4: Update class-level Javadoc**

Update the Javadoc `<ul>` list to mention the new beans. Add entries for `DaemonEntityScopeProvider` and `SecureCredentialsVault`.

- [ ] **Step 5: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common install
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java
git commit -m "$(cat <<'EOF'
feat: wire DaemonEntityScopeProvider and JCEKS SCV in daemon-common

Conditional bean wiring: when NodeDao is present (Provisiond, Pollerd,
Collectd, Enlinkd), creates DaemonEntityScopeProvider. When absent,
falls back to NoOpEntityScopeProvider. Adds JCEKS SecureCredentialsVault
for ${scv:alias:password} resolution.
EOF
)"
```

---

### Task 4: Write Unit Tests

**Files:**
- Create: `core/daemon-common/src/test/java/org/opennms/core/daemon/common/DaemonEntityScopeProviderTest.java`

- [ ] **Step 1: Write the test class**

Create a JUnit 5 test class with mocked DAOs (Mockito) that verifies:

1. `getScopeForScv()` — mock SCV with an alias, verify `get(new ContextKey("scv", "myalias:password"))` returns the password
2. `getScopeForEnv()` — verify `get(new ContextKey("env", "PATH"))` returns a non-empty value
3. `getScopeForNode(nodeId)` — mock `nodeDao.get(1)` returning an OnmsNode with label "test-node", verify `get(new ContextKey("node", "label"))` returns "test-node"
4. `getScopeForNode(null)` — verify returns `EmptyScope.EMPTY`
5. `getScopeForNode(unknownId)` — mock `nodeDao.get(999)` returning null, verify returns `EmptyScope.EMPTY`
6. `getScopeForInterface(nodeId, ip)` — mock `ipInterfaceDao.findByNodeIdAndIpAddress()` returning an OnmsIpInterface, verify `get(new ContextKey("interface", "hostname"))` returns the hostname
7. `getScopeForService(nodeId, ip, svcName)` — mock `monitoredServiceDao.get()` returning an OnmsMonitoredService, verify `get(new ContextKey("service", "name"))` returns the service name

For all tests that use `sessionUtils`, mock it to directly execute the supplier:
```java
when(sessionUtils.withReadOnlyTransaction(any())).thenAnswer(inv -> {
    Supplier<?> supplier = inv.getArgument(0);
    return supplier.get();
});
```

- [ ] **Step 2: Verify tests pass**

Run:
```bash
./compile.pl --projects :org.opennms.core.daemon-common verify
```
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/src/test/java/org/opennms/core/daemon/common/DaemonEntityScopeProviderTest.java
git commit -m "$(cat <<'EOF'
test: add unit tests for DaemonEntityScopeProvider

Verifies scope resolution for SCV, env, node, interface, and service
scopes with mocked DAOs. Covers null/missing entity edge cases.
EOF
)"
```

---

### Task 5: Build and Verify

- [ ] **Step 1: Rebuild all 12 daemon-boot JARs**

```bash
cd /Users/david/development/src/opennms/delta-v
make build
```

If the UI module fails (known symlink issue), verify all 12 daemon-boot JARs were built:
```bash
ls -la core/daemon-boot-*/target/*-boot.jar 2>/dev/null | wc -l
```
Expected: 12 (or at least the count from prior builds — some daemons may not produce -boot.jar)

- [ ] **Step 2: Build Docker images**

```bash
cd opennms-container/delta-v
./build.sh deltav
```

- [ ] **Step 3: Deploy and verify DaemonEntityScopeProvider is loaded**

```bash
./deploy.sh down
docker ps --format '{{.Names}}' | grep delta-v | xargs -r docker rm -f
./deploy.sh up full
sleep 60
```

Check Provisiond logs for real provider (not no-op):
```bash
docker logs delta-v-provisiond 2>&1 | grep -i "entityScopeProvider\|DaemonEntityScopeProvider\|NoOpEntityScopeProvider" | head -5
```

- [ ] **Step 4: Run all 6 E2E test suites**

```bash
bash test-collectd-e2e.sh        # expect: 4/4
bash test-minion-e2e.sh          # expect: 13/13
bash test-syslog-e2e.sh          # expect: 15/15
bash test-passive-e2e.sh         # expect: 16/16
bash test-enlinkd-e2e.sh         # expect: 18/18
bash test-e2e.sh                 # expect: 12/12
```

- [ ] **Step 5: Commit any fixes discovered during verification**

If tests reveal issues, fix and commit before proceeding.
