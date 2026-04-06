# BSMd REST API v3 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Spring Web MVC REST API at `/api/v3/business-services` to BSMd for full CRUD on business services, plus a `/api/v3/monitored-services` query endpoint for ipServiceId lookup.

**Architecture:** REST controller delegates to existing `BusinessServiceManager` service layer. DTOs use Jackson annotations with camelCase. A mapper converts between DTOs and the BSM domain model (BusinessService, Edge, MapFunction, ReductionFunction). The controller calls `triggerDaemonReload()` after mutations.

**Tech Stack:** Spring Web MVC (already in `spring-boot-starter-web`), Jackson for JSON, JUnit 5 + MockMvc for testing

**Spec:** `docs/superpowers/specs/2026-03-18-bsmd-rest-api-and-e2e-test-design.md`

---

## File Structure

```
core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/
├── rest/
│   ├── BsmdRestController.java              # @RestController for /api/v3/business-services
│   ├── MonitoredServiceRestController.java   # @RestController for /api/v3/monitored-services
│   ├── model/
│   │   ├── BusinessServiceDto.java           # BS request/response DTO
│   │   ├── EdgeDto.java                      # Edge DTO with type discriminator
│   │   ├── MapFunctionDto.java               # Map function DTO
│   │   ├── ReduceFunctionDto.java            # Reduce function DTO
│   │   ├── BusinessServiceStatusDto.java     # Status response DTO
│   │   └── MonitoredServiceDto.java          # Monitored service query DTO
│   └── mapper/
│       └── BusinessServiceMapper.java        # Domain ↔ DTO conversion
├── boot/
│   └── BsmdApplication.java                 # MODIFY: add rest package to scanBasePackages
```

---

### Task 1: DTO model classes

Create all 6 DTO classes. These are simple data holders with Jackson annotations.

**Files:**
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/ReduceFunctionDto.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/MapFunctionDto.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/EdgeDto.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/BusinessServiceDto.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/BusinessServiceStatusDto.java`
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/MonitoredServiceDto.java`

- [ ] **Step 1: Create ReduceFunctionDto**

```java
package org.opennms.netmgt.bsm.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReduceFunctionDto {
    private String type;        // highestSeverity, highestSeverityAbove, threshold, exponentialPropagation
    private Integer threshold;  // for highestSeverityAbove (Status ordinal)
    private Float thresholdValue; // for threshold (0-1 range)
    private Double base;        // for exponentialPropagation

    // Getters and setters for all fields
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getThreshold() { return threshold; }
    public void setThreshold(Integer threshold) { this.threshold = threshold; }
    public Float getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Float thresholdValue) { this.thresholdValue = thresholdValue; }
    public Double getBase() { return base; }
    public void setBase(Double base) { this.base = base; }
}
```

- [ ] **Step 2: Create MapFunctionDto**

```java
package org.opennms.netmgt.bsm.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MapFunctionDto {
    private String type;     // identity, ignore, increase, decrease, setTo
    private String severity; // for setTo only (e.g. "critical")

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
}
```

- [ ] **Step 3: Create EdgeDto**

```java
package org.opennms.netmgt.bsm.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EdgeDto {
    private Long id;              // response only (assigned by server)
    private String type;          // child, ipService, reductionKey, application
    private MapFunctionDto mapFunction;
    private int weight = 1;

    // Type-specific fields
    private Long childId;         // for type=child
    private Integer ipServiceId;  // for type=ipService
    private String reductionKey;  // for type=reductionKey
    private Integer applicationId; // for type=application
    private String friendlyName;  // for type=ipService or reductionKey (optional)

    // Getters and setters for all fields
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public MapFunctionDto getMapFunction() { return mapFunction; }
    public void setMapFunction(MapFunctionDto mapFunction) { this.mapFunction = mapFunction; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public Long getChildId() { return childId; }
    public void setChildId(Long childId) { this.childId = childId; }
    public Integer getIpServiceId() { return ipServiceId; }
    public void setIpServiceId(Integer ipServiceId) { this.ipServiceId = ipServiceId; }
    public String getReductionKey() { return reductionKey; }
    public void setReductionKey(String reductionKey) { this.reductionKey = reductionKey; }
    public Integer getApplicationId() { return applicationId; }
    public void setApplicationId(Integer applicationId) { this.applicationId = applicationId; }
    public String getFriendlyName() { return friendlyName; }
    public void setFriendlyName(String friendlyName) { this.friendlyName = friendlyName; }
}
```

