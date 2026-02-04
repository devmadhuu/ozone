# New DataNode Rules Added

## Summary
Added 3 new DataNode configuration rules to validate reserved space settings and prevent common misconfigurations.

## New Rules

### 1. DN-STOR-002: Reserved Space Percentage Range
**Config Key**: `hdds.datanode.dir.du.reserved.percent`  
**Severity**: WARNING  
**Validation Type**: PERCENTAGE_RANGE (5% - 20%)

**Description**: Validates that the reserved space percentage is within the recommended range of 5-20%.

**Why This Matters**:
- **Too Low (<5%)**: Risk of disk full errors, OS instability
- **Too High (>20%)**: Wasted storage capacity, inefficient resource utilization

**Example Violations**:
```xml
<!-- BAD: Too low -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.02</value>  <!-- 2% - TOO LOW -->
</property>

<!-- BAD: Too high -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.35</value>  <!-- 35% - TOO HIGH -->
</property>

<!-- GOOD -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.10</value>  <!-- 10% - GOOD -->
</property>
```

### 2. DN-STOR-003: Reserved Space Not Exceeding Total Capacity
**Config Key**: `hdds.datanode.dir.du.reserved`  
**Severity**: CRITICAL  
**Validation Type**: CAPACITY_CHECK (requires runtime data)

**Description**: Ensures that the absolute reserved space value doesn't exceed the total DataNode capacity (sum of all volumes).

**Why This Matters**:
- **Critical Issue**: DataNode will fail to start or all volumes become read-only
- **Common Mistake**: Setting reserved space in bytes without considering actual disk capacity
- **Production Impact**: Can cause complete DataNode unavailability

**Example Scenario**:
```
DataNode has 3 volumes:
- /data/vol1: 1TB
- /data/vol2: 1TB  
- /data/vol3: 1TB
Total Capacity: 3TB

BAD Configuration:
hdds.datanode.dir.du.reserved = 5TB  ❌ EXCEEDS TOTAL!

GOOD Configuration:
hdds.datanode.dir.du.reserved = 300GB  ✓ (10% of 3TB)
```

**Note**: This rule requires runtime data (actual volume capacities) and will show an INFO message when run via CLI. Full validation is available when integrated with Recon.

### 3. DN-STOR-004: Reserved Space Within 30% Limit
**Config Key**: `hdds.datanode.dir.du.reserved`  
**Severity**: WARNING  
**Validation Type**: CAPACITY_CHECK (requires runtime data)

**Description**: Warns if reserved space exceeds 30% of total DataNode capacity, indicating potential over-reservation.

**Why This Matters**:
- **Capacity Waste**: Over 30% reservation significantly reduces usable storage
- **Cost Impact**: Paying for storage you can't use
- **Best Practice**: Most deployments need 10-20% reservation

**Example Scenario**:
```
DataNode Total Capacity: 10TB

WARNING Configuration:
hdds.datanode.dir.du.reserved = 3.5TB  ⚠️ (35% - TOO HIGH)

GOOD Configuration:
hdds.datanode.dir.du.reserved = 1TB  ✓ (10% - OPTIMAL)
hdds.datanode.dir.du.reserved = 2TB  ✓ (20% - ACCEPTABLE)
```

**Note**: This rule requires runtime data and will show an INFO message when run via CLI. Full validation is available when integrated with Recon.

## Implementation Details

### Code Changes

#### 1. Updated datanode-rules.json
Added 3 new rules to the DataNode rules file:
- DN-STOR-002: Percentage range validation (5-20%)
- DN-STOR-003: Capacity check (reserved <= total)
- DN-STOR-004: Capacity check (reserved <= 30% of total)

#### 2. Enhanced ConfigAnalyzer.java
Added two new validation methods:

**a) checkPercentageRangeViolation()**
```java
private boolean checkPercentageRangeViolation(String value, JsonNode validation) {
  // Handles both formats: 0.05 or 5%
  // Validates against min/max range
}
```

**b) checkCapacityViolation()**
```java
private boolean checkCapacityViolation(String configKey, String configValue, 
    JsonNode validation) {
  // Checks if runtime data is required
  // Shows INFO message for rules needing JMX/API data
  // Placeholder for future Recon integration
}
```

### Validation Logic

#### PERCENTAGE_RANGE Validation
- Accepts values in two formats:
  - Decimal: `0.10` (10%)
  - Percentage: `10%`
- Validates against min (0.05) and max (0.20)
- Returns violation if outside range

#### CAPACITY_CHECK Validation
- Checks `requiresRuntime` flag
- If true: Shows INFO message and skips (needs runtime data)
- If false: Can perform static validation
- Future: Will integrate with Recon to get actual volume capacities

## Testing

### Test Case 1: Percentage Too Low
```bash
# Set in ozone-site.xml
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.02</value>
</property>

# Run analyzer
$ ozone admin datanode config-analyzer

# Expected Output:
[WARNING] DN-STOR-002
  Config: hdds.datanode.dir.du.reserved.percent
  Current Value: 0.02
  Issue: Reserved space percentage should be between 5% and 20%
  Recommendation: Set hdds.datanode.dir.du.reserved.percent between 0.05 (5%) and 0.20 (20%)
  Impact: Too low: Risk of disk full. Too high: Wasted capacity
```

