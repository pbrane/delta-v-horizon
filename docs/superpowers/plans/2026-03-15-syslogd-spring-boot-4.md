# Syslogd Spring Boot 4 Migration — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Syslogd to a Spring Boot 4.0.3 microservice that consumes syslog messages from Minion via Kafka Sink and publishes enriched events to `opennms-fault-events`.

**Architecture:** Extract shared JDBC classes from `daemon-boot-trapd` to `daemon-common`, then create `daemon-boot-syslogd` following the same pattern as Trapd. Reuses `daemon-sink-kafka` for Kafka Sink bridge and `daemon-common` for JDBC infrastructure. New code: `SyslogdConfiguration`, `LocalDnsLookupClient`.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Apache Kafka, PostgreSQL (JDBC), Protobuf (SinkMessage)

**Spec:** `docs/superpowers/specs/2026-03-15-syslogd-spring-boot-4-design.md`

---

## File Structure

### Modified: `core/daemon-common/`

| File | Change |
|------|--------|
| `src/main/java/org/opennms/core/daemon/common/JdbcDistPollerDao.java` | **Move** from daemon-boot-trapd, change package, remove `@Component` |
| `src/main/java/org/opennms/core/daemon/common/JdbcInterfaceToNodeCache.java` | **Move** from daemon-boot-trapd, change package, remove `@Component`, generalize `@Scheduled` property name, add `AbstractInterfaceToNodeCache.setInstance()` call |
| `src/test/java/org/opennms/core/daemon/common/JdbcInterfaceToNodeCacheTest.java` | **Move** from daemon-boot-trapd, change package |
| `pom.xml` | Add `opennms-dao-api` dependency (for `DistPollerDao`, `InterfaceToNodeCache`) |

### Modified: `core/daemon-boot-trapd/`

| File | Change |
|------|--------|
| `src/main/java/.../trapd/boot/JdbcDistPollerDao.java` | **Delete** (moved to daemon-common) |
| `src/main/java/.../trapd/boot/JdbcInterfaceToNodeCache.java` | **Delete** (moved to daemon-common) |
| `src/test/java/.../trapd/boot/JdbcInterfaceToNodeCacheTest.java` | **Delete** (moved to daemon-common) |
| `src/main/java/.../trapd/boot/TrapdConfiguration.java` | Update imports to `daemon-common` package, add `AbstractInterfaceToNodeCache.setInstance()` |
| `src/main/resources/application.yml` | Update property name to `opennms.daemon.interface-to-node-cache.refresh-interval-ms` |

### New: `core/daemon-boot-syslogd/`

| File | Responsibility |
|------|---------------|
| `pom.xml` | Maven module — Spring Boot 4, daemon-common, daemon-sink-kafka, syslog feature |
| `src/main/java/.../syslogd/boot/SyslogdApplication.java` | Spring Boot entry point, excludes JPA auto-config |
| `src/main/java/.../syslogd/boot/SyslogdConfiguration.java` | Wires SyslogSinkConsumer, SyslogSinkModule, SyslogdConfig, MetricRegistry, InterfaceToNodeCache |
| `src/main/java/.../syslogd/boot/LocalDnsLookupClient.java` | Local `LocationAwareDnsLookupClient` implementation |
| `src/main/resources/application.yml` | DataSource, Kafka, logging, cache refresh |
| `src/test/java/.../syslogd/boot/LocalDnsLookupClientTest.java` | Unit test for DNS client |

### Modified: Docker/Build

| File | Change |
|------|--------|
| `core/pom.xml` | Add `daemon-boot-syslogd`, remove `daemon-loader-syslogd` |
| `opennms-container/delta-v/build.sh` | Stage `daemon-boot-syslogd.jar`, remove `daemon-loader-syslogd.jar` |
| `opennms-container/delta-v/Dockerfile.daemon` | COPY `daemon-boot-syslogd.jar`, remove daemon-loader-syslogd COPY |
| `opennms-container/delta-v/docker-compose.yml` | Replace syslogd service with Spring Boot config |
| `container/features/src/main/resources/features.xml` | Remove `opennms-daemon-syslogd` feature |

### Deleted

