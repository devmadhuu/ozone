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

import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.apache.hadoop.ozone.OzoneConsts.MULTIPART_FORM_DATA_BOUNDARY;
import static org.apache.hadoop.ozone.OzoneConsts.OM_DB_NAME;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_DB_CHECKPOINT_HTTP_ENDPOINT_V2;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_DB_CHECKPOINT_INCLUDE_SNAPSHOT_DATA;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_DB_CHECKPOINT_REQUEST_FLUSH;
import static org.apache.hadoop.ozone.OzoneConsts.ROCKSDB_SST_SUFFIX;
import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_OM_SNAPSHOT_DB;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.server.http.HttpConfig;
import org.apache.hadoop.hdds.utils.HAUtils;
import org.apache.hadoop.hdds.utils.RDBSnapshotProvider;
import org.apache.hadoop.hdds.utils.db.DBCheckpoint;
import org.apache.hadoop.hdds.utils.db.InodeMetadataRocksDBCheckpoint;
import org.apache.hadoop.hdds.utils.db.RocksDBCheckpoint;
import org.apache.hadoop.hdfs.web.URLConnectionFactory;
import org.apache.hadoop.ozone.om.helpers.ServiceInfo;
import org.apache.hadoop.ozone.om.ratis_snapshot.OmRatisSnapshotProvider;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.ServicePort.Type;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recon's {@link RDBSnapshotProvider} implementation that downloads the OM DB
 * checkpoint using the same incremental bootstrap mechanism an OM follower
 * uses: a chunked {@code POST /v2/dbCheckpoint} request carrying a
 * {@code toExcludeList[]} of SST files Recon already has, with hard-link dedup
 * on the leader and a completion sentinel to end the transfer.
 *
 * <p>On top of the follower behavior, this provider <b>seeds</b> the candidate
 * dir with hard links to the SST files in Recon's currently-installed OM DB
 * (the "live" DB). Those SSTs then appear in the exclude list, so a
 * full-snapshot fallback only transfers SST files that actually changed. The
 * resulting DB is always complete: RocksDB SST file numbers are content-stable
 * within a DB lineage, so a seeded {@code 000123.sst} is byte-identical to the
 * leader's, and non-SST files (CURRENT/MANIFEST/OPTIONS) are always re-sent.
 *
 * <p>Only {@code .sst} files are seeded. Non-SST files are small, are always
 * re-sent by the leader, and hard-linking the live DB's {@code LOCK} file would
 * risk a lock conflict when the assembled DB is opened.
 */
