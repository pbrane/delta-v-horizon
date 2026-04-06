# Enlinkd Spring Boot 4 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Enlinkd (Enhanced Link Discovery) from Karaf/OSGi to a standalone Spring Boot 4 application with full JPA persistence via `opennms-model-jakarta`.

**Architecture:** Full JPA port of 15 Enlinkd entities with `AttributeConverter`s, 15 JPA DAOs extending `AbstractDaoJpa`, and a `core/daemon-boot-enlinkd` module wiring `EnhancedLinkd` via `DaemonSmartLifecycle`. Kafka RPC for SNMP collection through Minion. All existing collector/service/updater modules reused unchanged.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Hibernate 7, Jakarta Persistence, Kafka RPC, Docker

**Spec:** `docs/superpowers/specs/2026-03-23-enlinkd-spring-boot-migration-design.md`

---

## File Structure

### New files to create in `core/opennms-model-jakarta`:
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/IntegerEnumConverter.java` — Abstract base for all enum converters
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/CiscoNetworkProtocolTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/CdpGlobalDeviceIdFormatConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/LldpChassisIdSubTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/LldpPortIdSubTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/StatusConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/TruthValueConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/IsisAdminStateConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/IsisISAdjStateConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/IsisISAdjNeighSysTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/BridgeDot1dBaseTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/BridgeDot1dStpProtocolSpecificationConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/BridgeMacLinkTypeConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/BridgeDot1dStpPortStateConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/BridgeDot1dStpPortEnableConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/ImportAsExternConverter.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpElement.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpElement.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfElement.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfArea.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IsIsLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IsIsElement.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/BridgeBridgeLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/BridgeMacLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/BridgeStpLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/BridgeElement.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IpNetToMedia.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/UserDefinedLink.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/CdpLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/CdpElementDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/LldpLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/LldpElementDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfElementDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfAreaDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IsIsLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IsIsElementDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/BridgeBridgeLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/BridgeMacLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/BridgeStpLinkDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/BridgeElementDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IpNetToMediaDaoJpa.java`
- `src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/UserDefinedLinkDaoJpa.java`

### New files to create in `core/daemon-boot-enlinkd`:
- `pom.xml`
- `src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdBootApplication.java`
- `src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdJpaConfiguration.java`
- `src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdDaemonConfiguration.java`
- `src/main/resources/application.yml`

### Files to modify:
- `core/opennms-model-jakarta/pom.xml` — add enlinkd persistence-api dependency
- `core/pom.xml` — add `daemon-boot-enlinkd` module
- `opennms-container/delta-v/docker-compose.yml` — add enlinkd service
- `opennms-container/delta-v/build.sh` — add JAR staging
- `opennms-container/delta-v/Dockerfile.springboot` — add COPY line

---

## Task 1: IntegerEnumConverter Base Class and All Converters

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/IntegerEnumConverter.java`
- Create: 15 concrete converter files (listed in File Structure above)
- Modify: `core/opennms-model-jakarta/pom.xml` — add `enlinkd-persistence-api` dependency for enum types

- [ ] **Step 1: Add enlinkd persistence-api dependency to opennms-model-jakarta**

Add to `core/opennms-model-jakarta/pom.xml`:
```xml
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.persistence.api</artifactId>
    <version>${project.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.servicemix.bundles</groupId>
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Also add `core-lib` dependency if not already present (for `LldpUtils`):
```xml
<dependency>
    <groupId>org.opennms</groupId>
    <artifactId>opennms-util</artifactId>
    <version>${project.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.servicemix.bundles</groupId>
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

- [ ] **Step 2: Create IntegerEnumConverter abstract base**

```java
package org.opennms.netmgt.enlinkd.model.jakarta.converter;

import jakarta.persistence.AttributeConverter;
import java.util.function.Function;

public abstract class IntegerEnumConverter<E> implements AttributeConverter<E, Integer> {
    private final Function<E, Integer> toDb;
    private final Function<Integer, E> fromDb;

    protected IntegerEnumConverter(Function<E, Integer> toDb, Function<Integer, E> fromDb) {
        this.toDb = toDb;
        this.fromDb = fromDb;
    }

    @Override
    public Integer convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : toDb.apply(attribute);
    }

    @Override
    public E convertToEntityAttribute(Integer dbValue) {
        return dbValue == null ? null : fromDb.apply(dbValue);
    }
}
```

- [ ] **Step 3: Create all 15 concrete converters**

Each is a one-liner extending `IntegerEnumConverter`. Create all 15 files:

```java
// CiscoNetworkProtocolTypeConverter.java
package org.opennms.netmgt.enlinkd.model.jakarta.converter;

import jakarta.persistence.Converter;
import org.opennms.netmgt.enlinkd.model.CdpLink.CiscoNetworkProtocolType;

