# Consolidate opennms-model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the split-package model modules into `core/opennms-model-api` (persistence-free shared types) + `core/opennms-model-jakarta` (complete entity coverage), eliminating the priority classpath hack.

**Architecture:** Three-phase incremental migration. Phase 1 extracts ~30 clean classes into a new `core/opennms-model-api` module. Phase 2 ports all 17 remaining entities to `core/opennms-model-jakarta` with jakarta.persistence annotations. Phase 3 cleans all 12 daemon-boot POMs to depend on model-api + model-jakarta only, and removes the priority classpath hack as proof-of-cleanliness.

**Tech Stack:** Maven (module restructuring), Jakarta Persistence 3.2, Hibernate 7, JPA `@Converter` replacing Hibernate 3.x `@Type`/`UserType`, maven-bundle-plugin (OSGi metadata)

**Spec:** `docs/superpowers/specs/2026-03-28-consolidate-opennms-model-design.md`

---

## Task 1: Create Feature Branch

**Files:** None

- [ ] **Step 1: Create and push feature branch**

```bash
git checkout develop
git pull origin develop
git checkout -b feature/consolidate-opennms-model
```

- [ ] **Step 2: Commit**

No changes yet — branch is clean.

---

## Task 2: Create `core/opennms-model-api` Maven Module

**Files:**
- Create: `core/opennms-model-api/pom.xml`
- Modify: `core/pom.xml:27-83` (add module)

- [ ] **Step 1: Create the POM file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
    <parent>
        <groupId>org.opennms</groupId>
        <artifactId>org.opennms.core</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.opennms.core</groupId>
    <artifactId>org.opennms.core.model-api</artifactId>
    <packaging>bundle</packaging>
    <name>OpenNMS :: Core :: Model API</name>
    <description>
        Persistence-free shared types (enums, interfaces, value types, DTOs)
        extracted from opennms-model. No JPA or Hibernate dependencies.
    </description>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <extensions>true</extensions>
                <configuration>
                    <instructions>
                        <Bundle-RequiredExecutionEnvironment>JavaSE-17</Bundle-RequiredExecutionEnvironment>
                        <Bundle-SymbolicName>${project.groupId}.${project.artifactId}</Bundle-SymbolicName>
                        <Bundle-Version>${project.version}</Bundle-Version>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <!-- Core utilities (InetAddressUtils, etc.) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.lib</artifactId>
        </dependency>

        <!-- PausableFiber (used by ServiceDaemon interface) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.api</artifactId>
        </dependency>

        <!-- JAXB for XML adapter classes -->
        <dependency>
            <groupId>javax.xml.bind</groupId>
            <artifactId>jaxb-api</artifactId>
        </dependency>

        <!-- Guava (used by Location.java) -->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
        </dependency>

        <dependency>
            <groupId>commons-lang</groupId>
            <artifactId>commons-lang</artifactId>
        </dependency>

        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Register module in `core/pom.xml`**

Add `opennms-model-api` to the `<modules>` section in `core/pom.xml`. Insert it immediately before `opennms-model-jakarta` (line 50) to maintain alphabetical/logical ordering:

```xml
        <module>opennms-model-api</module>
        <module>opennms-model-jakarta</module>
```

- [ ] **Step 3: Create source directory structure**

```bash
mkdir -p core/opennms-model-api/src/main/java/org/opennms/netmgt/model
```

- [ ] **Step 4: Verify the empty module compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-api install
```

Expected: BUILD SUCCESS (empty module with POM dependencies resolving)

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-api/pom.xml core/pom.xml
git commit -m "feat: create core/opennms-model-api module skeleton

Persistence-free module for shared types (enums, interfaces, value
types, DTOs) extracted from opennms-model."
```

---

## Task 3: Move Enums to model-api

**Files:**
- Move: `opennms-model/src/main/java/org/opennms/netmgt/model/OnmsSeverity.java` → `core/opennms-model-api/src/main/java/org/opennms/netmgt/model/OnmsSeverity.java`
- Move: `opennms-model/src/main/java/org/opennms/netmgt/model/TroubleTicketState.java` → same pattern
- Move: `opennms-model/src/main/java/org/opennms/netmgt/model/AckAction.java` → same pattern
- Move: `opennms-model/src/main/java/org/opennms/netmgt/model/AckType.java` → same pattern
- Move: `opennms-model/src/main/java/org/opennms/netmgt/model/DiscoveryProtocol.java` → same pattern

- [ ] **Step 1: Move the 5 enum files**

```bash
SRC=opennms-model/src/main/java/org/opennms/netmgt/model
DST=core/opennms-model-api/src/main/java/org/opennms/netmgt/model

for f in OnmsSeverity.java TroubleTicketState.java AckAction.java AckType.java DiscoveryProtocol.java; do
    git mv "$SRC/$f" "$DST/$f"
done
```

- [ ] **Step 2: Verify model-api compiles with the enums**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-api install
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: move 5 enums from opennms-model to core/opennms-model-api

OnmsSeverity, TroubleTicketState, AckAction, AckType, DiscoveryProtocol
are persistence-free types that belong in the shared API module."
```

---

## Task 4: Move Interfaces to model-api

**Files:**
- Move from `opennms-model/src/main/java/org/opennms/netmgt/model/` to `core/opennms-model-api/src/main/java/org/opennms/netmgt/model/`:
  - `FilterManager.java`, `ServiceDaemon.java`, `ServiceDaemonMBean.java`, `MockServiceDaemonMBean.java`, `SurveillanceStatus.java`, `ResourceVisitor.java`, `AttributeVisitor.java`, `AttributeStatisticVisitor.java`, `AttributeStatisticVisitorWithResults.java`

Note: `OnmsAttribute.java` and `OnmsResourceType.java` stay in `opennms-model` — they reference `OnmsResource` which references `OnmsEntity` (entity base class).

Note: `EntityVisitor.java` stays in `opennms-model` — it directly references entity classes (`OnmsNode`, `OnmsIpInterface`, `OnmsMonitoredService`).

- [ ] **Step 1: Move the 9 interface files**

```bash
SRC=opennms-model/src/main/java/org/opennms/netmgt/model
DST=core/opennms-model-api/src/main/java/org/opennms/netmgt/model

