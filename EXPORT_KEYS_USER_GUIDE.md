# Recon Container Keys Export Tool

Exports `containerId<TAB>completePath` mappings for containers in a selected
lifecycle state by reading Recon, SCM, and OM snapshot RocksDB files directly
from disk.

The tool is intentionally run with the Java main class:

```text
org.apache.hadoop.ozone.recon.cli.ReconContainerMain
```

No launcher shell script is required.

## What The Tool Reads

| Input | Recommended source | Purpose |
|---|---|---|
| `scm.db` | SCM node, from `ozone.scm.db.dirs` | Authoritative container state |
| `recon-container-key.db` | Recon node, from `ozone.recon.db.dir` | Container-to-key mapping |
| `om.snapshot.db_<timestamp>` | Recon node, from `ozone.recon.om.db.dir` | Key metadata and full path resolution |

Use `scm.db` from SCM when possible. `recon-scm.db` can be stale and may miss
state transitions such as `CLOSING -> QUASI_CLOSED -> CLOSED`.

## Prerequisites

Confirm Recon has built the container-key mapping:

```bash
grep "ContainerKeyMapperTask" /var/log/hadoop/ozone-recon.log | tail -20
```

Or check the Recon API:

```bash
curl -s "http://<recon-host>:9888/api/v1/containers?limit=1" | python3 -m json.tool
```

## Build The Tool Jar

Build from the same Ozone branch/version as the target cluster runtime.

From the repository root:

```bash
mvn package -DskipTests -DskipRecon \
  -pl hadoop-ozone/recon -am \
  -P tool-jar
```

Output jar:

```bash
hadoop-ozone/recon/target/ozone-recon-<version>-container-tool.jar
```

For Cloudera branches, use the branch-specific Maven command/settings required
by that branch. Do not mix a tool jar built from one Ozone branch with another
cluster runtime.

## Copy The Jar

Copy the built jar to the node where the DB files are available:

```bash
scp hadoop-ozone/recon/target/ozone-recon-*-container-tool.jar \
  root@<recon-host>:/root/
```

## Set Classpath And Run

On the target node, set the classpath inline with the Java command. Use
`ozone classpath ozone-recon`; do not use `ozone-manager` in the classpath
because it can introduce incompatible Ozone/HDDS classes.

```bash
CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --container-state QUASI_CLOSED --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/closed-export --shards 256 --threads 32
```

Change the jar name, DB paths, `--container-state`, and `--output-dir` as
needed for the target cluster.

To export mappings only for containers whose `usedBytes` value in the SCM
`containers` column family is negative, use `--negative-size-only`:

```bash
CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --negative-size-only --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/negative-size-export --shards 256 --threads 32
```

You can combine `--negative-size-only` with `--container-state` when both
filters are needed.

## Resume Failed Export

If a run fails partway through, rerun the same command with `--resume`:

```bash
CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --container-state QUASI_CLOSED --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/closed-export --shards 256 --threads 32 --resume
```

For a failed negative-size export, rerun the negative-size command with
`--resume`:

```bash
CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --negative-size-only --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/negative-size-export --shards 256 --threads 32 --resume
```

Use the same `--container-state`, `--negative-size-only`, `--shards`, and
`--compress` values as the original run.

## Options

| Option | Required | Default | Description |
|---|---:|---|---|
| `--container-state` | No | | Container lifecycle state to export. Required unless `--negative-size-only` is used |
| `--negative-size-only` | No | `false` | Export only containers whose SCM `containers` table `usedBytes` value is negative |
| `--scm-db` | Yes | | Path to authoritative `scm.db` |
| `--recon-db` | Yes | | Path to `recon-container-key.db` |
| `--om-snapshot-db` | Yes | | Path to `om.snapshot.db_<timestamp>` |
| `--output-dir` | No | stdout | Directory for shard files |
| `--shards` | No | `256` | Number of output shard files |
| `--threads` | No | `16` | Parallel worker threads |
| `--compress` | No | `false` | Write gzip-compressed shard files |
| `--resume` | No | `false` | Skip completed shards and retry incomplete shards |

