#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import html
import json
from pathlib import Path
from typing import Any

from moqui_xsd_action_grammar import extract_action_grammar, find_xml_actions_xsd
from generate_service_action_catalog import load_semantics


XML_ACTIONS_CATEGORY_ID = "AgentXmlActionsDsl"
CONTEXT_OBJECT_ID = "AgentXmlCtxObj"
PREDICATE_OBJECT_ID = "AgentXmlPredicateObj"
RESOURCE_OBJECT_ID = "AgentXmlResourceObj"

PARAMETER_DEFS = [
    ("AgMorphSourceDialectDef", "agMorphSourceDialect", "Source Dialect", "PtTextShort", "Dialect that defines the morphism."),
    ("AgMorphSourceElementDef", "agMorphSourceElementName", "Source Element", "PtTextShort", "XSD element name for the morphism."),
    ("AgMorphSubstGroupDef", "agMorphSubstitutionGroupName", "Substitution Group", "PtTextShort", "xml-actions substitution group name."),
    ("AgMorphStatementClassDef", "agMorphStatementClass", "Statement Class", "PtTextShort", "Semantic statement class."),
    ("AgMorphOperationEffectDef", "agMorphOperationEffect", "Operation Effect", "PtTextShort", "Semantic operation effect."),
    ("AgMorphCompositionPolicyDef", "agMorphCompositionPolicy", "Composition Policy", "PtTextShort", "Whether the morphism composes as pure, stateful, structural, delegating, or contextual."),
    ("AgMorphMutatesStateDef", "agMorphMutatesState", "Mutates State", "PtTextIndicator", "Whether the morphism changes state."),
    ("AgMorphControlFlowDef", "agMorphControlFlow", "Control Flow", "PtTextIndicator", "Whether the morphism controls execution flow."),
    ("AgMorphOpaqueDef", "agMorphOpaque", "Opaque", "PtTextIndicator", "Whether the morphism is opaque and cannot be decomposed safely."),
    ("AgMorphGrammarAttrsDef", "agMorphGrammarAttributesJson", "Grammar Attributes", "PtTextShort", "JSON array of attribute operands from XSD."),
    ("AgMorphGrammarChildrenDef", "agMorphGrammarChildrenJson", "Grammar Children", "PtTextShort", "JSON array of child element operands from XSD."),
    ("AgMorphOperandNameDef", "agMorphOperandName", "Operand Name", "PtTextShort", "Name of a morphism operand."),
    ("AgMorphOperandSourceDef", "agMorphOperandSourceType", "Operand Source", "PtTextShort", "Whether the operand comes from an attribute or child element."),
    ("AgMorphOperandRoleDef", "agMorphOperandRole", "Operand Role", "PtTextShort", "Semantic role of the operand."),
    ("AgMorphOperandTypeDef", "agMorphOperandDataType", "Operand Data Type", "PtTextShort", "Logical operand data type."),
    ("AgMorphOperandRequiredDef", "agMorphOperandRequired", "Operand Required", "PtTextIndicator", "Whether the operand is required by the DSL."),
    ("AgMorphOperandMultiDef", "agMorphOperandMultiValue", "Operand Multi Value", "PtTextIndicator", "Whether multiple values are allowed for the operand."),
]

MUTATING_EFFECTS = {"create", "update", "delete", "store", "upsert", "mutate", "delete_related"}
CONTROL_FLOW_CLASSES = {"control_flow", "exit"}
OPAQUE_CLASSES = {"opaque_code"}
PREDICATE_ELEMENTS = {"compare", "expression", "and", "or", "not", "condition"}
RESOURCE_ELEMENTS = {"script"}


def xml_escape(value: Any) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def make_id(prefix: str, name: str) -> str:
    normalized = "".join(ch if ch.isalnum() else "_" for ch in name)
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    return f"{prefix}{normalized.strip('_')[:48]}"


def stable_parameter_id(morphism_id: str, parameter_def_id: str, identity_key: str, sequence_num: int) -> str:
    digest = hashlib.sha1(f"{morphism_id}|{parameter_def_id}|{identity_key}|{sequence_num}".encode("utf-8")).hexdigest()[:28]
    return f"AgXmlPar_{digest}"


def infer_semantics(element_name: str, semantics_map: dict[str, dict[str, str]]) -> dict[str, str]:
    if element_name in semantics_map:
        return semantics_map[element_name]
    if element_name.startswith("entity-find"):
        return {"statementClass": "entity_read", "operationEffect": "read"}
    if element_name.startswith("entity-"):
        return {"statementClass": "entity_write", "operationEffect": "mutate"}
    if element_name in {"if", "while", "iterate", "and", "or", "not", "compare", "expression", "assert"}:
        return {"statementClass": "control_flow", "operationEffect": "conditional_branch"}
    if element_name in {"message", "log", "return", "break", "continue", "check-errors"}:
        return {"statementClass": "control_flow", "operationEffect": "control"}
    return {"statementClass": "unknown", "operationEffect": "unknown"}


