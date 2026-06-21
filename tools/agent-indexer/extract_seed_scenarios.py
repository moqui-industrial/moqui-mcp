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


VALIDITY_BY_SOURCE_KIND = {
    "seed_initial": "production_pattern",
    "install": "production_pattern",
    "seed": "production_pattern",
    "demo": "demo_pattern",
}

TECHNICAL_ENTITY_NAMES = {
    "artifactauthz", "artifactgroup", "artifactgroups", "datafeed", "datadocument",
    "datadocumentcondition", "datadocuments", "dbresource", "dbresourcefile",
    "dbviewentity", "entitysync", "enumeration", "enumgroupmember", "localizedmessage",
    "moquiconf", "screenscheduled", "statusflow", "statusflowitem", "statusflowtransition",
    "usergroupmember", "usergrouppreference", "wiki", "wikipage", "wikipagehistory",
    "wikispace", "wikispaceuser",
}

TECHNICAL_DOMAIN_TOKENS = {
    "artifact", "datadocument", "datafeed", "dbresource", "dbviewentity", "entitysync",
    "enumeration", "enumgroup", "localized", "notification", "screendocument", "screentheme",
    "subscreens", "systemmessage", "usergroup", "wiki",
}

REFERENCE_DOMAIN_TOKENS = {
    "dbform", "enumerationtype", "geo", "statusitem", "tenant", "uom", "userfield",
    "workeffortcategory",
}

AREA_BY_TOKEN = {
    "invoice": "Invoice",
    "payment": "Payment",
    "financialaccount": "Payment",
    "glaccount": "Accounting",
    "acctg": "Accounting",
    "productstore": "ProductStore",
    "productcategory": "Product",
    "productcatalog": "Product",
    "product": "Product",
    "facility": "Facility",
    "shipment": "Shipment",
    "order": "Order",
    "party": "Party",
    "organization": "Party",
    "agreement": "Party",
    "return": "Return",
    "workeffort": "WorkEffort",
    "task": "WorkEffort",
    "project": "WorkEffort",
    "request": "Request",
    "communication": "Communication",
    "content": "Content",
    "asset": "Asset",
    "payroll": "Party",
    "geo": "Party",
    "uom": "Product",
}