## Output

Successful output directory:

```text
/tmp/closed-export/
  _MANIFEST
  QUASI_CLOSED-shard-000.tsv
  QUASI_CLOSED-shard-001.tsv
  ...
  QUASI_CLOSED-shard-255.tsv
  _SUCCESS
```

Each TSV line is:

```text
containerId<TAB>completePath
```

Example:

```text
12345    vol1/bucket1/dir/mykey.parquet
12346    vol2/bucket2/data/file001.orc
```

If a run fails, `_FAILED` is written and incomplete shard files keep the
`_INCOMPLETE` suffix. Re-run with `--resume` after fixing the issue.

## Test Resume And Compression

Use a separate test output directory so production exports are not disturbed.

Start a negative-size export and interrupt it with `Ctrl+C` while it is
running:

```bash
rm -rf /tmp/negative-size-export-test

CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --negative-size-only --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/negative-size-export-test --shards 256 --threads 8
```

Inspect the partial output:

```bash
ls -lh /tmp/negative-size-export-test
cat /tmp/negative-size-export-test/_MANIFEST
```

Expected partial state: `_MANIFEST` exists, `_SUCCESS` does not exist, and
there may be completed shard files plus one or more `_INCOMPLETE` files.

Resume with the same options plus `--resume`:

```bash
CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --negative-size-only --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/negative-size-export-test --shards 256 --threads 8 --resume
```

Expected final state: `_SUCCESS` exists, `_FAILED` does not exist, and no
files have the `_INCOMPLETE` suffix.

Test compressed output with a fresh directory:

```bash
rm -rf /tmp/negative-size-export-gz-test

CLASSPATH=$(ozone classpath ozone-recon):/root/ozone-recon-1.4.0.7.1.9.1003-SNAPSHOT-container-tool.jar /usr/java/default/bin/java -Xmx4g org.apache.hadoop.ozone.recon.cli.ReconContainerMain export-keys --negative-size-only --scm-db /var/lib/hadoop-ozone/recon/scm/data/recon-scm.db --recon-db /var/lib/hadoop-ozone/recon/data/recon-container-key.db_1776409065580 --om-snapshot-db /var/lib/hadoop-ozone/recon/om/data/om.snapshot.db_1777285004004 --output-dir /tmp/negative-size-export-gz-test --shards 256 --threads 8 --compress
```

Validate gzip files:

```bash
cat /tmp/negative-size-export-gz-test/_MANIFEST
gzip -t /tmp/negative-size-export-gz-test/*.tsv.gz
zcat /tmp/negative-size-export-gz-test/*.tsv.gz | head
```

Expected compressed state: shard files end in `.tsv.gz`, `_MANIFEST` has
`compress=true`, `gzip -t` exits successfully, and `zcat` prints
`containerId<TAB>completePath` rows.

To test compressed resume, interrupt the `--compress` command and resume with
the same options plus `--resume`. Resume must use the same `--container-state`,
`--negative-size-only`, `--shards`, and `--compress` values as the original
run. Changing any of these should fail with `--resume parameter mismatch`.

## Troubleshooting

| Error | Likely cause | Fix |
|---|---|---|
| `ClassNotFoundException: picocli.CommandLine` | Missing Ozone runtime classpath | Use inline `CLASSPATH=$(ozone classpath ozone-recon):/root/<tool-jar>` with the Java command |
| `NoSuchMethodError` | Tool jar built from a different Ozone branch than the cluster runtime | Rebuild the tool from the same branch/version as the cluster |
| Empty output | Recon container-key mapping is not ready or wrong DB paths were used | Check `ContainerKeyMapperTask` logs and DB paths |
| `--resume parameter mismatch` | Filter options, `--shards`, or `--compress` changed from original run | Reuse the original values |
