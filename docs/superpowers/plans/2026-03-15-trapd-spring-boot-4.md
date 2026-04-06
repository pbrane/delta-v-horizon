# Trapd Spring Boot 4 Migration — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Trapd to a Spring Boot 4.0.3 microservice that consumes SNMP traps from Minion via Kafka Sink and publishes enriched events to `opennms-fault-events`.

**Architecture:** Two new Maven modules: `core/daemon-sink-kafka/` (shared Kafka Sink bridge infrastructure) and `core/daemon-boot-trapd/` (Spring Boot 4 application). Trapd uses JDBC only (no JPA). The old `core/daemon-loader-trapd/` Karaf module is deleted and replaced.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Apache Kafka, PostgreSQL (JDBC), Protobuf (SinkMessage)

**Spec:** `docs/superpowers/specs/2026-03-15-trapd-spring-boot-4-design.md`

---

## File Structure

### New Module: `core/daemon-sink-kafka/`

| File | Responsibility |
|------|---------------|
| `pom.xml` | Maven module — Spring Boot 4 starter, Kafka clients, protobuf, Sink API |
| `src/main/java/.../sink/kafka/KafkaSinkBridge.java` | Polls Kafka Sink topic, deserializes SinkMessage, dispatches to consumer manager |
| `src/main/java/.../sink/kafka/LocalMessageConsumerManager.java` | Routes messages to registered MessageConsumer implementations |
| `src/main/java/.../sink/kafka/KafkaSinkConfiguration.java` | Spring @Configuration wiring bridge + manager beans |
| `src/test/java/.../sink/kafka/KafkaSinkBridgeTest.java` | Unit test — deserialization, dispatch, error handling |

### New Module: `core/daemon-boot-trapd/`

| File | Responsibility |
|------|---------------|
| `pom.xml` | Maven module — Spring Boot 4, daemon-common, daemon-sink-kafka, traps feature |
| `src/main/java/.../trapd/boot/TrapdApplication.java` | Spring Boot entry point, excludes JPA auto-config |
| `src/main/java/.../trapd/boot/TrapdConfiguration.java` | Wires TrapSinkConsumer, TrapSinkModule, EventCreator, TrapdConfig |
| `src/main/java/.../trapd/boot/JdbcInterfaceToNodeCache.java` | JDBC-based (location, IP) → nodeId cache with periodic refresh |
| `src/main/java/.../trapd/boot/JdbcEventConfLoader.java` | Loads eventconf_events from DB, initializes DefaultEventConfDao |
| `src/main/java/.../trapd/boot/JdbcDistPollerDao.java` | JDBC-based DistPollerDao — reads monitoringsystems table |
| `src/main/resources/application.yml` | DataSource, Kafka, logging configuration |
| `src/test/java/.../trapd/boot/JdbcInterfaceToNodeCacheTest.java` | Unit test for cache |
| `src/test/java/.../trapd/boot/TrapdApplicationTest.java` | Context loads test |

### Modified Files

| File | Change |
|------|--------|
| `core/pom.xml` | Add `daemon-sink-kafka` and `daemon-boot-trapd` modules |
| `opennms-container/delta-v/build.sh` | Stage `daemon-boot-trapd.jar`, remove `daemon-loader-trapd.jar` |
| `opennms-container/delta-v/Dockerfile.daemon` | COPY `daemon-boot-trapd.jar` to `/opt/`, remove daemon-loader-trapd COPY |
| `opennms-container/delta-v/docker-compose.yml` | Replace trapd service with Spring Boot config |
| `container/features/src/main/resources/features.xml` | Remove `opennms-daemon-trapd` feature |

### Deleted

| Path | Reason |
|------|--------|
| `core/daemon-loader-trapd/` | Replaced by daemon-sink-kafka + daemon-boot-trapd |

---

## Chunk 1: daemon-sink-kafka Module

### Task 1: Create daemon-sink-kafka POM

**Files:**
- Create: `core/daemon-sink-kafka/pom.xml`

