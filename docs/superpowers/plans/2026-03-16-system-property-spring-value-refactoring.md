# System Property → Spring @Value Refactoring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all JVM `-D` system properties in Spring Boot daemon Docker commands with Spring's `@Value` → `application.yml` → environment variable pattern, with `System.setProperty()` bridges for legacy code.

**Architecture:** An `EnvironmentPostProcessor` in `daemon-common` bridges static-init properties (`instance.id`, `tsid.node-id`) before any beans are created. `@Value` field injection bridges lazy properties (`rpc.kafka.*`) in existing `@Configuration` classes. Docker `command:` lines are stripped to only `-Xms`/`-Xmx`.

**Tech Stack:** Spring Boot 4.0.3, Spring Framework 7, Java 17

**Prerequisite:** PR #38 (`refactor/system-properties-to-spring-value`) must be merged first — it introduces `OpennmsHomeConfiguration` and the `/opt/deltav` rename for Trapd, Syslogd, EventTranslator, Alarmd.

**Spec:** `docs/superpowers/specs/2026-03-16-system-property-spring-value-refactoring-design.md`

---

## Chunk 1: Shared Properties in `daemon-common`

**Guard:** Before starting, verify PR #38 is merged to develop: `git log --oneline develop | grep "system properties"` should show the `OpennmsHomeConfiguration` commit. If not, merge PR #38 first.

### Task 1: Create SystemPropertyBridgePostProcessor

`SystemInfoUtils` reads `org.opennms.instance.id` in a **static initializer** and caches it forever. We must set the system property before any bean creation triggers class loading. An `EnvironmentPostProcessor` runs before Spring's `refresh()` phase — the only safe option.

**Background:**
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaEventTransportConfiguration.java` — example of `@Value` field injection pattern in daemon-common
- `core/lib/src/main/java/org/opennms/core/utils/SystemInfoUtils.java:48-51` — static initializer reads `org.opennms.instance.id` and `version.display`
- `core/event-forwarder-kafka/src/main/java/org/opennms/core/event/forwarder/kafka/KafkaEventSubscriptionService.java:115` — reads `org.opennms.tsid.node-id` lazily
- Spring Boot 4 uses `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` (not the deprecated `spring.factories`)

**Files:**
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/SystemPropertyBridgePostProcessor.java`
- Create: `core/daemon-common/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`

- [ ] **Step 1: Create the EnvironmentPostProcessor class**

```java
/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.core.daemon.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Bridges Spring Boot properties to JVM system properties for legacy classes
 * that read {@code System.getProperty()} in static initializers or before
 * Spring bean creation.
 *
 * <p>Runs before any {@code @Configuration} class is processed, guaranteeing
 * that static initializers (e.g., {@code SystemInfoUtils}) see the correct
 * values.</p>
 *
 * <p>Registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.</p>
 *
 * <h3>Bridged properties:</h3>
 * <table>
 *   <tr><th>Spring property</th><th>System property</th><th>Consumer</th></tr>
 *   <tr><td>{@code opennms.instance.id}</td>
 *       <td>{@code org.opennms.instance.id}</td>
 *       <td>{@code SystemInfoUtils} static init, Kafka topic names</td></tr>
 *   <tr><td>{@code opennms.tsid.node-id}</td>
 *       <td>{@code org.opennms.tsid.node-id}</td>
 *       <td>{@code KafkaEventSubscriptionService} TSID generation</td></tr>
 * </table>
 */
public class SystemPropertyBridgePostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                        SpringApplication application) {
        bridge(environment, "opennms.instance.id", "org.opennms.instance.id", "OpenNMS");
        bridge(environment, "opennms.tsid.node-id", "org.opennms.tsid.node-id", "0");
    }

    private void bridge(ConfigurableEnvironment env, String springKey,
                         String systemKey, String defaultValue) {
        String value = env.getProperty(springKey, defaultValue);
        System.setProperty(systemKey, value);
    }
}
```

