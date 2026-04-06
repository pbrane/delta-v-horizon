# Layered JAR Deduplication for Docker Images

## Problem

The 12 Spring Boot daemon fat JARs share ~335 dependency libraries (~143MB), but the current Docker image (`daemon-deltav-springboot`, 4.85GB) bakes all 12 fat JARs into a single image layer. Any daemon change invalidates the entire layer, causing full 4.85GB rebuilds and pushes.

## Goals

1. **Fast rebuilds** — Changing one daemon rebuilds/pushes ~2-60MB, not 4.85GB
2. **Per-daemon images** — Each daemon gets its own tagged image for independent deployment
3. **Phase B ready** — Architecture supports future extraction of daemons into separate git repos with independent versioning

## Non-Goals

- Changing daemon behavior or configuration
- Modifying the JRE base image (`jre-deltav`)
- Splitting daemons into separate git repos (Phase B, future)
- Optimizing the Maven build itself

## Architecture

### Three-tier image hierarchy

```
opennms/jre-deltav:21                     (~95MB)  — custom jlink JRE + diagnostic tools [unchanged]
  └── opennms/daemon-base:VERSION         (~240MB) — two sub-layers:
        │   Layer 1: /opt/libs/external/          — 3rd-party libs (Spring, Hibernate, Kafka, Jackson — static)
        │   Layer 2: /opt/libs/internal/          — project JARs (daemon-common, model-jakarta — changes with code)
        │
        ├── opennms/alarmd:VERSION          (~5MB)
        ├── opennms/bsmd:VERSION            (~15MB)
        ├── opennms/collectd:VERSION        (~60MB)
        ├── opennms/discovery:VERSION       (~3MB)
        ├── opennms/enlinkd:VERSION         (~10MB)
        ├── opennms/eventtranslator:VERSION (~5MB)
        ├── opennms/perspectivepollerd:VERSION (~10MB)
        ├── opennms/pollerd:VERSION         (~10MB)
        ├── opennms/provisiond:VERSION      (~20MB)
        ├── opennms/syslogd:VERSION         (~3MB)
        ├── opennms/telemetryd:VERSION      (~10MB)
        └── opennms/trapd:VERSION           (~3MB)
```

The `daemon-base` image uses two `COPY` layers so Docker can cache 3rd-party libs independently from project JARs. During development, only the internal layer changes — the external layer (~130MB of Spring/Hibernate/Kafka) stays cached.

## Design

### 1. Shared Library Extraction

Use Spring Boot's built-in layered JAR extraction (`java -Djarmode=tools -jar app.jar extract`) to split each fat JAR into:
- `lib/` — third-party dependency JARs
- `application/` — application classes and resources

A build-time script computes the **intersection** of all 12 `lib/` directories to identify the ~335 libraries shared by every daemon. These go into the `daemon-base` image. Each daemon's unique libraries stay in its per-daemon image.

**Why strict intersection (not "used by >1")?** The intersection guarantees no classpath pollution — a daemon never loads a library it didn't originally include. A library used by 11/12 daemons but not the 12th would bloat that daemon's classpath with an unused dependency. For the initial implementation, strictness is safer. This can be relaxed later if the intersection proves too small, but with 335/396 libs shared across all 12, the intersection is already large.

The `compute-shared-libs.sh` script includes a **safety check**: after partitioning, it verifies that no JAR filename appears in both the shared directory and any daemon-specific directory. This prevents the shell's alphabetical `*` expansion from loading a duplicate JAR from two locations.

### 2. Main Class Detection

Each Spring Boot fat JAR embeds a `Start-Class` attribute in `META-INF/MANIFEST.MF`. The extraction script reads this and writes it to a `.main_class` file in each daemon's staging directory. The `Dockerfile.daemon` bakes this into the image as an environment variable:

```dockerfile
ARG MAIN_CLASS
ENV MAIN_CLASS=${MAIN_CLASS}
```

This eliminates manual `MAIN_CLASS` configuration in `docker-compose.yml` — each image knows how to launch itself.

### 3. Docker Images

**`Dockerfile.daemon-base`** — Built once per release (or when shared deps change):
```dockerfile
FROM opennms/jre-deltav:21

# Layer 1: 3rd-party libraries (rarely changes)
COPY staging/shared-external/ /opt/libs/external/

# Layer 2: project-internal libraries (changes with code)
COPY staging/shared-internal/ /opt/libs/internal/

# Shared entrypoint
COPY entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/entrypoint.sh
```

**`Dockerfile.daemon`** — Template used for each daemon:
```dockerfile
ARG VERSION
ARG DAEMON_NAME
ARG MAIN_CLASS
FROM opennms/daemon-base:${VERSION}

ENV MAIN_CLASS=${MAIN_CLASS}

COPY staging/${DAEMON_NAME}/libs/ /opt/libs/daemon/
COPY staging/${DAEMON_NAME}/app/ /opt/app/

ENTRYPOINT ["/opt/entrypoint.sh"]
```

