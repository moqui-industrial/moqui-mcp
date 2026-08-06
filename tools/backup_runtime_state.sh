#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${1:-/home/igor/development/projects/moqui/tests/ai}"
MOQUI_DIR="${ROOT_DIR}/moqui-framework"
BACKUP_ROOT="${ROOT_DIR}/backups"
STAMP="$(date '+%Y%m%d-%H%M%S')"
DEST_DIR="${BACKUP_ROOT}/${STAMP}"

mkdir -p "${DEST_DIR}"

H2_JAR="${MOQUI_DIR}/execwartmp/ROOT/webapp/WEB-INF/lib/h2-2.4.240.jar"
H2_URL="jdbc:h2:tcp://127.0.1.1:9092/moqui"

echo "Creating backup in ${DEST_DIR}"

java -cp "${H2_JAR}" org.h2.tools.Script \
  -url "${H2_URL}" \
  -user sa \
  -password sa \
  -script "${DEST_DIR}/moqui-h2-script.sql"

tar --warning=no-file-changed -czf "${DEST_DIR}/moqui-h2-files.tgz" -C "${MOQUI_DIR}/runtime/db/h2" . || true

curl -fsS http://127.0.0.1:9200/_cat/indices?v > "${DEST_DIR}/opensearch-indices.txt"
curl -fsS http://127.0.0.1:9200/_cluster/health?pretty > "${DEST_DIR}/opensearch-cluster-health.json"
tar -czf "${DEST_DIR}/opensearch-runtime.tgz" -C "${MOQUI_DIR}/runtime" opensearch

cat > "${DEST_DIR}/README.txt" <<EOF
Backup created at: ${STAMP}
Root directory: ${ROOT_DIR}

Contents:
- moqui-h2-script.sql          Consistent H2 SQL export
- moqui-h2-files.tgz           Raw H2 database files
- opensearch-indices.txt       OpenSearch index inventory
- opensearch-cluster-health.json Cluster health snapshot
- opensearch-runtime.tgz       OpenSearch runtime/config/data copy
EOF

echo "Backup completed: ${DEST_DIR}"
