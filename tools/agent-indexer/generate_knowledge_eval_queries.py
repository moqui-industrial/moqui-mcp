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
import re
from collections import Counter
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    if not path.exists():
        return docs
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            docs.append(json.loads(line))
    return docs


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=True) + "\n")


def humanize_token(text: str | None) -> str:
    raw = (text or "").strip()
    if not raw:
        return ""
    raw = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", raw)
    raw = raw.replace("_", " ").replace("-", " ")
    return " ".join(raw.split())


def top_entities(doc: dict[str, Any], limit: int = 3) -> list[str]:
    entities = []
    for entity in doc.get("relatedEntities") or []:
        label = humanize_token(entity)
        if label and label not in entities:
            entities.append(label)
        if len(entities) >= limit:
            break
    return entities


def normalized_name(text: str | None) -> str:
    return humanize_token(text).lower()


def source_stem_tokens(doc: dict[str, Any], limit: int = 4) -> list[str]:
    source = doc.get("sourceFile") or ""
    stem = Path(source).stem
    tokens = [t.lower() for t in re.split(r"[^A-Za-z0-9]+", humanize_token(stem)) if t]
    generic = {"data", "demo", "install", "seed", "initial", "test", "tests", "screen", "service"}
    out: list[str] = []
    for token in tokens:
        if len(token) < 3 or token in generic:
            continue
        if token not in out:
            out.append(token)
        if len(out) >= limit:
            break
    return out


def scenario_query_specificity(doc: dict[str, Any], domain_counts: Counter[str]) -> bool:
    domain = normalized_name(doc.get("domainObject"))
    if not domain:
        return False
    return domain_counts.get(domain, 0) > 1


def scenario_queries(doc: dict[str, Any], domain_counts: Counter[str]) -> list[tuple[str, str]]:
    domain = humanize_token(doc.get("domainObject"))
    area = humanize_token(doc.get("area"))
    scenario = doc.get("scenarioName") or ""
    category = doc.get("knowledgeCategory") or "business_configuration"
    entities = top_entities(doc)
    source_tokens = source_stem_tokens(doc)
    needs_specificity = scenario_query_specificity(doc, domain_counts)
    rows: list[tuple[str, str]] = []
    rows.append((f"understand {scenario}", "title_lookup"))
    rows.append((f"{scenario} {domain}".strip(), "scenario_lookup"))
    if source_tokens:
        rows.append((f"{scenario} {' '.join(source_tokens[:2])} {domain}".strip(), "source_specific_lookup"))
    if area and area != domain:
        rows.append((f"{area} {domain} configuration in {scenario}".strip(), "area_domain_lookup"))
    if category == "technical_configuration":
        if not needs_specificity:
            rows.append((f"{domain} technical configuration", "technical_configuration_lookup"))
        if scenario:
            rows.append((f"{scenario} technical setup for {domain}".strip(), "technical_setup_lookup"))
    elif category == "reference_data":
        rows.append((f"{scenario} {domain} reference data".strip(), "reference_data_lookup"))
        rows.append((f"{domain} master data in {scenario}".strip(), "reference_master_data_lookup"))
        if source_tokens:
            rows.append((f"{' '.join(source_tokens[:2])} {domain} reference data".strip(), "reference_specific_lookup"))
    else:
        rows.append((f"how to configure {domain} in {scenario}".strip(), "business_configuration_lookup"))
        if scenario:
            rows.append((f"{scenario} business setup for {domain}".strip(), "business_setup_lookup"))
        if source_tokens:
            rows.append((f"{' '.join(source_tokens[:2])} setup for {domain}".strip(), "business_source_specific_lookup"))
    if entities:
        rows.append((f"{scenario} {domain} with {' and '.join(entities[:2])}".strip(), "entity_cluster_lookup"))
        rows.append((f"which data is related to {domain} in {scenario}".strip(), "related_data_lookup"))
    if not needs_specificity and category == "business_configuration":
        rows.append((f"{domain} configuration in Moqui", "generic_business_lookup"))
    return rows


