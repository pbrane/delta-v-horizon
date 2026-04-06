# Enlinkd Spring Boot 4 Migration Design

**Date:** 2026-03-23
**Status:** Draft
**Branch:** `feat/pollerd-spring-boot`
**Daemon:** Enlinkd (Enhanced Link Discovery)
**Complexity:** Tier 4 (Events + Kafka RPC + JPA-heavy persistence)

## Context

Enlinkd is daemon 11 of 13 in the Spring Boot 4 migration. It discovers network topology links (CDP, LLDP, OSPF, ISIS, Bridge) via SNMP polling through Minion and writes link entities to PostgreSQL. It is self-contained in `features/enlinkd/` with no dependency on `opennms-services`.

**Current state:** 10 daemons migrated, 3 remain (Enlinkd, Collectd, Scriptd). All 44 E2E tests passing.

## Approach

**Full JPA Port (Approach A):** Port all 15 Enlinkd entities to `opennms-model-jakarta` with Jakarta Persistence annotations and `AttributeConverter`s. Replace `AbstractDaoHibernate` DAOs with `EntityManager`-based implementations extending `AbstractDaoJpa`. Reuse all existing collector, service, and updater modules unchanged. Wire the daemon via `@Bean` factory methods in a new `core/daemon-boot-enlinkd` module.

## Architecture

### Module Structure

New module: `core/daemon-boot-enlinkd`

Dependencies (reused unchanged):
- `features/enlinkd/daemon` — `EnhancedLinkd`, `EventProcessor`
- `features/enlinkd/service/api` + `service/impl` — 7 topology services + NodeTopologyService
- `features/enlinkd/adapters/*` — 6 collectors, 6 updaters, 1 discover, common
- `features/enlinkd/config` — `EnhancedLinkdConfigFactory`
- `features/enlinkd/api` — `ReloadableTopologyDaemon` interface

New/modified in `core/opennms-model-jakarta`:
- 15 Jakarta entity classes
- 15 `AttributeConverter` implementations (+ 1 abstract base)
- 18 JPA DAO implementations

Infrastructure from `core/daemon-common`:
- `AbstractDaoJpa` (base DAO)
- `DaemonSmartLifecycle` (lifecycle management)
- `KafkaRpcClientConfiguration` (SNMP RPC to Minion)
- `KafkaEventTransportConfiguration` (event forwarding)
- `DaemonDataSourceConfiguration` (PostgreSQL DataSource)

### Daemon Class

`EnhancedLinkd extends AbstractServiceDaemon implements ReloadableTopologyDaemon`

- Extends `AbstractServiceDaemon` — compatible with `DaemonSmartLifecycle`
- Field-injected `@Autowired` dependencies resolved by Spring context
- `LegacyScheduler` + `LegacyPriorityExecutor` manage SNMP collection threads
- Not modified — reused as-is

### Event Processing

`EventProcessor` with `@EventHandler` annotations handles:
- `NODE_ADDED_EVENT_UEI` → `addNode()`
- `NODE_DELETED_EVENT_UEI` → `deleteNode()`
- `NODE_GAINED_SERVICE_EVENT_UEI` → `execSingleSnmpCollection()` (if SNMP)
- `NODE_LOST_SERVICE_EVENT_UEI` → `suspendNodeCollection()` (if SNMP)
- `NODE_REGAINED_SERVICE_EVENT_UEI` → `wakeUpNodeCollection()` (if SNMP)
- `FORCE_RESCAN_EVENT_UEI` → `execSingleSnmpCollection()`

Wired via 0-arg `AnnotationBasedEventListenerAdapter` + setters (PerspectivePollerd pattern to avoid duplicate `afterPropertiesSet()` registration).

