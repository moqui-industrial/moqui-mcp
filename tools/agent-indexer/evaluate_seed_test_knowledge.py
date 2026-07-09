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
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any


def load_module(script_path: Path):
    spec = importlib.util.spec_from_file_location("screen_prompt_eval", script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load helpers from {script_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def write_md(path: Path, title: str, payload: dict[str, Any]) -> None:
    lines = [f"# {title}", ""]
    for key, value in payload.items():
        if isinstance(value, dict):
            lines.append(f"## {key}")
            for sub_key, sub_value in value.items():
                lines.append(f"- `{sub_key}`: `{sub_value}`")
            lines.append("")
        else:
            lines.append(f"- `{key}`: `{value}`")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def filter_docs(docs: list[dict[str, Any]], kind: str | None = None) -> list[dict[str, Any]]:
    if not kind:
        return docs
    return [doc for doc in docs if doc.get("documentKind") == kind]


def filter_queries(queries: list[dict[str, Any]], kind: str | None = None) -> list[dict[str, Any]]:
    if not kind:
        return queries
    return [q for q in queries if q.get("documentKind") == kind]


def slim_result(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "mode": result.get("mode"),
        "queries": result.get("queries"),
        "recallAt1": result.get("recallAt1"),
        "recallAt3": result.get("recallAt3"),
        "recallAt5": result.get("recallAt5"),
        "groupRecallAt1": result.get("groupRecallAt1"),
        "groupRecallAt3": result.get("groupRecallAt3"),
        "groupRecallAt5": result.get("groupRecallAt5"),
        "byDocumentKind": result.get("byDocumentKind"),
        "failureClassCounts": result.get("failureClassCounts"),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description="Evaluate knowledge-only retrieval and screen anti-regression")
    ap.add_argument("--screen-docs", required=True)
    ap.add_argument("--knowledge-docs", required=True)
    ap.add_argument("--screen-queries", required=True)
    ap.add_argument("--knowledge-queries", required=True)
    ap.add_argument("--output-dir", default="output")
    ap.add_argument("--mode", default="weighted", choices=["lexical", "weighted"])
    ap.add_argument("--anti-regression-mode", default="lexical", choices=["lexical", "weighted"])
    ap.add_argument("--anti-regression-threshold", type=float, default=0.02)
    args = ap.parse_args()

    repo_root = Path(__file__).resolve().parent
    out_dir = (repo_root / args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    evalmod = load_module(repo_root / "evaluate_screen_prompt_retrieval.py")
    screen_docs = evalmod.read_jsonl(Path(args.screen_docs))
    knowledge_docs = evalmod.read_jsonl(Path(args.knowledge_docs))
    screen_queries = evalmod.read_jsonl(Path(args.screen_queries))
    knowledge_queries = evalmod.read_jsonl(Path(args.knowledge_queries))
    combined_docs = screen_docs + knowledge_docs

    outputs = [
        ("seed_scenario", "global-retrieval-seed-scenarios-summary"),
        ("test_workflow_story", "global-retrieval-test-workflows-summary"),
        ("generic_business_pattern", "global-retrieval-business-patterns-summary"),
        (None, "global-retrieval-all-knowledge-summary"),
    ]

    for kind, stem in outputs:
        docs_subset = filter_docs(knowledge_docs, kind)
        queries_subset = filter_queries(knowledge_queries, kind)
        result = evalmod.evaluate(
            docs_subset,
            queries_subset,
            mode=args.mode,
            top_k=5,
            openai_model="text-embedding-3-large",
            opensearch_url=None,
            opensearch_index=None,
            opensearch_user=None,
            opensearch_password=None,
            opensearch_knn=False,
            embedding_field="embedding",
        )
        payload = slim_result(result)
        write_json(out_dir / f"{stem}.json", payload)
        write_md(out_dir / f"{stem}.md", stem.replace("-", " ").title(), payload)

    screen_only = evalmod.evaluate(
        screen_docs,
        screen_queries,
        mode=args.anti_regression_mode,
        top_k=5,
        openai_model="text-embedding-3-large",
        opensearch_url=None,
        opensearch_index=None,
        opensearch_user=None,
        opensearch_password=None,
        opensearch_knn=False,
        embedding_field="embedding",
    )
    screen_combined = evalmod.evaluate(
        combined_docs,
        screen_queries,
        mode=args.anti_regression_mode,
        top_k=5,
        openai_model="text-embedding-3-large",
        opensearch_url=None,
        opensearch_index=None,
        opensearch_user=None,
        opensearch_password=None,
        opensearch_knn=False,
        embedding_field="embedding",
    )
    delta_r3 = round(screen_combined["recallAt3"] - screen_only["recallAt3"], 4)
    delta_gr3 = round(screen_combined["groupRecallAt3"] - screen_only["groupRecallAt3"], 4)
    anti = {
        "mode": args.anti_regression_mode,
        "screenOnlyRecallAt3": screen_only["recallAt3"],
        "screenCombinedRecallAt3": screen_combined["recallAt3"],
        "screenOnlyGroupRecallAt3": screen_only["groupRecallAt3"],
        "screenCombinedGroupRecallAt3": screen_combined["groupRecallAt3"],
        "deltaRecallAt3": delta_r3,
        "deltaGroupRecallAt3": delta_gr3,
        "threshold": args.anti_regression_threshold,
        "degradedBeyondThreshold": bool(delta_r3 < -args.anti_regression_threshold or delta_gr3 < -args.anti_regression_threshold),
    }
    write_json(out_dir / "global-retrieval-screen-vs-combined-summary.json", anti)
    write_md(out_dir / "global-retrieval-screen-vs-combined-summary.md", "Screen Vs Combined Anti Regression", anti)

    print(f"Wrote {out_dir / 'global-retrieval-seed-scenarios-summary.json'}")
    print(f"Wrote {out_dir / 'global-retrieval-test-workflows-summary.json'}")
    print(f"Wrote {out_dir / 'global-retrieval-business-patterns-summary.json'}")
    print(f"Wrote {out_dir / 'global-retrieval-all-knowledge-summary.json'}")
    print(f"Wrote {out_dir / 'global-retrieval-screen-vs-combined-summary.json'}")


if __name__ == "__main__":
    main()
