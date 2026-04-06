# Karaf Removal Design

> Delta-V has completed the Spring Boot 4 migration for all 12 daemons and Minion.
> Karaf has zero runtime consumers. This spec removes it entirely.

## Principles

1. **Replace, don't keep.** When a deleted module surfaces as a transitive dependency, replace the dependency rather than preserving the module.
2. **Document every config migration.** Each config change gets a before-after mapping in `docs/config-migration.md`.
3. **Eventconf DB loading is transitional.** The JDBC pattern works today but the future target is file-based configs managed with Git, supporting CI/CD versioned deployments.
4. **`@Value` for Spring Boot properties.** Simple daemon properties use `@Value` annotations in `config` package classes within each daemon-boot module. Not `@ConfigurationProperties`.

## Phases

| Phase | What | Type | Est. PRs |
|-------|------|------|----------|
| 1 | Delete dead Karaf infrastructure | Pure deletion | 1-2 |
| 2 | Convert bundle to jar packaging | Mechanical/scripted | 1 |
| 3 | Eliminate opennms-model | Entity porting + deletion | 3-4 |
| 4 | Eliminate opennms-config | Config bean replacement | 6-8 |

Phases 1-2 are pure deletion and mechanical changes. Phases 3-4 require per-module design.

Phases 1 and 2 are sequential (2 depends on a clean module list from 1). Phases 3 and 4 can proceed in parallel after 2 lands.

## Config Strategy (Phase 4)

Three patterns based on config complexity:

- **JDBC (transitional)**: Configs currently in PostgreSQL (eventconf). Keep working via `DaemonEventConfDao` and `EventConfEnrichmentService`. Future goal: move to file-based loading for GitOps compatibility.
- **File-based XML readers**: Complex configs that are inherently structured (snmp-config.xml, poller packages, collection packages, discovery config). Replace factory singletons with lightweight `@Configuration` beans that read the same XML files. Build these readers in a pattern that eventconf could eventually adopt.
- **`@Value` properties**: Simple daemon tunables (ports, intervals, thread counts). Externalized to `application.yml` or environment variables. Defined in `config` package classes per daemon-boot module.

### Config classes used by daemons today (from opennms-config)

| Class | Daemons | Replacement pattern |
|-------|---------|-------------------|
| SnmpPeerFactory | collectd, enlinkd, perspectivepollerd, pollerd, provisiond | File-based XML reader |
| PollerConfig / PollerConfigFactory | pollerd, perspectivepollerd | File-based XML reader |
| CollectdConfigFactory | collectd | File-based XML reader |
| DataCollectionConfigFactory | collectd | File-based XML reader |
| DefaultDataCollectionConfigDao | collectd | File-based XML reader |
| DefaultResourceTypesDao | collectd | File-based XML reader |
| DatabaseSchemaConfigFactory | collectd, pollerd, perspectivepollerd | @Value or file-based |
| EnhancedLinkdConfig / Factory | enlinkd | File-based XML reader |
| DiscoveryConfigFactory | discovery | File-based XML reader |
| EventTranslatorConfigFactory | eventtranslator | File-based XML reader |
| SyslogdConfig / Factory | syslogd | @Value + file-based |
| TrapdConfig | trapd | @Value |
| SnmpAssetAdapterConfig / Factory | provisiond | File-based XML reader |
| SnmpMetadataConfigDao | provisiond | File-based XML reader |
| ProvisiondConfiguration / RequisitionDef | provisiond | File-based XML reader |

---

## Phase 1: Delete Dead Karaf Infrastructure (Detailed)

### Scope

Remove everything with zero runtime consumers. No behavioral changes to running daemons.

### Deletions

#### 1a. Container directory

Delete `container/` entirely (15 subdirectories):
- `branding/` - Karaf console branding
- `bridge/` - OSGi bridge (api, proxy, rest)
- `extender/` - Karaf extender
- `features/` - 8 feature XML files (features.xml, features-core.xml, features-minion.xml, features-sentinel.xml, features-experimental.xml, karaf-extensions.xml, spring-legacy.xml, overrides.properties)
- `jaas-login-module/` - Karaf JAAS auth
- `karaf/` - Karaf assembly with karaf-maven-plugin
- `noop-jetty-extension/` - Karaf Jetty stub
- `servlet/` - Karaf servlet container integration
- `shared/` - Shared Karaf assembly components
- `spring-extender/` - OSGi Spring context extender

#### 1b. Assembly modules

Delete:
- `opennms-full-assembly/` - primary Karaf distribution assembly
- `opennms-base-assembly/` - base distribution components
- `opennms-assemblies/` - Karaf-related assembly sub-modules
- `assemble.pl` - Perl assembly wrapper script

#### 1c. Blueprint XML files

Delete all ~362 `OSGI-INF/blueprint/*.xml` files in `src/` directories across the source tree (not `target/`). These are OSGi service wiring definitions. No Spring Boot daemon loads them.

Also delete `META-INF/spring/*.xml` files used by the old Spring-DM (OSGi) extender. These are strictly OSGi-related Spring context files that the Spring-DM extender loaded inside the Karaf container. Spring Boot does not use this mechanism.

