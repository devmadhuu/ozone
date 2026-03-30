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

package org.apache.hadoop.ozone.recon.tools;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.Table.KeyValue;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.recon.ReconUtils;
import org.apache.hadoop.ozone.recon.api.types.ContainerKeyPrefix;
import org.apache.hadoop.ozone.recon.spi.ReconNamespaceSummaryManager;
import org.apache.hadoop.ozone.recon.spi.impl.ReconDBDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeks directly into Recon's {@code containerKeyTable} for each container ID
 * in a sorted {@code long[]} produced by {@link ContainerIdLoader}.
 *
 * <p>For every key entry found in the {@code containerKeyTable} this reader:
 * <ol>
 *   <li>Looks up the {@link OmKeyInfo} in the OM snapshot DB (LEGACY
 *       {@code keyTable} first, then the FSO {@code fileTable}), exactly as the
 *       existing {@code /api/v1/containers/{id}/keys} REST endpoint does.</li>
 *   <li>Skips the entry if no {@code OmKeyInfo} is found (key deleted from OM
 *       after the last Recon sync).</li>
 *   <li>Constructs the {@code CompletePath} via
 *       {@link ReconUtils#constructFullPathPrefix} for the parent directory
 *       (caching the result per {@code parentObjectId} to avoid redundant
 *       NSSummary DB walks for keys that share ancestor directories) and then
 *       appends the key name.  For OBS/LEGACY keys ({@code parentObjectId==0})
 *       no NSSummary walk is needed.  The result is semantically identical to
 *       {@link ReconUtils#constructFullPath}, but much faster for FSO clusters
 *       with deep directory trees and many files per directory.</li>
 *   <li>Skips the entry if the constructed path is empty (NSSummary is being
 *       rebuilt).</li>
 *   <li>Suppresses duplicate versions of the same key within a container
 *       (same {@code keyPrefix}, incremented {@code keyVersion}).</li>
 * </ol>
 *
 * <p>Seeking to the first key of each container is O(log n) in the size of
 * the {@code containerKeyTable}; forward iteration within a container is
 * O(keys in container).
 *
 * <p>The per-instance {@code pathPrefixCache} amortises NSSummary walks across
 * all containers scanned in a single {@link #scan} call.  Reuse the same
 * reader instance across containers (as the single-threaded path does) to
 * maximise cache hits.  In the multi-threaded path each worker thread owns its
 * own reader, so the cache is still effective within the subset of containers
 * assigned to that thread.
 */
public class ContainerKeyTableSeekReader implements Closeable {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerKeyTableSeekReader.class);

  private final Table<ContainerKeyPrefix, Integer> containerKeyTable;

  /** OM LEGACY keyTable: keyed by {@code /vol/bucket/keyName}. */
  private final Table<String, OmKeyInfo> legacyKeyTable;

  /** OM FSO fileTable: keyed by {@code /volId/bucketId/parentId/fileName}. */
  private final Table<String, OmKeyInfo> fsoFileTable;

  private final ReconNamespaceSummaryManager nsSummaryManager;

  /**
   * Cache: {@code parentObjectId → "vol/bucket/dir1/.../dirN/"} prefix string.
   *
   * <p>FSO keys that share a parent directory (e.g. 10 000 files under the
   * same leaf dir) pay the NSSummary chain walk cost only once.  Keys with
   * {@code parentObjectId == 0} (OBS / LEGACY bucket layout) are intentionally
   * NOT cached here — their path is derived directly from
   * {@link OmKeyInfo#getVolumeName()} / {@link OmKeyInfo#getBucketName()} /
   * {@link OmKeyInfo#getKeyName()} with no DB lookup.
   *
   * <p>A sentinel empty string ({@code ""}) is stored for parent IDs whose
   * NSSummary chain is missing (NSSummary rebuild in progress), so we don't
   * retry the walk for every key under that parent.
   */
  private final Map<Long, String> pathPrefixCache = new HashMap<>();

  /**
   * Creates a reader that resolves every key to a full {@code CompletePath}
   * using the OM snapshot DB and the Recon NSSummary table — mirroring exactly
   * what {@code /api/v1/containers/{id}/keys} returns.
   *
   * @param reconDb       open Recon container-key DB (read-only)
   * @param omSnapshotDb  open OM snapshot DB (read-only)
   * @param nsSummaryMgr  NSSummary manager backed by {@code reconDb}
   */
  public ContainerKeyTableSeekReader(DBStore reconDb,
      DBStore omSnapshotDb,
      ReconNamespaceSummaryManager nsSummaryMgr) throws IOException {

    this.containerKeyTable = reconDb.getTable(
        ReconDBDefinition.CONTAINER_KEY.getName(),
        ReconDBDefinition.CONTAINER_KEY.getKeyCodec(),
        ReconDBDefinition.CONTAINER_KEY.getValueCodec());

    this.legacyKeyTable = omSnapshotDb.getTable(
        "keyTable",
        org.apache.hadoop.hdds.utils.db.StringCodec.get(),
        OmKeyInfo.getCodec(true));
    this.fsoFileTable = omSnapshotDb.getTable(
        "fileTable",
        org.apache.hadoop.hdds.utils.db.StringCodec.get(),
        OmKeyInfo.getCodec(true));
    this.nsSummaryManager = nsSummaryMgr;
  }

  /**
   * Iterates over all {@code containerKeyTable} entries for the supplied
   * (sorted) container IDs and invokes {@code callback} for each key whose
   * {@code CompletePath} can be resolved.
   *
   * <p>Keys are skipped when:
   * <ul>
   *   <li>No {@code OmKeyInfo} exists in the OM snapshot (key deleted).</li>
   *   <li>{@code ReconUtils.constructFullPath} returns an empty string
   *       (NSSummary is being rebuilt).</li>
   *   <li>The entry is a different version of the immediately preceding key
   *       for the same container (within-container version deduplication).</li>
   * </ul>
   *
   * @param sortedContainerIds sorted array of container IDs to scan
   * @param callback           called with {@code (containerId, completePath)}
   *                           for each unique, resolvable key
   */
  public void scan(long[] sortedContainerIds, KeyEntryCallback callback)
      throws Exception {

    long emitted = 0L;
    long skippedVersions = 0L;
    long skippedMissing = 0L;

    try (TableIterator<ContainerKeyPrefix,
        ? extends KeyValue<ContainerKeyPrefix, Integer>> iter =
            containerKeyTable.iterator()) {

      for (long cid : sortedContainerIds) {
        iter.seek(ContainerKeyPrefix.get(cid));

        // Track the previous raw keyPrefix to skip duplicate versions of the
        // same key within this container (same keyPrefix, higher keyVersion).
        String prevRawKeyPrefix = null;

        while (iter.hasNext()) {
          KeyValue<ContainerKeyPrefix, Integer> entry = iter.next();
          ContainerKeyPrefix ckp = entry.getKey();

          if (ckp.getContainerId() != cid) {
            break;
          }

          String rawKeyPrefix = ckp.getKeyPrefix();

          // Skip further versions of the same key within this container.
          if (rawKeyPrefix.equals(prevRawKeyPrefix)) {
            skippedVersions++;
            continue;
          }
          prevRawKeyPrefix = rawKeyPrefix;

          // Look up OmKeyInfo exactly as the REST endpoint does:
          // try LEGACY keyTable first, then FSO fileTable.
          OmKeyInfo omKeyInfo = legacyKeyTable.getSkipCache(rawKeyPrefix);
          if (omKeyInfo == null) {
            omKeyInfo = fsoFileTable.getSkipCache(rawKeyPrefix);
          }
          if (omKeyInfo == null) {
            // Key deleted from OM after the last Recon sync — skip it,
            // matching the REST endpoint behaviour (if (null != omKeyInfo)).
            skippedMissing++;
            continue;
          }

          // Build CompletePath — same semantics as the REST endpoint but with a
          // per-instance cache that amortises NSSummary walks for FSO keys.
          String completePath = buildCompletePath(omKeyInfo);
          if (completePath == null || completePath.isEmpty()) {
            // NSSummary is being rebuilt — skip and let caller retry later.
            skippedMissing++;
            continue;
          }

          callback.accept(cid, completePath);
          emitted++;
        }
      }
    }

    if (skippedVersions > 0) {
      LOG.debug("Skipped {} duplicate key-version entries within containers.",
          skippedVersions);
    }
    if (skippedMissing > 0) {
      LOG.debug("Skipped {} keys with no OM entry or empty CompletePath "
          + "(deleted or NSSummary rebuild in progress).", skippedMissing);
    }
    LOG.info("Scan complete: {} (containerId, completePath) pairs emitted.",
        emitted);
  }

  /**
   * Builds the complete path for a key, using a per-instance cache for the
   * parent-directory prefix so that NSSummary chain walks are amortised across
   * keys sharing the same ancestor directories.
   *
   * <p>For OBS / LEGACY keys ({@code parentObjectId == 0}) the path is
   * assembled directly from the volume, bucket, and key name stored in
   * {@code OmKeyInfo} — no NSSummary lookup is needed.
   *
   * <p>For FSO keys ({@code parentObjectId != 0}) the prefix is looked up in
   * {@link #pathPrefixCache}.  On a cache miss,
   * {@link ReconUtils#constructFullPathPrefix} is called (which walks the
   * NSSummary chain) and the result is cached.  An empty sentinel is stored
   * when the NSSummary chain is incomplete (rebuild in progress), so the walk
   * is not retried for every sibling file.
   *
   * @return the complete path string, or {@code ""} if the NSSummary chain is
   *         incomplete for this key's parent directory
   */
  private String buildCompletePath(OmKeyInfo omKeyInfo) throws IOException {
    long parentId = omKeyInfo.getParentObjectID();

    if (parentId == 0) {
      // OBS / LEGACY layout: path is already fully encoded in OmKeyInfo.
      // Avoid the NSSummary walk and the cache entirely.
      return omKeyInfo.getVolumeName() + "/"
          + omKeyInfo.getBucketName() + "/"
          + omKeyInfo.getKeyName();
    }

    // FSO layout: look up (or compute and cache) the directory prefix.
    String prefix = pathPrefixCache.get(parentId);
    if (prefix == null) {
      StringBuilder sb = ReconUtils.constructFullPathPrefix(
          parentId,
          omKeyInfo.getVolumeName(),
          omKeyInfo.getBucketName(),
          nsSummaryManager);
      // Store empty string as a sentinel if the chain is broken so we don't
      // retry on every sibling key.
      prefix = sb.toString();
      pathPrefixCache.put(parentId, prefix);
    }

    if (prefix.isEmpty()) {
      return "";
    }
    return prefix + omKeyInfo.getKeyName();
  }

  @Override
  public void close() {
    pathPrefixCache.clear();
    // The caller owns the DBStore instances; nothing to close here.
  }

  /**
   * Callback invoked for each resolvable container-key entry.
   */
  @FunctionalInterface
  public interface KeyEntryCallback {
    /**
     * @param containerId  the container ID
     * @param completePath resolved full path (e.g. {@code vol/bucket/dir/file})
     */
    void accept(long containerId, String completePath) throws Exception;
  }
}
