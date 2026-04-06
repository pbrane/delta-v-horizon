# Trapd Spring Boot 4 Migration Design

**Date:** 2026-03-15
**Status:** Approved
**Predecessor:** [Spring Boot 4 Migration Design](2026-03-14-spring-boot-4-migration-design.md)

## Summary

Migrate Trapd to a Spring Boot 4.0.3 microservice. Trapd consumes SNMP traps forwarded by Minion via Kafka Sink (`OpenNMS.Sink.Trap`), converts them to enriched events, and publishes to `opennms-fault-events`. This migration introduces the **Kafka Sink bridge pattern** — shared infrastructure that Syslogd and Telemetryd will reuse.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Sink bridge location | New `core/daemon-sink-kafka/` module | Sink dependencies (protobuf, Sink API) are orthogonal to daemon-common's event transport. Keeps daemon-common lean. |
| InterfaceToNodeCache | JDBC-based, no JPA | Only needs `(location, ipAddress) → nodeId`. Single SQL query. Keeps startup fast (~2.4s). |
| Twin API | NoOp (skip) | Already NoOp in production. Minion config sync is a future control plane project. |
| SecureCredentialsVault | Skip | Only needed for local TrapListener SNMPv3 config. No local listener in microservice. |
| TrapListener (UDP) | Remove | Architecture is Minion-first. All SNMP traps flow through Minion → Kafka Sink. |

## New Modules

### core/daemon-sink-kafka/

Shared Kafka Sink bridge infrastructure extracted from `core/daemon-loader-trapd/`. Reusable by any daemon that consumes from Minion Sink topics.

**Classes:**

- **`KafkaSinkBridge`** — Kafka consumer that polls a Sink topic (`OpenNMS.Sink.{moduleId}`), deserializes protobuf `SinkMessage`, calls `SinkModule.unmarshal()`, and dispatches to `LocalMessageConsumerManager`. Works with raw `SinkModule<?, Message>` via `setModule()` callback (same non-generic design as existing code). Configuration via Spring environment properties (mapped from env vars, consistent with `KafkaEventTransportConfiguration` pattern):
  - `KAFKA_BOOTSTRAP_SERVERS` — Kafka broker addresses
  - `KAFKA_CONSUMER_GROUP` — per-daemon consumer group ID
  - Additional Kafka consumer properties via `KAFKA_SINK_*` prefix
  - Note: replaces legacy `org.opennms.core.ipc.sink.kafka.*` system properties with Spring-style env vars for consistency with the Boot 4 approach

- **`LocalMessageConsumerManager`** — Extends `AbstractMessageConsumerManager`. Routes deserialized messages to registered `MessageConsumer` implementations. No remote transport — all in-process dispatch.

- **`KafkaSinkConfiguration`** — Spring `@Configuration` class. Creates `KafkaSinkBridge` and `LocalMessageConsumerManager` beans. Auto-wires the `SinkModule` bean from the daemon's context.

**Not included** (removed from daemon-loader-trapd, not needed):
- `LocalMessageDispatcherFactory` — no local UDP dispatch path
- `NoOpTwinPublisher` — not needed without Twin API
- `DaemonLifecycleManager` — not needed; Spring Boot manages lifecycle natively
- `EventConfInitializer` — replaced by JDBC-based `EventConfDao` in daemon-boot-trapd (see below)

**Dependencies:**
- `org.opennms:opennms-ipc-sink-api` — SinkModule, MessageConsumer interfaces
- `org.opennms:opennms-ipc-sink-common` — AbstractMessageConsumerManager
- `com.google.protobuf:protobuf-java` — SinkMessage deserialization
- `org.apache.kafka:kafka-clients` — Kafka consumer
- `org.springframework.boot:spring-boot-starter` — @Configuration support

### core/daemon-boot-trapd/

Spring Boot 4.0.3 Trapd application.

**Application class:** `TrapdApplication`
- `@SpringBootApplication(exclude = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})`
- `scanBasePackages = {"org.opennms.core.daemon.common", "org.opennms.core.daemon.boot.trapd", "org.opennms.core.daemon.sink.kafka"}`

