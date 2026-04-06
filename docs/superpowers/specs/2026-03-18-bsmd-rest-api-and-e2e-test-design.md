# BSMd REST API (v3) and E2E Test Design

## Summary

Add a Spring Web MVC REST API (`/api/v3/business-services`) to the BSMd Spring Boot container for full CRUD operations on business services. Use this API plus a static requisition and setup script to create an end-to-end test that validates BSM status propagation across the Delta-V container infrastructure.

## Prerequisites

### BSMd Docker Compose Migration

The `docker-compose.yml` currently runs BSMd on the Karaf-based Sentinel image (`opennms/daemon-deltav`) with a Karaf health check on port 8181. As part of this work, the BSMd compose service must be migrated to the Spring Boot jar pattern (matching Trapd, Syslogd, Alarmd, etc.):

- `entrypoint: []` and `command: ["java", "-jar", "/opt/daemon-boot-bsmd.jar"]`
- Spring Boot datasource env vars (`SPRING_DATASOURCE_URL`, etc.)
- Health check: `curl -sf http://localhost:8080/actuator/health`
- Port mapping: `8180:8080` (exposes the v3 REST API)

### BsmdApplication scanBasePackages Update

Add `"org.opennms.netmgt.bsm.rest"` to `BsmdApplication`'s `scanBasePackages` so the REST controller is discovered:

```java
@SpringBootApplication(scanBasePackages = {
    "org.opennms.core.daemon.common",
    "org.opennms.netmgt.bsm.boot",
    "org.opennms.netmgt.bsm.dao",
    "org.opennms.netmgt.bsm.rest",
    "org.opennms.netmgt.model.jakarta.dao"
})
```

## Part 1: BSM v3 REST API

### Architecture

```
BsmdRestController (@RestController)
    ↓
BusinessServiceManager (existing service layer, reused unchanged)
    ↓
JPA DAOs (existing from BSMd migration)
    ↓
Jakarta Entities (existing from BSMd migration)
```

The controller is the only new production code. DTOs and a mapper handle conversion between the REST JSON representation and the existing domain/entity model.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v3/business-services` | List all business services |
| `GET` | `/api/v3/business-services/{id}` | Get a single business service |
| `POST` | `/api/v3/business-services` | Create a business service |
| `PUT` | `/api/v3/business-services/{id}` | Update a business service |
| `DELETE` | `/api/v3/business-services/{id}` | Delete a business service |
| `GET` | `/api/v3/business-services/{id}/status` | Get operational status |

### v3 JSON Model

Clean break from the legacy v2 API. Uses camelCase, flat edge structure with type discriminator, and a separate status endpoint.

**Business Service (response — `id` and edge `id` fields included):**

```json
{
  "id": 1,
  "name": "Delta-V",
  "attributes": {
    "owner": "ops-team"
  },
  "reduceFunction": {
    "type": "highestSeverity"
  },
  "edges": [
    {
      "id": 10,
      "type": "child",
      "childId": 2,
      "mapFunction": { "type": "identity" },
      "weight": 1
    },
    {
      "id": 11,
      "type": "ipService",
      "ipServiceId": 42,
      "mapFunction": { "type": "identity" },
      "weight": 1,
      "friendlyName": "HTTP on postgresql"
    },
    {
      "id": 12,
      "type": "reductionKey",
      "reductionKey": "uei.opennms.org/nodes/nodeLostService::1:10.0.0.5:HTTP",
      "mapFunction": { "type": "setTo", "severity": "critical" },
      "weight": 1
    },
    {
      "id": 13,
      "type": "application",
      "applicationId": 3,
      "mapFunction": { "type": "identity" },
      "weight": 1
    }
  ]
}
```

**Request (POST/PUT):** Same structure but `id` fields are omitted (assigned by the server). For PUT, the full edge set is provided — edges not in the list are removed.

**Note:** `ipServiceId` and `applicationId` are `Integer` types matching the Java API (`BusinessServiceManager.getIpServiceById(Integer)`, `getApplicationById(Integer)`). `childId` is `Long` matching `BusinessServiceEntity.getId()`.
```

**Reduce function types:** `highestSeverity`, `highestSeverityAbove` (with `threshold` field), `threshold` (with `threshold` field), `exponentialPropagation` (with `base` field).

**Map function types:** `identity`, `ignore`, `increase`, `decrease`, `setTo` (with `severity` field).

