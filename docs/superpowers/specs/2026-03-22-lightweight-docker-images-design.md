# Lightweight Docker Images for Spring Boot Daemons

## Overview

Replace the 4.75GB monolithic `daemon-deltav` image (based on Sentinel/Karaf) with a ~350MB lightweight image for the 9 Spring Boot daemons. Uses a jlink custom JRE on Alpine Linux with diagnostic tools baked in.

The 4 remaining Karaf daemons (Collectd, Enlinkd, Telemetryd, Scriptd) continue using the existing Sentinel-based image until migrated.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Image strategy | Two-image split | Spring Boot daemons get lightweight image; Karaf daemons keep existing Sentinel-based image |
| JRE approach | jlink custom JRE | Smaller than full JDK, includes `jcmd`/`jstack` for diagnostics |
| Base OS | `alpine:3.21` | Smallest stable base, widely used |
| JRE base image | Separate `opennms/jre-deltav:21` | Built once, shared by all Spring Boot daemon images; changes rarely |
| Diagnostic tools | Baked into base image | Avoid ephemeral container friction for `jcmd`/`jstack` (require same PID namespace + JDK version) |
| Module discovery | Known-good list (not jdeps) | `jdeps` misses reflection-based usage (Hibernate, Spring, Kafka); manual list is reliable |
| JAR layout | Fat JARs in `/opt/` | Phase 1 simplicity; Phase 2 will add layered JAR deduplication |
| Dockerfile.daemon cleanup | Keep Spring Boot JARs during transition | Allows fallback to old image by changing one docker-compose line |

## Image Architecture

```
alpine:3.21
  └─ opennms/jre-deltav:21 (~150MB)
       └─ opennms/daemon-deltav-springboot:${VERSION} (~350MB)

opennms/sentinel:${VERSION} (1.33GB)
  └─ opennms/daemon-deltav:${VERSION} (4.75GB, Karaf daemons only)
```

| Image | Base | Contents | Estimated Size |
|-------|------|----------|----------------|
| `opennms/jre-deltav:21` | `alpine:3.21` | jlink custom JRE + diagnostic tools + curl + non-root user | ~150MB |
| `opennms/daemon-deltav-springboot:${VERSION}` | `opennms/jre-deltav:21` | 9 Spring Boot fat JARs in `/opt/` | ~500-700MB (to be validated; fat JARs total ~1.2GB uncompressed but Docker layer compression helps) |
| `opennms/daemon-deltav:${VERSION}` | `opennms/daemon:${VERSION}` | Existing Sentinel-based, 4 Karaf daemons | ~4.75GB (unchanged) |

## JRE Base Image: `opennms/jre-deltav:21`

### Dockerfile.jre

Multi-stage build:
1. Stage 1 (`eclipse-temurin:21-jdk-alpine`): runs `jlink` to create custom JRE
2. Stage 2 (`alpine:3.21`): copies custom JRE, installs tools, creates user

### jlink Module List

| Module | Reason |
|--------|--------|
| `java.base` | Always required |
| `java.sql` | JDBC / PostgreSQL driver |
| `java.naming` | JNDI (Kafka client internal usage) |
| `java.management` | JMX (Spring Boot actuator, Kafka metrics) |
| `java.xml` | JAXB, poller-configuration.xml parsing |
| `java.desktop` | `java.beans.Introspector` (Spring property binding — mandatory); `ImageIO` (some monitors) |
| `java.logging` | JUL-to-SLF4J bridge |
| `java.security.jgss` | Kerberos (Kafka SASL authentication) |
| `java.instrument` | Spring Boot agent attachment |
| `java.net.http` | Java HTTP client |
| `java.compiler` | Runtime annotation processing |
| `jdk.jcmd` | `jcmd` diagnostic tool (thread dumps, heap info) |
| `jdk.management` | Platform MBeans |
| `jdk.management.agent` | Remote JMX agent |
| `jdk.naming.dns` | DNS lookups |
| `jdk.crypto.ec` | TLS with EC ciphers |
| `java.transaction.xa` | XA transaction interfaces (Hibernate 7 / `jakarta.transaction.xa.XAResource`) |
| `java.sql.rowset` | `javax.sql.rowset.serial.SerialBlob` (Hibernate BLOB/CLOB proxy internals) |
| `jdk.unsupported` | `sun.misc.Unsafe` (Kafka, Netty) |
| `jdk.zipfs` | Spring Boot JAR-in-JAR loading |
| `jdk.naming.rmi` | RMI naming (avoids warnings when `jdk.management.agent` is present) |

### Diagnostic Tools

Installed via `apk add --no-cache`:
- `curl` — actuator health checks
- `bind-tools` — `dig`, `nslookup`
- `iproute2` — `ip`, `ss`
- `procps` — `ps`, `top`
- `htop` — interactive process monitor
- `lsof` — open file listing
- `tcpdump` — packet capture (requires `cap_add: [NET_RAW]` in docker-compose or `--privileged` for `docker exec`)
- `busybox-extras` — `telnet`
- `nmap-ncat` — `ncat` (netcat replacement)

### User and Directories