def infer_composition_policy(statement_class: str, operation_effect: str) -> str:
    if statement_class in CONTROL_FLOW_CLASSES:
        return "structural"
    if statement_class == "entity_read":
        return "pure"
    if statement_class == "service_call":
        return "delegating"
    if statement_class in OPAQUE_CLASSES:
        return "opaque"
    if statement_class == "entity_write" or operation_effect in MUTATING_EFFECTS:
        return "stateful"
    return "contextual"


def infer_operand_role(element_name: str, operand_name: str) -> str:
    if operand_name == "entity-name":
        return "domain_object"
    if operand_name in {"name", "service-name"} and element_name == "service-call":
        return "called_morphism"
    if operand_name in {"field", "to-field"}:
        return "target_slot"
    if operand_name in {"from", "from-field"}:
        return "source_slot"
    if operand_name in {"value", "default-value"}:
        return "literal_or_expression"
    if operand_name.endswith("-map") or operand_name == "field-map":
        return "mapping"
    if operand_name in {"list", "entry"}:
        return "collection_operand"
    if operand_name in {"econdition", "econditions", "date-filter"}:
        return "predicate_operand"
    return "parameter"


def infer_operand_type(operand_name: str) -> str:
    if operand_name.endswith("-json"):
        return "json"
    if operand_name.endswith("-list") or operand_name == "list":
        return "list"
    if operand_name.endswith("-map") or operand_name in {"in-map", "out-map"}:
        return "map"
    if operand_name in {"async", "required", "ignore-error", "disable-authz", "set-if-empty", "cache", "distinct", "for-update"}:
        return "boolean"
    if operand_name in {"transaction-timeout", "limit", "offset"}:
        return "integer"
    return "string"


def operand_required(element_name: str, operand_name: str) -> bool:
    explicit_required = {
        "service-call": {"name"},
        "set": {"field"},
        "entity-find-one": {"entity-name"},
        "entity-find": {"entity-name"},
        "entity-find-count": {"entity-name"},
        "entity-delete-by-condition": {"entity-name"},
        "entity-find-related-one": {"relationship"},
        "entity-find-related": {"relationship"},
        "entity-create": {"value-field"},
        "entity-update": {"value-field"},
        "entity-delete": {"value-field"},
        "iterate": {"list", "entry"},
    }
    return operand_name in explicit_required.get(element_name, set())


def morphism_target_object(element_name: str, substitution_group: str) -> str:
    if element_name in PREDICATE_ELEMENTS or substitution_group in {"IfCombineConditions", "IfBasicOperations"}:
        return PREDICATE_OBJECT_ID
    if element_name in RESOURCE_ELEMENTS:
        return RESOURCE_OBJECT_ID
    return CONTEXT_OBJECT_ID


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


