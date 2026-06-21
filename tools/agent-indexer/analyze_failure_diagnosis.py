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
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> dict[str, dict[str, Any]]:
    docs = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            doc = json.loads(line)
            docs[doc["documentId"]] = doc
    return docs


def classify_failure(failure: dict[str, Any]) -> tuple[bool, str, str]:
    failure_class = failure.get("failureClass")
    if failure_class == "sibling_document_ok":
        return True, "sibling equivalence group expansion", "Returned a sibling in the same operational family; runtime usefulness is likely acceptable."
    if failure_class == "ambiguous_by_design":
        return True, "evaluation target correction", "Query is underspecified or generic and likely has multiple valid answers."
    if failure_class == "group_hit_outside_top3":
        return False, "service-action complement boost", "Correct group is present but not ranked into top3."
    if failure_class == "domain_object_confusion":
        return False, "domainObject metadata correction", "Intent is close, but the ranking drifts to the wrong business object."
    if failure_class == "action_kind_confusion":
        return False, "action policy mismatch", "Returned documents stay on the same domain but choose the wrong action family."
    if failure_class == "true_miss":
        return False, "true retrieval miss", "Target group is absent from the top results."
    return False, "manual review", "Unexpected failure class; needs manual inspection."


def main() -> None:
    ap = argparse.ArgumentParser(description="Produce failure diagnosis artifacts from evaluation reports")
    ap.add_argument("--failure-report", required=True)
    ap.add_argument("--docs", required=True)
    ap.add_argument("--mode", default="hybrid_rerank")
    ap.add_argument("--out-json", required=True)
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()

    failures_by_mode = json.loads(Path(args.failure_report).read_text(encoding="utf-8"))
    failures = failures_by_mode[args.mode]
    docs_by_id = load_jsonl(Path(args.docs))

    summary: dict[str, Any] = {
        "mode": args.mode,
        "topFailureCount": len(failures),
        "operationallyAcceptableCount": 0,
        "operationallyAcceptableRate": 0.0,
        "byFailureClass": {},
        "bySuggestedFixCategory": {},
        "rows": [],
    }

    for index, failure in enumerate(failures, start=1):
        acceptable, fix_category, rationale = classify_failure(failure)
        target_doc = docs_by_id.get(failure["targetDocumentId"], {})
        top3 = failure.get("topRetrieved", [])[:3]
        top3_groups = [docs_by_id.get(doc_id, {}).get("promptGroupId") for doc_id in top3]
        row = {
            "rank": index,
            "query": failure.get("query"),
            "targetDocumentId": failure.get("targetDocumentId"),
            "targetGroupId": target_doc.get("promptGroupId"),
            "targetDocumentKind": failure.get("targetDocumentKind"),
            "targetOperationEffect": failure.get("targetOperationEffect"),
            "targetActionKind": failure.get("targetActionKind"),
            "targetDomainObject": failure.get("targetDomainObject"),
            "top3Returned": top3,
            "top3ReturnedGroups": top3_groups,
            "failureClass": failure.get("failureClass"),
            "operationallyAcceptable": acceptable,
            "suggestedFixCategory": fix_category,
            "rationale": rationale,
        }
        summary["rows"].append(row)
        if acceptable:
            summary["operationallyAcceptableCount"] += 1
        summary["byFailureClass"][failure.get("failureClass")] = summary["byFailureClass"].get(failure.get("failureClass"), 0) + 1
        summary["bySuggestedFixCategory"][fix_category] = summary["bySuggestedFixCategory"].get(fix_category, 0) + 1

    summary["operationallyAcceptableRate"] = round(summary["operationallyAcceptableCount"] / max(len(failures), 1), 4)

    out_json = Path(args.out_json)
    out_md = Path(args.out_md)
    out_json.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [
        "# Failure Diagnosis Top50",
        "",
        f"- Mode: `{args.mode}`",
        f"- Top failures analyzed: `{len(failures)}`",
        f"- Operationally acceptable: `{summary['operationallyAcceptableCount']}` ({summary['operationallyAcceptableRate']:.2%})",
        "",
        "## Failure Class Counts",
    ]
    for key, value in sorted(summary["byFailureClass"].items()):
        lines.append(f"- `{key}`: {value}")
    lines.extend(["", "## Suggested Fix Categories"])
    for key, value in sorted(summary["bySuggestedFixCategory"].items()):
        lines.append(f"- `{key}`: {value}")
    lines.extend(["", "## Top50 Detail"])
    for row in summary["rows"]:
        lines.append(f"### {row['rank']}. {row['query']}")
        lines.append(f"- failureClass: `{row['failureClass']}`")
        lines.append(f"- target: `{row['targetDocumentId']}`")
        lines.append(f"- targetGroup: `{row['targetGroupId']}`")
        lines.append(f"- top3Returned: `{row['top3Returned']}`")
        lines.append(f"- top3ReturnedGroups: `{row['top3ReturnedGroups']}`")
        lines.append(f"- operationallyAcceptable: `{row['operationallyAcceptable']}`")
        lines.append(f"- suggestedFixCategory: `{row['suggestedFixCategory']}`")
        lines.append(f"- rationale: {row['rationale']}")
        lines.append("")
    out_md.write_text("\n".join(lines), encoding="utf-8")

    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")


if __name__ == "__main__":
    main()
