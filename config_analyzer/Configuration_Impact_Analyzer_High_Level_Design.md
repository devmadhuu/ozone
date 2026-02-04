# Configuration Impact Analyzer - High-Level Design

**Feature**: Intelligent configuration analysis, recommendations, and impact prediction for Apache Ozone
**Target**: Recon (Monitoring & Management Service)
**Priority**: High (addresses recurring production configuration issues)

---

## 1. Problem Statement

### Production Issues
- Configuration mistakes cause operational problems (e.g., `hdds.scm.safemode.threshold.pct`)
- No visibility into which configurations deviate from best practices
- Cannot predict impact of configuration changes before applying them
- Configuration dependencies are unclear
- Risky configuration combinations go undetected

### Current Limitations
- Recon shows metrics and container health but not configuration intelligence
- Operators must manually correlate configs with issues
- No proactive warnings about misconfiguration
- Trial-and-error approach to configuration changes

---

## 2. Goals and Non-Goals

### Goals
1. **Detection** - Identify configurations deviating from recommended values
2. **Impact Analysis** - Predict consequences of configuration changes
3. **Dependency Mapping** - Visualize how configurations affect each other
4. **Historical Correlation** - Link config changes to incidents/performance
5. **Risk Detection** - Auto-detect dangerous configuration combinations

### Non-Goals (Phase 1)
- Auto-remediation of configuration issues
- Runtime configuration updates (read-only analysis)
- Configuration distribution/management (use existing tools)

---

## 3. Architecture Overview

### High-Level Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        Recon UI (React)                         │
├─────────────────────────────────────────────────────────────────┤
│  Configuration Dashboard  │  What-If Analyzer  │  Dependency    │
│  - Current vs Recommended │  - Impact Preview  │  Graph Viewer  │
│  - Risk Indicators        │  - Validation      │  - Related     │
│  - Change History         │  - Recommendations │  Configs       │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ REST API
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Configuration Analyzer Service               │
├─────────────────────────────────────────────────────────────────┤
│  1. Configuration Collector (ON-DEMAND)                         │
│     - Fetch configs from SCM, OM, DataNodes, Recon             │
│     - Triggered by: User request, Recon startup, component     │
│       restart detection                                         │
│     - Change detection (compare with last snapshot)             │
│                                                                  │
│  2. Knowledge Base Engine                                       │
│     - Configuration metadata (type, range, dependencies)        │
│     - Best practice rules                                       │
│     - Known risky combinations                                  │
│     - Workload-specific recommendations                         │
│                                                                  │
│  3. Impact Analyzer                                             │
│     - Dependency graph evaluation                               │
│     - "What-if" simulation                                      │
│     - Risk scoring                                              │
│     - Performance impact prediction                             │
│                                                                  │
│  4. Historical Correlator                                       │
│     - Config change timeline                                    │
│     - Correlate with incidents/metrics                          │
│     - Pattern detection                                         │
│     - Anomaly detection after config changes                    │
│                                                                  │
│  5. Recommendation Engine                                       │
│     - Generate actionable recommendations                       │
│     - Prioritize by impact                                      │
│     - Context-aware suggestions                                 │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Data Storage Layer                           │
├─────────────────────────────────────────────────────────────────┤
│  RocksDB Tables:                                                │
│  - CONFIG_SNAPSHOTS: Historical config values                   │
│  - CONFIG_CHANGES: Change events with timestamps                │
│  - CONFIG_RULES: Knowledge base rules                           │
│  - CONFIG_INCIDENTS: Config-related incidents                   │
│  - CONFIG_RECOMMENDATIONS: Active recommendations               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Components Detail

### 4.1 Configuration Collector

**Purpose**: Gather all configurations from Ozone components

**Data Sources**:
```java
// SCM Configuration
- Endpoint: /api/v1/scm/configuration
- Source: StorageContainerManager.getConfiguration()
- Trigger: User request, Recon startup, SCM restart detected
- Key configs: Replication, safe mode, pipeline, container

// OM Configuration
- Endpoint: /api/v1/om/configuration
- Source: OzoneManager.getConfiguration()
- Trigger: User request, Recon startup, OM restart detected
- Key configs: Namespace, quota, S3, bucket defaults

// DataNode Configuration (sampled)
- Endpoint: /api/v1/datanodes/{id}/configuration
- Source: HddsDatanodeService.getConfiguration()
- Trigger: User request (full analysis)
- Strategy: Sample 10% of nodes, flag inconsistencies

// Recon Configuration
- Source: ReconConfiguration (in-process)
- Trigger: User request, Recon startup
- Key configs: Task intervals, database, API
```

