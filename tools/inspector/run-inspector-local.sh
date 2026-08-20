#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/moqui-mcp-inspector-local.json"

exec npx @modelcontextprotocol/inspector --config "${CONFIG_FILE}"
