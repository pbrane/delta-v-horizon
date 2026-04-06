# Collectd Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Collectd — the last Karaf daemon — to a standalone Spring Boot 4 fat JAR, using the TSS integration layer with InMemoryStorage for persistence.

**Architecture:** Follow the established daemon-boot pattern (Pollerd as closest analog). Create `core/daemon-boot-collectd` with explicit JPA entity management, Kafka RPC for Minion collection, TSS persistence pipeline, no-op thresholding, and Kafka event transport. Collectd.java stays in `opennms-services` unchanged.

**Tech Stack:** Spring Boot 4.0.3, Hibernate 7, Jakarta Persistence, Kafka RPC, TSS Integration Layer, InMemoryStorage

**Spec:** `docs/superpowers/specs/2026-03-24-collectd-spring-boot-migration-design.md`

**Reference module:** `core/daemon-boot-pollerd` (closest analog — same Tier 4 complexity, same `opennms-services` dependency)

---

## File Structure

### Files to create

| File | Responsibility |
|------|---------------|
| `core/daemon-boot-collectd/pom.xml` | Maven module: Spring Boot 4 BOM, dependencies, exclusions, fat JAR packaging |
| `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdApplication.java` | `@SpringBootApplication` entry point with explicit component scanning |
| `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdJpaConfiguration.java` | JPA entities, DAOs, SessionUtils, TransactionTemplate, FilterDao init |
| `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdDaemonConfiguration.java` | Collectd bean wiring, config factories, collector registry, TSS persistence, thresholding stub, events, lifecycle |
| `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdRpcConfiguration.java` | LocationAwareCollectorClient, CollectorClientRpcModule, SNMP RPC |
| `core/daemon-boot-collectd/src/main/resources/application.yml` | DataSource, Kafka, RPC, TSS config |

### Files to modify

| File | Change |
|------|--------|
| `core/pom.xml:48` | Add `<module>daemon-boot-collectd</module>` after `daemon-boot-enlinkd` |
| `opennms-container/delta-v/Dockerfile.springboot:24` | Add `COPY` for collectd JAR |
| `opennms-container/delta-v/docker-compose.yml:158-191` | Replace Karaf-based collectd service with Spring Boot service |

---

## Task 1: Create Maven Module (pom.xml)

**Files:**
- Create: `core/daemon-boot-collectd/pom.xml`
- Modify: `core/pom.xml:48` — add module entry

- [ ] **Step 1: Create the pom.xml**

Use `core/daemon-boot-pollerd/pom.xml` as template. Key differences from Pollerd:
- `artifactId`: `org.opennms.core.daemon-boot-collectd`
- Replace Pollerd-specific deps (`poller.client-rpc`, `daemon-loader-pollerd`, `icmp.proxy.rpc-impl`, `ipc.twin.kafka.publisher`) with Collectd-specific deps. Keep `kv-store.json.noop` (needed for `NoOpJsonStore` in `pollOutagesDao`):
  - `org.opennms.features.collection:org.opennms.features.collection.client-rpc` (LocationAwareCollectorClientImpl, CollectorClientRpcModule)
  - `org.opennms.core.daemon:org.opennms.core.daemon-loader-collectd` (LocalServiceCollectorRegistry)
  - `org.opennms.features.collection:org.opennms.features.collection.snmp-collector` (SnmpCollector)
  - `org.opennms.features:org.opennms.features.timeseries` (TimeseriesPersisterFactory, RingBufferTimeseriesWriter)
  - `org.opennms.features:inmemory-timeseries-plugin` (InMemoryStorage)
  - `org.opennms.features.collection:org.opennms.features.collection.thresholding-api` (ThresholdingService interface)
  - `org.opennms.core.snmp:org.opennms.core.snmp.proxy.rpc-impl` (LocationAwareSnmpClient for SnmpCollector)
- Keep shared deps: `daemon-common`, `model-jakarta`, `opennms-services` (with same exclusion block as Pollerd), `opennms-config`, Spring Boot starters

