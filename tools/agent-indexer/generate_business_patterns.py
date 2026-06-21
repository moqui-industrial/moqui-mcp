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


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def norm_token(text: str | None) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def split_focus_parts(text: str | None) -> list[str]:
    raw = text or ""
    parts = re.findall(r"[A-Z]?[a-z]+|[0-9]+", raw)
    if len(parts) <= 1:
        parts = re.split(r"[^A-Za-z0-9]+", raw)
    return [part.lower() for part in parts if part]


def normalize_area(text: str | None) -> str:
    return text or "Knowledge"


def normalize_domain(text: str | None) -> str:
    return text or "Pattern"


def is_technical_doc(doc: dict[str, Any]) -> bool:
    if doc.get("knowledgeCategory") == "technical_configuration":
        return True
    area = normalize_area(doc.get("area"))
    if area == "TechnicalConfiguration":
        return True
    domain = normalize_domain(doc.get("domainObject")).lower()
    return any(token in domain for token in (
        "artifact", "datadocument", "datafeed", "dbresource", "dbviewentity", "entitysync",
        "enumeration", "enumgroup", "localized", "notification", "screendocument",
        "screentheme", "subscreens", "systemmessage", "usergroup", "wiki"
    ))


def is_reference_doc(doc: dict[str, Any]) -> bool:
    if doc.get("knowledgeCategory") == "reference_data":
        return True
    area = normalize_area(doc.get("area"))
    if area == "ReferenceData":
        return True
    domain = normalize_domain(doc.get("domainObject")).lower()
    return any(token in domain for token in (
        "dbform", "enumerationtype", "geo", "statusitem", "tenant", "uom", "userfield",
        "workeffortcategory"
    ))


def choose_business_validity(source_kinds: set[str]) -> str:
    if {"seed_initial", "install", "seed"} & source_kinds:
        return "production_pattern"
    if "demo" in source_kinds:
        return "demo_pattern"
    if {"spock_test", "service_test", "test_screen"} & source_kinds:
        return "test_pattern"
    return "uncertain"