- Non-root user: `opennms:opennms` (note: existing `daemon-deltav` image uses `sentinel:sentinel` — this is safe because all overlays are mounted `:ro` so file ownership doesn't matter for reads)
- Config directory: `/opt/deltav/etc` (owned by opennms, bind-mounted from overlay)
- No entrypoint — daemons specify full `command` in docker-compose

**Alpine musl libc note:** Alpine uses musl libc, not glibc. The Eclipse Temurin Alpine JDK handles this natively. JNI-based native libraries (unlikely in this stack) would need musl-compatible builds.

**This image changes rarely** — only on JDK version upgrade or tool changes.

## Spring Boot Daemon Image: `opennms/daemon-deltav-springboot:${VERSION}`

### Dockerfile.springboot

```dockerfile
ARG JRE_IMAGE=opennms/jre-deltav:21
FROM ${JRE_IMAGE}

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

### Spring Boot Daemons Included

1. `daemon-boot-alarmd.jar`
2. `daemon-boot-eventtranslator.jar`
3. `daemon-boot-trapd.jar`
4. `daemon-boot-syslogd.jar`
5. `daemon-boot-discovery.jar`
6. `daemon-boot-provisiond.jar`
7. `daemon-boot-bsmd.jar`
8. `daemon-boot-pollerd.jar`
9. `daemon-boot-perspectivepollerd.jar`

Each daemon is selected at runtime via docker-compose `command`:
```yaml
image: opennms/daemon-deltav-springboot:${VERSION}
command: ["java", "-Xms512m", "-Xmx1g", "-XX:MaxMetaspaceSize=256m", ..., "-jar", "/opt/daemon-boot-xxx.jar"]
```

### Standard JVM Flags

All Spring Boot daemons should use these flags:
- `-Xms512m -Xmx1g` — heap bounds
- `-XX:MaxMetaspaceSize=256m` — cap Metaspace per JVM (measured range: 76-137MB across daemons; 256MB provides headroom without allowing unbounded growth across 9+ JVMs)

## docker-compose.yml Changes

### Spring Boot daemons (9 services)

The following services switch from `opennms/daemon-deltav:${VERSION}` to `opennms/daemon-deltav-springboot:${VERSION}`:

1. `alarmd`
2. `eventtranslator`
3. `trapd`
4. `syslogd`
5. `discovery`
6. `provisiond`
7. `bsmd`
8. `pollerd`
9. `perspectivepollerd`

For each service:
```yaml
# Before:
image: opennms/daemon-deltav:${VERSION}
entrypoint: []
command: ["java", ..., "-jar", "/opt/daemon-boot-xxx.jar"]

# After:
image: opennms/daemon-deltav-springboot:${VERSION}
command: ["java", ..., "-jar", "/opt/daemon-boot-xxx.jar"]
```

The `entrypoint: []` override is no longer needed — `jre-deltav` has no entrypoint (unlike the Sentinel image). Remove the `entrypoint: []` line from all 9 services.

### Karaf daemons (4 services: Collectd, Enlinkd, Telemetryd, Scriptd)

Unchanged — continue using `opennms/daemon-deltav:${VERSION}`.

## Build Integration

### build.sh Changes

All of these are NEW code to add to `build.sh`:

**New function: `do_jre_image()`**
```bash
do_jre_image() {
    log "Building opennms/jre-deltav:21..."
    cd "$SCRIPT_DIR"
    docker build -f Dockerfile.jre \
        -t "opennms/jre-deltav:21" \
        -t "opennms/jre-deltav:latest" \
        .
}
```

**New command:** `./build.sh jre` — calls `do_jre_image()` (run manually, rarely needed)

**Modified: `do_deltav_images()`** — add springboot image build before existing Karaf image build:
```bash
# Check prerequisite
if ! docker image inspect opennms/jre-deltav:21 >/dev/null 2>&1; then
    err "opennms/jre-deltav:21 not found — run './build.sh jre' first"
fi

# Spring Boot daemons — lightweight image
log "Building opennms/daemon-deltav-springboot:$VERSION..."
docker build \
    -f Dockerfile.springboot \
    -t "opennms/daemon-deltav-springboot:$VERSION" \
    -t "opennms/daemon-deltav-springboot:latest" \
    .
```

**Staging:** `do_stage_daemon_jars()` is unchanged — stages all JARs. `Dockerfile.springboot` COPYs only the 9 Spring Boot JARs; `Dockerfile.daemon` COPYs everything.

## Verification

1. Build `jre-deltav:21` — verify `java -version`, `jcmd`, `curl` work
2. Build `daemon-deltav-springboot:${VERSION}` — verify all 9 JARs in `/opt/`
3. Start PerspectivePollerd from new image — verify actuator health UP
4. Start all 9 Spring Boot daemons — verify all healthy
5. Compare sizes — expect significant reduction from 4.75GB (exact size to be validated during build)
6. Verify Karaf daemons still work on existing `daemon-deltav` image

## Followup Tasks (Out of Scope)

1. **Phase 2: Layered JAR deduplication** — extract and share common dependency layer across daemons (after all Karaf daemons migrated)
2. **Remove Spring Boot JARs from Dockerfile.daemon** — after springboot image proven stable
3. **Retire `daemon-deltav` entirely** — after all 4 Karaf daemons migrated to Spring Boot