**Change Detection**:
```java
class ConfigurationSnapshot {
  String componentType;      // SCM, OM, DATANODE, RECON
  String componentId;        // hostname or service ID
  long timestamp;
  Map<String, String> configs;  // All config key-value pairs
  String configHash;         // MD5 of sorted config string
}

class ConfigurationChange {
  String configKey;
  String oldValue;
  String newValue;
  long changeTimestamp;
  String componentType;
  String componentId;
  ChangeSource source;       // RESTART, DYNAMIC_UPDATE, UNKNOWN
}
```

### 4.2 Knowledge Base Engine

**Purpose**: Store configuration metadata, rules, and best practices

**Configuration Metadata**:
```java
class ConfigurationMetadata {
  String key;                          // e.g., "ozone.scm.pipeline.creation.interval"
  ConfigType type;                     // INT, LONG, BOOLEAN, STRING, ENUM, SIZE, DURATION
  String defaultValue;
  Range validRange;                    // Min/max for numeric types
  List<String> validValues;            // For enum types
  String description;
  ConfigCategory category;             // PERFORMANCE, RELIABILITY, CAPACITY, SECURITY
  ComponentType component;             // SCM, OM, DATANODE, CLIENT
  boolean dynamicUpdate;               // Can be changed without restart
  List<String> dependencies;           // Related config keys
  List<String> conflicts;              // Incompatible config keys
  ImpactLevel impactLevel;             // LOW, MEDIUM, HIGH, CRITICAL
  String documentationUrl;
}

enum ConfigCategory {
  PERFORMANCE,      // Affects throughput, latency
  RELIABILITY,      // Affects data safety, availability
  CAPACITY,         // Affects storage utilization
  SECURITY,         // Affects authentication, authorization
  OPERATIONAL,      // Affects management, monitoring
  DEBUGGING         // Affects logging, diagnostics
}

enum ImpactLevel {
  LOW,              // Minimal impact, safe to change
  MEDIUM,           // Moderate impact, test recommended
  HIGH,             // Significant impact, careful testing required
  CRITICAL          // Cluster-wide impact, expert review needed
}
```

**Best Practice Rules**:
```java
class ConfigurationRule {
  String ruleId;
  String ruleName;
  String description;
  RuleType type;                       // RANGE, EQUALITY, RELATIONSHIP, COMBINATION
  RuleSeverity severity;               // INFO, WARNING, ERROR, CRITICAL

  // Rule definition
  String configKey;                    // Primary config key
  Predicate<String> condition;         // Rule logic
  String violationMessage;
  String recommendation;
  String reasoning;                    // Why this rule exists

  // Context
  List<WorkloadType> applicableWorkloads;  // ALL, S3_HEAVY, FSO_HEAVY, MIXED
  ClusterSize applicableClusterSize;       // SMALL, MEDIUM, LARGE, XLARGE

  // Evidence
  List<String> relatedJiras;           // Known issues related to this config
  List<String> documentationLinks;
}

enum RuleType {
  RANGE,           // Value must be within range
  EQUALITY,        // Value must equal something
  RELATIONSHIP,    // Config A must relate to Config B (e.g., A < B)
  COMBINATION,     // Multiple configs must satisfy constraint together
  PATTERN          // String value must match pattern
}

enum RuleSeverity {
  INFO,            // Informational, no action needed
  WARNING,         // Suboptimal, should consider changing
  ERROR,           // Likely to cause issues, should fix
  CRITICAL         // Dangerous, fix immediately
}

enum WorkloadType {
  ALL,
  S3_HEAVY,        // High S3 traffic
  FSO_HEAVY,       // High file system operations
  MIXED,           // Balanced workload
  WRITE_HEAVY,     // High write throughput
  READ_HEAVY       // High read throughput
}
```