| Path | Reason |
|------|--------|
| `core/daemon-loader-syslogd/` | Replaced by daemon-sink-kafka + daemon-boot-syslogd |

---

## Chunk 1: Extract JDBC Classes to daemon-common

### Task 1: Move JdbcDistPollerDao and JdbcInterfaceToNodeCache to daemon-common

**Files:**
- Move: `core/daemon-boot-trapd/src/main/java/.../trapd/boot/JdbcDistPollerDao.java` → `core/daemon-common/src/main/java/org/opennms/core/daemon/common/JdbcDistPollerDao.java`
- Move: `core/daemon-boot-trapd/src/main/java/.../trapd/boot/JdbcInterfaceToNodeCache.java` → `core/daemon-common/src/main/java/org/opennms/core/daemon/common/JdbcInterfaceToNodeCache.java`
- Move: `core/daemon-boot-trapd/src/test/java/.../trapd/boot/JdbcInterfaceToNodeCacheTest.java` → `core/daemon-common/src/test/java/org/opennms/core/daemon/common/JdbcInterfaceToNodeCacheTest.java`
- Modify: `core/daemon-common/pom.xml`
- Modify: `core/daemon-boot-trapd/src/main/java/.../trapd/boot/TrapdConfiguration.java`
- Modify: `core/daemon-boot-trapd/src/main/resources/application.yml`

- [ ] **Step 1: Add dependencies to daemon-common POM**

Add to `core/daemon-common/pom.xml` `<dependencies>` section (these are needed for `JdbcDistPollerDao` and `JdbcInterfaceToNodeCache`):

```xml
        <!-- DAO APIs (DistPollerDao, InterfaceToNodeCache) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Model (OnmsDistPoller, OnmsMonitoringSystem) -->
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
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- JDBC (for JdbcTemplate in JdbcDistPollerDao, JdbcInterfaceToNodeCache) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
```

Note: Check if `opennms-dao-api` and `opennms-model` are already in daemon-common's POM from the JPA infrastructure. If so, skip adding them.

- [ ] **Step 2: Copy files to daemon-common with new package**

Copy the 3 files to `core/daemon-common/src/main/java/org/opennms/core/daemon/common/` and `core/daemon-common/src/test/java/org/opennms/core/daemon/common/`, then update each file:

**JdbcDistPollerDao.java** changes:
- Package: `org.opennms.core.daemon.common`
- Remove `@Component` annotation and its import

**JdbcInterfaceToNodeCache.java** changes:
- Package: `org.opennms.core.daemon.common`
- Change `@Scheduled` property from `opennms.trapd.interface-to-node-cache.refresh-interval-ms` to `opennms.daemon.interface-to-node-cache.refresh-interval-ms`
- Add `AbstractInterfaceToNodeCache.setInstance(this)` call in the `refresh()` method, after populating the cache, so that `ConvertToEvent` (Syslogd) can use the static singleton:

```java
    @Scheduled(fixedDelayString = "${opennms.daemon.interface-to-node-cache.refresh-interval-ms:300000}",
               initialDelayString = "0")
    public void refresh() {
        if (jdbc == null) return;
        long start = System.currentTimeMillis();
        var newCache = new ConcurrentHashMap<LocationIpKey, Entry>();

        jdbc.query(LOAD_SQL, rs -> {
            // ... existing code ...
        });

        cache.clear();
        cache.putAll(newCache);
        AbstractInterfaceToNodeCache.setInstance(this);
        LOG.info("InterfaceToNodeCache refreshed: {} entries in {} ms",
                cache.size(), System.currentTimeMillis() - start);
    }
```

Add import: `import org.opennms.netmgt.dao.api.AbstractInterfaceToNodeCache;`

**JdbcInterfaceToNodeCacheTest.java** changes:
- Package: `org.opennms.core.daemon.common`

- [ ] **Step 3: Delete old files from daemon-boot-trapd**

Delete:
- `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcDistPollerDao.java`
- `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCache.java`
- `core/daemon-boot-trapd/src/test/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCacheTest.java`

- [ ] **Step 4: Update TrapdConfiguration imports**

In `core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/TrapdConfiguration.java`:

Change the `interfaceToNodeCache` bean to import from daemon-common and set the static singleton:

