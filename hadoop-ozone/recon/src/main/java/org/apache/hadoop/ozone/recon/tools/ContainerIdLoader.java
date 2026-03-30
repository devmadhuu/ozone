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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.metadata.SCMDBDefinition;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 0: Loads container IDs matching a given lifecycle state directly from
 * a SCM-compatible DB file.  Returns a sorted {@code long[]} of container IDs.
 *
 * <p>Accepts either of the following DB files — both share the same
 * {@code containers} column family schema ({@code ContainerID → ContainerInfo}
 * with {@link HddsProtos.LifeCycleState}):
 * <ul>
 *   <li>{@code scm.db} — SCM's authoritative container store (most up-to-date
 *       state; present on the SCM node at the path configured by
 *       {@code ozone.scm.db.dirs}).</li>
 *   <li>{@code recon-scm.db} — Recon's local mirror of SCM container state
 *       (periodically synced from SCM; present on the Recon node at the path
 *       configured by {@code ozone.recon.scm.db.dir}).</li>
 * </ul>
 *
 * <p>No live SCM or Recon service is required — this is a direct offline
 * read of a RocksDB file on disk.
 *
 * <p>Design: Phase 0 is needed because {@code containerKeyTable} in Recon's
 * RocksDB is keyed by {@code (containerId, keyPrefix, keyVersion)}.  Knowing
 * the IDs up front lets Phase 1 use direct O(log n) seeks instead of a full
 * table scan.
 */
public final class ContainerIdLoader {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerIdLoader.class);

  private ContainerIdLoader() {
    // utility class
  }

  /**
   * Scans the {@code containers} column family of the supplied DB store and
   * returns a sorted array of all container IDs whose lifecycle state matches
   * {@code state}.
   *
   * <p>The DB store must be backed by either {@code scm.db} or
   * {@code recon-scm.db} — both contain the containers table defined in
   * {@link SCMDBDefinition#CONTAINERS}.
   *
   * <p>No network call is made; this is a pure offline file read.
   *
   * @param scmDbStore open SCM-compatible DB store (read-only; caller owns
   *                   lifecycle)
   * @param state      the desired {@link HddsProtos.LifeCycleState}
   * @return sorted {@code long[]} of matching container IDs
   * @throws IOException if the DB read fails
   */
  public static long[] load(DBStore scmDbStore,
      HddsProtos.LifeCycleState state) throws IOException {

    Table<ContainerID, ContainerInfo> containerTable =
        SCMDBDefinition.CONTAINERS.getTable(scmDbStore);

    List<Long> ids = new ArrayList<>();

    try (TableIterator<ContainerID,
        ? extends Table.KeyValue<ContainerID, ContainerInfo>> iter =
            containerTable.iterator()) {

      iter.seekToFirst();
      while (iter.hasNext()) {
        Table.KeyValue<ContainerID, ContainerInfo> kv = iter.next();
        if (kv.getValue().getState() == state) {
          ids.add(kv.getKey().getId());
        }
      }
    }

    LOG.info("Phase 0 complete: {} container IDs in state {} found in DB",
        ids.size(), state);

    long[] arr = new long[ids.size()];
    for (int i = 0; i < ids.size(); i++) {
      arr[i] = ids.get(i);
    }
    Arrays.sort(arr);
    return arr;
  }
}
