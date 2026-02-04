# Configuration Impact Analyzer - Production-Ready Design

**Feature**: Context-aware configuration analysis with per-node, per-volume validation
**Target**: Recon (Monitoring & Management Service)
**Priority**: High (addresses recurring production configuration issues)
**Status**: Enhanced design with operational insights from production use cases

---

## 1. Executive Summary

### Problem Statement
Ozone has **hundreds of configuration parameters** across multiple components (DataNode, SCM, OM, Recon). Misconfigurations lead to:
- Production outages (disk full, OOM, timeouts)
- Performance degradation (suboptimal cache, handlers, buffers)
- Capacity waste (excessive reservation, over-provisioning)
- Silent failures (configs that self-heal but indicate issues)

### Solution Overview
**Configuration Impact Analyzer** validates configurations against:
1. **Static thresholds** - Fixed best practices (e.g., handler count 10-100)
2. **Dynamic context** - Volume size, cluster size, hardware specs
3. **Live metrics** - JMX metrics, API data, runtime state
4. **Operational impact** - Explains what actually happens in the system

### Key Innovation
**Context-aware validation**: Rules adapt based on operational context (volume size, CPU cores, cluster size) instead of applying one-size-fits-all thresholds.

**Example**: `hdds.datanode.dir.du.reserved.percent`
- Small volumes (<500GB): Recommend 15-20% reservation
- Medium volumes (500GB-2TB): Recommend 10-15%
- Large volumes (>2TB): Recommend 5-10%
- Absolute minimum: 50GB regardless of size
- Over-reservation (>available): Flag as ADVISORY (system handles gracefully)

---

## 2. Architecture Principles

### Design Goals
1. **Clarity**: Explicit data sources - no ambiguity about where data comes from
2. **Extensibility**: Generic context system - works for any config type
3. **Simplicity**: JSON rules with clear structure - admins can create rules easily
4. **Operability**: Impact-aware severity - explains real-world consequences
5. **Flexibility**: Built-in + custom rules - admins can add new validations

