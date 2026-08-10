#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import html
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ENTITY_CATEGORY_ID = "AgentEntityModel"

PARAMETER_DEFS = [
    ("AgEntSourceFileDef", "agEntSourceFile", "Source File", "PtTextShort", "Source XML file containing the entity or view-entity definition."),
    ("AgEntObjectKindDef", "agEntObjectKind", "Object Kind", "PtTextShort", "Whether the object is an entity or view-entity."),
    ("AgEntPackageDef", "agEntPackageName", "Package Name", "PtTextShort", "Package that owns the entity or view-entity."),
    ("AgEntUseTypeDef", "agEntUseType", "Use Type", "PtTextShort", "Declared use type of the entity."),
    ("AgEntFieldListDef", "agEntFieldListJson", "Field List", "PtTextShort", "JSON array of field metadata."),
    ("AgEntPkFieldListDef", "agEntPkFieldListJson", "Primary Key Field List", "PtTextShort", "JSON array of primary-key field names."),
    ("AgEntRelationshipListDef", "agEntRelationshipListJson", "Relationship List", "PtTextShort", "JSON array of relationship metadata."),
    ("AgEntMasterDetailDef", "agEntMasterDetailJson", "Master Detail", "PtTextShort", "JSON array of master/detail metadata."),
    ("AgEntViewMembersDef", "agEntViewMembersJson", "View Members", "PtTextShort", "JSON array of member-entity metadata."),
    ("AgEntViewMemberRelsDef", "agEntViewMemberRelationshipsJson", "View Member Relationships", "PtTextShort", "JSON array of member-relationship metadata."),
    ("AgEntViewAliasAllDef", "agEntViewAliasAllJson", "View Alias All", "PtTextShort", "JSON array of alias-all metadata."),
    ("AgEntViewAliasesDef", "agEntViewAliasesJson", "View Aliases", "PtTextShort", "JSON array of alias metadata."),
    ("AgEntViewConditionDef", "agEntViewConditionJson", "View Condition", "PtTextShort", "JSON summary of entity-condition metadata."),
    ("AgEntRelTypeDef", "agEntRelationshipType", "Relationship Type", "PtTextShort", "Relationship cardinality/type."),
    ("AgEntRelTitleDef", "agEntRelationshipTitle", "Relationship Title", "PtTextShort", "Relationship title attribute."),
    ("AgEntRelShortAliasDef", "agEntRelationshipShortAlias", "Relationship Short Alias", "PtTextShort", "Relationship short alias."),
    ("AgEntRelMutableDef", "agEntRelationshipMutable", "Relationship Mutable", "PtTextIndicator", "Whether the relationship is mutable."),
    ("AgEntRelTargetDef", "agEntRelatedEntityName", "Related Entity Name", "PtTextShort", "Fully qualified related entity name."),
    ("AgEntRelKeyMapDef", "agEntRelationshipKeyMapJson", "Relationship Key Map", "PtTextShort", "JSON array of relationship key-map metadata."),
    ("AgEntRelKeyValueDef", "agEntRelationshipKeyValueJson", "Relationship Key Value", "PtTextShort", "JSON array of relationship key-value metadata."),
    ("AgEntRelFkNameDef", "agEntRelationshipFkName", "Relationship FK Name", "PtTextShort", "Declared foreign-key name of the relationship."),
    ("AgEntViewAliasDef", "agEntViewMemberAlias", "View Member Alias", "PtTextShort", "Alias used by a view member."),
    ("AgEntViewJoinFromDef", "agEntViewJoinFromAlias", "View Join From Alias", "PtTextShort", "Alias from which a view member joins."),
    ("AgEntViewJoinOptionalDef", "agEntViewJoinOptional", "View Join Optional", "PtTextIndicator", "Whether the view join is optional."),
    ("AgEntViewRelNameDef", "agEntViewRelationshipName", "View Relationship Name", "PtTextShort", "Relationship name used by member-relationship."),
]


def xml_escape(value: Any) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def stable_id(prefix: str, value: str) -> str:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}{digest}"


