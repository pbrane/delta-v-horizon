# Karaf Removal Phase 1: Delete Dead Infrastructure

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all Karaf/OSGi infrastructure that has zero runtime consumers, reducing codebase noise and build time before Phases 2-4 tackle the remaining legacy dependencies.

**Architecture:** Pure deletion. No behavioral changes to running daemons. Delete the Karaf container assembly, OSGi blueprint wiring files, Karaf-specific modules, and build script references. Clean up the root POM properties and managed dependencies.

**Tech Stack:** Maven POM editing, git rm, shell scripting for bulk deletions, `make build` for verification.

**Spec:** `docs/superpowers/specs/2026-03-30-karaf-removal-design.md` (Phase 1 section)

**Branch:** Create `chore/karaf-removal-phase1` from `develop`

---

### Task 1: Capture Dependency Tree Baseline

**Files:**
- Create: `/tmp/dep-tree-before.txt` (temporary, not committed)

This baseline lets us diff transitive dependencies before and after to catch anything we accidentally removed.

- [ ] **Step 1: Pull latest develop and create feature branch**

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b chore/karaf-removal-phase1 develop
```

- [ ] **Step 2: Capture dependency tree for all daemon-boot modules**

```bash
./compile.pl -DskipTests --projects \
  :org.opennms.core.daemon-boot-alarmd,\
  :org.opennms.core.daemon-boot-bsmd,\
  :org.opennms.core.daemon-boot-collectd,\
  :org.opennms.core.daemon-boot-discovery,\
  :org.opennms.core.daemon-boot-enlinkd,\
  :org.opennms.core.daemon-boot-eventtranslator,\
  :org.opennms.core.daemon-boot-perspectivepollerd,\
  :org.opennms.core.daemon-boot-pollerd,\
  :org.opennms.core.daemon-boot-provisiond,\
  :org.opennms.core.daemon-boot-syslogd,\
  :org.opennms.core.daemon-boot-telemetryd,\
  :org.opennms.core.daemon-boot-trapd \
  -am dependency:tree 2>&1 | tee /tmp/dep-tree-before.txt
```

- [ ] **Step 3: Extract Karaf/ServiceMix/Pax/Felix artifacts from baseline**

```bash
grep -E 'org\.apache\.(karaf|servicemix|felix)|org\.ops4j\.pax|org\.osgi' /tmp/dep-tree-before.txt | sort -u > /tmp/karaf-deps-before.txt
cat /tmp/karaf-deps-before.txt
```

Review the output. These are the Karaf-ecosystem artifacts currently on daemon classpaths. Most should be harmless (unused at runtime), but this is the reference list for the post-deletion diff.

---

### Task 2: Delete Container Directory

**Files:**
- Delete: `container/` (entire directory — 15 subdirectories)
- Modify: `pom.xml:98` (remove `<module>container</module>`)

- [ ] **Step 1: Remove container module from root POM**

In `pom.xml`, remove line 98:
```xml
    <module>container</module>
```

- [ ] **Step 2: Delete the container directory**

```bash
git rm -r container/
```

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: delete container/ (Karaf OSGi container infrastructure)

Removes all 15 Karaf container subdirectories: branding, bridge,
extender, features (8 feature XMLs), jaas-login-module, karaf,
noop-jetty-extension, servlet, shared, spring-extender.

No runtime consumer exists — all daemons are Spring Boot 4."
```

---

### Task 3: Delete Assembly Modules and Installer

**Files:**
- Delete: `opennms-full-assembly/`
- Delete: `opennms-base-assembly/`
- Delete: `opennms-assemblies/`
- Delete: `opennms-install/`
- Delete: `assemble.pl`

None of these are in the root POM `<modules>` list — they were invoked directly by `assemble.pl` and the Makefile `assemble` target. They exist as orphaned directories.

- [ ] **Step 1: Delete all assembly directories and assemble.pl**

```bash
git rm -r opennms-full-assembly/
git rm -r opennms-base-assembly/
git rm -r opennms-assemblies/
git rm -r opennms-install/
git rm assemble.pl
```

- [ ] **Step 2: Remove assembly references from root POM dependencyManagement**

In `pom.xml`, remove these managed dependency entries (they reference deleted modules):

