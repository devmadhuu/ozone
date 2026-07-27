/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The
 * ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.container.diskbalancer;

import static java.util.Arrays.asList;
import static org.apache.hadoop.hdds.fs.MockSpaceUsagePersistence.inMemory;
import static org.apache.hadoop.hdds.fs.MockSpaceUsageSource.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.fs.MockSpaceUsageCheckFactory;
import org.apache.hadoop.hdds.fs.SpaceUsageCheckFactory;
import org.apache.hadoop.hdds.fs.SpaceUsageSource;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.common.volume.StorageVolume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Tests for {@link DiskBalancerUtils}.
 */
public class DiskBalancerUtilsTest {

  @TempDir
  File testRoot;

  private MutableVolumeSet volumeSet;
  private Map<HddsVolume, Long> deltaMap;

  private static final OzoneConfiguration CONF = new OzoneConfiguration();
  private static final long CAPACITY = 100L;

  public void setUp(List<StorageVolume> hddsVolumes) throws IOException {
    volumeSet = Mockito.mock(MutableVolumeSet.class);
    deltaMap = new HashMap<>();
    when(volumeSet.getVolumesList()).thenReturn(hddsVolumes);
  }

  @Test
  public void testGetVolumePair() throws IOException {
    UUID datanodeId = UUID.randomUUID();
    Pair<HddsVolume, HddsVolume> result;
    HddsVolume vol1 = createHddsVolume(datanodeId, StorageType.SSD, 0.6);
    HddsVolume vol2 = createHddsVolume(datanodeId, StorageType.SSD, 0.3);
    HddsVolume vol3 = createHddsVolume(datanodeId, StorageType.SSD, 0.2);
    HddsVolume vol4 = createHddsVolume(datanodeId, StorageType.DISK, 0.75);
    HddsVolume vol5 = createHddsVolume(datanodeId, StorageType.DISK, 0.5);
    List<StorageVolume> hddsVolumes = asList(vol1, vol2, vol3, vol4, vol5);
    setUp(hddsVolumes);

    // With no storage type filter, picks the globally-best pair.
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, new ArrayList<>());
    assertNotNull(result, "Expected a non-null volume pair");

    // Restricting to SSD: vol1 (60 %) → vol3 (20 %)
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, asList(StorageType.SSD));
    assertNotNull(result, "Expected SSD pair");
    assertEquals(vol1.getStorageDir(), result.getLeft().getStorageDir(),
        "Source should be most-used SSD volume");
    assertEquals(vol3.getStorageDir(), result.getRight().getStorageDir(),
        "Dest should be least-used SSD volume");

    // Restricting to DISK: vol4 (75 %) → vol5 (50 %)
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, asList(StorageType.DISK));
    assertNotNull(result, "Expected DISK pair");
    assertEquals(vol4.getStorageDir(), result.getLeft().getStorageDir(),
        "Source should be most-used DISK volume");
    assertEquals(vol5.getStorageDir(), result.getRight().getStorageDir(),
        "Dest should be least-used DISK volume");

    // No ARCHIVE volumes → null
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, asList(StorageType.ARCHIVE));
    assertNull(result, "Expected null for ARCHIVE (no volumes)");
  }

  @Test
  public void testGetVolumePair2() throws IOException {
    UUID datanodeId = UUID.randomUUID();
    Pair<HddsVolume, HddsVolume> result;
    HddsVolume vol1 = createHddsVolume(datanodeId, StorageType.SSD, 0.2);
    HddsVolume vol2 = createHddsVolume(datanodeId, StorageType.DISK, 0.75);
    HddsVolume vol3 = createHddsVolume(datanodeId, StorageType.DISK, 0.5);
    List<StorageVolume> hddsVolumes = asList(vol1, vol2, vol3);
    setUp(hddsVolumes);

    // Only one SSD vol → no SSD pair
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, asList(StorageType.SSD));
    assertNull(result, "Expected null for SSD (only 1 volume)");

    // DISK: vol2 (75 %) → vol3 (50 %)
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, asList(StorageType.DISK));
    assertNotNull(result, "Expected DISK pair");
    assertEquals(vol2.getStorageDir(), result.getLeft().getStorageDir());
    assertEquals(vol3.getStorageDir(), result.getRight().getStorageDir());

    // No filter → DISK pair is the best available
    result = DiskBalancerUtils.getVolumePair(
        volumeSet, 0, deltaMap, new ArrayList<>());
    assertNotNull(result, "Expected pair with no filter");
  }

  private HddsVolume createHddsVolume(UUID datanodeId,
      StorageType storageType, double utilization) throws IOException {
    long available = (long) (CAPACITY * (1 - utilization));
    long used = (long) (CAPACITY * utilization);
    SpaceUsageSource spaceUsage = fixed(CAPACITY, available, used);
    SpaceUsageCheckFactory factory = MockSpaceUsageCheckFactory.of(
        spaceUsage, Duration.ZERO, inMemory(new AtomicLong(0)));
    return new HddsVolume.Builder(
        new File(testRoot, UUID.randomUUID().toString()).getAbsolutePath())
        .datanodeUuid(datanodeId.toString())
        .conf(CONF)
        .storageType(storageType)
        .usageCheckFactory(factory)
        .build();
  }
}