- [ ] **Step 4: Create BusinessServiceDto**

```java
package org.opennms.netmgt.bsm.rest.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessServiceDto {
    private Long id;                    // response only
    private String name;
    private Map<String, String> attributes;
    private ReduceFunctionDto reduceFunction;
    private List<EdgeDto> edges;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public ReduceFunctionDto getReduceFunction() { return reduceFunction; }
    public void setReduceFunction(ReduceFunctionDto reduceFunction) { this.reduceFunction = reduceFunction; }
    public List<EdgeDto> getEdges() { return edges; }
    public void setEdges(List<EdgeDto> edges) { this.edges = edges; }
}
```

- [ ] **Step 5: Create BusinessServiceStatusDto**

```java
package org.opennms.netmgt.bsm.rest.model;

import java.util.List;

public class BusinessServiceStatusDto {
    private Long id;
    private String name;
    private String operationalStatus;  // lowercase: normal, warning, minor, major, critical, indeterminate
    private List<String> rootCause;    // empty when status is normal/indeterminate

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOperationalStatus() { return operationalStatus; }
    public void setOperationalStatus(String operationalStatus) { this.operationalStatus = operationalStatus; }
    public List<String> getRootCause() { return rootCause; }
    public void setRootCause(List<String> rootCause) { this.rootCause = rootCause; }
}
```

- [ ] **Step 6: Create MonitoredServiceDto**

```java
package org.opennms.netmgt.bsm.rest.model;

public class MonitoredServiceDto {
    private int id;
    private String nodeLabel;
    private String ipAddress;
    private String serviceName;

    public MonitoredServiceDto() {}

    public MonitoredServiceDto(int id, String nodeLabel, String ipAddress, String serviceName) {
        this.id = id;
        this.nodeLabel = nodeLabel;
        this.ipAddress = ipAddress;
        this.serviceName = serviceName;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getNodeLabel() { return nodeLabel; }
    public void setNodeLabel(String nodeLabel) { this.nodeLabel = nodeLabel; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
}
```

- [ ] **Step 7: Verify compilation**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests` (timeout 300000)
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/model/
git commit -m "feat: add BSM v3 REST API DTO model classes"
```

---

### Task 2: BusinessServiceMapper

Converts between DTOs and the BSM domain model. Uses the `EdgeVisitor` pattern for edge type discrimination and `MapFunctionVisitor`/`ReduceFunctionVisitor` for function type mapping.

**Files:**
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/mapper/BusinessServiceMapper.java`
- Test: `core/daemon-boot-bsmd/src/test/java/org/opennms/netmgt/bsm/rest/mapper/BusinessServiceMapperTest.java`

- [ ] **Step 1: Write mapper test**

```java
package org.opennms.netmgt.bsm.rest.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceDto;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceStatusDto;
import org.opennms.netmgt.bsm.rest.model.EdgeDto;
import org.opennms.netmgt.bsm.rest.model.MapFunctionDto;
import org.opennms.netmgt.bsm.rest.model.ReduceFunctionDto;
import org.opennms.netmgt.bsm.service.model.BusinessService;
import org.opennms.netmgt.bsm.service.model.IpService;
import org.opennms.netmgt.bsm.service.model.Status;
import org.opennms.netmgt.bsm.service.model.edge.ChildEdge;
import org.opennms.netmgt.bsm.service.model.edge.IpServiceEdge;
import org.opennms.netmgt.bsm.service.model.edge.ReductionKeyEdge;
import org.opennms.netmgt.bsm.service.model.functions.map.Identity;
import org.opennms.netmgt.bsm.service.model.functions.reduce.HighestSeverity;
import org.opennms.netmgt.bsm.service.model.graph.GraphVertex;

class BusinessServiceMapperTest {

    private final BusinessServiceMapper mapper = new BusinessServiceMapper();

    @Test
    void mapsBusinessServiceToDto() {
        var bs = mock(BusinessService.class);
        when(bs.getId()).thenReturn(1L);
        when(bs.getName()).thenReturn("Delta-V");
        when(bs.getAttributes()).thenReturn(Map.of("owner", "ops"));
        when(bs.getReduceFunction()).thenReturn(new HighestSeverity());
        when(bs.getEdges()).thenReturn(Collections.emptySet());

        BusinessServiceDto dto = mapper.toDto(bs);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Delta-V");
        assertThat(dto.getAttributes()).containsEntry("owner", "ops");
        assertThat(dto.getReduceFunction().getType()).isEqualTo("highestSeverity");
    }

