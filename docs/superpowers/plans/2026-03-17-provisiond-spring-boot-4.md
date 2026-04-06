# Provisiond Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Provisiond to a Spring Boot 4.0.3 microservice with constructor injection, JPA DAOs, 3× Kafka RPC clients, Quartz scheduling, and 3 SNMP provisioning adapters.

**Architecture:** New `core/daemon-boot-provisiond/` Spring Boot module replaces `core/daemon-loader-provisiond/`. Shared JPA DAOs in `core/opennms-model-jakarta/` extend the Alarmd-established pattern. Core Provisiond classes refactored to constructor injection. Single `ProvisiondBootConfiguration` wires all beans.

**Tech Stack:** Spring Boot 4.0.3, Spring Framework 7, Hibernate 7, Jakarta Persistence, Java 21, Quartz, Kafka RPC, PostgreSQL

**Spec:** `docs/superpowers/specs/2026-03-17-provisiond-spring-boot-4-design.md`

---

## Chunk 1: JPA DAO Implementations

Extend the shared `jakarta.dao` package in `core/opennms-model-jakarta/` with DAOs needed by Provisiond. All DAOs extend `AbstractDaoJpa` (in `core/daemon-common/`), which provides standard CRUD methods (`get()`, `save()`, `update()`, `delete()`, `saveOrUpdate()`, `flush()`, `clear()`, `findAll()`, `load()`, `initialize()`). Only custom query methods need implementation.

**Background:**
- `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/` — existing DAO package (AlarmDaoJpa, NodeDaoJpa, ServiceTypeDaoJpa, DistPollerDaoJpa)
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/AbstractDaoJpa.java` — base class providing CRUD
- `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/` — DAO interfaces
- Pattern: `@Repository @Transactional`, extend `AbstractDaoJpa<Entity, KeyType>`, implement DAO interface, use `findUnique()` / `find()` helpers for HQL queries

### Task 1: Expand NodeDaoJpa with Provisiond-required methods

NodeDaoJpa currently only implements `get()` for Alarmd. Provisiond calls 9 additional methods beyond what `AbstractDaoJpa` provides.

**Files:**
- Modify: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/NodeDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/dao/NodeDaoJpaTest.java`

- [ ] **Step 1: Add `getHierarchy(Integer id)` method**

Returns node with initialized collections (ipInterfaces, snmpInterfaces, categories). Used by `DefaultProvisionService.getRequisitionedNode()`.

```java
@Override
public OnmsNode getHierarchy(Integer id) {
    OnmsNode node = get(id);
    if (node != null) {
        initialize(node.getIpInterfaces());
        initialize(node.getSnmpInterfaces());
        initialize(node.getCategories());
    }
    return node;
}
```

- [ ] **Step 2: Add `findByForeignId(String foreignSource, String foreignId)` method**

```java
@Override
public OnmsNode findByForeignId(String foreignSource, String foreignId) {
    return findUnique("from OnmsNode n where n.foreignSource = ?1 and n.foreignId = ?2",
        foreignSource, foreignId);
}
```

- [ ] **Step 3: Add `findByLabel(String label)` method**

```java
@Override
public List<OnmsNode> findByLabel(String label) {
    return find("from OnmsNode n where n.label = ?1", label);
}
```

- [ ] **Step 4: Add `findAllProvisionedNodes()` method**

```java
@Override
public List<OnmsNode> findAllProvisionedNodes() {
    return find("from OnmsNode n where n.foreignSource is not null");
}
```

- [ ] **Step 5: Add `getForeignIdToNodeIdMap(String foreignSource)` method**

```java
@Override
public Map<String, Integer> getForeignIdToNodeIdMap(String foreignSource) {
    List<Object[]> rows = findObjects(Object[].class,
        "select n.foreignId, n.id from OnmsNode n where n.foreignSource = ?1", foreignSource);
    Map<String, Integer> map = new HashMap<>();
    for (Object[] row : rows) {
        map.put((String) row[0], (Integer) row[1]);
    }
    return map;
}
```

- [ ] **Step 6: Add `findByForeignSourceAndIpAddress(String foreignSource, String ipAddress)` method**

```java
@Override
public List<OnmsNode> findByForeignSourceAndIpAddress(String foreignSource, String ipAddress) {
    return find("select distinct n from OnmsNode n join n.ipInterfaces iface " +
        "where n.foreignSource = ?1 and iface.ipAddress = ?2",
        foreignSource, InetAddressUtils.addr(ipAddress));
}
```

- [ ] **Step 7: Add `findObsoleteIpInterfaces(Integer nodeId, Date scanStamp)` method**

```java
@Override
public List<OnmsIpInterface> findObsoleteIpInterfaces(Integer nodeId, Date scanStamp) {
    return findObjects(OnmsIpInterface.class,
        "from OnmsIpInterface iface where iface.node.id = ?1 " +
        "and (iface.ipLastCapsdPoll is null or iface.ipLastCapsdPoll < ?2)",
        nodeId, scanStamp);
}
```

- [ ] **Step 8: Add `deleteObsoleteInterfaces(Integer nodeId, Date scanStamp)` method**

```java
@Override
public void deleteObsoleteInterfaces(Integer nodeId, Date scanStamp) {
    entityManager().createQuery(
        "delete from OnmsIpInterface iface where iface.node.id = ?1 " +
        "and (iface.ipLastCapsdPoll is null or iface.ipLastCapsdPoll < ?2)")
        .setParameter(1, nodeId)
        .setParameter(2, scanStamp)
        .executeUpdate();
}
```

- [ ] **Step 9: Add `updateNodeScanStamp(Integer nodeId, Date scanStamp)` method**

```java
@Override
public void updateNodeScanStamp(Integer nodeId, Date scanStamp) {
    entityManager().createQuery(
        "update OnmsNode set lastCapsdPoll = ?1 where id = ?2")
        .setParameter(1, scanStamp)
        .setParameter(2, nodeId)
        .executeUpdate();
}
```

- [ ] **Step 10: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 11: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/NodeDaoJpa.java
git commit -m "$(cat <<'EOF'
feat: expand NodeDaoJpa with Provisiond-required methods

