#!/usr/bin/env python3
"""Convert graph JSONL files to Moqui entity-facade-xml seed data."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape

def hash_id(graph_id: str, raw_id: str) -> str:
    return hashlib.sha1(f"{graph_id}|{raw_id}".encode("utf-8")).hexdigest()


def jsonl_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def attr(name: str, value: object | None) -> str:
    if value is None or value == "":
        return ""
    return f' {name}="{escape(str(value))}"'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vertices", required=True)
    parser.add_argument("--edges", required=True)
    parser.add_argument("--graph-id", default="AgentArtifactGraph")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    vertices = jsonl_rows(Path(args.vertices))
    edges = jsonl_rows(Path(args.edges))
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<entity-facade-xml type="seed">']
    for vertex in vertices:
        raw_id = vertex.get("vertexId") or vertex.get("graphVertexId")
        if not raw_id:
            continue
        graph_vertex_id = hash_id(args.graph_id, str(raw_id))
        line = (
            f'    <moqui.math.GraphVertex graphId="{escape(args.graph_id)}"'
            f' graphVertexId="{graph_vertex_id}"'
            f'{attr("label", vertex.get("label"))}'
            '/>'
        )
        lines.append(line)
    for edge in edges:
        raw_id = edge.get("edgeId")
        raw_from = edge.get("fromVertexId")
        raw_to = edge.get("toVertexId")
        if not raw_id or not raw_from or not raw_to:
            continue
        graph_edge_id = hash_id(args.graph_id, str(raw_id))
        line = (
            f'    <moqui.math.GraphEdge graphId="{escape(args.graph_id)}"'
            f' graphEdgeId="{graph_edge_id}"'
            f' fromVertexId="{hash_id(args.graph_id, str(raw_from))}"'
            f' toVertexId="{hash_id(args.graph_id, str(raw_to))}"'
            f'{attr("label", edge.get("label") or edge.get("edgeType"))}'
            f'{attr("edgeTypeEnumId", "GetDirected" if edge.get("isDirected", True) else "GetUndirected")}'
            '/>'
        )
        lines.append(line)
    lines.append("</entity-facade-xml>")
    Path(args.output).write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