Each `opennms-services` and `opennms-config` dependency must exclude:
```xml
<exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-*</artifactId></exclusion>
<exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
<exclusion><groupId>org.hibernate.javax.persistence</groupId><artifactId>hibernate-jpa-2.0-api</artifactId></exclusion>
```

Include `javax.persistence-api:2.2` at runtime scope (prevents ClassNotFoundException from legacy opennms-model classes on classpath).

- [ ] **Step 2: Add module to core/pom.xml**

Add after line 48 (`daemon-boot-enlinkd`):
```xml
    <module>daemon-boot-collectd</module>
```

- [ ] **Step 3: Verify module compiles**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-collectd -am compile
```
Expected: BUILD SUCCESS (dependencies resolve, no classpath conflicts)

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-collectd/pom.xml core/pom.xml
git commit -m "feat(collectd): create daemon-boot-collectd Maven module"
```

---

## Task 2: Application Entry Point

**Files:**
- Create: `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdApplication.java`

- [ ] **Step 1: Create CollectdApplication.java**

```java
package org.opennms.netmgt.collectd.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.collectd.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class CollectdApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectdApplication.class, args);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdApplication.java
git commit -m "feat(collectd): add CollectdApplication entry point"
```

---

## Task 3: JPA Configuration

**Files:**
- Create: `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdJpaConfiguration.java`

- [ ] **Step 1: Create CollectdJpaConfiguration.java**

Follow Pollerd's `PollerdJpaConfiguration.java` pattern. Sections:

**1. JPA Naming:**
```java
@Bean
public PhysicalNamingStrategy physicalNamingStrategy() {
    return new PhysicalNamingStrategyStandardImpl();
}
```

**2. Entity Management — `PersistenceManagedTypes`:**
Explicit entity list (from spec + Pollerd pattern):
- `OnmsNode`, `OnmsIpInterface`, `OnmsSnmpInterface`, `OnmsMonitoredService`, `OnmsServiceType`
- `OnmsMonitoringSystem`, `OnmsMonitoringLocation`, `OnmsDistPoller`, `OnmsCategory`
- AttributeConverters: `NodeTypeConverter`, `PrimaryTypeConverter`, `InetAddressConverter`, `NodeLabelSourceConverter`, `OnmsSeverityConverter`

