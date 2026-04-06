# Design: Real MATE EntityScopeProvider for Spring Boot Daemons

**Date:** 2026-03-28
**Status:** Approved
**Scope:** Replace `NoOpEntityScopeProvider` with a real `DaemonEntityScopeProvider` in daemons that have database access, enabling MATE metadata interpolation (`${node:label}`, `${scv:alias:password}`, `${asset:region}`, etc.).

## Problem

All 12 Spring Boot daemons currently use `NoOpEntityScopeProvider`, which returns empty scopes for every entity type. This means:

- `${scv:alias:password}` in SNMP community strings resolves to empty
- `${node:label}` in threshold definitions resolves to empty
- `${asset:region}` in poller configurations resolves to empty
- Requisition URL interpolation in Provisiond ignores SCV credentials

Daemons with database access (Provisiond, Pollerd, Collectd, Enlinkd) should use a real implementation that resolves these expressions against node/interface/service metadata and the Secure Credentials Vault.

## Solution

### DaemonEntityScopeProvider

A new class in `core/daemon-common` that implements `EntityScopeProvider` with constructor injection. The implementation replicates the upstream `EntityScopeProviderImpl` scope composition logic using the same scope classes from `core/mate/api`:

- `SecureCredentialsVaultScope` — resolves `${scv:alias:attribute}`
- `EnvironmentScope` — resolves `${env:VAR}`
- `ObjectScope` — maps entity attributes to context keys (`node:label`, `interface:hostname`, `asset:region`, etc.)
- `MapScope` — maps OnmsMetaData collections to context keys
- `FallbackScope` — chains scopes with priority ordering

**Constructor parameters (all required):**
```java
public DaemonEntityScopeProvider(
    NodeDao nodeDao,
    IpInterfaceDao ipInterfaceDao,
    SnmpInterfaceDao snmpInterfaceDao,
    MonitoredServiceDao monitoredServiceDao,
    SessionUtils sessionUtils,
    SecureCredentialsVault scv
)
```

**Method implementations** match the upstream `EntityScopeProviderImpl` exactly — same `ObjectScope` mappings for node attributes (17 fields), asset record attributes (48 fields), IP interface attributes (4 fields + 4 SNMP fields), SNMP interface attributes (4 fields), and service attributes (1 field). All DAO calls wrapped in `sessionUtils.withReadOnlyTransaction()`.

### Conditional Bean Wiring

In `DaemonProvisioningConfiguration`:

```java
@Bean
@ConditionalOnBean(NodeDao.class)
public EntityScopeProvider entityScopeProvider(
        NodeDao nodeDao,
        IpInterfaceDao ipInterfaceDao,
        SnmpInterfaceDao snmpInterfaceDao,
        MonitoredServiceDao monitoredServiceDao,
        SessionUtils sessionUtils,
        SecureCredentialsVault scv) {
    return new DaemonEntityScopeProvider(nodeDao, ipInterfaceDao,
            snmpInterfaceDao, monitoredServiceDao, sessionUtils, scv);
}

@Bean
@ConditionalOnMissingBean(EntityScopeProvider.class)
public EntityScopeProvider noOpEntityScopeProvider() {
    return new NoOpEntityScopeProvider();
}
```

When `NodeDao` is present (Provisiond, Pollerd, Collectd, Enlinkd), Spring creates the real provider. When absent (Trapd, Alarmd, Syslogd, etc.), the no-op fallback takes effect.

### SecureCredentialsVault Bean

Add a `JCEKSSecureCredentialsVault` bean to `DaemonProvisioningConfiguration`, conditional on the keystore file existing:

```java
@Bean
@ConditionalOnMissingBean(SecureCredentialsVault.class)
public SecureCredentialsVault secureCredentialsVault(
        @Value("${opennms.home:/opt/deltav}") String opennmsHome) {
    return new JCEKSSecureCredentialsVault(opennmsHome + "/etc/scv.jce", "notReallyASecret");
}
```