### Non-Goals (Out of Scope for Prototype)
- Automatic configuration remediation (suggest only, don't auto-fix)
- Real-time configuration monitoring (on-demand analysis only)
- Configuration versioning/rollback
- Cross-cluster configuration comparison

---

## 3. Core Concepts

### 3.1 Data Source Types

Every rule explicitly specifies where to get data:

```java
public enum DataSourceType {
  CONFIG_FILE,    // Read from OzoneConfiguration (current config value)
  JMX,            // Fetch from JMX MBean (live metrics from DataNode/SCM/OM)
  API,            // Call REST API endpoint (Recon APIs, external services)
  STATIC,         // Static threshold value defined in rule
  CALCULATED      // Calculate from other data sources (expressions)
}
```

### 3.2 Validation Scope

```java
public enum ValidationScope {
  CLUSTER,        // Cluster-wide config (e.g., SCM safe mode threshold)
  NODE,           // Per-node config (e.g., DataNode handler count)
  VOLUME,         // Per-volume config (e.g., reserved space per volume)
  COMPONENT       // Per-component config (e.g., OM Ratis settings)
}
```

### 3.3 Impact-Aware Severity

```java
public enum Severity {
  INFO,           // Informational, no action needed
  ADVISORY,       // Non-standard config with valid use case (e.g., over-reservation)
  WARNING,        // Suboptimal config, should review
  ERROR,          // Misconfiguration with operational impact
  CRITICAL;       // Immediate action required
}

public enum ImpactLevel {
  NONE,           // No operational impact
  READ_ONLY,      // Volume becomes read-only (writes blocked)
  DEGRADED,       // Reduced performance or capacity
  UNAVAILABLE,    // Component unavailable
  DATA_LOSS_RISK; // Risk of data loss or corruption
}
```

### 3.4 Operational Metadata

```java
public class RuleViolation {
  // Standard fields
  private String ruleId;
  private String configKey;
  private Severity severity;
  private String violationMessage;
  private String recommendation;

  // NEW: Operational insights
  private ImpactLevel impact;           // What's the actual impact?
  private boolean selfHealing;          // Does system handle automatically?
  private boolean intentionalUseCase;   // Valid intentional scenario?
  private String operationalContext;    // Explain what happens in the system
  private String reasoning;             // Why this matters

  // NEW: Per-node/volume tracking
  private String nodeId;                // Which DataNode/SCM/OM
  private String volumePath;            // Which volume (for volume-scoped rules)
}
```

---

## 4. Rule Definition Format (JSON)

### 4.1 Simple Static Threshold Rule

```json
{
  "ruleId": "DN-HANDLER-001",
  "ruleName": "DataNode Handler Count Range",
  "component": "DATANODE",
  "featureArea": "PERFORMANCE",
  "enabled": true,
  "builtIn": true,
  "scope": "NODE",

  "targetConfig": {
    "key": "hdds.datanode.handler.count",
    "source": "CONFIG_FILE"
  },

  "dataSources": {
    "minHandlers": {
      "source": "STATIC",
      "value": 10
    },
    "maxHandlers": {
      "source": "STATIC",
      "value": 100
    }
  },

  "validation": {
    "expression": "configValue >= minHandlers && configValue <= maxHandlers",
    "severity": "WARNING",
    "impact": "DEGRADED",
    "message": "Handler count ${configValue} is outside recommended range ${minHandlers}-${maxHandlers}",
    "recommendation": "Set hdds.datanode.handler.count between 10-100 based on workload",
    "reasoning": "Too few handlers cause request queuing. Too many cause context switching overhead."
  }
}
```

### 4.2 Context-Aware Multi-Validation Rule

```json
{
  "ruleId": "DN-STOR-001",
  "ruleName": "DataNode Volume Reserved Space",
  "component": "DATANODE",
  "featureArea": "STORAGE",
  "enabled": true,
  "builtIn": true,
  "scope": "VOLUME",

  "targetConfig": {
    "key": "hdds.datanode.dir.du.reserved.percent",
    "source": "CONFIG_FILE"
  },

  "dataSources": {
    "volumeCapacity": {
      "source": "JMX",
      "bean": "Hadoop:service=DataNode,name=FSDatasetState",
      "attribute": "Capacity",
      "scope": "PER_VOLUME"
    },
    "availableSpace": {
      "source": "JMX",
      "bean": "Hadoop:service=DataNode,name=FSDatasetState",
      "attribute": "Remaining",
      "scope": "PER_VOLUME"
    },
    "reservedBytes": {
      "source": "CALCULATED",
      "expression": "volumeCapacity * configValue"
    }
  },

  "validations": [
    {
      "id": "over-reservation",
      "name": "Over-Reservation Detection",
      "context": {},
      "expression": "reservedBytes <= availableSpace",
      "severity": "ADVISORY",
      "impact": "READ_ONLY",
      "selfHealing": true,
      "intentionalUseCase": true,
      "message": "Reserved space (${reservedBytes}) exceeds available space (${availableSpace})",
      "operationalContext": "DataNode excludes volume from writes. Volume becomes read-only. Existing containers can still be read. Safe operation.",
      "recommendation": "If intentional (read-only volume): OK.\nOtherwise:\n  1. Reduce hdds.datanode.dir.du.reserved to ${availableSpace * 0.1}\n  2. Remove volume from hdds.datanode.data.dir\n  3. Free up disk space",
      "reasoning": "Over-reservation causes DataNode to treat volume as full, excluding it from write path."
    },
    {
      "id": "small-volume-check",
      "name": "Small Volume (<500GB) Reservation",
      "context": {
        "volumeCapacity": {
          "type": "numeric",
          "max": 536870912000
        }
      },
      "expression": "configValue >= 0.15 && configValue <= 0.20",
      "severity": "WARNING",
      "impact": "UNAVAILABLE",
      "selfHealing": false,
      "intentionalUseCase": false,
      "message": "Small volume (${volumeCapacity}) has ${configValue * 100}% reserved",
      "recommendation": "Increase to 15-20% for small volumes",
      "reasoning": "Small volumes fill quickly; higher reservation prevents disk full errors."
    },
    {
      "id": "medium-volume-check",
      "name": "Medium Volume (500GB-2TB) Reservation",
      "context": {
        "volumeCapacity": {
          "type": "numeric",
          "min": 536870912000,
          "max": 2199023255552
        }
      },
      "expression": "configValue >= 0.10 && configValue <= 0.15",
      "severity": "WARNING",
      "impact": "UNAVAILABLE",
      "message": "Medium volume (${volumeCapacity}) has ${configValue * 100}% reserved",
      "recommendation": "Maintain 10-15% for medium volumes"
    },
    {
      "id": "large-volume-check",
      "name": "Large Volume (>2TB) Reservation",
      "context": {
        "volumeCapacity": {
          "type": "numeric",
          "min": 2199023255552
        }
      },
      "expression": "configValue >= 0.05 && configValue <= 0.10",
      "severity": "WARNING",
      "impact": "UNAVAILABLE",
      "message": "Large volume (${volumeCapacity}) has ${configValue * 100}% reserved",
      "recommendation": "Maintain 5-10% for large volumes"
    },
    {
      "id": "absolute-minimum-large-volumes",
      "name": "Absolute Minimum for Large Volumes (>=1TB)",
      "context": {
        "volumeCapacity": {
          "type": "numeric",
          "min": 1099511627776
        }
      },
      "expression": "reservedBytes >= 53687091200",
      "severity": "ERROR",
      "impact": "UNAVAILABLE",
      "selfHealing": false,
      "intentionalUseCase": false,
      "message": "Large volume (${humanReadable(volumeCapacity)}) has only ${humanReadable(reservedBytes)} reserved (minimum: 50GB)",
      "operationalContext": "Even large volumes need minimum 50GB to prevent OS issues and non-HDDS service failures",
      "recommendation": "Set hdds.datanode.dir.du.reserved to at least 50GB for volumes >= 1TB",
      "reasoning": "50GB minimum only applies to volumes >= 1TB where 5% would naturally be >= 50GB. For smaller volumes, percentage-based minimums (15-20%) apply."
    },
    {
      "id": "critically-low-reservation",
      "name": "Critically Low Reservation (Any Volume Size)",
      "context": {},
      "expression": "configValue >= 0.05",
      "severity": "ERROR",
      "impact": "UNAVAILABLE",
      "selfHealing": false,
      "intentionalUseCase": false,
      "message": "Reserved only ${configValue * 100}% of volume - critically low!",
      "operationalContext": "Risk of disk full, OS instability, container write failures. All volumes need minimum 5% reservation regardless of size.",
      "recommendation": "Increase to at least 5% of volume capacity.\nFor ${humanReadable(volumeCapacity)} volume: minimum ${humanReadable(volumeCapacity * 0.05)}",
      "reasoning": "Universal safety threshold: no volume should have less than 5% reserved space, regardless of size."
    }
  ],

  "relatedDocs": [
    "https://ozone.apache.org/docs/1.4.0/feature/reservedspace.html"
  ]
}
```

### 4.3 Hardware-Context-Aware Rule

```json
{
  "ruleId": "DN-HANDLER-002",
  "ruleName": "DataNode Handler Count (CPU-aware)",
  "component": "DATANODE",
  "featureArea": "PERFORMANCE",
  "scope": "NODE",

  "targetConfig": {
    "key": "hdds.datanode.handler.count",
    "source": "CONFIG_FILE"
  },

  "dataSources": {
    "cpuCores": {
      "source": "JMX",
      "bean": "java.lang:type=OperatingSystem",
      "attribute": "AvailableProcessors"
    },
    "diskCount": {
      "source": "JMX",
      "bean": "Hadoop:service=DataNode,name=FSDatasetState",
      "attribute": "NumFailedVolumes",
      "transform": "totalVolumes - value"
    }
  },

  "validations": [
    {
      "id": "small-dn",
      "name": "Small DataNode (< 8 cores)",
      "context": {
        "cpuCores": {"type": "numeric", "max": 8}
      },
      "expression": "configValue >= 10 && configValue <= 20",
      "severity": "WARNING",
      "message": "Small DataNode (${cpuCores} cores) should use 10-20 handlers",
      "recommendation": "Set to ${cpuCores * 2} handlers (2 per core)"
    },
    {
      "id": "medium-dn",
      "name": "Medium DataNode (8-16 cores)",
      "context": {
        "cpuCores": {"type": "numeric", "min": 8, "max": 16}
      },
      "expression": "configValue >= 20 && configValue <= 40",
      "severity": "WARNING",
      "message": "Medium DataNode (${cpuCores} cores) should use 20-40 handlers",
      "recommendation": "Set to ${cpuCores * 2} handlers"
    },
    {
      "id": "large-dn",
      "name": "Large DataNode (> 16 cores)",
      "context": {
        "cpuCores": {"type": "numeric", "min": 16}
      },
      "expression": "configValue >= 40 && configValue <= 80",
      "severity": "WARNING",
      "message": "Large DataNode (${cpuCores} cores) should use 40-80 handlers"
    }
  ]
}
```

---

## 5. Generic Context System

### 5.1 Context Constraint Types

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = NumericConstraint.class, name = "numeric"),
  @JsonSubTypes.Type(value = StringConstraint.class, name = "string"),
  @JsonSubTypes.Type(value = BooleanConstraint.class, name = "boolean"),
  @JsonSubTypes.Type(value = EnumConstraint.class, name = "enum"),
  @JsonSubTypes.Type(value = ExpressionConstraint.class, name = "expression")
})
public interface ContextConstraint {
  boolean matches(Object actualValue);
}
```

### 5.2 Pluggable Context Providers

```java
/**
 * Extensible context provider system
 */