**Important:** `EventProcessor` also implements `MessageHandler` and has an `init()` method that subscribes to `MessageBus`. In the Spring Boot context:
- `MessageBus` is not provided — the `@Autowired(required = false)` field will be null
- `EventProcessor.init()` gracefully handles null `MessageBus` (logs a warning, continues)
- Do **NOT** call `EventProcessor.init()` as a bean init method — it's the MessageBus subscription path we're dropping
- The `@EventListener(name="enlinkd")` class-level annotation is inert when using explicit `@Bean` wiring — it's only processed by `AnnotationBasedEventListenerAdapter`, which we control via the 0-arg constructor

### What's Dropped

- **Runtime reload** — no `MessageBus` support, no `reloadConfig()`/`reloadTopology()` triggers. Spring Boot daemons restart for config changes.
- **`ReloadableTopologyDaemon` exposure** — interface still implemented at class level but not exposed as a bean. YAGNI.
- **Karaf shell commands** (`features/enlinkd/shell/`) — no equivalent in Spring Boot.
- **Topology generator** (`features/enlinkd/generator/`) — dev tooling, not runtime.

## Entity & Converter Port

### Entities (15 total)

All ported to `core/opennms-model-jakarta` under package `org.opennms.netmgt.enlinkd.model.jakarta`.

| Entity | Table | Notes |
|--------|-------|-------|
| CdpLink | cdpLink | CiscoNetworkProtocolType enum |
| CdpElement | cdpElement | CdpGlobalDeviceIdFormat enum |
| LldpLink | lldpLink | LldpChassisIdSubType, LldpPortIdSubType enums |
| LldpElement | lldpElement | LldpChassisIdSubType enum |
| OspfLink | ospfLink | InetAddress fields via converter |
| OspfElement | ospfElement | Status, TruthValue enums; InetAddress fields |
| OspfArea | ospfArea | ImportAsExtern enum; InetAddress fields |
| IsIsLink | isisLink | IsIsAdminState, IsIsISAdjNeighSysType, IsIsISAdjState enums |
| IsIsElement | isisElement | IsIsAdminState enum |
| BridgeBridgeLink | bridgeBridgeLink | — |
| BridgeMacLink | bridgeMacLink | BridgeMacLinkType enum |
| BridgeStpLink | bridgeStpLink | BridgeDot1dStpPortState, BridgeDot1dStpPortEnable enums |
| BridgeElement | bridgeElement | BridgeDot1dBaseType, BridgeDot1dStpProtocolSpecification enums |
| IpNetToMedia | ipNetToMedia | InetAddress fields |
| UserDefinedLink | userDefinedLink | Simple scalar fields |

### Per-Entity Changes (Mechanical)

- `javax.persistence.*` → `jakarta.persistence.*`
- `@Type(type="...UserType")` → `@Convert(converter=...Converter.class)`
- `@SequenceGenerator` gets `allocationSize=1` (Hibernate 7 defaults to 50)
- `@ManyToOne(fetch = FetchType.LAZY)` explicitly preserved on all `OnmsNode` references (JPA default is EAGER)
- `@Filter(name=AUTH_FILTER_NAME)` preserved for schema compatibility but **not enabled** in daemon EntityManager — Enlinkd is a writer daemon, not a REST endpoint

### Converter Architecture

Abstract base class eliminates boilerplate across all 15 enum converters:

```java
public abstract class IntegerEnumConverter<E> implements AttributeConverter<E, Integer> {
    private final Function<E, Integer> toDb;
    private final Function<Integer, E> fromDb;

    protected IntegerEnumConverter(Function<E, Integer> toDb, Function<Integer, E> fromDb) {
        this.toDb = toDb;
        this.fromDb = fromDb;
    }

    @Override
    public Integer convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : toDb.apply(attribute);
    }

    @Override
    public E convertToEntityAttribute(Integer dbValue) {
        return dbValue == null ? null : fromDb.apply(dbValue);
    }
}
```

Each concrete converter is a one-liner:

```java
@Converter
public class CiscoNetworkProtocolTypeConverter extends IntegerEnumConverter<CiscoNetworkProtocolType> {
    public CiscoNetworkProtocolTypeConverter() {
        super(CiscoNetworkProtocolType::getValue, CiscoNetworkProtocolType::get);
    }
}
```