    @Test
    void mapsChildEdgeToDto() {
        var childBs = mock(BusinessService.class);
        when(childBs.getId()).thenReturn(2L);

        var edge = mock(ChildEdge.class);
        when(edge.getId()).thenReturn(10L);
        when(edge.getChild()).thenReturn(childBs);
        when(edge.getMapFunction()).thenReturn(new Identity());
        when(edge.getWeight()).thenReturn(1);

        EdgeDto dto = mapper.edgeToDto(edge);

        assertThat(dto.getType()).isEqualTo("child");
        assertThat(dto.getChildId()).isEqualTo(2L);
        assertThat(dto.getMapFunction().getType()).isEqualTo("identity");
    }

    @Test
    void mapsStatusToDto() {
        var bs = mock(BusinessService.class);
        when(bs.getId()).thenReturn(1L);
        when(bs.getName()).thenReturn("Delta-V");

        var vertex = mock(GraphVertex.class);
        var ipService = mock(IpService.class);
        when(ipService.getNodeLabel()).thenReturn("postgresql");
        when(ipService.getIpAddress()).thenReturn("169.254.0.1");
        when(ipService.getServiceName()).thenReturn("PostgreSQL");
        when(vertex.getIpService()).thenReturn(ipService);
        when(vertex.getBusinessService()).thenReturn(null);
        when(vertex.getApplication()).thenReturn(null);
        when(vertex.getReductionKey()).thenReturn(null);

        BusinessServiceStatusDto dto = mapper.toStatusDto(bs, Status.WARNING, List.of(vertex));

        assertThat(dto.getOperationalStatus()).isEqualTo("warning");
        assertThat(dto.getRootCause()).containsExactly("IP service 'postgresql/169.254.0.1/PostgreSQL'");
    }

    @Test
    void mapsReduceFunctionFromDto() {
        var dto = new ReduceFunctionDto();
        dto.setType("highestSeverity");

        var fn = mapper.toReduceFunction(dto);

        assertThat(fn).isInstanceOf(HighestSeverity.class);
    }

    @Test
    void mapsMapFunctionFromDto() {
        var dto = new MapFunctionDto();
        dto.setType("identity");

        var fn = mapper.toMapFunction(dto);

        assertThat(fn).isInstanceOf(Identity.class);
    }
}
```

- [ ] **Step 2: Implement BusinessServiceMapper**

```java
package org.opennms.netmgt.bsm.rest.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.opennms.netmgt.bsm.rest.model.BusinessServiceDto;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceStatusDto;
import org.opennms.netmgt.bsm.rest.model.EdgeDto;
import org.opennms.netmgt.bsm.rest.model.MapFunctionDto;
import org.opennms.netmgt.bsm.rest.model.ReduceFunctionDto;
import org.opennms.netmgt.bsm.service.model.BusinessService;
import org.opennms.netmgt.bsm.service.model.Status;
import org.opennms.netmgt.bsm.service.model.edge.ApplicationEdge;
import org.opennms.netmgt.bsm.service.model.edge.ChildEdge;
import org.opennms.netmgt.bsm.service.model.edge.Edge;
import org.opennms.netmgt.bsm.service.model.edge.EdgeVisitor;
import org.opennms.netmgt.bsm.service.model.edge.IpServiceEdge;
import org.opennms.netmgt.bsm.service.model.edge.ReductionKeyEdge;
import org.opennms.netmgt.bsm.service.model.functions.map.Decrease;
import org.opennms.netmgt.bsm.service.model.functions.map.Identity;
import org.opennms.netmgt.bsm.service.model.functions.map.Ignore;
import org.opennms.netmgt.bsm.service.model.functions.map.Increase;
import org.opennms.netmgt.bsm.service.model.functions.map.MapFunction;
import org.opennms.netmgt.bsm.service.model.functions.map.SetTo;
import org.opennms.netmgt.bsm.service.model.functions.reduce.ExponentialPropagation;
import org.opennms.netmgt.bsm.service.model.functions.reduce.HighestSeverity;
import org.opennms.netmgt.bsm.service.model.functions.reduce.HighestSeverityAbove;
import org.opennms.netmgt.bsm.service.model.functions.reduce.ReductionFunction;
import org.opennms.netmgt.bsm.service.model.functions.reduce.Threshold;
import org.opennms.netmgt.bsm.service.model.graph.GraphVertex;
import org.springframework.stereotype.Component;

