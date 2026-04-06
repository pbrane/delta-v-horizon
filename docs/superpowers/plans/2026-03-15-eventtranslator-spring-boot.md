# EventTranslator Spring Boot 4 Migration Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate EventTranslator from Karaf/OSGi to Spring Boot 4.0.3, delete the Karaf daemon-loader, and validate with the existing Minion E2E test.

**Architecture:** New `core/daemon-boot-eventtranslator` Spring Boot module following the Alarmd pattern. EventTranslator receives events via Kafka, applies XML-configured translation rules with optional SQL enrichment, and sends translated events back to Kafka. No JPA — uses raw JDBC `DataSource` only.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Kafka, HikariCP, JAXB config

**Spec:** `docs/superpowers/specs/2026-03-15-eventtranslator-spring-boot-design.md`

**Worktree:** `/Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd` (branch `feature/spring-boot-4-alarmd-migration`)

---

## Chunk 1: Module, Config, Bug Fix

### Task 1: Fix EventTranslatorConfigFactory.update() DataSource bug

**Files:**
- Modify: `opennms-config/src/main/java/org/opennms/netmgt/config/EventTranslatorConfigFactory.java:142-144,152-168`

- [ ] **Step 1: Read the buggy code**

In `EventTranslatorConfigFactory.java`, the `update()` method (line 152) calls `unmarshall(stream)` (line 161), which delegates to `unmarshall(stream, null)` (line 143), setting `m_dbConnFactory = null` (line 138). This wipes the DataSource on every config reload.

- [ ] **Step 2: Fix unmarshall(stream) to preserve DataSource**

Change `unmarshall(InputStream stream)` (lines 142-144) from:
```java
private synchronized void unmarshall(InputStream stream) throws IOException {
    unmarshall(stream, null);
}
```
to:
```java
private synchronized void unmarshall(InputStream stream) throws IOException {
    unmarshall(stream, m_dbConnFactory);
}
```

This preserves the existing `m_dbConnFactory` across config reloads instead of nullifying it.

- [ ] **Step 3: Verify opennms-config compiles**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :opennms-config -am install`

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add opennms-config/src/main/java/org/opennms/netmgt/config/EventTranslatorConfigFactory.java
git commit -m "fix: preserve DataSource in EventTranslatorConfigFactory.update()

The update() method called unmarshall(stream) which delegated to
unmarshall(stream, null), nullifying m_dbConnFactory. All SQL value
specs in translation rules would fail with NPE after any config reload."
```

---

### Task 2: Create daemon-boot-eventtranslator Maven module

**Files:**
- Create: `core/daemon-boot-eventtranslator/pom.xml`
- Modify: `core/pom.xml` (add module entry)

- [ ] **Step 1: Create the POM**

Create `core/daemon-boot-eventtranslator/pom.xml`. Follow the Alarmd POM pattern (`core/daemon-boot-alarmd/pom.xml`) but simpler — no `opennms-model-jakarta` dependency, no alarm-specific deps.

Key differences from Alarmd POM:
- Artifact: `org.opennms.core.daemon-boot-eventtranslator`
- Name: `OpenNMS :: Core :: Daemon Boot :: EventTranslator`
- Dependencies: `daemon-common`, `opennms-services` (with same exclusions as Alarmd), `opennms-config`, `opennms-config-model`
- Same Spring Boot 4.0.3 BOM, JUnit 6.0.3, Jackson 2.19.4, logback 1.5.32 pins
- Same `javax.persistence-api:2.2` and `javax.xml.bind:jaxb-api:2.3.1` runtime deps
- Same surefire/compiler/spring-boot-maven-plugin configuration
- NO `opennms-alarmd`, `opennms-alarm-api`, `opennms-model-jakarta`, `opennms-dao-api` deps

Read `core/daemon-boot-alarmd/pom.xml` for the exact dependency management structure and exclusion patterns to replicate.

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-eventtranslator</module>` after `daemon-boot-alarmd` in the `<modules>` section.

- [ ] **Step 3: Create directory structure**

```bash
mkdir -p core/daemon-boot-eventtranslator/src/main/java/org/opennms/netmgt/translator/boot
mkdir -p core/daemon-boot-eventtranslator/src/main/resources
mkdir -p core/daemon-boot-eventtranslator/src/test/java/org/opennms/netmgt/translator/boot
```

- [ ] **Step 4: Verify Maven resolves**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-eventtranslator -am install`

