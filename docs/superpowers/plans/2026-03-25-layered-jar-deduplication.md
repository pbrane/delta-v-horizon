# Layered JAR Deduplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 4.85GB `daemon-deltav-springboot` Docker image with a shared `daemon-base` image (~240MB) plus 12 per-daemon images (~3-60MB each), using Spring Boot layered JAR extraction to deduplicate shared dependencies.

**Architecture:** Spring Boot's `java -Djarmode=tools -jar app.jar extract` splits each fat JAR into `lib/` (dependencies) and a thin application JAR. A build-time script computes the intersection of all 12 `lib/` directories, partitions shared libs into external (3rd-party) and internal (project) layers, then builds a `daemon-base` image with the shared libs and 12 per-daemon images with the unique remainder.

**Tech Stack:** Docker, Spring Boot layered JARs, shell scripts (bash), docker-compose

**Spec:** `docs/superpowers/specs/2026-03-25-layered-jar-deduplication-design.md`

---

### Task 1: Write `compute-shared-libs.sh` — extraction and intersection logic

The core script that extracts all 12 fat JARs, computes the shared library intersection, partitions into external/internal, and stages everything for Docker image builds.

**Files:**
- Create: `opennms-container/delta-v/compute-shared-libs.sh`

- [ ] **Step 1: Create the script with extraction logic**

