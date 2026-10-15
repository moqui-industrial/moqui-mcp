#!/usr/bin/env bash
# This software is in the public domain under CC0 1.0 Universal plus a
# Grant of Patent License. See the LICENSE.md file for details.

set -euo pipefail

BASE_URL="${MCP_BASE_URL:-http://localhost:8081/mcp}"
PROTO_VERSION="${MCP_PROTOCOL_VERSION:-2026-07-28}"
SCREEN_QUERY="${MCP_TEST_SCREEN_QUERY:-find product}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

auth_args=()
extra_headers=()

if [[ -n "${MCP_BASIC_USER:-}" || -n "${MCP_BASIC_PASSWORD:-}" ]]; then
  : "${MCP_BASIC_USER:?Set MCP_BASIC_USER when using basic auth}"
  : "${MCP_BASIC_PASSWORD:?Set MCP_BASIC_PASSWORD when using basic auth}"
  auth_args=(-u "${MCP_BASIC_USER}:${MCP_BASIC_PASSWORD}")
elif [[ -n "${MCP_LOGIN_KEY:-}" ]]; then
  extra_headers+=(-H "api_key: ${MCP_LOGIN_KEY}")
fi

curl_json() {
  local method="$1"
  local body="$2"
  shift 2
  curl -sS "${auth_args[@]}" -X POST "${BASE_URL}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "MCP-Protocol-Version: ${PROTO_VERSION}" \
    -H "Mcp-Method: ${method}" \
    "${extra_headers[@]}" \
    "$@" \
    --data "${body}"
}

assert_contains() {
  local file="$1"
  local needle="$2"
  if ! grep -Fq "$needle" "$file"; then
    echo "Expected to find: $needle" >&2
    echo "Actual response:" >&2
    cat "$file" >&2
    exit 1
  fi
}

assert_tool_complete() {
  local file="$1"
  python3 - "$file" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], 'r', encoding='utf-8'))
result = payload.get('result', {})
if result.get('resultType') != 'complete':
    raise SystemExit(f"Unexpected resultType: {result.get('resultType')!r}")
if result.get('isError'):
    raise SystemExit(f"Tool error: {result.get('content')!r}")
PY
}

echo "1. server/discover"
curl_json "server/discover" \
  '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/discover.json"
assert_contains "${TMP_DIR}/discover.json" '"supportedVersions"'
assert_contains "${TMP_DIR}/discover.json" '"2026-07-28"'

echo "2. tools/list"
curl_json "tools/list" \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/tools.json"
assert_contains "${TMP_DIR}/tools.json" '"browse"'
assert_contains "${TMP_DIR}/tools.json" '"run_service"'

echo "3. tools/call browse (${SCREEN_QUERY})"
curl_json "tools/call" \
  "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"browse\",\"arguments\":{\"q\":\"${SCREEN_QUERY}\"},\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}" \
  -H "Mcp-Name: browse" \
  > "${TMP_DIR}/browse.json"
assert_tool_complete "${TMP_DIR}/browse.json"

echo "4. resources are not offered"
curl_json "resources/list" \
  '{"jsonrpc":"2.0","id":4,"method":"resources/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/resources.json"
assert_contains "${TMP_DIR}/resources.json" '"code":-32601'

echo "5. negative mismatch"
curl_json "tools/list" \
  '{"jsonrpc":"2.0","id":5,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/mismatch.json"
assert_contains "${TMP_DIR}/mismatch.json" '"code":-32020'

echo "Smoke test passed."
