# PerspectivePollerd Spring Boot 4 Migration Design

## Overview

Migrate PerspectivePollerd from Karaf/OSGi to a standalone Spring Boot 4 application, following the established daemon-boot pattern used by Alarmd, Provisiond, Trapd, Syslogd, EventTranslator, Discovery, BSMd, and Pollerd.

PerspectivePollerd polls monitored services from multiple geographic Minion locations (perspectives) to measure availability per location. It is architecturally independent from Pollerd — no inheritance — but shares the same monitor registry, RPC client, and response-time persistence infrastructure.

## Pre-requisites

All pre-requisites are already satisfied from prior migrations:

- **OnmsOutage Jakarta entity** — ported during Pollerd migration, includes nullable `perspective` FK to `OnmsMonitoringLocation`
- **OutageDaoJpa** — includes `findMatching(Criteria)` and `currentOutagesByServiceId()`
- **ApplicationDaoJpa** — ported during BSMd migration, provides `getServicePerspectives()`
- **MonitoredServiceDaoJpa** — includes `findMatching(InRestriction)`
- **Transport-layer EventConfDao enrichment** — in daemon-common's KafkaEventForwarder
- **Constructor injection** — PerspectivePollerd already uses constructor injection (13 params)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Configuration structure | 3 sectioned classes | JPA, RPC, Daemon — no PassiveStatus needed |
| PassiveStatusKeeper | Omitted | Perspective polling doesn't use passive status or Twin API |
| Monitor execution | Minion-only via Kafka RPC | Delta-V architecture: all I/O through Minion |
| PersisterFactory | No-op stub | Followup: Kafka timeseries persister shared by Pollerd, PerspectivePollerd, and Collectd |
| ThresholdingService | No-op stub | Separate daemon concern |
| CollectionAgentFactory | No-op stub | Required by constructor (`Objects.requireNonNull`) but never called at runtime; PerspectivePollerd builds CollectionAgentDTO directly |
| opennms-services dependency | Not needed | Daemon class lives in `features/perspectivepoller/`, not in opennms-services |
| StandalonePollContext | Not needed | PerspectivePollerd implements SpringServiceDaemon directly, no DefaultPollContext |
| Event handling | AnnotationBasedEventListenerAdapter | Both PerspectivePollerd and PerspectiveServiceTracker use @EventListener/@EventHandler |
| Docker profile | `full` only | Perspective polling is an advanced feature |

## Module Structure

New Maven module: `core/daemon-boot-perspectivepollerd`

```
core/daemon-boot-perspectivepollerd/
  pom.xml
  src/main/java/org/opennms/netmgt/perspectivepoller/boot/
    PerspectivePollerdApplication.java
    PerspectivePollerdJpaConfiguration.java
    PerspectivePollerdRpcConfiguration.java
    PerspectivePollerdDaemonConfiguration.java
  src/main/resources/
    application.yml
```