Remove the `opennms-base-assembly` dependency (around line 3263):
```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-base-assembly</artifactId>
            ...
        </dependency>
```

Remove the `opennms-full-assembly` dependency (around line 3355):
```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-full-assembly</artifactId>
            ...
        </dependency>
```

Remove the `opennms-install` dependency (around line 3402):
```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-install</artifactId>
            ...
        </dependency>
```

Also remove the license-plugin `<exclude>` entries for `opennms-base-assembly` (around lines 1138-1139):
```xml
                <exclude>opennms-base-assembly/src/main/filtered/etc</exclude>
                <exclude>opennms-base-assembly/src/main/resources</exclude>
```

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: delete assembly modules, installer, and assemble.pl

Removes opennms-full-assembly, opennms-base-assembly, opennms-assemblies,
opennms-install, and assemble.pl. These built the Karaf distribution
which is no longer deployed. Docker pipeline uses build.sh deltav."
```

---

### Task 4: Delete Karaf-Specific Top-Level Modules and Smoke Test

**Files:**
- Delete: `opennms-bootstrap/`
- Delete: `opennms-jetty/`
- Delete: `opennms-config-tester/`
- Delete: `smoke-test/`
- Modify: `pom.xml:108,111,124` (remove three `<module>` entries)
- Modify: `pom.xml:2358` (remove `smoke-test` module from profile)
- Modify: `integration-tests/config/pom.xml:86` (remove `opennms-jetty` dependency)

These modules have zero consumers in daemon-boot or daemon-common POMs (verified). The `smoke-test` module is Karaf-era smoke testing that references `opennms-base-assembly` and `opennms-bootstrap` (both deleted in Task 3).

- [ ] **Step 1: Remove modules from root POM**

In `pom.xml`, remove these three lines from the `<modules>` section:
```xml
    <module>opennms-bootstrap</module>     <!-- line 108 -->
    <module>opennms-config-tester</module> <!-- line 111 -->
    <module>opennms-jetty</module>         <!-- line 124 -->
```

Also remove the `smoke-test` module entry from inside its Maven profile (around line 2358):
```xml
        <module>smoke-test</module>
```

- [ ] **Step 2: Delete the directories**

```bash
git rm -r opennms-bootstrap/
git rm -r opennms-config-tester/
git rm -r opennms-jetty/
git rm -r smoke-test/
```

- [ ] **Step 3: Clean up references in surviving modules**

Remove the `opennms-jetty` dependency from `integration-tests/config/pom.xml` (around line 86):
```xml
      <artifactId>opennms-jetty</artifactId>
```
(Remove the full `<dependency>` block containing this artifact.)

- [ ] **Step 4: Remove managed dependency entries from root POM**

Search the root `pom.xml` `<dependencyManagement>` section for entries referencing the deleted artifactIds (`opennms-bootstrap`, `opennms-config-tester`, `opennms-jetty`) and remove them.

- [ ] **Step 5: Commit**

```bash
git add pom.xml integration-tests/config/pom.xml
git commit -m "chore: delete opennms-bootstrap, opennms-jetty, opennms-config-tester, smoke-test

Karaf bootstrap launcher, Jetty integration, config validation tool,
and Karaf-era smoke tests. No daemon-boot module depends on any of
these. Cleaned opennms-jetty reference from integration-tests/config."
```

---

### Task 5: Delete Blueprint XML Files

**Files:**
- Delete: ~209 `OSGI-INF/blueprint/` directories under `src/`

- [ ] **Step 1: Delete all blueprint directories in source tree**

```bash
find . -path '*/src/*/OSGI-INF/blueprint' -type d \
  -not -path '*/target/*' \
  -not -path '*/.claude/*' \
  -not -path '*/.git/*' \
  -exec rm -rf {} + 2>/dev/null

# Remove empty OSGI-INF parents
find . -path '*/src/*/OSGI-INF' -type d -empty \
  -not -path '*/target/*' \
  -not -path '*/.claude/*' \
  -not -path '*/.git/*' \
  -exec rmdir {} + 2>/dev/null
```

- [ ] **Step 2: Stage the deletions**

```bash
git add -A
```

Review with `git diff --cached --stat` to confirm only blueprint XMLs and empty OSGI-INF directories are staged. The count should be approximately 209 directories with their XML contents.

- [ ] **Step 3: Commit**

```bash
git commit -m "chore: delete ~209 OSGI-INF/blueprint directories

