# Design: Split commonConfigs.xml and Fix E2E Tests

**Date:** 2026-03-28
**Status:** Approved
**Scope:** Fix two E2E test suite failures caused by (1) monolithic config loading in per-daemon containers and (2) wrong trap port in test-e2e.sh.

## Problem

After eliminating opennms-services (PR #65), all 12 daemons run as standalone Spring Boot apps. Two E2E test suites have failures:

1. **test-enlinkd-e2e.sh** (2/17 failing) — `applicationContext-commonConfigs.xml` loads all 22 config factories regardless of which daemon runs. When Enlinkd's SNMP RPC chain triggers `SnmpPeerFactory.getSecureCredentialsScope()` → `BeanUtils.getBean("daoContext", "entityScopeProvider", ...)`, `BeanUtils.m_context` is null, causing fallback to `ContextRegistry` → loads `beanRefContext.xml` → `commonConfigs.xml` → tries to init `PollerConfigFactory` → fails (no `poller-configuration.xml` in Enlinkd's container). This breaks SNMP interface collection and LLDP link resolution.

2. **test-e2e.sh** (6/11 failing) — Sends traps to port 1162 (direct to Trapd) but Trapd has no exposed port in Delta-V. All traps must go through Minion on port 11162.

## Root Cause Analysis

### Why BeanUtils.m_context is null

`BeanUtils` (in `org.opennms.core.spring`) implements `ApplicationContextAware`. For `m_context` to be set, `BeanUtils` must be registered as a Spring bean. But:

- Daemon-boot apps scan `org.opennms.core.daemon.common`, NOT `org.opennms.core.spring`
- No daemon-boot module explicitly registers a `BeanUtils` bean
- `BeanUtils` has no `@Component` annotation

So `m_context` stays null, and every `BeanUtils.getBean()` call falls through to `ContextRegistry`, which loads the entire legacy XML context chain.

### The Legacy Context Chain

```
BeanUtils.getBean("daoContext", "entityScopeProvider", ...)
  → m_context == null
  → ContextRegistry.getInstance().getBeanFactory("daoContext")
    → scans classpath for ALL beanRefContext.xml (~20 files)
    → loads "commonContext" (opennms-config/beanRefContext.xml)
      → loads applicationContext-commonConfigs.xml
        → PollerConfigFactory.init() → FAILS (missing poller-configuration.xml)
        → CollectdConfigFactory → FAILS (missing collectd-configuration.xml)
        → etc.
```

## Solution

### Task 1: Register BeanUtils as a Spring Bean in daemon-common

Add a `BeanUtils` bean to `DaemonProvisioningConfiguration` in `core/daemon-common`:

```java
@Bean
public BeanUtils beanUtils() {
    return new BeanUtils();
}
```

Spring calls `setApplicationContext()` via `ApplicationContextAware`, setting `m_context` to the daemon's Spring Boot `ApplicationContext`. All subsequent `BeanUtils.getBean()` calls use the daemon's own context directly, bypassing `ContextRegistry` entirely.

**Bean availability for Enlinkd's path:**
- `SnmpPeerFactory.getSecureCredentialsScope()` requests `"entityScopeProvider"` (EntityScopeProvider)
- `DaemonProvisioningConfiguration` already provides `NoOpEntityScopeProvider` as `entityScopeProvider` bean
- Call succeeds; no legacy context needed

**Catch block hardening in SnmpPeerFactory:**
- `getSecureCredentialsScope()` catches `FatalBeanException`
- `NoSuchBeanDefinitionException` extends `BeansException`, NOT `FatalBeanException`
- Widen to `catch (Exception e)` for robustness against beans missing from daemon contexts

### Task 2: Fix test-e2e.sh Trap Port

- Change `TRAP_PORT="1162"` to `TRAP_PORT="11162"`
- Add Minion container health check (same pattern as test-minion-e2e.sh)
- Update log messages to reference Minion path

## Files Changed

| File | Change |
|------|--------|
| `core/daemon-common/.../DaemonProvisioningConfiguration.java` | Add `BeanUtils` bean registration |
| `opennms-config/.../SnmpPeerFactory.java` | Widen catch to `Exception` in `getSecureCredentialsScope()` |
| `opennms-container/delta-v/test-e2e.sh` | Change trap port 1162 → 11162, add Minion check |

## Verification

Rebuild all 12 daemon-boot JARs, run `build.sh deltav`, then:

```bash
bash test-collectd-e2e.sh        # expect: 4/4
bash test-minion-e2e.sh          # expect: 13/13
bash test-syslog-e2e.sh          # expect: 15/15
bash test-passive-e2e.sh         # expect: 16/16
bash test-enlinkd-e2e.sh         # expect: 17/17 (was 15/17)
bash test-e2e.sh                 # expect: 11/11 (was 5/11)
```

## Risks

1. **Other BeanUtils.getBean() callers**: Collectors/monitors that call `BeanUtils.getBean()` for beans not in the daemon's context will get `NoSuchBeanDefinitionException`. Mitigated: (a) daemon-boot modules already pre-inject dependencies, (b) the legacy chain would also fail in per-daemon containers, (c) the exception is easier to diagnose than the current hard crash.

2. **Static m_context shared across tests**: `BeanUtils.m_context` is a static field. In unit tests that create multiple Spring contexts, the last context wins. Mitigated: this is existing behavior and test isolation is not affected (tests either mock BeanUtils or set up their own contexts).
