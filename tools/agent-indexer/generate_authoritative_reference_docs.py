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
import re
import subprocess
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET
from zipfile import ZipFile


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")


def clean_text(text: str | None) -> str:
    if not text:
        return ""
    text = text.replace("\r", "\n")
    text = re.sub(r"\{\{[^}]+\}\}", " ", text)
    text = re.sub(r"\[[^\]|]+\|([^\]]+)\]", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]", r"\1", text)
    text = re.sub(r"[*_`#>|]", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def local_name(tag: str) -> str:
    name = tag.split("}", 1)[-1] if "}" in tag else tag
    return name.split(".")[-1]


def load_seed_xml(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def parse_universal_patterns(path: Path) -> list[dict[str, Any]]:
    root = load_seed_xml(path)
    levels_by_pattern: dict[str, list[dict[str, Any]]] = defaultdict(list)
    patterns: dict[str, dict[str, Any]] = {}

    for child in root:
        tag = local_name(child.tag)
        if tag == "AgentUniversalPattern":
            pattern_id = child.attrib["agentUniversalPatternId"]
            patterns[pattern_id] = dict(child.attrib)
        elif tag == "AgentUniversalPatternLevel":
            levels_by_pattern[child.attrib["agentUniversalPatternId"]].append(dict(child.attrib))

    docs: list[dict[str, Any]] = []
    for pattern_id, pattern in sorted(patterns.items(), key=lambda item: int(item[1].get("chapterNumber", "0"))):
        levels = sorted(levels_by_pattern.get(pattern_id, []), key=lambda lvl: lvl.get("patternLevelSeqId", ""))
        level_titles = [lvl.get("levelTitle", "") for lvl in levels if lvl.get("levelTitle")]
        structure_focus = [lvl.get("structureFocus", "") for lvl in levels if lvl.get("structureFocus")]
        mapping_notes = [lvl.get("moquiMappingNotes", "") for lvl in levels if lvl.get("moquiMappingNotes")]
        related_patterns = [lvl.get("mappedAggregatePatternId", "") for lvl in levels if lvl.get("mappedAggregatePatternId")]
        title = pattern.get("chapterTitle") or pattern.get("bookConceptName") or pattern_id
        chapter_number = pattern.get("chapterNumber", "")
        explanation = " ".join(part for part in [
            f"Universal pattern reference chapter {chapter_number}: {title}." if chapter_number else f"Universal pattern reference: {title}.",
            pattern.get("description", ""),
            pattern.get("moquiRelevance", ""),
        ] if part)
        docs.append({
            "documentId": f"agent-ref://universal-pattern/{slug(pattern_id)}",
            "documentKind": "authoritative_universal_pattern",
            "sourceKind": "authoritative_reference",
            "canonicalPrompt": f"understand {pattern.get('patternFamily', 'universal')} pattern",
            "area": "UniversalPatterns",
            "subArea": f"Chapter{chapter_number}" if chapter_number else "UniversalPatterns",
            "domainObject": pattern.get("patternFamily", "") or "UniversalPattern",
            "patternName": title,
            "chapterNumber": int(chapter_number) if chapter_number else None,
            "coreQuestion": pattern.get("coreQuestion", ""),
            "knowledgeCategory": "authoritative_reference",
            "relatedEntities": [],
            "requiredEntities": [],
            "optionalEntities": [],
            "serviceSequence": [],
            "sourceArtifacts": [str(path)],
            "sourceExamples": [str(path)],
            "businessQuestions": [
                f"What does the {title} pattern mean in Moqui?",
                f"How should the agent use the {pattern.get('patternFamily', 'universal')} pattern during planning?",
            ],
            "processHints": [hint for hint in structure_focus[:4] if hint] + [hint for hint in mapping_notes[:4] if hint],
            "relatedAgentPrompts": [],
            "businessValidity": "authoritative_reference",
            "verifiedByTest": False,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
            "sourceTitle": "Universal pattern reference volume",
            "humanExplanation": explanation,
            "levelTitles": level_titles,
            "mappedAggregatePatterns": related_patterns,
            "embeddingText": " ".join(part for part in [
                title,
                pattern.get("bookConceptName", ""),
                pattern.get("patternFamily", ""),
                pattern.get("coreQuestion", ""),
                explanation,
                " ".join(level_titles),
                " ".join(structure_focus),
                " ".join(mapping_notes),
                "mapped aggregate patterns " + ", ".join(related_patterns) if related_patterns else "",
            ] if part).strip(),
        })
    return docs


def parse_aggregate_patterns(path: Path) -> list[dict[str, Any]]:
    root = load_seed_xml(path)
    members_by_pattern: dict[str, list[dict[str, Any]]] = defaultdict(list)
    patterns: dict[str, dict[str, Any]] = {}

    for child in root:
        tag = local_name(child.tag)
        if tag == "AgentAggregatePattern":
            patterns[child.attrib["agentAggregatePatternId"]] = dict(child.attrib)
        elif tag == "AgentAggregatePatternMember":
            members_by_pattern[child.attrib["agentAggregatePatternId"]].append(dict(child.attrib))

    docs: list[dict[str, Any]] = []
    for pattern_id, pattern in sorted(patterns.items(), key=lambda item: item[1].get("patternName", "")):
        members = sorted(members_by_pattern.get(pattern_id, []), key=lambda m: m.get("patternMemberSeqId", ""))
        related_entities = [m.get("entityName", "") for m in members if m.get("entityName")]
        member_roles = [f"{m.get('memberName', m.get('entityName', ''))}: {m.get('memberRoleType', '')}" for m in members]
        title = pattern.get("patternName") or pattern_id
        explanation = " ".join(part for part in [
            f"Aggregate structure reference: {title}.",
            pattern.get("description", ""),
            pattern.get("usageNotes", ""),
        ] if part)
        docs.append({
            "documentId": f"agent-ref://aggregate-pattern/{slug(pattern_id)}",
            "documentKind": "authoritative_aggregate_pattern",
            "sourceKind": "authoritative_reference",
            "canonicalPrompt": f"understand {title.lower()} aggregate pattern",
            "area": "UniversalPatterns",
            "subArea": "AggregatePatterns",
            "domainObject": pattern.get("rootEntityName", "") or title,
            "patternName": title,
            "patternType": pattern.get("patternType", ""),
            "knowledgeCategory": "authoritative_reference",
            "relatedEntities": related_entities,
            "requiredEntities": related_entities,
            "optionalEntities": [],
            "serviceSequence": [],
            "sourceArtifacts": [str(path)],
            "sourceExamples": [str(path)],
            "businessQuestions": [
                f"What root and child entities define the {title} structure?",
                f"How should the planner decompose requests that follow the {title} pattern?",
            ],
            "processHints": member_roles[:8],
            "relatedAgentPrompts": [],
            "businessValidity": "authoritative_reference",
            "verifiedByTest": False,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
            "sourceTitle": "Aggregate pattern reference",
            "humanExplanation": explanation,
            "rootEntityName": pattern.get("rootEntityName", ""),
            "memberRoles": member_roles,
            "embeddingText": " ".join(part for part in [
                title,
                pattern.get("patternType", ""),
                pattern.get("rootEntityName", ""),
                pattern.get("rootPkFieldNames", ""),
                pattern.get("rootReferenceFieldName", ""),
                pattern.get("parentReferenceFieldName", ""),
                pattern.get("sequenceFieldNames", ""),
                explanation,
                " ".join(related_entities),
                " ".join(member_roles),
            ] if part).strip(),
        })
    return docs


def parse_moqui_org_pages(path: Path) -> list[dict[str, Any]]:
    root = load_seed_xml(path)
    page_by_id: dict[str, dict[str, str]] = {}
    content_by_id: dict[str, str] = {}

    for child in root:
        tag = local_name(child.tag)
        attrs = dict(child.attrib)
        if tag == "WikiPage":
            page_by_id[attrs.get("wikiPageId", "")] = attrs
        elif tag == "DbResourceFile":
            resource_id = attrs.get("resourceId", "")
            file_data = child.findtext("fileData") or ""
            content_by_id[resource_id] = clean_text(file_data)

    docs: list[dict[str, Any]] = []
    for page_id, page in sorted(page_by_id.items(), key=lambda item: item[1].get("pagePath", "")):
        page_path = page.get("pagePath", page_id)
        content = content_by_id.get(page_id, "")
        if not content:
            continue
        short_content = " ".join(content.split()[:400])
        docs.append({
            "documentId": f"agent-ref://moqui-org/{slug(page_id)}",
            "documentKind": "authoritative_moqui_guide",
            "sourceKind": "authoritative_reference",
            "canonicalPrompt": f"understand {page_path.lower()} in moqui",
            "area": "MoquiGuides",
            "subArea": "moqui-org",
            "domainObject": page_path,
            "patternName": page_path,
            "knowledgeCategory": "authoritative_reference",
            "relatedEntities": [],
            "requiredEntities": [],
            "optionalEntities": [],
            "serviceSequence": [],
            "sourceArtifacts": [str(path)],
            "sourceExamples": [str(path)],
            "businessQuestions": [
                f"What guidance does the {page_path} page provide?",
                f"How does {page_path} fit into Moqui usage or deployment?",
            ],
            "processHints": [],
            "relatedAgentPrompts": [],
            "businessValidity": "authoritative_reference",
            "verifiedByTest": False,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
            "sourceTitle": "moqui-org guide pages",
            "humanExplanation": f"Authoritative Moqui guide page: {page_path}.",
            "embeddingText": f"{page_path}. {short_content}".strip(),
        })
    return docs


def read_pdf_preview(path: Path, first_page: int = 1, last_page: int = 6) -> str:
    proc = subprocess.run(
        ["pdftotext", "-f", str(first_page), "-l", str(last_page), str(path), "-"],
        check=True,
        capture_output=True,
        text=True,
    )
    return clean_text(proc.stdout)


def parse_making_apps_pdf(path: Path) -> list[dict[str, Any]]:
    preview = read_pdf_preview(path, 1, 8)
    lines = [line.strip() for line in preview.split(" ") if line.strip()]
    title = "Making Apps with Moqui"
    explanation = "Authoritative Moqui guidebook covering framework concepts, application artifacts, execution context, and development approach."
    return [{
        "documentId": "agent-ref://moqui-guide/making-apps-overview",
        "documentKind": "authoritative_moqui_guide",
        "sourceKind": "authoritative_reference",
        "canonicalPrompt": "understand making apps with moqui",
        "area": "MoquiGuides",
        "subArea": "MakingApps",
        "domainObject": "MoquiApplicationDevelopment",
        "patternName": title,
        "knowledgeCategory": "authoritative_reference",
        "relatedEntities": [],
        "requiredEntities": [],
        "optionalEntities": [],
        "serviceSequence": [],
        "sourceArtifacts": [str(path)],
        "sourceExamples": [str(path)],
        "businessQuestions": [
            "What is the high-level development model in Moqui?",
            "Which core Moqui concepts matter when building applications?",
        ],
        "processHints": [
            "Use this reference for framework concepts, artifact understanding, and application composition.",
            "Use moqui-org pages for runnable tutorials and operational guidance.",
        ],
        "relatedAgentPrompts": [],
        "businessValidity": "authoritative_reference",
        "verifiedByTest": False,
        "knowledgeOnly": True,
        "runtimeExecutable": False,
        "sourceTitle": title,
        "humanExplanation": explanation,
        "embeddingText": f"{title}. {explanation} {preview[:4000]}".strip(),
    }]


def parse_epub_chapters(path: Path) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    with ZipFile(path) as zf:
        container = ET.fromstring(zf.read("META-INF/container.xml"))
        opf_path = container.find(".//{*}rootfile").get("full-path")
        opf = ET.fromstring(zf.read(opf_path))
        title = opf.findtext(".//{http://purl.org/dc/elements/1.1/}title") or "Universal pattern reference volume"
        toc = ET.fromstring(zf.read("OEBPS/toc.ncx"))
        ns = {"n": "http://www.daisy.org/z3986/2005/ncx/"}
        chapter_entries: list[tuple[str, str]] = []
        for nav in toc.findall(".//n:navPoint", ns):
            label = (nav.findtext("n:navLabel/n:text", default="", namespaces=ns) or "").strip()
            src = nav.find("n:content", ns)
            src_val = src.get("src") if src is not None else ""
            if label.startswith("Chapter "):
                chapter_entries.append((label, src_val))

    for label, src in chapter_entries:
        domain = label.split(":", 1)[-1].strip() if ":" in label else label
        docs.append({
            "documentId": f"agent-ref://universal-volume/{slug(label)}",
            "documentKind": "authoritative_chapter_reference",
            "sourceKind": "authoritative_reference",
            "canonicalPrompt": f"understand {domain.lower()} pattern chapter",
            "area": "UniversalPatterns",
            "subArea": "VolumeReference",
            "domainObject": domain or label,
            "patternName": label,
            "knowledgeCategory": "authoritative_reference",
            "relatedEntities": [],
            "requiredEntities": [],
            "optionalEntities": [],
            "serviceSequence": [],
            "sourceArtifacts": [str(path)],
            "sourceExamples": [str(path)],
            "businessQuestions": [
                f"What modeling problem does {label} address?",
                f"How does {label} connect to Moqui artifact and data patterns?",
            ],
            "processHints": ["Use together with seeded universal pattern metadata and aggregate pattern mappings."],
            "relatedAgentPrompts": [],
            "businessValidity": "authoritative_reference",
            "verifiedByTest": False,
            "knowledgeOnly": True,
            "runtimeExecutable": False,
            "sourceTitle": title,
            "humanExplanation": f"Authoritative chapter reference: {label}.",
            "embeddingText": f"{title}. {label}. Chapter source {src}. Connect this chapter to Moqui aggregate, classification, status, role, contact, and rule patterns.",
        })
    return docs


def write_jsonl(path: Path, docs: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for doc in docs:
            fh.write(json.dumps(doc, ensure_ascii=True) + "\n")


def write_summary(path: Path, docs: list[dict[str, Any]]) -> None:
    by_kind = Counter(doc.get("documentKind") for doc in docs)
    by_area = Counter(doc.get("area") for doc in docs)
    lines = [
        "# Authoritative Reference Summary",
        "",
        f"- Documents: `{len(docs)}`",
        "",
        "## By Kind",
        "",
    ]
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_kind.most_common())
    lines.extend(["", "## By Area", ""])
    lines.extend(f"- `{k}`: `{v}`" for k, v in by_area.most_common())
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate authoritative reference docs from Moqui guides and universal pattern sources")
    ap.add_argument("--universal-pattern-seed", required=True)
    ap.add_argument("--aggregate-pattern-seed", required=True)
    ap.add_argument("--moqui-org-page-data", required=True)
    ap.add_argument("--making-apps-pdf", required=True)
    ap.add_argument("--pattern-reference-epub", required=True)
    ap.add_argument("--output-dir", default="output")
    args = ap.parse_args()

    out_dir = Path(args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    docs: list[dict[str, Any]] = []
    docs.extend(parse_universal_patterns(Path(args.universal_pattern_seed)))
    docs.extend(parse_aggregate_patterns(Path(args.aggregate_pattern_seed)))
    docs.extend(parse_moqui_org_pages(Path(args.moqui_org_page_data)))
    docs.extend(parse_making_apps_pdf(Path(args.making_apps_pdf)))
    docs.extend(parse_epub_chapters(Path(args.pattern_reference_epub)))
    docs.sort(key=lambda d: (d.get("documentKind", ""), d.get("area", ""), d.get("documentId", "")))

    jsonl_path = out_dir / "global-authoritative-reference-documents.jsonl"
    summary_path = out_dir / "global-authoritative-reference-summary.md"
    write_jsonl(jsonl_path, docs)
    write_summary(summary_path, docs)
    print(f"Wrote {jsonl_path}")
    print(f"Wrote {summary_path}")
    print(f"Generated {len(docs)} authoritative reference documents")


if __name__ == "__main__":
    main()
