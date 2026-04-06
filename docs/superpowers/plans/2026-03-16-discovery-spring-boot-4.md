# Discovery Spring Boot 4 Migration — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Discovery to a Spring Boot 4.0.3 microservice that scans IP ranges via Kafka RPC to Minion and publishes NEW_SUSPECT events.

**Architecture:** Add `KafkaRpcClientConfiguration` to `daemon-common` (shared RPC infrastructure for 6 daemons), then create `daemon-boot-discovery` following the established pattern. Discovery uses `DaemonSmartLifecycle` for timer-based scanning, `KafkaRpcClientFactory` for Minion RPC, and JDBC for node cache.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Apache Kafka (event transport + RPC), PostgreSQL (JDBC)

**Spec:** `docs/superpowers/specs/2026-03-15-discovery-spring-boot-4-design.md`

---

## File Structure

### Modified: `core/daemon-common/`

| File | Change |
|------|--------|
| `src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java` | **New** — `KafkaRpcClientFactory`, `RpcTargetHelper`, `NoOpTracerRegistry` beans |
| `src/main/java/org/opennms/core/daemon/common/NoOpTracerRegistry.java` | **New** — copied from `daemon-loader-shared`, new package |
| `pom.xml` | Add RPC deps: `org.opennms.core.ipc.rpc.kafka`, `org.opennms.core.ipc.rpc.api`, tracing API |

### New: `core/daemon-boot-discovery/`

| File | Responsibility |
|------|---------------|
| `pom.xml` | Maven module — Spring Boot 4, daemon-common, discovery feature, ICMP/detector RPC modules |
| `src/main/java/.../discovery/boot/DiscoveryApplication.java` | Spring Boot entry point |
| `src/main/java/.../discovery/boot/DiscoveryBootConfiguration.java` | Wires all Discovery beans |
| `src/main/java/.../discovery/boot/NoOpEntityScopeProvider.java` | Empty MATE scope provider |
| `src/main/resources/application.yml` | DataSource, Kafka, logging config |
| `src/test/java/.../discovery/boot/NoOpEntityScopeProviderTest.java` | Unit test |

### Modified: Docker/Build

| File | Change |
|------|--------|
| `core/pom.xml` | Add `daemon-boot-discovery`, remove `daemon-loader-discovery` |
| `opennms-container/delta-v/build.sh` | Stage `daemon-boot-discovery.jar`, remove `daemon-loader-discovery.jar` |
| `opennms-container/delta-v/Dockerfile.daemon` | COPY `daemon-boot-discovery.jar`, remove daemon-loader-discovery COPY |
| `opennms-container/delta-v/docker-compose.yml` | Replace discovery service with Spring Boot config |
| `container/features/src/main/resources/features.xml` | Remove `opennms-daemon-discovery` feature |

### Deleted

| Path | Reason |
|------|--------|
| `core/daemon-loader-discovery/` | Replaced by daemon-common RPC + daemon-boot-discovery |

---

## Chunk 1: KafkaRpcClientConfiguration in daemon-common

### Task 1: Add Kafka RPC client infrastructure to daemon-common

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java`
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/NoOpTracerRegistry.java`
- Modify: `core/daemon-common/pom.xml`

- [ ] **Step 1: Add RPC dependencies to daemon-common POM**

Add to `core/daemon-common/pom.xml` dependencies section. Check existing deps first — some may already be present.

```xml
        <!-- Kafka RPC client (shared by Discovery, Pollerd, Collectd, Enlinkd, etc.) -->
        <dependency>
            <groupId>org.opennms.core.ipc.rpc</groupId>
            <artifactId>org.opennms.core.ipc.rpc.kafka</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-tx</artifactId></exclusion>
                <exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- RPC utils (RpcTargetHelper) -->
        <dependency>
            <groupId>org.opennms.core.rpc</groupId>
            <artifactId>org.opennms.core.rpc.utils</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Tracing API (TracerRegistry interface for NoOpTracerRegistry) -->
        <dependency>
            <groupId>org.opennms.core.tracing</groupId>
            <artifactId>org.opennms.core.tracing.api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- OpenTracing (required by KafkaRpcClientFactory) -->
        <dependency>
            <groupId>io.opentracing</groupId>
            <artifactId>opentracing-api</artifactId>
            <version>${opentracingVersion}</version>
        </dependency>
        <dependency>
            <groupId>io.opentracing</groupId>
            <artifactId>opentracing-util</artifactId>
            <version>${opentracingVersion}</version>
        </dependency>
```

