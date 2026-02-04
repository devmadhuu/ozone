# DataNode Configuration Rules - Quick Reference

## Reserved Space Rules

### DN-STOR-001: Volume Reserved Space ⚠️ WARNING
**Config**: `hdds.datanode.dir.du.reserved`  
**Check**: At least 10% of volume capacity  
**Impact**: Disk full errors

### DN-STOR-002: Reserved Space Percentage Range ⚠️ WARNING
**Config**: `hdds.datanode.dir.du.reserved.percent`  
**Check**: Between 5% and 20%  
**Impact**: Too low = disk full risk, Too high = wasted capacity

**Valid Examples**:
```xml
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.05</value>  <!-- 5% - Minimum acceptable -->
</property>

<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.10</value>  <!-- 10% - Recommended -->
</property>

<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.20</value>  <!-- 20% - Maximum recommended -->
</property>
```

**Invalid Examples**:
```xml
<!-- TOO LOW -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.02</value>  <!-- 2% - TOO LOW! -->
</property>

<!-- TOO HIGH -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.35</value>  <!-- 35% - TOO HIGH! -->
</property>
```

### DN-STOR-003: Reserved Space Not Exceeding Total Capacity 🔴 CRITICAL
**Config**: `hdds.datanode.dir.du.reserved`  
**Check**: Reserved ≤ Total Capacity (sum of all volumes)  
**Impact**: DataNode fails to start or all volumes read-only  
**Note**: Requires runtime data (Recon integration)

**Example**:
```
DataNode Volumes:
- /data/vol1: 1TB
- /data/vol2: 1TB
- /data/vol3: 1TB
Total: 3TB

✓ GOOD: hdds.datanode.dir.du.reserved = 300GB (10%)
✓ GOOD: hdds.datanode.dir.du.reserved = 600GB (20%)
✗ BAD:  hdds.datanode.dir.du.reserved = 5TB (exceeds total!)
```

### DN-STOR-004: Reserved Space Within 30% Limit ⚠️ WARNING
**Config**: `hdds.datanode.dir.du.reserved`  
**Check**: Reserved ≤ 30% of Total Capacity  
**Impact**: Excessive reservation reduces usable capacity  
**Note**: Requires runtime data (Recon integration)

**Example**:
```
DataNode Total: 10TB

✓ GOOD: 1TB reserved (10%)
✓ GOOD: 2TB reserved (20%)
⚠️ WARN: 3.5TB reserved (35% - over 30% limit)
```

## Performance Rules

### DN-HANDLER-001: Handler Count Range ⚠️ WARNING
**Config**: `hdds.datanode.handler.count`  
**Range**: 10 to 100  
**Impact**: Too few = request queuing, Too many = context switching

**Recommendations**:
- Small clusters: 10-20 handlers
- Medium clusters: 20-50 handlers
- Large clusters: 50-100 handlers

### DN-CACHE-001: Write Cache Size 🔴 CRITICAL
**Config**: `dfs.container.chunk.write.cache.size`  
**Minimum**: 256MB  
**Recommended**: 512MB or higher  
**Impact**: OOM risk with small cache and many handlers

### DN-PERF-001: Container Delete Threads ⚠️ WARNING
**Config**: `hdds.datanode.container.delete.threads.max`  
**Range**: 2 to 10  
**Recommended**: 5  
**Impact**: Too few = slow deletion, Too many = resource contention

## Network Rules

### DN-HB-001: Heartbeat Configuration ❌ ERROR
**Config**: `hdds.heartbeat.interval`  
**Rule**: `heartbeat_timeout ≥ heartbeat_interval × 3`  
**Impact**: False node failure detection

**Example**:
```xml
<!-- GOOD -->
<property>
  <name>hdds.heartbeat.interval</name>
  <value>30s</value>
</property>
<property>
  <name>hdds.heartbeat.timeout</name>
  <value>90s</value>  <!-- 3x interval -->
</property>

<!-- BAD -->
<property>
  <name>hdds.heartbeat.interval</name>
  <value>30s</value>
</property>
<property>
  <name>hdds.heartbeat.timeout</name>
  <value>60s</value>  <!-- Only 2x - TOO SHORT! -->
</property>
```

## I/O Rules

### DN-IO-001: Chunk Size Configuration ⚠️ WARNING
**Config**: `ozone.chunk.size`  
**Range**: 1MB to 16MB  
**Recommended**: 4MB  
**Impact**: Small = metadata overhead, Large = memory pressure