def build_seed(grammar: dict[str, dict[str, Any]], semantics_map: dict[str, dict[str, str]]) -> dict[str, list[dict[str, Any]]]:
    seed: dict[str, list[dict[str, Any]]] = {
        "category": [{
            "categoryId": XML_ACTIONS_CATEGORY_ID,
            "categoryTypeEnumId": "CtSmall",
            "categoryName": "xml-actions DSL",
            "description": "Atomic morphisms derived directly from xml-actions-3.xsd.",
        }],
        "category_objects": [
            {
                "categoryObjectId": CONTEXT_OBJECT_ID,
                "categoryId": XML_ACTIONS_CATEGORY_ID,
                "objectEntityName": "moqui.agent.dsl.ContextState",
                "objectPkValue": "ContextState",
                "objectTypeEnumId": "CotGeneric",
                "objectName": "ContextState",
                "description": "Generic execution context state before and after a DSL step.",
            },
            {
                "categoryObjectId": PREDICATE_OBJECT_ID,
                "categoryId": XML_ACTIONS_CATEGORY_ID,
                "objectEntityName": "moqui.agent.dsl.Predicate",
                "objectPkValue": "Predicate",
                "objectTypeEnumId": "CotGeneric",
                "objectName": "Predicate",
                "description": "Boolean predicate produced or consumed by conditional xml-actions operators.",
            },
            {
                "categoryObjectId": RESOURCE_OBJECT_ID,
                "categoryId": XML_ACTIONS_CATEGORY_ID,
                "objectEntityName": "moqui.agent.dsl.ResourceEffect",
                "objectPkValue": "ResourceEffect",
                "objectTypeEnumId": "CotGeneric",
                "objectName": "ResourceEffect",
                "description": "Opaque resource or script evaluation effect.",
            },
        ],
        "parameter_defs": [],
        "morphisms": [],
        "parameters": [],
    }

    for parameter_def_id, parameter_code, parameter_name, parameter_type, description in PARAMETER_DEFS:
        seed["parameter_defs"].append({
            "parameterDefId": parameter_def_id,
            "parameterTypeEnumId": parameter_type,
            "purposeEnumId": "PpMathModel",
            "parameterCode": parameter_code,
            "parameterName": parameter_name,
            "description": description,
        })

    for element_name, info in sorted(grammar.items()):
        semantics = infer_semantics(element_name, semantics_map)
        statement_class = semantics.get("statementClass", "unknown")
        operation_effect = semantics.get("operationEffect", "unknown")
        substitution_group = info.get("substitutionGroup") or ""
        morphism_id = make_id("AgXmlMorph_", element_name)
        source_object_id = CONTEXT_OBJECT_ID
        target_object_id = morphism_target_object(element_name, substitution_group)
        composition_policy = infer_composition_policy(statement_class, operation_effect)
        attributes = info.get("attributes", []) or []
        children = info.get("children", []) or []

        seed["morphisms"].append({
            "morphismId": morphism_id,
            "categoryId": XML_ACTIONS_CATEGORY_ID,
            "morphismTypeEnumId": "MtGeneral",
            "sourceObjectId": source_object_id,
            "targetObjectId": target_object_id,
            "morphismName": f"xml-actions::{element_name}",
            "morphismSymbol": element_name,
            "serviceName": element_name if element_name == "service-call" else "",
            "description": f"Atomic xml-actions morphism for <{element_name}>.",
        })

        seq = 1
        base_parameter_specs = [
            ("AgMorphSourceDialectDef", "xml-actions", False),
            ("AgMorphSourceElementDef", element_name, False),
            ("AgMorphSubstGroupDef", substitution_group, False),
            ("AgMorphStatementClassDef", statement_class, False),
            ("AgMorphOperationEffectDef", operation_effect, False),
            ("AgMorphCompositionPolicyDef", composition_policy, False),
            ("AgMorphMutatesStateDef", "Y" if operation_effect in MUTATING_EFFECTS or statement_class == "entity_write" else "N", False),
            ("AgMorphControlFlowDef", "Y" if statement_class in CONTROL_FLOW_CLASSES else "N", False),
            ("AgMorphOpaqueDef", "Y" if statement_class in OPAQUE_CLASSES else "N", False),
            ("AgMorphGrammarAttrsDef", attributes, True),
            ("AgMorphGrammarChildrenDef", children, True),
        ]
        for parameter_def_id, value, use_text in base_parameter_specs:
            identity_key = parameter_def_id
            parameter_id = stable_parameter_id(morphism_id, parameter_def_id, identity_key, seq)
            seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, morphism_id, value, use_text=use_text))
            seq += 1

        for attr_name in attributes:
            operand_specs = [
                ("AgMorphOperandNameDef", attr_name, False),
                ("AgMorphOperandSourceDef", "attribute", False),
                ("AgMorphOperandRoleDef", infer_operand_role(element_name, attr_name), False),
                ("AgMorphOperandTypeDef", infer_operand_type(attr_name), False),
                ("AgMorphOperandRequiredDef", "Y" if operand_required(element_name, attr_name) else "N", False),
                ("AgMorphOperandMultiDef", "N", False),
            ]
            for parameter_def_id, value, use_text in operand_specs:
                identity_key = f"attr:{attr_name}:{parameter_def_id}"
                parameter_id = stable_parameter_id(morphism_id, parameter_def_id, identity_key, seq)
                seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, morphism_id, value, use_text=use_text))
                seq += 1

        for child_name in children:
            operand_specs = [
                ("AgMorphOperandNameDef", child_name, False),
                ("AgMorphOperandSourceDef", "child-element", False),
                ("AgMorphOperandRoleDef", infer_operand_role(element_name, child_name), False),
                ("AgMorphOperandTypeDef", "element", False),
                ("AgMorphOperandRequiredDef", "N", False),
                ("AgMorphOperandMultiDef", "Y", False),
            ]
            for parameter_def_id, value, use_text in operand_specs:
                identity_key = f"child:{child_name}:{parameter_def_id}"
                parameter_id = stable_parameter_id(morphism_id, parameter_def_id, identity_key, seq)
                seed["parameters"].append(parameter_row(parameter_id, parameter_def_id, seq, morphism_id, value, use_text=use_text))
                seq += 1

    return seed


def write_seed(output_path: Path, seed: dict[str, list[dict[str, Any]]]) -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<entity-facade-xml type="seed">',
    ]

    entity_map = [
        ("moqui.math.ct.Category", "category"),
        ("moqui.math.ct.CategoryObject", "category_objects"),
        ("moqui.math.ParameterDef", "parameter_defs"),
        ("moqui.math.ct.Morphism", "morphisms"),
        ("moqui.math.Parameter", "parameters"),
    ]
    for entity_name, key in entity_map:
        for row in seed[key]:
            attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
            lines.append(f"    <{entity_name} {attrs}/>")

    lines.append("</entity-facade-xml>")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate seed XML for atomic xml-actions morphisms on top of moqui-math category entities.")
    parser.add_argument("--moqui-root", required=True, help="Path to Moqui root or workspace containing framework/xsd")
    parser.add_argument("--output", required=True, help="Output seed XML file")
    parser.add_argument("--semantics", help="Optional path to xml_action_semantics.json")
    args = parser.parse_args()

    xsd_path = find_xml_actions_xsd(Path(args.moqui_root))
    grammar = extract_action_grammar(xsd_path)
    semantics_map = load_semantics(Path(args.semantics) if args.semantics else None)
    seed = build_seed(grammar, semantics_map)
    write_seed(Path(args.output), seed)


if __name__ == "__main__":
    main()
