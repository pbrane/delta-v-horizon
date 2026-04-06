# Telemetryd Spring Boot 4 Migration Design

## Overview

Migrate Telemetryd from Karaf/OSGi to a standalone Spring Boot 4 application, following the established daemon-boot pattern. Telemetryd is migrated as a **pure ingestion bridge** — it consumes telemetry data from Minion via Kafka Sink topics and makes it available in the daemon framework. No adapters are enabled, no flow processing, no Elasticsearch persistence.

This architectural decision separates ingestion from processing, creating the exact Kafka topic topology that a future `flow-processor` service (Spring Cloud Stream + Kafka Streams) will consume. Telemetryd is the Source; the future flow-processor is the Processor/Sink.

## Pre-requisites

All pre-requisites are already satisfied from prior migrations:

- **daemon-common** — KafkaEventTransport, DataSource, NoOpTracerRegistry, NoOpEntityScopeProvider
- **daemon-loader-telemetryd** — TelemetryMessageConsumerManager, KafkaSinkBridge, LocalMessageDispatcherFactory (existing module, reused)
- **Lightweight Docker images** — `opennms/daemon-deltav-springboot` image and `opennms/jre-deltav:21` base available
- **AnnotationBasedEventListenerAdapter pattern** — 0-arg constructor + setters (established in PerspectivePollerd migration)
- **Inline SmartLifecycle pattern** — for SpringServiceDaemon (established in PerspectivePollerd migration)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | Pure ingestion bridge | Separates ingestion from processing; creates Kafka topology for future Nephron replacement |
| Configuration structure | 3 sectioned classes | JPA (minimal), Sink (multi-bridge), Daemon (Telemetryd + ConnectorManager) |
| Adapters | All disabled | Flow processing moves to future `flow-processor` service |
| Elasticsearch | Not included | No adapter execution means no flow documents to persist |
| ConnectorManager | Included | Preserves OpenConfig streaming telemetry connector config publishing via Twin API |
| Twin API | Included | ConnectorManager publishes connector configs to Minion via Twin |
| opennms-services | Not needed | Telemetryd lives in `features/telemetry/daemon/` |
| Constructor injection | Convert 7 @Autowired fields | Wired via @Bean factory method in @Configuration class; @Autowired left on constructor for Karaf compatibility |
| Lifecycle | Inline SmartLifecycle | Telemetryd implements SpringServiceDaemon, not AbstractServiceDaemon |
| Docker image | `daemon-deltav-springboot` | Lightweight Alpine JRE, consistent with other Spring Boot daemons |

## Module Structure

New Maven module: `core/daemon-boot-telemetryd`

```
core/daemon-boot-telemetryd/
  pom.xml
  src/main/java/org/opennms/netmgt/telemetry/boot/
    TelemetrydApplication.java
    TelemetrydJpaConfiguration.java
    TelemetrydSinkConfiguration.java
    TelemetrydDaemonConfiguration.java
  src/main/resources/
    application.yml
```