**Edge types:** `child` (with `childId`), `ipService` (with `ipServiceId`, optional `friendlyName`), `reductionKey` (with `reductionKey`, optional `friendlyName`), `application` (with `applicationId`).

**Status response:**

```json
{
  "id": 1,
  "name": "Delta-V",
  "operationalStatus": "warning",
  "rootCause": [
    "IP service 'postgresql/10.0.0.2/HTTP'",
    "business service 'Delta-V-Infra'"
  ]
}
```

**Status casing:** `operationalStatus` uses lowercase (`normal`, `warning`, `minor`, `major`, `critical`, `indeterminate`) — the mapper calls `Status.name().toLowerCase()`.

**Root cause mapping:** The mapper inspects each `GraphVertex` returned by `BusinessServiceStateMachine.calculateRootCause()` and formats based on vertex type:
- Business service vertex → `"business service '<name>'"`
- IP service vertex → `"IP service '<nodeLabel>/<ipAddress>/<serviceName>'"`
- Reduction key vertex → `"reduction key '<key>'"`
- Application vertex → `"application '<name>'"`

`rootCause` is empty when `operationalStatus` is `normal` or `indeterminate`.

### Key improvements over v2

- camelCase field names (not `reduce-function`, `edge-type`)
- Edge `type` is a simple string discriminator
- Flat edge structure with type-specific fields
- Status endpoint is separate from CRUD
- Standard Spring Web error responses (not CXF)

### New files in `core/daemon-boot-bsmd/`

```
src/main/java/org/opennms/netmgt/bsm/
├── rest/
│   ├── BsmdRestController.java
│   ├── model/
│   │   ├── BusinessServiceDto.java
│   │   ├── EdgeDto.java
│   │   ├── MapFunctionDto.java
│   │   ├── ReduceFunctionDto.java
│   │   └── BusinessServiceStatusDto.java
│   └── mapper/
│       └── BusinessServiceMapper.java
```

### Controller responsibilities

- `BsmdRestController` handles HTTP concerns: request validation, response codes, exception mapping
- `BusinessServiceMapper` converts between DTOs and the existing domain model (`BusinessService`, `Edge`, `MapFunction`, `ReductionFunction`)
- The controller delegates to `BusinessServiceManager` for all business logic
- HTTP 201 for successful create, 200 for get/update, 204 for delete, 404 for not found, 400 for validation errors
- **After mutations** (POST, PUT, DELETE): the controller calls `BusinessServiceManager.triggerDaemonReload()` to reload the state machine graph with the updated configuration

### Node/Service query endpoint

The E2E setup script needs to look up `ipServiceId` values after provisioning. Add a lightweight read-only endpoint to BSMd:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v3/monitored-services` | List monitored services (with node label, IP, service name, and service ID) |

This queries `MonitoredServiceDao.findAllServices()` and returns a flat list:

```json
[
  { "id": 42, "nodeLabel": "trapd", "ipAddress": "169.254.0.10", "serviceName": "Deltav-Health" },
  { "id": 43, "nodeLabel": "postgresql", "ipAddress": "169.254.0.1", "serviceName": "PostgreSQL" }
]
```

The setup script uses this to map `(nodeLabel, serviceName)` → `ipServiceId` for BSM edge creation.

## Part 2: E2E Test Infrastructure

### Requisition

**File:** `opennms-container/delta-v/etc/imports/delta-v.xml`

Defines all Delta-V container nodes with link-local placeholder IPs (PSM uses `${nodeLabel}` for actual hostname resolution via Docker DNS).

```xml
<model-import foreign-source="delta-v">
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

### Poller Service Definitions

Added to Pollerd's daemon overlay at `opennms-container/delta-v/pollerd-daemon-overlay/etc/poller-configuration.xml`. These service definitions must be appended to the existing configuration:

```xml
<!-- Spring Boot actuator health check via PageSequenceMonitor -->
<service name="Deltav-Health" interval="30000" user-defined="false" status="on">
  <parameter key="page-sequence">
    <page-sequence>
      <page host="${nodeLabel}" path="/actuator/health" port="8080"
            response-range="200-299"/>
    </page-sequence>
  </parameter>
</service>
<monitor service="Deltav-Health"
         class-name="org.opennms.netmgt.poller.monitors.PageSequenceMonitor"/>

<!-- Minion Karaf health check via PageSequenceMonitor -->
<service name="Minion-Health" interval="30000" user-defined="false" status="on">
  <parameter key="page-sequence">
    <page-sequence>
      <page host="${nodeLabel}" path="/minion/rest/health/probe" port="8181"
            response-range="200-299"/>
    </page-sequence>
  </parameter>
</service>
<monitor service="Minion-Health"
         class-name="org.opennms.netmgt.poller.monitors.PageSequenceMonitor"/>

<!-- TCP monitors for infrastructure -->
<service name="PostgreSQL" interval="30000" user-defined="false" status="on">
  <parameter key="port" value="5432"/>
  <parameter key="hostname" value="${nodeLabel}"/>
</service>
<monitor service="PostgreSQL"
         class-name="org.opennms.netmgt.poller.monitors.TcpMonitor"/>

<service name="Kafka" interval="30000" user-defined="false" status="on">
  <parameter key="port" value="9092"/>
  <parameter key="hostname" value="${nodeLabel}"/>
</service>
<monitor service="Kafka"
         class-name="org.opennms.netmgt.poller.monitors.TcpMonitor"/>
```

### BSM Hierarchy

Created by the setup script via the v3 REST API after containers are running:

```
BSM: "Delta-V" (HighestSeverity)
├── child → BSM: "Delta-V-Infra" (HighestSeverity)
│   ├── ipService → postgresql / PostgreSQL
│   ├── ipService → kafka / Kafka
│   └── ipService → minion / Minion-Health
└── child → BSM: "Delta-V Passive Monitoring" (HighestSeverity)
    ├── ipService → trapd / Deltav-Health
    ├── ipService → syslogd / Deltav-Health
    └── ipService → eventtranslator / Deltav-Health
```

The `ipService` edges reference the monitored service IDs that are assigned after provisioning. The setup script looks these up via BSMd's `/api/v3/monitored-services` endpoint.

### Setup Script

**File:** `opennms-container/delta-v/scripts/setup-bsm-e2e.sh`

Runs after `docker-compose up --wait`. Steps:

1. **Wait for Provisiond** — poll Provisiond's `/actuator/health` until ready
2. **Trigger requisition import** — call Provisiond REST API to import `delta-v` foreign source
3. **Wait for nodes to be provisioned** — poll until all 6 nodes exist
4. **Look up ipService IDs** — query BSMd's `GET /api/v3/monitored-services` to map `(nodeLabel, serviceName)` → `ipServiceId`
5. **Create BSM hierarchy** — POST to BSMd `/api/v3/business-services` (leaf BSMs first, then parent)
6. **Verify status propagation** — poll `GET /api/v3/business-services/{id}/status` on the top-level "Delta-V" BSM until it shows a non-INDETERMINATE status

### BSMd Port Exposure

Add BSMd port mapping in `docker-compose.yml`: `8180:8080` (so the setup script can reach the v3 API from outside the Docker network).

### E2E Test Flow

```
1. docker-compose up --wait           (all containers healthy via Docker healthchecks)
2. ./scripts/setup-bsm-e2e.sh        (provisions nodes, creates BSM hierarchy)
3. Wait for Pollerd to poll           (services go UP → no alarms → BSM status normal)
4. Verify: GET .../Delta-V/status     → "normal"
5. docker-compose stop trapd          (simulate container failure)
6. Wait for Pollerd to detect         (nodeLostService alarm generated)
7. Verify: GET .../Delta-V/status     → severity > normal
8. docker-compose start trapd         (recover)
9. Wait for alarm clear
10. Verify: GET .../Delta-V/status    → "normal"
```

Steps 5-10 are the actual E2E validation — they prove that a container failure propagates through the alarm → BSM state machine → status endpoint chain.

### Compose Profile

Use the `full` profile (`docker compose --profile full up`) which includes all daemons. The monitored targets (trapd, syslogd, eventtranslator) are part of `passive` and `full` profiles; Pollerd, Provisiond, Alarmd, and BSMd are in `lite` and `full`.

## What We Don't Build

- **No authentication** on the v3 API — internal container network only
- **No pagination** on the list endpoint — BSM hierarchies are small (tens, not thousands)
- **No HATEOAS/hypermedia** — simple JSON, not Spring HATEOAS
- **No WebSocket/SSE status streaming** — polling is sufficient for the E2E test
- **No foreign source definition REST API** — Provisiond already handles requisition import
