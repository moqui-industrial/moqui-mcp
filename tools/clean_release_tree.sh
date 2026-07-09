#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
component_dir="$(cd "${script_dir}/.." && pwd)"

find "${component_dir}" -type d \( -name "__pycache__" -o -name ".pytest_cache" -o -name "output" -o -name "build" -o -name "bin" -o -name "lib" \) -prune -exec rm -rf {} +
find "${component_dir}" -type f \( -name "*.pyc" -o -name "*.pyo" \) -delete