### PerspectivePollerdApplication.java

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.perspectivepoller.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class PerspectivePollerdApplication {
    public static void main(String[] args) {
        SpringApplication.run(PerspectivePollerdApplication.class, args);
    }
}
```

### Key Dependencies (pom.xml)

- `daemon-common` — KafkaEventTransport, KafkaRpcClientConfiguration, DaemonSmartLifecycle, DataSource
- `features/perspectivepoller` (`org.opennms.features:org.opennms.features.perspectivepoller`) — PerspectivePollerd, PerspectiveServiceTracker, PerspectivePollJob, PerspectivePolledService
- `opennms-model-jakarta` — Jakarta JPA entities and DAO implementations
- `poller-client-rpc` — LocationAwarePollerClientImpl, PollerRequestBuilderImpl
- `opennms-config` — PollerConfigManager
- `opennms-poller-api` — PollStatus, ServiceMonitor, LocationAwarePollerClient interfaces
- `opennms-collection-api` — CollectionAgentFactory, PersisterFactory interfaces

**NOT** depending on `opennms-services` at compile scope. This is the first Spring Boot daemon to avoid this dependency entirely. (The `features/perspectivepoller` module has `opennms-services` as a test-scoped dependency, which does not affect the runtime classpath.)

## Configuration Classes

### PerspectivePollerdJpaConfiguration

JPA entities, DAOs, session/transaction management.

**Entities** (explicit `PersistenceManagedTypes.of(...)`):
- OnmsNode, OnmsIpInterface, OnmsMonitoredService, OnmsServiceType
- OnmsOutage (with nullable perspective FK)
- OnmsMonitoringSystem, OnmsMonitoringLocation, OnmsDistPoller
- OnmsCategory, OnmsSnmpInterface
- OnmsApplication (required by ApplicationDaoJpa for getServicePerspectives())

**DAOs** (from `opennms-model-jakarta` component scan):
- ApplicationDaoJpa — `getServicePerspectives()` drives PerspectiveServiceTracker
- MonitoredServiceDaoJpa — service lookup for outage creation
- OutageDaoJpa — perspective outage create/update/query
- MonitoringLocationDaoJpa — perspective location lookup
- NodeDaoJpa, IpInterfaceDaoJpa, DistPollerDaoJpa

**Infrastructure:**
- PhysicalNamingStrategyStandardImpl — preserve camelCase table names
- SessionUtils wrapping TransactionTemplate
- FilterDaoFactory initialization with JdbcFilterDao + DataSource before PollerConfigFactory.init()

**No-op stubs:**
- PersisterFactory — stub (followup: Kafka timeseries)
- ThresholdingService — stub
- CollectionAgentFactory — stub
- EventUtil — stub

### PerspectivePollerdRpcConfiguration

Kafka RPC for delegating polls to Minion. Identical to Pollerd's RPC configuration.

**Beans:**
- KafkaRpcClientFactory — from daemon-common (gated by `opennms.rpc.kafka.enabled=true`)
- LocationAwarePollerClientImpl — dispatches poll requests to Minion
- LocalServiceMonitorRegistry — ServiceLoader-based (monitors delegate to Minion via RPC)
- PollerClientRpcModule — RPC module for poller requests
- pollerExecutor — cached thread pool for async RPC responses

**Auto-provided by daemon-common component scan:** TracerRegistry (NoOpTracerRegistry — PerspectivePollerd calls `init()` in constructor, PerspectivePollJob calls `getTracer()`), EntityScopeProvider (NoOpEntityScopeProvider — required by LocationAwarePollerClientImpl constructor).

### PerspectivePollerdDaemonConfiguration

Core daemon wiring — the main difference from Pollerd.

**Beans:**
- PollerConfigFactory — loads poller-configuration.xml (singleton init, depends on FilterDaoFactory via @DependsOn)
- PerspectiveServiceTracker — queries ApplicationDao.getServicePerspectives() on 30-second timer, drives scheduling
- PerspectivePollerd — the daemon, wired with 13 constructor-injected dependencies
- DaemonSmartLifecycle — wraps PerspectivePollerd for Spring lifecycle (phase = Integer.MAX_VALUE)
- AnnotationBasedEventListenerAdapter for PerspectivePollerd — registers event handlers for perspective lost/regained/reload events
- AnnotationBasedEventListenerAdapter for PerspectiveServiceTracker — registers event handlers for 14 topology-change events (nodeGainedService, serviceDeleted, nodeCategoryMembershipChanged, nodeLocationChanged, nodeAdded, nodeDeleted, nodeGainedInterface, interfaceDeleted, interfaceReparented, suspendPollingService, resumePollingService, applicationChanged, applicationCreated, applicationDeleted)

**Event handling detail:**

Both PerspectivePollerd and PerspectiveServiceTracker are annotated with `@EventListener` and `@EventHandler`. In Spring Boot (without OSGi annotation scanning), each needs an explicit `AnnotationBasedEventListenerAdapter` bean wired to the `EventIpcManager`.

**Event flow:**
1. PerspectivePollerd polls service from Minion perspective → gets PollStatus
2. `reportResult()` sends PERSPECTIVE_NODE_LOST_SERVICE or PERSPECTIVE_NODE_REGAINED_SERVICE via EventForwarder → KafkaEventForwarder → Kafka
3. PerspectivePollerd's own event handler receives it back from Kafka subscription → creates/resolves perspective outage in DB
4. Sends OUTAGE_CREATED/OUTAGE_RESOLVED → Kafka (for Alarmd consumption)

**Self-consuming events:** PerspectivePollerd produces events that it also listens to. The poll execution and outage management are separate concerns connected via the event bus.

## application.yml

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
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
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-perspectivepollerd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
  rpc:
    kafka:
      enabled: ${OPENNMS_RPC_KAFKA_ENABLED:true}
      bootstrap-servers: ${OPENNMS_RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
```

