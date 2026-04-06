# opennms-model-jakarta Design Spec

## Overview

Create `core/opennms-model-jakarta`, an Alarmd-scoped module containing Jakarta Persistence (Hibernate 7) entity classes, JPA `AttributeConverter` implementations, and JPA DAO classes. This module replaces the legacy `opennms-model` + `opennms-dao` dependency chain for the Spring Boot 4 Alarmd microservice, eliminating the javax→jakarta bytecode transformation step entirely.

## Goals

- Alarmd boots as a Spring Boot 4.0.3 application with a real PostgreSQL DataSource, Hibernate 7, and JPA DAOs
- Entity classes use `jakarta.persistence.*` natively — no Eclipse Transformer
- Custom Hibernate 3.6 `UserType` implementations replaced with standard JPA `AttributeConverter`
- JAXB annotations stripped — Jackson-only serialization for the microservice
- Alarmd can receive events via Kafka, create/update/clear alarms, and persist them to PostgreSQL

## Non-Goals

- Migrating all ~32 entity classes from `opennms-model` — only Alarmd's transitive closure
- Maintaining backward compatibility with Karaf consumers — this module is Spring Boot-only
- REST API endpoints — separate future concern
- OpenNMS Criteria → JPA CriteriaBuilder translation (DAOs use HQL for now)

## Entity Scope

Only entities in Alarmd's transitive dependency graph:

| Entity | Table | Why Alarmd Needs It |
|--------|-------|-------------------|
| `OnmsAlarm` | `alarms` | Primary — Alarmd creates/updates/clears alarms |
| `OnmsNode` | `node` | Alarms reference the node they belong to |
| `OnmsMonitoringSystem` | `monitoringSystems` | Alarms reference the monitoring system |
| `OnmsDistPoller` | `monitoringSystems` | Single-table inheritance subclass of OnmsMonitoringSystem |
| `OnmsServiceType` | `service` | Alarms can reference a service type |
| `OnmsCategory` | `categories` | Nodes have categories (ManyToMany, needed for `@Filter` auth) |
| `OnmsIpInterface` | `ipInterface` | OnmsNode cascades to interfaces; OnmsAlarm has FK to node |
| `OnmsSnmpInterface` | `snmpInterface` | Referenced by OnmsIpInterface |
| `OnmsMonitoredService` | `ifServices` | Referenced by OnmsAlarm directly |
| `AlarmAssociation` | `alarm_situations` | OnmsAlarm.m_associatedAlarms (situation/correlated alarm support) |
| `OnmsMemo` | `memos` | OnmsAlarm.m_stickyMemo (single-table inheritance parent) |
| `OnmsReductionKeyMemo` | `memos` | OnmsAlarm.m_reductionKeyMemo (extends OnmsMemo) |

**Note:** `OnmsEvent` does not exist as a JPA entity in the codebase. OnmsAlarm stores denormalized event fields directly (eventUei, eventSource, etc.) — there is no FK to an events table.

Enums (`OnmsSeverity`, `NodeType`, `NodeLabelSource`, `PrimaryType`) and utility classes (`InetAddressUtils`) are **referenced from existing modules**, not copied.

### Package Strategy

Entity classes use the **same package** `org.opennms.netmgt.model` as the legacy `opennms-model`. This is critical: DAO interfaces in `opennms-dao-api` are parameterized with `org.opennms.netmgt.model.OnmsAlarm`, etc. Using a different package would make it impossible for `AlarmDaoJpa` to implement `AlarmDao` without forking the DAO interfaces.

The entities live in a different Maven module (`opennms-model-jakarta`) but the same Java package. At runtime, only one module is on the classpath — the legacy `opennms-model` for Karaf daemons, or `opennms-model-jakarta` for Spring Boot daemons. No split-package conflict.

## Module Structure

```
core/opennms-model-jakarta/
  pom.xml
  src/main/java/org/opennms/netmgt/model/
    OnmsAlarm.java
    OnmsNode.java
    OnmsMonitoringSystem.java
    OnmsDistPoller.java
    OnmsServiceType.java
    OnmsCategory.java
    OnmsIpInterface.java
    OnmsSnmpInterface.java
    OnmsMonitoredService.java
    AlarmAssociation.java
    OnmsMemo.java
    OnmsReductionKeyMemo.java
  src/main/java/org/opennms/netmgt/model/jakarta/
    converter/
      InetAddressConverter.java
      OnmsSeverityConverter.java
      PrimaryTypeConverter.java
      NodeTypeConverter.java
      NodeLabelSourceConverter.java
    dao/
      AlarmDaoJpa.java
      NodeDaoJpa.java
      DistPollerDaoJpa.java
      ServiceTypeDaoJpa.java
  src/test/java/org/opennms/netmgt/model/jakarta/
    converter/
      InetAddressConverterTest.java
      OnmsSeverityConverterTest.java
      PrimaryTypeConverterTest.java
      NodeTypeConverterTest.java
      NodeLabelSourceConverterTest.java
```

