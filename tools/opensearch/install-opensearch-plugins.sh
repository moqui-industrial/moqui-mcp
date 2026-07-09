#!/usr/bin/env bash
set -euo pipefail

PLUGIN_BIN="${1:-/usr/share/opensearch/bin/opensearch-plugin}"

declare -a REQUIRED_PLUGINS=(
  "opensearch-knn"
)

for plugin in "${REQUIRED_PLUGINS[@]}"; do
  if "${PLUGIN_BIN}" list | grep -Fxq "${plugin}"; then
    echo "OK ${plugin}: already installed"
    continue
  fi
  echo "Installing ${plugin}"
  "${PLUGIN_BIN}" install --batch "${plugin}"
done

echo "Installed plugins:"
"${PLUGIN_BIN}" list | sort
