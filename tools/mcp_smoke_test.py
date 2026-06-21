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
            with urllib.request.urlopen(req, timeout=60) as resp:
                returned_session_id = resp.headers.get("Mcp-Session-Id")
                if returned_session_id:
                    self.session_id = returned_session_id
                body = resp.read().decode("utf-8")
                if not body.strip():
                    return {}
                return json.loads(body)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore")
            return {"httpError": exc.code, "body": body}


def extract_result(response: dict[str, Any]) -> dict[str, Any]:
    if "result" in response:
        return response["result"]
    return response


def parse_tool_payload(response: dict[str, Any]) -> dict[str, Any]:
    result = extract_result(response)
    if not isinstance(result, dict):
        return {"rawResult": result}
    if result.get("isError"):
        return result
    content = result.get("content", [])
    if content and isinstance(content, list):
        first = content[0]
        if isinstance(first, dict) and "text" in first:
            try:
                return json.loads(first["text"])
            except json.JSONDecodeError:
                return {"rawText": first["text"]}
    return result


def check(name: str, ok: bool, detail: str) -> dict[str, Any]:
    return {"name": name, "ok": ok, "detail": detail}


def normalize_auth_header(raw_value: str | None) -> str | None:
    if raw_value is None:
        return None
    value = raw_value.strip()
    if value.lower().startswith("authorization:"):
        value = value.split(":", 1)[1].strip()
    return value or None


RUNTIME_TOOLS = {
    "moqui_get_runtime_context",
    "moqui_check_runtime_configuration",
    "moqui_search_agent_prompts",
    "moqui_get_agent_document",
    "moqui_get_session_context",
    "moqui_update_session_context",
    "moqui_execute_agent_prompt",
    "moqui_check_artifact_access",
    "moqui_find_records_guarded",
    "moqui_resolve_and_execute",
    "moqui_create_agent_session",
    "moqui_update_agent_session_external_context",
    "moqui_find_agent_sessions",
    "moqui_close_agent_session",
}

DEBUG_TOOLS = {
    "moqui_search_artifacts",
    "moqui_get_artifact",
    "moqui_call_service_guarded",
}


