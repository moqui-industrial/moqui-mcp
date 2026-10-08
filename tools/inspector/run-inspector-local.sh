#!/usr/bin/env bash
# This software is in the public domain under CC0 1.0 Universal plus a
# Grant of Patent License. See the LICENSE.md file for details.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/moqui-mcp-inspector-local.json"

exec npx @modelcontextprotocol/inspector --config "${CONFIG_FILE}"