**Example Rules**:
```java
// Rule 1: Safe mode threshold
ConfigurationRule rule1 = new ConfigurationRule(
  ruleId: "SCM-001",
  ruleName: "Safe Mode Threshold Check",
  description: "hdds.scm.safemode.threshold.pct should be between 0.95 and 0.999",
  type: RuleType.RANGE,
  severity: RuleSeverity.ERROR,
  configKey: "hdds.scm.safemode.threshold.pct",
  condition: value -> {
    double val = Double.parseDouble(value);
    return val >= 0.95 && val <= 0.999;
  },
  violationMessage: "Safe mode threshold is outside recommended range",
  recommendation: "Set hdds.scm.safemode.threshold.pct to 0.99",
  reasoning: "Too low: SCM exits safe mode prematurely. Too high: SCM stuck in safe mode.",
  relatedJiras: ["HDDS-4567", "HDDS-5678"]
);

// Rule 2: Pipeline vs container relationship
ConfigurationRule rule2 = new ConfigurationRule(
  ruleId: "SCM-002",
  ruleName: "Pipeline Creation Interval vs Container Creation",
  type: RuleType.RELATIONSHIP,
  severity: RuleSeverity.WARNING,
  condition: (pipelineInterval, containerCreationInterval) -> {
    // Pipeline creation should be more frequent than container creation
    return pipelineInterval < containerCreationInterval * 2;
  },
  recommendation: "Adjust ozone.scm.pipeline.creation.interval to be less than 2x container creation interval"
);

// Rule 3: Risky combination
ConfigurationRule rule3 = new ConfigurationRule(
  ruleId: "DATANODE-001",
  ruleName: "Dangerous Cache + Handler Combination",
  type: RuleType.COMBINATION,
  severity: RuleSeverity.CRITICAL,
  condition: (cacheSize, handlerCount) -> {
    // If cache is small and handlers are many, memory pressure
    long cache = parseSize(cacheSize);
    int handlers = Integer.parseInt(handlerCount);
    return cache / handlers > 10 * 1024 * 1024; // 10MB per handler minimum
  },
  violationMessage: "Cache size too small for handler count - risk of OOM",
  recommendation: "Increase dfs.container.chunk.write.cache.size or reduce dfs.container.ipc.handlers"
);
```

### 4.3 Impact Analyzer

**Purpose**: Predict impact of configuration changes

**Dependency Graph**:
```java
class ConfigurationDependencyGraph {
  // Directed graph: A -> B means "A affects B"
  Map<String, List<ConfigDependency>> adjacencyList;

  void addDependency(String fromConfig, String toConfig,
                     DependencyType type, double impactWeight);

  List<String> getAffectedConfigs(String configKey);
  List<String> getDependentConfigs(String configKey);
  ImpactAssessment analyzeChangeImpact(String configKey, String newValue);
}

class ConfigDependency {
  String targetConfig;
  DependencyType type;
  double impactWeight;      // 0.0 to 1.0
  String relationship;      // Human-readable description
}

enum DependencyType {
  DIRECT,          // A directly controls B
  INDIRECT,        // A affects component that affects B
  CONSTRAINT,      // A constrains valid values of B
  PERFORMANCE,     // A affects performance of B
  SCALING          // A scales with B
}
```

