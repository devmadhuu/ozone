# Configuration Analyzer Refactoring Summary

## Changes Made

### 1. Removed Hardcoded JMX Bean Names ✅

**Problem**: `JMXMetricFetcher.fetchAllVolumeMetrics()` had hardcoded JMX bean pattern:
```java
String jmxUrl = String.format("http://%s:%d/jmx?qry=Hadoop:service=HddsDatanode,name=VolumeInfoMetrics-*", ...);
```

**Solution**: Made bean pattern configurable via rule's `dataSources`:
```java
public List<VolumeMetrics> fetchAllVolumeMetrics(String nodeHost, int jmxPort, String beanPattern) {
  // Replace {volumePath} placeholder with wildcard
  String beanQuery = beanPattern.replace("{volumePath}", "*");
  String jmxUrl = String.format("http://%s:%d/jmx?qry=%s", nodeHost, jmxPort, beanQuery);
  ...
}
```

**Files Modified**:
- `JMXMetricFetcher.java:126-135`

---

### 2. Moved DataSources from Rule to Validation Level ✅

**Problem**: DataSources were defined at rule level, but different validations within the same rule might need different data sources.

**Before** (Rule-level):
```json
{
  "ruleId": "DN-001",
  "dataSources": {
    "volumeCapacity": { "source": "JMX", "bean": "..." }
  },
  "validations": [
    { "id": "DN-001-V1", "expression": "..." },
    { "id": "DN-001-V2", "expression": "..." }
  ]
}
```

**After** (Validation-level):
```json
{
  "ruleId": "DN-001",
  "validations": [
    {
      "id": "DN-001-V1",
      "dataSources": {
        "volumeCapacity": {
          "source": "JMX",
          "bean": "Hadoop:service=VolumeInfoMetrics-{volumePath},name=VolumeInfoMetrics"
        }
      },
      "expression": "..."
    }
  ]
}
```

**Benefits**:
- More flexible - different validations can have different data requirements
- Better separation of concerns - each validation declares its own dependencies
- Supports validations that don't need any external data sources

**Files Modified**:
- `ValidationRule.java` - Added `dataSources` field
- `ContextAwareRuleEvaluator.java` - Updated to read from validation-level dataSources
- `config-analyzer-rules.json` - Moved dataSources from rule DN-001 to its validations

---

### 3. Updated Rule Evaluator Logic ✅

**Changes in `ContextAwareRuleEvaluator.java`**:

1. **`requiresPerVolumeIteration()`** - Now checks validation-level dataSources:
```java
for (ValidationRule validation : validations) {
  Map<String, DataSource> dataSources = validation.getDataSources();
  // Check if any validation has PER_VOLUME JMX dataSources
}
```

2. **`evaluatePerVolumeRule()`** - Iterates validations to extract bean pattern:
```java
for (ValidationRule validation : validations) {
  String beanPattern = extractJMXBeanPattern(validation);
  // Fetch metrics using this validation's bean pattern
  jmxFetcher.fetchAllVolumeMetrics(dnEndpoint.getHostname(), dnEndpoint.getJmxPort(), beanPattern);
}
```

3. **`extractJMXBeanPattern()`** - Changed signature from `ConfigurationRule` to `ValidationRule`:
```java
private String extractJMXBeanPattern(ValidationRule validation) {
  // Extract bean pattern from validation's dataSources
}
```

4. **`createValidationContext()` & `createVolumeValidationContext()`** - Priority-based resolution:
```java
// Try validation-level dataSources first
if (validation.getDataSources() != null && !validation.getDataSources().isEmpty()) {
  // Use validation dataSources
} else if (rule.getDataSources() != null) {
  // Fallback to rule-level dataSources for backward compatibility
}
```

---

### 4. Backward Compatibility ✅

The implementation maintains **full backward compatibility**:
- Rules can still have dataSources at rule level
- Validation-level dataSources take priority
- Falls back to rule-level if validation doesn't define its own

This allows gradual migration of existing rules.

---

## Configuration Structure

### Validation-Level DataSource Definition

```json
{
  "id": "DN-001-V1",
  "name": "Reserved Space Not Exceeding Volume Capacity",
  "dataSources": {
    "volumeCapacity": {
      "name": "volumeCapacity",
      "source": "JMX",
      "bean": "Hadoop:service=VolumeInfoMetrics-{volumePath},name=VolumeInfoMetrics",
      "attribute": "TotalCapacity",
      "scope": "PER_VOLUME"
    }
  },
  "expression": "configValueParsed < volumeCapacity",
  "severity": "WARNING",
  "message": "..."
}
```

**Key Fields**:
- `source`: Data source type (`JMX`, `CONFIG`, `METRICS`, etc.)
- `bean`: JMX bean pattern with `{volumePath}` placeholder
- `attribute`: JMX attribute to fetch
- `scope`: `PER_VOLUME` for volume-level iteration, `NODE` for node-level

**Placeholder Replacement**:
- `{volumePath}` is replaced with `*` for wildcard JMX queries
- Example: `VolumeInfoMetrics-{volumePath}` → `VolumeInfoMetrics-*`

---

## Testing Recommendations

1. **Verify JMX Query Pattern**:
   - Check that JMX queries use the configured bean pattern
   - Verify `{volumePath}` placeholder is replaced correctly

2. **Test Per-Volume Evaluation**:
   - Ensure DN-001 rule evaluates all volumes on all DataNodes
   - Verify each volume gets validated with its actual capacity from JMX

3. **Test Backward Compatibility**:
   - Rules without validation-level dataSources should still work
   - Rule-level dataSources should be used as fallback

4. **Test DataNode Discovery**:
   - Verify DataNodes register with Recon after config changes
   - Check that `nodeManager.getAllNodes()` returns registered DataNodes
   - Test JMX port discovery from DataNode HTTP port

---

## Benefits

1. **Configurability**: JMX bean patterns are now defined in rules JSON, not hardcoded
2. **Flexibility**: Each validation can have different data source requirements
3. **Maintainability**: Changes to JMX structure only require JSON updates, not code changes
4. **Extensibility**: Easy to add new validations with different JMX beans
5. **Backward Compatible**: Existing rules continue to work during migration

---

## Related Changes

### Fixed DataNode Registration Issue ✅
**Problem**: DataNodes weren't registering with Recon in HA SCM setup
**Root Cause**: Missing `ozone.recon.address` configuration in `ozone-balancer/docker-config`
**Fix**: Added Recon endpoint configuration to docker-config
**File**: `hadoop-ozone/dist/src/main/compose/ozone-balancer/docker-config`

### Fixed Hardcoded JMX Port ✅
**Problem**: JMX port was hardcoded as 9882
**Fix**: Now dynamically queries DataNode's HTTP port (which serves JMX)
**File**: `ContextAwareRuleEvaluator.java` - `getDataNodeEndpoints()` method

---

## Summary

✅ **JMX bean patterns** - Now configurable via dataSources
✅ **DataSources** - Moved to validation level for flexibility
✅ **Backward compatibility** - Maintained with fallback to rule-level dataSources
✅ **DataNode registration** - Fixed by adding Recon configuration
✅ **JMX port discovery** - Dynamically queries from DataNode info

The Config Analyzer is now more flexible, maintainable, and production-ready!