Note: Check if these deps or their versions are already in daemon-common's POM. If so, skip.

- [ ] **Step 2: Create NoOpTracerRegistry**

Copy from `core/daemon-loader-shared/src/main/java/org/opennms/core/daemon/loader/NoOpTracerRegistry.java` with new package:

```java
package org.opennms.core.daemon.common;

import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import org.opennms.core.tracing.api.TracerRegistry;

/**
 * No-op TracerRegistry for Spring Boot daemon containers.
 * Returns the GlobalTracer (which defaults to NoopTracer).
 * Satisfies KafkaRpcClientFactory's @Autowired TracerRegistry.
 */
public class NoOpTracerRegistry implements TracerRegistry {

    @Override
    public Tracer getTracer() {
        return GlobalTracer.get();
    }

    @Override
    public void init(String serviceName) {
        // No-op
    }
}
```

- [ ] **Step 3: Create KafkaRpcClientConfiguration**

```java
package org.opennms.core.daemon.common;

import com.codahale.metrics.MetricRegistry;

import org.opennms.core.ipc.rpc.kafka.KafkaRpcClientFactory;
import org.opennms.core.rpc.utils.RpcTargetHelper;
import org.opennms.core.tracing.api.TracerRegistry;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} for the Kafka RPC client infrastructure.
 *
 * <p>Shared by all daemons that send Kafka RPC requests to Minions:
 * Discovery, Pollerd, Collectd, Enlinkd, PerspectivePoller, Provisiond.</p>
 *
 * <p>Replaces the shared {@code kafka-rpc-client-factory.xml} used by
 * Karaf daemon loaders.</p>
 *
 * <p>{@code KafkaRpcClientFactory} reads bootstrap servers from the system
 * property {@code org.opennms.core.ipc.rpc.kafka.bootstrap.servers}.
 * The Docker command must include
 * {@code -Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092}.</p>
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "opennms.rpc.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaRpcClientConfiguration {

    @Bean
    public TracerRegistry tracerRegistry() {
        return new NoOpTracerRegistry();
    }

    @Bean
    public MetricRegistry kafkaRpcMetricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public RpcTargetHelper rpcTargetHelper() {
        return new RpcTargetHelper();
    }

    /**
     * Conditional on {@code opennms.rpc.kafka.enabled=true} to avoid breaking
     * daemons that don't use RPC (Trapd, Syslogd). Without the system property
     * {@code org.opennms.core.ipc.rpc.kafka.bootstrap.servers}, the
     * KafkaProducer constructor in {@code start()} will throw.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "opennms.rpc.kafka.enabled", havingValue = "true", matchIfMissing = false)
    public KafkaRpcClientFactory rpcClientFactory(DistPollerDao distPollerDao,
                                                   MetricRegistry kafkaRpcMetricRegistry) {
        var factory = new KafkaRpcClientFactory();
        factory.setLocation(distPollerDao.whoami().getLocation());
        factory.setMetrics(kafkaRpcMetricRegistry);
        return factory;
    }
}
```

- [ ] **Step 4: Compile daemon-common**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml compile`

Fix any dependency conflicts.

- [ ] **Step 5: Run tests**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml test`

- [ ] **Step 6: Install**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml -DskipTests install`

- [ ] **Step 7: Verify Trapd and Syslogd still compile**

Run: `./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml -DskipTests compile`
Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml -DskipTests compile`

