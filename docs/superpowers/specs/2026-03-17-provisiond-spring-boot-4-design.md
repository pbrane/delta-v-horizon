# Provisiond Spring Boot 4 Migration Design

**Date:** 2026-03-17
**Status:** Draft
**Predecessor:** [Shared Infrastructure](2026-03-16-provisiond-shared-infrastructure-design.md), [Discovery](2026-03-15-discovery-spring-boot-4-design.md)
**Tier:** 5 (most complex daemon migrated to date)

## Summary

Migrate Provisiond to a Spring Boot 4.0.3 microservice. Provisiond orchestrates the full node lifecycle — requisition imports, node scanning (SNMP walks, DNS lookups, service detection), interface/service creation, and scheduled rescans — all via Minion Kafka RPC. This is the most complex daemon migration due to 3× RPC clients, a custom activity lifecycle framework, Quartz-based import scheduling, a deep DAO surface (12 DAOs), and bundled SNMP provisioning adapters.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Configuration style | Single `ProvisiondBootConfiguration` class | Matches established pattern (Discovery, Alarmd). Split into layered configs later if needed. |
| Injection style | **Modernize to constructor injection** | Karaf deployment path is being retired. Refactor `Provisioner`, `DefaultProvisionService`, `DefaultPluginRegistry`, `ImportScheduler`, `ImportJobFactory` to constructor injection. Remove `BeanUtils.assertAutowiring()` calls. |
| DAO layer | Shared JPA DAOs in `jakarta.dao` package | Extend the package Alarmd established. 12 DAOs needed; many are reusable by future daemons. |
| JPA / Hibernate | Enabled (Alarmd pattern) | `DefaultProvisionService` uses `@Transactional`, ORM operations (`save()`, `flush()`, `findByNodeIdAndIpAddress()`). Requires full JPA stack. |
| Entity classes | Explicit `PersistenceManagedTypes` | Same approach as Alarmd — list Jakarta-compatible entities explicitly to avoid scanning javax.persistence classes. |
| Quartz | Spring Boot Quartz auto-config | Add `spring-boot-starter-quartz`. No other daemon uses Quartz yet; idiomatic Spring Boot path. |
| RPC clients | 3× via `KafkaRpcClientConfiguration` | `LocationAwareSnmpClient`, `LocationAwareDetectorClient`, `LocationAwareDnsLookupClient` — all use shared RPC infrastructure from `daemon-common`. |
| Provisioning adapters | Bundle 3 SNMP adapters | SnmpHardwareInventory, SnmpAsset, SnmpMetadata — all Minion-compliant via `LocationAwareSnmpClient`. |
| Deferred adapters | Dns, ReverseDns, GeoIp, Geolocation | Make direct network/file I/O — violate Minion-mandatory architecture. Need RPC wrappers before migration. |
| Excluded adapters | WsMan, Puppet | WsMan makes direct calls; Puppet is a non-functional stub. |
| Policies / Adapters | Empty collections for policies; 3 SNMP adapters for adapter list | `DefaultPluginRegistry` wired with `Collections.emptyList()` for `NodePolicy`, `IpInterfacePolicy`, `SnmpInterfacePolicy`. |
| daemon-loader-provisiond | **Delete** | Clean break. Spring Boot replaces the Karaf deployment path entirely. Classes needed from this module (`InlineProvisiondConfigDao`, `NoOpSnmpProfileMapper`, `SnmpPeerFactoryInitializer`, `QualifiedEventForwarder`) are moved to `daemon-boot-provisiond` or `daemon-common` before deletion. |
| DistPollerDao | Use `JdbcDistPollerDao` from `daemon-common` | Provisiond only calls `distPollerDao.whoami()` (via `KafkaRpcClientConfiguration`). The JDBC implementation is sufficient and avoids conflict with JPA auto-config. Declare as explicit `@Bean` to prevent JPA auto-discovery from creating a competing bean. |

## Bean Graph

### Core Daemon Beans

