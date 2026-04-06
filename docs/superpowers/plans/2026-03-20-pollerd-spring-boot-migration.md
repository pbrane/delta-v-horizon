# Pollerd Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Pollerd from Karaf/OSGi to a standalone Spring Boot 4 application so it has deterministic bean initialization, direct Kafka event forwarding, and no OSGi service registry dependencies.

**Architecture:** New Maven module `core/daemon-boot-pollerd` with four `@Configuration` classes (JPA, RPC, PassiveStatus, Daemon) following the established pattern from 7 prior daemon migrations. Pollerd delegates all poll execution to Minion via Kafka RPC. Events flow through `KafkaEventForwarder` to the `opennms-fault-events` Kafka topic.

**Tech Stack:** Spring Boot 4.0.3, Hibernate 7 (Jakarta Persistence), Kafka (event transport + RPC + Twin API), PostgreSQL, Java 21.

**Spec:** `docs/superpowers/specs/2026-03-20-pollerd-spring-boot-migration-design.md`

---

## File Map

### New Files
- `core/daemon-boot-pollerd/pom.xml` — Maven module with Spring Boot 4 + legacy dependency management
- `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdApplication.java` — Entry point
- `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdJpaConfiguration.java` — JPA entities, DAOs, no-ops
- `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdRpcConfiguration.java` — Kafka RPC clients
- `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdPassiveStatusConfiguration.java` — Twin API
- `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdDaemonConfiguration.java` — Core daemon wiring
- `core/daemon-boot-pollerd/src/main/resources/application.yml` — Spring Boot config
- `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsOutage.java` — Jakarta entity port
- `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/OutageDaoJpa.java` — JPA DAO

### Modified Files
- `opennms-services/src/main/java/org/opennms/netmgt/poller/Poller.java` — Constructor injection (8 fields)
- `opennms-services/src/main/java/org/opennms/netmgt/poller/QueryManagerDaoImpl.java` — Constructor injection (5 fields)
- `opennms-services/src/main/java/org/opennms/netmgt/poller/DefaultPollContext.java` — Constructor injection (setters → constructor)
- `core/daemon-loader-pollerd/src/main/java/org/opennms/core/daemon/loader/StandalonePollContext.java` — Move to daemon-boot-pollerd, update constructor
- `features/poller/client-rpc/src/main/java/org/opennms/netmgt/poller/client/rpc/LocationAwarePollerClientImpl.java` — Constructor injection (5 fields)
- `opennms-icmp/opennms-icmp-proxy-rpc-impl/src/main/java/org/opennms/netmgt/icmp/proxy/LocationAwarePingClientImpl.java` — Constructor injection (3 fields)
- `opennms-container/delta-v/docker-compose.yml` — Switch pollerd to Spring Boot JAR
- `opennms-container/delta-v/build.sh` — Stage daemon-boot-pollerd JAR
- `pom.xml` (root) — Add daemon-boot-pollerd module

---

### Task 1: Port OnmsOutage to Jakarta Persistence

**Files:**
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsOutage.java` (493 lines)
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsOutage.java`
- Reference: Existing Jakarta entity `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAlarm.java` for annotation patterns

- [ ] **Step 1: Copy OnmsOutage to Jakarta module**

Copy `opennms-model/.../OnmsOutage.java` to `core/opennms-model-jakarta/.../OnmsOutage.java`. Change package imports from `javax.persistence.*` to `jakarta.persistence.*`. Replace `org.hibernate.annotations.Type` with Hibernate 7 equivalents. Replace `@Filter` with Hibernate 7 `@Filter`. Remove `@Temporal` annotations (deprecated in Hibernate 7 — use `java.time` types or leave as-is if `java.util.Date` still compiles).