def main() -> None:
    ap = argparse.ArgumentParser(description="Run MCP smoke tests against moqui-mcp endpoint")
    ap.add_argument("--endpoint", required=True, help="MCP endpoint, for example http://localhost:8080/mcp/message")
    ap.add_argument("--session-id", default="smoke-session")
    ap.add_argument("--auth-header", default=None, help="Optional Authorization header value")
    ap.add_argument("--profile", choices=["runtime", "debug"], default="runtime",
                    help="User profile: 'runtime' (MCP_RUNTIME role, debug tools must be hidden) or "
                         "'debug' (MCP_DEBUG role, all tools visible). Default: runtime")
    ap.add_argument("--runtime-tool", default="moqui_get_runtime_context")
    ap.add_argument("--debug-tool", default="moqui_search_artifacts")
    ap.add_argument("--expect-debug-access", action="store_true",
                    help="[deprecated] Equivalent to --profile=debug. Use --profile instead.")
    ap.add_argument("--prompt-query", default="list orders")
    ap.add_argument("--agent-document-id", default=None)
    ap.add_argument("--dry-run-document-id", default=None)
    ap.add_argument("--high-risk-document-id", default=None)
    ap.add_argument("--missing-context-document-id", default=None)
    ap.add_argument("--sensitive-entity-name", default="mantle.account.invoice.Invoice")
    ap.add_argument("--out-json", required=True)
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()
    # --expect-debug-access overrides --profile for backwards compatibility
    if args.expect_debug_access:
        args.profile = "debug"

    headers = {}
    normalized_auth = normalize_auth_header(args.auth_header)
    if normalized_auth:
        headers["Authorization"] = normalized_auth
    client = JsonRpcClient(args.endpoint, headers=headers)

    results: list[dict[str, Any]] = []

    init_resp = extract_result(client.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-smoke", "version": "1.0"}, "sessionId": args.session_id}))
    results.append(check("initialize", "protocolVersion" in init_resp, f"protocolVersion={init_resp.get('protocolVersion')}"))
    results.append(check("session_header_received", bool(client.session_id), f"mcpSessionId={client.session_id}"))

    initialized_resp = client.call("notifications/initialized", {}, notification=True)
    results.append(check("notifications_initialized", initialized_resp == {}, f"response={initialized_resp}"))

    tools_resp = extract_result(client.call("tools/list", {}))
    tools = tools_resp.get("tools", []) if isinstance(tools_resp, dict) else []
    tool_names = [t.get("name") for t in tools if isinstance(t, dict)]
    tool_name_set = set(tool_names)
    results.append(check("tools_list_runtime_tool_visible", args.runtime_tool in tool_name_set, f"tools={tool_names[:12]}"))
    if args.profile == "runtime":
        leaked = sorted(DEBUG_TOOLS & tool_name_set)
        results.append(check(
            "tools_list_debug_tools_hidden_for_runtime_user",
            len(leaked) == 0,
            f"leaked_debug_tools={leaked}",
        ))
    else:
        visible_debug = sorted(DEBUG_TOOLS & tool_name_set)
        results.append(check(
            "tools_list_debug_tools_visible_for_debug_user",
            len(visible_debug) > 0,
            f"visible_debug_tools={visible_debug}",
        ))

    debug_args: dict[str, Any]
    if args.debug_tool == "moqui_call_service_guarded":
        debug_args = {"serviceName": "org.moqui.impl.BasicServices.echo#Data", "parameters": {"textIn1": "mcp-smoke"}}
    elif args.debug_tool == "moqui_search_artifacts":
        debug_args = {"queryText": args.prompt_query, "limit": 2}
    elif args.debug_tool == "moqui_get_artifact":
        debug_args = {"artifactName": "component://tools/screen/Tools.xml", "artifactTypeEnumId": "AT_XML_SCREEN"}
    else:
        debug_args = {}

    debug_resp = extract_result(client.call("tools/call", {"name": args.debug_tool, "arguments": debug_args}))
    debug_is_error = isinstance(debug_resp, dict) and (
        debug_resp.get("isError") is True
        or debug_resp.get("httpError") is not None
    )
    debug_allowed = not debug_is_error
    is_debug_profile = args.profile == "debug"
    debug_ok = debug_allowed if is_debug_profile else debug_is_error
    debug_name = "tools_call_debug_allowed" if is_debug_profile else "tools_call_debug_denied_or_blocked"
    results.append(check(debug_name, debug_ok, f"response={debug_resp}"))

    search_resp = client.call("tools/call", {"name": "moqui_search_agent_prompts", "arguments": {"queryText": args.prompt_query, "limit": 3}})
    search_payload = parse_tool_payload(search_resp)
    search_ok = isinstance(search_payload, dict) and isinstance(search_payload.get("resultList"), list)
    results.append(check("search_agent_prompts", search_ok, f"resultCount={len(search_payload.get('resultList', [])) if isinstance(search_payload, dict) else 'n/a'}"))

    if args.agent_document_id:
        get_doc_resp = client.call("tools/call", {"name": "moqui_get_agent_document", "arguments": {"documentId": args.agent_document_id}})
        get_doc_payload = parse_tool_payload(get_doc_resp)
        doc_ok = isinstance(get_doc_payload, dict) and isinstance(get_doc_payload.get("document"), dict)
        results.append(check("get_agent_document", doc_ok, f"documentId={get_doc_payload.get('document', {}).get('documentId') if doc_ok else get_doc_payload}"))

    update_ctx_resp = extract_result(client.call("tools/call", {"name": "moqui_update_session_context", "arguments": {"currentArea": "Order", "currentBusinessObjects": {"orderId": "TEST-ORDER-1"}}}))
    results.append(check("update_session_context", isinstance(update_ctx_resp, dict) and update_ctx_resp.get("isError") is not True, f"response={update_ctx_resp}"))

    # --- External session registry tests ---
    create_sess_payload = parse_tool_payload(client.call("tools/call", {
        "name": "moqui_create_agent_session",
        "arguments": {
            "clientTypeEnumId": "AgClientMcp",
            "clientSessionId": "smoke-ext-sess-001",
            "clientConversationId": "smoke-conv-001",
            "clientUrl": "/smoke/test",
            "modelName": "gpt-5",
            "contextStorageModeEnumId": "AgCtxExternalOnly",
        },
    }))
    created_session_id = create_sess_payload.get("agentSessionContextId") if isinstance(create_sess_payload, dict) else None
    create_ok = bool(created_session_id) and not create_sess_payload.get("isError")
    results.append(check("create_agent_session", create_ok, f"agentSessionContextId={created_session_id}"))

    find_sess_payload = parse_tool_payload(client.call("tools/call", {
        "name": "moqui_find_agent_sessions",
        "arguments": {"clientTypeEnumId": "AgClientMcp", "limit": 10},
    }))
    find_sess_list = find_sess_payload.get("agentSessionList", []) if isinstance(find_sess_payload, dict) else []
    found_ids = [s.get("agentSessionContextId") for s in find_sess_list if isinstance(s, dict)]
    find_ok = created_session_id in found_ids if created_session_id else isinstance(find_sess_list, list)
    results.append(check("find_agent_sessions", find_ok, f"found={len(found_ids)} sessions, created_in_list={created_session_id in found_ids if created_session_id else 'n/a'}"))

    if created_session_id:
        update_ext_payload = parse_tool_payload(client.call("tools/call", {
            "name": "moqui_update_agent_session_external_context",
            "arguments": {
                "agentSessionContextId": created_session_id,
                "clientUrl": "/smoke/updated",
                "providerConversationId": "provider-conv-001",
                "statusId": "AgSessActive",
            },
        }))
        update_ext_ok = isinstance(update_ext_payload, dict) and not update_ext_payload.get("isError") and update_ext_payload.get("agentSessionContext") is not None
        results.append(check("update_agent_session_external_context", update_ext_ok, f"response={update_ext_payload.get('agentSessionContext', {}).get('statusId') if update_ext_ok else update_ext_payload}"))

        close_sess_payload = parse_tool_payload(client.call("tools/call", {
            "name": "moqui_close_agent_session",
            "arguments": {"agentSessionContextId": created_session_id},
        }))
        closed_status = close_sess_payload.get("agentSessionContext", {}).get("statusId") if isinstance(close_sess_payload, dict) else None
        close_ok = closed_status == "AgSessClosed"
        results.append(check("close_agent_session", close_ok, f"statusId={closed_status}"))
    else:
        results.append(check("update_agent_session_external_context", False, "skipped: create_agent_session failed"))
        results.append(check("close_agent_session", False, "skipped: create_agent_session failed"))

    denied_find_resp = client.call(
        "tools/call",
        {
            "name": "moqui_find_records_guarded",
            "arguments": {"entityName": args.sensitive_entity_name, "limit": 1},
        },
    )
    denied_find_payload = parse_tool_payload(denied_find_resp)
    if is_debug_profile:
        # Privileged users (ADMIN/MCP_DEBUG) are allowed to query sensitive entities without conditions
        denied_find_ok = isinstance(denied_find_payload, dict) and not denied_find_payload.get("isError")
        denied_find_check_name = "find_records_guarded_privileged_access"
    else:
        denied_find_ok = (
            isinstance(denied_find_payload, dict)
            and (
                denied_find_payload.get("isError") is True
                or "Error:" in json.dumps(denied_find_payload, ensure_ascii=False)
                or "non consentita" in json.dumps(denied_find_payload, ensure_ascii=False)
            )
        )
        denied_find_check_name = "find_records_guarded_sensitive_denied"
    results.append(check(denied_find_check_name, denied_find_ok, f"response={denied_find_payload}"))

    if args.dry_run_document_id:
        dry_run_resp = client.call(
            "tools/call",
            {
                "name": "moqui_execute_agent_prompt",
                "arguments": {"documentId": args.dry_run_document_id, "dryRun": True},
            },
        )
        dry_run_payload = parse_tool_payload(dry_run_resp)
        dry_run_ok = isinstance(dry_run_payload, dict) and (
            dry_run_payload.get("success") is True
            or (isinstance(dry_run_payload.get("serviceResult"), dict) and dry_run_payload["serviceResult"].get("dryRun") is True)
        )
        results.append(check("execute_agent_prompt_dry_run", dry_run_ok, f"response={dry_run_payload}"))

    if args.high_risk_document_id:
        confirm_resp = client.call(
            "tools/call",
            {
                "name": "moqui_execute_agent_prompt",
                "arguments": {"documentId": args.high_risk_document_id, "confirmed": False},
            },
        )
        confirm_payload = parse_tool_payload(confirm_resp)
        confirm_ok = isinstance(confirm_payload, dict) and bool(confirm_payload.get("confirmationRequired"))
        results.append(check("high_risk_confirmation_required", confirm_ok, f"response={confirm_payload}"))

    if args.missing_context_document_id:
        missing_ctx_resp = client.call(
            "tools/call",
            {
                "name": "moqui_execute_agent_prompt",
                "arguments": {"documentId": args.missing_context_document_id, "useSessionContext": False},
            },
        )
        missing_ctx_payload = parse_tool_payload(missing_ctx_resp)
        missing_ctx_ok = isinstance(missing_ctx_payload, dict) and bool(missing_ctx_payload.get("needsMoreContext"))
        results.append(check("missing_context_detected", missing_ctx_ok, f"response={missing_ctx_payload}"))

    out_json = Path(args.out_json)
    out_md = Path(args.out_md)
    out_json.parent.mkdir(parents=True, exist_ok=True)

    summary = {
        "endpoint": args.endpoint,
        "sessionId": client.session_id or args.session_id,
        "results": results,
        "passed": len([r for r in results if r["ok"]]),
        "failed": len([r for r in results if not r["ok"]]),
    }
    out_json.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [
        "# MCP Smoke Test Report",
        "",
        f"- Endpoint: `{args.endpoint}`",
        f"- Session: `{args.session_id}`",
        f"- Passed: `{summary['passed']}`",
        f"- Failed: `{summary['failed']}`",
        "",
        "## Checks",
    ]
    for item in results:
        status = "PASS" if item["ok"] else "FAIL"
        lines.append(f"- `{status}` `{item['name']}`: {item['detail']}")
    out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")
    sys.exit(0 if summary["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