The new RPC beans in daemon-common should not interfere with Trapd/Syslogd since they don't provide a `DistPollerDao` bean in scan — wait, they DO provide `DistPollerDao` via their `@Configuration` classes. The `rpcClientFactory` bean will try to call `distPollerDao.whoami()` at startup in Trapd/Syslogd too. This is fine — the RPC factory just won't be used by those daemons.

The `KafkaRpcClientConfiguration` class and its `rpcClientFactory` bean are annotated with `@ConditionalOnProperty(name = "opennms.rpc.kafka.enabled")`. Only Discovery (and future RPC daemons) set this to `true` in their `application.yml`. Trapd/Syslogd don't set it, so the RPC beans are not created for them.

- [ ] **Step 8: Commit**

```bash
git add core/daemon-common/
git commit -m "feat: add KafkaRpcClientConfiguration to daemon-common — shared RPC infrastructure

KafkaRpcClientFactory, RpcTargetHelper, NoOpTracerRegistry for all daemons
that send Kafka RPC requests to Minions. Replaces kafka-rpc-client-factory.xml."
```

---

## Chunk 2: daemon-boot-discovery Module

### Task 2: Create POM and DiscoveryApplication

**Files:**
- Create: `core/daemon-boot-discovery/pom.xml`
- Create: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryApplication.java`
- Create: `core/daemon-boot-discovery/src/main/resources/application.yml`
- Modify: `core/pom.xml`

- [ ] **Step 1: Create POM**

Fork from `core/daemon-boot-trapd/pom.xml`. Key differences:
- artifactId: `org.opennms.core.daemon-boot-discovery`
- Replace traps feature with discovery feature: `org.opennms.features:org.opennms.features.discovery`
- Add discovery-specific deps:
  - `org.opennms:opennms-icmp-api` (PingerFactory interface)
  - `org.opennms:opennms-icmp-best` (BestMatchPingerFactory)
  - `org.opennms:org.opennms.icmp.proxy.rpc-impl` (LocationAwarePingClientImpl, PingSweepRpcModule, PingProxyRpcModule)
  - `org.opennms:opennms-provision-api` (LocationAwareDetectorClient, LocationAwareDnsLookupClient)
  - `org.opennms:opennms-detectorclient-rpc` (LocationAwareDetectorClientRpcImpl, DetectorClientRpcModule)
  - `org.opennms.core:org.opennms.core.daemon-loader-shared` (LocalServiceDetectorRegistry)
  - `org.opennms.core.mate:org.opennms.core.mate.api` (EntityScopeProvider)
  - `io.dropwizard.metrics:metrics-core` (for KafkaRpcClientFactory MetricRegistry)
- Keep: daemon-common, daemon-sink-kafka NOT needed, opennms-config (for DiscoveryConfigFactory), opennms-dao-api, opennms-model
- Same ServiceMix exclusions, Jackson 2.19.4 pins, JUnit pins, logback pins

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-discovery</module>` after `daemon-boot-syslogd`.

- [ ] **Step 3: Create DiscoveryApplication**

```java
package org.opennms.netmgt.discovery.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.netmgt.discovery.boot"
    },
    exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
    }
)
@EnableScheduling
public class DiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }
}
```

Note: No `daemon-sink-kafka` in scan — Discovery doesn't use Kafka Sink.

- [ ] **Step 4: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always

opennms:
  home: ${OPENNMS_HOME:/opt/opennms}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-discovery}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
  rpc:
    kafka:
      enabled: ${RPC_KAFKA_ENABLED:true}
  daemon:
    interface-to-node-cache:
      refresh-interval-ms: ${INTERFACE_NODE_CACHE_REFRESH_MS:300000}

logging:
  level:
    org.opennms: INFO
    org.opennms.netmgt.discovery: INFO
```

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-discovery/ core/pom.xml
git commit -m "feat: add daemon-boot-discovery module — Spring Boot 4 Discovery skeleton"
```

### Task 3: Create NoOpEntityScopeProvider with test

