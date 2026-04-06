# PerspectivePollerd Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate PerspectivePollerd from Karaf/OSGi to a standalone Spring Boot 4 application.

**Architecture:** Clone-and-adapt from the completed Pollerd Spring Boot migration (`core/daemon-boot-pollerd/`). Three configuration classes (JPA, RPC, Daemon) instead of Pollerd's four (no PassiveStatus). No `opennms-services` dependency — daemon class lives in `features/perspectivepoller/`. Event handling via two `AnnotationBasedEventListenerAdapter` beans for PerspectivePollerd and PerspectiveServiceTracker.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Hibernate 7, Kafka RPC, PostgreSQL, Docker

**Spec:** `docs/superpowers/specs/2026-03-21-perspectivepollerd-spring-boot-migration-design.md`

---

## File Structure

### New files to create:
- `core/daemon-boot-perspectivepollerd/pom.xml` — Maven module (clone Pollerd POM, replace opennms-services with perspectivepoller feature, remove Twin publisher dep)
- `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdApplication.java` — Spring Boot entry point
- `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdJpaConfiguration.java` — JPA/DAO/stubs config
- `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdRpcConfiguration.java` — Kafka RPC config
- `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java` — Daemon + event wiring
- `core/daemon-boot-perspectivepollerd/src/main/resources/application.yml` — Spring Boot config
- `opennms-container/delta-v/perspectivepollerd-overlay/etc/poller-configuration.xml` — symlink (already exists)
- `opennms-container/delta-v/perspectivepollerd-overlay/etc/poll-outages.xml` — copy from pollerd-overlay
- `opennms-container/delta-v/perspectivepollerd-overlay/etc/database-schema.xml` — copy from pollerd-overlay

### Files to modify:
- `core/pom.xml` — add `daemon-boot-perspectivepollerd` module
- `opennms-container/delta-v/docker-compose.yml` — replace Sentinel-style entry with Spring Boot entry
- `opennms-container/delta-v/build.sh` — add JAR staging

---

## Task 1: Create Maven Module

**Files:**
- Create: `core/daemon-boot-perspectivepollerd/pom.xml`
- Modify: `core/pom.xml`

- [ ] **Step 1: Create pom.xml**

Clone from `core/daemon-boot-pollerd/pom.xml` with these changes:
- `artifactId`: `org.opennms.core.daemon-boot-perspectivepollerd`
- `name`: `OpenNMS :: Core :: Daemon Boot :: PerspectivePollerd`
- Replace `opennms-services` dependency with `org.opennms.features.perspectivepoller` (same exclusion block for ServiceMix/Hibernate/SLF4J)
- Remove `org.opennms.core.ipc.twin.kafka.publisher` dependency (not needed — no Twin API)
- Remove `org.opennms.core.daemon-loader-pollerd` dependency
- Remove `org.opennms.features.distributed.kv-store.json.noop` dependency (no PollOutagesDao)
- Keep all other dependencies identical (model-jakarta, daemon-common, poller-client-rpc, opennms-config, icmp-rpc-common, javax.persistence, jakarta.xml.bind, jaxb-runtime, jakarta.inject, jaxb-api, slf4j, spring-boot-starter-web, test deps)

**Important:** The `features/perspectivepoller` module uses `<packaging>bundle</packaging>` (OSGi) and depends on `opennms-model` and `opennms-dao` (legacy javax.persistence). The exclusion block must cover these transitive deps the same way `opennms-services` exclusions do. After creating the POM, run `mvn dependency:tree` and verify no Hibernate 3.x or conflicting javax.persistence classes leak onto the classpath.

Reference: `core/daemon-boot-pollerd/pom.xml`

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-perspectivepollerd</module>` after the `daemon-boot-pollerd` module entry at line 45.

- [ ] **Step 3: Verify module compiles (empty)**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-perspectivepollerd -am install
```
Expected: BUILD SUCCESS (empty module with dependencies resolving)

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-perspectivepollerd/pom.xml core/pom.xml
git commit -m "feat: add daemon-boot-perspectivepollerd Maven module"
```

---

## Task 2: Application Entry Point and application.yml

**Files:**
- Create: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdApplication.java`
- Create: `core/daemon-boot-perspectivepollerd/src/main/resources/application.yml`

- [ ] **Step 1: Create PerspectivePollerdApplication.java**

```java
package org.opennms.netmgt.perspectivepoller.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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

- [ ] **Step 2: Create application.yml**

Clone from `core/daemon-boot-pollerd/src/main/resources/application.yml` with one change:
- `consumer-group` default: `opennms-perspectivepollerd` (was `opennms-pollerd`)

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

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-perspectivepollerd/src/
git commit -m "feat: add PerspectivePollerd Spring Boot entry point and config"
```

---

## Task 3: JPA Configuration