@Converter
public class CiscoNetworkProtocolTypeConverter extends IntegerEnumConverter<CiscoNetworkProtocolType> {
    public CiscoNetworkProtocolTypeConverter() {
        super(CiscoNetworkProtocolType::getValue, CiscoNetworkProtocolType::get);
    }
}
```

Apply the same pattern for all converters. Enum imports:

| Converter Class | Enum Import |
|----------------|-------------|
| `CiscoNetworkProtocolTypeConverter` | `org.opennms.netmgt.enlinkd.model.CdpLink.CiscoNetworkProtocolType` |
| `CdpGlobalDeviceIdFormatConverter` | `org.opennms.netmgt.enlinkd.model.CdpElement.CdpGlobalDeviceIdFormat` |
| `LldpChassisIdSubTypeConverter` | `org.opennms.core.utils.LldpUtils.LldpChassisIdSubType` |
| `LldpPortIdSubTypeConverter` | `org.opennms.core.utils.LldpUtils.LldpPortIdSubType` |
| `StatusConverter` | `org.opennms.netmgt.enlinkd.model.OspfElement.Status` |
| `TruthValueConverter` | `org.opennms.netmgt.enlinkd.model.OspfElement.TruthValue` |
| `IsisAdminStateConverter` | `org.opennms.netmgt.enlinkd.model.IsIsElement.IsisAdminState` |
| `IsisISAdjStateConverter` | `org.opennms.netmgt.enlinkd.model.IsIsLink.IsisISAdjState` |
| `IsisISAdjNeighSysTypeConverter` | `org.opennms.netmgt.enlinkd.model.IsIsLink.IsisISAdjNeighSysType` |
| `BridgeDot1dBaseTypeConverter` | `org.opennms.netmgt.enlinkd.model.BridgeElement.BridgeDot1dBaseType` |
| `BridgeDot1dStpProtocolSpecificationConverter` | `org.opennms.netmgt.enlinkd.model.BridgeElement.BridgeDot1dStpProtocolSpecification` |
| `BridgeMacLinkTypeConverter` | `org.opennms.netmgt.enlinkd.model.BridgeMacLink.BridgeMacLinkType` |
| `BridgeDot1dStpPortStateConverter` | `org.opennms.netmgt.enlinkd.model.BridgeStpLink.BridgeDot1dStpPortState` |
| `BridgeDot1dStpPortEnableConverter` | `org.opennms.netmgt.enlinkd.model.BridgeStpLink.BridgeDot1dStpPortEnable` |
| `ImportAsExternConverter` | `org.opennms.netmgt.enlinkd.model.OspfArea.ImportAsExtern` |

- [ ] **Step 4: Verify opennms-model-jakarta compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/pom.xml core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/converter/
git commit -m "feat: add IntegerEnumConverter base and 15 Enlinkd AttributeConverters"
```

---

## Task 2: Port CDP and LLDP Entities to Jakarta Persistence

**Files:**
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpLink.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpElement.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpLink.java`
- Create: `core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpElement.java`
- Reference: `features/enlinkd/persistence/api/src/main/java/org/opennms/netmgt/enlinkd/model/CdpLink.java` (and peers)

- [ ] **Step 1: Port CdpLink entity**

Copy `features/enlinkd/persistence/api/src/main/java/org/opennms/netmgt/enlinkd/model/CdpLink.java` to the Jakarta package. Apply these transformations:
- `javax.persistence.*` → `jakarta.persistence.*`
- Remove `@Type(type="org.opennms.netmgt.enlinkd.model.CiscoNetworkProtocolTypeUserType")` → `@Convert(converter = CiscoNetworkProtocolTypeConverter.class)`
- `@SequenceGenerator(name="opennmsSequence", sequenceName="opennmsNxtId")` → add `allocationSize=1`
- Ensure `@ManyToOne(fetch = FetchType.LAZY)` is explicit on the `node` field
- Keep `@Filter(name=FilterManager.AUTH_FILTER_NAME, ...)` annotation as-is
- Remove Hibernate-specific imports (`org.hibernate.*`)
- Verify `@Column`, `@Temporal`, `@JoinColumn` annotations preserved

- [ ] **Step 2: Port CdpElement entity**

Same transformation pattern. CdpElement has two enum types:
- `CdpGlobalDeviceIdFormat` → `@Convert(converter = CdpGlobalDeviceIdFormatConverter.class)`
- `TruthValue` (from OspfElement) → `@Convert(converter = TruthValueConverter.class)`

- [ ] **Step 3: Port LldpLink entity**

LldpLink has two enum types:
- `LldpChassisIdSubType` → `@Convert(converter = LldpChassisIdSubTypeConverter.class)`
- `LldpPortIdSubType` → `@Convert(converter = LldpPortIdSubTypeConverter.class)`

Note: LldpLink also has `getIfIndex()` method that queries `OnmsSnmpInterface` and `OnmsIpInterface` — this complex query logic lives in the DAO, not the entity.

- [ ] **Step 4: Port LldpElement entity**

LldpElement has one enum type:
- `LldpChassisIdSubType` → `@Convert(converter = LldpChassisIdSubTypeConverter.class)`

- [ ] **Step 5: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpLink.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/CdpElement.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpLink.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/LldpElement.java
git commit -m "feat: port CDP and LLDP entities to Jakarta Persistence"
```

---

## Task 3: Port OSPF, IS-IS, and IpNetToMedia Entities

**Files:**
- Create: `OspfLink.java`, `OspfElement.java`, `OspfArea.java`, `IsIsLink.java`, `IsIsElement.java`, `IpNetToMedia.java` (all in `core/opennms-model-jakarta/.../enlinkd/model/jakarta/`)
- Reference: Corresponding files in `features/enlinkd/persistence/api/.../enlinkd/model/`

- [ ] **Step 1: Port OspfLink, OspfElement, OspfArea entities**

OspfLink uses `InetAddress` fields — use existing `InetAddressConverter`:
```java
@Convert(converter = InetAddressConverter.class)
@Column(name="ospfIpAddr")
private InetAddress ospfIpAddr;
```

OspfElement enums: `Status` → `StatusConverter`, `TruthValue` → `TruthValueConverter`
OspfArea enums: `ImportAsExtern` → `ImportAsExternConverter`. Also has InetAddress fields.

- [ ] **Step 2: Port IsIsLink and IsIsElement entities**

IsIsLink enums: `IsisAdminState` → `IsisAdminStateConverter`, `IsisISAdjState` → `IsisISAdjStateConverter`, `IsisISAdjNeighSysType` → `IsisISAdjNeighSysTypeConverter`
IsIsElement enum: `IsisAdminState` → `IsisAdminStateConverter`

- [ ] **Step 3: Port IpNetToMedia entity**

