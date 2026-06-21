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
import xml.etree.ElementTree as ET


SERVICE_NAME_RE = re.compile(r"""name\s*\(\s*['"]([^'"]+)['"]\s*\)""")
ASSERT_LINE_RE = re.compile(r"""\bassert\b|then:|expect:|thrown\s*\(|noExceptionThrown\s*\(""")
STEP_RE = re.compile(r"""resultMessages\.add\(\s*["']([^"']+)["']""")

TECHNICAL_ENTITY_NAMES = {
    "artifactauthz", "artifactgroup", "artifactgroups", "datafeed", "datadocument",
    "datadocumentcondition", "datadocuments", "dbresource", "dbresourcefile",
    "dbviewentity", "entitysync", "enumeration", "enumgroupmember", "localizedmessage",
    "screenscheduled", "statusflow", "statusflowitem", "statusflowtransition",
    "usergroupmember", "usergrouppreference", "wiki", "wikipage", "wikipagehistory",
    "wikispace", "wikispaceuser",
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


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def humanize_stem(path_str: str) -> str:
    stem = Path(path_str).stem
    stem = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", stem)
    stem = stem.replace("_", " ").replace("-", " ")
    return " ".join(stem.split())


def short_entity(name: str) -> str:
    return name.rsplit(".", 1)[-1]


def area_from_token(token: str) -> str | None:
    key = re.sub(r"[^a-z0-9]", "", token.lower())
    for needle, area in AREA_BY_TOKEN.items():
        if needle in key:
            return area
    return None


def norm_token(text: str | None) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def split_focus_parts(text: str | None) -> list[str]:
    raw = text or ""
    parts = re.findall(r"[A-Z]?[a-z]+|[0-9]+", raw)
    if len(parts) <= 1:
        parts = re.split(r"[^A-Za-z0-9]+", raw)
    return [part.lower() for part in parts if part]


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


def domain_from_service(service_name: str) -> str | None:
    if "#" not in service_name:
        return None
    after_hash = service_name.partition("#")[2].rsplit(".", 1)[-1]
    return after_hash or None


def choose_area_from_services(service_names: list[str]) -> str | None:
    counts = Counter()
    for service_name in service_names:
        parts = re.split(r"[^A-Za-z0-9]+", service_name)
        for part in parts:
            area = area_from_token(part)
            if area:
                counts[area] += 1
    return counts.most_common(1)[0][0] if counts else None


def choose_domain_from_services(service_names: list[str], area: str | None) -> str | None:
    candidates = [domain_from_service(name) for name in service_names]
    candidates = [name for name in candidates if name]
    if not candidates:
        return None
    if area:
        matching = [name for name in candidates if area_from_token(name) == area]
        if matching:
            return Counter(matching).most_common(1)[0][0]
    return Counter(candidates).most_common(1)[0][0]


def service_steps(service_names: list[str]) -> list[str]:
    out: list[str] = []
    for service_name in service_names[:12]:
        verb, _, noun = service_name.partition("#")
        if "#" in service_name:
            step = f"Call service {verb} on {noun}"
        else:
            step = f"Call service {service_name}"
        out.append(step)
    return out


def extract_xml_story(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    root = ET.fromstring(text)
    service_calls: list[str] = []
    transitions: list[str] = []
    for elem in root.iter():
        name = local_name(elem.tag)
        if name in {"service-call", "service-call-sync", "service-call-async", "call-service"}:
            service_name = elem.attrib.get("name") or elem.attrib.get("service-name")
            if service_name:
                service_calls.append(service_name)
        if name == "transition" and elem.attrib.get("name"):
            transitions.append(elem.attrib["name"])
    steps = service_steps(service_calls)
    return {
        "serviceSequence": service_calls,
        "businessSteps": steps,
        "expectedResult": [f"Workflow reaches {len(service_calls)} service calls"] if service_calls else [],
        "screenTransitions": transitions,
        "assertionCount": 0,
    }


def extract_groovy_story(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    service_calls = SERVICE_NAME_RE.findall(text)
    step_texts = STEP_RE.findall(text)
    business_steps = step_texts[:10] if step_texts else service_steps(service_calls)
    expected = []
    if ASSERT_LINE_RE.search(text):
        expected.append("Assertions verify the workflow outcome")
    if service_calls:
        expected.append(f"Service/test sequence executes {len(service_calls)} calls")
    return {
        "serviceSequence": service_calls,
        "businessSteps": business_steps[:12],
        "expectedResult": expected,
        "screenTransitions": [],
        "assertionCount": len(ASSERT_LINE_RE.findall(text)),
    }


def choose_area(entry: dict[str, Any], story: dict[str, Any]) -> str:
    service_area = choose_area_from_services(story.get("serviceSequence") or [])
    if service_area:
        return service_area
    entities = business_entities(entry)
    entity_area = Counter(area_from_token(entity) for entity in entities if area_from_token(entity))
    if entity_area:
        return entity_area.most_common(1)[0][0]
    areas = entry.get("businessAreas") or []
    non_generic = [area for area in areas if area not in {"WorkEffort", "Facility"}]
    if non_generic:
        return non_generic[0]
    return areas[0] if areas else entry.get("component", "Knowledge")


def choose_domain_object(entry: dict[str, Any], story: dict[str, Any], chosen_area: str) -> str:
    service_domain = choose_domain_from_services(story.get("serviceSequence") or [], chosen_area)
    if service_domain:
        return service_domain
    entities = business_entities(entry)
    if entities:
        matching = [entity for entity in entities if area_from_token(entity) == chosen_area]
        pool = matching or entities
        return Counter(pool).most_common(1)[0][0]
    areas = entry.get("businessAreas") or []
    if areas:
        return areas[0]
    if entry.get("serviceCalls"):
        last = entry["serviceCalls"][0].partition("#")[2]
        return last.rsplit(".", 1)[-1] if last else "Workflow"
    return "Workflow"


def workflow_focus_tokens(domain_object: str, service_sequence: list[str], related_entities: list[str]) -> list[str]:
    tokens: list[str] = []
    primary = norm_token(domain_object)
    if primary:
        tokens.append(primary)
    for raw in [domain_object, *related_entities[:6]]:
        parts = split_focus_parts(raw)
        if len(parts) >= 2:
            combined = "".join(parts[:2])
            if len(combined) >= 6 and combined not in tokens:
                tokens.append(combined)
            tail = "".join(parts[1:])
            if len(tail) >= 6 and tail not in tokens:
                tokens.append(tail)
        elif parts:
            part = parts[0]
            if len(part) >= 5 and part not in tokens:
                tokens.append(part)
    for service_name in service_sequence[:8]:
        verb_part, _, noun_part = service_name.partition("#")
        if noun_part:
            noun = norm_token(noun_part.rsplit(".", 1)[-1])
            if noun and noun not in tokens:
                tokens.append(noun)
        verb_tokens = [norm_token(part) for part in re.split(r"[^A-Za-z0-9]+", verb_part) if part]
        for token in verb_tokens:
            if len(token) >= 4 and token not in tokens:
                tokens.append(token)
    return tokens[:12]


def service_action_tokens(service_sequence: list[str]) -> tuple[set[str], set[str]]:
    action_tokens: set[str] = set()
    service_name_tokens: set[str] = set()
    for service_name in service_sequence[:12]:
        verb_part, _, noun_part = service_name.partition("#")
        if verb_part:
            action_tokens.add(norm_token(verb_part.rsplit(".", 1)[-1]))
        if noun_part:
            service_name_tokens.add(norm_token(noun_part.rsplit(".", 1)[-1]))
        for part in re.split(r"[^A-Za-z0-9]+", service_name):
            token = norm_token(part)
            if len(token) >= 4:
                service_name_tokens.add(token)
    return action_tokens, service_name_tokens


def prompt_bag(prompt: dict[str, Any]) -> str:
    return " ".join(
        str(prompt.get(key, "") or "")
        for key in ("documentId", "domainObject", "subArea", "preferredService", "sourceScreenPath", "actionKind")
    ).lower()


def link_prompts(screen_docs: list[dict[str, Any]], entry: dict[str, Any], story: dict[str, Any], area: str, domain_object: str) -> list[str]:
    areas = set(entry.get("businessAreas") or [])
    service_sequence = story.get("serviceSequence") or []
    related_entities = sorted(set(business_entities(entry)))
    focus_tokens = workflow_focus_tokens(domain_object, service_sequence, related_entities)
    action_tokens, service_name_tokens = service_action_tokens(service_sequence)
    scored: list[tuple[int, int, str]] = []
    for prompt in screen_docs:
        score = 0
        cross_area = 0
        prompt_id = prompt.get("documentId")
        if not prompt_id:
            continue
        bag = prompt_bag(prompt)
        domain_match = prompt.get("domainObject") == domain_object
        focus_match = any(token and token in bag for token in focus_tokens)
        preferred_service = prompt.get("preferredService")
        exact_service_match = bool(preferred_service and preferred_service in service_sequence)
        if not (domain_match or focus_match or exact_service_match):
            continue
        if prompt.get("area") == area:
            score += 12
        else:
            cross_area = 1
            score -= 6
        if domain_match:
            score += 12
        if prompt.get("subArea") in areas:
            score += 5
        if exact_service_match:
            score += 30
        if prompt.get("actionKind"):
            action_key = norm_token(prompt.get("actionKind"))
            if action_key and any(action_key in token or token in action_key for token in action_tokens):
                score += 8
        if focus_match:
            for token in focus_tokens:
                if token and token in bag:
                    score += 6
        for token in service_name_tokens:
            if token and token in bag:
                score += 3
        if "unresolved" in bag:
            score -= 8
        if score > 0:
            scored.append((cross_area, -score, prompt_id))
    scored.sort()
    out: list[str] = []
    seen = set()
    for _, _, doc_id in scored:
        if not doc_id or doc_id in seen:
            continue
        seen.add(doc_id)
        out.append(doc_id)
        if len(out) >= 8:
            break
    return out


def should_suppress_prompt_links(entry: dict[str, Any], story: dict[str, Any], area: str, domain_object: str, workflow_name: str) -> bool:
    component = (entry.get("component") or "").lower()
    workflow_key = workflow_name.lower()
    service_sequence = story.get("serviceSequence") or []
    source_file = (entry.get("sourceFile") or "").lower()

    # Example/demo test fixtures often describe framework CRUD behavior rather than executable business UI flows.
    if domain_object in {"Example", "ExampleMessage"}:
        return True
    if component in {"example", "moqui", "moqui-framework"} and "example" in workflow_key:
        return True

    # Very generic framework/screen/render/cache facade tests don't have enough business signal for reliable prompt linking.
    generic_workflow_markers = (
        "screen tests", "screen render tests", "rest api tests", "cache facade tests", "entity crud",
        "entity find tests", "entity no sql crud", "l10n facade tests", "message facade tests",
        "resource facade tests", "service facade tests", "sub select tests", "timezone test",
        "tools rest api tests", "tools screen render tests", "transaction facade tests", "user facade tests"
    )
    if area == "WorkEffort" and not service_sequence:
        if component in {"moqui", "moqui-framework", "hivemind", "simplescreens", "popcommerce", "start"}:
            return True
        if any(marker in workflow_key for marker in generic_workflow_markers):
            return True
        if "/src/test/groovy/" in source_file or "/bin/test/" in source_file:
            return True

    return False


def make_questions(area: str, domain_object: str, workflow_name: str, component: str = "") -> list[str]:
    questions = [
        f"How does the {workflow_name} workflow work in Moqui?",
        f"Which services are called in the {workflow_name} test?",
        f"What data is required before running the {workflow_name} workflow?",
        f"What result should be verified for the {workflow_name} test?",
        f"How can I debug the {workflow_name} workflow represented by this test?",
    ]
    domain_norm = re.sub(r"[^a-z0-9]", "", domain_object.lower())
    workflow_norm = re.sub(r"[^a-z0-9]", "", workflow_name.lower())
    if domain_norm and domain_norm not in workflow_norm:
        questions.append(f"How does the {workflow_name} test handle {domain_object} operations?")
    if component:
        questions.append(f"Where is the {workflow_name} test in the {component} component?")
    return questions


def _workflow_embedding_text(doc: dict[str, Any]) -> str:
    """Generate embedding text that uniquely identifies the test workflow by name and component."""
    service_seq = doc.get("serviceSequence", [])[:8]
    business_steps = doc.get("businessSteps", [])[:8]
    expected = doc.get("expectedResult", [])[:4]
    entities = doc.get("relatedEntities", [])[:8]
    related_prompts = doc.get("relatedAgentPrompts", [])[:4]
    questions = doc.get("businessQuestions", [])  # all questions for maximum retrieval surface

    source_stem = Path(doc.get("sourceFile", "")).stem
    component = doc.get("component", "")

    # Build a readable step sequence
    step_lines: list[str] = []
    if business_steps:
        for i, step in enumerate(business_steps, 1):
            step_lines.append(f"step {i}: {step}")
    elif service_seq:
        for i, svc in enumerate(service_seq[:6], 1):
            verb, _, noun = svc.partition("#")
            noun_short = noun.rsplit(".", 1)[-1] if "." in noun else noun
            step_lines.append(f"step {i}: {verb} {noun_short}")

    steps_str = "; ".join(step_lines) if step_lines else "no explicit steps captured"
    entities_str = ", ".join(entities) if entities else "none"
    expected_str = "; ".join(expected) if expected else "no explicit assertions captured"
    prompt_hint = f"Related executable actions: {', '.join(related_prompts)}." if related_prompts else ""
    question_str = " ".join(questions) if questions else ""
    assertion_note = f"Verified by {doc['assertionCount']} assertions." if doc.get("assertionCount") else ""
    prerequisite_note = f"Requires these entities to exist: {entities_str}." if entities else ""
    component_note = f"Component: {component}. Test file: {source_stem}." if component else (f"Test file: {source_stem}." if source_stem else "")

    return (
        f"This verified Moqui test workflow is named {doc['workflowName']}. "
        f"{component_note} "
        f"Business area: {doc['area']}. "
        f"Domain object: {doc['domainObject']}. "
        f"To execute this workflow: {steps_str}. "
        f"{prerequisite_note} "
        f"Expected outcome: {expected_str}. "
        f"{assertion_note} "
        f"{prompt_hint} "
        f"{question_str}"
    ).strip()


def build_documents(inventory: dict[str, Any], screen_docs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out_by_key: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    valid_source_kinds = {"test_screen", "service_test", "spock_test"}
    for entry in inventory.get("entries", []):
        if entry.get("sourceKind") not in valid_source_kinds:
            continue
        if not entry.get("includeInKnowledgeIndex"):
            continue
        source_file = Path(entry["sourceFile"])
        try:
            story = extract_xml_story(source_file) if source_file.suffix.lower() == ".xml" else extract_groovy_story(source_file)
        except Exception:
            continue
        if not story["serviceSequence"] and not story["businessSteps"] and story["assertionCount"] == 0:
            continue

        area = choose_area(entry, story)
        domain_object = choose_domain_object(entry, story, area)
        workflow_name = humanize_stem(entry["sourceFile"])
        canonical_prompt = f"understand {workflow_name.lower()} workflow"
        business_questions = make_questions(area, domain_object, workflow_name, entry.get("component", ""))
        dedupe_key = (entry["sourceKind"], entry["component"], workflow_name, domain_object)
        doc = {
            "documentId": f"agent-test-story://{slug(entry['component'])}/{slug(entry['sourceKind'])}/{slug(workflow_name)}/{slug(domain_object)}",
            "documentKind": "test_workflow_story",
            "sourceKind": entry["sourceKind"],
            "component": entry["component"],
            "canonicalPrompt": canonical_prompt,
            "area": area,
            "subArea": area,
            "domainObject": domain_object,
            "workflowName": workflow_name,
            "sourceArtifacts": [entry["sourceFile"]],
            "sourceFile": entry["sourceFile"],
            "serviceSequence": story["serviceSequence"][:20],
            "businessSteps": story["businessSteps"][:20],
            "expectedResult": story["expectedResult"][:10],
            "businessQuestions": business_questions,
            "relatedEntities": sorted(set(business_entities(entry)))[:20],
            "businessValidity": "test_pattern",
            "verifiedByTest": True,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
            "assertionCount": story["assertionCount"],
        }
        if should_suppress_prompt_links(entry, story, area, domain_object, workflow_name):
            doc["relatedAgentPrompts"] = []
        else:
            doc["relatedAgentPrompts"] = link_prompts(screen_docs, entry, story, area, domain_object)
        doc["embeddingText"] = _workflow_embedding_text(doc)
        if dedupe_key in out_by_key:
            existing = out_by_key[dedupe_key]
            existing["sourceArtifacts"] = sorted(set(existing["sourceArtifacts"] + doc["sourceArtifacts"]))
            existing["serviceSequence"] = list(dict.fromkeys(existing["serviceSequence"] + doc["serviceSequence"]))[:20]
            existing["businessSteps"] = list(dict.fromkeys(existing["businessSteps"] + doc["businessSteps"]))[:20]
            existing["expectedResult"] = list(dict.fromkeys(existing["expectedResult"] + doc["expectedResult"]))[:10]
            existing["relatedEntities"] = sorted(set(existing["relatedEntities"] + doc["relatedEntities"]))[:20]
            existing["relatedAgentPrompts"] = sorted(set(existing["relatedAgentPrompts"] + doc["relatedAgentPrompts"]))[:10]
            existing["assertionCount"] = max(existing["assertionCount"], doc["assertionCount"])
            existing["embeddingText"] = _workflow_embedding_text(existing)
        else:
            out_by_key[dedupe_key] = doc
    out = sorted(out_by_key.values(), key=lambda d: (d["area"], d["component"], d["workflowName"], d["sourceKind"]))
    return out


def write_jsonl(path: Path, docs: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for doc in docs:
            fh.write(json.dumps(doc, ensure_ascii=True) + "\n")


def write_summary(path: Path, docs: list[dict[str, Any]]) -> None:
    by_area = Counter(doc["area"] for doc in docs)
    by_component = Counter(doc["component"] for doc in docs)
    lines = [
        "# Test Workflow Summary",
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
    ap = argparse.ArgumentParser(description="Extract test workflow stories from source inventory")
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
    jsonl_path = out_dir / "global-test-workflow-documents.jsonl"
    summary_path = out_dir / "global-test-workflow-summary.md"
    write_jsonl(jsonl_path, docs)
    write_summary(summary_path, docs)
    print(f"Wrote {jsonl_path}")
    print(f"Wrote {summary_path}")
    print(f"Generated {len(docs)} test workflow documents")


if __name__ == "__main__":
    main()
