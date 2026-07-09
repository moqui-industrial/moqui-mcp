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
"""
Evaluate retrieval quality for screen-derived prompt documents.

Inputs:
- screen-prompt-documents.jsonl
- screen-prompt-eval-queries.jsonl

Modes:
- lexical (default): BM25 over local docs
- weighted: weighted lexical scoring on key fields
- vector: local cosine with OpenAI embeddings (optional)
- opensearch: query existing OpenSearch index (optional)
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import re
import urllib.request
import urllib.error
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", (s or "")).strip().lower()


def tokenize(s: str) -> list[str]:
    raw = [t for t in re.split(r"[^a-z0-9]+", norm(s)) if t]
    out = []
    for t in raw:
        out.append(t)
        if len(t) > 4 and t.endswith("ies"):
            out.append(t[:-3] + "y")
        elif len(t) > 4 and t.endswith("s"):
            out.append(t[:-1])
    return out


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    return rows


def join_doc_text(d: dict) -> str:
    parts = [
        d.get("canonicalPrompt", ""),
        " ".join(d.get("promptVariants", [])),
        d.get("subArea", ""),
        d.get("domainObject", ""),
        d.get("promptGroupId", ""),
        d.get("actionKind", ""),
        " ".join(d.get("uiLabels", [])),
        " ".join(d.get("transitionNames", [])),
        d.get("embeddingText", ""),
        d.get("documentKind", ""),
        d.get("operationEffect", ""),
        d.get("screenName", ""),
        d.get("preferredService", ""),
    ]
    return norm(" ".join(str(p) for p in parts if p is not None))


def infer_query_action_kind(query: str) -> str:
    q = norm(query)
    if any(x in q for x in ["create", "add", "new"]):
        return "create"
    if any(x in q for x in ["update", "edit", "modify", "set"]):
        return "update"
    if any(x in q for x in ["delete", "remove"]):
        return "delete"
    if any(x in q for x in ["approve", "cancel", "reject", "complete", "status"]):
        return "status"
    if any(x in q for x in ["print", "pdf", "download", "export"]):
        return "print"
    if any(x in q for x in ["email", "send"]):
        return "email"
    if any(x in q for x in ["validate", "check", "verify"]):
        return "validate"
    if any(x in q for x in ["detail", "open", "view"]):
        return "detail"
    if any(x in q for x in ["find", "search", "list", "lookup", "show"]):
        return "list"
    return "unresolved"


def weighted_lexical_score(query: str, d: dict) -> float:
    q = tokenize(query)
    if not q:
        return 0.0
    qset = set(q)
    qtxt = norm(query)
    query_action_kind = infer_query_action_kind(query)
    query_is_read = query_action_kind in {"list", "detail"}
    query_is_mut = query_action_kind in {"create", "update", "delete", "status"}
    canonical = norm(d.get("canonicalPrompt", ""))
    sub_area = d.get("subArea", "")
    dom = d.get("domainObject", "")
    action_kind = d.get("actionKind", "unresolved")
    fields = [
        (d.get("canonicalPrompt", ""), 11.0),
        (d.get("domainObject", ""), 9.0),
        (d.get("subArea", ""), 8.0),
        (d.get("actionKind", ""), 8.0),
        (d.get("promptGroupId", ""), 5.0),
        (" ".join(d.get("englishPromptVariants", [])), 4.0),
        (" ".join(d.get("italianPromptVariants", [])), 4.0),
        (" ".join(d.get("promptVariants", [])), 2.5),
        (" ".join(d.get("uiLabels", [])), 2.0),
        (" ".join(d.get("transitionNames", [])), 3.0),
        (d.get("preferredService", ""), 2.5),
        (" ".join(d.get("formNames", [])), 4.5),
        (" ".join(d.get("fieldNames", [])), 1.5),
        (d.get("embeddingText", ""), 0.3),
    ]
    score = 0.0
    for text, w in fields:
        toks = set(tokenize(str(text)))
        overlap = sum(1 for t in q if t in toks)
        if overlap:
            score += w * overlap / max(len(q), 1)

    if qtxt == canonical:
        score += 5.0
    if qtxt in set(norm(v) for v in d.get("englishPromptVariants", [])[:12]):
        score += 2.0

    dkind = d.get("documentKind", "")
    if any(x in qtxt for x in ["find", "search", "list", "show", "view"]):
        if dkind == "screen_query_prompt":
            score += 1.5
    if any(x in qtxt for x in ["approve", "cancel", "place", "complete", "reject"]):
        if d.get("operationEffect") == "status_transition":
            score += 1.0
    if any(x in qtxt for x in ["print", "pdf", "download", "export"]) and d.get("operationEffect") == "print_export":
        score += 1.0
    if any(x in qtxt for x in ["validate", "check", "verify"]) and d.get("operationEffect") in {"validation", "external_call"}:
        score += 1.0
    # structured reranking: prefer matching subArea/domainObject + operationEffect
    if qset & set(tokenize(sub_area)):
        score += 2.0
    if qset & set(tokenize(dom)):
        score += 2.0
    if query_action_kind != "unresolved":
        if action_kind == query_action_kind:
            score += 2.5
        elif query_action_kind in {"list", "detail"} and action_kind in {"list", "detail", "navigate"}:
            score += 1.0
        elif query_action_kind in {"create", "update", "delete", "status"} and action_kind in {"create", "update", "delete", "status"}:
            score += 1.0
        else:
            score -= 1.3
    op = d.get("operationEffect")
    if query_is_read and op in {"read_query", "read_detail", "navigation"}:
        score += 1.4
    if query_is_read and op in {"create", "update", "delete", "status_transition", "batch_update"}:
        score -= 1.0
    if query_is_mut and op in {"create", "update", "delete", "status_transition", "batch_update"}:
        score += 1.4
    if query_is_mut and op in {"read_query", "read_detail", "navigation"}:
        score -= 1.0
    return score


@dataclass
class BM25Index:
    docs: list[dict]
    doc_tokens: list[list[str]]
    df: dict[str, int]
    avgdl: float
    k1: float = 1.5
    b: float = 0.75

    @staticmethod
    def build(docs: list[dict]) -> "BM25Index":
        dts = [tokenize(join_doc_text(d)) for d in docs]
        df: dict[str, int] = {}
        for toks in dts:
            for t in set(toks):
                df[t] = df.get(t, 0) + 1
        avgdl = (sum(len(t) for t in dts) / len(dts)) if dts else 0.0
        return BM25Index(docs=docs, doc_tokens=dts, df=df, avgdl=avgdl)

    def score(self, query: str, doc_idx: int) -> float:
        q_terms = tokenize(query)
        if not q_terms:
            return 0.0
        toks = self.doc_tokens[doc_idx]
        if not toks:
            return 0.0
        tf = Counter(toks)
        n_docs = len(self.docs)
        dl = len(toks)
        score = 0.0
        for t in q_terms:
            dft = self.df.get(t, 0)
            if dft == 0:
                continue
            idf = math.log(1 + (n_docs - dft + 0.5) / (dft + 0.5))
            f = tf.get(t, 0)
            if f == 0:
                continue
            denom = f + self.k1 * (1 - self.b + self.b * (dl / (self.avgdl or 1.0)))
            score += idf * (f * (self.k1 + 1)) / denom
        return score

    def retrieve(self, query: str, top_k: int) -> list[tuple[str, float]]:
        scored = []
        for i, d in enumerate(self.docs):
            s = self.score(query, i)
            if s > 0:
                scored.append((d["documentId"], s))
        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k]


def cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return -1.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return -1.0
    return dot / (na * nb)


def openai_embed(texts: list[str], model: str) -> list[list[float]]:
    api_key = os.getenv("OPENAI_API_KEY", "")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required for vector mode")
    url = "https://api.openai.com/v1/embeddings"
    req = urllib.request.Request(url, method="POST")
    req.add_header("Authorization", f"Bearer {api_key}")
    req.add_header("Content-Type", "application/json")
    payload = json.dumps({"model": model, "input": texts}).encode("utf-8")
    try:
        with urllib.request.urlopen(req, data=payload, timeout=120) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"OpenAI error: {e.code} {detail}") from e
    data = body.get("data", [])
    data.sort(key=lambda x: x.get("index", 0))
    return [x.get("embedding", []) for x in data]


def os_headers(user: str | None, password: str | None) -> dict[str, str]:
    h = {"Content-Type": "application/json"}
    if user is not None and password is not None:
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        h["Authorization"] = f"Basic {token}"
    return h


def opensearch_search(
    url: str,
    index: str,
    query: str,
    top_k: int,
    user: str | None,
    password: str | None,
    knn_vector: list[float] | None,
    embedding_field: str,
) -> list[tuple[str, float]]:
    endpoint = f"{url.rstrip('/')}/{index}/_search"
    if knn_vector is not None:
        body = {
            "size": top_k,
            "query": {
                "knn": {
                    embedding_field: {
                        "vector": knn_vector,
                        "k": top_k,
                    }
                }
            },
        }
    else:
        body = {
            "size": top_k,
            "query": {
                "multi_match": {
                    "query": query,
                    "fields": [
                        "canonicalPrompt^5",
                        "domainObject^5",
                        "subArea^4",
                        "actionKind^4",
                        "promptGroupId^3",
                        "promptVariants^4",
                        "uiLabels^3",
                        "transitionNames^3",
                        "embeddingText^2",
                        "documentKind",
                        "operationEffect",
                        "preferredService",
                    ],
                }
            },
        }

    req = urllib.request.Request(endpoint, method="POST", data=json.dumps(body).encode("utf-8"))
    for k, v in os_headers(user, password).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=60) as r:
        res = json.loads(r.read().decode("utf-8"))
    out = []
    for h in res.get("hits", {}).get("hits", []):
        src = h.get("_source", {})
        doc_id = src.get("documentId") or h.get("_id")
        out.append((doc_id, float(h.get("_score", 0.0))))
    return out[:top_k]


def evaluate(
    docs: list[dict],
    queries: list[dict],
    mode: str,
    top_k: int,
    openai_model: str,
    opensearch_url: str | None,
    opensearch_index: str | None,
    opensearch_user: str | None,
    opensearch_password: str | None,
    opensearch_knn: bool,
    embedding_field: str,
) -> dict[str, Any]:
    docs_by_id = {d["documentId"]: d for d in docs}

    bm25 = BM25Index.build(docs)
    doc_vecs: dict[str, list[float]] = {}

    if mode == "vector":
        texts = [join_doc_text(d) for d in docs]
        vecs = openai_embed(texts, openai_model)
        for d, v in zip(docs, vecs):
            doc_vecs[d["documentId"]] = v

    total = len(queries)
    hit1 = hit3 = hit5 = 0
    ghit1 = ghit3 = ghit5 = 0
    by_kind = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_effect = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_screen = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_action_kind = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_subarea = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    by_domain_object = defaultdict(lambda: {"n": 0, "h1": 0, "h3": 0, "h5": 0, "gh1": 0, "gh3": 0, "gh5": 0})
    failures = []
    failure_causes = Counter()
    failure_class_counts = Counter()
    mutative_re = re.compile(r"\b(create|update|delete|add|remove|set|approve|cancel|complete)\b")

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

        if mode == "lexical":
            ranked = bm25.retrieve(qq, top_k)
        elif mode == "weighted":
            scored = [(d["documentId"], weighted_lexical_score(qq, d)) for d in docs]
            scored = [x for x in scored if x[1] > 0]
            scored.sort(key=lambda x: x[1], reverse=True)
            ranked = scored[:top_k]
        elif mode == "vector":
            qv = openai_embed([qq], openai_model)[0]
            scored = [(doc_id, cosine(qv, dv)) for doc_id, dv in doc_vecs.items()]
            scored.sort(key=lambda x: x[1], reverse=True)
            ranked = scored[:top_k]
        elif mode == "opensearch":
            if not opensearch_url or not opensearch_index:
                raise RuntimeError("opensearch mode requires --opensearch-url and --opensearch-index")
            qvec = openai_embed([qq], openai_model)[0] if opensearch_knn else None
            ranked = opensearch_search(
                opensearch_url,
                opensearch_index,
                qq,
                top_k,
                opensearch_user,
                opensearch_password,
                qvec,
                embedding_field,
            )
        else:
            raise RuntimeError(f"Unsupported mode: {mode}")

        ranked_ids = [x[0] for x in ranked]
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
            qn = norm(qq)
            ambiguous = False
            failure_class = "true_miss"
            target_action = d.get("actionKind", "unresolved")
            target_domain = d.get("domainObject", "unknown")
            if qn in {"search", "find", "list", "view"} or "search form" in qn or "record by" in qn:
                failure_causes["generic_query_bad"] += 1
                failure_class = "generic_query_bad"
                ambiguous = True
            elif bool(re.search(r"[A-Z]", qq)) or "#" in qq:
                failure_causes["machine_style_query"] += 1
            elif len(tokenize(qn)) <= 2:
                failure_causes["query_too_short"] += 1
                failure_class = "query_too_short"
                ambiguous = True
            elif "order detail" in qn:
                failure_causes["order_detail_ambiguity"] += 1
                ambiguous = True
            else:
                failure_causes["other"] += 1

            # evaluation target error: mutative query evaluated against query/list target
            if dk == "screen_query_prompt" and mutative_re.search(qn):
                # if top contains a mutative operation target, count as evaluation target error
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
        for k, v in sorted(bucket.items()):
            n = max(v["n"], 1)
            out[k] = {
                "n": v["n"],
                "recallAt1": round(v["h1"] / n, 4),
                "recallAt3": round(v["h3"] / n, 4),
                "recallAt5": round(v["h5"] / n, 4),
                "groupRecallAt1": round(v["gh1"] / n, 4),
                "groupRecallAt3": round(v["gh3"] / n, 4),
                "groupRecallAt5": round(v["gh5"] / n, 4),
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
    }


def write_md(path: Path, result: dict[str, Any]) -> None:
    lines = []
    lines.append("# Screen Prompt Retrieval Evaluation")
    lines.append("")
    lines.append(f"- Mode: `{result['mode']}`")
    lines.append(f"- Queries: `{result['queries']}`")
    lines.append(f"- Recall@1: `{result['recallAt1']}`")
    lines.append(f"- Recall@3: `{result['recallAt3']}`")
    lines.append(f"- Recall@5: `{result['recallAt5']}`")
    lines.append(f"- Group Recall@1: `{result.get('groupRecallAt1', 0.0)}`")
    lines.append(f"- Group Recall@3: `{result.get('groupRecallAt3', 0.0)}`")
    lines.append(f"- Group Recall@5: `{result.get('groupRecallAt5', 0.0)}`")
    lines.append("")

    lines.append("## By DocumentKind")
    for k, v in result["byDocumentKind"].items():
        lines.append(
            f"- `{k}`: n={v['n']}, r@1={v['recallAt1']}, r@3={v['recallAt3']}, r@5={v['recallAt5']}, "
            f"gr@1={v.get('groupRecallAt1', 0.0)}, gr@3={v.get('groupRecallAt3', 0.0)}, gr@5={v.get('groupRecallAt5', 0.0)}"
        )
    lines.append("")

    if "screenQueryPromptMetrics" in result:
        lines.append("## Screen Query Prompt")
        for k, v in result["screenQueryPromptMetrics"].items():
            lines.append(
                f"- `{k}`: n={v['n']}, r@1={v['recallAt1']}, r@3={v['recallAt3']}, r@5={v['recallAt5']}, "
                f"gr@1={v.get('groupRecallAt1', 0.0)}, gr@3={v.get('groupRecallAt3', 0.0)}, gr@5={v.get('groupRecallAt5', 0.0)}"
            )
        lines.append("")

    lines.append("## By OperationEffect")
    for k, v in result["byOperationEffect"].items():
        lines.append(
            f"- `{k}`: n={v['n']}, r@1={v['recallAt1']}, r@3={v['recallAt3']}, r@5={v['recallAt5']}, "
            f"gr@1={v.get('groupRecallAt1', 0.0)}, gr@3={v.get('groupRecallAt3', 0.0)}, gr@5={v.get('groupRecallAt5', 0.0)}"
        )
    lines.append("")
    lines.append("## By ActionKind")
    for k, v in result.get("byActionKind", {}).items():
        lines.append(
            f"- `{k}`: n={v['n']}, r@1={v['recallAt1']}, r@3={v['recallAt3']}, r@5={v['recallAt5']}, "
            f"gr@1={v.get('groupRecallAt1', 0.0)}, gr@3={v.get('groupRecallAt3', 0.0)}, gr@5={v.get('groupRecallAt5', 0.0)}"
        )
    lines.append("")

    lines.append("## Failures (Top-3 miss, first 50)")
    for f in result.get("failuresTop3", [])[:50]:
        amb = " ambiguous_by_design" if f.get("ambiguousByDesign") else ""
        fcls = f.get("failureClass", "true_miss")
        lines.append(f"- query=`{f['query']}` target=`{f['targetDocumentId']}` class=`{fcls}`{amb} retrieved={f['topRetrieved']}")
    lines.append("")

    if result.get("failureCauses"):
        lines.append("## Failure Causes")
        for k, v in result["failureCauses"].items():
            lines.append(f"- `{k}`: `{v}`")
        lines.append(f"- `ambiguousFailures`: `{result.get('ambiguousFailures', 0)}`")
        lines.append("")

    if result.get("failureClassCounts"):
        lines.append("## Failure Classes")
        for k, v in result["failureClassCounts"].items():
            lines.append(f"- `{k}`: `{v}`")
        lines.append("")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Evaluate retrieval for screen prompt documents")
    ap.add_argument("--docs", default="output/screen-prompt-documents.jsonl")
    ap.add_argument("--queries", default="output/screen-prompt-eval-queries.jsonl")
    ap.add_argument("--mode", choices=["lexical", "weighted", "vector", "opensearch"], default="lexical")
    ap.add_argument("--top-k", type=int, default=5)
    ap.add_argument("--openai-model", default="text-embedding-3-large")
    ap.add_argument("--out-json", default="output/screen-prompt-retrieval-eval.json")
    ap.add_argument("--out-md", default="output/screen-prompt-retrieval-eval.md")
    ap.add_argument("--opensearch-url", default=None)
    ap.add_argument("--opensearch-index", default=None)
    ap.add_argument("--opensearch-user", default=None)
    ap.add_argument("--opensearch-password", default=None)
    ap.add_argument("--opensearch-knn", action="store_true")
    ap.add_argument("--embedding-field", default="embedding")
    args = ap.parse_args()

    docs = read_jsonl(Path(args.docs))
    queries = read_jsonl(Path(args.queries))

    result = evaluate(
        docs,
        queries,
        mode=args.mode,
        top_k=args.top_k,
        openai_model=args.openai_model,
        opensearch_url=args.opensearch_url,
        opensearch_index=args.opensearch_index,
        opensearch_user=args.opensearch_user,
        opensearch_password=args.opensearch_password,
        opensearch_knn=args.opensearch_knn,
        embedding_field=args.embedding_field,
    )

    out_json = Path(args.out_json)
    out_md = Path(args.out_md)
    out_json.parent.mkdir(parents=True, exist_ok=True)

    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_md(out_md, result)

    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")


if __name__ == "__main__":
    main()
