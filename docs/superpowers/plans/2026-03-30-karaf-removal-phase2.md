# Karaf Removal Phase 2: Convert Bundle to Jar Packaging

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all OSGi bundle packaging and maven-bundle-plugin declarations from ~1,009 POMs, converting them to standard Maven jar packaging.

**Architecture:** Karaf has zero runtime consumers after Phase 1 (PR #84). Spring Boot fat JARs ignore OSGi manifests. This is a scripted, mechanical deletion — every `<packaging>bundle</packaging>` becomes `<packaging>jar</packaging>` (or is removed since jar is the Maven default), every maven-bundle-plugin declaration is deleted, and the root POM's pluginManagement entry is cleaned up. Three intermediate "bundle settings" parent POMs in `features/topology-map/poms/` have their plugin configs stripped. Six `osgi.bnd` files are deleted.

**Tech Stack:** Maven, Perl one-liners, Python script for multi-line XML block removal

**Branch:** `chore/karaf-removal-phase2` off latest `develop`

**Scope:**
| Item | Count |
|------|-------|
| POMs with `<packaging>bundle</packaging>` | 1,009 |
| POMs with maven-bundle-plugin references | ~955 |
| Intermediate parent POMs (bundle settings) | 3 |
| osgi.bnd files to delete | 6 |
| Anomalous jar+bundle-plugin POMs | 4 |

---

### Task 1: Create Feature Branch

**Files:** None (git operation only)

- [ ] **Step 1: Pull latest develop and create branch**

```bash
git checkout develop
git pull origin develop
git checkout -b chore/karaf-removal-phase2
```

- [ ] **Step 2: Verify clean starting state**

```bash
git status
# Should show clean working tree (untracked files are OK)
```

---

### Task 2: Convert Bundle Packaging to Jar

**Files:** ~1,009 pom.xml files across the entire source tree

- [ ] **Step 1: Convert all `<packaging>bundle</packaging>` to `<packaging>jar</packaging>`**

```bash
find . -name 'pom.xml' -not -path '*/target/*' -not -path '*/.claude/*' \
  -exec grep -l '<packaging>bundle</packaging>' {} \; \
  | xargs sed -i '' 's|<packaging>bundle</packaging>|<packaging>jar</packaging>|g'
```

- [ ] **Step 2: Verify no bundle packaging remains**

```bash
grep -rl '<packaging>bundle</packaging>' --include='pom.xml' . | grep -v target | grep -v .claude
# Expected: zero results
```

- [ ] **Step 3: Count converted POMs**

```bash
grep -rl '<packaging>jar</packaging>' --include='pom.xml' . | grep -v target | grep -v .claude | wc -l
# Expected: ~1,009 more than before (some already had jar packaging)
```

- [ ] **Step 4: Commit**

```bash
git add -A '*.pom.xml' || git add -A
git commit -m "chore: convert bundle to jar packaging in 1,009 POMs"
```

---

### Task 3: Remove maven-bundle-plugin from Child POMs

This is the trickiest step. The plugin blocks vary in size (3 lines to 30+ lines) and content. A Python script handles the multi-line XML removal reliably.

**Files:**
- Create: `tools/remove-bundle-plugin.py` (temporary script, deleted after use)
- Modify: ~470 pom.xml files across child modules

- [ ] **Step 1: Create the removal script**

Create `tools/remove-bundle-plugin.py`:

```python
#!/usr/bin/env python3
"""Remove maven-bundle-plugin blocks from Maven POM files.

Handles all variations:
- Simple plugin declarations (3 lines)
- Plugin declarations with <configuration>/<instructions> blocks (10-30+ lines)
- Plugin declarations with <extensions>true</extensions>
- Preserves XML formatting and indentation
- Skips the root POM (handled separately)
- Cleans up empty <plugins></plugins> sections left behind
"""

import re
import sys
import os

def remove_bundle_plugin(content):
    """Remove <plugin>..maven-bundle-plugin..</plugin> blocks from POM content."""

    # Pattern matches a <plugin> block containing maven-bundle-plugin
    # Uses non-greedy .*? to match the smallest enclosing <plugin>...</plugin>
    # The \s* before <plugin> captures leading whitespace (indentation)
    pattern = re.compile(
        r'\n?\s*<plugin>\s*\n'
        r'(?:.*?\n)*?'  # any lines in between (non-greedy)
        r'.*?maven-bundle-plugin.*?\n'
        r'(?:.*?\n)*?'  # any lines after artifactId (non-greedy)
        r'\s*</plugin>',
        re.DOTALL
    )

    # More targeted: match plugin blocks that contain 'maven-bundle-plugin'
    # Strategy: find all <plugin>...</plugin> blocks, check if they contain the artifact
    result = content
    plugin_pattern = re.compile(
        r'(\n[ \t]*(?:<!--[^>]*-->\s*\n\s*)?<plugin>)(.*?)(</plugin>)',
        re.DOTALL
    )

    def should_remove(match):
        return 'maven-bundle-plugin' in match.group(0)

    # Find all plugin blocks and remove those containing maven-bundle-plugin
    # Work backwards to preserve positions
    matches = list(plugin_pattern.finditer(result))
    for match in reversed(matches):
        if should_remove(match):
            start = match.start()
            end = match.end()
            # Also consume trailing newline if present
            if end < len(result) and result[end] == '\n':
                end += 1
            # Also consume leading whitespace on the line
            while start > 0 and result[start - 1] in (' ', '\t'):
                start -= 1
            result = result[:start] + result[end:]

    # Clean up empty <plugins></plugins> blocks left behind
    result = re.sub(
        r'\n\s*<plugins>\s*\n\s*</plugins>',
        '',
        result
    )

    return result


def process_file(filepath, dry_run=False):
    """Process a single POM file."""
    with open(filepath, 'r') as f:
        original = f.read()

    if 'maven-bundle-plugin' not in original:
        return False

    modified = remove_bundle_plugin(original)

    if modified == original:
        return False

    if dry_run:
        print(f"  WOULD MODIFY: {filepath}")
        return True

    with open(filepath, 'w') as f:
        f.write(modified)
    print(f"  MODIFIED: {filepath}")
    return True


def main():
    dry_run = '--dry-run' in sys.argv
    root_dir = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('-') else '.'

    # Find all pom.xml files (excluding target/ and .claude/)
    count = 0
    skipped_root = False
    for dirpath, dirnames, filenames in os.walk(root_dir):
        # Skip target and .claude directories
        dirnames[:] = [d for d in dirnames if d not in ('target', '.claude')]
        for filename in filenames:
            if filename == 'pom.xml':
                filepath = os.path.join(dirpath, filename)
                # Skip the root POM (handled separately in Task 4)
                if os.path.abspath(filepath) == os.path.abspath(os.path.join(root_dir, 'pom.xml')):
                    skipped_root = True
                    continue
                if process_file(filepath, dry_run):
                    count += 1

    action = "Would modify" if dry_run else "Modified"
    print(f"\n{action} {count} POM files")
    if skipped_root:
        print("  (Skipped root pom.xml — handle separately)")


if __name__ == '__main__':
    main()
```

- [ ] **Step 2: Dry-run the script to verify scope**

```bash
python3 tools/remove-bundle-plugin.py . --dry-run 2>&1 | tail -5
# Expected: "Would modify ~470 POM files"
# (fewer than the 955 grep hits because many hits are in the root POM
#  or in <pluginManagement> which this script handles)
```

- [ ] **Step 3: Run the script for real**

```bash
python3 tools/remove-bundle-plugin.py .
```

- [ ] **Step 4: Verify no maven-bundle-plugin remains in child POMs**

```bash
grep -rl 'maven-bundle-plugin' --include='pom.xml' . | grep -v target | grep -v .claude
# Expected: ONLY the root pom.xml should remain
# (and possibly the 3 intermediate parent POMs — handled in Task 5)
```

- [ ] **Step 5: Spot-check a few modified POMs**

Verify that the plugin block was cleanly removed and no XML was corrupted.

Check a simple POM (should have no `<build>` or `<plugins>` section, or a clean one):
```bash
head -40 core/cache/pom.xml
```

Check a POM that had complex instructions:
```bash
cat core/snmp/impl-snmp4j/pom.xml | head -60
```

Check a POM that had the bundle plugin as its only plugin (empty `<plugins>` should be cleaned up):
```bash
cat opennms-dao-api/pom.xml
```

- [ ] **Step 6: Delete the temporary script**

```bash
rm tools/remove-bundle-plugin.py
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: remove maven-bundle-plugin from ~470 child POMs"
```

---

### Task 4: Remove maven-bundle-plugin from Root POM

**Files:**
- Modify: `pom.xml` (root) — lines ~530-542 (pluginManagement block), line ~1644 (property)

- [ ] **Step 1: Remove the plugin from `<pluginManagement>`**

In `pom.xml`, delete the entire plugin block (lines 530-542):

```xml
      <plugin>
        <groupId>org.apache.felix</groupId>
        <artifactId>maven-bundle-plugin</artifactId>
        <version>${maven.bundle.plugin.version}</version>
        <extensions>true</extensions>
        <configuration>
          <obrRepository>NONE</obrRepository>
          <instructions>
            <!-- Don't add Import-Service MANIFEST.MF headers, just rely on normal OSGi service resolution -->
            <_removeheaders>Import-Service</_removeheaders>
          </instructions>
        </configuration>
      </plugin>
```

- [ ] **Step 2: Remove the version property**

In `pom.xml`, delete line ~1644:

```xml
    <maven.bundle.plugin.version>5.1.9</maven.bundle.plugin.version>
```

- [ ] **Step 3: Verify no maven-bundle-plugin references remain in root POM**

```bash
grep -n 'maven-bundle-plugin\|maven\.bundle\.plugin' pom.xml
# Expected: zero results
```

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: remove maven-bundle-plugin from root POM pluginManagement"
```

---

### Task 5: Clean Up Intermediate Parent POMs

Three intermediate POM parents in `features/topology-map/poms/` exist solely for OSGi bundle configuration. Strip their bundle plugin declarations.

**Files:**
- Modify: `features/topology-map/poms/pom.xml` (shared-plugin-settings) — remove pluginManagement bundle plugin block AND the Karaf shell dependency
- Modify: `features/topology-map/poms/compiled/pom.xml` (compiled-bundle-settings) — remove entire `<plugins>` section
- Modify: `features/topology-map/poms/wrappers/pom.xml` (wrapper-bundle-settings) — remove entire `<plugins>` section

- [ ] **Step 1: Clean up `features/topology-map/poms/pom.xml`**

Remove the `<pluginManagement>` block (lines 24-33) containing the maven-bundle-plugin.

Also remove the Karaf dependency management block (lines 36-44):
```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.apache.karaf.shell</groupId>
        <artifactId>org.apache.karaf.shell.core</artifactId>
        <version>${karafVersion}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
```

The POM should retain: parent reference, GAV, name, packaging, modules.

- [ ] **Step 2: Clean up `features/topology-map/poms/compiled/pom.xml`**

Remove the entire `<build>` block (lines 18-62). The resources entry is Maven's default, and the plugin block is all OSGi.

The POM should retain: parent reference, GAV, name, packaging.

- [ ] **Step 3: Clean up `features/topology-map/poms/wrappers/pom.xml`**

Remove the entire `<build>` block (lines 26-51). The plugin block is all OSGi.

The POM should retain: parent reference, GAV, name, packaging, description.

- [ ] **Step 4: Verify no maven-bundle-plugin references remain anywhere**

```bash
grep -rl 'maven-bundle-plugin' --include='pom.xml' . | grep -v target | grep -v .claude
# Expected: zero results
```

- [ ] **Step 5: Commit**

```bash
git add features/topology-map/poms/
git commit -m "chore: strip bundle plugin from topology-map parent POMs"
```

---

### Task 6: Delete osgi.bnd Files

Six `osgi.bnd` files contain bnd directives (Embed-Dependency, Import-Package, OnmsAutoExportServices, etc.) that were consumed by the bundle plugin via `<_include>-osgi.bnd</_include>`. With the plugin removed, these are dead.

**Files to delete:**
- `features/topology-map/org.opennms.features.topology.app/osgi.bnd`
- `features/topology-map/plugins/org.opennms.features.topology.plugins.browsers/osgi.bnd`
- `features/topology-map/plugins/org.opennms.features.topology.plugins.layout/osgi.bnd`
- `features/topology-map/plugins/org.opennms.features.topology.plugins.topo.application/osgi.bnd`
- `features/topology-map/plugins/org.opennms.features.topology.plugins.topo.bsm/osgi.bnd`
- `features/topology-map/plugins/org.opennms.features.topology.plugins.topo.pathoutage/osgi.bnd`

- [ ] **Step 1: Delete all osgi.bnd files**

```bash
find . -name 'osgi.bnd' -not -path '*/target/*' -not -path '*/.claude/*' -exec rm -v {} +
```

- [ ] **Step 2: Verify deletion**

```bash
find . -name 'osgi.bnd' -not -path '*/target/*' -not -path '*/.claude/*'
# Expected: zero results
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: delete osgi.bnd files — no longer consumed by bundle plugin"
```

---

### Task 7: Remove Redundant `<packaging>jar</packaging>` Lines

Since `jar` is Maven's default packaging, the explicit `<packaging>jar</packaging>` line is redundant in every POM that we just converted from `bundle`. Removing it reduces noise. POMs that were already `jar` before this PR should be left alone (they were explicit for a reason in the original codebase — don't change what we didn't break).

**Files:** ~1,009 pom.xml files that were converted in Task 2

- [ ] **Step 1: Identify only the POMs we changed in this PR**

The POMs converted in Task 2 are exactly those that now have `<packaging>jar</packaging>` and were changed in this branch. We can safely remove the packaging line from all POMs that have it, since:
- POMs that previously had `<packaging>bundle</packaging>` now have the redundant `<packaging>jar</packaging>`
- POMs that already had `<packaging>jar</packaging>` are also fine to strip (jar is the default)
- POMs with other packaging types (`pom`, `war`, `maven-plugin`) are unaffected

```bash
find . -name 'pom.xml' -not -path '*/target/*' -not -path '*/.claude/*' \
  -exec grep -l '<packaging>jar</packaging>' {} \; \
  | xargs sed -i '' '/<packaging>jar<\/packaging>/d'
```

- [ ] **Step 2: Verify no jar packaging lines remain**

```bash
grep -rl '<packaging>jar</packaging>' --include='pom.xml' . | grep -v target | grep -v .claude
# Expected: zero results
```

- [ ] **Step 3: Verify non-jar packaging types are untouched**

```bash
grep -rn '<packaging>pom</packaging>' --include='pom.xml' . | grep -v target | grep -v .claude | wc -l
# Expected: same count as before (these should be untouched)

grep -rn '<packaging>war</packaging>' --include='pom.xml' . | grep -v target | grep -v .claude | wc -l
# Expected: same count as before
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: remove redundant <packaging>jar</packaging> — jar is Maven default"
```

---

### Task 8: Verify Compilation

- [ ] **Step 1: Full compile**

```bash
make build
```

This must succeed with zero errors. If a module fails because it relied on the bundle plugin to generate something non-OSGi (unlikely but possible), fix it in this task before proceeding.

**Common failure modes and fixes:**
- **"Unknown packaging: bundle"** — a POM was missed. Find and convert it.
- **Missing class from an OSGi import** — this would indicate a compile dependency was pulled in via the bundle plugin's `Embed-Dependency`. Check `core/snmp/impl-snmp4j/pom.xml` which used `Embed-Dependency: snmp4j*`. If the snmp4j dependency is only in the bundle instructions and not in `<dependencies>`, it needs to be added as an explicit Maven dependency.

- [ ] **Step 2: Check for snmp4j embed edge case**

The `core/snmp/impl-snmp4j/pom.xml` used `Embed-Dependency: snmp4j*` in its bundle plugin instructions. Verify snmp4j is also declared as a regular Maven dependency:

```bash
grep -A2 'snmp4j' core/snmp/impl-snmp4j/pom.xml | head -10
```

If it's only in the bundle instructions (now deleted), add it as a `<dependency>`.

- [ ] **Step 3: Commit any fixes**

If compilation required fixes:
```bash
git add -A
git commit -m "fix: add explicit dependencies previously embedded via bundle plugin"
```

---

### Task 9: Verify Assembly and Deployment

- [ ] **Step 1: Build all 12 daemon boot JARs**

```bash
build.sh deltav
```

All 12 JARs must be produced.

- [ ] **Step 2: Deploy and health check**

```bash
deploy.sh up full
```

All 12 containers must start and pass `/actuator/health`.

- [ ] **Step 3: Run E2E tests**

Run the standard 6 suites:
```bash
# trapd, alarmd, pollerd, collectd, enlinkd, provisiond
```

All must pass.

- [ ] **Step 4: Dependency tree diff (optional but recommended)**

Before this change (on develop) and after (on this branch), compare:
```bash
# On develop, capture:
# mvn dependency:tree -pl :alarmd-boot,:collectd-boot,...  > /tmp/deps-before.txt

# On this branch:
# mvn dependency:tree -pl :alarmd-boot,:collectd-boot,...  > /tmp/deps-after.txt

# diff /tmp/deps-before.txt /tmp/deps-after.txt
# Expected: identical — this change should not alter the dependency graph
```

---

### Task 10: Create PR

- [ ] **Step 1: Push branch**

```bash
git push -u origin chore/karaf-removal-phase2
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --repo pbrane/delta-v --base develop \
  --title "chore: convert bundle to jar packaging — Karaf removal Phase 2" \
  --body "$(cat <<'EOF'
## Summary

- Convert 1,009 POMs from `<packaging>bundle</packaging>` to standard jar packaging (Maven default)
- Remove maven-bundle-plugin from ~470 child POMs and root POM pluginManagement
- Strip bundle plugin from 3 intermediate topology-map parent POMs
- Delete 6 `osgi.bnd` files no longer consumed by any plugin
- Remove `<maven.bundle.plugin.version>` property from root POM

Phase 2 of the Karaf removal plan. Design spec: `docs/superpowers/specs/2026-03-30-karaf-removal-design.md`

## Context

Karaf has zero runtime consumers after Phase 1 (PR #84). Spring Boot fat JARs ignore OSGi manifests. The maven-bundle-plugin generates MANIFEST.MF entries that no runtime reads.

## Verification

- [ ] `make build` succeeds
- [ ] `build.sh deltav` produces all 12 daemon boot JARs
- [ ] `deploy.sh up full` — all 12 containers start, pass `/actuator/health`
- [ ] E2E tests pass (trapd, alarmd, pollerd, collectd, enlinkd, provisiond)
- [ ] `mvn dependency:tree` identical before/after on all daemon-boot modules

## Test plan

- Full compilation verifies no module depended on bundle packaging for compilation
- Assembly verifies fat JARs are produced identically
- E2E tests verify no runtime dependency was silently provided by the bundle plugin
- Dependency tree diff confirms zero behavioral change
EOF
)"
```