**What-If Analyzer**:
```java
class WhatIfAnalyzer {
  /**
   * Simulate impact of configuration change
   */
  ImpactAssessment analyzeChange(
      String configKey,
      String currentValue,
      String proposedValue,
      ClusterContext context
  );

  /**
   * Analyze multiple changes together
   */
  ImpactAssessment analyzeChangeSet(
      Map<String, String> proposedChanges,
      ClusterContext context
  );
}

class ImpactAssessment {
  String configKey;
  String currentValue;
  String proposedValue;

  // Validation
  boolean isValid;
  List<ValidationError> errors;

  // Impact
  ImpactLevel overallImpact;
  List<ImpactDetail> impacts;

  // Affected components
  List<String> affectedComponents;
  List<String> affectedConfigs;

  // Risk
  RiskLevel riskLevel;
  List<Risk> risks;

  // Recommendations
  List<String> recommendations;
  List<String> warnings;

  // Performance prediction
  PerformanceImpact performanceImpact;
}

class ImpactDetail {
  String component;          // Which component is affected
  ImpactType type;           // PERFORMANCE, RELIABILITY, CAPACITY, etc.
  ImpactDirection direction; // POSITIVE, NEGATIVE, NEUTRAL
  double magnitude;          // 0.0 to 1.0
  String description;
}

enum ImpactDirection {
  POSITIVE,    // Improves the aspect
  NEGATIVE,    // Degrades the aspect
  NEUTRAL      // No significant change
}

class PerformanceImpact {
  // Predicted performance changes
  Double throughputChangePercent;    // +10% or -5%
  Double latencyChangePercent;
  Double memoryChangePercent;
  Double cpuChangePercent;

  // Confidence
  double confidence;         // 0.0 to 1.0
  String reasoning;
}

class Risk {
  RiskLevel level;
  String description;
  List<String> mitigations;
  List<String> relatedIncidents;  // Historical incidents with this change
}

enum RiskLevel {
  LOW,       // Safe to change
  MEDIUM,    // Should test first
  HIGH,      // Requires careful planning
  CRITICAL   // Could cause outage
}
```

**Example Analysis**:
```java
// Example: Analyze increasing pipeline creation interval
ImpactAssessment assessment = whatIfAnalyzer.analyzeChange(
  configKey: "ozone.scm.pipeline.creation.interval",
  currentValue: "60s",
  proposedValue: "300s",
  context: clusterContext
);

// Result:
assessment = {
  isValid: true,
  overallImpact: ImpactLevel.MEDIUM,
  impacts: [
    {
      component: "SCM",
      type: PERFORMANCE,
      direction: NEGATIVE,
      magnitude: 0.3,
      description: "Slower pipeline creation may delay write operations during pipeline unavailability"
    },
    {
      component: "SCM",
      type: RELIABILITY,
      direction: POSITIVE,
      magnitude: 0.2,
      description: "Reduced pipeline churn improves stability"
    }
  ],
  affectedConfigs: [
    "ozone.scm.pipeline.allocated.timeout",
    "ozone.scm.container.creation.lease.timeout"
  ],
  riskLevel: MEDIUM,
  risks: [
    {
      level: MEDIUM,
      description: "Write operations may experience delays if pipelines fail frequently",
      mitigations: [
        "Monitor pipeline health closely after change",
        "Ensure pipeline failure rate is < 1%",
        "Consider staged rollout"
      ]
    }
  ],
  recommendations: [
    "Test with 180s first to find optimal balance",
    "Monitor pipeline creation rate and write latency",
    "Ensure ozone.scm.pipeline.allocated.timeout is at least 2x this value"
  ],
  performanceImpact: {
    throughputChangePercent: -5.0,  // Slight decrease
    latencyChangePercent: +10.0,    // Increase in p99 write latency
    confidence: 0.7
  }
}
```

### 4.4 Historical Correlator

**Purpose**: Link configuration changes to cluster behavior

**Data Model**:
```java
class ConfigChangeEvent {
  long eventId;
  long timestamp;
  String configKey;
  String oldValue;
  String newValue;
  String component;
  String triggeredBy;        // RESTART, ADMIN_UPDATE, DEPLOYMENT

  // Correlation window (30 minutes after change)
  List<IncidentCorrelation> correlatedIncidents;
  List<MetricAnomaly> correlatedAnomalies;
  PerformanceChange performanceChange;
}

class IncidentCorrelation {
  String incidentId;
  long incidentTimestamp;
  String incidentType;       // CONTAINER_UNHEALTHY, PIPELINE_FAILURE, etc.
  double correlationScore;   // 0.0 to 1.0 (how related)
  String correlationReason;
}

class MetricAnomaly {
  String metricName;
  double baselineValue;
  double observedValue;
  double deviationPercent;
  long detectedAt;
}

class PerformanceChange {
  // Compare 30min before vs 30min after config change
  double throughputChange;
  double latencyChange;
  double errorRateChange;
}
```