```java
    @Bean
    public InterfaceToNodeCache interfaceToNodeCache(DataSource dataSource) {
        var cache = new org.opennms.core.daemon.common.JdbcInterfaceToNodeCache(dataSource);
        return cache;
    }
```

Since `JdbcDistPollerDao` is no longer annotated with `@Component`, add an explicit `@Bean` in `TrapdConfiguration`:

```java
    @Bean
    public DistPollerDao distPollerDao(DataSource dataSource) {
        return new org.opennms.core.daemon.common.JdbcDistPollerDao(dataSource);
    }
```

Note: Trapd does NOT need `AbstractInterfaceToNodeCache.setInstance()` — `EventCreator` injects `InterfaceToNodeCache` directly, it does not use the static singleton. The `setInstance()` call in `refresh()` is for Syslogd's `ConvertToEvent` which uses `AbstractInterfaceToNodeCache.getInstance()`.

- [ ] **Step 5: Update Trapd application.yml**

In `core/daemon-boot-trapd/src/main/resources/application.yml`, change:
```yaml
opennms:
  trapd:
    interface-to-node-cache:
      refresh-interval-ms: ${INTERFACE_NODE_CACHE_REFRESH_MS:300000}
```
to:
```yaml
opennms:
  daemon:
    interface-to-node-cache:
      refresh-interval-ms: ${INTERFACE_NODE_CACHE_REFRESH_MS:300000}
```

- [ ] **Step 6: Compile and test daemon-common**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml -DskipTests compile`
Then: `./maven/bin/mvn -f core/daemon-common/pom.xml test`

- [ ] **Step 7: Install daemon-common and compile daemon-boot-trapd**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml -DskipTests install`
Then: `./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test`

- [ ] **Step 8: Commit**

```bash
git add core/daemon-common/ core/daemon-boot-trapd/
git rm core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcDistPollerDao.java \
       core/daemon-boot-trapd/src/main/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCache.java \
       core/daemon-boot-trapd/src/test/java/org/opennms/netmgt/trapd/boot/JdbcInterfaceToNodeCacheTest.java
git commit -m "refactor: extract JdbcDistPollerDao and JdbcInterfaceToNodeCache to daemon-common

Shared JDBC infrastructure for all Spring Boot daemon containers.
Generalized @Scheduled property name. Added AbstractInterfaceToNodeCache.setInstance()
for ConvertToEvent static singleton lookup (Syslogd)."
```

---

## Chunk 2: Create daemon-boot-syslogd Module

### Task 2: Create POM and SyslogdApplication

**Files:**
- Create: `core/daemon-boot-syslogd/pom.xml`
- Create: `core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdApplication.java`
- Create: `core/daemon-boot-syslogd/src/main/resources/application.yml`
- Modify: `core/pom.xml`

- [ ] **Step 1: Create POM**

Fork from `core/daemon-boot-trapd/pom.xml`. Key differences:
- artifactId: `org.opennms.core.daemon-boot-syslogd`
- name: `OpenNMS :: Core :: Daemon Boot :: Syslogd`
- Replace traps feature dep with syslog feature dep:

```xml
        <!-- Syslogd feature (SyslogSinkConsumer, SyslogSinkModule, ConvertToEvent) -->
        <dependency>
            <groupId>org.opennms.features.events</groupId>
            <artifactId>org.opennms.features.events.syslog</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <!-- Same ServiceMix/Hibernate/SLF4J exclusions as traps feature -->
            </exclusions>
        </dependency>
```

Add syslog-specific dependencies:
```xml
        <!-- Provision API (LocationAwareDnsLookupClient interface) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-provision-api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Dropwizard Metrics (SyslogSinkConsumer constructor requires MetricRegistry) -->
        <dependency>
            <groupId>io.dropwizard.metrics</groupId>
            <artifactId>metrics-core</artifactId>
        </dependency>
```

Remove Trapd-specific deps: traps feature, `opennms-config` (for DefaultEventConfDao — not needed for Syslogd).