IpNetToMedia has InetAddress fields → `InetAddressConverter`. Note: IpNetToMedia uses `sourceNode` (not `node`) for the `@ManyToOne` to `OnmsNode`. Ensure this is preserved with `fetch = FetchType.LAZY`.

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfLink.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfElement.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/OspfArea.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IsIsLink.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IsIsElement.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/IpNetToMedia.java
git commit -m "feat: port OSPF, IS-IS, and IpNetToMedia entities to Jakarta Persistence"
```

---

## Task 4: Port Bridge and UserDefinedLink Entities

**Files:**
- Create: `BridgeBridgeLink.java`, `BridgeMacLink.java`, `BridgeStpLink.java`, `BridgeElement.java`, `UserDefinedLink.java` (all in `core/opennms-model-jakarta/.../enlinkd/model/jakarta/`)

- [ ] **Step 1: Port BridgeElement entity**

BridgeElement enums: `BridgeDot1dBaseType` → `BridgeDot1dBaseTypeConverter`, `BridgeDot1dStpProtocolSpecification` → `BridgeDot1dStpProtocolSpecificationConverter`

- [ ] **Step 2: Port BridgeBridgeLink entity**

BridgeBridgeLink has TWO `@ManyToOne` to `OnmsNode`: `node` and `designatedNode`. Both must be `fetch = FetchType.LAZY`.

- [ ] **Step 3: Port BridgeMacLink entity**

BridgeMacLink enum: `BridgeMacLinkType` → `BridgeMacLinkTypeConverter`

- [ ] **Step 4: Port BridgeStpLink entity**

BridgeStpLink enums: `BridgeDot1dStpPortState` → `BridgeDot1dStpPortStateConverter`, `BridgeDot1dStpPortEnable` → `BridgeDot1dStpPortEnableConverter`

- [ ] **Step 5: Port UserDefinedLink entity**

Simple scalar fields only — no enum types or InetAddress fields. Note: UserDefinedLink does NOT have `@ManyToOne` to `OnmsNode` — it stores `nodeIdA` and `nodeIdZ` as plain integers.

- [ ] **Step 6: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```

- [ ] **Step 7: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/Bridge*.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/UserDefinedLink.java
git commit -m "feat: port Bridge and UserDefinedLink entities to Jakarta Persistence"
```

---

## Task 5: JPA DAOs — CDP, LLDP, and Element DAOs

**Files:**
- Create: `CdpLinkDaoJpa.java`, `CdpElementDaoJpa.java`, `LldpLinkDaoJpa.java`, `LldpElementDaoJpa.java` (all in `core/opennms-model-jakarta/.../enlinkd/model/jakarta/dao/`)
- Reference: Corresponding `*DaoHibernate.java` files in `features/enlinkd/persistence/impl/`

- [ ] **Step 1: Create CdpLinkDaoJpa**

```java
@Repository
@Transactional
public class CdpLinkDaoJpa extends AbstractDaoJpa<CdpLink, Integer> implements CdpLinkDao {

    public CdpLinkDaoJpa() { super(CdpLink.class); }

    @Override
    public CdpLink get(OnmsNode node, Integer cdpCacheIfIndex, Integer cdpCacheDeviceIndex) {
        return findUnique("SELECT c FROM CdpLink c WHERE c.node = ?1 AND c.cdpCacheIfIndex = ?2 AND c.cdpCacheDeviceIndex = ?3",
            node, cdpCacheIfIndex, cdpCacheDeviceIndex);
    }

    @Override
    public CdpLink get(Integer nodeId, Integer cdpCacheIfIndex, Integer cdpCacheDeviceIndex) {
        return findUnique("SELECT c FROM CdpLink c WHERE c.node.id = ?1 AND c.cdpCacheIfIndex = ?2 AND c.cdpCacheDeviceIndex = ?3",
            nodeId, cdpCacheIfIndex, cdpCacheDeviceIndex);
    }

    @Override
    public List<CdpLink> findByNodeId(Integer nodeId) {
        return find("SELECT c FROM CdpLink c WHERE c.node.id = ?1", nodeId);
    }

    @Override
    public void deleteByNodeIdOlderThen(Integer nodeId, Date now) {
        entityManager().createQuery("DELETE FROM CdpLink c WHERE c.node.id = ?1 AND c.cdpLinkLastPollTime < ?2")
            .setParameter(1, nodeId).setParameter(2, now).executeUpdate();
    }

    @Override
    public void deleteByNodeId(Integer nodeId) {
        entityManager().createQuery("DELETE FROM CdpLink c WHERE c.node.id = ?1")
            .setParameter(1, nodeId).executeUpdate();
    }

    @Override
    public void deleteAll() {
        entityManager().createQuery("DELETE FROM CdpLink").executeUpdate();
    }
}
```

- [ ] **Step 2: Create CdpElementDaoJpa**

Key methods:
- `findByNodeId(Integer id)` → `findUnique("SELECT e FROM CdpElement e WHERE e.node.id = ?1", id)`
- `findByGlobalDeviceId(String deviceId)` → `find("SELECT e FROM CdpElement e WHERE e.cdpGlobalDeviceId = ?1 ORDER BY e.id", deviceId)` — returns first result
- `findByCacheDeviceIdOfCdpLinksOfNode(int nodeId)` → subquery: `SELECT e FROM CdpElement e WHERE e.cdpGlobalDeviceId IN (SELECT l.cdpCacheDeviceId FROM CdpLink l WHERE l.node.id = ?1)`
- `deleteByNodeId`, `deleteAll` — same pattern

- [ ] **Step 3: Create LldpLinkDaoJpa**

Key methods follow CdpLink pattern plus:
- `findLinksForIds(List<Integer> linkIds)` → `SELECT l FROM LldpLink l WHERE l.id IN :ids` with `setParameter("ids", linkIds)`
- `getIfIndex(Integer nodeid, String portId)` → Two JPQL queries against `OnmsSnmpInterface` and `OnmsIpInterface`. This is the most complex DAO method in Enlinkd. Port the HQL to JPQL:
  ```
  SELECT s FROM OnmsSnmpInterface s WHERE s.node.id = ?1 AND (LOWER(s.ifDescr) = LOWER(?2) OR LOWER(s.ifName) = LOWER(?2) OR s.physAddr = ?2)
  ```
  Fallback query on `OnmsIpInterface` if no SNMP match.

- [ ] **Step 4: Create LldpElementDaoJpa**

Key methods:
- `findByChassisId(String chassisId, LldpChassisIdSubType type)` → `find("SELECT e FROM LldpElement e WHERE e.lldpChassisId = ?1 AND e.lldpChassisIdSubType = ?2", chassisId, type)`
- `findByChassisOfLldpLinksOfNode(int nodeId)` → EXISTS subquery: `SELECT e FROM LldpElement e WHERE EXISTS (SELECT 1 FROM LldpLink l WHERE e.lldpChassisId = l.lldpRemChassisId AND e.lldpChassisIdSubType = l.lldpRemChassisIdSubType AND l.node.id = ?1)`
- `findBySysname(String sysname)` → `findUnique("SELECT e FROM LldpElement e WHERE e.lldpSysname = ?1", sysname)`

- [ ] **Step 5: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/CdpLinkDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/CdpElementDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/LldpLinkDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/LldpElementDaoJpa.java
git commit -m "feat: add JPA DAOs for CDP and LLDP entities"
```