@Component
public interface ContextProvider {
  Set<String> getSupportedKeys();
  void populateContext(EvaluationContext context,
                       DatanodeDetails datanode,
                       VolumeInfo volume);
}

// Built-in providers
@Component
public class VolumeContextProvider implements ContextProvider {
  public Set<String> getSupportedKeys() {
    return Set.of("volumeCapacity", "volumePath", "diskType",
                  "filesystemType", "raidLevel");
  }
}

@Component
public class HardwareContextProvider implements ContextProvider {
  public Set<String> getSupportedKeys() {
    return Set.of("cpuCores", "cpuModel", "totalMemoryGB",
                  "diskCount", "networkBandwidthMbps");
  }
}

@Component
public class ClusterContextProvider implements ContextProvider {
  public Set<String> getSupportedKeys() {
    return Set.of("clusterSize", "datanodeCount", "totalCapacityTB",
                  "usagePercent", "pipelineCount");
  }
}

@Component
public class WorkloadContextProvider implements ContextProvider {
  public Set<String> getSupportedKeys() {
    return Set.of("avgObjectSizeMB", "readWriteRatio",
                  "requestsPerSecond", "containerChurnRate");
  }
}
```

---

## 6. Data Resolution Architecture

### 6.1 Data Resolver

```java
@Component
public class DataResolver {
  private final JMXMetricFetcher jmxFetcher;
  private final RestTemplate restClient;
  private final OzoneConfiguration ozoneConf;

