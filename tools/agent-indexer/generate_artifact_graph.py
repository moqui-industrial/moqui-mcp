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
import hashlib
import json
from collections import Counter
from pathlib import Path

DEFAULT_GRAPH_ID = "AgentArtifactGraph"
DEFAULT_MATH_MODEL_ID = "AgentMoquiRagModel_v1"
DEFAULT_MODEL_DEF_ID = "AgentArtifactGraphGenerationModelDef"
DEFAULT_SCHEMA_VERSION = "artifact-graph-v1"


def now_utc() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_jsonl(path: Path | None) -> list[dict]:
    if path is None or not path.exists():
        return []
    rows: list[dict] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def screen_uri(source_screen_path: str) -> str:
    return f"screen://{source_screen_path}"


def transition_uri(source_screen_path: str, transition_name: str) -> str:
    return f"transition://{source_screen_path}#{transition_name}"


def service_uri(service_name: str) -> str:
    return f"service://{service_name}"


def entity_uri(entity_name: str) -> str:
    return f"entity://{entity_name}"


def field_uri(field_name: str, entity_name: str | None = None) -> str:
    return f"field://{entity_name}.{field_name}" if entity_name else f"field://{field_name}"


def document_vertex_type(document_kind: str) -> str:
    if document_kind == "test_workflow_story":
        return "TestCase"
    if document_kind == "seed_scenario":
        return "SeedRecord"
    return "AgentDocument"


def make_edge_id(from_vertex_id: str, to_vertex_id: str, edge_type: str, label: str | None = None) -> str:
    payload = f"{from_vertex_id}|{to_vertex_id}|{edge_type}|{label or ''}"
    return f"graph-edge://{hashlib.sha1(payload.encode('utf-8')).hexdigest()[:24]}"


def add_vertex(vertices: dict[str, dict], vertex_id: str, vertex_type: str, label: str, **extra) -> None:
    vertex = vertices.setdefault(
        vertex_id,
        {
            "vertexId": vertex_id,
            "vertexType": vertex_type,
            "label": label,
        },
    )
    if label and not vertex.get("label"):
        vertex["label"] = label
    if vertex_type and vertex.get("vertexType") in (None, "", "AgentDocument"):
        vertex["vertexType"] = vertex_type
    for key, value in extra.items():
        if value not in (None, "", [], {}):
            vertex[key] = value


def add_edge(edges: dict[tuple[str, str, str, str], dict], from_vertex_id: str, to_vertex_id: str,
        edge_type: str, label: str | None = None, **extra) -> None:
    key = (from_vertex_id, to_vertex_id, edge_type, label or "")
    if key in edges:
        return
    edge = {
        "edgeId": make_edge_id(from_vertex_id, to_vertex_id, edge_type, label),
        "fromVertexId": from_vertex_id,
        "toVertexId": to_vertex_id,
        "edgeType": edge_type,
        "label": label or edge_type,
    }
    for name, value in extra.items():
        if value not in (None, "", [], {}):
            edge[name] = value
    edges[key] = edge


def choose_entity_for_field(field_name: str, entities: list[str]) -> str | None:
    if not entities:
        return None
    if len(entities) == 1:
        return entities[0]
    lowered = field_name.lower()
    for entity_name in entities:
        entity_tail = entity_name.split(".")[-1].lower()
        if entity_tail in lowered or lowered in entity_tail:
            return entity_name
    return None


def add_service_entity_edges(vertices: dict[str, dict], edges: dict[tuple[str, str, str, str], dict],
        service_vertex_id: str, entity_names: list[str], edge_type: str) -> None:
    for entity_name in sorted({name for name in entity_names if name}):
        entity_vertex_id = entity_uri(entity_name)
        add_vertex(vertices, entity_vertex_id, "Entity", entity_name.split(".")[-1], entityName=entity_name)
        add_edge(edges, service_vertex_id, entity_vertex_id, edge_type, label=edge_type, entityName=entity_name)


