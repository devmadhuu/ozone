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

import org.apache.hadoop.hdds.conf.ConfigurationException;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.conf.StorageSize;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.ozone.container.common.volume.StorageVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates DataNode volume configuration after volumes are initialized.
 * Checks reserved space against actual volume capacities.
 */
public class DatanodeVolumeConfigValidator {

  private static final Logger LOG =
      LoggerFactory.getLogger(DatanodeVolumeConfigValidator.class);

  private static final String CONFIG_KEY_ENFORCE_VOLUME_VALIDATION =
      "hdds.datanode.startup.enforce.volume.validation";
  private static final String CONFIG_KEY_VALIDATION_MODE =
      "hdds.datanode.startup.validation.mode";

  private final ConfigurationSource conf;

  public DatanodeVolumeConfigValidator(ConfigurationSource conf) {
    this.conf = conf;
  }

  /**
   * Validate volume configuration against actual volumes.
   *
   * @param volumeMap Map of volume path to StorageVolume
   * @throws ConfigurationException if validation fails and enforcement enabled
   */
  public void validateVolumes(Map<String, ? extends StorageVolume> volumeMap)
      throws ConfigurationException {

    boolean enforceValidation = conf.getBoolean(
        CONFIG_KEY_ENFORCE_VOLUME_VALIDATION, true);

    String modeStr = conf.get(CONFIG_KEY_VALIDATION_MODE, "ENFORCE_ALL");
    boolean isDryRun = "DRY_RUN".equalsIgnoreCase(modeStr);

    LOG.info("Validating volume configuration. Mode: {}, Enforce: {}",
        modeStr, enforceValidation);

    try {
      // Calculate total capacity
      long totalCapacity = calculateTotalCapacity(volumeMap);
      LOG.info("Total volume capacity: {} bytes ({} GB)",
          totalCapacity, totalCapacity / (1024 * 1024 * 1024));

      // Get reserved space configuration
      long totalReserved = calculateTotalReserved(totalCapacity);
      LOG.info("Total reserved space: {} bytes ({} GB)",
          totalReserved, totalReserved / (1024 * 1024 * 1024));

      // Validate DN-STOR-001: Reserved >= 10% of capacity
      validateMinimumReserved(totalCapacity, totalReserved, isDryRun,
          enforceValidation);

      // Validate DN-STOR-003: Reserved <= total capacity (CRITICAL)
      validateReservedNotExceeding(totalCapacity, totalReserved, isDryRun,
          enforceValidation);

      // Validate DN-STOR-004: Reserved <= 30% of capacity
      validateReservedWithin30Percent(totalCapacity, totalReserved, isDryRun,
          enforceValidation);

      LOG.info("Volume configuration validation completed successfully");

    } catch (ConfigurationException e) {
      if (enforceValidation && !isDryRun) {
        throw e;
      } else {
        LOG.warn("Volume configuration validation failed but enforcement " +
            "disabled: {}", e.getMessage());
      }
    }
  }

  private long calculateTotalCapacity(
      Map<String, ? extends StorageVolume> volumeMap) {
    long total = 0;
    for (StorageVolume volume : volumeMap.values()) {
      long capacity = volume.getCurrentUsage().getCapacity();
      total += capacity;
      LOG.debug("Volume {}: capacity = {} bytes",
          volume.getStorageDir(), capacity);
    }
    return total;
  }

  private long calculateTotalReserved(long totalCapacity) {
    // Check if per-volume reserved space is configured
    Collection<String> reserveList = conf.getTrimmedStringCollection(
        ScmConfigKeys.HDDS_DATANODE_DIR_DU_RESERVED);

    long totalReserved = 0;

    if (!reserveList.isEmpty()) {
      // Parse per-volume reserved space
      for (String reserve : reserveList) {
        String[] parts = reserve.split(":");
        if (parts.length >= 2) {
          try {
            StorageSize size = StorageSize.parse(parts[1].trim());
            long bytes = (long) size.getUnit().toBytes(size.getValue());
            totalReserved += bytes;
            LOG.debug("Reserved space for {}: {} bytes",
                parts[0].trim(), bytes);
          } catch (IllegalArgumentException e) {
            LOG.error("Failed to parse reserved space: {}", parts[1].trim(), e);
          }
        }
      }
    } else {
      // Use percentage-based reservation
      float percentage = conf.getFloat(
          ScmConfigKeys.HDDS_DATANODE_DIR_DU_RESERVED_PERCENT,
          ScmConfigKeys.HDDS_DATANODE_DIR_DU_RESERVED_PERCENT_DEFAULT);
      totalReserved = (long) Math.ceil(totalCapacity * percentage);
      LOG.debug("Using percentage-based reservation: {}% = {} bytes",
          percentage * 100, totalReserved);
    }

    return totalReserved;
  }

