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

package org.apache.hadoop.ozone.container.diskbalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.diskbalancer.DiskBalancerVolumeCalculation.VolumeFixedUsage;

/**
 * Utility methods for the DN Disk Balancer.
 *
 * <p>The primary entry point is
 * {@link #getVolumePair(MutableVolumeSet, double, Map, List)}, which selects
 * the source/target {@link HddsVolume} pair with the greatest utilisation
 * difference while constraining both ends to be on the <em>same</em>
 * {@link StorageType}.  When the supplied {@code storageTypes} list is empty
 * every StorageType is considered.
 */
public final class DiskBalancerUtils {

  private DiskBalancerUtils() {
  }

  /**
   * Select the source and target volumes that have the greatest utilisation
   * difference within a single {@link StorageType}.
   *
   * @param volumeSet   The datanode volume set.
   * @param threshold   Minimum utilisation delta to consider a pair.
   * @param deltaMap    In-progress transfer deltas (source = negative).
   * @param storageTypes Storage types to consider; if empty, all types are
   *                    evaluated and the globally-best pair is returned.
   * @return The pair (source = left, destination = right), or {@code null}
   *         if no eligible pair was found.
   */
  public static Pair<HddsVolume, HddsVolume> getVolumePair(
      MutableVolumeSet volumeSet, double threshold,
      Map<HddsVolume, Long> deltaMap, List<StorageType> storageTypes) {

    List<Pair<HddsVolume, HddsVolume>> allPairs = new ArrayList<>();

    List<StorageType> types = (storageTypes == null || storageTypes.isEmpty())
        ? Arrays.asList(StorageType.values())
        : storageTypes;

    for (StorageType type : types) {
      Pair<HddsVolume, HddsVolume> pair =
          getStorageTypeVolumePair(volumeSet, threshold, deltaMap, type);
      if (pair != null) {
        allPairs.add(pair);
      }
    }

    if (allPairs.isEmpty()) {
      return null;
    }

    // Return the pair with the greatest utilisation difference.
    allPairs.sort((p1, p2) -> {
      double diff1 = calculateUtilizationDiff(p1, deltaMap);
      double diff2 = calculateUtilizationDiff(p2, deltaMap);
      return Double.compare(diff2, diff1);
    });

    return allPairs.get(0);
  }

  /**
   * Select the source/target pair with the greatest utilisation difference
   * whose volumes are both of the given {@code storageType}.
   */
  private static Pair<HddsVolume, HddsVolume> getStorageTypeVolumePair(
      MutableVolumeSet volumeSet, double threshold,
      Map<HddsVolume, Long> deltaMap, StorageType storageType) {

    List<VolumeFixedUsage> usages = DiskBalancerVolumeCalculation
        .getVolumeUsages(volumeSet, deltaMap)
        .stream()
        .filter(u -> u.getVolume().getStorageType().equals(storageType))
        .collect(Collectors.toList());

    if (usages.size() < 2) {
      return null;
    }

    double idealUsage = DiskBalancerVolumeCalculation.getIdealUsage(usages);

    // Volumes sufficiently far from ideal become candidates.
    List<VolumeFixedUsage> candidates = usages.stream()
        .filter(u -> u.getUsage().getCapacity() > 0
            && Math.abs(u.getUtilization() - idealUsage) >= threshold)
        .sorted((a, b) -> {
          // ascending by utilisation so index 0 is the least utilised
          double aUtil = a.getEffectiveUsed() / (double) a.getUsage().getCapacity();
          double bUtil = b.getEffectiveUsed() / (double) b.getUsage().getCapacity();
          return Double.compare(aUtil, bUtil);
        })
        .collect(Collectors.toList());

    if (candidates.size() < 2) {
      return null;
    }

    HddsVolume dest = candidates.get(0).getVolume();
    HddsVolume src  = candidates.get(candidates.size() - 1).getVolume();
    return Pair.of(src, dest);
  }

  private static double calculateUtilizationDiff(
      Pair<HddsVolume, HddsVolume> pair, Map<HddsVolume, Long> deltaMap) {
    HddsVolume src  = pair.getLeft();
    HddsVolume dest = pair.getRight();

    long srcDelta  = deltaMap == null ? 0 : deltaMap.getOrDefault(src,  0L);
    long destDelta = deltaMap == null ? 0 : deltaMap.getOrDefault(dest, 0L);

    long srcCap  = getCapacity(src);
    long destCap = getCapacity(dest);

    double srcUtil  = srcCap  > 0
        ? (src.getCurrentUsage().getUsedSpace()  + srcDelta)  / (double) srcCap  : 0;
    double destUtil = destCap > 0
        ? (dest.getCurrentUsage().getUsedSpace() + destDelta) / (double) destCap : 0;

    return srcUtil - destUtil;
  }

  private static long getCapacity(HddsVolume volume) {
    return volume.getCurrentUsage() != null
        ? volume.getCurrentUsage().getCapacity() : 0L;
  }
}
