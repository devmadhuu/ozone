/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.container.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.hdds.conf.ConfigurationException;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageSize;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates DataNode configuration at startup.
 * Prevents startup if CRITICAL or ERROR violations are found.
 */
public class DatanodeConfigStartupValidator {

  private static final Logger LOG =
      LoggerFactory.getLogger(DatanodeConfigStartupValidator.class);

  private static final String CONFIG_KEY_VALIDATION_ENABLED =
      "hdds.datanode.startup.config.validation.enabled";
  private static final String CONFIG_KEY_VALIDATION_MODE =
      "hdds.datanode.startup.validation.mode";
  private static final String CONFIG_KEY_FORCE_START =
      "hdds.datanode.startup.ignore.config.errors";

  private final OzoneConfiguration conf;
  private final ValidationMode mode;
  private final boolean forceStart;

  /**
   * Validation enforcement modes.
   */
  public enum ValidationMode {
    DRY_RUN,           // Log only, never abort
    ENFORCE_CRITICAL,  // Abort on CRITICAL only
    ENFORCE_ALL        // Abort on CRITICAL and ERROR
  }

  /**
   * Severity levels for violations.
   */
  public enum Severity {
    CRITICAL, ERROR, WARNING, INFO
  }

  /**
   * Represents a configuration violation.
   */
  public static class Violation {
    private final String ruleId;
    private final String configKey;
    private final Severity severity;
    private final String message;
    private final String currentValue;
    private final String recommendation;
    private final String impact;

    public Violation(String ruleId, String configKey, Severity severity,
        String message, String currentValue, String recommendation,
        String impact) {
      this.ruleId = ruleId;
      this.configKey = configKey;
      this.severity = severity;
      this.message = message;
      this.currentValue = currentValue;
      this.recommendation = recommendation;
      this.impact = impact;
    }

    public String getRuleId() {
      return ruleId;
    }

    public String getConfigKey() {
      return configKey;
    }

    public Severity getSeverity() {
      return severity;
    }

    public String getMessage() {
      return message;
    }

    public String getCurrentValue() {
      return currentValue;
    }

    public String getRecommendation() {
      return recommendation;
    }

    public String getImpact() {
      return impact;
    }
  }

  /**
   * Holds validation results.
   */
  public static class ValidationResult {
    private final List<Violation> violations = new ArrayList<>();

    public void addViolation(Violation violation) {
      violations.add(violation);
    }

    public List<Violation> getViolations() {
      return violations;
    }

    public List<Violation> getCriticalViolations() {
      List<Violation> critical = new ArrayList<>();
      for (Violation v : violations) {
        if (v.getSeverity() == Severity.CRITICAL) {
          critical.add(v);
        }
      }
      return critical;
    }

    public List<Violation> getErrorViolations() {
      List<Violation> errors = new ArrayList<>();
      for (Violation v : violations) {
        if (v.getSeverity() == Severity.ERROR) {
          errors.add(v);
        }
      }
      return errors;
    }

    public boolean hasCriticalViolations() {
      return !getCriticalViolations().isEmpty();
    }

    public boolean hasErrorViolations() {
      return !getErrorViolations().isEmpty();
    }

    public int getViolationCount() {
      return violations.size();
    }
  }

  public DatanodeConfigStartupValidator(OzoneConfiguration conf) {
    this.conf = conf;

    // Check if validation is enabled
    boolean enabled = conf.getBoolean(CONFIG_KEY_VALIDATION_ENABLED, true);
    ValidationMode tempMode;
    
    if (!enabled) {
      tempMode = ValidationMode.DRY_RUN;
      LOG.info("DataNode startup config validation is disabled");
    } else {
      // Parse validation mode (default: ENFORCE_ALL)
      String modeStr = conf.get(CONFIG_KEY_VALIDATION_MODE, "ENFORCE_ALL");
      try {
        tempMode = ValidationMode.valueOf(modeStr.toUpperCase());
      } catch (IllegalArgumentException e) {
        LOG.warn("Invalid validation mode: {}. Defaulting to ENFORCE_ALL", modeStr);
        tempMode = ValidationMode.ENFORCE_ALL;
      }
    }
    
    this.mode = tempMode;
    this.forceStart = conf.getBoolean(CONFIG_KEY_FORCE_START, false);

    LOG.info("DataNode startup validation mode: {}, forceStart: {}",
        mode, forceStart);
  }

