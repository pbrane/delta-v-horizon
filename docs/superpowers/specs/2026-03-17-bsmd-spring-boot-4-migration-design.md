# BSMd Spring Boot 4.0.3 Migration Design

## Summary

Migrate the Business Service Monitor daemon (BSMd) from its Karaf/OSGi container (`core/daemon-loader-bsmd`) to a standalone Spring Boot 4.0.3 fat JAR (`core/daemon-boot-bsmd`), following the established pattern from Alarmd, Trapd, Syslogd, EventTranslator, and Discovery.

BSMd is the first daemon with its own entity model (17 JPA entity classes + 3 interfaces = 20 files in `features/bsm/persistence/api/`) that must be converted from `javax.persistence` to `jakarta.persistence` for Hibernate 7 compatibility.

## Approach: Option C — Jakarta Entities + Reused Service Layer

The BSM codebase has clean architectural layers:

```
Entities (javax → jakarta) → DAOs (new JPA impls) → Service interfaces → Service impls (unchanged)
```

We create Jakarta versions of all BSM entities and new JPA DAOs, but **reuse the existing service classes** (`BusinessServiceManagerImpl`, `DefaultBusinessServiceStateMachine`) unchanged. These service classes depend on DAO interfaces and domain model interfaces, not on `javax.persistence` directly.

**Note:** `BusinessServiceManagerImpl` uses `org.hibernate.Hibernate.initialize()` in two places (for lazy-loading `OnmsApplication.monitoredServices`). This resolves correctly against Hibernate 7 since `org.hibernate.Hibernate` exists at the same package path in both Hibernate 3.x and 7.x.

### Bsmd: Convert to Constructor Injection

As part of this migration, convert `Bsmd` from `@Autowired` field injection to constructor injection. The 6 fields to convert:
- `EventIpcManager m_eventIpcManager`
- `MessageBus m_messageBus` (optional — keep `@Nullable` or use `Optional`)
- `EventConfDao m_eventConfDao`
- `TransactionTemplate m_template`
- `BusinessServiceStateMachine m_stateMachine`
- `BusinessServiceManager m_manager`

`BusinessServiceManagerImpl` retains `@Autowired` field injection for now since it lives in `features/bsm/service/impl/` and is shared with the Karaf deployment.

### Why not other approaches

- **Option A (rewrite service layer too)**: No benefit — the service layer works through interfaces and doesn't touch `javax.persistence`.
- **Option B (JDBC/no ORM)**: Diverges from the entity pattern established across the project; can't reuse `BusinessServiceManagerImpl` which expects DAO interfaces returning entity objects.

## Module Structure

### New Module: `core/daemon-boot-bsmd/`

```
core/daemon-boot-bsmd/
├── pom.xml
└── src/main/java/org/opennms/netmgt/bsm/
    ├── boot/
    │   ├── BsmdApplication.java          # @SpringBootApplication
    │   └── BsmdConfiguration.java        # Bean wiring
    ├── persistence/
    │   └── api/                          # Jakarta BSM entities (same package as legacy)
    │       ├── BusinessServiceEntity.java
    │       ├── BusinessServiceEdgeEntity.java
    │       ├── BusinessServiceChildEdgeEntity.java
    │       ├── IPServiceEdgeEntity.java
    │       ├── ApplicationEdgeEntity.java
    │       ├── SingleReductionKeyEdgeEntity.java
    │       ├── EdgeEntity.java                    # Interface (no JPA annotations)
    │       ├── EdgeEntityVisitor.java             # Interface (no JPA annotations)
    │       └── functions/
    │           ├── map/
    │           │   ├── AbstractMapFunctionEntity.java
    │           │   ├── IdentityEntity.java
    │           │   ├── IgnoreEntity.java
    │           │   ├── DecreaseEntity.java
    │           │   ├── IncreaseEntity.java
    │           │   ├── SetToEntity.java
    │           │   └── MapFunctionEntityVisitor.java    # Interface
    │           └── reduce/
    │               ├── AbstractReductionFunctionEntity.java
    │               ├── HighestSeverityEntity.java
    │               ├── HighestSeverityAboveEntity.java
    │               ├── ThresholdEntity.java
    │               ├── ExponentialPropagationEntity.java
    │               └── ReductionFunctionEntityVisitor.java  # Interface
    └── dao/
        ├── BusinessServiceDaoJpa.java
        ├── BusinessServiceEdgeDaoJpa.java
        ├── MapFunctionDaoJpa.java
        └── ReductionFunctionDaoJpa.java
```

