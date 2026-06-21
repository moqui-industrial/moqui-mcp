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
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET


FILE_NAME_PATTERNS = [
    "*Seed*.xml",
    "*SeedData*.xml",
    "*Demo*.xml",
    "*DemoData*.xml",
    "*Install*.xml",
    "*InstallData*.xml",
    "*Test*.xml",
    "*TestData*.xml",
    "*.spock",
    "*Spec.groovy",
    "*Tests.groovy",
    "*Test.groovy",
]

EXCLUDED_DIR_NAMES = {
    ".git",
    ".gradle",
    "build",
    "lib",
    "target",
    "node_modules",
    "__pycache__",
    "output",
}

SERVICE_NAME_RE = re.compile(r"""name\s*\(\s*['"]([^'"]+)['"]\s*\)""")
SERVICE_XML_RE = re.compile(r"""service-name\s*=\s*["']([^"']+)["']""")
ASSERTION_RE = re.compile(r"""\b(assert|expect:|then:|thrown\s*\(|noExceptionThrown\s*\()""")
SPOCK_RE = re.compile(r"""spock\.lang|given:|when:|then:|expect:""", re.IGNORECASE)

AREA_RULES = [
    ("ProductStore", ["productstore", "product store"]),
    ("Product", ["productprice", "product", "catalog"]),
    ("Order", ["order", "orderpart", "orderitem"]),
    ("Invoice", ["invoice"]),
    ("Payment", ["payment"]),
    ("Shipment", ["shipment", "shipping"]),
    ("Return", ["return"]),
    ("Accounting", ["glaccount", "ledger", "financialaccount", "acctg", "accounting"]),
    ("Party", ["party", "customer", "supplier", "vendor"]),
    ("Facility", ["facility", "inventory"]),
    ("WorkEffort", ["workeffort", "project", "task", "manufacturing"]),
    ("Asset", ["asset"]),
    ("Request", ["request"]),
    ("Communication", ["communication", "email"]),
    ("Content", ["content", "wiki"]),
]


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def local_name(tag: str) -> str:
    if "}" in tag:
        return tag.rsplit("}", 1)[1]
    return tag


def looks_interesting(path: Path) -> bool:
    path_str = str(path)
    if any(part in EXCLUDED_DIR_NAMES for part in path.parts):
        return False
    for pattern in FILE_NAME_PATTERNS:
        if path.match(pattern) or path.name.endswith(pattern.replace("*", "")):
            return True
    lowered = path_str.lower()
    if any(seg in lowered for seg in ["/test/", "/tests/", "/data/", "/demo/", "/install/"]):
        if path.suffix.lower() in {".xml", ".groovy", ".spock"}:
            return True
    return False


def classify_xml(path: Path, root: ET.Element, text: str) -> tuple[str, float, str]:
    lower_path = str(path).lower()
    file_name = path.name.lower()
    root_name = local_name(root.tag)
    type_attr = (root.attrib.get("type") or "").strip().lower()

    if root_name == "entity-facade-xml":
        if type_attr == "seed-initial":
            return "seed_initial", 0.99, "entity-facade-xml type=seed-initial"
        if type_attr == "seed":
            return "seed", 0.99, "entity-facade-xml type=seed"
        if type_attr == "demo":
            return "demo", 0.99, "entity-facade-xml type=demo"
        if type_attr == "install":
            return "install", 0.99, "entity-facade-xml type=install"
        if "test" in type_attr:
            return "test_data", 0.96, f"entity-facade-xml type={type_attr}"

    if "/screen/" in lower_path and "test" in file_name:
        return "test_screen", 0.95, "screen XML test artifact"
    if "/service/" in lower_path and "test" in file_name:
        return "service_test", 0.95, "service XML test artifact"
    if "/src/test/" in lower_path or "/tests/" in lower_path or "/test/" in lower_path:
        return "test_data", 0.85, "XML file under test directory"
    if "install" in file_name or "/install" in lower_path:
        return "install", 0.82, "install naming/path heuristic"
    if "demo" in file_name or "/demo" in lower_path:
        return "demo", 0.82, "demo naming/path heuristic"
    if "seed" in file_name:
        return "seed", 0.82, "seed naming heuristic"
    if SERVICE_XML_RE.search(text):
        return "service_test", 0.65, "XML contains service-call style content"
    return "unknown_data", 0.35, "XML did not match seed/demo/test heuristics"


