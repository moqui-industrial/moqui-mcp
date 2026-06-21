#!/usr/bin/env bash
set -euo pipefail

opensearch_url="${OPENSEARCH_URL:-http://127.0.0.1:9200}"

echo "== _cat/plugins =="
curl -sS "${opensearch_url}/_cat/plugins?v"

echo
echo "== _nodes/plugins =="
curl -sS "${opensearch_url}/_nodes/plugins?pretty"