**`entrypoint.sh`** — Classpath-based launch replacing `java -jar`:
```bash
#!/bin/sh
exec java $JAVA_OPTS \
  -cp "/opt/libs/external/*:/opt/libs/internal/*:/opt/libs/daemon/*:/opt/app/*" \
  "$MAIN_CLASS"
```

Classpath ordering: external libs first, then internal project libs, then daemon-specific libs, then application classes. This matches the Spring Boot fat JAR loader's ordering where framework JARs load before application code.

### 4. Build Pipeline Changes

**`build.sh deltav`** changes:
1. Extract all 12 fat JARs using `java -Djarmode=tools -jar app.jar extract`
2. Run `compute-shared-libs.sh` to:
   - Find the strict intersection of all 12 `lib/` directories
   - Partition shared libs into `shared-external/` (3rd-party, groupId != `org.opennms`) and `shared-internal/` (project JARs, groupId = `org.opennms`)
   - Move remaining per-daemon libs into `{daemon-name}/libs/`
   - Extract `Start-Class` from each `MANIFEST.MF` into `{daemon-name}/.main_class`
   - Run the safety check (no JAR in both shared and daemon directories)
3. Build `daemon-base` image from shared libs
4. For each daemon: build per-daemon image from unique libs + app classes + main class

**New script: `compute-shared-libs.sh`**
- Inputs: directories of extracted fat JARs
- Outputs: `staging/shared-external/`, `staging/shared-internal/`, `staging/{daemon}/libs/`, `staging/{daemon}/app/`, `staging/{daemon}/.main_class`
- Deterministic: same inputs always produce same outputs (sorted, reproducible)

### 5. docker-compose.yml Changes

Each daemon service switches from the shared image to its own:

```yaml
# Before
alarmd:
  image: opennms/daemon-deltav-springboot:${VERSION}
  command: ["java", "-Xms256m", "-Xmx512m", "-jar", "/opt/daemon-boot-alarmd.jar"]

# After
alarmd:
  image: opennms/alarmd:${VERSION}
  environment:
    JAVA_OPTS: "-Xms256m -Xmx512m"
```

The `command` override is no longer needed — each image has its own entrypoint with the correct main class baked in.

### 6. Size Impact

| Metric | Before | After |
|--------|--------|-------|
| Total image size | 4.85GB (1 image) | ~240MB base + ~150MB total daemon layers |
| Rebuild one daemon | 4.85GB push | ~5-60MB push |
| Shared external layer | None | ~130MB (3rd-party, very stable) |
| Shared internal layer | None | ~110MB (project JARs, changes with code) |
| Number of images | 1 | 13 (1 base + 12 daemons) |

### 7. Phase B Compatibility

This design directly enables Phase B (separate repos):
- Each daemon repo builds only its thin application layer
- `daemon-base:VERSION` becomes a published dependency, like a Maven parent POM but for Docker
- No daemon repo needs to know about other daemons
- Shared platform changes (daemon-common, event transport) update `daemon-base`, all daemons pick it up on next build

## Files Changed

| File | Change |
|------|--------|
| `opennms-container/delta-v/Dockerfile.springboot` | Replaced by `Dockerfile.daemon-base` + `Dockerfile.daemon` |
| `opennms-container/delta-v/build.sh` | `do_deltav_images()` rewritten for extraction + dedup |
| `opennms-container/delta-v/docker-compose.yml` | Each daemon gets its own image reference, `command` removed |
| `opennms-container/delta-v/compute-shared-libs.sh` | New: intersection logic, external/internal partitioning, main class extraction, safety check |
| `opennms-container/delta-v/entrypoint.sh` | New: shared classpath-based launch |

## Risks

- **Classpath ordering** — Shell `*` expansion is alphabetical within each directory. The classpath is ordered: external → internal → daemon → app, matching Spring Boot's loader order. The safety check in `compute-shared-libs.sh` guarantees no JAR appears in multiple directories, so alphabetical ordering within a directory cannot cause conflicts.
- **Spring Boot DevTools / actuator** — Some Spring Boot features assume fat JAR packaging. Mitigation: actuator endpoints work fine with classpath launch; DevTools is not used in production.
- **Build complexity** — More moving parts than a simple `COPY *.jar`. Mitigation: the extraction script is deterministic and testable; a `verify-staging.sh` script can validate the output before image build.
- **Internal libs cache invalidation** — Project JARs like `daemon-common` change frequently during development, invalidating the internal layer of `daemon-base`. Mitigation: the two-layer split means 3rd-party libs (~130MB) stay cached even when project JARs change.

## Constraints

- Must work on a feature branch with a PR
- All 12 daemons must still start and pass health checks
- The `test-e2e.sh` script must still pass
- No changes to daemon application code — only Docker build pipeline and compose config