- [ ] **Step 2: Verify Jakarta entity compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.model.jakarta -am install`
Expected: BUILD SUCCESS

- [ ] **Step 3: Create OutageDaoJpa**

Create `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/OutageDaoJpa.java` extending `AbstractDaoJpa<OnmsOutage, Integer>` with `@Repository`. Follow pattern from existing `AlarmDaoJpa` in same package. Must implement `OutageDao` interface from `opennms-dao-api`.

- [ ] **Step 4: Verify DAO compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.model.jakarta -am install`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```
git add core/opennms-model-jakarta/
git commit -m "feat: port OnmsOutage entity and OutageDaoJpa to Jakarta Persistence"
```

---

### Task 2: Convert Poller, QueryManagerDaoImpl, DefaultPollContext to Constructor Injection

**Files:**
- Modify: `opennms-services/src/main/java/org/opennms/netmgt/poller/Poller.java`
- Modify: `opennms-services/src/main/java/org/opennms/netmgt/poller/QueryManagerDaoImpl.java`
- Modify: `opennms-services/src/main/java/org/opennms/netmgt/poller/DefaultPollContext.java`
- Modify: `core/daemon-loader-pollerd/src/main/resources/META-INF/opennms/applicationContext-daemon-loader-pollerd.xml` (update bean definitions to use constructor-arg)

- [ ] **Step 1: Convert Poller to constructor injection**

Replace 8 `@Autowired` fields with a constructor. Remove setter methods that only exist for injection. Keep setters needed by Spring XML (Karaf still uses XML wiring until the Karaf loader is removed). Add `final` to injected fields.

- [ ] **Step 2: Convert QueryManagerDaoImpl to constructor injection**

Replace 5 `@Autowired` fields (`m_nodeDao`, `m_outageDao`, `m_monitoredServiceDao`, `m_ipInterfaceDao`, `m_transcationOps`) with a constructor. Add `final` to fields.

- [ ] **Step 3: Convert DefaultPollContext to constructor injection**

Replace setter-injected fields with a constructor. `DefaultPollContext` has: `m_eventManager`, `m_pollerConfig`, `m_queryManager`, `m_nodeDao`, `m_tsidFactory`, `m_localHostName`, `m_name`. All currently set via `<property>` in the Spring XML context.

**Important:** `StandalonePollContext` extends `DefaultPollContext` and must get a matching constructor that calls `super(...)`. Update `StandalonePollContext` in `core/daemon-loader-pollerd/` in this same step to keep the Karaf build compiling.

- [ ] **Step 4: Convert LocationAwarePollerClientImpl to constructor injection**

Replace 5 `@Autowired` fields with a constructor: `serviceMonitorRegistry`, `pollerClientRpcModule`, `rpcClientFactory`, `rpcTargetHelper`, `entityScopeProvider`. Remove `afterPropertiesSet()` call from old constructor. Add `final` to fields.

- [ ] **Step 5: Convert LocationAwarePingClientImpl to constructor injection**

Replace 3 `@Autowired` fields with a constructor: `rpcClientFactory`, `pingProxyRpcModule`, `pingSweepRpcModule`. Add `final` to fields.

- [ ] **Step 6: Update Karaf Spring XML context**

Update `applicationContext-daemon-loader-pollerd.xml` to use `<constructor-arg>` instead of `<property>` for the converted beans. This keeps the Karaf loader working during the transition.

- [ ] **Step 7: Verify Karaf build still compiles**

Run: `./compile.pl -DskipTests --projects :opennms-services,:org.opennms.core.daemon-loader-pollerd,:org.opennms.features.poller.client-rpc,:org.opennms.netmgt.icmp.proxy.rpc -am install`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
git add opennms-services/ core/daemon-loader-pollerd/ features/poller/client-rpc/ opennms-icmp/
git commit -m "refactor: convert Poller, QueryManagerDaoImpl, DefaultPollContext, LocationAwarePollerClientImpl, LocationAwarePingClientImpl to constructor injection"
```

---

### Task 3: Create daemon-boot-pollerd Maven Module (Skeleton)

**Files:**
- Create: `core/daemon-boot-pollerd/pom.xml`
- Create: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdApplication.java`
- Create: `core/daemon-boot-pollerd/src/main/resources/application.yml`
- Modify: `pom.xml` (root) — add module