---

## Task 6: JPA DAOs — OSPF, IS-IS, and IpNetToMedia

**Files:**
- Create: `OspfLinkDaoJpa.java`, `OspfElementDaoJpa.java`, `OspfAreaDaoJpa.java`, `IsIsLinkDaoJpa.java`, `IsIsElementDaoJpa.java`, `IpNetToMediaDaoJpa.java`

- [ ] **Step 1: Create OspfLinkDaoJpa, OspfElementDaoJpa, OspfAreaDaoJpa**

Follow CdpLink pattern. OspfElement has:
- `findByRouterId(InetAddress routerId)` — uses `InetAddress` parameter directly in JPQL
- `findByRouterIdOfRelatedOspfLink(int nodeId)` → subquery: `SELECT e FROM OspfElement e WHERE e.ospfRouterId IN (SELECT l.ospfRemRouterId FROM OspfLink l WHERE l.node.id = ?1)`

OspfArea has no `deleteAll()` — only `deleteByNodeId` and `deleteByNodeIdOlderThen`.

- [ ] **Step 2: Create IsIsLinkDaoJpa, IsIsElementDaoJpa**

IsIsLink has a complex method:
- `findBySysIdAndAdjAndCircIndex(int nodeId)` → EXISTS subquery:
  ```
  SELECT r FROM IsIsLink r WHERE EXISTS (SELECT 1 FROM IsIsElement e, IsIsLink l WHERE r.node.id = e.node.id AND r.isisISAdjIndex = l.isisISAdjIndex AND r.isisCircIndex = l.isisCircIndex AND e.isisSysID = l.isisISAdjNeighSysID AND l.node.id = ?1)
  ```

IsIsElement has:
- `findBySysIdOfIsIsLinksOfNode(int nodeId)` → IN subquery: `SELECT e FROM IsIsElement e WHERE e.isisSysID IN (SELECT l.isisISAdjNeighSysID FROM IsIsLink l WHERE l.node.id = ?1)`

- [ ] **Step 3: Create IpNetToMediaDaoJpa**

**Important:** IpNetToMedia uses `sourceNode` (not `node`) for its `@ManyToOne`. Ensure JPQL uses `rec.sourceNode.id`.

IpNetToMedia has a delete pattern different from others — the legacy code finds entities then deletes them individually (not bulk HQL). Port to bulk JPQL delete:
- `deleteBySourceNodeIdOlderThen(Integer nodeId, Date now)` → `DELETE FROM IpNetToMedia m WHERE m.sourceNode.id = ?1 AND m.lastPollTime < ?2`
- `deleteBySourceNodeId(Integer nodeId)` → `DELETE FROM IpNetToMedia m WHERE m.sourceNode.id = ?1`

Cross-entity query: `findByMacLinksOfNode(Integer nodeId)` → IN subquery: `SELECT m FROM IpNetToMedia m WHERE m.physAddress IN (SELECT l.macAddress FROM BridgeMacLink l WHERE l.node.id = ?1)`

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```

- [ ] **Step 5: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfLinkDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfElementDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/OspfAreaDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IsIsLinkDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IsIsElementDaoJpa.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/IpNetToMediaDaoJpa.java
git commit -m "feat: add JPA DAOs for OSPF, IS-IS, and IpNetToMedia entities"
```

---

## Task 7: JPA DAOs — Bridge and UserDefinedLink

**Files:**
- Create: `BridgeBridgeLinkDaoJpa.java`, `BridgeMacLinkDaoJpa.java`, `BridgeStpLinkDaoJpa.java`, `BridgeElementDaoJpa.java`, `UserDefinedLinkDaoJpa.java`

- [ ] **Step 1: Create BridgeBridgeLinkDaoJpa**

Most complex Bridge DAO — has TWO node references (`node` and `designatedNode`). All methods:
- `findByNodeId(Integer id)` → `find("SELECT r FROM BridgeBridgeLink r WHERE r.node.id = ?1", id)`
- `findByDesignatedNodeId(Integer id)` → `find("SELECT r FROM BridgeBridgeLink r WHERE r.designatedNode.id = ?1", id)`
- `getByNodeIdBridgePort(Integer id, Integer port)` → `findUnique(... WHERE r.node.id = ?1 AND r.bridgePort = ?2 ...)`
- `getByNodeIdBridgePortIfIndex(Integer id, Integer ifindex)` → `findUnique(... WHERE r.node.id = ?1 AND r.bridgePortIfIndex = ?2 ...)`
- `getByDesignatedNodeIdBridgePort(Integer id, Integer port)` → `find(... WHERE r.designatedNode.id = ?1 AND r.designatedPort = ?2 ...)`
- `getByDesignatedNodeIdBridgePortIfIndex(Integer id, Integer ifindex)` → `find(... WHERE r.designatedNode.id = ?1 AND r.designatedPortIfIndex = ?2 ...)`
- `deleteByNodeIdOlderThen`, `deleteByDesignatedNodeIdOlderThen` — separate timestamp-based pruning
- `deleteByNodeId`, `deleteByDesignatedNodeId`, `deleteAll` — bulk deletes