Reuses existing `InetAddressConverter` from `opennms-model-jakarta` for `OspfLink`, `OspfElement`, `OspfArea`, `IpNetToMedia` InetAddress fields.

## JPA DAO Layer

### 18 DAO Implementations

All extend `AbstractDaoJpa<T, Integer>` from `daemon-common`. Located in `core/opennms-model-jakarta` under `org.opennms.netmgt.enlinkd.model.jakarta.dao`.

HQL → JPQL translation is nearly 1:1:
- Add explicit `SELECT` alias
- Positional parameters: `?` → `?1`, `?2`, etc.
- Bulk deletes: `getHibernateTemplate().bulkUpdate()` → `entityManager().createQuery().executeUpdate()`

Example (CdpLinkDaoJpa):

```java
@Repository
@Transactional
public class CdpLinkDaoJpa extends AbstractDaoJpa<CdpLink, Integer> implements CdpLinkDao {

    public CdpLinkDaoJpa() {
        super(CdpLink.class);
    }

    @Override
    public CdpLink get(Integer nodeId, Integer cdpCacheIfIndex, Integer cdpCacheDeviceIndex) {
        return findUnique(
            "SELECT c FROM CdpLink c WHERE c.node.id = ?1 AND c.cdpCacheIfIndex = ?2 AND c.cdpCacheDeviceIndex = ?3",
            nodeId, cdpCacheIfIndex, cdpCacheDeviceIndex);
    }

    @Override
    public List<CdpLink> findByNodeId(Integer nodeId) {
        return find("SELECT c FROM CdpLink c WHERE c.node.id = ?1", nodeId);
    }

    @Override
    public void deleteByNodeIdOlderThen(Integer nodeId, Date now) {
        entityManager().createQuery(
            "DELETE FROM CdpLink c WHERE c.node.id = ?1 AND c.cdpLinkLastPollTime < ?2")
            .setParameter(1, nodeId).setParameter(2, now).executeUpdate();
    }

    @Override
    public void deleteByNodeId(Integer nodeId) {
        entityManager().createQuery("DELETE FROM CdpLink c WHERE c.node.id = ?1")
            .setParameter(1, nodeId).executeUpdate();
    }
}
```

### NodeTopologyService — JPQL Constructor Projection

Replaces the legacy `NodeDao` Criteria query with a type-safe JPQL projection that selects only the 6 fields needed:

```java
@Override
public List<Node> findAllSnmpNode() {
    return entityManager().createQuery(
        "SELECT NEW org.opennms.netmgt.enlinkd.Node(" +
        "  n.id, n.label, i.ipAddress, n.sysObjectId, n.sysName, n.location.locationName" +
        ") FROM OnmsNode n JOIN n.ipInterfaces i " +
        "WHERE n.type = ?1 AND i.snmpPrimary = ?2",
        Node.class)
        .setParameter(1, NodeType.ACTIVE)
        .setParameter(2, PrimaryType.PRIMARY)
        .getResultList();
}
```

This avoids loading full `OnmsNode` entity graphs while staying within JPA's type system.

### Unused Interface Methods

Legacy `LinkDao` and `ElementDao` interfaces inherit ~20 methods from the Hibernate era. `AbstractDaoJpa` handles CRUD. Any remaining unused methods throw `UnsupportedOperationException` — consistent with the Alarmd pattern.

## Spring Boot Wiring

### Application Class

```java
@SpringBootApplication(
    scanBasePackages = {},
    exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
    }
)
@Import({
    DaemonDataSourceConfiguration.class,
    KafkaRpcClientConfiguration.class,
    KafkaEventTransportConfiguration.class,
    EnlinkdJpaConfiguration.class,
    EnlinkdDaemonConfiguration.class
})
public class EnlinkdBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnlinkdBootApplication.class, args);
    }
}
```

### EnlinkdJpaConfiguration

