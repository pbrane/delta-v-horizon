# Split commonConfigs.xml and Fix E2E Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 2 Enlinkd E2E failures (caused by monolithic commonConfigs.xml loading) and the 6 test-e2e.sh failures (caused by wrong trap port) so all 6 E2E test suites pass.

**Architecture:** Register `BeanUtils` as a Spring bean in `daemon-common` so all 12 daemon-boot apps set `BeanUtils.m_context` via `ApplicationContextAware`, preventing the legacy `ContextRegistry` → `commonConfigs.xml` fallback. Fix `test-e2e.sh` to send traps through Minion (port 11162) instead of directly to Trapd (port 1162).

**Tech Stack:** Java 17, Spring Boot 4, Shell scripting

**Spec:** `docs/superpowers/specs/2026-03-28-split-common-configs-fix-e2e-design.md`

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java` | Modify | Add `BeanUtils` bean registration |
| `opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java` | Modify | Widen catch block in `getSecureCredentialsScope()` |
| `opennms-container/delta-v/test-e2e.sh` | Modify | Change trap port, add Minion check |

---

### Task 1: Register BeanUtils Bean in daemon-common

**Files:**
- Modify: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java`

- [ ] **Step 1: Add BeanUtils import and bean method**

Add the import and bean method to `DaemonProvisioningConfiguration`:

```java
// Add import:
import org.opennms.core.spring.BeanUtils;
```

Add this bean method after the existing `serviceDetectorRegistry()` method (before the closing brace of the class):

```java
    /**
     * Bridges legacy {@link BeanUtils} static lookups to the daemon's Spring
     * Boot ApplicationContext.
     *
     * <p>{@code BeanUtils} implements {@code ApplicationContextAware}. Registering
     * it as a bean causes Spring to call {@code setApplicationContext()}, setting
     * the static {@code m_context} field. This prevents the legacy fallback path
     * through {@link org.opennms.core.spring.ContextRegistry} which would load
     * {@code applicationContext-commonConfigs.xml} and fail on missing config
     * files in per-daemon containers.</p>
     */
    @Bean
    public BeanUtils beanUtils() {
        return new BeanUtils();
    }
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /Users/david/development/src/opennms/delta-v
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common install
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java
git commit -m "fix: register BeanUtils bean in daemon-common to prevent legacy context loading

BeanUtils.m_context was never set in Spring Boot daemons because
BeanUtils (in org.opennms.core.spring) was outside the component
scan. This caused BeanUtils.getBean() to fall through to
ContextRegistry, which loaded applicationContext-commonConfigs.xml
and failed on missing config files (e.g., poller-configuration.xml
in Enlinkd's container).

Registering BeanUtils as a bean triggers ApplicationContextAware
injection, setting m_context so all lookups use the daemon's own
Spring Boot context."
```

---

### Task 2: Widen SnmpPeerFactory Catch Block

**Files:**
- Modify: `opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java:291`

- [ ] **Step 1: Change catch clause**

In `getSecureCredentialsScope()` (line 291), change:

```java
            } catch (FatalBeanException e) {
                LOG.warn("SnmpPeerFactory: Error retrieving EntityScopeProvider bean");
            }
```

to:

```java
            } catch (Exception e) {
                LOG.warn("SnmpPeerFactory: Error retrieving EntityScopeProvider bean: {}", e.getMessage());
            }
```

This catches `NoSuchBeanDefinitionException` (extends `BeansException`, not `FatalBeanException`) if a daemon's context doesn't have the requested bean.

- [ ] **Step 2: Remove unused FatalBeanException import if present**

Check if `FatalBeanException` is still used elsewhere in the file. If not, remove the import:
```java
// Remove if unused:
import org.springframework.beans.FatalBeanException;
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./compile.pl -DskipTests --projects :opennms-config install
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java
git commit -m "fix: widen SnmpPeerFactory catch to handle NoSuchBeanDefinitionException

The catch block only caught FatalBeanException, but
NoSuchBeanDefinitionException extends BeansException (different
hierarchy). With BeanUtils now using the daemon's Spring Boot
context, beans not registered in that context throw
NoSuchBeanDefinitionException instead of triggering the legacy
context chain."
```

---

### Task 3: Fix test-e2e.sh Trap Port and Add Minion Check

**Files:**
- Modify: `opennms-container/delta-v/test-e2e.sh`

- [ ] **Step 1: Change TRAP_PORT**

At line 29, change:
```bash
TRAP_PORT="1162"
```
to:
```bash
TRAP_PORT="11162"                    # Minion's mapped trap port (11162 → 1162/udp)
```

- [ ] **Step 2: Add Minion container check**

After the `REQUIRED_SERVICES` loop (after line 137 `ok "All required services running"`), add:

```bash

# Minion must be running for trap forwarding
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qw "delta-v-minion"; then
    err "Minion container is not running. Start with: docker start delta-v-minion"
fi
ok "Minion container running"
```

- [ ] **Step 3: Update trap-sending log messages**

At line 166, change:
```bash
ok "coldStart trap sent to ${TRAP_HOST}:${TRAP_PORT}"
```
to:
```bash
ok "coldStart trap sent to Minion at ${TRAP_HOST}:${TRAP_PORT}"
```

At line 162, change:
```bash
snmptrap -v 2c -c "$TRAP_COMMUNITY" "${TRAP_HOST}:${TRAP_PORT}" '' \
```
The snmptrap command itself doesn't change (it already uses the variable), but update the preceding log line. Find the log line before the snmptrap command in Phase 1 and ensure it mentions Minion. Search for other `snmptrap` invocations and update their surrounding log text similarly.

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/test-e2e.sh
git commit -m "fix: route test-e2e.sh traps through Minion (port 11162)

In Delta-V, Trapd has no exposed port. All traps go through Minion
on port 11162. The test was sending to port 1162 (direct to Trapd),
causing 6/11 test failures. Also adds Minion container health check
matching test-minion-e2e.sh pattern."
```

---

### Task 4: Rebuild and Verify

- [ ] **Step 1: Rebuild all 12 daemon-boot JARs**

```bash
cd /Users/david/development/src/opennms/delta-v
make build
```

Wait for BUILD SUCCESS.

- [ ] **Step 2: Build Docker images**

```bash
cd opennms-container/delta-v
./build.sh deltav
```

- [ ] **Step 3: Deploy and run E2E tests**

```bash
./deploy.sh down && ./deploy.sh up passive
# Wait for services to stabilize (~60s)

bash test-collectd-e2e.sh        # expect: 4/4
bash test-minion-e2e.sh          # expect: 13/13
bash test-syslog-e2e.sh          # expect: 15/15
bash test-passive-e2e.sh         # expect: 16/16
bash test-enlinkd-e2e.sh         # expect: 17/17
bash test-e2e.sh                 # expect: 11/11
```

- [ ] **Step 4: Commit any fixes discovered during verification**

If tests reveal issues, fix and commit before proceeding.
