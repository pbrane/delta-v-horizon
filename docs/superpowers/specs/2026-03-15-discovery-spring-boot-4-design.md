# Discovery Spring Boot 4 Migration Design

**Date:** 2026-03-15
**Status:** Approved
**Predecessor:** [Syslogd Spring Boot 4 Migration](2026-03-15-syslogd-spring-boot-4-design.md)

## Summary

Migrate Discovery to a Spring Boot 4.0.3 microservice. Discovery periodically scans IP ranges by dispatching ICMP ping sweep RPCs to Minion via `KafkaRpcClientFactory`, then generates `NEW_SUSPECT_INTERFACE_EVENT_UEI` events for responsive hosts. Unlike Trapd/Syslogd (passive Kafka Sink consumers), Discovery is an active initiator that uses Kafka RPC for outbound network I/O. Introduces the **Kafka RPC client pattern** as shared infrastructure in `daemon-common`.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| RPC client location | Add `KafkaRpcClientConfiguration` to `daemon-common` | 6 daemons share this infrastructure (Discovery, Pollerd, Collectd, Enlinkd, PerspectivePoller, Provisiond). RPC deps are lightweight (no new module needed). |
| EntityScopeProvider (MATE) | No-op implementation | MATE variable interpolation in discovery detector configs is rare. Deferred to future work. |
| Service detectors | Reuse `LocalServiceDetectorRegistry` (from `daemon-loader-shared`) | Returns no detectors when none registered. Ping sweeps work; `<detector>` elements silently ignored. Real detector support deferred. |
| JPA | Excluded | JDBC-only, same as Trapd/Syslogd |
| Kafka Sink bridge | Not used | Discovery is an active scanner, not a passive consumer. No `daemon-sink-kafka` dependency. |
| Discovery daemon class | Used with `DaemonSmartLifecycle` | Unlike Trapd/Syslogd, Discovery has real lifecycle (Timer for periodic scans, init/start/stop). |

## New Shared Infrastructure

### KafkaRpcClientConfiguration (in daemon-common)

Spring `@Configuration` class that replaces the shared `kafka-rpc-client-factory.xml` used by 6 Karaf daemon loaders. Provides:

- **`KafkaRpcClientFactory`** — creates Kafka RPC clients for sending requests to Minions. Reads bootstrap servers from system property `org.opennms.core.ipc.rpc.kafka.bootstrap.servers`. Declared with `@Bean(initMethod = "start", destroyMethod = "stop")`. The `location` and `metrics` properties must be set before `start()` is called — the `@Bean` method sets them via setters, then Spring calls `start()` via `initMethod`.
- **`RpcTargetHelper`** — routes RPC calls to the correct Minion location.
- **`NoOpTracerRegistry`** — satisfies `KafkaRpcClientFactory`'s `@Autowired TracerRegistry` without pulling in tracing infrastructure.

Configuration is via JVM system property (not Spring environment) because `KafkaRpcClientFactory` reads `System.getProperty()` internally. The Docker `command` must include `-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092`.

### NoOpEntityScopeProvider (in daemon-boot-discovery)

Implements `EntityScopeProvider` with empty scope resolution. Satisfies `LocationAwareDetectorClientRpcImpl`'s dependency. Discovery-specific (not shared) since other daemons may need real MATE support.

### LocalServiceDetectorRegistry (from daemon-loader-shared)

Already exists at `core/daemon-loader-shared/`. Returns no detectors when none are registered on the classpath. Ping sweeps work normally; `<detector>` elements in config are silently ignored since no matching detector factories are found.

## Module Changes

### daemon-common (modified)

Add `KafkaRpcClientConfiguration`:
- Depends on `org.opennms.core.ipc.rpc.kafka` (for `KafkaRpcClientFactory`)
- Depends on `org.opennms.core.rpc.utils` (for `RpcTargetHelper`)
- Provides `rpcClientFactory`, `rpcTargetHelper`, `tracerRegistry` beans
- `NoOpTracerRegistry` class (already exists in `daemon-loader-shared`, move or recreate)

New POM dependencies:
- `org.opennms.core.ipc.rpc:org.opennms.core.ipc.rpc.kafka` (with ServiceMix exclusions)
- `org.opennms.core.ipc.rpc:org.opennms.core.ipc.rpc.api`

### daemon-boot-discovery (new)

Spring Boot 4.0.3 Discovery application.