### Addition to `core/opennms-model-jakarta/`

Add `OnmsApplication.java` — Jakarta version of the core model entity. It has:
- `@ManyToMany(mappedBy="applications")` relationship to `OnmsMonitoredService`
- `@ManyToMany` relationship to `OnmsMonitoringLocation` via join table `application_perspective_location_map`
- Both referenced entities already exist in `model-jakarta`

### Registration in `core/pom.xml`

Add `<module>daemon-boot-bsmd</module>` to the reactor.

## Entity Migration

All 17 BSM entity classes receive a mechanical `javax.persistence → jakarta.persistence` import conversion. The entity classes are placed in the `daemon-boot-bsmd` module under the **same package** (`org.opennms.netmgt.bsm.persistence.api`) so the service layer code that references these types by name works unchanged.

### Cross-references to core model

Two BSM entities reference core model classes:

| BSM Entity | Core Model Reference | Resolution |
|---|---|---|
| `IPServiceEdgeEntity` | `OnmsMonitoredService` | Use Jakarta version from `model-jakarta` |
| `ApplicationEdgeEntity` | `OnmsApplication` | Use new Jakarta version added to `model-jakarta` |

`SetToEntity` references `OnmsSeverity` which is an enum (no JPA annotations) — works as-is.

### Entity list for PersistenceManagedTypes

```java
PersistenceManagedTypes.of(
    // Core entities (from model-jakarta)
    OnmsAlarm.class.getName(),
    OnmsNode.class.getName(),
    OnmsIpInterface.class.getName(),
    OnmsMonitoredService.class.getName(),
    OnmsServiceType.class.getName(),
    OnmsMonitoringLocation.class.getName(),
    OnmsMonitoringSystem.class.getName(),
    OnmsDistPoller.class.getName(),
    OnmsCategory.class.getName(),
    OnmsSnmpInterface.class.getName(),
    OnmsApplication.class.getName(),
    AlarmAssociation.class.getName(),
    OnmsMemo.class.getName(),
    OnmsReductionKeyMemo.class.getName(),
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
)
```

## DAO Layer

Four JPA DAOs extending `AbstractDaoJpa` from `daemon-common`, implementing the existing DAO interfaces from `features/bsm/persistence/api/`:

| New DAO | Extends | Implements | Custom Methods |
|---|---|---|---|
| `BusinessServiceDaoJpa` | `AbstractDaoJpa<BusinessServiceEntity, Long>` | `BusinessServiceDao` | `findParents()` via HQL |
| `BusinessServiceEdgeDaoJpa` | `AbstractDaoJpa<BusinessServiceEdgeEntity, Long>` | `BusinessServiceEdgeDao` | None |
| `MapFunctionDaoJpa` | `AbstractDaoJpa<AbstractMapFunctionEntity, Long>` | `MapFunctionDao` | None |
| `ReductionFunctionDaoJpa` | `AbstractDaoJpa<AbstractReductionFunctionEntity, Long>` | `ReductionFunctionDao` | None |

### BusinessServiceDaoJpa.findParents()

The legacy `BusinessServiceDaoImpl.findParents()` uses HQL:
```sql
select edge from BusinessServiceEdgeEntity edge
where type(edge) = BusinessServiceChildEdgeEntity and edge.child.id = :childId
```

The JPA version uses `entityManager().createQuery()` directly (not the inherited `find()` helper) because the HQL returns `BusinessServiceEdgeEntity` objects, not `BusinessServiceEntity`. The method collects the parent business service from each edge's `getBusinessService()` and returns `Set<BusinessServiceEntity>`.

### findMatching() not needed

`AbstractDaoJpa.findMatching()` throws `UnsupportedOperationException`. BSMd only calls `findAll()` via `BusinessServiceManager.getAllBusinessServices()`. The complex `findMatching()` override in the legacy `BusinessServiceDaoImpl` (for paginated Vaadin admin UI) is not needed in the daemon context.

## Service Layer (Reused Unchanged)

| Class | Source Module | Role |
|---|---|---|
| `BusinessServiceManagerImpl` | `features/bsm/service/impl` | Loads BS config from DB via DAOs, converts to domain model |
| `DefaultBusinessServiceStateMachine` | `features/bsm/service/impl` | In-memory graph computation, alarm→status propagation |

### BusinessServiceManagerImpl @Autowired dependencies

