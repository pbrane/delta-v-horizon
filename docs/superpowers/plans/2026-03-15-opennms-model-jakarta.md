# opennms-model-jakarta Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `core/opennms-model-jakarta` with Hibernate 7 / Jakarta Persistence entities, JPA AttributeConverters, and DAO implementations so Alarmd fully boots on Spring Boot 4 and processes events into alarms.

**Architecture:** Fork the 12 entity classes Alarmd needs from `opennms-model` into a new module, rewriting `javax.persistence` → `jakarta.persistence`, replacing Hibernate 3.6 `@Type` UserTypes with standard JPA `@Converter` AttributeConverters, stripping JAXB annotations, and providing JPA DAO implementations that extend `AbstractDaoJpa`. Wire into `daemon-boot-alarmd`, remove `@EntityScan` from `daemon-common`, and enable the integration test.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Hibernate 7 (from Spring Boot BOM), Jakarta Persistence 3.2, PostgreSQL, JUnit 5, Testcontainers

**Spec:** `docs/superpowers/specs/2026-03-15-opennms-model-jakarta-design.md`

**Worktree:** `/Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd` (branch `feature/spring-boot-4-alarmd-migration`)

---

## Chunk 1: Module Scaffold and AttributeConverters

### Task 1: Create opennms-model-jakarta Maven module

**Files:**
- Create: `core/opennms-model-jakarta/pom.xml`
- Modify: `core/pom.xml:37` (add module entry after `daemon-boot-alarmd`)

- [ ] **Step 1: Create the POM file**

Create `core/opennms-model-jakarta/pom.xml`:

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
    <artifactId>org.opennms.core.model-jakarta</artifactId>
    <packaging>jar</packaging>
    <name>OpenNMS :: Core :: Model Jakarta</name>
    <description>
        Jakarta Persistence entity classes for Spring Boot 4 daemons.
        Same package (org.opennms.netmgt.model) as legacy opennms-model,
        different module. Only one should be on the classpath at runtime.
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
        <!-- Jakarta Persistence API (from Spring Boot 4 BOM) -->
        <dependency>
            <groupId>jakarta.persistence</groupId>
            <artifactId>jakarta.persistence-api</artifactId>
        </dependency>

        <!-- Hibernate 7 core — for @Filter, @Formula, @SQLRestriction annotations -->
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-core</artifactId>
        </dependency>

        <!-- AbstractDaoJpa base class -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- DAO interfaces (AlarmDao, NodeDao, DistPollerDao, ServiceTypeDao) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
                <exclusion><groupId>org.hibernate.javax.persistence</groupId><artifactId>hibernate-jpa-2.0-api</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- Shared enums and utilities (OnmsSeverity, InetAddressUtils, NodeType, etc.) -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></exclusion>
                <exclusion><groupId>org.hibernate.javax.persistence</groupId><artifactId>hibernate-jpa-2.0-api</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-beans</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-context</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-core</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-tx</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-orm</artifactId></exclusion>
                <exclusion><groupId>org.apache.servicemix.bundles</groupId><artifactId>org.apache.servicemix.bundles.spring-jdbc</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion>
                <exclusion><groupId>org.slf4j</groupId><artifactId>log4j-over-slf4j</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-log4j2</artifactId></exclusion>
                <exclusion><groupId>org.ops4j.pax.logging</groupId><artifactId>pax-logging-api</artifactId></exclusion>
                <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
            </exclusions>
        </dependency>

        <!-- Jackson for @JsonIgnoreProperties -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>org.junit.vintage</groupId>
                    <artifactId>junit-vintage-engine</artifactId>
                </exclusion>
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

In `core/pom.xml`, add `<module>opennms-model-jakarta</module>` after line 37 (after `daemon-boot-alarmd`):

```xml
    <module>daemon-boot-alarmd</module>
    <module>opennms-model-jakarta</module>
```

- [ ] **Step 3: Create directory structure**

```bash
mkdir -p core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model
mkdir -p core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter
mkdir -p core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao
mkdir -p core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter
```