Expected: BUILD SUCCESS (empty module)

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-eventtranslator/pom.xml core/pom.xml
git commit -m "build: add daemon-boot-eventtranslator Maven module scaffold"
```

---

### Task 3: Create Spring Boot application and configuration

**Files:**
- Create: `core/daemon-boot-eventtranslator/src/main/java/org/opennms/netmgt/translator/boot/EventTranslatorApplication.java`
- Create: `core/daemon-boot-eventtranslator/src/main/java/org/opennms/netmgt/translator/boot/EventTranslatorBootConfiguration.java`
- Create: `core/daemon-boot-eventtranslator/src/main/resources/application.yml`

- [ ] **Step 1: Create EventTranslatorApplication.java**

```java
package org.opennms.netmgt.translator.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = {
        "org.opennms.core.daemon.common",
        "org.opennms.netmgt.translator.boot"
    },
    exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
    }
)
public class EventTranslatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventTranslatorApplication.class, args);
    }
}
```

**Key difference from Alarmd:** JPA auto-config excluded because EventTranslator uses raw JDBC only. The `daemon-common` module pulls in `spring-boot-starter-data-jpa` which would fail without entity classes.

- [ ] **Step 2: Create EventTranslatorBootConfiguration.java**

```java
package org.opennms.netmgt.translator.boot;

import javax.sql.DataSource;

import org.opennms.core.daemon.common.DaemonSmartLifecycle;
import org.opennms.core.db.DataSourceFactory;
import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.netmgt.config.EventTranslatorConfigFactory;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.translator.EventTranslator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventTranslatorBootConfiguration {

    /**
     * Load translation rules from ${opennms.home}/etc/translator-configuration.xml.
     * Sets DataSourceFactory singleton so EventTranslatorConfigFactory.init() can find it,
     * then initializes the config factory which reads the XML and sets up translation specs.
     */
    @Bean
    public EventTranslatorConfigFactory eventTranslatorConfig(DataSource dataSource) throws Exception {
        // Set OPENNMS_HOME for ConfigFileConstants
        String opennmsHome = System.getProperty("opennms.home",
                System.getenv().getOrDefault("OPENNMS_HOME", "/opt/sentinel"));
        System.setProperty("opennms.home", opennmsHome);

        // Initialize via static factory (reads translator-configuration.xml)
        DataSourceFactory.setInstance(dataSource);
        EventTranslatorConfigFactory.init();
        return (EventTranslatorConfigFactory) EventTranslatorConfigFactory.getInstance();
    }

    /**
     * Wire EventTranslator with setter injection.
     * EventTranslator self-registers for events during onInit() via
     * eventManager.addEventListener(this, ueiList) — no AnnotationBasedEventListenerAdapter needed.
     */
    @Bean
    public EventTranslator eventTranslator(
            @Qualifier("eventIpcManager") EventIpcManager eventIpcManager,
            EventTranslatorConfigFactory config,
            DataSource dataSource) {
        var translator = new EventTranslator();
        translator.setEventManager(eventIpcManager);
        translator.setConfig(config);
        translator.setDataSource(dataSource);
        return translator;
    }

    @Bean
    public SmartLifecycle eventTranslatorLifecycle(EventTranslator eventTranslator) {
        return new DaemonSmartLifecycle(eventTranslator);
    }
}
```

**Key differences from Alarmd:**
- No `AnnotationBasedEventListenerAdapter` — EventTranslator self-registers via `addEventListener()`
- No `PersistenceManagedTypes` bean — no JPA entities
- No `PhysicalNamingStrategyStandardImpl` — no Hibernate
- `DataSourceFactory.setInstance()` called before `EventTranslatorConfigFactory.init()` so the static init can find the DataSource
- `ConfigFileConstants` requires `opennms.home` system property

- [ ] **Step 3: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
  autoconfigure:
    exclude:
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration

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
  home: ${OPENNMS_HOME:/opt/sentinel}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event-topic: ${KAFKA_EVENT_TOPIC:opennms-fault-events}
    consumer-group: ${KAFKA_CONSUMER_GROUP:opennms-core}
    poll-timeout-ms: ${KAFKA_POLL_TIMEOUT_MS:100}

logging:
  level:
    org.opennms: INFO
    org.opennms.netmgt.translator: DEBUG
```

Note: JPA exclusion is in both `@SpringBootApplication` and `application.yml` for belt-and-suspenders safety.

- [ ] **Step 4: Verify module compiles**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-eventtranslator -am install`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-eventtranslator/src/
git commit -m "feat: add EventTranslator Spring Boot application and configuration"
```

---

## Chunk 2: Delete Karaf Loader, Wire Docker, Test

### Task 4: Delete Karaf daemon-loader-eventtranslator and overlay

**Files:**
- Delete: `core/daemon-loader-eventtranslator/` (entire directory)
- Delete: `opennms-container/delta-v/eventtranslator-overlay/` (entire directory)
- Modify: `core/pom.xml` (remove module entry)
- Modify: `container/features/src/main/resources/features.xml` (remove feature, lines 2242-2252)

- [ ] **Step 1: Remove module from core/pom.xml**

Remove `<module>daemon-loader-eventtranslator</module>` from `core/pom.xml`.

- [ ] **Step 2: Delete daemon-loader-eventtranslator directory**

```bash
rm -rf core/daemon-loader-eventtranslator/
```

- [ ] **Step 3: Delete eventtranslator-overlay directory**

```bash
rm -rf opennms-container/delta-v/eventtranslator-overlay/
```

- [ ] **Step 4: Remove Karaf feature from features.xml**

