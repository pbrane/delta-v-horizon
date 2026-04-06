# Lightweight Docker Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 4.75GB monolithic Docker image with a ~500MB lightweight image for all 9 Spring Boot daemons using a jlink custom JRE on Alpine Linux.

**Architecture:** Three-image split — `opennms/jre-deltav:21` (base JRE + tools), `opennms/daemon-deltav-springboot:${VERSION}` (9 Spring Boot fat JARs), existing `opennms/daemon-deltav:${VERSION}` (4 Karaf daemons unchanged). Spring Boot services switch `image:` in docker-compose.

**Tech Stack:** jlink, Alpine 3.21, Eclipse Temurin 21, Docker multi-stage build

**Spec:** `docs/superpowers/specs/2026-03-22-lightweight-docker-images-design.md`

---

## File Structure

### New files to create:
- `opennms-container/delta-v/Dockerfile.jre` — Multi-stage build for `opennms/jre-deltav:21`
- `opennms-container/delta-v/Dockerfile.springboot` — Spring Boot daemon image

### Files to modify:
- `opennms-container/delta-v/build.sh` — Add `jre` command, modify `deltav` to build springboot image
- `opennms-container/delta-v/docker-compose.yml` — Switch 9 services to new image, add MaxMetaspaceSize, remove entrypoint overrides

---

## Task 1: Create Dockerfile.jre

**Files:**
- Create: `opennms-container/delta-v/Dockerfile.jre`

- [ ] **Step 1: Create Dockerfile.jre**

```dockerfile
# JRE base image for Delta-V Spring Boot daemons.
# Uses jlink to create a custom JRE with only the modules needed by
# Spring Boot 4 + Hibernate 7 + Kafka + PostgreSQL JDBC.
#
# Build with: ./build.sh jre
# Rebuild only when upgrading JDK version or changing diagnostic tools.

FROM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN jlink \
    --add-modules \
      java.base,\
      java.sql,\
      java.sql.rowset,\
      java.naming,\
      java.management,\
      java.xml,\
      java.desktop,\
      java.logging,\
      java.security.jgss,\
      java.instrument,\
      java.net.http,\
      java.compiler,\
      java.transaction.xa,\
      jdk.jcmd,\
      jdk.management,\
      jdk.management.agent,\
      jdk.naming.dns,\
      jdk.naming.rmi,\
      jdk.crypto.ec,\
      jdk.unsupported,\
      jdk.zipfs \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

FROM alpine:3.21
LABEL org.opencontainers.image.title="Delta-V JRE Base" \
      org.opencontainers.image.description="Custom JRE with diagnostic tools for Delta-V Spring Boot daemons"

# Custom JRE
COPY --from=jre-builder /custom-jre /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Diagnostic tools
RUN apk add --no-cache \
    curl \
    bind-tools \
    iproute2 \
    procps \
    htop \
    lsof \
    tcpdump \
    busybox-extras \
    nmap-ncat

# Non-root user
RUN addgroup -S opennms && adduser -S opennms -G opennms

# Config directory for Spring Boot daemons
RUN mkdir -p /opt/deltav/etc && chown opennms:opennms /opt/deltav/etc

USER opennms
WORKDIR /opt
```

- [ ] **Step 2: Build and verify the JRE image**

```bash
cd opennms-container/delta-v
docker build -f Dockerfile.jre -t opennms/jre-deltav:21 -t opennms/jre-deltav:latest .
```
Expected: Successful build

- [ ] **Step 3: Verify the image works**

```bash
# Check java
docker run --rm opennms/jre-deltav:21 java -version

# Check jcmd
docker run --rm opennms/jre-deltav:21 jcmd -l

# Check curl
docker run --rm opennms/jre-deltav:21 curl --version

# Check user
docker run --rm opennms/jre-deltav:21 whoami
```
Expected: Java 21, jcmd runs, curl available, user is `opennms`

- [ ] **Step 4: Check image size**

```bash
docker images opennms/jre-deltav --format "{{.Tag}}\t{{.Size}}"
```
Expected: ~150MB