- [ ] **Step 4: Verify Maven resolves**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS (empty module compiles)

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/pom.xml core/pom.xml
git commit -m "build: add opennms-model-jakarta Maven module scaffold"
```

---

### Task 2: Implement AttributeConverters with tests

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/InetAddressConverter.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/OnmsSeverityConverter.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/PrimaryTypeConverter.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/NodeTypeConverter.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/NodeLabelSourceConverter.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter/InetAddressConverterTest.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter/OnmsSeverityConverterTest.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter/PrimaryTypeConverterTest.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter/NodeTypeConverterTest.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/jakarta/converter/NodeLabelSourceConverterTest.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/InetAddressUserType.java` (understand legacy conversion logic)
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsSeverityUserType.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/PrimaryTypeUserType.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/NodeTypeUserType.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/NodeLabelSourceUserType.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsSeverity.java` (enum with getId()/get(int))
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsNode.java` (NodeType and NodeLabelSource inner enums)
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/PrimaryType.java` (or look for it on OnmsIpInterface)
- Reference: `opennms-core/src/main/java/org/opennms/core/utils/InetAddressUtils.java` (addr() and str() methods)

- [ ] **Step 1: Write converter tests**

Write all 5 test classes. Each test covers: round-trip conversion, null handling, edge cases. Example for `InetAddressConverterTest`:

```java
package org.opennms.netmgt.model.jakarta.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class InetAddressConverterTest {

    private final InetAddressConverter converter = new InetAddressConverter();

    @Test
    void convertToDatabaseColumn_ipv4() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.1.1");
        assertThat(converter.convertToDatabaseColumn(addr)).isEqualTo("192.168.1.1");
    }

    @Test
    void convertToDatabaseColumn_null() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_ipv4() {
        InetAddress result = converter.convertToEntityAttribute("192.168.1.1");
        assertThat(result.getHostAddress()).isEqualTo("192.168.1.1");
    }

    @Test
    void convertToEntityAttribute_null() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void roundTrip() throws Exception {
        InetAddress original = InetAddress.getByName("::1");
        String db = converter.convertToDatabaseColumn(original);
        InetAddress result = converter.convertToEntityAttribute(db);
        assertThat(result).isEqualTo(original);
    }
}
```

Follow same pattern for:
- `OnmsSeverityConverterTest` — test each severity level (INDETERMINATE=1 through CRITICAL=7), null, round-trip
- `PrimaryTypeConverterTest` — test PRIMARY('P'), SECONDARY('S'), NOT_ELIGIBLE('N'), null
- `NodeTypeConverterTest` — test ACTIVE('A'), DELETED('D'), null
- `NodeLabelSourceConverterTest` — test USER('U'), NETBIOS('N'), HOSTNAME('H'), SYSNAME('S'), ADDRESS('A'), null

**Important:** Read the legacy `UserType` implementations first to understand exact conversion semantics. The legacy `InetAddressUserType` uses `InetAddressUtils.addr()` for String→InetAddress and `InetAddressUtils.str()` for InetAddress→String. Mirror that exact behavior.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl --projects :org.opennms.core.model-jakarta -am verify`

