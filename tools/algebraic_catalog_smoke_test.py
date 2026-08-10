#!/usr/bin/env python3
"""
Runtime smoke test for the algebraic Moqui catalog.

This validates the first algebraic slice end-to-end through JSON-RPC:
- search#Morphisms
- get#MorphismSignature
- search#Objects
- get#ObjectSchema
- plan#PromptAsMorphism
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass
class RpcCheck:
    name: str
    method: str
    params: dict


def rpc_call(endpoint: str, username: str, password: str, method: str, params: dict, request_id: int) -> dict:
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
        "params": params,
    }).encode("utf-8")
    auth = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    req = urllib.request.Request(endpoint, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Basic {auth}")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke test the algebraic Moqui catalog services over JSON-RPC")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8081/rpc/json")
    parser.add_argument("--username", default="john.doe")
    parser.add_argument("--password", default="moqui")
    args = parser.parse_args()

    checks = [
        RpcCheck(
            name="search_morphisms",
            method="org.moqui.agent.AgentAlgebraicServices.search#Morphisms",
            params={"queryText": "create budget item", "limit": 5},
        ),
        RpcCheck(
            name="get_morphism_signature",
            method="org.moqui.agent.AgentAlgebraicServices.get#MorphismSignature",
            params={"serviceName": "mantle.other.BudgetServices.create#Budget"},
        ),
        RpcCheck(
            name="search_objects",
            method="org.moqui.agent.AgentAlgebraicServices.search#Objects",
            params={"queryText": "budget item", "limit": 5},
        ),
        RpcCheck(
            name="get_object_schema",
            method="org.moqui.agent.AgentAlgebraicServices.get#ObjectSchema",
            params={"entityName": "mantle.other.budget.BudgetItem"},
        ),
        RpcCheck(
            name="plan_prompt_as_morphism",
            method="org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism",
            params={"queryText": "Create an operating budget for 2028", "limit": 5, "dryRunOnly": True},
        ),
    ]

    summary: dict[str, dict] = {}
    overall_ok = True

    for idx, check in enumerate(checks, start=1):
        try:
            response = rpc_call(args.endpoint, args.username, args.password, check.method, check.params, idx)
            if "error" in response:
                overall_ok = False
                summary[check.name] = {
                    "ok": False,
                    "error": response["error"],
                }
            else:
                result = response.get("result", {})
                summary[check.name] = {
                    "ok": True,
                    "resultKeys": sorted(result.keys()) if isinstance(result, dict) else [],
                }
        except urllib.error.HTTPError as exc:
            overall_ok = False
            summary[check.name] = {"ok": False, "httpStatus": exc.code, "error": exc.read().decode("utf-8", errors="ignore")}
        except Exception as exc:  # noqa: BLE001
            overall_ok = False
            summary[check.name] = {"ok": False, "error": str(exc)}

    print(json.dumps({
        "status": "ok" if overall_ok else "failed",
        "endpoint": args.endpoint,
        "summary": summary,
    }, indent=2, ensure_ascii=False))
    return 0 if overall_ok else 1


if __name__ == "__main__":
    sys.exit(main())
