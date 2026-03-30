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

package org.apache.hadoop.ozone.recon.cli;

import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_CONTAINER_KEY_DB;
import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_OM_SNAPSHOT_DB;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.hadoop.hdds.cli.AbstractSubcommand;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.metadata.SCMDBDefinition;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.DBStoreBuilder;
import org.apache.hadoop.ozone.om.codec.OMDBDefinition;
import org.apache.hadoop.ozone.recon.spi.ReconNamespaceSummaryManager;
import org.apache.hadoop.ozone.recon.spi.impl.ReconDBDefinition;
import org.apache.hadoop.ozone.recon.tools.ContainerIdLoader;
import org.apache.hadoop.ozone.recon.tools.ContainerKeyTableSeekReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI subcommand: {@code ozone recon-container export-keys}.
 *
 * <p>Exports a {@code containerId → completePath} mapping for all containers
 * matching a given lifecycle state, reading Recon RocksDB and the OM snapshot
 * DB directly — bypassing the REST API for maximum throughput while producing
 * output identical to what {@code /api/v1/containers/{id}/keys} would return
 * for each container.
 *
 * <h3>Key properties</h3>
 * <ul>
 *   <li>Every {@code (containerId, completePath)} pair is emitted.  With
 *       RATIS-3 replication the same key's blocks exist in three containers,
 *       so its path will appear three times — once per replica container.
 *       This gives the full picture of which containers hold each key.</li>
 *   <li>Within a single container, duplicate <em>versions</em> of the same
 *       key (same {@code keyPrefix}, incremented {@code keyVersion}) are
 *       suppressed — only the first version entry is written.</li>
 *   <li>Keys deleted from OM since the last Recon sync and keys whose
 *       NSSummary path cannot be resolved are silently skipped — matching the
 *       REST endpoint behaviour.</li>
 *   <li>Output format: TSV {@code containerId<TAB>completePath}, one line per
 *       {@code (containerId, key)} pair.</li>
 * </ul>
 *
 * <h3>Threading model (shard-per-writer)</h3>
 * <p>Container IDs are partitioned into {@code --shards} buckets by
 * {@code containerId % shards}.  A fixed thread pool of {@code --threads}
 * workers processes one shard at a time; each worker owns its shard file
 * exclusively — zero cross-shard lock contention and sequential I/O per file.
 *
 * <p>Each shard is written to a temporary file named
 * {@code <STATE>-shard-NNN<suffix>_INCOMPLETE}.  On successful flush and
 * close the file is atomically renamed to its final name
 * {@code <STATE>-shard-NNN<suffix>}.  If a worker fails the temporary file
 * is left with the {@code _INCOMPLETE} suffix so callers can distinguish
 * partial from complete shards.  A directory-level {@code _SUCCESS} or
 * {@code _FAILED} marker is written after all workers finish.
 *
 * <h3>Resuming a failed export</h3>
 * <p>Pass {@code --resume} to skip shards that already completed (final file
 * present) and re-process only the incomplete ones.  The first run writes a
 * {@code _MANIFEST} file recording the key parameters; {@code --resume}
 * verifies the parameters match before proceeding.
 *
 * <h3>Phases</h3>
 * <ol>
 *   <li><b>Phase 0</b> – Load container IDs by scanning the
 *       {@code containers} table of a SCM-compatible DB file ({@code scm.db}
 *       or {@code recon-scm.db}) passed via {@code --scm-db}.
 *       No live SCM service required ({@link ContainerIdLoader}).</li>
 *   <li><b>Phase 1</b> – Seek per container ID into Recon's
 *       {@code containerKeyTable}, resolve each key to its full path, and
 *       emit results ({@link ContainerKeyTableSeekReader}).</li>
 * </ol>
 */
