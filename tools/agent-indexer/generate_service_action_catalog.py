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
from collections import Counter
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree as ET

from moqui_xsd_action_grammar import extract_action_grammar, find_xml_actions_xsd

SCRIPT_DIR = Path(__file__).resolve().parent
CONFIG_DIR = SCRIPT_DIR / "config"
SKIP_CHILD_TAGS = {
    "field-map", "econdition", "date-filter", "alias", "parameter", "description",
    "auto-parameters", "check-errors", "check-conditions", "default-message"
}


def local_name(tag: str | None) -> str:
    if not tag:
        return ""
    return tag.split("}", 1)[-1].split(":", 1)[-1]


def load_semantics(config_path: Path | None = None) -> dict[str, dict]:
    effective_path = config_path or (CONFIG_DIR / "xml_action_semantics.json")
    return json.loads(effective_path.read_text(encoding="utf-8"))


def scan_service_files(moqui_root: Path, component_paths: list[str] | None = None) -> list[Path]:
    if component_paths:
        roots = [Path(path) if Path(path).is_absolute() else (moqui_root / path) for path in component_paths]
    else:
        roots = [moqui_root]

    files: list[Path] = []
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*.xml"):
            if "/service/" not in path.as_posix():
                continue
            if any(part in {"build", ".gradle", "lib", "bin"} for part in path.parts):
                continue
            files.append(path)
    return sorted({path.resolve() for path in files})


def infer_service_base_name(path: Path) -> str:
    parts = list(path.parts)
    if "service" not in parts:
        return path.stem
    idx = parts.index("service")
    rel_parts = parts[idx + 1 :]
    namespace = ".".join(rel_parts[:-1])
    stem = path.stem
    return f"{namespace}.{stem}" if namespace else stem


def resolve_effective_xml_path(path: Path) -> Path | None:
    raw_bytes = path.read_bytes()
    if raw_bytes.startswith(b"\xef\xbb\xbf"):
        raw_bytes = raw_bytes[3:]
    decoded = raw_bytes.decode("utf-8", errors="ignore").strip()
    if decoded.startswith("/") and decoded.endswith(".xml"):
        if decoded == str(path):
            return None
        redirected = Path(decoded)
        if redirected.exists():
            return redirected
    return path


def collect_parameter_names(service_el: ET.Element, tag_name: str) -> list[str]:
    block = service_el.find(tag_name)
    if block is None:
        return []
    result = []
    for child in block.findall("parameter"):
        name = child.get("name")
        if name:
            result.append(name)
    return result


def infer_semantics(statement_verb: str, semantics_map: dict[str, dict]) -> dict[str, str]:
    if statement_verb in semantics_map:
        return semantics_map[statement_verb]
    if statement_verb.startswith("entity-find"):
        return {"statementClass": "entity_read", "operationEffect": "read"}
    if statement_verb.startswith("entity-"):
        return {"statementClass": "entity_write", "operationEffect": "mutate"}
    if statement_verb.startswith("if") or statement_verb in {"while", "iterate"}:
        return {"statementClass": "control_flow", "operationEffect": "conditional_branch"}
    return {"statementClass": "unknown", "operationEffect": "unknown"}


def infer_subject(statement_verb: str, attrs: dict[str, str]) -> tuple[str | None, str | None, str | None]:
    if attrs.get("entity-name"):
        entity_name = attrs["entity-name"]
        return "entity", entity_name, entity_name.rsplit(".", 1)[-1]
    if statement_verb == "service-call" and attrs.get("name"):
        service_name = attrs["name"]
        domain_object = service_name.split("#", 1)[-1] if "#" in service_name else service_name.rsplit(".", 1)[-1]
        return "service", service_name, domain_object
    if attrs.get("location"):
        return "resource", attrs["location"], None
    return None, None, None


def complement_role(attr_name: str, statement_class: str, operation_effect: str) -> str:
    if attr_name in {"field-name", "name"}:
        return "condition_field" if statement_class == "entity_read" else "input_parameter"
    if attr_name in {"to-field", "field"}:
        return "updated_field" if operation_effect in {"update", "store", "upsert"} else "created_field"
    if attr_name in {"from-field", "from"}:
        return "assigned_variable"
    if attr_name in {"value-field", "list", "entry"}:
        return "output_variable"
    if attr_name == "entity-name":
        return "read_entity" if statement_class == "entity_read" else "written_entity"
    if attr_name == "status-id":
        return "status_target"
    if attr_name == "service-name":
        return "called_service"
    return "input_parameter"