def add_service_field_edges(vertices: dict[str, dict], edges: dict[tuple[str, str, str, str], dict],
        service_vertex_id: str, field_names: list[str], read_entities: list[str], written_entities: list[str]) -> None:
    entity_candidates = sorted({*read_entities, *written_entities})
    for field_name in sorted({name for name in field_names if name}):
        matched_entity = choose_entity_for_field(field_name, entity_candidates)
        field_vertex_id = field_uri(field_name, matched_entity)
        add_vertex(
            vertices,
            field_vertex_id,
            "EntityField",
            field_name,
            fieldName=field_name,
            entityName=matched_entity,
        )
        add_edge(
            edges,
            service_vertex_id,
            field_vertex_id,
            "SERVICE_USES_FIELD",
            label="SERVICE_USES_FIELD",
            entityName=matched_entity,
            fieldName=field_name,
        )


def add_service_field_edges_from_statements(vertices: dict[str, dict], edges: dict[tuple[str, str, str, str], dict],
        service_vertex_id: str, statement_docs: list[dict], read_entities: list[str], written_entities: list[str]) -> None:
    entity_candidates = sorted({*read_entities, *written_entities})
    generic_complement_names = {
        "condition", "value", "from", "to", "entry", "list", "name", "field", "map", "location"
    }
    seen_field_pairs: set[tuple[str, str | None]] = set()

    for statement_doc in statement_docs:
        statement_entity = statement_doc.get("subject") if statement_doc.get("subjectKind") == "entity" else None
        for complement in statement_doc.get("complements", []):
            field_name = complement.get("name")
            role = complement.get("role")
            if not field_name or field_name in generic_complement_names:
                continue
            if complement.get("kind") != "field" and role not in {
                "lookup_key", "updated_field", "created_field", "condition_field"
            }:
                continue
            matched_entity = statement_entity or choose_entity_for_field(field_name, entity_candidates)
            pair = (field_name, matched_entity)
            if pair in seen_field_pairs:
                continue
            seen_field_pairs.add(pair)
            field_vertex_id = field_uri(field_name, matched_entity)
            add_vertex(
                vertices,
                field_vertex_id,
                "EntityField",
                field_name,
                fieldName=field_name,
                entityName=matched_entity,
                role=role,
            )
            add_edge(
                edges,
                service_vertex_id,
                field_vertex_id,
                "SERVICE_USES_FIELD",
                label="SERVICE_USES_FIELD",
                entityName=matched_entity,
                fieldName=field_name,
                role=role,
            )