**Configuration classes:**

- **`TrapdConfiguration`** — Loads `trapd-configuration.xml` via `TrapdConfigFactory`, creates `TrapdConfigBean`. Creates `TrapSinkModule` bean (with `DistPollerDao` for system ID/location). Instantiates `TrapSinkConsumer` as a `@Bean` with constructor injection of all 6 dependencies (replacing `@Autowired` field injection). The `TrapSinkConsumer` class in `features/events/traps/` is NOT modified — instead, the `@Bean` method sets fields via setters or constructs a subclass. Creates `EventCreator` (with `InterfaceToNodeCache` and `EventConfDao`).

- **`JdbcEventConfDao`** — JDBC-based implementation of `EventConfDao` (new class in daemon-boot-trapd). Loads event definitions from the `eventconf_events` table in PostgreSQL on startup. Implements `findByEvent(Event)` for UEI matching, severity, alarm-data, logmsg lookup. Refreshed periodically via `@Scheduled`. This replaces the monolith's `DefaultEventConfDao` which depends on the full config system. Same approach as `EventConfEnrichmentService` but implements the `EventConfDao` interface that `EventCreator` and `TrapSinkConsumer` require.

- **`JdbcInterfaceToNodeCache`** — JDBC-based cache implementation:
  - Startup query (JOINs through node for location):
    ```sql
    SELECT ip.nodeid, ip.ipaddr, ip.issnmpprimary, ip.id AS interfaceid,
           n.location
    FROM ipinterface ip
    JOIN node n ON ip.nodeid = n.nodeid
    WHERE n.nodetype != 'D' AND ip.ismanaged != 'D'
    ```
  - Stores `ConcurrentHashMap<LocationIpKey, Integer>` for O(1) lookups
  - Implements `InterfaceToNodeCache` interface (returns `Entry` with nodeId + interfaceId)
  - Periodic refresh via `@Scheduled` (configurable interval, default 5 minutes)

- **Imported configurations:**
  - `DaemonDataSourceConfiguration` (from daemon-common) — PostgreSQL DataSource
  - `KafkaEventTransportConfiguration` (from daemon-common) — event forwarding to `opennms-fault-events`
  - `KafkaSinkConfiguration` (from daemon-sink-kafka) — Sink bridge consuming from `OpenNMS.Sink.Trap`

**Lifecycle (Spring-managed, no DaemonSmartLifecycle):**
- The `Trapd` class (`AbstractServiceDaemon`) is NOT used — its responsibilities (TrapListener, Twin, SCV) are all removed. No daemon wrapper needed.
- `TrapSinkConsumer` is a Spring `@Bean`. Its `@PostConstruct init()` calls `messageConsumerManager.registerConsumer(this)`, which triggers `LocalMessageConsumerManager.startConsumingForModule()`, which calls `kafkaSinkBridge.setModule()` → Kafka polling begins.
- On Spring context shutdown: `@PreDestroy` on consumer deregisters from manager, `KafkaSinkBridge` closes its Kafka consumer.
- This is purely Spring lifecycle — no `SmartLifecycle` or `AbstractServiceDaemon` involved.

**newSuspect event handling:**
- `TrapSinkConsumer` sends `NEW_SUSPECT_INTERFACE_EVENT_UEI` for unknown IPs when `newSuspectOnTrap=true`. These events go through `KafkaEventForwarder` to `opennms-fault-events`. Provisiond (when migrated) will consume from this topic. Until then, new-suspect events are published but not consumed — this is acceptable as the feature is opt-in via config.

**DistPollerDao:**
- JDBC-based, reads from `monitoringsystems` table for local system ID and location
- Same pattern as EventTranslator

## Data Flow