Keep: `opennms-config` (still needed for `SyslogdConfigFactory`), `opennms-config-api`, `opennms-dao-api`, `opennms-model`, daemon-common, daemon-sink-kafka, Jackson pins, JUnit pins, all established patterns.

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-syslogd</module>` after `daemon-boot-trapd`.

- [ ] **Step 3: Create SyslogdApplication**

```java
package org.opennms.netmgt.syslogd.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.core.daemon.sink.kafka",
        "org.opennms.netmgt.syslogd.boot"
    },
    exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
    }
)
@EnableScheduling
public class SyslogdApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyslogdApplication.class, args);
    }
}
```

Note: Verify the exact Spring Boot 4 auto-config class locations — they changed from Spring Boot 3. The Trapd module has the correct imports; copy from there.

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
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-syslogd}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}
    sink:
      consumer-group: ${KAFKA_SINK_CONSUMER_GROUP:opennms-syslogd-sink}
  daemon:
    interface-to-node-cache:
      refresh-interval-ms: ${INTERFACE_NODE_CACHE_REFRESH_MS:300000}

logging:
  level:
    org.opennms: INFO
    org.opennms.core.daemon.sink.kafka: INFO
```

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-syslogd/ core/pom.xml
git commit -m "feat: add daemon-boot-syslogd module — Spring Boot 4 Syslogd skeleton"
```

### Task 3: Create LocalDnsLookupClient with test

**Files:**
- Create: `core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/LocalDnsLookupClient.java`
- Create: `core/daemon-boot-syslogd/src/test/java/org/opennms/netmgt/syslogd/boot/LocalDnsLookupClientTest.java`

- [ ] **Step 1: Write test**

```java
package org.opennms.netmgt.syslogd.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class LocalDnsLookupClientTest {

    private final LocalDnsLookupClient client = new LocalDnsLookupClient();

    @Test
    void lookupResolvesHostname() throws Exception {
        String result = client.lookup("localhost", "Default").get();
        assertThat(result).isNotNull();
    }

    @Test
    void lookupWithSystemIdDelegates() throws Exception {
        String result = client.lookup("localhost", "Default", "sys-1").get();
        assertThat(result).isNotNull();
    }

    @Test
    void reverseLookupResolvesAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        String result = client.reverseLookup(addr, "Default").get();
        assertThat(result).isNotNull();
    }

    @Test
    void reverseLookupWithSystemIdDelegates() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        String result = client.reverseLookup(addr, "Default", "sys-1").get();
        assertThat(result).isNotNull();
    }
}
```

- [ ] **Step 2: Write implementation**

```java
package org.opennms.netmgt.syslogd.boot;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

import org.opennms.netmgt.provision.LocationAwareDnsLookupClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local DNS lookup client for Spring Boot daemon containers.
 *
 * <p>Performs DNS resolution locally using {@link InetAddress} rather than
 * dispatching via Kafka RPC to a location-aware Minion. Location and systemId
 * parameters are ignored.</p>
 *
 * <p><strong>Known limitation:</strong> gives wrong answers for private IPs
 * from remote Minion networks. Deferred item: have Minion include resolved
 * hostname in Kafka Sink message envelope.</p>
 */