```bash
#!/usr/bin/env bash
#
# compute-shared-libs.sh — Extract and deduplicate Spring Boot fat JARs
#
# Extracts all daemon fat JARs, computes the shared library intersection,
# partitions into external/internal layers, and stages for Docker builds.
#
# Usage: ./compute-shared-libs.sh <repo-root> <version>
#
# Outputs (in ./staging/):
#   shared-external/  — 3rd-party JARs shared by all daemons
#   shared-internal/  — org.opennms JARs shared by all daemons
#   <daemon>/libs/    — daemon-specific JARs (not in shared set)
#   <daemon>/app/     — thin application JAR
#   <daemon>/.main_class — Start-Class from MANIFEST.MF
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${1:?Usage: $0 <repo-root> <version>}"
VERSION="${2:?Usage: $0 <repo-root> <version>}"

STAGING="$SCRIPT_DIR/staging"
EXTRACT_DIR="$STAGING/extracted"

log() { echo "==> $*"; }
err() { echo "ERROR: $*" >&2; exit 1; }

# All 12 Spring Boot daemon modules — name:jar-path pairs
DAEMONS=(
    "alarmd:core/daemon-boot-alarmd/target/org.opennms.core.daemon-boot-alarmd-${VERSION}-boot.jar"
    "bsmd:core/daemon-boot-bsmd/target/org.opennms.core.daemon-boot-bsmd-${VERSION}-boot.jar"
    "collectd:core/daemon-boot-collectd/target/org.opennms.core.daemon-boot-collectd-${VERSION}-boot.jar"
    "discovery:core/daemon-boot-discovery/target/org.opennms.core.daemon-boot-discovery-${VERSION}-boot.jar"
    "enlinkd:core/daemon-boot-enlinkd/target/org.opennms.core.daemon-boot-enlinkd-${VERSION}-boot.jar"
    "eventtranslator:core/daemon-boot-eventtranslator/target/org.opennms.core.daemon-boot-eventtranslator-${VERSION}-boot.jar"
    "perspectivepollerd:core/daemon-boot-perspectivepollerd/target/org.opennms.core.daemon-boot-perspectivepollerd-${VERSION}-boot.jar"
    "pollerd:core/daemon-boot-pollerd/target/org.opennms.core.daemon-boot-pollerd-${VERSION}-boot.jar"
    "provisiond:core/daemon-boot-provisiond/target/org.opennms.core.daemon-boot-provisiond-${VERSION}-boot.jar"
    "syslogd:core/daemon-boot-syslogd/target/org.opennms.core.daemon-boot-syslogd-${VERSION}-boot.jar"
    "telemetryd:core/daemon-boot-telemetryd/target/org.opennms.core.daemon-boot-telemetryd-${VERSION}-boot.jar"
    "trapd:core/daemon-boot-trapd/target/org.opennms.core.daemon-boot-trapd-${VERSION}-boot.jar"
)

# ── Phase 1: Extract all fat JARs ────────────────────────────────
log "Phase 1: Extracting ${#DAEMONS[@]} fat JARs..."
rm -rf "$STAGING"
mkdir -p "$EXTRACT_DIR"

for entry in "${DAEMONS[@]}"; do
    name="${entry%%:*}"
    jar_path="${entry##*:}"
    full_path="$REPO_ROOT/$jar_path"

    if [ ! -f "$full_path" ]; then
        err "Fat JAR not found: $full_path — run './build.sh compile' first"
    fi

    log "  Extracting $name..."
    java -Djarmode=tools -jar "$full_path" extract --destination "$EXTRACT_DIR/$name"

    # Extract Start-Class from MANIFEST.MF
    main_class=$(unzip -p "$full_path" META-INF/MANIFEST.MF | grep "^Start-Class:" | sed 's/Start-Class: *//' | tr -d '\r')
    if [ -z "$main_class" ]; then
        err "No Start-Class found in $full_path MANIFEST.MF"
    fi
    echo "$main_class" > "$EXTRACT_DIR/$name/.main_class"
    log "    Start-Class: $main_class"
done

# ── Phase 2: Compute shared library intersection ─────────────────
log "Phase 2: Computing shared library intersection..."

# List libs from each daemon
daemon_count=0
for entry in "${DAEMONS[@]}"; do
    name="${entry%%:*}"
    ls "$EXTRACT_DIR/$name/lib/" | sort > "$EXTRACT_DIR/$name/.libs"
    daemon_count=$((daemon_count + 1))
done

# Find JARs present in ALL daemons (strict intersection)
cat "$EXTRACT_DIR"/*/.libs | sort | uniq -c | sort -rn | \
    awk -v total="$daemon_count" '$1 == total {print $2}' > "$STAGING/shared-libs.txt"

shared_count=$(wc -l < "$STAGING/shared-libs.txt" | tr -d ' ')
log "  Found $shared_count libraries shared by all $daemon_count daemons"

# ── Phase 3: Partition shared into external/internal ──────────────
log "Phase 3: Partitioning into external/internal layers..."
mkdir -p "$STAGING/shared-external" "$STAGING/shared-internal"

# Use the first daemon's lib/ as the source for shared JARs (all identical)
first_daemon="${DAEMONS[0]%%:*}"
first_lib="$EXTRACT_DIR/$first_daemon/lib"

while read -r jar; do
    # org.opennms JARs go to internal, everything else to external
    if echo "$jar" | grep -qE "^(org\.opennms\.|opennms-)"; then
        cp "$first_lib/$jar" "$STAGING/shared-internal/"
    else
        cp "$first_lib/$jar" "$STAGING/shared-external/"
    fi
done < "$STAGING/shared-libs.txt"

ext_count=$(ls "$STAGING/shared-external/" | wc -l | tr -d ' ')
int_count=$(ls "$STAGING/shared-internal/" | wc -l | tr -d ' ')
log "  External (3rd-party): $ext_count JARs"
log "  Internal (org.opennms): $int_count JARs"

# ── Phase 4: Stage per-daemon unique libs + app ──────────────────
log "Phase 4: Staging per-daemon layers..."

for entry in "${DAEMONS[@]}"; do
    name="${entry%%:*}"
    daemon_staging="$STAGING/$name"
    mkdir -p "$daemon_staging/libs" "$daemon_staging/app"

    # Copy unique libs (those NOT in the shared set)
    unique=0
    for jar in "$EXTRACT_DIR/$name/lib/"*.jar; do
        jar_name=$(basename "$jar")
        if ! grep -qx "$jar_name" "$STAGING/shared-libs.txt"; then
            cp "$jar" "$daemon_staging/libs/"
            unique=$((unique + 1))
        fi
    done

    # Copy thin application JAR
    cp "$EXTRACT_DIR/$name/"*.jar "$daemon_staging/app/" 2>/dev/null || true

    # Copy main class file
    cp "$EXTRACT_DIR/$name/.main_class" "$daemon_staging/"

    libs_size=$(du -sh "$daemon_staging/libs/" 2>/dev/null | awk '{print $1}')
    app_size=$(du -sh "$daemon_staging/app/" 2>/dev/null | awk '{print $1}')
    log "  $name: $unique unique libs ($libs_size) + app ($app_size)"
done

# ── Phase 5: Safety check ────────────────────────────────────────
log "Phase 5: Running safety checks..."

errors=0
for entry in "${DAEMONS[@]}"; do
    name="${entry%%:*}"
    for jar in "$STAGING/$name/libs/"*.jar 2>/dev/null; do
        jar_name=$(basename "$jar")
        if [ -f "$STAGING/shared-external/$jar_name" ] || [ -f "$STAGING/shared-internal/$jar_name" ]; then
            echo "  DUPLICATE: $jar_name found in both shared and $name/libs/"
            errors=$((errors + 1))
        fi
    done
done

if [ "$errors" -gt 0 ]; then
    err "Safety check failed: $errors duplicate JARs found"
fi
log "  No duplicates found — staging is clean"

# ── Cleanup ──────────────────────────────────────────────────────
rm -rf "$EXTRACT_DIR"
rm -f "$STAGING/shared-libs.txt"

# ── Summary ──────────────────────────────────────────────────────
shared_size=$(du -sh "$STAGING/shared-external" "$STAGING/shared-internal" | awk '{sum+=$1} END {print sum"M"}' 2>/dev/null || echo "?")
log ""
log "Staging complete:"
log "  shared-external/: $ext_count JARs"
log "  shared-internal/: $int_count JARs"
for entry in "${DAEMONS[@]}"; do
    name="${entry%%:*}"
    u=$(ls "$STAGING/$name/libs/" 2>/dev/null | wc -l | tr -d ' ')
    mc=$(cat "$STAGING/$name/.main_class")
    log "  $name/: $u unique libs, main=$mc"
done
```

