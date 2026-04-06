# Eliminate opennms-services Monolith

**Date:** 2026-03-27
**Status:** Approved
**Scope:** Delete daemon-loaders, extract daemon implementations, delete opennms-services

## Context

All 12 daemons run as standalone Spring Boot 4 applications (`daemon-boot-*`). The `opennms-services` module is a legacy monolith (~84 Java classes) that still bundles daemon implementations for Pollerd, Collectd, and EventTranslator alongside dead code from deleted daemons (Scriptd, RTCd, Capsd, SnmpInterfacePoller).

The `daemon-loader-*` modules (15 total, 7 active) are Karaf OSGi launchers from the pre-Spring-Boot era. They are dead code — Karaf has been eliminated from the production runtime. All daemons launch via `java -cp` using Spring Boot `Main-Class`.

### Current Dependency Landscape

**14 active reactor modules** depend on `opennms-services`:

| Category | Count | Modules |
|----------|-------|---------|
| Daemon boots | 3 | pollerd, collectd, eventtranslator |
| Daemon loaders | 3 | collectd, pollerd, perspectivepoller |
| Features | 2 | juniper-tca-collector, perspectivepoller |
| Integrations | 4 | dns/geoip/snmp-hw-inv/snmp-metadata provisioning adapters |
| Protocols | 2 | nsclient, xml |
| Other | 3 | opennms-asterisk, drools-correlation-engine, opennms-alarms/daemon |

Of these, **8 are test-only dependencies** (provisioning adapters, perspectivepoller, alarms/daemon, drools, protocols/xml) and **3 are compile-time** (juniper-tca-collector, asterisk, nsclient).

## Design

### Phase 1: Delete Daemon-Loaders

Delete all 15 `daemon-loader-*` directories (7 active modules + 8 empty stubs from prior deletions).

**Before deleting**, extract reusable classes into `core/daemon-common/`:

| Class | Source | Purpose |
|-------|--------|---------|
| `LocalServiceDetectorRegistry` | daemon-loader-shared | ServiceLoader discovery for detectors |
| `LocalServiceCollectorRegistry` | daemon-loader-collectd | ServiceLoader discovery for collectors |
| `LocalServiceMonitorRegistry` | daemon-loader-pollerd (full version) | ServiceLoader + explicit registration for monitors |
Package: `org.opennms.core.daemon.loader` -> `org.opennms.core.daemon.common.registry`

Note: `StandalonePollContext` (from daemon-loader-pollerd) extends `DefaultPollContext` which moves to `features/poller/impl`. To avoid a circular dependency between `core/daemon-common` and `features/poller/impl`, `StandalonePollContext` moves into `features/poller/impl/` (not daemon-common) alongside `DefaultPollContext`.

New dependencies for `core/daemon-common/pom.xml`:
- `org.opennms.features.collection:org.opennms.features.collection.api` (for ServiceCollector/Registry)
- `org.opennms.features.poller:org.opennms.features.poller.api` (for ServiceMonitor/Registry)

**Cleanup:**
- Remove all 7 active module entries from `core/pom.xml`
- Remove `opennms-daemon-*` feature definitions from `container/features/src/main/resources/features.xml`
- Remove daemon-loader references from any Karaf feature files

### Phase 2: Extract Pollerd Implementation

**New module:** `features/poller/impl/` (artifactId: `org.opennms.features.poller.impl`)

Move ~28 classes from `opennms-services/src/main/java/org/opennms/netmgt/poller/`:
- `Poller` — main daemon class
- `QueryManager`, `QueryManagerDaoImpl` — outage/node queries
- `DefaultPollContext` — poll lifecycle context
- `pollables/` package (~20 classes) — PollableNetwork, PollableNode, PollableService, etc.

Also move 3 passive status classes from `org.opennms.netmgt.passive/`:
- `PassiveStatusKeeper`, `PassiveStatusValue`, `PassiveStatusKey`

Add as child module in `features/poller/pom.xml`.

**Dependencies:**
- `org.opennms.features.poller:org.opennms.features.poller.api`
- `org.opennms:opennms-dao-api`, `org.opennms:opennms-dao`
- `org.opennms:opennms-config`, `org.opennms:opennms-model`
- `org.opennms.features.collection:org.opennms.features.collection.api`
- Spring framework, SLF4J

**Move associated tests** from `opennms-services/src/test/` into `features/poller/impl/src/test/`.

**Update consumers:**
- `core/daemon-boot-pollerd/pom.xml` — replace `opennms-services` with `org.opennms.features.poller.impl`
- `core/daemon-boot-perspectivepollerd/pom.xml` — same