- [ ] **Step 5: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.jre
git commit -m "feat: add Dockerfile.jre — jlink custom JRE base image for Spring Boot daemons"
```

---

## Task 2: Create Dockerfile.springboot

**Files:**
- Create: `opennms-container/delta-v/Dockerfile.springboot`

- [ ] **Step 1: Create Dockerfile.springboot**

```dockerfile
# Delta-V Spring Boot daemon image — all 9 migrated daemons in one lightweight image.
# Each daemon is selected at runtime via docker-compose command:
#   command: ["java", "-jar", "/opt/daemon-boot-xxx.jar"]
#
# Build with: ./build.sh deltav  (requires prior: ./build.sh jre)

ARG JRE_IMAGE=opennms/jre-deltav:21
FROM ${JRE_IMAGE}

LABEL org.opencontainers.image.title="Delta-V Spring Boot Daemons" \
      org.opencontainers.image.description="Lightweight image for all Spring Boot daemon services"

# Spring Boot fat JARs
COPY staging/daemon/daemon-boot-alarmd.jar /opt/daemon-boot-alarmd.jar
COPY staging/daemon/daemon-boot-eventtranslator.jar /opt/daemon-boot-eventtranslator.jar
COPY staging/daemon/daemon-boot-trapd.jar /opt/daemon-boot-trapd.jar
COPY staging/daemon/daemon-boot-syslogd.jar /opt/daemon-boot-syslogd.jar
COPY staging/daemon/daemon-boot-discovery.jar /opt/daemon-boot-discovery.jar
COPY staging/daemon/daemon-boot-provisiond.jar /opt/daemon-boot-provisiond.jar
COPY staging/daemon/daemon-boot-bsmd.jar /opt/daemon-boot-bsmd.jar
COPY staging/daemon/daemon-boot-pollerd.jar /opt/daemon-boot-pollerd.jar
COPY staging/daemon/daemon-boot-perspectivepollerd.jar /opt/daemon-boot-perspectivepollerd.jar
```

- [ ] **Step 2: Build and verify** (requires staging JARs)

Note: You cannot `source build.sh` to call individual functions (the script calls `main` at the end). Instead, build the springboot image after Task 3 is done, using the full `./build.sh deltav` flow. For initial testing in Task 2, stage the JARs manually:

```bash
cd opennms-container/delta-v
mkdir -p staging/daemon

# Copy the 9 Spring Boot JARs to staging (adjust VERSION as needed)
VERSION=36.0.0-SNAPSHOT
for jar in alarmd eventtranslator trapd syslogd discovery provisiond bsmd pollerd perspectivepollerd; do
    src="../../core/daemon-boot-$jar/target/org.opennms.core.daemon-boot-$jar-$VERSION-boot.jar"
    [ ! -f "$src" ] && src="../../core/daemon-boot-$jar/target/org.opennms.core.daemon-boot-$jar-$VERSION.jar"
    [ -f "$src" ] && cp "$src" "staging/daemon/daemon-boot-$jar.jar" && echo "Staged $jar" || echo "MISSING: $src"
done

# Build the image
docker build -f Dockerfile.springboot \
    -t "opennms/daemon-deltav-springboot:$VERSION" \
    -t "opennms/daemon-deltav-springboot:latest" \
    .

# Clean up manual staging
rm -rf staging
```
Expected: Successful build

- [ ] **Step 3: Verify all 9 JARs are present**

```bash
docker run --rm opennms/daemon-deltav-springboot:36.0.0-SNAPSHOT ls -la /opt/daemon-boot-*.jar
```
Expected: 9 JAR files listed

- [ ] **Step 4: Check image size**

```bash
docker images opennms/daemon-deltav-springboot --format "{{.Tag}}\t{{.Size}}"
```
Expected: Significantly smaller than 4.75GB

- [ ] **Step 5: Quick smoke test — start PerspectivePollerd from new image**

```bash
docker run --rm --name test-perspectivepollerd \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/opennms \
    opennms/daemon-deltav-springboot:36.0.0-SNAPSHOT \
    java -version
```
Expected: Java 21 version output (just verifying the JAR can find Java)

- [ ] **Step 6: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.springboot
git commit -m "feat: add Dockerfile.springboot — lightweight image for 9 Spring Boot daemons"
```

---

## Task 3: Update build.sh

**Files:**
- Modify: `opennms-container/delta-v/build.sh`

- [ ] **Step 1: Add `do_jre_image()` function**

Add after `do_db_init_image()` function (around line 101):