**Files:**
- Create: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProvider.java`
- Create: `core/daemon-boot-discovery/src/test/java/org/opennms/netmgt/discovery/boot/NoOpEntityScopeProviderTest.java`

- [ ] **Step 1: Write test**

```java
package org.opennms.netmgt.discovery.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.opennms.core.mate.api.Scope;
import org.junit.jupiter.api.Test;

class NoOpEntityScopeProviderTest {

    private final NoOpEntityScopeProvider provider = new NoOpEntityScopeProvider();

    @Test
    void getScopeForNode_returnsEmptyScope() {
        Scope scope = provider.getScopeForNode(42);
        assertThat(scope).isNotNull();
    }

    @Test
    void getScopeForScv_returnsEmptyScope() {
        Scope scope = provider.getScopeForScv();
        assertThat(scope).isNotNull();
    }

    @Test
    void getScopeForInterface_returnsEmptyScope() {
        Scope scope = provider.getScopeForInterface(1, "192.168.1.1");
        assertThat(scope).isNotNull();
    }
}
```

- [ ] **Step 2: Write implementation**

```java
package org.opennms.netmgt.discovery.boot;

import java.net.InetAddress;

import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.Scope;

/**
 * No-op EntityScopeProvider for Spring Boot daemon containers.
 *
 * <p>Returns empty scopes for all entity types. MATE variable interpolation
 * (e.g., {@code ${requisition:username}}) is disabled.</p>
 *
 * <p><strong>Deferred:</strong> Real MATE support requires scope resolution
 * from the database (node/interface/service attributes).</p>
 */
public class NoOpEntityScopeProvider implements EntityScopeProvider {

    private static final Scope EMPTY = new EmptyScope();

    @Override public Scope getScopeForScv() { return EMPTY; }
    @Override public Scope getScopeForEnv() { return EMPTY; }
    @Override public Scope getScopeForNode(Integer nodeId) { return EMPTY; }
    @Override public Scope getScopeForInterface(Integer nodeId, String ipAddress) { return EMPTY; }
    @Override public Scope getScopeForInterfaceByIfIndex(Integer nodeId, int ifIndex) { return EMPTY; }
    @Override public Scope getScopeForService(Integer nodeId, InetAddress ipAddress, String serviceName) { return EMPTY; }
}
```

- [ ] **Step 3: Run test**

Run: `./maven/bin/mvn -f core/daemon-boot-discovery/pom.xml test -Dtest=NoOpEntityScopeProviderTest`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-discovery/src/
git commit -m "feat: add NoOpEntityScopeProvider — empty MATE scopes for Discovery"
```

### Task 4: Create DiscoveryBootConfiguration

**Files:**
- Create: `core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryBootConfiguration.java`

- [ ] **Step 1: Write DiscoveryBootConfiguration**

This is the largest configuration class — it replaces `applicationContext-daemon-loader-discovery.xml`.

