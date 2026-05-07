# Recon Container Keys Export — Design & Usage Guide

## Overview

This document describes the design and usage of the **Container Keys Export**
CLI tool added to Apache Ozone Recon.  It provides a scalable, high-throughput
offline mechanism for retrieving all keys belonging to containers in a given
lifecycle state (e.g. `QUASI_CLOSED`, `CLOSED`), supporting clusters with
millions of containers and billions of keys.

```
ozone recon-container export-keys \
    --container-state QUASI_CLOSED \
    --recon-db     /data/recon/recon-container-key.db \
    --om-snapshot-db /data/recon/om.snapshot.db
```

---

## Problem Statement

The existing REST API (`GET /api/v1/containers/<id>/keys`) retrieves keys for a
single container over JSON/HTTP.  For a cluster with ~4 million `QUASI_CLOSED`
containers and potentially billions of keys:

- **Network overhead** — one HTTP round-trip per container × 4M = prohibitively slow.
- **JSON serialisation** — full `KeyMetadata` objects (block locations, versions,
  timestamps, data size, …) serialised for every key.
- **Sequential access** — no parallelism across containers; only one container at a time.
- **Pagination overhead** — containers with many keys require multiple HTTP calls
  each (default page size 1 000).

The CLI tool bypasses HTTP entirely, reads Recon's RocksDB and the OM snapshot DB
directly, and writes compact TSV output.

---

## Architecture

### Data Flow

```
scm.db or recon-scm.db    Recon RocksDB              OM Snapshot DB
    │  (local file read)         │                           │
    │ Phase 0: scan containers   │                           │
    │ table by LifeCycleState    │                           │
    │ ──────────────────────── ►│                           │
    │  sorted long[] of IDs      │                           │
    │                           │                           │
    │                           │ Phase 1: seek per ID      │
    │                           │ into containerKeyTable    │
    │                           │ ─── OmKeyInfo lookup ────►│
    │                           │ ◄── OmKeyInfo (proto) ─── │
    │                           │     NSSummary walk        │
    │                           │     (cached per parentId) │
    ▼                           ▼                           ▼
                    Output (TSV to stdout or shard files)
                    containerId <TAB> completePath
```

### Phase 0 — Load Container IDs from a Local DB File

`ContainerIdLoader` performs a **single sequential scan** of the `containers`
column family in a SCM-compatible DB file passed via `--scm-db`.  Two files
are accepted — both share the identical `ContainerID → ContainerInfo` schema
(defined in `SCMDBDefinition.CONTAINERS`):

| File | Location | State freshness |
|---|---|---|
| `scm.db` | SCM node — path in `ozone.scm.db.dirs` | Authoritative (live SCM state) |
| `recon-scm.db` | Recon node — path in `ozone.recon.scm.db.dir` | Slightly stale (last Recon sync) |

**No live SCM or Recon service is required** — this is a direct offline read
of a RocksDB file on disk.  The tool is now fully self-contained: copy the
three DB files to any machine and run.

- Source: `recon/.../tools/ContainerIdLoader.java`

### Phase 1 — Seek-per-ID into `containerKeyTable`

`ContainerKeyTableSeekReader` exploits the fact that Recon's
`containerKeyTable` is **keyed by `(containerId, keyPrefix, keyVersion)`**.
For each container ID:

1. **Seek** directly to the first entry for that container — `O(log N)` in
   the total number of entries, independent of position.
2. **Forward scan** until `containerId` changes — `O(keys in container)`.

This avoids a full table scan entirely.

- Source: `recon/.../tools/ContainerKeyTableSeekReader.java`

#### Key Path Resolution (always on)

Path resolution behaviour matches the existing REST endpoint exactly:

| Bucket layout | Stored `keyPrefix` | Resolution method |
|---|---|---|
| OBS / LEGACY | `/vol/bucket/keyName` (full OM row key) | Direct from `OmKeyInfo.{volumeName, bucketName, keyName}` — **no NSSummary lookup** |
| FSO | `<volId>/<bucketId>/<parentId>/<fileName>` (opaque numeric IDs) | `OmKeyInfo` protobuf parse + NSSummary chain walk from leaf to root |

**FSO path resolution — NSSummary walk cost and how it is amortised:**

`ReconUtils.constructFullPathPrefix` walks the NSSummary chain once per unique
`parentObjectId`.  Without caching, a directory with 10 000 files would trigger
10 000 identical walks.  `ContainerKeyTableSeekReader` holds a per-instance
`parentObjectId → "vol/bucket/dir1/.../dirN/"` cache:

- **Cache hit** (same parent seen before): O(1) HashMap lookup — zero DB reads.
- **Cache miss** (new parent): one NSSummary chain walk, result stored.
- **OBS / LEGACY keys** (`parentObjectId == 0`): path assembled from
  `OmKeyInfo` fields directly — never enters the cache.
- **Missing NSSummary** (rebuild in progress): empty sentinel `""` stored so
  sibling keys do not each retry the walk; entry skipped, matching REST
  endpoint behaviour.

In the single-threaded stdout path the same reader instance is reused across
all containers, maximising cache hits.  In the multi-threaded path each worker
thread owns its own reader, keeping the cache effective within its container
subset.

#### Key Skipping Rules (same as REST endpoint)

A key is silently skipped when:

1. No `OmKeyInfo` found in either `keyTable` or `fileTable` — key was deleted
   from OM after the last Recon sync.
2. `buildCompletePath` returns `""` — NSSummary rebuild is in progress for
   this key's parent directory.
3. The entry is a later **version** of the same key within the same container
   (same `keyPrefix`, higher `keyVersion`) — only the first version is emitted,
   matching the REST endpoint's deduplication.

---

## Performance Edge Over the REST API

### Qualitative Comparison

| Factor | REST API (`/api/v1/containers/{id}/keys`) | CLI tool |
|---|---|---|
| HTTP round-trips | 1+ per container (+ pagination calls) | **0** |
| JSON serialisation | Full `KeyMetadata` per key | **TSV only** — `containerId\tcompletePath` |
| Path resolution | `ReconUtils.constructFullPath` (no cross-key caching) | Same logic + **per-reader `parentObjectId` cache** |
| Parallelism | None (sequential, one container per request) | **Multi-threaded** (`--threads`) across containers |
| Pagination | Multiple HTTP calls for large containers | Single forward scan per container |
| Live server load | Runs against live Recon; competes with other requests | **Reads offline snapshot** — zero Recon server load |
| Global deduplication | Not provided | Not applied — every `(containerId, completePath)` pair is emitted |

### Quantitative Estimate for 4M Containers

Assume 4M `QUASI_CLOSED` containers, average 250 keys each (1B keys total),
Ratis replication factor 3:

| Step | REST API | CLI tool |
|---|---|---|
| Enumerate containers | 4M HTTP calls @ ~5 ms each → **~5.5 hours** | Single sequential scan of `recon-scm.db` → **seconds, no RPC** |
| Key retrieval I/O | 4M HTTP calls × multiple pages → billions of HTTP bytes | 1B RocksDB key lookups + 1B NSSummary cache accesses (mostly hits) |
| Serialisation | JSON `KeyMetadata` per key: ~500 B × 1B = **~500 GB** of JSON | TSV `id\tpath` per key: ~80 B × 1B = **~80 GB** of TSV |
| Parallelism | 1 thread, sequential | 32 threads × seek-per-ID |

Even in the worst case (all FSO keys, cold NSSummary cache, single thread) the
CLI avoids the HTTP stack entirely, producing output 5–10× faster than the REST
approach for a large-scale export.

### Measured Performance — Real Cluster Benchmark

The following numbers were captured on a production-like cluster running
CDH-7.3.2.x with Ratis-3 replication using default hardware (no special
tuning).

**Command:**
```bash
/usr/java/default/bin/java -Xmx4g \
    org.apache.hadoop.ozone.recon.cli.ReconContainerMain \
    export-keys \
    --container-state CLOSED \
    --scm-db         scm.db \
    --recon-db       recon-container-key.db \
    --om-snapshot-db om.snapshot.db_1771955223969 \
    --output-dir     /tmp/closed-export \
    --shards         256 \
    --threads        32
```

**Cluster profile:**

| Metric | Value |
|---|---|
| Containers in state CLOSED | **118,453** |
| Total `(containerId, key)` pairs emitted | **3,715,562** |
| Average keys per container | ~31.4 |
| JVM heap | `-Xmx4g` |
| Threads / Shards | 32 / 256 |

**Timing breakdown:**

| Phase | Elapsed |
|---|---|
| Phase 0 — scan `scm.db` for CLOSED container IDs | **19.0 sec** |
| Wait for background DB opens (recon-db + om-snapshot-db in parallel) | **16.5 sec** |
| Phase 1 — seek + resolve + write all 256 shards | **6 min 42 sec** |
| **Total wall-clock time** | **7 min 18 sec** |