Add 9 query methods: getHierarchy, findByForeignId, findByLabel,
findAllProvisionedNodes, getForeignIdToNodeIdMap,
findByForeignSourceAndIpAddress, findObsoleteIpInterfaces,
deleteObsoleteInterfaces, updateNodeScanStamp.
EOF
)"
```

---

### Task 2: Create IpInterfaceDaoJpa

Only 1 custom method needed — `findByNodeIdAndIpAddress()`. All other methods (update, flush, saveOrUpdate, initialize) come from `AbstractDaoJpa`.

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/IpInterfaceDaoJpa.java`

- [ ] **Step 1: Create IpInterfaceDaoJpa**

```java
package org.opennms.netmgt.model.jakarta.dao;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

@Repository
@Transactional
public class IpInterfaceDaoJpa extends AbstractDaoJpa<OnmsIpInterface, Integer> implements IpInterfaceDao {

    public IpInterfaceDaoJpa() {
        super(OnmsIpInterface.class);
    }

    @Override
    public OnmsIpInterface findByNodeIdAndIpAddress(Integer nodeId, String ipAddress) {
        return findUnique(
            "from OnmsIpInterface iface where iface.node.id = ?1 and iface.ipAddress = ?2",
            nodeId, InetAddressUtils.addr(ipAddress));
    }

    // --- Methods not used by Provisiond — throw UnsupportedOperationException ---

    @Override
    public OnmsIpInterface get(OnmsIpInterface.PrimaryKey key) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.get(PrimaryKey) not implemented");
    }

    @Override
    public List<OnmsIpInterface> findMatching(org.opennms.core.criteria.Criteria criteria) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findMatching(Criteria) not implemented");
    }

    @Override
    public int countMatching(org.opennms.core.criteria.Criteria criteria) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.countMatching(Criteria) not implemented");
    }

    @Override
    public OnmsIpInterface findByNodeIdAndIpAddress(Integer nodeId, InetAddress ipAddress) {
        return findUnique(
            "from OnmsIpInterface iface where iface.node.id = ?1 and iface.ipAddress = ?2",
            nodeId, ipAddress);
    }

    @Override
    public OnmsIpInterface findByForeignKeyAndIpAddress(String foreignSource, String foreignId, String ipAddress) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findByForeignKeyAndIpAddress not implemented");
    }

    @Override
    public List<OnmsIpInterface> findByIpAddress(String ipAddress) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findByIpAddress not implemented");
    }

    @Override
    public List<OnmsIpInterface> findByNodeId(Integer nodeId) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findByNodeId not implemented");
    }

    @Override
    public List<OnmsIpInterface> findByServiceType(String svcName) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findByServiceType not implemented");
    }

    @Override
    public List<OnmsIpInterface> findHierarchyByServiceType(String svcName) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findHierarchyByServiceType not implemented");
    }

    @Override
    public Map<InetAddress, Integer> getInterfaceAddressMap() {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.getInterfaceAddressMap not implemented");
    }

    @Override
    public OnmsIpInterface findPrimaryInterfaceByNodeId(Integer nodeId) {
        throw new UnsupportedOperationException("IpInterfaceDaoJpa.findPrimaryInterfaceByNodeId not implemented");
    }
}
```

Note: The implementer should check the `IpInterfaceDao` interface for the full method list. The above covers the known interface methods; any additional methods should also throw `UnsupportedOperationException`. Use the `AbstractDaoJpa` pattern for consistency.

- [ ] **Step 2: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/IpInterfaceDaoJpa.java
git commit -m "feat: add IpInterfaceDaoJpa for Provisiond

findByNodeIdAndIpAddress() for interface lookups during provisioning.
Other IpInterfaceDao methods throw UnsupportedOperationException."
```

---

### Task 3: Create remaining DAOs (batch)

Create 7 more DAOs following the same pattern. Each has 0-1 custom query methods; the rest come from `AbstractDaoJpa`.

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/SnmpInterfaceDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/MonitoredServiceDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/CategoryDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/RequisitionedCategoryAssociationDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/MonitoringLocationDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/MonitoringSystemDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/HwEntityDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/HwEntityAttributeTypeDaoJpa.java`

- [ ] **Step 1: Create SnmpInterfaceDaoJpa**

Custom method: `findByNodeIdAndIfIndex(Integer nodeId, Integer ifIndex)`

```java
@Repository
@Transactional
public class SnmpInterfaceDaoJpa extends AbstractDaoJpa<OnmsSnmpInterface, Integer> implements SnmpInterfaceDao {
    public SnmpInterfaceDaoJpa() { super(OnmsSnmpInterface.class); }

    @Override
    public OnmsSnmpInterface findByNodeIdAndIfIndex(Integer nodeId, Integer ifIndex) {
        return findUnique(
            "from OnmsSnmpInterface si where si.node.id = ?1 and si.ifIndex = ?2",
            nodeId, ifIndex);
    }

    // All other SnmpInterfaceDao methods: throw UnsupportedOperationException
    // Check SnmpInterfaceDao interface for full list
}
```

- [ ] **Step 2: Create MonitoredServiceDaoJpa**

Custom method: `get(Integer nodeId, InetAddress ipAddress, String svcName)` — 3-param lookup.

```java
@Repository
@Transactional
public class MonitoredServiceDaoJpa extends AbstractDaoJpa<OnmsMonitoredService, Integer> implements MonitoredServiceDao {
    public MonitoredServiceDaoJpa() { super(OnmsMonitoredService.class); }

    @Override
    public OnmsMonitoredService get(Integer nodeId, InetAddress ipAddress, String svcName) {
        return findUnique(
            "from OnmsMonitoredService ms where ms.ipInterface.node.id = ?1 " +
            "and ms.ipInterface.ipAddress = ?2 and ms.serviceType.name = ?3",
            nodeId, ipAddress, svcName);
    }

    // All other MonitoredServiceDao methods: throw UnsupportedOperationException
}
```

- [ ] **Step 3: Create CategoryDaoJpa**

Custom method: `findByName(String name)`