@Component
public class BusinessServiceMapper {

    public BusinessServiceDto toDto(BusinessService bs) {
        var dto = new BusinessServiceDto();
        dto.setId(bs.getId());
        dto.setName(bs.getName());
        dto.setAttributes(bs.getAttributes());
        dto.setReduceFunction(toReduceFunctionDto(bs.getReduceFunction()));
        dto.setEdges(bs.getEdges().stream()
                .map(this::edgeToDto)
                .collect(Collectors.toList()));
        return dto;
    }

    public EdgeDto edgeToDto(Edge edge) {
        return edge.accept(new EdgeVisitor<EdgeDto>() {
            @Override
            public EdgeDto visit(IpServiceEdge e) {
                var dto = baseEdgeDto(e);
                dto.setType("ipService");
                dto.setIpServiceId(e.getIpService().getId());
                dto.setFriendlyName(e.getFriendlyName());
                return dto;
            }

            @Override
            public EdgeDto visit(ReductionKeyEdge e) {
                var dto = baseEdgeDto(e);
                dto.setType("reductionKey");
                dto.setReductionKey(e.getReductionKey());
                dto.setFriendlyName(e.getFriendlyName());
                return dto;
            }

            @Override
            public EdgeDto visit(ChildEdge e) {
                var dto = baseEdgeDto(e);
                dto.setType("child");
                dto.setChildId(e.getChild().getId());
                return dto;
            }

            @Override
            public EdgeDto visit(ApplicationEdge e) {
                var dto = baseEdgeDto(e);
                dto.setType("application");
                dto.setApplicationId(e.getApplication().getId());
                return dto;
            }
        });
    }

    private EdgeDto baseEdgeDto(Edge edge) {
        var dto = new EdgeDto();
        dto.setId(edge.getId());
        dto.setMapFunction(toMapFunctionDto(edge.getMapFunction()));
        dto.setWeight(edge.getWeight());
        return dto;
    }

    public BusinessServiceStatusDto toStatusDto(BusinessService bs, Status status,
                                                 List<GraphVertex> rootCauseVertices) {
        var dto = new BusinessServiceStatusDto();
        dto.setId(bs.getId());
        dto.setName(bs.getName());
        dto.setOperationalStatus(status.name().toLowerCase());
        dto.setRootCause(rootCauseVertices.stream()
                .map(this::formatRootCauseVertex)
                .collect(Collectors.toList()));
        return dto;
    }

    private String formatRootCauseVertex(GraphVertex vertex) {
        if (vertex.getBusinessService() != null) {
            return "business service '" + vertex.getBusinessService().getName() + "'";
        } else if (vertex.getIpService() != null) {
            var svc = vertex.getIpService();
            return "IP service '" + svc.getNodeLabel() + "/" + svc.getIpAddress() + "/" + svc.getServiceName() + "'";
        } else if (vertex.getApplication() != null) {
            return "application '" + vertex.getApplication().getApplicationName() + "'";
        } else if (vertex.getReductionKey() != null) {
            return "reduction key '" + vertex.getReductionKey() + "'";
        }
        return "unknown vertex";
    }

    // --- DTO → Domain conversion ---

    public ReductionFunction toReduceFunction(ReduceFunctionDto dto) {
        return switch (dto.getType()) {
            case "highestSeverity" -> new HighestSeverity();
            case "highestSeverityAbove" -> {
                if (dto.getThreshold() == null) {
                    throw new IllegalArgumentException("threshold is required for reduce function type 'highestSeverityAbove'");
                }
                var fn = new HighestSeverityAbove();
                fn.setThreshold(Status.get(dto.getThreshold()));
                yield fn;
            }
            case "threshold" -> {
                if (dto.getThresholdValue() == null) {
                    throw new IllegalArgumentException("thresholdValue is required for reduce function type 'threshold'");
                }
                var fn = new Threshold();
                fn.setThreshold(dto.getThresholdValue());
                yield fn;
            }
            case "exponentialPropagation" -> {
                if (dto.getBase() == null) {
                    throw new IllegalArgumentException("base is required for reduce function type 'exponentialPropagation'");
                }
                var fn = new ExponentialPropagation();
                fn.setBase(dto.getBase());
                yield fn;
            }
            default -> throw new IllegalArgumentException("Unknown reduce function type: " + dto.getType());
        };
    }