```java
package org.opennms.netmgt.discovery.boot;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import com.codahale.metrics.MetricRegistry;

import org.opennms.core.daemon.common.DaemonSmartLifecycle;
import org.opennms.core.daemon.common.JdbcDistPollerDao;
import org.opennms.core.daemon.common.JdbcInterfaceToNodeCache;
import org.opennms.core.daemon.loader.LocalServiceDetectorRegistry;
import org.opennms.core.ipc.rpc.kafka.KafkaRpcClientFactory;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.DiscoveryConfigFactory;
import org.opennms.netmgt.dao.api.AbstractInterfaceToNodeCache;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.opennms.netmgt.discovery.Discovery;
import org.opennms.netmgt.discovery.DiscoveryTaskExecutorImpl;
import org.opennms.netmgt.discovery.RangeChunker;
import org.opennms.netmgt.discovery.UnmanagedInterfaceFilter;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.events.api.AnnotationBasedEventListenerAdapter;
import org.opennms.netmgt.icmp.best.BestMatchPingerFactory;
import org.opennms.netmgt.icmp.PingerFactory;
import org.opennms.netmgt.icmp.proxy.LocationAwarePingClientImpl;
import org.opennms.netmgt.icmp.proxy.PingProxyRpcModule;
import org.opennms.netmgt.icmp.proxy.PingSweepRpcModule;
import org.opennms.netmgt.provision.LocationAwareDetectorClient;
import org.opennms.netmgt.provision.detector.client.rpc.DetectorClientRpcModule;
import org.opennms.netmgt.provision.detector.client.rpc.LocationAwareDetectorClientRpcImpl;
import org.opennms.netmgt.provision.detector.registry.api.ServiceDetectorRegistry;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot @Configuration that wires all Discovery beans.
 *
 * <p>Replaces the Karaf-era {@code applicationContext-daemon-loader-discovery.xml}.</p>
 */
@Configuration
public class DiscoveryBootConfiguration {

    // ── DAO / Cache ──

    @Bean
    public DistPollerDao distPollerDao(DataSource dataSource) {
        return new JdbcDistPollerDao(dataSource);
    }

    @Bean
    public InterfaceToNodeCache interfaceToNodeCache(DataSource dataSource) {
        return new JdbcInterfaceToNodeCache(dataSource);
    }

    // ── Discovery Config ──

    /**
     * Reads discovery-configuration.xml from ${opennms.home}/etc/.
     * The Docker overlay MUST include this file or startup will fail with IOException.
     * Verify: ls discovery-overlay/etc/discovery-configuration.xml
     */
    @Bean
    public DiscoveryConfigFactory discoveryConfigFactory() throws Exception {
        return new DiscoveryConfigFactory();
    }

    // ── ICMP Ping RPC ──

    @Bean
    public PingerFactory pingerFactory() {
        return new BestMatchPingerFactory();
    }

    @Bean
    public PingProxyRpcModule pingProxyRpcModule() {
        return new PingProxyRpcModule();
    }

    @Bean
    public PingSweepRpcModule pingSweepRpcModule() {
        return new PingSweepRpcModule();
    }

    /**
     * IMPORTANT: Uses {@code javax.annotation.PostConstruct} which Spring 7
     * does NOT recognize. Must use {@code initMethod = "init"}.
     */
    @Bean(initMethod = "init")
    public LocationAwarePingClientImpl locationAwarePingClient() {
        return new LocationAwarePingClientImpl();
    }

    // ── Detector RPC ──

    @Bean
    public EntityScopeProvider entityScopeProvider() {
        return new NoOpEntityScopeProvider();
    }

    @Bean
    public ServiceDetectorRegistry serviceDetectorRegistry() {
        return new LocalServiceDetectorRegistry();
    }

    @Bean(name = "scanExecutor")
    public Executor scanExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public DetectorClientRpcModule detectorClientRpcModule() {
        return new DetectorClientRpcModule();
    }

    @Bean
    public LocationAwareDetectorClient locationAwareDetectorClient() {
        // Implements InitializingBean — Spring 7 calls afterPropertiesSet() natively
        return new LocationAwareDetectorClientRpcImpl();
    }

    // ── Discovery Core ──

    @Bean
    public UnmanagedInterfaceFilter unmanagedInterfaceFilter(InterfaceToNodeCache cache) {
        return new UnmanagedInterfaceFilter(cache);
    }

    @Bean
    public RangeChunker rangeChunker(UnmanagedInterfaceFilter filter) {
        return new RangeChunker(filter);
    }

    @Bean
    public DiscoveryTaskExecutorImpl discoveryTaskExecutor() {
        // @Autowired fields: rangeChunker, locationAwarePingClient,
        //   eventForwarder (@Primary), locationAwareDetectorClient (optional)
        return new DiscoveryTaskExecutorImpl();
    }

    @Bean
    public Discovery discovery() {
        // @Autowired fields: discoveryConfigFactory, discoveryTaskExecutor,
        //   eventForwarder (@Qualifier("eventIpcManager"))
        return new Discovery();
    }

    @Bean
    public SmartLifecycle discoveryLifecycle(Discovery discovery) {
        return new DaemonSmartLifecycle(discovery);
    }

    // Note: Config reload via RELOAD_DAEMON_CONFIG_UEI events is NOT wired.
    // Discovery.reloadAndReStart() is private with no @EventHandler annotation.
    // To reload config, restart the container. This is acceptable for the
    // initial migration; event-driven reload can be added later.
}
```

