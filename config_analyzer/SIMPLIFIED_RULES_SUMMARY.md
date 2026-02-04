# Simplified Rules JSON Structure

## Overview
Based on internal team feedback, the configuration analyzer rules have been simplified and organized per component.

## Key Simplifications

### 1. **Reduced Complexity**
**Before (CSV with 17 fields):**
- rule_id, rule_name, component, feature_area, config_key, config_type, rule_type
- validation_expression, source_data_type, source_data_value, source_data_unit
- severity, violation_message, recommendation, reasoning
- applicable_workloads, related_jiras

**After (JSON with essential fields only):**
- id, name, configKey
- severity, validation
- message, recommendation, impact

### 2. **Component-Specific Files**
Rules are now organized into separate files:
- `datanode-rules.json` - 6 DataNode rules
- `om-rules.json` - 4 OM rules
- `scm-rules.json` - 5 SCM rules
- `recon-rules.json` - 4 Recon rules

### 3. **Simplified Validation Types**

**Before:**
Complex validation with multiple source data types (STATIC_THRESHOLD, LIVE_METRIC, API_RESPONSE, DERIVED)

**After:**
Simple validation types:
- **RANGE** - Value must be between min and max
- **MIN_VALUE** - Value must be at least minimum
- **RELATIONSHIP** - Config relates to another config
- **BOOLEAN** - Boolean flag recommendation

### 4. **Clear Structure**

```json
{
  "id": "DN-HANDLER-001",
  "name": "Handler Count Range",
  "configKey": "hdds.datanode.handler.count",
  "severity": "WARNING",
  "validation": {
    "type": "RANGE",
    "min": 10,
    "max": 100
  },
  "message": "Handler count should be between 10 and 100",
  "recommendation": "Set hdds.datanode.handler.count between 10-100 based on workload",
  "impact": "Too few handlers cause request queuing. Too many cause context switching"
}
```

## Validation Types Explained

### RANGE Validation
```json
{
  "type": "RANGE",
  "min": 10,
  "max": 100
}
```
Checks if config value is between min and max.

### MIN_VALUE Validation
```json
{
  "type": "MIN_VALUE",
  "min": "256MB"
}
```
Checks if config value is at least the minimum. Supports size units (KB, MB, GB, TB) and duration units (s, m, h).

### RELATIONSHIP Validation
```json
{
  "type": "RELATIONSHIP",
  "rule": "heartbeat_timeout >= heartbeat_interval * 3",
  "requiredConfigs": ["hdds.heartbeat.timeout"]
}
```
Checks relationship between multiple configs.

### BOOLEAN Validation
```json
{
  "type": "BOOLEAN",
  "recommended": false
}
```
Recommends a specific boolean value.

### Context-Aware Validation
```json
{
  "type": "RANGE",
  "min": "10%",
  "context": "volume_capacity"
}
```
Validation adapts based on runtime context (volume size, CPU cores, etc.).

## Benefits of Simplification

1. **Easier to Read** - Admin users can understand rules at a glance
2. **Easier to Write** - Creating custom rules requires less boilerplate
3. **Easier to Maintain** - Fewer fields mean simpler updates
4. **Component-Focused** - Each component team owns their rules
5. **CLI-Friendly** - Simple structure works well with CLI output formats

## Migration from CSV

The original CSV prototype rules have been converted:
- DN-CACHE-001: Cache Size vs Handler Count ✓
- DN-STOR-001: Disk Reserved Space Check ✓
- DN-HB-001: Heartbeat Configuration ✓
- DN-PERF-001: Container Delete Threads ✓
- DN-IO-001: Chunk Size Configuration ✓

All rules retain their core validation logic but with cleaner syntax.

## Next Steps

1. ✓ Create simplified JSON structure
2. ✓ Break rules per component (DN, OM, SCM, Recon)
3. ⏭️ Implement CLI commands: `ozone admin <component> config-analyzer`
4. ⏭️ Add JSON and table output formats
5. ⏭️ Integrate with existing config validation logic