**Correlation Engine**:
```java
class HistoricalCorrelator {
  /**
   * Find config changes that happened before an incident
   */
  List<ConfigChangeEvent> findRelatedChanges(
      Incident incident,
      Duration lookbackWindow
  );

  /**
   * Analyze impact of a historical config change
   */
  ChangeImpactAnalysis analyzeHistoricalChange(
      ConfigChangeEvent change
  );

  /**
   * Find patterns: "Every time config X changed to Y, incident Z occurred"
   */
  List<ConfigChangePattern> detectPatterns();
}

class ConfigChangePattern {
  String configKey;
  String valuePattern;           // e.g., "< 100MB"
  String subsequentIssue;        // e.g., "OOM in DataNode"
  int occurrenceCount;
  double confidence;             // How confident we are this is causal
  List<Long> exampleEventIds;
}
```

### 4.5 Recommendation Engine

**Purpose**: Generate actionable recommendations

```java
class Recommendation {
  String id;
  long timestamp;
  RecommendationType type;
  RuleSeverity severity;

  // What's wrong
  String issue;
  String configKey;
  String currentValue;

  // What to do
  String recommendedValue;
  String action;
  String reasoning;

  // Context
  double impactScore;            // 0.0 to 1.0
  ImpactAssessment impactAnalysis;
  List<String> benefits;
  List<String> risks;

  // Evidence
  List<String> relatedJiras;
  List<String> historicalIncidents;
  String documentationLink;

  // Tracking
  RecommendationStatus status;   // ACTIVE, DISMISSED, APPLIED, RESOLVED
  String dismissReason;
}

enum RecommendationType {
  VIOLATION,         // Rule violation detected
  OPTIMIZATION,      // Can improve performance
  RISK_MITIGATION,   // Reduce risk
  BEST_PRACTICE,     // Align with best practices
  WORKLOAD_SPECIFIC  // Tailored to current workload
}

enum RecommendationStatus {
  ACTIVE,      // Current recommendation
  DISMISSED,   // User dismissed
  APPLIED,     // User applied the recommendation
  RESOLVED     // Issue no longer exists
}
```

**Recommendation Prioritization**:
```java
class RecommendationEngine {
  List<Recommendation> generateRecommendations(
      Map<String, String> currentConfig,
      ClusterContext context
  );

  // Prioritize by: severity, impact, evidence
  List<Recommendation> prioritizeRecommendations(
      List<Recommendation> recommendations
  );
}

// Prioritization algorithm
double calculatePriority(Recommendation rec) {
  double severityWeight = getSeverityWeight(rec.severity);
  double impactWeight = rec.impactScore;
  double evidenceWeight = getEvidenceWeight(rec);

  return (severityWeight * 0.5) +
         (impactWeight * 0.3) +
         (evidenceWeight * 0.2);
}
```

---

## 5. Data Flow

### 5.1 Configuration Collection Flow (ON-DEMAND)
```
Triggered by: User clicks "Analyze Now", Recon startup, or component restart detected:
  1. ConfigurationAnalyzer.analyze()
  2. Fetch configs from SCM, OM, sample DataNodes
  3. Compute MD5 hash of each component's config
  4. Compare with last snapshot hash (if exists)
  5. If changed or first analysis:
     - Store new ConfigurationSnapshot
     - Detect specific changes (if previous snapshot exists)
     - Store ConfigurationChange events
     - Trigger rule evaluation
  6. Store in RocksDB
  7. Return analysis results to UI
```

### 5.2 Analysis Flow
```
On config change detected OR on-demand API call:
  1. Load current configuration
  2. Load knowledge base rules
  3. Evaluate each rule against current config
  4. For violations:
     - Create Recommendation
     - Calculate impact score
     - Generate suggested fix
  5. Store recommendations
  6. Update dashboard
```

### 5.3 What-If Analysis Flow
```
User requests "What if I change X to Y?":
  1. WhatIfAnalyzer.analyzeChange(X, Y)
  2. Validate new value against rules
  3. Load dependency graph
  4. Find all affected configs
  5. Calculate impact for each affected area
  6. Load historical data for similar changes
  7. Predict performance impact
  8. Calculate risk score
  9. Generate warnings and recommendations
  10. Return ImpactAssessment to UI
```

---

## 6. Database Schema

### RocksDB Tables

