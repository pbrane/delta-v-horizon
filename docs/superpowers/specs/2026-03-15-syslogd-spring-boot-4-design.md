# Syslogd Spring Boot 4 Migration Design

**Date:** 2026-03-15
**Status:** Approved
**Predecessor:** [Trapd Spring Boot 4 Migration](2026-03-15-trapd-spring-boot-4-design.md)

## Summary

Migrate Syslogd to a Spring Boot 4.0.3 microservice. Syslogd consumes syslog messages forwarded by Minion via Kafka Sink (`OpenNMS.Sink.Syslog`), parses them into events using RFC 3164/5424 parsers, and publishes to `opennms-fault-events`. Reuses `daemon-sink-kafka` (built during Trapd migration) and shared JDBC infrastructure extracted from `daemon-boot-trapd` into `daemon-common`.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Kafka Sink bridge | Reuse `daemon-sink-kafka` | Already built and tested during Trapd migration |
| JDBC infrastructure | Extract to `daemon-common` | `JdbcDistPollerDao` and `JdbcInterfaceToNodeCache` needed by both Trapd and Syslogd. `JdbcEventConfLoader` stays in Trapd (Syslogd does not use `EventConfDao`). |
| DNS resolution | Local `InetAddress.getHostName()` | Real `LocationAwareDnsLookupClientRpcImpl` requires full Kafka RPC stack. Local is wrong for private IPs from remote Minion networks — deferred item to have Minion include resolved hostname in Kafka Sink message envelope. |
| Metrics | Real Dropwizard `MetricRegistry` | `SyslogSinkConsumer` constructor requires it. One-line bean. |
| UDP listener | Remove | Minion-only ingestion via Kafka Sink |
| Syslogd daemon class | Not used | No UDP listener to manage, no lifecycle beyond Spring context |
| JPA | Excluded | JDBC-only, same as Trapd |

## Module Changes

### daemon-common (modified)

Extract from `daemon-boot-trapd` into `daemon-common`:
- `JdbcDistPollerDao` — reads system identity from `monitoringsystems` table
- `JdbcInterfaceToNodeCache` — caches (location, IP) → Entry(nodeId, interfaceId) with periodic refresh. Must call `AbstractInterfaceToNodeCache.setInstance(this)` on initialization so that `ConvertToEvent` (which uses the static singleton `AbstractInterfaceToNodeCache.getInstance()`) can resolve node IDs.
- `JdbcInterfaceToNodeCacheTest` — unit tests move with the class

`JdbcEventConfLoader` stays in `daemon-boot-trapd` — Syslogd does not use `EventConfDao` (it matches UEIs via `SyslogdConfig.getUeiList()` directly).

These classes move from package `org.opennms.netmgt.trapd.boot` to `org.opennms.core.daemon.common`. The `@Component` annotation is removed; each daemon wires them via explicit `@Bean` methods in its own `@Configuration` class.

**`@Scheduled` property name generalization:** The `JdbcInterfaceToNodeCache` refresh interval property changes from `opennms.trapd.interface-to-node-cache.refresh-interval-ms` to `opennms.daemon.interface-to-node-cache.refresh-interval-ms`. Both Trapd and Syslogd `application.yml` files must be updated to use the new property name.

### daemon-boot-trapd (modified)

- Remove `JdbcDistPollerDao.java`, `JdbcInterfaceToNodeCache.java`, `JdbcInterfaceToNodeCacheTest.java`
- `JdbcEventConfLoader` stays (Trapd-specific)
- Update `JdbcEventConfLoader` `@Scheduled` property from `opennms.trapd.eventconf-refresh-interval-ms` to `opennms.trapd.eventconf-refresh-interval-ms` (no change — this one is correctly Trapd-specific)
- `TrapdConfiguration` imports from `daemon-common` instead of local classes
- Verify compilation and tests still pass

### daemon-boot-syslogd (new)

Spring Boot 4.0.3 Syslogd application.

**Application class:** `SyslogdApplication`
- `@SpringBootApplication(exclude = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})`
- `scanBasePackages = {"org.opennms.core.daemon.common", "org.opennms.core.daemon.sink.kafka", "org.opennms.netmgt.syslogd.boot"}`
- `@EnableScheduling`