def collect_complements(node: ET.Element, statement_class: str, operation_effect: str, attrs: dict[str, str]) -> list[dict]:
    complements: list[dict] = []
    for attr_name, value in attrs.items():
        if not value or attr_name in {"location", "name", "entity-name"}:
            continue
        complements.append({
            "kind": "attribute",
            "name": attr_name,
            "value": value,
            "role": complement_role(attr_name, statement_class, operation_effect),
        })

    for child in node:
        child_tag = local_name(child.tag)
        if child_tag == "field-map":
            field_name = child.get("field-name") or child.get("to-field") or child.get("field")
            from_value = child.get("from") or child.get("from-field") or child.get("value")
            if field_name:
                complements.append({
                    "kind": "field",
                    "name": field_name,
                    "value": from_value,
                    "role": "lookup_key" if statement_class == "entity_read" else "updated_field",
                })
        elif child_tag == "econdition":
            field_name = child.get("field-name") or child.get("name")
            if field_name:
                complements.append({
                    "kind": "field",
                    "name": field_name,
                    "value": child.get("value"),
                    "role": "condition_field",
                })
    return complements


def summarize_sentence(statement_verb: str, subject_kind: str | None, subject: str | None, operation_effect: str,
        complements: list[dict], called_service: str | None) -> tuple[str, str]:
    domain_label = subject.rsplit(".", 1)[-1] if subject else "artifact"
    comp_names = [item["name"] for item in complements if item.get("name")]
    comp_phrase = ""
    if comp_names:
        unique_names = []
        seen = set()
        for name in comp_names:
            if name in seen:
                continue
            seen.add(name)
            unique_names.append(name)
        comp_phrase = " using " + ", ".join(unique_names[:6])

    if statement_verb == "service-call" and called_service:
        return (
            f"Service action calls downstream service {called_service}.",
            f"Call service {called_service}{comp_phrase}."
        )
    if subject_kind == "entity":
        if operation_effect.startswith("read"):
            return (
                f"Service action performs {statement_verb} on entity {subject}.",
                f"Read {domain_label}{comp_phrase}."
            )
        if operation_effect in {"create", "update", "delete", "store", "upsert"}:
            return (
                f"Service action performs {statement_verb} on entity {subject}.",
                f"{operation_effect.capitalize()} {domain_label}{comp_phrase}."
            )
    if statement_verb == "script":
        return (
            "Service action executes opaque script logic.",
            "Execute opaque script logic."
        )
    return (
        f"Service action executes {statement_verb}.",
        f"Execute {statement_verb}{comp_phrase}."
    )


def likely_queries(service_verb: str, service_noun: str, domain_object: str | None, operation_effect: str) -> list[str]:
    noun_words = service_noun.replace("-", " ").replace("_", " ")
    domain_words = domain_object.replace("_", " ") if domain_object else noun_words
    queries = {
        f"{service_verb} {noun_words}".strip(),
        f"{operation_effect} {domain_words}".strip(),
        f"{service_verb} {domain_words}".strip(),
    }
    if operation_effect == "create":
        queries.add(f"create {domain_words}")
    elif operation_effect == "update":
        queries.add(f"update {domain_words}")
    elif operation_effect.startswith("read"):
        queries.add(f"find {domain_words}")
    return sorted(query for query in queries if query)


def make_statement_id(service_name: str, path_parts: list[int]) -> str:
    joined = "/".join(f"{part:03d}" for part in path_parts)
    return f"statement://service/{service_name}/actions/{joined}"


