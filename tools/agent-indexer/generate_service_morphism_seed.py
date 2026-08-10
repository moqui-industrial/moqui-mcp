#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import hashlib
from pathlib import Path
from typing import Any


XML_ACTIONS_CATEGORY_ID = "AgentXmlActionsDsl"
CONTEXT_OBJECT_ID = "AgentXmlCtxObj"
PREDICATE_OBJECT_ID = "AgentXmlPredicateObj"
RESOURCE_OBJECT_ID = "AgentXmlResourceObj"
ENTITY_AUTO_VERBS = {"create", "update", "store", "delete"}

SERVICE_PARAMETER_DEFS = [
    ("AgSrvStatementCountDef", "agSrvStatementCount", "Statement Count", "PtNumberInteger", "Number of parsed statements inside the service actions block."),
    ("AgSrvSourceFileDef", "agSrvSourceFile", "Source File", "PtTextShort", "Source XML file containing the service definition."),
    ("AgSrvReadEntitiesDef", "agSrvReadEntitiesJson", "Read Entities", "PtTextShort", "JSON array of entities read by the service."),
    ("AgSrvWrittenEntitiesDef", "agSrvWrittenEntitiesJson", "Written Entities", "PtTextShort", "JSON array of entities written by the service."),
    ("AgSrvInOperandDef", "agSrvInOperandJson", "Input Operand", "PtTextShort", "JSON metadata for one input operand of the service morphism."),
    ("AgSrvOutOperandDef", "agSrvOutOperandJson", "Output Operand", "PtTextShort", "JSON metadata for one output operand of the service morphism."),
    ("AgSrvRequiredOperandListDef", "agSrvRequiredOperandListJson", "Required Operands", "PtTextShort", "JSON array of required input operand names."),
    ("AgSrvOptionalOperandListDef", "agSrvOptionalOperandListJson", "Optional Operands", "PtTextShort", "JSON array of optional input operand names."),
    ("AgSrvProducedOperandListDef", "agSrvProducedOperandListJson", "Produced Operands", "PtTextShort", "JSON array of produced output operand names."),
    ("AgSrvRequiredIdOperandListDef", "agSrvRequiredIdOperandListJson", "Required Identity Operands", "PtTextShort", "JSON array of required identity input operand names."),
    ("AgSrvProducedIdOperandListDef", "agSrvProducedIdOperandListJson", "Produced Identity Operands", "PtTextShort", "JSON array of produced identity output operand names."),
    ("AgSrvSemanticDescriptionDef", "agSrvSemanticDescription", "Semantic Description", "PtTextLong", "Semantic service-level description synthesized from the parsed service graph."),
    ("AgSrvSelectionDescriptionDef", "agSrvSelectionDescription", "Selection Description", "PtTextLong", "Short guidance for when this morphism should be selected for a user request."),
    ("AgSrvBindingDescriptionDef", "agSrvBindingDescription", "Binding Description", "PtTextLong", "Short guidance for required bindings, produced identifiers, and execution constraints."),
    ("AgSrvStepPathDef", "agSrvStatementPath", "Statement Path", "PtTextShort", "Stable path of a statement inside the service actions tree."),
    ("AgSrvStepVerbDef", "agSrvStatementVerb", "Statement Verb", "PtTextShort", "Atomic xml-actions operator used by the statement."),
    ("AgSrvStepMorphismRefDef", "agSrvStatementMorphismRef", "Statement Morphism Ref", "PtTextShort", "Referenced atomic xml-actions morphism id."),
    ("AgSrvStepCalledServiceDef", "agSrvCalledService", "Called Service", "PtTextShort", "Downstream service called by the statement, when present."),
    ("AgSrvStepEntityDef", "agSrvStatementEntityName", "Statement Entity", "PtTextShort", "Entity touched by the statement, when present."),
    ("AgSrvStepComplementsDef", "agSrvStatementComplementsJson", "Statement Complements", "PtTextShort", "JSON complements extracted for the statement."),
    ("AgSrvStepConditionsDef", "agSrvStatementConditionsJson", "Statement Conditions", "PtTextShort", "JSON conditions extracted for the statement."),
    ("AgSrvStepOutputsDef", "agSrvStatementOutputsJson", "Statement Outputs", "PtTextShort", "JSON output variables extracted for the statement."),
]