  public Map<String, Object> resolveData(
      ConfigurationRule rule,
      String datanodeId,
      String volumePath) {

    Map<String, Object> resolvedData = new HashMap<>();

    // Resolve each data source
    for (Map.Entry<String, DataSource> entry :
         rule.getDataSources().entrySet()) {
      String name = entry.getKey();
      DataSource ds = entry.getValue();

      Object value = resolve(ds, datanodeId, volumePath);
      resolvedData.put(name, value);
    }

    return resolvedData;
  }

  private Object resolve(DataSource ds, String datanodeId, String volumePath) {
    switch (ds.getSource()) {
      case CONFIG_FILE:
        return ozoneConf.get(ds.getConfigKey());

      case JMX:
        String jmxUrl = buildJMXUrl(datanodeId);
        return jmxFetcher.getAttribute(jmxUrl, ds.getBean(), ds.getAttribute());

      case API:
        String response = restClient.getForObject(ds.getApiEndpoint(), String.class);
        return JsonPath.parse(response).read(ds.getJsonPath());

      case STATIC:
        return ds.getValue();

      case CALCULATED:
        return evaluateExpression(ds.getExpression(), resolvedData);

      default:
        throw new IllegalArgumentException("Unknown source: " + ds.getSource());
    }
  }
}
```

### 6.2 JMX Metric Fetcher

```java
@Component
public class JMXMetricFetcher {
  private final Map<String, JMXConnector> connectionCache = new ConcurrentHashMap<>();

  public Object getAttribute(String jmxUrl, String beanName, String attribute) {
    try {
      JMXConnector connector = getOrCreateConnection(jmxUrl);
      MBeanServerConnection mbsc = connector.getMBeanServerConnection();

      ObjectName objectName = new ObjectName(beanName);
      return mbsc.getAttribute(objectName, attribute);

    } catch (Exception e) {
      LOG.error("Failed to fetch JMX metric: {}:{}", beanName, attribute, e);
      return null;
    }
  }