OSGi service wiring definitions. No Spring Boot daemon loads these.
Service registration and dependency injection handled by Spring
@Configuration, @Bean, and component scanning."
```

---

### Task 6: Clean Up Build Scripts

**Files:**
- Modify: `Makefile:13-14,47,117-121`
- Verify: `compile.pl` (check for assembly references)
- Verify: `runtests.sh` (check for assembly path references)

- [ ] **Step 1: Remove assemble target from Makefile**

Remove the help lines (around lines 13-14):
```
#   make assemble                       Assemble distribution (default profile)
#   make assemble PROFILE=dir|full|fulldir
```

Remove `assemble` from the `.PHONY` line (line 47):
```makefile
.PHONY: help build module dependents test-class test ui assemble clean
```
Change to:
```makefile
.PHONY: help build module dependents test-class test ui clean
```

Remove the `assemble` target (lines 117-121):
```makefile
assemble: ## Assemble the distribution; set PROFILE=dir|full|fulldir (default: dir)
	cd opennms-full-assembly && \
	$(CURDIR)/mvnw $(MAVEN_FLAGS) $(COMMON) \
	  -Dbuild.profile=$(PROFILE) \
	  install
```

Also remove the `PROFILE` variable if it's only used by the assemble target:
```makefile
PROFILE   ?= dir
```

- [ ] **Step 2: Check compile.pl for assembly references**

```bash
grep -n 'assemble\|opennms-full-assembly\|karaf' compile.pl
```

Remove any references found. If `compile.pl` has a conditional path that invokes the assembly, remove it.

- [ ] **Step 3: Check runtests.sh for assembly path references**

```bash
grep -n 'assemble\|opennms-full-assembly\|karaf' runtests.sh
```

Remove any references found.

- [ ] **Step 4: Commit**

```bash
git add Makefile compile.pl runtests.sh
git commit -m "chore: remove Karaf assembly targets from build scripts

Remove assemble target from Makefile, clean assembly references
from compile.pl and runtests.sh."
```

---

### Task 7: Clean Up Root POM Properties

**Files:**
- Modify: `pom.xml` (properties section, lines ~1640-1930)

- [ ] **Step 1: Remove Karaf/OSGi version properties**

Remove these properties from the root `pom.xml` `<properties>` section. They are all tied to the embedded Karaf container which no longer exists.

Properties to remove (verify no remaining references before each deletion with `grep -r "propertyName" --include="pom.xml" .`):

```
maven.karaf.plugin.version (line ~1640)
karaf.servicemix.specs.version (line ~1715)
karafVersion (line ~1867)
karafSshdVersion (line ~1973)
opennms.osgi.version (line ~1670)
osgiVersion (line ~1919)
osgiAnnotationVersion (line ~1920)
osgiCompendiumVersion (line ~1921)
osgiEnterpriseVersion (line ~1922)
osgiServiceJdbcVersion (line ~1923)
osgiJaxRsVersion (line ~1926)
osgiUtilFunctionVersion (line ~1881)
osgiUtilPromiseVersion (line ~1882)
eclipseOsgiVersion (line ~1869)
felixCmJsonVersion (line ~1870)
felixConfigadminVersion (line ~1871)
felixConfigadminPluginInterpolationVersion (line ~1872)
felixCoordinatorVersion (line ~1873)
felixConfiguratorVersion (line ~1874)
felixConverterVersion (line ~1875)
felixFileinstallVersion (line ~1876)
felixMetatypeVersion (line ~1877)
paxLoggingVersion (line ~1883)
paxSwissboxVersion (line ~1884)
paxSwissboxOptionalJclVersion (line ~1885)
paxUrlAetherVersion (line ~1886)
paxWebVersion (line ~1887)
paxCdiVersion (line ~1906)
paxExamVersion (line ~1907)
```

**Batch-verify all properties** before removing. Use this loop to check which ones still have active consumers:

```bash
for prop in maven.karaf.plugin.version karaf.servicemix.specs.version \
  karafVersion karafSshdVersion opennms.osgi.version osgiVersion \
  osgiAnnotationVersion osgiCompendiumVersion osgiEnterpriseVersion \
  osgiServiceJdbcVersion osgiJaxRsVersion osgiUtilFunctionVersion \
  osgiUtilPromiseVersion eclipseOsgiVersion felixCmJsonVersion \
  felixConfigadminVersion felixConfigadminPluginInterpolationVersion \
  felixCoordinatorVersion felixConfiguratorVersion felixConverterVersion \
  felixFileinstallVersion felixMetatypeVersion paxLoggingVersion \
  paxSwissboxVersion paxSwissboxOptionalJclVersion paxUrlAetherVersion \
  paxWebVersion paxCdiVersion paxExamVersion; do
  count=$(grep -r "\${${prop}}" --include="pom.xml" . | grep -v target/ | grep -v '.claude/' | grep -v 'pom.xml:.*<.*\.version>' | wc -l | tr -d ' ')
  echo "$prop: $count references"