- [ ] **Step 2: Compile**

Run: `./maven/bin/mvn -f core/daemon-boot-discovery/pom.xml compile`

Fix any dependency conflicts.

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-discovery/src/main/java/org/opennms/netmgt/discovery/boot/DiscoveryBootConfiguration.java
git commit -m "feat: add DiscoveryBootConfiguration — wires all Discovery beans for Spring Boot 4"
```

---

## Chunk 3: Docker Infrastructure and Cleanup

### Task 5: Update Docker infrastructure

**Files:**
- Modify: `opennms-container/delta-v/build.sh`
- Modify: `opennms-container/delta-v/Dockerfile.daemon`
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Update build.sh**

In `do_stage_daemon_jars()`, find `daemon-loader-discovery` and replace with `daemon-boot-discovery`:
```
"core/daemon-boot-discovery/target/org.opennms.core.daemon-boot-discovery-$VERSION.jar:daemon-boot-discovery.jar"
```

- [ ] **Step 2: Update Dockerfile.daemon**

Remove daemon-loader-discovery COPY. There may not be an explicit COPY for it — check if Discovery was using the generic `daemon-deltav` image with Karaf features. If there IS a COPY line, remove it. Add after the syslogd boot JAR COPY:
```dockerfile
COPY staging/daemon/daemon-boot-discovery.jar /opt/daemon-boot-discovery.jar
```

- [ ] **Step 3: Update docker-compose.yml**

Replace the discovery service block with:

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

Remove `discovery-data:` from volumes section if present.

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/
git commit -m "build: update Docker infrastructure for Spring Boot Discovery"
```

### Task 6: Delete old Karaf artifacts

- [ ] **Step 1: Remove Karaf feature**

Search for `opennms-daemon-discovery` in `container/features/src/main/resources/features.xml` and delete the entire feature block.

- [ ] **Step 2: Remove module from core/pom.xml, delete directory**

```bash
# Remove <module>daemon-loader-discovery</module> from core/pom.xml
git rm -r core/daemon-loader-discovery/
git add core/pom.xml container/features/
git commit -m "refactor: delete daemon-loader-discovery and Karaf feature — replaced by Spring Boot"
```

---

## Chunk 4: Build Verification

### Task 7: Compile, test, package

- [ ] **Step 1: Install dependencies**

```bash
./maven/bin/mvn -f core/daemon-common/pom.xml -DskipTests install
./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml -DskipTests install
```

- [ ] **Step 2: Compile daemon-boot-discovery**

Run: `./maven/bin/mvn -f core/daemon-boot-discovery/pom.xml -DskipTests compile`

- [ ] **Step 3: Run all tests**

```bash
./maven/bin/mvn -f core/daemon-common/pom.xml test
./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test
./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml test
./maven/bin/mvn -f core/daemon-boot-discovery/pom.xml test
```

- [ ] **Step 4: Package fat JAR**

Run: `./maven/bin/mvn -f core/daemon-boot-discovery/pom.xml -DskipTests package`
Verify: `ls -lh core/daemon-boot-discovery/target/org.opennms.core.daemon-boot-discovery-36.0.0-SNAPSHOT.jar`

- [ ] **Step 5: Fix dependency conflicts and commit**

```bash
git add -A
git commit -m "fix: resolve Spring Boot 4 dependency conflicts for Discovery"
```

---

## Chunk 5: Memory Update

### Task 8: Update project memory

- [ ] **Step 1: Update migration status**

Add Discovery to completed daemons in `project_spring_boot_4_migration.md`. Note KafkaRpcClientConfiguration as shared infrastructure.

- [ ] **Step 2: Update README.md**

Update daemon status table and counts (5 Spring Boot, 8 Karaf).
