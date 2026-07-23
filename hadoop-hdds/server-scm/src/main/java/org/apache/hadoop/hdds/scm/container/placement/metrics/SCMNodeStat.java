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
import com.google.common.base.Preconditions;
import jakarta.annotation.Nonnull;
import java.util.EnumMap;
import java.util.Map;
import org.apache.hadoop.fs.StorageType;

/**
 * This class represents the SCM node stat.
 */
public class SCMNodeStat implements NodeStat {
  private LongMetric capacity;
  private LongMetric scmUsed;
  private LongMetric remaining;
  private LongMetric committed;
  private LongMetric freeSpaceToSpare;
  private LongMetric reserved;

  // Per-StorageType views. Each map is initialised with an entry for every
  // StorageType value so lookups never return null.
  private final Map<StorageType, LongMetric> capacityPerStorageType =
      newZeroedTypeMap();
  private final Map<StorageType, LongMetric> usedPerStorageType =
      newZeroedTypeMap();
  private final Map<StorageType, LongMetric> remainingPerStorageType =
      newZeroedTypeMap();
  private final Map<StorageType, LongMetric> committedPerStorageType =
      newZeroedTypeMap();
  private final Map<StorageType, LongMetric> freeSpaceToSparePerStorageType =
      newZeroedTypeMap();
  private final Map<StorageType, LongMetric> reservedPerStorageType =
      newZeroedTypeMap();

  private static Map<StorageType, LongMetric> newZeroedTypeMap() {
    Map<StorageType, LongMetric> m = new EnumMap<>(StorageType.class);
    for (StorageType t : StorageType.values()) {
      m.put(t, new LongMetric(0L));
    }
    return m;
  }

  public SCMNodeStat() {
    this(0L, 0L, 0L, 0L, 0L, 0L);
  }

  public SCMNodeStat(SCMNodeStat other) {
    this(other.capacity.get(), other.scmUsed.get(), other.remaining.get(),
        other.committed.get(), other.freeSpaceToSpare.get(), other.reserved.get());
    // Deep-copy the per-StorageType view so mutations on the copy do not
    // leak back into `other`.
    for (StorageType t : StorageType.values()) {
      capacityPerStorageType.get(t).set(other.capacityPerStorageType.get(t).get());
      usedPerStorageType.get(t).set(other.usedPerStorageType.get(t).get());
      remainingPerStorageType.get(t).set(other.remainingPerStorageType.get(t).get());
      committedPerStorageType.get(t).set(other.committedPerStorageType.get(t).get());
      freeSpaceToSparePerStorageType.get(t)
          .set(other.freeSpaceToSparePerStorageType.get(t).get());
      reservedPerStorageType.get(t).set(other.reservedPerStorageType.get(t).get());
    }
  }

  public SCMNodeStat(long capacity, long used, long remaining, long committed,
                     long freeSpaceToSpare, long reserved) {
    Preconditions.checkArgument(capacity >= 0, "Capacity cannot be " +
        "negative.");
    Preconditions.checkArgument(used >= 0, "used space cannot be " +
        "negative.");
    Preconditions.checkArgument(remaining >= 0, "remaining cannot be " +
        "negative");
    this.capacity = new LongMetric(capacity);
    this.scmUsed = new LongMetric(used);
    this.remaining = new LongMetric(remaining);
    this.committed = new LongMetric(committed);
    this.freeSpaceToSpare = new LongMetric(freeSpaceToSpare);
    this.reserved = new LongMetric(reserved);
    // Attribute the entire scalar total to StorageType.DEFAULT so per-type
    // lookups for DEFAULT match the totals for the legacy constructor path.
    capacityPerStorageType.get(StorageType.DEFAULT).set(capacity);
    usedPerStorageType.get(StorageType.DEFAULT).set(used);
    remainingPerStorageType.get(StorageType.DEFAULT).set(remaining);
    committedPerStorageType.get(StorageType.DEFAULT).set(committed);
    freeSpaceToSparePerStorageType.get(StorageType.DEFAULT).set(freeSpaceToSpare);
    reservedPerStorageType.get(StorageType.DEFAULT).set(reserved);
  }

  /**
   * @return the total configured capacity of the node.
   */
  @Override
  public LongMetric getCapacity() {
    return capacity;
  }

  /**
   * @return the total SCM used space on the node.
   */
  @Override
  public LongMetric getScmUsed() {
    return scmUsed;
  }

  /**
   * @return the total remaining space available on the node.
   */
  @Override
  public LongMetric getRemaining() {
    return remaining;
  }

  /**
   *
   * @return the total committed space on the node
   */
  @Override
  public LongMetric getCommitted() {
    return committed;
  }