def load_inventory(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_screen_prompt_links(path: Path | None) -> list[dict[str, Any]]:
    if not path or not path.exists():
        return []
    docs: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                docs.append(json.loads(line))
            except Exception:
                continue
    return docs


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def humanize_stem(path_str: str) -> str:
    stem = Path(path_str).stem
    stem = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", stem)
    stem = stem.replace("_", " ").replace("-", " ")
    return " ".join(stem.split())


def short_entity(name: str) -> str:
    return name.rsplit(".", 1)[-1]


def norm_token(text: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def split_name_parts(text: str | None) -> list[str]:
    raw = text or ""
    parts = re.findall(r"[A-Z]?[a-z]+|[0-9]+", raw)
    if len(parts) <= 1:
        parts = re.split(r"[^A-Za-z0-9]+", raw)
    return [part for part in parts if part]


def area_from_token(token: str) -> str | None:
    key = re.sub(r"[^a-z0-9]", "", token.lower())
    for needle, area in AREA_BY_TOKEN.items():
        if needle in key:
            return area
    return None


def is_technical_token(token: str) -> bool:
    key = re.sub(r"[^a-z0-9]", "", token.lower())
    return any(needle in key for needle in TECHNICAL_DOMAIN_TOKENS)


def is_reference_token(token: str) -> bool:
    key = re.sub(r"[^a-z0-9]", "", token.lower())
    return any(needle in key for needle in REFERENCE_DOMAIN_TOKENS)


def business_entities(entry: dict[str, Any]) -> list[str]:
    entities = []
    for raw in entry.get("entityNames") or []:
        short = short_entity(raw)
        cleaned = re.sub(r"[^a-z0-9]", "", short.lower())
        if not cleaned or cleaned in TECHNICAL_ENTITY_NAMES:
            continue
        if short in {"parties", "documents"}:
            continue
        entities.append(short)
    return entities


def choose_area_from_entities(entities: list[str]) -> str | None:
    counts = Counter()
    for entity in entities:
        area = area_from_token(entity)
        if area:
            counts[area] += 1
    return counts.most_common(1)[0][0] if counts else None


def choose_domain_from_entities(entities: list[str], chosen_area: str | None) -> str | None:
    if not entities:
        return None
    if chosen_area:
        matching = [entity for entity in entities if area_from_token(entity) == chosen_area]
        if matching:
            return Counter(matching).most_common(1)[0][0]
    return Counter(entities).most_common(1)[0][0]


def affinity_tokens(domain_object: str, related_entities: list[str]) -> list[str]:
    tokens: list[str] = []
    for raw in [domain_object, *related_entities]:
        token = norm_token(raw)
        if token and token not in tokens:
            tokens.append(token)
    return tokens


def text_bag(prompt: dict[str, Any]) -> str:
    return " ".join(
        str(prompt.get(key, "") or "")
        for key in ("documentId", "domainObject", "subArea", "preferredService", "sourceScreenPath")
    ).lower()


def choose_area(entry: dict[str, Any]) -> str:
    entities = business_entities(entry)
    scenario_name = humanize_stem(entry["sourceFile"])
    if is_technical_configuration(entry, entities, scenario_name):
        return "TechnicalConfiguration"
    if is_reference_configuration(entry, entities, scenario_name):
        return "ReferenceData"
    entity_area = choose_area_from_entities(entities)
    if entity_area:
        return entity_area
    areas = entry.get("businessAreas") or []
    non_generic = [area for area in areas if area not in {"WorkEffort", "Facility"}]
    if non_generic:
        return non_generic[0]
    return areas[0] if areas else entry.get("component", "Knowledge")


def choose_domain_object(entry: dict[str, Any]) -> str:
    entities = business_entities(entry)
    scenario_name = humanize_stem(entry["sourceFile"])
    if is_technical_configuration(entry, entities, scenario_name):
        technical_entities = [entity for entity in entities if is_technical_token(entity)]
        if technical_entities:
            return Counter(technical_entities).most_common(1)[0][0]
        return "TechnicalConfiguration"
    if is_reference_configuration(entry, entities, scenario_name):
        reference_entities = [entity for entity in entities if is_reference_token(entity)]
        if reference_entities:
            return Counter(reference_entities).most_common(1)[0][0]
        return "ReferenceData"
    if entities:
        chosen_area = choose_area(entry)
        return choose_domain_from_entities(entities, chosen_area) or entities[0]
    areas = entry.get("businessAreas") or []
    return areas[0] if areas else "Scenario"


def is_technical_configuration(entry: dict[str, Any], entities: list[str], scenario_name: str) -> bool:
    source_file = entry.get("sourceFile", "").lower()
    name_text = scenario_name.lower()
    if any(token in source_file for token in ("setup", "security", "document", "l10n", "type data")):
        return True
    technical_count = sum(1 for entity in entities if is_technical_token(entity))
    business_count = sum(1 for entity in entities if not is_technical_token(entity))
    if technical_count >= 2 and technical_count >= business_count:
        return True
    if technical_count and business_count == 0:
        return True
    if any(token in name_text for token in ("setup", "security", "document", "theme", "message", "notification")):
        return True
    return False


def is_reference_configuration(entry: dict[str, Any], entities: list[str], scenario_name: str) -> bool:
    source_file = entry.get("sourceFile", "").lower()
    name_text = scenario_name.lower()
    reference_count = sum(1 for entity in entities if is_reference_token(entity))
    business_count = sum(1 for entity in entities if not is_reference_token(entity) and not is_technical_token(entity))
    if reference_count >= 1 and reference_count >= business_count:
        return True
    if any(token in source_file for token in ("default data", "type data", "sales data", "holiday")):
        return True
    if any(token in name_text for token in ("default data", "type data", "sales data", "holiday")):
        return True
    return False


def make_business_questions(area: str, domain_object: str, entities: list[str], source_kind: str) -> list[str]:
    entity_label = domain_object.replace("_", " ")
    questions = [
        f"How is {entity_label} configured in Moqui?",
        f"What data is required for {entity_label} setup?",
        f"Which entities are involved in {entity_label} configuration?",
        f"What does this {source_kind.replace('_', ' ')} example teach about {entity_label}?",
    ]
    if area and area != domain_object:
        questions.append(f"How does {entity_label} fit into the {area} area?")
    if entities:
        questions.append(f"Which related business objects appear with {entity_label}?")
    return questions[:6]


def make_business_questions_for_scenario(
    area: str,
    domain_object: str,
    entities: list[str],
    source_kind: str,
    scenario_name: str,
    repeated_domain: bool,
) -> list[str]:
    if not repeated_domain:
        return make_business_questions(area, domain_object, entities, source_kind)
    entity_label = domain_object.replace("_", " ")
    questions = [
        f"How is {entity_label} configured in {scenario_name}?",
        f"What data is required for {entity_label} setup in {scenario_name}?",
        f"Which entities are involved in {entity_label} configuration for {scenario_name}?",
        f"What does this {source_kind.replace('_', ' ')} example teach about {entity_label} in {scenario_name}?",
    ]
    if area and area != domain_object:
        questions.append(f"How does {entity_label} fit into the {area} area in {scenario_name}?")
    if entities:
        questions.append(f"Which related business objects appear with {entity_label} in {scenario_name}?")
    return questions[:6]


def domain_family_key(domain_object: str) -> str:
    parts = split_name_parts(domain_object)
    if not parts:
        return norm_token(domain_object)
    if len(parts) >= 2:
        return norm_token("".join(parts[:2]))
    return norm_token(parts[0])


def scenario_prompt_variants(
    scenario_name: str,
    domain_object: str,
    knowledge_category: str,
    source_file: str,
) -> list[str]:
    domain_label = humanize_stem(domain_object)
    source_tokens = [t.lower() for t in re.split(r"[^A-Za-z0-9]+", humanize_stem(Path(source_file).stem)) if t]
    generic = {"data", "demo", "install", "seed", "initial", "test", "tests", "screen", "service"}
    source_tokens = [t for t in source_tokens if len(t) >= 3 and t not in generic]
    source_phrase = " ".join(source_tokens[:2]).strip()
    variants = [
        f"understand {scenario_name.lower()} {domain_label.lower()}",
        f"{scenario_name} {domain_label}",
    ]
    if knowledge_category == "reference_data":
        variants.append(f"{scenario_name} {domain_label} reference data")
    elif knowledge_category == "technical_configuration":
        variants.append(f"{scenario_name} {domain_label} technical configuration")
    else:
        variants.append(f"{scenario_name} {domain_label} configuration")
    if source_phrase:
        variants.append(f"{source_phrase} {domain_label.lower()} setup")
        variants.append(f"{source_phrase} {domain_label.lower()} configuration")
    seen: list[str] = []
    for variant in variants:
        cleaned = " ".join(variant.split()).strip()
        if cleaned and cleaned not in seen:
            seen.append(cleaned)
    return seen[:6]


def make_canonical_prompt(
    domain_object: str,
    scenario_name: str,
    knowledge_category: str,
    repeated_domain: bool,
) -> str:
    if not repeated_domain:
        return f"understand {domain_object.lower()} configuration"
    if knowledge_category == "reference_data":
        return f"understand {scenario_name.lower()} {humanize_stem(domain_object).lower()} reference data"
    if knowledge_category == "technical_configuration":
        return f"understand {scenario_name.lower()} {humanize_stem(domain_object).lower()} technical configuration"
    return f"understand {scenario_name.lower()} {humanize_stem(domain_object).lower()} configuration"


def make_embedding_text(doc: dict[str, Any]) -> str:
    entities = doc.get("relatedEntities", [])[:12]
    questions = doc.get("businessQuestions", [])[:4]
    related_prompts = doc.get("relatedAgentPrompts", [])[:4]

    entity_str = ", ".join(entities) if entities else "none"
    question_str = " ".join(questions) if questions else ""
    prompt_hint = f"Related executable actions: {', '.join(related_prompts)}." if related_prompts else ""

    # Derive an action hint from the knowledge category
    category = doc.get("knowledgeCategory", "business_configuration")
    if category == "reference_data":
        action_hint = f"Use as reference context when querying or filtering {doc['domainObject']} records."
    elif category == "technical_configuration":
        action_hint = f"Use as technical context when configuring or troubleshooting {doc['domainObject']}."
    else:
        action_hint = (
            f"Use as business context when creating, updating, or processing {doc['domainObject']} records. "
            f"This data represents the reference state needed for {doc['area']} operations."
        )

    return (
        f"This Moqui {doc['sourceKind'].replace('_', ' ')} scenario covers {doc['scenarioName']} "
        f"in the {doc['area']} business area. "
        f"Domain object: {doc['domainObject']}. "
        f"Business purpose: {doc['businessPurpose']} "
        f"Involved entities: {entity_str}. "
        f"{action_hint} "
        f"{prompt_hint} "
        f"{question_str} "
        f"Business validity: {doc['businessValidity']}."
    ).strip()


def link_prompts(screen_docs: list[dict[str, Any]], entry: dict[str, Any], area: str, domain_object: str) -> list[str]:
    areas = set(entry.get("businessAreas") or [])
    related_entities = sorted(set(business_entities(entry)))[:25]
    primary_token = norm_token(domain_object)
    secondary_tokens = [token for token in affinity_tokens(domain_object, related_entities) if token != primary_token]
    scored_primary: list[tuple[int, str]] = []
    scored_fallback: list[tuple[int, str]] = []
    for prompt in screen_docs:
        score = 0
        same_area = prompt.get("area") == area
        if prompt.get("domainObject") == domain_object:
            score += 10
        if same_area:
            score += 2
        if prompt.get("subArea") in areas:
            score += 2
        bag = text_bag(prompt)
        primary_hits = 0
        if primary_token:
            if primary_token in norm_token(prompt.get("domainObject")):
                primary_hits += 2
            if primary_token in norm_token(prompt.get("subArea")):
                primary_hits += 2
            if primary_token in bag:
                primary_hits += 1
        secondary_hits = sum(1 for token in secondary_tokens if token and token in bag)
        score += primary_hits * 4
        score += secondary_hits * 2
        if primary_hits > 0 and secondary_hits == 0:
            score += 4
        elif secondary_hits > 0 and prompt.get("actionKind") == "unresolved":
            score -= 2
        if prompt.get("actionKind") in {"navigate", "list", "create", "update"}:
            score += 1
        if domain_object in {"Party", "Order", "ProductStore", "Product", "Payment"} and primary_hits == 0 and secondary_hits == 0 and prompt.get("domainObject") != domain_object:
            continue
        if primary_hits == 0 and secondary_hits == 0 and prompt.get("domainObject") != domain_object and prompt.get("subArea") not in areas:
            continue
        if score > 0:
            target = scored_primary if same_area or primary_hits > 0 else scored_fallback
            target.append((score, prompt.get("documentId")))
    scored = sorted(scored_primary, key=lambda item: (-item[0], item[1]))
    if len(scored) < 8:
        scored.extend(sorted(scored_fallback, key=lambda item: (-item[0], item[1])))
    seen = set()
    out: list[str] = []
    for _, doc_id in scored:
        if not doc_id or doc_id in seen:
            continue
        seen.add(doc_id)
        out.append(doc_id)
        if len(out) >= 8:
            break
    return out


def build_documents(inventory: dict[str, Any], screen_docs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    candidate_domains: Counter[str] = Counter()
    candidate_families: Counter[str] = Counter()
    for entry in inventory.get("entries", []):
        source_kind = entry.get("sourceKind")
        if source_kind not in {"seed_initial", "seed", "install", "demo"}:
            continue
        if not entry.get("includeInKnowledgeIndex"):
            continue
        domain_object = choose_domain_object(entry)
        candidate_domains[domain_object] += 1
        candidate_families[domain_family_key(domain_object)] += 1

    out_by_key: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    for entry in inventory.get("entries", []):
        source_kind = entry.get("sourceKind")
        if source_kind not in {"seed_initial", "seed", "install", "demo"}:
            continue
        if not entry.get("includeInKnowledgeIndex"):
            continue

        scenario_name = humanize_stem(entry["sourceFile"])
        related_entities = sorted(set(business_entities(entry)))[:25]
        technical_configuration = is_technical_configuration(entry, related_entities, scenario_name)
        reference_configuration = (not technical_configuration) and is_reference_configuration(entry, related_entities, scenario_name)
        area = choose_area(entry)
        domain_object = choose_domain_object(entry)
        knowledge_category = (
            "technical_configuration"
            if technical_configuration
            else "reference_data" if reference_configuration else "business_configuration"
        )
        repeated_domain = candidate_domains[domain_object] > 1 or candidate_families[domain_family_key(domain_object)] > 1
        canonical_prompt = make_canonical_prompt(domain_object, scenario_name, knowledge_category, repeated_domain)
        business_questions = make_business_questions_for_scenario(
            area, domain_object, related_entities, source_kind, scenario_name, repeated_domain
        )
        prompt_variants = scenario_prompt_variants(scenario_name, domain_object, knowledge_category, entry["sourceFile"])
        entity_summary = ", ".join(related_entities[:6]) if related_entities else domain_object
        if knowledge_category == "reference_data":
            business_purpose = (
                f"Provides reference data for {domain_object} ({entity_summary}) "
                f"from {entry['component']} {source_kind.replace('_', ' ')} source. "
                f"Required as lookup data for {area} operations."
            )
        elif knowledge_category == "technical_configuration":
            business_purpose = (
                f"Technical setup for {domain_object} ({entity_summary}) "
                f"in component {entry['component']}. "
                f"Establishes the Moqui configuration artifacts needed for {area} functionality."
            )
        else:
            business_purpose = (
                f"Seeds the business data needed for {domain_object} operations in {area}: "
                f"{entity_summary}. "
                f"Sourced from {entry['component']} {source_kind.replace('_', ' ')} data "
                f"— represents a realistic starting state for {area} business processes."
            )
        dedupe_key = (source_kind, entry["component"], scenario_name, domain_object)
        doc = {
            "documentId": f"agent-scenario://{slug(entry['component'])}/{slug(source_kind)}/{slug(scenario_name)}/{slug(domain_object)}",
            "documentKind": "seed_scenario",
            "sourceKind": source_kind,
            "component": entry["component"],
            "canonicalPrompt": canonical_prompt,
            "area": area,
            "subArea": area,
            "domainObject": domain_object,
            "scenarioName": scenario_name,
            "businessPurpose": business_purpose,
            "sourceArtifacts": [entry["sourceFile"]],
            "sourceFile": entry["sourceFile"],
            "relatedEntities": related_entities,
            "businessQuestions": business_questions,
            "englishPromptVariants": prompt_variants,
            "promptVariants": prompt_variants,
            "businessValidity": VALIDITY_BY_SOURCE_KIND.get(source_kind, "uncertain"),
            "knowledgeCategory": knowledge_category,
            "verifiedByTest": False,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
        }
        doc["relatedAgentPrompts"] = link_prompts(screen_docs, entry, area, domain_object)
        doc["embeddingText"] = make_embedding_text(doc)
        if dedupe_key in out_by_key:
            existing = out_by_key[dedupe_key]
            existing["sourceArtifacts"] = sorted(set(existing["sourceArtifacts"] + doc["sourceArtifacts"]))
            existing["relatedEntities"] = sorted(set(existing["relatedEntities"] + doc["relatedEntities"]))[:25]
            existing["relatedAgentPrompts"] = sorted(set(existing["relatedAgentPrompts"] + doc["relatedAgentPrompts"]))[:10]
            existing["businessQuestions"] = existing["businessQuestions"] + [q for q in doc["businessQuestions"] if q not in existing["businessQuestions"]]
            existing["englishPromptVariants"] = list(dict.fromkeys(existing.get("englishPromptVariants", []) + doc.get("englishPromptVariants", [])))[:8]
            existing["promptVariants"] = list(dict.fromkeys(existing.get("promptVariants", []) + doc.get("promptVariants", [])))[:8]
            existing["embeddingText"] = make_embedding_text(existing)
        else:
            out_by_key[dedupe_key] = doc
    out = sorted(out_by_key.values(), key=lambda d: (d["area"], d["component"], d["scenarioName"], d["sourceKind"]))
    return out


def write_jsonl(path: Path, docs: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for doc in docs:
            fh.write(json.dumps(doc, ensure_ascii=True) + "\n")


def write_summary(path: Path, docs: list[dict[str, Any]]) -> None:
    by_area = Counter(doc["area"] for doc in docs)
    by_component = Counter(doc["component"] for doc in docs)
    lines = [
        "# Seed Scenario Summary",
        "",
        f"- Documents: `{len(docs)}`",
        "",
        "## By Area",
        "",
    ]
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_area.most_common())
    lines.extend(["", "## By Component", ""])
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_component.most_common(20))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Extract seed scenario knowledge documents from source inventory")
    ap.add_argument("--inventory", required=True)
    ap.add_argument("--screen-docs")
    ap.add_argument("--output-dir", default="output")
    args = ap.parse_args()

    inventory = load_inventory(Path(args.inventory))
    screen_docs = load_screen_prompt_links(Path(args.screen_docs)) if args.screen_docs else []
    docs = build_documents(inventory, screen_docs)

    repo_root = Path(__file__).resolve().parent
    out_dir = (repo_root / args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    jsonl_path = out_dir / "global-seed-scenario-documents.jsonl"
    summary_path = out_dir / "global-seed-scenario-summary.md"
    write_jsonl(jsonl_path, docs)
    write_summary(summary_path, docs)
    print(f"Wrote {jsonl_path}")
    print(f"Wrote {summary_path}")
    print(f"Generated {len(docs)} seed scenario documents")


if __name__ == "__main__":
    main()