def short_morphism_name(prefix: str, full_name: str, *, max_len: int = 63) -> str:
    tail = full_name.split(".")[-1]
    digest = hashlib.sha1(full_name.encode("utf-8")).hexdigest()[:8]
    base = f"{prefix}{tail}"
    if len(base) > max_len - 10:
        base = base[: max_len - 10]
    return f"{base}::{digest}"


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _preview_value(value: Any, *, list_limit: int = 8, dict_limit: int = 12, string_limit: int = 160) -> Any:
    if isinstance(value, list):
        preview = [_preview_value(item, list_limit=list_limit, dict_limit=dict_limit, string_limit=string_limit) for item in value[:list_limit]]
        if len(value) > list_limit:
            preview.append({"_truncated": True, "omittedCount": len(value) - list_limit})
        return preview
    if isinstance(value, dict):
        items = list(value.items())
        preview_items = items[:dict_limit]
        preview = {key: _preview_value(val, list_limit=list_limit, dict_limit=dict_limit, string_limit=string_limit) for key, val in preview_items}
        if len(items) > dict_limit:
            preview["_truncated"] = True
            preview["_omittedKeyCount"] = len(items) - dict_limit
        return preview
    if isinstance(value, str) and len(value) > string_limit:
        return value[:string_limit] + "..."
    return value


def encode_text_payload(value: Any, *, max_len: int = 3900) -> str:
    payload = compact_json(value) if not isinstance(value, str) else value
    if len(payload) <= max_len:
        return payload
    summary = {
        "truncated": True,
        "originalLength": len(payload),
        "sha1": hashlib.sha1(payload.encode("utf-8")).hexdigest(),
        "preview": _preview_value(value),
    }
    summary_payload = compact_json(summary)
    if len(summary_payload) <= max_len:
        return summary_payload
    summary["preview"] = str(_preview_value(value, list_limit=4, dict_limit=6, string_limit=96))[:1200]
    summary_payload = compact_json(summary)
    return summary_payload[:max_len]


def full_entity_name(package: str, entity_name: str) -> str:
    return f"{package}.{entity_name}".strip(".")