Entity classes are in `org.opennms.netmgt.model` (same package as legacy). Converters and DAOs are in `org.opennms.netmgt.model.jakarta.*` subpackages since they are new classes with no legacy counterpart.

## AttributeConverter Design

Five converters replace Hibernate 3.6 `UserType` implementations:

| Converter | Java Type | DB Column Type | Replaces |
|-----------|-----------|---------------|----------|
| `InetAddressConverter` | `InetAddress` ↔ `String` | VARCHAR | `InetAddressUserType` |
| `OnmsSeverityConverter` | `OnmsSeverity` ↔ `Integer` | INTEGER | `OnmsSeverityUserType` |
| `PrimaryTypeConverter` | `PrimaryType` ↔ `String` | CHAR(1) | `PrimaryTypeUserType` + `CharacterUserType` |
| `NodeTypeConverter` | `NodeType` ↔ `String` | CHAR(1) | `NodeTypeUserType` |
| `NodeLabelSourceConverter` | `NodeLabelSource` ↔ `String` | CHAR(1) | `NodeLabelSourceUserType` |

### Converter Pattern

```java
@Converter
public class OnmsSeverityConverter implements AttributeConverter<OnmsSeverity, Integer> {
    @Override
    public Integer convertToDatabaseColumn(OnmsSeverity severity) {
        return severity == null ? null : severity.getId();
    }

    @Override
    public OnmsSeverity convertToEntityAttribute(Integer id) {
        return id == null ? null : OnmsSeverity.get(id);
    }
}
```

All converters are null-safe and bidirectional. Applied to entity fields via `@Convert(converter = XxxConverter.class)`, replacing `@Type(type="org.opennms.netmgt.model.XxxUserType")`.

## Entity Migration Patterns

### Namespace Change

```java
// Before (opennms-model)
import javax.persistence.*;

// After (opennms-model-jakarta)
import jakarta.persistence.*;
```

### @Type → @Convert

```java
// Before
@Type(type="org.opennms.netmgt.model.InetAddressUserType")
@Column(name="ipAddr")
private InetAddress ipAddr;

// After
@Convert(converter = InetAddressConverter.class)
@Column(name="ipAddr")
private InetAddress ipAddr;
```

### JAXB Annotations Stripped

All `@XmlRootElement`, `@XmlElement`, `@XmlAttribute`, `@XmlTransient`, `@XmlJavaTypeAdapter` annotations removed. Jackson `@JsonIgnoreProperties` / `@JsonIgnore` retained where needed for serialization control.

### Hibernate-Specific Annotations Retained

These annotations are valid in Hibernate 7 and remain unchanged:

- `@Filter(name=..., condition="...")` — row-level security
- `@FilterDef(name=...)` — filter definitions
- `@Formula(value="(SELECT ...)")` — read-only computed properties

### Hibernate Annotations Migrated

- `@Where(clause="...")` → `@SQLRestriction("...")` — `@Where` was removed in Hibernate 7; `@SQLRestriction` is the replacement. Used in `OnmsMonitoredService`.
- `@DiscriminatorOptions(force=true)` — verify availability in Hibernate 7; if removed, omit (standard JPA discriminator handling suffices for `OnmsMonitoringSystem`/`OnmsMemo` hierarchies)

### Single-Table Inheritance

`OnmsMonitoringSystem` → `OnmsDistPoller` inheritance is standard JPA:

```java
@Entity
@Table(name = "monitoringSystems")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
public class OnmsMonitoringSystem { ... }

@Entity
@DiscriminatorValue("OpenNMS")
public class OnmsDistPoller extends OnmsMonitoringSystem { ... }
```

Works identically in Hibernate 7.

### @Temporal Handling

Retained on `java.util.Date` fields for explicitness, though Hibernate 7 infers `TIMESTAMP` automatically.

## DAO Design

### Interface Hierarchy

