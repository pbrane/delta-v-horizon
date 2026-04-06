# Collectd Spring Boot 4 Migration Design

**Date:** 2026-03-24
**Status:** Approved
**Approach:** Minimal Port (Approach A) — port existing daemon, defer modernization

## Context

Collectd is the **last remaining Karaf daemon**. Once migrated, the Sentinel/Karaf image (`opennms/daemon-deltav`) can be retired, and Phase 2 (layered JAR deduplication) can begin.

**Current state:** 11 daemons on Spring Boot 4, 11 daemons deleted, 1 on Karaf (Collectd).

### What Collectd Does

Collectd performs SNMP data collection (and other protocols) on scheduled intervals, storing time-series data:

- Queries the DB for nodes with collection packages matching their IPs/categories
- Sends SNMP collection requests to Minions via Kafka RPC (location-aware)
- Receives collected data and persists to the time-series store
- Runs thresholding as a collection visitor (Threshd was folded into Collectd in 2013)
- Listens for `nodeGainedService`, `nodeLostService`, `nodeDeleted`, and 7 other events

### Complexity Tier

**Tier 4** (same as Enlinkd) — Events + Kafka RPC + Scheduled collection.

## Architecture Decisions

### AD-1: Persistence via TSS Integration Layer with InMemoryStorage

**Decision:** Use the Time Series Storage (TSS) integration layer (`features/timeseries/`) with `InMemoryStorage` as the initial backend.

**Rationale:** The TSS layer provides `TimeseriesPersisterFactory` — a drop-in `PersisterFactory` implementation that decouples Collectd from any specific time-series database. `InMemoryStorage` proves the pipeline works. Cortex will be wired as the production backend immediately after migration (see Deferred Tasks).

**Pluggability seam:** The `TimeSeriesStorage` bean is selected via `@ConditionalOnProperty`:

```java
@Bean
@ConditionalOnProperty(name = "opennms.timeseries.strategy", havingValue = "inmemory", matchIfMissing = true)
public TimeSeriesStorage inMemoryStorage() {
    return new InMemoryStorage();
}
```

A future Cortex module adds another `@Bean TimeSeriesStorage` with `havingValue = "cortex"`. The rest of the pipeline (`TimeseriesPersisterFactory` -> `TimeseriesWriter` -> `TimeSeriesStorage`) is unchanged.

### AD-2: No-Op Thresholding

**Decision:** Stub `ThresholdingService` with a no-op implementation that returns sessions which accept and discard collection sets.

**Rationale:** Same pattern as Pollerd and Enlinkd. The `ThresholdingService` interface is preserved as the integration point for real thresholding later. Wiring real thresholding has deep dependencies (ThresholdingSet, baselines, evaluators) that would balloon scope.

### AD-3: Keep LegacyScheduler

**Decision:** Keep `LegacyScheduler` as-is. Collectd creates it internally during `onInit()`.

**Rationale:** Both Pollerd and Enlinkd use `LegacyScheduler` in their Spring Boot incarnations. It's self-contained — the Spring Boot module doesn't reference it. Replacement is a daemon-internal refactor captured as a deferred task.

### AD-4: Explicit ServiceCollector Registration

**Decision:** Register `SnmpCollector` as a Spring bean in `ServiceCollectorRegistry`. In Karaf this used `ServiceLoader` SPI; here we wire it directly.

**Rationale:** SnmpCollector is the only collector needed for E2E validation against the cEOS lab nodes. Additional collectors (WS-Man, JDBC, etc.) can be added as `@Bean` methods later.

## Module Structure

```
core/daemon-boot-collectd/
├── pom.xml
├── src/main/java/org/opennms/netmgt/collectd/boot/
│   ├── CollectdApplication.java            # @SpringBootApplication entry point
│   ├── CollectdJpaConfiguration.java       # Entities, DAOs, SessionUtils, TransactionTemplate
│   ├── CollectdDaemonConfiguration.java    # Collectd bean, config, collectors, persistence, events
│   └── CollectdRpcConfiguration.java       # LocationAwareCollectorClient, LocationAwareSnmpClient
└── src/main/resources/
    ├── application.yml                     # Datasource, Kafka, RPC, TSS strategy
    ├── logback-spring.xml
    └── META-INF/jakarta-rename.properties
```

