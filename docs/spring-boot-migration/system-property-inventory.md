# System Property Inventory — Spring Boot Daemons

**Last updated:** 2026-03-16
**Scope:** All Spring Boot 4 migrated daemons in Delta-V

## Bridged Properties

Properties that have been converted from JVM `-D` flags to Spring Boot
`@Value` injection with `System.setProperty()` bridges for legacy code.

Configuration flows: `Docker environment:` → `application.yml` → Spring property resolution → `System.setProperty()` bridge

### Shared (daemon-common, all daemons)

| System Property | Spring Property | Env Var | Default | Bridge Class | Consumer |
|----------------|----------------|---------|---------|--------------|----------|
| `opennms.home` | `opennms.home` | `OPENNMS_HOME` | `/opt/deltav` | `OpennmsHomeConfiguration` (InitializingBean) | `ConfigFileConstants.getHome()` — lazy method call |
| `org.opennms.instance.id` | `opennms.instance.id` | `OPENNMS_INSTANCE_ID` | `OpenNMS` | `SystemPropertyBridgePostProcessor` (EnvironmentPostProcessor) | `SystemInfoUtils` static init — Kafka topic names, consumer group IDs |
| `org.opennms.tsid.node-id` | `opennms.tsid.node-id` | `OPENNMS_TSID_NODE_ID` | `0` | `SystemPropertyBridgePostProcessor` (EnvironmentPostProcessor) | `KafkaEventSubscriptionService.create()` — unique event ID generation |

### Discovery-specific (KafkaRpcClientConfiguration)

| System Property | Spring Property | Env Var | Default | Consumer |
|----------------|----------------|---------|---------|----------|
| `org.opennms.core.ipc.rpc.kafka.bootstrap.servers` | `opennms.rpc.kafka.bootstrap-servers` | `RPC_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | `KafkaRpcClientFactory.start()` via `OnmsKafkaConfigProvider` |
| `org.opennms.core.ipc.rpc.force-remote` | `opennms.rpc.kafka.force-remote` | `RPC_KAFKA_FORCE_REMOTE` | `true` | `KafkaRpcClientFactory.execute()` — forces RPC to Minion instead of local execution |

### Syslogd-specific (SyslogdConfiguration)

| System Property | Spring Property | Env Var | Default | Consumer |
|----------------|----------------|---------|---------|----------|
| `org.opennms.netmgt.syslogd.dnscache.config` | `opennms.syslogd.dnscache.config` | `SYSLOGD_DNS_CACHE_CONFIG` | `maximumSize=1000,expireAfterWrite=8h` | `SyslogSinkConsumer` constructor — Guava CacheBuilder spec for DNS resolution cache |

### TSID Node ID Assignments

Each daemon must have a unique TSID node-id to prevent event ID collisions:

| Daemon | TSID Node ID | Type |
|--------|-------------|------|
| Provisiond (Karaf) | 4 | Karaf `-D` flag |
| Pollerd (Karaf) | 5 | Karaf `-D` flag |
| PerspectivePoller (Karaf) | 7 | Karaf `-D` flag |
| Discovery (Karaf, legacy) | 9 | Karaf `-D` flag |
| Enlinkd (Karaf) | 14 | Karaf `-D` flag |
| Scriptd (Karaf) | 15 | Karaf `-D` flag |
| BSMd (Karaf) | 16 | Karaf `-D` flag |
| Alarmd (Karaf, legacy) | 17 | Karaf `-D` flag |
| Telemetryd (Karaf) | 18 | Karaf `-D` flag |
| **Trapd (Spring Boot)** | **20** | `OPENNMS_TSID_NODE_ID` env var |
| **Syslogd (Spring Boot)** | **21** | `OPENNMS_TSID_NODE_ID` env var |
| **EventTranslator (Spring Boot)** | **22** | `OPENNMS_TSID_NODE_ID` env var |
| **Alarmd (Spring Boot)** | **23** | `OPENNMS_TSID_NODE_ID` env var |
| **Discovery (Spring Boot)** | **24** | `OPENNMS_TSID_NODE_ID` env var |

## Deferred Properties

Properties found in daemon dependency trees but NOT bridged. These remain as
`System.getProperty()` calls in legacy JARs. They either aren't reached by our
daemons at runtime, or use acceptable defaults that nobody configures.

| System Property | Found In | Reason Deferred |
|----------------|----------|-----------------|
| `version.display` | `SystemInfoUtils` static init | Webapp-only — `getDisplayVersion()` / `getVersion()` have zero callers in daemon code |
| `org.opennms.timeseries.strategy` | `TimeSeries.getTimeseriesStrategy()` | Not actively used by any Spring Boot daemon |
| `org.opennms.web.graphs.engine` | `TimeSeries.getGraphEngine()` | UI-only |
| `org.opennms.dao.ipinterface.findByServiceType` | `IpInterfaceDaoHibernate` constructor | HQL query override — nobody configures this |
| `org.opennms.user.allowUnsalted` | `UserManager` constructor | Authentication setting — not relevant to daemon containers |
| `org.opennms.dao.hibernate.NodeLabelDaoImpl.primaryInterfaceSelectMethod` | `NodeLabelDaoImpl` | DAO query customization — nobody configures this |
| `org.opennms.eventd.eventTemplateCacheSize` | `AbstractEventUtil` constructor | Not in any Spring Boot daemon's dependency chain (Alarmd references it but the code path is commented out) |

## Migration Methodology

When migrating a new daemon to Spring Boot 4, follow this checklist:

### 1. Audit

Search the daemon's dependency tree for `System.getProperty()` and `System.setProperty()`:

```bash
# Search in the daemon's source
grep -r "System.getProperty\|System.setProperty" features/<daemon-module>/src/main/java/