**Files:**
- Create: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdJpaConfiguration.java`

- [ ] **Step 1: Create PerspectivePollerdJpaConfiguration.java**

Clone from `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdJpaConfiguration.java` with these changes:

**Keep identical:**
- Section 1 (physicalNamingStrategy bean)
- Section 2 (sessionUtils bean, transactionTemplate bean)
- Section 3 (filterDaoInitializer bean)
- Section 6 no-op stubs: PersisterFactory, ThresholdingService, EventUtil
- PersistenceManagedTypes entity list (identical — same entities including OnmsApplication)

**Remove:**
- Section 4 (QueryManager / QueryManagerDaoImpl) — PerspectivePollerd doesn't use QueryManager
- Section 5 (TsidFactory) — PerspectivePollerd doesn't generate TSIDs for outages (it sets them from event dbid)

**Add:**
- No-op `CollectionAgentFactory` bean — required by PerspectivePollerd constructor but never called at runtime:

```java
@Bean
public CollectionAgentFactory collectionAgentFactory() {
    return new CollectionAgentFactory() {
        @Override
        public org.opennms.netmgt.collection.api.CollectionAgent createCollectionAgent(
                OnmsIpInterface ipInterface) {
            throw new UnsupportedOperationException("Not used in standalone PerspectivePollerd");
        }
        @Override
        public org.opennms.netmgt.collection.api.CollectionAgent createCollectionAgent(
                String nodeCriteria, java.net.InetAddress ipAddr) {
            throw new UnsupportedOperationException("Not used in standalone PerspectivePollerd");
        }
        @Override
        public org.opennms.netmgt.collection.api.CollectionAgent createCollectionAgentAndOverrideLocation(
                String nodeCriteria, java.net.InetAddress ipAddr, String location) {
            throw new UnsupportedOperationException("Not used in standalone PerspectivePollerd");
        }
    };
}
```

**Change package declaration** to `org.opennms.netmgt.perspectivepoller.boot` and class name to `PerspectivePollerdJpaConfiguration`.

Reference: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdJpaConfiguration.java`

- [ ] **Step 2: Verify compilation**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-perspectivepollerd -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdJpaConfiguration.java
git commit -m "feat: add PerspectivePollerd JPA configuration"
```

---

## Task 4: RPC Configuration

**Files:**
- Create: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdRpcConfiguration.java`

- [ ] **Step 1: Create PerspectivePollerdRpcConfiguration.java**

Clone `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdRpcConfiguration.java` verbatim. Only changes:

- Package: `org.opennms.netmgt.perspectivepoller.boot`
- Class name: `PerspectivePollerdRpcConfiguration`
- Javadoc: update to reference PerspectivePollerd

All beans are identical — same ServiceMonitorRegistry, pollerExecutor, PingerFactory, PollerClientRpcModule, PingProxyRpcModule, PingSweepRpcModule, LocationAwarePollerClient, LocationAwarePingClient.

Reference: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdRpcConfiguration.java`

- [ ] **Step 2: Verify compilation**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-perspectivepollerd -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdRpcConfiguration.java
git commit -m "feat: add PerspectivePollerd RPC configuration"
```

---

## Task 5: Daemon Configuration

**Files:**
- Create: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java`

This is the key configuration class that differs most from Pollerd. It wires PerspectivePollerd (not Poller), PerspectiveServiceTracker, and two AnnotationBasedEventListenerAdapters.

- [ ] **Step 1: Create PerspectivePollerdDaemonConfiguration.java**

```java
package org.opennms.netmgt.perspectivepoller.boot;

import java.io.IOException;

import org.opennms.core.daemon.common.DaemonSmartLifecycle;
import org.opennms.core.tracing.api.TracerRegistry;
import org.opennms.netmgt.collection.api.CollectionAgentFactory;
import org.opennms.netmgt.collection.api.PersisterFactory;
import org.opennms.netmgt.config.PollerConfig;
import org.opennms.netmgt.config.PollerConfigFactory;
import org.opennms.netmgt.dao.api.ApplicationDao;
import org.opennms.netmgt.dao.api.MonitoredServiceDao;
import org.opennms.netmgt.dao.api.MonitoringLocationDao;
import org.opennms.netmgt.dao.api.OutageDao;
import org.opennms.netmgt.dao.api.SessionUtils;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.AnnotationBasedEventListenerAdapter;
import org.opennms.netmgt.perspectivepoller.PerspectivePollerd;
import org.opennms.netmgt.perspectivepoller.PerspectiveServiceTracker;
import org.opennms.netmgt.poller.LocationAwarePollerClient;
import org.opennms.netmgt.threshd.api.ThresholdingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.SmartLifecycle;

