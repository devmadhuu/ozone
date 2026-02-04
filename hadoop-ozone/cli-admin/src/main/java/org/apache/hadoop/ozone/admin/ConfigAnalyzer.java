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

package org.apache.hadoop.ozone.admin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageSize;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.server.JsonUtils;
import org.apache.hadoop.ozone.utils.FormattingCLIUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared configuration analyzer for all Ozone components.
 * Provides common logic for loading rules and checking violations.
 */
public class ConfigAnalyzer {

  private final String component;
  private final OzoneConfiguration conf;
  private final String rulesPath;
  private final String defaultRulesResource;
  private final String serviceHttpAddress;

  private static final List<String> CONFIG_ANALYZER_HEADER = Arrays.asList(
      "Rule ID", "Config Key", "Severity", "Status", "Message");

  /**
   * Create a new config analyzer.
   *
   * @param component Component name (OM, SCM, DATANODE, RECON)
   * @param conf Ozone configuration (used for HTTP connection and fallback)
   * @param rulesPath Optional custom rules file path
   * @param defaultRulesResource Default rules resource path
   * @param serviceHttpAddress Optional HTTP address of running service (host:port)
   */
  public ConfigAnalyzer(String component, OzoneConfiguration conf,
      String rulesPath, String defaultRulesResource,
      String serviceHttpAddress) {
    this.component = component;
    this.conf = conf;
    this.rulesPath = rulesPath;
    this.defaultRulesResource = defaultRulesResource;
    this.serviceHttpAddress = serviceHttpAddress;
  }

  /**
   * Analyze configuration and output results.
   *
   * @param json Output as JSON format
   * @param table Output as table format
   * @throws IOException if rules cannot be loaded or output fails
   */
  public void analyze(boolean json, boolean table) throws IOException {
    // Load runtime configuration from live service if address provided
    OzoneConfiguration runtimeConf = loadRuntimeConfiguration();

    // Load rules
    JsonNode rules = loadRules();

    // Analyze configuration
    List<ConfigViolation> violations = analyzeConfiguration(rules, runtimeConf);

    // Output results
    if (json) {
      printAsJson(violations);
    } else if (table) {
      printAsTable(violations);
    } else {
      printAsText(violations);
    }
  }

  /**
   * Load runtime configuration from live service or use provided config.
   */
  private OzoneConfiguration loadRuntimeConfiguration() throws IOException {
    if (serviceHttpAddress != null && !serviceHttpAddress.isEmpty()) {
      // Check if address is the bind-all address (won't work for connecting)
      if (serviceHttpAddress.startsWith("0.0.0.0:")) {
        System.err.println("Warning: Service address is " + serviceHttpAddress + 
            " (bind-all address).");
        System.err.println("This address cannot be used to connect to the service.");
        System.err.println("Please specify the actual hostname/IP using:");
        System.err.println("  For OM: --om-host <hostname>:9874");
        System.err.println("  For SCM: --scm-host <hostname>:9876");
        System.err.println("  For DataNode: --datanode-host <hostname>:9882");
        System.err.println("Examples:");
        System.err.println("  Docker: --om-host om:9874 or --datanode-host datanode1:9882");
        System.err.println("  Local: --om-host localhost:9874");
        System.err.println("\nFalling back to local configuration files.");
        return conf;
      }
      
      System.out.println("Fetching runtime configuration from " + 
          component + " at " + serviceHttpAddress);
      return fetchConfigurationFromService();
    } else {
      System.out.println("Warning: Analyzing configuration from local files, " +
          "not from running " + component + " service.");
      System.out.println("To analyze runtime config, specify service address:");
      System.out.println("  For OM: --om-host <hostname>:9874");
      System.out.println("  For SCM: --scm-host <hostname>:9876");
      System.out.println("  For DataNode: --datanode-host <hostname>:9882");
      return conf;
    }
  }