# Search in shared dependencies
grep -r "System.getProperty\|System.setProperty" core/daemon-common/src/main/java/
```

Categorize each property:
- **Already bridged** in `daemon-common` → check tables above, no action needed
- **Daemon-specific** → add `@Value` bridge in the daemon's `*Configuration` class
- **Dead code** in this daemon's context → document in Deferred table, skip
- **Legacy JAR refactoring** needed → add to Deferred table with rationale

### 2. Bridge

For each property needing a bridge, determine the lifecycle:

| Consumer Lifecycle | Bridge Strategy |
|-------------------|-----------------|
| Static initializer (`static { }` block) | `EnvironmentPostProcessor` — runs before any bean creation |
| Constructor or `InitializingBean` | `@Value` field + `System.setProperty()` in `@Bean` method, before `new Consumer()` |
| `initMethod` / `start()` | `@Value` field + `System.setProperty()` in `@Bean` method body, before return |
| Lazy method call (called at runtime) | `@Value` field + `System.setProperty()` in any `@Configuration` `InitializingBean` |

Add to `application.yml`:
```yaml
opennms:
  <daemon>:
    <property>: ${ENV_VAR_NAME:default-value}
```

### 3. Docker cleanup

Ensure the daemon's Docker service block has:
- `command:` with NO `-D` flags except `-Xms`/`-Xmx`
- All configuration in `environment:` section
- Volume mount to `/opt/deltav/etc:ro`
- `OPENNMS_HOME: /opt/deltav`
- Unique `OPENNMS_TSID_NODE_ID`

### 4. Verify

- Compile: `./compile.pl -DskipTests --projects :<artifact-id> -am install`
- Docker: `./build.sh deltav && docker compose up <daemon>`
- Actuator: `curl http://localhost:8080/actuator/health`
- E2E: Run `./test-passive-e2e.sh` for full pipeline verification

### 5. Document

Update this file:
- Add new bridged properties to the appropriate table
- Add new TSID node-id assignment
- Move any deferred properties from "Deferred" to "Bridged" if converted