**Configuration classes:**

- **`SyslogdConfiguration`** — wires all Syslogd beans:
  - `SyslogdConfig` via `SyslogdConfigFactory` (no-arg constructor, requires `-Dopennms.home` to find `syslogd-configuration.xml`). The overlay must provide `syslogd-configuration.xml` with UEI matching rules.
  - `MetricRegistry` bean (Dropwizard `com.codahale.metrics.MetricRegistry`)
  - `SyslogSinkModule` (with config + distPollerDao)
  - `SyslogSinkConsumer` (constructor takes `MetricRegistry`; `@Autowired` handles remaining 4 fields; `InitializingBean.afterPropertiesSet()` registers with `MessageConsumerManager` — no `initMethod` needed unlike Trapd)
  - `InterfaceToNodeCache` via `JdbcInterfaceToNodeCache` (from daemon-common). **Must call `AbstractInterfaceToNodeCache.setInstance(cache)` after creating the bean** — `ConvertToEvent` at line 162 uses the static singleton `AbstractInterfaceToNodeCache.getInstance()` for node ID resolution. Without this, all syslog events will have no node ID.

- **`LocalDnsLookupClient`** — implements `LocationAwareDnsLookupClient` (all 4 methods):
  - `lookup(String hostname, String location)` → delegates to 3-arg version
  - `lookup(String hostname, String location, String systemId)` → `CompletableFuture.completedFuture(InetAddress.getByName(hostname).getHostAddress())`
  - `reverseLookup(InetAddress ipAddress, String location)` → delegates to 3-arg version
  - `reverseLookup(InetAddress ipAddress, String location, String systemId)` → `CompletableFuture.completedFuture(ipAddress.getCanonicalHostName())`
  - Location and systemId parameters ignored (local resolution only)
  - Known limitation: wrong answers for private IPs from remote Minion networks

**Lifecycle:**
- `SyslogSinkConsumer` implements `InitializingBean` — Spring calls `afterPropertiesSet()` after `@Autowired` injection
- `afterPropertiesSet()` registers consumer with `MessageConsumerManager`
- `LocalMessageConsumerManager.startConsumingForModule()` notifies `KafkaSinkBridge.setModule()`
- Bridge starts polling `OpenNMS.Sink.Syslog`

**SyslogSinkConsumer @Autowired fields (5 total):**
- `MessageConsumerManager messageConsumerManager` — from `KafkaSinkConfiguration`
- `SyslogdConfig syslogdConfig` — from `SyslogdConfiguration`
- `DistPollerDao distPollerDao` — `JdbcDistPollerDao` from `daemon-common`
- `EventForwarder eventForwarder` — `EventIpcManager` from `KafkaEventTransportConfiguration`
- `LocationAwareDnsLookupClient m_locationAwareDnsLookupClient` — `LocalDnsLookupClient`

## Data Flow

```
Minion receives syslog message (UDP 1514 via Camel-Netty)
    → SyslogReceiverCamelNettyImpl → SyslogSinkModule aggregation
    → Minion publishes to Kafka topic: OpenNMS.Sink.Syslog
        (protobuf SinkMessage containing SyslogMessageLogDTO)

Syslogd Spring Boot microservice:
    → KafkaSinkBridge polls OpenNMS.Sink.Syslog
    → SinkMessage.parseFrom(bytes) → extract content
    → SyslogSinkModule.unmarshal(content) → SyslogMessageLogDTO
    → LocalMessageConsumerManager.dispatch(module, SyslogMessageLogDTO)
    → SyslogSinkConsumer.handleMessage(SyslogMessageLogDTO)
        → for each SyslogMessageDTO:
            → ConvertToEvent (RFC 3164/5424 parsing)
                → LocalDnsLookupClient.reverseLookup(sourceAddr)
                → AbstractInterfaceToNodeCache.getInstance() → node ID resolution
                → SyslogdConfig UEI matching rules
            → Event with UEI, severity, nodeId, parameters
        → EventForwarder.sendNowSync(eventLog)
    → KafkaEventForwarder → opennms-fault-events topic
```