Execution:
```bash
find . -path '*/src/*/OSGI-INF/blueprint' -type d -exec rm -rf {} +
find . -path '*/src/*/META-INF/spring' -type d -exec rm -rf {} +
```

After deletion, remove empty `OSGI-INF/` and `META-INF/` parent directories where no other content remains.

#### 1d. Karaf-specific top-level modules

Delete:
- `opennms-bootstrap/` - Karaf bootstrap launcher
- `opennms-jetty/` - Jetty integration for Karaf
- `opennms-install/` - Karaf-era installer scripts
- `opennms-config-tester/` - config validation for Karaf runtime

#### 1e. Build script cleanup

- `Makefile`: remove `assemble` target and any `opennms-full-assembly` references
- `compile.pl`: remove Karaf assembly references
- `runtests.sh`: remove references to Karaf assembly paths if present

#### 1f. Parent POM cleanup

In root `pom.xml`:
- Remove from `<modules>`: container, opennms-full-assembly, opennms-base-assembly, opennms-assemblies, opennms-bootstrap, opennms-jetty, opennms-install, opennms-config-tester
- Remove properties: `karafVersion`, `maven.karaf.plugin.version` (if no remaining references)
- Remove any `<dependencyManagement>` entries for deleted modules

### What stays in Phase 1

- `opennms-model/` - compile dependency (Phase 3)
- `opennms-config/` - used by 9 daemon boot modules (Phase 4)
- `opennms-dao/`, `opennms-dao-api/` - DAO interfaces still consumed
- `opennms-config-model/`, `opennms-config-api/`, `opennms-config-jaxb/` - config model classes
- `<packaging>bundle</packaging>` in POMs - Phase 2
- `maven-bundle-plugin` declarations - Phase 2
- All `core/` modules
- All `features/` modules that are compile dependencies of daemon boot modules

### Verification

1. **Compile**: `make build` succeeds (all modules in reactor still compile)
2. **Assemble**: `build.sh deltav` produces all 12 daemon boot JARs
3. **Deploy**: `deploy.sh up full` - all 12 containers start, pass `/actuator/health`
4. **E2E tests**: Run all 6 E2E test suites (trapd, alarmd, pollerd, collectd, enlinkd, provisiond) to catch runtime transitive dependency failures
5. **Transitive dependency audit**: Run `mvn dependency:tree` on all daemon-boot modules before and after, diff output. Specifically watch for `org.apache.karaf.*` and `org.apache.servicemix.*` artifacts — these are the most likely hidden transitive dependencies that need explicit replacement or exclusion in the Spring Boot 4 world.

If an E2E test fails due to a missing transitive dependency from a deleted module, **replace the dependency** rather than keeping the deleted module.

---

## Phase 2: Convert Bundle to Jar Packaging (High Level)

Convert 1,028 POMs from `<packaging>bundle</packaging>` to `<packaging>jar</packaging>`. Remove `maven-bundle-plugin` declarations. This is a scripted, mechanical change.

The OSGi manifests generated by the bundle plugin are not consumed by any runtime. Spring Boot fat JARs ignore them.

Verification: same as Phase 1 (compile, assemble, deploy, E2E).

Detailed spec written when Phase 1 is complete.

---

## Phase 3: Eliminate opennms-model (High Level)

28 entity classes exist in `core/opennms-model-jakarta`. 6 classes in model-jakarta still require opennms-model at compile time for types not yet in model-api: `EventBuilder`, `NodeLabelChangedEventBuilder`, `AlarmSummary`, `SituationSummary`, `HeatMapElement`, `OnmsCriteria`.

Steps:
1. Move remaining needed types to model-api (lightweight, no JPA)
2. Remove opennms-model compile dependency from model-jakarta
3. Remove opennms-model compile dependency from daemon-common
4. Delete `opennms-model/` entirely

Detailed spec written when Phase 2 is complete.

---

## Phase 4: Eliminate opennms-config (High Level)

21 factory/config classes from opennms-config are used across 9 daemon boot modules. Each gets replaced according to the config strategy:

- `SnmpPeerFactory` (5 daemons) - file-based XML reader in daemon-common **(prioritize first — unlocks 5 modules)**
- `PollerConfigFactory` (2 daemons) - file-based XML reader
- `CollectdConfigFactory` + data collection configs (1 daemon) - file-based XML readers
- Per-daemon configs (syslogd, trapd, discovery, enlinkd, etc.) - `@Value` properties or file-based readers

Prioritize `SnmpPeerFactory` replacement early in Phase 4 — it is the single highest-fanout dependency (collectd, enlinkd, perspectivepollerd, pollerd, provisiond) and unlocks the migration for the largest number of modules.

Each daemon's config replacement is an independent PR. After all consumers are migrated, delete `opennms-config/`.

The Karaf-era `EventConfInitializer` in `core/event-forwarder-kafka` is dead code (replaced by `KafkaEventTransportConfiguration` in daemon-common). Delete it as part of this phase.

Detailed spec written when Phase 2 is complete.