- [ ] **Step 1: Create pom.xml**

Use `core/daemon-boot-provisiond/pom.xml` as the template. Key differences:
- `artifactId`: `org.opennms.core.daemon-boot-pollerd`
- Dependencies: `daemon-common`, `opennms-services`, `poller-client-rpc`, `opennms-model-jakarta`, `opennms-config`, `icmp-rpc-common`
- Same Spring Boot 4.0.3 parent, same exclusion patterns for servicemix/OSGi bundles
- Spring Boot Maven plugin with `boot` classifier for repackaging

- [ ] **Step 2: Create PollerdApplication.java**

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

- [ ] **Step 3: Create application.yml**

Copy from spec section "application.yml". Includes datasource, JPA/Hibernate, Kafka event transport, Kafka RPC, and opennms.home configuration.

- [ ] **Step 4: Add module to root pom.xml**

Add `<module>core/daemon-boot-pollerd</module>` in the modules section, near the other daemon-boot modules.

- [ ] **Step 5: Verify skeleton compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install`
Expected: BUILD SUCCESS (empty app with no config classes yet)

- [ ] **Step 6: Commit**

```
git add core/daemon-boot-pollerd/ pom.xml
git commit -m "feat: add daemon-boot-pollerd Maven module skeleton"
```

---

### Task 4: PollerdJpaConfiguration

**Files:**
- Create: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdJpaConfiguration.java`
- Reference: `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdConfiguration.java` (JPA section)
- Reference: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java` (JPA + FilterDao section)

- [ ] **Step 1: Create PollerdJpaConfiguration with entity list**

`PersistenceManagedTypes.of(...)` listing: OnmsNode, OnmsIpInterface, OnmsMonitoredService, OnmsServiceType, OnmsOutage, OnmsMonitoringSystem, OnmsMonitoringLocation, OnmsDistPoller, OnmsCategory. Add `PhysicalNamingStrategyStandardImpl` bean.

- [ ] **Step 2: Add SessionUtils and TransactionTemplate beans**

Follow Alarmd pattern: `SessionUtils` wrapping `TransactionTemplate` wrapping `PlatformTransactionManager`.

- [ ] **Step 3: Add FilterDaoFactory initialization**

Create a bean that initializes `FilterDaoFactory` with the Spring-managed `DataSource`. Use `@DependsOn` or bean ordering to ensure it runs before `PollerConfigFactory.init()`.

Reference `ProvisiondBootConfiguration` for how Provisiond handles `FilterDaoFactory`.

- [ ] **Step 4: Add QueryManagerDaoImpl bean**

Wire with constructor args: `nodeDao`, `outageDao`, `monitoredServiceDao`, `ipInterfaceDao`, `transactionOperations`.

- [ ] **Step 5: Add TsidFactory bean**

```java
@Bean
public TsidFactory tsidFactory(@Value("${opennms.tsid.node-id:0}") long nodeId) {
    return new TsidFactory(nodeId);
}
```

- [ ] **Step 6: Add no-op stubs**

- `PersisterFactory` — no-op implementation that discards metrics
- `ThresholdingService` — no-op implementation
- `EventUtil` — no-op implementation

- [ ] **Step 7: Verify compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
git add core/daemon-boot-pollerd/
git commit -m "feat: add PollerdJpaConfiguration — entities, DAOs, no-op stubs"
```

---

### Task 5: PollerdRpcConfiguration