  /**
   * Validates configuration and throws exception if violations found
   * based on enforcement mode.
   *
   * @throws ConfigurationException if startup should be aborted
   */
  public void validateOrAbort() throws ConfigurationException {
    long startTime = System.currentTimeMillis();

    try {
      // Load rules from embedded resource
      JsonNode rules = loadEmbeddedRules();

      // Validate configuration (static checks only, no runtime data)
      ValidationResult result = validateStaticRules(rules);

      long duration = System.currentTimeMillis() - startTime;
      LOG.info("Configuration validation completed in {}ms, found {} violations",
          duration, result.getViolationCount());

      // Log all violations
      logViolations(result);

      // Decide whether to abort based on mode and violations
      if (forceStart) {
        LOG.warn("FORCE START flag is set - bypassing config validation!");
        LOG.warn("This should only be used in emergency situations.");
        return;
      }

      enforceValidationResult(result);

    } catch (IOException e) {
      LOG.error("Failed to load validation rules: {}", e.getMessage());
      LOG.warn("Skipping config validation due to error");
      // Don't fail startup if validator itself has issues
    }
  }

  /**
   * Load embedded DataNode rules from resources.
   */
  private JsonNode loadEmbeddedRules() throws IOException {
    String rulesResource = "config-analyzer-rules/datanode-rules.json";
    InputStream rulesStream = getClass().getClassLoader()
        .getResourceAsStream(rulesResource);

    if (rulesStream == null) {
      throw new IOException("Cannot find embedded datanode rules: " +
          rulesResource);
    }

    ObjectMapper mapper = new ObjectMapper();
    return mapper.readTree(rulesStream);
  }

  /**
   * Validate static rules that don't require runtime data.
   */
  private ValidationResult validateStaticRules(JsonNode rulesJson) {
    ValidationResult result = new ValidationResult();
    JsonNode rules = rulesJson.get("rules");

    if (rules == null || !rules.isArray()) {
      LOG.error("Invalid rules format: 'rules' array not found");
      return result;
    }

    for (JsonNode rule : rules) {
      // Skip rules that require runtime data
      JsonNode validation = rule.get("validation");
      if (validation.has("requiresRuntime") &&
          validation.get("requiresRuntime").asBoolean()) {
        LOG.debug("Skipping runtime-dependent rule: {}",
            rule.get("id").asText());
        continue;
      }

      // Skip rules with volume_capacity context (need post-init validation)
      if (validation.has("context") &&
          "volume_capacity".equals(validation.get("context").asText())) {
        LOG.debug("Skipping volume-capacity rule: {} (requires post-init check)",
            rule.get("id").asText());
        continue;
      }

      // Check if rule is violated
      String id = rule.get("id").asText();
      String configKey = rule.get("configKey").asText();
      String severityStr = rule.get("severity").asText();
      String message = rule.get("message").asText();
      String configValue = conf.get(configKey);

      if (configValue == null) {
        continue; // Config not set, using defaults
      }

      boolean violated = checkViolation(configKey, configValue, validation);

      if (violated) {
        Severity severity = Severity.valueOf(severityStr.toUpperCase());
        Violation violation = new Violation(
            id,
            configKey,
            severity,
            message,
            configValue,
            rule.get("recommendation").asText(),
            rule.get("impact").asText()
        );
        result.addViolation(violation);
      }
    }

    return result;
  }

  /**
   * Check if a configuration value violates a validation rule.
   */
  private boolean checkViolation(String configKey, String configValue,
      JsonNode validation) {
    if (configValue == null) {
      return false;
    }

    String validationType = validation.get("type").asText();

    try {
      switch (validationType) {
        case "RANGE":
          return checkRangeViolation(configValue, validation);
        case "MIN_VALUE":
          return checkMinValueViolation(configValue, validation);
        case "BOOLEAN":
          return checkBooleanViolation(configValue, validation);
        case "PERCENTAGE_RANGE":
          return checkPercentageRangeViolation(configValue, validation);
        case "RELATIONSHIP":
          return checkRelationshipViolation(configKey, configValue, validation);
        default:
          LOG.warn("Unknown validation type: {}", validationType);
          return false;
      }
    } catch (Exception e) {
      LOG.error("Error checking violation for {}: {}",
          configKey, e.getMessage());
      return false;
    }
  }

  private boolean checkRangeViolation(String value, JsonNode validation) {
    try {
      if (validation.has("min") && validation.has("max")) {
        String minStr = validation.get("min").asText();
        String maxStr = validation.get("max").asText();

        // Check if values contain storage size units
        if (isStorageSize(value) || isStorageSize(minStr) ||
            isStorageSize(maxStr)) {
          return checkStorageSizeRange(value, minStr, maxStr);
        }

        // Check if values contain time duration units
        if (isTimeDuration(value) || isTimeDuration(minStr) ||
            isTimeDuration(maxStr)) {
          return checkTimeDurationRange(value, minStr, maxStr);
        }

        // Numeric comparison
        double doubleValue = Double.parseDouble(value);
        double min = validation.get("min").asDouble();
        double max = validation.get("max").asDouble();
        return doubleValue < min || doubleValue > max;
      }
    } catch (NumberFormatException e) {
      LOG.error("Failed to parse numeric value: {}", value);
      return false;
    }
    return false;
  }