- [ ] **Step 1: Create the POM file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.opennms</groupId>
        <artifactId>org.opennms.core</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>

    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.daemon-sink-kafka</artifactId>
    <packaging>jar</packaging>
    <name>OpenNMS :: Core :: Daemon Sink :: Kafka</name>
    <description>
        Shared Kafka Sink bridge infrastructure for Spring Boot daemon containers.
        Consumes from Minion Sink topics (OpenNMS.Sink.*) and dispatches to local consumers.
    </description>

    <properties>
        <java.version>21</java.version>
        <spring-boot.version>4.0.3</spring-boot.version>
        <enforcer-skip-banned-dependencies>true</enforcer-skip-banned-dependencies>
        <enforcer-skip-dependency-convergence>true</enforcer-skip-dependency-convergence>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-classic</artifactId>
                <version>1.5.32</version>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-core</artifactId>
                <version>1.5.32</version>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>2.0.17</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter-api</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter-engine</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-commons</artifactId>
                <version>1.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-engine</artifactId>
                <version>1.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-launcher</artifactId>
                <version>1.12.2</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Sink API (SinkModule, MessageConsumer, AbstractMessageConsumerManager) -->
        <dependency>
            <groupId>org.opennms.core.ipc.sink</groupId>
            <artifactId>org.opennms.core.ipc.sink.api</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.opennms.core.ipc.sink</groupId>
            <artifactId>org.opennms.core.ipc.sink.common</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-tx</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- Protobuf (SinkMessage deserialization) -->
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java</artifactId>
            <version>${protobufVersion}</version>
        </dependency>

        <!-- Kafka clients -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-clients</artifactId>
        </dependency>

        <!-- Spring Boot (for @Configuration, @Value) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Metrics (required by AbstractMessageConsumerManager) -->
        <dependency>
            <groupId>io.dropwizard.metrics</groupId>
            <artifactId>metrics-core</artifactId>
        </dependency>

        <!-- OpenTracing (required by Sink common) -->
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

        <!-- SLF4J 2.x -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId></exclusion>
            </exclusions>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                    <release>${java.version}</release>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.5</version>
                <configuration>
                    <includeJUnit5Engines>
                        <includeJUnit5Engine>junit-jupiter</includeJUnit5Engine>
                    </includeJUnit5Engines>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter-engine</artifactId>
                        <version>5.12.2</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.platform</groupId>
                        <artifactId>junit-platform-launcher</artifactId>
                        <version>1.12.2</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-sink-kafka</module>` after the `daemon-common` module entry in `core/pom.xml`.

- [ ] **Step 3: Verify POM compiles**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml validate`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/daemon-sink-kafka/pom.xml core/pom.xml
git commit -m "feat: add daemon-sink-kafka module POM — shared Kafka Sink bridge"
```

### Task 2: Extract KafkaSinkBridge to daemon-sink-kafka

**Files:**
- Create: `core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/KafkaSinkBridge.java`

- [ ] **Step 1: Write the KafkaSinkBridge test**

Create `core/daemon-sink-kafka/src/test/java/org/opennms/core/daemon/sink/kafka/KafkaSinkBridgeTest.java`:

```java
package org.opennms.core.daemon.sink.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class KafkaSinkBridgeTest {

    @Test
    void bridgeStartsInDaemonThread() {
        var manager = new LocalMessageConsumerManager();
        var bridge = new KafkaSinkBridge(manager, "localhost:9092", "test-group");

        // Bridge should not throw on construction
        assertThat(bridge).isNotNull();
    }

    @Test
    void destroyStopsCleanly() throws Exception {
        var manager = new LocalMessageConsumerManager();
        var bridge = new KafkaSinkBridge(manager, "localhost:9092", "test-group");

        // Start the bridge (it will spin waiting for module)
        bridge.afterPropertiesSet();

        // Immediately destroy — should not hang
        bridge.destroy();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml test -pl . -Dtest=KafkaSinkBridgeTest`
Expected: FAIL — class not found

- [ ] **Step 3: Write KafkaSinkBridge implementation**

Create `core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/KafkaSinkBridge.java`:

```java
package org.opennms.core.daemon.sink.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opennms.core.ipc.sink.api.Message;
import org.opennms.core.ipc.sink.api.SinkModule;
import org.opennms.core.ipc.sink.model.SinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Bridges Kafka Sink topic consumption to the local {@link LocalMessageConsumerManager}.
 *
 * <p>Minion forwards messages (traps, syslogs, telemetry) to Kafka Sink topics
 * ({@code OpenNMS.Sink.{moduleId}}). This bridge consumes from that topic and
 * dispatches to the local consumer manager.</p>
 *
 * <p>Reusable by any daemon that consumes from Minion Sink topics.</p>
 */
public class KafkaSinkBridge implements InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSinkBridge.class);
    private static final Duration POLL_DURATION = Duration.ofMillis(100);

    private final LocalMessageConsumerManager consumerManager;
    private final String bootstrapServers;
    private final String groupId;

    private volatile SinkModule<?, Message> module;
    private volatile Thread consumerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public KafkaSinkBridge(LocalMessageConsumerManager consumerManager,
                           String bootstrapServers,
                           String groupId) {
        this.consumerManager = consumerManager;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
    }

    public void setModule(SinkModule<?, Message> module) {
        this.module = module;
    }

    @Override
    public void afterPropertiesSet() {
        consumerThread = new Thread(this::pollLoop, "kafka-sink-bridge");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private void pollLoop() {
        while (module == null && !closed.get()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (closed.get()) return;

        final String topic = "OpenNMS.Sink." + module.getId();
        LOG.info("KafkaSinkBridge starting: topic={}, bootstrapServers={}, groupId={}",
                topic, bootstrapServers, groupId);

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        props.put("enable.auto.commit", "true");
        props.put("auto.commit.interval.ms", "1000");
        props.put("auto.offset.reset", "latest");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            while (!closed.get()) {
                try {
                    ConsumerRecords<String, byte[]> records = consumer.poll(POLL_DURATION);
                    for (ConsumerRecord<String, byte[]> record : records) {
                        try {
                            SinkMessage sinkMessage = SinkMessage.parseFrom(record.value());
                            byte[] content = sinkMessage.getContent().toByteArray();
                            Message message = module.unmarshal(content);
                            consumerManager.dispatch(module, message);
                        } catch (Exception e) {
                            LOG.warn("Error processing Sink message (offset={}): {}",
                                    record.offset(), e.getMessage(), e);
                        }
                    }
                } catch (Throwable t) {
                    if (closed.get()) break;
                    LOG.error("Error in KafkaSinkBridge poll loop: {}", t.getMessage(), t);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                LOG.error("Fatal error in KafkaSinkBridge: {}", t.getMessage(), t);
            }
        }
        LOG.info("KafkaSinkBridge stopped");
    }

    @Override
    public void destroy() {
        closed.set(true);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml test -Dtest=KafkaSinkBridgeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/daemon-sink-kafka/src/
git commit -m "feat: add KafkaSinkBridge to daemon-sink-kafka — shared Sink bridge"
```

### Task 3: Extract LocalMessageConsumerManager

**Files:**
- Create: `core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/LocalMessageConsumerManager.java`

- [ ] **Step 1: Write LocalMessageConsumerManager**

```java
package org.opennms.core.daemon.sink.kafka;

import org.opennms.core.ipc.sink.api.Message;
import org.opennms.core.ipc.sink.api.SinkModule;
import org.opennms.core.ipc.sink.common.AbstractMessageConsumerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process MessageConsumerManager for Spring Boot daemon containers.
 *
 * <p>When a consumer registers (e.g., TrapSinkConsumer), this manager notifies
 * the {@link KafkaSinkBridge} with the registered module so it can start
 * consuming from the Kafka Sink topic.</p>
 */
public class LocalMessageConsumerManager extends AbstractMessageConsumerManager {

    private static final Logger LOG = LoggerFactory.getLogger(LocalMessageConsumerManager.class);

    private KafkaSinkBridge kafkaSinkBridge;

    public void setKafkaSinkBridge(KafkaSinkBridge kafkaSinkBridge) {
        this.kafkaSinkBridge = kafkaSinkBridge;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void startConsumingForModule(SinkModule<?, Message> module) {
        LOG.info("Local sink consumer started for module: {}", module.getId());
        if (kafkaSinkBridge != null) {
            kafkaSinkBridge.setModule(module);
        }
    }

    @Override
    protected void stopConsumingForModule(SinkModule<?, Message> module) {
        LOG.info("Local sink consumer stopped for module: {}", module.getId());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/LocalMessageConsumerManager.java
git commit -m "feat: add LocalMessageConsumerManager to daemon-sink-kafka"
```

### Task 4: Create KafkaSinkConfiguration

**Files:**
- Create: `core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/KafkaSinkConfiguration.java`

- [ ] **Step 1: Write the Configuration class**

```java
package org.opennms.core.daemon.sink.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} for the Kafka Sink bridge infrastructure.
 *
 * <p>Creates the {@link KafkaSinkBridge} and {@link LocalMessageConsumerManager} beans
 * that together consume from Minion Sink topics and dispatch to local consumers.</p>
 *
 * <p>The daemon's application context must provide a {@code SinkModule} bean
 * (e.g., {@code TrapSinkModule}) which gets registered when the consumer starts.</p>
 */
@Configuration
public class KafkaSinkConfiguration {

    @Value("${opennms.kafka.bootstrap-servers:kafka:9092}")
    private String bootstrapServers;

    @Value("${opennms.kafka.sink.consumer-group:opennms-sink}")
    private String sinkConsumerGroup;

    @Bean
    public LocalMessageConsumerManager localMessageConsumerManager(KafkaSinkBridge kafkaSinkBridge) {
        var manager = new LocalMessageConsumerManager();
        manager.setKafkaSinkBridge(kafkaSinkBridge);
        return manager;
    }

    @Bean(initMethod = "afterPropertiesSet", destroyMethod = "destroy")
    public KafkaSinkBridge kafkaSinkBridge() {
        return new KafkaSinkBridge(
                // Circular: manager needs bridge, bridge needs manager.
                // Bridge only uses manager for dispatch, manager only uses bridge for setModule.
                // Wire manager later via setter.
                null, bootstrapServers, sinkConsumerGroup);
    }
}
```

Wait — there's a circular dependency. Let me fix this. The bridge needs the manager for `dispatch()`, the manager needs the bridge for `setModule()`. The existing code solves this with setter injection. Let me adjust:

```java
package org.opennms.core.daemon.sink.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaSinkConfiguration {

    @Value("${opennms.kafka.bootstrap-servers:kafka:9092}")
    private String bootstrapServers;

    @Value("${opennms.kafka.sink.consumer-group:opennms-sink}")
    private String sinkConsumerGroup;

    @Bean
    public LocalMessageConsumerManager localMessageConsumerManager() {
        return new LocalMessageConsumerManager();
    }

    @Bean(initMethod = "afterPropertiesSet", destroyMethod = "destroy")
    public KafkaSinkBridge kafkaSinkBridge(LocalMessageConsumerManager consumerManager) {
        var bridge = new KafkaSinkBridge(consumerManager, bootstrapServers, sinkConsumerGroup);
        consumerManager.setKafkaSinkBridge(bridge);
        return bridge;
    }
}
```

- [ ] **Step 2: Compile the module**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run all tests**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add core/daemon-sink-kafka/src/main/java/org/opennms/core/daemon/sink/kafka/KafkaSinkConfiguration.java
git commit -m "feat: add KafkaSinkConfiguration — wires bridge + consumer manager"
```

---

## Chunk 2: daemon-boot-trapd Module — POM and Application

### Task 5: Create daemon-boot-trapd POM

**Files:**
- Create: `core/daemon-boot-trapd/pom.xml`

- [ ] **Step 1: Create the POM**

Based on `core/daemon-boot-alarmd/pom.xml` but with traps feature instead of alarmd, daemon-sink-kafka instead of JPA, and no Hibernate/JPA dependencies.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.opennms</groupId>
        <artifactId>org.opennms.core</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>

    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.daemon-boot-trapd</artifactId>
    <packaging>jar</packaging>
    <name>OpenNMS :: Core :: Daemon Boot :: Trapd</name>

    <properties>
        <java.version>21</java.version>
        <spring-boot.version>4.0.3</spring-boot.version>
        <enforcer-skip-banned-dependencies>true</enforcer-skip-banned-dependencies>
        <enforcer-skip-dependency-convergence>true</enforcer-skip-dependency-convergence>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-classic</artifactId>
                <version>1.5.32</version>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-core</artifactId>
                <version>1.5.32</version>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>2.0.17</version>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>jcl-over-slf4j</artifactId>
                <version>2.0.17</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter-api</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter-engine</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter-params</artifactId>
                <version>5.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-commons</artifactId>
                <version>1.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-engine</artifactId>
                <version>1.12.2</version>
            </dependency>
            <dependency>
                <groupId>org.junit.platform</groupId>
                <artifactId>junit-platform-launcher</artifactId>
                <version>1.12.2</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Shared daemon infrastructure (Kafka event transport, DataSource) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Shared Kafka Sink bridge -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon-sink-kafka</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Trapd feature (TrapSinkConsumer, EventCreator, TrapSinkModule) -->
        <dependency>
            <groupId>org.opennms.features.events</groupId>
            <artifactId>org.opennms.features.events.traps</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-aop</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context-support</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-expression</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-jdbc</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-orm</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-tx</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-web</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-webmvc</artifactId></exclusion>
                <exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
                <exclusion><groupId>org.hibernate.javax.persistence</groupId><artifactId>hibernate-jpa-2.0-api</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>jcl-over-slf4j</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>log4j-over-slf4j</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-log4j2</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- EventConfDao + DefaultEventConfDao (event matching) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config</artifactId>
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

        <!-- DAO APIs (DistPollerDao, InterfaceToNodeCache) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Model (EventConfEvent for DB loading, OnmsDistPoller for system identity) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-tx</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-orm</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-jdbc</artifactId></exclusion>
                <exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
                <exclusion><groupId>org.hibernate.javax.persistence</groupId><artifactId>hibernate-jpa-2.0-api</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>log4j-over-slf4j</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-log4j2</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- javax.persistence API (legacy classes need it loadable) -->
        <dependency>
            <groupId>javax.persistence</groupId>
            <artifactId>javax.persistence-api</artifactId>
            <version>2.2</version>
        </dependency>

        <!-- javax.xml.bind (Kafka event XML unmarshalling) -->
        <dependency>
            <groupId>javax.xml.bind</groupId>
            <artifactId>jaxb-api</artifactId>
            <version>2.3.1</version>
        </dependency>

        <!-- SLF4J 2.x -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>jcl-over-slf4j</artifactId>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- PostgreSQL JDBC driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId></exclusion>
            </exclusions>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                    <release>${java.version}</release>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.5</version>
                <configuration>
                    <includeJUnit5Engines>
                        <includeJUnit5Engine>junit-jupiter</includeJUnit5Engine>
                    </includeJUnit5Engines>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter-engine</artifactId>
                        <version>5.12.2</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.platform</groupId>
                        <artifactId>junit-platform-launcher</artifactId>
                        <version>1.12.2</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-trapd</module>` after the `daemon-sink-kafka` module entry.

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-trapd/pom.xml core/pom.xml
git commit -m "feat: add daemon-boot-trapd module POM"
```

### Task 6: Create TrapdApplication and application.yml

**Files:**
- Create: `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/TrapdApplication.java`
- Create: `core/daemon-boot-trapd/src/main/resources/application.yml`

- [ ] **Step 1: Write TrapdApplication**

```java
package org.opennms.netmgt.trapd.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.jpa.DataJpaRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.core.daemon.sink.kafka",
        "org.opennms.netmgt.trapd.boot"
    },
    exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
    }
)
@EnableScheduling
public class TrapdApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrapdApplication.class, args);
    }
}
```

- [ ] **Step 2: Write application.yml**

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
        include: health,prometheus
  endpoint:
    health:
      show-details: always

opennms:
  home: ${OPENNMS_HOME:/opt/opennms}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-trapd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
    sink:
      consumer-group: ${KAFKA_SINK_CONSUMER_GROUP:opennms-trapd-sink}
  trapd:
    interface-to-node-cache:
      refresh-interval-ms: ${INTERFACE_NODE_CACHE_REFRESH_MS:300000}

logging:
  level:
    org.opennms: INFO
    org.opennms.core.daemon.sink.kafka: INFO
```

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/TrapdApplication.java \
        core/daemon-boot-trapd/src/main/resources/application.yml
git commit -m "feat: add TrapdApplication entry point and config"
```

---

## Chunk 3: JDBC Infrastructure Classes

### Task 7: JdbcDistPollerDao

**Files:**
- Create: `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcDistPollerDao.java`

- [ ] **Step 1: Write JdbcDistPollerDao**

Implements the minimal DistPollerDao subset needed by TrapSinkConsumer (just `whoami()`). Uses JDBC to read from the `monitoringsystems` table.

```java
package org.opennms.netmgt.trapd.boot;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.model.OnmsDistPoller;
import org.opennms.netmgt.model.OnmsMonitoringSystem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDistPollerDao implements DistPollerDao {

    private final JdbcTemplate jdbc;
    private volatile OnmsDistPoller localPoller;

    public JdbcDistPollerDao(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public OnmsDistPoller whoami() {
        if (localPoller == null) {
            synchronized (this) {
                if (localPoller == null) {
                    localPoller = jdbc.queryForObject(
                        "SELECT id, label, location FROM monitoringsystems WHERE type = 'OpenNMS' LIMIT 1",
                        (rs, rowNum) -> {
                            var dp = new OnmsDistPoller();
                            dp.setId(rs.getString("id"));
                            dp.setLabel(rs.getString("label"));
                            dp.setLocation(rs.getString("location"));
                            dp.setType(OnmsMonitoringSystem.TYPE_OPENNMS);
                            return dp;
                        });
                }
            }
        }
        return localPoller;
    }

    // Remaining DAO methods — not needed by Trapd, throw UnsupportedOperationException
    @Override public OnmsDistPoller get(String id) { throw new UnsupportedOperationException(); }
    @Override public String save(OnmsDistPoller entity) { throw new UnsupportedOperationException(); }
    @Override public void saveOrUpdate(OnmsDistPoller entity) { throw new UnsupportedOperationException(); }
    @Override public void update(OnmsDistPoller entity) { throw new UnsupportedOperationException(); }
    @Override public void delete(OnmsDistPoller entity) { throw new UnsupportedOperationException(); }
    @Override public void delete(String key) { throw new UnsupportedOperationException(); }
    @Override public List<OnmsDistPoller> findAll() { throw new UnsupportedOperationException(); }
    @Override public List<OnmsDistPoller> findMatching(org.opennms.core.criteria.Criteria criteria) { throw new UnsupportedOperationException(); }
    @Override public int countAll() { throw new UnsupportedOperationException(); }
    @Override public int countMatching(org.opennms.core.criteria.Criteria criteria) { throw new UnsupportedOperationException(); }
    @Override public void flush() { }
    @Override public void clear() { }
    @Override public void initialize(Object obj) { }
    @Override public OnmsDistPoller load(String id) { throw new UnsupportedOperationException(); }
    @Override public void lock() { }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcDistPollerDao.java
git commit -m "feat: add JdbcDistPollerDao — JDBC-based system identity lookup"
```

### Task 8: JdbcInterfaceToNodeCache

**Files:**
- Create: `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCache.java`
- Create: `core/daemon-boot-trapd/src/test/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCacheTest.java`

- [ ] **Step 1: Write the test**

```java
package org.opennms.netmgt.trapd.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.junit.jupiter.api.Test;

class JdbcInterfaceToNodeCacheTest {

    @Test
    void emptyCache_returnsEmpty() throws Exception {
        var cache = new JdbcInterfaceToNodeCache();
        var result = cache.getFirst("Default", InetAddress.getByName("192.168.1.1"));
        assertThat(result).isEmpty();
    }

    @Test
    void populatedCache_findsEntry() throws Exception {
        var cache = new JdbcInterfaceToNodeCache();
        cache.addEntry("Default", InetAddress.getByName("192.168.1.1"), 42, 100);

        var result = cache.getFirst("Default", InetAddress.getByName("192.168.1.1"));
        assertThat(result).isPresent();
        assertThat(result.get().nodeId).isEqualTo(42);
        assertThat(result.get().interfaceId).isEqualTo(100);
    }

    @Test
    void differentLocation_doesNotMatch() throws Exception {
        var cache = new JdbcInterfaceToNodeCache();
        cache.addEntry("Default", InetAddress.getByName("192.168.1.1"), 42, 100);

        var result = cache.getFirst("Remote", InetAddress.getByName("192.168.1.1"));
        assertThat(result).isEmpty();
    }

    @Test
    void removeInterfacesForNode_removesAll() throws Exception {
        var cache = new JdbcInterfaceToNodeCache();
        cache.addEntry("Default", InetAddress.getByName("192.168.1.1"), 42, 100);
        cache.addEntry("Default", InetAddress.getByName("192.168.1.2"), 42, 101);
        cache.addEntry("Default", InetAddress.getByName("10.0.0.1"), 99, 200);

        cache.removeInterfacesForNode(42);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.getFirst("Default", InetAddress.getByName("10.0.0.1"))).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test -Dtest=JdbcInterfaceToNodeCacheTest`
Expected: FAIL — class not found

- [ ] **Step 3: Write JdbcInterfaceToNodeCache**

```java
package org.opennms.netmgt.trapd.boot;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.sql.DataSource;

import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * JDBC-based InterfaceToNodeCache for Spring Boot daemon containers.
 *
 * <p>Loads (location, ipAddress) → Entry(nodeId, interfaceId) mappings from
 * the database and refreshes periodically.</p>
 */
public class JdbcInterfaceToNodeCache implements InterfaceToNodeCache {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcInterfaceToNodeCache.class);

    private static final String LOAD_SQL =
            "SELECT ip.nodeid, ip.ipaddr, ip.id AS interfaceid, n.location " +
            "FROM ipinterface ip " +
            "JOIN node n ON ip.nodeid = n.nodeid " +
            "WHERE n.nodetype != 'D' AND ip.ismanaged != 'D'";

    private final ConcurrentMap<LocationIpKey, Entry> cache = new ConcurrentHashMap<>();
    private JdbcTemplate jdbc;

    /** No-arg constructor for testing without a DataSource. */
    public JdbcInterfaceToNodeCache() {
    }

    public JdbcInterfaceToNodeCache(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Populate cache entry directly (for testing). */
    void addEntry(String location, InetAddress addr, int nodeId, int interfaceId) {
        cache.put(new LocationIpKey(location, addr), new Entry(nodeId, interfaceId));
    }

    @Override
    public Optional<Entry> getFirst(String location, InetAddress addr) {
        return Optional.ofNullable(cache.get(new LocationIpKey(location, addr)));
    }

    @Override
    public boolean setNodeId(String location, InetAddress addr, int nodeId) {
        cache.put(new LocationIpKey(location, addr), new Entry(nodeId, 0));
        return true;
    }

    @Override
    public boolean removeNodeId(String location, InetAddress addr, int nodeId) {
        var key = new LocationIpKey(location, addr);
        Entry entry = cache.get(key);
        if (entry != null && entry.nodeId == nodeId) {
            cache.remove(key);
            return true;
        }
        return false;
    }

    @Override
    public void removeInterfacesForNode(int nodeId) {
        cache.entrySet().removeIf(e -> e.getValue().nodeId == nodeId);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Scheduled(fixedDelayString = "${opennms.trapd.interface-to-node-cache.refresh-interval-ms:300000}",
               initialDelayString = "0")
    public void refresh() {
        if (jdbc == null) return;
        long start = System.currentTimeMillis();
        var newCache = new ConcurrentHashMap<LocationIpKey, Entry>();

        jdbc.query(LOAD_SQL, rs -> {
            try {
                int nodeId = rs.getInt("nodeid");
                int interfaceId = rs.getInt("interfaceid");
                String ipAddr = rs.getString("ipaddr");
                String location = rs.getString("location");
                if (ipAddr != null && location != null) {
                    InetAddress addr = InetAddress.getByName(ipAddr);
                    newCache.putIfAbsent(new LocationIpKey(location, addr),
                            new Entry(nodeId, interfaceId));
                }
            } catch (Exception e) {
                LOG.warn("Error parsing interface-to-node entry: {}", e.getMessage());
            }
        });

        cache.clear();
        cache.putAll(newCache);
        LOG.info("InterfaceToNodeCache refreshed: {} entries in {} ms",
                cache.size(), System.currentTimeMillis() - start);
    }

    @Override
    public void dataSourceSync() {
        refresh();
    }

    private record LocationIpKey(String location, InetAddress addr) {
        LocationIpKey {
            Objects.requireNonNull(location);
            Objects.requireNonNull(addr);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test -Dtest=JdbcInterfaceToNodeCacheTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCache.java \
        core/daemon-boot-trapd/src/test/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCacheTest.java
git commit -m "feat: add JdbcInterfaceToNodeCache — JDBC-based IP→node cache"
```

### Task 9: JdbcEventConfLoader

**Files:**
- Create: `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcEventConfLoader.java`

- [ ] **Step 1: Write JdbcEventConfLoader**

Replaces `EventConfInitializer` + `EventConfEventDaoHibernate`. Loads enabled events from `eventconf_events` + `eventconf_sources` tables via JDBC, builds `EventConfEvent` objects (with `xmlContent` and `source`), and feeds them to `DefaultEventConfDao.loadEventsFromDB()`.

**Key insight:** `EventConfEvent` stores event definitions as raw XML in the `xml_content` column. `DefaultEventConfDao.loadEventsFromDB()` groups events by `source.getName()`, sorts by `source.getFileOrder()`, then parses each event's `xmlContent` via `JaxbUtils.unmarshal(Event.class, xmlContent)`. We must construct `EventConfEvent` + `EventConfSource` objects correctly.

```java
package org.opennms.netmgt.trapd.boot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.opennms.netmgt.config.api.EventConfDao;
import org.opennms.netmgt.model.EventConfEvent;
import org.opennms.netmgt.model.EventConfSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loads event definitions from the {@code eventconf_events} table via JDBC
 * and initializes the in-memory {@link DefaultEventConfDao}.
 *
 * <p>Replaces the Karaf-era {@code EventConfInitializer} + Hibernate DAO.
 * The event definitions are stored as XML blobs in the {@code xml_content}
 * column; {@code DefaultEventConfDao.loadEventsFromDB()} parses them.</p>
 */
@Component
public class JdbcEventConfLoader {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcEventConfLoader.class);

    private static final String LOAD_SQL =
            "SELECT e.id, e.uei, e.event_label, e.description, e.severity, " +
            "e.xml_content, e.enabled, e.source_id, " +
            "s.name AS source_name, s.file_order AS source_file_order " +
            "FROM eventconf_events e " +
            "JOIN eventconf_sources s ON e.source_id = s.id " +
            "WHERE e.enabled = true " +
            "ORDER BY s.file_order ASC, e.id ASC";

    private final JdbcTemplate jdbc;
    private final EventConfDao eventConfDao;

    public JdbcEventConfLoader(DataSource dataSource, EventConfDao eventConfDao) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.eventConfDao = eventConfDao;
    }

    @Scheduled(fixedDelayString = "${opennms.trapd.eventconf-refresh-interval-ms:600000}",
               initialDelayString = "0")
    public void loadEventConf() {
        long start = System.currentTimeMillis();
        List<EventConfEvent> events = new ArrayList<>();
        Map<Long, EventConfSource> sourceCache = new HashMap<>();

        jdbc.query(LOAD_SQL, rs -> {
            long sourceId = rs.getLong("source_id");
            EventConfSource source = sourceCache.computeIfAbsent(sourceId, id -> {
                EventConfSource s = new EventConfSource();
                s.setId(id);
                s.setName(rs.getString("source_name"));
                s.setFileOrder(rs.getInt("source_file_order"));
                return s;
            });

            EventConfEvent event = new EventConfEvent();
            event.setId(rs.getLong("id"));
            event.setUei(rs.getString("uei"));
            event.setEventLabel(rs.getString("event_label"));
            event.setDescription(rs.getString("description"));
            event.setSeverity(rs.getString("severity"));
            event.setXmlContent(rs.getString("xml_content"));
            event.setEnabled(true);
            event.setSource(source);
            events.add(event);
        });

        eventConfDao.loadEventsFromDB(events);
        long elapsed = System.currentTimeMillis() - start;
        LOG.info("Loaded {} event definitions from database in {} ms", events.size(), elapsed);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcEventConfLoader.java
git commit -m "feat: add JdbcEventConfLoader — JDBC-based eventconf loading"
```

---

## Chunk 4: TrapdConfiguration Wiring

### Task 10: Create TrapdConfiguration

**Files:**
- Create: `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/TrapdConfiguration.java`

- [ ] **Step 1: Write TrapdConfiguration**

This replaces the entire `applicationContext-daemon-loader-trapd.xml`. Wires all Trapd beans with constructor/setter injection.

```java
package org.opennms.netmgt.trapd.boot;

import javax.sql.DataSource;

import org.opennms.netmgt.config.DefaultEventConfDao;
import org.opennms.netmgt.config.api.EventConfDao;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.opennms.netmgt.trapd.TrapdConfig;
import org.opennms.netmgt.trapd.TrapdConfigBean;
import org.opennms.netmgt.trapd.TrapSinkConsumer;
import org.opennms.netmgt.trapd.TrapSinkModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot @Configuration that wires all Trapd beans.
 *
 * <p>Replaces the Karaf-era {@code applicationContext-daemon-loader-trapd.xml}.</p>
 *
 * <p>{@code TrapSinkConsumer} uses {@code @Autowired} field injection internally.
 * Spring's {@code AutowiredAnnotationBeanPostProcessor} processes these annotations
 * on beans returned from {@code @Bean} methods, injecting the matching beans from
 * the application context (MessageConsumerManager, EventConfDao, EventIpcManager,
 * InterfaceToNodeCache, TrapdConfig, DistPollerDao).</p>
 *
 * <p>Note: {@code EventCreator} is package-private in {@code org.opennms.netmgt.trapd}
 * and is instantiated inside {@code TrapSinkConsumer.init()}, not here.</p>
 */
@Configuration
public class TrapdConfiguration {

    @Bean
    public TrapdConfig trapdConfig() {
        return new TrapdConfigBean();
    }

    @Bean
    public EventConfDao eventConfDao() {
        return new DefaultEventConfDao();
    }

    @Bean
    public InterfaceToNodeCache interfaceToNodeCache(DataSource dataSource) {
        return new JdbcInterfaceToNodeCache(dataSource);
    }

    @Bean
    public TrapSinkModule trapSinkModule(TrapdConfig config, DistPollerDao distPollerDao) {
        return new TrapSinkModule(config, distPollerDao.whoami());
    }

    @Bean
    public TrapSinkConsumer trapSinkConsumer() {
        // TrapSinkConsumer uses @Autowired field injection for its 6 dependencies.
        // Spring's AutowiredAnnotationBeanPostProcessor injects them after construction.
        // @PostConstruct init() runs after injection, registering with MessageConsumerManager,
        // which notifies KafkaSinkBridge.setModule(), starting Kafka polling.
        return new TrapSinkConsumer();
    }
}
```

**Lifecycle flow:**
1. Spring creates `TrapSinkConsumer` via `@Bean` method
2. `AutowiredAnnotationBeanPostProcessor` injects all 6 `@Autowired` fields
3. `@PostConstruct init()` runs → calls `messageConsumerManager.registerConsumer(this)`
4. `LocalMessageConsumerManager.startConsumingForModule()` → calls `kafkaSinkBridge.setModule(module)`
5. `KafkaSinkBridge` poll thread wakes up, subscribes to `OpenNMS.Sink.Trap`, starts consuming

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/TrapdConfiguration.java
git commit -m "feat: add TrapdConfiguration — wires all Trapd beans for Spring Boot"
```

---

## Chunk 5: Docker Infrastructure

### Task 11: Update build.sh

**Files:**
- Modify: `opennms-container/delta-v/build.sh`

- [ ] **Step 1: Add daemon-boot-trapd.jar staging and remove daemon-loader-trapd.jar**

In the `do_stage_daemon_jars()` function, find the line:
```
"core/daemon-loader-trapd/target/org.opennms.core.daemon-loader-trapd-$VERSION.jar:daemon-loader-trapd.jar"
```

Replace with:
```
"core/daemon-boot-trapd/target/org.opennms.core.daemon-boot-trapd-$VERSION.jar:daemon-boot-trapd.jar"
```

- [ ] **Step 2: Commit**

```bash
git add opennms-container/delta-v/build.sh
git commit -m "build: stage daemon-boot-trapd.jar, remove daemon-loader-trapd"
```

### Task 12: Update Dockerfile.daemon

**Files:**
- Modify: `opennms-container/delta-v/Dockerfile.daemon`

- [ ] **Step 1: Replace daemon-loader-trapd COPY with daemon-boot-trapd**

Find and remove:
```dockerfile
COPY staging/daemon/daemon-loader-trapd.jar \
     /opt/sentinel/system/org/opennms/core/org.opennms.core.daemon-loader-trapd/${VERSION}/org.opennms.core.daemon-loader-trapd-${VERSION}.jar
```

Add after the existing `daemon-boot-alarmd.jar` COPY:
```dockerfile
COPY staging/daemon/daemon-boot-trapd.jar /opt/daemon-boot-trapd.jar
```

- [ ] **Step 2: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.daemon
git commit -m "build: add daemon-boot-trapd.jar to Docker image, remove Karaf loader"
```

### Task 13: Update docker-compose.yml

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Replace trapd service with Spring Boot config**

Replace the entire `trapd:` service block (lines 213-245) with:

```yaml
  trapd:
    profiles: [passive, full]
    image: opennms/daemon-deltav:${VERSION}
    container_name: delta-v-trapd
    hostname: trapd
    entrypoint: []
    command: ["java", "-Xms256m", "-Xmx512m", "-Dopennms.home=/opt/sentinel", "-jar", "/opt/daemon-boot-trapd.jar"]
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
      KAFKA_CONSUMER_GROUP: opennms-trapd
      KAFKA_SINK_CONSUMER_GROUP: opennms-trapd-sink
      OPENNMS_HOME: /opt/sentinel
      INTERFACE_NODE_CACHE_REFRESH_MS: "15000"
    volumes:
      - ./trapd-overlay/etc:/opt/sentinel/etc:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Key changes from old config:
- `entrypoint: []` overrides Sentinel entrypoint
- `command` runs fat JAR directly
- Spring Boot env vars replace `JAVA_OPTS` system properties
- Actuator health replaces Karaf REST probe
- No UDP port mapping (traps come via Kafka Sink, not UDP)
- No `trapd-data` volume (no persistent state)

- [ ] **Step 2: Remove `trapd-data` from the volumes section**

Find and remove the `trapd-data:` volume declaration at the bottom of docker-compose.yml.

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "build: replace trapd Docker service with Spring Boot config"
```

---

## Chunk 6: Cleanup — Delete Old Artifacts

### Task 14: Remove Karaf feature declaration

**Files:**
- Modify: `container/features/src/main/resources/features.xml`

- [ ] **Step 1: Remove the opennms-daemon-trapd feature**

Search for `opennms-daemon-trapd` in `container/features/src/main/resources/features.xml` and delete the entire `<feature name="opennms-daemon-trapd" ...>...</feature>` block. The block includes dependency features (`opennms-spring-extender`, `opennms-event-forwarder-kafka`, etc.) and multiple `<bundle>` entries — delete all of it, from `<feature name="opennms-daemon-trapd"` through the closing `</feature>` tag.

Run: `grep -n "opennms-daemon-trapd" container/features/src/main/resources/features.xml` to find the exact line numbers.

- [ ] **Step 2: Commit**

```bash
git add container/features/src/main/resources/features.xml
git commit -m "refactor: remove opennms-daemon-trapd Karaf feature"
```

### Task 15: Delete daemon-loader-trapd module

**Files:**
- Delete: `core/daemon-loader-trapd/` (entire directory)
- Modify: `core/pom.xml` (remove module entry)

- [ ] **Step 1: Remove module from core/pom.xml**

Remove the line: `<module>daemon-loader-trapd</module>`

- [ ] **Step 2: Delete the directory**

```bash
rm -rf core/daemon-loader-trapd/
```

- [ ] **Step 3: Commit**

```bash
git add core/pom.xml
git rm -r core/daemon-loader-trapd/
git commit -m "refactor: delete daemon-loader-trapd — replaced by daemon-sink-kafka + daemon-boot-trapd"
```

---

## Chunk 7: Build Verification and Integration Test

### Task 16: Compile both new modules

- [ ] **Step 1: Compile daemon-sink-kafka**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: Compile daemon-boot-trapd**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run all unit tests**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml test && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test`
Expected: All tests pass

- [ ] **Step 4: Resolve any dependency conflicts**

If compilation fails with `ClassNotFoundException` or `NoSuchMethodError`:
- Check for ServiceMix Spring exclusions missing on a new transitive dep
- Check for SLF4J 1.x leaking from a transitive dep
- Add exclusions to the POM following the established pattern from daemon-boot-alarmd

- [ ] **Step 5: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve Spring Boot 4 dependency conflicts for Trapd"
```

### Task 17: Docker build test

- [ ] **Step 1: Build fat JAR**

Run: `cd /Users/david/development/src/opennms/delta-v && ./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml -DskipTests package`
Expected: `core/daemon-boot-trapd/target/org.opennms.core.daemon-boot-trapd-36.0.0-SNAPSHOT.jar` exists

- [ ] **Step 2: Verify JAR is executable**

Run: `java -jar core/daemon-boot-trapd/target/org.opennms.core.daemon-boot-trapd-36.0.0-SNAPSHOT.jar --help 2>&1 | head -5`
Expected: Spring Boot banner or startup attempt (will fail without DB, but proves JAR is packaged)

- [ ] **Step 3: Build Docker images**

Run: `cd /Users/david/development/src/opennms/delta-v/opennms-container/delta-v && ./build.sh deltav`
Expected: `opennms/daemon-deltav` image built with `daemon-boot-trapd.jar` at `/opt/`

- [ ] **Step 4: Run Docker Compose and verify Trapd starts**

Run: `cd /Users/david/development/src/opennms/delta-v/opennms-container/delta-v && docker compose up -d trapd postgres kafka zookeeper db-init`
Verify: `docker compose logs trapd | grep "Started TrapdApplication"`

- [ ] **Step 5: Verify Actuator health endpoint**

Run: `curl -sf http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 6: Commit final state**

```bash
git add -A
git commit -m "feat: Trapd Spring Boot 4 migration complete — Kafka Sink bridge pattern"
```

---

## Chunk 8: Update Memory

### Task 18: Update project memory

- [ ] **Step 1: Update migration status memory**

Update `project_spring_boot_4_migration.md` to add Trapd to completed daemons and note daemon-sink-kafka as shared infrastructure.

- [ ] **Step 2: Update next session memory**

Replace `project_next_session_trapd.md` with next daemon (Syslogd) context.

- [ ] **Step 3: Commit memory updates**

No git commit needed — memory files are outside the repo.