### Phase 3: Extract Collectd Implementation

**New module:** `features/collection/impl/` (artifactId: `org.opennms.features.collection.impl`)

Move ~12 classes from `opennms-services/src/main/java/org/opennms/netmgt/collectd/`:
- `Collectd` — main daemon class
- `CollectableService` — scheduling wrapper
- `DefaultSnmpCollectionAgent`, `DefaultSnmpCollectionAgentFactory` — SNMP agent creation
- `DefaultResourceTypeMapper` — resource type registration
- Property extender classes

Add as child module in `features/collection/pom.xml`.

**Dependencies:**
- `org.opennms.features.collection:org.opennms.features.collection.api`
- `org.opennms.features.collection:org.opennms.features.collection.core`
- `org.opennms.features.collection:org.opennms.features.collection.snmp-collector`
- `org.opennms:opennms-dao-api`, `org.opennms:opennms-config`, `org.opennms:opennms-model`
- Spring framework, SLF4J

**Move associated tests** from `opennms-services/src/test/`.

**Update consumers:**
- `core/daemon-boot-collectd/pom.xml` — replace `opennms-services` with `org.opennms.features.collection.impl`
- `features/juniper-tca-collector/pom.xml` — replace with `org.opennms.features.collection.impl` (compile-time)

### Phase 4: Extract EventTranslator Implementation

**New module:** `features/event-translator/` (artifactId: `org.opennms.features.event-translator`)

Move 3 classes from `opennms-services/src/main/java/org/opennms/netmgt/translator/`:
- `EventTranslator` — main daemon
- `EventTranslatorMBean` — JMX interface

Top-level feature module added to `features/pom.xml`.

**Dependencies:** Event API, config, model, Spring, SLF4J.

**Update consumers:**
- `core/daemon-boot-eventtranslator/pom.xml` — replace `opennms-services` with `org.opennms.features.event-translator`

### Phase 5: Break Remaining Dependents

**Test-only dependents (8 modules)** — replace `opennms-services` dependency with correct target:

| Module | Replacement |
|--------|-------------|
| features/perspectivepoller | `features/poller/impl` (test scope) |
| integrations/dns-provisioning-adapter | Remove — actual imports from dao-api, model, events-api |
| integrations/geoip-provisioning-adapter | Remove — same |
| integrations/snmp-hw-inv-provisioning-adapter | Remove — same |
| integrations/snmp-metadata-provisioning-adapter | Remove — same |
| opennms-alarms/daemon | Remove — uses own module + events-api |
| opennms-correlation/drools-correlation-engine | Remove — correlation classes in own module |
| protocols/xml | `features/collection/impl` (test scope) |

**Compile-time dependents (3 modules):**

| Module | Replacement |
|--------|-------------|
| features/juniper-tca-collector | `features/collection/impl` |
| opennms-asterisk | `features/poller/impl` + `opennms-config` |
| protocols/nsclient | `features/collection/impl` + `features/poller/impl` |

### Phase 6: Delete opennms-services

- Remove `opennms-services/` directory entirely
- Remove `<module>opennms-services</module>` from root `pom.xml`
- Grep to confirm zero remaining references

**Dead code deleted without extraction (~50 classes):**
- `org.opennms.netmgt.scriptd` — Scriptd daemon deleted (PR #55)
- `org.opennms.netmgt.rtc` — RTCd deleted
- `org.opennms.netmgt.capsd` — Capsd deleted years ago
- `org.opennms.netmgt.snmpinterfacepoller` — dead
- `org.opennms.netmgt.provisiond` — lives in `features/provisioning/`

### Phase 7: Verification

1. `make build` — full reactor compile passes
2. All 12 daemon-boot JARs build successfully
3. `grep -r opennms-services` returns zero hits in pom.xml files
4. `./build.sh deltav` builds Docker images
5. Docker health checks pass for Pollerd, Collectd, EventTranslator

## PR Strategy

Single PR covering all phases. The extraction and deletion must be atomic — extracting without deleting creates a split-brain where classes exist in two places.

## Risks

**Split-package contamination:** `opennms-model-jakarta` and `opennms-model` (legacy) still coexist. The new `features/*/impl` modules must not transitively pull in the wrong model version. Verify POM exclusions and check that the runtime priority library pattern in Dockerfiles is not relied upon to mask a build-time mistake.

## Constraints

- No behavior changes — only move code and update dependencies
- Each daemon remains independently deployable as a fat JAR
- PR against `pbrane/delta-v` (NEVER against `OpenNMS/opennms`)
- Feature branch, not direct to develop