- [ ] **Step 2: Create BridgeMacLinkDaoJpa, BridgeStpLinkDaoJpa**

Standard patterns. BridgeMacLink:
- `getByNodeIdBridgePortMac(Integer id, Integer port, String mac)` → 3-param findUnique

BridgeStpLink:
- `findByDesignatedBridge(String designated)`, `findByDesignatedRoot(String root)` — string queries

- [ ] **Step 3: Create BridgeElementDaoJpa**

BridgeElement has conditional null handling in `getByNodeIdVlan`:
```java
@Override
public BridgeElement getByNodeIdVlan(Integer id, Integer vlanId) {
    if (vlanId == null) {
        return findUnique("SELECT e FROM BridgeElement e WHERE e.node.id = ?1 AND e.vlan IS NULL", id);
    }
    return findUnique("SELECT e FROM BridgeElement e WHERE e.node.id = ?1 AND e.vlan = ?2", id, vlanId);
}
```

- [ ] **Step 4: Create UserDefinedLinkDaoJpa**

Simple — 3 methods:
- `getOutLinks(int nodeIdA)` → `find("SELECT u FROM UserDefinedLink u WHERE u.nodeIdA = ?1", nodeIdA)`
- `getInLinks(int nodeIdZ)` → same pattern
- `getLinksWithLabel(String label)` → same pattern

- [ ] **Step 5: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.opennms-model-jakarta -am install
```

- [ ] **Step 6: Commit**

```bash
git add core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/Bridge*.java core/opennms-model-jakarta/src/main/java/org/opennms/netmgt/enlinkd/model/jakarta/dao/UserDefinedLinkDaoJpa.java
git commit -m "feat: add JPA DAOs for Bridge and UserDefinedLink entities"
```

---

## Task 8: Create daemon-boot-enlinkd Maven Module

**Files:**
- Create: `core/daemon-boot-enlinkd/pom.xml`
- Modify: `core/pom.xml`

- [ ] **Step 1: Create pom.xml**

Clone from `core/daemon-boot-telemetryd/pom.xml`. Change:
- `artifactId`: `org.opennms.core.daemon-boot-enlinkd`
- `name`: `OpenNMS :: Core :: Daemon Boot :: Enlinkd`

Replace Telemetryd-specific dependencies with Enlinkd dependencies (all with ServiceMix wildcard exclusions):

```xml
<!-- Enlinkd daemon and adapters -->
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.daemon</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard + spring-dependencies --></exclusions>
</dependency>
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.api</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.config</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
<!-- Service layer -->
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.service.api</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.service.impl</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
<!-- Adapter modules: common + all 6 collectors + all 6 updaters + bridge discover -->
<dependency>
    <groupId>org.opennms.features.enlinkd</groupId>
    <artifactId>org.opennms.features.enlinkd.adapters.common</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
<!-- Repeat for: adapters.collectors.cdp, .lldp, .ospf, .isis, .bridge, .ipnettomedia -->
<!-- Repeat for: adapters.updaters.cdp, .lldp, .ospf, .isis, .bridge, .nodes -->
<!-- Repeat for: adapters.discovers.bridge -->
<!-- SNMP RPC -->
<dependency>
    <groupId>org.opennms.core.snmp</groupId>
    <artifactId>org.opennms.core.snmp.proxy.rpc-impl</artifactId>
    <version>${project.version}</version>
    <exclusions><!-- ServiceMix wildcard --></exclusions>
</dependency>
```

Keep from Telemetryd POM: `model-jakarta`, `daemon-common`, `opennms-config`, javax/jakarta compat deps, Spring Boot BOM + pins, test deps.

Remove (not needed): Sink/Twin dependencies, `daemon-loader-telemetryd`.

Add `opennms-dao-api` (for topology-related interfaces).

- [ ] **Step 2: Register module in core/pom.xml**

Add `<module>daemon-boot-enlinkd</module>` after `daemon-boot-telemetryd`.

- [ ] **Step 3: Create empty src/main/java directory**

```bash
mkdir -p core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot
mkdir -p core/daemon-boot-enlinkd/src/main/resources
```

- [ ] **Step 4: Verify module compiles (empty)**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-enlinkd -am install
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-enlinkd/pom.xml core/pom.xml
git commit -m "feat: add daemon-boot-enlinkd Maven module"
```

---

## Task 9: Enlinkd JPA and Daemon Configuration Classes

**Files:**
- Create: `core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdJpaConfiguration.java`
- Create: `core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdDaemonConfiguration.java`
- Create: `core/daemon-boot-enlinkd/src/main/resources/application.yml`

- [ ] **Step 1: Create EnlinkdJpaConfiguration**