```
ProvisiondBootConfiguration
├── Provisioner (daemon)
│   ├── ProvisionService (DefaultProvisionService)
│   ├── EventForwarder (QualifiedEventForwarder wrapping KafkaEventForwarder)
│   ├── LifeCycleRepository (DefaultLifeCycleRepository)
│   │   └── TaskCoordinator (DefaultTaskCoordinator)
│   │       ├── importExecutor (ScheduledExecutorFactoryBean)
│   │       ├── scanExecutor (ScheduledExecutorFactoryBean)
│   │       └── writeExecutor (ScheduledExecutorFactoryBean)
│   ├── ScheduledExecutorService (PausibleScheduledThreadPoolExecutor)
│   ├── ImportScheduler
│   │   └── Quartz Scheduler (Spring Boot auto-config)
│   ├── CoreImportActivities
│   ├── TaskCoordinator
│   ├── SnmpAgentConfigFactory
│   ├── ProvisioningAdapterManager
│   ├── MonitoringSystemDao
│   ├── TracerRegistry (NoOp, from daemon-common)
│   └── MonitorHolder
│
├── DefaultProvisionService
│   ├── MonitoringLocationDao (JPA)
│   ├── NodeDao (JPA)
│   ├── IpInterfaceDao (JPA)
│   ├── SnmpInterfaceDao (JPA)
│   ├── MonitoredServiceDao (JPA)
│   ├── ServiceTypeDao (JPA)
│   ├── CategoryDao (JPA)
│   ├── RequisitionedCategoryAssociationDao (JPA)
│   ├── EventForwarder @Qualifier("transactionAware")
│   ├── ForeignSourceRepository @Qualifier("fastFused")
│   ├── ForeignSourceRepository @Qualifier("fastFilePending")
│   ├── PluginRegistry (DefaultPluginRegistry)
│   ├── PlatformTransactionManager (JPA auto-config)
│   ├── LocationAwareDetectorClient
│   ├── LocationAwareDnsLookupClient
│   ├── LocationAwareSnmpClient
│   └── SnmpProfileMapper (NoOp — moved from daemon-loader-provisiond)
│
├── DefaultPluginRegistry
│   ├── ServiceRegistry (DefaultServiceRegistry from core/soa)
│   ├── ApplicationContext (auto-injected by Spring)
│   ├── List<NodePolicy> (empty)
│   ├── List<IpInterfacePolicy> (empty)
│   ├── List<SnmpInterfacePolicy> (empty)
│   └── List<ProvisioningAdapter> (3 SNMP adapters)
│
├── Infrastructure Beans
│   ├── ServiceRegistry (DefaultServiceRegistry)
│   ├── QualifiedEventForwarder @Qualifier("transactionAware")
│   ├── DaemonSmartLifecycle (wraps Provisioner)
│   ├── DistPollerDao (JdbcDistPollerDao from daemon-common)
│   ├── InterfaceToNodeCache (JdbcInterfaceToNodeCache from daemon-common)
│   ├── JSR223ScriptCache (for policy scripts)
│   ├── SnmpPeerFactoryInitializer (moved from daemon-loader-provisiond)
│   ├── InlineProvisiondConfigDao (moved from daemon-loader-provisiond)
│   └── SessionUtils (TransactionTemplate-backed, same as Alarmd)
│
├── ForeignSourceRepository (6 instances)
│   ├── pendingForeignSourceRepository (FilesystemForeignSourceRepository)
│   ├── deployedForeignSourceRepository (FilesystemForeignSourceRepository)
│   ├── fastPendingForeignSourceRepository (FasterFilesystemForeignSourceRepository)
│   ├── fastDeployedForeignSourceRepository (FasterFilesystemForeignSourceRepository)
│   ├── fusedForeignSourceRepository (FusedForeignSourceRepository)
│   └── fastFusedForeignSourceRepository (FusedForeignSourceRepository)
│
├── RPC Clients (3)
│   ├── LocationAwareSnmpClientRpcImpl
│   ├── LocationAwareDetectorClientRpcImpl
│   │   └── DetectorClientRpcModule
│   └── LocationAwareDnsLookupClientRpcImpl
│       └── DnsLookupClientRpcModule (4-thread pool)
│
├── Event Listeners (2 + adapter listeners)
│   ├── AnnotationBasedEventListenerAdapter (daemon)
│   ├── AnnotationBasedEventListenerAdapter (adapterManager)
│   ├── AnnotationBasedEventListenerAdapter (snmpHardwareInventory — config reload)
│   ├── AnnotationBasedEventListenerAdapter (snmpAsset — config reload)
│   └── AnnotationBasedEventListenerAdapter (snmpMetadata — config reload)
│
└── SNMP Provisioning Adapters (3)
    ├── SnmpHardwareInventoryProvisioningAdapter
    │   ├── HwEntityDao (JPA)
    │   ├── HwEntityAttributeTypeDao (JPA)
    │   └── SnmpHwInventoryAdapterConfigDao (XML file)
    ├── SnmpAssetProvisioningAdapter
    │   └── SnmpAssetAdapterConfig (XML file)
    └── SnmpMetadataProvisioningAdapter
        └── SnmpMetadataConfigDao (XML file)
```