**Application class:** `DiscoveryApplication`
- `@SpringBootApplication(exclude = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})`
- `scanBasePackages = {"org.opennms.core.daemon.common", "org.opennms.netmgt.discovery.boot"}`
- `@EnableScheduling`
- No `daemon-sink-kafka` in scan (Discovery doesn't use Kafka Sink)

**Configuration classes:**

- **`DiscoveryBootConfiguration`** — wires all Discovery beans:
  - `DiscoveryConfigFactory` (no-arg constructor, reads `discovery-configuration.xml` from `opennms.home/etc/`)
  - `UnmanagedInterfaceFilter` (wraps `InterfaceToNodeCache`, implements `IpAddressFilter`)
  - `RangeChunker` (constructor takes `IpAddressFilter` — pass the `UnmanagedInterfaceFilter`)
  - `PingerFactory` via `BestMatchPingerFactory` — required by `PingSweepRpcModule` and `PingProxyRpcModule` (`@Autowired PingerFactory`). These modules only execute locally if location matches; in production all pings go to Minion. But Spring still autowires the field at startup.
  - `PingSweepRpcModule` and `PingProxyRpcModule` (RPC module beans, `@Autowired PingerFactory`)
  - `LocationAwarePingClientImpl` — uses `KafkaRpcClientFactory` from daemon-common. **Must use `@Bean(initMethod = "init")`** because it has `javax.annotation.PostConstruct` which Spring 7 does not recognize. The `init()` method calls `rpcClientFactory.getClient()` to create RPC delegates.
  - `scanExecutor` — `@Bean(name = "scanExecutor")` providing `Executors.newCachedThreadPool()`. Required by `DetectorClientRpcModule` (`@Autowired @Qualifier("scanExecutor") Executor`).
  - `DetectorClientRpcModule` (RPC module bean, needs `scanExecutor`)
  - `LocalServiceDetectorRegistry` (from `daemon-loader-shared`, returns no detectors)
  - `LocationAwareDetectorClientRpcImpl` — uses `KafkaRpcClientFactory`, `NoOpEntityScopeProvider`, `LocalServiceDetectorRegistry`. Implements `InitializingBean` (Spring 7 handles natively, no `initMethod` needed).
  - `DiscoveryTaskExecutorImpl` (core orchestrator)
  - `Discovery` daemon (main daemon class)
  - `DaemonSmartLifecycle` wrapping `Discovery` (timer-based scheduling via init/start/stop)
  - `AnnotationBasedEventListenerAdapter` (routes `RELOAD_DAEMON_CONFIG_UEI` events to `Discovery`)
  - `InterfaceToNodeCache` via `JdbcInterfaceToNodeCache` (from daemon-common)
  - `DistPollerDao` via `JdbcDistPollerDao` (from daemon-common)

**`DiscoveryTaskExecutorImpl.getLocationAwareDetectorClient()` fallback risk:** If the `LocationAwareDetectorClient` bean is `null` (it's `@Autowired(required = false)`), the code falls back to `BeanUtils.getBean("provisiondContext", ...)` which will throw in Spring Boot (no `provisiondContext`). The `@Bean` for `LocationAwareDetectorClientRpcImpl` in `DiscoveryBootConfiguration` prevents this — the bean will always be non-null.

**Lifecycle:**
- `DaemonSmartLifecycle` wraps `Discovery` daemon
- `Discovery.onInit()` loads config from `DiscoveryConfigFactory`
- `Discovery.onStart()` schedules periodic timer task
- Timer fires → `DiscoveryTaskExecutorImpl.handleDiscoveryTask(config)`
- RPC calls to Minion for ping sweeps
- Results → NEW_SUSPECT events → `EventForwarder` → Kafka `opennms-fault-events`
- `Discovery.onStop()` cancels timer

**Discovery daemon `@Autowired` fields:**
- `DiscoveryTaskExecutor discoveryTaskExecutor` — from `DiscoveryBootConfiguration`
- `EventForwarder eventForwarder` — `EventIpcManager` from `KafkaEventTransportConfiguration` (marked `@Primary`)

**DiscoveryTaskExecutorImpl `@Autowired` fields:**
- `LocationAwarePingClient locationAwarePingClient` — from `DiscoveryBootConfiguration`
- `LocationAwareDetectorClient locationAwareDetectorClient` (`required = false`) — from `DiscoveryBootConfiguration`
- `EventForwarder eventForwarder` — from `KafkaEventTransportConfiguration` via `@Primary`. Used at line 163 to send NEW_SUSPECT events directly. Both `Discovery` and `DiscoveryTaskExecutorImpl` autowire `EventForwarder` — Discovery uses `@Qualifier("eventIpcManager")`, the task executor does not. Both resolve to the same `@Primary` bean.
- `RangeChunker rangeChunker` — from `DiscoveryBootConfiguration`

**`javax.annotation.PostConstruct` status:**
- `Discovery` — does NOT use `@PostConstruct` or `InitializingBean`. Lifecycle managed by `DaemonSmartLifecycle` (calls `init()`/`start()`/`stop()`).
- `DiscoveryTaskExecutorImpl` — does NOT use `@PostConstruct` or `InitializingBean`. Pure `@Autowired` field injection.
- `LocationAwarePingClientImpl` — **uses `@PostConstruct init()`**. Must use `@Bean(initMethod = "init")`.
- `LocationAwareDetectorClientRpcImpl` — uses `InitializingBean.afterPropertiesSet()`. Spring 7 handles natively.

## Data Flow

```
Spring Boot starts → DaemonSmartLifecycle.start()
    → Discovery.onInit() → load DiscoveryConfigFactory
    → Discovery.onStart() → Timer.schedule(task, initialDelay, restartDelay)

Timer fires periodically:
    → DiscoveryTaskExecutorImpl.handleDiscoveryTask(config)
    → RangeChunker: config → Map<location, List<DiscoveryJob>>
    → For each location, for each job:
        → LocationAwarePingClient.sweep(ipRanges, location)
            → KafkaRpcClientFactory.getClient(PingSweepRpcModule)
            → Kafka RPC request → Minion at location
            → Minion runs PingSweepRpcModule (ICMP sweep)
            → Returns PingSweepSummary (responsive IPs + RTTs)
        → Filter via InterfaceToNodeCache (skip already-managed IPs)
        → (Detectors skipped — empty registry)
        → Generate NEW_SUSPECT_INTERFACE_EVENT for each new IP
    → EventForwarder.sendNow(events)
    → KafkaEventForwarder → opennms-fault-events topic

Provisiond (when migrated) consumes NEW_SUSPECT events → creates nodes
```

## Deleted Artifacts

| Artifact | Path | Reason |
|----------|------|--------|
| daemon-loader-discovery module | `core/daemon-loader-discovery/` | Karaf-only wiring replaced by Spring Boot |
| Karaf feature | `opennms-daemon-discovery` in `features.xml` | No longer OSGi bundle |

## Docker Compose

```yaml
discovery:
  profiles: [passive, full]
  image: opennms/daemon-deltav:${VERSION}
  container_name: delta-v-discovery
  hostname: discovery
  entrypoint: []
  command: ["java", "-Xms256m", "-Xmx512m", "-Dopennms.home=/opt/sentinel",
            "-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092",
            "-jar", "/opt/daemon-boot-discovery.jar"]
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
    OPENNMS_HOME: /opt/sentinel
    INTERFACE_NODE_CACHE_REFRESH_MS: "15000"
  volumes:
    - ./discovery-overlay/etc:/opt/sentinel/etc:ro
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
    interval: 15s
    timeout: 10s
    retries: 20
    start_period: 30s
```

Note: `-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers` is a JVM system property because `KafkaRpcClientFactory` reads it via `System.getProperty()`. This is in addition to the Spring `KAFKA_BOOTSTRAP_SERVERS` env var used by the event transport.

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
- `@Bean(initMethod=...)` for any class using `javax.annotation.PostConstruct`
- `@Primary` on `eventIpcManager` bean (already in daemon-common)

## Metrics

Discovery daemon uses Java `Timer` for scheduling, not Micrometer. `KafkaRpcClientFactory` uses Dropwizard `MetricRegistry` internally for RPC monitoring. These are kept as-is. Micrometer/Actuator bridging is a future enhancement.

## Testing Strategy

**Unit tests:**
- `NoOpEntityScopeProvider`: returns empty scopes

**Build verification:**
- Compile daemon-common (with new RPC configuration)
- Compile daemon-boot-discovery
- Package fat JAR, verify executable

**E2E (Docker Compose):**
- Run Discovery + Kafka + PostgreSQL + Minion
- Ensure Discovery starts, timer fires, ping sweep RPC reaches Minion
- Verify NEW_SUSPECT events on `opennms-fault-events`

## Deferred Items

- **Service detector support:** `LocalServiceDetectorRegistry` has no registered detectors — `<detector>` elements in discovery config silently ignored. Need to register real detector factories (SNMP, SSH, HTTP) and ensure Minion-side compatibility. Tracked in `project_future_features.md`.
- **MATE EntityScopeProvider:** No-op implementation — variable interpolation (`${requisition:username}`) disabled in detector configs. Tracked in `project_future_features.md`.

## Future Work

- **Pollerd, Collectd, Enlinkd, PerspectivePoller, Provisiond** — all reuse `KafkaRpcClientConfiguration` from daemon-common. Discovery establishes the pattern.