def likely_required(items: list[str], doc_count: int) -> list[str]:
    freq = Counter(items)
    threshold = max(2, (doc_count + 1) // 2)
    return sorted(name for name, count in freq.items() if count >= threshold)


def likely_optional(items: list[str], required: set[str]) -> list[str]:
    freq = Counter(items)
    return sorted(name for name, count in freq.items() if count >= 2 and name not in required)


def summarize_services(services: list[str]) -> list[str]:
    if not services:
        return []
    counts = Counter(services)
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return [name for name, _ in ordered[:8]]


def summarize_steps(docs: list[dict[str, Any]]) -> list[str]:
    step_counter: Counter[str] = Counter()
    for doc in docs:
        for step in doc.get("businessSteps") or []:
            if step:
                step_counter[step] += 1
    return [step for step, _ in step_counter.most_common(8)]


def affinity_tokens(domain_object: str, related_entities: list[str], source_examples: list[str]) -> tuple[str, list[str]]:
    primary_token = norm_token(domain_object)
    secondary_tokens: list[str] = []
    for raw in [*related_entities, *source_examples]:
        token = norm_token(Path(raw).stem if "/" in raw or "\\" in raw else raw)
        if token and token != primary_token and token not in secondary_tokens:
            secondary_tokens.append(token)
    return primary_token, secondary_tokens


def focus_tokens(domain_object: str, related_entities: list[str]) -> list[str]:
    tokens: list[str] = []
    raw_values = [domain_object, *related_entities[:6]]
    for raw in raw_values:
        normalized = norm_token(raw)
        if normalized and normalized not in tokens:
            tokens.append(normalized)
        parts = split_focus_parts(raw)
        if len(parts) >= 2:
            combined = "".join(parts[:2])
            if len(combined) >= 6 and combined not in tokens:
                tokens.append(combined)
            tail_combined = "".join(parts[1:])
            if len(tail_combined) >= 6 and tail_combined not in tokens:
                tokens.append(tail_combined)
        elif parts:
            part = parts[0]
            if len(part) >= 5 and part not in tokens:
                tokens.append(part)
    return tokens[:8]


def prompt_bag(prompt_id: str) -> str:
    return prompt_id.lower()


def prompt_area_token(prompt_id: str) -> str:
    if prompt_id.startswith("agent-prompt://"):
        prompt_path = prompt_id[len("agent-prompt://"):].split("/")
        if prompt_path:
            return norm_token(prompt_path[0])
    return ""


def prompt_has_primary_focus(prompt_id: str, focus_tokens_list: list[str]) -> bool:
    bag = prompt_bag(prompt_id)
    return any(token and token in bag for token in focus_tokens_list)


def score_related_prompt(
    prompt_id: str,
    area: str,
    domain: str,
    required_entities: list[str],
    optional_entities: list[str],
    source_examples: list[str],
    service_sequence: list[str],
) -> tuple[int, int]:
    bag = prompt_bag(prompt_id)
    prompt_area = prompt_area_token(prompt_id)
    normalized_area = norm_token(area)
    primary_token, secondary_tokens = affinity_tokens(domain, required_entities + optional_entities, source_examples)
    focus_tokens_list = focus_tokens(domain, required_entities + optional_entities)
    service_tokens = [norm_token(service.rsplit(".", 1)[-1]) for service in service_sequence[:6]]

    score = 0
    cross_area = 0

    if prompt_area and prompt_area == normalized_area:
        score += 12
    elif prompt_area:
        cross_area = 1
        score -= 5

    if any(token in bag for token in focus_tokens_list):
        score += 20
        if f"/{primary_token}" in bag or f"edit{primary_token}" in bag or f"find{primary_token}" in bag:
            score += 6

    matched_secondary = 0
    for token in secondary_tokens[:12]:
        if token and token in bag:
            matched_secondary += 1
            score += 4

    for token in service_tokens:
        if token and token in bag:
            score += 5

    if not any(token in bag for token in focus_tokens_list) and matched_secondary:
        score -= 4

    if "unresolved" in bag:
        score -= 6

    return cross_area, score


def make_questions(area: str, domain: str, required_entities: list[str], top_services: list[str]) -> list[str]:
    label = domain.replace("_", " ")
    questions = [
        f"How do I configure or operate {label} in Moqui?",
        f"What data is required before working with {label}?",
        f"Which business objects are typically involved in {label}?",
    ]
    if top_services:
        questions.append(f"Which services commonly appear in the {label} workflow?")
    if required_entities:
        questions.append(f"What are the core entities in the {label} pattern?")
    if area and area != domain:
        questions.append(f"How does {label} fit into the {area} area?")
    return questions[:6]


def make_process_hints(required_entities: list[str], optional_entities: list[str], top_services: list[str], common_steps: list[str]) -> list[str]:
    hints: list[str] = []
    if required_entities:
        hints.append(f"Start from core records: {', '.join(required_entities[:6])}")
    if optional_entities:
        hints.append(f"Optional supporting records often include: {', '.join(optional_entities[:6])}")
    for service_name in top_services[:4]:
        hints.append(f"Common service in the pattern: {service_name}")
    for step in common_steps[:4]:
        if step not in hints:
            hints.append(step)
    return hints[:8]


def make_pattern_name(area: str, domain: str, has_seed: bool, has_test: bool) -> str:
    if area == "TechnicalConfiguration":
        return f"{domain} technical configuration pattern"
    if area == "ReferenceData":
        return f"{domain} reference data pattern"
    if has_seed and has_test:
        return f"{domain} configuration and workflow pattern"
    if has_seed:
        return f"{domain} configuration pattern"
    if has_test:
        return f"{domain} workflow pattern"
    return f"{area} {domain} business pattern".strip()


def make_embedding_text(doc: dict[str, Any]) -> str:
    return (
        f"This Moqui generic business pattern describes {doc['patternName']}. "
        f"Business area is {doc['area']}. "
        f"Domain object is {doc['domainObject']}. "
        f"Required entities: {', '.join(doc.get('requiredEntities', [])[:10]) or 'none'}. "
        f"Optional entities: {', '.join(doc.get('optionalEntities', [])[:10]) or 'none'}. "
        f"Common service sequence hints: {', '.join(doc.get('serviceSequence', [])[:8]) or 'none'}. "
        f"Process hints: {'; '.join(doc.get('processHints', [])[:6]) or 'none'}. "
        f"Typical questions: {'; '.join(doc.get('businessQuestions', [])[:5])}. "
        f"Business validity is {doc['businessValidity']}."
    )


def group_key(doc: dict[str, Any]) -> tuple[str, str]:
    return normalize_area(doc.get("area")), normalize_domain(doc.get("domainObject"))


def build_documents(seed_docs: list[dict[str, Any]], test_docs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for doc in seed_docs + test_docs:
        grouped.setdefault(group_key(doc), []).append(doc)

    out: list[dict[str, Any]] = []
    for (area, domain), docs in grouped.items():
        if len(docs) < 2:
            continue

        source_kinds = {doc.get("sourceKind", "") for doc in docs}
        has_seed = any(doc.get("documentKind") == "seed_scenario" for doc in docs)
        has_test = any(doc.get("documentKind") == "test_workflow_story" for doc in docs)
        technical_pattern = all(is_technical_doc(doc) for doc in docs)
        reference_pattern = (not technical_pattern) and all(is_reference_doc(doc) for doc in docs)

        related_entities_all = [entity for doc in docs for entity in (doc.get("relatedEntities") or [])]
        required_entities = likely_required(related_entities_all, len(docs))
        optional_entities = likely_optional(related_entities_all, set(required_entities))

        service_sequence = summarize_services(
            [service for doc in docs for service in (doc.get("serviceSequence") or [])]
        )
        common_steps = summarize_steps(docs)
        candidate_prompts = sorted({
            prompt
            for doc in docs
            for prompt in (doc.get("relatedAgentPrompts") or [])
            if prompt
        })
        source_examples = sorted({
            artifact
            for doc in docs
            for artifact in (doc.get("sourceArtifacts") or [])
            if artifact
        })[:12]

        if len(source_examples) < 2 and len(required_entities) < 2 and len(service_sequence) < 2:
            continue

        pattern_name = make_pattern_name(area, domain, has_seed, has_test)
        business_questions = make_questions(area, domain, required_entities, service_sequence)
        process_hints = make_process_hints(required_entities, optional_entities, service_sequence, common_steps)
        prompt_scores = {
            prompt_id: score_related_prompt(
                prompt_id,
                area,
                domain,
                required_entities,
                optional_entities,
                source_examples,
                service_sequence,
            )
            for prompt_id in candidate_prompts
        }
        ranked_prompts = sorted(
            candidate_prompts,
            key=lambda prompt_id: (
                prompt_scores[prompt_id][0],
                -prompt_scores[prompt_id][1],
                prompt_id,
            ),
        )
        focus_tokens_list = focus_tokens(domain, required_entities + optional_entities)
        same_area_primary = [
            prompt_id for prompt_id in ranked_prompts
            if prompt_scores[prompt_id][0] == 0 and prompt_has_primary_focus(prompt_id, focus_tokens_list)
        ]
        same_area_all = [prompt_id for prompt_id in ranked_prompts if prompt_scores[prompt_id][0] == 0]
        cross_area_strong = [
            prompt_id for prompt_id in ranked_prompts
            if prompt_scores[prompt_id][0] == 1
            and prompt_scores[prompt_id][1] >= 12
            and prompt_has_primary_focus(prompt_id, focus_tokens_list)
        ]

        if len(same_area_primary) >= 3:
            related_agent_prompts = same_area_primary[:8]
        elif len(same_area_all) >= 4:
            related_agent_prompts = same_area_all[:8]
        else:
            related_agent_prompts = (same_area_all + cross_area_strong)[:8]
        doc = {
            "documentId": f"agent-pattern://{slug(area)}/{slug(domain)}/{slug(pattern_name)}",
            "documentKind": (
                "technical_configuration_pattern"
                if technical_pattern
                else "reference_data_pattern" if reference_pattern else "generic_business_pattern"
            ),
            "sourceKind": "derived_pattern",
            "canonicalPrompt": f"understand {domain.lower()} business pattern",
            "area": area,
            "subArea": area,
            "domainObject": domain,
            "patternName": pattern_name,
            "abstractPattern": True,
            "sourceExamples": source_examples,
            "sourceArtifacts": source_examples,
            "requiredEntities": required_entities[:12],
            "optionalEntities": optional_entities[:12],
            "relatedEntities": sorted(set(required_entities + optional_entities))[:20],
            "serviceSequence": service_sequence,
            "processHints": process_hints,
            "businessQuestions": business_questions,
            "relatedAgentPrompts": related_agent_prompts,
            "businessValidity": choose_business_validity(source_kinds),
            "knowledgeCategory": (
                "technical_configuration"
                if technical_pattern
                else "reference_data" if reference_pattern else "business_pattern"
            ),
            "verifiedByTest": has_test,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
        }
        doc["embeddingText"] = make_embedding_text(doc)
        out.append(doc)

    out.sort(key=lambda d: (d["area"], d["domainObject"], d["patternName"]))
    return out


def write_summary(path: Path, docs: list[dict[str, Any]]) -> None:
    by_area = Counter(doc.get("area") for doc in docs)
    lines = [
        "# Business Pattern Summary",
        "",
        f"- Documents: `{len(docs)}`",
        "",
        "## By Area",
        "",
    ]
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_area.most_common())
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate generic business patterns from seed scenarios and test workflows")
    ap.add_argument("--seed-docs", required=True)
    ap.add_argument("--test-docs", required=True)
    ap.add_argument("--output-dir", default="output")
    args = ap.parse_args()

    repo_root = Path(__file__).resolve().parent
    out_dir = (repo_root / args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    seed_docs = load_jsonl(Path(args.seed_docs))
    test_docs = load_jsonl(Path(args.test_docs))
    docs = build_documents(seed_docs, test_docs)

    jsonl_path = out_dir / "global-business-pattern-documents.jsonl"
    summary_path = out_dir / "global-business-pattern-summary.md"
    write_jsonl(jsonl_path, docs)
    write_summary(summary_path, docs)
    print(f"Wrote {jsonl_path}")
    print(f"Wrote {summary_path}")
    print(f"Generated {len(docs)} generic business pattern documents")


if __name__ == "__main__":
    main()