## Deleted Artifacts

| Artifact | Path | Reason |
|----------|------|--------|
| daemon-loader-syslogd module | `core/daemon-loader-syslogd/` | Karaf-only wiring replaced by Spring Boot |
| Karaf feature | `opennms-daemon-syslogd` in `features.xml` | No longer OSGi bundle |

## Docker Compose

```yaml
syslogd:
  profiles: [passive, full]
  image: opennms/daemon-deltav:${VERSION}
  container_name: delta-v-syslogd
  hostname: syslogd
  entrypoint: []
  command: ["java", "-Xms256m", "-Xmx512m", "-Dopennms.home=/opt/sentinel", "-jar", "/opt/daemon-boot-syslogd.jar"]
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
    KAFKA_CONSUMER_GROUP: opennms-syslogd
    KAFKA_SINK_CONSUMER_GROUP: opennms-syslogd-sink
    OPENNMS_HOME: /opt/sentinel
    INTERFACE_NODE_CACHE_REFRESH_MS: "15000"
  volumes:
    - ./syslogd-overlay/etc:/opt/sentinel/etc:ro
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
    interval: 15s
    timeout: 10s
    retries: 20
    start_period: 30s
```

Key changes from old config:
- `entrypoint: []` overrides Sentinel base image
- `command` runs Spring Boot fat JAR with `-Dopennms.home`
- Spring env vars replace `JAVA_OPTS` system properties
- Actuator health replaces Karaf REST probe
- No UDP port (syslogs come via Kafka Sink from Minion)
- No `syslogd-data` volume (no persistent state)

## Dependency Pattern

Same established patterns (mandatory):
- Spring Boot 4.0.3 BOM first in `dependencyManagement`
- Jackson 2.19.4 pins (parent has 2.16.2)
- JUnit 5.12.2 / Platform 1.12.2 pins
- Logback 1.5.32, SLF4J 2.0.17 pins
- `javax.persistence-api:2.2` runtime
- `javax.xml.bind:jaxb-api:2.3.1` runtime
- ServiceMix Spring 4.2.x exclusions on all OpenNMS dependencies
- Exclude `HibernateJpaAutoConfiguration` + `DataJpaRepositoriesAutoConfiguration`
- `@Bean(initMethod=...)` for any class using `javax.annotation.PostConstruct` (Spring 7 only recognizes `jakarta.annotation.PostConstruct`). Note: `SyslogSinkConsumer` uses `InitializingBean` which Spring 7 handles natively — no workaround needed.
- HibernateJpaAutoConfiguration at `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration` in Spring Boot 4

## Testing Strategy

**Unit tests:**
- `LocalDnsLookupClient`: forward and reverse lookup
- `JdbcInterfaceToNodeCache`: already exists (moves with class to daemon-common)

**Build verification:**
- Compile daemon-common (with extracted classes)
- Compile daemon-boot-trapd (verify still works after extraction)
- Compile daemon-boot-syslogd
- Package fat JAR, verify executable

**E2E (Docker Compose):**
- Run Syslogd + Kafka + PostgreSQL + Minion
- Send syslog via `logger` or `nc` to Minion UDP port
- Verify event appears on `opennms-fault-events`

## Metrics

`SyslogSinkConsumer` uses Dropwizard `MetricRegistry` for timers (`consumerTimer`, `toEventTimer`, `broadcastTimer`) and DNS cache stats. These are kept as-is via the injected `MetricRegistry` bean. Micrometer/Actuator bridging (to expose via `/actuator/prometheus`) is a future enhancement — not required for initial migration.

## Deferred Items

- **Minion-side hostname resolution:** The `LocalDnsLookupClient` gives wrong answers for private IPs from remote Minion networks. Long-term fix: have Minion resolve the hostname locally and include it in the Kafka Sink message envelope (extend `SinkMessage` protobuf or `SyslogMessageLogDTO` metadata). Tracked in `project_future_features.md`.

## Future Work

- **Telemetryd** — multi-module Sink consumer, same bridge pattern, more complex (multiple SinkModules per daemon)