- [ ] **Step 2: Make it executable and test**

```bash
chmod +x opennms-container/delta-v/compute-shared-libs.sh
cd opennms-container/delta-v
./compute-shared-libs.sh "$(cd ../.. && pwd)" 36.0.0-SNAPSHOT
```

Expected: script completes with "Staging complete:" summary, no "DUPLICATE" errors, staging/ directory populated with `shared-external/`, `shared-internal/`, and 12 daemon subdirectories.

- [ ] **Step 3: Verify staging output**

```bash
ls staging/shared-external/ | wc -l  # expect ~300+
ls staging/shared-internal/ | wc -l  # expect ~30+
ls staging/alarmd/libs/ | wc -l      # expect ~28
ls staging/collectd/libs/ | wc -l    # expect ~124
cat staging/alarmd/.main_class        # expect: org.opennms.netmgt.alarmd.boot.AlarmdApplication
cat staging/collectd/.main_class      # expect: org.opennms.netmgt.collectd.boot.CollectdApplication
```

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/compute-shared-libs.sh
git commit -m "feat: add compute-shared-libs.sh for layered JAR deduplication"
```

---

### Task 2: Create `entrypoint.sh` and `Dockerfile.daemon-base`

**Files:**
- Create: `opennms-container/delta-v/entrypoint.sh`
- Create: `opennms-container/delta-v/Dockerfile.daemon-base`

- [ ] **Step 1: Create entrypoint.sh**

```bash
#!/bin/sh
#
# Shared entrypoint for all Delta-V Spring Boot daemons.
# Launches the daemon using classpath-based execution.
#
# Environment variables:
#   MAIN_CLASS  — baked into image at build time (from MANIFEST.MF Start-Class)
#   JAVA_OPTS   — JVM options (set in docker-compose.yml per daemon)
#
if [ -z "$MAIN_CLASS" ]; then
    echo "ERROR: MAIN_CLASS not set" >&2
    exit 1