```bash
do_jre_image() {
    log "Building opennms/jre-deltav:21..."
    cd "$SCRIPT_DIR"
    docker build -f Dockerfile.jre \
        -t "opennms/jre-deltav:21" \
        -t "opennms/jre-deltav:latest" \
        .
    log "JRE image built:"
    docker images opennms/jre-deltav --format "  {{.Repository}}:{{.Tag}}\t{{.Size}}"
}
```

- [ ] **Step 2: Modify `do_deltav_images()` to build springboot image**

In `do_deltav_images()` (around line 190), add the springboot build BEFORE the existing Karaf image build:

Replace the entire `do_deltav_images()` function (lines 190-219) with:

```bash
do_deltav_images() {
    log "Building Delta-V layered images..."

    # Check that JRE base image exists
    if ! docker image inspect opennms/jre-deltav:21 >/dev/null 2>&1; then
        err "opennms/jre-deltav:21 not found — run './build.sh jre' first"
    fi

    do_stage_daemon_jars

    # Spring Boot daemons — lightweight image
    log "Building opennms/daemon-deltav-springboot:$VERSION..."
    cd "$SCRIPT_DIR"
    docker build \
        -f Dockerfile.springboot \
        -t "opennms/daemon-deltav-springboot:$VERSION" \
        -t "opennms/daemon-deltav-springboot:latest" \
        .

    # Karaf daemons — existing Sentinel-based image
    log "Building opennms/daemon-deltav:$VERSION..."
    cd "$SCRIPT_DIR"
    docker build \
        --build-arg "VERSION=$VERSION" \
        -f Dockerfile.daemon \
        -t "opennms/daemon-deltav:$VERSION" \
        -t "opennms/daemon-deltav:latest" \
        .

    # Minion image
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
    docker images --format "  {{.Repository}}:{{.Tag}}\t{{.Size}}" | grep -E "deltav" | head -10
}
```

- [ ] **Step 3: Add `jre` case to `main()` switch and update `all`/`push` cases**

In the `main()` case statement (around line 260):

Add new case:
```bash
        jre)
            do_jre_image
            ;;
```

Update the `all` case to build JRE if missing:
```bash
        all)
            do_compile
            do_assemble
            do_images
            # Build JRE base if not already present
            if ! docker image inspect opennms/jre-deltav:21 >/dev/null 2>&1; then
                do_jre_image
            fi
            do_deltav_images
            log "Build complete! Run: cd $SCRIPT_DIR && docker compose up -d"
            ;;
```

Update the `push` case similarly:
```bash
        push)
            do_compile
            do_assemble
            do_images push
            if ! docker image inspect opennms/jre-deltav:21 >/dev/null 2>&1; then
                do_jre_image
            fi
            do_deltav_images
            ;;
```

- [ ] **Step 4: Update usage text**

Add `jre` to the usage help:

```
  jre       Build JRE base image (opennms/jre-deltav:21, rarely needed)
```

- [ ] **Step 5: Test the build flow**

```bash
cd opennms-container/delta-v

# Build JRE base (first time)
VERSION=36.0.0-SNAPSHOT bash build.sh jre

# Build Delta-V images (should build both springboot and karaf)
VERSION=36.0.0-SNAPSHOT bash build.sh deltav
```
Expected: Both images built successfully

- [ ] **Step 6: Verify both images exist**

```bash
docker images --format "{{.Repository}}:{{.Tag}}\t{{.Size}}" | grep -E "daemon-deltav|jre-deltav"
```
Expected: Three images — `jre-deltav:21`, `daemon-deltav-springboot:VERSION`, `daemon-deltav:VERSION`

- [ ] **Step 7: Commit**

```bash
git add opennms-container/delta-v/build.sh
git commit -m "feat: add jre build command and springboot image to build.sh"
```

---

## Task 4: Update docker-compose.yml

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

This task switches all 9 Spring Boot services to the new image, adds `-XX:MaxMetaspaceSize=256m` to all commands, and removes `entrypoint: []` overrides.

For each service: change `image:` to `opennms/daemon-deltav-springboot:${VERSION}`, remove `entrypoint: []`, add `-XX:MaxMetaspaceSize=256m` to command. Preserve ALL existing `-D` system properties and heap sizes.

- [ ] **Step 1: Update pollerd** (line 124)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m",
              "-Dorg.opennms.core.ipc.twin.kafka.bootstrap.servers=kafka:9092",
              "-Dorg.opennms.core.ipc.rpc.force-remote=true",
              "-jar", "/opt/daemon-boot-pollerd.jar"]