  /**
   * Fetch configuration from running service via HTTP /conf endpoint.
   */
  private OzoneConfiguration fetchConfigurationFromService() throws IOException {
    String confUrl = "http://" + serviceHttpAddress + "/conf";
    
    try {
      URL url = new URL(confUrl);
      HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
      
      // Set connection properties
      httpConn.setRequestMethod("GET");
      httpConn.setConnectTimeout(10000); // 10 seconds
      httpConn.setReadTimeout(30000); // 30 seconds
      httpConn.setInstanceFollowRedirects(true);
      
      // Connect
      httpConn.connect();

      int responseCode = httpConn.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        throw new IOException("HTTP error code: " + responseCode + 
            " when fetching config from " + confUrl);
      }

      // Read configuration XML from service into memory first
      // This prevents "stream is closed" errors when Configuration parses lazily
      byte[] configData;
      try (InputStream inputStream = httpConn.getInputStream();
           ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
          buffer.write(data, 0, bytesRead);
        }
        configData = buffer.toByteArray();
      }
      
      // Now parse the configuration from the buffered data
      OzoneConfiguration runtimeConf = new OzoneConfiguration();
      try (ByteArrayInputStream configStream = 
          new ByteArrayInputStream(configData)) {
        runtimeConf.addResource(configStream);
      }
      