```java
@Configuration
@EnableTransactionManagement
public class EnlinkdJpaConfiguration {

    @Bean
    public PhysicalNamingStrategyStandardImpl physicalNamingStrategy() {
        return new PhysicalNamingStrategyStandardImpl();
    }

    @Bean
    public PersistenceManagedTypes persistenceManagedTypes() {
        return PersistenceManagedTypes.of(
            // Core entities
            OnmsNode.class.getName(),
            OnmsIpInterface.class.getName(),
            OnmsSnmpInterface.class.getName(),
            OnmsMonitoringLocation.class.getName(),
            OnmsDistPoller.class.getName(),
            OnmsMonitoringSystem.class.getName(),
            OnmsCategory.class.getName(),
            // Enlinkd entities (all 15)
            CdpLink.class.getName(),
            CdpElement.class.getName(),
            LldpLink.class.getName(),
            LldpElement.class.getName(),
            OspfLink.class.getName(),
            OspfElement.class.getName(),
            OspfArea.class.getName(),
            IsIsLink.class.getName(),
            IsIsElement.class.getName(),
            BridgeBridgeLink.class.getName(),
            BridgeMacLink.class.getName(),
            BridgeStpLink.class.getName(),
            BridgeElement.class.getName(),
            IpNetToMedia.class.getName(),
            UserDefinedLink.class.getName()
        );
    }

    @Bean
    public SessionUtils sessionUtils(PlatformTransactionManager txManager) {
        var txTemplate = new TransactionTemplate(txManager);
        var readOnlyTxTemplate = new TransactionTemplate(txManager);
        readOnlyTxTemplate.setReadOnly(true);
        return new SessionUtils() {
            @Override
            public <V> V withTransaction(Supplier<V> supplier) {
                return txTemplate.execute(status -> supplier.get());
            }
            @Override
            public <V> V withReadOnlyTransaction(Supplier<V> supplier) {
                return readOnlyTxTemplate.execute(status -> supplier.get());
            }
            @Override
            public <V> V withManualFlush(Supplier<V> supplier) {
                return supplier.get();
            }
        };
    }

    // All 15 DAO beans
    @Bean public CdpLinkDaoJpa cdpLinkDao() { return new CdpLinkDaoJpa(); }
    @Bean public CdpElementDaoJpa cdpElementDao() { return new CdpElementDaoJpa(); }
    @Bean public LldpLinkDaoJpa lldpLinkDao() { return new LldpLinkDaoJpa(); }
    @Bean public LldpElementDaoJpa lldpElementDao() { return new LldpElementDaoJpa(); }
    @Bean public OspfLinkDaoJpa ospfLinkDao() { return new OspfLinkDaoJpa(); }
    @Bean public OspfElementDaoJpa ospfElementDao() { return new OspfElementDaoJpa(); }
    @Bean public OspfAreaDaoJpa ospfAreaDao() { return new OspfAreaDaoJpa(); }
    @Bean public IsIsLinkDaoJpa isisLinkDao() { return new IsIsLinkDaoJpa(); }
    @Bean public IsIsElementDaoJpa isisElementDao() { return new IsIsElementDaoJpa(); }
    @Bean public BridgeBridgeLinkDaoJpa bridgeBridgeLinkDao() { return new BridgeBridgeLinkDaoJpa(); }
    @Bean public BridgeMacLinkDaoJpa bridgeMacLinkDao() { return new BridgeMacLinkDaoJpa(); }
    @Bean public BridgeStpLinkDaoJpa bridgeStpLinkDao() { return new BridgeStpLinkDaoJpa(); }
    @Bean public BridgeElementDaoJpa bridgeElementDao() { return new BridgeElementDaoJpa(); }
    @Bean public IpNetToMediaDaoJpa ipNetToMediaDao() { return new IpNetToMediaDaoJpa(); }
    @Bean public UserDefinedLinkDaoJpa userDefinedLinkDao() { return new UserDefinedLinkDaoJpa(); }
}
```

- [ ] **Step 2: Create EnlinkdDaemonConfiguration**