  private void validateMinimumReserved(long totalCapacity, long totalReserved,
      boolean isDryRun, boolean enforceValidation)
      throws ConfigurationException {

    long requiredMinimum = (long) (totalCapacity * 0.10); // 10%

    if (totalReserved < requiredMinimum) {
      String message = String.format(
          "DN-STOR-001 VIOLATION: Reserved space (%d bytes = %.2f GB) is " +
              "less than required minimum (%d bytes = %.2f GB = 10%% of capacity)",
          totalReserved, totalReserved / (1024.0 * 1024 * 1024),
          requiredMinimum, requiredMinimum / (1024.0 * 1024 * 1024));

      if (isDryRun) {
        LOG.warn("[DRY_RUN] {}", message);
      } else {
        LOG.error(message);
        // This is WARNING severity, so we log but don't throw
      }
    }
  }

  private void validateReservedNotExceeding(long totalCapacity,
      long totalReserved, boolean isDryRun, boolean enforceValidation)
      throws ConfigurationException {

    if (totalReserved > totalCapacity) {
      String message = String.format(
          "DN-STOR-003 CRITICAL VIOLATION: Reserved space (%d bytes = %.2f GB) " +
              "exceeds total capacity (%d bytes = %.2f GB). " +
              "DataNode cannot function with this configuration!",
          totalReserved, totalReserved / (1024.0 * 1024 * 1024),
          totalCapacity, totalCapacity / (1024.0 * 1024 * 1024));

      if (isDryRun) {
        LOG.error("[DRY_RUN] Would have aborted: {}", message);
      } else {
        LOG.error(message);
        
        String validationMode = conf.get(CONFIG_KEY_VALIDATION_MODE, "DRY_RUN");
        boolean shouldAbort = "ENFORCE_CRITICAL".equalsIgnoreCase(validationMode) ||
            "ENFORCE_ALL".equalsIgnoreCase(validationMode);

        if (shouldAbort && enforceValidation) {
          throw new ConfigurationException(
              "\n========================================================\n" +
              "CRITICAL: Reserved space exceeds total capacity!\n" +
              "========================================================\n\n" +
              message + "\n\n" +
              "HOW TO FIX:\n" +
              "  1. Reduce hdds.datanode.dir.du.reserved in ozone-site.xml\n" +
              "  2. Or increase disk capacity\n\n" +
              "Current config: " + 
              conf.get(ScmConfigKeys.HDDS_DATANODE_DIR_DU_RESERVED) + "\n\n" +
              "TO OVERRIDE (NOT RECOMMENDED):\n" +
              "  Set: hdds.datanode.startup.ignore.config.errors=true\n" +
              "========================================================\n");
        }
      }
    }
  }

  private void validateReservedWithin30Percent(long totalCapacity,
      long totalReserved, boolean isDryRun, boolean enforceValidation)
      throws ConfigurationException {

    long maxReserved = (long) (totalCapacity * 0.30); // 30%

    if (totalReserved > maxReserved) {
      String message = String.format(
          "DN-STOR-004 ERROR VIOLATION: Reserved space (%d bytes = %.2f GB) " +
              "exceeds 30%% of capacity (%d bytes = %.2f GB). " +
              "This reduces usable storage significantly.",
          totalReserved, totalReserved / (1024.0 * 1024 * 1024),
          maxReserved, maxReserved / (1024.0 * 1024 * 1024));

      if (isDryRun) {
        LOG.error("[DRY_RUN] Would have aborted: {}", message);
      } else {
        LOG.error(message);
        
        // DN-STOR-004 is now ERROR severity - abort in ENFORCE_ALL mode
        String validationMode = conf.get(CONFIG_KEY_VALIDATION_MODE, "ENFORCE_ALL");
        boolean shouldAbort = "ENFORCE_ALL".equalsIgnoreCase(validationMode);

        if (shouldAbort && enforceValidation) {
          throw new ConfigurationException(
              "\n========================================================\n" +
              "ERROR: Reserved space exceeds 30% of capacity!\n" +
              "========================================================\n\n" +
              message + "\n\n" +
              "HOW TO FIX:\n" +
              "  1. Reduce hdds.datanode.dir.du.reserved in ozone-site.xml\n" +
              "  2. Recommended: Keep reserved space under 30% for efficiency\n\n" +
              "Current config: " + 
              conf.get(ScmConfigKeys.HDDS_DATANODE_DIR_DU_RESERVED) + "\n\n" +
              "TO OVERRIDE:\n" +
              "  Set: hdds.datanode.startup.ignore.config.errors=true\n" +
              "========================================================\n");
        }
      }
    }
  }
}