The password `"notReallyASecret"` is the upstream default keystore password (it's in the upstream source — the real security is filesystem ACLs on the keystore file).

### SessionUtils in daemon-common

`SessionUtils` is currently defined per-daemon (Provisiond, Pollerd each have their own). For daemons that don't define it yet, add a default `SessionUtils` bean to `DaemonProvisioningConfiguration` with `@ConditionalOnMissingBean`. This ensures the `DaemonEntityScopeProvider` can be wired in any daemon that has DAOs + a transaction manager.

## Which Daemons Get What

| Daemon | Has NodeDao? | Has SessionUtils? | Gets |
|--------|-------------|-------------------|------|
| Provisiond | Yes | Yes (own) | DaemonEntityScopeProvider |
| Pollerd | Yes | Yes (own) | DaemonEntityScopeProvider |
| Collectd | Yes | Needs default | DaemonEntityScopeProvider |
| Enlinkd | Yes | Needs default | DaemonEntityScopeProvider |
| PerspectivePollerd | Yes | Needs default | DaemonEntityScopeProvider |
| Discovery | Partial (no MonitoredServiceDao) | No | NoOpEntityScopeProvider |
| Trapd | No | No | NoOpEntityScopeProvider |
| Alarmd | No | No | NoOpEntityScopeProvider |
| Syslogd | No | No | NoOpEntityScopeProvider |
| BSMd | No | No | NoOpEntityScopeProvider |
| Telemetryd | No | No | NoOpEntityScopeProvider |
| EventTranslator | No | No | NoOpEntityScopeProvider |

## Dependencies

`daemon-common` pom.xml needs new dependencies:
- `core/mate/api` — already present (for `EntityScopeProvider` interface)
- `features/scv/jceks-impl` — for `JCEKSSecureCredentialsVault`
- `core/mate/model` may NOT be needed — we're writing our own impl using only `core/mate/api` scope classes

Model classes needed from existing dependencies:
- `OnmsNode`, `OnmsIpInterface`, `OnmsSnmpInterface`, `OnmsMonitoredService`, `OnmsAssetRecord`, `OnmsGeolocation`, `OnmsMetaData` — from `opennms-model`
- `GeoHash` — from `ch.hsr.geohash` (transitively available via `opennms-model`)

## Files Changed

| File | Action | Purpose |
|------|--------|---------|
| `core/daemon-common/.../DaemonEntityScopeProvider.java` | Create | Real EntityScopeProvider with constructor injection |
| `core/daemon-common/.../DaemonProvisioningConfiguration.java` | Modify | Conditional bean wiring for real vs. no-op provider, SCV bean, default SessionUtils |
| `core/daemon-common/pom.xml` | Modify | Add `scv/jceks-impl` dependency |
| `core/daemon-common/.../DaemonEntityScopeProviderTest.java` | Create | Unit test with mocked DAOs |

## Testing

1. **Unit test** `DaemonEntityScopeProvider` with mocked DAOs — verify each method returns correct scope composition:
   - `getScopeForScv()` resolves `${scv:alias:password}` from mocked SCV
   - `getScopeForNode(nodeId)` returns node attributes, asset attributes, metadata
   - `getScopeForInterface(nodeId, ip)` returns IP interface attributes + SNMP attributes
   - `getScopeForService(nodeId, ip, svc)` returns service metadata + name
   - Null/missing entity returns `EmptyScope.EMPTY`

2. **Conditional wiring test** — verify `@ConditionalOnBean(NodeDao.class)` selects real provider when DAOs are present, no-op when absent.

## Verification

After implementation, rebuild all 12 daemon JARs and run all 6 E2E test suites to confirm no regressions. Verify via daemon logs that `DaemonEntityScopeProvider` is created (not `NoOpEntityScopeProvider`) in Provisiond, Pollerd, Collectd, and Enlinkd.