public class ReconRDBSnapshotProvider extends RDBSnapshotProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(ReconRDBSnapshotProvider.class);

  private final URLConnectionFactory connectionFactory;
  private final boolean spnegoEnabled;
  private final boolean httpsEnabled;
  private final boolean flushBeforeCheckpoint;
  private final Supplier<ServiceInfo> leaderInfoSupplier;
  private final Supplier<File> liveDbDirSupplier;

  public ReconRDBSnapshotProvider(File snapshotDir,
      URLConnectionFactory connectionFactory, boolean spnegoEnabled,
      HttpConfig.Policy httpPolicy, boolean flushBeforeCheckpoint,
      Supplier<ServiceInfo> leaderInfoSupplier,
      Supplier<File> liveDbDirSupplier) {
    super(snapshotDir, RECON_OM_SNAPSHOT_DB);
    this.connectionFactory = connectionFactory;
    this.spnegoEnabled = spnegoEnabled;
    this.httpsEnabled = httpPolicy.isHttpsEnabled();
    this.flushBeforeCheckpoint = flushBeforeCheckpoint;
    this.leaderInfoSupplier = leaderInfoSupplier;
    this.liveDbDirSupplier = liveDbDirSupplier;
  }

  /**
   * Seed the candidate dir with hard links to the live DB's SST files so they
   * are advertised in the exclude list and not re-downloaded. Only runs when
   * the candidate dir is empty (first sync, after a wipe on leader change, or
   * after a previous clean download); if a partial download is present it is
   * left untouched so it can resume.
   */
  @Override
  protected void seedCandidateDir(String leaderNodeID) throws IOException {
    File candidate = getCandidateDir();
    if (!HAUtils.getExistingFiles(candidate).isEmpty()) {
      // Partial download in progress - resume, do not re-seed.
      return;
    }
    File liveDbDir = liveDbDirSupplier.get();
    if (liveDbDir == null || !liveDbDir.exists()) {
      LOG.info("No live Recon OM DB found to seed from; a full snapshot will "
          + "be downloaded.");
      return;
    }
    File[] sstFiles = liveDbDir.listFiles(
        (dir, name) -> name.endsWith(ROCKSDB_SST_SUFFIX));
    if (sstFiles == null || sstFiles.length == 0) {
      return;
    }
    int linked = 0;
    for (File sst : sstFiles) {
      Path target = candidate.toPath().resolve(sst.getName());
      if (!Files.exists(target)) {
        Files.createLink(target, sst.toPath());
        linked++;
      }
    }
    LOG.info("Seeded {} SST files into candidate dir {} from live OM DB {} for "
        + "incremental exclusion.", linked, candidate, liveDbDir);
  }

  @Override
  public void downloadSnapshot(String leaderNodeID, File targetFile)
      throws IOException {
    ServiceInfo leader = leaderInfoSupplier.get();
    URL checkpointUrl = buildCheckpointUrl(leader);
    LOG.info("Downloading OM DB checkpoint from leader {}. Checkpoint: {}, "
        + "URL: {}", leaderNodeID, targetFile.getName(), checkpointUrl);
    SecurityUtil.doAsLoginUser(() -> {
      HttpURLConnection connection = (HttpURLConnection)
          connectionFactory.openConnection(checkpointUrl, spnegoEnabled);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type",
          "multipart/form-data; boundary=" + MULTIPART_FORM_DATA_BOUNDARY);
      connection.setDoOutput(true);

      List<String> existingFiles = HAUtils.getExistingFiles(getCandidateDir());
      OmRatisSnapshotProvider.writeFormData(connection, existingFiles);

      connection.connect();
      int errorCode = connection.getResponseCode();
      if (errorCode != HTTP_OK && errorCode != HTTP_CREATED) {
        throw new IOException("Unexpected response code " + errorCode
            + " when downloading OM DB checkpoint from " + checkpointUrl);
      }
      try (InputStream inputStream = connection.getInputStream()) {
        OmRatisSnapshotProvider.downloadFileWithProgress(inputStream,
            targetFile);
      } catch (IOException ex) {
        if (!FileUtils.deleteQuietly(targetFile)) {
          LOG.error("Failed to delete partial checkpoint file {}", targetFile);
        }
        throw ex;
      } finally {
        connection.disconnect();
      }
      return null;
    });
  }

  /**
   * After the transfer completes, install the leader's hard-link inventory,
   * normalize the layout to {@code <candidate>/om.db}, then move that DB out of
   * the reused candidate dir into a stable timestamped snapshot dir that Recon
   * opens as its new live DB. The candidate dir is emptied so the next sync
   * re-seeds from the freshly promoted DB.
   */
  @Override
  public DBCheckpoint getCheckpointFromUntarredDb(Path untarredDbDir)
      throws IOException {
    // The base class only calls this once it has seen the leader's
    // end-of-tarball marker. That marker is named "ratis snapshot complete"
    // for historical reasons, but it is not Ratis-specific: OM's shared
    // DBCheckpointServlet appends it to the end of every /v2/dbCheckpoint
    // response (the same one an OM follower bootstraps from), so here it just
    // means "the leader finished sending the checkpoint".

    // Installs hard links from hardLinkFile (tolerates a missing/empty file)
    // and moves root-level DB files into <untarredDbDir>/om.db.
    new InodeMetadataRocksDBCheckpoint(untarredDbDir, true);

    Path omDbDir = untarredDbDir.resolve(OM_DB_NAME);
    if (!Files.isDirectory(omDbDir)) {
      throw new IOException("Expected RocksDB directory not found after "
          + "assembling checkpoint: " + omDbDir);
    }

    String stableName = RECON_OM_SNAPSHOT_DB + "_" + System.currentTimeMillis();
    Path stablePath = getSnapshotDir().toPath().resolve(stableName);
    Files.move(omDbDir, stablePath);
    LOG.info("Assembled OM DB moved from {} to {}", omDbDir, stablePath);

    // Clear residual entries (completion flag, orphan seeded files, empty dirs)
    // so the candidate dir is empty for the next sync cycle.
    cleanupCandidateDir(untarredDbDir.toFile());

    return new RocksDBCheckpoint(stablePath);
  }

  private URL buildCheckpointUrl(ServiceInfo leader) throws IOException {
    Type portType = httpsEnabled ? Type.HTTPS : Type.HTTP;
    try {
      return new URIBuilder()
          .setScheme(httpsEnabled ? "https" : "http")
          .setHost(leader.getHostname())
          .setPort(leader.getPort(portType))
          .setPath(OZONE_DB_CHECKPOINT_HTTP_ENDPOINT_V2)
          // Recon does not need OM's nested snapshot data.
          .addParameter(OZONE_DB_CHECKPOINT_INCLUDE_SNAPSHOT_DATA, "false")
          .addParameter(OZONE_DB_CHECKPOINT_REQUEST_FLUSH,
              flushBeforeCheckpoint ? "true" : "false")
          .build().toURL();
    } catch (URISyntaxException | MalformedURLException e) {
      throw new IOException("Could not build OM DB checkpoint URL", e);
    }
  }

  private void cleanupCandidateDir(File candidate) {
    File[] entries = candidate.listFiles();
    if (entries == null) {
      return;
    }
    for (File entry : entries) {
      if (!FileUtils.deleteQuietly(entry)) {
        LOG.warn("Failed to clean up candidate dir entry {}", entry);
      }
    }
  }

  @Override
  public void close() {
    // The URLConnectionFactory is owned and destroyed by
    // OzoneManagerServiceProviderImpl; nothing to release here.
  }
}