```java
@Repository
@Transactional
public class CategoryDaoJpa extends AbstractDaoJpa<OnmsCategory, Integer> implements CategoryDao {
    public CategoryDaoJpa() { super(OnmsCategory.class); }

    @Override
    public OnmsCategory findByName(String name) {
        return findUnique("from OnmsCategory c where c.name = ?1", name);
    }

    @Override
    public OnmsCategory findByName(String name, boolean useCaching) {
        return findByName(name); // no caching in JPA impl
    }

    // All other CategoryDao methods: throw UnsupportedOperationException
}
```

- [ ] **Step 4: Create RequisitionedCategoryAssociationDaoJpa**

Custom method: `findByNodeId(Integer nodeId)`. Note: check the `RequisitionedCategoryAssociationDao` interface for the exact method signature — it may be a custom extension of `OnmsDao`.

```java
@Repository
@Transactional
public class RequisitionedCategoryAssociationDaoJpa
        extends AbstractDaoJpa<RequisitionedCategoryAssociation, Integer>
        implements RequisitionedCategoryAssociationDao {

    public RequisitionedCategoryAssociationDaoJpa() {
        super(RequisitionedCategoryAssociation.class);
    }

    @Override
    public List<RequisitionedCategoryAssociation> findByNodeId(Integer nodeId) {
        return find("from RequisitionedCategoryAssociation rca where rca.node.id = ?1", nodeId);
    }
}
```

- [ ] **Step 5: Create MonitoringLocationDaoJpa**

Custom method: `getDefaultLocation()`. Note: `MonitoringLocationDao` may extend a different base interface than `OnmsDao` — check `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/MonitoringLocationDao.java` for the exact interface hierarchy. The implementer MUST verify the interface and implement all abstract methods.

```java
@Repository
@Transactional
public class MonitoringLocationDaoJpa extends AbstractDaoJpa<OnmsMonitoringLocation, String>
        implements MonitoringLocationDao {

    public MonitoringLocationDaoJpa() { super(OnmsMonitoringLocation.class); }

    @Override
    public OnmsMonitoringLocation getDefaultLocation() {
        return get("Default");
    }

    // Verify full MonitoringLocationDao interface and implement/stub remaining methods
}
```

- [ ] **Step 6: Create MonitoringSystemDaoJpa**

No custom methods — `AbstractDaoJpa.get()` is sufficient. Provisioner only calls `monitoringSystemDao.get(systemId)`.

```java
@Repository
@Transactional
public class MonitoringSystemDaoJpa extends AbstractDaoJpa<OnmsMonitoringSystem, String>
        implements MonitoringSystemDao {
    public MonitoringSystemDaoJpa() { super(OnmsMonitoringSystem.class); }

    // All other MonitoringSystemDao methods: throw UnsupportedOperationException
}
```

- [ ] **Step 7: Create HwEntityDaoJpa**

Custom method: `findRootByNodeId(Integer nodeId)`.

```java
@Repository
@Transactional
public class HwEntityDaoJpa extends AbstractDaoJpa<OnmsHwEntity, Integer> implements HwEntityDao {
    public HwEntityDaoJpa() { super(OnmsHwEntity.class); }

    @Override
    public OnmsHwEntity findRootByNodeId(Integer nodeId) {
        return findUnique(
            "from OnmsHwEntity e where e.node.id = ?1 and e.parent is null", nodeId);
    }

    // Other HwEntityDao methods: throw UnsupportedOperationException
}
```

- [ ] **Step 8: Create HwEntityAttributeTypeDaoJpa**

`AbstractDaoJpa` provides `findAll()` and `saveOrUpdate()`. The `HwEntityAttributeTypeDao` interface also defines `findTypeByName(String)` and `findTypeByOid(String)` — check if the SNMP HW inventory adapter calls these. If not, stub them with `UnsupportedOperationException`.

```java
@Repository
@Transactional
public class HwEntityAttributeTypeDaoJpa extends AbstractDaoJpa<HwEntityAttributeType, Integer>
        implements HwEntityAttributeTypeDao {
    public HwEntityAttributeTypeDaoJpa() { super(HwEntityAttributeType.class); }

    @Override
    public HwEntityAttributeType findTypeByName(String name) {
        return findUnique("from HwEntityAttributeType t where t.name = ?1", name);
    }

    @Override
    public HwEntityAttributeType findTypeByOid(String oid) {
        return findUnique("from HwEntityAttributeType t where t.oid = ?1", oid);
    }
}
```

- [ ] **Step 9: Add any missing POM dependencies to opennms-model-jakarta**

Check if `opennms-model-jakarta/pom.xml` needs dependencies for `HwEntityDao`, `HwEntityAttributeTypeDao`, `RequisitionedCategoryAssociationDao`, `MonitoringSystemDao`, `MonitoringLocationDao` interfaces. These live in `opennms-dao-api` which should already be a dependency. Verify.

Run: `./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/
git commit -m "$(cat <<'EOF'
feat: add JPA DAOs for Provisiond migration

New DAOs: IpInterfaceDaoJpa, SnmpInterfaceDaoJpa, MonitoredServiceDaoJpa,
CategoryDaoJpa, RequisitionedCategoryAssociationDaoJpa,
MonitoringLocationDaoJpa, MonitoringSystemDaoJpa, HwEntityDaoJpa,
HwEntityAttributeTypeDaoJpa. Each implements only the methods
Provisiond actually calls; others throw UnsupportedOperationException.
EOF
)"
```

---

## Chunk 2: Constructor Injection Refactoring

Refactor core Provisiond classes from `@Autowired` field/setter injection to constructor injection. These changes are in the `opennms-provisiond` module which will ONLY be consumed by the Spring Boot container going forward (Karaf path retired).

**Background:**
- `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/` — source directory
- Each class currently uses a mix of setter injection and `@Autowired` field injection
- After refactoring: single constructor, no `@Autowired` annotations, no setter injection for dependencies
- Remove `BeanUtils.assertAutowiring()` — constructor injection makes it redundant

### Task 4: Refactor DefaultProvisionService to constructor injection

The heaviest refactoring — 17 `@Autowired` fields become constructor parameters.

**Files:**
- Modify: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/DefaultProvisionService.java`

- [ ] **Step 1: Read the full DefaultProvisionService class**

Read: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/DefaultProvisionService.java`