  private boolean checkMinValueViolation(String value, JsonNode validation) {
    try {
      if (validation.has("min")) {
        String minStr = validation.get("min").asText();

        if (isStorageSize(value) && isStorageSize(minStr)) {
          StorageSize valueSize = StorageSize.parse(value);
          StorageSize minSize = StorageSize.parse(minStr);
          long valueBytes = (long) valueSize.getUnit()
              .toBytes(valueSize.getValue());
          long minBytes = (long) minSize.getUnit().toBytes(minSize.getValue());
          return valueBytes < minBytes;
        }

        // Simple numeric comparison
        if (!minStr.contains("MB") && !minStr.contains("GB") &&
            !minStr.contains("s") && !minStr.contains("%")) {
          long longValue = Long.parseLong(value);
          long min = Long.parseLong(minStr);
          return longValue < min;
        }
      }
    } catch (Exception e) {
      LOG.error("Failed to check min value: {}", e.getMessage());
      return false;
    }
    return false;
  }

  private boolean checkBooleanViolation(String value, JsonNode validation) {
    if (validation.has("recommended")) {
      boolean recommended = validation.get("recommended").asBoolean();
      boolean actual = Boolean.parseBoolean(value);
      return actual != recommended;
    }
    return false;
  }

  private boolean checkPercentageRangeViolation(String value,
      JsonNode validation) {
    try {
      double percentValue;
      if (value.endsWith("%")) {
        percentValue = Double.parseDouble(
            value.substring(0, value.length() - 1)) / 100.0;
      } else {
        percentValue = Double.parseDouble(value);
      }

      double min = validation.get("min").asDouble();
      double max = validation.get("max").asDouble();

      return percentValue < min || percentValue > max;
    } catch (NumberFormatException e) {
      LOG.error("Cannot parse percentage value: {}", value);
      return false;
    }
  }

  private boolean checkRelationshipViolation(String configKey,
      String configValue, JsonNode validation) {
    // Relationship checks require multiple configs
    // Example: heartbeat_timeout >= heartbeat_interval * 3
    String rule = validation.get("rule").asText();
    
    if (rule.contains("heartbeat_timeout >= heartbeat_interval")) {
      return checkHeartbeatRelationship();
    }
    
    // Other relationships not implemented yet
    return false;
  }

  private boolean checkHeartbeatRelationship() {
    try {
      String intervalStr = conf.get("hdds.heartbeat.interval");
      String timeoutStr = conf.get("hdds.heartbeat.timeout");

      if (intervalStr == null || timeoutStr == null) {
        return false; // Can't check if not configured
      }

      long intervalSeconds = parseTimeToSeconds(intervalStr);
      long timeoutSeconds = parseTimeToSeconds(timeoutStr);

      // Rule: timeout should be >= interval * 3
      return timeoutSeconds < (intervalSeconds * 3);

    } catch (Exception e) {
      LOG.error("Failed to check heartbeat relationship: {}", e.getMessage());
      return false;
    }
  }