Expected: Compilation errors (converter classes don't exist yet)

- [ ] **Step 3: Implement all 5 converters**

Each converter implements `jakarta.persistence.AttributeConverter<JavaType, DbType>`. Pattern:

```java
package org.opennms.netmgt.model.jakarta.converter;

import java.net.InetAddress;

import org.opennms.core.utils.InetAddressUtils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class InetAddressConverter implements AttributeConverter<InetAddress, String> {
    @Override
    public String convertToDatabaseColumn(InetAddress addr) {
        return addr == null ? null : InetAddressUtils.str(addr);
    }

    @Override
    public InetAddress convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : InetAddressUtils.addr(dbValue);
    }
}
```

For enum converters (OnmsSeverity, PrimaryType, NodeType, NodeLabelSource):
- Read the legacy UserType `nullSafeGet`/`nullSafeSet` to match exact DB↔Java mapping
- OnmsSeverity: `Integer` ↔ `OnmsSeverity` via `severity.getId()` / `OnmsSeverity.get(id)`
- PrimaryType: `String` (single char) ↔ `PrimaryType` via `PrimaryType.get(char)` / `primaryType.getCharCode()` — check the actual method names on the PrimaryType enum
- NodeType: `String` (single char) ↔ `OnmsNode.NodeType` — check the actual enum in OnmsNode
- NodeLabelSource: `String` (single char) ↔ `OnmsNode.NodeLabelSource` — check the actual enum in OnmsNode

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl --projects :org.opennms.core.model-jakarta -am verify`

Expected: All converter tests PASS

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/
git commit -m "feat: add JPA AttributeConverters for InetAddress, OnmsSeverity, PrimaryType, NodeType, NodeLabelSource"
```

---

## Chunk 2: Simple Entity Classes

Migrate the simpler entities first. These have no custom UserType annotations and few relationships.

### Task 3: Migrate OnmsServiceType, OnmsCategory, OnmsMemo, OnmsReductionKeyMemo, AlarmAssociation

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsServiceType.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsCategory.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMemo.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsReductionKeyMemo.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/AlarmAssociation.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsServiceType.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsCategory.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsMemo.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsReductionKeyMemo.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/AlarmAssociation.java`

- [ ] **Step 1: Fork OnmsServiceType**

Copy from `opennms-model/.../OnmsServiceType.java` to `core/opennms-model-jakarta/.../OnmsServiceType.java`. Apply these transformations:
1. `javax.persistence.*` → `jakarta.persistence.*`
2. Remove `javax.xml.bind.*` imports and all `@XmlRootElement`, `@XmlAttribute` annotations
3. Remove `org.codehaus.jackson.annotate.JsonIgnoreProperties` → replace with `com.fasterxml.jackson.annotation.JsonIgnoreProperties` (Jackson 2.x)
4. Keep `@Entity`, `@Table`, `@Id`, `@Column`, `@SequenceGenerator`, `@GeneratedValue`, `@JsonIgnoreProperties`
5. Retain `serialVersionUID`, constructors, equals/hashCode, toString

- [ ] **Step 2: Fork OnmsCategory**

Same transformation pattern. Additional notes:
- `@Filter(name=FilterManager.AUTH_FILTER_NAME, condition="...")` — keep as-is (Hibernate 7 supports `@Filter`)
- `@ElementCollection` + `@JoinTable` for `authorizedGroups` — standard JPA, just change namespace
- `org.hibernate.annotations.Filter` import stays the same package in Hibernate 7

- [ ] **Step 3: Fork OnmsMemo**

Same pattern. Notes:
- `@Inheritance(strategy = InheritanceType.SINGLE_TABLE)` — standard JPA
- `@DiscriminatorColumn` / `@DiscriminatorValue` — standard JPA
- `@PrePersist` / `@PreUpdate` lifecycle callbacks — standard JPA
- Field-level annotations (this entity uses field access, not property access like others)

- [ ] **Step 4: Fork OnmsReductionKeyMemo**

Read the legacy source first. It extends `OnmsMemo` with `@DiscriminatorValue("ReductionKeyMemo")` and adds a `reductionKey` field. Same namespace change.

- [ ] **Step 5: Fork AlarmAssociation**

Same pattern. Notes:
- `@ManyToOne` / `@OneToOne` / `@JoinColumn` to `OnmsAlarm` — standard JPA
- This entity references `OnmsAlarm` which we haven't migrated yet. That's OK — same package, the class will exist when we add it in a later task.
- `@Temporal(TemporalType.TIMESTAMP)` on `mappedTime` — keep as-is

- [ ] **Step 6: Verify module compiles**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS (entities may have unresolved references to OnmsAlarm — that's OK if using forward references within the same package. If compilation fails due to OnmsAlarm missing, add a stub or reorder to do OnmsAlarm first.)

**If AlarmAssociation fails to compile** because OnmsAlarm doesn't exist yet: defer AlarmAssociation to Task 5 (after OnmsAlarm is migrated).

- [ ] **Step 7: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/
git commit -m "feat: add Jakarta entity classes — OnmsServiceType, OnmsCategory, OnmsMemo, OnmsReductionKeyMemo, AlarmAssociation"
```

---

## Chunk 3: Complex Entity Classes

### Task 4: Migrate OnmsMonitoringSystem, OnmsDistPoller, OnmsSnmpInterface, OnmsIpInterface, OnmsMonitoredService

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMonitoringSystem.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsDistPoller.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsSnmpInterface.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsIpInterface.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMonitoredService.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsMonitoringSystem.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsDistPoller.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsSnmpInterface.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsIpInterface.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsMonitoredService.java`

- [ ] **Step 1: Fork OnmsMonitoringSystem**

Key transformations beyond namespace change:
- `@Inheritance(strategy = InheritanceType.SINGLE_TABLE)` + `@DiscriminatorColumn` — standard JPA
- `@DiscriminatorOptions(force=true)` — verify in Hibernate 7 API. If the annotation doesn't exist, remove it (standard JPA discriminator handling is sufficient)
- `@Type(type="org.opennms.netmgt.model.InetAddressUserType")` → `@Convert(converter = InetAddressConverter.class)` — import from `org.opennms.netmgt.model.jakarta.converter`
- `@ElementCollection` + `@JoinTable` for properties map — standard JPA
- Remove all JAXB annotations

- [ ] **Step 2: Fork OnmsDistPoller**

Simple — extends `OnmsMonitoringSystem` with `@DiscriminatorValue("OpenNMS")`. Namespace change + JAXB strip only.

- [ ] **Step 3: Fork OnmsSnmpInterface**

Read the legacy source carefully. Key transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- `@ManyToOne` to `OnmsNode` — standard JPA
- `@OneToMany` to `OnmsIpInterface` — standard JPA
- Remove JAXB
- Check for any `@Type` annotations — if InetAddress fields exist, use `@Convert(converter = InetAddressConverter.class)`

- [ ] **Step 4: Fork OnmsIpInterface**

Key transformations:
- `@Type(type="org.opennms.netmgt.model.InetAddressUserType")` on `ipAddr` field → `@Convert(converter = InetAddressConverter.class)`
- `@Type(type="org.opennms.netmgt.model.PrimaryTypeUserType")` or `@Type(type="org.opennms.netmgt.model.CharacterUserType")` on `snmpPrimary` → `@Convert(converter = PrimaryTypeConverter.class)`
- `@ManyToOne` to `OnmsNode`, `OnmsSnmpInterface` — standard JPA
- `@OneToMany` to `OnmsMonitoredService` — standard JPA
- `@Filter` — keep
- Remove JAXB

- [ ] **Step 5: Fork OnmsMonitoredService**

Key transformations:
- `@Where(clause="ifRegainedService is null")` → `@SQLRestriction("ifRegainedService is null")` — import `org.hibernate.annotations.SQLRestriction`
- `@ManyToOne` to `OnmsIpInterface`, `OnmsServiceType` — standard JPA
- Remove JAXB

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/
git commit -m "feat: add Jakarta entity classes — OnmsMonitoringSystem, OnmsDistPoller, OnmsSnmpInterface, OnmsIpInterface, OnmsMonitoredService"
```

---

### Task 5: Migrate OnmsNode and OnmsAlarm

These are the two most complex entities. OnmsAlarm is the primary entity for Alarmd.

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAlarm.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsNode.java`
- Reference: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsAlarm.java`

- [ ] **Step 1: Fork OnmsNode**

This is a large entity (~800+ lines). Key transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- `@Type(type="org.opennms.netmgt.model.InetAddressUserType")` → `@Convert(converter = InetAddressConverter.class)`
- `@Type(type="org.opennms.netmgt.model.NodeTypeUserType")` → `@Convert(converter = NodeTypeConverter.class)`
- `@Type(type="org.opennms.netmgt.model.NodeLabelSourceUserType")` → `@Convert(converter = NodeLabelSourceConverter.class)`
- `@SecondaryTable(name="pathOutage")` — standard JPA, namespace change only
- `@ManyToMany` to `OnmsCategory` with `@JoinTable` — standard JPA
- `@OneToMany` to `OnmsIpInterface`, `OnmsSnmpInterface` — standard JPA
- `@ManyToOne` to `OnmsMonitoringSystem` — standard JPA
- `@Filter` — keep
- `@Embedded` / `@AttributeOverrides` — standard JPA, namespace change
- Remove all JAXB, update Jackson import from `org.codehaus.jackson` → `com.fasterxml.jackson`

**Critical:** The `NodeType` and `NodeLabelSource` enums may be inner enums of `OnmsNode` or standalone classes. Read the source to determine. The converters must reference the correct type.

- [ ] **Step 2: Fork OnmsAlarm**

This is the most complex entity (~1300 lines). Key transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- `@Type(type="org.opennms.netmgt.model.InetAddressUserType")` → `@Convert(converter = InetAddressConverter.class)`
- `@Type(type="org.opennms.netmgt.model.OnmsSeverityUserType")` → `@Convert(converter = OnmsSeverityConverter.class)`
- `@ManyToOne` to `OnmsNode`, `OnmsMonitoringSystem`, `OnmsServiceType` — standard JPA
- `@OneToMany` to `AlarmAssociation` with `cascade = CascadeType.ALL, orphanRemoval = true` — standard JPA
- `@ElementCollection` for `relatedSituations` — standard JPA
- `@Formula(value = "(SELECT COUNT(*)>0 FROM ...)")` — keep (Hibernate 7 supports this)
- `@Filter(name=FilterManager.AUTH_FILTER_NAME, ...)` — keep
- `@ManyToOne` to `OnmsMemo` (m_stickyMemo) and `OnmsReductionKeyMemo` (m_reductionKeyMemo) — standard JPA
- Remove all JAXB annotations and `@XmlJavaTypeAdapter`
- Update Jackson imports from `org.codehaus.jackson` → `com.fasterxml.jackson`

**Note about denormalized event fields:** OnmsAlarm stores event data as flat columns (eventUei, eventSource, etc.) — there is no FK to an events table and no `OnmsEvent` entity to reference.

- [ ] **Step 3: If AlarmAssociation was deferred from Task 3, add it now**

`AlarmAssociation` references `OnmsAlarm` bidirectionally. Now that `OnmsAlarm` exists, ensure `AlarmAssociation.java` compiles correctly.

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS with all 12 entity classes

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/
git commit -m "feat: add Jakarta entity classes — OnmsNode and OnmsAlarm (most complex entities)"
```

---

## Chunk 4: JPA DAO Implementations

### Task 6: Implement AlarmDaoJpa

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/AlarmDaoJpa.java`
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/AlarmDao.java` (interface to implement)
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/LegacyOnmsDao.java` (parent interface)
- Reference: `opennms-dao/src/main/java/org/opennms/netmgt/dao/hibernate/AlarmDaoHibernate.java` (legacy implementation for HQL reference)
- Reference: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/AbstractDaoJpa.java` (base class)

- [ ] **Step 1: Create AlarmDaoJpa**

```java
package org.opennms.netmgt.model.jakarta.dao;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.opennms.core.daemon.common.AbstractDaoJpa;
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.model.alarm.AlarmSummary;
import org.opennms.netmgt.model.alarm.SituationSummary;
import org.opennms.netmgt.model.HeatMapElement;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsCriteria;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AlarmDaoJpa extends AbstractDaoJpa<OnmsAlarm, Integer> implements AlarmDao {

    public AlarmDaoJpa() {
        super(OnmsAlarm.class);
    }

    // --- LegacyOnmsDao methods (not used by Alarmd) ---

    @Override
    public List<OnmsAlarm> findMatching(OnmsCriteria criteria) {
        throw new UnsupportedOperationException("OnmsCriteria not supported in JPA DAOs");
    }

    @Override
    public int countMatching(OnmsCriteria criteria) {
        throw new UnsupportedOperationException("OnmsCriteria not supported in JPA DAOs");
    }

    // --- AlarmDao custom methods ---

    @Override
    public OnmsAlarm findByReductionKey(String reductionKey) {
        return findUnique("SELECT a FROM OnmsAlarm a WHERE a.reductionKey = ?1", reductionKey);
    }

    @Override
    public List<AlarmSummary> getNodeAlarmSummaries() {
        // Implement by reading legacy AlarmDaoHibernate.getNodeAlarmSummaries() HQL
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<SituationSummary> getSituationSummaries() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<AlarmSummary> getNodeAlarmSummariesIncludeAcknowledgedOnes(List<Integer> nodeIds) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<HeatMapElement> getHeatMapItemsForEntity(String entityNameColumn,
            String entityIdColumn, boolean processAcknowledgedAlarms,
            String restrictionColumn, String restrictionValue, String... groupByColumns) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<OnmsAlarm> getAlarmsForEventParameters(Map<String, String> eventParameters) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public long getNumSituations() {
        return queryInt("SELECT COUNT(a) FROM OnmsAlarm a WHERE a.reductionKey LIKE 'situation%'");
    }

    @Override
    public long getNumAlarmsLastHours(int hours) {
        // Use HQL with current_timestamp - hours
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
```

**Important:** Read `opennms-dao/src/main/java/org/opennms/netmgt/dao/hibernate/AlarmDaoHibernate.java` to get the exact HQL for each method. The key method Alarmd actually calls is `findByReductionKey`. The summary/heatmap methods are used by REST APIs (future scope). Implement `findByReductionKey` fully; the rest can throw `UnsupportedOperationException` for now.

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/AlarmDaoJpa.java
git commit -m "feat: add AlarmDaoJpa — JPA implementation of AlarmDao"
```

---

### Task 7: Implement NodeDaoJpa, DistPollerDaoJpa, ServiceTypeDaoJpa

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/NodeDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/DistPollerDaoJpa.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/ServiceTypeDaoJpa.java`
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/NodeDao.java`
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/DistPollerDao.java`
- Reference: `opennms-dao-api/src/main/java/org/opennms/netmgt/dao/api/ServiceTypeDao.java`
- Reference: `opennms-dao/src/main/java/org/opennms/netmgt/dao/hibernate/NodeDaoHibernate.java`
- Reference: `opennms-dao/src/main/java/org/opennms/netmgt/dao/hibernate/DistPollerDaoHibernate.java`
- Reference: `opennms-dao/src/main/java/org/opennms/netmgt/dao/hibernate/ServiceTypeDaoHibernate.java`

- [ ] **Step 1: Create NodeDaoJpa**

`NodeDao` extends `LegacyOnmsDao` and has many custom methods. Implement only what `AlarmPersisterImpl` calls:
- `get(Integer id)` — inherited from `AbstractDaoJpa`

All other `NodeDao` methods: throw `UnsupportedOperationException`. Also implement `LegacyOnmsDao` methods:
- `findMatching(OnmsCriteria)` → throw
- `countMatching(OnmsCriteria)` → throw

**Pattern:**
```java
@Repository
@Transactional
public class NodeDaoJpa extends AbstractDaoJpa<OnmsNode, Integer> implements NodeDao {
    public NodeDaoJpa() { super(OnmsNode.class); }

    // LegacyOnmsDao
    @Override
    public List<OnmsNode> findMatching(OnmsCriteria criteria) {
        throw new UnsupportedOperationException("OnmsCriteria not supported");
    }
    @Override
    public int countMatching(OnmsCriteria criteria) {
        throw new UnsupportedOperationException("OnmsCriteria not supported");
    }

    // NodeDao custom methods — implement only what Alarmd uses
    // All others throw UnsupportedOperationException
    // ...
}
```

- [ ] **Step 2: Create DistPollerDaoJpa**

`DistPollerDao` extends `OnmsDao<OnmsDistPoller, String>` directly (no LegacyOnmsDao).

```java
@Repository
@Transactional
public class DistPollerDaoJpa extends AbstractDaoJpa<OnmsDistPoller, String> implements DistPollerDao {
    public DistPollerDaoJpa() { super(OnmsDistPoller.class); }

    @Override
    public OnmsDistPoller whoami() {
        // Return the local system — find by ID "00000000-0000-0000-0000-000000000000"
        // or query by type = "OpenNMS". Read legacy DistPollerDaoHibernate for exact logic.
        return findUnique("SELECT d FROM OnmsDistPoller d WHERE d.type = 'OpenNMS'");
    }
}
```

- [ ] **Step 3: Create ServiceTypeDaoJpa**

`ServiceTypeDao` extends `OnmsDao<OnmsServiceType, Integer>` directly.

```java
@Repository
@Transactional
public class ServiceTypeDaoJpa extends AbstractDaoJpa<OnmsServiceType, Integer> implements ServiceTypeDao {
    public ServiceTypeDaoJpa() { super(OnmsServiceType.class); }

    @Override
    public OnmsServiceType findByName(String name) {
        return findUnique("SELECT s FROM OnmsServiceType s WHERE s.name = ?1", name);
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta -am install`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/dao/
git commit -m "feat: add NodeDaoJpa, DistPollerDaoJpa, ServiceTypeDaoJpa"
```

---

## Chunk 5: Wire Into daemon-boot-alarmd

### Task 8: Update daemon-common — remove hardcoded @EntityScan

**Files:**
- Modify: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonDataSourceConfiguration.java:9`

- [ ] **Step 1: Remove @EntityScan from DaemonDataSourceConfiguration**

In `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonDataSourceConfiguration.java`:

Remove line 3 (`import org.springframework.boot.persistence.autoconfigure.EntityScan;`) and line 9 (`@EntityScan(basePackages = "org.opennms.netmgt.model")`).

The file becomes:
```java
package org.opennms.core.daemon.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
public class DaemonDataSourceConfiguration {
    // Spring Boot auto-configuration handles:
    // - HikariCP DataSource from spring.datasource.* properties
    // - Hibernate SessionFactory from spring.jpa.* properties
    // - JpaTransactionManager
    //
    // @EntityScan is placed on each boot application's configuration
    // to scan the correct entity package for that daemon.
    // Schema management is "none" (Liquibase manages via db-init container).
}
```

- [ ] **Step 2: Verify daemon-common still compiles**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonDataSourceConfiguration.java
git commit -m "refactor: remove hardcoded @EntityScan from DaemonDataSourceConfiguration

Each boot application now controls its own @EntityScan via its
configuration class. This allows different daemons to scan different
entity packages."
```

---

### Task 9: Update daemon-boot-alarmd — switch to opennms-model-jakarta

**Files:**
- Modify: `core/daemon-boot-alarmd/pom.xml` (swap opennms-model → opennms-model-jakarta, remove javax.persistence-api)
- Modify: `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdApplication.java` (add scan package)
- Modify: `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdConfiguration.java` (add @EntityScan)

- [ ] **Step 1: Update daemon-boot-alarmd pom.xml**

In `core/daemon-boot-alarmd/pom.xml`:

1. **Add** dependency on `opennms-model-jakarta`:
```xml
<dependency>
    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.model-jakarta</artifactId>
    <version>${project.version}</version>
</dependency>
```

2. **Remove** the direct `opennms-model` dependency block (lines 187-206) — it's now pulled transitively through `opennms-model-jakarta`

3. **Remove** the `javax.persistence-api:2.2` dependency block (lines 208-215) — no longer needed

4. **Remove** the `opennms-dao-api` direct dependency (lines 182-186) — it's now pulled transitively through `opennms-model-jakarta`

- [ ] **Step 2: Add @EntityScan to AlarmdConfiguration**

In `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdConfiguration.java`:

Add `@EntityScan` annotation:
```java
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@Configuration
@EntityScan(basePackages = "org.opennms.netmgt.model")
public class AlarmdConfiguration {
```

- [ ] **Step 3: Add DAO package to component scan**

In `core/daemon-boot-alarmd/src/main/java/org/opennms/netmgt/alarmd/boot/AlarmdApplication.java`:

Add the DAO package to `scanBasePackages`:
```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.alarmd",
    "org.opennms.netmgt.model.jakarta.dao"
})
```

- [ ] **Step 4: Verify daemon-boot-alarmd compiles**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-alarmd -am install`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-alarmd/pom.xml core/daemon-boot-alarmd/src/
git commit -m "feat: wire opennms-model-jakarta into daemon-boot-alarmd

Replace opennms-model + javax.persistence with opennms-model-jakarta.
Add @EntityScan to AlarmdConfiguration and DAO package to component scan.
Alarmd now uses native Jakarta Persistence entities and JPA DAOs."
```

---

## Chunk 6: Integration Test

### Task 10: Create DDL script for test database

**Files:**
- Create: `core/opennms-model-jakarta/src/test/resources/schema.sql`

- [ ] **Step 1: Extract current schema DDL**

Create a simplified DDL script containing only the tables Alarmd's entities map to. Extract from the existing Liquibase-managed schema or write by hand based on entity annotations:

Tables needed:
- `monitoringSystems` (OnmsMonitoringSystem / OnmsDistPoller)
- `node` (OnmsNode)
- `pathOutage` (secondary table for OnmsNode)
- `categories` (OnmsCategory)
- `category_node` (join table for Node↔Category)
- `category_group` (join table for Category authorized groups)
- `service` (OnmsServiceType)
- `snmpInterface` (OnmsSnmpInterface)
- `ipInterface` (OnmsIpInterface)
- `ifServices` (OnmsMonitoredService)
- `alarms` (OnmsAlarm)
- `alarm_situations` (AlarmAssociation)
- `memos` (OnmsMemo / OnmsReductionKeyMemo)
- `accessLocks` (used by AbstractDaoJpa.lock())

**Source:** Read `core/schema/src/main/liquibase/changelog.xml` and the referenced changeset files to get exact column definitions, or reverse-engineer from the entity annotations.

The DDL should use PostgreSQL syntax. Include sequence definitions (alarmsNxtId, catNxtId, memoNxtId, serviceNxtId, etc.) and the accessLocks seed data.

- [ ] **Step 2: Commit**

```bash
git add core/opennms-model-jakarta/src/test/resources/schema.sql
git commit -m "test: add PostgreSQL DDL script for Alarmd integration tests"
```

---

### Task 11: Enable AlarmdApplicationIT

**Files:**
- Modify: `core/daemon-boot-alarmd/src/test/java/org/opennms/netmgt/alarmd/boot/AlarmdApplicationIT.java`
- Modify: `core/daemon-boot-alarmd/pom.xml` (add Testcontainers dependencies if not present)

- [ ] **Step 1: Read the existing disabled test**

Read `core/daemon-boot-alarmd/src/test/java/org/opennms/netmgt/alarmd/boot/AlarmdApplicationIT.java` to understand what's currently there and what's commented out.

- [ ] **Step 2: Add Testcontainers dependencies to daemon-boot-alarmd pom.xml**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

Also add Testcontainers BOM to `<dependencyManagement>` if not already managed by Spring Boot BOM:
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.19.7</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: Rewrite AlarmdApplicationIT**

Enable the full integration test:

```java
package org.opennms.netmgt.alarmd.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.model.OnmsAlarm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class AlarmdApplicationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("opennms")
            .withUsername("opennms")
            .withPassword("opennms")
            .withInitScript("schema.sql");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("opennms.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private AlarmDao alarmDao;

    @Autowired
    private DistPollerDao distPollerDao;

    @Test
    void contextLoads() {
        assertThat(alarmDao).isNotNull();
        assertThat(distPollerDao).isNotNull();
    }

    @Test
    void canPersistAndRetrieveAlarm() {
        var alarm = new OnmsAlarm();
        alarm.setUei("uei.opennms.org/test");
        alarm.setReductionKey("uei.opennms.org/test::1");
        alarm.setCounter(1);
        alarm.setSeverity(org.opennms.netmgt.model.OnmsSeverity.MAJOR);
        alarm.setDistPoller(distPollerDao.whoami());

        Integer id = alarmDao.save(alarm);
        assertThat(id).isNotNull();

        OnmsAlarm loaded = alarmDao.get(id);
        assertThat(loaded.getReductionKey()).isEqualTo("uei.opennms.org/test::1");
        assertThat(loaded.getSeverity()).isEqualTo(org.opennms.netmgt.model.OnmsSeverity.MAJOR);
    }

    @Test
    void findByReductionKey() {
        var alarm = new OnmsAlarm();
        alarm.setUei("uei.opennms.org/test/rk");
        alarm.setReductionKey("test-reduction-key");
        alarm.setCounter(1);
        alarm.setSeverity(org.opennms.netmgt.model.OnmsSeverity.WARNING);
        alarm.setDistPoller(distPollerDao.whoami());
        alarmDao.save(alarm);

        OnmsAlarm found = alarmDao.findByReductionKey("test-reduction-key");
        assertThat(found).isNotNull();
        assertThat(found.getUei()).isEqualTo("uei.opennms.org/test/rk");
    }
}
```

**Note:** The `schema.sql` init script from Task 10 must be on the test classpath. Since it's in `opennms-model-jakarta/src/test/resources/`, it needs to be accessible from `daemon-boot-alarmd`'s test classpath. Either:
- Copy the `schema.sql` to `daemon-boot-alarmd/src/test/resources/`, or
- Add `opennms-model-jakarta` with `<classifier>tests</classifier>` as a test dependency

Prefer copying for simplicity.

- [ ] **Step 4: Run integration test**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -t --projects :org.opennms.core.daemon-boot-alarmd -am verify`

Expected: Tests PASS (context loads, alarm persisted and retrieved)

**If tests fail:** Debug by checking:
1. Schema.sql syntax errors → check Testcontainers PostgreSQL logs
2. Entity mapping errors → check Hibernate startup logs for unmapped columns
3. Converter errors → check that `@Convert` annotations are processed
4. Bean wiring errors → check that DAOs are discovered by component scan

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-alarmd/pom.xml core/daemon-boot-alarmd/src/test/
git commit -m "test: enable AlarmdApplicationIT with Testcontainers PostgreSQL + Kafka

Validates: Spring context loads, AlarmDaoJpa persists alarms,
findByReductionKey works, OnmsSeverity converter round-trips."
```

---

### Task 12: Full build verification

- [ ] **Step 1: Run full compile of all modules**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -DskipTests`

Expected: BUILD SUCCESS (all modules compile, including legacy Karaf daemons that still use opennms-model)

This verifies no split-package conflicts and that the new module doesn't break existing modules.

- [ ] **Step 2: Run opennms-model-jakarta tests**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl --projects :org.opennms.core.model-jakarta -am verify`

Expected: All converter tests PASS

- [ ] **Step 3: Run daemon-boot-alarmd integration tests**

Run: `cd /Users/david/development/src/opennms/delta-v/.worktrees/spring-boot-alarmd && ./compile.pl -t --projects :org.opennms.core.daemon-boot-alarmd -am verify`

Expected: All integration tests PASS

- [ ] **Step 4: Push branch and open PR**

```bash
git push delta-v feature/spring-boot-4-alarmd-migration
gh pr create --repo pbrane/delta-v --base develop \
    --title "feat: add opennms-model-jakarta with Hibernate 7 entities for Alarmd" \
    --body "$(cat <<'EOF'
## Summary
- New `core/opennms-model-jakarta` module with 12 Jakarta Persistence entity classes
- 5 JPA AttributeConverters replacing Hibernate 3.6 UserTypes
- 4 JPA DAO implementations (AlarmDaoJpa, NodeDaoJpa, DistPollerDaoJpa, ServiceTypeDaoJpa)
- Alarmd Spring Boot app now boots with real Hibernate 7 + PostgreSQL
- Integration test validates alarm persistence end-to-end

## Test plan
- [ ] Converter unit tests pass
- [ ] AlarmdApplicationIT integration test passes with Testcontainers
- [ ] Full `./compile.pl -DskipTests` passes (no split-package conflicts)
- [ ] Docker E2E: `./test-passive-e2e.sh` passes with Alarmd on Spring Boot 4
EOF
)"
```

**CRITICAL:** Always use `--repo pbrane/delta-v` — never PR against OpenNMS/opennms.