- [ ] **Step 2: Create the SPI registration file**

Note: The `META-INF/spring/` directory does not exist yet — create it first.

Create `core/daemon-common/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` with content:

```
org.opennms.core.daemon.common.SystemPropertyBridgePostProcessor
```

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/SystemPropertyBridgePostProcessor.java \
        core/daemon-common/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports
git commit -m "feat: add SystemPropertyBridgePostProcessor for instance.id and tsid.node-id

EnvironmentPostProcessor bridges Spring properties to system properties
before any bean creation, ensuring SystemInfoUtils static initializer
reads the correct instance ID value."
```

---

### Task 2: Add instance.id and tsid.node-id to all application.yml files

Each daemon needs the new Spring properties mapped to environment variables with sensible defaults. TSID node IDs must be unique per daemon to avoid event ID collisions.

**Background:**
- Karaf daemons use TSID node IDs 4, 5, 7, 14-18 (set via `-D` flags in docker-compose)
- Spring Boot daemons will use 20-24 to avoid collisions
- `core/daemon-boot-trapd/src/main/resources/application.yml:23-24` — example of existing `opennms:` property block

**Files:**
- Modify: `core/daemon-boot-trapd/src/main/resources/application.yml`
- Modify: `core/daemon-boot-syslogd/src/main/resources/application.yml`
- Modify: `core/daemon-boot-eventtranslator/src/main/resources/application.yml`
- Modify: `core/daemon-boot-alarmd/src/main/resources/application.yml`
- Modify: `core/daemon-boot-discovery/src/main/resources/application.yml`

- [ ] **Step 1: Add properties to all 5 application.yml files**

In each file, add these two new blocks under the existing `opennms:` block (after the `home:` line). Do NOT modify the `home:` line — PR #38 handles that separately.

```yaml
  # Add these under the existing opennms: block, after home:
  instance:
    id: ${OPENNMS_INSTANCE_ID:OpenNMS}
  tsid:
    node-id: ${OPENNMS_TSID_NODE_ID:0}
```

Note: `opennms.instance.id` maps to YAML key `opennms.instance.id` via Spring's relaxed binding. The nested YAML structure `opennms: instance: id:` produces the same Spring property key.

- [ ] **Step 2: Verify compilation of all 5 daemons**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-trapd,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-discovery -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS` with all 5 daemon-boot modules listed

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-trapd/src/main/resources/application.yml \
        core/daemon-boot-syslogd/src/main/resources/application.yml \
        core/daemon-boot-eventtranslator/src/main/resources/application.yml \
        core/daemon-boot-alarmd/src/main/resources/application.yml \
        core/daemon-boot-discovery/src/main/resources/application.yml
git commit -m "feat: add instance.id and tsid.node-id to all daemon application.yml files

Maps OPENNMS_INSTANCE_ID and OPENNMS_TSID_NODE_ID environment variables
to Spring properties consumed by SystemPropertyBridgePostProcessor."
```

---

### Task 3: Add TSID node IDs and instance ID to Docker environment

**Background:**
- `opennms-container/delta-v/docker-compose.yml:225-233` — Trapd environment block example
- Karaf daemons use TSID node IDs 4, 5, 7, 14-18
- Spring Boot daemons get 20 (Trapd), 21 (Syslogd), 22 (EventTranslator), 23 (Alarmd), 24 (Discovery)

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Add env vars to 4 Spring Boot daemon services (not Discovery)**

Add `OPENNMS_INSTANCE_ID` and `OPENNMS_TSID_NODE_ID` to the `environment:` block of Trapd, Syslogd, EventTranslator, and Alarmd. Skip Discovery — Task 5 replaces its entire Docker block.

**Trapd** (after `INTERFACE_NODE_CACHE_REFRESH_MS`):
```yaml
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "20"
```

**Syslogd:**
```yaml
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "21"
```

**EventTranslator:**
```yaml
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "22"
```

**Alarmd:**
```yaml
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "23"
```

- [ ] **Step 2: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "feat: add OPENNMS_INSTANCE_ID and OPENNMS_TSID_NODE_ID to Spring Boot daemon envs

Each daemon gets a unique TSID node-id (20-24) to prevent event ID
collisions. IDs 4-18 are reserved for Karaf-based daemons."
```