### Beans from daemon-common (auto-discovered via component scan)

- `DaemonDataSourceConfiguration` — HikariCP + Hibernate SessionFactory + JpaTransactionManager
- `KafkaEventTransportConfiguration` — KafkaEventForwarder, KafkaEventSubscriptionService, KafkaEventIpcManagerAdapter
- `KafkaRpcClientConfiguration` — KafkaRpcClientFactory (conditional on `opennms.rpc.kafka.enabled=true`)
- `DaemonProvisioningConfiguration` — NoOpEntityScopeProvider, LocalServiceDetectorRegistry
- `OpennmsHomeConfiguration` — bridges `opennms.home` to system property
- `SystemPropertyBridgePostProcessor` — bridges instance-id, tsid-node-id
- `EventConfEnrichmentService` — loads event conf from PostgreSQL, enriches events with alarm-data/severity

## Constructor Injection Refactoring

The following classes in `opennms-provisiond` will be refactored from `@Autowired` field/setter injection to constructor injection:

### Provisioner

**Before:** 7 setter-injected fields + 4 `@Autowired` fields
**After:** Single constructor with all dependencies:

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
    MonitorHolder monitorHolder
)
```

Remove: all setter methods, `@Autowired` annotations, `BeanUtils.assertAutowiring()` in `afterPropertiesSet()`.

### DefaultProvisionService

**Before:** 17 `@Autowired` fields (8 DAOs, 3 RPC clients, 2 qualified repositories, PluginRegistry, TransactionManager, SnmpProfileMapper) + `BeanUtils.assertAutowiring()` in `afterPropertiesSet()`
**After:** Single constructor with all dependencies. `afterPropertiesSet()` retains only the `RequisitionFileUtils.deleteAllSnapshots()` call and `HostnameResolver` initialization.

### DefaultPluginRegistry

**Before:** 5 `@Autowired` fields — 3 optional policy lists (`Set<NodePolicy>`, `Set<IpInterfacePolicy>`, `Set<SnmpInterfacePolicy>`), `ServiceRegistry`, and `ApplicationContext`
**After:** Single constructor. Optional policy sets default to empty collections via `Optional.ofNullable()` or `@Nullable` + null-check. `ServiceRegistry` and `ApplicationContext` are required parameters.

### ImportScheduler

**Before:** 3 `@Autowired` fields (ProvisiondConfigurationDao, MonitorHolder, EntityScopeProvider) + constructor taking Scheduler
**After:** Single constructor with all dependencies including Quartz `Scheduler`.

### ImportJobFactory

**Before:** 2 fields + setter injection (provisioner, entityScopeProvider)
**After:** Constructor injection for all dependencies.

## JPA DAO Implementations

New JPA DAO implementations to add to the shared `jakarta.dao` package, extending `AbstractDaoJpa`:

| DAO Interface | Entity | Status | Notes |
|---------------|--------|--------|-------|
| `NodeDao` | `OnmsNode` | **Exists** (expand) | Heavy — needs ~15-20 additional methods for Provisiond. Currently only implements `get()` for Alarmd. |
| `IpInterfaceDao` | `OnmsIpInterface` | **New** | Interface lookups by nodeId + IP, `findByNodeIdAndIpAddress()`, `save()` |
| `SnmpInterfaceDao` | `OnmsSnmpInterface` | **New** | SNMP interface persistence |
| `MonitoredServiceDao` | `OnmsMonitoredService` | **New** | Service detection results |
| `ServiceTypeDao` | `OnmsServiceType` | **Exists** (verify) | `findByName()`, create-if-necessary pattern |
| `CategoryDao` | `OnmsCategory` | **New** | `findByName()`, node categorization |
| `RequisitionedCategoryAssociationDao` | `RequisitionedCategoryAssociation` | **New** | Tracks requisition-assigned categories |
| `MonitoringLocationDao` | `OnmsMonitoringLocation` | **New** | `getDefaultLocation()`, `get()`, `save()` |
| `MonitoringSystemDao` | `OnmsMonitoringSystem` | **New** | `get()` — used by Provisioner to resolve monitoring system location |
| `ProvisiondConfigurationDao` | N/A (config file) | **Exists** (move) | Not JPA. Move `InlineProvisiondConfigDao` from daemon-loader-provisiond to daemon-boot-provisiond. Update paths from `/opt/sentinel` to `${opennms.home}`. |
| `HwEntityDao` | `OnmsHwEntity` | **New** | For SnmpHardwareInventory adapter |
| `HwEntityAttributeTypeDao` | `HwEntityAttributeType` | **New** | For SnmpHardwareInventory adapter |

**9 new DAOs, 2 existing DAOs to expand, 1 config DAO to relocate.**

**Strategy:** Implement only the methods Provisiond actually calls (verified by reading the source), not the full DAO interface. Unimplemented methods throw `UnsupportedOperationException` following the `AbstractDaoJpa` pattern. This keeps the implementation tractable and avoids speculative work.

### PersistenceManagedTypes

Extend Alarmd's entity list with Provisiond-specific entities:

```java
@Bean
public PersistenceManagedTypes persistenceManagedTypes() {
    return PersistenceManagedTypes.of(
        // From Alarmd
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
        // Provisiond additions
        RequisitionedCategoryAssociation.class.getName(),
        OnmsHwEntity.class.getName(),
        OnmsHwEntityAttribute.class.getName(), // @Entity — mapped collection in OnmsHwEntity
        HwEntityAttributeType.class.getName(),
        OnmsHwEntityAlias.class.getName()      // @Entity — mapped collection in OnmsHwEntity
    );
}
```

**Notes:**
- `OnmsHwEntityAlias` is required because `OnmsHwEntity` has a mapped `SortedSet<OnmsHwEntityAlias>` collection. Without it, Hibernate 7 fails to resolve the association.
- `PathElement` and `OnmsMetaData` are `@Embeddable`, NOT `@Entity`. Hibernate auto-discovers embeddables referenced by registered entities. They must NOT be listed here.
- `OnmsSeverity` is an enum used as `@Enumerated` on several entities. Hibernate 7 handles enum types without explicit registration, so it is intentionally omitted.

## Classes Relocated from daemon-loader-provisiond

Before deleting `core/daemon-loader-provisiond/`, the following classes must be relocated:

| Class | Current Location | New Location | Rationale |
|-------|-----------------|--------------|-----------|
| `InlineProvisiondConfigDao` | `core/daemon-loader-provisiond` | `core/daemon-boot-provisiond` | Provisiond-specific; reads `provisiond-configuration.xml`. Update default paths from `/opt/sentinel` to `${opennms.home}`. **Bug fix during move:** getter methods (`getForeignSourceDir()`, `getRequisitionDir()`, `getImportThreads()`, etc.) currently return hardcoded defaults instead of delegating to the loaded config object — fix to return `config.getXxx()` values. |
| `NoOpSnmpProfileMapper` | `core/daemon-loader-provisiond` | `core/daemon-boot-provisiond` | Provisiond-specific for now. Could move to `daemon-common` later if other daemons need it, but must NOT be component-scanned into daemons that don't use SNMP. |
| `SnmpPeerFactoryInitializer` | `core/daemon-loader-provisiond` | `core/daemon-boot-provisiond` | Calls `SnmpPeerFactory.init()` which requires `snmp-config.xml`. Must NOT be in `daemon-common` — would break Alarmd, Trapd, Syslogd, EventTranslator which don't ship `snmp-config.xml`. Discovery doesn't use SNMP either. |
| `QualifiedEventForwarder` | `core/daemon-loader-provisiond` | `core/daemon-boot-provisiond` | Wraps `EventForwarder` with `@Qualifier("transactionAware")`. Provisiond-specific need; in standalone Spring Boot container, events go straight to Kafka — no JPA transaction deferral needed. |

## transactionAware EventForwarder

`DefaultProvisionService` requires `@Qualifier("transactionAware") EventForwarder`. The `KafkaEventTransportConfiguration` in `daemon-common` does NOT produce a `transactionAware`-qualified bean. Solution: `QualifiedEventForwarder` — a simple delegating wrapper that applies the `@Qualifier("transactionAware")` annotation. In the Spring Boot container, this delegates directly to the Kafka event forwarder (no transaction deferral needed).

```java
@Bean
@Qualifier("transactionAware")
public EventForwarder transactionAwareEventForwarder(EventForwarder eventForwarder) {
    return new QualifiedEventForwarder(eventForwarder);
}
```

## ServiceRegistry

`DefaultPluginRegistry` requires `ServiceRegistry` (from `core/soa`) to register policy extensions. In the Karaf context, this came from OSGi. In Spring Boot, create a `DefaultServiceRegistry` bean:

```java
@Bean
public ServiceRegistry serviceRegistry() {
    return new DefaultServiceRegistry();
}
```

This requires adding `core/soa` as a dependency in the POM (with ServiceMix exclusions).

## ForeignSourceRepository Beans

Six filesystem-based repository instances. Paths use `${opennms.home}` (resolved at runtime via `OpennmsHomeConfiguration`), NOT the legacy `importer.requisition.dir` / `importer.foreign-source.dir` properties. This is a deliberate change from the daemon-loader XML which used those properties with `/opt/sentinel` defaults.

```java
@Bean @Qualifier("filePending")
public ForeignSourceRepository pendingForeignSourceRepository(
        @Value("${opennms.home:/opt/deltav}") String opennmsHome) {
    var repo = new FilesystemForeignSourceRepository();
    repo.setRequisitionPath(opennmsHome + "/etc/imports/pending");
    repo.setForeignSourcePath(opennmsHome + "/etc/foreign-sources/pending");
    return repo;
}