def workflow_queries(doc: dict[str, Any]) -> list[tuple[str, str]]:
    workflow = doc.get("workflowName") or ""
    domain = humanize_token(doc.get("domainObject"))
    area = humanize_token(doc.get("area"))
    services = doc.get("serviceSequence") or []
    rows = [
        (f"understand {workflow}", "title_lookup"),
        (f"{workflow} workflow", "workflow_lookup"),
        (f"how does {workflow} work", "workflow_howto"),
    ]
    if domain:
        rows.append((f"{domain} service sequence", "service_sequence_lookup"))
    if area and area != domain:
        rows.append((f"{area} verified workflow", "verified_workflow_lookup"))
    if services:
        first = services[0].split("#", 1)[0].rsplit(".", 1)[-1]
        rows.append((f"{first} workflow test", "service_family_lookup"))
    return rows


def pattern_queries(doc: dict[str, Any]) -> list[tuple[str, str]]:
    domain = humanize_token(doc.get("domainObject"))
    area = humanize_token(doc.get("area"))
    pattern = doc.get("patternName") or ""
    kind = doc.get("documentKind") or "generic_business_pattern"
    entities = top_entities(doc)
    rows = [
        (f"understand {pattern}", "title_lookup"),
        (f"{domain} pattern", "pattern_lookup"),
    ]
    if kind == "technical_configuration_pattern":
        rows.append((f"{domain} technical pattern", "technical_pattern_lookup"))
    elif kind == "reference_data_pattern":
        rows.append((f"{domain} reference pattern", "reference_pattern_lookup"))
    else:
        rows.append((f"how to configure or operate {domain}", "business_pattern_lookup"))
    if area and area != domain:
        rows.append((f"{area} {domain} pattern", "area_domain_pattern_lookup"))
    if entities:
        rows.append((f"{domain} core entities", "core_entities_lookup"))
    return rows


def query_rows_for_doc(doc: dict[str, Any], domain_counts: Counter[str]) -> list[dict[str, Any]]:
    base = {
        "targetDocumentId": doc.get("documentId"),
        "area": doc.get("area"),
        "subArea": doc.get("subArea"),
        "domainObject": doc.get("domainObject"),
        "documentKind": doc.get("documentKind"),
        "sourceKind": doc.get("sourceKind"),
        "source": "seed_test_generated",
        "expectedTopK": 3,
    }
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()

    def add_query(query: str, query_type: str) -> None:
        q = (query or "").strip()
        if not q or q in seen:
            return
        seen.add(q)
        row = dict(base)
        row["query"] = q
        row["queryType"] = query_type
        rows.append(row)

    add_query(doc.get("canonicalPrompt", ""), "canonical")
    if doc.get("documentKind") != "seed_scenario":
        for question in doc.get("businessQuestions") or []:
            add_query(question, "business_question")
    else:
        scenario = (doc.get("scenarioName") or "").lower()
        for question in doc.get("businessQuestions") or []:
            qn = question.strip()
            if not qn:
                continue
            if scenario and scenario in qn.lower():
                add_query(qn, "business_question")
    if doc.get("documentKind") == "seed_scenario":
        for query, query_type in scenario_queries(doc, domain_counts):
            add_query(query, query_type)
    elif doc.get("documentKind") == "test_workflow_story":
        for query, query_type in workflow_queries(doc):
            add_query(query, query_type)
    else:
        for query, query_type in pattern_queries(doc):
            add_query(query, query_type)
    for prompt_id in doc.get("relatedAgentPrompts") or []:
        if prompt_id:
            add_query(f"Which executable prompt is related to {doc.get('domainObject', 'this')}?", "related_prompt_lookup")
            break
    for name_key in ("scenarioName", "workflowName", "patternName"):
        if doc.get(name_key):
            add_query(f"understand {doc[name_key]}", "title_lookup")
    return rows[:8]


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate evaluation queries for knowledge-only documents")
    ap.add_argument("--docs", required=True)
    ap.add_argument("--output", default="output/global-knowledge-eval-queries.jsonl")
    args = ap.parse_args()

    docs = load_jsonl(Path(args.docs))
    domain_counts = Counter(
        normalized_name(doc.get("domainObject"))
        for doc in docs
        if doc.get("documentKind") == "seed_scenario"
    )
    rows: list[dict[str, Any]] = []
    for doc in docs:
        rows.extend(query_rows_for_doc(doc, domain_counts))

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    write_jsonl(out_path, rows)
    print(f"Wrote {out_path}")
    print(f"Generated {len(rows)} knowledge evaluation queries")


if __name__ == "__main__":
    main()