    public MapFunction toMapFunction(MapFunctionDto dto) {
        return switch (dto.getType()) {
            case "identity" -> new Identity();
            case "ignore" -> new Ignore();
            case "increase" -> new Increase();
            case "decrease" -> new Decrease();
            case "setTo" -> {
                var fn = new SetTo();
                fn.setStatus(Status.of(dto.getSeverity()));
                yield fn;
            }
            default -> throw new IllegalArgumentException("Unknown map function type: " + dto.getType());
        };
    }

    // --- Domain → DTO conversion for functions ---

    private ReduceFunctionDto toReduceFunctionDto(ReductionFunction fn) {
        var dto = new ReduceFunctionDto();
        if (fn instanceof HighestSeverity) {
            dto.setType("highestSeverity");
        } else if (fn instanceof HighestSeverityAbove hsa) {
            dto.setType("highestSeverityAbove");
            dto.setThreshold(hsa.getThreshold().getId());
        } else if (fn instanceof Threshold t) {
            dto.setType("threshold");
            dto.setThresholdValue(t.getThreshold());
        } else if (fn instanceof ExponentialPropagation ep) {
            dto.setType("exponentialPropagation");
            dto.setBase(ep.getBase());
        }
        return dto;
    }

    private MapFunctionDto toMapFunctionDto(MapFunction fn) {
        var dto = new MapFunctionDto();
        if (fn instanceof Identity) {
            dto.setType("identity");
        } else if (fn instanceof Ignore) {
            dto.setType("ignore");
        } else if (fn instanceof Increase) {
            dto.setType("increase");
        } else if (fn instanceof Decrease) {
            dto.setType("decrease");
        } else if (fn instanceof SetTo setTo) {
            dto.setType("setTo");
            dto.setSeverity(setTo.getStatus().name().toLowerCase());
        }
        return dto;
    }
}
```

- [ ] **Step 3: Run mapper tests**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn test -pl . -Dtest=BusinessServiceMapperTest` (timeout 300000)
Expected: PASS — all 5 tests green.

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/mapper/ \
        core/daemon-boot-bsmd/src/test/java/org/opennms/netmgt/bsm/rest/mapper/
git commit -m "feat: add BusinessServiceMapper for v3 REST API DTO conversion"
```

---

### Task 3: BsmdRestController

The main REST controller handling CRUD operations on business services.

**Files:**
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/BsmdRestController.java`
- Modify: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/BsmdApplication.java` (add rest to scanBasePackages)

- [ ] **Step 1: Update BsmdApplication scanBasePackages**

Read `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/BsmdApplication.java` and add `"org.opennms.netmgt.bsm.rest"` to the `scanBasePackages` array.

- [ ] **Step 2: Create BsmdRestController**

```java
package org.opennms.netmgt.bsm.rest;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.opennms.netmgt.bsm.rest.mapper.BusinessServiceMapper;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceDto;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceStatusDto;
import org.opennms.netmgt.bsm.rest.model.EdgeDto;
import org.opennms.netmgt.bsm.service.BusinessServiceManager;
import org.opennms.netmgt.bsm.service.BusinessServiceStateMachine;
import org.opennms.netmgt.bsm.service.model.BusinessService;
import org.opennms.netmgt.bsm.service.model.Status;
import org.opennms.netmgt.bsm.service.model.graph.GraphVertex;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v3/business-services")
public class BsmdRestController {