Identify all `@Autowired` fields (lines 144-197) and the `afterPropertiesSet()` method.

- [ ] **Step 2: Replace all @Autowired fields with final fields + constructor**

Replace the 17 `@Autowired` field declarations (lines 144-200) with `private final` fields and a single constructor. Remove `@Qualifier` annotations from fields — qualifiers move to the `@Bean` method in the configuration class.

The constructor should accept all dependencies:
```java
public DefaultProvisionService(
        MonitoringLocationDao monitoringLocationDao,
        NodeDao nodeDao,
        IpInterfaceDao ipInterfaceDao,
        SnmpInterfaceDao snmpInterfaceDao,
        MonitoredServiceDao monitoredServiceDao,
        ServiceTypeDao serviceTypeDao,
        CategoryDao categoryDao,
        RequisitionedCategoryAssociationDao categoryAssociationDao,
        EventForwarder eventForwarder,
        ForeignSourceRepository foreignSourceRepository,
        ForeignSourceRepository pendingForeignSourceRepository,
        PluginRegistry pluginRegistry,
        PlatformTransactionManager transactionManager,
        LocationAwareDetectorClient locationAwareDetectorClient,
        LocationAwareDnsLookupClient locationAwareDnsLookupClient,
        LocationAwareSnmpClient locationAwareSnmpClient,
        SnmpProfileMapper snmpProfileMapper) {
    // assign all fields
}
```

Remove `@Qualifier("transactionAware")`, `@Qualifier("fastFused")`, `@Qualifier("fastFilePending")` from fields — these will be handled by the `@Bean` method in `ProvisiondBootConfiguration` which passes the correct qualified instances.

- [ ] **Step 3: Simplify afterPropertiesSet()**

Remove `BeanUtils.assertAutowiring(this)`. Keep only:
```java
@Override
public void afterPropertiesSet() throws Exception {
    RequisitionFileUtils.deleteAllSnapshots(m_pendingForeignSourceRepository);
    m_hostnameResolver = new DefaultHostnameResolver(m_locationAwareDnsLookuClient);
}
```

- [ ] **Step 4: Remove @Service annotation**

`DefaultProvisionService` is annotated `@Service` — remove it. The bean will be created explicitly via `@Bean` in `ProvisiondBootConfiguration`.

- [ ] **Step 5: Verify compilation**

Run: `./compile.pl -DskipTests --projects :opennms-provisiond -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add opennms-provision/opennms-provisiond/
git commit -m "refactor: convert DefaultProvisionService to constructor injection

Replace 17 @Autowired fields with constructor parameters. Remove
@Service annotation (bean created via @Bean). Remove
BeanUtils.assertAutowiring() — constructor injection makes it
redundant."
```

---

### Task 5: Refactor Provisioner to constructor injection

**Files:**
- Modify: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/Provisioner.java`

- [ ] **Step 1: Read the full Provisioner class**

Read: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/Provisioner.java`

Identify all setter methods (setProvisionService, setScheduledExecutor, setLifeCycleRepository, setImportSchedule, setImportActivities, setTaskCoordinator, setAgentConfigFactory, setEventForwarder) and `@Autowired` fields (manager, monitoringSystemDao, tracerRegistry, monitorHolder).

- [ ] **Step 2: Replace setters and @Autowired fields with constructor**

```java
public Provisioner(
        ProvisionService provisionService,
        EventForwarder eventForwarder,
        LifeCycleRepository lifeCycleRepository,
        ScheduledExecutorService scheduledExecutor,
        ImportScheduler importSchedule,
        CoreImportActivities importActivities,
        TaskCoordinator taskCoordinator,
        SnmpAgentConfigFactory agentConfigFactory,
        ProvisioningAdapterManager manager,
        MonitoringSystemDao monitoringSystemDao,
        TracerRegistry tracerRegistry,
        MonitorHolder monitorHolder) {
    // assign all final fields
}
```

Remove all setter methods. Remove `@Autowired` annotations. Make fields `private final`.

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :opennms-provisiond -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add opennms-provision/opennms-provisiond/
git commit -m "refactor: convert Provisioner to constructor injection

Replace 7 setter methods + 4 @Autowired fields with single
constructor. All fields now final."
```

---

### Task 6: Refactor DefaultPluginRegistry, ImportScheduler, ImportJobFactory

**Files:**
- Modify: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/DefaultPluginRegistry.java`
- Modify: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/ImportScheduler.java`
- Modify: `opennms-provision/opennms-provisiond/src/main/java/org/opennms/netmgt/provision/service/ImportJobFactory.java`

- [ ] **Step 1: Read all three classes**

Read each class to understand the full set of `@Autowired` fields and setter methods.

- [ ] **Step 2: Refactor DefaultPluginRegistry**

Fields: `Set<NodePolicy>` (optional), `Set<IpInterfacePolicy>` (optional), `Set<SnmpInterfacePolicy>` (optional), `ServiceRegistry`, `ApplicationContext`.

Constructor should use `@Nullable` or default-to-empty for the optional policy sets:

```java
public DefaultPluginRegistry(
        ServiceRegistry serviceRegistry,
        ApplicationContext applicationContext,
        @Nullable Set<NodePolicy> nodePolicies,
        @Nullable Set<IpInterfacePolicy> ipInterfacePolicies,
        @Nullable Set<SnmpInterfacePolicy> snmpInterfacePolicies) {
    this.m_serviceRegistry = serviceRegistry;
    this.m_appContext = applicationContext;
    this.m_nodePolicies = nodePolicies != null ? nodePolicies : Collections.emptySet();
    this.m_ipInterfacePolicies = ipInterfacePolicies != null ? ipInterfacePolicies : Collections.emptySet();
    this.m_snmpInterfacePolicies = snmpInterfacePolicies != null ? snmpInterfacePolicies : Collections.emptySet();
}
```

- [ ] **Step 3: Refactor ImportScheduler**

Read `ImportScheduler.java` to find all dependencies. Constructor should take `Scheduler` (Quartz), `ProvisiondConfigurationDao`, and `ImportJobFactory`.

**CRITICAL: Circular dependency.** `ImportScheduler` references `Provisioner`, and `Provisioner` references `ImportScheduler`. With full constructor injection, Spring will throw `BeanCurrentlyInCreationException`. **Keep `setProvisioner()` as a setter** — do NOT move it to the constructor. The `@Bean` method in `ProvisiondBootConfiguration` will call the setter after construction:

```java
@Bean
public ImportScheduler importScheduler(Scheduler scheduler, ProvisiondConfigurationDao configDao, ImportJobFactory jobFactory) {
    return new ImportScheduler(scheduler, configDao, jobFactory);
    // provisioner set later via setter in the provisioner @Bean method
}
```

Preserve `GenericURLFactory.initialize()` call in `afterPropertiesSet()`.

- [ ] **Step 4: Refactor ImportJobFactory**

Read `ImportJobFactory.java` to find all dependencies. Constructor should take `EntityScopeProvider`.

**Same circular dependency:** `ImportJobFactory` references `Provisioner`. **Keep `setProvisioner()` as a setter.** The `@Bean` method sets it after construction.

- [ ] **Step 5: Verify compilation**

Run: `./compile.pl -DskipTests --projects :opennms-provisiond -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Run existing unit tests**