@Bean @Qualifier("fileDeployed")
public ForeignSourceRepository deployedForeignSourceRepository(...) { ... }

@Bean @Qualifier("fastFilePending")
public ForeignSourceRepository fastPendingForeignSourceRepository(...) { ... }

@Bean @Qualifier("fastFileDeployed")
public ForeignSourceRepository fastDeployedForeignSourceRepository(...) { ... }

@Bean @Qualifier("fused")
public ForeignSourceRepository fusedForeignSourceRepository(...) { ... }

@Bean @Qualifier("fastFused")
public ForeignSourceRepository fastFusedForeignSourceRepository(...) { ... }
```

## ProvisioningAdapterManager and Adapter Registration

`ProvisioningAdapterManager` uses setter injection for `pluginRegistry` and `eventForwarder` — these must be set explicitly in the `@Bean` method:

```java
@Bean
public ProvisioningAdapterManager adapterManager(
        DefaultPluginRegistry pluginRegistry,
        EventForwarder eventForwarder) {
    var manager = new ProvisioningAdapterManager();
    manager.setPluginRegistry(pluginRegistry);
    manager.setEventForwarder(eventForwarder);
    return manager;
}
```

**Adapter registration mechanism:** `DefaultPluginRegistry.afterPropertiesSet()` calls `addAllExtensions(ApplicationContext, ...)` which discovers all beans of type `ProvisioningAdapter` in the Spring `ApplicationContext` and registers them in the `ServiceRegistry`. The 3 SNMP adapter `@Bean` methods make them discoverable. `ProvisioningAdapterManager.afterPropertiesSet()` then calls `pluginRegistry.getAllPlugins(ProvisioningAdapter.class)` to find them. No manual registration needed — the `ApplicationContext` scan handles it.

## TransactionTemplate

Multiple beans need `TransactionTemplate` — adapters (via `SimplerQueuedProvisioningAdapter.setTemplate()`), and potentially `DefaultProvisionService` internals. Provide it as an explicit `@Bean`:

```java
@Bean
public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
    return new TransactionTemplate(txManager);
}
```

## SNMP Provisioning Adapters

Three SNMP-based adapters bundled as direct dependencies. Each needs:
1. A `@Bean` declaration wiring its dependencies via setter injection (legacy classes, not refactored as part of this migration)
2. An adapter-specific config DAO bean reading XML from `${opennms.home}/etc/`
3. An `AnnotationBasedEventListenerAdapter` for config reload events

```java
// Example: SnmpMetadataProvisioningAdapter
@Bean
public SnmpMetadataConfigDao snmpMetadataConfigDao() {
    var dao = new SnmpMetadataConfigDao();
    dao.setConfigResource("file:" + opennmsHome + "/etc/snmp-metadata-adapter-configuration.xml");
    return dao;
}