fi

exec java $JAVA_OPTS \
    -cp "/opt/libs/external/*:/opt/libs/internal/*:/opt/libs/daemon/*:/opt/app/*" \
    "$MAIN_CLASS"
```

- [ ] **Step 2: Create Dockerfile.daemon-base**

```dockerfile
# daemon-base — shared dependency layer for all Delta-V Spring Boot daemons.
#
# Contains 3rd-party libraries (external) and project libraries (internal)
# shared by all 12 daemons. Per-daemon images extend this with their
# unique libs and application classes.
#
# Build with: ./build.sh deltav  (built automatically)
# Rebuild when: shared dependencies change (Spring Boot, Hibernate, Kafka, etc.)

ARG JRE_IMAGE=opennms/jre-deltav:21
FROM ${JRE_IMAGE}

LABEL org.opencontainers.image.title="Delta-V Daemon Base" \
      org.opencontainers.image.description="Shared dependency layer for all Spring Boot daemons"

# Layer 1: 3rd-party libraries (rarely changes — Spring, Hibernate, Kafka, Jackson)
COPY staging/shared-external/ /opt/libs/external/

# Layer 2: project-internal libraries (changes with code — daemon-common, model-jakarta)
COPY staging/shared-internal/ /opt/libs/internal/

# Shared entrypoint for all daemons
COPY entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/entrypoint.sh

# Config directory
RUN mkdir -p /opt/libs/daemon /opt/app
```

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/entrypoint.sh opennms-container/delta-v/Dockerfile.daemon-base
git commit -m "feat: add daemon-base Dockerfile and shared entrypoint"
```

---

### Task 3: Create `Dockerfile.daemon` template

A single Dockerfile used by all 12 per-daemon image builds, parameterized via build args.

**Files:**
- Create: `opennms-container/delta-v/Dockerfile.daemon-per`

- [ ] **Step 1: Create the template Dockerfile**

```dockerfile
# Per-daemon image — extends daemon-base with daemon-specific libs and app classes.
#
# Build args:
#   VERSION     — image version tag (e.g., 36.0.0-SNAPSHOT)
#   DAEMON_NAME — daemon directory name in staging/ (e.g., alarmd)
#   MAIN_CLASS  — Spring Boot main class (from .main_class file)
#
# Built automatically by build.sh for each of the 12 daemons.

ARG VERSION
FROM opennms/daemon-base:${VERSION}

ARG DAEMON_NAME
ARG MAIN_CLASS

LABEL org.opencontainers.image.title="Delta-V ${DAEMON_NAME}" \
      org.opencontainers.image.description="Spring Boot daemon: ${DAEMON_NAME}"

# Bake the main class into the image — entrypoint.sh reads this
ENV MAIN_CLASS=${MAIN_CLASS}

# Daemon-specific libraries (not in the shared base)
COPY staging/${DAEMON_NAME}/libs/ /opt/libs/daemon/

# Thin application JAR (classes + resources only, ~18KB-200KB)
COPY staging/${DAEMON_NAME}/app/ /opt/app/

ENTRYPOINT ["/opt/entrypoint.sh"]
```

