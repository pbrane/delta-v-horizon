# BSMd Spring Boot 4.0.3 Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate BSMd from Karaf/OSGi to a standalone Spring Boot 4.0.3 fat JAR, including Jakarta entity conversion of 17 BSM entity classes.

**Architecture:** Create `core/daemon-boot-bsmd/` with Jakarta BSM entities, JPA DAOs, and Spring Boot configuration that reuses the existing BSM service layer (`BusinessServiceManagerImpl`, `DefaultBusinessServiceStateMachine`) unchanged. Add `SpringServiceDaemonSmartLifecycle` to `daemon-common` for `SpringServiceDaemon` support. Add `OnmsApplication`, `MonitoredServiceDaoJpa`, and `ApplicationDaoJpa` to `model-jakarta`.

**Tech Stack:** Spring Boot 4.0.3, Hibernate 7, Jakarta Persistence, Java 21, Kafka event transport, Testcontainers 2.0.3

**Spec:** `docs/superpowers/specs/2026-03-17-bsmd-spring-boot-4-migration-design.md`

---

### Task 1: Add SpringServiceDaemonSmartLifecycle to daemon-common

`Bsmd` implements `SpringServiceDaemon` (not `AbstractServiceDaemon`), so the existing `DaemonSmartLifecycle` doesn't accept it. Add a new adapter.

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/SpringServiceDaemonSmartLifecycle.java`
- Test: `core/daemon-common/src/test/java/org/opennms/core/daemon/common/SpringServiceDaemonSmartLifecycleTest.java`

- [ ] **Step 1: Write the test**

```java
package org.opennms.core.daemon.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opennms.netmgt.daemon.SpringServiceDaemon;

class SpringServiceDaemonSmartLifecycleTest {

    private boolean initCalled = false;
    private boolean startCalled = false;
    private boolean destroyCalled = false;

    private final SpringServiceDaemon mockDaemon = new SpringServiceDaemon() {
        @Override
        public void afterPropertiesSet() { initCalled = true; }
        @Override
        public void start() { startCalled = true; }
        @Override
        public void destroy() { destroyCalled = true; }
    };

