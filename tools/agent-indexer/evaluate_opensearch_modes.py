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
import importlib.util
import json
import sys
import traceback
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_MODES = ["bm25", "vector", "hybrid", "hybrid_rerank"]
OPERATIONALLY_ACCEPTABLE_FAILURE_CLASSES = ("sibling_document_ok", "ambiguous_by_design")


def load_eval_module(script_path: Path):
    spec = importlib.util.spec_from_file_location("screen_prompt_eval", script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load evaluation helpers from {script_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class JsonRpcClient:
    def __init__(self, endpoint: str, headers: dict[str, str] | None = None) -> None:
        self.endpoint = endpoint
        self.headers = headers or {}
        self.counter = 0
        self.session_id: str | None = None

    def call(self, method: str, params: dict[str, Any] | None = None, notification: bool = False) -> dict[str, Any]:
        payload = {"jsonrpc": "2.0", "method": method}
        if not notification:
            self.counter += 1
            payload["id"] = self.counter
        if params is not None:
            payload["params"] = params
        request_headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", **self.headers}
        if self.session_id:
            request_headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(
            self.endpoint,
            method="POST",
            data=json.dumps(payload).encode("utf-8"),
            headers=request_headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                returned_session_id = resp.headers.get("Mcp-Session-Id")
                if returned_session_id:
                    self.session_id = returned_session_id
                body = resp.read().decode("utf-8")
                if not body.strip():
                    return {}
                return json.loads(body)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"HTTP {exc.code}: {body}") from exc


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


def extract_service_result(response: dict[str, Any]) -> dict[str, Any]:
    if "error" in response:
        raise RuntimeError(json.dumps(response["error"], ensure_ascii=False))
    result = response.get("result", {})
    if not isinstance(result, dict):
        raise RuntimeError(f"Unexpected service result type: {type(result).__name__}")
    return result


def normalize_auth_header(raw_value: str | None) -> str | None:
    if raw_value is None:
        return None
    value = raw_value.strip()
    if value.lower().startswith("authorization:"):
        value = value.split(":", 1)[1].strip()
    return value or None


def parse_tool_result(tool_response: dict[str, Any]) -> dict[str, Any]:
    result = tool_response.get("result", tool_response)
    if not isinstance(result, dict):
        raise RuntimeError(f"Unexpected tool response type: {type(result).__name__}")
    if result.get("isError"):
        raise RuntimeError(f"Tool returned error: {result}")
    content = result.get("content", [])
    if not content:
        return result
    first = content[0] if isinstance(content, list) and content else {}
    if isinstance(first, dict) and "text" in first:
        return json.loads(first["text"])
    return result


def retrieve_ranked_ids(client: JsonRpcClient, query_text: str, mode: str, top_k: int) -> list[str]:
    response = client.call(
        "tools/call",
        {
            "name": "moqui_search_agent_prompts",
            "arguments": {
                "queryText": query_text,
                "mode": mode,
                "limit": top_k,
            },
        },
    )
    payload = parse_tool_result(response)
    result_list = payload.get("resultList", []) if isinstance(payload, dict) else []
    ranked_ids = []
    for item in result_list:
        if not isinstance(item, dict):
            continue
        doc_id = item.get("documentId")
        if doc_id:
            ranked_ids.append(doc_id)
    return ranked_ids[:top_k]


def evaluate_runtime(
    docs: list[dict],
    queries: list[dict],
    client: JsonRpcClient,
    mode: str,
    top_k: int,
    evalmod,
) -> dict[str, Any]:
    docs_by_id = {d["documentId"]: d for d in docs}
    total = len(queries)
    hit1 = hit3 = hit5 = 0
    ghit1 = ghit3 = ghit5 = 0
    by_kind = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_effect = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_screen = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_action_kind = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_subarea = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_domain_object = evalmod.defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    failures = []
    failure_causes = evalmod.Counter()
    failure_class_counts = evalmod.Counter()
    mutative_re = evalmod.re.compile(r"\b(create|update|delete|add|remove|set|approve|cancel|complete)\b")

    for q in queries:
        qq = q.get("query", "")
        target = q.get("targetDocumentId")
        d = docs_by_id.get(target, {})
        dk = d.get("documentKind", "unknown")
        de = d.get("operationEffect", "unknown")
        ds = d.get("screenName", "unknown")
        da = d.get("actionKind", "unresolved")
        dsub = d.get("subArea", "unknown")
        ddom = d.get("domainObject", "unknown")

        ranked_ids = retrieve_ranked_ids(client, qq, mode, top_k)
        h1 = int(target in ranked_ids[:1])
        h3 = int(target in ranked_ids[:3])
        h5 = int(target in ranked_ids[:5])
        target_group = q.get("targetPromptGroupId") or d.get("promptGroupId")
        ranked_groups = [docs_by_id.get(rid, {}).get("promptGroupId") for rid in ranked_ids]
        gh1 = int(bool(target_group) and target_group in ranked_groups[:1])
        gh3 = int(bool(target_group) and target_group in ranked_groups[:3])
        gh5 = int(bool(target_group) and target_group in ranked_groups[:5])

        hit1 += h1
        hit3 += h3
        hit5 += h5
        ghit1 += gh1
        ghit3 += gh3
        ghit5 += gh5

        for bucket, key in (
            (by_kind, dk),
            (by_effect, de),
            (by_screen, ds),
            (by_action_kind, da),
            (by_subarea, dsub),
            (by_domain_object, ddom),
        ):
            bucket[key]["n"] += 1
            bucket[key]["h1"] += h1
            bucket[key]["h3"] += h3
            bucket[key]["h5"] += h5
            bucket[key]["gh1"] += gh1
            bucket[key]["gh3"] += gh3
            bucket[key]["gh5"] += gh5

        if not h3:
            qn = evalmod.norm(qq)
            ambiguous = False
            failure_class = "true_miss"
            target_action = d.get("actionKind", "unresolved")
            target_domain = d.get("domainObject", "unknown")
            if qn in {"search", "find", "list", "view"} or "search form" in qn or "record by" in qn:
                failure_causes["generic_query_bad"] += 1
                failure_class = "generic_query_bad"
                ambiguous = True
            elif bool(evalmod.re.search(r"[A-Z]", qq)) or "#" in qq:
                failure_causes["machine_style_query"] += 1
            elif len(evalmod.tokenize(qn)) <= 2:
                failure_causes["query_too_short"] += 1
                failure_class = "query_too_short"
                ambiguous = True
            elif "order detail" in qn:
                failure_causes["order_detail_ambiguity"] += 1
                ambiguous = True
            else:
                failure_causes["other"] += 1

            if dk == "screen_query_prompt" and mutative_re.search(qn):
                for rid in ranked_ids[:5]:
                    rd = docs_by_id.get(rid, {})
                    if rd.get("operationEffect") in {"create", "update", "delete", "status_transition", "batch_update"}:
                        failure_class = "evaluation_target_error"
                        break
            if failure_class == "true_miss" and gh3:
                failure_class = "sibling_document_ok"
            elif failure_class == "true_miss" and not gh3 and gh5:
                failure_class = "group_hit_outside_top3"
            if failure_class == "true_miss" and ranked_ids:
                top_doc = docs_by_id.get(ranked_ids[0], {})
                top_action = top_doc.get("actionKind", "unresolved")
                top_domain = top_doc.get("domainObject", "unknown")
                if target_domain == top_domain and target_action != top_action:
                    failure_class = "action_kind_confusion"
                elif target_action == top_action and target_domain != top_domain:
                    failure_class = "domain_object_confusion"
            if ambiguous and failure_class != "evaluation_target_error":
                failure_class = "ambiguous_by_design"
            failure_class_counts[failure_class] += 1
            failures.append(
                {
                    "query": qq,
                    "targetDocumentId": target,
                    "targetDocumentKind": dk,
                    "targetOperationEffect": de,
                    "targetActionKind": target_action,
                    "targetDomainObject": target_domain,
                    "ambiguousByDesign": ambiguous,
                    "failureClass": failure_class,
                    "groupHitAt3": bool(gh3),
                    "groupHitAt5": bool(gh5),
                    "topRetrieved": ranked_ids[:5],
                }
            )

    def finalize(bucket: dict[str, dict[str, int]]) -> dict[str, dict[str, float]]:
        out = {}
        for key, value in sorted(bucket.items()):
            n = max(value["n"], 1)
            out[key] = {
                "n": value["n"],
                "recallAt1": round(value["h1"] / n, 4),
                "recallAt3": round(value["h3"] / n, 4),
                "recallAt5": round(value["h5"] / n, 4),
                "groupRecallAt1": round(value["gh1"] / n, 4),
                "groupRecallAt3": round(value["gh3"] / n, 4),
                "groupRecallAt5": round(value["gh5"] / n, 4),
            }
        return out

    sq = by_kind.get("screen_query_prompt", {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    return {
        "mode": mode,
        "queries": total,
        "recallAt1": round(hit1 / max(total, 1), 4),
        "recallAt3": round(hit3 / max(total, 1), 4),
        "recallAt5": round(hit5 / max(total, 1), 4),
        "groupRecallAt1": round(ghit1 / max(total, 1), 4),
        "groupRecallAt3": round(ghit3 / max(total, 1), 4),
        "groupRecallAt5": round(ghit5 / max(total, 1), 4),
        "byDocumentKind": finalize(by_kind),
        "byOperationEffect": finalize(by_effect),
        "byActionKind": finalize(by_action_kind),
        "bySubArea": finalize(by_subarea),
        "byDomainObject": finalize(by_domain_object),
        "byScreenName": finalize(by_screen),
        "screenQueryPromptMetrics": finalize({"screen_query_prompt": sq}),
        "failureCauses": dict(sorted(failure_causes.items())),
        "failureClassCounts": dict(sorted(failure_class_counts.items())),
        "ambiguousFailures": len([f for f in failures if f.get("ambiguousByDesign")]),
        "failuresTop3": failures[:200],
        "evaluationSource": "mcp_runtime",
    }


def write_mode_outputs(output_dir: Path, mode: str, result: dict[str, Any], evalmod) -> None:
    out_json = output_dir / f"global-retrieval-opensearch-{mode}.json"
    out_md = output_dir / f"global-retrieval-opensearch-{mode}.md"
    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    evalmod.write_md(out_md, result)


def derive_operational_metrics(result: dict[str, Any]) -> dict[str, Any]:
    query_count = max(int(result.get("queries", 0) or 0), 1)
    failure_class_counts = result.get("failureClassCounts", {}) or {}
    sibling_count = int(failure_class_counts.get("sibling_document_ok", 0) or 0)
    ambiguous_count = int(failure_class_counts.get("ambiguous_by_design", 0) or 0)
    true_miss_count = int(failure_class_counts.get("true_miss", 0) or 0)
    action_confusion_count = int(failure_class_counts.get("action_kind_confusion", 0) or 0)
    domain_confusion_count = int(failure_class_counts.get("domain_object_confusion", 0) or 0)
    acceptable_count = sibling_count + ambiguous_count
    acceptable_rate = acceptable_count / query_count
    ambiguous_rate = ambiguous_count / query_count
    sibling_rate = sibling_count / query_count
    metrics = {
        "strictRecallAt3": result.get("recallAt3"),
        "strictGroupRecallAt3": result.get("groupRecallAt3"),
        "siblingEquivalentRate": round(sibling_rate, 4),
        "ambiguousByDesignRate": round(ambiguous_rate, 4),
        "operationallyAcceptableFailureRate": round(acceptable_rate, 4),
        "operationallyAcceptableFailureCount": acceptable_count,
        "strictTrueMissRate": round(true_miss_count / query_count, 4),
        "strictTrueMissCount": true_miss_count,
        "actionKindConfusionRate": round(action_confusion_count / query_count, 4),
        "actionKindConfusionCount": action_confusion_count,
        "domainObjectConfusionRate": round(domain_confusion_count / query_count, 4),
        "domainObjectConfusionCount": domain_confusion_count,
        "operationalAdjustedRecallAt3": round(min(1.0, float(result.get("recallAt3", 0.0) or 0.0) + acceptable_rate), 4),
        "operationalAdjustedGroupRecallAt3": round(min(1.0, float(result.get("groupRecallAt3", 0.0) or 0.0) + ambiguous_rate), 4),
        "operationallyAcceptableFailureClasses": list(OPERATIONALLY_ACCEPTABLE_FAILURE_CLASSES),
    }
    return metrics


def main() -> None:
    ap = argparse.ArgumentParser(description="Run runtime retrieval evaluation across bm25/vector/hybrid modes via MCP")
    ap.add_argument("--docs", required=True)
    ap.add_argument("--queries", required=True)
    ap.add_argument("--mcp-endpoint", required=True, help="JSON-RPC endpoint that exposes moqui-mcp")
    ap.add_argument("--session-id", default="eval-session")
    ap.add_argument("--auth-header", default=None)
    ap.add_argument("--output-dir", required=True)
    ap.add_argument("--top-k", type=int, default=5)
    ap.add_argument("--hybrid-rerank-threshold", type=float, default=0.90)
    ap.add_argument("--screen-query-threshold", type=float, default=0.80)
    ap.add_argument("--modes", default=None,
                    help="Comma-separated list of modes to evaluate. Default: bm25,vector,hybrid,hybrid_rerank")
    ap.add_argument("--sample-size", type=int, default=0,
                    help="Limit query count per mode (useful for expensive LLM modes). 0 = all queries")
    ap.add_argument("--rpc-endpoint", default=None, help="Optional Moqui JSON-RPC endpoint used to track MathModelRun metadata")
    ap.add_argument("--math-model-id", default="AgentMoquiRagModel_v1")
    ap.add_argument("--create-math-model-run", action="store_true")
    ap.add_argument("--run-name", default="Full Runtime Retrieval Evaluation")
    args = ap.parse_args()

    docs = Path(args.docs)
    queries = Path(args.queries)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    eval_script = Path(__file__).with_name("evaluate_screen_prompt_retrieval.py")
    evalmod = load_eval_module(eval_script)

    headers = {}
    normalized_auth = normalize_auth_header(args.auth_header)
    if normalized_auth:
        headers["Authorization"] = normalized_auth
    client = JsonRpcClient(args.mcp_endpoint, headers=headers)
    service_client = JsonRpcServiceClient(args.rpc_endpoint, headers=headers) if args.rpc_endpoint else None
    math_model_run_id: str | None = None

    try:
        if args.create_math_model_run:
            if not service_client:
                raise RuntimeError("--create-math-model-run requires --rpc-endpoint")
            run_result = extract_service_result(service_client.call(
                "org.moqui.agent.AgentMathModelServices.create#AgentMathModelRun",
                {
                    "mathModelId": args.math_model_id,
                    "runTypeEnumId": "AgentRunEvaluation",
                    "runName": args.run_name,
                    "sourceServiceName": "tools/agent-indexer/evaluate_opensearch_modes.py",
                    "parametersJson": json.dumps({
                        "mcpEndpoint": args.mcp_endpoint,
                        "rpcEndpoint": args.rpc_endpoint,
                        "modes": args.modes or ",".join(DEFAULT_MODES),
                        "sampleSize": args.sample_size,
                        "topK": args.top_k,
                    }, ensure_ascii=False),
                },
            ))
            math_model_run_id = run_result.get("mathModelRunId")

        init_resp = client.call(
            "initialize",
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "mcp-runtime-eval", "version": "1.0"},
                "sessionId": args.session_id,
            },
        )
        if "result" not in init_resp:
            raise RuntimeError(f"Initialize failed: {init_resp}")
        if not client.session_id:
            raise RuntimeError("Initialize did not return Mcp-Session-Id header")
        client.call("notifications/initialized", {}, notification=True)
        runtime_config_details: dict = (init_resp.get("result", {}).get("runtimeConfiguration", {}).get("details") or {})

        summary: dict[str, dict] = {}
        active_modes = [m.strip() for m in args.modes.split(",")] if args.modes else DEFAULT_MODES
        docs_list = evalmod.read_jsonl(docs)
        queries_list = evalmod.read_jsonl(queries)
        original_query_count = len(queries_list)
        if args.sample_size and args.sample_size < len(queries_list):
            import random
            random.seed(42)
            queries_list = random.sample(queries_list, args.sample_size)
            print(f"Sampled {args.sample_size} of {original_query_count} queries for evaluation")

        for mode in active_modes:
            result = evaluate_runtime(
                docs=docs_list,
                queries=queries_list,
                client=client,
                mode=mode,
                top_k=args.top_k,
                evalmod=evalmod,
            )
            summary[mode] = result
            write_mode_outputs(output_dir, mode, result, evalmod)

        gate = {}
        best_mode = "hybrid_llm_rerank" if "hybrid_llm_rerank" in summary else ("hybrid_rerank" if "hybrid_rerank" in summary else None)
        if best_mode:
            gate["hybrid_rerank_groupRecallAt3_ok"] = summary[best_mode].get("groupRecallAt3", 0.0) >= args.hybrid_rerank_threshold
            gate["screen_query_prompt_groupRecallAt3_ok"] = (
                summary[best_mode]
                .get("screenQueryPromptMetrics", {})
                .get("screen_query_prompt", {})
                .get("groupRecallAt3", 0.0)
                >= args.screen_query_threshold
            )

        summary_report = {
            "mathModelId": args.math_model_id,
            "mathModelRunId": math_model_run_id,
            "mcpEndpoint": args.mcp_endpoint,
            "rpcEndpoint": args.rpc_endpoint,
            "sessionId": client.session_id or args.session_id,
            "queryCount": len(queries_list),
            "originalQueryCount": original_query_count,
            "runtimeConfiguration": {
                "embeddingProvider": runtime_config_details.get("embeddingProvider"),
                "embeddingModel": runtime_config_details.get("embeddingModel"),
                "embeddingDimensions": runtime_config_details.get("embeddingDimensions"),
                "embeddingCompatBaseUrlPresent": runtime_config_details.get("embeddingCompatBaseUrlPresent"),
                "embeddingApiKeyPresent": runtime_config_details.get("embeddingApiKeyPresent"),
                "rerankerProvider": runtime_config_details.get("rerankerProvider"),
                "rerankerModel": runtime_config_details.get("rerankerModel"),
                "rerankerCompatBaseUrlPresent": runtime_config_details.get("rerankerCompatBaseUrlPresent"),
                "rerankerApiKeyPresent": runtime_config_details.get("rerankerApiKeyPresent"),
                "searchMode": runtime_config_details.get("searchMode"),
            },
            "thresholds": {
                "hybridRerankGroupRecallAt3": args.hybrid_rerank_threshold,
                "screenQueryPromptGroupRecallAt3": args.screen_query_threshold,
            },
            "gate": gate,
            "modes": {
                mode: {
                    "evaluationSource": result.get("evaluationSource"),
                    "recallAt1": result.get("recallAt1"),
                    "recallAt3": result.get("recallAt3"),
                    "recallAt5": result.get("recallAt5"),
                    "groupRecallAt1": result.get("groupRecallAt1"),
                    "groupRecallAt3": result.get("groupRecallAt3"),
                    "groupRecallAt5": result.get("groupRecallAt5"),
                    "screenQueryPromptMetrics": result.get("screenQueryPromptMetrics", {}),
                    "failureClassCounts": result.get("failureClassCounts", {}),
                    "operationalMetrics": derive_operational_metrics(result),
                }
                for mode, result in summary.items()
            },
        }

        out_json = output_dir / "global-retrieval-opensearch-summary.json"
        out_md = output_dir / "global-retrieval-opensearch-summary.md"
        out_json.write_text(json.dumps(summary_report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        rc = summary_report["runtimeConfiguration"]
        lines = [
            "# OpenSearch Retrieval Summary",
            "",
            f"- MCP endpoint: `{args.mcp_endpoint}`",
            f"- RPC endpoint: `{args.rpc_endpoint}`",
            f"- Session: `{args.session_id}`",
            f"- MathModelId: `{args.math_model_id}`",
            f"- MathModelRunId: `{math_model_run_id}`",
            f"- Queries evaluated: `{len(queries_list)}` of `{original_query_count}`",
            f"- Hybrid rerank groupRecall@3 gate: `{args.hybrid_rerank_threshold}` -> `{gate.get('hybrid_rerank_groupRecallAt3_ok')}`",
            f"- Screen query prompt groupRecall@3 gate: `{args.screen_query_threshold}` -> `{gate.get('screen_query_prompt_groupRecallAt3_ok')}`",
            "",
            "## Runtime Configuration",
            f"- embeddingProvider: `{rc.get('embeddingProvider')}`",
            f"- embeddingModel: `{rc.get('embeddingModel')}`",
            f"- embeddingDimensions: `{rc.get('embeddingDimensions')}`",
            f"- embeddingCompatBaseUrlPresent: `{rc.get('embeddingCompatBaseUrlPresent')}`",
            f"- embeddingApiKeyPresent: `{rc.get('embeddingApiKeyPresent')}`",
            f"- rerankerProvider: `{rc.get('rerankerProvider')}`",
            f"- rerankerModel: `{rc.get('rerankerModel')}`",
            f"- rerankerCompatBaseUrlPresent: `{rc.get('rerankerCompatBaseUrlPresent')}`",
            f"- rerankerApiKeyPresent: `{rc.get('rerankerApiKeyPresent')}`",
            f"- searchMode: `{rc.get('searchMode')}`",
            "",
            "## Modes",
        ]
        for mode, result in summary_report["modes"].items():
            sq = result.get("screenQueryPromptMetrics", {}).get("screen_query_prompt", {})
            op = result.get("operationalMetrics", {})
            lines.append(
                f"- `{mode}`: r@3={result.get('recallAt3')}, gr@3={result.get('groupRecallAt3')}, operational gr@3={op.get('operationalAdjustedGroupRecallAt3')}, trueMissRate={op.get('strictTrueMissRate')}, screenQuery gr@3={sq.get('groupRecallAt3')}, source={result.get('evaluationSource')}"
            )
        out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

        failure_lines = ["# Retrieval Failure Report", ""]
        failure_report: dict[str, list[dict[str, Any]]] = {}
        for mode, result in summary.items():
            failures = result.get("failuresTop3", [])[:50]
            failure_report[mode] = failures
            failure_lines.append(f"## {mode}")
            if not failures:
                failure_lines.append("- No top-50 failures captured.")
            else:
                for failure in failures[:10]:
                    failure_lines.append(
                        f"- `{failure.get('failureClass')}` query=`{failure.get('query')}` target=`{failure.get('targetDocumentId')}` topRetrieved={failure.get('topRetrieved')}"
                    )
            failure_lines.append("")
        (output_dir / "failure-report-top50.json").write_text(json.dumps(failure_report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (output_dir / "failure-report-top50.md").write_text("\n".join(failure_lines), encoding="utf-8")

        if math_model_run_id and service_client:
            extract_service_result(service_client.call(
                "org.moqui.agent.AgentMathModelServices.complete#AgentMathModelRun",
                {
                    "mathModelRunId": math_model_run_id,
                    "resultSummaryJson": json.dumps(summary_report, ensure_ascii=False),
                },
            ))

        print(f"Wrote {out_json}")
        print(f"Wrote {out_md}")
    except Exception as exc:
        if math_model_run_id and service_client:
            try:
                extract_service_result(service_client.call(
                    "org.moqui.agent.AgentMathModelServices.fail#AgentMathModelRun",
                    {
                        "mathModelRunId": math_model_run_id,
                        "errorMessage": str(exc),
                        "metricsJson": json.dumps({"traceback": traceback.format_exc()}, ensure_ascii=False),
                    },
                ))
            except Exception:
                pass
        raise


if __name__ == "__main__":
    main()