/**
 * Spring Boot configuration for PerspectivePollerd daemon and event wiring.
 *
 * <p>PerspectivePollerd and PerspectiveServiceTracker both use annotation-based
 * event handling ({@code @EventListener} / {@code @EventHandler}). In Spring Boot
 * (without OSGi annotation scanning), each needs an explicit
 * {@link AnnotationBasedEventListenerAdapter} bean wired to the
 * {@link EventIpcManager}.</p>
 *
 * <p>Bean ordering: {@link PollerConfigFactory#init()} calls
 * {@code FilterDaoFactory.getInstance()} internally, so the
 * {@code filterDaoInitializer} bean in {@link PerspectivePollerdJpaConfiguration}
 * must be initialized first. This is enforced via {@code @DependsOn}.</p>
 */
@Configuration
public class PerspectivePollerdDaemonConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PerspectivePollerdDaemonConfiguration.class);

    @Bean
    @DependsOn("filterDaoInitializer")
    public PollerConfig pollerConfig() throws IOException {
        LOG.info("Initializing PollerConfigFactory");
        PollerConfigFactory.init();
        return PollerConfigFactory.getInstance();
    }

    @Bean
    public PerspectiveServiceTracker perspectiveServiceTracker(
            SessionUtils sessionUtils,
            ApplicationDao applicationDao) {
        return new PerspectiveServiceTracker(sessionUtils, applicationDao);
    }

    @Bean
    public PerspectivePollerd perspectivePollerd(
            SessionUtils sessionUtils,
            MonitoringLocationDao monitoringLocationDao,
            PollerConfig pollerConfig,
            MonitoredServiceDao monitoredServiceDao,
            LocationAwarePollerClient locationAwarePollerClient,
            ApplicationDao applicationDao,
            CollectionAgentFactory collectionAgentFactory,
            PersisterFactory persisterFactory,
            EventIpcManager eventIpcManager,
            ThresholdingService thresholdingService,
            OutageDao outageDao,
            TracerRegistry tracerRegistry,
            PerspectiveServiceTracker perspectiveServiceTracker) {
        return new PerspectivePollerd(sessionUtils, monitoringLocationDao, pollerConfig,
                monitoredServiceDao, locationAwarePollerClient, applicationDao,
                collectionAgentFactory, persisterFactory, eventIpcManager,
                thresholdingService, outageDao, tracerRegistry, perspectiveServiceTracker);
    }

    /**
     * Event listener adapter for PerspectivePollerd — handles:
     * - PERSPECTIVE_NODE_LOST_SERVICE (creates perspective outage)
     * - PERSPECTIVE_NODE_REGAINED_SERVICE (resolves perspective outage)
     * - RELOAD_DAEMON_CONFIG (reloads poller config, restarts scheduler)
     *
     * No initMethod needed — the 2-arg constructor calls afterPropertiesSet()
     * internally, which registers event subscriptions with EventIpcManager.
     */
    @Bean
    public AnnotationBasedEventListenerAdapter perspectivePollerdEventAdapter(
            PerspectivePollerd perspectivePollerd,
            EventIpcManager eventIpcManager) {
        return new AnnotationBasedEventListenerAdapter(perspectivePollerd, eventIpcManager);
    }

    /**
     * Event listener adapter for PerspectiveServiceTracker — handles 14 topology-change
     * events (nodeGainedService, serviceDeleted, applicationChanged, etc.) that trigger
     * perspective service list refresh.
     */
    @Bean
    public AnnotationBasedEventListenerAdapter perspectiveServiceTrackerEventAdapter(
            PerspectiveServiceTracker perspectiveServiceTracker,
            EventIpcManager eventIpcManager) {
        return new AnnotationBasedEventListenerAdapter(perspectiveServiceTracker, eventIpcManager);
    }

    @Bean
    public SmartLifecycle perspectivePollerdLifecycle(PerspectivePollerd perspectivePollerd) {
        return new DaemonSmartLifecycle(perspectivePollerd);
    }
}
```

**Key differences from PollerdDaemonConfiguration:**
- No PollOutagesDao, PollContext, PollableNetwork, or Poller beans
- No QueryManager dependency
- Two AnnotationBasedEventListenerAdapter beans (Pollerd has none — its event listener self-registers)
- PerspectivePollerd constructor takes 13 params (Poller takes 8 + 3 setters)
- The 2-arg constructor of `AnnotationBasedEventListenerAdapter` calls `afterPropertiesSet()` internally, which registers event subscriptions with `EventIpcManager` based on `@EventHandler` UEIs

**Important:** The `EventForwarder` parameter in PerspectivePollerd's constructor is satisfied by the `EventIpcManager` bean from daemon-common (KafkaEventTransportConfiguration), which implements both `EventIpcManager` and `EventForwarder`. Use `EventIpcManager` as the parameter type so it resolves to the single `@Primary` bean.

- [ ] **Step 2: Verify compilation**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-perspectivepollerd -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java
git commit -m "feat: add PerspectivePollerd daemon and event listener configuration"
```