def build_graph(screen_docs: list[dict], service_statement_docs: list[dict], service_docs: list[dict],
        knowledge_docs: list[dict], graph_id: str = DEFAULT_GRAPH_ID, math_model_id: str = DEFAULT_MATH_MODEL_ID,
        model_def_id: str = DEFAULT_MODEL_DEF_ID, schema_version: str = DEFAULT_SCHEMA_VERSION) -> tuple[list[dict], list[dict], dict]:
    vertices: dict[str, dict] = {}
    edges: dict[tuple[str, str, str, str], dict] = {}

    service_doc_map = {doc["serviceName"]: doc for doc in service_docs if doc.get("serviceName")}
    statement_map = {doc["statementId"]: doc for doc in service_statement_docs if doc.get("statementId")}
    statements_by_service: dict[str, list[dict]] = {}
    for statement_doc in service_statement_docs:
        service_name = statement_doc.get("serviceName")
        if service_name:
            statements_by_service.setdefault(service_name, []).append(statement_doc)

    for doc in service_docs:
        service_name = doc.get("serviceName")
        if not service_name:
            continue
        service_vertex_id = service_uri(service_name)
        add_vertex(
            vertices,
            service_vertex_id,
            "Service",
            service_name.split(".")[-1],
            serviceName=service_name,
            sourceArtifactUri=doc.get("sourceArtifactUri"),
        )
        for statement_id in doc.get("statementIds", []):
            statement_doc = statement_map.get(statement_id)
            statement_label = statement_doc.get("statementVerb") if statement_doc else statement_id.rsplit("/", 1)[-1]
            add_vertex(
                vertices,
                statement_id,
                "XmlAction",
                statement_label,
                serviceName=service_name,
                statementPath=statement_doc.get("statementPath") if statement_doc else None,
                sourceArtifactUri=(statement_doc.get("sourceFile") or statement_doc.get("sourceArtifactUri")) if statement_doc else None,
                sourceArtifactKind="service",
                xmlActionElement=statement_doc.get("statementVerb") if statement_doc else statement_label,
                domainObject=statement_doc.get("domainObject") if statement_doc else None,
                subject=statement_doc.get("subject") if statement_doc else None,
                subjectKind=statement_doc.get("subjectKind") if statement_doc else None,
                operationEffect=statement_doc.get("operationEffect") if statement_doc else None,
                calledService=statement_doc.get("calledService") if statement_doc else None,
            )
            add_edge(edges, service_vertex_id, statement_id, "SERVICE_HAS_STATEMENT", label="SERVICE_HAS_STATEMENT")
        add_service_entity_edges(vertices, edges, service_vertex_id, doc.get("readEntities", []), "SERVICE_READS_ENTITY")
        add_service_entity_edges(vertices, edges, service_vertex_id, doc.get("writtenEntities", []), "SERVICE_WRITES_ENTITY")
        add_service_field_edges_from_statements(
            vertices,
            edges,
            service_vertex_id,
            statements_by_service.get(service_name, []),
            doc.get("readEntities", []),
            doc.get("writtenEntities", []),
        )
        if not any(edge_key[2] == "SERVICE_USES_FIELD" and edge_key[0] == service_vertex_id for edge_key in edges.keys()):
            add_service_field_edges(
                vertices,
                edges,
                service_vertex_id,
                doc.get("serviceComplements", []),
                doc.get("readEntities", []),
                doc.get("writtenEntities", []),
            )
        for called_service in doc.get("calledServices", []):
            called_vertex_id = service_uri(called_service)
            add_vertex(vertices, called_vertex_id, "Service", called_service.split(".")[-1], serviceName=called_service)
            add_edge(edges, service_vertex_id, called_vertex_id, "SERVICE_CALLS_SERVICE", label="SERVICE_CALLS_SERVICE")

    for statement_doc in service_statement_docs:
        statement_id = statement_doc.get("statementId")
        if not statement_id:
            continue
        add_vertex(
            vertices,
            statement_id,
            "XmlAction",
            statement_doc.get("statementVerb", statement_id),
            serviceName=statement_doc.get("serviceName"),
            statementPath=statement_doc.get("statementPath"),
            sourceArtifactUri=statement_doc.get("sourceFile") or statement_doc.get("sourceArtifactUri"),
            sourceArtifactKind="service",
            xmlActionElement=statement_doc.get("statementVerb"),
            domainObject=statement_doc.get("domainObject"),
            subject=statement_doc.get("subject"),
            subjectKind=statement_doc.get("subjectKind"),
            operationEffect=statement_doc.get("operationEffect"),
            calledService=statement_doc.get("calledService"),
        )
        subject_kind = statement_doc.get("subjectKind")
        subject = statement_doc.get("subject")
        if subject_kind == "entity" and subject:
            entity_vertex_id = entity_uri(subject)
            add_vertex(vertices, entity_vertex_id, "Entity", subject.split(".")[-1], entityName=subject)
        for complement in statement_doc.get("complements", []):
            field_name = complement.get("name")
            if not field_name:
                continue
            matched_entity = statement_doc.get("subject") if statement_doc.get("subjectKind") == "entity" else None
            field_vertex_id = field_uri(field_name, matched_entity)
            add_vertex(
                vertices,
                field_vertex_id,
                "EntityField",
                field_name,
                fieldName=field_name,
                entityName=matched_entity,
                role=complement.get("role"),
            )
            add_edge(
                edges,
                statement_id,
                field_vertex_id,
                "STATEMENT_HAS_COMPLEMENT",
                label=complement.get("role") or "STATEMENT_HAS_COMPLEMENT",
                role=complement.get("role"),
            )

    for doc in screen_docs:
        doc_id = doc.get("documentId")
        if not doc_id:
            continue
        add_vertex(
            vertices,
            doc_id,
            "AgentDocument",
            doc.get("canonicalPrompt", doc_id),
            documentKind=doc.get("documentKind"),
            area=doc.get("area"),
            domainObject=doc.get("domainObject"),
        )
        source_screen_path = doc.get("sourceScreenPath")
        if source_screen_path:
            screen_vertex_id = screen_uri(source_screen_path)
            add_vertex(vertices, screen_vertex_id, "Screen", doc.get("screenName", source_screen_path), sourceScreenPath=source_screen_path)
            add_edge(edges, doc_id, screen_vertex_id, "AGENT_DOCUMENT_DERIVED_FROM", label="AGENT_DOCUMENT_DERIVED_FROM")

            transition_names = doc.get("transitionNames", [])
            for transition_name in transition_names:
                transition_vertex_id = transition_uri(source_screen_path, transition_name)
                add_vertex(vertices, transition_vertex_id, "ScreenTransition", transition_name, sourceScreenPath=source_screen_path)
                add_edge(edges, screen_vertex_id, transition_vertex_id, "SCREEN_HAS_TRANSITION", label="SCREEN_HAS_TRANSITION")
                add_edge(edges, doc_id, transition_vertex_id, "AGENT_DOCUMENT_DERIVED_FROM", label="AGENT_DOCUMENT_DERIVED_FROM")
                for service_name in doc.get("boundServices", []):
                    service_vertex_id = service_uri(service_name)
                    add_vertex(vertices, service_vertex_id, "Service", service_name.split(".")[-1], serviceName=service_name)
                    add_edge(edges, transition_vertex_id, service_vertex_id, "TRANSITION_CALLS_SERVICE", label="TRANSITION_CALLS_SERVICE")

            action_edge_type = "SCREEN_EDITS_FIELD" if doc.get("mutative") else "SCREEN_DISPLAYS_FIELD"
            for detail in doc.get("fieldLabelDetails", []):
                field_name = detail.get("name")
                entity_name = detail.get("entityName")
                if not field_name:
                    continue
                field_vertex_id = field_uri(field_name, entity_name)
                add_vertex(
                    vertices,
                    field_vertex_id,
                    "EntityField",
                    field_name,
                    fieldName=field_name,
                    entityName=entity_name,
                )
                add_edge(
                    edges,
                    screen_vertex_id,
                    field_vertex_id,
                    action_edge_type,
                    label=action_edge_type,
                    fieldName=field_name,
                    entityName=entity_name,
                )

        for statement_id in doc.get("linkedServiceStatements", []):
            statement_doc = statement_map.get(statement_id)
            add_vertex(
                vertices,
                statement_id,
                "XmlAction",
                (statement_doc.get("statementVerb") if statement_doc else None) or statement_id.rsplit("/", 1)[-1],
                serviceName=statement_doc.get("serviceName") if statement_doc else None,
                statementPath=statement_doc.get("statementPath") if statement_doc else None,
                sourceArtifactUri=(statement_doc.get("sourceFile") or statement_doc.get("sourceArtifactUri")) if statement_doc else None,
                sourceArtifactKind="service",
                xmlActionElement=statement_doc.get("statementVerb") if statement_doc else None,
                domainObject=statement_doc.get("domainObject") if statement_doc else None,
                subject=statement_doc.get("subject") if statement_doc else None,
                subjectKind=statement_doc.get("subjectKind") if statement_doc else None,
                operationEffect=statement_doc.get("operationEffect") if statement_doc else None,
                calledService=statement_doc.get("calledService") if statement_doc else None,
            )
            add_edge(edges, doc_id, statement_id, "AGENT_DOCUMENT_DERIVED_FROM", label="AGENT_DOCUMENT_DERIVED_FROM")

    for doc in knowledge_docs:
        doc_id = doc.get("documentId")
        if not doc_id:
            continue
        add_vertex(
            vertices,
            doc_id,
            document_vertex_type(doc.get("documentKind", "")),
            doc.get("canonicalPrompt", doc_id),
            documentKind=doc.get("documentKind"),
            area=doc.get("area"),
            domainObject=doc.get("domainObject"),
        )
        for entity_name in doc.get("relatedEntities", []):
            entity_vertex_id = entity_uri(entity_name)
            add_vertex(vertices, entity_vertex_id, "Entity", entity_name.split(".")[-1], entityName=entity_name)
            add_edge(edges, doc_id, entity_vertex_id, "AGENT_DOCUMENT_DERIVED_FROM", label="AGENT_DOCUMENT_DERIVED_FROM")
        for prompt_id in doc.get("relatedAgentPrompts", []):
            add_vertex(vertices, prompt_id, "AgentDocument", prompt_id)
            add_edge(edges, doc_id, prompt_id, "AGENT_DOCUMENT_DERIVED_FROM", label="AGENT_DOCUMENT_DERIVED_FROM")

    vertex_rows = sorted(vertices.values(), key=lambda item: (item.get("vertexType", ""), item.get("vertexId", "")))
    edge_rows = sorted(edges.values(), key=lambda item: (item.get("edgeType", ""), item.get("fromVertexId", ""), item.get("toVertexId", "")))
    vertex_type_counts = Counter(vertex.get("vertexType", "Unknown") for vertex in vertex_rows)
    edge_type_counts = Counter(edge.get("edgeType", "UNKNOWN") for edge in edge_rows)
    connected_vertex_ids: set[str] = set()
    for edge in edge_rows:
        from_vertex_id = edge.get("fromVertexId")
        to_vertex_id = edge.get("toVertexId")
        if from_vertex_id:
            connected_vertex_ids.add(from_vertex_id)
        if to_vertex_id:
            connected_vertex_ids.add(to_vertex_id)
    orphan_vertices = [
        vertex["vertexId"]
        for vertex in vertex_rows
        if vertex["vertexId"] not in connected_vertex_ids
    ]
    summary = {
        "graphId": graph_id,
        "mathModelId": math_model_id,
        "modelDefId": model_def_id,
        "generator": "generate_artifact_graph.py",
        "schemaVersion": schema_version,
        "generatedAt": now_utc(),
        "vertexCount": len(vertex_rows),
        "edgeCount": len(edge_rows),
        "vertexTypeCounts": dict(sorted(vertex_type_counts.items())),
        "edgeTypeCounts": dict(sorted(edge_type_counts.items())),
        "orphanVertexCount": len(orphan_vertices),
        "orphanVertexSample": orphan_vertices[:20],
    }
    return vertex_rows, edge_rows, summary


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate offline artifact graph vertices/edges from agent documents")
    parser.add_argument("--screen-docs", default="", help="Path to screen prompt documents JSONL")
    parser.add_argument("--service-action-statements", default="", help="Path to service action statement documents JSONL")
    parser.add_argument("--service-action-documents", default="", help="Path to service action aggregate documents JSONL")
    parser.add_argument("--knowledge-docs", action="append", default=[], help="Path to knowledge document JSONL; may be repeated")
    parser.add_argument("--graph-id", default=DEFAULT_GRAPH_ID, help="Logical graph id")
    parser.add_argument("--math-model-id", default=DEFAULT_MATH_MODEL_ID, help="MathModel id for summary metadata")
    parser.add_argument("--model-def-id", default=DEFAULT_MODEL_DEF_ID, help="MathModelDef id for summary metadata")
    parser.add_argument("--schema-version", default=DEFAULT_SCHEMA_VERSION, help="Graph schema version")
    parser.add_argument("--output-dir", required=True, help="Output directory for graph JSONL files")
    args = parser.parse_args()

    screen_docs = load_jsonl(Path(args.screen_docs)) if args.screen_docs else []
    service_statement_docs = load_jsonl(Path(args.service_action_statements)) if args.service_action_statements else []
    service_docs = load_jsonl(Path(args.service_action_documents)) if args.service_action_documents else []
    knowledge_docs: list[dict] = []
    for value in args.knowledge_docs:
        knowledge_docs.extend(load_jsonl(Path(value)))

    vertex_rows, edge_rows, summary = build_graph(
        screen_docs,
        service_statement_docs,
        service_docs,
        knowledge_docs,
        args.graph_id,
        args.math_model_id,
        args.model_def_id,
        args.schema_version,
    )

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "global-artifact-graph-vertices.jsonl", vertex_rows)
    write_jsonl(output_dir / "global-artifact-graph-edges.jsonl", edge_rows)
    (output_dir / "global-artifact-graph-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