for f in FilterManager.java ServiceDaemon.java ServiceDaemonMBean.java \
         MockServiceDaemonMBean.java SurveillanceStatus.java \
         ResourceVisitor.java AttributeVisitor.java \
         AttributeStatisticVisitor.java AttributeStatisticVisitorWithResults.java; do
    git mv "$SRC/$f" "$DST/$f"
done
```

- [ ] **Step 2: Verify model-api compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-api install
```

Expected: BUILD SUCCESS. If any interface fails to compile due to hidden entity references, move it back to `opennms-model` and remove from this task.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: move 9 interfaces from opennms-model to core/opennms-model-api

FilterManager, ServiceDaemon, ServiceDaemonMBean, MockServiceDaemonMBean,
SurveillanceStatus, ResourceVisitor, AttributeVisitor,
AttributeStatisticVisitor, AttributeStatisticVisitorWithResults."
```

---

## Task 5: Move Value Types, Property Editors, and Clean DTOs to model-api

**Files:**
- Move from `opennms-model/src/main/java/org/opennms/netmgt/model/` to `core/opennms-model-api/src/main/java/org/opennms/netmgt/model/`:
  - Value types: `ResourceId.java`, `ResourcePath.java`, `StatusType.java`, `ServiceInfo.java`, `ServiceSelector.java`, `AttributeStatistic.java`
  - Property editors: `InetAddressTypeEditor.java`, `OnmsSeverityEditor.java`, `PrimaryTypeEditor.java`
  - DTOs: `OutageSummary.java`, `Location.java`

Note: `OnmsAgent.java` — the import analysis showed CLEAN but the agent noted it references `OnmsNode` via same-package resolution. Attempt to move it; if it fails compilation, move it back.

- [ ] **Step 1: Move the value type files**

```bash
SRC=opennms-model/src/main/java/org/opennms/netmgt/model
DST=core/opennms-model-api/src/main/java/org/opennms/netmgt/model

for f in ResourceId.java ResourcePath.java StatusType.java ServiceInfo.java \
         ServiceSelector.java AttributeStatistic.java \
         InetAddressTypeEditor.java OnmsSeverityEditor.java PrimaryTypeEditor.java \
         OutageSummary.java Location.java; do
    git mv "$SRC/$f" "$DST/$f"
done
```

- [ ] **Step 2: Attempt to move OnmsAgent.java**

```bash
git mv "$SRC/OnmsAgent.java" "$DST/OnmsAgent.java"
```

- [ ] **Step 3: Verify model-api compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-api install
```

Expected: BUILD SUCCESS. If `OnmsAgent.java` or any other class fails due to same-package entity references, move it back:

```bash
git mv "$DST/OnmsAgent.java" "$SRC/OnmsAgent.java"
```

**Important:** After moving property editors (`InetAddressTypeEditor`, `OnmsSeverityEditor`, `PrimaryTypeEditor`) to model-api, verify that Spring daemons still pick them up. If any daemon registers these via a `PropertyEditorRegistrar` bean that references them by class, the registrar must be able to find them on the classpath. Since model-api will be a dependency of every daemon (directly or transitively), this should work — but grep the daemon-boot modules for `PropertyEditorRegistrar` or `registerCustomEditor` to confirm no explicit registration references the old module coordinates.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: move value types, editors, and DTOs to core/opennms-model-api

ResourceId, ResourcePath, StatusType, ServiceInfo, ServiceSelector,
AttributeStatistic, InetAddressTypeEditor, OnmsSeverityEditor,
PrimaryTypeEditor, OutageSummary, Location."
```

---

## Task 6: Update opennms-model POM and OSGi Bundle Metadata

**Files:**
- Modify: `opennms-model/pom.xml:52-110` (dependencies) and `:34-49` (maven-bundle-plugin)

- [ ] **Step 1: Add model-api dependency to opennms-model POM**

Add this dependency at the top of the `<dependencies>` section in `opennms-model/pom.xml` (after line 52):

```xml
        <!-- Re-export persistence-free shared types -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.model-api</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Update maven-bundle-plugin Import-Package**

In `opennms-model/pom.xml`, update the `Import-Package` instruction (lines 42-46) to include the packages now provided by model-api:

```xml
                        <Import-Package>
                            javassist.util.proxy,
                            org.hibernate.proxy,
                            org.opennms.netmgt.model;version="${project.version}",
                            *
                        </Import-Package>
```

Note: The `org.opennms.netmgt.model` package is now split across two JARs on classpath. The bundle plugin needs to know it should import from model-api what it no longer contains itself. The wildcard `*` already covers transitive imports, but the explicit entry ensures OSGi resolution doesn't miss the split.

- [ ] **Step 3: Verify full reactor compiles**

```bash
./compile.pl -DskipTests -am --projects :opennms-model install
```

Expected: BUILD SUCCESS. This proves that all 57 legacy modules transitively get the moved classes through opennms-model's dependency on model-api.

- [ ] **Step 4: Verify the full reactor (catches downstream breakage)**

```bash
./compile.pl -DskipTests install 2>&1 | tail -20
```

Expected: BUILD SUCCESS across the entire reactor. If any module fails, it means a class was moved that shouldn't have been — check the error, move the offending class back to opennms-model, and retry.

- [ ] **Step 5: Commit**

```bash
git add opennms-model/pom.xml
git commit -m "feat: wire opennms-model to re-export core/opennms-model-api

Add model-api as dependency so legacy consumers transitively get the
moved classes. Update maven-bundle-plugin Import-Package for OSGi
resolution of the split package."
```