Note: No `OnmsOutage` or `OnmsApplication` (Collectd doesn't manage outages — that's Pollerd's concern).

**3. SessionUtils:**
Same `TransactionTemplate`-backed implementation as Pollerd — `withTransaction()`, `withReadOnlyTransaction()`, `withManualFlush()`.

**4. TransactionTemplate:**
Standard bean from `PlatformTransactionManager`.

**5. FilterDaoFactory initialization:**
Same as Pollerd — `JdbcFilterDao` backed by Spring `DataSource`, `DatabaseSchemaConfigFactory.init()`, `FilterDaoFactory.setInstance()`.
Must be `@DependsOn`-able so `CollectdConfigFactory` bean can depend on it.

**6. TsidFactory:**
```java
@Bean
public TsidFactory tsidFactory(@Value("${opennms.tsid.node-id:0}") int nodeId) {
    return new TsidFactory(nodeId);
}
```

**7. EventUtil (minimal):**
Same no-op as Pollerd — returns inputs unchanged (no database-backed token resolution).

- [ ] **Step 2: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-collectd compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdJpaConfiguration.java
git commit -m "feat(collectd): add JPA configuration with entities and DAOs"
```

---

## Task 4: RPC Configuration

**Files:**
- Create: `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdRpcConfiguration.java`

- [ ] **Step 1: Create CollectdRpcConfiguration.java**

Wire the Kafka RPC clients for remote collection via Minion. Follow the Karaf loader XML bean definitions:

```java
@Configuration
public class CollectdRpcConfiguration {

    @Bean
    public LocalServiceCollectorRegistry serviceCollectorRegistry() {
        return new LocalServiceCollectorRegistry();
    }

    @Bean
    public Executor collectorExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public CollectorClientRpcModule collectorClientRpcModule() {
        return new CollectorClientRpcModule();
    }

    @Bean
    public LocationAwareCollectorClientImpl locationAwareCollectorClient() {
        return new LocationAwareCollectorClientImpl();
        // @Autowired fields: rpcModule, rpcClientFactory, rpcTargetHelper, entityScopeProvider
        // afterPropertiesSet() creates RPC delegate
    }

    // SNMP RPC for SnmpCollector (LocationAwareSnmpClient)
    @Bean
    public SnmpProxyRpcModule snmpProxyRpcModule() {
        return new SnmpProxyRpcModule();
    }

    @Bean
    public LocationAwareSnmpClientImpl locationAwareSnmpClient() {
        return new LocationAwareSnmpClientImpl();
    }
}
```

`RpcClientFactory` and `RpcTargetHelper` are provided by `KafkaRpcClientConfiguration` in daemon-common (auto-scanned). `EntityScopeProvider` is provided by `DaemonProvisioningConfiguration` (no-op default).

- [ ] **Step 2: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-collectd compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdRpcConfiguration.java
git commit -m "feat(collectd): add RPC configuration for Minion collection"
```

---

## Task 5: Daemon Configuration (Core Wiring)

**Files:**
- Create: `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdDaemonConfiguration.java`

This is the largest and most critical file. Follow the Karaf loader XML (`applicationContext-daemon-loader-collectd.xml`) for bean inventory.

- [ ] **Step 1: Create CollectdDaemonConfiguration.java**

Sections:

**1. Config factories:**
```java
@Bean
@DependsOn("filterDaoInitializer")
public CollectdConfigFactory collectdConfigFactory() {
    return new CollectdConfigFactory();
}

@Bean
public SnmpPeerFactory snmpPeerFactory() throws Exception {
    SnmpPeerFactory.init();
    return SnmpPeerFactory.getInstance();
}
```

**2. Resource type mapper (required by collection pipeline):**
```java
@Bean
public DefaultResourceTypesDao resourceTypesDao() {
    return new DefaultResourceTypesDao();
}

@Bean
public DefaultResourceTypeMapper defaultResourceTypeMapper(ResourceTypesDao resourceTypesDao) {
    DefaultResourceTypeMapper mapper = new DefaultResourceTypeMapper();
    // mapper has @Autowired ResourceTypesDao + @PostConstruct registerWithTypeMapper()
    return mapper;
}
```

**3. Collection agent factory:**
```java
@Bean
public DefaultSnmpCollectionAgentFactory collectionAgentFactory() {
    return new DefaultSnmpCollectionAgentFactory();
}
```

**4. Poll outages (optional, same as Pollerd):**
```java
@Bean
public ReadablePollOutagesDao pollOutagesDao() {
    return new OnmsPollOutagesDao(new NoOpJsonStore<>());
}
```

**5. TSS Persistence Pipeline:**
```java
@Bean
@ConditionalOnProperty(name = "opennms.timeseries.strategy", havingValue = "inmemory", matchIfMissing = true)
public TimeSeriesStorage inMemoryStorage() {
    return new InMemoryStorage();
}

@Bean
public TimeseriesStorageManager timeseriesStorageManager(TimeSeriesStorage storage) {
    TimeseriesStorageManagerImpl manager = new TimeseriesStorageManagerImpl();
    manager.onBind(storage, Map.of());
    return manager;
}

@Bean
public TimeseriesWriterConfig timeseriesWriterConfig() {
    TimeseriesWriterConfig config = new TimeseriesWriterConfig();
    config.setBufferSize(8192);
    config.setNumWriterThreads(16);
    return config;
}

@Bean
public StatisticsCollector statisticsCollector() {
    return new StatisticsCollectorImpl(Runtime.getRuntime().availableProcessors());
}

@Bean
public MetricRegistry timeseriesMetricRegistry() {
    return new MetricRegistry();
}

@Bean
public CacheConfig timeseriesPersisterMetaTagCache() {
    CacheConfig config = new CacheConfig();
    config.setName("timeseriesPersisterMetaTagCache");
    config.setMaximumSize(8192);
    config.setExpireAfterWrite(300);
    return config;
}

@Bean
public MetaTagDataLoader metaTagDataLoader(NodeDao nodeDao, SessionUtils sessionUtils,
                                            EntityScopeProvider entityScopeProvider) {
    return new MetaTagDataLoader(nodeDao, sessionUtils, entityScopeProvider);
}

@Bean
public PersisterFactory persisterFactory(MetaTagDataLoader metaTagDataLoader,
                                          StatisticsCollector stats,
                                          TimeseriesStorageManager storageManager,
                                          CacheConfig timeseriesPersisterMetaTagCache,
                                          MetricRegistry timeseriesMetricRegistry,
                                          TimeseriesWriterConfig writerConfig) {
    return new TimeseriesPersisterFactory(metaTagDataLoader, stats, storageManager,
            timeseriesPersisterMetaTagCache, timeseriesMetricRegistry, writerConfig);
}
```

**6. No-op thresholding (same anonymous class pattern as Pollerd):**
```java
@Bean
public ThresholdingService thresholdingService() {
    return new ThresholdingService() {
        @Override
        public ThresholdingSession createSession(int nodeId, String hostAddress,
                String serviceName, ServiceParameters serviceParameters)
                throws ThresholdInitializationException {
            return null;
        }

        @Override
        public ThresholdingSetPersister getThresholdingSetPersister() {
            return null;
        }
    };
}
```

**7. Collectd daemon bean:**
```java
@Bean
public Collectd collectd(EventIpcManager eventIpcManager) {
    Collectd collectd = new Collectd();
    collectd.setEventIpcManager(eventIpcManager);
    return collectd;
}
```
All `@Autowired` fields on Collectd (`CollectdConfigFactory`, `IpInterfaceDao`, `FilterDao`, `ServiceCollectorRegistry`, `LocationAwareCollectorClient`, `TransactionTemplate`, `NodeDao`, `PersisterFactory`, `ThresholdingService`, `ReadablePollOutagesDao`, `EntityScopeProvider`) are satisfied by beans defined in this config, JpaConfiguration, RpcConfiguration, or daemon-common.

**8. Event registration:**

No `AnnotationBasedEventListenerAdapter` needed. Collectd implements `EventListener` directly and self-registers via `getEventIpcManager().addEventListener(this, ueiList)` in its `onInit()` method. The `EventIpcManager` bean (provided by daemon-common's Kafka event transport) is injected via the `setEventIpcManager()` setter on the Collectd bean above. This matches how Pollerd handles its own event registration — Poller creates `PollerEventProcessor` internally during `init()`.

**Important:** Verify that the `EventIpcManager` injected is the Kafka-backed implementation from daemon-common (`KafkaEventIpcManagerAdapter`), NOT a `LocalEventIpcManager`. In the Karaf world, Collectd sometimes received a local dispatcher. In Delta-V, cross-service eventing requires the Kafka transport. The daemon-common auto-scan provides this — confirm by checking that `KafkaEventTransportConfiguration` is loaded (look for "Kafka event transport" in startup logs).

**9. Lifecycle:**
```java
@Bean
public SmartLifecycle collectdLifecycle(Collectd collectd) {
    return new DaemonSmartLifecycle(collectd);
}
```
`DaemonSmartLifecycle` has a single-arg constructor; phase `Integer.MAX_VALUE` is hardcoded internally (starts last, stops first).

- [ ] **Step 2: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-collectd compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdDaemonConfiguration.java
git commit -m "feat(collectd): add daemon configuration with TSS persistence and event wiring"
```

---

## Task 6: Application Configuration (application.yml)

**Files:**
- Create: `core/daemon-boot-collectd/src/main/resources/application.yml`

- [ ] **Step 1: Create application.yml**

Based on Pollerd's `application.yml`, with Collectd-specific consumer group:

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
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-collectd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
  rpc:
    kafka:
      enabled: ${OPENNMS_RPC_KAFKA_ENABLED:true}
      bootstrap-servers: ${OPENNMS_RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
  timeseries:
    strategy: ${OPENNMS_TIMESERIES_STRATEGY:inmemory}
```

**Note:** No `logback-spring.xml` or `META-INF/jakarta-rename.properties` needed — Pollerd doesn't have them either. Spring Boot's default logging and no XML namespace remapping required.

- [ ] **Step 2: Commit**

```bash
git add core/daemon-boot-collectd/src/main/resources/application.yml
git commit -m "feat(collectd): add application.yml configuration"
```

---

## Task 7: Build Verification

- [ ] **Step 1: Full module compile with dependency resolution**

Run:
```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-collectd -am package
```
Expected: BUILD SUCCESS, fat JAR created at `core/daemon-boot-collectd/target/org.opennms.core.daemon-boot-collectd-36.0.0-SNAPSHOT-boot.jar`

- [ ] **Step 2: Verify fat JAR starts (smoke test)**

Run:
```bash
java -jar core/daemon-boot-collectd/target/org.opennms.core.daemon-boot-collectd-36.0.0-SNAPSHOT-boot.jar --spring.main.lazy-initialization=true --server.port=0 2>&1 | head -50
```
Expected: Spring Boot banner appears, context starts loading. Will fail on DataSource (no PostgreSQL) but should get past class loading — confirms no classpath conflicts.

- [ ] **Step 3: Check for javax.persistence/jakarta.persistence conflicts**

Run:
```bash
jar tf core/daemon-boot-collectd/target/org.opennms.core.daemon-boot-collectd-36.0.0-SNAPSHOT-boot.jar | grep -i 'hibernate-jpa-2.0\|hibernate-core-3\|javax.persistence-2.0'
```
Expected: No matches (legacy Hibernate excluded, only `javax.persistence-api-2.2` at runtime)

- [ ] **Step 4: Commit build validation results**

If any fixes were needed, commit them:
```bash
git add -u
git commit -m "fix(collectd): resolve build/classpath issues"
```

---

## Task 8: Docker Integration

**Files:**
- Modify: `opennms-container/delta-v/Dockerfile.springboot:24`
- Modify: `opennms-container/delta-v/docker-compose.yml:158-191`

- [ ] **Step 1: Add JAR to Dockerfile.springboot**

After line 24 (`daemon-boot-enlinkd.jar`), add:
```dockerfile
COPY staging/daemon/daemon-boot-collectd.jar /opt/daemon-boot-collectd.jar
```

- [ ] **Step 2: Replace Karaf-based collectd service in docker-compose.yml**

Replace the existing `collectd:` service definition (lines 158-191) with the Spring Boot version:

```yaml
  collectd:
    profiles: [lite, full]
    image: opennms/daemon-deltav-springboot:${VERSION}
    container_name: delta-v-collectd
    hostname: collectd
    depends_on:
      db-init:
        condition: service_completed_successfully
      kafka:
        condition: service_healthy
    command: ["java", "-Xms512m", "-Xmx1g",
              "-Dorg.opennms.tsid.node-id=5",
              "-jar", "/opt/daemon-boot-collectd.jar"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
      SPRING_DATASOURCE_USERNAME: opennms
      SPRING_DATASOURCE_PASSWORD: opennms
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      OPENNMS_HOME: /opt/deltav
      OPENNMS_TIMESERIES_STRATEGY: inmemory
    volumes:
      - ./collectd-daemon-overlay/etc:/opt/deltav/etc:ro
    stop_grace_period: 60s
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 10
```

Key changes from Karaf version:
- Image: `daemon-deltav-springboot` (not `daemon-deltav`)
- Command: `java -jar /opt/daemon-boot-collectd.jar` (not Karaf)
- Port: 8080 (Spring Boot actuator, not 8181 Karaf)
- Volumes: `/opt/deltav/etc` (not `/opt/sentinel-etc-overlay`)
- Health: Spring Boot actuator (not Karaf REST)
- Added: `stop_grace_period: 60s`
- Removed: `collectd-data` volume (no Sentinel data dir needed)

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.springboot opennms-container/delta-v/docker-compose.yml
git commit -m "feat(collectd): integrate Spring Boot collectd into Docker pipeline"
```

---

## Task 9: Local Docker Smoke Test

- [ ] **Step 1: Build the Spring Boot image**

Run:
```bash
cd opennms-container/delta-v && make build-springboot
```
Expected: Image builds successfully with collectd JAR included.

- [ ] **Step 2: Start the stack with collectd**

Run:
```bash
cd opennms-container/delta-v && docker compose --profile lite up -d collectd
```
Expected: Container starts, health check passes.

- [ ] **Step 3: Verify Collectd daemon starts**

Run:
```bash
docker compose logs collectd 2>&1 | grep -E 'Collectd|scheduleInterface|Started CollectdApplication'
```
Expected: See "Started CollectdApplication" and Collectd init/start messages. Collection scheduling will depend on whether nodes exist in the DB and `findMatching()` is implemented.

- [ ] **Step 4: Verify actuator health**

Run:
```bash
curl -sf http://localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 5: Document results and commit any fixes**

```bash
git add -u
git commit -m "fix(collectd): resolve Docker integration issues"
```

---

## Task 10: E2E Test Script (Skeleton)

**Files:**
- Create: `opennms-container/delta-v/test-collectd-e2e.sh`

- [ ] **Step 1: Create test-collectd-e2e.sh skeleton**

Follow the pattern from `test-enlinkd-e2e.sh`. The script should:
1. Source shared helpers (`wait_for_db`, `psql_query`)
2. Verify Collectd container is healthy
3. Check Collectd logs for "scheduleInterface" messages (proves scheduling works)
4. Check Collectd logs for "collect" messages (proves collection cycles run)
5. If `findMatching()` is implemented: verify `ifservices` table has entries with collection enabled

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-helpers.sh"

echo "=== Collectd E2E Test ==="

# 1. Wait for Collectd health
wait_for_health "collectd" "http://localhost:8080/actuator/health" 120

# 2. Check scheduling
echo "Checking Collectd scheduling..."
if docker compose logs collectd 2>&1 | grep -q "scheduleInterface"; then
    echo "PASS: Collectd scheduled interfaces for collection"
elif docker compose logs collectd 2>&1 | grep -q "No collection packages matched"; then
    echo "FAIL: findMatching() blocker — no collection packages matched (AbstractDaoJpa.findMatching() unimplemented)"
    echo "  This is the known prerequisite blocker. Collectd started but cannot schedule collection."
else
    echo "WARN: No scheduleInterface messages found (check logs for errors)"
fi

# 3. Check collection cycles
echo "Checking collection cycles..."
sleep 60  # Wait for at least one collection interval
if docker compose logs collectd 2>&1 | grep -q "collectData"; then
    echo "PASS: Collectd completed collection cycles"
else
    echo "WARN: No collection cycles detected"
fi

echo "=== Collectd E2E Test Complete ==="
```

- [ ] **Step 2: Make executable and commit**

```bash
chmod +x opennms-container/delta-v/test-collectd-e2e.sh
git add opennms-container/delta-v/test-collectd-e2e.sh
git commit -m "feat(collectd): add E2E test skeleton for collection verification"
```

---

## Summary

| Task | What it does | Estimated complexity |
|------|-------------|---------------------|
| 1 | Maven module (pom.xml) | Medium — dependency exclusions are the tricky part |
| 2 | Application entry point | Trivial |
| 3 | JPA configuration | Medium — entity list, FilterDao init |
| 4 | RPC configuration | Medium — multiple RPC beans |
| 5 | Daemon configuration | **High** — TSS pipeline, config factories, event wiring |
| 6 | application.yml | Trivial |
| 7 | Build verification | Medium — classpath conflict debugging |
| 8 | Docker integration | Low — template replacement |
| 9 | Docker smoke test | Medium — debugging startup issues |
| 10 | E2E test skeleton | Low |

**Critical path:** Tasks 1→2→3→4→5→6→7 must be sequential (each builds on the prior). Tasks 8-10 can follow after 7 passes.

**Known blocker:** `findMatching()` must be implemented in `opennms-model-jakarta` for Collectd to schedule any collection (spec risk section). The daemon will start and pass health checks without it, but won't actually collect data.