> **Note on DB open wait**: Both `recon-container-key.db` and `om.snapshot.db`
> were opened concurrently in background threads during Phase 0.  The 16.5 sec
> wait means Phase 0 (19 sec) slightly exceeded the DB open time — so
> effectively the DB opens added ~zero incremental delay beyond Phase 0 itself.
> On faster storage the two phases may overlap entirely (zero additional wait).

**Output:**

| Metric | Value |
|---|---|
| Total output size | **167 MB** (uncompressed TSV) |
| Number of shard files written | 256 |
| Empty shards | 0 |
| Failed shards | 0 |
| Average shard size | ~652 KB |
| Typical shard size range | 400 KB – 560 KB |
| Outlier shards (hot containers) | 8 shards at 4.4–5.0 MB each (`shard-001` to `shard-008`, `shard-233`, `shard-234`) |

**Throughput:**

| Metric | Value |
|---|---|
| Pairs per second (Phase 1 only) | **~9,200 pairs/sec** (3.7M ÷ 402 sec) |
| MB per second output | ~0.41 MB/sec |
| Containers per second (Phase 0) | ~6,200 containers/sec |

**Key observations:**
- The shard size distribution is uneven — a small number of shards (≤5%)
  were 8–9× larger than average, reflecting containers whose IDs happen to
  hash to the same shard bucket and which individually hold many more keys.
  This is expected behaviour; `--shards 256` with `--threads 32` ensures these
  heavy shards do not block others.
- Using `--compress` on a cluster of this size would reduce the 167 MB output
  to approximately 40–55 MB (typical compression ratio 3–4× for path-heavy
  TSV data).
- Scaling projection: at the same ~9,200 pairs/sec throughput rate, a cluster
  with 1M containers and 30M keys would complete Phase 1 in approximately
  **54 minutes**.  For 10M containers / 300M keys: approximately **9 hours**
  — at which point increasing `--threads` beyond 32 and/or running on faster
  NVMe storage would be the primary levers.

### Seek-per-ID vs Full Table Scan

| Approach | Time complexity | When to prefer |
|---|---|---|
| Full table scan | `O(total entries in containerKeyTable)` | Fetching keys for **all** containers |
| **Seek-per-ID (this implementation)** | `O(M × log N + K)` where M = matching containers, N = total containers, K = total matching keys | Fetching keys for a **subset** of containers (e.g. just `QUASI_CLOSED`) |

For 4M `QUASI_CLOSED` out of 20M total containers: each seek is
`O(log 20M) ≈ 24` RocksDB comparisons vs scanning all entries across all 20M
containers.

---

## CLI Tool

### Installation

The command is registered as `ozone recon-container` via a dedicated entry in
the `ozone` shell script (`OZONE_RUN_ARTIFACT_NAME=ozone-recon`), so the full
Recon RocksDB library is on the classpath.  No additional setup is required
after building.

### Synopsis

```
ozone recon-container export-keys \
    --container-state <STATE> \
    --recon-db       <PATH> \
    --scm-db         <PATH> \
    --om-snapshot-db <PATH> \
   [--output-dir     <DIR>] \
   [--shards         <N>] \
   [--threads        <N>] \
   [--compress]
```

### Options

| Option | Required | Default | Description |
|---|---|---|---|
| `--container-state` | Yes | — | Lifecycle state: `OPEN`, `CLOSING`, `QUASI_CLOSED`, `CLOSED`, `DELETING`, `DELETED` |
| `--recon-db` | Yes | — | Full path to Recon's container-key DB directory (e.g. `/var/data/recon/recon-container-key.db`) |
| `--scm-db` | Yes | — | Full path to `scm.db` **or** `recon-scm.db`. Both contain the `containers` table. No live SCM service required — direct offline file read |
| `--om-snapshot-db` | Yes | — | Full path to the OM snapshot DB directory (e.g. `/var/data/recon/om.snapshot.db`). Required for path resolution |
| `--output-dir` | No | stdout | Directory for shard output files.  If omitted, writes to stdout (single-threaded) |
| `--shards` | No | `256` | Number of output shard files (ignored when writing to stdout) |
| `--threads` | No | `16` | Worker thread count for parallel seeks |
| `--compress` | No | `false` | Gzip-compress shard files (adds `.gz` suffix) |

**Removed options (compared to earlier design iterations):**