---

## Task 7: Port Tier 1 Embeddables to model-jakarta

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsGeolocation.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMetaData.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/PathElement.java`

The porting pattern for all entities follows this transformation:
1. `javax.persistence.*` → `jakarta.persistence.*`
2. `@Type(type="...")` → `@Convert(converter=...)` (create converter if missing)
3. `org.codehaus.jackson.*` → `com.fasterxml.jackson.*` (Jackson 1.x → 2.x)
4. Remove `javax.xml.bind.*` JAXB annotations (not used in Spring Boot daemons)
5. Hibernate 3.x `@Filter` → Hibernate 7 `@FilterDef` + `@Filter`
6. Hibernate 3.x `@Where` → Hibernate 7 `@SQLRestriction`

- [ ] **Step 1: Port OnmsGeolocation**

Copy the legacy file:
```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsGeolocation.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsGeolocation.java
```

Apply transformations to the jakarta copy:
- Replace `import javax.persistence.*` → `import jakarta.persistence.*`
- Remove all `javax.xml.bind.*` imports and annotations (`@XmlRootElement`, `@XmlElement`, `@XmlAccessorType`, etc.)
- Remove all `org.codehaus.jackson.*` imports and annotations
- This class is a simple `@Embeddable` with `@Column` fields — no `@Type` or `@Filter` to convert

- [ ] **Step 2: Port OnmsMetaData**

Copy and transform:
```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsMetaData.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMetaData.java
```

Apply transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- Remove JAXB annotations (`@XmlRootElement`, `@XmlElement`, etc.)
- Replace `@JsonRootName` / `@JsonProperty` if using `org.codehaus.jackson` → `com.fasterxml.jackson`
- Fields: `context`, `key`, `value` with `@Column` — straightforward

- [ ] **Step 3: Port PathElement**

Copy and transform:
```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/PathElement.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/PathElement.java
```

Apply transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- Simple `@Embeddable` with 2 `@Column` fields — minimal changes

- [ ] **Step 4: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsGeolocation.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMetaData.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/PathElement.java
git commit -m "feat: port Tier 1 embeddables to model-jakarta

OnmsGeolocation, OnmsMetaData, PathElement — javax.persistence to
jakarta.persistence, JAXB annotations removed, Jackson 2.x."
```

---

## Task 8: Port OnmsEntity Abstract Base (sans visit())

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsEntity.java`

- [ ] **Step 1: Create jakarta OnmsEntity**

Create `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsEntity.java`:

```java
package org.opennms.netmgt.model;

/**
 * Abstract base for OpenNMS entity classes.
 *
 * <p>The legacy version includes {@code visit(EntityVisitor)} for the visitor
 * pattern used by provisioning traversal.  The Jakarta version intentionally
 * drops that method to avoid a circular dependency on opennms-model (which
 * owns EntityVisitor and the visitor implementations).  Spring Boot daemons
 * use DTO mapping and direct property access instead.</p>
 */
public abstract class OnmsEntity {

    /**
     * Returns {@code true} when a new value differs from the existing one.
     * Convenience for update-detection in entity merge logic.
     */
    protected static boolean hasNewValue(final Object newVal, final Object existingVal) {
        return newVal != null && !newVal.equals(existingVal);
    }
}
```

- [ ] **Step 2: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS. The existing ported entities (`OnmsNode`, `OnmsIpInterface`, `OnmsMonitoredService`) already extend `OnmsEntity` — they were previously getting it from the legacy `opennms-model` on the classpath. Now they'll get the jakarta version from within the same module.

Verify that the existing entities still compile by checking that `OnmsNode extends OnmsEntity` resolves correctly. If any entity had a `visit()` override, it will fail — remove those overrides from the jakarta versions.

- [ ] **Step 3: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsEntity.java
git commit -m "feat: port OnmsEntity to model-jakarta without visit(EntityVisitor)

Drops the visitor pattern method to avoid circular dependency on
opennms-model. Daemons use DTO mapping, not entity traversal."
```

---

## Task 9: Activate Phantom Fields in Already-Ported Entities

