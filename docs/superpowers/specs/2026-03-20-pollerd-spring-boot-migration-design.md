# Pollerd Spring Boot 4 Migration Design

## Overview

Migrate Pollerd from Karaf/OSGi to a standalone Spring Boot 4 application, following the established daemon-boot pattern used by Alarmd, Provisiond, Trapd, Syslogd, EventTranslator, Discovery, and BSMd.

Pollerd is the polling daemon responsible for monitoring service availability. It schedules polls, delegates execution to Minion via Kafka RPC, processes results (outage creation/resolution), and publishes passive status to Minion via the Twin API.

## Pre-requisites

### OnmsOutage Jakarta Entity Port

`OnmsOutage` in `opennms-model` uses `javax.persistence` annotations. A Jakarta-annotated version must be created in `core/opennms-model-jakarta/` before Pollerd can manage outages via JPA with Hibernate 7. The entity is relatively simple (no complex inheritance), but `@Filter` and Hibernate-specific `@Type` annotations need attention.

A corresponding `OutageDaoJpa` is also needed in `core/opennms-model-jakarta/`.

`QueryManagerDaoImpl` (in `opennms-services`) uses the Criteria API against `OnmsOutage` extensively. Its `@Autowired` fields will be converted to constructor injection during migration.

### FilterDaoFactory Initialization

`PollerConfigFactory.init()` validates filter rules via `FilterDaoFactory.getInstance()`. In Karaf, `FilterDao` was imported from OSGi. In Spring Boot, `FilterDaoFactory` must be explicitly initialized with a `DataSource` before `PollerConfigFactory.init()` is called.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Configuration structure | Sectioned classes (Approach B) | Four distinct subsystems map cleanly to separate configs; testable independently |
| Monitor execution | Minion-only via Kafka RPC | Delta-V architecture: all I/O through Minion |
| PersisterFactory | No-op stub | Followup: Kafka timeseries persister shared by Pollerd and Collectd |
| ThresholdingService | No-op stub | Separate daemon concern |
| LocationAwarePingClient | Included | For future node-outage/critical-path use |
| Passive status | Included (publisher side only) | PassiveServiceMonitor executes on Minion; Pollerd receives events and publishes Twin updates |
| Constructor injection | Convert during migration | Already touching all bean declarations; cheaper now than later |
| Poll context | Use `StandalonePollContext` | Subclass of `DefaultPollContext` that skips `AsyncPollingEngine` creation in `afterPropertiesSet()` |

## Module Structure

New Maven module: `core/daemon-boot-pollerd`

```
core/daemon-boot-pollerd/
  pom.xml
  src/main/java/org/opennms/netmgt/poller/boot/
    PollerdApplication.java
    PollerdJpaConfiguration.java
    PollerdRpcConfiguration.java
    PollerdPassiveStatusConfiguration.java
    PollerdDaemonConfiguration.java
  src/main/resources/
    application.yml
```