`BusinessServiceManagerImpl` has 9 `@Autowired` fields. BSMd only calls `getAllBusinessServices()` at runtime (which uses `businessServiceDao.findAll()`), but Spring requires all `@Autowired` fields to be satisfied at startup:

| Field | Type | Provided By |
|---|---|---|
| `businessServiceDao` | `BusinessServiceDao` | `BusinessServiceDaoJpa` (new) |
| `edgeDao` | `BusinessServiceEdgeDao` | `BusinessServiceEdgeDaoJpa` (new) |
| `mapFunctionDao` | `MapFunctionDao` | `MapFunctionDaoJpa` (new) |
| `reductionFunctionDao` | `ReductionFunctionDao` | `ReductionFunctionDaoJpa` (new) |
| `businessServiceStateMachine` | `BusinessServiceStateMachine` | `DefaultBusinessServiceStateMachine` bean |
| `monitoredServiceDao` | `MonitoredServiceDao` | New `MonitoredServiceDaoJpa` in `model-jakarta` |
| `nodeDao` | `NodeDao` | Existing `NodeDaoJpa` in `model-jakarta` |
| `applicationDao` | `ApplicationDao` | New `ApplicationDaoJpa` in `model-jakarta` |
| `eventForwarder` | `EventForwarder` | `KafkaEventForwarder` from `daemon-common` |

**New DAOs needed in `model-jakarta`:** `MonitoredServiceDaoJpa` and `ApplicationDaoJpa`. These are primarily needed to satisfy Spring autowiring; BSMd's core path (`getAllBusinessServices()`) doesn't call them, but `getAllIpServices()`, `getAllApplications()`, and `getNodeById()` do use them and could be called by the state machine or service layer during graph building.

## Alarm Lifecycle Integration

`AlarmLifecycleListenerManager` (from `opennms-alarmd`) polls `AlarmDao.findAll()` on a configurable interval (default 2 minutes, overridable via `org.opennms.alarms.snapshot.sync.ms`). It pushes alarm snapshots to all registered `AlarmLifecycleListener` instances.

Configuration:
1. Wire `AlarmLifecycleListenerManager` as a `@Bean`
2. Register `Bsmd` as an `AlarmLifecycleListener` programmatically
3. `AlarmDaoJpa` from `model-jakarta` provides alarm data

## Event Infrastructure

### Outbound events (BSMd → Kafka)

