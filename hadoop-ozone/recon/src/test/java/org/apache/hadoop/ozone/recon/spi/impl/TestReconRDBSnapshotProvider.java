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

package org.apache.hadoop.ozone.recon.spi.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.ozone.OzoneConsts.HARDLINK_SEPARATOR;
import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_OM_SNAPSHOT_DB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.server.http.HttpConfig;
import org.apache.hadoop.hdds.utils.HddsServerUtil;
import org.apache.hadoop.hdds.utils.db.DBCheckpoint;
import org.apache.hadoop.hdds.utils.db.InodeMetadataRocksDBCheckpoint;
import org.apache.hadoop.ozone.om.helpers.ServiceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ReconRDBSnapshotProvider}: candidate-dir seeding from the
 * live OM DB and normalization/promotion of the assembled checkpoint.
 */
public class TestReconRDBSnapshotProvider {

  private static final Supplier<ServiceInfo> NO_LEADER = () -> null;

  private ReconRDBSnapshotProvider newProvider(File snapshotDir,
      Supplier<File> liveDbDirSupplier) {
    return new ReconRDBSnapshotProvider(snapshotDir, null, false,
        HttpConfig.Policy.HTTP_ONLY, false, NO_LEADER, liveDbDirSupplier);
  }

  private void writeFile(File dir, String name, String content)
      throws IOException {
    FileUtils.write(new File(dir, name), content, UTF_8);
  }

  @Test
  public void testSeedLinksOnlySstFiles(@TempDir File snapshotDir,
      @TempDir File liveDbDir) throws IOException {
    writeFile(liveDbDir, "000001.sst", "sst-one");
    writeFile(liveDbDir, "000002.sst", "sst-two");
    writeFile(liveDbDir, "CURRENT", "current");
    writeFile(liveDbDir, "MANIFEST-000005", "manifest");

    ReconRDBSnapshotProvider provider =
        newProvider(snapshotDir, () -> liveDbDir);
    provider.seedCandidateDir("leader-1");

    Set<String> seeded = new HashSet<>(
        Arrays.asList(provider.getCandidateDir().list()));
    assertEquals(new HashSet<>(Arrays.asList("000001.sst", "000002.sst")),
        seeded, "Only .sst files should be seeded");

    // Seeded files must be hard links (identical content) to the live DB.
    assertEquals("sst-one", FileUtils.readFileToString(
        new File(provider.getCandidateDir(), "000001.sst"), UTF_8));
  }

  @Test
  public void testSeedSkippedWhenCandidateNotEmpty(@TempDir File snapshotDir,
      @TempDir File liveDbDir) throws IOException {
    writeFile(liveDbDir, "000001.sst", "sst-one");

    ReconRDBSnapshotProvider provider =
        newProvider(snapshotDir, () -> liveDbDir);
    // Simulate a partial download already present in the candidate dir.
    writeFile(provider.getCandidateDir(), "partial.sst", "partial");

    provider.seedCandidateDir("leader-1");

    Set<String> candidate = new HashSet<>(
        Arrays.asList(provider.getCandidateDir().list()));
    assertEquals(new HashSet<>(Arrays.asList("partial.sst")), candidate,
        "Existing partial download must not be re-seeded");
  }

  @Test
  public void testSeedNoOpWhenNoLiveDb(@TempDir File snapshotDir)
      throws IOException {
    ReconRDBSnapshotProvider provider = newProvider(snapshotDir, () -> null);
    provider.seedCandidateDir("leader-1");
    assertEquals(0, provider.getCandidateDir().list().length);
  }

  @Test
  public void testGetCheckpointPromotesDbAndClearsCandidate(
      @TempDir File snapshotDir) throws IOException {
    ReconRDBSnapshotProvider provider = newProvider(snapshotDir, () -> null);
    File candidate = provider.getCandidateDir();

    // Simulate a fully untarred v2 checkpoint: flat DB files at the root plus
    // the completion sentinel (no hardLinkFile - it should be tolerated).
    writeFile(candidate, "000010.sst", "data-a");
    writeFile(candidate, "CURRENT", "current");
    writeFile(candidate, HddsServerUtil.OZONE_RATIS_SNAPSHOT_COMPLETE_FLAG_NAME,
        "");

    DBCheckpoint checkpoint =
        provider.getCheckpointFromUntarredDb(candidate.toPath());

    File promoted = checkpoint.getCheckpointLocation().toFile();
    assertTrue(promoted.getName().startsWith(RECON_OM_SNAPSHOT_DB + "_"),
        "Promoted DB should be a timestamped snapshot dir");
    assertEquals(snapshotDir, promoted.getParentFile());
    assertTrue(new File(promoted, "000010.sst").exists());
    assertTrue(new File(promoted, "CURRENT").exists());
    // The completion sentinel must not leak into the DB.
    assertFalse(new File(promoted,
        HddsServerUtil.OZONE_RATIS_SNAPSHOT_COMPLETE_FLAG_NAME).exists());
    // Candidate dir must be emptied so the next sync re-seeds cleanly.
    assertEquals(0, candidate.list().length);
  }

  @Test
  public void testGetCheckpointInstallsHardLinks(@TempDir File snapshotDir)
      throws IOException {
    ReconRDBSnapshotProvider provider = newProvider(snapshotDir, () -> null);
    File candidate = provider.getCandidateDir();

    writeFile(candidate, "000001.sst", "shared-content");
    // hardLinkFile: create 000002.sst as a hard link to 000001.sst.
    writeFile(candidate, InodeMetadataRocksDBCheckpoint.OM_HARDLINK_FILE,
        "000002.sst" + HARDLINK_SEPARATOR + "000001.sst" + "\n");

    DBCheckpoint checkpoint =
        provider.getCheckpointFromUntarredDb(candidate.toPath());

    File promoted = checkpoint.getCheckpointLocation().toFile();
    File linked = new File(promoted, "000002.sst");
    assertTrue(linked.exists(), "Hard-linked SST should be materialized");
    assertEquals("shared-content",
        FileUtils.readFileToString(linked, UTF_8));
    assertFalse(new File(promoted,
        InodeMetadataRocksDBCheckpoint.OM_HARDLINK_FILE).exists(),
        "hardLinkFile must be consumed, not left in the DB");
  }

  @Test
  public void testCandidateDirLocation(@TempDir File snapshotDir) {
    ReconRDBSnapshotProvider provider = newProvider(snapshotDir, () -> null);
    assertEquals(RECON_OM_SNAPSHOT_DB + ".candidate",
        provider.getCandidateDir().getName());
    assertEquals(snapshotDir, provider.getCandidateDir().getParentFile());
  }
}