```java
@Configuration
public class EnlinkdDaemonConfiguration {

    // SnmpPeerFactory initialization — NodeCollector uses SnmpPeerFactory.getInstance() statically
    // Must be initialized before EnhancedLinkd starts SNMP collection
    @Bean(initMethod = "init")
    public SnmpPeerFactory snmpPeerFactory() {
        return new SnmpPeerFactory(/* loads from opennms.home/etc/snmp-config.xml */);
    }

    // SNMP RPC
    @Bean
    public LocationAwareSnmpClientRpcImpl locationAwareSnmpClient(RpcClientFactory rpcClientFactory) {
        return new LocationAwareSnmpClientRpcImpl(rpcClientFactory);
    }

    // Configuration — constructor self-initializes via reload(), throws IOException
    @Bean
    public EnhancedLinkdConfigFactory linkdConfig() throws IOException {
        return new EnhancedLinkdConfigFactory();
    }

    // Topology services — all use setter injection (legacy pattern)
    @Bean
    public NodeTopologyServiceImpl nodeTopologyService(NodeDaoJpa nodeDao) {
        var svc = new NodeTopologyServiceImpl();
        svc.setNodeDao(nodeDao);
        return svc;
    }

    @Bean
    public CdpTopologyServiceImpl cdpTopologyService(CdpLinkDaoJpa cdpLinkDao, CdpElementDaoJpa cdpElementDao) {
        var svc = new CdpTopologyServiceImpl();
        svc.setCdpLinkDao(cdpLinkDao);
        svc.setCdpElementDao(cdpElementDao);
        return svc;
    }

    @Bean
    public LldpTopologyServiceImpl lldpTopologyService(LldpLinkDaoJpa lldpLinkDao, LldpElementDaoJpa lldpElementDao) {
        var svc = new LldpTopologyServiceImpl();
        svc.setLldpLinkDao(lldpLinkDao);
        svc.setLldpElementDao(lldpElementDao);
        return svc;
    }

    @Bean
    public OspfTopologyServiceImpl ospfTopologyService(OspfLinkDaoJpa ospfLinkDao, OspfElementDaoJpa ospfElementDao, OspfAreaDaoJpa ospfAreaDao) {
        var svc = new OspfTopologyServiceImpl();
        svc.setOspfLinkDao(ospfLinkDao);
        svc.setOspfElementDao(ospfElementDao);
        svc.setOspfAreaDao(ospfAreaDao);
        return svc;
    }

    @Bean
    public IsisTopologyServiceImpl isisTopologyService(IsIsLinkDaoJpa isisLinkDao, IsIsElementDaoJpa isisElementDao) {
        var svc = new IsisTopologyServiceImpl();
        svc.setIsisLinkDao(isisLinkDao);
        svc.setIsisElementDao(isisElementDao);
        return svc;
    }

    @Bean
    public BridgeTopologyServiceImpl bridgeTopologyService(
            BridgeElementDaoJpa bridgeElementDao, BridgeBridgeLinkDaoJpa bridgeBridgeLinkDao,
            BridgeMacLinkDaoJpa bridgeMacLinkDao, BridgeStpLinkDaoJpa bridgeStpLinkDao,
            IpNetToMediaDaoJpa ipNetToMediaDao) {
        var svc = new BridgeTopologyServiceImpl();
        svc.setBridgeElementDao(bridgeElementDao);
        svc.setBridgeBridgeLinkDao(bridgeBridgeLinkDao);
        svc.setBridgeMacLinkDao(bridgeMacLinkDao);
        svc.setBridgeStpLinkDao(bridgeStpLinkDao);
        svc.setIpNetToMediaDao(ipNetToMediaDao);
        return svc;
    }

    @Bean
    public IpNetToMediaTopologyServiceImpl ipNetToMediaTopologyService(IpNetToMediaDaoJpa ipNetToMediaDao) {
        var svc = new IpNetToMediaTopologyServiceImpl();
        svc.setIpNetToMediaDao(ipNetToMediaDao);
        // Note: IpNetToMediaTopologyServiceImpl also needs IpInterfaceDao — check if Enlinkd uses
        // the methods that require it. If so, add an IpInterfaceDaoJpa bean.
        return svc;
    }

    @Bean
    public UserDefinedLinkTopologyServiceImpl userDefinedLinkTopologyService(UserDefinedLinkDaoJpa userDefinedLinkDao) {
        var svc = new UserDefinedLinkTopologyServiceImpl();
        // UserDefinedLinkTopologyServiceImpl uses @Autowired field injection for userDefinedLinkDao
        // Spring's AutowiredAnnotationBeanPostProcessor will handle this
        return svc;
    }

    // OnmsTopologyDao (in-memory, for updaters)
    @Bean
    public OnmsTopologyDao onmsTopologyDao() {
        return new InMemoryOnmsTopologyDao();
    }

    // Topology updaters — all use constructor injection
    @Bean
    public NodesOnmsTopologyUpdater nodesTopologyUpdater(OnmsTopologyDao topologyDao, NodeTopologyService nodeTopologyService) {
        return new NodesOnmsTopologyUpdater(topologyDao, nodeTopologyService);
    }

    @Bean
    public CdpOnmsTopologyUpdater cdpTopologyUpdater(OnmsTopologyDao topologyDao, CdpTopologyService cdpTopologyService, NodeTopologyService nodeTopologyService) {
        return new CdpOnmsTopologyUpdater(topologyDao, cdpTopologyService, nodeTopologyService);
    }

    @Bean
    public LldpOnmsTopologyUpdater lldpTopologyUpdater(OnmsTopologyDao topologyDao, LldpTopologyService lldpTopologyService, NodeTopologyService nodeTopologyService) {
        return new LldpOnmsTopologyUpdater(topologyDao, lldpTopologyService, nodeTopologyService);
    }

    @Bean
    public OspfOnmsTopologyUpdater ospfTopologyUpdater(OnmsTopologyDao topologyDao, OspfTopologyService ospfTopologyService, NodeTopologyService nodeTopologyService) {
        return new OspfOnmsTopologyUpdater(topologyDao, ospfTopologyService, nodeTopologyService);
    }

    @Bean
    public IsisOnmsTopologyUpdater isisTopologyUpdater(OnmsTopologyDao topologyDao, IsisTopologyService isisTopologyService, NodeTopologyService nodeTopologyService) {
        return new IsisOnmsTopologyUpdater(topologyDao, isisTopologyService, nodeTopologyService);
    }

    @Bean
    public BridgeOnmsTopologyUpdater bridgeTopologyUpdater(OnmsTopologyDao topologyDao, BridgeTopologyService bridgeTopologyService, NodeTopologyService nodeTopologyService) {
        return new BridgeOnmsTopologyUpdater(topologyDao, bridgeTopologyService, nodeTopologyService);
    }

    // Note: OspfAreaOnmsTopologyUpdater, UserDefinedLinkTopologyUpdater, NetworkRouterTopologyUpdater
    // — check constructor signatures at implementation time and add beans here.
    // DiscoveryBridgeDomains — check constructor at implementation time; likely needs
    // BridgeTopologyService and NodeTopologyService.

    // The daemon
    @Bean
    public EnhancedLinkd enhancedLinkd() {
        return new EnhancedLinkd();
    }

    // Event processor — do NOT call init() (MessageBus subscription path, dropped)
    @Bean
    public EventProcessor eventProcessor(EnhancedLinkd linkd) {
        var processor = new EventProcessor();
        processor.setLinkd(linkd);
        return processor;
    }

    // Event listener adapter — 0-arg constructor pattern
    @Bean
    public AnnotationBasedEventListenerAdapter enlinkdEventListener(
            EventProcessor eventProcessor,
            EventSubscriptionService eventSubscriptionService) {
        var adapter = new AnnotationBasedEventListenerAdapter();
        adapter.setAnnotatedListener(eventProcessor);
        adapter.setEventSubscriptionService(eventSubscriptionService);
        return adapter;
    }

    // Lifecycle
    @Bean
    public DaemonSmartLifecycle enlinkdLifecycle(EnhancedLinkd daemon) {
        return new DaemonSmartLifecycle(daemon);
    }
}
```

- [ ] **Step 3: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/opennms}
    username: ${SPRING_DATASOURCE_USERNAME:opennms}
    password: ${SPRING_DATASOURCE_PASSWORD:opennms}
    driver-class-name: org.postgresql.Driver
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none