Run: `./compile.pl --projects :opennms-provisiond -am verify 2>&1 | tail -20`
Expected: Tests pass. If tests fail due to constructor changes (test fixtures creating these classes), update the test fixtures too.

- [ ] **Step 7: Commit**

```bash
git add opennms-provision/opennms-provisiond/
git commit -m "refactor: convert DefaultPluginRegistry, ImportScheduler, ImportJobFactory to constructor injection"
```

---

## Chunk 3: Spring Boot Module Creation

Create the `core/daemon-boot-provisiond/` module with Application class, Configuration class, POM, application.yml, and relocated classes from daemon-loader-provisiond.

### Task 7: Relocate classes from daemon-loader-provisiond

Move the 4 classes that are needed after `daemon-loader-provisiond` is deleted.

**Files:**
- Read: `core/daemon-loader-provisiond/src/main/java/org/opennms/core/daemon/loader/InlineProvisiondConfigDao.java`
- Read: `core/daemon-loader-provisiond/src/main/java/org/opennms/core/daemon/loader/NoOpSnmpProfileMapper.java`
- Read: `core/daemon-loader-provisiond/src/main/java/org/opennms/core/daemon/loader/SnmpPeerFactoryInitializer.java`
- Read: `core/daemon-loader-provisiond/src/main/java/org/opennms/core/daemon/loader/QualifiedEventForwarder.java`
- Create: 4 new files in `core/daemon-boot-provisiond/` (see below)

- [ ] **Step 1: Read all 4 source classes**

Read each class. Note:
- `InlineProvisiondConfigDao` — has bug where getter methods return hardcoded defaults instead of config values. Fix during relocation.
- `QualifiedEventForwarder` — simple delegating wrapper
- `NoOpSnmpProfileMapper` — returns null for all methods
- `SnmpPeerFactoryInitializer` — calls `SnmpPeerFactory.init()`

- [ ] **Step 2: Create directory structure**

```bash
ls core/ | grep daemon-boot
```
Verify `core/daemon-boot-provisiond/` does not exist yet.

```bash
mkdir -p core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot
mkdir -p core/daemon-boot-provisiond/src/main/resources
mkdir -p core/daemon-boot-provisiond/src/test/java/org/opennms/netmgt/provision/boot
```

- [ ] **Step 3: Relocate InlineProvisiondConfigDao with bug fixes**

Copy to `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/InlineProvisiondConfigDao.java`. Change package to `org.opennms.netmgt.provision.boot`.

**Fix the getter bug:** Change hardcoded defaults to delegate to the loaded config object:
```java
// BEFORE (buggy):
public int getImportThreads() { return DEFAULT_IMPORT_THREADS; }
// AFTER (fixed):
public int getImportThreads() { return config.getImportThreads(); }
```

Fix ALL getter methods similarly. Update default paths from `/opt/sentinel` to use `System.getProperty("opennms.home", "/opt/deltav")`.

- [ ] **Step 4: Relocate QualifiedEventForwarder**

Copy to `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/QualifiedEventForwarder.java`. Change package.

- [ ] **Step 5: Relocate NoOpSnmpProfileMapper**

Copy to `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/NoOpSnmpProfileMapper.java`. Change package.

- [ ] **Step 6: Relocate SnmpPeerFactoryInitializer**

Copy to `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/SnmpPeerFactoryInitializer.java`. Change package.

- [ ] **Step 7: Commit relocated classes**

```bash
git add core/daemon-boot-provisiond/src/main/java/
git commit -m "refactor: relocate daemon-loader-provisiond classes to daemon-boot-provisiond

Move InlineProvisiondConfigDao (with getter bug fixes),
QualifiedEventForwarder, NoOpSnmpProfileMapper,
SnmpPeerFactoryInitializer. Paths updated from /opt/sentinel
to opennms.home system property."
```

---

### Task 8: Create POM

**Files:**
- Create: `core/daemon-boot-provisiond/pom.xml`
- Modify: `core/pom.xml` (add module)

- [ ] **Step 1: Read reference POMs**

Read: `core/daemon-boot-discovery/pom.xml` (for RPC daemon pattern)
Read: `core/daemon-boot-alarmd/pom.xml` (for JPA daemon pattern)

- [ ] **Step 2: Create pom.xml**

Follow the established pattern. Key differences from Discovery:
- Include JPA dependencies (do NOT exclude `HibernateJpaAutoConfiguration`)
- Add `spring-boot-starter-quartz`
- Add SNMP adapter dependencies (3 adapter modules from `integrations/`)
- Add `core/soa` for `ServiceRegistry` (with ServiceMix exclusions)
- Add `opennms-model-jakarta` for JPA DAOs
- TSID node-id default: 25

The POM must include:
1. Spring Boot BOM first in dependencyManagement
2. Pin Jackson 2.19.4, logback 1.5.32, SLF4J 2.0.17, JUnit 5.12.2
3. Skip enforcer banned-dependencies
4. Exclude ALL ServiceMix Spring bundles
5. Exclude old Hibernate 3.x
6. `spring-boot-maven-plugin` with repackage goal