def walk_action_nodes(node: ET.Element, grammar: dict[str, dict], semantics: dict[str, dict], service_context: dict,
        path_parts: list[int], statement_docs: list[dict]) -> None:
    statement_verb = local_name(node.tag)
    statement_rules = infer_semantics(statement_verb, semantics)
    attrs = {key: value for key, value in node.attrib.items() if value not in (None, "")}
    statement_class = statement_rules.get("statementClass", "unknown")
    operation_effect = statement_rules.get("operationEffect", "unknown")
    subject_kind, subject, domain_object = infer_subject(statement_verb, attrs)
    called_service = attrs.get("name") if statement_verb == "service-call" else None
    complements = collect_complements(node, statement_class, operation_effect, attrs)
    read_entities = [subject] if subject_kind == "entity" and statement_class == "entity_read" else []
    written_entities = [subject] if subject_kind == "entity" and statement_class == "entity_write" else []
    technical_sentence, business_sentence = summarize_sentence(
        statement_verb, subject_kind, subject, operation_effect, complements, called_service
    )
    statement_path = "/".join(f"{part:03d}" for part in path_parts)
    statement_id = make_statement_id(service_context["serviceName"], path_parts)

    likely_user_queries = likely_queries(
        service_context["serviceVerb"],
        service_context["serviceNoun"],
        domain_object or service_context["domainObject"],
        operation_effect,
    )

    doc = {
        "documentId": statement_id,
        "documentKind": "service_action_statement",
        "artifactType": "service",
        "artifactName": service_context["serviceName"],
        "sourceArtifactUri": service_context["sourceArtifactUri"],
        "serviceName": service_context["serviceName"],
        "serviceVerb": service_context["serviceVerb"],
        "serviceNoun": service_context["serviceNoun"],
        "statementId": statement_id,
        "statementPath": f"actions/{statement_path}",
        "statementVerb": statement_verb,
        "statementClass": statement_class,
        "subjectKind": subject_kind,
        "subject": subject,
        "domainObject": domain_object or service_context["domainObject"],
        "operationEffect": operation_effect,
        "complements": complements,
        "conditions": [item for item in complements if item.get("role") == "condition_field"],
        "inputFields": [item["name"] for item in complements if item.get("role") in {"lookup_key", "input_parameter", "condition_field"}],
        "outputVariables": [item["name"] for item in complements if item.get("role") == "output_variable"],
        "calledService": called_service,
        "readEntities": read_entities,
        "writtenEntities": written_entities,
        "opaque": statement_verb == "script",
        "semanticConfidence": "low" if statement_verb == "script" or statement_class == "unknown" else "high",
        "technicalSentence": technical_sentence,
        "businessSentence": business_sentence,
        "likelyUserQueries": likely_user_queries,
        "sourceFile": service_context["sourceFile"],
        "sourceLineHint": attrs.get("_line") or None,
        "grammarAttributes": grammar.get(statement_verb, {}).get("attributes", []),
        "grammarChildren": grammar.get(statement_verb, {}).get("children", []),
        "embeddingText": " ".join([
            technical_sentence,
            business_sentence,
            service_context["serviceName"],
            service_context["serviceVerb"],
            service_context["serviceNoun"],
            domain_object or service_context["domainObject"] or "",
            " ".join(likely_user_queries),
        ]).strip(),
    }
    statement_docs.append(doc)

    child_idx = 0
    for child in node:
        child_tag = local_name(child.tag)
        if child_tag in SKIP_CHILD_TAGS:
            continue
        if child_tag not in grammar and child_tag not in semantics:
            continue
        child_idx += 1
        walk_action_nodes(child, grammar, semantics, service_context, path_parts + [child_idx], statement_docs)


def build_service_document(service_context: dict, statement_docs: list[dict]) -> dict:
    read_entities = sorted({entity for doc in statement_docs for entity in doc.get("readEntities", []) if entity})
    written_entities = sorted({entity for doc in statement_docs for entity in doc.get("writtenEntities", []) if entity})
    called_services = sorted({doc.get("calledService") for doc in statement_docs if doc.get("calledService")})
    complements = sorted({
        comp.get("name")
        for doc in statement_docs
        for comp in doc.get("complements", [])
        if comp.get("name")
    })
    likely_user_queries = sorted({
        query
        for doc in statement_docs
        for query in doc.get("likelyUserQueries", [])
        if query
    })
    operation_effects = sorted({doc.get("operationEffect") for doc in statement_docs if doc.get("operationEffect")})
    statement_classes = sorted({doc.get("statementClass") for doc in statement_docs if doc.get("statementClass")})
    opaque = any(bool(doc.get("opaque")) for doc in statement_docs)
    domain_object = service_context["domainObject"]

    business_sentence = f"Service {service_context['serviceName']} orchestrates {service_context['serviceVerb']} {domain_object or service_context['serviceNoun']}."
    technical_sentence = (
        f"Service {service_context['serviceName']} contains {len(statement_docs)} action statements, "
        f"reads {len(read_entities)} entities, writes {len(written_entities)} entities, and calls "
        f"{len(called_services)} downstream services."
    )

    return {
        "documentId": f"service://{service_context['serviceName']}",
        "documentKind": "service_action_document",
        "artifactType": "service",
        "artifactName": service_context["serviceName"],
        "serviceName": service_context["serviceName"],
        "serviceVerb": service_context["serviceVerb"],
        "serviceNoun": service_context["serviceNoun"],
        "domainObject": domain_object,
        "sourceArtifactUri": service_context["sourceArtifactUri"],
        "sourceFile": service_context["sourceFile"],
        "inParameters": service_context["inParameters"],
        "outParameters": service_context["outParameters"],
        "implements": service_context["implements"],
        "statementCount": len(statement_docs),
        "statementIds": [doc["statementId"] for doc in statement_docs],
        "readEntities": read_entities,
        "writtenEntities": written_entities,
        "calledServices": called_services,
        "serviceComplements": complements,
        "operationEffects": operation_effects,
        "statementClasses": statement_classes,
        "opaque": opaque,
        "likelyUserQueries": likely_user_queries,
        "technicalSentence": technical_sentence,
        "businessSentence": business_sentence,
        "embeddingText": " ".join([
            technical_sentence,
            business_sentence,
            " ".join(likely_user_queries[:20]),
            " ".join(read_entities[:10]),
            " ".join(written_entities[:10]),
            " ".join(called_services[:10]),
            " ".join(complements[:20]),
        ]).strip(),
    }