| Removed option | Reason |
|---|---|
| `--resolve-paths` | Path resolution is now always on — output always matches REST API `CompletePath` |
| `--unique-keys` | Removed — the tool emits every `(containerId, completePath)` pair; post-process with `sort -u -k2` if you need unique paths only |
| `--output-format` | Output is always TSV (`containerId<TAB>completePath`) |

### Output Format

```
containerId<TAB>completePath
12345	vol1/bucket1/dir/mykey
12345	vol1/bucket1/dir/otherkey
12346	vol2/bucket2/data.parquet
```

Every `(containerId, completePath)` pair is emitted.  With Ratis replication
factor 3, the same key's blocks exist in three replica containers, so its path
will appear **three times** — once per replica container.  This gives the
complete picture of which containers hold each key.

If you need only unique key paths (ignoring which containers they belong to),
post-process with `sort -u -k2` on the TSV output.

When `--output-dir` is used, shard files are created as:
```
<STATE>-shard-000.tsv[.gz]
<STATE>-shard-001.tsv[.gz]
...
_SUCCESS                      ← written only after all shards complete without error
```

The number of shards created is `min(--shards, actual container count)` — no
empty files are written.

### Examples

**Export all QUASI_CLOSED container keys to stdout (using scm.db):**
```bash
ozone recon-container export-keys \
    --container-state QUASI_CLOSED \
    --scm-db         /data/metadata/scm/current/scm.db \
    --recon-db       /data/recon/recon-container-key.db \
    --om-snapshot-db /data/recon/om.snapshot.db
```

**Same export using recon-scm.db (if scm.db is not directly accessible):**
```bash
ozone recon-container export-keys \
    --container-state QUASI_CLOSED \
    --scm-db         /data/recon/recon-scm.db \
    --recon-db       /data/recon/recon-container-key.db \
    --om-snapshot-db /data/recon/om.snapshot.db
```

**Export to sharded compressed files with 32 parallel threads:**
```bash
ozone recon-container export-keys \
    --container-state QUASI_CLOSED \
    --scm-db         /data/metadata/scm/current/scm.db \
    --recon-db       /data/recon/recon-container-key.db \
    --om-snapshot-db /data/recon/om.snapshot.db \
    --output-dir     /tmp/quasi-closed-export \
    --shards         512 \
    --threads        32 \
    --compress
```

**Count keys per container, find the top 20 largest:**
```bash
ozone recon-container export-keys \
    --container-state QUASI_CLOSED \
    --scm-db         /data/recon/recon-scm.db \
    --recon-db       /data/recon/recon-container-key.db \
    --om-snapshot-db /data/recon/om.snapshot.db \
    | awk -F'\t' '{count[$1]++} END {for (id in count) print id, count[id]}' \
    | sort -k2 -rn | head -20
```

---

## Recon Bug Fix — Duplicate Keys in `containerKeyTable`

A related bug was fixed in `ContainerKeyMapperHelper` where key overwrites
(`PUT` events) and renames (`UPDATE` events) did not remove stale
`(containerId, keyPrefix)` entries before adding new ones.  Over time this
caused duplicate or ghost entries in `containerKeyTable`.

**Fix**: `handleDeleteOMKeyEvent` is now always called before
`handlePutOMKeyEvent` for both `PUT` and `UPDATE` events.  For `UPDATE` events
where `oldValue` is null the current key name is used as a fallback to ensure
stale container entries are cleaned up.

---

## Source Files

| File | Purpose |
|---|---|
| `recon/.../tools/ContainerIdLoader.java` | Phase 0: offline scan of `containers` table in `scm.db` or `recon-scm.db` → sorted `long[]` |
| `recon/.../tools/ContainerKeyTableSeekReader.java` | Phase 1: seek-per-ID scanner, always-on path resolution with `parentObjectId` cache |
| `recon/.../cli/ExportKeysSubcommand.java` | Picocli CLI command, sharded file output, multi-threaded producer/consumer, global dedup |
| `recon/.../cli/ReconContainerAdmin.java` | Parent picocli command group `recon-container` |
| `recon/.../cli/ReconContainerMain.java` | `main()` entry point; bootstraps picocli on `ReconContainerAdmin` |
| `dist/src/shell/ozone/ozone` | Shell script: adds `recon-container` subcommand using `ozone-recon` classpath |
| `recon/.../tasks/ContainerKeyMapperHelper.java` | Bug fix: stale entry cleanup on PUT/UPDATE events |