done
```

Properties with 0 references outside their own definition are safe to remove. If a property IS still referenced by a non-deleted module, leave it for now and note it for Phase 2.

- [ ] **Step 2: Remove OSGi managed dependencies**

Remove these entries from the `<dependencyManagement>` section:

```xml
<!-- around lines 4810-4828 -->
<dependency>
    <groupId>org.osgi</groupId>
    <artifactId>osgi.core</artifactId>
    ...
</dependency>
<dependency>
    <groupId>org.osgi</groupId>
    <artifactId>osgi.cmpn</artifactId>
    ...
</dependency>
<dependency>
    <groupId>org.osgi</groupId>
    <artifactId>osgi.annotation</artifactId>
    ...
</dependency>
<dependency>
    <groupId>org.osgi</groupId>
    <artifactId>osgi.enterprise</artifactId>
    ...
</dependency>

<!-- around line 4882-4885 -->
<dependency>
    <groupId>org.apache.karaf.shell</groupId>
    <artifactId>org.apache.karaf.shell.core</artifactId>
    ...
</dependency>
```

Same rule: verify no remaining POM references before removal. If a `features/` or `opennms-*` module still imports an OSGi artifact, leave the managed dependency and note it.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: remove Karaf/OSGi/Felix/Pax properties and managed deps

Remove ~30 version properties for Karaf 4.4.9, Felix, Pax, and OSGi.
Remove 5 OSGi managed dependency entries. Properties still referenced
by non-deleted modules are retained for Phase 2."
```

---

### Task 8: Compile Verification

- [ ] **Step 1: Full compile**

```bash
make build
```

Expected: BUILD SUCCESS. If it fails, the error will indicate a module that depended on a deleted module. Fix by replacing the dependency (not by restoring the deleted module).

- [ ] **Step 2: Build all 12 daemon boot JARs**

```bash
./compile.pl -DskipTests --projects \
  :org.opennms.core.daemon-boot-alarmd,\
  :org.opennms.core.daemon-boot-bsmd,\
  :org.opennms.core.daemon-boot-collectd,\
  :org.opennms.core.daemon-boot-discovery,\
  :org.opennms.core.daemon-boot-enlinkd,\
  :org.opennms.core.daemon-boot-eventtranslator,\
  :org.opennms.core.daemon-boot-perspectivepollerd,\
  :org.opennms.core.daemon-boot-pollerd,\
  :org.opennms.core.daemon-boot-provisiond,\
  :org.opennms.core.daemon-boot-syslogd,\
  :org.opennms.core.daemon-boot-telemetryd,\
  :org.opennms.core.daemon-boot-trapd \
  -am install
```

Expected: BUILD SUCCESS for all 12 modules.

---

### Task 9: Transitive Dependency Audit

- [ ] **Step 1: Capture post-deletion dependency tree**

```bash
./compile.pl -DskipTests --projects \
  :org.opennms.core.daemon-boot-alarmd,\
  :org.opennms.core.daemon-boot-bsmd,\
  :org.opennms.core.daemon-boot-collectd,\
  :org.opennms.core.daemon-boot-discovery,\
  :org.opennms.core.daemon-boot-enlinkd,\
  :org.opennms.core.daemon-boot-eventtranslator,\
  :org.opennms.core.daemon-boot-perspectivepollerd,\
  :org.opennms.core.daemon-boot-pollerd,\
  :org.opennms.core.daemon-boot-provisiond,\
  :org.opennms.core.daemon-boot-syslogd,\
  :org.opennms.core.daemon-boot-telemetryd,\
  :org.opennms.core.daemon-boot-trapd \
  -am dependency:tree 2>&1 | tee /tmp/dep-tree-after.txt
```

