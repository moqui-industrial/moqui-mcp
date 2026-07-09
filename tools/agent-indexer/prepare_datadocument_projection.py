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
from collections import Counter, defaultdict
from pathlib import Path


DEFAULT_GRAPH_ID = "AgentArtifactGraph"
DEFAULT_MATH_MODEL_ID = "AgentMoquiRagModel_v1"


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


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def projection_document_type(document_kind: str) -> str:
    if document_kind in {"screen_prompt", "screen_query_prompt", "screen_navigation_prompt", "screen_print_prompt", "screen_email_prompt", "screen_validation_prompt", "screen_batch_prompt"}:
        return "AgentArtifactDocument"
    if document_kind in {"service_action_statement", "service_action_document"}:
        return "AgentServiceActionDocument"
    return "AgentArtifactDocument"


def derive_source_artifact_uri(doc: dict) -> str | None:
    if doc.get("sourceArtifactUri"):
        return doc.get("sourceArtifactUri")
    if doc.get("sourceScreenPath"):
        return f"screen://{doc['sourceScreenPath']}"
    if doc.get("serviceName"):
        return f"service://{doc['serviceName']}"
    source_artifacts = doc.get("sourceArtifacts") or []
    if source_artifacts:
        return f"component://{source_artifacts[0]}"
    source_file = doc.get("sourceFile")
    if source_file:
        return f"component://{source_file}"
    return None


def build_graph_indices(vertex_rows: list[dict], edge_rows: list[dict]) -> tuple[dict[str, dict], dict[str, list[str]], dict[str, list[str]]]:
    vertices_by_id = {row["vertexId"]: row for row in vertex_rows if row.get("vertexId")}
    incoming_edges: dict[str, list[str]] = defaultdict(list)
    incoming_vertices: dict[str, list[str]] = defaultdict(list)
    for edge in edge_rows:
        edge_id = edge.get("edgeId")
        to_vertex_id = edge.get("toVertexId")
        from_vertex_id = edge.get("fromVertexId")
        if edge_id and to_vertex_id:
            incoming_edges[to_vertex_id].append(edge_id)
        if from_vertex_id and to_vertex_id:
            incoming_vertices[to_vertex_id].append(from_vertex_id)
    return vertices_by_id, dict(incoming_edges), dict(incoming_vertices)


def project_docs(rows: list[dict], vertices_by_id: dict[str, dict], incoming_edges: dict[str, list[str]],
        incoming_vertices: dict[str, list[str]], graph_id: str, math_model_id: str | None = None,
        math_model_run_id: str | None = None, embedding_tensor_id: str | None = None) -> tuple[list[dict], dict]:
    projected_rows: list[dict] = []
    projection_counts = Counter()

    for row in rows:
        projected = dict(row)
        document_id = projected.get("documentId")
        source_vertex_id = document_id if document_id in vertices_by_id else None
        source_artifact_uri = derive_source_artifact_uri(projected)
        derived_from_vertex_ids = sorted(set(incoming_vertices.get(document_id, []))) if document_id else []
        derived_from_edge_ids = sorted(set(incoming_edges.get(document_id, []))) if document_id else []

        projected["sourceGraphId"] = graph_id
        projected["sourceVertexId"] = source_vertex_id
        projected["sourceArtifactUri"] = source_artifact_uri
        projected["derivedFromVertexIds"] = derived_from_vertex_ids
        projected["derivedFromEdgeIds"] = derived_from_edge_ids
        projected["projectionDocumentType"] = projection_document_type(projected.get("documentKind", ""))
        projected["mathModelId"] = math_model_id
        projected["mathModelRunId"] = math_model_run_id
        projected["embeddingTensorId"] = embedding_tensor_id

        projection_counts[projected["projectionDocumentType"]] += 1
        projected_rows.append(projected)

    summary = {
        "graphId": graph_id,
        "documentCount": len(projected_rows),
        "projectionDocumentTypeCounts": dict(sorted(projection_counts.items())),
        "documentsWithSourceVertex": len([row for row in projected_rows if row.get("sourceVertexId")]),
        "documentsWithDerivedFromVertices": len([row for row in projected_rows if row.get("derivedFromVertexIds")]),
        "documentsWithDerivedFromEdges": len([row for row in projected_rows if row.get("derivedFromEdgeIds")]),
        "mathModelId": math_model_id,
        "mathModelRunId": math_model_run_id,
        "embeddingTensorId": embedding_tensor_id,
    }
    return projected_rows, summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare agent document JSONL for future DataDocument/DataFeed projection")
    parser.add_argument("--docs", action="append", default=[], help="Input JSONL document file; may be repeated")
    parser.add_argument("--graph-vertices", default="", help="Artifact graph vertices JSONL")
    parser.add_argument("--graph-edges", default="", help="Artifact graph edges JSONL")
    parser.add_argument("--graph-id", default=DEFAULT_GRAPH_ID, help="Logical graph id to embed in projected docs")
    parser.add_argument("--math-model-id", default=DEFAULT_MATH_MODEL_ID, help="MathModel id to stamp into projected docs")
    parser.add_argument("--math-model-run-id", default="", help="Optional MathModelRun id to stamp into projected docs")
    parser.add_argument("--embedding-tensor-id", default="", help="Optional Tensor id for the backing embedding matrix")
    parser.add_argument("--output-dir", required=True, help="Directory for projected JSONL outputs")
    args = parser.parse_args()

    vertex_rows = load_jsonl(Path(args.graph_vertices)) if args.graph_vertices else []
    edge_rows = load_jsonl(Path(args.graph_edges)) if args.graph_edges else []
    vertices_by_id, incoming_edges, incoming_vertices = build_graph_indices(vertex_rows, edge_rows)

    all_rows: list[dict] = []
    by_type_rows: dict[str, list[dict]] = defaultdict(list)
    projection_summaries: dict[str, dict] = {}

    for doc_path in args.docs:
        path = Path(doc_path)
        rows = load_jsonl(path)
        projected_rows, summary = project_docs(
            rows,
            vertices_by_id,
            incoming_edges,
            incoming_vertices,
            args.graph_id,
            args.math_model_id,
            args.math_model_run_id or None,
            args.embedding_tensor_id or None,
        )
        output_key = path.stem
        by_type_rows[output_key].extend(projected_rows)
        projection_summaries[output_key] = summary
        all_rows.extend(projected_rows)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    for output_key, rows in sorted(by_type_rows.items()):
        write_jsonl(output_dir / f"{output_key}-projected.jsonl", rows)
    write_jsonl(output_dir / "global-agent-documents-projected.jsonl", all_rows)
    (output_dir / "global-agent-documents-projection-summary.json").write_text(
        json.dumps(
            {
                "graphId": args.graph_id,
                "mathModelId": args.math_model_id,
                "mathModelRunId": args.math_model_run_id or None,
                "embeddingTensorId": args.embedding_tensor_id or None,
                "inputSummaries": projection_summaries,
                "totalDocumentCount": len(all_rows),
            },
            indent=2,
            ensure_ascii=False,
        ) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