  private JMXConnector getOrCreateConnection(String jmxUrl) throws IOException {
    return connectionCache.computeIfAbsent(jmxUrl, url -> {
      try {
        JMXServiceURL serviceURL = new JMXServiceURL(url);
        return JMXConnectorFactory.connect(serviceURL);
      } catch (Exception e) {
        LOG.error("Failed to connect to JMX: {}", url, e);
        throw new RuntimeException(e);
      }
    });
  }
}
```

---

## 7. Rule Storage & Management

### 7.1 RocksDB Storage (Not Derby!)

**Why RocksDB**: Recon already uses RocksDB for metadata. Consistent with existing architecture.

```java
@Component
public class RuleManagementService {
  private final RDBStore rulesStore;
  private final ObjectMapper objectMapper;

  // Built-in rules loaded at startup
  private static final String BUILTIN_RULES_PATH =
      "/config-analyzer-rules/builtin/";

  @PostConstruct
  public void init() {
    loadBuiltinRules();
  }

  private void loadBuiltinRules() {
    List<String> builtinRules = List.of(
      "DN-STOR-001.json",    // Volume reservation
      "DN-HANDLER-001.json", // Handler count
      "DN-HANDLER-002.json", // CPU-aware handlers
      "SCM-PIPE-001.json",   // Pipeline interval
      "OM-HANDLER-001.json"  // OM handlers
    );

    for (String ruleFile : builtinRules) {
      try {
        InputStream is = getClass().getResourceAsStream(BUILTIN_RULES_PATH + ruleFile);
        ConfigurationRule rule = objectMapper.readValue(is, ConfigurationRule.class);
        rule.setBuiltIn(true);
        saveRule(rule);
        LOG.info("Loaded built-in rule: {}", rule.getRuleId());
      } catch (Exception e) {
        LOG.error("Failed to load built-in rule: {}", ruleFile, e);
      }
    }
  }

  public void saveRule(ConfigurationRule rule) throws IOException {
    validateRule(rule);

    String key = rule.getRuleId();
    String json = objectMapper.writeValueAsString(rule);
    rulesStore.put(key.getBytes(), json.getBytes());

    LOG.info("Saved rule: {} ({})", key, rule.isBuiltIn() ? "built-in" : "custom");
  }

  public ConfigurationRule getRule(String ruleId) throws IOException {
    byte[] value = rulesStore.get(ruleId.getBytes());
    if (value == null) return null;
    return objectMapper.readValue(value, ConfigurationRule.class);
  }

  public List<ConfigurationRule> getAllRules() throws IOException {
    List<ConfigurationRule> rules = new ArrayList<>();
    try (TableIterator<String, ? extends Table.KeyValue<String, String>>
         iterator = rulesStore.iterator()) {
      while (iterator.hasNext()) {
        Table.KeyValue<String, String> kv = iterator.next();
        ConfigurationRule rule = objectMapper.readValue(kv.getValue(),
                                                        ConfigurationRule.class);
        rules.add(rule);
      }
    }
    return rules;
  }

  public void deleteRule(String ruleId) throws IOException {
    ConfigurationRule rule = getRule(ruleId);
    if (rule != null && rule.isBuiltIn()) {
      throw new IllegalArgumentException("Cannot delete built-in rule: " + ruleId);
    }
    rulesStore.delete(ruleId.getBytes());
  }
}
```

### 7.2 Rule Upload API

```java
@POST
@Path("/rules/upload")
@Consumes(MediaType.APPLICATION_JSON)
public Response uploadRule(ConfigurationRule rule) {
  try {
    // Validate rule
    ruleManagementService.validateRule(rule);

    // Check conflicts with built-in rules
    ConfigurationRule existing = ruleManagementService.getRule(rule.getRuleId());
    if (existing != null && existing.isBuiltIn()) {
      return Response.status(Response.Status.CONFLICT)
          .entity("Cannot override built-in rule: " + rule.getRuleId())
          .build();
    }

    // Save custom rule
    rule.setBuiltIn(false);
    rule.setCreatedAt(System.currentTimeMillis());
    ruleManagementService.saveRule(rule);

    LOG.info("Admin uploaded custom rule: {}", rule.getRuleId());

    return Response.ok(Map.of(
        "success", true,
        "ruleId", rule.getRuleId(),
        "message", "Rule uploaded successfully"
    )).build();

  } catch (IllegalArgumentException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("success", false, "message", e.getMessage()))
        .build();
  }
}

