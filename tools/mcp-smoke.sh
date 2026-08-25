#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${MCP_BASE_URL:-http://localhost:8081/mcp}"
PROTO_VERSION="${MCP_PROTOCOL_VERSION:-2026-07-28}"
ENTITY_URI="${MCP_TEST_ENTITY_URI:-entity://mantle.party.Person}"
PROMPT_NAME="${MCP_TEST_PROMPT_NAME:-}"

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

assert_resource_entity_name() {
  local file="$1"
  local expected="$2"
  python3 - "$file" "$expected" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], 'r', encoding='utf-8'))
contents = payload.get('result', {}).get('contents', [])
if not contents:
    raise SystemExit('Missing contents array')
doc_text = contents[0].get('text')
if not doc_text:
    raise SystemExit('Missing contents[0].text')
doc = json.loads(doc_text)
if doc.get('entityName') != sys.argv[2]:
    raise SystemExit(f"Unexpected entityName: {doc.get('entityName')!r}")
PY
}

assert_prompt_complete() {
  local file="$1"
  local expected_desc="$2"
  python3 - "$file" "$expected_desc" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], 'r', encoding='utf-8'))
result = payload.get('result', {})
if result.get('resultType') != 'complete':
    raise SystemExit(f"Unexpected resultType: {result.get('resultType')!r}")
if result.get('description') != sys.argv[2]:
    raise SystemExit(f"Unexpected description: {result.get('description')!r}")
messages = result.get('messages') or []
if not messages:
    raise SystemExit('Missing messages array')
content = messages[0].get('content') or {}
if content.get('type') != 'text':
    raise SystemExit(f"Unexpected content.type: {content.get('type')!r}")
if not content.get('text'):
    raise SystemExit('Missing prompt text')
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
assert_contains "${TMP_DIR}/tools.json" '"moqui_search_data_documents"'
assert_contains "${TMP_DIR}/tools.json" '"moqui_call_service"'

echo "3. resources/list"
curl_json "resources/list" \
  '{"jsonrpc":"2.0","id":3,"method":"resources/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/resources.json"
assert_contains "${TMP_DIR}/resources.json" '"resources"'

echo "4. resources/read"
curl_json "resources/read" \
  "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"${ENTITY_URI}\",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}" \
  -H "Mcp-Name: ${ENTITY_URI}" \
  > "${TMP_DIR}/resource.json"
assert_resource_entity_name "${TMP_DIR}/resource.json" "${ENTITY_URI#entity://}"

echo "5. prompts/list"
curl_json "prompts/list" \
  '{"jsonrpc":"2.0","id":5,"method":"prompts/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/prompts.json"
assert_contains "${TMP_DIR}/prompts.json" '"prompts"'

if [[ -n "${PROMPT_NAME}" ]]; then
  echo "6. prompts/get (${PROMPT_NAME})"
  curl_json "prompts/get" \
    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/get\",\"params\":{\"name\":\"${PROMPT_NAME}\",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}" \
    > "${TMP_DIR}/prompt.json"
  assert_prompt_complete "${TMP_DIR}/prompt.json" "${PROMPT_NAME}"
fi

echo "7. negative mismatch"
curl_json "tools/list" \
  '{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  > "${TMP_DIR}/mismatch.json"
assert_contains "${TMP_DIR}/mismatch.json" '"code":-32020'

echo "Smoke test passed."
