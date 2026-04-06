# System Property to Spring @Value Refactoring — Design Spec

**Date:** 2026-03-16
**Status:** Approved
**Scope:** All 5 Spring Boot 4 daemons (Trapd, Syslogd, EventTranslator, Alarmd, Discovery)
**Depends on:** PR #38 (`refactor/system-properties-to-spring-value`) must be merged first — it introduces `OpennmsHomeConfiguration` and the `/opt/deltav` rename.

## Problem

Spring Boot daemons currently use a mix of configuration patterns:
- JVM `-D` flags on Docker `command:` lines
- Spring env vars in Docker `environment:` sections
- `System.getProperty()` in legacy Java classes

This makes configuration untestable (no `@TestPropertySource`), undiscoverable (grep across Dockerfiles, YAML, and Java), and inconsistent across daemons.

## Goal

Establish a single configuration path for all Spring Boot daemons:

```
Docker environment: → application.yml → @Value → System.setProperty() bridge (for legacy code)
```

Every configurable value is:
1. Self-documenting in `application.yml`
2. Overridable via environment variable
3. Testable via `@TestPropertySource`
4. Bridged to `System.setProperty()` only where legacy classes require it

## Scope Decisions

### Bridge (convert to @Value with System.setProperty bridge)

Properties actively consumed at runtime by our 5 daemons:

| Property | Legacy Consumer | Used By | Lifecycle Concern |
|----------|----------------|---------|-------------------|
| `opennms.home` | `ConfigFileConstants.getHome()` (lazy method call) | All 5 daemons | None — `InitializingBean` is sufficient |
| `org.opennms.instance.id` | `SystemInfoUtils` **static initializer** — caches value permanently at class load | All 5 daemons (Alarmd, Discovery heavily) | **Critical** — must use `EnvironmentPostProcessor` to set before any bean creation |
| `org.opennms.tsid.node-id` | `KafkaEventSubscriptionService.create()` (called by `KafkaEventTransportConfiguration`) | All 5 daemons | None — called lazily during bean creation |
| `org.opennms.core.ipc.rpc.kafka.bootstrap.servers` | `KafkaRpcClientFactory.start()` via `OnmsKafkaConfigProvider` | Discovery | None — called in `initMethod = "start"` |
| `org.opennms.core.ipc.rpc.force-remote` | `KafkaRpcClientFactory.execute()` via `Boolean.getBoolean()` | Discovery | None — called at RPC execution time. Also fixes existing bug: Discovery currently missing this flag, causing local execution instead of Minion delegation |
| `org.opennms.netmgt.syslogd.dnscache.config` | `SyslogSinkConsumer` constructor | Syslogd | None — bridge before `new SyslogSinkConsumer()` |

### Defer (document only, not bridged)

Properties in legacy JARs that are either not reached by our daemons or never realistically configured:

| Property | Reason Deferred |
|----------|----------------|
| `version.display` | Webapp-only; zero callers in any daemon code path |
| `org.opennms.timeseries.strategy` | Not actively used by these 5 daemons |
| `org.opennms.web.graphs.engine` | UI-only |
| `org.opennms.dao.ipinterface.findByServiceType` | HQL query override; nobody configures this |
| `org.opennms.user.allowUnsalted` | Authentication setting; not relevant to daemon containers |
| `org.opennms.dao.hibernate.NodeLabelDaoImpl.primaryInterfaceSelectMethod` | DAO query override; nobody configures this |

### Removed from scope

| Property | Reason |
|----------|--------|
| `org.opennms.eventd.eventTemplateCacheSize` | Originally attributed to EventTranslator, but `AbstractEventUtil` is NOT in EventTranslator's dependency chain. `EventTranslator` uses `EventUtils` (different class). `AbstractEventUtil`/`EventUtilDaoImpl` is used by Alarmd only (commented out — full DAO layer not available). No daemon currently exercises this code path. |

### Already Done (PR #38)

- `opennms.home` — `OpennmsHomeConfiguration` in `daemon-common` (using `InitializingBean`)
- `/opt/sentinel` → `/opt/deltav` rename for 4 Spring Boot daemons (Trapd, Syslogd, EventTranslator, Alarmd)
- Removed `-Dopennms.home` from Trapd and Syslogd Docker command lines

## Implementation Plan

### Phase 1: Shared Properties in `daemon-common`

Single PR covering all 5 daemons.

**1. SystemPropertyBridgePostProcessor (new class in daemon-common)**

`org.opennms.instance.id` is read by `SystemInfoUtils` in a **static initializer** — the value is cached permanently at class load time. An `InitializingBean` is NOT safe because another bean's creation could trigger `SystemInfoUtils` class loading first. We must use an `EnvironmentPostProcessor` which runs before ANY bean creation:

```java
public class SystemPropertyBridgePostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        bridge(env, "opennms.instance.id", "org.opennms.instance.id", "OpenNMS");
        bridge(env, "opennms.tsid.node-id", "org.opennms.tsid.node-id", "0");
    }

    private void bridge(ConfigurableEnvironment env, String springKey, String systemKey, String defaultValue) {
        String value = env.getProperty(springKey, defaultValue);
        System.setProperty(systemKey, value);
    }
}
```

Registered via `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` (Spring Boot 3+ / 4 convention).

- All `application.yml` files get:
  - `opennms.instance.id: ${OPENNMS_INSTANCE_ID:OpenNMS}`
  - `opennms.tsid.node-id: ${OPENNMS_TSID_NODE_ID:0}` (per-daemon default in Docker env)
