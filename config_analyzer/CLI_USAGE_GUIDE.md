# Config Analyzer CLI Usage Guide

## Overview
The Config Analyzer provides a simple CLI-based tool for Ozone administrators to check configuration violations for specific components.

## Commands

### OM (Ozone Manager) Configuration Analysis
```bash
# Analyze OM configuration with default output
ozone admin om config-analyzer

# Analyze with JSON output
ozone admin om config-analyzer --json

# Analyze with table format
ozone admin om config-analyzer --table

# Use custom rules file
ozone admin om config-analyzer --rules /path/to/custom-om-rules.json --json
```

### SCM (Storage Container Manager) Configuration Analysis
```bash
# Analyze SCM configuration with default output
ozone admin scm config-analyzer

# Analyze with JSON output
ozone admin scm config-analyzer --json

# Analyze with table format
ozone admin scm config-analyzer --table

# Use custom rules file
ozone admin scm config-analyzer --rules /path/to/custom-scm-rules.json --json
```

### DataNode Configuration Analysis
```bash
# Analyze DataNode configuration with default output
ozone admin datanode config-analyzer

# Analyze with JSON output
ozone admin datanode config-analyzer --json

# Analyze with table format
ozone admin datanode config-analyzer --table

# Use custom rules file
ozone admin datanode config-analyzer --rules /path/to/custom-datanode-rules.json --json
```

## Output Formats

### 1. Default Text Output
Clean, human-readable format with detailed information:

```
OM Configuration Analysis
============================================================

Found 2 violation(s):

[WARNING] OM-HANDLER-001
  Config: ozone.om.handler.count.key
  Current Value: 5
  Issue: OM handler count outside recommended range
  Recommendation: Set ozone.om.handler.count.key between 20-100 based on client load
  Impact: Too few handlers cause client request queuing and timeouts

[ERROR] OM-RATIS-001
  Config: ozone.om.ratis.request.timeout
  Current Value: 30s
  Issue: Ratis request timeout too short
  Recommendation: Set ozone.om.ratis.request.timeout to at least 60s
  Impact: Short timeouts cause frequent request failures during leader election
```

### 2. Table Format
Compact tabular view for quick overview:

```
┌─────────────────────────────────────────────────────────────┐
│  OM Configuration Analysis                                  │
├────────────────┬─────────────────────┬──────────┬──────────┤
│ Rule ID        │ Config Key          │ Severity │ Status   │
├────────────────┼─────────────────────┼──────────┼──────────┤
│ OM-HANDLER-001 │ ozone.om.handler... │ WARNING  │ VIOLATED │
│ OM-RATIS-001   │ ozone.om.ratis.r... │ ERROR    │ VIOLATED │
└────────────────┴─────────────────────┴──────────┴──────────┘

Total violations: 2

Run with --json for detailed recommendations
```

### 3. JSON Format
Machine-readable format for automation:

```json
{
  "component": "OM",
  "violationCount": 2,
  "violations": [
    {
      "ruleId": "OM-HANDLER-001",
      "configKey": "ozone.om.handler.count.key",
      "severity": "WARNING",
      "status": "VIOLATED",
      "message": "OM handler count outside recommended range",
      "currentValue": "5",
      "recommendation": "Set ozone.om.handler.count.key between 20-100 based on client load",
      "impact": "Too few handlers cause client request queuing and timeouts"
    },
    {
      "ruleId": "OM-RATIS-001",
      "configKey": "ozone.om.ratis.request.timeout",
      "severity": "ERROR",
      "status": "VIOLATED",
      "message": "Ratis request timeout too short",
      "currentValue": "30s",
      "recommendation": "Set ozone.om.ratis.request.timeout to at least 60s",
      "impact": "Short timeouts cause frequent request failures during leader election"
    }
  ]
}
```

## Use Cases

### 1. Pre-Deployment Configuration Check
Before deploying a new Ozone cluster:

```bash
# Check all components
ozone admin om config-analyzer --table
ozone admin scm config-analyzer --table
ozone admin datanode config-analyzer --table

# Fix any CRITICAL or ERROR violations
```

### 2. Post-Deployment Validation
After cluster deployment:

```bash
# Verify configuration with JSON output for logging
ozone admin om config-analyzer --json > om-config-check.json
ozone admin scm config-analyzer --json > scm-config-check.json
```

### 3. Troubleshooting Performance Issues
When investigating performance problems:

```bash
# Check for configuration issues
ozone admin datanode config-analyzer
ozone admin om config-analyzer
```

### 4. Regular Configuration Audits
Scheduled periodic checks:

```bash
#!/bin/bash
# config-audit.sh
DATE=$(date +%Y%m%d)
ozone admin om config-analyzer --json > om-audit-$DATE.json
ozone admin scm config-analyzer --json > scm-audit-$DATE.json
ozone admin datanode config-analyzer --json > dn-audit-$DATE.json
```

### 5. CI/CD Integration
In your deployment pipeline:

```bash
# Fail build if CRITICAL violations found
violations=$(ozone admin om config-analyzer --json | jq '.violationCount')
if [ "$violations" -gt 0 ]; then
  echo "Configuration violations found!"
  ozone admin om config-analyzer
  exit 1
fi
```

## Severity Levels

- **INFO**: Informational, no immediate action needed
- **WARNING**: Suboptimal configuration, should review
- **ERROR**: Likely to cause operational issues, should fix
- **CRITICAL**: Dangerous configuration, fix immediately

## Custom Rules

You can create custom rules for your organization:

### Creating a Custom Rules File

```json
{
  "component": "OM",
  "rules": [
    {
      "id": "CUSTOM-OM-001",
      "name": "Custom Memory Setting",
      "configKey": "ozone.om.heap.size",
      "severity": "WARNING",
      "validation": {
        "type": "MIN_VALUE",
        "min": "4GB"
      },
      "message": "OM heap size below recommended minimum",
      "recommendation": "Set ozone.om.heap.size to at least 8GB for production",
      "impact": "Insufficient heap can cause OOM errors under load"
    }
  ]
}
```

### Using Custom Rules

```bash
ozone admin om config-analyzer --rules /etc/ozone/custom-om-rules.json --json
```

## Integration with Monitoring

### Prometheus Metrics
Export violations count as metric:

```bash
violations=$(ozone admin om config-analyzer --json | jq '.violationCount')
echo "ozone_om_config_violations $violations" | curl --data-binary @- http://pushgateway:9091/metrics/job/config_analyzer
```

### Alerting
Send alerts when violations detected:

```bash
violations=$(ozone admin scm config-analyzer --json)
if [ $(echo "$violations" | jq '.violationCount') -gt 0 ]; then
  echo "$violations" | mail -s "SCM Config Violations" admin@example.com
fi
```

## Troubleshooting

### No Violations Found
```
✓ No configuration violations found
```
This means all checked configurations are within recommended ranges.

### Cannot Find Rules File
```
Error: Cannot find built-in OM rules. Please specify --rules option.
```
**Solution**: Ensure the JAR file includes the rules resources, or specify custom rules with `--rules`.

### Invalid Rules Format
```
Invalid rules format: 'rules' array not found
```
**Solution**: Check that your custom rules JSON has the correct structure with a "rules" array.

## Best Practices

1. **Run Before Major Changes**: Always run config analyzer before:
   - Cluster upgrades
   - Configuration changes
   - Adding new nodes

2. **Document Accepted Violations**: If you intentionally violate a rule:
   - Document why in your runbook
   - Consider creating a custom rules file that excludes it

3. **Automate Regular Checks**: Set up cron jobs or CI/CD pipelines to run periodic checks

4. **Component-Specific Focus**: Run analyzer for the component you're troubleshooting

5. **Combine with Recon**: Use CLI for quick checks, Recon UI for detailed analysis

## FAQ

**Q: Does this modify my configuration?**
A: No, it's read-only. It only analyzes and reports violations.

**Q: What if I intentionally use a non-standard configuration?**
A: You can ignore warnings, or create custom rules that match your setup.

**Q: Can I add my own rules?**
A: Yes! Create a JSON file following the rules format and use `--rules` option.

**Q: How often should I run this?**
A: Run it:
- Before any configuration changes
- After cluster deployment
- Weekly as part of regular maintenance
- When troubleshooting issues

**Q: Does it check all configurations?**
A: No, only critical configurations covered by the rules. It's not exhaustive.

## Examples by Scenario

### Example 1: New Cluster Setup
```bash
# Step 1: Check configurations before first start
$ ozone admin om config-analyzer --table
$ ozone admin scm config-analyzer --table
$ ozone admin datanode config-analyzer --table

# Step 2: Fix any CRITICAL/ERROR violations

# Step 3: Generate baseline report
$ ozone admin om config-analyzer --json > baseline-om-config.json
```

### Example 2: Investigating OOM Issues
```bash
# Check DataNode memory-related configs
$ ozone admin datanode config-analyzer | grep -i "cache\|memory"

# Look for violations
$ ozone admin datanode config-analyzer --json | jq '.violations[] | select(.configKey | contains("cache"))'
```

### Example 3: Pre-Production Checklist
```bash
#!/bin/bash
echo "Running pre-production config check..."

for component in om scm datanode; do
  echo "Checking $component..."
  violations=$(ozone admin $component config-analyzer --json | jq '.violationCount')
  
  if [ "$violations" -gt 0 ]; then
    echo "❌ $component has $violations violation(s)"
    ozone admin $component config-analyzer
    exit 1
  else
    echo "✓ $component configuration OK"
  fi
done

echo "All configurations validated!"
```

## See Also

- [Simplified Rules JSON Structure](SIMPLIFIED_RULES_SUMMARY.md)
- [Ozone Admin Commands Documentation](https://ozone.apache.org/docs/current/shell/Admin.html)
- [Configuration Best Practices](https://ozone.apache.org/docs/current/feature/Configuration.html)