- `PhysicalNamingStrategyStandardImpl` bean (preserve camelCase table names)
- `PersistenceManagedTypes` bean listing all 15 Enlinkd entities + core entities (`OnmsNode`, `OnmsIpInterface`, `OnmsMonitoringLocation`, `OnmsDistPoller`, `OnmsMonitoringSystem`, `OnmsSnmpInterface`, `OnmsCategory`)
- 18 DAO `@Bean` definitions
- `SessionUtils` bean (TransactionTemplate-based, same as Pollerd pattern)

### EnlinkdDaemonConfiguration

- `LocationAwareSnmpClientRpcImpl` bean (constructor-injected with `RpcClientFactory`)
- `EnhancedLinkdConfigFactory` bean (no `initMethod` — constructor calls `reload()` directly, throws `IOException` which the `@Bean` method must propagate)
- 7 topology service beans + `NodeTopologyServiceImpl` (constructor-injected with their DAOs)
- 9 topology updater beans + `DiscoveryBridgeDomains`
- `DiscoveryBridgeDomains` bean
- `EnhancedLinkd` bean (field `@Autowired` resolved by Spring's `AutowiredAnnotationBeanPostProcessor`, which is active even with `scanBasePackages = {}`)
- `EventProcessor` bean (setter-injected with `EnhancedLinkd`; do NOT call `init()`)
- `AnnotationBasedEventListenerAdapter` bean (0-arg constructor + setters)
- `DaemonSmartLifecycle` bean (wraps `EnhancedLinkd`)

### Key Wiring Decisions

- `scanBasePackages = {}` — no component scanning; all beans explicit. Spring Boot still registers `AutowiredAnnotationBeanPostProcessor`, so `@Autowired` field injection on `@Bean`-returned objects works.
- `DaemonSmartLifecycle` — works because `EnhancedLinkd extends AbstractServiceDaemon`
- `KafkaRpcClientConfiguration` imported — SNMP walks route through Minion via Kafka RPC
- `KafkaEventTransportConfiguration` imported — receives node events from Provisiond
- `EnhancedLinkd.onInit()` calls `BeanUtils.assertAutowiring(this)` — this validates all `@Autowired` fields are non-null at daemon start, providing a clear error if any dependency is missing
- `EnhancedLinkdConfigFactory` constructor calls `reload()` and throws `IOException` — no `initMethod` needed

### Required Configuration Files

The following must be present in the `/opt/deltav/etc/` overlay:
- `enlinkd-configuration.xml` — daemon configuration (thread counts, poll intervals, protocol enable flags)
- `snmp-config.xml` — SNMP community strings and agent configs (read-only)
- `eventconf.xml` + event definition files — for event enrichment via `KafkaEventTransportConfiguration`

## Docker Integration

### Dockerfile.springboot

Add COPY line:
```dockerfile
COPY core/daemon-boot-enlinkd/target/daemon-boot-enlinkd.jar /opt/daemon-boot-enlinkd.jar
```

### docker-compose.yml

```yaml
enlinkd:
  build:
    context: .
    dockerfile: opennms-container/delta-v/Dockerfile.springboot
  entrypoint: []
  command:
    - "java"
    - "-Xms256m"
    - "-Xmx512m"
    - "-XX:MaxMetaspaceSize=256m"
    - "-Dopennms.home=/opt/deltav"
    - "-jar"
    - "/opt/daemon-boot-enlinkd.jar"
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    SPRING_DATASOURCE_USERNAME: opennms
    SPRING_DATASOURCE_PASSWORD: opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: enlinkd
    OPENNMS_HOME: /opt/deltav
    OPENNMS_RPC_KAFKA_ENABLED: "true"
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
    interval: 30s
    timeout: 5s
    retries: 3
  stop_grace_period: 60s
  volumes:
    - ./opennms-container/delta-v/overlay/etc:/opt/deltav/etc:ro
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_started
```

### stop_grace_period Rationale

Enlinkd runs `LegacyScheduler` and `LegacyPriorityExecutor` thread pools with in-flight SNMP walks that route through Kafka RPC to Minion. Worst-case RPC timeout is ~45s (SNMP timeout + retries + Kafka producer flush). Docker's default 10s SIGKILL would cut off mid-write to PostgreSQL. 60s gives comfortable headroom for `LegacyScheduler.stop()` to drain via `Thread.join()`.

### build.sh

Add JAR staging:
```bash
cp core/daemon-boot-enlinkd/target/daemon-boot-enlinkd-*.jar \
   core/daemon-boot-enlinkd/target/daemon-boot-enlinkd.jar
```

## Scope Boundaries

### Not Migrated

- `features/enlinkd/shell/` — Karaf shell commands, no Spring Boot equivalent
- `features/enlinkd/generator/` — topology generator, dev tooling only
- `features/enlinkd/tests/` — OSGi integration tests; E2E tests validate via Docker
- `ReloadableTopologyDaemon` interface — not exposed as a bean
- `MessageBus` support — dropped, no runtime config reload
- `@Filter` (AUTH_FILTER_NAME) — preserved on entities but not enabled in daemon
- `TopologyEntityCache` / `TopologyEntity` projections — read-side concerns for REST API

### Reused Unchanged

- All collector classes (`NodeDiscoveryCdp`, `NodeDiscoveryLldp`, etc.)
- All SNMP tracker classes (`CdpGlobalGroupTracker`, etc.)
- `SchedulableNodeCollectorGroup` and `NodeCollector` base
- `LegacyScheduler` and `LegacyPriorityExecutor`
- `EnhancedLinkd` daemon class (field injection resolved by context)
- `EventProcessor` (instantiated via `@Bean`, setters called explicitly)
- `EnhancedLinkdConfigFactory` (JAXB config loading)
- All topology updater classes

### Verified Safe Assumptions

- **snmp-config.xml is read-only from Enlinkd's perspective.** `SnmpPeerFactory` is used only via `getAgentConfig()` in `NodeCollector.java:137`. No `saveConfiguration()` or `saveCurrent()` calls anywhere in `features/enlinkd/`. The `SnmpPeerFactory.init()` encryption write-back path is not triggered by Enlinkd. `:ro` volume mount is safe.
- **No direct Kafka/ActiveMQ usage.** Only Kafka RPC (via `KafkaRpcClientConfiguration`) and Kafka events (via `KafkaEventTransportConfiguration`).

## Established Patterns Applied

| Pattern | Source | Application |
|---------|--------|-------------|
| `DaemonSmartLifecycle` | Alarmd, Pollerd | `EnhancedLinkd extends AbstractServiceDaemon` |
| 0-arg `AnnotationBasedEventListenerAdapter` | PerspectivePollerd | Avoid duplicate `afterPropertiesSet()` |
| ServiceMix wildcard exclusions | PerspectivePollerd | On all OpenNMS deps including model-jakarta, daemon-common |
| `initMethod="init"` | All daemons | Legacy `@PostConstruct` not recognized by Spring 7 (note: `EnhancedLinkdConfigFactory` self-initializes in constructor, does not need `initMethod`) |
| `allocationSize=1` | Alarmd | All `@SequenceGenerator` annotations |
| `PhysicalNamingStrategyStandardImpl` | Alarmd, Pollerd | Preserve camelCase table/column names |
| `scanBasePackages = {}` | All daemons | Prevent legacy class conflicts |
| Explicit `PersistenceManagedTypes` | All JPA daemons | Avoid scanning javax.persistence classes |
| `@Primary` on `eventIpcManager` | Pollerd | Disambiguate EventForwarder implementations |
| `TracerRegistry` via `KafkaRpcClientConfiguration` | Telemetryd | Provided automatically when RPC is enabled |

## Follow-ups (Not Part of This Migration)

- Add `stop_grace_period: 60s` to existing Provisiond and Discovery containers (tracked in memory)
- Convert `EnhancedLinkd` from `@Autowired` field injection to constructor injection (tracked in memory as part of standardize-SpringServiceDaemon effort)