Dependencies (in addition to Discovery's deps):
```xml
<!-- JPA / Hibernate (Alarmd pattern) -->
<dependency>
    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.model-jakarta</artifactId>
</dependency>

<!-- Quartz -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>

<!-- Provisiond core -->
<dependency>
    <groupId>org.opennms</groupId>
    <artifactId>opennms-provisiond</artifactId>
    <!-- heavy exclusions needed -->
</dependency>

<!-- ServiceRegistry -->
<dependency>
    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.soa</artifactId>
    <!-- exclude spring-dependencies -->
</dependency>

<!-- SNMP Provisioning Adapters -->
<dependency>
    <groupId>org.opennms.integrations</groupId>
    <artifactId>opennms-snmp-hardware-inventory-provisioning-adapter</artifactId>
</dependency>
<dependency>
    <groupId>org.opennms.integrations</groupId>
    <artifactId>opennms-snmp-asset-provisioning-adapter</artifactId>
</dependency>
<dependency>
    <groupId>org.opennms.integrations</groupId>
    <artifactId>opennms-snmp-metadata-provisioning-adapter</artifactId>
</dependency>

<!-- DNS Lookup RPC -->
<dependency>
    <groupId>org.opennms</groupId>
    <artifactId>opennms-detectorclient-rpc</artifactId>
</dependency>

<!-- SNMP RPC -->
<dependency>
    <groupId>org.opennms.core.snmp</groupId>
    <artifactId>org.opennms.core.snmp.proxy-rpc-impl</artifactId>
</dependency>

<!-- Provision persistence (ForeignSourceRepository) -->
<dependency>
    <groupId>org.opennms</groupId>
    <artifactId>opennms-provision-persistence</artifactId>
</dependency>
```

**CRITICAL:** Include `javax.persistence:javax.persistence-api:2.2` — the entity classes use `javax.persistence` annotations that Hibernate 7 needs on classpath. Alarmd POM has this (check `core/daemon-boot-alarmd/pom.xml` for exact coordinates and version).

**CRITICAL:** Each of these dependencies will bring in transitive Spring 4.2.x ServiceMix bundles. The implementer MUST run `mvn dependency:tree -Dincludes=org.apache.servicemix.bundles:*` after adding each dependency and add exclusions as needed. Follow the exclusion patterns in `core/daemon-boot-discovery/pom.xml` and `core/daemon-boot-alarmd/pom.xml`.

- [ ] **Step 3: Add module to core/pom.xml reactor**

Add `<module>daemon-boot-provisiond</module>` to `core/pom.xml` in the modules section, after `daemon-boot-discovery`.

- [ ] **Step 4: Verify POM resolves**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-provisiond -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS` (compilation may fail since Application/Configuration classes don't exist yet — that's OK if dependency resolution succeeds)

- [ ] **Step 5: Verify no Spring 4.2.x leaks**

Run: `./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-boot-provisiond '-Dincludes=org.apache.servicemix.bundles:org.apache.servicemix.bundles.spring-*' 2>&1 | grep servicemix`
Expected: No `spring-beans`, `spring-context`, `spring-core`, `spring-tx` from ServiceMix.

- [ ] **Step 6: Commit**

```bash
git add core/daemon-boot-provisiond/pom.xml core/pom.xml
git commit -m "build: add daemon-boot-provisiond module to reactor

Spring Boot 4.0.3 Provisiond with JPA, Quartz, 3x RPC clients,
3x SNMP adapters. All ServiceMix Spring 4.2.x exclusions applied."
```

---

### Task 9: Create Application class and application.yml

**Files:**
- Create: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondApplication.java`
- Create: `core/daemon-boot-provisiond/src/main/resources/application.yml`

- [ ] **Step 1: Create ProvisiondApplication.java**

```java
package org.opennms.netmgt.provision.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.netmgt.model.jakarta.dao",
        "org.opennms.netmgt.provision.boot"
    }
    // NOTE: Do NOT exclude HibernateJpaAutoConfiguration — Provisiond needs JPA
)
@EnableScheduling
public class ProvisiondApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProvisiondApplication.class, args);
    }
}
```

- [ ] **Step 2: Create application.yml**

Copy the full application.yml from the design spec (section "application.yml"). Key settings:
- `spring.datasource` — HikariCP with pool-size 10
- `spring.jpa` — Hibernate 7, PhysicalNamingStrategyStandardImpl, no ddl-auto
- `spring.quartz` — in-memory job store, scheduler name "provisiond"
- `opennms.home` — defaults to `/opt/deltav`
- `opennms.tsid.node-id` — 25
- `opennms.rpc.kafka.enabled` — true (always needs RPC)
- `opennms.kafka.consumer-group` — `opennms-provisiond`

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondApplication.java \
        core/daemon-boot-provisiond/src/main/resources/application.yml
git commit -m "feat: add ProvisiondApplication and application.yml

Spring Boot 4.0.3 entry point with JPA (Hibernate 7), Quartz
(in-memory), Kafka RPC enabled by default. TSID node-id 25."
```

---

### Task 10: Create ProvisiondBootConfiguration

The main configuration class — wires all beans. This is the largest single file.

**Files:**
- Create: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java`

- [ ] **Step 1: Read reference configurations**

Read: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryBootConfiguration.java`
Read: `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdConfiguration.java`
Read: `core/daemon-loader-provisiond/src/main/resources/META-INF/opennms/applicationContext-daemon-loader-provisiond.xml`

- [ ] **Step 2: Write the configuration class**

The class must wire ALL beans from the daemon-loader-provisiond.xml, translated to `@Bean` methods. Organize in sections:

```java
@Configuration
public class ProvisiondBootConfiguration {

    @Value("${opennms.home:/opt/deltav}")
    private String opennmsHome;

    // ===== JPA & Naming =====
    @Bean
    public PhysicalNamingStrategyStandardImpl physicalNamingStrategy() { ... }

    @Bean
    public PersistenceManagedTypes persistenceManagedTypes() { ... }
    // List all entity classes per spec

    // ===== DAO / Cache / Transaction =====
    @Bean
    public DistPollerDao distPollerDao(DataSource dataSource) { return new JdbcDistPollerDao(dataSource); }

    @Bean
    public InterfaceToNodeCache interfaceToNodeCache(DataSource ds) { return new JdbcInterfaceToNodeCache(ds); }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) { ... }

    @Bean
    public SessionUtils sessionUtils(PlatformTransactionManager txManager) { ... }
    // Same pattern as Alarmd

    // ===== SNMP Config =====
    @Bean
    public SnmpPeerFactoryInitializer snmpPeerFactoryInitializer() { ... }

    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(SnmpPeerFactoryInitializer init) { return init.getInstance(); }

    @Bean
    public SnmpProfileMapper snmpProfileMapper() { return new NoOpSnmpProfileMapper(); }

    // ===== RPC Clients =====
    @Bean
    public LocationAwareSnmpClient locationAwareSnmpClient() { return new LocationAwareSnmpClientRpcImpl(); }

    @Bean
    public DetectorClientRpcModule detectorClientRpcModule() { ... }

    @Bean
    public LocationAwareDetectorClient locationAwareDetectorClient() { return new LocationAwareDetectorClientRpcImpl(); }

    @Bean
    public DnsLookupClientRpcModule dnsLookupClientRpcModule() { return new DnsLookupClientRpcModule(4); }

    @Bean
    public LocationAwareDnsLookupClient locationAwareDnsLookupClient() { return new LocationAwareDnsLookupClientRpcImpl(); }

    // ===== Event Forwarder =====
    @Bean
    @Qualifier("transactionAware")
    public EventForwarder transactionAwareEventForwarder(EventForwarder eventForwarder) {
        return new QualifiedEventForwarder(eventForwarder);
    }

    // ===== ServiceRegistry =====
    @Bean
    public ServiceRegistry serviceRegistry() { return new DefaultServiceRegistry(); }

    // ===== Provisiond Config =====
    @Bean
    public ProvisiondConfigurationDao provisiondConfigDao() { return new InlineProvisiondConfigDao(); }

    // ===== ForeignSourceRepository (6 instances) =====
    // pendingForeignSourceRepository, deployedForeignSourceRepository,
    // fastPendingForeignSourceRepository, fastDeployedForeignSourceRepository,
    // fusedForeignSourceRepository, fastFusedForeignSourceRepository
    // All using opennmsHome + "/etc/imports" etc.

    // ===== Thread Pools =====
    // importExecutor, scanExecutor, writeExecutor, scheduledExecutor
    // Pool sizes from provisiondConfigDao

    // ===== Task Coordinator =====
    @Bean
    public DefaultTaskCoordinator taskCoordinator(...) { ... }

    // ===== Lifecycle Repository =====
    @Bean
    public DefaultLifeCycleRepository lifeCycleRepository(DefaultTaskCoordinator coordinator) { ... }
    // Two lifecycles: "import" (7 phases) and "nodeImport" (2 phases)

    // ===== Core Provisiond Beans =====
    @Bean
    public JSR223ScriptCache scriptCache() { return new JSR223ScriptCache(); }

    @Bean
    public DefaultPluginRegistry pluginRegistry(ServiceRegistry sr, ApplicationContext ctx) { ... }

    @Bean
    public DefaultProvisionService provisionService(...all 17 deps...) { ... }

    @Bean
    public CoreImportActivities coreImportActivities(ProvisionService ps) { ... }

    @Bean
    public MonitorHolder monitorHolder() { return new MonitorHolder(); }

    @Bean
    public ProvisioningAdapterManager adapterManager(DefaultPluginRegistry pr, EventForwarder ef) { ... }

    // ===== Import Scheduler (Quartz) =====
    @Bean
    public ImportJobFactory importJobFactory(Provisioner provisioner, EntityScopeProvider esp) { ... }

    @Bean
    public ImportScheduler importScheduler(Scheduler scheduler, ...) { ... }

    // ===== Provisioner Daemon =====
    @Bean
    public Provisioner provisioner(...all 12 deps...) { ... }

    // ===== Lifecycle =====
    @Bean
    public SmartLifecycle provisiondLifecycle(Provisioner provisioner) {
        return new DaemonSmartLifecycle(provisioner);
    }

    // ===== Event Listeners =====
    @Bean
    public AnnotationBasedEventListenerAdapter provisiondEventListener(
            Provisioner provisioner,
            @Qualifier("kafkaEventSubscriptionService") EventSubscriptionService ess) { ... }

    @Bean
    public AnnotationBasedEventListenerAdapter adapterManagerEventListener(
            ProvisioningAdapterManager manager,
            @Qualifier("kafkaEventSubscriptionService") EventSubscriptionService ess) { ... }

    // ===== SNMP Provisioning Adapters =====
    // SnmpHardwareInventoryProvisioningAdapter + config DAO + event listener
    // SnmpAssetProvisioningAdapter + config + event listener
    // SnmpMetadataProvisioningAdapter + config DAO + event listener
}
```

The implementer should use the daemon-loader-provisiond.xml as the authoritative bean graph and translate each `<bean>` to a `@Bean` method. Every bean in the XML must have a corresponding `@Bean` method or be identified as coming from daemon-common auto-scan.

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-provisiond -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Verify dependency tree**

Run: `./maven/bin/mvn dependency:tree -pl :org.opennms.core.daemon-boot-provisiond '-Dincludes=org.apache.servicemix.bundles:org.apache.servicemix.bundles.spring-*' 2>&1 | grep servicemix`
Expected: No Spring 4.2.x ServiceMix artifacts.

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java
git commit -m "$(cat <<'EOF'
feat: add ProvisiondBootConfiguration — Spring Boot 4 Provisiond

Single configuration class wiring all beans:
- 3x RPC clients (SNMP, Detector, DNS)
- 6x ForeignSourceRepository instances
- Quartz ImportScheduler
- TaskCoordinator with import/scan/write executors
- 3x SNMP provisioning adapters
- Event listeners for daemon and adapter manager
- JPA entity registration via PersistenceManagedTypes
EOF
)"
```

