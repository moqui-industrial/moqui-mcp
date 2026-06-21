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
from pathlib import Path
from xml.etree import ElementTree as ET

XS_NS = {"xs": "http://www.w3.org/2001/XMLSchema"}
ABSTRACT_GROUPS = {
    "CallOperations", "EnvOperations", "EntityMiscOperations", "EntityFindOperations",
    "EntityValueOperations", "EntityListOperations", "ControlOperations", "XmlOperations",
    "IfCombineConditions", "IfBasicOperations", "IfOtherOperations", "OtherOperations"
}
ROOT_HELPERS = {"actions", "condition"}


def _local_name(value: str | None) -> str:
    if not value:
        return ""
    return value.split("}", 1)[-1].split(":", 1)[-1]


def find_xml_actions_xsd(moqui_root: Path) -> Path:
    roots = [moqui_root] + list(moqui_root.parents)
    candidates: list[Path] = []
    for root in roots:
        candidates.extend(
            [
                root / "framework" / "xsd" / "xml-actions-3.xsd",
                root / "moqui-framework" / "framework" / "xsd" / "xml-actions-3.xsd",
            ]
        )
    seen: set[str] = set()
    for candidate in candidates:
        candidate_key = str(candidate)
        if candidate_key in seen:
            continue
        seen.add(candidate_key)
        if candidate.exists():
            return candidate
    raise FileNotFoundError(f"Could not find xml-actions-3.xsd under {moqui_root}")


def extract_action_grammar(xsd_path: Path) -> dict[str, dict]:
    tree = ET.parse(xsd_path)
    root = tree.getroot()
    grammar: dict[str, dict] = {}

    for element in root.findall("xs:element", XS_NS):
        name = element.get("name")
        if not name or name in ABSTRACT_GROUPS or name in ROOT_HELPERS:
            continue
        if element.get("abstract") == "true":
            continue

        attr_names = sorted({
            attr.get("name")
            for attr in element.findall(".//xs:attribute", XS_NS)
            if attr.get("name")
        })
        child_names = sorted({
            child.get("name") or _local_name(child.get("ref"))
            for child in element.findall(".//xs:element", XS_NS)
            if (child.get("name") or child.get("ref"))
        })
        child_names = [child for child in child_names if child and child != name]

        grammar[name] = {
            "attributes": attr_names,
            "children": child_names,
            "substitutionGroup": element.get("substitutionGroup"),
        }

    return dict(sorted(grammar.items()))


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract Moqui xml-actions grammar from xml-actions-3.xsd")
    parser.add_argument("--moqui-root", required=True, help="Path to Moqui root or workspace containing framework/xsd")
    parser.add_argument("--output", required=True, help="Output JSON file")
    args = parser.parse_args()

    xsd_path = find_xml_actions_xsd(Path(args.moqui_root))
    grammar = extract_action_grammar(xsd_path)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(grammar, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