The DAO interfaces in `opennms-dao-api` follow this hierarchy:

```
OnmsDao<T, K>                    ← base (16 methods: get, save, delete, findAll, countAll, etc.)
  ├─ LegacyOnmsDao<T, K>        ← adds findMatching(OnmsCriteria), countMatching(OnmsCriteria)
  │    ├─ AlarmDao               ← adds 8 custom methods (findByReductionKey, getNodeAlarmSummaries, etc.)
  │    └─ NodeDao                ← adds many custom methods
  ├─ DistPollerDao               ← extends OnmsDao directly; adds whoami(), etc.
  └─ ServiceTypeDao              ← extends OnmsDao directly; adds findByName()
```

`AbstractDaoJpa` implements `OnmsDao<T, K>` (including throwing `UnsupportedOperationException` for `findMatching(Criteria)`). `AlarmDaoJpa` and `NodeDaoJpa` must additionally implement `LegacyOnmsDao`'s `findMatching(OnmsCriteria)` and `countMatching(OnmsCriteria)` methods (throw `UnsupportedOperationException`), since `AbstractDaoJpa` does not cover those. `DistPollerDaoJpa` and `ServiceTypeDaoJpa` extend `OnmsDao` directly and do not need `LegacyOnmsDao` methods.

### DAO Table

| DAO Class | Entity | Interface | Key Custom Methods |
|-----------|--------|-----------|-------------------|
| `AlarmDaoJpa` | `OnmsAlarm` | `AlarmDao` | `findByReductionKey(String)`, `getNodeAlarmSummaries()`, `getSituationSummaries()`, `getNodeAlarmSummariesIncludeAcknowledgedOnes(List)`, `getHeatMapItemsForEntity(...)`, `getAlarmsForEventParameters(Map)`, `getNumSituations()`, `getNumAlarmsLastHours(int)` |
| `NodeDaoJpa` | `OnmsNode` | `NodeDao` | `get(Integer)`, `findByLabel(String)` — implement only methods Alarmd calls; others throw `UnsupportedOperationException` |
| `DistPollerDaoJpa` | `OnmsDistPoller` | `DistPollerDao` | `whoami()` — returns the local monitoring system |
| `ServiceTypeDaoJpa` | `OnmsServiceType` | `ServiceTypeDao` | `findByName(String)` |

**Note:** No `EventDao` or `EventDaoJpa` — `OnmsEvent` does not exist as a JPA entity. Alarmd receives events via Kafka and stores denormalized event fields on `OnmsAlarm`.

DAOs use HQL via `AbstractDaoJpa.find()` and `findUnique()` helpers. `AlarmDaoJpa` and `NodeDaoJpa` implement `LegacyOnmsDao`'s `findMatching(OnmsCriteria)` and `countMatching(OnmsCriteria)` by throwing `UnsupportedOperationException` — Alarmd does not use the legacy Criteria API. `DistPollerDaoJpa` and `ServiceTypeDaoJpa` extend `OnmsDao` directly and do not have these methods.

DAOs are annotated with `@Repository` for Spring auto-detection and `@Transactional` where needed.

## Maven Dependencies

### opennms-model-jakarta pom.xml