```
Minion receives SNMP trap (UDP 162)
    → Minion TrapListener → TrapSinkModule aggregation
    → Minion publishes to Kafka topic: OpenNMS.Sink.Trap
        (protobuf SinkMessage containing TrapLogDTO)

Trapd Spring Boot microservice:
    → KafkaSinkBridge polls OpenNMS.Sink.Trap
    → SinkMessage.parseFrom(bytes) → extract content
    → TrapSinkModule.unmarshal(content) → TrapLogDTO
    → LocalMessageConsumerManager.dispatch(module, TrapLogDTO)
    → TrapSinkConsumer.handleMessage(TrapLogDTO)
        → for each TrapDTO in TrapLogDTO:
            → EventCreator.createEventFrom(trapDTO, systemId, location, trapAddress)
                → InterfaceToNodeCache.getFirstNodeId(location, trapAddress) → nodeId
                → EventConfDao.findByEvent(event) → UEI, severity, alarm-data
                → Expand alarm-data reduction-key/clear-key templates
            → Enrich event with alarm-data, severity, logmsg
        → EventForwarder.sendNowSync(events)
    → KafkaEventForwarder → opennms-fault-events topic

Alarmd consumes from opennms-fault-events → creates/updates alarms
```

## Deleted Artifacts

| Artifact | Path | Reason |
|----------|------|--------|
| daemon-loader-trapd module | `core/daemon-loader-trapd/` | Karaf-only wiring replaced by Spring Boot |
| Karaf feature | `opennms-daemon-trapd` in `features.xml` | No longer OSGi bundle |
| Trapd overlay | `opennms-container/delta-v/trapd-overlay/` | Replaced by Docker config |

## Docker Compose

```yaml
trapd:
  image: opennms/sentinel:latest
  entrypoint: []
  command: ["java", "-Xms512m", "-Xmx1g", "-jar", "/opt/daemon-boot-trapd.jar"]
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    SPRING_DATASOURCE_USERNAME: opennms
    SPRING_DATASOURCE_PASSWORD: opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: opennms-trapd
    OPENNMS_HOME: /opt/sentinel
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
  volumes:
    - ./overlay/etc:/opt/sentinel/etc:ro
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_healthy
```

## Dependency Pattern

Follows established Spring Boot 4 migration patterns (mandatory):

- Spring Boot 4.0.3 BOM first in `dependencyManagement`
- Jackson pins (version managed by Spring Boot BOM; pin explicitly only if conflicts arise with XML event serialization)
- JUnit 5.12.2 pins (Spring Boot 4 requires JUnit Jupiter 5.12.x)
- Logback 1.5.32 pins
- `javax.persistence-api:2.2` runtime (legacy class loading)
- `javax.xml.bind:jaxb-api:2.3.1` runtime (Kafka event XML)
- ServiceMix Spring 4.2.x exclusions on all OpenNMS dependencies
- Exclude `HibernateJpaAutoConfiguration` + `DataJpaRepositoriesAutoConfiguration`
- `build.sh` stages `-boot.jar` classifier

## Testing Strategy

**Unit tests:**
- `EventCreator`: trap→event conversion, alarm-data template expansion, node ID resolution
- `InterfaceToNodeCache`: JDBC load, lookup, refresh
- `KafkaSinkBridge`: message deserialization, dispatch (with mock consumer manager)

**Integration test:**
- Publish protobuf `SinkMessage` containing `TrapLogDTO` to `OpenNMS.Sink.Trap`
- Assert enriched event appears on `opennms-fault-events` with correct UEI, severity, alarm-data, nodeId

**E2E (Docker Compose):**
- Run Trapd + Alarmd + Kafka + PostgreSQL
- Publish trap via Sink topic → verify alarm created in database

## Metrics

The existing `TrapdInstrumentation` exposes JMX counters (traps received, discarded, errored). In the Spring Boot microservice, these are replaced by Micrometer counters exposed via `/actuator/prometheus`. The `TrapSinkConsumer` increment calls (`incTrapsReceivedCount`, etc.) are adapted to Micrometer `Counter` beans. Spring Boot Actuator auto-configures the metrics endpoint.

## Future Work

- **Kafka Twin API** — build `KafkaTwinPublisher` for pushing trap config to Minions (tracked in project_future_features.md)
- **Syslogd** — reuses `daemon-sink-kafka` with `SyslogSinkModule` instead of `TrapSinkModule`
- **Telemetryd** — multi-module Sink consumer, same bridge pattern