---

## Chunk 4: Cleanup, Docker & Verification

### Task 11: Delete daemon-loader-provisiond

**Files:**
- Delete: `core/daemon-loader-provisiond/` (entire module)
- Modify: `core/pom.xml` (remove module)

- [ ] **Step 1: Remove module from reactor**

Edit `core/pom.xml` — remove the `<module>daemon-loader-provisiond</module>` line.

- [ ] **Step 2: Delete the module directory**

```bash
rm -rf core/daemon-loader-provisiond
```

- [ ] **Step 3: Verify no other module references daemon-loader-provisiond**

Run: `grep -r "daemon-loader-provisiond" --include="pom.xml" . | grep -v target | grep -v ".git"`
Expected: No references (or only in the deleted directory).

Also check Karaf feature files:
Run: `grep -r "daemon-loader-provisiond" container/features/src/main/resources/ 2>/dev/null`
If found, remove those references.

- [ ] **Step 4: Verify full reactor compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-provisiond -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add -A core/daemon-loader-provisiond/ core/pom.xml
git commit -m "refactor: delete daemon-loader-provisiond — replaced by daemon-boot-provisiond

Karaf deployment path for Provisiond is retired. All classes
relocated to daemon-boot-provisiond in prior commits."
```

---

### Task 12: Docker and container updates

**Files:**
- Modify: `opennms-container/delta-v/Dockerfile.daemon`
- Modify: `opennms-container/delta-v/docker-compose.yml` (or equivalent)

- [ ] **Step 1: Read current Dockerfile.daemon**

Read: `opennms-container/delta-v/Dockerfile.daemon`
Identify where other daemon fat JARs are copied.

- [ ] **Step 2: Add Provisiond fat JAR**

Add after the existing daemon JAR COPY lines:
```dockerfile
COPY staging/daemon/daemon-boot-provisiond.jar /opt/daemon-boot-provisiond.jar
```

- [ ] **Step 3: Update docker-compose.yml**

Read the docker-compose file and add a Provisiond service entry following the pattern of existing daemons. Include:
- `OPENNMS_TSID_NODE_ID=25`
- `RPC_KAFKA_ENABLED=true`
- `RPC_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`
- `RPC_KAFKA_FORCE_REMOTE=true`
- Config file volume mounts for provisioning configs + adapter configs

- [ ] **Step 4: Update staging script**

Read the staging script (likely `opennms-container/delta-v/build.sh` or similar) and add the Provisiond fat JAR to the staging step.

- [ ] **Step 5: Commit**

```bash
git add opennms-container/delta-v/
git commit -m "feat: add Provisiond to Docker daemon container