def xml_escape(value: Any) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def parameter_row(parameter_id: str, parameter_def_id: str, sequence_num: int, morphism_id: str, value: Any, *, use_text: bool = False) -> dict[str, Any]:
    row = {
        "parameterId": parameter_id,
        "parameterDefId": parameter_def_id,
        "sequenceNum": sequence_num,
        "morphismId": morphism_id,
    }
    if use_text:
        row["textValue"] = json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value
    else:
        row["symbolicValue"] = "" if value is None else str(value)
    return row


def stable_parameter_id(morphism_id: str, parameter_def_id: str, identity_key: str, sequence_num: int) -> str:
    digest = hashlib.sha1(f"{morphism_id}|{parameter_def_id}|{identity_key}|{sequence_num}".encode("utf-8")).hexdigest()[:28]
    return f"AgSrvPar_{digest}"


def is_meaningful_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str) and value.strip().lower() in ("", "null", "none"):
        return False
    if isinstance(value, (list, tuple, set, dict)) and not value:
        return False
    return True


def is_identity_operand_name(name: str | None, operand_type: str | None = None) -> bool:
    lowered_name = (name or "").lower()
    lowered_type = (operand_type or "").lower()
    return lowered_name.endswith("id") or lowered_name.endswith("seqid") or lowered_type == "id"


def normalize_operand(operand: dict[str, Any], direction: str) -> dict[str, Any]:
    if isinstance(operand, dict):
        normalized = dict(operand)
    elif isinstance(operand, str):
        normalized = {"name": operand}
    else:
        normalized = {"name": str(operand)} if operand is not None else {}
    name = normalized.get("name")
    operand_type = normalized.get("type")
    required = bool(normalized.get("required"))
    identity = bool(normalized.get("identity")) or is_identity_operand_name(name, operand_type)
    normalized["direction"] = direction
    normalized["required"] = required if direction == "in" else False
    normalized["identity"] = identity
    normalized.setdefault("fieldName", name)
    normalized.setdefault("source", "service-definition")
    if not normalized.get("role"):
        lowered_name = (name or "").lower()
        if direction == "out":
            normalized["role"] = "produced_identity_operand" if identity else "produced_operand"
        elif lowered_name.endswith("enumid") or lowered_name.endswith("typeenumid") or lowered_name == "statusid":
            normalized["role"] = "enum_operand"
        elif lowered_name.endswith("seqid"):
            normalized["role"] = "generated_operand"
        elif identity:
            normalized["role"] = "parent_operand" if required else "lookup_operand"
        else:
            normalized["role"] = "literal_value_operand"
    return normalized


def derive_entity_auto_operands(service_name: str, service_verb: str, service_noun: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not service_noun or "." not in service_noun:
        return [], []

    entity_short_name = service_noun.rsplit(".", 1)[-1]
    identity_operand = {
        "name": f"{entity_short_name[:1].lower()}{entity_short_name[1:]}Id" if entity_short_name else "entityId",
        "direction": "in",
        "type": "id",
        "required": service_verb in {"update", "store", "delete"},
        "entityName": service_noun,
        "fieldName": None,
        "defaultValue": None,
        "identity": True,
        "role": "parent_operand" if service_verb in {"update", "store", "delete"} else "lookup_operand",
        "source": "entity-auto-service",
    }
    normalized_in_operands = [normalize_operand(identity_operand, "in")]
    normalized_out_operands = [normalize_operand({
        "name": identity_operand["name"],
        "direction": "out",
        "type": "id",
        "required": False,
        "entityName": service_noun,
        "fieldName": None,
        "defaultValue": None,
        "identity": True,
        "role": "produced_identity_operand",
        "source": "entity-auto-service",
    }, "out")]
    return normalized_in_operands, normalized_out_operands


def stable_id(prefix: str, value: str) -> str:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}{digest}"


