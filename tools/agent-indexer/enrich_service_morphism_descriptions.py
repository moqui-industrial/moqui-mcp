#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PROMPT_VERSION = "service-morphism-enrichment-v2"


def _input_names(service_doc: dict[str, Any]) -> set[str]:
    return {
        item.get("name")
        for item in (service_doc.get("inParameters") or [])
        if isinstance(item, dict) and item.get("name")
    }


def _called_services(service_doc: dict[str, Any]) -> list[str]:
    return [str(item) for item in (service_doc.get("calledServices") or []) if item]


def _contains_any(text: str, parts: list[str]) -> bool:
    lowered = (text or "").lower()
    return any(part.lower() in lowered for part in parts)


def heuristic_semantic_overrides(service_doc: dict[str, Any]) -> dict[str, str]:
    service_name = service_doc.get("serviceName") or ""
    domain_object = service_doc.get("domainObject") or service_doc.get("serviceNoun") or "target domain object"
    in_names = _input_names(service_doc)
    called = _called_services(service_doc)
    source_file = service_doc.get("sourceFile") or ""
    semantic = service_doc.get("semanticDescription") or service_doc.get("businessSentence") or ""

    if service_name.startswith("org.moqui.agent.") or service_name.startswith("org.moqui.mcp."):
        return {
            "selection": (
                "Select only for internal agent runtime, MCP protocol, telemetry, or algebraic metamodel operations. "
                "Do not select for ordinary ERP business requests from end users."
            )
        }

    if domain_object == "Request":
        if "assignToPartyId" in in_names or _contains_any(source_file, ["/mantle/request/"]) or any("RequestParty" in svc for svc in called):
            return {
                "semantic": (
                    f"Service {service_name} creates or updates a support-style request or ticket. "
                    "It can record the reporter, assign the ticket to a person, connect a customer or client, "
                    "and preserve request status, priority, type, and resolution context."
                ),
                "selection": (
                    "Select for user requests about opening, creating, updating, or assigning tickets, support requests, "
                    "service requests, issue reports, maintenance requests, or urgent cases. "
                    "It is the right morphism when the prompt speaks about a ticket plus assignee, reporter, customer, "
                    "priority, or status, rather than accounting due dates or invoice terms."
                ),
                "binding": (
                    "Use lookup bindings for assignee, reporter, customer, or client party operands when names are provided. "
                    "If a deadline or due date is requested, bind it only through request fields actually exposed by the chosen request morphism; "
                    "do not substitute invoice due-date services."
                ),
            }

    if domain_object == "Task":
        if "assignToPartyId" in in_names or "milestoneWorkEffortId" in in_names:
            return {
                "semantic": (
                    f"Service {service_name} creates or updates executable work under a project hierarchy. "
                    "It supports task creation, optional assignment to a party, and optional attachment to a milestone "
                    "inside a project or work-effort tree."
                ),
                "selection": (
                    "Select for prompts about creating or assigning tasks, especially when the prompt mentions a project, milestone, "
                    "assignee, work name, priority, or parent work context."
                ),
            }

    if domain_object == "Project":
        return {
            "semantic": (
                f"Service {service_name} creates or updates the project root of a work-effort hierarchy. "
                "It establishes the main project identity and may assign responsible parties at project level."
            ),
            "selection": (
                "Select for prompts about creating or managing a project root, not for lower-level ticket, accounting, "
                "or technical runtime operations."
            ),
        }

    if _contains_any(service_name, ["InvoiceDueDate"]) or _contains_any(semantic, ["InvoiceDueDate"]):
        return {
            "selection": (
                "Select only when the request is explicitly about invoice payment terms, invoice due-date calculation, "
                "settlement terms, or agreement-based accounting deadlines. "
                "Do not select for support tickets, work tasks, project deadlines, maintenance issues, or generic requests with a date."
            )
        }

    if _contains_any(service_name, ["PartyAcctgPreference"]):
        return {
            "selection": (
                "Select only for direct accounting-preference maintenance on the accounting-preferences entity itself, "
                "or when an expert user explicitly asks to create or update accounting preferences. "
                "Do not select as a fallback for budget, project, request, or support prompts."
            )
        }

    if _contains_any(service_name, ["Bai2SystemMessage"]):
        return {
            "selection": (
                "Select only for technical bank-file ingestion or BAI2 message import workflows. "
                "Do not select for generic accounting, budgeting, project, request, or HR prompts."
            )
        }

    return {}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def stable_hash(payload: dict[str, Any]) -> str:
    return hashlib.sha1(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def compact_payload(service_doc: dict[str, Any]) -> dict[str, Any]:
    return {
        "serviceName": service_doc.get("serviceName"),
        "serviceVerb": service_doc.get("serviceVerb"),
        "serviceNoun": service_doc.get("serviceNoun"),
        "domainObject": service_doc.get("domainObject"),
        "statementCount": service_doc.get("statementCount"),
        "readEntities": (service_doc.get("readEntities") or [])[:12],
        "writtenEntities": (service_doc.get("writtenEntities") or [])[:12],
        "calledServices": (service_doc.get("calledServices") or [])[:12],
        "operationEffects": (service_doc.get("operationEffects") or [])[:12],
        "statementClasses": (service_doc.get("statementClasses") or [])[:12],
        "requiredOperands": [
            item.get("name")
            for item in (service_doc.get("inParameters") or [])
            if item.get("required") and item.get("name")
        ][:12],
        "producedOperands": [
            item.get("name")
            for item in (service_doc.get("outParameters") or [])
            if item.get("name")
        ][:12],
        "serviceComplements": (service_doc.get("serviceComplements") or [])[:20],
        "likelyUserQueries": (service_doc.get("likelyUserQueries") or [])[:12],
        "technicalSentence": service_doc.get("technicalSentence"),
        "businessSentence": service_doc.get("businessSentence"),
        "semanticDescription": service_doc.get("semanticDescription"),
    }


def local_fallback(service_doc: dict[str, Any]) -> dict[str, Any]:
    required = [
        item.get("name")
        for item in (service_doc.get("inParameters") or [])
        if item.get("required") and item.get("name")
    ]
    produced = [
        item.get("name")
        for item in (service_doc.get("outParameters") or [])
        if item.get("name")
    ]
    written = service_doc.get("writtenEntities") or []
    read = service_doc.get("readEntities") or []
    called = service_doc.get("calledServices") or []
    domain_object = service_doc.get("domainObject") or service_doc.get("serviceNoun") or "target domain object"
    operation_effects = service_doc.get("operationEffects") or []
    statement_classes = service_doc.get("statementClasses") or []
    likely_queries = service_doc.get("likelyUserQueries") or []
    semantic = service_doc.get("semanticDescription") or service_doc.get("businessSentence") or ""
    overrides = heuristic_semantic_overrides(service_doc)
    if overrides.get("semantic"):
        semantic = overrides["semantic"]
    if not semantic:
        semantic = f"Service {service_doc.get('serviceName')} operates on {domain_object}."
    semantic_parts = [semantic]
    if operation_effects:
        semantic_parts.append(f"Observed operation effects: {', '.join(operation_effects[:8])}.")
    if statement_classes:
        semantic_parts.append(f"Statement classes present: {', '.join(statement_classes[:8])}.")
    if likely_queries:
        semantic_parts.append(f"Representative retrieval phrases: {', '.join(likely_queries[:8])}.")
    selection_bits = [f"Use when the request is about {domain_object}."]
    if written:
        selection_bits.append(f"It persists or mutates {', '.join(written[:4])}.")
    elif read:
        selection_bits.append(f"It primarily looks up {', '.join(read[:4])}.")
    if likely_queries:
        selection_bits.append(f"Likely matches phrases such as {', '.join(likely_queries[:6])}.")
    if required:
        selection_bits.append(f"It requires bindings for {', '.join(required[:6])}.")
    if overrides.get("selection"):
        selection_bits.append(overrides["selection"])
    binding_bits = []
    if required:
        binding_bits.append(f"Execution is blocked until required operands are bound: {', '.join(required[:8])}.")
    else:
        binding_bits.append("Execution has no required input operands at the service boundary.")
    if produced:
        binding_bits.append(f"The service produces operands such as {', '.join(produced[:8])}.")
    if called:
        binding_bits.append(f"It delegates part of the workflow to downstream services like {', '.join(called[:6])}.")
    if overrides.get("binding"):
        binding_bits.append(overrides["binding"])
    return {
        "semanticDescriptionLlm": " ".join(semantic_parts).strip(),
        "selectionDescription": " ".join(selection_bits).strip(),
        "bindingDescription": " ".join(binding_bits).strip(),
        "llmConfidence": "fallback",
    }


def build_prompt_payload(service_doc: dict[str, Any]) -> dict[str, Any]:
    return {
        "promptVersion": PROMPT_VERSION,
        "service": compact_payload(service_doc),
        "instructions": {
            "goal": "Produce concise semantic descriptions for service-morphism retrieval and safe operand binding.",
            "constraints": [
                "Do not invent entities, parameters, or effects that are not present in the payload.",
                "Prefer exact names of required operands when describing binding constraints.",
                "Describe the service as a morphism over business objects, not as UI behavior.",
                "Keep all strings plain English and concise.",
            ],
            "outputSchema": {
                "semanticDescriptionLlm": "One concise paragraph describing what the service really does.",
                "selectionDescription": "A short text explaining when this morphism should be selected.",
                "bindingDescription": "A short text explaining what must be bound before execution and what it produces.",
                "llmConfidence": "high|medium|low"
            },
        },
    }


def parse_json_object(text: str) -> dict[str, Any]:
    text = text.strip()
    if not text:
        raise ValueError("Empty LLM response")
    if text.startswith("```"):
        parts = text.split("```")
        text = "".join(part for idx, part in enumerate(parts) if idx % 2 == 1 or idx == len(parts) - 1).strip()
        if text.startswith("json"):
            text = text[4:].strip()
    return json.loads(text)


def call_chat_completion(provider: str, model: str, api_key: str, payload: dict[str, Any], *,
        base_url: str | None = None, timeout: int = 120) -> dict[str, Any]:
    if provider == "openai":
        endpoint = "https://api.openai.com/v1/chat/completions"
    elif provider == "openai_compatible":
        if not base_url:
            raise ValueError("openai_compatible provider requires --base-url")
        endpoint = base_url.rstrip("/") + "/chat/completions"
    else:
        raise ValueError(f"Unsupported provider {provider}")

    body = {
        "model": model,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "system",
                "content": (
                    "You enrich algebraic service-morphism descriptions for a Moqui-based system. "
                    "You receive only structured service graph metadata. Return strict JSON."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(payload, ensure_ascii=False),
            },
        ],
    }
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(endpoint, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", f"Bearer {api_key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        response_body = response.read().decode("utf-8")
    response_map = json.loads(response_body)
    content = response_map["choices"][0]["message"]["content"]
    return parse_json_object(content)


def enrich_rows(rows: list[dict[str, Any]], provider: str, model: str | None, api_key: str | None,
        base_url: str | None, cache_dir: Path, limit: int | None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    output_rows: list[dict[str, Any]] = []
    enriched_count = 0
    cached_count = 0
    fallback_count = 0
    failure_count = 0

    for index, row in enumerate(rows, start=1):
        prompt_payload = build_prompt_payload(row)
        cache_key_payload = {
            "provider": provider,
            "model": model or "",
            "baseUrl": base_url or "",
            "payload": prompt_payload,
        }
        cache_key = stable_hash(cache_key_payload)
        cache_path = cache_dir / f"{cache_key}.json"

        enrichment: dict[str, Any]
        if cache_path.exists():
            enrichment = json.loads(cache_path.read_text(encoding="utf-8"))
            cached_count += 1
        elif provider == "none":
            enrichment = local_fallback(row)
            fallback_count += 1
            cache_path.write_text(json.dumps(enrichment, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif limit is not None and enriched_count >= limit:
            enrichment = local_fallback(row)
            fallback_count += 1
        else:
            try:
                enrichment = call_chat_completion(provider, model or "", api_key or "", prompt_payload, base_url=base_url)
                if "semanticDescriptionLlm" not in enrichment:
                    enrichment["semanticDescriptionLlm"] = row.get("semanticDescription") or row.get("businessSentence") or ""
                if "selectionDescription" not in enrichment:
                    enrichment["selectionDescription"] = ""
                if "bindingDescription" not in enrichment:
                    enrichment["bindingDescription"] = ""
                enrichment.setdefault("llmConfidence", "medium")
                cache_path.write_text(json.dumps(enrichment, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                enriched_count += 1
                time.sleep(0.1)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, KeyError, json.JSONDecodeError) as exc:
                enrichment = local_fallback(row)
                enrichment["llmFailure"] = str(exc)
                fallback_count += 1
                failure_count += 1

        merged = dict(row)
        merged.update({
            "semanticDescriptionLlm": enrichment.get("semanticDescriptionLlm") or row.get("semanticDescription") or row.get("businessSentence") or "",
            "selectionDescription": enrichment.get("selectionDescription") or "",
            "bindingDescription": enrichment.get("bindingDescription") or "",
            "llmConfidence": enrichment.get("llmConfidence") or "fallback",
            "llmDescriptionProvider": provider,
            "llmDescriptionModel": model or "",
            "llmPromptVersion": PROMPT_VERSION,
        })
        merged["embeddingText"] = " ".join([
            merged.get("semanticDescriptionLlm", ""),
            merged.get("selectionDescription", ""),
            merged.get("bindingDescription", ""),
            merged.get("embeddingText", ""),
        ]).strip()
        output_rows.append(merged)

    summary = {
        "inputCount": len(rows),
        "enrichedCount": enriched_count,
        "cachedCount": cached_count,
        "fallbackCount": fallback_count,
        "failureCount": failure_count,
        "provider": provider,
        "model": model or "",
        "promptVersion": PROMPT_VERSION,
    }
    return output_rows, summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Enrich service morphism descriptions with optional LLM-generated semantic summaries.")
    parser.add_argument("--input", required=True, help="Path to global-service-action-documents.jsonl")
    parser.add_argument("--output", required=True, help="Output JSONL path for enriched service documents")
    parser.add_argument("--summary", required=True, help="Output summary JSON path")
    parser.add_argument("--provider", default=os.environ.get("MORPHISM_DESCRIPTION_PROVIDER", "none"),
                        choices=["none", "openai", "openai_compatible"])
    parser.add_argument("--model", default=os.environ.get("MORPHISM_DESCRIPTION_MODEL", ""))
    parser.add_argument("--api-key", default=os.environ.get("MORPHISM_DESCRIPTION_API_KEY", ""))
    parser.add_argument("--base-url", default=os.environ.get("MORPHISM_DESCRIPTION_BASE_URL", ""))
    parser.add_argument("--cache-dir", default=os.environ.get("MORPHISM_DESCRIPTION_CACHE_DIR", ""))
    parser.add_argument("--limit", type=int, default=None, help="Maximum number of rows to enrich through LLM on this run.")
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)
    summary_path = Path(args.summary)
    cache_dir = Path(args.cache_dir) if args.cache_dir else output_path.parent / "cache" / "service-morphism-descriptions"

    if args.provider != "none" and not args.model:
        print("Missing --model for non-none provider", file=sys.stderr)
        sys.exit(2)
    if args.provider != "none" and not args.api_key:
        print("Missing --api-key for non-none provider", file=sys.stderr)
        sys.exit(2)

    rows = load_jsonl(input_path)
    enriched_rows, summary = enrich_rows(rows, args.provider, args.model, args.api_key, args.base_url or None, cache_dir, args.limit)
    write_jsonl(output_path, enriched_rows)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