def parse_service_file(path: Path, grammar: dict[str, dict], semantics: dict[str, dict]) -> tuple[list[dict], list[dict]]:
    effective_path = resolve_effective_xml_path(path)
    if effective_path is None:
        return [], []
    parser = ET.XMLParser()
    xml_text = effective_path.read_text(encoding="utf-8-sig")
    tree = ET.ElementTree(ET.fromstring(xml_text, parser=parser))
    root = tree.getroot()
    base_name = infer_service_base_name(effective_path)
    statement_docs: list[dict] = []
    service_docs: list[dict] = []

    for service_el in root.findall("service"):
        service_verb = service_el.get("verb", "").strip()
        service_noun = service_el.get("noun", "").strip()
        if not service_verb or not service_noun:
            continue
        service_name = f"{base_name}.{service_verb}#{service_noun}"
        service_context = {
            "serviceName": service_name,
            "serviceVerb": service_verb,
            "serviceNoun": service_noun,
            "domainObject": service_noun,
            "sourceArtifactUri": f"service://{service_name}",
            "sourceFile": str(effective_path),
            "inParameters": collect_parameter_names(service_el, "in-parameters"),
            "outParameters": collect_parameter_names(service_el, "out-parameters"),
            "implements": [impl.get("service") for impl in service_el.findall("implements") if impl.get("service")],
        }

        actions_el = service_el.find("actions")
        service_statement_docs: list[dict] = []
        if actions_el is not None:
            statement_index = 0
            for child in actions_el:
                child_tag = local_name(child.tag)
                if child_tag not in grammar and child_tag not in semantics:
                    continue
                statement_index += 1
                walk_action_nodes(child, grammar, semantics, service_context, [statement_index], service_statement_docs)

        if service_statement_docs:
            statement_docs.extend(service_statement_docs)
            service_docs.append(build_service_document(service_context, service_statement_docs))

    return statement_docs, service_docs


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def generate_catalog(moqui_root: Path, output_dir: Path, component_paths: list[str] | None = None,
        semantics_path: Path | None = None) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    semantics = load_semantics(semantics_path)
    grammar = extract_action_grammar(find_xml_actions_xsd(moqui_root))
    service_files = scan_service_files(moqui_root, component_paths)

    statement_docs: list[dict] = []
    service_docs: list[dict] = []
    failures: list[dict] = []

    for path in service_files:
        try:
            statements, services = parse_service_file(path, grammar, semantics)
            statement_docs.extend(statements)
            service_docs.extend(services)
        except Exception as exc:  # pragma: no cover - summary captures the error
            failures.append({"file": str(path), "error": str(exc)})

    statement_path = output_dir / "global-service-action-statements.jsonl"
    document_path = output_dir / "global-service-action-documents.jsonl"
    summary_path = output_dir / "global-service-action-summary.json"

    write_jsonl(statement_path, statement_docs)
    write_jsonl(document_path, service_docs)

    statement_class_counts = Counter(doc.get("statementClass", "unknown") for doc in statement_docs)
    operation_effect_counts = Counter(doc.get("operationEffect", "unknown") for doc in statement_docs)
    summary = {
        "serviceFileCount": len(service_files),
        "serviceDocumentCount": len(service_docs),
        "statementDocumentCount": len(statement_docs),
        "statementClassCounts": dict(sorted(statement_class_counts.items())),
        "operationEffectCounts": dict(sorted(operation_effect_counts.items())),
        "failureCount": len(failures),
        "failures": failures[:50],
        "outputFiles": {
            "statements": str(statement_path),
            "documents": str(document_path),
            "summary": str(summary_path),
        },
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate Moqui service-action statement and document catalogs")
    parser.add_argument("--moqui-root", required=True, help="Path to Moqui root/workspace")
    parser.add_argument("--component-paths", default="", help="Optional comma-separated component paths to restrict scanning")
    parser.add_argument("--output-dir", required=True, help="Directory for generated JSONL/summary files")
    parser.add_argument("--semantics", default="", help="Optional path to xml_action_semantics.json")
    args = parser.parse_args()

    component_paths = [item.strip() for item in args.component_paths.split(",") if item.strip()]
    semantics_path = Path(args.semantics) if args.semantics else None
    summary = generate_catalog(Path(args.moqui_root), Path(args.output_dir), component_paths, semantics_path)
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