- [ ] **Step 2: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.daemon-per
git commit -m "feat: add per-daemon Dockerfile template"
```

---

### Task 4: Rewrite `build.sh` — replace `do_stage_daemon_jars` and `do_deltav_images`

Replace the old staging/build functions with the new extraction + dedup + per-daemon image build pipeline.

**Files:**
- Modify: `opennms-container/delta-v/build.sh`

- [ ] **Step 1: Replace `do_stage_daemon_jars()` and `do_deltav_images()` in build.sh**

Remove the old `do_stage_daemon_jars()` function entirely — its work is now done by `compute-shared-libs.sh`.

Replace `do_deltav_images()` with:

```bash
do_deltav_images() {
    log "Building Delta-V layered images..."

    # Check that JRE base image exists
    if ! docker image inspect opennms/jre-deltav:21 >/dev/null 2>&1; then
        err "opennms/jre-deltav:21 not found — run './build.sh jre' first"
    fi

    # Phase 1: Extract and deduplicate
    "$SCRIPT_DIR/compute-shared-libs.sh" "$REPO_ROOT" "$VERSION"

    cd "$SCRIPT_DIR"

    # Phase 2: Build daemon-base image
    log "Building opennms/daemon-base:$VERSION..."
    docker build --no-cache \
        -f Dockerfile.daemon-base \
        -t "opennms/daemon-base:$VERSION" \
        -t "opennms/daemon-base:latest" \
        .

    # Phase 3: Build per-daemon images
    local daemon_names="alarmd bsmd collectd discovery enlinkd eventtranslator perspectivepollerd pollerd provisiond syslogd telemetryd trapd"
    for name in $daemon_names; do
        local main_class
        main_class=$(cat "staging/$name/.main_class")
        log "Building opennms/$name:$VERSION (main: $main_class)..."
        docker build \
            -f Dockerfile.daemon-per \
            --build-arg "VERSION=$VERSION" \
            --build-arg "DAEMON_NAME=$name" \
            --build-arg "MAIN_CLASS=$main_class" \
            -t "opennms/$name:$VERSION" \
            -t "opennms/$name:latest" \
            .
    done

    # Phase 4: Karaf daemons — existing Sentinel-based image (unchanged)
    log "Building opennms/daemon-deltav:$VERSION..."
    docker build \
        --build-arg "VERSION=$VERSION" \
        -f Dockerfile.daemon \
        -t "opennms/daemon-deltav:$VERSION" \
        -t "opennms/daemon-deltav:latest" \
        .

    # Minion image (unchanged)
    log "Building opennms/minion-deltav:$VERSION..."
    docker build \
        --build-arg "VERSION=$VERSION" \
        -f Dockerfile.minion \
        -t "opennms/minion-deltav:$VERSION" \
        -t "opennms/minion-deltav:latest" \
        .

    # Clean up staging
    rm -rf "$SCRIPT_DIR/staging"

    log "Delta-V images built:"
    docker images --format "  {{.Repository}}:{{.Tag}}\t{{.Size}}" | grep -E "deltav|daemon-base|alarmd|bsmd|collectd|discovery|enlinkd|eventtranslator|perspectivepollerd|pollerd|provisiond|syslogd|telemetryd|trapd" | sort | head -20
}
```

- [ ] **Step 2: Test the build**

```bash
cd opennms-container/delta-v
./build.sh deltav
```

Expected: `daemon-base` image built (~240MB), then 12 per-daemon images (each ~3-60MB on top of base). No errors.

- [ ] **Step 3: Verify image sizes**

```bash
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}" | grep -E "daemon-base|alarmd|collectd|trapd|pollerd"
```

Expected: `daemon-base` ~240MB, `alarmd` ~245MB (base + ~5MB), `collectd` ~300MB (base + ~60MB), `trapd` ~243MB (base + ~3MB).

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/build.sh
git commit -m "feat: rewrite build.sh for layered JAR extraction and per-daemon images"
```

---

### Task 5: Update `docker-compose.yml` — per-daemon images and JAVA_OPTS

Replace all `image: opennms/daemon-deltav-springboot:${VERSION}` references with per-daemon image references, move JVM options from `command` to `JAVA_OPTS` environment variable, and remove `command` overrides.

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Update each daemon service**

For each of the 12 daemon services, apply this pattern:

**Before:**
```yaml
alarmd:
  image: opennms/daemon-deltav-springboot:${VERSION}
  command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m", "-jar", "/opt/daemon-boot-alarmd.jar"]
```

**After:**
```yaml
alarmd:
  image: opennms/alarmd:${VERSION}
  environment:
    JAVA_OPTS: "-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m"
    # ... (keep all existing env vars like SPRING_DATASOURCE_URL, KAFKA_BOOTSTRAP_SERVERS, etc.)
```

