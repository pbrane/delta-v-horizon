# Next Session: Spring Boot 4 Entity Migration (opennms-model-jakarta)

## Context

PR #27 (`feature/spring-boot-4-alarmd-migration`) established the Spring Boot 4.0.3 infrastructure for Alarmd. Spring Boot starts and reaches Hibernate 7 entity scanning, but fails because `opennms-model` uses Hibernate 3.6-era annotations incompatible with Hibernate 7.

The v1.0.2 release (master branch) proved we could break each daemon into its own Karaf container. Now we're forking from old OpenNMS entirely — the goal is **super lightweight Spring Boot 4.0.3 fat JAR containers** for every daemon. No more Karaf, no more OSGi, no more ServiceMix Spring 4.2.x.

## The Blocker

`opennms-model` entities use:
- `javax.persistence` annotations (need `jakarta.persistence`)
- Hibernate 3.6 `@ParamDef(type = "string")` (Hibernate 7 changed `type` from `String` to `Class<?>`)
- Hibernate 3.6 `@TypeDef` / `@Type` annotations (removed in Hibernate 6+, replaced with `@JavaType` / `@JdbcType`)
- Old Hibernate Criteria API references

## What Needs to Happen

1. **Create `opennms-model-jakarta` module** — fork `opennms-model` source, run OpenRewrite recipes:
   - `org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta` (javax→jakarta)
   - `org.openrewrite.java.migrate.hibernate.MigrateToHibernate61` or `MigrateToHibernate63` (Hibernate annotation updates)
   - Manual fixups for custom Hibernate types (`OnmsSeverityUserType`, etc.)

2. **Create `opennms-dao-jakarta` module** — fork `opennms-dao`, update to extend `AbstractDaoJpa` instead of `AbstractDaoHibernate`. Key class: `AlarmDaoJpa extends AbstractDaoJpa<OnmsAlarm, Integer> implements AlarmDao`

3. **Update `daemon-boot-alarmd`** to depend on `-jakarta` variants

4. **E2E test** — Spring Boot Alarmd processes events and creates/clears alarms in PostgreSQL

## Key Files

- Worktree: `.worktrees/spring-boot-alarmd` (branch `feature/spring-boot-4-alarmd-migration`)
- daemon-common: `core/daemon-common/` (DaemonSmartLifecycle, KafkaEventTransportConfiguration, AbstractDaoJpa, etc.)
- daemon-boot-alarmd: `core/daemon-boot-alarmd/` (AlarmdApplication, AlarmdConfiguration, application.yml)
- Plan: `docs/superpowers/plans/2026-03-14-spring-boot-4-alarmd-migration.md`
- Design spec: `docs/superpowers/specs/2026-03-14-spring-boot-4-migration-design.md`

## Dependency Conflict Cheatsheet

Parent POM `<dependencyManagement>` always wins over child BOM imports. Each Spring Boot 4 module needs these explicit pins in `<dependencyManagement>`:
```xml
logback-classic/core: 1.5.32
slf4j-api: 2.0.17
jboss-logging: 3.6.1.Final
jakarta.xml.bind-api: 4.0.2
JUnit jupiter: 5.12.2
JUnit platform: 1.12.2
```
And these exclusions on OpenNMS dependencies:
- ALL `org.apache.servicemix.bundles:org.apache.servicemix.bundles.spring-*` (16 JARs)
- `org.hibernate:hibernate-core` (old 3.6)
- `org.hibernate.javax.persistence:hibernate-jpa-2.0-api`
- `org.slf4j:slf4j-api` (old 1.7.x), `log4j-over-slf4j`, `jcl-over-slf4j`
- `org.ops4j.pax.logging:pax-logging-*`

## Approach Suggestion

Consider using OpenRewrite as a Maven plugin in the `-jakarta` modules rather than manually editing. The plugin can transform source on `mvn compile` so the `-jakarta` modules stay in sync with their javax originals via a source-copy + transform build step.

Alternatively, just fork and manually migrate — `opennms-model` doesn't change often, and a clean manual migration gives full control over Hibernate type mappings.

## Start Command

```
Please continue the Spring Boot 4 migration. PR #27 established the infrastructure. The next step is creating opennms-model-jakarta with Hibernate 7-compatible entity annotations, then wiring it into daemon-boot-alarmd so Alarmd fully boots and processes events. Work in the existing worktree on branch feature/spring-boot-4-alarmd-migration.
```