**Files:**
- Create: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdRpcConfiguration.java`
- Reference: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java`
- Reference: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java` (RPC section)

- [ ] **Step 1: Create PollerdRpcConfiguration**

Beans:
- `LocalServiceMonitorRegistry` — `new LocalServiceMonitorRegistry()` (ServiceLoader-based)
- `PollerClientRpcModule` — stateless RPC protocol definition bean
- `PingProxyRpcModule` — stateless ICMP ping RPC module
- `PingSweepRpcModule` — stateless ICMP sweep RPC module
- `LocationAwarePollerClientImpl` — constructor (after Task 2 conversion): `serviceMonitorRegistry`, `pollerClientRpcModule`, `rpcClientFactory`, `rpcTargetHelper`, `entityScopeProvider`
- `LocationAwarePingClientImpl` — constructor (after Task 2 conversion): `rpcClientFactory`, `pingProxyRpcModule`, `pingSweepRpcModule`

`KafkaRpcClientFactory`, `RpcTargetHelper`, and `NoOpEntityScopeProvider` are auto-wired from `KafkaRpcClientConfiguration` and `DaemonProvisioningConfiguration` in daemon-common (gated by `opennms.rpc.kafka.enabled=true`). Verify that `DaemonProvisioningConfiguration` provides `EntityScopeProvider` via `@ConditionalOnMissingBean`.

- [ ] **Step 2: Verify compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
git add core/daemon-boot-pollerd/
git commit -m "feat: add PollerdRpcConfiguration — Kafka RPC clients for Minion"
```

---

### Task 6: PollerdPassiveStatusConfiguration

**Files:**
- Create: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdPassiveStatusConfiguration.java`
- Reference: `core/daemon-loader-pollerd/src/main/resources/META-INF/opennms/applicationContext-daemon-loader-pollerd.xml` (passive status section)
- Reference: `opennms-services/src/main/java/org/opennms/netmgt/passive/PassiveStatusKeeper.java`
- Reference: `opennms-services/src/main/java/org/opennms/netmgt/passive/PassiveStatusTwinPublisher.java`

- [ ] **Step 1: Create PollerdPassiveStatusConfiguration**

Beans:
- `PassiveStatusKeeper` — create instance, call `PassiveStatusKeeper.setInstance(keeper)`, wire eventManager and dataSource
- `InlineIdentity` — identity provider from `DistPollerDao.whoami()`
- `MetricRegistry` — dedicated instance for Twin publisher metrics (qualified bean name to avoid conflicts)
- `LocalTwinSubscriberImpl` — local Twin subscriber for same-JVM consumers. Constructor: `identity`
- `KafkaTwinPublisher` — use 3-arg constructor: `localTwinSubscriber`, `identity`, `metricRegistry`. The publisher internally creates its own `OnmsKafkaConfigProvider` to read Kafka bootstrap servers from system properties. `TracerRegistry` comes from `NoOpTracerRegistry` in daemon-common's `KafkaRpcClientConfiguration`.
- `PassiveStatusTwinPublisher` — constructor: `twinPublisher`, `passiveStatusKeeper`, `eventIpcManager`
- `AnnotationBasedEventListenerAdapter` for `PassiveStatusTwinPublisher`

Note: `TracerRegistry` and `NoOpTracerRegistry` are provided by daemon-common via component scanning of `org.opennms.core.daemon.common`.

- [ ] **Step 2: Verify compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
git add core/daemon-boot-pollerd/
git commit -m "feat: add PollerdPassiveStatusConfiguration — Twin API for passive status"
```

---

### Task 7: PollerdDaemonConfiguration