### PollerdApplication.java

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.poller.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class PollerdApplication {
    public static void main(String[] args) {
        SpringApplication.run(PollerdApplication.class, args);
    }
}
```

### Key Dependencies (pom.xml)

- `daemon-common` — KafkaEventTransport, KafkaRpcClientConfiguration, DaemonSmartLifecycle
- `opennms-services` — Poller, PollableServiceConfig, StandalonePollContext, QueryManagerDaoImpl
- `poller-client-rpc` — LocationAwarePollerClientImpl, PollerRequestBuilderImpl
- `opennms-model-jakarta` — Jakarta JPA entities and DAO implementations (NOT `opennms-dao` which has legacy javax.persistence DAOs)
- `opennms-config` — PollerConfigManager, PollOutagesConfigManager
- `icmp-rpc-common` — LocationAwarePingClient

## Configuration Classes

### PollerdJpaConfiguration

JPA entities, DAOs, session/transaction management.

**Entities** (explicit `PersistenceManagedTypes.of(...)`):
- OnmsNode, OnmsIpInterface, OnmsMonitoredService, OnmsServiceType
- OnmsOutage (requires Jakarta port — see Pre-requisites)
- OnmsMonitoringSystem, OnmsMonitoringLocation, OnmsDistPoller
- OnmsCategory

**DAOs** (from `opennms-model-jakarta` component scan):
- NodeDao, IpInterfaceDao, MonitoredServiceDao — service lookup
- OutageDao (requires Jakarta port — see Pre-requisites)
- MonitoringLocationDao, DistPollerDao — identity/location

**Infrastructure:**
- PhysicalNamingStrategyStandardImpl — no CamelCase conversion
- SessionUtils wrapping TransactionTemplate
- FilterDao — JDBC-based, initialized via `FilterDaoFactory.setInstance()` with DataSource before PollerConfigFactory.init()
- QueryManager / QueryManagerDaoImpl — outage open/resolve interface
- TsidFactory — generates outage/event TSIDs, used by StandalonePollContext

**No-ops:**
- PersisterFactory — stub (followup: Kafka timeseries)
- ThresholdingService — stub
- EventUtil — stub (token expansion handled by KafkaEventForwarder's EventConfEnrichmentService; Pollerd code does not call EventUtil directly)

### PollerdRpcConfiguration

Kafka RPC for delegating polls and pings to Minion.

**Beans:**
- KafkaRpcClientFactory — from daemon-common (gated by `opennms.rpc.kafka.enabled=true`)
- LocationAwarePollerClientImpl — dispatches poll requests to Minion
- LocationAwarePingClientImpl — ICMP proxy to Minion
- LocalServiceMonitorRegistry — ServiceLoader-based (monitors not found locally, delegates to Minion)

### PollerdPassiveStatusConfiguration

Passive status event handling and Twin API publishing. Pollerd is the publisher side — it receives passive status events from Kafka and syncs the status map to Minion via Twin API. PassiveServiceMonitor executes on Minion only.

**Beans:**
- PassiveStatusKeeper — holds passive service status map, registered via `PassiveStatusKeeper.setInstance()`
- KafkaTwinPublisher — publishes Twin updates to Kafka for Minion consumption
- InlineIdentity — identity provider for Twin publisher (system ID + location)
- MetricRegistry — for Twin publisher metrics
- PassiveStatusTwinPublisher — event listener receiving passiveServiceStatus events, pushes through Twin
- AnnotationBasedEventListenerAdapter for PassiveStatusTwinPublisher

### PollerdDaemonConfiguration

Core Poller daemon wiring.

**Beans:**
- PollerConfigFactory — loads poller-configuration.xml (singleton init with FilterDaoFactory)
- PollOutagesConfigManager — loads poll-outages.xml
- StandalonePollContext — poll context wired with EventIpcManager, QueryManager, DAOs, TsidFactory, localHostName (`InetAddressUtils.getLocalHostName()`)
- PollableNetwork — in-memory tree of polled services
- Poller — the daemon, wired with config, context, network, scheduler
- DaemonSmartLifecycle — wraps Poller for Spring lifecycle

**Event handling:** PollerEventProcessor is NOT wired via AnnotationBasedEventListenerAdapter. It implements EventListener directly and self-registers via `EventIpcManager.addEventListener()` inside `Poller.init()`. No adapter bean is needed.

**Event flow:** StandalonePollContext.sendEvent() -> EventIpcManager.sendNow() -> KafkaEventForwarder -> enrichWithEventConf() -> Kafka `opennms-fault-events` topic. This produces nodeLostService/nodeRegainedService/interfaceDown/nodeDown events with alarm-data and expanded reduction keys.

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
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-pollerd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
  rpc:
    kafka:
      enabled: ${OPENNMS_RPC_KAFKA_ENABLED:true}
      bootstrap-servers: ${OPENNMS_RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
```

## Docker Integration

### docker-compose.yml changes

```yaml
pollerd:
  profiles: [lite, full]
  image: opennms/daemon-deltav:${VERSION}
  entrypoint: []
  command: ["java", "-Xms512m", "-Xmx1g", "-jar", "/opt/daemon-boot-pollerd.jar"]
  container_name: delta-v-pollerd
  hostname: pollerd
  depends_on:
    db-init:
      condition: service_completed_successfully
    kafka:
      condition: service_healthy
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    SPRING_DATASOURCE_USERNAME: opennms
    SPRING_DATASOURCE_PASSWORD: opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: opennms-pollerd
    OPENNMS_RPC_KAFKA_ENABLED: "true"
    OPENNMS_RPC_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

### Config overlay

- `poller-configuration.xml` stays (loaded by PollerConfigFactory from `opennms.home` path)
- Remove Karaf-specific files: `featuresBoot.d/`, `org.opennms.core.health.cfg.cfg`, `org.opennms.features.kafka.producer.client.cfg`, `org.opennms.core.event.forwarder.kafka.cfg`, `org.opennms.netmgt.distributed.datasource.cfg`

### build.sh

- Add `daemon-boot-pollerd` JAR to Delta-V staging/daemon directory
- Remove `daemon-loader-pollerd` from Karaf feature set (if still referenced)

## Constructor Injection

All Poller classes with `@Autowired` field injection will be converted to constructor injection during this migration. This includes:
- Poller daemon class (8 `@Autowired` fields)
- StandalonePollContext (setter injection → constructor injection)
- QueryManagerDaoImpl (6 `@Autowired` fields)
- Any other classes touched during the wiring

## Verification

Run the BSM E2E test (`scripts/setup-bsm-e2e.sh --test`) to verify the full Pollerd→Alarmd→BSMd event chain:

1. All 6 Delta-V services polled successfully through Minion via Kafka RPC — BSM status = normal
2. Stop trapd container — Pollerd detects failure, sends `nodeLostService` to Kafka, Alarmd creates alarm, BSM status = major
3. Start trapd container — Pollerd detects recovery, sends `nodeRegainedService` to Kafka, Alarmd clears alarm, BSM status = normal

This test exercises the exact path that was broken under the Karaf Pollerd (PersisterFactory crash, event forwarding failures) and validates that the Spring Boot migration resolves those issues end-to-end.

## Followup Tasks (Out of Scope)

1. **Kafka timeseries persister** — Replace no-op PersisterFactory with implementation publishing response time and poll status metrics to Kafka. Shared by Pollerd and Collectd.
2. **EventUtil/EventUtils consolidation** — Merge or clarify boundary between EventUtil (singleton token expander) and EventUtils (static parm reader). Code smell.
