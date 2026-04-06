# Configuration Migration Guide

Tracks every configuration change as Karaf infrastructure is removed from Delta-V.
Each entry maps the old config mechanism to its replacement.

See [Karaf Removal Design](superpowers/specs/2026-03-30-karaf-removal-design.md) for the overall plan.

## Phase 1: Dead Infrastructure Removal

| Removed | What it was | Impact on running daemons |
|---------|------------|--------------------------|
| `container/features/overrides.properties` | Karaf bundle version overrides | None — Spring Boot daemons use Maven-resolved versions in fat JARs |
| `container/features/features.xml` | Karaf feature definitions (bundle install order, dependencies) | None — daemon boot JARs embed all dependencies |
| `container/features/features-core.xml` | Core third-party bundle features for Karaf | None |
| `container/features/features-minion.xml` | Minion Karaf feature definitions | None — Minion is Spring Boot 4 |
| `container/features/features-sentinel.xml` | Sentinel Karaf feature definitions | None — Sentinel not deployed in Delta-V |
| `OSGI-INF/blueprint/*.xml` (~362 files) | OSGi service wiring (bean export/import) | None — Spring Boot uses `@Configuration` / `@Bean` / component scanning |
| `META-INF/spring/*.xml` | Spring-DM OSGi context files | None — Spring Boot does not use Spring-DM extender |
| `opennms-full-assembly/` | Karaf distribution packaging | None — Docker pipeline uses `build.sh deltav` |
| `opennms-bootstrap/` | Karaf bootstrap launcher (`opennms` start script) | None — daemons launch via `java -jar` |
| `opennms-install/` | Karaf-era installer (DB schema, etc.) | None — Liquibase runs at daemon startup |

## Phase 2: Bundle to Jar Packaging

_(To be filled when Phase 2 lands)_

## Phase 3: opennms-model Elimination

_(To be filled when Phase 3 lands)_

## Phase 4: opennms-config Replacement

_(To be filled per-daemon as each config is migrated)_