      System.out.println("Successfully fetched runtime configuration from " +
          component);
      return runtimeConf;
      
    } catch (IOException e) {
      System.err.println("Failed to fetch runtime config from " + confUrl +
          ": " + e.getMessage());
      System.err.println("Falling back to local configuration files.");
      return conf;
    }
  }

  private JsonNode loadRules() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    InputStream rulesStream;

    if (rulesPath != null) {
      // Load custom rules from file
      rulesStream = new FileInputStream(rulesPath);
    } else {
      // Load built-in rules from resources
      rulesStream = getClass().getClassLoader()
          .getResourceAsStream(defaultRulesResource);

      if (rulesStream == null) {
        throw new IOException("Cannot find built-in " + component + 
            " rules. Please specify --rules option.");
      }
    }

    return mapper.readTree(rulesStream);
  }

  private List<ConfigViolation> analyzeConfiguration(JsonNode rulesJson,
      OzoneConfiguration runtimeConf) {
    List<ConfigViolation> violations = new ArrayList<>();
    JsonNode rules = rulesJson.get("rules");

    if (rules == null || !rules.isArray()) {
      System.err.println("Invalid rules format: 'rules' array not found");
      return violations;
    }

    for (JsonNode rule : rules) {
      String id = rule.get("id").asText();
      String configKey = rule.get("configKey").asText();
      String severity = rule.get("severity").asText();
      JsonNode validation = rule.get("validation");
      String message = rule.get("message").asText();

      // Get config value from runtime configuration
      String configValue = runtimeConf.get(configKey);

      // Check if rule is violated
      boolean violated = checkViolation(configKey, configValue, validation);

      if (violated) {
        ConfigViolation violation = new ConfigViolation(
            id,
            configKey,
            severity,
            "VIOLATED",
            message,
            configValue,
            rule.get("recommendation").asText(),
            rule.get("impact").asText()
        );
        violations.add(violation);
      }
    }

    return violations;
  }

  private boolean checkViolation(String configKey, String configValue,
      JsonNode validation) {
    if (configValue == null) {
      // Config not set, might be using default - not necessarily a violation
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
        case "CAPACITY_CHECK":
          return checkCapacityViolation(configKey, configValue, validation);
        case "RELATIONSHIP":
          // Relationship checks require multiple configs, skip for now
          return false;
        default:
          System.err.println("Unknown validation type: " + validationType);
          return false;
      }
    } catch (Exception e) {
      System.err.println("Error checking violation for " + configKey + ": " +
          e.getMessage());
      return false;
    }
  }

  private boolean checkRangeViolation(String value, JsonNode validation) {
    try {
      // Check for context-specific validation (e.g., volume_capacity)
      if (validation.has("context")) {
        String context = validation.get("context").asText();
        if ("volume_capacity".equals(context)) {
          // DN-STOR-001: Reserved space should be at least 10% of volume capacity
          return checkReservedSpaceMinimum(value, validation);
        }
      }
      
      if (validation.has("min") && validation.has("max")) {
        String minStr = validation.get("min").asText();
        String maxStr = validation.get("max").asText();
        
        // Check if values contain storage size units (GB, MB, etc.)
        if (isStorageSize(value) || isStorageSize(minStr) || isStorageSize(maxStr)) {
          return checkStorageSizeRange(value, minStr, maxStr);
        }
        
        // Check if values contain time duration units (s, m, h, etc.)
        if (isTimeDuration(value) || isTimeDuration(minStr) || isTimeDuration(maxStr)) {
          return checkTimeDurationRange(value, minStr, maxStr);
        }
        
        // Try parsing as double to handle both int and decimal values
        double doubleValue = Double.parseDouble(value);
        double min = validation.get("min").asDouble();
        double max = validation.get("max").asDouble();
        return doubleValue < min || doubleValue > max;
      }
    } catch (NumberFormatException e) {
      // Could be a size or duration value, needs more sophisticated parsing
      System.err.println("Failed to parse numeric value: " + value);
      return false;
    }
    return false;
  }

  /**
   * Check if string represents a storage size (e.g., "100MB", "5GB").
   */
  private boolean isStorageSize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }
    String upper = value.trim().toUpperCase();
    return upper.matches(".*\\d+\\s*(B|KB|MB|GB|TB|PB)$");
  }

  /**
   * Check if string represents a time duration (e.g., "60s", "5m").
   */
  private boolean isTimeDuration(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }
    String lower = value.trim().toLowerCase();
    return lower.matches(".*\\d+\\s*(ms|s|m|h|d)$");
  }

  /**
   * Check storage size range violation.
   */
  private boolean checkStorageSizeRange(String value, String minStr, String maxStr) {
    try {
      // Parse storage sizes
      StorageSize valueSize = StorageSize.parse(value);
      StorageSize minSize = StorageSize.parse(minStr);
      StorageSize maxSize = StorageSize.parse(maxStr);
      
      // Convert all to bytes for comparison
      long valueBytes = (long) valueSize.getUnit().toBytes(valueSize.getValue());
      long minBytes = (long) minSize.getUnit().toBytes(minSize.getValue());
      long maxBytes = (long) maxSize.getUnit().toBytes(maxSize.getValue());
      
      boolean violation = valueBytes < minBytes || valueBytes > maxBytes;
      
      if (violation) {
        System.out.println("DEBUG: Storage size " + value + " (" + 
            valueBytes + " bytes) outside range [" + minStr + " (" + 
            minBytes + " bytes), " + maxStr + " (" + maxBytes + " bytes)]");
      }
      
      return violation;
      
    } catch (IllegalArgumentException e) {
      System.err.println("Failed to parse storage size: " + e.getMessage());
      return false;
    }
  }

  /**
   * Check time duration range violation.
   */
  private boolean checkTimeDurationRange(String value, String minStr, String maxStr) {
    try {
      // Parse time durations to seconds for comparison
      long valueSeconds = parseTimeToSeconds(value);
      long minSeconds = parseTimeToSeconds(minStr);
      long maxSeconds = parseTimeToSeconds(maxStr);
      
      boolean violation = valueSeconds < minSeconds || valueSeconds > maxSeconds;
      
      if (violation) {
        System.out.println("DEBUG: Time duration " + value + " (" + 
            valueSeconds + "s) outside range [" + minStr + " (" + 
            minSeconds + "s), " + maxStr + " (" + maxSeconds + "s)]");
      }
      
      return violation;
      
    } catch (Exception e) {
      System.err.println("Failed to parse time duration: " + e.getMessage());
      return false;
    }
  }

  /**
   * Parse time duration string to seconds.
   * Supports: ms, s, m, h, d
   */
  private long parseTimeToSeconds(String duration) {
    if (duration == null || duration.trim().isEmpty()) {
      throw new IllegalArgumentException("Duration cannot be empty");
    }
    
    String trimmed = duration.trim().toLowerCase();
    
    // Extract number and unit
    Pattern pattern = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");
    Matcher matcher = pattern.matcher(trimmed);
    
    if (!matcher.find()) {
      throw new IllegalArgumentException("Invalid duration format: " + duration);
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
   * Check DN-STOR-001: Reserved space should be at least X% of volume capacity.
   */
  private boolean checkReservedSpaceMinimum(String reservedConfig,
      JsonNode validation) {
    try {
      // Fetch volume metrics from JMX
      Map<String, VolumeMetrics> volumeMetrics = fetchVolumeMetricsFromJMX();
      
      if (volumeMetrics.isEmpty()) {
        System.out.println("INFO: Cannot fetch volume metrics from JMX. " +
            "Skipping DN-STOR-001 validation.");
        return false;
      }

      long totalCapacity = calculateTotalCapacity(volumeMetrics);
      long totalReserved = calculateTotalReserved(reservedConfig, volumeMetrics);

      // Parse minimum percentage (e.g., "10%" → 0.10)
      String minStr = validation.get("min").asText();
      double minPercentage;
      
      if (minStr.endsWith("%")) {
        minPercentage = Double.parseDouble(minStr.substring(0, 
            minStr.length() - 1)) / 100.0;
      } else {
        minPercentage = Double.parseDouble(minStr);
      }

      long requiredReserved = (long) (totalCapacity * minPercentage);
      
      if (totalReserved < requiredReserved) {
        System.out.println("DEBUG: Reserved space (" + 
            formatBytes(totalReserved) + ") is less than required minimum (" + 
            formatBytes(requiredReserved) + " = " + 
            (minPercentage * 100) + "% of " + formatBytes(totalCapacity) + ")");
        return true; // Violation!
      }

      return false; // No violation

    } catch (Exception e) {
      System.err.println("Error checking reserved space minimum: " + 
          e.getMessage());
      return false;
    }
  }

  private boolean checkMinValueViolation(String value, JsonNode validation) {
    try {
      if (validation.has("min")) {
        String minStr = validation.get("min").asText();
        // Simple numeric comparison for now
        if (!minStr.contains("MB") && !minStr.contains("GB") &&
            !minStr.contains("s") && !minStr.contains("%")) {
          long longValue = Long.parseLong(value);
          long min = Long.parseLong(minStr);
          return longValue < min;
        }
      }
    } catch (NumberFormatException e) {
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
      // Parse percentage value (can be 0.05 or 5% format)
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
      System.err.println("Cannot parse percentage value: " + value);
      return false;
    }
  }

  private boolean checkCapacityViolation(String configKey, String configValue,
      JsonNode validation) {
    // Check if this requires runtime data
    if (validation.has("requiresRuntime") && 
        validation.get("requiresRuntime").asBoolean()) {
      // Fetch volume metrics from JMX
      try {
        Map<String, VolumeMetrics> volumeMetrics = fetchVolumeMetricsFromJMX();
        
        if (volumeMetrics.isEmpty()) {
          System.out.println("INFO: Cannot fetch volume metrics from JMX. " +
              "Skipping capacity validation for: " + configKey);
          return false;
        }

        long totalCapacity = calculateTotalCapacity(volumeMetrics);
        long totalReserved = calculateTotalReserved(configValue, volumeMetrics);

        String rule = validation.get("rule").asText();
        
        // DN-STOR-003: reserved_bytes <= total_capacity
        if (rule.contains("reserved_bytes <= total_capacity") && 
            !rule.contains("* 0.30")) {
          if (totalReserved > totalCapacity) {
            System.out.println("DEBUG: Reserved space (" + 
                formatBytes(totalReserved) + ") exceeds total capacity (" + 
                formatBytes(totalCapacity) + ")");
            return true; // Violation!
          }
        }
        
        // DN-STOR-004: reserved_bytes <= total_capacity * 0.30
        if (rule.contains("reserved_bytes <= total_capacity * 0.30")) {
          long maxReserved = (long) (totalCapacity * 0.30);
          if (totalReserved > maxReserved) {
            System.out.println("DEBUG: Reserved space (" + 
                formatBytes(totalReserved) + ") exceeds 30% of capacity (" + 
                formatBytes(maxReserved) + ")");
            return true; // Violation!
          }
        }

        return false; // No violation

      } catch (Exception e) {
        System.err.println("Error checking capacity violation for " + 
            configKey + ": " + e.getMessage());
        return false;
      }
    }

    // Static capacity checks can be implemented here if needed
    return false;
  }

  /**
   * Format bytes as human-readable string.
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
  }

  private void printAsJson(List<ConfigViolation> violations)
      throws IOException {
    System.out.println(JsonUtils.toJsonStringWithDefaultPrettyPrinter(
        new ConfigAnalysisResult(component, violations.size(), violations)));
  }

  private void printAsTable(List<ConfigViolation> violations) {
    FormattingCLIUtils formattingCLIUtils =
        new FormattingCLIUtils(component + " Configuration Analysis")
            .addHeaders(CONFIG_ANALYZER_HEADER);

    for (ConfigViolation violation : violations) {
      formattingCLIUtils.addLine(new String[]{
          violation.ruleId,
          violation.configKey,
          violation.severity,
          violation.status,
          truncate(violation.message, 50)
      });
    }

    System.out.println(formattingCLIUtils.render());
    System.out.println("\nTotal violations: " + violations.size());

    if (!violations.isEmpty()) {
      System.out.println("\nRun with --json for detailed recommendations");
    }
  }

  private void printAsText(List<ConfigViolation> violations) {
    System.out.println(component + " Configuration Analysis");
    System.out.println(repeatString("=", 60));
    System.out.println();

    if (violations.isEmpty()) {
      System.out.println("✓ No configuration violations found");
      return;
    }

    System.out.println("Found " + violations.size() + " violation(s):\n");

    for (ConfigViolation violation : violations) {
      System.out.println("[" + violation.severity + "] " + violation.ruleId);
      System.out.println("  Config: " + violation.configKey);
      if (violation.currentValue != null) {
        System.out.println("  Current Value: " + violation.currentValue);
      }
      System.out.println("  Issue: " + violation.message);
      System.out.println("  Recommendation: " + violation.recommendation);
      System.out.println("  Impact: " + violation.impact);
      System.out.println();
    }
  }

  private String truncate(String str, int maxLength) {
    if (str == null) {
      return "";
    }
    return str.length() <= maxLength ? 
        str : str.substring(0, maxLength - 3) + "...";
  }

  private String repeatString(String str, int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      sb.append(str);
    }
    return sb.toString();
  }

  /**
   * Represents a configuration violation.
   */
  public static class ConfigViolation {
    private final String ruleId;
    private final String configKey;
    private final String severity;
    private final String status;
    private final String message;
    private final String currentValue;
    private final String recommendation;
    private final String impact;

    public ConfigViolation(String ruleId, String configKey, String severity,
        String status, String message, String currentValue,
        String recommendation, String impact) {
      this.ruleId = ruleId;
      this.configKey = configKey;
      this.severity = severity;
      this.status = status;
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

    public String getSeverity() {
      return severity;
    }

    public String getStatus() {
      return status;
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
   * Result of configuration analysis.
   */
  public static class ConfigAnalysisResult {
    private final String component;
    private final int violationCount;
    private final List<ConfigViolation> violations;

    public ConfigAnalysisResult(String component, int violationCount,
        List<ConfigViolation> violations) {
      this.component = component;
      this.violationCount = violationCount;
      this.violations = violations;
    }

    public String getComponent() {
      return component;
    }

    public int getViolationCount() {
      return violationCount;
    }

    public List<ConfigViolation> getViolations() {
      return violations;
    }
  }

  /**
   * Represents volume metrics from JMX.
   */
  private static class VolumeMetrics {
    private final String volumePath;
    private final long capacity;
    private final long available;
    private final long used;
    private final long reserved;

    public VolumeMetrics(String volumePath, long capacity, long available,
        long used, long reserved) {
      this.volumePath = volumePath;
      this.capacity = capacity;
      this.available = available;
      this.used = used;
      this.reserved = reserved;
    }

    public String getVolumePath() {
      return volumePath;
    }

    public long getCapacity() {
      return capacity;
    }

    public long getAvailable() {
      return available;
    }

    public long getUsed() {
      return used;
    }

    public long getReserved() {
      return reserved;
    }
  }

  /**
   * Fetch volume metrics from DataNode JMX endpoint.
   */
  private Map<String, VolumeMetrics> fetchVolumeMetricsFromJMX()
      throws IOException {
    Map<String, VolumeMetrics> volumeMetrics = new HashMap<>();

    if (serviceHttpAddress == null || serviceHttpAddress.isEmpty()) {
      return volumeMetrics;
    }

    // Construct JMX query URL
    String jmxQuery = "Hadoop:service=HddsDatanode,name=VolumeInfoMetrics-*";
    String encodedQuery = URLEncoder.encode(jmxQuery, "UTF-8");
    String jmxUrl = "http://" + serviceHttpAddress + "/jmx?qry=" + encodedQuery;

    try {
      URL url = new URL(jmxUrl);
      HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
      httpConn.setRequestMethod("GET");
      httpConn.setConnectTimeout(10000);
      httpConn.setReadTimeout(30000);
      httpConn.connect();

      int responseCode = httpConn.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        System.err.println("Failed to fetch JMX metrics (HTTP " +
            responseCode + "), skipping capacity validation");
        return volumeMetrics;
      }

      // Read and parse JMX response
      ObjectMapper mapper = new ObjectMapper();
      JsonNode root;
      try (InputStream inputStream = httpConn.getInputStream()) {
        root = mapper.readTree(inputStream);
      }

      JsonNode beans = root.get("beans");
      if (beans != null && beans.isArray()) {
        for (JsonNode bean : beans) {
          String beanName = bean.get("name").asText();
          
          // Extract volume path from bean name
          // Format: Hadoop:service=HddsDatanode,name=VolumeInfoMetrics-/data/disk1
          Pattern pattern = Pattern.compile("VolumeInfoMetrics-(.+)$");
          Matcher matcher = pattern.matcher(beanName);
          
          if (matcher.find()) {
            String volumePath = matcher.group(1);
            long capacity = bean.has("Capacity") ? 
                bean.get("Capacity").asLong() : 0L;
            long available = bean.has("Available") ? 
                bean.get("Available").asLong() : 0L;
            long used = bean.has("Used") ? 
                bean.get("Used").asLong() : 0L;
            long reserved = bean.has("Reserved") ? 
                bean.get("Reserved").asLong() : 0L;

            VolumeMetrics metrics = new VolumeMetrics(
                volumePath, capacity, available, used, reserved);
            volumeMetrics.put(volumePath, metrics);
          }
        }
      }

      return volumeMetrics;

    } catch (IOException e) {
      System.err.println("Failed to fetch volume metrics from JMX: " +
          e.getMessage());
      System.err.println("Capacity validation will be skipped.");
      return volumeMetrics;
    }
  }

  /**
   * Parse hdds.datanode.dir.du.reserved configuration value.
   * Format: "/data/disk1:100GB,/data/disk2:50GB"
   * Returns map of volume path to reserved bytes.
   */
  private Map<String, Long> parseReservedSpaceConfig(String reservedConfig) {
    Map<String, Long> reservedMap = new HashMap<>();
    
    if (reservedConfig == null || reservedConfig.trim().isEmpty()) {
      return reservedMap;
    }

    String[] entries = reservedConfig.split(",");
    for (String entry : entries) {
      String[] parts = entry.trim().split(":");
      if (parts.length >= 2) {
        try {
          String path = new File(parts[0].trim()).getCanonicalPath();
          StorageSize size = StorageSize.parse(parts[1].trim());
          long bytes = (long) size.getUnit().toBytes(size.getValue());
          reservedMap.put(path, bytes);
        } catch (IllegalArgumentException e) {
          System.err.println("Failed to parse storage size from: " + 
              parts[1].trim() + " - " + e.getMessage());
        } catch (IOException e) {
          System.err.println("Failed to resolve path: " + 
              parts[0].trim() + " - " + e.getMessage());
        }
      }
    }

    return reservedMap;
  }

  /**
   * Calculate total capacity across all volumes.
   */
  private long calculateTotalCapacity(Map<String, VolumeMetrics> volumeMetrics) {
    return volumeMetrics.values().stream()
        .mapToLong(VolumeMetrics::getCapacity)
        .sum();
  }

  /**
   * Calculate total reserved space from configuration.
   */
  private long calculateTotalReserved(String reservedConfig,
      Map<String, VolumeMetrics> volumeMetrics) {
    Map<String, Long> reservedMap = parseReservedSpaceConfig(reservedConfig);
    
    if (reservedMap.isEmpty()) {
      // If no per-volume config, return sum of reserved from JMX
      return volumeMetrics.values().stream()
          .mapToLong(VolumeMetrics::getReserved)
          .sum();
    }
    
    // Sum up configured reserved space
    return reservedMap.values().stream()
        .mapToLong(Long::longValue)
        .sum();
  }
}