public class LocalDnsLookupClient implements LocationAwareDnsLookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDnsLookupClient.class);

    @Override
    public CompletableFuture<String> lookup(String hostName, String location) {
        return lookup(hostName, location, null);
    }

    @Override
    public CompletableFuture<String> lookup(String hostName, String location, String systemId) {
        try {
            String address = InetAddress.getByName(hostName).getHostAddress();
            return CompletableFuture.completedFuture(address);
        } catch (Exception e) {
            LOG.debug("DNS lookup failed for {}: {}", hostName, e.getMessage());
            return CompletableFuture.completedFuture(hostName);
        }
    }

    @Override
    public CompletableFuture<String> reverseLookup(InetAddress ipAddress, String location) {
        return reverseLookup(ipAddress, location, null);
    }

    @Override
    public CompletableFuture<String> reverseLookup(InetAddress ipAddress, String location, String systemId) {
        String hostname = ipAddress.getCanonicalHostName();
        return CompletableFuture.completedFuture(hostname);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml test -Dtest=LocalDnsLookupClientTest`

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-syslogd/src/
git commit -m "feat: add LocalDnsLookupClient — local DNS for Spring Boot daemons"
```

### Task 4: Create SyslogdConfiguration

**Files:**
- Create: `core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdConfiguration.java`

- [ ] **Step 1: Write SyslogdConfiguration**

```java
package org.opennms.netmgt.syslogd.boot;

import javax.sql.DataSource;

import com.codahale.metrics.MetricRegistry;

import org.opennms.core.daemon.common.JdbcDistPollerDao;
import org.opennms.core.daemon.common.JdbcInterfaceToNodeCache;
import org.opennms.netmgt.config.SyslogdConfigFactory;
import org.opennms.netmgt.config.SyslogdConfig;
import org.opennms.netmgt.dao.api.AbstractInterfaceToNodeCache;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.opennms.netmgt.provision.LocationAwareDnsLookupClient;
import org.opennms.netmgt.syslogd.SyslogSinkConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot @Configuration that wires all Syslogd beans.
 *
 * <p>Replaces the Karaf-era {@code applicationContext-daemon-loader-syslogd.xml}.</p>
 *
 * <p>{@code SyslogSinkConsumer} implements {@code InitializingBean} — Spring
 * calls {@code afterPropertiesSet()} natively after {@code @Autowired} injection.
 * No {@code initMethod} workaround needed (unlike Trapd's {@code javax.annotation.PostConstruct}).</p>
 */
@Configuration
public class SyslogdConfiguration {

    @Bean
    public SyslogdConfig syslogdConfig() throws Exception {
        return new SyslogdConfigFactory();
    }

    @Bean
    public MetricRegistry syslogdMetricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public DistPollerDao distPollerDao(DataSource dataSource) {
        return new JdbcDistPollerDao(dataSource);
    }

    @Bean
    public InterfaceToNodeCache interfaceToNodeCache(DataSource dataSource) {
        // AbstractInterfaceToNodeCache.setInstance() is called inside refresh(),
        // which fires immediately via @Scheduled(initialDelayString = "0").
        // This ensures the singleton is set after data is loaded, not before.
        return new JdbcInterfaceToNodeCache(dataSource);
    }

    @Bean
    public LocationAwareDnsLookupClient locationAwareDnsLookupClient() {
        return new LocalDnsLookupClient();
    }

    @Bean
    public SyslogSinkConsumer syslogSinkConsumer(MetricRegistry metricRegistry) {
        // SyslogSinkConsumer implements InitializingBean — Spring calls
        // afterPropertiesSet() after @Autowired injection completes.
        // afterPropertiesSet() registers consumer with MessageConsumerManager,
        // which triggers KafkaSinkBridge.setModule(), starting Kafka polling.
        //
        // Note: SyslogSinkConsumer.getModule() internally creates its own
        // SyslogSinkModule using its @Autowired syslogdConfig and distPollerDao.
        // No separate SyslogSinkModule @Bean is needed.
        return new SyslogSinkConsumer(metricRegistry);
    }
}
```

- [ ] **Step 2: Compile**

Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml compile`

Fix any dependency conflicts following the established pattern.

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-syslogd/src/main/java/org/opennms/netmgt/syslogd/boot/SyslogdConfiguration.java
git commit -m "feat: add SyslogdConfiguration — wires all Syslogd beans for Spring Boot 4"
```

---

## Chunk 3: Docker Infrastructure and Cleanup

### Task 5: Update Docker infrastructure

**Files:**
- Modify: `opennms-container/delta-v/build.sh`
- Modify: `opennms-container/delta-v/Dockerfile.daemon`
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Update build.sh**

In `do_stage_daemon_jars()`, find:
```
"core/daemon-loader-syslogd/target/org.opennms.core.daemon-loader-syslogd-$VERSION.jar:daemon-loader-syslogd.jar"
```
Replace with:
```
"core/daemon-boot-syslogd/target/org.opennms.core.daemon-boot-syslogd-$VERSION.jar:daemon-boot-syslogd.jar"
```

- [ ] **Step 2: Update Dockerfile.daemon**

Remove the daemon-loader-syslogd COPY line:
```dockerfile
COPY staging/daemon/daemon-loader-syslogd.jar \
     /opt/sentinel/system/org/opennms/core/org.opennms.core.daemon-loader-syslogd/${VERSION}/org.opennms.core.daemon-loader-syslogd-${VERSION}.jar
```

Add after the daemon-boot-trapd.jar COPY:
```dockerfile
COPY staging/daemon/daemon-boot-syslogd.jar /opt/daemon-boot-syslogd.jar
```

- [ ] **Step 3: Update docker-compose.yml**

Replace the syslogd service block (around lines 244-274) with:

```yaml
  syslogd:
    profiles: [passive, full]
    image: opennms/daemon-deltav:${VERSION}
    container_name: delta-v-syslogd
    hostname: syslogd
    entrypoint: []
    command: ["java", "-Xms256m", "-Xmx512m", "-Dopennms.home=/opt/sentinel", "-jar", "/opt/daemon-boot-syslogd.jar"]
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
      KAFKA_CONSUMER_GROUP: opennms-syslogd
      KAFKA_SINK_CONSUMER_GROUP: opennms-syslogd-sink
      OPENNMS_HOME: /opt/sentinel
      INTERFACE_NODE_CACHE_REFRESH_MS: "15000"
    volumes:
      - ./syslogd-overlay/etc:/opt/sentinel/etc:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Remove `syslogd-data:` from the volumes section at the bottom.

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/
git commit -m "build: update Docker infrastructure for Spring Boot Syslogd"
```

### Task 6: Delete old Karaf artifacts

**Files:**
- Modify: `container/features/src/main/resources/features.xml`
- Modify: `core/pom.xml`
- Delete: `core/daemon-loader-syslogd/`

- [ ] **Step 1: Remove Karaf feature**

Search for `opennms-daemon-syslogd` in `container/features/src/main/resources/features.xml` and delete the entire `<feature name="opennms-daemon-syslogd" ...>...</feature>` block.

Run: `grep -n "opennms-daemon-syslogd" container/features/src/main/resources/features.xml` to find exact lines.

- [ ] **Step 2: Remove module from core/pom.xml**

Remove: `<module>daemon-loader-syslogd</module>`

- [ ] **Step 3: Delete directory**

```bash
rm -rf core/daemon-loader-syslogd/
```

- [ ] **Step 4: Commit**

```bash
git rm -r core/daemon-loader-syslogd/
git add core/pom.xml container/features/
git commit -m "refactor: delete daemon-loader-syslogd and Karaf feature — replaced by Spring Boot"
```

---

## Chunk 4: Build Verification

### Task 7: Compile, test, package

- [ ] **Step 1: Install daemon-common**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml -DskipTests install`

- [ ] **Step 2: Install daemon-sink-kafka**

Run: `./maven/bin/mvn -f core/daemon-sink-kafka/pom.xml -DskipTests install`

- [ ] **Step 3: Compile daemon-boot-syslogd**

Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml -DskipTests compile`

- [ ] **Step 4: Run all tests**

Run: `./maven/bin/mvn -f core/daemon-common/pom.xml test`
Run: `./maven/bin/mvn -f core/daemon-boot-trapd/pom.xml test`
Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml test`

- [ ] **Step 5: Package fat JAR**

Run: `./maven/bin/mvn -f core/daemon-boot-syslogd/pom.xml -DskipTests package`

Verify: `ls -lh core/daemon-boot-syslogd/target/org.opennms.core.daemon-boot-syslogd-36.0.0-SNAPSHOT.jar`

- [ ] **Step 6: Fix dependency conflicts**

If compilation fails, fix following the established pattern:
- ServiceMix Spring exclusions
- Jackson 2.19.4 pins
- SLF4J 2.0.17 pins
- javax.persistence-api runtime

- [ ] **Step 7: Commit fixes (if any)**

```bash
git add -A
git commit -m "fix: resolve Spring Boot 4 dependency conflicts for Syslogd"
```

---

## Chunk 5: Memory Update

### Task 8: Update project memory

- [ ] **Step 1: Update migration status**

Update `project_spring_boot_4_migration.md`: add Syslogd to completed daemons, note shared JDBC extraction.

- [ ] **Step 2: Update next session**

Replace `project_next_session_trapd.md` content with Telemetryd context.