In `container/features/src/main/resources/features.xml`, remove the `opennms-daemon-eventtranslator` feature block (lines 2242-2252):
```xml
    <!-- Karaf-only EventTranslator: loads EventTranslator daemon without Manager/Eventd -->
    <feature name="opennms-daemon-eventtranslator" ...>
        ...
    </feature>
```

Also check `container/features/pom.xml` for any dependency on `daemon-loader-eventtranslator` and remove it.

- [ ] **Step 5: Verify build**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-eventtranslator -am install`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete daemon-loader-eventtranslator — replaced by Spring Boot daemon-boot-eventtranslator"
```

---

### Task 5: Wire into Docker pipeline

**Files:**
- Modify: `opennms-container/delta-v/build.sh` (add staging entry)
- Modify: `opennms-container/delta-v/Dockerfile.daemon` (add COPY line)
- Modify: `opennms-container/delta-v/docker-compose.yml` (replace eventtranslator service)

- [ ] **Step 1: Add staging entry to build.sh**

In `build.sh`, in the `do_stage_daemon_jars()` function's JAR list, add after the `daemon-boot-alarmd` entry:

```bash
"core/daemon-boot-eventtranslator/target/org.opennms.core.daemon-boot-eventtranslator-$VERSION-boot.jar:daemon-boot-eventtranslator.jar"
```

Update the JAR count validation (currently expects at least 20 — may need to increase by 1 or stay the same if the daemon-loader-eventtranslator JAR was previously counted).

- [ ] **Step 2: Add COPY line to Dockerfile.daemon**

In `Dockerfile.daemon`, after the `daemon-boot-alarmd.jar` COPY line, add:

```dockerfile
COPY staging/daemon/daemon-boot-eventtranslator.jar /opt/daemon-boot-eventtranslator.jar
```

- [ ] **Step 3: Replace eventtranslator service in docker-compose.yml**

Replace the existing `eventtranslator` service definition (lines 281-309) with:

```yaml
  eventtranslator:
    profiles: [passive, full]
    image: opennms/daemon-deltav:${VERSION}
    container_name: delta-v-eventtranslator
    hostname: eventtranslator
    entrypoint: []
    command: ["java", "-Xms256m", "-Xmx512m", "-jar", "/opt/daemon-boot-eventtranslator.jar"]
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
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Also remove the `eventtranslator-data:` volume definition (around line 542) since the Spring Boot app doesn't need a Karaf data volume.

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/build.sh opennms-container/delta-v/Dockerfile.daemon opennms-container/delta-v/docker-compose.yml
git commit -m "feat: wire daemon-boot-eventtranslator into Docker pipeline"
```

---

### Task 6: Build, deploy, and run E2E test

- [ ] **Step 1: Full compile**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests`

Expected: BUILD SUCCESS

- [ ] **Step 2: Build Docker images**

Run: `cd opennms-container/delta-v && bash build.sh`

Expected: Images built, including `daemon-boot-eventtranslator.jar` staged

- [ ] **Step 3: Deploy and verify EventTranslator starts**

```bash
cd opennms-container/delta-v
docker compose --profile passive down -v
docker compose --profile passive up -d
```

Wait for services, then check:
```bash
docker logs delta-v-eventtranslator 2>&1 | grep "Started EventTranslatorApp"
```

Expected: `Started EventTranslatorApplication in X.XXX seconds`

If startup fails, check logs for missing beans or classpath issues (same debugging pattern as Alarmd).

- [ ] **Step 4: Run Minion E2E test**

Run: `bash test-minion-e2e.sh`

Expected: 13/13 passing. Phase 2 specifically validates EventTranslator:
```
[PASS] Translated SNMP_Link_Down event seen in Kafka (via Minion path)
```

- [ ] **Step 5: Commit any runtime fixes**

If runtime issues required code changes (similar to Alarmd's EventUtil/SessionUtils fixes), commit them:

```bash
git add -A
git commit -m "fix: resolve EventTranslator Spring Boot runtime issues"
```

- [ ] **Step 6: Push and create PR**

```bash
git push delta-v feature/spring-boot-4-alarmd-migration
gh pr create --repo pbrane/delta-v --base develop \
    --title "feat: migrate EventTranslator to Spring Boot 4" \
    --body "$(cat <<'EOF'
## Summary
- New `core/daemon-boot-eventtranslator` Spring Boot 4.0.3 module
- Deleted Karaf `daemon-loader-eventtranslator` and overlay
- Fixed EventTranslatorConfigFactory.update() DataSource nullification bug
- EventTranslator now runs as `java -jar` in Docker

## Key differences from Alarmd migration
- No JPA — JPA auto-config excluded (EventTranslator uses raw JDBC only)
- No AnnotationBasedEventListenerAdapter — self-registers via addEventListener()
- Simpler: 3 beans (config, daemon, lifecycle) vs Alarmd's 10+

## Test plan
- [x] Full `./compile.pl -DskipTests` passes
- [x] `test-minion-e2e.sh`: 13/13 passing
EOF
)"
```

**CRITICAL:** Always use `--repo pbrane/delta-v` — never PR against OpenNMS/opennms.