Apply to all 12 daemons. The mapping of image names:
- `alarmd` → `opennms/alarmd:${VERSION}`
- `bsmd` → `opennms/bsmd:${VERSION}`
- `collectd` → `opennms/collectd:${VERSION}`
- `discovery` → `opennms/discovery:${VERSION}`
- `enlinkd` → `opennms/enlinkd:${VERSION}`
- `eventtranslator` → `opennms/eventtranslator:${VERSION}`
- `perspectivepollerd` → `opennms/perspectivepollerd:${VERSION}`
- `pollerd` → `opennms/pollerd:${VERSION}`
- `provisiond` → `opennms/provisiond:${VERSION}`
- `syslogd` → `opennms/syslogd:${VERSION}`
- `telemetryd` → `opennms/telemetryd:${VERSION}`
- `trapd` → `opennms/trapd:${VERSION}`

Extract JVM flags from each daemon's `command` array into `JAVA_OPTS`. Preserve all other environment variables, volumes, healthchecks, depends_on, profiles, and stop_grace_period.

- [ ] **Step 2: Remove or rename `Dockerfile.springboot`**

The old `Dockerfile.springboot` is no longer used. Rename it to `Dockerfile.springboot.old` to preserve history, or delete it.

```bash
git rm opennms-container/delta-v/Dockerfile.springboot
```

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "feat: switch docker-compose to per-daemon images with JAVA_OPTS"
```

---

### Task 6: Deploy and run E2E tests

Verify that the new layered images work correctly in the full Docker environment.

**Files:**
- No file changes — verification only

- [ ] **Step 1: Build the new images**

```bash
cd opennms-container/delta-v
./build.sh deltav
```

- [ ] **Step 2: Redeploy with the `passive` profile**

```bash
./deploy.sh reset
./deploy.sh up passive
```

Wait for all services to become healthy:
```bash
watch -n 5 'docker compose ps --format "table {{.Name}}\t{{.Status}}"'
```

- [ ] **Step 3: Run E2E test**

```bash
./test-e2e.sh --verbose
```

Expected: Phase 1 (provisioning) passes, Phase 2 (alarm creation) creates alarm in PostgreSQL, Phase 3 (alarm clearing) clears alarm. The 3 Kafka consumer timeouts are a known pre-existing issue.

- [ ] **Step 4: Verify health endpoints**

```bash
for svc in alarmd discovery provisiond trapd syslogd eventtranslator; do
    echo -n "$svc: "
    docker compose exec -T $svc curl -sf http://localhost:8080/actuator/health | head -c 80
    echo
done
```

Expected: each daemon returns `{"status":"UP",...}`

- [ ] **Step 5: Check image sizes**

```bash
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}" | \
    grep -E "daemon-base|alarmd|bsmd|collectd|discovery|enlinkd|eventtranslator|perspectivepollerd|pollerd|provisiond|syslogd|telemetryd|trapd" | sort
```

Expected: `daemon-base` ~240MB, per-daemon images adding ~3-60MB each on top.

- [ ] **Step 6: Commit any fixes, then create final commit**

If any fixes were needed, commit them. Then:

```bash
git add -A
git commit -m "test: verify layered JAR images pass E2E"
```

---

### Task 7: Redeploy with `full` profile and verify all 12 daemons

**Files:**
- No file changes — verification only

- [ ] **Step 1: Deploy full profile**

```bash
./deploy.sh reset
./deploy.sh up full
```

- [ ] **Step 2: Wait for all 12 daemons + infrastructure to be healthy**

```bash
watch -n 10 'docker compose ps --format "table {{.Name}}\t{{.Status}}" | grep -c healthy'
```

Expected: 15+ services healthy (12 daemons + postgres + kafka + minion + others).

- [ ] **Step 3: Run E2E test again**

```bash
./test-e2e.sh
```

- [ ] **Step 4: Verify stop_grace_period still works**

```bash
# Test graceful shutdown of an RPC-based daemon
time docker compose stop provisiond
```

Expected: stops within 60s (the stop_grace_period), not instantly killed at 10s.