opennms:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    event:
      consumer-group: ${KAFKA_CONSUMER_GROUP:enlinkd}
      topic: opennms-fault-events
  rpc:
    kafka:
      enabled: ${OPENNMS_RPC_KAFKA_ENABLED:true}
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
      force-remote: "true"

server:
  port: 8080
```

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-enlinkd -am install
```

- [ ] **Step 5: Commit**

```bash
git add core/daemon-boot-enlinkd/src/
git commit -m "feat: add Enlinkd daemon configuration with JPA and topology services"
```

---

## Task 10: Application Class and Boot Wiring

**Files:**
- Create: `core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdBootApplication.java`

- [ ] **Step 1: Create EnlinkdBootApplication**

```java
@SpringBootApplication(
    scanBasePackages = {},
    exclude = {
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.DataJpaRepositoriesAutoConfiguration.class
    }
)
@Import({
    DaemonDataSourceConfiguration.class,
    KafkaRpcClientConfiguration.class,
    KafkaEventTransportConfiguration.class,
    EnlinkdJpaConfiguration.class,
    EnlinkdDaemonConfiguration.class
})
public class EnlinkdBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnlinkdBootApplication.class, args);
    }
}
```

- [ ] **Step 2: Build the fat JAR**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-enlinkd -am install
```

Verify the fat JAR exists:
```bash
ls -lh core/daemon-boot-enlinkd/target/daemon-boot-enlinkd-*.jar
```

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdBootApplication.java
git commit -m "feat: add EnlinkdBootApplication with Spring Boot 4 wiring"
```

---

## Task 11: Docker Integration

**Files:**
- Modify: `opennms-container/delta-v/Dockerfile.springboot`
- Modify: `opennms-container/delta-v/docker-compose.yml`
- Modify: `opennms-container/delta-v/build.sh`

- [ ] **Step 1: Add JAR staging to build.sh**

Add after the Telemetryd staging line:
```bash
cp core/daemon-boot-enlinkd/target/daemon-boot-enlinkd-*.jar \
   core/daemon-boot-enlinkd/target/daemon-boot-enlinkd.jar
```

- [ ] **Step 2: Add COPY to Dockerfile.springboot**

Add after the Telemetryd COPY line:
```dockerfile
COPY core/daemon-boot-enlinkd/target/daemon-boot-enlinkd.jar /opt/daemon-boot-enlinkd.jar
```

- [ ] **Step 3: Add enlinkd service to docker-compose.yml**

```yaml
enlinkd:
  build:
    context: .
    dockerfile: opennms-container/delta-v/Dockerfile.springboot
  entrypoint: []
  command:
    - "java"
    - "-Xms256m"
    - "-Xmx512m"
    - "-XX:MaxMetaspaceSize=256m"
    - "-Dopennms.home=/opt/deltav"
    - "-jar"
    - "/opt/daemon-boot-enlinkd.jar"
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
    SPRING_DATASOURCE_USERNAME: opennms
    SPRING_DATASOURCE_PASSWORD: opennms
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: enlinkd
    OPENNMS_HOME: /opt/deltav
    OPENNMS_RPC_KAFKA_ENABLED: "true"
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
    interval: 30s
    timeout: 5s
    retries: 3
  stop_grace_period: 60s
  volumes:
    - ./opennms-container/delta-v/overlay/etc:/opt/deltav/etc:ro
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_started
```

- [ ] **Step 4: Run build.sh and verify Docker build**

```bash
cd opennms-container/delta-v && ./build.sh
docker compose build enlinkd
```

- [ ] **Step 5: Commit**

```bash
git add opennms-container/delta-v/build.sh opennms-container/delta-v/Dockerfile.springboot opennms-container/delta-v/docker-compose.yml
git commit -m "feat: update Docker integration for Spring Boot Enlinkd"
```

---

## Task 12: E2E Verification

- [ ] **Step 1: Start the full stack**

```bash
cd opennms-container/delta-v && docker compose up -d
```

- [ ] **Step 2: Verify Enlinkd starts cleanly**

```bash
docker compose logs enlinkd --tail 50
```

Check for:
- `Started EnlinkdBootApplication` message
- No `BeanCreationException` or `@Autowired` failures
- `BeanUtils.assertAutowiring` passes (no assertion errors)
- `EnhancedLinkd: init:` log lines showing scheduler and executor creation
- `EnhancedLinkd: start:` log lines

- [ ] **Step 3: Verify health endpoint**

```bash
curl -sf http://localhost:<enlinkd-port>/actuator/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 4: Verify SNMP RPC connectivity**

Check logs for Kafka RPC client initialization:
```bash
docker compose logs enlinkd | grep -i "kafka\|rpc"
```

- [ ] **Step 5: Run existing E2E tests**

Verify no regressions in existing daemon tests:
```bash
cd opennms-container/delta-v && docker compose exec -T test-runner ./run-e2e-tests.sh
```

- [ ] **Step 6: Commit dashboard update**

```bash
# Update docs/dashboard.md with Enlinkd migration status
git add docs/
git commit -m "docs: update dashboard — 11 daemons migrated, Enlinkd complete"
```

---

## Dependency Graph

```
Task 1 (Converters) ──┐
                       ├── Task 2 (CDP/LLDP entities) ──┐
                       ├── Task 3 (OSPF/ISIS entities) ──├── Task 5 (CDP/LLDP DAOs) ──┐
                       └── Task 4 (Bridge entities) ─────├── Task 6 (OSPF/ISIS DAOs) ──├── Task 8 (Maven module) ──> Task 9 (Config) ──> Task 10 (App) ──> Task 11 (Docker) ──> Task 12 (E2E)
                                                         └── Task 7 (Bridge DAOs) ─────┘
```

Tasks 2-4 can run in parallel after Task 1.
Tasks 5-7 can run in parallel after their respective entity tasks.
Tasks 8-12 are sequential.