## Docker Integration

### docker-compose.yml

Replaces the existing Sentinel-style PerspectivePollerd entry (which uses `/opt/sentinel` paths and Karaf health check on port 8181) with a Spring Boot entry using `/opt/deltav` paths and actuator health on port 8080.

```yaml
perspectivepollerd:
  profiles: [full]
  image: opennms/daemon-deltav:${VERSION}
  entrypoint: []
  command: ["java", "-Xms512m", "-Xmx1g",
            "-Dorg.opennms.core.ipc.rpc.force-remote=true",
            "-jar", "/opt/daemon-boot-perspectivepollerd.jar"]
  container_name: delta-v-perspectivepollerd
  hostname: perspectivepollerd
  depends_on:
    db-init: { condition: service_completed_successfully }
    kafka: { condition: service_healthy }
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    SPRING_DATASOURCE_USERNAME: opennms
    SPRING_DATASOURCE_PASSWORD: opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: opennms-perspectivepollerd
    OPENNMS_HOME: /opt/deltav
    OPENNMS_INSTANCE_ID: OpenNMS
    OPENNMS_TSID_NODE_ID: "9"
    OPENNMS_RPC_KAFKA_ENABLED: "true"
    OPENNMS_RPC_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  volumes:
    - ./perspectivepollerd-overlay/etc:/opt/deltav/etc:ro
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
```

**Profile:** `full` only — perspective polling is an advanced feature not needed in lite deployments.

### Config overlay

`perspectivepollerd-overlay/etc/` with:
- `poller-configuration.xml` — shared poller config (same monitors as Pollerd)
- `opennms-datasources.xml` — shared datasource config
- No Karaf-specific files

### build.sh

Add JAR staging:
```
"core/daemon-boot-perspectivepollerd/target/org.opennms.core.daemon-boot-perspectivepollerd-$VERSION-boot.jar:daemon-boot-perspectivepollerd.jar"
```

## Verification

### What we can verify without full perspective infrastructure:
- App starts and reaches healthy state (actuator health UP)
- KafkaEventForwarder connects and is ready
- Kafka RPC client initializes
- PerspectiveServiceTracker timer starts and queries DB without error (finds no perspectives, idles)
- Event listener adapters register for the correct UEIs
- PollerConfigFactory loads poller-configuration.xml

### What requires multi-location Minion setup:
- Actual poll execution via Minion from a perspective location
- Perspective outage creation/resolution cycle
- OUTAGE_CREATED/OUTAGE_RESOLVED events reaching Alarmd

### Recommended approach:
Verify startup + bean wiring + clean idle first. Defer full E2E perspective testing to when multi-location Minion infrastructure is available.

## Followup Tasks (Out of Scope)

1. **Kafka timeseries persister** — replace no-op PersisterFactory with Kafka-based response time persistence (shared by Pollerd, PerspectivePollerd, and Collectd)
2. **Multi-location E2E test** — full perspective polling verification with multiple Minion locations
3. **opennms-services elimination** — PerspectivePollerd proves daemon classes can live outside opennms-services; extract Poller, Trapd, Syslogd, etc. into focused modules