### Test Case 2: Percentage Too High
```bash
# Set in ozone-site.xml
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.35</value>
</property>

# Run analyzer
$ ozone admin datanode config-analyzer

# Expected Output:
[WARNING] DN-STOR-002
  Config: hdds.datanode.dir.du.reserved.percent
  Current Value: 0.35
  Issue: Reserved space percentage should be between 5% and 20%
  ...
```

### Test Case 3: Capacity Checks (Runtime Required)
```bash
# Run analyzer
$ ozone admin datanode config-analyzer

# Expected Output:
INFO: Rule requires runtime data (total DataNode capacity). 
      Skipping validation for: hdds.datanode.dir.du.reserved
```

## CLI Output Examples

### Text Format
```
DATANODE Configuration Analysis
============================================================

Found 1 violation(s):

[WARNING] DN-STOR-002
  Config: hdds.datanode.dir.du.reserved.percent
  Current Value: 0.02
  Issue: Reserved space percentage should be between 5% and 20%
  Recommendation: Set hdds.datanode.dir.du.reserved.percent between 0.05 (5%) and 0.20 (20%)
  Impact: Too low: Risk of disk full. Too high: Wasted capacity

INFO: Rule requires runtime data (total DataNode capacity). 
      Skipping validation for: hdds.datanode.dir.du.reserved
```

### JSON Format
```json
{
  "component": "DATANODE",
  "violationCount": 1,
  "violations": [
    {
      "ruleId": "DN-STOR-002",
      "configKey": "hdds.datanode.dir.du.reserved.percent",
      "severity": "WARNING",
      "status": "VIOLATED",
      "message": "Reserved space percentage should be between 5% and 20%",
      "currentValue": "0.02",
      "recommendation": "Set hdds.datanode.dir.du.reserved.percent between 0.05 (5%) and 0.20 (20%)",
      "impact": "Too low: Risk of disk full. Too high: Wasted capacity"
    }
  ]
}
```

### Table Format
```
┌─────────────────────────────────────────────────────────────┐
│  DATANODE Configuration Analysis                            │
├────────────────┬─────────────────────┬──────────┬──────────┤
│ Rule ID        │ Config Key          │ Severity │ Status   │
├────────────────┼─────────────────────┼──────────┼──────────┤
│ DN-STOR-002    │ hdds.datanode.di... │ WARNING  │ VIOLATED │
└────────────────┴─────────────────────┴──────────┴──────────┘

Total violations: 1

INFO: Rule requires runtime data (total DataNode capacity). 
      Skipping validation for: hdds.datanode.dir.du.reserved
```

## Future Enhancements

### Phase 1: Recon Integration (Next)
When integrated with Recon, the capacity check rules (DN-STOR-003, DN-STOR-004) will:
1. Query Recon API for DataNode volume information
2. Calculate total capacity by summing all volumes
3. Perform actual capacity validation
4. Report violations with actual values

Example with Recon integration:
```
[CRITICAL] DN-STOR-003
  Config: hdds.datanode.dir.du.reserved
  Current Value: 5TB
  Total DataNode Capacity: 3TB (vol1: 1TB, vol2: 1TB, vol3: 1TB)
  Issue: Reserved space exceeds total DataNode capacity
  Recommendation: Reduce hdds.datanode.dir.du.reserved to less than 3TB
  Impact: DataNode will fail to start or all volumes become read-only
```

### Phase 2: Per-Volume Validation
Extend to validate per-volume reserved space:
- Check each volume's reserved space individually
- Detect over-reservation on specific volumes
- Provide volume-specific recommendations

### Phase 3: Dynamic Recommendations
Calculate optimal reserved space based on:
- Actual volume sizes
- Historical usage patterns
- Cluster workload characteristics

## Summary

### Rules Added: 3
1. **DN-STOR-002**: Percentage range validation (immediate)
2. **DN-STOR-003**: Capacity check - not exceeding total (requires Recon)
3. **DN-STOR-004**: Capacity check - within 30% limit (requires Recon)

### Validation Types Added: 2
1. **PERCENTAGE_RANGE**: Validates percentage values with min/max
2. **CAPACITY_CHECK**: Framework for runtime capacity validation

### Total DataNode Rules: 9
- DN-STOR-001: Volume Reserved Space (existing)
- DN-STOR-002: Reserved Space Percentage Range (NEW)
- DN-STOR-003: Reserved Space Not Exceeding Total Capacity (NEW)
- DN-STOR-004: Reserved Space Within 30% Limit (NEW)
- DN-HANDLER-001: Handler Count Range
- DN-CACHE-001: Write Cache Size
- DN-HB-001: Heartbeat Configuration
- DN-PERF-001: Container Delete Threads
- DN-IO-001: Chunk Size Configuration

### Immediate Benefits
✅ Catches percentage misconfigurations (too low or too high)  
✅ Prevents common reserved space mistakes  
✅ Provides clear recommendations  
✅ Works offline without cluster  

### Future Benefits (with Recon)
⏭️ Full capacity validation with actual volume data  
⏭️ Detects critical over-reservation issues  
⏭️ Per-volume validation  
⏭️ Dynamic recommendations based on cluster state