    @Test
    void startCallsAfterPropertiesSetThenStart() throws Exception {
        var lifecycle = new SpringServiceDaemonSmartLifecycle(mockDaemon, "TestDaemon");
        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.start();

        assertThat(initCalled).isTrue();
        assertThat(startCalled).isTrue();
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void stopCallsDestroy() throws Exception {
        var lifecycle = new SpringServiceDaemonSmartLifecycle(mockDaemon, "TestDaemon");
        lifecycle.start();

        lifecycle.stop();

        assertThat(destroyCalled).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void phaseIsMaxValue() {
        var lifecycle = new SpringServiceDaemonSmartLifecycle(mockDaemon, "TestDaemon");
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd core/daemon-common && ../../maven/bin/mvn test -pl . -Dtest=SpringServiceDaemonSmartLifecycleTest -DfailIfNoTests=false`
Expected: Compilation failure — class does not exist yet.

- [ ] **Step 3: Implement SpringServiceDaemonSmartLifecycle**

```java
package org.opennms.core.daemon.common;

import org.opennms.netmgt.daemon.SpringServiceDaemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Adapts a {@link SpringServiceDaemon} to Spring's {@link SmartLifecycle} interface.
 *
 * <p>Unlike {@link DaemonSmartLifecycle} which accepts {@code AbstractServiceDaemon},
 * this adapter works with daemons that implement {@code SpringServiceDaemon}
 * ({@code InitializingBean + DisposableBean + start()}).</p>
 */
public class SpringServiceDaemonSmartLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SpringServiceDaemonSmartLifecycle.class);

    private final SpringServiceDaemon daemon;
    private final String name;
    private volatile boolean running = false;

    public SpringServiceDaemonSmartLifecycle(SpringServiceDaemon daemon, String name) {
        this.daemon = daemon;
        this.name = name;
    }

    @Override
    public void start() {
        LOG.info("Starting daemon: {}", name);
        try {
            daemon.afterPropertiesSet();
            daemon.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start daemon: " + name, e);
        }
        running = true;
        LOG.info("Daemon started: {}", name);
    }

    @Override
    public void stop() {
        LOG.info("Stopping daemon: {}", name);
        try {
            daemon.destroy();
        } catch (Exception e) {
            LOG.error("Error stopping daemon: {}", name, e);
        }
        running = false;
        LOG.info("Daemon stopped: {}", name);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd core/daemon-common && ../../maven/bin/mvn test -pl . -Dtest=SpringServiceDaemonSmartLifecycleTest`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/SpringServiceDaemonSmartLifecycle.java \
        core/daemon-common/src/test/java/org/opennms/core/daemon/common/SpringServiceDaemonSmartLifecycleTest.java
git commit -m "feat: add SpringServiceDaemonSmartLifecycle for SpringServiceDaemon adaption"
```

---

### Task 2: Add OnmsApplication, MonitoredServiceDaoJpa, and ApplicationDaoJpa to model-jakarta

`BusinessServiceManagerImpl` has `@Autowired` fields for `MonitoredServiceDao`, `ApplicationDao`, and `NodeDao`. `NodeDaoJpa` already exists. We need the other two DAOs plus the `OnmsApplication` Jakarta entity.

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsApplication.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/MonitoredServiceDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/ApplicationDaoJpa.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsApplication.java` (legacy source)
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/MonitoredServiceDao.java`
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/ApplicationDao.java`

- [ ] **Step 1: Create Jakarta OnmsApplication entity**

Copy `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsApplication.java` to `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsApplication.java`. Convert:
- `javax.persistence.*` → `jakarta.persistence.*`
- `javax.xml.bind.*` → `jakarta.xml.bind.*`
- Remove `org.codehaus.jackson` import (legacy Jackson 1.x) — use `com.fasterxml.jackson` if needed
- Ensure `OnmsMonitoredService` and `OnmsMonitoringLocation` references resolve to the Jakarta versions already in this module

Key annotations to preserve:
- `@Entity`, `@Table(name = "applications")`
- `@ManyToMany(mappedBy="applications")` for monitoredServices
- `@ManyToMany` with `@JoinTable(name="application_perspective_location_map")` for perspectiveLocations

- [ ] **Step 2: Create MonitoredServiceDaoJpa**

```java
package org.opennms.netmgt.model.jakarta.dao;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.model.OnmsApplication;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.ServiceSelector;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoredServiceDaoJpa extends AbstractDaoJpa<OnmsMonitoredService, Integer>
        implements MonitoredServiceDao {

    public MonitoredServiceDaoJpa() {
        super(OnmsMonitoredService.class);
    }

    @Override
    public OnmsMonitoredService get(Integer nodeId, InetAddress ipAddress, Integer serviceId) {
        return findUnique(
            "SELECT s FROM OnmsMonitoredService s WHERE s.ipInterface.node.id = ?1 AND s.ipInterface.ipAddress = ?2 AND s.serviceType.id = ?3",
            nodeId, ipAddress, serviceId);
    }

    @Override
    public OnmsMonitoredService get(Integer nodeId, InetAddress ipAddr, Integer ifIndex, Integer serviceId) {
        return findUnique(
            "SELECT s FROM OnmsMonitoredService s WHERE s.ipInterface.node.id = ?1 AND s.ipInterface.ipAddress = ?2 AND s.ipInterface.ifIndex = ?3 AND s.serviceType.id = ?4",
            nodeId, ipAddr, ifIndex, serviceId);
    }

    @Override
    public OnmsMonitoredService get(Integer nodeId, InetAddress ipAddr, String svcName) {
        return findUnique(
            "SELECT s FROM OnmsMonitoredService s WHERE s.ipInterface.node.id = ?1 AND s.ipInterface.ipAddress = ?2 AND s.serviceType.name = ?3",
            nodeId, ipAddr, svcName);
    }

    @Override
    public List<OnmsMonitoredService> findByType(String typeName) {
        return find("SELECT s FROM OnmsMonitoredService s WHERE s.serviceType.name = ?1", typeName);
    }

    @Override
    public List<OnmsMonitoredService> findAllServices() {
        return findAll();
    }

    @Override
    public Set<OnmsMonitoredService> findByApplication(OnmsApplication application) {
        return Set.copyOf(find(
            "SELECT s FROM OnmsMonitoredService s JOIN s.applications a WHERE a.id = ?1",
            application.getId()));
    }

    @Override
    public OnmsMonitoredService getPrimaryService(Integer nodeId, String svcName) {
        return findUnique(
            "SELECT s FROM OnmsMonitoredService s WHERE s.ipInterface.node.id = ?1 AND s.serviceType.name = ?2 AND s.ipInterface.isSnmpPrimary = 'P'",
            nodeId, svcName);
    }

    @Override
    public List<OnmsMonitoredService> findMatchingServices(ServiceSelector selector) {
        return Collections.emptyList();
    }

    @Override
    public List<OnmsMonitoredService> findByNode(int nodeId) {
        return find("SELECT s FROM OnmsMonitoredService s WHERE s.ipInterface.node.id = ?1", nodeId);
    }

    // --- LegacyOnmsDao methods (deprecated, stub implementations) ---

    @Override
    public List<OnmsMonitoredService> findMatching(org.opennms.core.criteria.OnmsCriteria criteria) {
        throw new UnsupportedOperationException("LegacyOnmsDao.findMatching(OnmsCriteria) not implemented — use findMatching(Criteria)");
    }

    @Override
    public int countMatching(org.opennms.core.criteria.OnmsCriteria criteria) {
        throw new UnsupportedOperationException("LegacyOnmsDao.countMatching(OnmsCriteria) not implemented — use countMatching(Criteria)");
    }
}
```

- [ ] **Step 3: Create ApplicationDaoJpa**

```java
package org.opennms.netmgt.model.jakarta.dao;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.dao.api.ApplicationDao;
import org.opennms.netmgt.dao.api.ApplicationStatus;
import org.opennms.netmgt.dao.api.MonitoredServiceStatusEntity;
import org.opennms.netmgt.dao.api.ServicePerspective;
import org.opennms.netmgt.model.OnmsApplication;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationDaoJpa extends AbstractDaoJpa<OnmsApplication, Integer>
        implements ApplicationDao {

    public ApplicationDaoJpa() {
        super(OnmsApplication.class);
    }

    @Override
    public OnmsApplication findByName(String name) {
        return findUnique("SELECT a FROM OnmsApplication a WHERE a.name = ?1", name);
    }

    @Override
    public List<ApplicationStatus> getApplicationStatus() {
        return Collections.emptyList();
    }

    @Override
    public List<ApplicationStatus> getApplicationStatus(List<OnmsApplication> applications) {
        return Collections.emptyList();
    }

    @Override
    public List<MonitoredServiceStatusEntity> getAlarmStatus() {
        return Collections.emptyList();
    }

    @Override
    public List<MonitoredServiceStatusEntity> getAlarmStatus(List<OnmsApplication> applications) {
        return Collections.emptyList();
    }

    @Override
    public List<OnmsMonitoringLocation> getPerspectiveLocationsForService(int nodeId, InetAddress ipAddress, String serviceName) {
        return Collections.emptyList();
    }

    @Override
    public List<ServicePerspective> getServicePerspectives() {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 4: Verify model-jakarta compiles**

Run: `cd core/opennms-model-jakarta && ../../maven/bin/mvn compile -pl . -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsApplication.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/MonitoredServiceDaoJpa.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/ApplicationDaoJpa.java
git commit -m "feat: add OnmsApplication entity and MonitoredServiceDaoJpa/ApplicationDaoJpa to model-jakarta"
```

---

### Task 3: Create daemon-boot-bsmd module skeleton (POM + application.yml + register in reactor)

**Files:**
- Create: `core/daemon-boot-bsmd/pom.xml`
- Create: `core/daemon-boot-bsmd/src/main/resources/application.yml`
- Modify: `core/pom.xml` (add module)

- [ ] **Step 1: Create pom.xml**

Use `core/daemon-boot-eventtranslator/pom.xml` as the template. Key changes:
- ArtifactId: `org.opennms.core.daemon-boot-bsmd`
- Name: `OpenNMS :: Core :: Daemon Boot :: BSMd`
- Dependencies:
  - `org.opennms.core:org.opennms.core.model-jakarta` (FIRST — Jakarta entities win classpath)
  - `org.opennms.core:org.opennms.core.daemon-common` (with `jackson-module-scala_2.13` exclusion)
  - `org.opennms.features.bsm:org.opennms.features.bsm.daemon` (Bsmd class, with standard ServiceMix/Hibernate/SLF4J/config-model exclusions)
  - `org.opennms.features.bsm:org.opennms.features.bsm.service.api`
  - `org.opennms.features.bsm:org.opennms.features.bsm.service.impl` (with same exclusions)
  - `org.opennms:opennms-alarmd` (AlarmLifecycleListenerManager, with exclusions)
  - `org.opennms:opennms-alarm-api`
  - `javax.persistence:javax.persistence-api:2.2` (runtime)
  - `javax.xml.bind:jaxb-api:2.3.1` (runtime)
  - Jakarta XML Bind, Jakarta Inject, JAXB runtime
  - SLF4J 2.x overrides
  - `spring-boot-starter-web`, `spring-boot-starter-data-jpa`
  - Test deps: `spring-boot-starter-test`, Testcontainers
- Same `<dependencyManagement>` block as EventTranslator
- Same build plugins (compiler, spring-boot-maven-plugin, surefire 3.5.5, failsafe 3.5.5)

- [ ] **Step 2: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: none
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
        implicit-strategy: org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
      hibernate.archive.autodetection: none
    open-in-view: false

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always

opennms:
  home: ${OPENNMS_HOME:/opt/deltav}
  instance:
    id: ${OPENNMS_INSTANCE_ID:OpenNMS}
  tsid:
    node-id: ${OPENNMS_TSID_NODE_ID:0}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-core}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}

logging:
  level:
    org.opennms: INFO
    org.hibernate: WARN
    org.hibernate.SQL: ${HIBERNATE_SQL_LOG_LEVEL:WARN}
```

- [ ] **Step 3: Register in core/pom.xml**

Add `<module>daemon-boot-bsmd</module>` after `daemon-boot-discovery` in `core/pom.xml`.

- [ ] **Step 4: Verify POM resolves dependencies**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn dependency:resolve -pl .`
Expected: BUILD SUCCESS — all dependencies resolved.

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-bsmd/pom.xml core/daemon-boot-bsmd/src/main/resources/application.yml core/pom.xml
git commit -m "feat: create daemon-boot-bsmd module skeleton"
```

---

### Task 4: Create Jakarta BSM entities (20 files — mechanical javax→jakarta conversion)

Copy all 17 entity classes + 3 interfaces from `features/bsm/persistence/api/` to `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/persistence/api/`. Convert `javax.persistence.*` → `jakarta.persistence.*` and `javax.validation.*` → `jakarta.validation.*`. Change `OnmsMonitoredService` and `OnmsApplication` imports to use the `model-jakarta` versions (same package, so no import changes needed — they're in the same `org.opennms.netmgt.model` package).

**Files to create** (all under `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/persistence/api/`):

Entity classes (javax→jakarta conversion):
- `BusinessServiceEntity.java`
- `BusinessServiceEdgeEntity.java`
- `BusinessServiceChildEdgeEntity.java`
- `IPServiceEdgeEntity.java`
- `ApplicationEdgeEntity.java`
- `SingleReductionKeyEdgeEntity.java`
- `functions/reduce/AbstractReductionFunctionEntity.java`
- `functions/reduce/HighestSeverityEntity.java`
- `functions/reduce/HighestSeverityAboveEntity.java`
- `functions/reduce/ThresholdEntity.java`
- `functions/reduce/ExponentialPropagationEntity.java`
- `functions/map/AbstractMapFunctionEntity.java`
- `functions/map/IdentityEntity.java`
- `functions/map/IgnoreEntity.java`
- `functions/map/DecreaseEntity.java`
- `functions/map/IncreaseEntity.java`
- `functions/map/SetToEntity.java`

Interfaces (no JPA annotations — copy as-is):
- `EdgeEntity.java`
- `EdgeEntityVisitor.java`
- `functions/map/MapFunctionEntityVisitor.java`
- `functions/reduce/ReductionFunctionEntityVisitor.java`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/persistence/api/functions/map
mkdir -p core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/persistence/api/functions/reduce
```

- [ ] **Step 2: Copy and convert all entity files**

For each file, the conversion is mechanical:
- Replace `import javax.persistence.` → `import jakarta.persistence.`
- Replace `import javax.validation.` → `import jakarta.validation.`
- All other code remains identical

The 3 interface files (`EdgeEntity.java`, `EdgeEntityVisitor.java`, `MapFunctionEntityVisitor.java`, `ReductionFunctionEntityVisitor.java`) have no JPA imports — copy them unchanged.

Reference the complete source of each file from `features/bsm/persistence/api/src/main/java/org/opennms/netmgt/bsm/persistence/api/` (all sources read during planning phase).

- [ ] **Step 3: Verify entities compile**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests`
Expected: BUILD SUCCESS — all 20 files compile with Jakarta imports.

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/persistence/api/
git commit -m "feat: add Jakarta BSM persistence entities for Spring Boot 4"
```

---

### Task 5: Create BSM JPA DAOs

**Files to create** (all under `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/dao/`):
- `BusinessServiceDaoJpa.java`
- `BusinessServiceEdgeDaoJpa.java`
- `MapFunctionDaoJpa.java`
- `ReductionFunctionDaoJpa.java`

- [ ] **Step 1: Create BusinessServiceDaoJpa**

```java
package org.opennms.netmgt.bsm.dao;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceChildEdgeEntity;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceDao;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceEdgeEntity;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceEntity;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessServiceDaoJpa extends AbstractDaoJpa<BusinessServiceEntity, Long>
        implements BusinessServiceDao {

    public BusinessServiceDaoJpa() {
        super(BusinessServiceEntity.class);
    }

    @Override
    public Set<BusinessServiceEntity> findParents(BusinessServiceEntity child) {
        List<BusinessServiceEdgeEntity> edges = entityManager()
            .createQuery(
                "SELECT edge FROM BusinessServiceEdgeEntity edge " +
                "WHERE TYPE(edge) = BusinessServiceChildEdgeEntity " +
                "AND edge.child.id = :childId",
                BusinessServiceEdgeEntity.class)
            .setParameter("childId", child.getId())
            .getResultList();
        return edges.stream()
            .map(BusinessServiceEdgeEntity::getBusinessService)
            .collect(Collectors.toSet());
    }
}
```

Note: The `findParents()` HQL uses named parameter `:childId` with `entityManager()` directly because the inherited `find()` helper returns `List<T>` typed to `BusinessServiceEntity`, but this query returns edge entities.

- [ ] **Step 2: Create BusinessServiceEdgeDaoJpa**

```java
package org.opennms.netmgt.bsm.dao;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceEdgeDao;
import org.opennms.netmgt.bsm.persistence.api.BusinessServiceEdgeEntity;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessServiceEdgeDaoJpa extends AbstractDaoJpa<BusinessServiceEdgeEntity, Long>
        implements BusinessServiceEdgeDao {

    public BusinessServiceEdgeDaoJpa() {
        super(BusinessServiceEdgeEntity.class);
    }
}
```

- [ ] **Step 3: Create MapFunctionDaoJpa**

```java
package org.opennms.netmgt.bsm.dao;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.bsm.persistence.api.functions.map.AbstractMapFunctionEntity;
import org.opennms.netmgt.bsm.persistence.api.functions.map.MapFunctionDao;
import org.springframework.stereotype.Repository;

@Repository
public class MapFunctionDaoJpa extends AbstractDaoJpa<AbstractMapFunctionEntity, Long>
        implements MapFunctionDao {

    public MapFunctionDaoJpa() {
        super(AbstractMapFunctionEntity.class);
    }
}
```

- [ ] **Step 4: Create ReductionFunctionDaoJpa**

```java
package org.opennms.netmgt.bsm.dao;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.bsm.persistence.api.functions.reduce.AbstractReductionFunctionEntity;
import org.opennms.netmgt.bsm.persistence.api.functions.reduce.ReductionFunctionDao;
import org.springframework.stereotype.Repository;

@Repository
public class ReductionFunctionDaoJpa extends AbstractDaoJpa<AbstractReductionFunctionEntity, Long>
        implements ReductionFunctionDao {

    public ReductionFunctionDaoJpa() {
        super(AbstractReductionFunctionEntity.class);
    }
}
```

- [ ] **Step 5: Verify DAOs compile**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/dao/
git commit -m "feat: add BSM JPA DAOs for Spring Boot 4"
```

---

### Task 6: Convert Bsmd to constructor injection

Modify the `Bsmd` class in `features/bsm/daemon/` to use constructor injection instead of `@Autowired` field injection. This makes it explicit what dependencies the daemon requires.

**Files:**
- Modify: `features/bsm/daemon/src/main/java/org/opennms/netmgt/bsm/daemon/Bsmd.java`

- [ ] **Step 1: Convert Bsmd to constructor injection**

Replace the 6 `@Autowired` fields with constructor parameters:

```java
// Remove these @Autowired fields:
// @Autowired @Qualifier("eventIpcManager") private EventIpcManager m_eventIpcManager;
// @Autowired(required = false) private MessageBus m_messageBus;
// @Autowired private EventConfDao m_eventConfDao;
// @Autowired private TransactionTemplate m_template;
// @Autowired private BusinessServiceStateMachine m_stateMachine;
// @Autowired private BusinessServiceManager m_manager;

// Add constructor:
public Bsmd(EventIpcManager eventIpcManager,
            EventConfDao eventConfDao,
            TransactionTemplate transactionTemplate,
            BusinessServiceStateMachine stateMachine,
            BusinessServiceManager manager,
            @org.springframework.lang.Nullable MessageBus messageBus) {
    m_eventIpcManager = Objects.requireNonNull(eventIpcManager);
    m_eventConfDao = Objects.requireNonNull(eventConfDao);
    m_template = Objects.requireNonNull(transactionTemplate);
    m_stateMachine = Objects.requireNonNull(stateMachine);
    m_manager = Objects.requireNonNull(manager);
    m_messageBus = messageBus; // nullable — IPC reload optional
}
```

Keep the existing setter methods for backward compatibility with the Karaf XML context (`applicationContext-daemon-loader-bsmd.xml`) which uses property injection.

Remove `@Autowired` and `@Qualifier` annotations from the fields. Keep the fields as `private` (not `final` since setters exist).

- [ ] **Step 2: Backward compatibility with Karaf XML context**

Add `@Autowired` on the new constructor. Remove `@Autowired` from the fields. Spring will use the constructor in both Boot and Karaf contexts — the Karaf XML context uses `<context:annotation-config/>` which enables `@Autowired` constructor resolution. Keep the existing setter methods as-is for any remaining XML property injection.

- [ ] **Step 3: Verify features/bsm/daemon compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.features.bsm.daemon -am install`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add features/bsm/daemon/src/main/java/org/opennms/netmgt/bsm/daemon/Bsmd.java
git commit -m "refactor: convert Bsmd to constructor injection"
```

---

### Task 7: Create BsmdApplication and BsmdConfiguration

**Files:**
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/BsmdApplication.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/BsmdConfiguration.java`

- [ ] **Step 1: Create BsmdApplication**

```java
package org.opennms.netmgt.bsm.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.bsm.boot",
    "org.opennms.netmgt.bsm.dao",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class BsmdApplication {

    public static void main(String[] args) {
        SpringApplication.run(BsmdApplication.class, args);
    }
}
```

- [ ] **Step 2: Create BsmdConfiguration**

This is the central wiring class. Reference `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdConfiguration.java` for the pattern. Key beans:

```java
package org.opennms.netmgt.bsm.boot;

import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.opennms.core.daemon.common.SpringServiceDaemonSmartLifecycle;
import org.opennms.netmgt.alarmd.AlarmLifecycleListenerManager;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.bsm.daemon.Bsmd;
import org.opennms.netmgt.bsm.persistence.api.*;
import org.opennms.netmgt.bsm.persistence.api.functions.map.*;
import org.opennms.netmgt.bsm.persistence.api.functions.reduce.*;
import org.opennms.netmgt.bsm.service.BusinessServiceManager;
import org.opennms.netmgt.bsm.service.BusinessServiceStateMachine;
import org.opennms.netmgt.bsm.service.internal.BusinessServiceManagerImpl;
import org.opennms.netmgt.bsm.service.internal.DefaultBusinessServiceStateMachine;
import org.opennms.netmgt.config.DefaultEventConfDao;
import org.opennms.netmgt.config.api.EventConfDao;
import org.opennms.netmgt.events.api.AnnotationBasedEventListenerAdapter;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.model.*;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class BsmdConfiguration {

    @Bean
    public PhysicalNamingStrategyStandardImpl physicalNamingStrategy() {
        return new PhysicalNamingStrategyStandardImpl();
    }

    @Bean
    public PersistenceManagedTypes persistenceManagedTypes() {
        return PersistenceManagedTypes.of(
            // Core entities (from model-jakarta)
            OnmsAlarm.class.getName(),
            AlarmAssociation.class.getName(),
            OnmsCategory.class.getName(),
            OnmsDistPoller.class.getName(),
            OnmsIpInterface.class.getName(),
            OnmsMemo.class.getName(),
            OnmsMonitoredService.class.getName(),
            OnmsMonitoringSystem.class.getName(),
            OnmsNode.class.getName(),
            OnmsReductionKeyMemo.class.getName(),
            OnmsServiceType.class.getName(),
            OnmsSnmpInterface.class.getName(),
            OnmsMonitoringLocation.class.getName(),
            OnmsApplication.class.getName(),
            // BSM entities (Jakarta versions in this module)
            BusinessServiceEntity.class.getName(),
            BusinessServiceEdgeEntity.class.getName(),
            BusinessServiceChildEdgeEntity.class.getName(),
            IPServiceEdgeEntity.class.getName(),
            ApplicationEdgeEntity.class.getName(),
            SingleReductionKeyEdgeEntity.class.getName(),
            AbstractReductionFunctionEntity.class.getName(),
            HighestSeverityEntity.class.getName(),
            HighestSeverityAboveEntity.class.getName(),
            ThresholdEntity.class.getName(),
            ExponentialPropagationEntity.class.getName(),
            AbstractMapFunctionEntity.class.getName(),
            IdentityEntity.class.getName(),
            IgnoreEntity.class.getName(),
            DecreaseEntity.class.getName(),
            IncreaseEntity.class.getName(),
            SetToEntity.class.getName()
        );
    }

    @Bean
    public org.opennms.netmgt.dao.api.SessionUtils sessionUtils(PlatformTransactionManager txManager) {
        var txTemplate = new TransactionTemplate(txManager);
        var readOnlyTxTemplate = new TransactionTemplate(txManager);
        readOnlyTxTemplate.setReadOnly(true);
        return new org.opennms.netmgt.dao.api.SessionUtils() {
            @Override
            public <V> V withTransaction(java.util.function.Supplier<V> supplier) {
                return txTemplate.execute(status -> supplier.get());
            }
            @Override
            public <V> V withReadOnlyTransaction(java.util.function.Supplier<V> supplier) {
                return readOnlyTxTemplate.execute(status -> supplier.get());
            }
            @Override
            public <V> V withManualFlush(java.util.function.Supplier<V> supplier) {
                return supplier.get();
            }
        };
    }

    @Bean
    public TransactionOperations transactionOperations(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    // --- BSM Service Layer (reused unchanged) ---

    @Bean
    public BusinessServiceStateMachine businessServiceStateMachine() {
        return new DefaultBusinessServiceStateMachine();
    }

    @Bean
    public BusinessServiceManager businessServiceManager() {
        // @Autowired field injection will wire DAOs, state machine, etc.
        return new BusinessServiceManagerImpl();
    }

    // --- Alarm Lifecycle ---

    @Bean
    public AlarmLifecycleListenerManager alarmLifecycleListenerManager() {
        return new AlarmLifecycleListenerManager();
    }

    /**
     * Register Bsmd as an AlarmLifecycleListener so it receives alarm snapshots.
     * Uses SmartInitializingSingleton to run after all beans are constructed,
     * avoiding circular dependency between AlarmLifecycleListenerManager and Bsmd.
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton registerBsmdAsAlarmListener(
            AlarmLifecycleListenerManager manager, Bsmd bsmd) {
        return () -> manager.onListenerRegistered(bsmd, new java.util.HashMap<>());
    }

    // --- EventConf ---

    @Bean
    public EventConfDao eventConfDao() {
        return new DefaultEventConfDao();
    }

    // --- Bsmd Daemon ---

    @Bean
    public Bsmd bsmd(
            @Qualifier("eventIpcManager") EventIpcManager eventIpcManager,
            EventConfDao eventConfDao,
            TransactionTemplate transactionTemplate,
            BusinessServiceStateMachine stateMachine,
            BusinessServiceManager manager) {
        return new Bsmd(eventIpcManager, eventConfDao, transactionTemplate,
                        stateMachine, manager, null /* MessageBus not available */);
    }

    @Bean
    public AnnotationBasedEventListenerAdapter bsmdEventListenerAdapter(
            Bsmd bsmd,
            @Qualifier("kafkaEventSubscriptionService") EventSubscriptionService eventSubscriptionService) {
        var adapter = new AnnotationBasedEventListenerAdapter();
        adapter.setAnnotatedListener(bsmd);
        adapter.setEventSubscriptionService(eventSubscriptionService);
        return adapter;
    }

    @Bean
    public SmartLifecycle bsmdLifecycle(Bsmd bsmd) {
        return new SpringServiceDaemonSmartLifecycle(bsmd, Bsmd.NAME);
    }
}
```

- [ ] **Step 3: Verify module compiles**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/
git commit -m "feat: add BsmdApplication and BsmdConfiguration for Spring Boot 4"
```

---

### Task 8: Build verification and dependency resolution

Full build to verify everything compiles together and dependency conflicts are resolved.

**Files:**
- Possibly modify: `core/daemon-boot-bsmd/pom.xml` (exclusions to fix conflicts)

- [ ] **Step 1: Build daemon-boot-bsmd with all upstream dependencies**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-bsmd -am install`
Expected: BUILD SUCCESS. If not, resolve dependency conflicts by adding exclusions to `pom.xml`.

Common issues to watch for:
- `jackson-module-scala_2.13` version conflict — exclude from `daemon-common` dep
- ServiceMix Spring bundles leaking through BSM dependencies — add exclusions
- Duplicate `javax.persistence` / `jakarta.persistence` classes — ensure `model-jakarta` is listed FIRST
- `opennms-config-model` pulling in legacy `OnmsMonitoringLocation` — exclude it

- [ ] **Step 2: Run dependency:tree to verify no conflicts**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn dependency:tree -pl . | grep -i "conflict\|omitted"`
Expected: No unexpected omissions or conflicts.

- [ ] **Step 3: Fix any remaining exclusions and rebuild**

Iterate on exclusions until the build is clean.

- [ ] **Step 4: Commit any POM fixes**

```bash
git add core/daemon-boot-bsmd/pom.xml
git commit -m "fix: resolve dependency conflicts in daemon-boot-bsmd"
```

---

### Task 9: Context loads integration test

Verify the Spring Boot application context starts with all beans wired correctly against a real PostgreSQL database.

**Files:**
- Create: `core/daemon-boot-bsmd/src/test/java/org/opennms/netmgt/bsm/boot/BsmdApplicationIT.java`

- [ ] **Step 1: Write the integration test**

```java
package org.opennms.netmgt.bsm.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opennms.netmgt.bsm.daemon.Bsmd;
import org.opennms.netmgt.bsm.service.BusinessServiceManager;
import org.opennms.netmgt.bsm.service.BusinessServiceStateMachine;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = BsmdApplication.class,
    properties = {
        "opennms.kafka.bootstrap-servers=localhost:9092",
        "spring.jpa.hibernate.ddl-auto=none"
    })
@Testcontainers
class BsmdApplicationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("opennms")
        .withUsername("opennms")
        .withPassword("opennms")
        .withInitScript("schema/bsm-schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private Bsmd bsmd;

    @Autowired
    private BusinessServiceStateMachine stateMachine;

    @Autowired
    private BusinessServiceManager manager;

    @Autowired
    private EventIpcManager eventIpcManager;

    @Test
    void contextLoads() {
        assertThat(bsmd).isNotNull();
        assertThat(stateMachine).isNotNull();
        assertThat(manager).isNotNull();
        assertThat(eventIpcManager).isNotNull();
    }
}
```

Note: The `schema/bsm-schema.sql` test resource will need to contain the BSM table DDL (`bsm_service`, `bsm_service_edge`, `bsm_service_children`, `bsm_service_ifservices`, `bsm_service_applications`, `bsm_service_reductionkeys`, `bsm_reduce`, `bsm_map`, `bsm_service_attributes`) plus the core tables needed by the alarm and model entities. Extract these from the Liquibase changelogs or from an existing test schema file.

- [ ] **Step 2: Create test schema SQL**

Create `core/daemon-boot-bsmd/src/test/resources/schema/bsm-schema.sql` containing the DDL for all required tables. Extract from `core/schema/src/main/liquibase/` changelogs or adapt from existing daemon-boot test schemas.

- [ ] **Step 3: Run the integration test**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn verify -pl . -Dit.test=BsmdApplicationIT`
Expected: PASS — context loads successfully with all beans wired.

- [ ] **Step 4: Fix any wiring issues and iterate**

If the context fails to load:
- Check for missing bean definitions (Spring will log which bean can't be satisfied)
- Check for entity scanning issues (verify `PersistenceManagedTypes` lists all entities)
- Check for Hibernate mapping errors (column name mismatches, missing sequences)

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-bsmd/src/test/
git commit -m "test: add BsmdApplicationIT context loads integration test"
```