def short_morphism_name(prefix: str, full_name: str, *, max_len: int = 63) -> str:
    tail = full_name.split(".")[-1]
    base = f"{prefix}{tail}"
    digest = hashlib.sha1(full_name.encode("utf-8")).hexdigest()[:8]
    if len(base) > max_len - 10:
        base = base[: max_len - 10]
    return f"{base}::{digest}"


def short_service_element_name(service_name: str, service_verb: str, service_noun: str, *, max_len: int = 63) -> str:
    if service_verb and service_noun:
        candidate = f"{service_verb}#{service_noun}"
    else:
        tail = service_name.split(".")[-1]
        candidate = tail[:max_len]
    if len(candidate) <= max_len:
        return candidate
    digest = hashlib.sha1(service_name.encode("utf-8")).hexdigest()[:8]
    return f"{candidate[: max_len - 10]}::{digest}"


def build_morphism_description(service_name: str, service_doc: dict[str, Any]) -> str:
    parts: list[str] = []

    semantic = (
        service_doc.get("semanticDescriptionLlm")
        or service_doc.get("semanticDescription")
        or service_doc.get("businessSentence")
        or service_doc.get("technicalSentence")
    )
    selection = service_doc.get("selectionDescription")
    binding = service_doc.get("bindingDescription")

    if is_meaningful_value(semantic):
        parts.append(str(semantic).strip())
    if is_meaningful_value(selection):
        selection_text = str(selection).strip()
        if selection_text not in parts:
            parts.append(f"When to select: {selection_text}")
    if is_meaningful_value(binding):
        binding_text = str(binding).strip()
        if f"When to select: {binding_text}" not in parts and binding_text not in parts:
            parts.append(f"Binding: {binding_text}")

    if not parts:
        service_verb = service_doc.get("serviceVerb") or ""
        service_noun = service_doc.get("serviceNoun") or ""
        if service_verb and service_noun:
            return f"Composed service morphism for {service_verb}#{service_noun} ({service_name})."
        return f"Composed service morphism for {service_name}."

    return " ".join(parts)


def choose_target_object(service_doc: dict[str, Any]) -> str:
    if service_doc.get("opaque") is True:
        return RESOURCE_OBJECT_ID
    return CONTEXT_OBJECT_ID


def parse_service_verb_noun(service_name: str) -> tuple[str, str]:
    if not service_name or "#" not in service_name:
        return "", ""
    left, noun = service_name.split("#", 1)
    verb = left.split(".")[-1] if left else ""
    return verb or "", noun or ""


def is_entity_auto_service_name(service_name: str) -> bool:
    verb, noun = parse_service_verb_noun(service_name)
    return verb in ENTITY_AUTO_VERBS and "." in noun


def entity_auto_effects(verb: str) -> list[str]:
    if verb == "create":
        return ["create_record"]
    if verb in {"update", "store"}:
        return ["update_record"]
    if verb == "delete":
        return ["delete_record"]
    return []