    private final BusinessServiceManager manager;
    private final BusinessServiceStateMachine stateMachine;
    private final BusinessServiceMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public BsmdRestController(BusinessServiceManager manager,
                               BusinessServiceStateMachine stateMachine,
                               BusinessServiceMapper mapper,
                               TransactionTemplate transactionTemplate) {
        this.manager = manager;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping
    public List<BusinessServiceDto> listAll() {
        return transactionTemplate.execute(status ->
            manager.getAllBusinessServices().stream()
                .map(mapper::toDto)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public BusinessServiceDto getById(@PathVariable Long id) {
        return transactionTemplate.execute(status -> {
            BusinessService bs = findOrThrow(id);
            return mapper.toDto(bs);
        });
    }

    @PostMapping
    public ResponseEntity<BusinessServiceDto> create(@RequestBody BusinessServiceDto request) {
        BusinessServiceDto result = transactionTemplate.execute(status -> {
            BusinessService bs = manager.createBusinessService();
            bs.setName(request.getName());
            if (request.getAttributes() != null) {
                bs.setAttributes(request.getAttributes());
            }
            bs.setReduceFunction(mapper.toReduceFunction(request.getReduceFunction()));
            bs.save();

            if (request.getEdges() != null) {
                addEdges(bs, request.getEdges());
                bs.save();
            }

            return mapper.toDto(bs);
        });
        manager.triggerDaemonReload();
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PutMapping("/{id}")
    public BusinessServiceDto update(@PathVariable Long id, @RequestBody BusinessServiceDto request) {
        BusinessServiceDto result = transactionTemplate.execute(status -> {
            BusinessService bs = findOrThrow(id);
            bs.setName(request.getName());
            if (request.getAttributes() != null) {
                bs.setAttributes(request.getAttributes());
            }
            bs.setReduceFunction(mapper.toReduceFunction(request.getReduceFunction()));

            // Clear existing edges and re-add from request
            // Copy to list first to avoid ConcurrentModificationException
            new java.util.ArrayList<>(bs.getEdges()).forEach(bs::removeEdge);
            if (request.getEdges() != null) {
                addEdges(bs, request.getEdges());
            }

            bs.save();
            return mapper.toDto(bs);
        });
        manager.triggerDaemonReload();
        return result;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            BusinessService bs = findOrThrow(id);
            manager.deleteBusinessService(bs);
        });
        manager.triggerDaemonReload();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/status")
    public BusinessServiceStatusDto getStatus(@PathVariable Long id) {
        return transactionTemplate.execute(status -> {
            BusinessService bs = findOrThrow(id);
            Status opStatus = manager.getOperationalStatus(bs);
            List<GraphVertex> rootCause = (opStatus.isGreaterThan(Status.NORMAL))
                    ? stateMachine.calculateRootCause(bs)
                    : List.of();
            return mapper.toStatusDto(bs, opStatus, rootCause);
        });
    }

    private BusinessService findOrThrow(Long id) {
        try {
            return manager.getBusinessServiceById(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Business service not found: " + id);
        }
    }

    private void addEdges(BusinessService bs, List<EdgeDto> edges) {
        for (EdgeDto edgeDto : edges) {
            var mapFn = mapper.toMapFunction(edgeDto.getMapFunction());
            switch (edgeDto.getType()) {
                case "child" -> manager.addChildEdge(bs,
                        manager.getBusinessServiceById(edgeDto.getChildId()),
                        mapFn, edgeDto.getWeight());
                case "ipService" -> manager.addIpServiceEdge(bs,
                        manager.getIpServiceById(edgeDto.getIpServiceId()),
                        mapFn, edgeDto.getWeight(), edgeDto.getFriendlyName());
                case "reductionKey" -> manager.addReductionKeyEdge(bs,
                        edgeDto.getReductionKey(),
                        mapFn, edgeDto.getWeight(), edgeDto.getFriendlyName());
                case "application" -> manager.addApplicationEdge(bs,
                        manager.getApplicationById(edgeDto.getApplicationId()),
                        mapFn, edgeDto.getWeight());
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown edge type: " + edgeDto.getType());
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests` (timeout 300000)
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/BsmdRestController.java \
        core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/boot/BsmdApplication.java
git commit -m "feat: add BsmdRestController for /api/v3/business-services"
```

---

### Task 4: MonitoredServiceRestController

Lightweight read-only endpoint for ipServiceId lookup. Used by the E2E setup script.

**Files:**
- Create: `core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/MonitoredServiceRestController.java`

- [ ] **Step 1: Create MonitoredServiceRestController**

```java
package org.opennms.netmgt.bsm.rest;

import java.util.List;
import java.util.stream.Collectors;

import org.opennms.netmgt.bsm.rest.model.MonitoredServiceDto;
import org.opennms.netmgt.bsm.service.BusinessServiceManager;
import org.opennms.netmgt.bsm.service.model.IpService;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/monitored-services")
public class MonitoredServiceRestController {

    private final BusinessServiceManager manager;
    private final TransactionTemplate transactionTemplate;

    public MonitoredServiceRestController(BusinessServiceManager manager,
                                           TransactionTemplate transactionTemplate) {
        this.manager = manager;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping
    public List<MonitoredServiceDto> listAll() {
        return transactionTemplate.execute(status ->
            manager.getAllIpServices().stream()
                .map(this::toDto)
                .collect(Collectors.toList()));
    }

    private MonitoredServiceDto toDto(IpService svc) {
        return new MonitoredServiceDto(
            svc.getId(),
            svc.getNodeLabel(),
            svc.getIpAddress(),
            svc.getServiceName());
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn compile -pl . -DskipTests` (timeout 300000)
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-bsmd/src/main/java/org/opennms/netmgt/bsm/rest/MonitoredServiceRestController.java
git commit -m "feat: add MonitoredServiceRestController for /api/v3/monitored-services"
```

---

### Task 5: REST API integration test

Test the REST endpoints against a real database using Testcontainers.

**Files:**
- Create: `core/daemon-boot-bsmd/src/test/java/org/opennms/netmgt/bsm/rest/BsmdRestControllerIT.java`
- Modify: `core/daemon-boot-bsmd/src/test/resources/schema.sql` (already exists from BSMd migration)

- [ ] **Step 1: Write integration test**

```java
package org.opennms.netmgt.bsm.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opennms.netmgt.bsm.boot.BsmdApplication;
import org.opennms.netmgt.bsm.rest.model.BusinessServiceDto;
import org.opennms.netmgt.bsm.rest.model.MapFunctionDto;
import org.opennms.netmgt.bsm.rest.model.ReduceFunctionDto;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = BsmdApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@Import(BsmdRestControllerIT.TestConfig.class)
class BsmdRestControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("opennms")
            .withUsername("opennms")
            .withPassword("opennms")
            .withInitScript("schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("opennms.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public EventSubscriptionService eventSubscriptionService() {
            return mock(EventSubscriptionService.class);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void listEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/v3/business-services"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void createAndGetBusinessService() throws Exception {
        var reduceFn = new ReduceFunctionDto();
        reduceFn.setType("highestSeverity");

        var dto = new BusinessServiceDto();
        dto.setName("test-bs");
        dto.setAttributes(Map.of("env", "test"));
        dto.setReduceFunction(reduceFn);

        // Create
        MvcResult createResult = mockMvc.perform(post("/api/v3/business-services")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        BusinessServiceDto created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                BusinessServiceDto.class);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("test-bs");

        // Get
        mockMvc.perform(get("/api/v3/business-services/" + created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("test-bs"))
                .andExpect(jsonPath("$.reduceFunction.type").value("highestSeverity"));

        // Status
        mockMvc.perform(get("/api/v3/business-services/" + created.getId() + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationalStatus").value("indeterminate"));

        // Delete
        mockMvc.perform(delete("/api/v3/business-services/" + created.getId()))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get("/api/v3/business-services/" + created.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateBusinessService() throws Exception {
        var reduceFn = new ReduceFunctionDto();
        reduceFn.setType("highestSeverity");

        var dto = new BusinessServiceDto();
        dto.setName("update-test");
        dto.setReduceFunction(reduceFn);

        // Create
        MvcResult createResult = mockMvc.perform(post("/api/v3/business-services")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        BusinessServiceDto created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                BusinessServiceDto.class);

        // Update name
        created.setName("update-test-renamed");
        mockMvc.perform(put("/api/v3/business-services/" + created.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("update-test-renamed"));

        // Cleanup
        mockMvc.perform(delete("/api/v3/business-services/" + created.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void listMonitoredServices() throws Exception {
        mockMvc.perform(get("/api/v3/monitored-services"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v3/business-services/999999"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn verify -pl . -Dit.test=BsmdRestControllerIT` (timeout 600000)
Expected: PASS. If it fails, fix wiring/schema issues and re-run.

- [ ] **Step 3: Commit**

```bash
git add core/daemon-boot-bsmd/src/test/java/org/opennms/netmgt/bsm/rest/
git commit -m "test: add BsmdRestControllerIT integration tests for v3 REST API"
```

---

### Task 6: Build verification

Full build to verify everything compiles and tests pass.

- [ ] **Step 1: Full build**

Run: `./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-bsmd -am install` (timeout 600000)
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests**

Run: `cd core/daemon-boot-bsmd && ../../maven/bin/mvn verify -pl .` (timeout 600000)
Expected: All tests pass (unit + integration).

- [ ] **Step 3: Commit any fixes**

```bash
git add -u
git commit -m "fix: resolve build issues in BSMd REST API"
```