### Component Scanning

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.collectd.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
```

### POM Dependencies (beyond daemon-common baseline)

| Dependency | Purpose |
|-----------|---------|
| `opennms-services` | `Collectd.java`, `CollectableService`, `CollectionSpecification` |
| `opennms-config` | `CollectdConfigFactory`, `SnmpPeerFactory` |
| `features/collection/api` | `ServiceCollector`, `PersisterFactory`, `CollectionSet` |
| `features/collection/snmp-collector` | `SnmpCollector` |
| `features/timeseries` | `TimeseriesPersisterFactory`, `RingBufferTimeseriesWriter` |
| `features/inmemory-timeseries-plugin` | `InMemoryStorage` |
| `features/collection/thresholding/api` | `ThresholdingService` interface |

## Bean Wiring: CollectdDaemonConfiguration

### Config Loading

- **`CollectdConfigFactory`** — `@Bean` calling `CollectdConfigFactory.init()`. Must depend on `FilterDaoFactory` initializer (package filters use FilterDao).
- **`SnmpPeerFactory`** — SNMP community/credential config.

### Collector Registry

- **`ServiceCollectorRegistry`** — Spring-managed bean with explicit `SnmpCollector` registration via `@Bean`. Extensible by adding more collector beans.

### Persistence Pipeline (TSS)

| Bean | Role |
|------|------|
| `InMemoryStorage` | `TimeSeriesStorage` SPI impl (swappable via `@ConditionalOnProperty`) |
| `TimeseriesStorageManager` | Wraps the storage bean |
| `TimeseriesWriterConfig` | Ring buffer config from `application.yml` |
| `RingBufferTimeseriesWriter` | Buffered writer |
| `MetaTagDataLoader` | Resource-level tag enrichment |
| `TimeseriesPersisterFactory` | The `PersisterFactory` that Collectd calls |

### Thresholding (No-Op)

- **`ThresholdingService`** — Returns no-op `ThresholdingSession` instances that accept and discard collection sets.

### Events

- **`AnnotationBasedEventListenerAdapter`** wrapping Collectd — registers for 9 event UEIs over Kafka transport.

### Lifecycle

- **`DaemonSmartLifecycle`** wrapping Collectd — calls `init()` then `start()` on app startup, `stop()` on shutdown.

## JPA Entities & DAOs: CollectdJpaConfiguration

### Entities

| Entity | Purpose |
|--------|---------|
| `OnmsNode` | Node lookup for collection scheduling |
| `OnmsIpInterface` | IP interfaces with SNMP primary flag |
| `OnmsSnmpInterface` | SNMP interface metadata (ifIndex, ifType, counters) |
| `OnmsMonitoredService` | Services eligible for collection |
| `OnmsServiceType` | Service type lookup (SNMP, WS-Man, etc.) |
| `OnmsCategory` | Category membership for package filter matching |
| `OnmsMonitoringLocation` | Location for Minion RPC routing |

### DAOs

| DAO | Purpose |
|-----|---------|
| `IpInterfaceDao` | Primary DAO — finding collection-eligible interfaces |
| `NodeDao` | Node lookups for event handling |
| `FilterDao` | Package filter evaluation (JDBC-backed) |

### Standard Beans

- `PersistenceManagedTypes.of(...)` — explicit entity list (no package scan)
- `PhysicalNamingStrategyStandardImpl` — use `@Table`/`@Column` names as-is
- `SessionUtils` — `TransactionTemplate`-backed implementation
- `TransactionTemplate` — for Collectd's event handler wrapping
- `FilterDaoFactory` initializer — must init before `CollectdConfigFactory`

## RPC Configuration: CollectdRpcConfiguration

| Bean | Purpose |
|------|---------|
| `LocationAwareCollectorClient` | Kafka RPC client routing collection to Minion by location |
| `LocationAwareSnmpClient` | SNMP walks through remote Minions |

Both use `KafkaRpcClientConfiguration` from daemon-common.

## Docker Deployment

### docker-compose.yml

```yaml
collectd:
  profiles: [lite, full]
  image: opennms/daemon-deltav-springboot:${VERSION}
  command: ["java", "-Xms512m", "-Xmx1g",
            "-jar", "/opt/daemon-boot-collectd.jar"]
  depends_on:
    db-init: {condition: service_completed_successfully}
    kafka: {condition: service_healthy}
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    OPENNMS_HOME: /opt/deltav
  volumes:
    - ./collectd-daemon-overlay/etc:/opt/deltav/etc:ro
  stop_grace_period: 60s
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
```

### Build Integration

- Add `<module>daemon-boot-collectd</module>` to `core/pom.xml`
- Add `COPY staging/daemon/daemon-boot-collectd.jar /opt/daemon-boot-collectd.jar` to `Dockerfile.springboot`
- Existing `collectd-daemon-overlay/` config directory already in place

## E2E Validation

Reuse the Enlinkd E2E infrastructure — 5 cEOS nodes at `mhuot-labs` with SNMP service detected.

A `test-collectd-e2e.sh` script would:
1. Start Collectd container
2. Wait for collection scheduling (check logs for "scheduleInterface" messages)
3. Verify RPC requests reach Minion (Kafka topic activity)
4. With InMemoryStorage, verify the daemon completes collection cycles without errors

Actual data persistence validation comes when Cortex is wired.

## Deferred Tasks

These are follow-ups to be executed after the core migration is stable:

### Immediate Follow-Up: Cortex TSS Backend

Wire Cortex as a Spring-discovered `TimeSeriesStorage` bean via `@ConditionalOnProperty(name = "opennms.timeseries.strategy", havingValue = "cortex")`. The TSS pipeline is already in place; this is a configuration change plus bringing in the Cortex client dependency.

### Modernization Phase (All Daemons)

1. **Replace LegacyScheduler** — Convert Collectd (and Pollerd, Enlinkd) from `LegacyScheduler` to Spring `ThreadPoolTaskScheduler` for better observability and Spring-native lifecycle management.

2. **Spring-native @EventListener** — Convert from `EventIpcManager.addEventListener(uei)` to Spring `@EventListener` annotations. Eliminates manual event registration/deregistration.

3. **Real ThresholdingService** — Wire the full thresholding implementation (ThresholdingSet, ThresholdEvaluator, baselines) to enable threshold-based alarm generation from collected data.

### Post-Migration Cleanup

4. **Retire `opennms/daemon-deltav` Karaf/Sentinel image** — After Collectd is proven stable on Spring Boot, the Karaf-based container image can be removed entirely.

5. **Phase 2: Layered JAR deduplication** — With all daemons on Spring Boot, deduplicate shared JARs across the multi-daemon Docker image (memory: `project_layered_jars_phase2.md`).

6. **Extract daemons from `opennms-services` monolith** — Collectd, Pollerd, and EventTranslator are the only Spring Boot daemons still sourcing their implementation classes from `opennms-services`. Extract each into focused per-daemon modules (e.g., `features/collection/daemon/`, `features/poller/daemon/`, `features/events/translator/`) to match the pattern used by Enlinkd, Alarmd, BSM, Trapd, etc. This eliminates `opennms-services` as a dependency entirely (memory: `project_eliminate_opennms_services.md`).

## Key Patterns Applied

| Pattern | Source Daemon | Application in Collectd |
|---------|--------------|------------------------|
| `DaemonSmartLifecycle` | All daemons | Wraps Collectd init/start/stop |
| `AnnotationBasedEventListenerAdapter` | Enlinkd | 9 event UEIs over Kafka |
| `PersistenceManagedTypes.of(...)` | All daemons | Explicit entity list |
| `@ConditionalOnProperty` for storage | New pattern | TSS backend selection |
| `FilterDaoFactory` init ordering | Pollerd | Package filters before config |
| ServiceMix wildcard exclusions | All daemons | Prevent classpath pollution |
| No-op ThresholdingService | Pollerd, Enlinkd | Stub until real impl wired |
| `@DependsOn` for config init order | Pollerd | FilterDao before CollectdConfigFactory |

## @Transactional Considerations

Per the Enlinkd migration lesson: any Collectd service methods that call `flush()`, `save()`, or `delete()` on DAOs need `@Transactional` in Spring Boot. In Karaf, transactions were managed by blueprint. Apply proactively to event handler methods that modify DB state.

Collectd's `onEvent()` already wraps all handlers in `TransactionTemplate.execute()`, so the transaction boundary is explicit. Verify this still works correctly with Hibernate 7's session management.

**JpaTransactionManager sharing:** Hibernate 7 (Jakarta) is stricter about session boundaries than Hibernate 3.6. `CollectdJpaConfiguration` must define a `JpaTransactionManager` that is correctly shared between the `TransactionTemplate` (used by event handlers) and all Spring-managed DAOs. A mismatch would cause detached-entity errors or silent session leaks.

## Known Risks

### `findMatching()` Blocker (High Risk — Prerequisite)

`AbstractDaoJpa.findMatching()` is unimplemented in the Jakarta DAO layer (tracked in project memory: `project_findmatching_blocker.md`). Collectd's `onInit()` and interface scheduling logic rely heavily on complex criteria queries to match nodes/interfaces against collection packages. Without a working `findMatching()`, Collectd will fail to schedule any collection.

**Action:** Implement a JPA Criteria-based `findMatching()` in `opennms-model-jakarta` before or in parallel with this migration. This is a prerequisite for E2E validation, not just a risk.

### LegacyScheduler Shutdown in Fat JAR Environment

`DaemonSmartLifecycle.stop()` calls `Collectd.stop()` which calls `m_scheduler.stop()`. In a fat JAR with `stop_grace_period: 60s`, verify that `LegacyScheduler`'s thread pool actually drains within the grace period. `LegacyScheduler` doesn't always handle JVM signals gracefully — the `stop()` method should be tested to confirm threads terminate before SIGKILL.

### `javax.persistence` Classpath Conflict

`opennms-services` transitively pulls in legacy `hibernate-core` (3.6) and `hibernate-jpa-2.0-api`, which conflict with Hibernate 7's `jakarta.persistence`. **Solved pattern from Pollerd:** exclude `hibernate-core` and `hibernate-jpa-2.0-api` from all `opennms-services` transitive dependencies, then include `javax.persistence-api:2.2` at runtime scope so legacy `opennms-model` classes don't cause `ClassNotFoundException` during classpath scanning. Hibernate 7 ignores `javax.persistence` annotations; only `jakarta.persistence` entities from `opennms-model-jakarta` are registered.