def build_entity_auto_service_semantics(service_name: str, service_verb: str, service_noun: str,
                                        in_operands: list[dict[str, Any]],
                                        out_operands: list[dict[str, Any]]) -> tuple[str, str, str]:
    required_operands = [item["name"] for item in in_operands if item.get("required") and item.get("name")]
    optional_operands = [item["name"] for item in in_operands if not item.get("required") and item.get("name")]
    produced_operands = [item["name"] for item in out_operands if item.get("name")]

    action_map = {
        "create": "creates a new record",
        "update": "updates an existing record",
        "store": "stores an existing or new record depending on the provided identity binding",
        "delete": "deletes an existing record",
    }
    action_text = action_map.get(service_verb, f"performs a {service_verb} operation")
    semantic = (
        f"Entity-auto service morphism for {service_name}. "
        f"It {action_text} in entity {service_noun}."
    )
    if required_operands:
        semantic += f" Required identity or control bindings: {', '.join(required_operands)}."
    if optional_operands:
        semantic += f" Optional bindings commonly refine the operation context: {', '.join(optional_operands)}."
    if produced_operands:
        semantic += f" Produced operands: {', '.join(produced_operands)}."

    selection = (
        f"Select when the user request is a direct {service_verb} intent on entity {service_noun} "
        f"without requiring a wider multi-domain workflow."
    )
    binding = (
        f"Required bindings: {', '.join(required_operands) if required_operands else 'none'}. "
        f"Optional bindings: {', '.join(optional_operands) if optional_operands else 'none'}. "
        f"Produced operands: {', '.join(produced_operands) if produced_operands else 'none'}."
    )
    return semantic, selection, binding


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def build_seed(statement_docs: list[dict[str, Any]], service_docs: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    seed: dict[str, list[dict[str, Any]]] = {
        "parameter_defs": [],
        "morphisms": [],
        "parameters": [],
    }

    for parameter_def_id, parameter_code, parameter_name, parameter_type, description in SERVICE_PARAMETER_DEFS:
        seed["parameter_defs"].append({
            "parameterDefId": parameter_def_id,
            "parameterTypeEnumId": parameter_type,
            "purposeEnumId": "PpMathModel",
            "parameterCode": parameter_code,
            "parameterName": parameter_name,
            "description": description,
        })

    statements_by_service: dict[str, list[dict[str, Any]]] = {}
    discovered_called_services: set[str] = set()
    for row in statement_docs:
        service_name = row.get("serviceName")
        if not service_name:
            continue
        statements_by_service.setdefault(service_name, []).append(row)
        called_service = row.get("calledService")
        if called_service:
            discovered_called_services.add(str(called_service))
    for rows in statements_by_service.values():
        rows.sort(key=lambda item: item.get("statementPath") or "")

    emitted_services: set[str] = set()
    for service_doc in service_docs:
        service_name = service_doc.get("serviceName")
        if not service_name:
            continue
        emitted_services.add(service_name)
        service_morphism_id = stable_id("AgSrvMorph_", service_name)
        service_verb = service_doc.get("serviceVerb") or ""
        service_noun = service_doc.get("serviceNoun") or ""
        seed["morphisms"].append({
            "morphismId": service_morphism_id,
            "categoryId": XML_ACTIONS_CATEGORY_ID,
            "morphismTypeEnumId": "MtGeneral",
            "sourceObjectId": CONTEXT_OBJECT_ID,
            "targetObjectId": choose_target_object(service_doc),
            "morphismName": short_morphism_name("svc::", service_name),
            "morphismSymbol": f"{service_verb}#{service_noun}" if service_verb and service_noun else service_name,
            "serviceName": service_name,
            "description": build_morphism_description(service_name, service_doc),
        })

        seq = 1
        in_operands = [normalize_operand(item, "in") for item in (service_doc.get("inParameters") or [])]
        out_operands = [normalize_operand(item, "out") for item in (service_doc.get("outParameters") or [])]
        required_operands = [item["name"] for item in in_operands if item.get("required")]
        optional_operands = [item["name"] for item in in_operands if not item.get("required")]
        produced_operands = [item["name"] for item in out_operands if item.get("name")]
        required_id_operands = [item["name"] for item in in_operands if item.get("required") and item.get("identity")]
        produced_id_operands = [item["name"] for item in out_operands if item.get("identity")]
        summary_specs = [
            ("AgMorphSourceDialectDef", "service-definition", False),
            ("AgMorphSourceElementDef", short_service_element_name(service_name, service_verb, service_noun), False),
            ("AgMorphStatementClassDef", "composite_service", False),
            ("AgMorphOperationEffectDef", service_doc.get("operationEffects", []), True),
            ("AgMorphCompositionPolicyDef", "composite", False),
            ("AgMorphMutatesStateDef", "Y" if service_doc.get("mutatesState") else "N", False),
            ("AgMorphControlFlowDef", "N", False),
            ("AgMorphOpaqueDef", "Y" if service_doc.get("opaque") else "N", False),
            ("AgSrvStatementCountDef", service_doc.get("statementCount", 0), False),
            ("AgSrvSourceFileDef", service_doc.get("sourceFile", ""), True),
            ("AgSrvReadEntitiesDef", service_doc.get("readEntities", []), True),
            ("AgSrvWrittenEntitiesDef", service_doc.get("writtenEntities", []), True),
            ("AgSrvRequiredOperandListDef", required_operands, True),
            ("AgSrvOptionalOperandListDef", optional_operands, True),
            ("AgSrvProducedOperandListDef", produced_operands, True),
            ("AgSrvRequiredIdOperandListDef", required_id_operands, True),
            ("AgSrvProducedIdOperandListDef", produced_id_operands, True),
            ("AgSrvSemanticDescriptionDef", service_doc.get("semanticDescriptionLlm") or service_doc.get("semanticDescription"), True),
            ("AgSrvSelectionDescriptionDef", service_doc.get("selectionDescription"), True),
            ("AgSrvBindingDescriptionDef", service_doc.get("bindingDescription"), True),
        ]
        for parameter_def_id, value, use_text in summary_specs:
            identity_key = parameter_def_id
            parameter_id = stable_parameter_id(service_morphism_id, parameter_def_id, identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, service_morphism_id, value, use_text=use_text))
            seq += 1

        for operand in in_operands:
            identity_key = f"in:{operand.get('name')}:{operand.get('fieldName')}:{operand.get('role')}"
            parameter_id = stable_parameter_id(service_morphism_id, "AgSrvInOperandDef", identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, "AgSrvInOperandDef", seq, service_morphism_id, operand, use_text=True))
            seq += 1

        for operand in out_operands:
            identity_key = f"out:{operand.get('name')}:{operand.get('fieldName')}:{operand.get('role')}"
            parameter_id = stable_parameter_id(service_morphism_id, "AgSrvOutOperandDef", identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, "AgSrvOutOperandDef", seq, service_morphism_id, operand, use_text=True))
            seq += 1

        for statement in statements_by_service.get(service_name, []):
            statement_verb = statement.get("statementVerb") or ""
            atomic_morphism_id = f"AgXmlMorph_{''.join(ch if ch.isalnum() else '_' for ch in statement_verb).strip('_')[:48]}"
            step_specs = [
                ("AgSrvStepPathDef", statement.get("statementPath", ""), False),
                ("AgSrvStepVerbDef", statement_verb, False),
                ("AgSrvStepMorphismRefDef", atomic_morphism_id, False),
                ("AgSrvStepCalledServiceDef", statement.get("calledService"), True),
                ("AgSrvStepEntityDef", statement.get("subject") if statement.get("subjectKind") == "entity" else statement.get("entityName"), True),
                ("AgSrvStepComplementsDef", statement.get("complements", []), True),
                ("AgSrvStepConditionsDef", statement.get("conditions", []), True),
                ("AgSrvStepOutputsDef", statement.get("outputVariables", []), True),
            ]
            for parameter_def_id, value, use_text in step_specs:
                if not is_meaningful_value(value):
                    continue
                identity_key = f"{statement.get('statementPath', '')}|{parameter_def_id}"
                parameter_id = stable_parameter_id(service_morphism_id, parameter_def_id, identity_key, seq)
                seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, service_morphism_id, value, use_text=use_text))
                seq += 1

    implicit_entity_auto_services = sorted(
        service_name for service_name in discovered_called_services
        if service_name not in emitted_services and is_entity_auto_service_name(service_name)
    )
    for service_name in implicit_entity_auto_services:
        service_morphism_id = stable_id("AgSrvMorph_", service_name)
        service_verb, service_noun = parse_service_verb_noun(service_name)
        normalized_in_operands, normalized_out_operands = derive_entity_auto_operands(service_name, service_verb, service_noun)
        entity_auto_semantic, entity_auto_selection, entity_auto_binding = build_entity_auto_service_semantics(
            service_name, service_verb, service_noun, normalized_in_operands, normalized_out_operands
        )
        seed["morphisms"].append({
            "morphismId": service_morphism_id,
            "categoryId": XML_ACTIONS_CATEGORY_ID,
            "morphismTypeEnumId": "MtGeneral",
            "sourceObjectId": CONTEXT_OBJECT_ID,
            "targetObjectId": RESOURCE_OBJECT_ID,
            "morphismName": short_morphism_name("svc::", service_name),
            "morphismSymbol": f"{service_verb}#{service_noun}",
            "serviceName": service_name,
            "description": " ".join([entity_auto_semantic, f"When to select: {entity_auto_selection}", f"Binding: {entity_auto_binding}"]),
        })

        seq = 1
        required_operands = [item["name"] for item in normalized_in_operands if item.get("required")]
        optional_operands = [item["name"] for item in normalized_in_operands if not item.get("required")]
        produced_operands = [item["name"] for item in normalized_out_operands if item.get("name")]
        required_id_operands = [item["name"] for item in normalized_in_operands if item.get("required") and item.get("identity")]
        produced_id_operands = [item["name"] for item in normalized_out_operands if item.get("identity")]
        summary_specs = [
            ("AgMorphSourceDialectDef", "entity-auto-service", False),
            ("AgMorphSourceElementDef", short_service_element_name(service_name, service_verb, service_noun), False),
            ("AgMorphStatementClassDef", "entity_auto_service", False),
            ("AgMorphOperationEffectDef", entity_auto_effects(service_verb), True),
            ("AgMorphCompositionPolicyDef", "atomic", False),
            ("AgMorphMutatesStateDef", "Y", False),
            ("AgMorphControlFlowDef", "N", False),
            ("AgMorphOpaqueDef", "N", False),
            ("AgSrvStatementCountDef", 0, False),
            ("AgSrvSourceFileDef", "", True),
            ("AgSrvReadEntitiesDef", [], True),
            ("AgSrvWrittenEntitiesDef", [service_noun], True),
            ("AgSrvRequiredOperandListDef", required_operands, True),
            ("AgSrvOptionalOperandListDef", optional_operands, True),
            ("AgSrvProducedOperandListDef", produced_operands, True),
            ("AgSrvRequiredIdOperandListDef", required_id_operands, True),
            ("AgSrvProducedIdOperandListDef", produced_id_operands, True),
            ("AgSrvSemanticDescriptionDef", entity_auto_semantic, True),
            ("AgSrvSelectionDescriptionDef", entity_auto_selection, True),
            ("AgSrvBindingDescriptionDef", entity_auto_binding, True),
        ]
        for parameter_def_id, value, use_text in summary_specs:
            identity_key = parameter_def_id
            parameter_id = stable_parameter_id(service_morphism_id, parameter_def_id, identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, service_morphism_id, value, use_text=use_text))
            seq += 1

        for operand in normalized_in_operands:
            identity_key = f"in:{operand.get('name')}:{operand.get('fieldName')}:{operand.get('role')}"
            parameter_id = stable_parameter_id(service_morphism_id, "AgSrvInOperandDef", identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, "AgSrvInOperandDef", seq, service_morphism_id, operand, use_text=True))
            seq += 1

        for operand in normalized_out_operands:
            identity_key = f"out:{operand.get('name')}:{operand.get('fieldName')}:{operand.get('role')}"
            parameter_id = stable_parameter_id(service_morphism_id, "AgSrvOutOperandDef", identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, "AgSrvOutOperandDef", seq, service_morphism_id, operand, use_text=True))
            seq += 1

    return seed


def write_seed(output_path: Path, seed: dict[str, list[dict[str, Any]]]) -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<entity-facade-xml type="seed">',
    ]
    for row in seed["parameter_defs"]:
        attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
        lines.append(f'    <moqui.math.ParameterDef {attrs}/>')
    for row in seed["morphisms"]:
        attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
        lines.append(f'    <moqui.math.ct.Morphism {attrs}/>')
    for row in seed["parameters"]:
        attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
        lines.append(f'    <moqui.math.Parameter {attrs}/>')
    lines.append("</entity-facade-xml>")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate service morphism seed XML from service-action statement catalogs.")
    parser.add_argument("--statements", required=True, help="Path to global-service-action-statements.jsonl")
    parser.add_argument("--services", required=True, help="Path to global-service-action-documents.jsonl")
    parser.add_argument("--output", required=True, help="Output seed XML file")
    args = parser.parse_args()

    statement_docs = load_jsonl(Path(args.statements))
    service_docs = load_jsonl(Path(args.services))
    seed = build_seed(statement_docs, service_docs)
    write_seed(Path(args.output), seed)


if __name__ == "__main__":
    main()
