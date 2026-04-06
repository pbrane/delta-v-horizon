# Telemetryd Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Telemetryd from Karaf/OSGi to a standalone Spring Boot 4 application as a pure ingestion bridge (no adapters, no Elasticsearch, no flow processing).

**Architecture:** Clone-and-adapt from PerspectivePollerd (SpringServiceDaemon pattern, inline SmartLifecycle, 0-arg AnnotationBasedEventListenerAdapter). 3 configuration classes: JPA (minimal), Sink (multi-bridge), Daemon (Telemetryd + ConnectorManager + Twin API + no-op stubs for registries and ServiceTracker).

**Tech Stack:** Java 21, Spring Boot 4.0.3, Kafka Sink bridge (per-module), Twin API, Docker

**Spec:** `docs/superpowers/specs/2026-03-22-telemetryd-spring-boot-migration-design.md`

---

## File Structure

### New files to create:
- `core/daemon-boot-telemetryd/pom.xml`
- `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydApplication.java`
- `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydJpaConfiguration.java`
- `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydSinkConfiguration.java`
- `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydDaemonConfiguration.java`
- `core/daemon-boot-telemetryd/src/main/resources/application.yml`

### Files to modify:
- `core/pom.xml` — add module
- `opennms-container/delta-v/docker-compose.yml` — replace Sentinel entry with Spring Boot entry
- `opennms-container/delta-v/build.sh` — add JAR to staging
- `opennms-container/delta-v/Dockerfile.springboot` — add COPY line

---

## Task 1: Create Maven Module

**Files:**
- Create: `core/daemon-boot-telemetryd/pom.xml`
- Modify: `core/pom.xml`

- [ ] **Step 1: Create pom.xml**

Clone from `core/daemon-boot-perspectivepollerd/pom.xml` with these changes:
- `artifactId`: `org.opennms.core.daemon-boot-telemetryd`
- `name`: `OpenNMS :: Core :: Daemon Boot :: Telemetryd`