  /**
   * Get a min space available to spare on the node.
   * @return a min free space available to spare on the node
   */
  @Override
  public LongMetric getFreeSpaceToSpare() {
    return freeSpaceToSpare;
  }

  /**
   * Get the reserved space on the node.
   * @return the reserved space on the node
   */
  @Override
  public LongMetric getReserved() {
    return reserved;
  }

  @Override
  public LongMetric getCapacity(StorageType storageType) {
    return storageType == null ? capacity : capacityPerStorageType.get(storageType);
  }

  @Override
  public LongMetric getScmUsed(StorageType storageType) {
    return storageType == null ? scmUsed : usedPerStorageType.get(storageType);
  }

  @Override
  public LongMetric getRemaining(StorageType storageType) {
    return storageType == null ? remaining : remainingPerStorageType.get(storageType);
  }

  @Override
  public LongMetric getCommitted(StorageType storageType) {
    return storageType == null ? committed : committedPerStorageType.get(storageType);
  }

  @Override
  public LongMetric getFreeSpaceToSpare(StorageType storageType) {
    return storageType == null
        ? freeSpaceToSpare : freeSpaceToSparePerStorageType.get(storageType);
  }

  @Override
  public LongMetric getReserved(StorageType storageType) {
    return storageType == null ? reserved : reservedPerStorageType.get(storageType);
  }

  /**
   * Set the capacity, used and remaining space on a datanode.
   *
   * @param newCapacity in bytes
   * @param newUsed in bytes
   * @param newRemaining in bytes
   */
  @Override
  @VisibleForTesting
  public void set(long newCapacity, long newUsed, long newRemaining,
                  long newCommitted, long newFreeSpaceToSpare, long newReserved) {
    Preconditions.checkArgument(newCapacity >= 0, "Capacity cannot be " +
        "negative.");
    Preconditions.checkArgument(newUsed >= 0, "used space cannot be " +
        "negative.");
    Preconditions.checkArgument(newRemaining >= 0, "remaining cannot be " +
        "negative");

    this.capacity = new LongMetric(newCapacity);
    this.scmUsed = new LongMetric(newUsed);
    this.remaining = new LongMetric(newRemaining);
    this.committed = new LongMetric(newCommitted);
    this.freeSpaceToSpare = new LongMetric(newFreeSpaceToSpare);
    this.reserved = new LongMetric(newReserved);

    // Reset per-StorageType view and attribute the totals to DEFAULT.
    for (StorageType t : StorageType.values()) {
      capacityPerStorageType.get(t).set(0L);
      usedPerStorageType.get(t).set(0L);
      remainingPerStorageType.get(t).set(0L);
      committedPerStorageType.get(t).set(0L);
      freeSpaceToSparePerStorageType.get(t).set(0L);
      reservedPerStorageType.get(t).set(0L);
    }
    capacityPerStorageType.get(StorageType.DEFAULT).set(newCapacity);
    usedPerStorageType.get(StorageType.DEFAULT).set(newUsed);
    remainingPerStorageType.get(StorageType.DEFAULT).set(newRemaining);
    committedPerStorageType.get(StorageType.DEFAULT).set(newCommitted);
    freeSpaceToSparePerStorageType.get(StorageType.DEFAULT).set(newFreeSpaceToSpare);
    reservedPerStorageType.get(StorageType.DEFAULT).set(newReserved);
  }

  /**
   * Adds a new nodestat to existing values of the node.
   *
   * @param stat Nodestat.
   * @return SCMNodeStat
   */
  @Override
  public SCMNodeStat add(NodeStat stat) {
    this.capacity.set(this.getCapacity().get() + stat.getCapacity().get());
    this.scmUsed.set(this.getScmUsed().get() + stat.getScmUsed().get());
    this.remaining.set(this.getRemaining().get() + stat.getRemaining().get());
    this.committed.set(this.getCommitted().get() + stat.getCommitted().get());
    this.freeSpaceToSpare.set(this.freeSpaceToSpare.get() + stat.getFreeSpaceToSpare().get());
    this.reserved.set(this.reserved.get() + stat.getReserved().get());
    for (StorageType t : StorageType.values()) {
      capacityPerStorageType.get(t)
          .set(capacityPerStorageType.get(t).get() + stat.getCapacity(t).get());
      usedPerStorageType.get(t)
          .set(usedPerStorageType.get(t).get() + stat.getScmUsed(t).get());
      remainingPerStorageType.get(t)
          .set(remainingPerStorageType.get(t).get() + stat.getRemaining(t).get());
      committedPerStorageType.get(t)
          .set(committedPerStorageType.get(t).get() + stat.getCommitted(t).get());
      freeSpaceToSparePerStorageType.get(t)
          .set(freeSpaceToSparePerStorageType.get(t).get()
              + stat.getFreeSpaceToSpare(t).get());
      reservedPerStorageType.get(t)
          .set(reservedPerStorageType.get(t).get() + stat.getReserved(t).get());
    }
    return this;
  }

