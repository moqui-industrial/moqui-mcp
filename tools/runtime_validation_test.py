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
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def normalize_auth_header(raw_value: str | None) -> str | None:
    if raw_value is None:
        return None
    value = raw_value.strip()
    if value.lower().startswith("authorization:"):
        value = value.split(":", 1)[1].strip()
    return value or None


class JsonRpcServiceClient:
    def __init__(self, endpoint: str, headers: dict[str, str] | None = None) -> None:
        self.endpoint = endpoint
        self.headers = {"Content-Type": "application/json", **(headers or {})}
        self.counter = 0

    def call(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        self.counter += 1
        payload = {"jsonrpc": "2.0", "id": self.counter, "method": method, "params": params or {}}
        req = urllib.request.Request(
            self.endpoint,
            method="POST",
            data=json.dumps(payload).encode("utf-8"),
            headers=self.headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"HTTP {exc.code}: {body}") from exc


def extract_result(response: dict[str, Any]) -> dict[str, Any]:
    if "error" in response:
        raise RuntimeError(json.dumps(response["error"], ensure_ascii=False))
    result = response.get("result", {})
    if not isinstance(result, dict):
        raise RuntimeError(f"Unexpected service result type: {type(result).__name__}")
    return result


def check(results: list[dict[str, Any]], name: str, ok: bool, detail: str) -> None:
    results.append({"name": name, "ok": ok, "detail": detail})


def write_markdown(results: list[dict[str, Any]], target: Path) -> None:
    lines = ["# Runtime Validation", ""]
    for row in results:
        status = "PASS" if row["ok"] else "FAIL"
        lines.append(f"- `{status}` `{row['name']}`: {row['detail']}")
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Run runtime validation for moqui-mcp MathModel/graph/tensor services")
    ap.add_argument("--rpc-endpoint", required=True, help="Moqui JSON-RPC endpoint, for example http://localhost:8080/rpc/json")
    ap.add_argument("--auth-header", default=None)
    ap.add_argument("--math-model-id", default="AgentMoquiRagModel_v1")
    ap.add_argument("--graph-id", default="AgentArtifactGraph")
    ap.add_argument("--vertices-file", required=True)
    ap.add_argument("--edges-file", required=True)
    ap.add_argument("--summary-file", default=None)
    ap.add_argument("--impact-service-uri", default="service://mantle.GeneralServices.lookup#ById")
    ap.add_argument("--index-docs-file", default=None, help="Optional JSONL fixture for index#AgentDocuments mini-run")
    ap.add_argument("--index-name", default="moqui_agent_prompts_v1")
    ap.add_argument("--out-json", required=True)
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()

    headers = {}
    normalized_auth = normalize_auth_header(args.auth_header)
    if normalized_auth:
        headers["Authorization"] = normalized_auth
    client = JsonRpcServiceClient(args.rpc_endpoint, headers=headers)

    results: list[dict[str, Any]] = []
    validation: dict[str, Any] = {"mathModelId": args.math_model_id, "graphId": args.graph_id}

    lineage = extract_result(client.call(
        "org.moqui.agent.AgentMathModelServices.get#AgentModelLineage",
        {"mathModelId": args.math_model_id},
    )).get("lineage", {})
    check(results, "get_agent_model_lineage_initial", bool(lineage.get("mathModel")), f"recentRuns={len(lineage.get('recentRuns', []))}")

    graph_load = extract_result(client.call(
        "org.moqui.agent.AgentDocumentServices.load#ArtifactGraph",
        {
            "mathModelId": args.math_model_id,
            "graphId": args.graph_id,
            "createMathModelRun": True,
            "verticesFilePath": args.vertices_file,
            "edgesFilePath": args.edges_file,
            "summaryFilePath": args.summary_file,
            "clearExisting": True,
        },
    ))
    validation["graphLoad"] = graph_load
    loaded_vertices = int(graph_load.get("loadedVertexCount", 0))
    loaded_edges = int(graph_load.get("loadedEdgeCount", 0))
    check(results, "load_artifact_graph", loaded_vertices > 0 and loaded_edges > 0, f"vertices={loaded_vertices}, edges={loaded_edges}")

    impact = extract_result(client.call(
        "org.moqui.agent.AgentDocumentServices.get#ArtifactImpact",
        {"graphId": args.graph_id, "artifactUri": args.impact_service_uri},
    ))
    validation["artifactImpact"] = impact
    impact_payload = impact.get("artifactImpact", impact) if isinstance(impact, dict) else {}
    impact_ok = any(impact_payload.get(key) for key in ("readEntities", "writtenEntities", "usedFields", "calledServices"))
    check(results, "get_artifact_impact", impact_ok, f"artifactUri={args.impact_service_uri}")

    tensor = extract_result(client.call(
        "org.moqui.agent.AgentMathModelServices.register#AgentEmbeddingTensor",
        {
            "mathModelId": args.math_model_id,
            "mathModelRunId": graph_load.get("mathModelRunId"),
            "indexName": args.index_name,
            "embeddingProvider": "openai",
            "embeddingModel": "text-embedding-3-small",
            "embeddingDimensions": 1536,
            "documentCount": loaded_vertices,
            "contentLocation": f"opensearch://{args.index_name}/embedding",
            "contentFormat": "opensearch-knn",
        },
    ))
    validation["tensorRegistration"] = tensor
    check(results, "register_agent_embedding_tensor", bool(tensor.get("effectiveTensorId")), f"tensorId={tensor.get('effectiveTensorId')}")

    summary = extract_result(client.call(
        "org.moqui.agent.AgentMathModelServices.summarize#AgentToolExecutionLogsToMathModelRun",
        {"mathModelId": args.math_model_id},
    ))
    validation["toolExecutionSummary"] = summary
    check(results, "summarize_agent_tool_execution_logs", bool(summary.get("mathModelRunId")), f"totalExecutions={summary.get('summary', {}).get('totalExecutions')}")

    lineage_after = extract_result(client.call(
        "org.moqui.agent.AgentMathModelServices.get#AgentModelLineage",
        {"mathModelId": args.math_model_id},
    )).get("lineage", {})
    validation["lineageAfter"] = {
        "recentRunsCount": len(lineage_after.get("recentRuns", [])),
        "recentModelDataCount": len(lineage_after.get("recentModelData", [])),
        "tensorContentsCount": len(lineage_after.get("tensorContents", [])),
        "graphContentsCount": len(lineage_after.get("graphContents", [])),
    }
    lineage_ok = bool(lineage_after.get("tensorContents")) and bool(lineage_after.get("graphContents")) and bool(lineage_after.get("runDataGroupedByRun"))
    check(results, "get_agent_model_lineage_enriched", lineage_ok, json.dumps(validation["lineageAfter"], ensure_ascii=False))

    if args.index_docs_file:
        index_result = extract_result(client.call(
            "org.moqui.agent.AgentDocumentServices.index#AgentDocuments",
            {
                "filePath": args.index_docs_file,
                "indexName": args.index_name,
                "forceReindex": False,
                "mathModelId": args.math_model_id,
                "createMathModelRun": True,
            },
        ))
        validation["indexDocuments"] = index_result
        check(results, "index_agent_documents_mini_run", bool(index_result.get("mathModelRunId")), f"mathModelRunId={index_result.get('mathModelRunId')}")
    else:
        check(results, "index_agent_documents_mini_run", True, "skipped: no --index-docs-file provided")

    out_json = Path(args.out_json)
    out_md = Path(args.out_md)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "ok": all(item["ok"] for item in results),
        "results": results,
        "validation": validation,
    }
    out_json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_markdown(results, out_md)

    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")
    if not payload["ok"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