---

### Task 4: Bridge RPC Kafka properties in KafkaRpcClientConfiguration

`KafkaRpcClientFactory.start()` reads bootstrap servers from `OnmsKafkaConfigProvider`, which scans system properties with prefix `org.opennms.core.ipc.rpc.kafka.`. `KafkaRpcClientFactory.execute()` reads `org.opennms.core.ipc.rpc.force-remote` via `Boolean.getBoolean()`. Both must be bridged before `start()` is called.

This also fixes an existing bug: Discovery's docker-compose was missing `-Dorg.opennms.core.ipc.rpc.force-remote=true`, meaning ping sweeps for the "Default" location executed locally instead of delegating to Minion.

**Background:**
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java` — current class (no `@Value` fields, no bridges)
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaEventTransportConfiguration.java:53-66` — example of `@Value` field injection pattern to follow
- `core/ipc/rpc/kafka/src/main/java/org/opennms/core/ipc/rpc/kafka/KafkaRpcClientFactory.java:164` — reads `org.opennms.core.ipc.rpc.force-remote`
- `core/ipc/rpc/kafka/src/main/java/org/opennms/core/ipc/rpc/kafka/KafkaRpcClientFactory.java:332-334` — reads bootstrap servers via `OnmsKafkaConfigProvider`

**Files:**
- Modify: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java`

- [ ] **Step 1: Add @Value fields and System.setProperty bridges**

Replace the entire `KafkaRpcClientConfiguration.java` with:

```java
/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.core.daemon.common;

import com.codahale.metrics.MetricRegistry;

import org.opennms.core.ipc.rpc.kafka.KafkaRpcClientFactory;
import org.opennms.core.rpc.utils.RpcTargetHelper;
import org.opennms.core.tracing.api.TracerRegistry;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} for the Kafka RPC client infrastructure.
 *
 * <p>Shared by all daemons that send Kafka RPC requests to Minions:
 * Discovery, Pollerd, Collectd, Enlinkd, PerspectivePoller, Provisiond.</p>
 *
 * <p>Bridges Spring properties to system properties for legacy
 * {@code KafkaRpcClientFactory} which reads configuration via
 * {@code OnmsKafkaConfigProvider} (system property scan) and
 * {@code Boolean.getBoolean()} calls.</p>
 */