Replace the `perspectivepoller` dependency with these Telemetryd-specific dependencies (same wildcard ServiceMix exclusion block on each):
- `org.opennms.features.telemetry:org.opennms.features.telemetry.daemon` — Telemetryd, ConnectorManager, OpenConfigTwinPublisherImpl, LocationPublisherManager
- `org.opennms.features.telemetry:org.opennms.features.telemetry.api` — TelemetryManager, TelemetryRegistry interfaces
- `org.opennms.features.telemetry:org.opennms.features.telemetry.common` — TelemetrySinkModule
- `org.opennms.features.telemetry:org.opennms.features.telemetry.config-jaxb` — TelemetrydConfigDao
- `org.opennms.features.telemetry:org.opennms.features.telemetry.registry` — TelemetryRegistryImpl
- `org.opennms.core:org.opennms.core.daemon-loader-telemetryd` — TelemetryMessageConsumerManager, KafkaSinkBridge, LocalMessageDispatcherFactory
- `org.opennms.core.ipc.twin.kafka:org.opennms.core.ipc.twin.kafka.publisher` — KafkaTwinPublisher (same exclusion block as Pollerd's Twin dep)

Keep from PerspectivePollerd POM: `model-jakarta`, `daemon-common`, `opennms-config`, `poller-client-rpc` (for RpcTargetHelper transitive), javax/jakarta compat deps, Spring Boot deps, test deps.

Remove (not needed): `poller-client-rpc`, `icmp-rpc-common` (Telemetryd has no RPC).

**Important:** Add `opennms-dao-api` dependency (for `ServiceTracker`, `ServiceRef` interfaces used by ConnectorManager). Use same exclusion block.

Reference: `core/daemon-boot-perspectivepollerd/pom.xml`

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-telemetryd</module>` after `daemon-boot-perspectivepollerd`.

- [ ] **Step 3: Verify module compiles (empty)**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-telemetryd -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-telemetryd/pom.xml core/pom.xml
git commit -m "feat: add daemon-boot-telemetryd Maven module"
```

---

## Task 2: Application Entry Point + application.yml + JPA Configuration

**Files:**
- Create: `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydApplication.java`
- Create: `core/daemon-boot-telemetryd/src/main/resources/application.yml`
- Create: `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydJpaConfiguration.java`

- [ ] **Step 1: Create TelemetrydApplication.java**

```java
package org.opennms.netmgt.telemetry.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.telemetry.boot",
    "org.opennms.netmgt.model.jakarta.dao"
})
public class TelemetrydApplication {
    public static void main(String[] args) {
        SpringApplication.run(TelemetrydApplication.class, args);
    }
}
```

- [ ] **Step 2: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
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
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-telemetryd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
```

- [ ] **Step 3: Create TelemetrydJpaConfiguration.java**

Minimal JPA config — clone from PerspectivePollerdJpaConfiguration but with a reduced entity list:
- Keep: `PhysicalNamingStrategyStandardImpl`, `SessionUtils`, `TransactionTemplate`
- **Reduce PersistenceManagedTypes** to only: `OnmsMonitoringSystem`, `OnmsMonitoringLocation`, `OnmsDistPoller`
- Keep: no-op `EventUtil` stub
- **Remove**: FilterDaoFactory (Telemetryd doesn't use PollerConfig), PersisterFactory, ThresholdingService, CollectionAgentFactory (none needed for pure bridge)

Reference: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdJpaConfiguration.java`

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-telemetryd -am install
```

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-telemetryd/src/
git commit -m "feat: add Telemetryd application, JPA config, and application.yml"
```

---

## Task 3: Sink Configuration (Multi-Bridge)

**Files:**
- Create: `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydSinkConfiguration.java`

- [ ] **Step 1: Create TelemetrydSinkConfiguration.java**

```java
package org.opennms.netmgt.telemetry.boot;

import org.opennms.core.daemon.loader.LocalMessageDispatcherFactory;
import org.opennms.core.daemon.loader.TelemetryMessageConsumerManager;
import org.opennms.core.ipc.sink.api.MessageConsumerManager;
import org.opennms.core.ipc.sink.api.MessageDispatcherFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelemetrydSinkConfiguration {

    @Bean
    public MessageConsumerManager messageConsumerManager() {
        return new TelemetryMessageConsumerManager();
    }

    @Bean
    public MessageDispatcherFactory messageDispatcherFactory(
            TelemetryMessageConsumerManager messageConsumerManager) {
        return new LocalMessageDispatcherFactory(messageConsumerManager);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-telemetryd -am install
```

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydSinkConfiguration.java
git commit -m "feat: add Telemetryd Sink configuration (multi-bridge)"
```

---

## Task 4: Daemon Configuration

**Files:**
- Create: `core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydDaemonConfiguration.java`

This is the most complex configuration class — wires Telemetryd daemon, ConnectorManager, Twin API, TelemetryRegistry with 4 sub-registries, and no-op stubs.

- [ ] **Step 1: Create TelemetrydDaemonConfiguration.java**

The configuration class must provide:

**Config DAO:**
- `TelemetrydConfigDao` — reads `telemetryd-configuration.xml` from `${opennms.home}/etc/`

**TelemetryRegistry with 4 sub-registries (no-op stubs):**
- `@Bean @Qualifier("adapterRegistry") TelemetryServiceRegistry<AdapterDefinition, Adapter>` — empty no-op
- `@Bean @Qualifier("listenerRegistry") TelemetryServiceRegistry<ListenerDefinition, Listener>` — empty no-op
- `@Bean @Qualifier("connectorRegistry") TelemetryServiceRegistry<ConnectorDefinition, Connector>` — empty no-op
- `@Bean @Qualifier("parserRegistry") TelemetryServiceRegistry<ParserDefinition, Parser>` — empty no-op
- `MetricRegistry` — real Dropwizard MetricRegistry (lightweight)
- `TelemetryRegistryImpl` — wired with all 4 sub-registries + MetricRegistry

**ConnectorManager + Twin API:**
- `ServiceTracker` — no-op stub (returns no-op Closeable from `track()`)
- `KafkaTwinPublisher` — declared as `@Bean TwinPublisher` (interface type). Constructor requires `LocalTwinSubscriber`, `TracerRegistry`, `MetricRegistry`. Use a no-op `LocalTwinSubscriber` stub, `NoOpTracerRegistry` from daemon-common, and real `MetricRegistry`. Call `init()` after construction (use `@Bean(initMethod = "init")`).
- `LocalTwinSubscriber` — no-op stub (Telemetryd publishes Twin updates, doesn't subscribe)
- `LocationPublisherManager` — uses `@Autowired TwinPublisher` field injection. Create via `new LocationPublisherManager()` — Spring will autowire the TwinPublisher field since annotation processing is active in `@Configuration` classes.
- `OpenConfigTwinPublisherImpl` — constructor takes `LocationPublisherManager`. Has `@Component` annotation — ensure NOT in scan path (it isn't — `org.opennms.netmgt.telemetry.daemon` is not in scanBasePackages).
- `ConnectorManager` — uses `@Autowired` field injection for `telemetryRegistry`, `entityScopeProvider`, `serviceTracker`, `openConfigTwinPublisher`. Create via `new ConnectorManager()` — Spring autowires the 4 fields.

**Daemon + lifecycle:**
- `Telemetryd` — create via `new Telemetryd()`. The class has NO multi-arg constructor — it uses `@Autowired` field injection for all 7 fields. Spring will autowire: `telemetrydConfigDao`, `messageDispatcherFactory`, `messageConsumerManager`, `applicationContext`, `telemetryRegistry`, `connectorManager`. The `messageBus` field is `@Autowired(required = false)` and will be null (no MessageBus bean provided).
- **No AnnotationBasedEventListenerAdapter needed** — Telemetryd has NO `@EventHandler` annotations. It uses `MessageBus` (IPC) for reload events, not the annotation-based pattern. With `messageBus = null`, daemon config reload via IPC will not work, but that's acceptable for the bridge scope (restart the container instead).
- Inline `SmartLifecycle` — `afterPropertiesSet()` / `start()` / `destroy()`, phase = Integer.MAX_VALUE

**CRITICAL: Field injection wiring pattern for Telemetryd, ConnectorManager, LocationPublisherManager:**

These classes use `@Autowired` field injection with no constructor. When declared via `@Bean` in a `@Configuration` class:
1. The `@Bean` method calls `new Xxx()` (default constructor)
2. Spring's `AutowiredAnnotationBeanPostProcessor` processes `@Autowired` fields AFTER construction
3. This works automatically because `@Configuration` classes enable annotation processing
4. The implementer does NOT need to call `autowireBean()` manually — Spring handles it

This is the same pattern the Karaf XML uses (`<bean class="...Telemetryd"/>` with no constructor-arg).

**TelemetrydConfigDao setup:**
- Create via `new TelemetrydConfigDao()`
- Call `setConfigResource(new FileSystemResource(opennmsHome + "/etc/telemetryd-configuration.xml"))` — required for JAXB config loading
- Inject `@Value("${opennms.home}")` into the @Bean method parameter

**Key implementation notes:**
- The no-op TelemetryServiceRegistry stubs can be anonymous inner classes or a shared `EmptyTelemetryServiceRegistry<D, S>` helper
- `KafkaTwinPublisher` needs Kafka properties from system properties (`org.opennms.core.ipc.twin.kafka.bootstrap.servers`) — passed via docker-compose command

Reference for patterns:
- `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java` — SmartLifecycle pattern
- `core/daemon-loader-telemetryd/src/main/resources/META-INF/opennms/applicationContext-daemon-loader-telemetryd.xml` — bean wiring reference (field injection, no constructors)

- [ ] **Step 2: Verify compilation**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-telemetryd -am install
```

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-telemetryd/src/main/java/org/opennms/netmgt/telemetry/boot/TelemetrydDaemonConfiguration.java
git commit -m "feat: add Telemetryd daemon configuration with registry stubs and Twin API"
```

---

## Task 5: Docker Integration

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml` (lines 501-541)
- Modify: `opennms-container/delta-v/build.sh` (staging array)
- Modify: `opennms-container/delta-v/Dockerfile.springboot` (add COPY line)

- [ ] **Step 1: Update docker-compose.yml**

Replace the existing `telemetryd` service entry (lines 501-532) with:

```yaml
  telemetryd:
    profiles: [full]
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-Dorg.opennms.core.ipc.sink.kafka.bootstrap.servers=kafka:9092",
              "-Dorg.opennms.core.ipc.sink.kafka.group.id=opennms-telemetryd-sink",
              "-Dorg.opennms.core.ipc.twin.kafka.bootstrap.servers=kafka:9092",
              "-jar", "/opt/daemon-boot-telemetryd.jar"]
    container_name: delta-v-telemetryd
    hostname: telemetryd
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
      KAFKA_CONSUMER_GROUP: opennms-telemetryd
      OPENNMS_HOME: /opt/deltav
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "18"
    volumes:
      - ./telemetryd-overlay/etc:/opt/deltav/etc:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Also remove `telemetryd-data:` from the `volumes:` section at the bottom (line 541).

- [ ] **Step 2: Add JAR to build.sh staging**

In `do_stage_daemon_jars()` pairs array, add after the perspectivepollerd entry:
```bash
"core/daemon-boot-telemetryd/target/org.opennms.core.daemon-boot-telemetryd-$VERSION-boot.jar:daemon-boot-telemetryd.jar"
```

- [ ] **Step 3: Add COPY to Dockerfile.springboot**

Add after the perspectivepollerd COPY line:
```dockerfile
COPY staging/daemon/daemon-boot-telemetryd.jar /opt/daemon-boot-telemetryd.jar
```

- [ ] **Step 4: Clean up telemetryd-overlay**

```bash
rm -rf opennms-container/delta-v/telemetryd-overlay/etc/featuresBoot.d
rm -f opennms-container/delta-v/telemetryd-overlay/etc/org.opennms.*.cfg
```

Keep `telemetryd-configuration.xml`.

- [ ] **Step 5: Verify docker-compose is valid**

```bash
cd opennms-container/delta-v
VERSION=36.0.0-SNAPSHOT docker compose config --quiet
```
Expected: exit code 0

- [ ] **Step 6: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml opennms-container/delta-v/build.sh opennms-container/delta-v/Dockerfile.springboot opennms-container/delta-v/telemetryd-overlay/
git commit -m "feat: update Docker integration for Spring Boot Telemetryd"
```

---

## Task 6: Build and Verify

- [ ] **Step 1: Build the module**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-telemetryd -am install
```

- [ ] **Step 2: Verify no ServiceMix Spring JARs in boot JAR**

```bash
jar tf core/daemon-boot-telemetryd/target/*-boot.jar | grep "servicemix.*spring"
```
Expected: empty output (no ServiceMix leaks)

- [ ] **Step 3: Build Docker images**

```bash
cd opennms-container/delta-v
VERSION=36.0.0-SNAPSHOT bash build.sh deltav
```

- [ ] **Step 4: Start Telemetryd**

```bash
VERSION=36.0.0-SNAPSHOT docker compose --profile full up -d --force-recreate telemetryd
```

- [ ] **Step 5: Wait and check health**

```bash
sleep 30
docker exec delta-v-telemetryd curl -sf http://localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 6: Check KafkaSinkBridge threads**

```bash
docker logs delta-v-telemetryd 2>&1 | grep -E "Started|KafkaSinkBridge|Telemetryd|consumer"
```
Expected: "Started TelemetrydApplication", KafkaSinkBridge consumer threads for each queue

- [ ] **Step 7: Verify jcmd**

```bash
docker exec delta-v-telemetryd jcmd 1 VM.version
```
Expected: JDK 21 version

- [ ] **Step 8: Check Metaspace usage**

```bash
docker exec delta-v-telemetryd jcmd 1 GC.heap_info 2>&1 | grep Metaspace
```
Record actual usage for future MaxMetaspaceSize tuning.

- [ ] **Step 9: Commit any fixes**

```bash
git add <changed-files>
git commit -m "fix: resolve Telemetryd Spring Boot startup issues"
```

---

## Task 7: Update Dashboard

- [ ] **Step 1: Update README.md**

In the "Migrated to Spring Boot 4" table, add Telemetryd entry. Update count from 9 to 10. Update "Running on Karaf" to 3 daemons. Update services table — change telemetryd from Karaf to Spring Boot 4. Update current state summary.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: update dashboard — 10 daemons migrated, Telemetryd complete"
```
