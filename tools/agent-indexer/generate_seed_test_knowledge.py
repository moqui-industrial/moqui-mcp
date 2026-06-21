#!/usr/bin/env python3
# This software is in the public domain under CC0 1.0 Universal plus a
# Grant of Patent License.
#
# To the extent possible under law, the author(s) have dedicated all
# copyright and related and neighboring rights to this software to the
# public domain worldwide. This software is distributed without any
# warranty.
#
# You should have received a copy of the CC0 Public Domain Dedication
# along with this software (see the LICENSE.md file). If not, see
# <https://creativecommons.org/publicdomain/zero/1.0/>.
from __future__ import annotations

import argparse
import json
import subprocess
from collections import Counter
from pathlib import Path
from typing import Any


def run(cmd: list[str], cwd: Path) -> None:
    subprocess.run(cmd, cwd=str(cwd), check=True)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    docs: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            docs.append(json.loads(line))
    return docs


def write_jsonl(path: Path, docs: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for doc in docs:
            fh.write(json.dumps(doc, ensure_ascii=True) + "\n")


def write_summary(path: Path, docs: list[dict[str, Any]]) -> None:
    by_kind = Counter(doc.get("documentKind") for doc in docs)
    by_area = Counter(doc.get("area") for doc in docs)
    lines = [
        "# Agent Knowledge Summary",
        "",
        f"- Documents: `{len(docs)}`",
        "",
        "## By Document Kind",
        "",
    ]
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_kind.most_common())
    lines.extend(["", "## By Area", ""])
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_area.most_common(25))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_combined_all(path: Path, screen_docs: list[dict[str, Any]], knowledge_docs: list[dict[str, Any]]) -> None:
    docs = list(screen_docs) + list(knowledge_docs)
    docs.sort(key=lambda d: (d.get("documentKind", ""), d.get("area", ""), d.get("documentId", "")))
    write_jsonl(path, docs)


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate seed/test knowledge documents for moqui-mcp")
    ap.add_argument("--inventory", required=True)
    ap.add_argument("--screen-docs")
    ap.add_argument("--output-dir", default="output")
    args = ap.parse_args()

    repo_root = Path(__file__).resolve().parent
    out_dir = (repo_root / args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    run([
        "python3",
        str(repo_root / "extract_seed_scenarios.py"),
        "--inventory", args.inventory,
        "--output-dir", args.output_dir,
        *([] if not args.screen_docs else ["--screen-docs", args.screen_docs]),
    ], repo_root)

    run([
        "python3",
        str(repo_root / "extract_test_workflows.py"),
        "--inventory", args.inventory,
        "--output-dir", args.output_dir,
        *([] if not args.screen_docs else ["--screen-docs", args.screen_docs]),
    ], repo_root)

    run([
        "python3",
        str(repo_root / "generate_business_patterns.py"),
        "--seed-docs", str(out_dir / "global-seed-scenario-documents.jsonl"),
        "--test-docs", str(out_dir / "global-test-workflow-documents.jsonl"),
        "--output-dir", args.output_dir,
    ], repo_root)

    docs = []
    docs.extend(load_jsonl(out_dir / "global-seed-scenario-documents.jsonl"))
    docs.extend(load_jsonl(out_dir / "global-test-workflow-documents.jsonl"))
    docs.extend(load_jsonl(out_dir / "global-business-pattern-documents.jsonl"))
    docs.sort(key=lambda d: (d.get("documentKind", ""), d.get("area", ""), d.get("documentId", "")))

    jsonl_path = out_dir / "global-agent-knowledge-documents.jsonl"
    summary_path = out_dir / "global-agent-knowledge-summary.md"
    write_jsonl(jsonl_path, docs)
    write_summary(summary_path, docs)
    if args.screen_docs:
        write_combined_all(out_dir / "global-agent-all-documents.jsonl", load_jsonl(Path(args.screen_docs)), docs)
    print(f"Wrote {jsonl_path}")
    print(f"Wrote {summary_path}")
    if args.screen_docs:
        print(f"Wrote {out_dir / 'global-agent-all-documents.jsonl'}")
    print(f"Generated {len(docs)} combined knowledge documents")


if __name__ == "__main__":
    main()