```
Remove `entrypoint: []` (line 127).

- [ ] **Step 2: Update discovery** (line 194)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-discovery.jar"]
```
Remove `entrypoint: []` (line 197).

- [ ] **Step 3: Update trapd** (line 226)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-trapd.jar"]
```
Remove `entrypoint: []` (line 229).

- [ ] **Step 4: Update syslogd** (line 258)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-syslogd.jar"]
```
Remove `entrypoint: []` (line 261).

- [ ] **Step 5: Update eventtranslator** (line 291)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-eventtranslator.jar"]
```
Remove `entrypoint: []` (line 294).

- [ ] **Step 6: Update provisiond** (line 385)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-provisiond.jar"]
```
Remove `entrypoint: []` (line 388).

- [ ] **Step 7: Update bsmd** (line 418)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms256m", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
              "-Dorg.opennms.alarms.snapshot.sync.ms=10000",
              "-jar", "/opt/daemon-boot-bsmd.jar"]
```
Remove `entrypoint: []` (line 421).

- [ ] **Step 8: Update perspectivepollerd** (line 450)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m",
              "-Dorg.opennms.core.ipc.rpc.force-remote=true",
              "-jar", "/opt/daemon-boot-perspectivepollerd.jar"]
```
Remove `entrypoint: []` (line 451).

- [ ] **Step 9: Update alarmd** (line 484)

```yaml
    image: opennms/daemon-deltav-springboot:${VERSION}
    command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m",
              "-jar", "/opt/daemon-boot-alarmd.jar"]
```
Remove `entrypoint: []` (line 485).

- [ ] **Step 10: Verify docker-compose is valid**

```bash
cd opennms-container/delta-v
VERSION=36.0.0-SNAPSHOT docker compose config --quiet
```
Expected: No errors (exit code 0)

- [ ] **Step 11: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "feat: switch 9 Spring Boot daemons to lightweight springboot image"
```

---

## Task 5: Full Stack Verification

- [ ] **Step 1: Build everything**

```bash
cd opennms-container/delta-v
VERSION=36.0.0-SNAPSHOT bash build.sh jre
VERSION=36.0.0-SNAPSHOT bash build.sh deltav
```

- [ ] **Step 2: Compare image sizes**

```bash
docker images --format "{{.Repository}}:{{.Tag}}\t{{.Size}}" | grep -E "daemon-deltav|jre-deltav"
```
Expected output like:
```
opennms/jre-deltav:21                              ~150MB
opennms/daemon-deltav-springboot:36.0.0-SNAPSHOT   ~500-700MB
opennms/daemon-deltav:36.0.0-SNAPSHOT              ~4.75GB
```

- [ ] **Step 3: Start the full stack**

```bash
cd opennms-container/delta-v
VERSION=36.0.0-SNAPSHOT docker compose --profile full up -d
```

- [ ] **Step 4: Wait for health checks and verify all Spring Boot daemons**

```bash
sleep 30

# Check each Spring Boot daemon
for svc in pollerd alarmd bsmd provisiond perspectivepollerd trapd syslogd eventtranslator discovery; do
    echo "=== $svc ==="
    docker exec "delta-v-$svc" curl -sf http://localhost:8080/actuator/health 2>&1 || echo "FAILED"
done
```
Expected: All 9 show `{"status":"UP"}`

- [ ] **Step 5: Verify Karaf daemons still work**

```bash
for svc in collectd enlinkd telemetryd scriptd; do
    echo "=== $svc ==="
    docker exec "delta-v-$svc" curl -sf -u admin:admin http://localhost:8181/sentinel/rest/health/probe 2>&1 || echo "FAILED or not running"
done
```
Expected: Karaf daemons healthy (or not running if their profile isn't active)

- [ ] **Step 6: Verify jcmd works inside a container**

```bash
docker exec delta-v-perspectivepollerd jcmd 1 VM.version
```
Expected: JDK version output

- [ ] **Step 7: Commit any fixes if needed**

```bash
git add <changed-files>
git commit -m "fix: resolve lightweight image startup issues"
```

---

## Task 6: Update Dashboard

- [ ] **Step 1: Update README.md**

Add a note about the lightweight Docker image to the README dashboard section.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: update dashboard — lightweight Docker images for Spring Boot daemons"
```
