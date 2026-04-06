# EventTranslator Spring Boot 4 Migration Design Spec

## Overview

Migrate the EventTranslator daemon from Karaf/OSGi to Spring Boot 4.0.3. This is the second daemon migration after Alarmd (PR #28), following the same established pattern: Spring Boot fat JAR, `java -jar` in Docker, Kafka event transport, Spring Boot Actuator healthcheck.

## Goals

- EventTranslator boots as a Spring Boot 4.0.3 application
- Receives events via Kafka, applies translation rules, sends translated events back to Kafka
- Reads `translator-configuration.xml` from filesystem (`${OPENNMS_HOME}/etc/`)
- Uses Spring-managed `DataSource` (HikariCP) for SQL value specs — no `DataSourceFactory` static singleton
- Fix `EventTranslatorConfigFactory.update()` bug that nullifies DataSource on config reload
- Delete the Karaf daemon-loader module and overlay

## Non-Goals

- Changing the translation rule engine or configuration format
- JPA/Hibernate entity integration (EventTranslator uses raw JDBC only)
- REST API endpoints

## Architecture

### Module: `core/daemon-boot-eventtranslator`

Follows the Alarmd pattern with one key difference: **no JPA**. EventTranslator uses raw JDBC only, so JPA auto-configuration must be excluded.

### Dependencies

| Dependency | Purpose |
|-----------|---------|
| `daemon-common` | DaemonDataSourceConfiguration, DaemonSmartLifecycle, KafkaEventTransportConfiguration |
| `opennms-services` | EventTranslator daemon class (with ServiceMix/Karaf exclusions) |
| `opennms-config` | EventTranslatorConfigFactory (translation rule engine) |
| `opennms-config-model` | JAXB config models (EventTranslatorConfiguration, EventTranslationSpec, Mapping) |
| `spring-boot-starter-web` | Embedded web server for Actuator health endpoint |
| `javax.persistence-api:2.2` | Runtime — legacy classes on classpath reference javax.persistence |
| `javax.xml.bind:jaxb-api:2.3.1` | Runtime — JAXB event XML unmarshalling in KafkaEventSubscriptionService |

### Bean Wiring (`EventTranslatorConfiguration.java`)

```java
@Configuration
public class EventTranslatorConfiguration {

    @Bean
    EventTranslatorConfigFactory eventTranslatorConfig(DataSource dataSource) {
        // Reads ${opennms.home}/etc/translator-configuration.xml
        // Receives Spring-managed DataSource for SQL value specs
    }

    @Bean
    EventTranslator eventTranslator(EventIpcManager eventIpcManager,
                                     EventTranslatorConfigFactory config,
                                     DataSource dataSource) {
        // Setter-injected: setEventManager(), setConfig(), setDataSource()
        // EventTranslator self-registers for events during onInit()
    }

    @Bean
    SmartLifecycle eventTranslatorLifecycle(EventTranslator eventTranslator) {
        return new DaemonSmartLifecycle(eventTranslator);
    }
}
```

**Key difference from Alarmd:** No `AnnotationBasedEventListenerAdapter` bean. EventTranslator implements `EventListener` directly and self-registers during `onInit()` via `eventManager.addEventListener(this, ueiList)`. It does NOT use `@EventHandler` annotations.

### Application Class

```java
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
public class EventTranslatorApplication { ... }
```

**JPA exclusion required:** `daemon-common` depends on `spring-boot-starter-data-jpa` (for Alarmd and other JPA-using daemons). EventTranslator doesn't use JPA entities, so Hibernate auto-configuration must be excluded to prevent startup failure from missing entity metadata.

Scans only `daemon.common` (Kafka/DataSource infrastructure) and `translator.boot` (this module's config). Does NOT scan `org.opennms.netmgt.translator` broadly.

### Configuration Files

**`application.yml`** — same structure as Alarmd minus JPA/Hibernate properties:
- `spring.datasource.*` — HikariCP PostgreSQL connection
- `opennms.home` — path to OpenNMS etc directory
- `opennms.kafka.*` — bootstrap servers, event topic, consumer group
- `server.port: 8080` — Actuator
- No `spring.jpa.*` properties (JPA excluded)

**`${OPENNMS_HOME}/etc/translator-configuration.xml`** — translation rules, mounted via Docker volume. Not embedded in JAR.

### Docker Compose Changes

Replace the existing `eventtranslator` service definition entirely:

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

### What Gets Deleted

| Path | Reason |
|------|--------|
| `core/daemon-loader-eventtranslator/` | Replaced by `daemon-boot-eventtranslator` |
| `opennms-container/delta-v/eventtranslator-overlay/` | Karaf featuresBoot.d no longer needed |
| `opennms-daemon-eventtranslator` feature in `features.xml` | Karaf feature no longer needed |
| `daemon-loader-eventtranslator` module in `core/pom.xml` | Replaced by new module |

### What Gets Added to build.sh

Staging entry for the fat JAR:
```bash
"core/daemon-boot-eventtranslator/target/org.opennms.core.daemon-boot-eventtranslator-$VERSION-boot.jar:daemon-boot-eventtranslator.jar"
```

Dockerfile.daemon COPY line:
```dockerfile
COPY staging/daemon/daemon-boot-eventtranslator.jar /opt/daemon-boot-eventtranslator.jar
```

### DataSource Strategy

EventTranslator uses `DataSource` for SQL value specs in translation rules (e.g., looking up `snmpIfDescr` from the `snmpInterface` table). In the Spring Boot app:

- Spring Boot auto-configures `HikariDataSource` from `spring.datasource.*` properties
- `EventTranslatorConfigFactory` receives the `DataSource` via its constructor
- No JPA `EntityManager` needed — pure JDBC
- `@EnableTransactionManagement` from `DaemonDataSourceConfiguration` is harmless but unused

### Bug Fix: EventTranslatorConfigFactory.update()

`EventTranslatorConfigFactory.update()` calls `unmarshall(stream)` which delegates to `unmarshall(stream, null)`, setting `m_dbConnFactory = null`. This means any config reload wipes the DataSource, causing all SQL value specs to fail with `NullPointerException`.

**Fix:** Modify `update()` to preserve the existing `m_dbConnFactory` after re-parsing the XML config. This is a one-line fix in `EventTranslatorConfigFactory`.

### Testing

- **Build verification:** Module compiles, fat JAR produced
- **E2E verification:** `test-minion-e2e.sh` Phase 2 validates EventTranslator — it checks for `Translated SNMP_Link_Down event seen in Kafka`. If this passes with the Spring Boot EventTranslator, the migration is validated.

### Risks

| Risk | Mitigation |
|------|-----------|
| `EventTranslatorConfigFactory` uses `ConfigFileConstants` which requires `opennms.home` | Set via environment variable `OPENNMS_HOME` — same as Alarmd |
| `DataSourceFactory.init()` called somewhere in init chain | Set `DataSourceFactory` with Spring-managed DataSource before EventTranslator init |
| JPA auto-config from `daemon-common` fails without entities | Exclude `HibernateJpaAutoConfiguration` and `JpaRepositoriesAutoConfiguration` in `@SpringBootApplication` |
| `EventTranslatorConfigFactory.update()` nullifies DataSource | Fix the bug — preserve `m_dbConnFactory` across config reloads |