@Bean
public SnmpMetadataProvisioningAdapter snmpMetadataProvisioningAdapter() {
    var adapter = new SnmpMetadataProvisioningAdapter();
    adapter.setNodeDao(nodeDao);
    adapter.setSnmpConfigDao(snmpPeerFactory);
    adapter.setLocationAwareSnmpClient(locationAwareSnmpClient);
    adapter.setEventForwarder(eventForwarder);
    adapter.setSnmpMetadataAdapterConfigDao(snmpMetadataConfigDao);
    adapter.setTemplate(transactionTemplate);
    return adapter;
}

@Bean
public AnnotationBasedEventListenerAdapter snmpMetadataEventListenerAdapter() {
    var adapter = new AnnotationBasedEventListenerAdapter();
    adapter.setAnnotatedListener(snmpMetadataProvisioningAdapter);
    adapter.setEventSubscriptionService(eventSubscriptionService);
    return adapter;
}
```

## Application Class

```java
@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.netmgt.model.jakarta.dao",   // JPA DAO @Repository classes
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

## application.yml

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10    # Higher than other daemons — Provisiond is DAO-heavy
      minimum-idle: 3

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

  quartz:
    job-store-type: memory       # In-memory, not JDBC — matches existing behavior
    scheduler-name: provisiond

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      show-details: always