```java
// Table 1: CONFIG_SNAPSHOTS
// Key: componentType|componentId|timestamp
// Value: ConfigurationSnapshot (serialized)
Table<String, ConfigurationSnapshot> CONFIG_SNAPSHOTS;

// Table 2: CONFIG_CHANGES
// Key: timestamp|componentType|configKey
// Value: ConfigurationChange (serialized)
Table<String, ConfigurationChange> CONFIG_CHANGES;

// Table 3: CONFIG_METADATA
// Key: configKey
// Value: ConfigurationMetadata (serialized)
Table<String, ConfigurationMetadata> CONFIG_METADATA;

// Table 4: CONFIG_RULES
// Key: ruleId
// Value: ConfigurationRule (serialized)
Table<String, ConfigurationRule> CONFIG_RULES;

// Table 5: CONFIG_RECOMMENDATIONS
// Key: recommendationId
// Value: Recommendation (serialized)
Table<String, Recommendation> CONFIG_RECOMMENDATIONS;

// Table 6: CONFIG_INCIDENTS
// Key: configKey|timestamp
// Value: List<IncidentCorrelation> (serialized)
Table<String, List<IncidentCorrelation>> CONFIG_INCIDENTS;
```

---

## 7. REST API Design

```java
// Configuration Analysis Endpoints

GET /api/v1/config/current
  → Returns: Current configuration for all components

GET /api/v1/config/current/{component}
  → Returns: Current configuration for specific component (SCM, OM, etc.)

GET /api/v1/config/recommendations
  → Returns: List of active recommendations, prioritized

GET /api/v1/config/violations
  → Returns: Current configuration violations

POST /api/v1/config/whatif
  Body: { "changes": { "key1": "newValue1", "key2": "newValue2" } }
  → Returns: ImpactAssessment

GET /api/v1/config/history?configKey={key}&startTime={ts}&endTime={ts}
  → Returns: Configuration change history

GET /api/v1/config/dependencies/{configKey}
  → Returns: Dependency graph for a configuration

GET /api/v1/config/correlation/{configKey}?startTime={ts}&endTime={ts}
  → Returns: Incidents correlated with config changes

POST /api/v1/config/recommendations/{id}/dismiss
  Body: { "reason": "Not applicable to our workload" }
  → Dismiss a recommendation

GET /api/v1/config/metadata/{configKey}
  → Returns: Metadata for a configuration (description, type, valid range, etc.)
```

---

## 8. UI Components

