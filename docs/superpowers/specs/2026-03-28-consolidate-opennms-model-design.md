# Design: Consolidate opennms-model into opennms-model-jakarta

**Date:** 2026-03-28
**Branch:** `feature/consolidate-opennms-model` off `develop`
**Prerequisite:** All 12 daemons on Spring Boot 4, 78/78 E2E tests passing

## Problem

The codebase has a split-package problem between two model modules:

- **`opennms-model`** (~158 classes, `javax.persistence`) — legacy module with 32 entity classes, 6 Hibernate 3.x UserTypes, and ~120 non-entity classes (enums, interfaces, DTOs, builders, visitors, collections, adapters). Pulled transitively by 57 modules.
- **`opennms-model-jakarta`** (~98 classes, `jakarta.persistence`) — selective port of 15 core entities + 15 enlinkd entities, converters, and DAOs for Spring Boot daemons. Uses Hibernate 7.

Both provide classes in `org.opennms.netmgt.model`. This is mitigated by a priority classpath directory (`/opt/libs/priority/`) in Docker containers that ensures model-jakarta wins the class-loading race. This hack is fragile and must go.

## Goal

Consolidate into a clean module structure so daemon-boot modules have complete entity coverage in model-jakarta and no direct dependency on the legacy opennms-model. Eliminate the priority classpath hack.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| jakarta-transform bytecode rewriting | Keep as bridge for 57 legacy modules | Minion-to-Boot4 is the prerequisite for removing it |
| Entity porting scope | All 17 remaining entities | Complete coverage avoids "missing entity" surprises |
| Hibernate 3.x UserTypes | Stay in `opennms-model` | Die naturally when opennms-model is deleted |
| opennms-model-api persistence deps | None | Clean separation; persistence-free shared types |
| opennms-model-api location | `core/opennms-model-api/` | Modern stack lives under `core/` |
| Migration strategy | Incremental (4 phases) | Each phase independently reviewable/revertable |
| OnmsEntity.visit(EntityVisitor) | Dropped from jakarta version | Daemons don't use visitor pattern; avoids circular dependency |
| Priority classpath hack | Remove in Phase 3 as proof-of-cleanliness | E2E failures reveal any remaining transitive pollution |

## Module Structure (End State)

```
core/opennms-model-api/          (~35 classes, persistence-free)
  ├── Enums: OnmsSeverity, TroubleTicketState, AckAction, AckType, DiscoveryProtocol
  ├── Interfaces: FilterManager, ServiceDaemon, ServiceDaemonMBean,
  │   SurveillanceStatus, OnmsAttribute, OnmsResourceType, AttributeVisitor,
  │   AttributeStatisticVisitor, AttributeStatisticVisitorWithResults, ResourceVisitor
  ├── Value types: ResourceId, ResourcePath, StatusType, ServiceInfo,
  │   ServiceSelector, AttributeStatistic
  ├── Property editors: InetAddressTypeEditor, OnmsSeverityEditor, PrimaryTypeEditor
  └── Clean DTOs: OnmsAgent, ResourceDTO, ResourceDTOCollection, OutageSummary, etc.

core/opennms-model-jakarta/      (47 entities total after porting)
  ├── 15 core entities (already ported)
  ├── 15 enlinkd entities (already ported)
  ├── 17 newly-ported entities (3 embeddables + 14 entities)
  ├── OnmsEntity abstract base (without visit() method)
  ├── Entity-aware JAXB adapters (NodeIdAdapter, MonitoringLocationIdAdapter, etc.)
  ├── Jakarta converters (existing + new for ported entities)
  ├── DAOs (existing + new for ported entities)
  └── Depends on: model-api (for enums/types used in entities)

opennms-model/                   (slimmed legacy bridge)
  ├── Re-exports model-api (transitive dependency)
  ├── javax.persistence entity classes (for 57 legacy consumers)
  ├── Hibernate 3.x UserTypes (6 classes)
  ├── Entity-referencing non-entity classes (builders, visitors, collections, adapters)
  ├── maven-bundle-plugin imports model-api packages for OSGi re-export
  └── Depends on: model-api
```

**Dependency direction:**
```
model-jakarta ──→ model-api ←── opennms-model (bridge)
     ↑                              ↑
 12 daemon-boot              57 legacy modules
```

## Phase 1: Create `core/opennms-model-api`

**Goal:** Extract ~35 persistence-free classes into a new module. Zero consumer breakage.