- Docker env: `OPENNMS_INSTANCE_ID: OpenNMS`, `OPENNMS_TSID_NODE_ID: <unique per daemon>`

**Why `org.opennms.tsid.node-id` matters:** `KafkaEventSubscriptionService.create()` uses this for TSID-based unique event ID generation across distributed daemons. Currently all 5 Spring Boot daemons default to `0`, creating a collision risk. Each daemon should get a unique value via Docker env.

**2. KafkaRpcClientConfiguration update (existing class in daemon-common)**

Add `@Value` fields (matching existing `KafkaEventTransportConfiguration` field injection style) and bridge calls before `KafkaRpcClientFactory.start()`:

```java
@Value("${opennms.rpc.kafka.bootstrap-servers:kafka:9092}")
private String rpcBootstrapServers;

@Value("${opennms.rpc.kafka.force-remote:true}")
private String forceRemote;

@Bean(initMethod = "start", destroyMethod = "stop")
@ConditionalOnProperty(name = "opennms.rpc.kafka.enabled", havingValue = "true", matchIfMissing = false)
public KafkaRpcClientFactory rpcClientFactory(DistPollerDao distPollerDao,
                                               MetricRegistry kafkaRpcMetricRegistry) {
    System.setProperty("org.opennms.core.ipc.rpc.kafka.bootstrap.servers", rpcBootstrapServers);
    System.setProperty("org.opennms.core.ipc.rpc.force-remote", forceRemote);
    var factory = new KafkaRpcClientFactory();
    factory.setLocation(distPollerDao.whoami().getLocation());
    factory.setMetrics(kafkaRpcMetricRegistry);
    return factory;
}
```

- Uses field injection with `@Value` to match existing `KafkaEventTransportConfiguration` pattern
- Discovery `application.yml` gets: `opennms.rpc.kafka.bootstrap-servers` and `opennms.rpc.kafka.force-remote`
- Docker: `RPC_KAFKA_BOOTSTRAP_SERVERS` and `RPC_KAFKA_FORCE_REMOTE` env vars
- Remove `-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092` from Discovery `command:`
- This also fixes an existing bug: Discovery's docker-compose was missing `-Dorg.opennms.core.ipc.rpc.force-remote=true`, meaning ping sweeps for the "Default" location would execute locally inside the container instead of delegating to Minion via Kafka

**3. Docker cleanup for Discovery**

- Remove all `-D` flags from Discovery `command:` (only keep `-Xms`/`-Xmx`)
- Apply `/opt/deltav` rename (missed in PR #38 since Discovery wasn't on develop)
- Move `OPENNMS_HOME` and volume mounts to `/opt/deltav`
- Add `RPC_KAFKA_BOOTSTRAP_SERVERS`, `RPC_KAFKA_FORCE_REMOTE`, `OPENNMS_TSID_NODE_ID` env vars

### Phase 2: Daemon-Specific Properties

One commit per daemon, single PR.

**4. Syslogd — DNS cache config**

Bridge in `SyslogdConfiguration` (actual class name in `core/daemon-boot-syslogd`):

```java
@Value("${opennms.syslogd.dnscache.config:maximumSize=1000,expireAfterWrite=8h}")
private String dnsCacheConfig;

// In the @Bean method that creates SyslogSinkConsumer:
System.setProperty("org.opennms.netmgt.syslogd.dnscache.config", dnsCacheConfig);
```

`application.yml`: `opennms.syslogd.dnscache.config: ${SYSLOGD_DNS_CACHE_CONFIG:maximumSize=1000,expireAfterWrite=8h}`

### Phase 3: Documentation

Same PR as Phase 2.

**5. System property inventory document**

`docs/spring-boot-migration/system-property-inventory.md` containing:
- Complete inventory of all system properties found in daemon dependency trees
- Bridged properties: what, where, why, Spring property name, env var name
- Deferred properties: what, why deferred, when to revisit
- Migration methodology checklist (reusable for future daemon conversions)

## Migration Methodology Checklist

For each new daemon migration to Spring Boot 4:

1. **Audit** — Search dependency tree for `System.getProperty()` / `System.setProperty()`. Categorize:
   - Already bridged in `daemon-common` → no action
   - Daemon-specific, needs bridge → add to `*Configuration` class
   - Dead code in this context → document, skip
   - Deferred to legacy JAR refactoring → add to inventory

2. **Bridge** — For each property needing a bridge:
   - Add Spring property to `application.yml` with `${ENV_VAR:default}`
   - Determine lifecycle: static initializer → `EnvironmentPostProcessor`; lazy call → `@Value` + `InitializingBean` or `@Bean` method
   - Call `System.setProperty()` for legacy consumers
   - Ensure bean/lifecycle ordering (bridge before consumer init)

3. **Docker cleanup** — No `-D` flags except `-Xms`/`-Xmx`. All config via `environment:`.

4. **Verify** — Compile, Docker rebuild, start, E2E tests.

5. **Document** — Update `system-property-inventory.md`.

## Testing Strategy

- All 5 daemons compile after each phase
- Docker rebuild + startup verification
- E2E: Kafka topic names include instance ID (validates `SystemInfoUtils` bridge via `EnvironmentPostProcessor`)
- E2E: Discovery ping sweep works via Minion (validates RPC bootstrap + force-remote bridges)
- E2E: Syslog and trap pipelines functional
- Verify each daemon gets a unique TSID node-id in Docker env
