#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# recon-container-tool.sh
#
# Launcher for the Recon Container Keys Export CLI tool.
#
# Copy this script and ozone-recon-container-tool-<version>.jar to any node
# that has a working Ozone installation (the script auto-discovers OZONE_HOME).
# The Recon service does NOT need to be running.
#
# Usage:
#   ./recon-container-tool.sh [export-keys options...]
#
# No running services are required — all three DB files are read directly
# from disk.  Pass either scm.db (SCM node) or recon-scm.db (Recon node)
# via --scm-db.
#
# Example (all Recon-node files, fully offline):
#   ./recon-container-tool.sh \
#       --container-state QUASI_CLOSED \
#       --scm-db         /data/metadata/recon/recon-scm.db \
#       --recon-db       /data/metadata/recon/recon-container-key.db \
#       --om-snapshot-db /data/metadata/recon/om.snapshot.db \
#       --output-dir     /tmp/quasi-closed-export \
#       --threads        16 \
#       --compress
# ---------------------------------------------------------------------------
set -euo pipefail

# ---------------------------------------------------------------------------
# 1. Locate the tool jar (same directory as this script)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find the tool jar; accept versioned filename.
TOOL_JAR=""
for f in "${SCRIPT_DIR}"/ozone-recon-container-tool-*.jar; do
  if [ -f "$f" ]; then
    TOOL_JAR="$f"
    break
  fi
done

if [ -z "${TOOL_JAR}" ]; then
  echo "ERROR: Tool jar not found in ${SCRIPT_DIR}." >&2
  echo "       Expected: ozone-recon-container-tool-<version>.jar" >&2
  echo "       Build it with:" >&2
  echo "         mvn package -DskipTests -DskipRecon -pl hadoop-ozone/recon -am -P tool-jar" >&2
  exit 1
fi

echo "Using tool jar: ${TOOL_JAR}"

# ---------------------------------------------------------------------------
# 2. Verify 'ozone' command is available (needed for classpath assembly)
# ---------------------------------------------------------------------------
if ! command -v ozone &>/dev/null; then
  echo "ERROR: 'ozone' command not found on PATH." >&2
  echo "       Add the Ozone bin directory to PATH, e.g.:" >&2
  echo "         export PATH=\$PATH:/opt/ozone/bin" >&2
  exit 1
fi

OZONE_HOME="$(cd "$(dirname "$(command -v ozone)")/.." && pwd)"
echo "Using Ozone installation: ${OZONE_HOME}"

# ---------------------------------------------------------------------------
# 3. Build classpath using 'ozone classpath'
# ---------------------------------------------------------------------------
# 'ozone classpath ozone-recon' prints the full classpath of all jars that
# ozone-recon depends on (Hadoop, RocksDB JNI, Protobuf, Picocli, etc.).
# This is the canonical way to assemble the classpath in an Ozone cluster.
OZONE_CLASSPATH="$(ozone classpath ozone-recon)"
if [ -z "${OZONE_CLASSPATH}" ]; then
  echo "ERROR: 'ozone classpath ozone-recon' returned empty output." >&2
  exit 1
fi

# Append the tool jar.
CP="${OZONE_CLASSPATH}:${TOOL_JAR}"

# ---------------------------------------------------------------------------
# 4. Ozone / Hadoop configuration directory
# ---------------------------------------------------------------------------
OZONE_CONF_DIR="${OZONE_CONF_DIR:-${OZONE_HOME}/etc/hadoop}"

# ---------------------------------------------------------------------------
# 5. JVM options
# ---------------------------------------------------------------------------
# Heap: 2 GB is sufficient for most exports.
# For very large exports (billions of keys) with --unique-keys, increase to 8G.
JAVA_HEAP="${RECON_TOOL_HEAP:-2g}"

# Log4j config — use Ozone's existing log4j2 config if present.
LOG4J_CONFIG=""
for f in \
    "${OZONE_CONF_DIR}/log4j2.properties" \
    "${OZONE_HOME}/etc/hadoop/log4j2.properties"; do
  if [ -f "$f" ]; then
    LOG4J_CONFIG="-Dlog4j.configurationFile=${f}"
    break
  fi
done

# ---------------------------------------------------------------------------
# 6. Run
# ---------------------------------------------------------------------------
# Use the cluster Java binary if not overridden.
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
JAVA="${JAVA:-/usr/java/default/bin/java}"

exec "${JAVA}" \
  -Xmx"${JAVA_HEAP}" \
  ${LOG4J_CONFIG} \
  -Dhadoop.conf.dir="${OZONE_CONF_DIR}" \
  -cp "${CP}" \
  org.apache.hadoop.ozone.recon.cli.ReconContainerMain \
  export-keys \
  "$@"