**Steps:**
1. Create `core/opennms-model-api/pom.xml` with minimal dependencies:
   - `slf4j-api`
   - `commons-lang`
   - `javax.xml.bind:jaxb-api` (for JAXB adapters that don't reference entities)
   - `org.opennms.core:org.opennms.core.lib` (for `InetAddressUtils`)
   - `org.opennms.core:org.opennms.core.fiber` (for `PausableFiber` used by `ServiceDaemon`)
2. Move ~35 classes from `opennms-model/src/main/java/` to `core/opennms-model-api/src/main/java/` (same package names)
3. Add `<dependency>opennms-model-api</dependency>` to `opennms-model/pom.xml` (re-export)
4. Update `maven-bundle-plugin` in `opennms-model`:
   - Add `Import-Package` for packages now provided by `model-api`
   - Keep `Export-Package` directives so legacy OSGi consumers see no change
5. Remove moved source files from `opennms-model/src/main/java/`
6. Register `core/opennms-model-api` in parent reactor POM (`core/pom.xml`)

**Classes to move (exact inventory — verify during implementation):**

Enums (5): `OnmsSeverity`, `TroubleTicketState`, `AckAction`, `AckType`, `DiscoveryProtocol`

Interfaces (11): `FilterManager`, `ServiceDaemon`, `ServiceDaemonMBean`, `MockServiceDaemonMBean`, `SurveillanceStatus`, `OnmsAttribute`, `OnmsResourceType`, `AttributeVisitor`, `AttributeStatisticVisitor`, `AttributeStatisticVisitorWithResults`, `ResourceVisitor`

Value types (6): `ResourceId`, `ResourcePath`, `StatusType`, `ServiceInfo`, `ServiceSelector`, `AttributeStatistic`

Property editors (3): `InetAddressTypeEditor`, `OnmsSeverityEditor`, `PrimaryTypeEditor`

Clean DTOs/utilities (~11): `OnmsAgent`, `ResourceDTO`, `ResourceDTOCollection`, `OutageSummary`, `OutageSummaryCollection`, `ServiceSelector`, `Location`, `LocationIpInterface`, `OnmsLocationAvailDataPoint`, `OnmsLocationAvailDefinition`, `OnmsLocationAvailDefinitionList`

**Note:** Some classes listed above may have hidden entity references discovered during implementation. If so, leave them in `opennms-model` — don't force them into model-api.

**Validation:** `./compile.pl -DskipTests -am install` (full reactor). No compilation errors, no OSGi resolution errors.

## Phase 2: Port 17 Remaining Entities to `model-jakarta`

**Goal:** Complete entity coverage in model-jakarta. Port in dependency order.

### Porting procedure (per entity)

1. Copy class from `opennms-model` to `core/opennms-model-jakarta`
2. `javax.persistence.*` → `jakarta.persistence.*`
3. `@Type(type="org.opennms.netmgt.model.XxxUserType")` → `@Convert(converter=XxxConverter.class)` — create converter if missing
4. Hibernate 3.x `@Filter` → Hibernate 7 `@FilterDef` + `@Filter` with explicit `@SqlFragmentAlias` where needed
5. Hibernate 3.x `@Where` → Hibernate 7 `@SQLRestriction`
6. Add DAO interface + JPA implementation if daemon-boot modules need it
7. Add to `@EntityScan` in relevant daemon-boot application classes

### Tier 1 — Embeddables (no entity dependencies)

| Class | Notes |
|-------|-------|
| `OnmsGeolocation` | `@Embeddable`. Used by `OnmsAssetRecord` |
| `OnmsMetaData` | `@Embeddable`. Used by `OnmsNode`, `OnmsIpInterface`, `OnmsMonitoredService`. **Highest value port** — activates metadata fields in the three most-used entities |
| `PathElement` | `@Embeddable`. Used by `OnmsPathOutage` |

### Tier 2 — Standalone entities

| Class | Key Relationships | Notes |
|-------|-------------------|-------|
| `OnmsAssetRecord` | `@OneToOne` `OnmsNode`, embeds `OnmsGeolocation` | **High value** — resolves phantom field in `OnmsNode`, unblocks `DaemonEntityScopeProvider` MATE interpolation (48 asset fields) |
| `OnmsMinion` | Extends `OnmsMonitoringSystem` (already ported) | |
| `OnmsPathOutage` | References `OnmsNode`, embeds `PathElement` | |
| `OnmsFilterFavorite` | Standalone (no FK) | |
| `ResourceReference` | Standalone | |
| `OnmsAcknowledgment` | References `OnmsAlarm` (already ported) | |
| `RequisitionedCategoryAssociation` | References `OnmsNode`, `OnmsCategory` (both ported) | |
| `EventConfEvent` | References `EventConfSource` | Bidirectional with `EventConfSource` — port together. Has complex JAXB mappings to the eventconf.xml schema. Add a unit test in model-jakarta that unmarshals a sample eventconf.xml snippet to verify JAXB fidelity after the port. |
| `EventConfSource` | References `EventConfEvent` | Bidirectional with `EventConfEvent` — port together. Same JAXB validation applies. |

### Tier 3 — Hardware inventory tree

| Class | Key Relationships |
|-------|-------------------|
| `HwEntityAttributeType` | Standalone lookup table |
| `OnmsHwEntity` | `@ManyToOne` self-reference (parent), `@ManyToOne` `OnmsNode` |
| `OnmsHwEntityAlias` | `@ManyToOne` `OnmsHwEntity` |
| `OnmsHwEntityAttribute` | `@ManyToOne` `OnmsHwEntity`, `@ManyToOne` `HwEntityAttributeType` |

### Also ported in Phase 2

- **`OnmsEntity`** abstract base class — ported to model-jakarta WITHOUT `visit(EntityVisitor)` method. Provides `getId()`, `hasNewValue()` only.
- **Entity-aware JAXB adapters** — `NodeIdAdapter`, `MonitoringLocationIdAdapter`, `SnmpInterfaceIdAdapter` ported to reference jakarta entities. Required by daemons doing JAXB/REST processing (especially Provisiond requisition handling).
- **New jakarta converters** — created for any ported entity that uses Hibernate 3.x `@Type` annotations (replacing UserType pattern with `@Convert`).

**Validation:** Compile model-jakarta + all daemon-boot modules. Run `test-e2e.sh` (12/12) and `test-collectd-e2e.sh` (4/4) to exercise provisiond (asset records, metadata) and collectd.

## Phase 3: Clean Daemon-Boot POMs + Remove Priority Classpath Hack

**Goal:** All 12 daemon-boot modules depend on `model-api` + `model-jakarta` only. Prove classpath cleanliness by removing the priority classpath hack.

**Steps:**
1. For each of the 8 daemon-boot modules already on model-jakarta:
   - Add `opennms-model-api` dependency
   - Verify no direct `opennms-model` dependency in POM
   - Add `<exclusion>opennms-model</exclusion>` to transitive deps that pull it in (e.g., `opennms-config`, `opennms-dao-api`)
2. For the 3 legacy daemon-boot modules (discovery, syslogd, trapd):
   - Switch from `opennms-model` to `opennms-model-api` + `opennms-model-jakarta`
   - These exclude JPA auto-config, so they just need the types as POJOs
3. For eventtranslator:
   - Add `opennms-model-api` if it needs enums, otherwise leave as-is
4. Run `mvn dependency:tree` on each daemon-boot module — confirm `opennms-model` does not appear (not even transitively)
5. **Remove priority classpath hack:**
   - `opennms-container/delta-v/compute-shared-libs.sh` — remove `shared-priority/` staging logic
   - `opennms-container/delta-v/Dockerfile.daemon-base` — remove priority layer
   - `opennms-container/delta-v/Dockerfile.daemon-per` — remove priority layer
   - `opennms-container/delta-v/entrypoint.sh` — remove `/opt/libs/priority/*` from classpath
6. Rebuild all 12 daemon-boot JARs
7. Run `build.sh deltav`

**Validation:** ALL 6 E2E suites must pass (78/78). This is the proof-of-cleanliness gate. If any test fails without the priority classpath, the failure identifies the exact transitive dependency still pulling in `opennms-model`.

| Test | Expected |
|------|----------|
| `test-collectd-e2e.sh` | 4/4 |
| `test-minion-e2e.sh` | 13/13 |
| `test-syslog-e2e.sh` | 15/15 |
| `test-passive-e2e.sh` | 16/16 |
| `test-enlinkd-e2e.sh` | 18/18 |
| `test-e2e.sh` | 12/12 |

## Phase 4: Deferred (Minion-to-Boot4)

Not part of this work. When it happens:
- Migrate 57 legacy modules from `opennms-model` → `opennms-model-api` + `opennms-model-jakarta`
- Delete `opennms-model` entirely
- Delete `jakarta-transform` Maven profile from `daemon-common`

## Risk Mitigation

| Risk | Impact | Mitigation | Detection |
|------|--------|------------|-----------|
| Transitive classpath pollution | javax entities on daemon classpath | `<exclusion>` in daemon-boot POMs | `mvn dependency:tree`, E2E without priority hack |
| Hibernate 7 mapping regression | Runtime entity failures | Port in Tier order, validate each tier | E2E tests (especially provisiond for asset records) |
| OSGi re-export breakage | Legacy Karaf consumers fail | Update `maven-bundle-plugin` imports in `opennms-model` | `mvn bundle:manifest`, inspect MANIFEST.MF |
| OnmsEntity divergence | Behavioral differences between jakarta/legacy versions | Jakarta version drops `visit()` only — daemons don't use visitors | Grep daemon-boot source for `EntityVisitor` references |
| Hidden entity references in model-api candidates | Class can't move to model-api | Leave in `opennms-model` — don't force | Compile failure during Phase 1 |

## What's NOT in Scope

- Migrating the 57 legacy modules (Phase 4)
- Deleting `opennms-model`
- Removing `jakarta-transform` Maven profile
- Refactoring entity-referencing non-entity classes (NetworkBuilder, EntityVisitor, collection wrappers)
