#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
component_dir="$(cd "${script_dir}/.." && pwd)"

violations=()

while IFS= read -r path; do
    violations+=("${path}")
done < <(find "${component_dir}" \
    \( -type d \( -name "__pycache__" -o -name ".pytest_cache" -o -name "output" -o -name "build" -o -name "bin" -o -name "lib" \) \) \
    -o \( -type f \( -name "*.pyc" -o -name "*.pyo" \) \))

if ((${#violations[@]} > 0)); then
    echo "Release tree check failed. Forbidden generated artifacts found:" >&2
    printf ' - %s\n' "${violations[@]}" >&2
    exit 1
fi

echo "Release tree check passed: no forbidden generated artifacts found."
