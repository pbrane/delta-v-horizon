# BSMd E2E Test Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create E2E test infrastructure that validates BSM status propagation across the Delta-V container deployment — provisioned nodes with health-check services monitored via Pollerd, a BSM hierarchy created via the v3 REST API, and a test script that verifies failure/recovery propagation.

**Architecture:** Static requisition XML for node provisioning, PSM-based health checks in Pollerd config overlay, Docker Compose BSMd migration to Spring Boot, and a setup/test shell script that orchestrates the full lifecycle.

**Tech Stack:** Docker Compose, shell scripting (bash + curl + jq), PageSequenceMonitor, TcpMonitor

**Spec:** `docs/superpowers/specs/2026-03-18-bsmd-rest-api-and-e2e-test-design.md` (Part 2)

---

## File Structure

```
opennms-container/delta-v/
├── docker-compose.yml                          # MODIFY: BSMd → Spring Boot, add port 8180
├── etc/
│   └── imports/
│       └── delta-v.xml                         # CREATE: requisition
├── pollerd-daemon-overlay/etc/
│   └── poller-configuration.xml                # MODIFY: add PSM services
├── scripts/
│   └── setup-bsm-e2e.sh                       # CREATE: setup + test script
```

---

### Task 1: Docker Compose BSMd migration to Spring Boot

Migrate the BSMd service in `docker-compose.yml` from the Karaf-based Sentinel image to the Spring Boot jar pattern (matching Trapd, Syslogd, etc.).

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Read current BSMd service definition**

Read `opennms-container/delta-v/docker-compose.yml` and find the `bsmd:` service block (around line 412).

- [ ] **Step 2: Update BSMd service to Spring Boot pattern**

Replace the BSMd service definition with:

```yaml
  bsmd:
    profiles: [lite, full]
    image: opennms/daemon-deltav:${VERSION}
    container_name: delta-v-bsmd
    hostname: bsmd
    entrypoint: []
    command: ["java", "-Xms256m", "-Xmx512m", "-jar", "/opt/daemon-boot-bsmd.jar"]
    depends_on:
      db-init:
        condition: service_completed_successfully
      kafka:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/opennms
      SPRING_DATASOURCE_USERNAME: opennms
      SPRING_DATASOURCE_PASSWORD: opennms
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      KAFKA_CONSUMER_GROUP: opennms-bsmd
      OPENNMS_HOME: /opt/deltav
      OPENNMS_INSTANCE_ID: OpenNMS
      OPENNMS_TSID_NODE_ID: "17"
    ports:
      - "8180:8080"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 30s
```