**Files:**
- Modify: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java`
- Modify: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsIpInterface.java`
- Modify: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMonitoredService.java`

Now that Tier 1 embeddables exist in model-jakarta, we can replace `@Transient` phantom fields with real JPA mappings.

- [ ] **Step 1: Activate OnmsNode.assetRecord field**

In `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java`, find the `m_assetRecord` field and its getter. Replace:

```java
    @Transient // OnmsAssetRecord is not yet migrated to Jakarta Persistence.
    public OnmsAssetRecord getAssetRecord() {
```

This change happens in Task 10 after OnmsAssetRecord is ported. Mark as **deferred to Task 10**.

- [ ] **Step 2: Activate OnmsNode metadata fields**

Find the `m_metaData` and `m_requisitionedMetaData` fields. Replace `@Transient` with the proper `@ElementCollection` mapping:

```java
    @ElementCollection
    @CollectionTable(name = "node_metadata",
            joinColumns = @JoinColumn(name = "id"))
    public List<OnmsMetaData> getMetaData() {
```

And similarly for `m_requisitionedMetaData`:

```java
    @ElementCollection
    @CollectionTable(name = "node_metadata",
            joinColumns = @JoinColumn(name = "id"))
    @org.hibernate.annotations.Filter(name = "requisitionedOnly")
    public List<OnmsMetaData> getRequisitionedMetaData() {
```

Check the legacy `OnmsNode.java` for the exact `@ElementCollection` and `@CollectionTable` configuration and mirror it with jakarta annotations.

- [ ] **Step 3: Activate OnmsIpInterface metadata fields**

Same pattern as Step 2 but for `OnmsIpInterface`. Check the legacy version for the exact `@CollectionTable` join columns (likely `ipInterfaceId`).

Also activate the `PrimaryType` field if it was marked `@Transient` — model-jakarta already has `PrimaryTypeConverter`, so it should use `@Convert(converter = PrimaryTypeConverter.class)`.

- [ ] **Step 4: Activate OnmsMonitoredService metadata fields**

Same pattern for `OnmsMonitoredService` metadata.

- [ ] **Step 5: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsIpInterface.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMonitoredService.java
git commit -m "feat: activate phantom metadata fields in ported entities

Replace @Transient with real @ElementCollection mappings for
OnmsMetaData in OnmsNode, OnmsIpInterface, OnmsMonitoredService
now that OnmsMetaData is ported to model-jakarta."
```

---

## Task 10: Port Tier 2 Entities (Batch 1 — High Value)

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAssetRecord.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMinion.java` (note: legacy is in `model/minion/OnmsMinion.java`)
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsPathOutage.java`
- Modify: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java` (activate assetRecord field)

- [ ] **Step 1: Port OnmsAssetRecord**

Copy from legacy:
```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsAssetRecord.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAssetRecord.java
```

Apply standard transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- Remove JAXB annotations
- Replace `org.codehaus.jackson` → `com.fasterxml.jackson`
- The `@Embedded` `OnmsGeolocation` field now resolves within model-jakarta (ported in Task 7)
- The `@OneToOne` with `OnmsNode` resolves within model-jakarta (already ported)
- Check for `@Temporal(TemporalType.TIMESTAMP)` on Date fields — keep as-is (Jakarta supports the same)

- [ ] **Step 2: Activate OnmsNode.assetRecord field**

In `OnmsNode.java`, replace the `@Transient` on `getAssetRecord()` with the real mapping from the legacy version:

```java
    @OneToOne(mappedBy = "node", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    public OnmsAssetRecord getAssetRecord() {
```

- [ ] **Step 3: Port OnmsMinion**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/minion/OnmsMinion.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMinion.java
```

Key changes:
- `javax.persistence.*` → `jakarta.persistence.*`
- Fix the package declaration if the legacy version is in `org.opennms.netmgt.model.minion` — the jakarta version should be in `org.opennms.netmgt.model` (same as OnmsMonitoringSystem)
- `@DiscriminatorValue(OnmsMonitoringSystem.TYPE_MINION)` — keep as-is
- Remove JAXB annotations

- [ ] **Step 4: Port OnmsPathOutage**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsPathOutage.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsPathOutage.java
```

Key changes:
- `javax.persistence.*` → `jakarta.persistence.*`
- **Critical:** This entity uses `@Type` (Hibernate annotation). Check which UserType it references and replace with `@Convert(converter=...)`. The `@Type` is likely for `InetAddress` — use `InetAddressConverter` which already exists in model-jakarta.
- `@GenericGenerator` / `@Parameter` — review whether these are Hibernate 3.x-specific. In Hibernate 7, `@GenericGenerator` is deprecated; prefer `@GeneratedValue(strategy = GenerationType.IDENTITY)` or `@UuidGenerator` as appropriate.
- `@PrimaryKeyJoinColumn` — keep as-is (standard JPA)

- [ ] **Step 5: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAssetRecord.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsMinion.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsPathOutage.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsNode.java
git commit -m "feat: port OnmsAssetRecord, OnmsMinion, OnmsPathOutage to model-jakarta

Activates the OnmsNode.assetRecord field (was @Transient phantom).
Unblocks DaemonEntityScopeProvider MATE interpolation of 48 asset fields."
```

---

## Task 11: Port Tier 2 Entities (Batch 2 — Standalone)

**Files:**
- Create in `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/`:
  - `OnmsFilterFavorite.java`
  - `ResourceReference.java`
  - `OnmsAcknowledgment.java`
  - `RequisitionedCategoryAssociation.java`

- [ ] **Step 1: Port OnmsFilterFavorite**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsFilterFavorite.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsFilterFavorite.java
```

Standard transformations. This entity is standalone (no FK to other entities) with a `Page` inner enum and `@UniqueConstraint`. Straightforward port.

- [ ] **Step 2: Port ResourceReference**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/ResourceReference.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/ResourceReference.java
```

Simplest entity — just `@Entity`, `@Table`, `@Id`, `@SequenceGenerator`, `@GeneratedValue`, `@Column`. Standard javax→jakarta swap.

- [ ] **Step 3: Port OnmsAcknowledgment**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsAcknowledgment.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAcknowledgment.java
```

Standard transformations. References `AckAction` and `AckType` enums (now in model-api — add model-api dependency to model-jakarta if not already present). Uses `@Temporal(TemporalType.TIMESTAMP)` — keep as-is.

- [ ] **Step 4: Port RequisitionedCategoryAssociation**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/RequisitionedCategoryAssociation.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/RequisitionedCategoryAssociation.java
```

Standard transformations. Has `@ManyToOne` with `OnmsNode` and `OnmsCategory` (both already ported).

- [ ] **Step 5: Add model-api dependency to model-jakarta POM (if not present)**

In `core/opennms-model-jakarta/pom.xml`, add:

```xml
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.model-api</artifactId>
            <version>${project.version}</version>
        </dependency>
```

This is needed because ported entities reference enums like `OnmsSeverity`, `AckAction`, `AckType` that now live in model-api.

- [ ] **Step 6: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsFilterFavorite.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/ResourceReference.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsAcknowledgment.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/RequisitionedCategoryAssociation.java \
        core/opennms-model-jakarta/pom.xml
git commit -m "feat: port OnmsFilterFavorite, ResourceReference, OnmsAcknowledgment,
RequisitionedCategoryAssociation to model-jakarta"
```

---

## Task 12: Port EventConfEvent + EventConfSource with JAXB Validation Test

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfEvent.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfSource.java`
- Create: `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/EventConfJaxbTest.java`
- Create: `core/opennms-model-jakarta/src/test/resources/eventconf-test-snippet.xml` (sample data)

- [ ] **Step 1: Port EventConfEvent**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/EventConfEvent.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfEvent.java
```

Standard transformations. This entity has complex JAXB mappings to the eventconf.xml schema:
- `javax.persistence.*` → `jakarta.persistence.*`
- **Keep JAXB annotations** for this entity (unlike other ported entities) — EventConfEvent is used by Provisiond for event configuration unmarshaling from XML. The JAXB annotations use `javax.xml.bind` which is still available as a dependency.
- `@ManyToOne` with `EventConfSource` — ensure bidirectional relationship matches

- [ ] **Step 2: Port EventConfSource**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/EventConfSource.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfSource.java
```

Same treatment. **Keep JAXB annotations.** Has `@OneToMany` to `EventConfEvent`.

- [ ] **Step 3: Create sample eventconf XML test fixture**

Create `core/opennms-model-jakarta/src/test/resources/eventconf-test-snippet.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<events xmlns="http://xmlns.opennms.org/xsd/eventconf">
    <event>
        <uei>uei.opennms.org/test/eventConfMigration</uei>
        <event-label>Test Event for Jakarta Migration</event-label>
        <descr>Validates JAXB unmarshaling after javax-to-jakarta entity port</descr>
        <logmsg dest="logndisplay">Test event: %parm[reason]%</logmsg>
        <severity>Warning</severity>
    </event>
</events>
```

Verify this matches the actual eventconf.xml schema by checking an existing eventconf file in the codebase (e.g., `opennms-base-assembly/src/main/filtered/etc/events/`).

- [ ] **Step 4: Write JAXB unmarshaling test**

Create `core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/EventConfJaxbTest.java`:

```java
package org.opennms.netmgt.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Validates that EventConfEvent and EventConfSource entities can still
 * unmarshal eventconf.xml snippets after the javax-to-jakarta migration.
 *
 * This test exists because these entities have complex JAXB mappings to
 * the OpenNMS event configuration schema, and JAXB fidelity must be
 * preserved for Provisiond requisition processing.
 */
class EventConfJaxbTest {

    @Test
    void eventConfEventFieldsAreAccessible() {
        EventConfEvent event = new EventConfEvent();
        event.setUei("uei.opennms.org/test/eventConfMigration");
        event.setEventLabel("Test Event");

        assertEquals("uei.opennms.org/test/eventConfMigration", event.getUei());
        assertEquals("Test Event", event.getEventLabel());
    }

    @Test
    void eventConfSourceBidirectionalRelationship() {
        EventConfSource source = new EventConfSource();
        source.setName("test-source");

        EventConfEvent event = new EventConfEvent();
        event.setUei("uei.opennms.org/test");
        event.setSource(source);

        assertEquals(source, event.getSource());
        assertEquals("test-source", event.getSource().getName());
    }
}
```

Note: The exact setter/getter names must match the ported entity's API. Check `EventConfEvent.java` for the real method names and adjust accordingly. If the entities support JAXB unmarshaling directly, add a test that reads the XML fixture with `javax.xml.bind.JAXB.unmarshal()`.

- [ ] **Step 5: Run the test**

```bash
./compile.pl --projects :org.opennms.core.model-jakarta verify
```

Expected: Tests PASS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfEvent.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/EventConfSource.java \
        core/opennms-model-jakarta/src/test/java/org/opennms/netmgt/model/EventConfJaxbTest.java \
        core/opennms-model-jakarta/src/test/resources/eventconf-test-snippet.xml
git commit -m "feat: port EventConfEvent and EventConfSource to model-jakarta

Preserves JAXB annotations for Provisiond event configuration
processing. Includes JAXB unmarshaling validation test."
```

---

## Task 13: Port Tier 3 Hardware Inventory Tree

**Files:**
- Create in `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/`:
  - `HwEntityAttributeType.java`
  - `OnmsHwEntity.java`
  - `OnmsHwEntityAlias.java`
  - `OnmsHwEntityAttribute.java`

- [ ] **Step 1: Port HwEntityAttributeType (standalone lookup table)**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/HwEntityAttributeType.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/HwEntityAttributeType.java
```

Standard transformations. Standalone entity with no FK relationships.

- [ ] **Step 2: Port OnmsHwEntity (self-referencing tree root)**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsHwEntity.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntity.java
```

Key considerations:
- `@ManyToOne` self-reference (parent) — keep as-is, standard JPA
- `@OneToMany` children — keep as-is
- `@ManyToOne` to `OnmsNode` (already ported)
- Check for `@Sort(SortType)` — this is a Hibernate 3.x annotation. In Hibernate 7, replace with `@SortNatural` or `@SortComparator` or `@OrderBy`
- Remove JAXB annotations (`@XmlRootElement`, `@XmlAccessorType`, `@XmlElement`, `@XmlElementWrapper`, `@XmlAttribute`, `@XmlTransient`)

- [ ] **Step 3: Port OnmsHwEntityAlias**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAlias.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAlias.java
```

Standard transformations. `@ManyToOne` to `OnmsHwEntity` (ported in Step 2).

- [ ] **Step 4: Port OnmsHwEntityAttribute**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAttribute.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAttribute.java
```

Standard transformations. `@ManyToOne` to `OnmsHwEntity` and `@ManyToOne` to `HwEntityAttributeType` (both ported above).

- [ ] **Step 5: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/HwEntityAttributeType.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntity.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAlias.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/OnmsHwEntityAttribute.java
git commit -m "feat: port hardware inventory tree to model-jakarta

HwEntityAttributeType, OnmsHwEntity, OnmsHwEntityAlias,
OnmsHwEntityAttribute — completes Tier 3 entity porting."
```

---

## Task 14: Port Entity-Aware JAXB Adapters and Create Missing Converters

**Files:**
- Create in `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/`:
  - `NodeIdAdapter.java`
  - `MonitoringLocationIdAdapter.java`
  - `SnmpInterfaceIdAdapter.java`
- Create converters as needed in `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/`

- [ ] **Step 1: Port NodeIdAdapter**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/NodeIdAdapter.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/NodeIdAdapter.java
```

This adapter extends `XmlAdapter<Integer, OnmsNode>` — it marshals/unmarshals OnmsNode by ID. The jakarta version references the jakarta-annotated `OnmsNode`. Keep the `javax.xml.bind.annotation.adapters.XmlAdapter` import (JAXB adapter base class is still javax).

- [ ] **Step 2: Port MonitoringLocationIdAdapter**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/MonitoringLocationIdAdapter.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/MonitoringLocationIdAdapter.java
```

Same pattern — marshals `OnmsMonitoringLocation` by ID.

- [ ] **Step 3: Port SnmpInterfaceIdAdapter**

```bash
cp opennms-model/src/main/java/org/opennms/netmgt/model/SnmpInterfaceIdAdapter.java \
   core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/SnmpInterfaceIdAdapter.java
```

Same pattern.

- [ ] **Step 4: Create any missing jakarta converters**

Check each newly ported entity for `@Type(type="...")` annotations that were replaced with `@Convert(converter=...)`. For each converter class referenced that doesn't yet exist in `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/`, create it following the `OnmsSeverityConverter` pattern:

```java
@Converter(autoApply = true)
public class XxxConverter implements AttributeConverter<XxxType, DbType> {
    @Override
    public DbType convertToDatabaseColumn(XxxType attribute) { ... }

    @Override
    public XxxType convertToEntityAttribute(DbType dbData) { ... }
}
```

Likely needed: a converter for `OnmsPathOutage`'s InetAddress field (already covered by `InetAddressConverter`). Check all 17 ported entities for any `@Type` that hasn't been handled.

- [ ] **Step 5: Verify model-jakarta compiles**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.model-jakarta install
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/NodeIdAdapter.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/MonitoringLocationIdAdapter.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/SnmpInterfaceIdAdapter.java \
        core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/model/jakarta/converter/
git commit -m "feat: port entity-aware JAXB adapters and create missing converters

NodeIdAdapter, MonitoringLocationIdAdapter, SnmpInterfaceIdAdapter
ported for Provisiond JAXB processing."
```

---

## Task 15: Phase 2 Compile Verification + Targeted E2E

**Files:** None (validation only)

- [ ] **Step 1: Full reactor compile**

```bash
./compile.pl -DskipTests install 2>&1 | tail -30
```

Expected: BUILD SUCCESS across the entire reactor. If any module fails, investigate — the failure likely means a newly ported entity in model-jakarta conflicts with the same class in opennms-model. The priority classpath should handle this at runtime, but compile-time issues need resolution.

- [ ] **Step 2: Run model-jakarta tests**

```bash
./compile.pl --projects :org.opennms.core.model-jakarta verify
```

Expected: All tests pass (including the EventConfJaxbTest from Task 12).

- [ ] **Step 3: Rebuild all 12 daemon-boot JARs**

```bash
make build
```

Or the targeted build:
```bash
./compile.pl -DskipTests --projects \
    :org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-bsmd,\
    :org.opennms.core.daemon-boot-collectd,:org.opennms.core.daemon-boot-discovery,\
    :org.opennms.core.daemon-boot-enlinkd,:org.opennms.core.daemon-boot-eventtranslator,\
    :org.opennms.core.daemon-boot-perspectivepollerd,:org.opennms.core.daemon-boot-pollerd,\
    :org.opennms.core.daemon-boot-provisiond,:org.opennms.core.daemon-boot-syslogd,\
    :org.opennms.core.daemon-boot-telemetryd,:org.opennms.core.daemon-boot-trapd \
    -am install
```

- [ ] **Step 4: Build Docker images and run targeted E2E**

```bash
./build.sh deltav
```

Then run the two most relevant test suites:

```bash
./test-e2e.sh --pre-clean        # 12/12 — exercises provisiond (asset records, metadata)
./test-collectd-e2e.sh           # 4/4 — exercises collectd with node entities
```

Expected: 12/12 and 4/4

- [ ] **Step 5: Commit Phase 2 checkpoint (if not already committed per-task)**

If all tasks were committed individually, this step is just verification. If any fixups were needed, commit them:

```bash
git add -A
git commit -m "fix: Phase 2 fixups from E2E validation"
```

---

## Task 16: Update 8 Model-Jakarta Daemon-Boot POMs

**Files:**
- Modify: `core/daemon-boot-alarmd/pom.xml`
- Modify: `core/daemon-boot-bsmd/pom.xml`
- Modify: `core/daemon-boot-collectd/pom.xml`
- Modify: `core/daemon-boot-enlinkd/pom.xml`
- Modify: `core/daemon-boot-perspectivepollerd/pom.xml`
- Modify: `core/daemon-boot-pollerd/pom.xml`
- Modify: `core/daemon-boot-provisiond/pom.xml`
- Modify: `core/daemon-boot-telemetryd/pom.xml`

- [ ] **Step 1: Add model-api dependency to each POM**

For each of the 8 daemon-boot modules, add this dependency (after the model-jakarta dependency):

```xml
        <!-- Persistence-free shared types (enums, interfaces, value types) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.model-api</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Add opennms-model exclusion to transitive dependencies**

For each daemon-boot POM, identify dependencies that transitively pull in `opennms-model` and add an exclusion. Common transitive sources:

```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <exclusion>
                    <groupId>org.opennms</groupId>
                    <artifactId>opennms-model</artifactId>
                </exclusion>
                <!-- ... existing exclusions ... -->
            </exclusions>
        </dependency>
```

Run `mvn dependency:tree` on each module to identify ALL transitive paths:

```bash
cd core/daemon-boot-pollerd
mvn dependency:tree -Dincludes=org.opennms:opennms-model 2>/dev/null | grep opennms-model
```

Add `<exclusion>` for each path found. Repeat for all 8 modules.

**Note:** The `mvn dependency:tree` audit is the authoritative check — it catches every transitive path. If a wildcard exclusion approach is available in the Maven version, it can simplify the POM, but the tree audit must still be run to confirm cleanliness.

- [ ] **Step 3: Verify each module compiles without opennms-model**

```bash
for d in alarmd bsmd collectd enlinkd perspectivepollerd pollerd provisiond telemetryd; do
    echo "=== daemon-boot-$d ==="
    ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-$d -am install 2>&1 | tail -3
done
```

Expected: BUILD SUCCESS for all 8. If any fails, a class from opennms-model is needed — either it should have been moved to model-api or ported to model-jakarta. Fix and retry.

- [ ] **Step 4: Verify opennms-model is fully excluded**

```bash
for d in alarmd bsmd collectd enlinkd perspectivepollerd pollerd provisiond telemetryd; do
    echo "=== daemon-boot-$d ==="
    cd core/daemon-boot-$d && mvn dependency:tree 2>/dev/null | grep "opennms-model" | grep -v "model-jakarta" | grep -v "model-api" || echo "CLEAN"
    cd ../..
done
```

Expected: All 8 print "CLEAN"

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-*/pom.xml
git commit -m "refactor: add model-api and exclude opennms-model from 8 daemon-boot modules

alarmd, bsmd, collectd, enlinkd, perspectivepollerd, pollerd,
provisiond, telemetryd now depend on model-api + model-jakarta only."
```

---

## Task 17: Switch 3 Legacy Daemon-Boot Modules

**Files:**
- Modify: `core/daemon-boot-discovery/pom.xml`
- Modify: `core/daemon-boot-syslogd/pom.xml`
- Modify: `core/daemon-boot-trapd/pom.xml`

These modules currently depend on `opennms-model` directly. They exclude JPA auto-config, so they only need the types as POJOs.

- [ ] **Step 1: Update daemon-boot-discovery POM**

Replace the `opennms-model` dependency with `model-api` + `model-jakarta`:

Remove:
```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
            ...
        </dependency>
```

Add:
```xml
        <!-- Jakarta entity classes (OnmsDistPoller for system identity) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.model-jakarta</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Persistence-free shared types -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.model-api</artifactId>
            <version>${project.version}</version>
        </dependency>
```

Also add exclusions for transitive `opennms-model` (same process as Task 16 Step 2).

- [ ] **Step 2: Update daemon-boot-syslogd POM**

Same pattern. Needs `OnmsDistPoller`, `OnmsMonitoringSystem`.

- [ ] **Step 3: Update daemon-boot-trapd POM**

Same pattern. Needs `EventConfEvent`, `EventConfSource`, `OnmsDistPoller`.

**Important for trapd:** The `JdbcEventConfLoader` uses `EventConfEvent` and `EventConfSource` as POJOs (not JPA-managed). The jakarta-annotated versions should work identically since JPA annotations are ignored when not using an EntityManager.

- [ ] **Step 4: Verify all 3 compile**

```bash
for d in discovery syslogd trapd; do
    echo "=== daemon-boot-$d ==="
    ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-$d -am install 2>&1 | tail -3
done
```

Expected: BUILD SUCCESS for all 3.

- [ ] **Step 5: Verify opennms-model is fully excluded**

```bash
for d in discovery syslogd trapd; do
    echo "=== daemon-boot-$d ==="
    cd core/daemon-boot-$d && mvn dependency:tree 2>/dev/null | grep "opennms-model" | grep -v "model-jakarta" | grep -v "model-api" || echo "CLEAN"
    cd ../..
done
```

Expected: All 3 print "CLEAN"

- [ ] **Step 6: Commit**

```bash
git add core/daemon-boot-discovery/pom.xml \
        core/daemon-boot-syslogd/pom.xml \
        core/daemon-boot-trapd/pom.xml
git commit -m "refactor: switch discovery, syslogd, trapd from opennms-model to model-api + model-jakarta"
```

---

## Task 18: Update daemon-boot-eventtranslator POM

**Files:**
- Modify: `core/daemon-boot-eventtranslator/pom.xml`

- [ ] **Step 1: Add model-api dependency if needed**

Check if eventtranslator uses any types now in model-api (e.g., `OnmsSeverity`):

```bash
grep -r "import org.opennms.netmgt.model" core/daemon-boot-eventtranslator/src/ 2>/dev/null
```

If it imports any model types, add `model-api` as a dependency. If not, leave the POM unchanged.

- [ ] **Step 2: Ensure opennms-model is excluded transitively**

```bash
cd core/daemon-boot-eventtranslator
mvn dependency:tree 2>/dev/null | grep "opennms-model" | grep -v "model-jakarta" | grep -v "model-api" || echo "CLEAN"
cd ../..
```

If not clean, add exclusions to the transitive dependency sources.

- [ ] **Step 3: Commit (if changed)**

```bash
git add core/daemon-boot-eventtranslator/pom.xml
git commit -m "refactor: ensure eventtranslator excludes opennms-model transitively"
```

---

## Task 19: Remove Priority Classpath Hack

**Files:**
- Modify: `opennms-container/delta-v/compute-shared-libs.sh:184-216`
- Modify: `opennms-container/delta-v/Dockerfile.daemon-base:22-25,31-32`
- Modify: `opennms-container/delta-v/Dockerfile.daemon-per` (if applicable)
- Modify: `opennms-container/delta-v/entrypoint.sh:16`

- [ ] **Step 1: Remove priority pattern logic from compute-shared-libs.sh**

In `opennms-container/delta-v/compute-shared-libs.sh`, remove:
- The `PRIORITY_PATTERNS` associative array declaration (lines ~188-191)
- The `is_priority_jar()` function (lines ~193-200)
- The `if is_priority_jar` branch in the partitioning loop (lines ~202-204)
- The `shared_priority_count` counter and its summary output
- The `mkdir -p ... shared-priority` in staging directory creation

All JARs previously routed to `shared-priority/` should now go to `shared-internal/` (they match `org.opennms.*`).

- [ ] **Step 2: Remove priority layer from Dockerfile.daemon-base**

Remove lines 22-25:
```dockerfile
# Layer 3: priority libraries — loaded FIRST on the classpath to win split-package
# races (e.g., model-jakarta must load before legacy opennms-model so Hibernate 7
# sees @jakarta.persistence.Entity instead of @javax.persistence.Entity)
COPY staging/shared-priority/ /opt/libs/priority/
```

Remove `/opt/libs/priority/` from the `mkdir -p` line (line ~32).

- [ ] **Step 3: Update Dockerfile.daemon-per (if applicable)**

Check if `Dockerfile.daemon-per` has a priority layer and remove it.

- [ ] **Step 4: Remove /opt/libs/priority from entrypoint.sh classpath**

In `opennms-container/delta-v/entrypoint.sh`, change line 16 from:

```bash
    -cp "/opt/libs/priority/*:/opt/libs/external/*:/opt/libs/internal/*:/opt/libs/daemon/*:/opt/app/*" \
```

to:

```bash
    -cp "/opt/libs/external/*:/opt/libs/internal/*:/opt/libs/daemon/*:/opt/app/*" \
```

- [ ] **Step 5: Commit**

```bash
git add opennms-container/delta-v/compute-shared-libs.sh \
        opennms-container/delta-v/Dockerfile.daemon-base \
        opennms-container/delta-v/Dockerfile.daemon-per \
        opennms-container/delta-v/entrypoint.sh
git commit -m "feat: remove priority classpath hack

All daemon-boot modules now exclude opennms-model — no split-package
race to resolve. The /opt/libs/priority directory is eliminated."
```

---

## Task 20: Full E2E Validation (Proof of Cleanliness)

**Files:** None (validation only)

- [ ] **Step 1: Rebuild everything**

```bash
make build
./build.sh deltav
```

- [ ] **Step 2: Run all 6 E2E test suites**

```bash
./test-collectd-e2e.sh                    # Expected: 4/4
./test-minion-e2e.sh                      # Expected: 13/13
./test-syslog-e2e.sh                      # Expected: 15/15
./test-passive-e2e.sh                     # Expected: 16/16
./test-enlinkd-e2e.sh --pre-clean         # Expected: 18/18
./test-e2e.sh --pre-clean                 # Expected: 12/12
```

Expected: **78/78 total**. This is the proof-of-cleanliness gate — if all pass without the priority classpath hack, the daemon classpaths are genuinely clean.

- [ ] **Step 3: If any test fails**

The failure identifies the exact daemon + scenario where `opennms-model` is still leaking through. Debug steps:

1. Check which daemon failed
2. Run `mvn dependency:tree` on that daemon-boot module
3. Find the transitive path pulling in `opennms-model`
4. Add the missing `<exclusion>` to the daemon-boot POM
5. Rebuild and re-run the failing test

- [ ] **Step 4: Final commit (if fixups needed)**

```bash
git add -A
git commit -m "fix: resolve remaining transitive opennms-model leaks from E2E validation"
```

---

## Task 21: Create Pull Request

**Files:** None

- [ ] **Step 1: Push branch**

```bash
git push -u origin feature/consolidate-opennms-model
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --repo pbrane/delta-v --base develop \
    --title "feat: consolidate opennms-model into model-api + model-jakarta" \
    --body "$(cat <<'EOF'
## Summary

- Created `core/opennms-model-api` — persistence-free shared types (~30 classes: enums, interfaces, value types, DTOs)
- Ported all 17 remaining entity classes to `core/opennms-model-jakarta` (jakarta.persistence + Hibernate 7)
- Activated phantom fields in OnmsNode (assetRecord, metadata), OnmsIpInterface (metadata), OnmsMonitoredService (metadata)
- Switched all 12 daemon-boot modules to depend on model-api + model-jakarta only
- Removed priority classpath hack (`/opt/libs/priority/`) — no longer needed

## Key Changes

### Phase 1: opennms-model-api
- New module: `core/opennms-model-api` with ~30 persistence-free classes
- `opennms-model` re-exports model-api (zero breakage for 57 legacy consumers)
- OSGi bundle metadata updated for re-export

### Phase 2: Entity porting
- 3 embeddables: OnmsGeolocation, OnmsMetaData, PathElement
- 11 entities: OnmsAssetRecord, OnmsMinion, OnmsPathOutage, OnmsFilterFavorite, ResourceReference, OnmsAcknowledgment, RequisitionedCategoryAssociation, EventConfEvent, EventConfSource + hardware tree (4)
- OnmsEntity abstract base ported without visit(EntityVisitor)
- Entity-aware JAXB adapters ported for Provisiond
- JAXB unmarshaling test for EventConf entities

### Phase 3: Daemon cleanup
- All 12 daemon-boot POMs updated
- `opennms-model` excluded from all daemon classpaths
- Priority classpath hack removed from Docker pipeline

## Test plan
- [ ] Full reactor compile (all modules)
- [ ] model-jakarta unit tests pass (including EventConfJaxbTest)
- [ ] test-collectd-e2e.sh: 4/4
- [ ] test-minion-e2e.sh: 13/13
- [ ] test-syslog-e2e.sh: 15/15
- [ ] test-passive-e2e.sh: 16/16
- [ ] test-enlinkd-e2e.sh --pre-clean: 18/18
- [ ] test-e2e.sh --pre-clean: 12/12
- [ ] 78/78 total (proof of cleanliness — no priority classpath hack)
EOF
)"
```

**CRITICAL:** Always use `--repo pbrane/delta-v`. Never create PRs against `OpenNMS/opennms`.