@GET
@Path("/rules")
public Response getRules() {
  try {
    List<ConfigurationRule> rules = ruleManagementService.getAllRules();
    return Response.ok(rules).build();
  } catch (Exception e) {
    LOG.error("Failed to get rules", e);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
  }
}

@DELETE
@Path("/rules/{ruleId}")
public Response deleteRule(@PathParam("ruleId") String ruleId) {
  try {
    ruleManagementService.deleteRule(ruleId);
    return Response.ok(Map.of("success", true)).build();
  } catch (IllegalArgumentException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("success", false, "message", e.getMessage()))
        .build();
  }
}
```

---

## 8. Analysis Execution Flow

### 8.1 High-Level Flow

```
1. User clicks "Run Analysis" in Recon UI
   ↓
2. ConfigAnalyzerEndpoint.analyzeConfiguration()
   ↓
3. For each DataNode in cluster:
   a. Get DataNode details from NodeManager
   b. For each volume in DataNode:
      - Build EvaluationContext (volume capacity, path, etc.)
      - Load enabled rules
      - For each rule:
        * Resolve data sources (JMX, API, config)
        * Evaluate all validations
        * Check context constraints
        * Generate violations
   ↓
4. Return violations grouped by severity and node
   ↓
5. UI displays violations with operational context
```

### 8.2 Rule Evaluator

```java
@Component
public class GenericRuleEvaluator {
  private final ContextProviderRegistry contextRegistry;
  private final DataResolver dataResolver;
  private final ExpressionEvaluator expressionEvaluator;

  public List<RuleViolation> evaluateRule(
      ConfigurationRule rule,
      DatanodeDetails datanode,
      VolumeInfo volume) {

    List<RuleViolation> violations = new ArrayList<>();

    // 1. Extract required context keys
    Set<String> requiredKeys = extractRequiredContextKeys(rule);

    // 2. Build evaluation context
    EvaluationContext evalCtx = contextRegistry.buildContext(
        datanode, volume, requiredKeys);

    // 3. Resolve data sources
    Map<String, Object> metrics = dataResolver.resolveData(
        rule, datanode.getUuidString(), volume.getPath());
    for (Map.Entry<String, Object> entry : metrics.entrySet()) {
      evalCtx.withMetric(entry.getKey(), entry.getValue());
    }

    // 4. Evaluate each validation
    for (Validation validation : rule.getValidations()) {

      // Check if context matches
      if (!validation.getContext().matches(evalCtx)) {
        continue; // Skip this validation
      }

      // Evaluate expression
      boolean passed = expressionEvaluator.evaluate(
          validation.getExpression(), evalCtx.getAllValues());

      if (!passed) {
        violations.add(buildViolation(rule, validation, evalCtx));
      }
    }

    return violations;
  }