## Severity Legend

- 🔴 **CRITICAL**: Fix immediately - can cause outages
- ❌ **ERROR**: Should fix - likely to cause issues
- ⚠️ **WARNING**: Should review - suboptimal configuration
- ℹ️ **INFO**: Informational - no immediate action needed

## Quick Commands

```bash
# Check all DataNode rules
ozone admin datanode config-analyzer

# JSON output for automation
ozone admin datanode config-analyzer --json

# Table format for quick overview
ozone admin datanode config-analyzer --table

# Use custom rules
ozone admin datanode config-analyzer --rules /path/to/custom-rules.json
```

## Common Scenarios

### Scenario 1: New DataNode Setup
```bash
# Before starting DataNode, validate config
ozone admin datanode config-analyzer --table

# Fix any CRITICAL or ERROR violations
# Review WARNING violations based on your requirements
```

### Scenario 2: DataNode Performance Issues
```bash
# Check for configuration problems
ozone admin datanode config-analyzer

# Look for:
# - DN-CACHE-001: Cache too small?
# - DN-HANDLER-001: Handler count issues?
# - DN-PERF-001: Delete thread problems?
```

### Scenario 3: Disk Space Issues
```bash
# Check reserved space configuration
ozone admin datanode config-analyzer | grep -i "reserved\|stor"

# Look for:
# - DN-STOR-002: Percentage too low?
# - DN-STOR-003: Over-reservation?
# - DN-STOR-004: Excessive reservation?
```

## Best Practices

1. **Reserved Space**:
   - Use percentage config for consistency across volumes
   - Set between 10-15% for most deployments
   - Never exceed 20% unless specific requirement
   - Ensure absolute value doesn't exceed total capacity

2. **Performance Tuning**:
   - Start with default handler count (20)
   - Increase cache size for write-heavy workloads
   - Monitor and adjust based on actual load

3. **Regular Validation**:
   - Run config analyzer before any config changes
   - Include in pre-deployment checklists
   - Automate in CI/CD pipelines

4. **Documentation**:
   - Document any intentional deviations from recommendations
   - Keep track of config changes and their impacts
   - Review configurations quarterly

## Troubleshooting

### Issue: "Reserved space percentage should be between 5% and 20%"
**Solution**: Adjust `hdds.datanode.dir.du.reserved.percent` to a value between 0.05 and 0.20

### Issue: "Rule requires runtime data (total DataNode capacity)"
**Explanation**: Rules DN-STOR-003 and DN-STOR-004 need actual volume information  
**Solution**: These will be fully validated when integrated with Recon. For now, manually verify:
```bash
# Check total capacity
df -h | grep /data/vol

# Calculate 30% of total
# Ensure hdds.datanode.dir.du.reserved is less than that
```

### Issue: Multiple violations detected
**Priority**:
1. Fix CRITICAL violations first
2. Then ERROR violations
3. Review WARNING violations
4. Note INFO items for future reference

## Related Configurations

These DataNode configs work together:

```xml
<!-- Reserved Space (choose ONE approach) -->
<!-- Approach 1: Percentage (recommended) -->
<property>
  <name>hdds.datanode.dir.du.reserved.percent</name>
  <value>0.10</value>  <!-- 10% -->
</property>

<!-- Approach 2: Absolute value -->
<property>
  <name>hdds.datanode.dir.du.reserved</name>
  <value>107374182400</value>  <!-- 100GB in bytes -->
</property>

<!-- Performance Settings -->
<property>
  <name>hdds.datanode.handler.count</name>
  <value>20</value>
</property>

<property>
  <name>dfs.container.chunk.write.cache.size</name>
  <value>536870912</value>  <!-- 512MB -->
</property>

<property>
  <name>hdds.datanode.container.delete.threads.max</name>
  <value>5</value>
</property>

<!-- Heartbeat Settings -->
<property>
  <name>hdds.heartbeat.interval</name>
  <value>30s</value>
</property>

<property>
  <name>hdds.heartbeat.timeout</name>
  <value>90s</value>  <!-- 3x interval -->
</property>

<!-- I/O Settings -->
<property>
  <name>ozone.chunk.size</name>
  <value>4194304</value>  <!-- 4MB -->
</property>
```

## See Also

- [CLI Usage Guide](CLI_USAGE_GUIDE.md)
- [New Rules Documentation](NEW_RULES_ADDED.md)
- [Ozone DataNode Configuration](https://ozone.apache.org/docs/current/feature/Datanodes.html)