Fat JAR, docker-compose service, staging. TSID node-id 25.
RPC Kafka enabled with force-remote."
```

---

### Task 13: Verify all daemons still compile (regression)

- [ ] **Step 1: Compile all 6 daemons**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-trapd,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-discovery,:org.opennms.core.daemon-boot-provisiond -am install 2>&1 | tail -20`
Expected: All 6 `BUILD SUCCESS`

- [ ] **Step 2: Run daemon-common tests**

Run: `./compile.pl --projects :org.opennms.core.daemon-common -am verify 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 3: Run opennms-model-jakarta tests**

Run: `./compile.pl --projects :org.opennms.core.model-jakarta -am verify 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 4: Run opennms-provisiond tests**

Run: `./compile.pl --projects :opennms-provisiond -am verify 2>&1 | tail -20`
Expected: Tests pass. Constructor injection changes may require test fixture updates — fix any failures.

---

### Task 14: E2E verification

- [ ] **Step 1: Docker rebuild**

```bash
cd opennms-container/delta-v && docker compose build
```

- [ ] **Step 2: Start stack and verify Provisiond starts**

```bash
docker compose up -d
docker compose logs -f provisiond 2>&1 | head -50
```
Expected: Spring Boot banner, "Started ProvisiondApplication", no bean creation errors.

- [ ] **Step 3: Verify Kafka connectivity**

Check logs for Kafka RPC client initialization and event subscription.

- [ ] **Step 4: Test requisition import**

Create a test requisition and trigger an import. Verify:
- Node created in database
- Interfaces discovered via SNMP (through Minion RPC)
- Services detected via Detector (through Minion RPC)

- [ ] **Step 5: Test SNMP adapters**

Verify SNMP metadata/asset/hardware-inventory adapters fire after node provisioning.

- [ ] **Step 6: Create PR**

```bash
gh pr create --repo pbrane/delta-v --base develop \
  --title "feat: migrate Provisiond to Spring Boot 4" \
  --body "$(cat <<'EOF'
## Summary

- **Spring Boot 4.0.3 Provisiond** — most complex daemon migration (Tier 5)
- **Constructor injection** — modernized Provisioner, DefaultProvisionService, DefaultPluginRegistry, ImportScheduler, ImportJobFactory
- **9 new JPA DAOs** + expanded NodeDaoJpa in shared jakarta.dao package
- **3× Kafka RPC** — SNMP, Detector, DNS via Minion
- **Spring Boot Quartz** for import scheduling
- **3× SNMP adapters** — HW inventory, asset, metadata (Minion-compliant)
- **Deleted daemon-loader-provisiond** — clean break from Karaf path
- **Docker** — fat JAR, docker-compose service, TSID node-id 25

## Deferred

- Dns, ReverseDns, GeoIp, Geolocation adapters (need Minion RPC wrappers)
- WsMan, Puppet adapters (excluded)
- Real MATE EntityScopeProvider (NoOp for now)
- Policy providers (empty collections)

## Test plan

- [ ] All 6 daemons compile
- [ ] daemon-common + opennms-model-jakarta tests pass
- [ ] opennms-provisiond unit tests pass (constructor injection)
- [ ] No Spring 4.2.x ServiceMix artifacts on classpath
- [ ] Docker: Provisiond starts, connects to Kafka + PostgreSQL
- [ ] E2E: Requisition import → node creation via Minion RPC
- [ ] E2E: SNMP adapters fire post-provisioning
- [ ] Regression: other 5 daemons still start
EOF
)"
```