`KafkaEventForwarder` (from `daemon-common`'s `KafkaEventTransportConfiguration`) handles:
- Eventconf enrichment via `enrichWithEventConf()` — applies severity and alarm-data from `EventConfDao`
- Topic routing via `EventClassifier` — BSM events are fault events, routed to the fault topic
- No full `EventUtil` needed — BSM events carry explicit params and don't need node label resolution

### Inbound events (Kafka → BSMd)

`KafkaEventSubscriptionService` (from `daemon-common`) polls the fault Kafka topic and dispatches to registered listeners. `AnnotationBasedEventListenerAdapter` bridges Bsmd's `@EventHandler` annotations to this subscription service.

### EventConfDao

`DefaultEventConfDao` loaded via `EventConfInitializer` from the database (same pattern as the Karaf loader context). Required for:
- Bsmd's reduction key verification in `start()`
- KafkaEventForwarder's eventconf enrichment of outbound events

## New Infrastructure: SpringServiceDaemonSmartLifecycle

`Bsmd` implements `SpringServiceDaemon` (which extends `InitializingBean` + `DisposableBean`), NOT `AbstractServiceDaemon`. The existing `DaemonSmartLifecycle` in `daemon-common` only accepts `AbstractServiceDaemon`.

**Solution:** Add `SpringServiceDaemonSmartLifecycle` to `daemon-common`. This adapter:
- On `start()`: calls `afterPropertiesSet()` then `start()` on the wrapped `SpringServiceDaemon`
- On `stop()`: calls `destroy()` on the wrapped `SpringServiceDaemon`
- Same phase (`Integer.MAX_VALUE`) as `DaemonSmartLifecycle`

This is a reusable addition — any future daemon that implements `SpringServiceDaemon` instead of `AbstractServiceDaemon` can use it.

## BsmdConfiguration Bean Wiring

```
BsmdConfiguration
├── physicalNamingStrategy()          — PhysicalNamingStrategyStandardImpl (camelCase tables)
├── persistenceManagedTypes()         — All core + BSM Jakarta entities
├── sessionUtils(txManager)           — TransactionTemplate wrapper
├── transactionOperations(txManager)  — TransactionTemplate for @Autowired fields
│
├── businessServiceDao()              — BusinessServiceDaoJpa
├── businessServiceEdgeDao()          — BusinessServiceEdgeDaoJpa
├── mapFunctionDao()                  — MapFunctionDaoJpa
├── reductionFunctionDao()            — ReductionFunctionDaoJpa
│
├── businessServiceStateMachine()     — DefaultBusinessServiceStateMachine
├── businessServiceManager()          — BusinessServiceManagerImpl (DAOs injected via @Autowired)
│
├── alarmLifecycleListenerManager()   — AlarmLifecycleListenerManager
│
├── eventConfDao()                    — DefaultEventConfDao (loaded via EventConfInitializer)
│
├── bsmd(stateMachine, manager, ...)  — Bsmd daemon instance
├── bsmdEventListenerAdapter(...)     — AnnotationBasedEventListenerAdapter
├── bsmdLifecycle(bsmd)              — SpringServiceDaemonSmartLifecycle (NEW)
```

## BsmdApplication

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.bsm.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class BsmdApplication {
    public static void main(String[] args) {
        SpringApplication.run(BsmdApplication.class, args);
    }
}
```

## application.yml

Same template as other daemon-boot modules:
- PostgreSQL datasource with HikariCP
- Hibernate dialect, `ddl-auto: none`, `PhysicalNamingStrategyStandardImpl`
- Actuator health endpoint on port 8080
- `opennms.home`, Kafka bootstrap servers, event topic, consumer group
- Logging levels: `org.opennms: INFO`, `org.hibernate: WARN`

## Maven Dependencies

Key dependencies for `daemon-boot-bsmd` (following `daemon-boot-alarmd` pattern):

| Dependency | Purpose |
|---|---|
| `org.opennms.core:daemon-common` | Shared Spring Boot daemon infrastructure |
| `org.opennms.core:model-jakarta` | Jakarta core entities + JPA DAOs |
| `org.opennms.features.bsm:service.api` | BSM service interfaces |
| `org.opennms.features.bsm:service.impl` | BusinessServiceManagerImpl, DefaultBusinessServiceStateMachine |
| `org.opennms:opennms-alarmd` | AlarmLifecycleListenerManager |
| `org.opennms:opennms-alarm-api` | AlarmLifecycleListener interface |
| `javax.persistence:javax.persistence-api:2.2` | Runtime — prevents CNFE from legacy classes on classpath |
| `jakarta.xml.bind:jakarta.xml.bind-api` | Hibernate 7 XML mapping support |
| `jakarta.inject:jakarta.inject-api` | Hibernate 7 requirement |
| `javax.xml.bind:jaxb-api:2.3.1` | Runtime — legacy Event XML unmarshalling |
| `org.opennms:opennms-dao-api` | `ReductionKeyHelper` used by Jakarta BSM edge entities, plus `MonitoredServiceDao`, `NodeDao`, `ApplicationDao` interfaces |
| Spring Boot starters | `spring-boot-starter-web`, `spring-boot-starter-test` |

Standard exclusions: ServiceMix Spring 4.2.x bundles, old Hibernate 3.x, old SLF4J 1.7.x, Karaf/OSGi logging, `opennms-config-model`, `commons-logging`.

## What We Skip

- **MessageBus**: Bsmd handles `null` MessageBus gracefully (logs warning, falls back to event-based reload). Can be added later.
- **EventUtil**: Not needed — BSM events carry explicit params.
- **EventProxy**: Not needed — Bsmd uses `EventIpcManager.sendNow()` directly.
- **findMatching() in BusinessServiceDaoJpa**: Not called by BSMd.
- **AlarmEntityNotifier**: Not needed — BSMd is a consumer of alarm state, not a producer.

## Known Risks

- **Bsmd's scheduled executor in instance initializer**: `Bsmd` creates a `ScheduledExecutorService` in an instance initializer block (lines 118-132) that starts immediately upon construction, before `afterPropertiesSet()` or `start()`. It references `m_eventIpcManager` which may be null at that point. The reload check (`reloadConfigurationAt > 0L`) guards against premature execution, but this should be verified during integration testing.

## Testing Strategy

- **Unit tests**: Context loads test verifying Spring Boot application starts with all beans wired
- **Integration tests**: Testcontainers PostgreSQL + Kafka verifying:
  - BSM config loaded from database via JPA DAOs
  - Alarm snapshot triggers state machine evaluation
  - BSM status change events published to Kafka fault topic