- [ ] **Step 2: Diff Karaf/ServiceMix/Pax/Felix artifacts**

```bash
grep -E 'org\.apache\.(karaf|servicemix|felix)|org\.ops4j\.pax|org\.osgi' /tmp/dep-tree-after.txt | sort -u > /tmp/karaf-deps-after.txt
diff /tmp/karaf-deps-before.txt /tmp/karaf-deps-after.txt
```

Any artifact that disappeared from the "after" list was a transitive dependency of something we deleted. Verify these are truly unused at runtime (they should be — container modules don't provide runtime classes to daemons).

- [ ] **Step 3: If issues found, fix and recommit**

If any daemon-boot module lost a needed transitive dependency, add it as an explicit dependency in that module's POM (replace, don't keep the deleted module).

---

### Task 10: Docker Deploy and E2E Verification

- [ ] **Step 1: Full reactor build**

Since Phase 1 is a massive reactor change (deleting many modules), a root-level `mvn install` is safer than targeted `-am` builds. It ensures the parent POM and shared dependencies are correctly installed in the local repository after the structural changes.

```bash
make build
```

- [ ] **Step 2: Build Docker images**

```bash
./opennms-container/delta-v/build.sh deltav
```

Expected: All 12 daemon images built successfully.

- [ ] **Step 3: Deploy and check health**

```bash
cd opennms-container/delta-v
./deploy.sh up full
```

Wait for all containers to start. Verify each daemon passes health check:

```bash
for svc in alarmd bsmd collectd discovery enlinkd eventtranslator perspectivepollerd pollerd provisiond syslogd telemetryd trapd; do
  echo -n "$svc: "
  docker compose exec -T "$svc" curl -sf http://localhost:8080/actuator/health 2>/dev/null | head -c 50 || echo "UNHEALTHY"
done
```

Expected: All 12 daemons report healthy.

- [ ] **Step 4: Run E2E tests**

```bash
cd opennms-container/delta-v
./test-trapd-e2e.sh
./test-alarmd-e2e.sh
./test-pollerd-e2e.sh
./test-collectd-e2e.sh
./test-enlinkd-e2e.sh
./test-provisiond-e2e.sh
```

Expected: All 6 E2E tests pass. If any test fails due to a missing class at runtime, that indicates a transitive dependency from a deleted module was needed. Replace it (add explicit dependency), don't restore the deleted module.

- [ ] **Step 5: Final commit if any fixes were needed**

If Steps 3-4 required dependency fixes, commit those fixes:

```bash
git add -A
git commit -m "fix: replace transitive dependencies from deleted Karaf modules"
```

- [ ] **Step 6: Push and create PR**

```bash
git push -u origin chore/karaf-removal-phase1
gh pr create --repo pbrane/delta-v --base develop \
  --title "chore: Karaf removal Phase 1 — delete dead infrastructure" \
  --body "$(cat <<'EOF'
## Summary

- Delete `container/` directory (Karaf OSGi container, 15 subdirectories, feature XMLs)
- Delete assembly modules (`opennms-full-assembly`, `opennms-base-assembly`, `opennms-assemblies`, `opennms-install`)
- Delete Karaf-specific modules (`opennms-bootstrap`, `opennms-jetty`, `opennms-config-tester`)
- Delete ~209 `OSGI-INF/blueprint/` directories (OSGi service wiring)
- Remove `assemble` target from Makefile, delete `assemble.pl`
- Remove ~30 Karaf/OSGi/Felix/Pax version properties from root POM
- Remove OSGi managed dependencies from root POM

## Test plan

- [ ] `make build` — full compile succeeds
- [ ] All 12 daemon boot JARs compile
- [ ] Dependency tree diff shows no unexpected lost artifacts
- [ ] `build.sh deltav` — Docker images build
- [ ] `deploy.sh up full` — all 12 daemons healthy
- [ ] All 6 E2E tests pass (trapd, alarmd, pollerd, collectd, enlinkd, provisiond)
EOF
)"
```