### 8.1 Configuration Dashboard
```
┌─────────────────────────────────────────────────────────────┐
│  Configuration Health Dashboard                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Overall Health: ⚠️ WARNING (3 critical, 5 warnings)        │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⛔ CRITICAL ISSUES (3)                                │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ 1. hdds.scm.safemode.threshold.pct = 0.85           │  │
│  │    Recommended: 0.99                                  │  │
│  │    Risk: SCM may exit safe mode prematurely          │  │
│  │    [View Details] [Apply Fix]                        │  │
│  │                                                       │  │
│  │ 2. dfs.container.chunk.write.cache.size = 50MB      │  │
│  │    Recommended: 256MB                                 │  │
│  │    Risk: OOM under high write load                   │  │
│  │    [View Details] [What-If Analysis]                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⚠️  WARNINGS (5)                                      │  │
│  │ [Show All]                                            │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Recent Configuration Changes (Last 24h)              │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ 2h ago: ozone.scm.pipeline.creation.interval         │  │
│  │         60s → 180s (SCM restart)                      │  │
│  │         Impact: -3% write throughput ⚠️               │  │
│  │         [View Correlation]                            │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 8.2 What-If Analyzer
```
┌─────────────────────────────────────────────────────────────┐
│  Configuration What-If Analyzer                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Configuration Key:                                          │
│  [ozone.scm.pipeline.creation.interval          ▼]          │
│                                                              │
│  Current Value: 60s                                          │
│  Proposed Value: [300s                          ]            │
│                                                              │
│  [Analyze Impact]                                            │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 📊 Impact Assessment                                  │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ Overall Impact: MEDIUM                                │  │
│  │ Risk Level: MEDIUM                                    │  │
│  │                                                       │  │
│  │ ✅ Benefits:                                          │  │
│  │   • Reduced SCM load (-20% pipeline churn)           │  │
│  │   • Improved pipeline stability                      │  │
│  │                                                       │  │
│  │ ⚠️  Risks:                                            │  │
│  │   • Write latency may increase by ~10% (p99)         │  │
│  │   • Slower recovery from pipeline failures           │  │
│  │                                                       │  │
│  │ 🔗 Affected Configurations:                           │  │
│  │   • ozone.scm.pipeline.allocated.timeout             │  │
│  │     (should be increased proportionally)             │  │
│  │                                                       │  │
│  │ 📈 Performance Prediction:                            │  │
│  │   Throughput: -5% ⚠️                                  │  │
│  │   Latency (p99): +10% ⚠️                              │  │
│  │   Confidence: 70%                                     │  │
│  │                                                       │  │
│  │ 💡 Recommendations:                                   │  │
│  │   1. Test with 180s first                            │  │
│  │   2. Monitor pipeline health closely                 │  │
│  │   3. Increase pipeline.allocated.timeout to 600s     │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 8.3 Dependency Graph Viewer
```
┌─────────────────────────────────────────────────────────────┐
│  Configuration Dependency Graph                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Selected: ozone.scm.pipeline.creation.interval             │
│                                                              │
│         [ozone.scm.pipeline.allocated.timeout]              │
│                      ↑ (must be > 2x)                       │
│                      |                                      │
│    [ozone.scm.pipeline.creation.interval] ← YOU ARE HERE    │
│                      |                                      │
│                      ↓ (affects)                            │
│         [ozone.scm.container.creation.lease.timeout]        │
│                      |                                      │
│                      ↓ (affects)                            │
│              [Write Operation Latency]                      │
│                                                              │
│  Legend:                                                     │
│  ─── Direct Dependency                                      │
│  ┈┈┈ Indirect Dependency                                    │
│                                                              │
│  [Export Graph] [View All Dependencies]                     │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Implementation Phases

### Phase 1: Foundation (4 weeks)
**Goal**: Basic configuration collection and rule evaluation

**Deliverables**:
1. Configuration Collector
   - Fetch configs from SCM, OM
   - Store snapshots in RocksDB
   - Change detection
2. Knowledge Base
   - Configuration metadata for top 50 configs
   - 20 critical rules (safe mode, replication, capacity)
3. Basic REST API
   - GET /config/current
   - GET /config/violations
4. Simple Dashboard
   - Show current config violations
   - List recommendations

**Success Metrics**:
- Detect configuration changes within 5 minutes
- Identify top 20 configuration issues

### Phase 2: Impact Analysis (4 weeks)
**Goal**: What-if analysis and dependency mapping

**Deliverables**:
1. Dependency Graph Engine
   - Map 100 key configuration dependencies
   - Graph traversal for impact analysis
2. What-If Analyzer
   - Validation engine
   - Impact prediction
   - Risk assessment
3. Enhanced API
   - POST /config/whatif
   - GET /config/dependencies/{key}
4. UI Components
   - What-If Analyzer interface
   - Dependency graph visualization

**Success Metrics**:
- Predict impact with >70% accuracy
- Provide actionable recommendations for 80% of changes

### Phase 3: Historical Intelligence (3 weeks)
**Goal**: Correlate config changes with incidents

**Deliverables**:
1. Historical Correlator
   - Store config change timeline
   - Detect anomalies after changes
   - Pattern detection
2. Incident Integration
   - Link container health issues to config changes
   - Link pipeline failures to config changes
3. Enhanced UI
   - Config change timeline
   - Correlation visualization

**Success Metrics**:
- Identify root cause config for >50% of incidents
- Build pattern database with >100 patterns

### Phase 4: Advanced Features (4 weeks)
**Goal**: Workload-specific recommendations and auto-learning

**Deliverables**:
1. Workload Profiler
   - Detect workload type (S3-heavy, FSO-heavy, etc.)
   - Tailor recommendations to workload
2. Performance Predictor
   - ML model for performance prediction
   - Historical performance correlation
3. Auto-Learning Rules
   - Learn cluster-specific baselines
   - Adapt recommendations to cluster behavior
4. Complete UI
   - Workload dashboard
   - Performance predictions
   - Custom rule editor

**Success Metrics**:
- Workload detection accuracy >80%
- Performance prediction accuracy >70%
- User satisfaction >4/5

---

## 10. Success Metrics

### Technical Metrics
- **Detection Rate**: % of configuration issues detected automatically
- **False Positive Rate**: < 10%
- **Analysis Latency**: What-if analysis completes in < 2 seconds
- **Prediction Accuracy**: Impact prediction correct in >70% of cases
- **Coverage**: Support for >200 critical configurations

### Business Metrics
- **Time to Resolution**: Reduce config issue debugging time by 50%
- **Incident Prevention**: Catch 80% of config issues before they cause incidents
- **Operator Confidence**: >80% of operators trust recommendations
- **Adoption**: Used by >70% of Ozone operators within 6 months

---

## 11. Security and Privacy

### Configuration Data Security
- Configuration values may contain sensitive information (passwords, keys)
- **Approach**:
  - Mask sensitive config values (detect by key pattern)
  - Store only hashes for sensitive configs
  - Access control on configuration API endpoints

### Recommendation Privacy
- Don't expose internal cluster details in error messages
- Sanitize recommendations before display

---

## 12. Testing Strategy

### Unit Tests
- Rule evaluation logic
- Dependency graph traversal
- Impact calculation algorithms
- Risk scoring

### Integration Tests
- End-to-end config collection
- Database persistence
- API endpoints
- What-if analysis accuracy

### Validation Tests
- Test against known configuration issues from production
- Verify recommendations match expert advice
- Historical validation: Apply analyzer to past incidents

### Performance Tests
- Handle 1000+ configuration keys
- What-if analysis latency < 2s
- Support 100 concurrent API requests

---

## 13. Future Enhancements

### Phase 5+ Ideas
1. **Auto-Remediation**
   - Automatically apply safe configuration fixes
   - Require approval for risky changes

2. **Configuration Templates**
   - Pre-built configs for common workloads
   - One-click application

3. **Drift Detection**
   - Detect when cluster config drifts from desired state
   - Configuration as Code integration

4. **Multi-Cluster Comparison**
   - Compare configs across clusters
   - Learn from high-performing clusters

5. **Cost Optimization**
   - Recommend configs to reduce resource usage
   - Balance performance vs cost

6. **AI-Powered Insights**
   - Use LLM to explain configuration issues in plain language
   - Natural language config queries

---

## 14. Open Questions

1. **Configuration Source of Truth**: Should we support fetching configs directly from config files, or only from running services?

2. **Dynamic vs Static Configs**: How to handle configs that require restart vs dynamic updates?

3. **Multi-Cluster Support**: Should this work across multiple Ozone clusters?

4. **Historical Data Retention**: How long to keep configuration history? (Propose: 90 days)

5. **Rule Authoring**: Should we provide a UI for custom rules, or only code-based rules initially?

---

## 15. Dependencies

### Internal Dependencies
- Recon task scheduler (for periodic collection)
- Recon RocksDB (for storage)
- SCM/OM APIs (for config fetching)
- Existing container health data (for correlation)

### External Dependencies
- None (self-contained in Recon)

---

## 16. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Inaccurate predictions | Medium | Validate against historical data; show confidence scores |
| Performance overhead | Low | Async collection; efficient storage; caching |
| False positives | Medium | Extensive testing; user feedback loop; rule tuning |
| Knowledge base maintenance | High | Community contribution model; automated rule learning |
| Complexity creep | Medium | Phased implementation; MVP first; iterate based on feedback |

---

## 17. Summary

The **Configuration Impact Analyzer** will transform Recon from a passive monitoring tool into an intelligent configuration management platform. By combining:

1. **Real-time configuration analysis** with best practice rules
2. **What-if simulation** for safe experimentation
3. **Historical correlation** to learn from past incidents
4. **Actionable recommendations** to guide operators

We will significantly reduce configuration-related incidents, improve operator confidence, and accelerate troubleshooting time from hours to minutes.

**Key Differentiator**: This feature provides *insights and answers*, not just data - making it a true intelligent debugging platform.

---

**Next Steps**:
1. Review and approve high-level design
2. Detail Phase 1 implementation plan
3. Create JIRA tickets for Phase 1
4. Begin implementation