def load_xml_root(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError:
        return None


def entity_files(scan_root: Path) -> list[Path]:
    return sorted(p for p in scan_root.rglob("*.xml") if "/entity/" in str(p))


def parse_key_maps(parent: ET.Element) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for key_map in parent.findall("./key-map"):
        rows.append({
            "fieldName": key_map.get("field-name", ""),
            "related": key_map.get("related", ""),
        })
    return rows


def parse_key_values(parent: ET.Element) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for key_value in parent.findall("./key-value"):
        rows.append({
            "related": key_value.get("related", ""),
            "value": key_value.get("value", ""),
        })
    return rows


def parse_entity_definitions(scan_root: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    entities: list[dict[str, Any]] = []
    views: list[dict[str, Any]] = []
    for path in entity_files(scan_root):
        root = load_xml_root(path)
        if root is None:
            continue

        for entity in root.findall("./entity"):
            package = entity.get("package", "").strip()
            entity_name = entity.get("entity-name", "").strip()
            if not package or not entity_name:
                continue
            full_name = full_entity_name(package, entity_name)
            fields = []
            pk_fields = []
            for field in entity.findall("./field"):
                field_name = field.get("name", "").strip()
                if not field_name:
                    continue
                field_row = {
                    "name": field_name,
                    "type": field.get("type", ""),
                    "isPk": field.get("is-pk", "false"),
                    "notNull": field.get("not-null", "false"),
                }
                fields.append(field_row)
                if field.get("is-pk") == "true":
                    pk_fields.append(field_name)

            relationships = []
            for rel in entity.findall("./relationship"):
                relationships.append({
                    "type": rel.get("type", ""),
                    "title": rel.get("title", ""),
                    "related": rel.get("related", ""),
                    "fkName": rel.get("fk-name", ""),
                    "shortAlias": rel.get("short-alias", ""),
                    "mutable": rel.get("mutable", ""),
                    "keyMaps": parse_key_maps(rel),
                    "keyValues": parse_key_values(rel),
                })

            masters = []
            for master in entity.findall("./master"):
                detail_rows = []
                for detail in master.findall(".//detail"):
                    detail_rows.append({
                        "relationship": detail.get("relationship", ""),
                        "useMaster": detail.get("use-master", ""),
                    })
                masters.append({
                    "name": master.get("name", "default"),
                    "details": detail_rows,
                })

            entities.append({
                "sourceFile": str(path),
                "package": package,
                "entityName": entity_name,
                "fullName": full_name,
                "useType": entity.get("use", "transactional"),
                "fields": fields,
                "pkFields": pk_fields,
                "relationships": relationships,
                "masters": masters,
            })

        for view in root.findall("./view-entity"):
            package = view.get("package", "").strip()
            entity_name = view.get("entity-name", "").strip()
            if not package or not entity_name:
                continue
            full_name = full_entity_name(package, entity_name)
            members = []
            for member in view.findall("./member-entity"):
                members.append({
                    "entityAlias": member.get("entity-alias", ""),
                    "entityName": member.get("entity-name", ""),
                    "joinFromAlias": member.get("join-from-alias", ""),
                    "joinOptional": member.get("join-optional", "false"),
                    "subSelect": member.get("sub-select", "false"),
                    "keyMaps": parse_key_maps(member),
                })
            member_relationships = []
            for member_rel in view.findall("./member-relationship"):
                member_relationships.append({
                    "entityAlias": member_rel.get("entity-alias", ""),
                    "joinFromAlias": member_rel.get("join-from-alias", ""),
                    "relationship": member_rel.get("relationship", ""),
                    "joinOptional": member_rel.get("join-optional", "false"),
                    "subSelect": member_rel.get("sub-select", "false"),
                })
            alias_all = []
            for alias_all_node in view.findall("./alias-all"):
                alias_all.append({
                    "entityAlias": alias_all_node.get("entity-alias", ""),
                    "prefix": alias_all_node.get("prefix", ""),
                    "excludeFields": [exc.get("field", "") for exc in alias_all_node.findall("./exclude") if exc.get("field")],
                })
            aliases = []
            for alias in view.findall("./alias"):
                aliases.append({
                    "entityAlias": alias.get("entity-alias", ""),
                    "name": alias.get("name", ""),
                    "field": alias.get("field", ""),
                    "function": alias.get("function", ""),
                    "isAggregate": alias.get("is-aggregate", ""),
                    "type": alias.get("type", ""),
                    "pqExpression": alias.get("pq-expression", ""),
                    "hasComplexAlias": alias.find("./complex-alias") is not None,
                    "hasCase": alias.find("./case") is not None,
                })
            condition = view.find("./entity-condition")
            condition_summary = None
            if condition is not None:
                condition_summary = {
                    "distinct": condition.get("distinct", "false"),
                    "dateFilterCount": len(condition.findall("./date-filter")),
                    "conditionCount": len(condition.findall("./econdition")),
                    "conditionsGroupCount": len(condition.findall("./econditions")),
                    "orderByCount": len(condition.findall("./order-by")),
                }

            views.append({
                "sourceFile": str(path),
                "package": package,
                "entityName": entity_name,
                "fullName": full_name,
                "members": members,
                "memberRelationships": member_relationships,
                "aliasAll": alias_all,
                "aliases": aliases,
                "condition": condition_summary,
            })

    return entities, views


def relationship_catalog(entities: list[dict[str, Any]]) -> dict[str, dict[str, dict[str, Any]]]:
    catalog: dict[str, dict[str, dict[str, Any]]] = {}
    for entity in entities:
        entity_map: dict[str, dict[str, Any]] = {}
        for rel in entity.get("relationships", []):
            related = rel.get("related") or ""
            title = rel.get("title") or ""
            short_alias = rel.get("shortAlias") or ""
            if short_alias:
                entity_map[short_alias] = rel
            if title:
                entity_map[f"{title}#{related}"] = rel
            if related:
                entity_map[related] = rel
        catalog[entity["fullName"]] = entity_map
    return catalog


def parameter_row(parameter_id: str, parameter_def_id: str, sequence_num: int, morphism_id: str, value: Any, *, use_text: bool = False) -> dict[str, Any]:
    row = {
        "parameterId": parameter_id,
        "parameterDefId": parameter_def_id,
        "sequenceNum": sequence_num,
        "morphismId": morphism_id,
    }
    if use_text:
        row["textValue"] = encode_text_payload(value)
    else:
        row["symbolicValue"] = "" if value is None else str(value)
    return row


def ensure_object(seed: dict[str, list[dict[str, Any]]], object_map: dict[str, str], entity_name: str, object_kind: str) -> str:
    existing = object_map.get(entity_name)
    if existing:
        return existing
    object_id = stable_id("AgEntObj_", entity_name)
    seed["category_objects"].append({
        "categoryObjectId": object_id,
        "categoryId": ENTITY_CATEGORY_ID,
        "objectEntityName": entity_name,
        "objectPkValue": entity_name,
        "objectTypeEnumId": "CotGeneric",
        "objectName": entity_name.split(".")[-1],
        "objectSymbol": entity_name.split(".")[-1],
        "description": f"{object_kind} object for {entity_name}.",
    })
    object_map[entity_name] = object_id
    return object_id


def build_seed(entities: list[dict[str, Any]], views: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    seed: dict[str, list[dict[str, Any]]] = {
        "category": [{
            "categoryId": ENTITY_CATEGORY_ID,
            "categoryTypeEnumId": "CtSmall",
            "categoryName": "Moqui Entity Model",
            "description": "Entity and view-entity objects plus structural morphisms extracted from entity-definition XML.",
        }],
        "category_objects": [],
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

    object_map: dict[str, str] = {}
    rel_catalog = relationship_catalog(entities)

    for entity in entities:
        object_id = ensure_object(seed, object_map, entity["fullName"], "entity")
        morphism_id = stable_id("AgEntSchema_", entity["fullName"])
        seed["morphisms"].append({
            "morphismId": morphism_id,
            "categoryId": ENTITY_CATEGORY_ID,
            "morphismTypeEnumId": "MtEndo",
            "sourceObjectId": object_id,
            "targetObjectId": object_id,
            "morphismName": short_morphism_name("schema::", entity["fullName"]),
            "morphismSymbol": entity["entityName"],
            "description": f"Schema morphism for entity {entity['fullName']}.",
        })
        seq = 1
        specs = [
            ("AgMorphSourceDialectDef", "entity-definition", False),
            ("AgMorphSourceElementDef", "entity", False),
            ("AgMorphStatementClassDef", "entity_schema", False),
            ("AgMorphCompositionPolicyDef", "structural", False),
            ("AgMorphMutatesStateDef", "N", False),
            ("AgMorphControlFlowDef", "N", False),
            ("AgMorphOpaqueDef", "N", False),
            ("AgEntSourceFileDef", entity["sourceFile"], True),
            ("AgEntObjectKindDef", "entity", False),
            ("AgEntPackageDef", entity["package"], True),
            ("AgEntUseTypeDef", entity.get("useType", "transactional"), False),
            ("AgEntFieldListDef", entity.get("fields", []), True),
            ("AgEntPkFieldListDef", entity.get("pkFields", []), True),
            ("AgEntRelationshipListDef", entity.get("relationships", []), True),
            ("AgEntMasterDetailDef", entity.get("masters", []), True),
        ]
        for parameter_def_id, value, use_text in specs:
            seed["parameters"].append(parameter_row(f"{morphism_id}_{seq:03d}", parameter_def_id, seq, morphism_id, value, use_text=use_text))
            seq += 1

        for rel in entity.get("relationships", []):
            related = rel.get("related") or ""
            if not related:
                continue
            target_id = ensure_object(seed, object_map, related, "entity")
            rel_name = rel.get("shortAlias") or (f"{rel.get('title')}#{related}" if rel.get("title") else related)
            rel_morphism_id = stable_id("AgEntRel_", f"{entity['fullName']}::{rel_name}::{related}")
            seed["morphisms"].append({
                "morphismId": rel_morphism_id,
                "parentMorphismId": morphism_id,
                "categoryId": ENTITY_CATEGORY_ID,
                "morphismTypeEnumId": "MtGeneral",
                "sourceObjectId": object_id,
                "targetObjectId": target_id,
                "morphismName": short_morphism_name("rel::", f"{entity['fullName']}::{rel_name}"),
                "morphismSymbol": rel_name,
                "description": f"Relationship morphism from {entity['fullName']} to {related}.",
            })
            rel_specs = [
                ("AgMorphSourceDialectDef", "entity-definition", False),
                ("AgMorphSourceElementDef", "relationship", False),
                ("AgMorphStatementClassDef", "entity_relationship", False),
                ("AgMorphCompositionPolicyDef", "structural", False),
                ("AgMorphMutatesStateDef", "N", False),
                ("AgMorphControlFlowDef", "N", False),
                ("AgMorphOpaqueDef", "N", False),
                ("AgEntRelTypeDef", rel.get("type", ""), False),
                ("AgEntRelTitleDef", rel.get("title", ""), False),
                ("AgEntRelShortAliasDef", rel.get("shortAlias", ""), False),
                ("AgEntRelMutableDef", rel.get("mutable", ""), False),
                ("AgEntRelTargetDef", related, True),
                ("AgEntRelKeyMapDef", rel.get("keyMaps", []), True),
                ("AgEntRelKeyValueDef", rel.get("keyValues", []), True),
                ("AgEntRelFkNameDef", rel.get("fkName", ""), True),
            ]
            rel_seq = 1
            for parameter_def_id, value, use_text in rel_specs:
                seed["parameters"].append(parameter_row(f"{rel_morphism_id}_{rel_seq:03d}", parameter_def_id, rel_seq, rel_morphism_id, value, use_text=use_text))
                rel_seq += 1

    for view in views:
        view_object_id = ensure_object(seed, object_map, view["fullName"], "view-entity")
        view_morphism_id = stable_id("AgViewSchema_", view["fullName"])
        seed["morphisms"].append({
            "morphismId": view_morphism_id,
            "categoryId": ENTITY_CATEGORY_ID,
            "morphismTypeEnumId": "MtEndo",
            "sourceObjectId": view_object_id,
            "targetObjectId": view_object_id,
            "morphismName": short_morphism_name("schema::", view["fullName"]),
            "morphismSymbol": view["entityName"],
            "description": f"Schema morphism for view-entity {view['fullName']}.",
        })
        seq = 1
        specs = [
            ("AgMorphSourceDialectDef", "entity-definition", False),
            ("AgMorphSourceElementDef", "view-entity", False),
            ("AgMorphStatementClassDef", "view_entity_schema", False),
            ("AgMorphCompositionPolicyDef", "projection", False),
            ("AgMorphMutatesStateDef", "N", False),
            ("AgMorphControlFlowDef", "N", False),
            ("AgMorphOpaqueDef", "N", False),
            ("AgEntSourceFileDef", view["sourceFile"], True),
            ("AgEntObjectKindDef", "view-entity", False),
            ("AgEntPackageDef", view["package"], True),
            ("AgEntViewMembersDef", view.get("members", []), True),
            ("AgEntViewMemberRelsDef", view.get("memberRelationships", []), True),
            ("AgEntViewAliasAllDef", view.get("aliasAll", []), True),
            ("AgEntViewAliasesDef", view.get("aliases", []), True),
            ("AgEntViewConditionDef", view.get("condition", {}), True),
        ]
        for parameter_def_id, value, use_text in specs:
            seed["parameters"].append(parameter_row(f"{view_morphism_id}_{seq:03d}", parameter_def_id, seq, view_morphism_id, value, use_text=use_text))
            seq += 1

        alias_to_entity = {member.get("entityAlias", ""): member.get("entityName", "") for member in view.get("members", []) if member.get("entityAlias")}
        for member in view.get("members", []):
            member_entity = member.get("entityName") or ""
            member_alias = member.get("entityAlias") or ""
            if not member_entity:
                continue
            target_object_id = ensure_object(seed, object_map, member_entity, "entity")
            member_morphism_id = stable_id("AgViewMember_", f"{view['fullName']}::{member_alias}::{member_entity}")
            seed["morphisms"].append({
                "morphismId": member_morphism_id,
                "parentMorphismId": view_morphism_id,
                "categoryId": ENTITY_CATEGORY_ID,
                "morphismTypeEnumId": "MtGeneral",
                "sourceObjectId": view_object_id,
                "targetObjectId": target_object_id,
                "morphismName": short_morphism_name("member::", f"{view['fullName']}::{member_alias or member_entity}"),
                "morphismSymbol": member_alias or member_entity.split(".")[-1],
                "description": f"View member morphism from {view['fullName']} to {member_entity}.",
            })
            member_specs = [
                ("AgMorphSourceDialectDef", "entity-definition", False),
                ("AgMorphSourceElementDef", "member-entity", False),
                ("AgMorphStatementClassDef", "view_member", False),
                ("AgMorphCompositionPolicyDef", "projection", False),
                ("AgMorphMutatesStateDef", "N", False),
                ("AgMorphControlFlowDef", "N", False),
                ("AgMorphOpaqueDef", "N", False),
                ("AgEntRelTargetDef", member_entity, True),
                ("AgEntViewAliasDef", member_alias, False),
                ("AgEntViewJoinFromDef", member.get("joinFromAlias", ""), False),
                ("AgEntViewJoinOptionalDef", member.get("joinOptional", "false"), False),
                ("AgEntRelKeyMapDef", member.get("keyMaps", []), True),
            ]
            member_seq = 1
            for parameter_def_id, value, use_text in member_specs:
                seed["parameters"].append(parameter_row(f"{member_morphism_id}_{member_seq:03d}", parameter_def_id, member_seq, member_morphism_id, value, use_text=use_text))
                member_seq += 1

        for member_rel in view.get("memberRelationships", []):
            join_from_alias = member_rel.get("joinFromAlias") or ""
            relationship_name = member_rel.get("relationship") or ""
            source_entity_name = alias_to_entity.get(join_from_alias, "")
            rel_meta = rel_catalog.get(source_entity_name, {}).get(relationship_name)
            target_entity_name = rel_meta.get("related", "") if rel_meta else ""
            if not source_entity_name or not target_entity_name:
                continue
            if member_rel.get("entityAlias"):
                alias_to_entity[member_rel.get("entityAlias")] = target_entity_name
            target_object_id = ensure_object(seed, object_map, target_entity_name, "entity")
            member_rel_morphism_id = stable_id("AgViewJoin_", f"{view['fullName']}::{join_from_alias}::{relationship_name}")
            seed["morphisms"].append({
                "morphismId": member_rel_morphism_id,
                "parentMorphismId": view_morphism_id,
                "categoryId": ENTITY_CATEGORY_ID,
                "morphismTypeEnumId": "MtGeneral",
                "sourceObjectId": view_object_id,
                "targetObjectId": target_object_id,
                "morphismName": short_morphism_name("join::", f"{view['fullName']}::{relationship_name}"),
                "morphismSymbol": relationship_name,
                "description": f"View member-relationship morphism from {view['fullName']} through {join_from_alias} via {relationship_name}.",
            })
            join_specs = [
                ("AgMorphSourceDialectDef", "entity-definition", False),
                ("AgMorphSourceElementDef", "member-relationship", False),
                ("AgMorphStatementClassDef", "view_member_relationship", False),
                ("AgMorphCompositionPolicyDef", "projection", False),
                ("AgMorphMutatesStateDef", "N", False),
                ("AgMorphControlFlowDef", "N", False),
                ("AgMorphOpaqueDef", "N", False),
                ("AgEntRelTargetDef", target_entity_name, True),
                ("AgEntViewAliasDef", member_rel.get("entityAlias", ""), False),
                ("AgEntViewJoinFromDef", join_from_alias, False),
                ("AgEntViewJoinOptionalDef", member_rel.get("joinOptional", "false"), False),
                ("AgEntViewRelNameDef", relationship_name, True),
            ]
            join_seq = 1
            for parameter_def_id, value, use_text in join_specs:
                seed["parameters"].append(parameter_row(f"{member_rel_morphism_id}_{join_seq:03d}", parameter_def_id, join_seq, member_rel_morphism_id, value, use_text=use_text))
                join_seq += 1

    return seed


def write_seed(output_path: Path, seed: dict[str, list[dict[str, Any]]]) -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<entity-facade-xml type="seed">',
    ]
    for row in seed["category"]:
        attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
        lines.append(f'    <moqui.math.ct.Category {attrs}/>')
    for row in seed["category_objects"]:
        attrs = " ".join(f'{field}="{xml_escape(value)}"' for field, value in row.items() if value not in (None, ""))
        lines.append(f'    <moqui.math.ct.CategoryObject {attrs}/>')
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
    parser = argparse.ArgumentParser(description="Generate moqui-math seed XML for Moqui entity and view-entity structural morphisms.")
    parser.add_argument("--scan-root", required=True, help="Root directory containing Moqui components to scan.")
    parser.add_argument("--output", required=True, help="Output seed XML path.")
    args = parser.parse_args()

    scan_root = Path(args.scan_root)
    entities, views = parse_entity_definitions(scan_root)
    seed = build_seed(entities, views)
    write_seed(Path(args.output), seed)


if __name__ == "__main__":
    main()