  @Override
  public SCMNodeStat add(long addCapacity, long addUsed, long addRemaining,
      long addCommitted, long addFreeSpaceToSpare, long addReserved,
      @Nonnull StorageType storageType) {
    capacity.set(capacity.get() + addCapacity);
    scmUsed.set(scmUsed.get() + addUsed);
    remaining.set(remaining.get() + addRemaining);
    committed.set(committed.get() + addCommitted);
    freeSpaceToSpare.set(freeSpaceToSpare.get() + addFreeSpaceToSpare);
    reserved.set(reserved.get() + addReserved);
    capacityPerStorageType.get(storageType)
        .set(capacityPerStorageType.get(storageType).get() + addCapacity);
    usedPerStorageType.get(storageType)
        .set(usedPerStorageType.get(storageType).get() + addUsed);
    remainingPerStorageType.get(storageType)
        .set(remainingPerStorageType.get(storageType).get() + addRemaining);
    committedPerStorageType.get(storageType)
        .set(committedPerStorageType.get(storageType).get() + addCommitted);
    freeSpaceToSparePerStorageType.get(storageType)
        .set(freeSpaceToSparePerStorageType.get(storageType).get() + addFreeSpaceToSpare);
    reservedPerStorageType.get(storageType)
        .set(reservedPerStorageType.get(storageType).get() + addReserved);
    return this;
  }

  /**
   * Subtracts the stat values from the existing NodeStat.
   *
   * @param stat SCMNodeStat.
   * @return Modified SCMNodeStat
   */
  @Override
  public SCMNodeStat subtract(NodeStat stat) {
    this.capacity.set(this.getCapacity().get() - stat.getCapacity().get());
    this.scmUsed.set(this.getScmUsed().get() - stat.getScmUsed().get());
    this.remaining.set(this.getRemaining().get() - stat.getRemaining().get());
    this.committed.set(this.getCommitted().get() - stat.getCommitted().get());
    this.freeSpaceToSpare.set(freeSpaceToSpare.get() - stat.getFreeSpaceToSpare().get());
    this.reserved.set(reserved.get() - stat.getReserved().get());
    for (StorageType t : StorageType.values()) {
      capacityPerStorageType.get(t)
          .set(capacityPerStorageType.get(t).get() - stat.getCapacity(t).get());
      usedPerStorageType.get(t)
          .set(usedPerStorageType.get(t).get() - stat.getScmUsed(t).get());
      remainingPerStorageType.get(t)
          .set(remainingPerStorageType.get(t).get() - stat.getRemaining(t).get());
      committedPerStorageType.get(t)
          .set(committedPerStorageType.get(t).get() - stat.getCommitted(t).get());
      freeSpaceToSparePerStorageType.get(t)
          .set(freeSpaceToSparePerStorageType.get(t).get()
              - stat.getFreeSpaceToSpare(t).get());
      reservedPerStorageType.get(t)
          .set(reservedPerStorageType.get(t).get() - stat.getReserved(t).get());
    }
    return this;
  }

  @Override
  public boolean equals(Object to) {
    if (to instanceof SCMNodeStat) {
      SCMNodeStat tempStat = (SCMNodeStat) to;
      return capacity.isEqual(tempStat.getCapacity().get()) &&
          scmUsed.isEqual(tempStat.getScmUsed().get()) &&
          remaining.isEqual(tempStat.getRemaining().get()) &&
          committed.isEqual(tempStat.getCommitted().get()) &&
          freeSpaceToSpare.isEqual(tempStat.freeSpaceToSpare.get()) &&
          reserved.isEqual(tempStat.reserved.get());
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(capacity.get() ^ scmUsed.get() ^ remaining.get() ^
        committed.get() ^ freeSpaceToSpare.get() ^ reserved.get());
  }

  @Override
  public String toString() {
    return "SCMNodeStat{" +
        "capacity=" + capacity.get() +
        ", scmUsed=" + scmUsed.get() +
        ", remaining=" + remaining.get() +
        ", committed=" + committed.get() +
        ", freeSpaceToSpare=" + freeSpaceToSpare.get() +
        ", reserved=" + reserved.get() +
        '}';
  }
}