def classify_groovy(path: Path, text: str) -> tuple[str, float, str]:
    lower_path = str(path).lower()
    file_name = path.name.lower()
    if path.suffix.lower() == ".spock" or "spec.groovy" in file_name or SPOCK_RE.search(text):
        return "spock_test", 0.98, "Spock naming/content"
    if "/src/test/" in lower_path or "/tests/" in lower_path or "/test/" in lower_path:
        return "service_test", 0.84, "Groovy test under test directory"
    return "unknown_data", 0.40, "Groovy file did not match test heuristics"


def detect_component(path: Path, scan_root: Path) -> str:
    parts = path.parts
    if "runtime" in parts:
        try:
            runtime_idx = parts.index("runtime")
            if len(parts) > runtime_idx + 2 and parts[runtime_idx + 1] == "component":
                return parts[runtime_idx + 2]
        except ValueError:
            pass
    rel_parts = path.relative_to(scan_root).parts
    if rel_parts and rel_parts[0] == "mantle" and len(rel_parts) > 1 and rel_parts[1].startswith("mantle-"):
        return rel_parts[1]
    if rel_parts and rel_parts[0].startswith("moqui-"):
        return rel_parts[0]
    if rel_parts and rel_parts[0] in {"SimpleScreens", "PopCommerce", "MarbleERP", "WeCreate", "AuthorizeDotNet", "HiveMind", "PopRestStore", "example", "start"}:
        return rel_parts[0]
    if rel_parts:
        return rel_parts[0]
    return scan_root.name


def infer_business_areas(path: Path, entity_names: list[str], service_calls: list[str], screen_transitions: list[str]) -> list[str]:
    haystack = " ".join([
        str(path).lower(),
        " ".join(entity_names).lower(),
        " ".join(service_calls).lower(),
        " ".join(screen_transitions).lower(),
    ])
    out: list[str] = []
    for area, keywords in AREA_RULES:
        if any(keyword in haystack for keyword in keywords):
            out.append(area)
    return out