  private boolean isStorageSize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }
    String upper = value.trim().toUpperCase();
    return upper.matches(".*\\d+\\s*(B|KB|MB|GB|TB|PB)$");
  }

  private boolean isTimeDuration(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }
    String lower = value.trim().toLowerCase();
    return lower.matches(".*\\d+\\s*(ms|s|m|h|d)$");
  }

  private boolean checkStorageSizeRange(String value, String minStr,
      String maxStr) {
    try {
      StorageSize valueSize = StorageSize.parse(value);
      StorageSize minSize = StorageSize.parse(minStr);
      StorageSize maxSize = StorageSize.parse(maxStr);

      long valueBytes = (long) valueSize.getUnit()
          .toBytes(valueSize.getValue());
      long minBytes = (long) minSize.getUnit().toBytes(minSize.getValue());
      long maxBytes = (long) maxSize.getUnit().toBytes(maxSize.getValue());

      return valueBytes < minBytes || valueBytes > maxBytes;

    } catch (IllegalArgumentException e) {
      LOG.error("Failed to parse storage size: {}", e.getMessage());
      return false;
    }
  }

  private boolean checkTimeDurationRange(String value, String minStr,
      String maxStr) {
    try {
      long valueSeconds = parseTimeToSeconds(value);
      long minSeconds = parseTimeToSeconds(minStr);
      long maxSeconds = parseTimeToSeconds(maxStr);

      return valueSeconds < minSeconds || valueSeconds > maxSeconds;

    } catch (Exception e) {
      LOG.error("Failed to parse time duration: {}", e.getMessage());
      return false;
    }
  }

  private long parseTimeToSeconds(String duration) {
    if (duration == null || duration.trim().isEmpty()) {
      throw new IllegalArgumentException("Duration cannot be empty");
    }

    String trimmed = duration.trim().toLowerCase();
    Pattern pattern = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");
    Matcher matcher = pattern.matcher(trimmed);

    if (!matcher.find()) {
      throw new IllegalArgumentException("Invalid duration format: " +
          duration);
    }

    long value = Long.parseLong(matcher.group(1));
    String unit = matcher.group(2);

    switch (unit) {
      case "ms":
        return value / 1000;
      case "s":
        return value;
      case "m":
        return value * 60;
      case "h":
        return value * 3600;
      case "d":
        return value * 86400;
      default:
        throw new IllegalArgumentException("Unknown time unit: " + unit);
    }
  }

  /**
   * Log all violations found.
   */
  private void logViolations(ValidationResult result) {
    if (result.getViolationCount() == 0) {
      LOG.info("Configuration validation passed - no violations found");
      return;
    }

    LOG.warn("Configuration validation found {} violations:",
        result.getViolationCount());

    for (Violation v : result.getViolations()) {
      String logLevel = v.getSeverity().toString();
      String logMsg = String.format(
          "[%s] %s: Config '%s' = '%s' - %s. Recommendation: %s. Impact: %s",
          logLevel, v.getRuleId(), v.getConfigKey(), v.getCurrentValue(),
          v.getMessage(), v.getRecommendation(), v.getImpact());

      switch (v.getSeverity()) {
        case CRITICAL:
          LOG.error(logMsg);
          break;
        case ERROR:
          LOG.error(logMsg);
          break;
        case WARNING:
          LOG.warn(logMsg);
          break;
        case INFO:
          LOG.info(logMsg);
          break;
      }
    }
  }

  /**
   * Enforce validation result based on mode.
   */
  private void enforceValidationResult(ValidationResult result)
      throws ConfigurationException {

    switch (mode) {
      case DRY_RUN:
        // Never abort, just log
        if (result.getViolationCount() > 0) {
          LOG.info("DRY_RUN mode: Would have blocked startup in " +
              "enforcement mode");
        }
        break;

      case ENFORCE_CRITICAL:
        if (result.hasCriticalViolations()) {
          throw buildConfigurationException(result.getCriticalViolations(),
              "CRITICAL");
        }
        break;

      case ENFORCE_ALL:
        if (result.hasCriticalViolations()) {
          throw buildConfigurationException(result.getCriticalViolations(),
              "CRITICAL");
        }
        if (result.hasErrorViolations()) {
          throw buildConfigurationException(result.getErrorViolations(),
              "ERROR");
        }
        break;
    }
  }

  /**
   * Build detailed ConfigurationException for violations.
   */
  private ConfigurationException buildConfigurationException(
      List<Violation> violations, String severityLevel) {

    StringBuilder sb = new StringBuilder();
    sb.append("\n");
    sb.append("========================================================\n");
    sb.append(severityLevel).append(" CONFIGURATION VIOLATIONS DETECTED\n");
    sb.append("DataNode Startup Aborted\n");
    sb.append("========================================================\n\n");

    sb.append("Found ").append(violations.size())
        .append(" ").append(severityLevel).append(" violation(s):\n\n");

    for (Violation v : violations) {
      sb.append("Rule: ").append(v.getRuleId()).append("\n");
      sb.append("  Config Key: ").append(v.getConfigKey()).append("\n");
      sb.append("  Current Value: ").append(v.getCurrentValue()).append("\n");
      sb.append("  Issue: ").append(v.getMessage()).append("\n");
      sb.append("  Impact: ").append(v.getImpact()).append("\n");
      sb.append("  Recommendation: ").append(v.getRecommendation())
          .append("\n\n");
    }

    sb.append("========================================================\n");
    sb.append("HOW TO FIX:\n");
    sb.append("  1. Edit ozone-site.xml to correct the violations above\n");
    sb.append("  2. Restart the DataNode\n\n");

    sb.append("TO OVERRIDE (Emergency Only):\n");
    sb.append("  Option 1 (Environment Variable):\n");
    sb.append("    export OZONE_DATANODE_IGNORE_CONFIG_ERRORS=true\n");
    sb.append("    ozone datanode\n\n");
    sb.append("  Option 2 (Command Line):\n");
    sb.append("    ozone datanode --force-start-ignore-config-errors\n\n");
    sb.append("  Option 3 (Configuration):\n");
    sb.append("    hdds.datanode.startup.ignore.config.errors=true\n\n");

    sb.append("MORE INFO:\n");
    sb.append("  Run: ozone admin datanode config-analyzer --help\n");
    sb.append("========================================================\n");

    return new ConfigurationException(sb.toString());
  }

  /**
   * Get current validation mode.
   */
  public ValidationMode getMode() {
    return mode;
  }

  /**
   * Check if force start is enabled.
   */
  public boolean isForceStart() {
    return forceStart;
  }
}