opennms:
  home: ${OPENNMS_HOME:/opt/deltav}
  instance:
    id: ${OPENNMS_INSTANCE_ID:OpenNMS}
  tsid:
    node-id: ${OPENNMS_TSID_NODE_ID:25}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-provisiond}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
  rpc:
    kafka:
      enabled: ${RPC_KAFKA_ENABLED:true}         # Provisiond always needs RPC
      bootstrap-servers: ${RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
      force-remote: ${RPC_KAFKA_FORCE_REMOTE:true}

logging:
  level:
    org.opennms: INFO
    org.opennms.netmgt.provision: DEBUG
```

## Docker / Deployment

### Dockerfile.daemon changes

Add the Provisiond fat JAR:
```dockerfile
COPY staging/daemon/daemon-boot-provisiond.jar /opt/daemon-boot-provisiond.jar
```

### Config overlay

The following config files must be present in `${OPENNMS_HOME}/etc/`:
- `provisiond-configuration.xml` — import schedules, thread pool sizes
- `imports/` + `foreign-sources/` — requisition and foreign source directories
- `snmp-config.xml` — SNMP peer configuration
- `snmp-hardware-inventory-adapter-configuration.xml` — HW inventory adapter config
- `snmp-asset-adapter-configuration.xml` — SNMP asset adapter config (factory pattern reads from default location)
- `snmp-metadata-adapter-configuration.xml` — SNMP metadata adapter config

### TSID Node ID

Provisiond uses TSID node-id **25** (following Discovery at 24).

## Deletion

### core/daemon-loader-provisiond/

Delete the entire module after relocating the 4 classes listed in "Classes Relocated from daemon-loader-provisiond". Remove from `core/pom.xml` reactor. The Spring Boot module replaces it completely.

## Deferred Work

| Item | Reason | Tracked |
|------|--------|---------|
| Dns provisioning adapter | Direct `SimpleResolver` calls — needs Minion RPC wrapper | Design note |
| ReverseDns provisioning adapter | Direct `SimpleResolver` calls — needs Minion RPC wrapper | Design note |
| GeoIp provisioning adapter | Reads local MaxMind .mmdb file — needs Minion RPC wrapper | Design note |
| Geolocation provisioning adapter | SPI-based, local lookups — needs Minion RPC wrapper | Design note |
| WsMan provisioning adapter | Direct WS-Man calls — needs Minion RPC wrapper; also excluded by scope | Design note |
| Puppet provisioning adapter | Non-functional stub — excluded by scope | Design note |
| Real EntityScopeProvider (MATE) | NoOp for now; Provisiond's `ImportJob.interpolate()` has try/catch fallback | Design note |
| NodePolicy / IpInterfacePolicy / SnmpInterfacePolicy providers | Empty collections; need non-OSGi provider mechanism | Design note |
| Split configuration classes (Approach B) | Monolithic configuration first; split if it becomes unwieldy | Design note |

## Testing Strategy

1. **Compilation:** `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-provisiond -am install`
2. **Unit tests:** `./compile.pl --projects :org.opennms.core.daemon-boot-provisiond,:opennms-provisiond -am verify`
3. **Constructor injection:** All refactored classes must compile and pass existing unit tests in `opennms-provisiond`
4. **JPA DAO tests:** 9 new DAOs + 2 expanded DAOs tested against Testcontainers PostgreSQL. Each DAO test verifies the methods Provisiond actually calls.
5. **Dependency tree:** `mvn dependency:tree` — verify no Spring 4.2.x ServiceMix artifacts
6. **Docker rebuild:** Verify Provisiond container starts, connects to Kafka and PostgreSQL
7. **E2E:** Requisition import → node creation → interface/service detection via Minion RPC
8. **Adapter E2E:** SNMP metadata/asset/hardware-inventory collection post-provisioning via Minion
9. **Regression:** All other daemons (Trapd, Syslogd, Alarmd, EventTranslator, Discovery) must still compile and start after shared `jakarta.dao` and `daemon-common` changes