**Files:**
- Create: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdDaemonConfiguration.java`
- Move: `core/daemon-loader-pollerd/.../StandalonePollContext.java` → `core/daemon-boot-pollerd/.../boot/StandalonePollContext.java`
- Reference: `opennms-services/src/main/java/org/opennms/netmgt/poller/Poller.java`

- [ ] **Step 1: Move StandalonePollContext to daemon-boot-pollerd**

Copy `StandalonePollContext` from `core/daemon-loader-pollerd/` to `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/`. Update package declaration. Update constructor to match `DefaultPollContext`'s new constructor injection signature.

- [ ] **Step 2: Create PollerdDaemonConfiguration**

Beans:
- `PollerConfigFactory` — `PollerConfigFactory.init()` then `PollerConfigFactory.getInstance()` (singleton pattern). Must run after FilterDaoFactory init.
- `PollOutagesConfigManager` — singleton init from `opennms.home`
- `StandalonePollContext` — constructor: `eventIpcManager`, `pollerConfig`, `queryManager`, `nodeDao`, `tsidFactory`, `localHostName`, `name("OpenNMS.Poller.DefaultPollContext")`
- `PollableNetwork` — `new PollableNetwork(pollContext)`
- `Poller` — constructor: `queryManager`, `monitoredServiceDao`, `outageDao`, `transactionTemplate`, `persisterFactory`, `thresholdingService`, `locationAwarePollerClient`, `pollOutagesDao`. Then set pollerConfig, pollContext, pollableNetwork.
- `DaemonSmartLifecycle` wrapping Poller

- [ ] **Step 3: Verify compiles**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```
git add core/daemon-boot-pollerd/
git commit -m "feat: add PollerdDaemonConfiguration — core daemon, poll context, lifecycle"
```

---

### Task 8: Docker Integration

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`
- Modify: `opennms-container/delta-v/build.sh`
- Remove/simplify: `opennms-container/delta-v/pollerd-daemon-overlay/etc/` (Karaf files)
- Create: `opennms-container/delta-v/pollerd-overlay/etc/poller-configuration.xml` (Spring Boot overlay)

- [ ] **Step 1: Update docker-compose.yml**

Change `pollerd` service:
- `command: ["java", "-Xms512m", "-Xmx1g", "-jar", "/opt/daemon-boot-pollerd.jar"]`
- `entrypoint: []`
- Remove Sentinel volume mounts
- Add Spring Boot environment variables (datasource, Kafka, RPC)
- Add volume mount for poller-configuration.xml: `./pollerd-overlay/etc:/opt/deltav/etc:ro`

- [ ] **Step 2: Create Spring Boot pollerd overlay**

Move `poller-configuration.xml` from `pollerd-daemon-overlay/etc/` to `pollerd-overlay/etc/`. Keep only `poller-configuration.xml` and `poll-outages.xml` (if needed). Remove all Karaf-specific files.

- [ ] **Step 3: Update build.sh**

Add `daemon-boot-pollerd` JAR to the Delta-V staging directory:
```bash
cp core/daemon-boot-pollerd/target/org.opennms.core.daemon-boot-pollerd-*-boot.jar staging/daemon/daemon-boot-pollerd.jar
```

- [ ] **Step 4: Build and verify image**

Run: `./build.sh deltav`
Expected: Image builds, `daemon-boot-pollerd.jar` present in image.

- [ ] **Step 5: Commit**

```
git add opennms-container/delta-v/
git commit -m "feat: switch Pollerd Docker service to Spring Boot JAR"
```

---

### Task 9: BSM E2E Verification

**Files:**
- Reference: `opennms-container/delta-v/scripts/setup-bsm-e2e.sh`

- [ ] **Step 1: Full build**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd -am install
cd opennms-container/delta-v && ./build.sh deltav
```

- [ ] **Step 2: Clean deploy**

```bash
docker compose --profile full down -v
docker compose --profile full up -d
sleep 180
```

- [ ] **Step 3: Verify Pollerd starts as Spring Boot app**

```bash
docker compose logs pollerd 2>&1 | grep "Started PollerdApplication"
```
Expected: `Started PollerdApplication in X seconds`

- [ ] **Step 4: Verify services are polled — BSM status normal**

```bash
curl -sf http://localhost:8180/api/v3/business-services/.../status | jq '.operationalStatus'
```
Expected: `"normal"`

- [ ] **Step 5: Run BSM E2E test**

```bash
./scripts/setup-bsm-e2e.sh --test
```

Expected output:
- `PASS: BSM detected failure — status=major`
- `PASS: BSM recovered — status=normal`
- `=== BSM E2E test PASSED ===`

- [ ] **Step 6: Commit any fixes needed during verification**

- [ ] **Step 7: Final commit**

```
git commit -m "fix: Pollerd Spring Boot migration — verified with BSM E2E test"
```