Key changes from the Karaf version:
- `entrypoint: []` and `command:` for Spring Boot jar
- Spring Boot env vars (`SPRING_DATASOURCE_URL`, etc.) instead of Karaf `POSTGRES_*`
- Actuator health check instead of Karaf REST probe
- Port `8180:8080` exposed for external REST API access
- Removed `bsmd-data` volume and `bsmd-overlay` mount (Spring Boot doesn't need Karaf overlays)

Also remove the `bsmd-data:` entry from the `volumes:` section at the bottom of the file.

- [ ] **Step 3: Verify docker-compose config is valid**

Run: `cd opennms-container/delta-v && docker compose config --quiet` (timeout 30000)
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "refactor: migrate BSMd Docker Compose service to Spring Boot"
```

---

### Task 2: Requisition file

Create the static requisition that defines all Delta-V container nodes.

**Files:**
- Create: `opennms-container/delta-v/etc/imports/delta-v.xml`

- [ ] **Step 1: Create imports directory**

```bash
mkdir -p opennms-container/delta-v/etc/imports
```

- [ ] **Step 2: Create requisition file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<model-import xmlns="http://xmlns.opennms.org/xsd/config/model-import"
              foreign-source="delta-v"
              date-stamp="2026-03-18T00:00:00.000Z">

  <!-- Infrastructure -->
  <node foreign-id="postgresql" node-label="postgresql">
    <interface ip-addr="169.254.0.1" managed="true" snmp-primary="P">
      <monitored-service service-name="PostgreSQL"/>
    </interface>
  </node>
  <node foreign-id="kafka" node-label="kafka">
    <interface ip-addr="169.254.0.2" managed="true" snmp-primary="P">
      <monitored-service service-name="Kafka"/>
    </interface>
  </node>
  <node foreign-id="minion" node-label="minion">
    <interface ip-addr="169.254.0.3" managed="true" snmp-primary="P">
      <monitored-service service-name="Minion-Health"/>
    </interface>
  </node>

  <!-- Passive Monitoring Daemons -->
  <node foreign-id="trapd" node-label="trapd">
    <interface ip-addr="169.254.0.10" managed="true" snmp-primary="P">
      <monitored-service service-name="Deltav-Health"/>
    </interface>
  </node>
  <node foreign-id="syslogd" node-label="syslogd">
    <interface ip-addr="169.254.0.11" managed="true" snmp-primary="P">
      <monitored-service service-name="Deltav-Health"/>
    </interface>
  </node>
  <node foreign-id="eventtranslator" node-label="eventtranslator">
    <interface ip-addr="169.254.0.12" managed="true" snmp-primary="P">
      <monitored-service service-name="Deltav-Health"/>
    </interface>
  </node>

</model-import>
```

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/etc/imports/delta-v.xml
git commit -m "feat: add delta-v requisition for BSM E2E test nodes"
```

---

### Task 3: Poller configuration — add PSM and TCP service definitions

Append the new service definitions and monitor entries to the existing `poller-configuration.xml`.

**Files:**
- Modify: `opennms-container/delta-v/pollerd-daemon-overlay/etc/poller-configuration.xml`

- [ ] **Step 1: Read existing poller config**

Read `opennms-container/delta-v/pollerd-daemon-overlay/etc/poller-configuration.xml`.

- [ ] **Step 2: Add services to the `example1` package**

Inside the `<package name="example1">` block, before the `<downtime>` elements (around line 273), add:

```xml
      <!-- Delta-V container health checks -->
      <service name="Deltav-Health" interval="30000" user-defined="false" status="on">
        <parameter key="page-sequence">
          <page-sequence>
            <page host="${nodeLabel}" path="/actuator/health" port="8080"
                  response-range="200-299"/>
          </page-sequence>
        </parameter>
      </service>
      <service name="Minion-Health" interval="30000" user-defined="false" status="on">
        <parameter key="page-sequence">
          <page-sequence>
            <page host="${nodeLabel}" path="/minion/rest/health/probe" port="8181"
                  response-range="200-299"/>
          </page-sequence>
        </parameter>
      </service>
      <service name="PostgreSQL" interval="30000" user-defined="false" status="on">
        <parameter key="port" value="5432"/>
        <parameter key="hostname" value="${nodeLabel}"/>
      </service>
      <service name="Kafka" interval="30000" user-defined="false" status="on">
        <parameter key="port" value="9092"/>
        <parameter key="hostname" value="${nodeLabel}"/>
      </service>
```

- [ ] **Step 3: Add monitor entries**

Before the closing `</poller-configuration>` tag, add:

```xml
   <monitor service="Deltav-Health" class-name="org.opennms.netmgt.poller.monitors.PageSequenceMonitor"/>
   <monitor service="Minion-Health" class-name="org.opennms.netmgt.poller.monitors.PageSequenceMonitor"/>
   <monitor service="PostgreSQL" class-name="org.opennms.netmgt.poller.monitors.TcpMonitor"/>
   <monitor service="Kafka" class-name="org.opennms.netmgt.poller.monitors.TcpMonitor"/>
```

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/pollerd-daemon-overlay/etc/poller-configuration.xml
git commit -m "feat: add Deltav-Health, Minion-Health, PostgreSQL, Kafka poller services"
```

---

### Task 4: BSM E2E setup and test script

Create the shell script that provisions nodes, creates the BSM hierarchy, and validates status propagation.

**Files:**
- Create: `opennms-container/delta-v/scripts/setup-bsm-e2e.sh`

- [ ] **Step 1: Create scripts directory**

```bash
mkdir -p opennms-container/delta-v/scripts
```

- [ ] **Step 2: Create setup-bsm-e2e.sh**

```bash
#!/usr/bin/env bash
#
# setup-bsm-e2e.sh — BSM End-to-End test for Delta-V
#
# Creates a BSM hierarchy that monitors Delta-V container health:
#   BSM: Delta-V
#     ├── BSM: Delta-V-Infra (postgresql, kafka, minion)
#     └── BSM: Delta-V Passive Monitoring (trapd, syslogd, eventtranslator)
#
# Prerequisites:
#   - Delta-V deployed with full profile: ./deploy.sh up full
#   - curl and jq installed on the host
#
# Usage:
#   ./scripts/setup-bsm-e2e.sh              Setup BSM hierarchy
#   ./scripts/setup-bsm-e2e.sh --test       Setup + run failure/recovery test
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

# ── Configuration ──────────────────────────────────────────────
BSMD_URL="http://localhost:8180"
PROVISIOND_URL="http://localhost:8084"  # Provisiond actuator port
BSM_API="${BSMD_URL}/api/v3/business-services"
SVC_API="${BSMD_URL}/api/v3/monitored-services"
TIMEOUT=120  # seconds to wait for conditions
POLL_INTERVAL=5

# ── Helpers ────────────────────────────────────────────────────
log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { log "FATAL: $*"; exit 1; }

wait_for_url() {
    local url="$1" label="$2" elapsed=0
    log "Waiting for ${label}..."
    while ! curl -sf "$url" > /dev/null 2>&1; do
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
        if [ "$elapsed" -ge "$TIMEOUT" ]; then
            die "${label} not ready after ${TIMEOUT}s"
        fi
    done
    log "${label} is ready"
}

# ── Step 1: Wait for services ─────────────────────────────────
log "=== BSM E2E Setup ==="
wait_for_url "${BSMD_URL}/actuator/health" "BSMd"

# ── Step 2: Trigger requisition import ────────────────────────
# Provisiond watches etc/imports/ — the delta-v.xml requisition
# is mounted into the container. Trigger a scan.
log "Triggering requisition import for 'delta-v'..."
# Wait for nodes to appear (Provisiond imports on startup scan)
EXPECTED_NODES=6
elapsed=0
while true; do
    NODE_COUNT=$(curl -sf "${SVC_API}" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    if [ "$NODE_COUNT" -ge "$EXPECTED_NODES" ]; then
        log "All ${EXPECTED_NODES} nodes provisioned (${NODE_COUNT} services found)"
        break
    fi
    sleep "$POLL_INTERVAL"
    elapsed=$((elapsed + POLL_INTERVAL))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
        die "Only ${NODE_COUNT} services found after ${TIMEOUT}s (expected ${EXPECTED_NODES}+)"
    fi
    log "Waiting for provisioning... (${NODE_COUNT} services so far)"
done

# ── Step 3: Look up ipService IDs ─────────────────────────────
log "Looking up monitored service IDs..."
SERVICES=$(curl -sf "${SVC_API}")

get_svc_id() {
    local node_label="$1" svc_name="$2"
    echo "$SERVICES" | jq -r ".[] | select(.nodeLabel==\"${node_label}\" and .serviceName==\"${svc_name}\") | .id"
}

PG_SVC_ID=$(get_svc_id "postgresql" "PostgreSQL")
KAFKA_SVC_ID=$(get_svc_id "kafka" "Kafka")
MINION_SVC_ID=$(get_svc_id "minion" "Minion-Health")
TRAPD_SVC_ID=$(get_svc_id "trapd" "Deltav-Health")
SYSLOGD_SVC_ID=$(get_svc_id "syslogd" "Deltav-Health")
ET_SVC_ID=$(get_svc_id "eventtranslator" "Deltav-Health")

for var in PG_SVC_ID KAFKA_SVC_ID MINION_SVC_ID TRAPD_SVC_ID SYSLOGD_SVC_ID ET_SVC_ID; do
    [ -n "${!var}" ] || die "Could not find service ID for ${var}"
    log "  ${var}=${!var}"
done

# ── Step 4: Create BSM hierarchy ──────────────────────────────
log "Creating BSM hierarchy..."

create_bs() {
    local json="$1"
    local result
    result=$(curl -sf -X POST "${BSM_API}" \
        -H "Content-Type: application/json" \
        -d "$json")
    echo "$result" | jq -r '.id'
}

# Create leaf BSMs first
INFRA_ID=$(create_bs "$(cat <<EOF
{
  "name": "Delta-V-Infra",
  "reduceFunction": { "type": "highestSeverity" },
  "edges": [
    { "type": "ipService", "ipServiceId": ${PG_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "PostgreSQL" },
    { "type": "ipService", "ipServiceId": ${KAFKA_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "Kafka" },
    { "type": "ipService", "ipServiceId": ${MINION_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "Minion" }
  ]
}
EOF
)")
log "  Created 'Delta-V-Infra' (id=${INFRA_ID})"

PASSIVE_ID=$(create_bs "$(cat <<EOF
{
  "name": "Delta-V Passive Monitoring",
  "reduceFunction": { "type": "highestSeverity" },
  "edges": [
    { "type": "ipService", "ipServiceId": ${TRAPD_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "Trapd" },
    { "type": "ipService", "ipServiceId": ${SYSLOGD_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "Syslogd" },
    { "type": "ipService", "ipServiceId": ${ET_SVC_ID}, "mapFunction": { "type": "identity" }, "weight": 1, "friendlyName": "EventTranslator" }
  ]
}
EOF
)")
log "  Created 'Delta-V Passive Monitoring' (id=${PASSIVE_ID})"

# Create parent BSM
ROOT_ID=$(create_bs "$(cat <<EOF
{
  "name": "Delta-V",
  "reduceFunction": { "type": "highestSeverity" },
  "edges": [
    { "type": "child", "childId": ${INFRA_ID}, "mapFunction": { "type": "identity" }, "weight": 1 },
    { "type": "child", "childId": ${PASSIVE_ID}, "mapFunction": { "type": "identity" }, "weight": 1 }
  ]
}
EOF
)")
log "  Created 'Delta-V' (id=${ROOT_ID})"

log "=== BSM hierarchy created successfully ==="
log ""
log "  Delta-V (id=${ROOT_ID})"
log "  ├── Delta-V-Infra (id=${INFRA_ID})"
log "  └── Delta-V Passive Monitoring (id=${PASSIVE_ID})"
log ""
log "View status: curl -s ${BSM_API}/${ROOT_ID}/status | jq"

# ── Step 5: Optional failure/recovery test ─────────────────────
if [ "${1:-}" = "--test" ]; then
    log ""
    log "=== Running failure/recovery test ==="

    # Wait for initial status to settle
    log "Waiting for BSM status to settle..."
    elapsed=0
    while true; do
        STATUS=$(curl -sf "${BSM_API}/${ROOT_ID}/status" | jq -r '.operationalStatus')
        if [ "$STATUS" != "indeterminate" ]; then
            log "BSM status: ${STATUS}"
            break
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
        if [ "$elapsed" -ge "$TIMEOUT" ]; then
            log "WARNING: BSM status still indeterminate after ${TIMEOUT}s — proceeding anyway"
            break
        fi
    done

    # Simulate failure: stop trapd
    log "Stopping trapd container..."
    docker compose stop trapd

    # Wait for alarm propagation
    log "Waiting for BSM to detect failure..."
    elapsed=0
    while true; do
        STATUS=$(curl -sf "${BSM_API}/${ROOT_ID}/status" | jq -r '.operationalStatus')
        if [ "$STATUS" != "normal" ] && [ "$STATUS" != "indeterminate" ]; then
            ROOT_CAUSE=$(curl -sf "${BSM_API}/${ROOT_ID}/status" | jq -r '.rootCause[]' 2>/dev/null || echo "unknown")
            log "PASS: BSM detected failure — status=${STATUS}, rootCause=${ROOT_CAUSE}"
            break
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
        if [ "$elapsed" -ge "$TIMEOUT" ]; then
            die "BSM did not detect trapd failure after ${TIMEOUT}s (status=${STATUS})"
        fi
    done

    # Recover: start trapd
    log "Starting trapd container..."
    docker compose start trapd

    # Wait for recovery
    log "Waiting for BSM to recover..."
    elapsed=0
    while true; do
        STATUS=$(curl -sf "${BSM_API}/${ROOT_ID}/status" | jq -r '.operationalStatus')
        if [ "$STATUS" = "normal" ]; then
            log "PASS: BSM recovered — status=normal"
            break
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
        if [ "$elapsed" -ge "$TIMEOUT" ]; then
            die "BSM did not recover after ${TIMEOUT}s (status=${STATUS})"
        fi
    done

    log ""
    log "=== BSM E2E test PASSED ==="
fi
```

- [ ] **Step 3: Make script executable**

```bash
chmod +x opennms-container/delta-v/scripts/setup-bsm-e2e.sh
```

- [ ] **Step 4: Commit**

```bash
git add opennms-container/delta-v/scripts/setup-bsm-e2e.sh
git commit -m "feat: add BSM E2E setup and test script"
```

---

### Task 5: Mount requisition into Provisiond container

The requisition file needs to be accessible to Provisiond inside the container. Add a volume mount to the Provisiond service in docker-compose.yml.

**Files:**
- Modify: `opennms-container/delta-v/docker-compose.yml`

- [ ] **Step 1: Read Provisiond service definition**

Find the `provisiond:` service block in `docker-compose.yml`.

- [ ] **Step 2: Add requisition volume mount**

Add to the Provisiond service's `volumes:` section:

```yaml
      - ./etc/imports:/opt/deltav/etc/imports:ro
```

This mounts the `delta-v.xml` requisition into Provisiond's imports directory. Provisiond scans this directory on startup and imports any requisitions found.

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/docker-compose.yml
git commit -m "feat: mount delta-v requisition into Provisiond container"
```

---

### Task 6: Dockerfile — include daemon-boot-bsmd.jar

The BSMd Spring Boot jar needs to be included in the `opennms/daemon-deltav` Docker image.

**Files:**
- Modify: `opennms-container/delta-v/Dockerfile.daemon` (or equivalent)

- [ ] **Step 1: Read the Dockerfile**

Read `opennms-container/delta-v/Dockerfile.daemon` to understand how other daemon-boot jars (trapd, syslogd, alarmd, etc.) are included.

- [ ] **Step 2: Add daemon-boot-bsmd.jar**

Follow the same pattern as the other daemon-boot jars. Typically this is a `COPY` instruction:

```dockerfile
COPY core/daemon-boot-bsmd/target/org.opennms.core.daemon-boot-bsmd-*-boot.jar /opt/daemon-boot-bsmd.jar
```

- [ ] **Step 3: Commit**

```bash
git add opennms-container/delta-v/Dockerfile.daemon
git commit -m "feat: include daemon-boot-bsmd.jar in daemon Docker image"
```

---

### Task 7: Smoke test

Deploy the full stack and run the setup script to verify everything works.

- [ ] **Step 1: Build the BSMd module**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-bsmd -am install` (timeout 600000)

- [ ] **Step 2: Build Docker images**

Run: `cd opennms-container/delta-v && ./build.sh` (timeout 600000)

- [ ] **Step 3: Deploy with full profile**

Run: `cd opennms-container/delta-v && docker compose --profile full up -d --wait` (timeout 300000)

- [ ] **Step 4: Run setup script**

Run: `cd opennms-container/delta-v && ./scripts/setup-bsm-e2e.sh`
Expected: BSM hierarchy created, all service IDs found.

- [ ] **Step 5: Run failure/recovery test**

Run: `cd opennms-container/delta-v && ./scripts/setup-bsm-e2e.sh --test`
Expected: Both PASS lines printed — failure detected and recovery confirmed.

- [ ] **Step 6: Document any fixes needed**

If issues are found, fix and re-run. Common issues:
- Provisiond not scanning imports directory → check volume mount path
- BSMd REST API not reachable → check port mapping 8180:8080
- PSM failing to resolve hostnames → check Docker network DNS
- Alarm not propagating to BSM → check AlarmLifecycleListenerManager polling interval
