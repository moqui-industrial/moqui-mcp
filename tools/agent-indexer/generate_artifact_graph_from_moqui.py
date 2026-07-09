#!/usr/bin/env python3
"""Generate a structural artifact graph directly from Moqui XML artifacts.

Optionally enrich the structural graph with semantic statement/service graph rows
derived from the service action catalog and related knowledge documents.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from generate_artifact_graph import build_graph, load_jsonl
from generate_service_action_catalog import infer_semantics, load_semantics
from moqui_xsd_artifact_relations import extract_xsd_registry, local_name
from moqui_xsd_action_grammar import extract_action_grammar

FORM_TAGS = {"form-single", "form-list", "form-list-column"}
ENTITY_READ_TAGS = {"entity-find", "entity-find-one", "entity-find-count"}
ENTITY_WRITE_TAGS = {"entity-create", "entity-update", "entity-delete"}
SERVICE_REF_TAGS = {"service-call", "implements"}
TRANSITION_ATTRS = {
    "transition",
    "transition-first-row",
    "transition-second-row",
    "transition-last-row",
    "dynamic-transition",
    "ac-transition",
    "default-transition",
}
ACTIONABLE_WIDGET_TYPES = {
    "link": "LinkWidget",
    "submit": "SubmitWidget",
    "editable": "EditableWidget",
    "editable-load": "EditableLoadWidget",
    "dynamic-container": "DynamicContainerWidget",
    "display-entity": "DisplayEntityWidget",
    "auto-widget-service": "AutoWidgetService",
    "auto-widget-entity": "AutoWidgetEntity",
}

SECURITY_ARTIFACT_VERTEX_TYPES = {"Screen", "ScreenTransition", "Service", "Entity", "ViewEntity"}
ARTIFACT_TYPE_BY_VERTEX_TYPE = {
    "Screen": "AT_XML_SCREEN",
    "ScreenTransition": "AT_XML_SCREEN_TRANS",
    "Service": "AT_SERVICE",
    "Entity": "AT_ENTITY",
    "ViewEntity": "AT_ENTITY",
}


def canonical_data_tag(tag: str) -> str:
    name = local_name(tag)
    return name.split(".")[-1] if "." in name else name


def component_uri_for_path(path: Path) -> str | None:
    for parent in [path.parent] + list(path.parents):
        if (parent / "component.xml").exists():
            try:
                rel_path = path.relative_to(parent).as_posix()
                return f"component://{parent.name}/{rel_path}"
            except ValueError:
                continue
    return None


def make_security_vertex_id(prefix: str, *parts: str) -> str:
    token = "::".join(part or "" for part in parts)
    digest = hashlib.sha1(f"{prefix}::{token}".encode("utf-8")).hexdigest()[:20]
    return f"security://{prefix}/{digest}"


def default_artifact_security_name(vertex_type: str, path: Path, entity_name: str = "", service_name: str = "", transition_name: str = "") -> str:
    if vertex_type in {"Screen", "ScreenTransition"}:
        component_uri = component_uri_for_path(path)
        if component_uri:
            return f"{component_uri}/{transition_name}" if vertex_type == "ScreenTransition" and transition_name else component_uri
        path_text = str(path)
        return f"{path_text}/{transition_name}" if vertex_type == "ScreenTransition" and transition_name else path_text
    if vertex_type in {"Entity", "ViewEntity"}:
        return entity_name
    if vertex_type == "Service":
        return service_name
    return ""


def synthesize_artifact_authz_id(authz: dict) -> str:
    base = "|".join([
        authz.get("artifactGroupId", ""),
        authz.get("userGroupId", ""),
        authz.get("authzTypeEnumId", ""),
        authz.get("authzActionEnumId", ""),
        authz.get("authzServiceName", ""),
    ])
    digest = hashlib.sha1(base.encode("utf-8")).hexdigest()[:16]
    return authz.get("artifactAuthzId") or f"ArtifactAuthz-{digest}"


def add_vertex(vertices: dict, vertex_id: str, **fields) -> None:
    entry = dict(fields)
    entry["vertexId"] = vertex_id
    entry.setdefault("label", vertex_id.split("://", 1)[-1])
    vertices[vertex_id] = entry


def add_edge(edges: list, edge_type: str, from_id: str, to_id: str, **fields) -> None:
    edge_id = fields.pop("edgeId", f"{edge_type.lower()}::{from_id}::{to_id}")
    row = {
        "edgeId": edge_id,
        "edgeType": edge_type,
        "fromVertexId": from_id,
        "toVertexId": to_id,
        "isDirected": True,
    }
    row.update(fields)
    edges.append(row)


def find_service_name(service_el: ET.Element) -> str:
    verb = service_el.get("verb", "").strip()
    noun = service_el.get("noun", "").strip()
    if verb or noun:
        return f"{verb}#{noun}".strip("#")
    return service_el.get("name", "").strip()


def split_service_name(service_name: str) -> tuple[str, str]:
    if "#" in service_name:
        verb, noun = service_name.split("#", 1)
        return verb.strip(), noun.strip()
    return "", service_name.strip()


def add_xsd_element_usage(vertices: dict, edges: list, source_id: str, element_name: str, xsd_meta: dict) -> None:
    for schema_name in xsd_meta.get("schemaNames", []):
        element_id = f"xsd-element://{schema_name}#{element_name}"
        add_vertex(
            vertices,
            element_id,
            vertexType="XsdElement",
            label=element_name,
            sourceArtifactUri=schema_name,
            sourceArtifactKind="xsd",
            fieldName=element_name,
        )
        add_edge(
            edges,
            "ARTIFACT_USES_XSD_ELEMENT",
            source_id,
            element_id,
            role=element_name,
            schemaName=schema_name,
        )


def infer_entity_edge_type(artifact_kind: str, source_id: str, tag_name: str, attr_name: str) -> str:
    if artifact_kind == "entity" and tag_name == "relationship" and attr_name == "related":
        return "ENTITY_RELATES_TO_ENTITY"
    if source_id.startswith("widget://"):
        return "WIDGET_USES_ENTITY"
    if source_id.startswith("service://"):
        if tag_name in ENTITY_READ_TAGS:
            return "SERVICE_READS_ENTITY"
        if tag_name in ENTITY_WRITE_TAGS:
            return "SERVICE_WRITES_ENTITY"
        return "SERVICE_USES_ENTITY"
    if source_id.startswith("screen://"):
        return "SCREEN_USES_ENTITY"
    if source_id.startswith("transition://"):
        return "TRANSITION_USES_ENTITY"
    return "ARTIFACT_USES_ENTITY"


def infer_service_edge_type(source_id: str, tag_name: str) -> str:
    if source_id.startswith("widget://"):
        return "WIDGET_CALLS_SERVICE"
    if source_id.startswith("transition://"):
        return "TRANSITION_CALLS_SERVICE"
    if source_id.startswith("service://") and tag_name == "implements":
        return "SERVICE_IMPLEMENTS_SERVICE"
    if source_id.startswith("service://"):
        return "SERVICE_CALLS_SERVICE"
    return "ARTIFACT_USES_SERVICE"


def infer_transition_edge_type(source_id: str) -> str:
    if source_id.startswith("widget://"):
        return "WIDGET_USES_TRANSITION"
    if source_id.startswith("form://"):
        return "FORM_USES_TRANSITION"
    if source_id.startswith("screen://"):
        return "SCREEN_USES_TRANSITION"
    return "ARTIFACT_USES_TRANSITION"


def infer_location_edge_type(source_id: str, tag_name: str) -> str:
    if source_id.startswith("screen://") and tag_name == "subscreens-item":
        return "SCREEN_INCLUDES_SUBSCREEN"
    if source_id.startswith("widget://"):
        return "WIDGET_REFERENCES_LOCATION"
    return "ARTIFACT_REFERENCES_LOCATION"


def infer_screen_edge_type(source_id: str, attr_name: str) -> str:
    if source_id.startswith("widget://"):
        return "WIDGET_USES_SCREEN"
    if source_id.startswith("form://"):
        return "FORM_USES_SCREEN"
    return "ARTIFACT_USES_SCREEN"


def infer_form_edge_type(source_id: str) -> str:
    if source_id.startswith("widget://"):
        return "WIDGET_REFERENCES_FORM"
    if source_id.startswith("form://"):
        return "FORM_REFERENCES_FORM"
    return "ARTIFACT_REFERENCES_FORM"


def current_source_id_for_element(path: Path, artifact_kind: str, artifact_root_id: str, element: ET.Element) -> str:
    tag_name = local_name(element.tag)
    if artifact_kind == "screen":
        if tag_name == "transition":
            name = element.get("name", "").strip()
            if name:
                return f"transition://{path}#{name}"
        if tag_name in FORM_TAGS:
            name = element.get("name", "").strip() or element.get("id", "").strip()
            if name:
                return f"form://{path}#{name}"
    if artifact_kind == "entity" and tag_name in {"entity", "view-entity"}:
        package = element.get("package", "").strip()
        name = element.get("entity-name", "").strip()
        if name:
            return f"entity://{f'{package}.{name}'.strip('.')}"
    if artifact_kind == "service" and tag_name == "service":
        service_name = find_service_name(element)
        if service_name:
            return f"service://{service_name}"
    return artifact_root_id


def add_target_vertex(vertices: dict, target_id: str, vertex_type: str, label: str, path: Path, artifact_kind: str, **fields) -> None:
    add_vertex(
        vertices,
        target_id,
        vertexType=vertex_type,
        label=label,
        sourceArtifactUri=str(path),
        sourceArtifactKind=artifact_kind,
        **fields,
    )


def widget_vertex_id(path: Path, element_path: str, tag_name: str) -> str:
    digest = hashlib.sha1(f"{path}::{element_path}::{tag_name}".encode("utf-8")).hexdigest()[:16]
    return f"widget://{path}#{tag_name}:{digest}"


def maybe_create_widget_vertex(path: Path, element: ET.Element, element_path: str, parent_source_id: str, artifact_kind: str,
                               vertices: dict, edges: list) -> str | None:
    tag_name = local_name(element.tag)
    widget_type = ACTIONABLE_WIDGET_TYPES.get(tag_name)
    if not widget_type:
        return None
    widget_id = widget_vertex_id(path, element_path, tag_name)
    label = element.get("name", "").strip() or element.get("id", "").strip() or element.get("text", "").strip() or tag_name
    add_vertex(
        vertices,
        widget_id,
        vertexType=widget_type,
        label=label,
        sourceArtifactUri=str(path),
        sourceArtifactKind=artifact_kind,
        sourceScreenPath=str(path),
        fieldName=element.get("name", "").strip() or None,
    )
    edge_type = "FORM_HAS_WIDGET" if parent_source_id.startswith("form://") else "SCREEN_HAS_WIDGET"
    add_edge(edges, edge_type, parent_source_id, widget_id, role=tag_name)
    return widget_id


def apply_xsd_relations(path: Path, root: ET.Element, artifact_root_id: str, artifact_kind: str, vertices: dict, edges: list,
                        xsd_registry: dict[str, dict]) -> None:
    def walk(element: ET.Element, current_source_id: str, element_path: str) -> None:
        tag_name = local_name(element.tag)
        xsd_meta = xsd_registry.get(tag_name)
        source_id = current_source_id_for_element(path, artifact_kind, artifact_root_id, element)
        if source_id == artifact_root_id:
            source_id = current_source_id
        widget_source_id = maybe_create_widget_vertex(path, element, element_path, current_source_id, artifact_kind, vertices, edges)
        if widget_source_id:
            source_id = widget_source_id
        if xsd_meta:
            add_xsd_element_usage(vertices, edges, source_id, tag_name, xsd_meta)

            for attr_name in xsd_meta.get("relationAttributes", []):
                value = (element.get(attr_name) or "").strip()
                if not value:
                    continue

                if attr_name in {"entity-name", "related", "entity", "validate-entity"}:
                    target_id = f"entity://{value}"
                    add_target_vertex(vertices, target_id, "Entity", value.split(".")[-1], path, artifact_kind, entityName=value)
                    add_edge(edges, infer_entity_edge_type(artifact_kind, source_id, tag_name, attr_name), source_id, target_id,
                             entityName=value, role=attr_name)
                elif attr_name in {"service", "name", "service-name", "validate-service"} and (
                        tag_name in SERVICE_REF_TAGS or attr_name in {"service-name", "validate-service"}):
                    target_id = f"service://{value}"
                    add_target_vertex(vertices, target_id, "Service", value.split(".")[-1], path, artifact_kind, serviceName=value)
                    add_edge(edges, infer_service_edge_type(source_id, tag_name), source_id, target_id, serviceName=value, role=attr_name)
                elif attr_name in TRANSITION_ATTRS and artifact_kind == "screen":
                    target_id = f"transition://{path}#{value}"
                    add_target_vertex(vertices, target_id, "ScreenTransition", value, path, artifact_kind, sourceScreenPath=str(path))
                    add_edge(edges, infer_transition_edge_type(source_id), source_id, target_id, role=attr_name)
                elif attr_name == "location":
                    target_id = f"screen://{value}" if value.endswith(".xml") else f"artifact://location/{value}"
                    vertex_type = "Screen" if value.endswith(".xml") else "Artifact"
                    add_target_vertex(vertices, target_id, vertex_type, value.split("/")[-1] or value, path, artifact_kind,
                                      sourceScreenPath=value if value.endswith(".xml") else None)
                    add_edge(edges, infer_location_edge_type(source_id, tag_name), source_id, target_id, role=attr_name)
                elif attr_name in {"relationship-name", "relationship"}:
                    target_id = f"relationship://{value}"
                    add_target_vertex(vertices, target_id, "Relationship", value, path, artifact_kind)
                    add_edge(edges, "ARTIFACT_USES_RELATIONSHIP", source_id, target_id, role=attr_name)
                elif attr_name in {"screen-path", "target-screen"}:
                    target_id = f"screen://{value}"
                    add_target_vertex(vertices, target_id, "Screen", value.split("/")[-1] or value, path, artifact_kind, sourceScreenPath=value)
                    add_edge(edges, infer_screen_edge_type(source_id, attr_name), source_id, target_id, role=attr_name)
                elif attr_name == "owner-form":
                    target_id = f"form://{path}#{value}"
                    add_target_vertex(vertices, target_id, "Form", value, path, artifact_kind, sourceScreenPath=str(path))
                    add_edge(edges, infer_form_edge_type(source_id), source_id, target_id, role=attr_name)

        child_counts: dict[str, int] = {}
        for child in list(element):
            child_tag = local_name(child.tag)
            child_counts[child_tag] = child_counts.get(child_tag, 0) + 1
            child_path = f"{element_path}/{child_tag}[{child_counts[child_tag]}]"
            walk(child, source_id, child_path)

    root_tag = local_name(root.tag) or "root"
    walk(root, artifact_root_id, f"/{root_tag}[1]")


def parse_view_entity_file(path: Path, vertices: dict, edges: list, xsd_registry: dict[str, dict]) -> None:
    root = ET.parse(path).getroot()
    for view_entity in root.findall(".//view-entity"):
        package = view_entity.get("package", "").strip()
        name = view_entity.get("entity-name", "").strip()
        if not name:
            continue
        entity_name = f"{package}.{name}".strip(".")
        view_id = f"view-entity://{entity_name}"
        add_vertex(vertices, view_id, vertexType="ViewEntity", entityName=entity_name, sourceArtifactUri=str(path), sourceArtifactKind="entity")
        vertices[view_id]["securityArtifactName"] = default_artifact_security_name("ViewEntity", path, entity_name=entity_name)
        vertices[view_id]["securityArtifactTypeEnumId"] = ARTIFACT_TYPE_BY_VERTEX_TYPE["ViewEntity"]

        member_alias_map: dict[str, str] = {}
        for member in view_entity.findall("./member-entity"):
            member_entity_name = member.get("entity-name", "").strip()
            member_alias = member.get("entity-alias", "").strip()
            if not member_entity_name:
                continue
            member_id = f"entity://{member_entity_name}"
            add_vertex(vertices, member_id, vertexType="Entity", entityName=member_entity_name, sourceArtifactUri=str(path), sourceArtifactKind="entity")
            add_edge(edges, "VIEW_ENTITY_HAS_MEMBER", view_id, member_id, entityName=member_entity_name, role=member_alias)
            if member_alias:
                member_alias_map[member_alias] = member_entity_name

        for member_rel in view_entity.findall("./member-relationship"):
            relationship_name = member_rel.get("relationship", "").strip()
            entity_alias = member_rel.get("entity-alias", "").strip()
            join_from_alias = member_rel.get("join-from-alias", "").strip()
            rel_vertex_id = f"relationship://{relationship_name}"
            add_vertex(vertices, rel_vertex_id, vertexType="Relationship", label=relationship_name, sourceArtifactUri=str(path), sourceArtifactKind="entity")
            add_edge(edges, "VIEW_ENTITY_USES_RELATIONSHIP", view_id, rel_vertex_id, role=entity_alias or join_from_alias)
            if entity_alias and entity_alias in member_alias_map:
                target_entity = member_alias_map[entity_alias]
                add_edge(edges, "VIEW_ENTITY_HAS_MEMBER", view_id, f"entity://{target_entity}", entityName=target_entity, role=entity_alias)

        for alias in view_entity.findall("./alias"):
            alias_name = alias.get("name", "").strip()
            alias_entity_alias = alias.get("entity-alias", "").strip()
            alias_field = alias.get("field", "").strip()
            if not alias_name:
                continue
            alias_id = f"field://{entity_name}.{alias_name}"
            add_vertex(vertices, alias_id, vertexType="ViewEntityField", entityName=entity_name, fieldName=alias_name,
                       sourceArtifactUri=str(path), sourceArtifactKind="entity")
            add_edge(edges, "VIEW_ENTITY_HAS_ALIAS", view_id, alias_id, fieldName=alias_name, role=alias_entity_alias or alias_field)

            target_entity_name = member_alias_map.get(alias_entity_alias)
            if target_entity_name and alias_field:
                source_field_id = f"field://{target_entity_name}.{alias_field}"
                add_vertex(vertices, source_field_id, vertexType="EntityField", entityName=target_entity_name, fieldName=alias_field,
                           sourceArtifactUri=str(path), sourceArtifactKind="entity")
                add_edge(edges, "VIEW_ENTITY_ALIASES_FIELD", alias_id, source_field_id, entityName=target_entity_name, fieldName=alias_field)

        apply_xsd_relations(path, view_entity, view_id, "entity", vertices, edges, xsd_registry)


def parse_entity_file(path: Path, vertices: dict, edges: list, xsd_registry: dict[str, dict]) -> None:
    root = ET.parse(path).getroot()
    for entity in root.findall(".//entity"):
        package = entity.get("package", "").strip()
        name = entity.get("entity-name", "").strip()
        if not name:
            continue
        entity_name = f"{package}.{name}".strip(".")
        entity_id = f"entity://{entity_name}"
        add_vertex(vertices, entity_id, vertexType="Entity", entityName=entity_name, sourceArtifactUri=str(path), sourceArtifactKind="entity")
        vertices[entity_id]["securityArtifactName"] = default_artifact_security_name("Entity", path, entity_name=entity_name)
        vertices[entity_id]["securityArtifactTypeEnumId"] = ARTIFACT_TYPE_BY_VERTEX_TYPE["Entity"]
        for field in entity.findall("./field"):
            field_name = field.get("name", "").strip()
            if not field_name:
                continue
            field_id = f"field://{entity_name}.{field_name}"
            add_vertex(vertices, field_id, vertexType="EntityField", entityName=entity_name, fieldName=field_name, sourceArtifactUri=str(path), sourceArtifactKind="entity")
            add_edge(edges, "ENTITY_HAS_FIELD", entity_id, field_id, entityName=entity_name, fieldName=field_name)
        for rel in entity.findall("./relationship"):
            related = rel.get("related", "").strip()
            if not related:
                continue
            related_id = f"entity://{related}"
            add_vertex(vertices, related_id, vertexType="Entity", entityName=related, sourceArtifactUri=str(path), sourceArtifactKind="entity")
            add_edge(
                edges,
                "ENTITY_RELATES_TO_ENTITY",
                entity_id,
                related_id,
                entityName=entity_name,
                role=rel.get("type", "").strip(),
                edgeId=f"entity-rel::{entity_name}::{rel.get('short-alias','')}::{related}",
            )
        apply_xsd_relations(path, entity, entity_id, "entity", vertices, edges, xsd_registry)
    parse_view_entity_file(path, vertices, edges, xsd_registry)


def parse_service_file(path: Path, vertices: dict, edges: list, xsd_registry: dict[str, dict]) -> None:
    root = ET.parse(path).getroot()
    for service in root.findall(".//service"):
        service_name = find_service_name(service)
        if not service_name:
            continue
        service_verb, service_noun = split_service_name(service_name)
        service_id = f"service://{service_name}"
        add_vertex(
            vertices,
            service_id,
            vertexType="Service",
            serviceName=service_name,
            serviceVerb=service_verb,
            serviceNoun=service_noun,
            sourceArtifactUri=str(path),
            sourceArtifactKind="service",
            securityArtifactName=default_artifact_security_name("Service", path, service_name=service_name),
            securityArtifactTypeEnumId=ARTIFACT_TYPE_BY_VERTEX_TYPE["Service"],
        )
        for tag_name, edge_type in (("in-parameters", "SERVICE_HAS_IN_PARAMETER"), ("out-parameters", "SERVICE_HAS_OUT_PARAMETER")):
            block = service.find(f"./{tag_name}")
            if block is None:
                continue
            for param in block.findall("./parameter"):
                param_name = param.get("name", "").strip()
                if not param_name:
                    continue
                param_id = f"service-param://{service_name}/{tag_name}/{param_name}"
                add_vertex(vertices, param_id, vertexType="ServiceParameter", serviceName=service_name, fieldName=param_name, sourceArtifactUri=str(path), sourceArtifactKind="service")
                add_edge(edges, edge_type, service_id, param_id, serviceName=service_name, fieldName=param_name)
        for impl in service.findall("./implements"):
            impl_service = impl.get("service", "").strip()
            if not impl_service:
                continue
            impl_id = f"service://{impl_service}"
            add_vertex(vertices, impl_id, vertexType="Service", serviceName=impl_service, sourceArtifactUri=str(path), sourceArtifactKind="service")
            add_edge(edges, "SERVICE_IMPLEMENTS_SERVICE", service_id, impl_id, serviceName=service_name)
        for auto in service.findall(".//auto-parameters"):
            entity_name = auto.get("entity-name", "").strip()
            if not entity_name:
                continue
            entity_id = f"entity://{entity_name}"
            add_vertex(vertices, entity_id, vertexType="Entity", entityName=entity_name, sourceArtifactUri=str(path), sourceArtifactKind="service")
            add_edge(edges, "SERVICE_READS_ENTITY", service_id, entity_id, serviceName=service_name, entityName=entity_name)
        for action in service.findall(".//service-call"):
            called = action.get("name", "").strip()
            if not called:
                continue
            called_id = f"service://{called}"
            add_vertex(vertices, called_id, vertexType="Service", serviceName=called, sourceArtifactUri=str(path), sourceArtifactKind="service")
            add_edge(edges, "SERVICE_CALLS_SERVICE", service_id, called_id, serviceName=service_name)
        for tag_name, edge_type in (("entity-find", "SERVICE_READS_ENTITY"), ("entity-find-one", "SERVICE_READS_ENTITY"), ("entity-find-count", "SERVICE_READS_ENTITY"), ("entity-create", "SERVICE_WRITES_ENTITY"), ("entity-update", "SERVICE_WRITES_ENTITY"), ("entity-delete", "SERVICE_WRITES_ENTITY")):
            for action in service.findall(f".//{tag_name}"):
                entity_name = action.get("entity-name", "").strip()
                if not entity_name:
                    continue
                entity_id = f"entity://{entity_name}"
                add_vertex(vertices, entity_id, vertexType="Entity", entityName=entity_name, sourceArtifactUri=str(path), sourceArtifactKind="service")
                add_edge(edges, edge_type, service_id, entity_id, serviceName=service_name, entityName=entity_name)
        apply_xsd_relations(path, service, service_id, "service", vertices, edges, xsd_registry)


def parse_screen_file(path: Path, vertices: dict, edges: list, xsd_registry: dict[str, dict]) -> None:
    root = ET.parse(path).getroot()
    screen_id = f"screen://{path}"
    security_screen_name = default_artifact_security_name("Screen", path)
    add_vertex(vertices, screen_id, vertexType="Screen", sourceArtifactUri=str(path), sourceArtifactKind="screen", sourceScreenPath=str(path),
               securityArtifactName=security_screen_name, securityArtifactTypeEnumId=ARTIFACT_TYPE_BY_VERTEX_TYPE["Screen"])
    for subscreen in root.findall(".//subscreens-item"):
        location = subscreen.get("location", "").strip()
        if not location:
            continue
        subscreen_id = f"screen://{location}"
        add_vertex(vertices, subscreen_id, vertexType="Screen", sourceArtifactUri=location, sourceArtifactKind="screen", sourceScreenPath=location)
        add_edge(edges, "SCREEN_INCLUDES_SUBSCREEN", screen_id, subscreen_id, role="subscreen")
    for transition in root.findall(".//transition"):
        name = transition.get("name", "").strip()
        if not name:
            continue
        transition_id = f"transition://{path}#{name}"
        add_vertex(vertices, transition_id, vertexType="ScreenTransition", sourceArtifactUri=str(path), sourceArtifactKind="screen", sourceScreenPath=str(path),
                   securityArtifactName=default_artifact_security_name("ScreenTransition", path, transition_name=name),
                   securityArtifactTypeEnumId=ARTIFACT_TYPE_BY_VERTEX_TYPE["ScreenTransition"])
        add_edge(edges, "SCREEN_HAS_TRANSITION", screen_id, transition_id, role=name)
        for service_call in transition.findall(".//service-call"):
            called = service_call.get("name", "").strip()
            if not called:
                continue
            service_id = f"service://{called}"
            add_vertex(vertices, service_id, vertexType="Service", serviceName=called, sourceArtifactUri=str(path), sourceArtifactKind="screen")
            add_edge(edges, "TRANSITION_CALLS_SERVICE", transition_id, service_id, serviceName=called)
    for form in root.findall(".//form-single") + root.findall(".//form-list") + root.findall(".//form-list-column"):
        name = form.get("name", "").strip() or form.get("id", "").strip()
        if not name:
            continue
        form_id = f"form://{path}#{name}"
        add_vertex(vertices, form_id, vertexType="Form", sourceArtifactUri=str(path), sourceArtifactKind="screen", sourceScreenPath=str(path))
        add_edge(edges, "SCREEN_HAS_FORM", screen_id, form_id, role=name)
    apply_xsd_relations(path, root, screen_id, "screen", vertices, edges, xsd_registry)


def parse_xsd_dir(xsd_dir: Path, vertices: dict, edges: list, xsd_registry: dict[str, dict]) -> None:
    xs_ns = {"xs": "http://www.w3.org/2001/XMLSchema"}
    semantics_map = load_semantics()
    for path in sorted(xsd_dir.glob("*.xsd")):
        grammar = extract_action_grammar(path) if path.name == "xml-actions-3.xsd" else {}
        schema_id = f"xsd://{path.name}"
        add_vertex(vertices, schema_id, vertexType="XsdSchema", sourceArtifactUri=str(path), sourceArtifactKind="xsd")
        root = ET.parse(path).getroot()
        for element in root.findall(".//xs:element", xs_ns):
            name = element.get("name", "").strip()
            if not name:
                continue
            element_id = f"xsd-element://{path.name}#{name}"
            xsd_meta = xsd_registry.get(name, {})
            grammar_meta = grammar.get(name, {})
            semantics = infer_semantics(name, semantics_map) if path.name == "xml-actions-3.xsd" else {}
            add_vertex(
                vertices,
                element_id,
                vertexType="XsdElement",
                sourceArtifactUri=str(path),
                sourceArtifactKind="xsd",
                xmlActionElement=name if path.name == "xml-actions-3.xsd" else None,
                statementClass=semantics.get("statementClass"),
                operation=semantics.get("operationEffect"),
                grammarAttributes=",".join(grammar_meta.get("attributes", [])),
                grammarChildren=",".join(grammar_meta.get("children", [])),
                grammarSubstitutionGroup=grammar_meta.get("substitutionGroup"),
                fieldName=name,
                relationshipName=",".join(xsd_meta.get("relationAttributes", [])),
            )
            add_edge(edges, "SCHEMA_DEFINES_ELEMENT", schema_id, element_id, role=name)


def extract_security_seed_records(scan_root: Path) -> dict[str, list[dict] | dict[str, dict]]:
    records = {
        "artifactGroups": {},
        "artifactGroupMembers": [],
        "artifactAuthz": [],
        "artifactAuthzFilters": [],
        "entityFilterSets": {},
        "entityFilters": [],
        "artifactTarpits": [],
    }

    def walk(element: ET.Element, path: Path, current_group_id: str | None = None,
             current_authz_id: str | None = None, current_filter_set_id: str | None = None) -> None:
        tag = canonical_data_tag(element.tag)
        attrs = {key: (value or "").strip() for key, value in element.attrib.items()}
        next_group_id = current_group_id
        next_authz_id = current_authz_id
        next_filter_set_id = current_filter_set_id

        if tag == "ArtifactGroup":
            artifact_group_id = attrs.get("artifactGroupId", "")
            if artifact_group_id:
                records["artifactGroups"][artifact_group_id] = {
                    "artifactGroupId": artifact_group_id,
                    "description": attrs.get("description", ""),
                    "sourceArtifactUri": str(path),
                }
                next_group_id = artifact_group_id
        elif tag == "ArtifactGroupMember":
            artifact_group_id = attrs.get("artifactGroupId") or current_group_id or ""
            if artifact_group_id and attrs.get("artifactName") and attrs.get("artifactTypeEnumId"):
                records["artifactGroupMembers"].append({
                    "artifactGroupId": artifact_group_id,
                    "artifactName": attrs.get("artifactName", ""),
                    "artifactTypeEnumId": attrs.get("artifactTypeEnumId", ""),
                    "nameIsPattern": attrs.get("nameIsPattern", ""),
                    "inheritAuthz": attrs.get("inheritAuthz", ""),
                    "filterMap": attrs.get("filterMap", ""),
                    "sourceArtifactUri": str(path),
                })
        elif tag == "ArtifactAuthz":
            artifact_group_id = attrs.get("artifactGroupId") or current_group_id or ""
            if artifact_group_id and attrs.get("userGroupId"):
                authz = {
                    "artifactAuthzId": attrs.get("artifactAuthzId", ""),
                    "artifactGroupId": artifact_group_id,
                    "userGroupId": attrs.get("userGroupId", ""),
                    "authzTypeEnumId": attrs.get("authzTypeEnumId", ""),
                    "authzActionEnumId": attrs.get("authzActionEnumId", ""),
                    "authzServiceName": attrs.get("authzServiceName", ""),
                    "sourceArtifactUri": str(path),
                }
                authz["artifactAuthzId"] = synthesize_artifact_authz_id(authz)
                records["artifactAuthz"].append(authz)
                next_authz_id = authz["artifactAuthzId"]
        elif tag == "ArtifactAuthzFilter":
            artifact_authz_id = attrs.get("artifactAuthzId") or current_authz_id or ""
            entity_filter_set_id = attrs.get("entityFilterSetId", "")
            if artifact_authz_id and entity_filter_set_id:
                records["artifactAuthzFilters"].append({
                    "artifactAuthzId": artifact_authz_id,
                    "entityFilterSetId": entity_filter_set_id,
                    "applyCond": attrs.get("applyCond", ""),
                    "sourceArtifactUri": str(path),
                })
        elif tag == "EntityFilterSet":
            entity_filter_set_id = attrs.get("entityFilterSetId", "")
            if entity_filter_set_id:
                records["entityFilterSets"][entity_filter_set_id] = {
                    "entityFilterSetId": entity_filter_set_id,
                    "description": attrs.get("description", ""),
                    "applyCond": attrs.get("applyCond", ""),
                    "allowMissingAlias": attrs.get("allowMissingAlias", ""),
                    "sourceArtifactUri": str(path),
                }
                next_filter_set_id = entity_filter_set_id
        elif tag == "EntityFilter":
            entity_filter_set_id = attrs.get("entityFilterSetId") or current_filter_set_id or ""
            entity_filter_id = attrs.get("entityFilterId", "")
            if entity_filter_set_id and entity_filter_id:
                records["entityFilters"].append({
                    "entityFilterId": entity_filter_id,
                    "entityFilterSetId": entity_filter_set_id,
                    "entityName": attrs.get("entityName", ""),
                    "filterMap": attrs.get("filterMap", ""),
                    "comparisonEnumId": attrs.get("comparisonEnumId", ""),
                    "joinOr": attrs.get("joinOr", ""),
                    "sourceArtifactUri": str(path),
                })
        elif tag == "ArtifactTarpit":
            artifact_group_id = attrs.get("artifactGroupId") or current_group_id or ""
            if artifact_group_id and attrs.get("userGroupId"):
                records["artifactTarpits"].append({
                    "artifactGroupId": artifact_group_id,
                    "userGroupId": attrs.get("userGroupId", ""),
                    "maxHitsCount": attrs.get("maxHitsCount", ""),
                    "maxHitsDuration": attrs.get("maxHitsDuration", ""),
                    "tarpitDuration": attrs.get("tarpitDuration", ""),
                    "sourceArtifactUri": str(path),
                })

        if tag == "artifactGroups":
            next_group_id = attrs.get("artifactGroupId") or current_group_id
            if attrs.get("artifactGroupId"):
                records["artifactGroups"][attrs["artifactGroupId"]] = {
                    "artifactGroupId": attrs["artifactGroupId"],
                    "description": attrs.get("description", ""),
                    "sourceArtifactUri": str(path),
                }
        elif tag == "artifacts":
            artifact_group_id = attrs.get("artifactGroupId") or current_group_id or ""
            if artifact_group_id and attrs.get("artifactName") and attrs.get("artifactTypeEnumId"):
                records["artifactGroupMembers"].append({
                    "artifactGroupId": artifact_group_id,
                    "artifactName": attrs.get("artifactName", ""),
                    "artifactTypeEnumId": attrs.get("artifactTypeEnumId", ""),
                    "nameIsPattern": attrs.get("nameIsPattern", ""),
                    "inheritAuthz": attrs.get("inheritAuthz", ""),
                    "filterMap": attrs.get("filterMap", ""),
                    "sourceArtifactUri": str(path),
                })
        elif tag == "authz":
            artifact_group_id = attrs.get("artifactGroupId") or current_group_id or ""
            if artifact_group_id and attrs.get("authzTypeEnumId") and attrs.get("authzActionEnumId"):
                authz = {
                    "artifactAuthzId": attrs.get("artifactAuthzId", ""),
                    "artifactGroupId": artifact_group_id,
                    "userGroupId": attrs.get("userGroupId", ""),
                    "authzTypeEnumId": attrs.get("authzTypeEnumId", ""),
                    "authzActionEnumId": attrs.get("authzActionEnumId", ""),
                    "authzServiceName": attrs.get("authzServiceName", ""),
                    "sourceArtifactUri": str(path),
                }
                authz["artifactAuthzId"] = synthesize_artifact_authz_id(authz)
                records["artifactAuthz"].append(authz)
                next_authz_id = authz["artifactAuthzId"]
        elif tag == "filters":
            if current_authz_id and attrs.get("entityFilterSetId"):
                records["artifactAuthzFilters"].append({
                    "artifactAuthzId": current_authz_id,
                    "entityFilterSetId": attrs.get("entityFilterSetId", ""),
                    "applyCond": attrs.get("applyCond", ""),
                    "sourceArtifactUri": str(path),
                })
            elif current_filter_set_id and attrs.get("entityFilterId"):
                records["entityFilters"].append({
                    "entityFilterId": attrs.get("entityFilterId", ""),
                    "entityFilterSetId": current_filter_set_id,
                    "entityName": attrs.get("entityName", ""),
                    "filterMap": attrs.get("filterMap", ""),
                    "comparisonEnumId": attrs.get("comparisonEnumId", ""),
                    "joinOr": attrs.get("joinOr", ""),
                    "sourceArtifactUri": str(path),
                })

        for child in list(element):
            walk(child, path, next_group_id, next_authz_id, next_filter_set_id)

    for path in sorted(scan_root.rglob("*.xml")):
        if "data" not in set(path.parts):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        walk(root, path)

    deduped = {}
    for member in records["artifactGroupMembers"]:
        key = (
            member["artifactGroupId"],
            member["artifactName"],
            member["artifactTypeEnumId"],
            member.get("nameIsPattern", ""),
            member.get("inheritAuthz", ""),
            member.get("filterMap", ""),
        )
        deduped[key] = member
    records["artifactGroupMembers"] = list(deduped.values())

    deduped = {}
    for authz in records["artifactAuthz"]:
        key = (
            authz["artifactAuthzId"],
            authz["artifactGroupId"],
            authz["userGroupId"],
            authz["authzTypeEnumId"],
            authz["authzActionEnumId"],
            authz.get("authzServiceName", ""),
        )
        deduped[key] = authz
    records["artifactAuthz"] = list(deduped.values())

    deduped = {}
    for authz_filter in records["artifactAuthzFilters"]:
        key = (
            authz_filter["artifactAuthzId"],
            authz_filter["entityFilterSetId"],
            authz_filter.get("applyCond", ""),
        )
        deduped[key] = authz_filter
    records["artifactAuthzFilters"] = list(deduped.values())

    deduped = {}
    for entity_filter in records["entityFilters"]:
        key = (
            entity_filter["entityFilterId"],
            entity_filter["entityFilterSetId"],
            entity_filter.get("entityName", ""),
            entity_filter.get("filterMap", ""),
            entity_filter.get("comparisonEnumId", ""),
            entity_filter.get("joinOr", ""),
        )
        deduped[key] = entity_filter
    records["entityFilters"] = list(deduped.values())

    deduped = {}
    for tarpit in records["artifactTarpits"]:
        key = (
            tarpit["artifactGroupId"],
            tarpit["userGroupId"],
            tarpit.get("maxHitsCount", ""),
            tarpit.get("maxHitsDuration", ""),
            tarpit.get("tarpitDuration", ""),
        )
        deduped[key] = tarpit
    records["artifactTarpits"] = list(deduped.values())
    return records


def add_security_graph(scan_root: Path, vertices: dict, edges: list) -> dict[str, int]:
    records = extract_security_seed_records(scan_root)
    artifact_vertices: list[dict] = []
    for vertex_id, row in vertices.items():
        vertex_type = row.get("vertexType", "")
        artifact_type = row.get("securityArtifactTypeEnumId") or ARTIFACT_TYPE_BY_VERTEX_TYPE.get(vertex_type, "")
        artifact_name = row.get("securityArtifactName", "")
        if vertex_type in SECURITY_ARTIFACT_VERTEX_TYPES and artifact_type and artifact_name:
            artifact_vertices.append({
                "vertexId": vertex_id,
                "artifactTypeEnumId": artifact_type,
                "artifactName": artifact_name,
            })

    artifact_groups = records["artifactGroups"]
    for artifact_group in artifact_groups.values():
        group_vertex_id = make_security_vertex_id("artifact-group", artifact_group["artifactGroupId"])
        add_vertex(
            vertices,
            group_vertex_id,
            vertexType="SecurityArtifactGroup",
            label=artifact_group["artifactGroupId"],
            sourceArtifactUri=artifact_group.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            artifactGroupId=artifact_group["artifactGroupId"],
            artifactGroupDescription=artifact_group.get("description", ""),
        )

    for member in records["artifactGroupMembers"]:
        member_vertex_id = make_security_vertex_id(
            "artifact-group-member",
            member["artifactGroupId"],
            member["artifactTypeEnumId"],
            member["artifactName"],
        )
        add_vertex(
            vertices,
            member_vertex_id,
            vertexType="SecurityArtifactGroupMember",
            label=member["artifactName"],
            sourceArtifactUri=member.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            securityArtifactName=member["artifactName"],
            securityArtifactTypeEnumId=member["artifactTypeEnumId"],
            artifactGroupId=member["artifactGroupId"],
            nameIsPattern=member.get("nameIsPattern", ""),
            inheritAuthz=member.get("inheritAuthz", ""),
            filterMap=member.get("filterMap", ""),
        )
        add_edge(
            edges,
            "AUTHZ_MEMBER_IN_GROUP",
            member_vertex_id,
            make_security_vertex_id("artifact-group", member["artifactGroupId"]),
            role=member["artifactTypeEnumId"],
        )
        pattern = member["artifactName"]
        is_pattern = member.get("nameIsPattern") == "Y"
        regex = re.compile(pattern) if is_pattern else None
        for artifact_vertex in artifact_vertices:
            if artifact_vertex["artifactTypeEnumId"] != member["artifactTypeEnumId"]:
                continue
            matched = bool(regex.fullmatch(artifact_vertex["artifactName"])) if regex else artifact_vertex["artifactName"] == pattern
            if not matched:
                continue
            add_edge(
                edges,
                "ARTIFACT_MATCHES_AUTHZ_MEMBER",
                artifact_vertex["vertexId"],
                member_vertex_id,
                role="pattern" if is_pattern else "exact",
            )

    for authz in records["artifactAuthz"]:
        authz_vertex_id = make_security_vertex_id("artifact-authz", authz["artifactAuthzId"])
        add_vertex(
            vertices,
            authz_vertex_id,
            vertexType="SecurityArtifactAuthz",
            label=authz["artifactAuthzId"],
            sourceArtifactUri=authz.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            artifactAuthzId=authz["artifactAuthzId"],
            artifactGroupId=authz["artifactGroupId"],
            userGroupId=authz.get("userGroupId", ""),
            authzTypeEnumId=authz.get("authzTypeEnumId", ""),
            authzActionEnumId=authz.get("authzActionEnumId", ""),
            authzServiceName=authz.get("authzServiceName", ""),
        )
        add_edge(
            edges,
            "ARTIFACT_GROUP_HAS_AUTHZ",
            make_security_vertex_id("artifact-group", authz["artifactGroupId"]),
            authz_vertex_id,
            role=authz.get("authzActionEnumId", ""),
        )

    for authz_filter in records["artifactAuthzFilters"]:
        authz_filter_vertex_id = make_security_vertex_id(
            "artifact-authz-filter",
            authz_filter["artifactAuthzId"],
            authz_filter["entityFilterSetId"],
        )
        add_vertex(
            vertices,
            authz_filter_vertex_id,
            vertexType="SecurityArtifactAuthzFilter",
            label=authz_filter["entityFilterSetId"],
            sourceArtifactUri=authz_filter.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            artifactAuthzId=authz_filter["artifactAuthzId"],
            entityFilterSetId=authz_filter["entityFilterSetId"],
            applyCond=authz_filter.get("applyCond", ""),
        )
        add_edge(
            edges,
            "ARTIFACT_AUTHZ_HAS_FILTER",
            make_security_vertex_id("artifact-authz", authz_filter["artifactAuthzId"]),
            authz_filter_vertex_id,
            role=authz_filter.get("applyCond", ""),
        )
        add_edge(
            edges,
            "AUTHZ_FILTER_USES_FILTER_SET",
            authz_filter_vertex_id,
            make_security_vertex_id("entity-filter-set", authz_filter["entityFilterSetId"]),
            role=authz_filter["entityFilterSetId"],
        )

    for filter_set in records["entityFilterSets"].values():
        filter_set_vertex_id = make_security_vertex_id("entity-filter-set", filter_set["entityFilterSetId"])
        add_vertex(
            vertices,
            filter_set_vertex_id,
            vertexType="SecurityEntityFilterSet",
            label=filter_set["entityFilterSetId"],
            sourceArtifactUri=filter_set.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            entityFilterSetId=filter_set["entityFilterSetId"],
            artifactGroupDescription=filter_set.get("description", ""),
            applyCond=filter_set.get("applyCond", ""),
            allowMissingAlias=filter_set.get("allowMissingAlias", ""),
        )

    for entity_filter in records["entityFilters"]:
        entity_filter_vertex_id = make_security_vertex_id("entity-filter", entity_filter["entityFilterId"])
        add_vertex(
            vertices,
            entity_filter_vertex_id,
            vertexType="SecurityEntityFilter",
            label=entity_filter["entityFilterId"],
            sourceArtifactUri=entity_filter.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            entityFilterId=entity_filter["entityFilterId"],
            entityFilterSetId=entity_filter["entityFilterSetId"],
            entityName=entity_filter.get("entityName", ""),
            filterMap=entity_filter.get("filterMap", ""),
            comparisonEnumId=entity_filter.get("comparisonEnumId", ""),
            joinOr=entity_filter.get("joinOr", ""),
        )
        add_edge(
            edges,
            "ENTITY_FILTER_SET_HAS_FILTER",
            make_security_vertex_id("entity-filter-set", entity_filter["entityFilterSetId"]),
            entity_filter_vertex_id,
            role=entity_filter.get("comparisonEnumId", ""),
        )
        if entity_filter.get("entityName"):
            target_entity_id = f"entity://{entity_filter['entityName']}"
            add_vertex(vertices, target_entity_id, vertexType="Entity", entityName=entity_filter["entityName"],
                       sourceArtifactUri=entity_filter.get("sourceArtifactUri", ""), sourceArtifactKind="security",
                       securityArtifactName=entity_filter["entityName"], securityArtifactTypeEnumId=ARTIFACT_TYPE_BY_VERTEX_TYPE["Entity"])
            add_edge(
                edges,
                "ENTITY_FILTER_APPLIES_TO_ENTITY",
                entity_filter_vertex_id,
                target_entity_id,
                entityName=entity_filter["entityName"],
            )

    for tarpit in records["artifactTarpits"]:
        tarpit_vertex_id = make_security_vertex_id("artifact-tarpit", tarpit["artifactGroupId"], tarpit["userGroupId"])
        add_vertex(
            vertices,
            tarpit_vertex_id,
            vertexType="SecurityArtifactTarpit",
            label=f"{tarpit['artifactGroupId']}:{tarpit['userGroupId']}",
            sourceArtifactUri=tarpit.get("sourceArtifactUri", ""),
            sourceArtifactKind="security",
            artifactGroupId=tarpit["artifactGroupId"],
            userGroupId=tarpit["userGroupId"],
            maxHitsCount=tarpit.get("maxHitsCount", ""),
            maxHitsDuration=tarpit.get("maxHitsDuration", ""),
            tarpitDuration=tarpit.get("tarpitDuration", ""),
        )
        add_edge(
            edges,
            "ARTIFACT_GROUP_HAS_TARPIT",
            make_security_vertex_id("artifact-group", tarpit["artifactGroupId"]),
            tarpit_vertex_id,
            role=tarpit["userGroupId"],
        )

    return {
        "artifactGroupCount": len(records["artifactGroups"]),
        "artifactGroupMemberCount": len(records["artifactGroupMembers"]),
        "artifactAuthzCount": len(records["artifactAuthz"]),
        "artifactAuthzFilterCount": len(records["artifactAuthzFilters"]),
        "entityFilterSetCount": len(records["entityFilterSets"]),
        "entityFilterCount": len(records["entityFilters"]),
        "artifactTarpitCount": len(records["artifactTarpits"]),
    }


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=True))
            handle.write("\n")


def merge_vertex_rows(base_rows: list[dict], extra_rows: list[dict]) -> list[dict]:
    merged: dict[str, dict] = {}
    for row in base_rows + extra_rows:
        vertex_id = row.get("vertexId")
        if not vertex_id:
            continue
        current = merged.get(vertex_id)
        if not current:
            merged[vertex_id] = dict(row)
            continue
        for key, value in row.items():
            if key == "vertexId":
                continue
            if value not in (None, "", [], {}):
                current[key] = value
    return sorted(merged.values(), key=lambda row: row["vertexId"])


def merge_edge_rows(base_rows: list[dict], extra_rows: list[dict]) -> list[dict]:
    merged: dict[tuple[str, str, str, str], dict] = {}
    for row in base_rows + extra_rows:
        key = (
            row.get("fromVertexId", ""),
            row.get("toVertexId", ""),
            row.get("edgeType", ""),
            row.get("label", ""),
        )
        current = merged.get(key)
        if not current:
            merged[key] = dict(row)
            continue
        for field, value in row.items():
            if value not in (None, "", [], {}):
                current[field] = value
    return sorted(merged.values(), key=lambda row: row["edgeId"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scan-root", required=True)
    parser.add_argument("--xsd-dir", required=True)
    parser.add_argument("--graph-id", default="AgentArtifactGraph")
    parser.add_argument("--service-action-statements", default="", help="Path to service action statement documents JSONL")
    parser.add_argument("--service-action-documents", default="", help="Path to service action aggregate documents JSONL")
    parser.add_argument("--screen-docs", default="", help="Optional screen prompt documents JSONL")
    parser.add_argument("--knowledge-docs", action="append", default=[], help="Optional knowledge document JSONL; may be repeated")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    scan_root = Path(args.scan_root)
    xsd_dir = Path(args.xsd_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    xsd_registry = extract_xsd_registry(xsd_dir)
    vertices: dict[str, dict] = {}
    edges: list[dict] = []
    for path in sorted(scan_root.rglob("*.xml")):
        parts = set(path.parts)
        try:
            if "entity" in parts:
                parse_entity_file(path, vertices, edges, xsd_registry)
            elif "service" in parts:
                parse_service_file(path, vertices, edges, xsd_registry)
            elif "screen" in parts:
                parse_screen_file(path, vertices, edges, xsd_registry)
        except ET.ParseError:
            continue
    parse_xsd_dir(xsd_dir, vertices, edges, xsd_registry)
    security_summary = add_security_graph(scan_root, vertices, edges)

    vertex_rows = sorted(vertices.values(), key=lambda row: row["vertexId"])
    edge_rows = sorted(edges, key=lambda row: row["edgeId"])

    service_statement_docs = load_jsonl(Path(args.service_action_statements)) if args.service_action_statements else []
    service_docs = load_jsonl(Path(args.service_action_documents)) if args.service_action_documents else []
    screen_docs = load_jsonl(Path(args.screen_docs)) if args.screen_docs else []
    knowledge_docs: list[dict] = []
    for value in args.knowledge_docs:
        knowledge_docs.extend(load_jsonl(Path(value)))

    if service_statement_docs or service_docs or screen_docs or knowledge_docs:
        semantic_vertex_rows, semantic_edge_rows, semantic_summary = build_graph(
            screen_docs,
            service_statement_docs,
            service_docs,
            knowledge_docs,
            graph_id=args.graph_id,
        )
        vertex_rows = merge_vertex_rows(vertex_rows, semantic_vertex_rows)
        edge_rows = merge_edge_rows(edge_rows, semantic_edge_rows)
    else:
        semantic_summary = {}

    write_jsonl(output_dir / "global-artifact-graph-vertices.jsonl", vertex_rows)
    write_jsonl(output_dir / "global-artifact-graph-edges.jsonl", edge_rows)
    summary = {
        "graphId": args.graph_id,
        "vertexCount": len(vertex_rows),
        "edgeCount": len(edge_rows),
        "schemaVersion": "artifact-graph-v3",
        "scanRoot": str(scan_root),
        "xsdDir": str(xsd_dir),
        "xsdElementRegistryCount": len(xsd_registry),
        "semanticSummary": semantic_summary,
        "securitySummary": security_summary,
    }
    (output_dir / "global-artifact-graph-summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