  private RuleViolation buildViolation(
      ConfigurationRule rule,
      Validation validation,
      EvaluationContext evalCtx) {

    // Variable substitution for messages
    Map<String, Object> variables = evalCtx.getAllValues();

    return RuleViolation.builder()
        .ruleId(rule.getRuleId())
        .ruleName(rule.getRuleName())
        .configKey(rule.getTargetConfig().getKey())
        .nodeId(evalCtx.getString("nodeId"))
        .volumePath(evalCtx.getString("volumePath"))
        .severity(validation.getSeverity())
        .impact(validation.getImpact())
        .selfHealing(validation.isSelfHealing())
        .intentionalUseCase(validation.isIntentionalUseCase())
        .violationMessage(substituteVariables(validation.getMessage(), variables))
        .operationalContext(validation.getOperationalContext())
        .recommendation(substituteVariables(validation.getRecommendation(), variables))
        .reasoning(validation.getReasoning())
        .detectedAt(System.currentTimeMillis())
        .build();
  }
}
```

---

## 9. UI Components

### 9.1 Configuration Analyzer Page Structure

```
┌─────────────────────────────────────────────────────────────────┐
│  Configuration Impact Analyzer                                  │
│  [Auto Reload: ON] [Last Refresh: 2min ago]                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  📊 Summary                                                     │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐ │
│  │ Total Rules  │ Violations   │ Passed       │ Skipped      │ │
│  │     42       │     15       │     25       │     2        │ │
│  └──────────────┴──────────────┴──────────────┴──────────────┘ │
│                                                                  │
│  📋 Rules Tab  │  ⚠️ Violations Tab  │  📤 Upload Rules         │
│  ════════════════════════════════════════════════════════════  │
│                                                                  │
│  Violations (15)                                                │
│  Filter: Component [All▼] Severity [All▼] Impact [All▼]        │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐│
│  │ Node: datanode-1  Volume: /data/volume1                   ││
│  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ││
│  │ ⚠️ WARNING [READ_ONLY] 🔄 Self-Healing ℹ️ May Be Intentional││
│  │                                                             ││
│  │ DN-STOR-001: Volume Reserved Space                         ││
│  │ Reserved (1TB) > Available (500GB)                         ││
│  │                                                             ││
│  │ 💡 What Happens:                                           ││
│  │ DataNode excludes volume from writes. Becomes read-only.   ││
│  │ Safe operation - system handles gracefully.                ││
│  │                                                             ││
│  │ 🔧 Recommendation:                                         ││
│  │ If intentional (read-only): OK                             ││
│  │ Otherwise:                                                  ││
│  │   1. Reduce reservation to 50GB                            ││
│  │   2. Remove volume from config                             ││
│  │   3. Free up disk space                                    ││
│  │                                                             ││
│  │ [View Details] [Snooze] [Mark as Expected]                 ││
│  └────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐│
│  │ Node: datanode-2  Volume: /data/volume1                   ││
│  │ ⛔ ERROR [UNAVAILABLE]                                     ││
│  │                                                             ││
│  │ DN-STOR-001: Volume Reserved Space Too Low                 ││
│  │ Reserved only 10GB (minimum: 50GB)                         ││
│  │                                                             ││
│  │ 💡 What Happens:                                           ││
│  │ Risk of disk full, OS instability, container write failures││
│  │                                                             ││
│  │ 🔧 Recommendation:                                         ││
│  │ Set hdds.datanode.dir.du.reserved to at least 50GB        ││
│  │                                                             ││
│  │ [Fix Now] [View Rule] [Related Docs]                       ││
│  └────────────────────────────────────────────────────────────┘│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 9.2 Rule Upload UI