def extract_from_xml(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    root = ET.fromstring(text)
    entity_names: list[str] = []
    service_calls: list[str] = []
    screen_transitions: list[str] = []
    xml_root_tag = local_name(root.tag)

    if xml_root_tag == "entity-facade-xml":
        for child in root:
            entity_names.append(local_name(child.tag))
    else:
        for elem in root.iter():
            name = local_name(elem.tag)
            if name in {"service-call", "service-call-sync", "service-call-async", "call-service"}:
                service_name = elem.attrib.get("name") or elem.attrib.get("service-name")
                if service_name:
                    service_calls.append(service_name)
            if name == "transition" and elem.attrib.get("name"):
                screen_transitions.append(elem.attrib["name"])

    source_kind, confidence, reason = classify_xml(path, root, text)
    return {
        "sourceKind": source_kind,
        "confidence": confidence,
        "reason": reason,
        "xmlRootTag": xml_root_tag,
        "entityNames": sorted(set(entity_names)),
        "entityCount": len(entity_names),
        "serviceCalls": sorted(set(service_calls)),
        "screenTransitions": sorted(set(screen_transitions)),
        "assertionCount": 0,
        "spockIndicators": [],
    }


def extract_from_groovy(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    service_calls = SERVICE_NAME_RE.findall(text)
    assertion_hits = ASSERTION_RE.findall(text)
    indicators = sorted(set(m.group(0) for m in SPOCK_RE.finditer(text)))
    source_kind, confidence, reason = classify_groovy(path, text)
    return {
        "sourceKind": source_kind,
        "confidence": confidence,
        "reason": reason,
        "xmlRootTag": None,
        "entityNames": [],
        "entityCount": 0,
        "serviceCalls": sorted(set(service_calls)),
        "screenTransitions": [],
        "assertionCount": len(assertion_hits),
        "spockIndicators": indicators[:20],
    }


def include_in_index(source_kind: str, extracted: dict[str, Any]) -> bool:
    if source_kind == "unknown_data":
        return False
    if extracted["entityCount"] > 0:
        return True
    if extracted["serviceCalls"] or extracted["screenTransitions"] or extracted["assertionCount"] > 0:
        return True
    return False


def walk_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if looks_interesting(path):
            files.append(path)
    return sorted(files)


def summarize_markdown(payload: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("# Source Knowledge Inventory")
    lines.append("")
    lines.append(f"- Generated at: `{payload['generatedAt']}`")
    lines.append(f"- Scan roots: `{', '.join(payload['scanRoots'])}`")
    lines.append(f"- Total files: `{payload['totals']['files']}`")
    lines.append(f"- Included in knowledge index: `{payload['totals']['included']}`")
    lines.append("")
    lines.append("## By Source Kind")
    lines.append("")
    for kind, count in payload["totals"]["bySourceKind"].items():
        lines.append(f"- `{kind}`: `{count}`")
    lines.append("")
    lines.append("## Top Components")
    lines.append("")
    for component, count in payload["totals"]["byComponent"][:20]:
        lines.append(f"- `{component}`: `{count}`")
    lines.append("")
    lines.append("## Included Sources")
    lines.append("")
    lines.append("| Source Kind | Component | Areas | Source File | Reason |")
    lines.append("| --- | --- | --- | --- | --- |")
    for entry in payload["entries"]:
        if not entry["includeInKnowledgeIndex"]:
            continue
        areas = ", ".join(entry["businessAreas"][:4])
        lines.append(
            f"| `{entry['sourceKind']}` | `{entry['component']}` | `{areas}` | `{entry['sourceFile']}` | {entry['reason']} |"
        )
    return "\n".join(lines) + "\n"


def build_inventory(scan_roots: list[Path]) -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    source_kind_counter: Counter[str] = Counter()
    component_counter: Counter[str] = Counter()
    included = 0

    for root in scan_roots:
        for path in walk_files(root):
            try:
                if path.suffix.lower() == ".xml":
                    extracted = extract_from_xml(path)
                elif path.suffix.lower() in {".groovy", ".spock"}:
                    extracted = extract_from_groovy(path)
                else:
                    continue
            except Exception as exc:
                extracted = {
                    "sourceKind": "unknown_data",
                    "confidence": 0.10,
                    "reason": f"parse failure: {exc.__class__.__name__}",
                    "xmlRootTag": None,
                    "entityNames": [],
                    "entityCount": 0,
                    "serviceCalls": [],
                    "screenTransitions": [],
                    "assertionCount": 0,
                    "spockIndicators": [],
                }

            component = detect_component(path, root)
            areas = infer_business_areas(path, extracted["entityNames"], extracted["serviceCalls"], extracted["screenTransitions"])
            include_flag = include_in_index(extracted["sourceKind"], extracted)
            if include_flag:
                included += 1

            entry = {
                "sourceFile": str(path),
                "component": component,
                "sourceKind": extracted["sourceKind"],
                "xmlRootTag": extracted["xmlRootTag"],
                "entityCount": extracted["entityCount"],
                "entityNames": extracted["entityNames"],
                "serviceCalls": extracted["serviceCalls"],
                "screenTransitions": extracted["screenTransitions"],
                "assertionCount": extracted["assertionCount"],
                "spockIndicators": extracted["spockIndicators"],
                "businessAreas": areas,
                "confidence": round(float(extracted["confidence"]), 2),
                "includeInKnowledgeIndex": include_flag,
                "reason": extracted["reason"],
            }
            entries.append(entry)
            source_kind_counter[entry["sourceKind"]] += 1
            component_counter[component] += 1

    entries.sort(key=lambda e: (not e["includeInKnowledgeIndex"], e["sourceKind"], e["component"], e["sourceFile"]))
    return {
        "generatedAt": iso_now(),
        "scanRoots": [str(p) for p in scan_roots],
        "totals": {
            "files": len(entries),
            "included": included,
            "bySourceKind": dict(sorted(source_kind_counter.items())),
            "byComponent": sorted(component_counter.items(), key=lambda kv: (-kv[1], kv[0])),
        },
        "entries": entries,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Collect seed/demo/install/test knowledge sources for moqui-mcp")
    parser.add_argument("--scan-root", dest="scan_roots", action="append", required=True,
                        help="Root directory to scan recursively; repeat for multiple roots")
    parser.add_argument("--output-dir", default="output", help="Output directory for inventory artifacts")
    args = parser.parse_args()

    scan_roots = [Path(p).resolve() for p in args.scan_roots if Path(p).exists()]
    if not scan_roots:
        raise SystemExit("No valid --scan-root paths were provided")

    repo_root = Path(__file__).resolve().parent
    output_dir = (repo_root / args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    payload = build_inventory(scan_roots)
    json_path = output_dir / "source-knowledge-inventory.json"
    md_path = output_dir / "source-knowledge-inventory.md"

    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=True), encoding="utf-8")
    md_path.write_text(summarize_markdown(payload), encoding="utf-8")

    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")
    print(f"Included {payload['totals']['included']} of {payload['totals']['files']} discovered sources")


if __name__ == "__main__":
    main()