```xml
<dependencies>
    <!-- Jakarta Persistence API (from Spring Boot 4 BOM) -->
    <dependency>
        <groupId>jakarta.persistence</groupId>
        <artifactId>jakarta.persistence-api</artifactId>
    </dependency>

    <!-- Hibernate 7 core (from Spring Boot 4 BOM) -->
    <dependency>
        <groupId>org.hibernate.orm</groupId>
        <artifactId>hibernate-core</artifactId>
    </dependency>

    <!-- DAO interfaces -->
    <dependency>
        <groupId>org.opennms</groupId>
        <artifactId>opennms-dao-api</artifactId>
        <version>${project.version}</version>
        <!-- Exclude all javax/Hibernate 3.6 transitive deps -->
    </dependency>

    <!-- Shared enums and utilities (OnmsSeverity, InetAddressUtils, etc.) -->
    <dependency>
        <groupId>org.opennms</groupId>
        <artifactId>opennms-model</artifactId>
        <version>${project.version}</version>
        <!-- Exclude javax.persistence, Hibernate 3.6, JAXB -->
    </dependency>

    <!-- AbstractDaoJpa base class -->
    <dependency>
        <groupId>org.opennms.core</groupId>
        <artifactId>org.opennms.core.daemon-common</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- Jackson for @JsonIgnoreProperties -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-annotations</artifactId>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

The module inherits Spring Boot 4.0.3 BOM from `daemon-common`'s dependency management, which pins Hibernate ORM, Jakarta Persistence, and Spring versions.

### daemon-boot-alarmd pom.xml Changes

- **Remove:** `opennms-model` direct dependency
- **Remove:** `javax.persistence:javax.persistence-api:2.2`
- **Add:** `opennms-model-jakarta`
- **Remove:** Eclipse Transformer profile (no longer needed for Alarmd)

## Integration with daemon-boot-alarmd

### EntityScan Update

`DaemonDataSourceConfiguration` in `daemon-common` currently hardcodes `@EntityScan(basePackages = "org.opennms.netmgt.model")`. This annotation must be **removed from daemon-common** and placed on each boot application's configuration instead. For Alarmd:

```java
@Configuration
@EntityScan(basePackages = "org.opennms.netmgt.model")
public class AlarmdConfiguration { ... }
```

The package stays `org.opennms.netmgt.model` because the Jakarta entities use the same package as the legacy entities (different module, same package).

### AlarmdConfiguration Bean Wiring

The JPA DAOs are auto-discovered via `@Repository` + component scanning of `org.opennms.netmgt.model.jakarta.dao`. They implement the same DAO interfaces (`AlarmDao`, `NodeDao`, `DistPollerDao`, `ServiceTypeDao`) that `AlarmPersisterImpl` and other Alarmd classes inject — no changes to Alarmd source code needed.

### AlarmPersisterImpl Dependencies

`AlarmPersisterImpl` uses `@Autowired` field injection for 7 dependencies:

| Dependency | Provided By |
|-----------|-------------|
| `AlarmDao` | `AlarmDaoJpa` (from opennms-model-jakarta) |
| `NodeDao` | `NodeDaoJpa` (from opennms-model-jakarta) |
| `DistPollerDao` | `DistPollerDaoJpa` (from opennms-model-jakarta) |
| `ServiceTypeDao` | `ServiceTypeDaoJpa` (from opennms-model-jakarta) |
| `EventUtil` | Bean defined in `AlarmdConfiguration` |
| `TransactionOperations` | Spring Boot auto-config (from `PlatformTransactionManager`) |
| `AlarmEntityNotifier` | `AlarmEntityNotifierImpl` bean in `AlarmdConfiguration` |

`EventUtil` is a stateful service with its own dependencies — it must be wired as a bean in `AlarmdConfiguration`. Field injection in `AlarmPersisterImpl` is left as-is for now (refactoring to constructor injection is out of scope for this migration step).

### Integration Test

`AlarmdApplicationIT` will be enabled with:
- Testcontainers PostgreSQL with schema loaded via a simplified DDL script (not Liquibase — the full Liquibase changelog has ~800 changesets with complex migration history; a flat DDL of the current schema is simpler and faster for tests)
- Testcontainers Kafka
- Verify: Spring context loads → Alarmd starts → send test event via Kafka → alarm created in PostgreSQL → alarm queryable via `AlarmDaoJpa`

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `@Formula` SQL subqueries behave differently in Hibernate 7 | Alarm `isSituation`/`isInSituation` fields break | Integration test specifically validates formula-derived properties |
| `@Filter` for auth not activated in microservice context | Security gap | Alarmd is internal-only (no external REST yet); filter activation deferred to REST API migration |
| Enum/utility class imports from `opennms-model` pull in javax transitives | Classpath conflicts | Careful `<exclusions>` in POM; only enum classes and `InetAddressUtils` needed |
| `opennms-dao-api` DAO interfaces reference `org.opennms.core.criteria.Criteria` | DAOs must implement or throw | Already handled: `AbstractDaoJpa` throws `UnsupportedOperationException`; Alarmd doesn't use Criteria API |
| HQL queries from legacy DAOs use positional parameters differently | Query failures at runtime | Integration test with real PostgreSQL validates all DAO queries |
| `opennms-model` on classpath alongside `opennms-model-jakarta` causes split-package | Class loading ambiguity | `daemon-boot-alarmd` depends on `opennms-model-jakarta` only; `opennms-model` excluded transitively via `<exclusions>` on any dependency that pulls it in |
| `EventUtil` has complex dependency tree | Context fails to load | Wire `EventUtil` explicitly in `AlarmdConfiguration`; stub unavailable dependencies if needed |