---

## Task 6: Docker Integration

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml` (lines 448-479)
- Modify: `opennms-container/delta-v/build.sh`
- Create: `opennms-container/delta-v/perspectivepollerd-overlay/etc/poll-outages.xml` (copy)
- Create: `opennms-container/delta-v/perspectivepollerd-overlay/etc/database-schema.xml` (copy)

- [ ] **Step 1: Update docker-compose.yml**

Replace the existing `perspectivepollerd` service entry (lines 448-479) which uses Sentinel-style deployment with a Spring Boot entry:

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
      db-init:
        condition: service_completed_successfully
      kafka:
        condition: service_healthy
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
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Also remove the `perspectivepollerd-data:` volume from the bottom `volumes:` section (Spring Boot doesn't need persistent data volume).

Note: `OPENNMS_TSID_NODE_ID: "9"` — the existing Sentinel entry used `7`. Verify this doesn't collide with other daemons in the stack; pick the next available unique ID.

- [ ] **Step 2: Update build.sh**

Add the perspectivepollerd JAR to the staging array, after the pollerd entry (around line 141):

```bash
"core/daemon-boot-perspectivepollerd/target/org.opennms.core.daemon-boot-perspectivepollerd-$VERSION-boot.jar:daemon-boot-perspectivepollerd.jar"
```

- [ ] **Step 3: Add config overlay files**

The `perspectivepollerd-overlay/etc/` directory already has `poller-configuration.xml` (symlink) and Karaf config files. Remove the Karaf config files and add Spring Boot equivalents:

```bash
# Remove Karaf-specific files
rm -rf opennms-container/delta-v/perspectivepollerd-overlay/etc/featuresBoot.d
rm opennms-container/delta-v/perspectivepollerd-overlay/etc/org.opennms.*.cfg

# Copy needed config from pollerd-overlay
cp opennms-container/delta-v/pollerd-overlay/etc/poll-outages.xml opennms-container/delta-v/perspectivepollerd-overlay/etc/
cp opennms-container/delta-v/pollerd-overlay/etc/database-schema.xml opennms-container/delta-v/perspectivepollerd-overlay/etc/
```

Final overlay contents should be:
- `poller-configuration.xml` (existing symlink to `../../pollerd-daemon-overlay/etc/poller-configuration.xml` — verify this resolves in the Docker build context; if the build copies files rather than following symlinks, replace with a direct copy)
- `poll-outages.xml` (copied from pollerd-overlay)
- `database-schema.xml` (copied from pollerd-overlay — needed by FilterDaoFactory)

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml opennms-container/delta-v/build.sh opennms-container/delta-v/perspectivepollerd-overlay/
git commit -m "feat: update Docker integration for Spring Boot PerspectivePollerd"
```

---

## Task 7: Build and Verify

- [ ] **Step 1: Build the module**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-perspectivepollerd -am install
```
Expected: BUILD SUCCESS with boot JAR at `core/daemon-boot-perspectivepollerd/target/org.opennms.core.daemon-boot-perspectivepollerd-36.0.0-SNAPSHOT-boot.jar`

- [ ] **Step 2: Verify boot JAR was created**

```bash
ls -la core/daemon-boot-perspectivepollerd/target/*-boot.jar
```
Expected: JAR file exists, approximately 50-100MB

- [ ] **Step 3: Build Docker image**

```bash
cd opennms-container/delta-v && VERSION=36.0.0-SNAPSHOT ./build.sh
```
Expected: Image builds with perspectivepollerd JAR included

- [ ] **Step 4: Start the stack and verify PerspectivePollerd starts**

```bash
cd opennms-container/delta-v && VERSION=36.0.0-SNAPSHOT docker compose --profile full up -d perspectivepollerd
```

Wait for health check, then:
```bash
docker logs delta-v-perspectivepollerd 2>&1 | tail -30
```
Expected: Spring Boot banner, "Started PerspectivePollerdApplication", actuator health UP. PerspectiveServiceTracker should start its timer and query for perspectives (finding none, idling cleanly).

- [ ] **Step 5: Verify actuator health**

```bash
docker exec delta-v-perspectivepollerd curl -sf http://localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 6: Commit (if any fixes were needed)**

Stage only the specific files that were fixed, then commit:
```bash
git add <changed-files> && git commit -m "fix: resolve PerspectivePollerd startup issues"
```

---

## Task 8: Update Dashboard

- [ ] **Step 1: Update migration dashboard docs**

Update `docs/superpowers/specs/` or project dashboard to record PerspectivePollerd as migrated (9 daemons done).

- [ ] **Step 2: Final commit**

```bash
git add docs/ && git commit -m "docs: update dashboard — 9 daemons migrated, PerspectivePollerd complete"
```
