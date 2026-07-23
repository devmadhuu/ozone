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

package org.apache.hadoop.hdds.scm.container.placement.metrics;

import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Nonnull;
import org.apache.hadoop.fs.StorageType;

/**
 * Interface that defines Node Stats.
 */
interface NodeStat {
  /**
   * Get capacity of the node.
   * @return capacity of the node.
   */
  LongMetric getCapacity();

  /**
   * Get the used space of the node.
   * @return the used space of the node.
   */
  LongMetric getScmUsed();

  /**
   * Get the remaining space of the node.
   * @return the remaining space of the node.
   */
  LongMetric getRemaining();

  /**
   * Get the committed space of the node.
   * @return the committed space of the node
   */
  LongMetric getCommitted();

  /**
   * Get a min free space available to spare on the node.
   * @return a min free space available to spare
   */
  LongMetric getFreeSpaceToSpare();

  /**
   * Get the reserved space on the node.
   * @return the reserved space on the node
   */
  LongMetric getReserved();

  /**
   * Per-{@link StorageType} view of {@link #getCapacity()}. Passing
   * {@code null} yields the aggregate value (same as {@link #getCapacity()}).
   */
  LongMetric getCapacity(StorageType storageType);

  /**
   * Per-{@link StorageType} view of {@link #getScmUsed()}. Passing
   * {@code null} yields the aggregate value.
   */
  LongMetric getScmUsed(StorageType storageType);

  /**
   * Per-{@link StorageType} view of {@link #getRemaining()}. Passing
   * {@code null} yields the aggregate value.
   */
  LongMetric getRemaining(StorageType storageType);

  /**
   * Per-{@link StorageType} view of {@link #getCommitted()}. Passing
   * {@code null} yields the aggregate value.
   */
  LongMetric getCommitted(StorageType storageType);

  /**
   * Per-{@link StorageType} view of {@link #getFreeSpaceToSpare()}. Passing
   * {@code null} yields the aggregate value.
   */
  LongMetric getFreeSpaceToSpare(StorageType storageType);

  /**
   * Per-{@link StorageType} view of {@link #getReserved()}. Passing
   * {@code null} yields the aggregate value.
   */
  LongMetric getReserved(StorageType storageType);

  /**
   * Set the total/used/remaining space.
   * @param capacity - total space.
   * @param used - used space.
   * @param remain - remaining space.
   */
  @VisibleForTesting
  void set(long capacity, long used, long remain, long committed,
           long freeSpaceToSpare, long reserved);

  /**
   * Adding of the stat.
   * @param stat - stat to be added.
   * @return updated node stat.
   */
  NodeStat add(NodeStat stat);

  /**
   * Add the specified values against a specific {@link StorageType}. The
   * aggregate totals are updated as well.
   *
   * @param capacity         Capacity to add for the specified storage type.
   * @param used             Used space to add.
   * @param remaining        Remaining space to add.
   * @param committed        Committed space to add.
   * @param freeSpaceToSpare Free-space-to-spare to add.
   * @param reserved         Reserved space to add.
   * @param storageType      The storage type these values apply to.
   * @return This stat.
   */
  NodeStat add(long capacity, long used, long remaining, long committed,
      long freeSpaceToSpare, long reserved, @Nonnull StorageType storageType);

  /**
   * Subtract of the stat.
   * @param stat - stat to be subtracted.
   * @return updated nodestat.
   */
  NodeStat subtract(NodeStat stat);
}