@Configuration
@ConditionalOnProperty(name = "opennms.rpc.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaRpcClientConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcClientConfiguration.class);

    @Value("${opennms.rpc.kafka.bootstrap-servers:kafka:9092}")
    private String rpcBootstrapServers;

    @Value("${opennms.rpc.kafka.force-remote:true}")
    private String forceRemote;

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

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "opennms.rpc.kafka.enabled", havingValue = "true", matchIfMissing = false)
    public KafkaRpcClientFactory rpcClientFactory(DistPollerDao distPollerDao,
                                                   MetricRegistry kafkaRpcMetricRegistry) {
        System.setProperty("org.opennms.core.ipc.rpc.kafka.bootstrap.servers", rpcBootstrapServers);
        System.setProperty("org.opennms.core.ipc.rpc.force-remote", forceRemote);
        LOG.info("Bridged RPC Kafka bootstrap.servers={}, force-remote={}", rpcBootstrapServers, forceRemote);

        var factory = new KafkaRpcClientFactory();
        factory.setLocation(distPollerDao.whoami().getLocation());
        factory.setMetrics(kafkaRpcMetricRegistry);
        return factory;
    }
}
```

- [ ] **Step 2: Add RPC properties to Discovery application.yml**

In `core/daemon-boot-discovery/src/main/resources/application.yml`, add two new keys under the **existing** `opennms.rpc.kafka` block (which already has `enabled`). Do NOT duplicate the `rpc:` or `kafka:` keys.

Add these two lines after the existing `enabled:` line:

```yaml
      bootstrap-servers: ${RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
      force-remote: ${RPC_KAFKA_FORCE_REMOTE:true}
```

The resulting block should look like:

```yaml
  rpc:
    kafka:
      enabled: ${RPC_KAFKA_ENABLED:true}
      bootstrap-servers: ${RPC_KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
      force-remote: ${RPC_KAFKA_FORCE_REMOTE:true}
```

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-common,:org.opennms.core.daemon-boot-discovery -am install 2>&1 | tail -10`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/KafkaRpcClientConfiguration.java \
        core/daemon-boot-discovery/src/main/resources/application.yml
git commit -m "feat: bridge RPC Kafka system properties via @Value in KafkaRpcClientConfiguration

Replaces -Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers and
-Dorg.opennms.core.ipc.rpc.force-remote Docker command-line flags
with Spring @Value injection from application.yml.

Also fixes bug: Discovery was missing force-remote=true, causing
local RPC execution instead of Minion delegation."
```

---

### Task 5: Docker cleanup for Discovery — remove -D flags, apply /opt/deltav

Discovery still has `-Dopennms.home=/opt/sentinel` and `-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092` on its Docker command line, and uses `/opt/sentinel` paths. Now that all properties flow through Spring, clean this up.

**Background:**
- `opennms-container/delta-v/docker-compose.yml:186-208` — Discovery service block
- PR #38 missed Discovery because it wasn't on develop at the time

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Update Discovery service block**

Replace the Discovery `command:`, `environment:`, and `volumes:` with:

```yaml
  discovery:
    profiles: [passive, full]
    image: opennms/daemon-deltav:${VERSION}
    container_name: delta-v-discovery
    hostname: discovery
    entrypoint: []
    command: ["java", "-Xms256m", "-Xmx512m", "-jar", "/opt/daemon-boot-discovery.jar"]
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
      OPENNMS_HOME: /opt/deltav
      INTERFACE_NODE_CACHE_REFRESH_MS: "15000"
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "24"
      RPC_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      RPC_KAFKA_FORCE_REMOTE: "true"
    volumes:
      - ./discovery-overlay/etc:/opt/deltav/etc:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Changes from current:
- `command:` — removed `-Dopennms.home=/opt/sentinel` and `-Dorg.opennms.core.ipc.rpc.kafka.bootstrap.servers=kafka:9092`
- `environment:` — changed `OPENNMS_HOME` to `/opt/deltav`, added `OPENNMS_INSTANCE_ID`, `OPENNMS_TSID_NODE_ID`, `RPC_KAFKA_BOOTSTRAP_SERVERS`, `RPC_KAFKA_FORCE_REMOTE`
- `volumes:` — changed mount from `/opt/sentinel/etc` to `/opt/deltav/etc`

- [ ] **Step 2: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "refactor: clean up Discovery Docker config — remove -D flags, apply /opt/deltav

All Discovery configuration now flows through Spring environment
variables. No -D flags remain except -Xms/-Xmx."
```

---

## Chunk 2: Daemon-Specific Properties and Documentation

### Task 6: Bridge Syslogd DNS cache config

`SyslogSinkConsumer` reads `org.opennms.netmgt.syslogd.dnscache.config` in its constructor (line 93 of `SyslogSinkConsumer.java`). The `SyslogdConfiguration` `@Bean` method must set the system property before creating the consumer.

**Background:**
- `core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdConfiguration.java:58-68` — `syslogSinkConsumer` bean method
- `features/events/syslog/src/main/java/org/opennms/netmgt/syslogd/SyslogSinkConsumer.java:93` — reads the system property

**Files:**
- Modify: `core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdConfiguration.java`
- Modify: `core/daemon-boot-syslogd/src/main/resources/application.yml`

- [ ] **Step 1: Add @Value field and bridge in SyslogdConfiguration**

Add the import and field at the top of the class:

```java
import org.springframework.beans.factory.annotation.Value;
```

Add field inside the class:

```java
    @Value("${opennms.syslogd.dnscache.config:maximumSize=1000,expireAfterWrite=8h}")
    private String dnsCacheConfig;
```

Modify the `syslogSinkConsumer` bean method to bridge before construction:

```java
    @Bean
    public SyslogSinkConsumer syslogSinkConsumer(MetricRegistry metricRegistry) {
        System.setProperty("org.opennms.netmgt.syslogd.dnscache.config", dnsCacheConfig);
        return new SyslogSinkConsumer(metricRegistry);
    }
```

- [ ] **Step 2: Add DNS cache config to Syslogd application.yml**

Add under the `opennms:` block:

```yaml
  syslogd:
    dnscache:
      config: ${SYSLOGD_DNS_CACHE_CONFIG:maximumSize=1000,expireAfterWrite=8h}
```

- [ ] **Step 3: Verify compilation**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-syslogd -am install 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdConfiguration.java \
        core/daemon-boot-syslogd/src/main/resources/application.yml
git commit -m "feat: bridge Syslogd DNS cache config via @Value

Replaces System.getProperty() in SyslogSinkConsumer constructor with
Spring @Value injection. Configurable via SYSLOGD_DNS_CACHE_CONFIG
environment variable."
```

---

### Task 7: Write system property inventory document

The inventory serves two purposes: (1) reference for operators deploying Delta-V daemons, (2) migration checklist for future daemon conversions.

**Files:**
- Create: `docs/spring-boot-migration/system-property-inventory.md`

- [ ] **Step 1: Create the inventory document**

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add docs/spring-boot-migration/system-property-inventory.md
git commit -m "docs: add system property inventory and migration methodology

Complete inventory of bridged and deferred system properties across
all 5 Spring Boot daemons. Includes TSID node-id assignments and
reusable migration checklist for future daemon conversions."
```

---

### Task 8: Create PR

- [ ] **Step 1: Create feature branch and push**

```bash
git checkout -b refactor/system-property-bridges
git push -u delta-v refactor/system-property-bridges
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --repo pbrane/delta-v --base develop \
  --title "refactor: bridge system properties to Spring @Value across all daemons" \
  --body "$(cat <<'EOF'
## Summary

- **SystemPropertyBridgePostProcessor** — `EnvironmentPostProcessor` in `daemon-common` that bridges `org.opennms.instance.id` and `org.opennms.tsid.node-id` to system properties before any bean creation (required because `SystemInfoUtils` reads in a static initializer)
- **KafkaRpcClientConfiguration** — bridges RPC Kafka bootstrap servers and force-remote flag via `@Value` field injection. Fixes existing bug where Discovery was missing `force-remote=true`
- **SyslogdConfiguration** — bridges DNS cache config for `SyslogSinkConsumer`
- **Discovery Docker cleanup** — removed all `-D` flags, applied `/opt/deltav` rename
- **TSID node-id assignments** — each Spring Boot daemon gets a unique ID (20-24) to prevent event ID collisions
- **System property inventory** — complete reference doc at `docs/spring-boot-migration/system-property-inventory.md`

Depends on: PR #38 (merged)

## Test plan

- [x] All 5 daemons compile (`daemon-boot-trapd`, `syslogd`, `eventtranslator`, `alarmd`, `discovery`)
- [ ] Docker rebuild and verify all 5 Spring Boot daemons start
- [ ] E2E: Kafka topics prefixed with correct instance ID
- [ ] E2E: Discovery ping sweep delegates to Minion (force-remote bridge)
- [ ] E2E: trap and syslog pipelines functional
EOF
)"
```