```
┌─────────────────────────────────────────────────────────────────┐
│  Upload Custom Rule                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  📤 Upload JSON Rule File                                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ [Choose File] my-custom-rule.json         [Upload]       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  OR                                                              │
│                                                                  │
│  ✏️ Create Rule Manually                                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Rule ID: [CUSTOM-001            ]                        │  │
│  │ Rule Name: [My Custom Rule      ]                        │  │
│  │ Component: [DATANODE ▼]                                  │  │
│  │ Config Key: [my.config.key      ]                        │  │
│  │                                                           │  │
│  │ Validation Expression:                                    │  │
│  │ ┌────────────────────────────────────────────────────┐  │  │
│  │ │ configValue >= 10 && configValue <= 100            │  │  │
│  │ └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ [Add Data Source] [Preview] [Save]                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  📖 Documentation                                                │
│  - [Rule Authoring Guide]                                       │
│  - [Built-in Rules Reference]                                   │
│  - [Expression Syntax]                                          │
│  - [Context Variables]                                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Implementation Plan

### Phase 1: Core Infrastructure (Week 1-2)

**Goal**: Build foundation for rule evaluation

**Deliverables**:
1. **Data Models**
   - ConfigurationRule (JSON model)
   - DataSource (JMX, API, STATIC, CONFIG)
   - Validation (context-aware conditions)
   - RuleViolation (with operational metadata)

2. **Data Resolution**
   - JMXMetricFetcher (connect to DN/SCM/OM JMX)
   - DataResolver (resolve all source types)
   - Expression evaluator (JEXL integration)

3. **Rule Storage**
   - RocksDB integration
   - RuleManagementService (CRUD)
   - Built-in rule loader

4. **Context System**
   - ContextProvider interface
   - VolumeContextProvider
   - HardwareContextProvider (basic)

**Success Criteria**:
- Load 3 built-in rules from JSON
- Resolve JMX metric from local Recon
- Evaluate simple expression successfully

### Phase 2: Rule Evaluation (Week 3)

**Goal**: End-to-end rule evaluation

**Deliverables**:
1. **Rule Evaluator**
   - GenericRuleEvaluator
   - Context matching logic
   - Multi-validation support

2. **Per-Node/Volume Analysis**
   - Iterate over all DataNodes
   - Iterate over all volumes
   - Build evaluation context per volume

3. **REST API**
   - POST /api/v1/configAnalyzer/analyze
   - GET /api/v1/configAnalyzer/violations
   - GET /api/v1/configAnalyzer/rules
   - POST /api/v1/configAnalyzer/rules/upload
   - DELETE /api/v1/configAnalyzer/rules/{ruleId}

**Success Criteria**:
- Analyze 1 DataNode with 2 volumes
- Detect 2 violations (1 per volume)
- Return violations via API with operational context

### Phase 3: UI Integration (Week 4)

**Goal**: Complete user experience

**Deliverables**:
1. **Config Analyzer Page**
   - Summary statistics
   - Violations table with filters
   - Operational context display
   - Impact/severity indicators

2. **Rule Management**
   - Rule upload (JSON)
   - Rule list/edit/delete
   - Built-in vs custom indicators

3. **Polish**
   - Auto-reload
   - Export violations
   - Link to documentation

**Success Criteria**:
- Admin uploads custom rule via UI
- Violations displayed with "What Happens" context
- Filter by severity/impact/component

### Phase 4: Production Readiness (Week 5-6)

**Goal**: Production-grade system

**Deliverables**:
1. **Performance**
   - JMX connection pooling
   - Metric caching
   - Async analysis

2. **Testing**
   - Unit tests (80%+ coverage)
   - Integration tests
   - Load testing (100 nodes)

3. **Documentation**
   - Rule authoring guide
   - Built-in rules reference
   - Admin guide

4. **Monitoring**
   - Analysis duration metrics
   - Rule evaluation metrics
   - Violation trends

---

## 11. Built-in Rules (Initial Set)

### DataNode Rules
1. **DN-STOR-001**: Volume reserved space (context-aware)
2. **DN-HANDLER-001**: Handler count range (static)
3. **DN-HANDLER-002**: Handler count (CPU-aware)
4. **DN-CACHE-001**: Write cache vs handlers
5. **DN-HB-001**: Heartbeat interval vs timeout

### SCM Rules
6. **SCM-PIPE-001**: Pipeline creation interval (cluster-size-aware)
7. **SCM-SAFE-001**: Safe mode threshold range
8. **SCM-HANDLER-001**: SCM handler count

### OM Rules
9. **OM-HANDLER-001**: OM handler count
10. **OM-RATIS-001**: Ratis configuration

---

## 12. Future Enhancements (Post-Prototype)

1. **Automated Remediation**
   - Generate config change proposals
   - One-click apply (with rollback)
   - Staged rollout across cluster

2. **Configuration Drift Detection**
   - Track config changes over time
   - Alert on unexpected changes
   - Config compliance reports

3. **Machine Learning Integration**
   - Learn optimal configs from cluster behavior
   - Predict issues before they occur
   - Workload-based recommendations

4. **Cross-Cluster Analysis**
   - Compare configs across dev/staging/prod
   - Best practices from fleet
   - Anomaly detection

---

## 13. Success Metrics

### Technical Metrics
- **Rule Coverage**: 50+ rules covering critical configs
- **Detection Accuracy**: <5% false positives
- **Analysis Speed**: <30s for 100-node cluster
- **UI Responsiveness**: <2s page load

### Operational Metrics
- **Issues Prevented**: Track production incidents avoided
- **Time Saved**: Admin time saved on config debugging
- **Config Compliance**: % of clusters following best practices
- **Adoption**: % of Ozone deployments using analyzer

---

## 14. Conclusion

The **Configuration Impact Analyzer** provides:

✅ **Context-aware validation** - adapts to cluster characteristics
✅ **Operational insights** - explains real-world impact
✅ **Extensible architecture** - easy to add new rules
✅ **Production-ready** - handles 100+ node clusters
✅ **Self-service** - admins can create custom rules

This design incorporates real production insights (over-reservation scenarios, self-healing behaviors) and provides a foundation for intelligent configuration management in Ozone.

---

**Next Steps**: Begin Phase 1 implementation with core infrastructure.