### TelemetrydApplication.java

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.telemetry.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class TelemetrydApplication {
    public static void main(String[] args) {
        SpringApplication.run(TelemetrydApplication.class, args);
    }
}
```

### Key Dependencies (pom.xml)

- `daemon-common` — KafkaEventTransport, KafkaRpcClientConfiguration (not used but harmless), DaemonSmartLifecycle (not used — inline SmartLifecycle), DataSource
- `daemon-loader-telemetryd` — TelemetryMessageConsumerManager, KafkaSinkBridge, LocalMessageDispatcherFactory, SpringDaemonLifecycleManager (not used)
- `features/telemetry/daemon` (`org.opennms.features.telemetry:org.opennms.features.telemetry.daemon`) — Telemetryd, ConnectorManager, OpenConfigTwinPublisherImpl, LocationPublisherManager
- `features/telemetry/api` — TelemetryManager, TelemetryRegistry, Listener/Adapter interfaces
- `features/telemetry/common` — TelemetrySinkModule, protobuf definitions
- `features/telemetry/config/jaxb` — TelemetrydConfigDao, QueueConfig, AdapterConfig
- `features/telemetry/registry` — TelemetryRegistryImpl (concrete TelemetryRegistry)
- `opennms-model-jakarta` — minimal entity set (DistPoller, MonitoringLocation)
- `opennms-dao-api` — ServiceTracker, ServiceRef interfaces (used by ConnectorManager)
- `org.opennms.core.ipc.twin.kafka.publisher` — KafkaTwinPublisher for ConnectorManager
- **NOT** depending on: `opennms-services`, `features/flows/*`, `opennms-jest`, Elasticsearch, `opennms-dao` (only `opennms-dao-api`)

**POM pattern:** Same as PerspectivePollerd — wildcard ServiceMix exclusions on `model-jakarta` and `daemon-common`, `spring-dependencies` POM exclusions on all OpenNMS deps.

## Configuration Classes

### TelemetrydJpaConfiguration

Minimal JPA configuration — Telemetryd doesn't manage outages, services, or nodes directly. Needed only for DistPollerDao (daemon identity) and SessionUtils.

**Entities** (explicit `PersistenceManagedTypes.of(...)`):
- OnmsMonitoringSystem, OnmsMonitoringLocation, OnmsDistPoller
- Minimal set — no OnmsNode, OnmsOutage, etc.

**DAOs:**
- DistPollerDaoJpa — daemon identity

**Infrastructure:**
- PhysicalNamingStrategyStandardImpl
- SessionUtils wrapping TransactionTemplate

**No-op stubs:**
- EventUtil — stub (same as other daemons)

### TelemetrydSinkConfiguration

Multi-bridge Kafka Sink consumer infrastructure. This is the core of Telemetryd's bridge function.

**Beans:**
- `TelemetryMessageConsumerManager` — extends AbstractMessageConsumerManager, spawns one KafkaSinkBridge per telemetry queue (Netflow-5, IPFIX, sFlow, etc.). When Telemetryd.start() registers a consumer for a queue, the manager creates a KafkaSinkBridge, sets the module, and starts a consumer thread.
- `LocalMessageDispatcherFactory` — in-process dispatcher. When a KafkaSinkBridge receives a message from Kafka, it dispatches to the consumer manager, which routes to the registered TelemetryMessageConsumer for that module.

**Kafka consumer properties** (via system properties, passed in docker-compose command):
- `org.opennms.core.ipc.sink.kafka.bootstrap.servers` — Kafka brokers
- `org.opennms.core.ipc.sink.kafka.group.id` — consumer group for Sink bridge (`opennms-telemetryd-sink`)

**Per-queue bridge lifecycle:**
1. Telemetryd.start() reads telemetryd-configuration.xml queues
2. For each queue, creates TelemetrySinkModule + TelemetryMessageConsumer
3. Registers consumer with TelemetryMessageConsumerManager
4. Manager calls startConsumingForModule() → creates KafkaSinkBridge → spawns consumer thread
5. Consumer thread polls from `OpenNMS.Sink.{queue.name}` topic (100ms poll interval)
6. Messages deserialized from protobuf SinkMessage → dispatched to consumer
7. Consumer invokes adapters (all disabled → no-op)

**Queue threading note:** The `threads`, `batch-size`, `batch-interval`, `queue-size` attributes in `telemetryd-configuration.xml` apply to the listener layer (UDP/TCP Netty threads) which is disabled in Delta-V. KafkaSinkBridge uses 1 thread per queue. Horizontal scaling is via Kafka consumer group rebalancing (multiple Telemetryd containers).

### TelemetrydDaemonConfiguration

Core daemon wiring.

**Beans:**
- `TelemetrydConfigDao` — JAXB DAO reading telemetryd-configuration.xml
- `TelemetryRegistryImpl` — concrete implementation of TelemetryRegistry. Requires 4 `@Qualifier`-ed sub-registry beans (see No-op Stubs section) + MetricRegistry
- `ConnectorManager` — watches for OpenConfig streaming telemetry service matches, publishes connector configs to Minion via Twin API. Dependencies: TelemetryRegistry, EntityScopeProvider (no-op from daemon-common), ServiceTracker (no-op stub), OpenConfigTwinPublisher
- `OpenConfigTwinPublisherImpl` — adapter between ConnectorManager and TwinPublisher
- `LocationPublisherManager` — manages per-location TwinPublisher sessions. Dependency: TwinPublisher (satisfied by KafkaTwinPublisher bean, which must be registered as the TwinPublisher interface type)
- `KafkaTwinPublisher` — implements TwinPublisher, publishes Twin updates to Kafka for Minion consumption. Declare as `@Bean TwinPublisher` to ensure interface-typed injection
- `Telemetryd` — the daemon, wired with 7 dependencies via @Bean factory method:
  1. TelemetrydConfigDao
  2. MessageDispatcherFactory (LocalMessageDispatcherFactory)
  3. MessageConsumerManager (TelemetryMessageConsumerManager)
  4. ApplicationContext (for dynamic bean instantiation)
  5. TelemetryRegistry (TelemetryRegistryImpl)
  6. ConnectorManager
  7. MessageBus (null — reload events handled via Kafka event subscription)
- `AnnotationBasedEventListenerAdapter` for Telemetryd — 0-arg constructor + setters (handles reloadDaemonConfig)
- Inline `SmartLifecycle` — calls `afterPropertiesSet()` / `start()` / `destroy()` (phase = Integer.MAX_VALUE)

**No-op Stubs Required:**

The pure bridge scope means several subsystems are wired but never exercised. These require no-op stubs:

| Interface | Used By | Stub Behavior |
|-----------|---------|---------------|
| `ServiceTracker` | ConnectorManager | `track()` returns no-op Closeable; no service matching performed |
| `TelemetryServiceRegistry<AdapterDefinition, Adapter>` (`@Qualifier("adapterRegistry")`) | TelemetryRegistryImpl | Empty registry; `getRegisteredTypes()` returns empty set |
| `TelemetryServiceRegistry<ListenerDefinition, Listener>` (`@Qualifier("listenerRegistry")`) | TelemetryRegistryImpl | Empty registry |
| `TelemetryServiceRegistry<ConnectorDefinition, Connector>` (`@Qualifier("connectorRegistry")`) | TelemetryRegistryImpl | Empty registry |
| `TelemetryServiceRegistry<ParserDefinition, Parser>` (`@Qualifier("parserRegistry")`) | TelemetryRegistryImpl | Empty registry |
| `MetricRegistry` | TelemetryRegistryImpl | Dropwizard MetricRegistry (real, lightweight — no stub needed) |

The 4 sub-registries are empty because in pure bridge mode no listeners, adapters, connectors, or parsers are loaded locally. The Telemetryd daemon reads the config, creates TelemetrySinkModule instances (for Kafka topic subscription), but delegates all protocol handling to the disabled adapters (no-op).

**Constructor injection note:** Telemetryd currently uses `@Autowired` field injection on 7 fields. The @Bean factory method in TelemetrydDaemonConfiguration will construct Telemetryd by passing all 7 dependencies as constructor arguments. The `@Autowired` annotations remain on the class fields for backward compatibility with the Karaf context (per project decision to strip them in a future pass after Karaf retirement).

**Dynamic bean instantiation:** Telemetryd uses `applicationContext.getAutowireCapableBeanFactory().autowireBean()` and `initializeBean()` to dynamically create TelemetrySinkModule and TelemetryMessageConsumer instances at runtime. The ApplicationContext is passed via constructor injection — this works identically in Spring Boot.

## application.yml

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
  jpa:
    hibernate:
      ddl-auto: none
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
      hibernate.archive.autodetection: none

opennms:
  home: ${OPENNMS_HOME:/opt/deltav}
  instance:
    id: ${OPENNMS_INSTANCE_ID:OpenNMS}
  tsid:
    node-id: ${OPENNMS_TSID_NODE_ID:0}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    ipc-topic: ${KAFKA_IPC_TOPIC:opennms-ipc-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-telemetryd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
```

Note: `maximum-pool-size: 5` and `minimum-idle: 1` — Telemetryd has minimal DB usage (just DistPoller identity). No RPC Kafka section — Telemetryd doesn't send RPC requests to Minion (ConnectorManager publishes via Twin API, not RPC).

## Docker Integration

### docker-compose.yml

Replaces the existing Sentinel-style Telemetryd entry with a Spring Boot entry on `daemon-deltav-springboot`:

```yaml
  telemetryd:
    profiles: [full]
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-Dorg.opennms.core.ipc.sink.kafka.bootstrap.servers=kafka:9092",
              "-Dorg.opennms.core.ipc.sink.kafka.group.id=opennms-telemetryd-sink",
              "-jar", "/opt/daemon-boot-telemetryd.jar"]
    container_name: delta-v-telemetryd
    hostname: telemetryd
    depends_on:
      db-init: { condition: service_completed_successfully }
      kafka: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
      SPRING_DATASOURCE_USERNAME: opennms
      SPRING_DATASOURCE_PASSWORD: opennms
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      KAFKA_CONSUMER_GROUP: opennms-telemetryd
      OPENNMS_HOME: /opt/deltav
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "18"
    volumes:
      - ./telemetryd-overlay/etc:/opt/deltav/etc:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Key differences from existing Sentinel-style entry:
- `image:` → `daemon-deltav-springboot` (lightweight Alpine JRE)
- No `entrypoint:` override needed
- Sink Kafka properties via `-D` system properties (preserved from JAVA_OPTS)
- Two consumer groups: `opennms-telemetryd` (event transport), `opennms-telemetryd-sink` (Sink bridge)
- Volume: `/opt/deltav/etc:ro` (replaces `/opt/sentinel-etc-overlay:ro`)
- Health check: actuator on 8080 (replaces Karaf on 8181)
- Remove `telemetryd-data:` from the `volumes:` top-level section in docker-compose.yml

### Config overlay cleanup

Remove Karaf files from `telemetryd-overlay/etc/`:
- Remove `featuresBoot.d/`
- Remove `org.opennms.*.cfg` files
- Keep `telemetryd-configuration.xml`

### build.sh + Dockerfile

Add `daemon-boot-telemetryd` JAR to:
- `do_stage_daemon_jars()` pairs array in build.sh
- `COPY` line in `Dockerfile.springboot`

## Verification

1. **App starts** — actuator health UP
2. **KafkaSinkBridge threads spawn** — one per queue in telemetryd-configuration.xml
3. **Kafka consumers connect** — consumer group `opennms-telemetryd-sink` on `OpenNMS.Sink.*` topics
4. **Event subscription active** — reloadDaemonConfig handler registered
5. **ConnectorManager initializes** — ready for OpenConfig (no-op if no connectors configured)
6. **jcmd works** — diagnostics available in container

## Followup Tasks (Out of Scope)

1. **`flow-processor` service** — new Spring Boot app consuming from Sink topics, running adapter logic, time-series aggregation, Elasticsearch persistence (Phase 1 of Nephron replacement)
2. **Kafka Streams analytics** — Spring Cloud Stream + Kafka Streams binder for windowed aggregation, flow classification, distributed thresholding (Phase 2 of Nephron replacement)
3. **SpringServiceDaemon standardization** — convert all AbstractServiceDaemon daemons to SpringServiceDaemon for consistent lifecycle API
4. **Strip @Autowired from constructors** — after Karaf is fully retired, remove dead annotations from all Spring Boot daemons
