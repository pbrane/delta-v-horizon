# Provisiond Shared Infrastructure — Design Spec

**Date:** 2026-03-16
**Status:** Approved
**Scope:** Prepare shared infrastructure in `daemon-common` for Provisiond Spring Boot 4 migration

## Problem

Three pieces of infrastructure are currently either duplicated across daemons or missing from the shared `daemon-common` module. Provisiond will need all three, and building them into the shared layer now simplifies the Provisiond migration.

## Goal

Move provisioning-related beans to `daemon-common` so they're available to all Spring Boot daemons via component scan, following the same patterns established by `KafkaEventTransportConfiguration`, `OpennmsHomeConfiguration`, and `KafkaRpcClientConfiguration`.

## Changes

### 1. New `DaemonProvisioningConfiguration` in daemon-common

A new `@Configuration` class in package `org.opennms.core.daemon.common`:

```java
@Configuration
public class DaemonProvisioningConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityScopeProvider.class)
    public EntityScopeProvider entityScopeProvider() {
        return new NoOpEntityScopeProvider();
    }

    @Bean
    @ConditionalOnMissingBean(ServiceDetectorRegistry.class)
    public ServiceDetectorRegistry serviceDetectorRegistry() {
        return new LocalServiceDetectorRegistry();
    }
}
```

**`@ConditionalOnMissingBean` rationale:** Any daemon can override these defaults by defining its own `@Bean`. When Provisiond migrates and needs real MATE support, it defines its own `EntityScopeProvider` bean and the shared NoOp steps aside automatically — no `@Primary` needed.

**`NoOpEntityScopeProvider`** — new class in `org.opennms.core.daemon.common` (not the Discovery-specific package). Returns `EmptyScope.EMPTY` for all methods. Currently duplicated in `daemon-boot-discovery` (`org.opennms.netmgt.discovery.boot`) and `daemon-loader-pollerd`. The daemon-common version becomes the single shared implementation. The `daemon-loader-pollerd` copy stays (Karaf daemon, separate classpath).

**`LocalServiceDetectorRegistry`** — uses `ServiceLoader.load(ServiceDetectorFactory.class)` to discover detector factories on the classpath. Returns empty if none are registered. Currently the `@Bean` is defined only in `DiscoveryBootConfiguration`.

### 2. Dependency additions to daemon-common POM

`LocalServiceDetectorRegistry` imports `ServiceDetectorRegistry`, `ServiceDetector`, and `ServiceDetectorFactory`. These interfaces live in `opennms-detector-registry` and `opennms-provision-api`, which are declared as `provided` scope in `daemon-loader-shared` — Maven does not resolve `provided`-scope dependencies transitively. We must add them explicitly.

**Warning:** Both `opennms-detector-registry` and `opennms-provision-api` transitively pull in `spring-dependencies` (ServiceMix Spring 4.2.x bundles), `opennms-model`, and other modules that conflict with Spring Boot 4. These must be excluded, following the same exclusion pattern already established in daemon-common for its existing `opennms-model` dependency.

| Dependency | Artifact | Purpose | Exclusions Needed |
|-----------|----------|---------|-------------------|
| MATE API | `org.opennms.core.mate:org.opennms.core.mate.api` | `EntityScopeProvider` interface, `EmptyScope` | Minimal — transitively brings `scv.api` (safe) |
| Daemon Loader Shared | `org.opennms.core:org.opennms.core.daemon-loader-shared` | `LocalServiceDetectorRegistry` class | None needed for the class itself |
| Detector Registry | `org.opennms:opennms-detector-registry` | `ServiceDetectorRegistry` interface | Exclude `core.soa` → `spring-dependencies` |
| Provision API | `org.opennms:opennms-provision-api` | `ServiceDetector`, `ServiceDetectorFactory` | Exclude `spring-dependencies`, `mina-dependencies`, `netty-dependencies`; exclude transitive `opennms-model` (already on classpath with proper exclusions) |

The implementer must verify the exclusion list by running `mvn dependency:tree` after adding each dependency and checking for any `spring-tx`, `spring-context`, or `spring-beans` artifacts from ServiceMix (version 4.2.x) that would conflict with Spring 7.

### 3. Cleanup in daemon-boot-discovery

| File | Action |
|------|--------|
| `NoOpEntityScopeProvider.java` | **Delete** — use shared version from daemon-common |
| `NoOpEntityScopeProviderTest.java` | **Delete** — move test to daemon-common |
| `DiscoveryBootConfiguration.java` | **Remove** `serviceDetectorRegistry()` and `entityScopeProvider()` beans — provided by `DaemonProvisioningConfiguration` |
| `pom.xml` | Remove direct `core/mate/api` and `core/daemon-loader-shared` deps if they become transitive via daemon-common |

### 4. Location flow documentation (no code changes)

Discovery's location handling is already well-structured. The pattern for Provisiond:

1. **Discovery** reads `location` from `discovery-configuration.xml`, groups ranges by location via `RangeChunker`, passes location to `PingSweepRequestBuilder.withLocation()` and `LocationAwareDetectorClient.detect().withLocation()`
2. **Provisiond** will extract location from `NEW_SUSPECT` event params (`EventConstants.PARM_LOCATION`, set by Discovery) or from requisition config
3. **Shared RPC infrastructure** (`KafkaRpcClientConfiguration` in daemon-common) handles the transport — location is just a routing parameter on the RPC request that tells Kafka which Minion consumer group to target

No validation of location against `MonitoringLocationDao` is performed — Discovery silently uses any string. This is acceptable for now.

## What does NOT change

- No detector factories registered (per-daemon concern, during each migration)
- No real MATE/EntityScopeProvider implementation (deferred to when Provisiond actually needs credential interpolation in import URLs — `ImportJob.interpolate()` already has try/catch fallback)
- No location validation logic
- No consolidation of `DistPollerDao`/`InterfaceToNodeCache` beans (natural companion work, but out of scope for this spec)
- Trapd, Syslogd, EventTranslator, Alarmd — no code changes. They scan `daemon-common` and get the NoOp/empty-registry beans automatically but never call them

## Testing Strategy

- All 5 daemons compile after changes: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-trapd,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-discovery -am install`
- Run existing unit tests: `./compile.pl --projects :org.opennms.core.daemon-common,:org.opennms.core.daemon-boot-discovery -am verify` to catch bean wiring failures
- `mvn dependency:tree` on daemon-common and all 5 daemon-boot modules — verify no Spring 4.2.x artifacts leak through
- Docker rebuild + startup: verify all daemons start (no new bean conflicts)
- E2E: `test-minion-e2e.sh` — validates trap pipeline still works (no regressions from new beans in daemon-common)