@Command(
    name = "export-keys",
    description = "Export containerId → completePath for all containers "
        + "matching a lifecycle state.  Output mirrors /api/v1/containers/{id}/keys: "
        + "paths are fully resolved and each unique key is emitted exactly once.",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class ExportKeysSubcommand extends AbstractSubcommand
    implements Callable<Void> {

  private static final Logger LOG =
      LoggerFactory.getLogger(ExportKeysSubcommand.class);

  private static final String MANIFEST_FILE = "_MANIFEST";

  // -------------------------------------------------------------------------
  // CLI options
  // -------------------------------------------------------------------------

  @Option(names = {"--container-state"},
      required = true,
      description = "Container lifecycle state: "
          + "OPEN, CLOSING, QUASI_CLOSED, CLOSED, DELETING, DELETED")
  private HddsProtos.LifeCycleState containerState;

  @Option(names = {"--recon-db"},
      required = true,
      description = "Full path to Recon's container-key DB directory "
          + "(e.g. /var/data/recon/" + RECON_CONTAINER_KEY_DB + ").")
  private String reconDb;

  @Option(names = {"--scm-db"},
      required = true,
      description = "Full path to a SCM-compatible DB directory containing "
          + "the 'containers' table. Accepts either: "
          + "(1) SCM's own scm.db (e.g. /data/metadata/scm/current/scm.db) — "
          + "most up-to-date state; "
          + "(2) Recon's recon-scm.db mirror "
          + "(e.g. /data/metadata/recon/recon-scm.db) — "
          + "slightly stale but avoids copying from the SCM node. "
          + "No live SCM service is required — this is a direct offline read.")
  private String scmDb;

  @Option(names = {"--om-snapshot-db"},
      required = true,
      description = "Full path to the OM snapshot DB directory inside Recon's "
          + "data dir (e.g. /var/data/recon/" + RECON_OM_SNAPSHOT_DB + "). "
          + "Required for full-path resolution.")
  private String omSnapshotDb;

  @Option(names = {"--output-dir"},
      description = "Directory where shard files are written. "
          + "Omit to write to stdout.")
  private String outputDir;

  @Option(names = {"--shards"},
      defaultValue = "256",
      description = "Number of output shard files (default: 256). "
          + "Ignored when writing to stdout.")
  private int shards;

  @Option(names = {"--threads"},
      defaultValue = "16",
      description = "Worker threads for parallel shard processing (default: 16). "
          + "Each thread processes one shard at a time exclusively.")
  private int threads;

  @Option(names = {"--compress"},
      defaultValue = "false",
      description = "Gzip-compress shard files (adds .gz suffix).")
  private boolean compress;

  @Option(names = {"--resume"},
      defaultValue = "false",
      description = "Resume a previously failed export. "
          + "Shards whose final file already exists are skipped; "
          + "shards with an _INCOMPLETE file are deleted and re-processed. "
          + "Requires a _MANIFEST file written by the original run. "
          + "Use the same --shards and --container-state values as the original run.")
  private boolean resume;

  // -------------------------------------------------------------------------
  // Entry point
  // -------------------------------------------------------------------------

  @Override
  public Void call() throws Exception {
    long startMs = System.currentTimeMillis();
    OzoneConfiguration conf = getOzoneConf();

    // getAbsoluteFile() is required: DBStoreBuilder calls getParentFile().toPath()
    // which throws NullPointerException when the File was constructed from a
    // relative path (no parent component), e.g. new File("scm.db").getParentFile()
    // returns null.  getAbsoluteFile() resolves against the working directory so
    // "scm.db" becomes "/root/scm.db" and getParentFile() returns "/root".
    File reconDbFile = new File(reconDb).getAbsoluteFile();
    if (!reconDbFile.exists()) {
      throw new IOException("Recon DB not found: " + reconDbFile);
    }
    File scmDbFile = new File(scmDb).getAbsoluteFile();
    if (!scmDbFile.exists()) {
      throw new IOException("SCM DB not found: " + scmDbFile
          + " — provide either scm.db or recon-scm.db path via --scm-db");
    }
    File omSnapshotDbFile = new File(omSnapshotDb).getAbsoluteFile();
    if (!omSnapshotDbFile.exists()) {
      throw new IOException("OM snapshot DB not found: " + omSnapshotDbFile);
    }

    // Opening recon-container-key.db and om.snapshot.db is expensive (each can
    // take 20-40+ seconds on large clusters).  Start both opens in background
    // threads immediately so their latency overlaps with Phase 0 (scm.db scan).
    System.err.println("[export-keys] Opening recon and OM snapshot databases "
        + "in background (parallel with Phase 0)...");
    ExecutorService dbOpenPool = Executors.newFixedThreadPool(2);
    Future<DBStore> reconFuture = dbOpenPool.submit(
        () -> openReconDb(conf, reconDbFile));
    Future<DBStore> omFuture = dbOpenPool.submit(
        () -> openOmSnapshotDb(conf, omSnapshotDbFile));
    dbOpenPool.shutdown();

    // Phase 0: scan scm.db for target container IDs while the two large DBs
    // are opening concurrently in the background.
    long[] containerIds;
    DBStore scmDbStore = openScmDb(conf, scmDbFile);
    try {
      containerIds = ContainerIdLoader.load(scmDbStore, containerState);
    } finally {
      closeQuietly(scmDbStore);
    }
    LOG.info("Phase 0 done: {} containers in state {} (read from {})",
        containerIds.length, containerState, scmDbFile.getName());
    System.err.printf("[export-keys] Phase 0: %d containers in state %s "
        + "loaded from %s (%.1f sec)%n",
        containerIds.length, containerState, scmDbFile.getName(),
        (System.currentTimeMillis() - startMs) / 1000.0);

    if (containerIds.length == 0) {
      reconFuture.cancel(false);
      omFuture.cancel(false);
      System.err.println("[export-keys] No containers found in state "
          + containerState + ".");
      System.err.println("[export-keys] Hints:");
      System.err.println("  1. Verify the live count:  "
          + "ozone admin container list --state " + containerState);
      System.err.println("  2. If you passed --scm-db scm.db, try "
          + "--scm-db recon-scm.db (Recon's own mirror) or vice versa.");
      System.err.println("  3. Check whether the DB file is a recent copy — "
          + "stale files may not reflect current container states.");
      System.err.printf("[export-keys] Total elapsed: %s%n",
          formatElapsed(System.currentTimeMillis() - startMs));
      return null;
    }

    // Wait for background DB opens. If Phase 0 took longer than the DB opens,
    // these get() calls return immediately (zero additional wait).
    long dbWaitStart = System.currentTimeMillis();
    DBStore reconDbStore;
    DBStore omSnapshotDbStore;
    try {
      reconDbStore = reconFuture.get();
    } catch (ExecutionException e) {
      omFuture.cancel(false);
      throw new IOException("Failed to open recon DB: "
          + e.getCause().getMessage(), e.getCause());
    }
    try {
      omSnapshotDbStore = omFuture.get();
    } catch (ExecutionException e) {
      closeQuietly(reconDbStore);
      throw new IOException("Failed to open OM snapshot DB: "
          + e.getCause().getMessage(), e.getCause());
    }
    long dbWaitMs = System.currentTimeMillis() - dbWaitStart;
    if (dbWaitMs > 500) {
      System.err.printf("[export-keys] DB opens finished (waited %.1f sec "
          + "after Phase 0 — open them earlier or increase Phase 0 overlap)%n",
          dbWaitMs / 1000.0);
    } else {
      System.err.println("[export-keys] DB opens completed during Phase 0 "
          + "(zero additional wait).");
    }

    try {
      ReconNamespaceSummaryManager nsSummaryMgr =
          buildNsSummaryManager(reconDbStore);
      runExport(containerIds, reconDbStore, omSnapshotDbStore, nsSummaryMgr);
    } finally {
      closeQuietly(omSnapshotDbStore);
      closeQuietly(reconDbStore);
    }
    System.err.printf("[export-keys] Total elapsed: %s%n",
        formatElapsed(System.currentTimeMillis() - startMs));
    return null;
  }

  // -------------------------------------------------------------------------
  // DB helpers
  // -------------------------------------------------------------------------

  private DBStore openScmDb(OzoneConfiguration conf, File dbFile)
      throws Exception {
    // SCMDBDefinition knows the 'containers' column family.
    // Works for both scm.db and recon-scm.db (which extends SCMDBDefinition).
    return DBStoreBuilder.newBuilder(conf, SCMDBDefinition.get(), dbFile)
        .setOpenReadOnly(true).build();
  }

  private DBStore openReconDb(OzoneConfiguration conf, File dbFile)
      throws Exception {
    ReconDBDefinition def = new ReconDBDefinition(dbFile.getName());
    return DBStoreBuilder.newBuilder(conf, def, dbFile)
        .setOpenReadOnly(true).build();
  }

  private DBStore openOmSnapshotDb(OzoneConfiguration conf, File dbFile)
      throws Exception {
    return DBStoreBuilder.newBuilder(conf, OMDBDefinition.get(), dbFile)
        .setOpenReadOnly(true).build();
  }

  private ReconNamespaceSummaryManager buildNsSummaryManager(
      DBStore reconDbStore) throws IOException {
    return new DirectNSSummaryManager(reconDbStore);
  }

  // -------------------------------------------------------------------------
  // Export logic
  // -------------------------------------------------------------------------

  private void runExport(long[] containerIds,
      DBStore reconDbStore,
      DBStore omSnapshotDbStore,
      ReconNamespaceSummaryManager nsSummaryMgr) throws Exception {

    long phase1StartMs = System.currentTimeMillis();
    boolean toStdout = (outputDir == null);

    if (toStdout) {
      // Single-threaded path writing to stdout.
      ContainerKeyTableSeekReader reader = new ContainerKeyTableSeekReader(
          reconDbStore, omSnapshotDbStore, nsSummaryMgr);
      try (PrintWriter pw = new PrintWriter(
          new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
        reader.scan(containerIds, (cid, completePath) ->
            writeLine(pw, cid, completePath));
        pw.flush();
      } finally {
        reader.close();
      }
      return;
    }

    // Multi-threaded sharded path.
    File outDir = new File(outputDir);
    if (!outDir.exists() && !outDir.mkdirs()) {
      throw new IOException("Cannot create output directory: " + outputDir);
    }

    // Cap shards to the number of containers.
    int numShards = Math.min(Math.max(1, shards), containerIds.length);

    if (resume) {
      // Short-circuit: if _SUCCESS already exists the export is fully done.
      if (new File(outDir, "_SUCCESS").exists()) {
        LOG.info("Output directory already contains _SUCCESS — export is "
            + "complete. Delete _SUCCESS to force a fresh re-run.");
        return;
      }
      // Guard: require a _MANIFEST written by the original run so we can
      // detect parameter mismatches (wrong --shards, wrong --container-state).
      if (!new File(outDir, MANIFEST_FILE).exists()) {
        throw new IOException("--resume requires a " + MANIFEST_FILE
            + " file in " + outDir + " but none was found. "
            + "Re-run without --resume to start a fresh export.");
      }
      verifyManifest(outDir, numShards, containerIds.length);
      // Clear the _FAILED marker; a new one will be written only if this
      // attempt also fails.
      new File(outDir, "_FAILED").delete();
      LOG.info("Resuming export in {}: skipping completed shards, "
          + "re-processing incomplete ones", outDir);
    } else {
      // Fresh run: remove any shard files and markers left by a previous run
      // so stale 0-byte or partial files from that run do not linger.
      cleanOutputDir(outDir);
      // Write the manifest so a future --resume can verify params.
      writeManifest(outDir, numShards, containerIds.length);
    }

    // Partition container IDs into per-shard buckets by containerId % numShards.
    // This assignment is deterministic and matches the shard file naming, so
    // callers can locate the shard for any given containerId without scanning all files.
    @SuppressWarnings("unchecked")
    List<Long>[] buckets = new ArrayList[numShards];
    for (int i = 0; i < numShards; i++) {
      buckets[i] = new ArrayList<>();
    }
    for (long cid : containerIds) {
      buckets[(int) (Math.abs(cid) % numShards)].add(cid);
    }

    // Shard-per-writer: each task owns one shard file exclusively.
    // The thread pool (size = --threads) processes shards in parallel;
    // shards beyond the pool size are queued and picked up as threads become free.
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<ShardResult>> futures = new ArrayList<>();
    int skippedShards = 0;

    for (int s = 0; s < numShards; s++) {
      if (buckets[s].isEmpty()) {
        continue;
      }
      File done = shardFinalFile(outDir, s);
      File tmp = shardTmpFile(outDir, s);

      if (resume && done.exists()) {
        // This shard completed successfully in a prior run — skip it.
        skippedShards++;
        continue;
      }

      if (resume && tmp.exists()) {
        // Stale _INCOMPLETE from the prior failed run — delete before retrying
        // so we don't append to a partially-written file.
        if (!tmp.delete()) {
          LOG.warn("Could not delete stale incomplete file: {}", tmp);
        }
      }

      final int shardIdx = s;
      final long[] shardIds = toLongArray(buckets[s]);

      futures.add(pool.submit(() -> {
        File shardTmp = shardTmpFile(outDir, shardIdx);
        File shardDone = shardFinalFile(outDir, shardIdx);
        long count = 0L;
        try {
          ContainerKeyTableSeekReader reader = new ContainerKeyTableSeekReader(
              reconDbStore, omSnapshotDbStore, nsSummaryMgr);
          try {
            java.io.OutputStream os = new FileOutputStream(shardTmp);
            if (compress) {
              os = new GZIPOutputStream(os);
            }
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(os, StandardCharsets.UTF_8)))) {
              final long[] c = {0L};
              reader.scan(shardIds, (cid, path) -> {
                writeLine(pw, cid, path);
                c[0]++;
              });
              pw.flush();
              count = c[0];
            }
          } finally {
            reader.close();
          }
          if (count > 0) {
            // Atomic rename: _INCOMPLETE → final name, only on success.
            // Both files are in the same directory so the rename stays on-filesystem.
            Files.move(shardTmp.toPath(), shardDone.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
          } else {
            // No keys found for any container in this shard partition.
            // This is normal when containers exist in SCM but Recon has not
            // yet received/processed container reports for them, or when
            // the containers are genuinely empty.  Delete the tmp file so
            // we don't litter the output directory with 0-byte files.
            shardTmp.delete();
          }
          return new ShardResult(shardIdx, count, null);
        } catch (Exception e) {
          LOG.error("Shard {} failed; {} left with _INCOMPLETE suffix",
              shardIdx, shardTmp.getName(), e);
          return new ShardResult(shardIdx, count, e);
        }
      }));
    }

    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);

    long totalWritten = 0L;
    int failedShards = 0;
    int emptyShards = 0;
    for (Future<ShardResult> f : futures) {
      ShardResult r = f.get();
      totalWritten += r.count;
      if (r.error != null) {
        failedShards++;
      } else if (r.count == 0) {
        emptyShards++;
      }
    }

    if (failedShards == 0) {
      new File(outDir, "_SUCCESS").createNewFile();
      LOG.info("Export complete: {} (containerId, key) pairs written across "
          + "{} shards ({} skipped as already complete, {} empty) to {}",
          totalWritten, futures.size(), skippedShards, emptyShards, outputDir);
      System.err.printf("[export-keys] SUCCESS: %d (containerId, key) pairs "
          + "written across %d shards "
          + "(%d skipped/already-complete, %d empty/no-keys-in-recon) "
          + "in %s.%n",
          totalWritten, futures.size(), skippedShards, emptyShards,
          formatElapsed(System.currentTimeMillis() - phase1StartMs));
      if (emptyShards > 0) {
        System.err.printf("[export-keys] %d shard(s) had containers but no "
            + "keys in the recon-container-key.db — Recon may not have synced "
            + "those container reports yet, or those containers are empty.%n",
            emptyShards);
      }
    } else {
      new File(outDir, "_FAILED").createNewFile();
      LOG.error("Export incomplete: {}/{} shards failed "
          + "(incomplete files retain _INCOMPLETE suffix; "
          + "successfully written shards are usable as-is). "
          + "Re-run with --resume to retry only the failed shards.",
          failedShards, futures.size() + skippedShards);
      System.err.printf("[export-keys] FAILED: %d/%d shards failed "
          + "after %s. Re-run with --resume to retry only the failed shards.%n",
          failedShards, futures.size() + skippedShards,
          formatElapsed(System.currentTimeMillis() - phase1StartMs));
    }
  }

  // -------------------------------------------------------------------------
  // Manifest helpers
  // -------------------------------------------------------------------------

  /**
   * Removes shard files and status markers left by a previous run from
   * {@code outDir}, so that a fresh run always starts with a clean slate.
   * Only files matching the shard naming pattern or known marker names are
   * removed; unrelated files in the directory are left untouched.
   * The {@code _MANIFEST} file is intentionally excluded — it is overwritten
   * by {@link #writeManifest} immediately after this call.
   */
  private void cleanOutputDir(File outDir) {
    String[] toDelete = outDir.list((dir, name) ->
        name.equals("_SUCCESS") || name.equals("_FAILED")
            || name.matches(".+-shard-\\d{3}.*\\.tsv(\\.gz)?(_INCOMPLETE)?"));
    if (toDelete != null && toDelete.length > 0) {
      for (String name : toDelete) {
        new File(outDir, name).delete();
      }
      LOG.info("Cleaned {} file(s) from previous run in {}", toDelete.length, outDir);
      System.err.printf("[export-keys] Cleaned %d file(s) from previous run "
          + "in %s%n", toDelete.length, outDir);
    }
  }

  /**
   * Writes a {@code _MANIFEST} file recording the key export parameters.
   * A subsequent {@code --resume} run reads this file to verify it is
   * resuming a compatible export before skipping completed shards.
   */
  private void writeManifest(File outDir, int numShards,
      int containerCount) throws IOException {
    Properties props = new Properties();
    props.setProperty("container-state", containerState.name());
    props.setProperty("shards", String.valueOf(numShards));
    props.setProperty("container-count", String.valueOf(containerCount));
    props.setProperty("written-at", Instant.now().toString());
    try (FileWriter fw = new FileWriter(new File(outDir, MANIFEST_FILE))) {
      props.store(fw, "export-keys manifest — verified on --resume");
    }
  }

  /**
   * Reads the existing {@code _MANIFEST} and verifies that the current
   * invocation's parameters are compatible with the original run.
   * Throws {@link IOException} on a hard mismatch ({@code container-state}
   * or {@code shards}); logs a warning for a changed {@code container-count}
   * since containers may have been added or removed between runs.
   */
  private void verifyManifest(File outDir, int numShards,
      int containerCount) throws IOException {
    Properties props = new Properties();
    try (FileReader fr = new FileReader(new File(outDir, MANIFEST_FILE))) {
      props.load(fr);
    }
    String mState = props.getProperty("container-state", "");
    if (!containerState.name().equals(mState)) {
      throw new IOException("--resume parameter mismatch: _MANIFEST has "
          + "container-state=" + mState + " but current run uses "
          + containerState.name() + ". Use the same --container-state to resume.");
    }
    String mShards = props.getProperty("shards", "");
    if (!String.valueOf(numShards).equals(mShards)) {
      throw new IOException("--resume parameter mismatch: _MANIFEST has "
          + "shards=" + mShards + " but current run uses " + numShards
          + ". Use the same --shards value to resume.");
    }
    String mCount = props.getProperty("container-count", "0");
    int prevCount = Integer.parseInt(mCount);
    if (prevCount != containerCount) {
      LOG.warn("Container count changed since original run: was {} now {}. "
          + "Resumed shards will reflect the current container set; "
          + "already-completed shards will not be re-written.",
          prevCount, containerCount);
    }
    LOG.info("Manifest verified: container-state={} shards={} original-written-at={}",
        mState, mShards, props.getProperty("written-at", "unknown"));
  }

  // -------------------------------------------------------------------------
  // Shard file path helpers
  // -------------------------------------------------------------------------

  /**
   * Returns the temporary (in-progress) path for a shard.
   * The {@code _INCOMPLETE} suffix signals that the file has not been fully
   * flushed and closed.  It is atomically renamed to {@link #shardFinalFile}
   * on success, or left as-is on failure.
   */
  private File shardTmpFile(File outDir, int shardIdx) {
    String suffix = compress ? ".tsv.gz" : ".tsv";
    return new File(outDir, String.format("%s-shard-%03d%s_INCOMPLETE",
        containerState.name(), shardIdx, suffix));
  }

  /** Returns the final (successfully completed) path for a shard. */
  private File shardFinalFile(File outDir, int shardIdx) {
    String suffix = compress ? ".tsv.gz" : ".tsv";
    return new File(outDir, String.format("%s-shard-%03d%s",
        containerState.name(), shardIdx, suffix));
  }

  /** Converts a {@code List<Long>} to a primitive {@code long[]}. */
  private static long[] toLongArray(List<Long> list) {
    long[] arr = new long[list.size()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = list.get(i);
    }
    return arr;
  }

  /** Formats a millisecond duration as {@code "Xs"} or {@code "Xm Ys"}. */
  private static String formatElapsed(long ms) {
    long totalSec = ms / 1000;
    if (totalSec < 60) {
      return totalSec + " sec";
    }
    return String.format("%d min %d sec", totalSec / 60, totalSec % 60);
  }

  /** Writes one TSV line: {@code containerId<TAB>completePath}. */
  private static void writeLine(PrintWriter pw, long cid, String completePath) {
    pw.printf("%d\t%s%n", cid, completePath);
  }

  private static void closeQuietly(AutoCloseable c) {
    if (c != null) {
      try {
        c.close();
      } catch (Exception e) {
        LOG.warn("Error closing resource", e);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  /** Result returned by each shard writer task. */
  private static final class ShardResult {
    final int shardIdx;
    final long count;
    final Exception error;

    ShardResult(int shardIdx, long count, Exception error) {
      this.shardIdx = shardIdx;
      this.count = count;
      this.error = error;
    }
  }

  /**
   * Minimal read-only {@link ReconNamespaceSummaryManager} backed by an
   * already-open {@link DBStore}.  Only {@link #getNSSummary(long)} is
   * implemented; all write methods throw {@link UnsupportedOperationException}.
   */
  private static final class DirectNSSummaryManager
      implements ReconNamespaceSummaryManager {

    private final org.apache.hadoop.hdds.utils.db.Table<Long,
        org.apache.hadoop.ozone.recon.api.types.NSSummary> nsSummaryTable;

    DirectNSSummaryManager(DBStore reconDbStore) throws IOException {
      this.nsSummaryTable = reconDbStore.getTable(
          ReconDBDefinition.NAMESPACE_SUMMARY.getName(),
          ReconDBDefinition.NAMESPACE_SUMMARY.getKeyCodec(),
          ReconDBDefinition.NAMESPACE_SUMMARY.getValueCodec());
    }

    @Override
    public org.apache.hadoop.ozone.recon.api.types.NSSummary
        getNSSummary(long objectId) throws IOException {
      return nsSummaryTable.getSkipCache(objectId);
    }

    @Override
    public ReconNamespaceSummaryManager getStagedNsSummaryManager(
        DBStore dbStore) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void reinitialize(
        org.apache.hadoop.ozone.recon.spi.impl.ReconDBProvider p) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void clearNSSummaryTable() {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void storeNSSummary(long objectId,
        org.apache.hadoop.ozone.recon.api.types.NSSummary nsSummary) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void batchStoreNSSummaries(
        org.apache.hadoop.hdds.utils.db.BatchOperation batch,
        long objectId,
        org.apache.hadoop.ozone.recon.api.types.NSSummary nsSummary) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void batchDeleteNSSummaries(
        org.apache.hadoop.hdds.utils.db.BatchOperation batch,
        long objectId) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void deleteNSSummary(long objectId) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }

    @Override
    public void commitBatchOperation(
        org.apache.hadoop.hdds.utils.db.RDBBatchOperation rdbBatchOperation) {
      throw new UnsupportedOperationException("read-only CLI manager");
    }
  }
}
