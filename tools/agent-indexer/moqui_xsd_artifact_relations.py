#!/usr/bin/env python3
"""Extract relation-bearing elements and attributes from Moqui XSD files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

XS_NS = {"xs": "http://www.w3.org/2001/XMLSchema"}
RELATION_ATTRS = {
    "related",
    "entity",
    "entity-name",
    "service-name",
    "validate-service",
    "validate-entity",
    "relationship-name",
    "relationship",
    "service",
    "name",
    "transition",
    "transition-first-row",
    "transition-second-row",
    "transition-last-row",
    "dynamic-transition",
    "ac-transition",
    "default-transition",
    "location",
    "screen-path",
    "target-screen",
    "owner-form",
    "noun",
    "verb",
}


def local_name(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


def classify_relation_attribute(attr_name: str) -> str:
    if attr_name in {"entity-name", "related", "entity", "validate-entity"}:
        return "entity"
    if attr_name in {"service", "name", "service-name", "validate-service"}:
        return "service_or_named_ref"
    if attr_name in {
        "transition",
        "transition-first-row",
        "transition-second-row",
        "transition-last-row",
        "dynamic-transition",
        "ac-transition",
        "default-transition",
    }:
        return "transition"
    if attr_name == "relationship-name":
        return "relationship"
    if attr_name == "relationship":
        return "relationship"
    if attr_name == "location":
        return "location"
    if attr_name in {"screen-path", "target-screen"}:
        return "screen"
    if attr_name == "owner-form":
        return "form"
    if attr_name == "verb":
        return "verb"
    if attr_name == "noun":
        return "noun"
    return "other"


def extract_xsd_file(path: Path) -> dict:
    tree = ET.parse(path)
    root = tree.getroot()
    elements = []
    for element in root.findall(".//xs:element", XS_NS):
        name = element.get("name")
        if not name:
            continue
        attrs = []
        for attr in element.findall(".//xs:attribute", XS_NS):
            attr_name = attr.get("name")
            if attr_name:
                attrs.append(attr_name)
        relation_attrs = sorted({attr_name for attr_name in attrs if attr_name in RELATION_ATTRS})
        if relation_attrs:
            elements.append(
                {
                    "elementName": name,
                    "relationAttributes": relation_attrs,
                    "allAttributes": sorted(set(attrs)),
                }
            )
    return {"schemaName": path.name, "elements": elements}


def extract_xsd_registry(xsd_dir: Path) -> dict[str, dict]:
    registry: dict[str, dict] = {}
    for path in sorted(xsd_dir.glob("*.xsd")):
        payload = extract_xsd_file(path)
        schema_name = payload["schemaName"]
        for element in payload["elements"]:
            element_name = element["elementName"]
            entry = registry.setdefault(
                element_name,
                {
                    "elementName": element_name,
                    "schemaNames": [],
                    "relationAttributes": set(),
                    "allAttributes": set(),
                    "attributeKinds": {},
                },
            )
            if schema_name not in entry["schemaNames"]:
                entry["schemaNames"].append(schema_name)
            entry["relationAttributes"].update(element.get("relationAttributes", []))
            entry["allAttributes"].update(element.get("allAttributes", []))
            for attr_name in element.get("relationAttributes", []):
                entry["attributeKinds"][attr_name] = classify_relation_attribute(attr_name)

    normalized: dict[str, dict] = {}
    for element_name, entry in registry.items():
        normalized[element_name] = {
            "elementName": entry["elementName"],
            "schemaNames": sorted(entry["schemaNames"]),
            "relationAttributes": sorted(entry["relationAttributes"]),
            "allAttributes": sorted(entry["allAttributes"]),
            "attributeKinds": dict(sorted(entry["attributeKinds"].items())),
        }
    return normalized


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xsd-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    xsd_dir = Path(args.xsd_dir)
    payload = {
        "xsdDir": str(xsd_dir),
        "schemas": [extract_xsd_file(path) for path in sorted(xsd_dir.glob("*.xsd"))],
        "elementRegistry": extract_xsd_registry(xsd_dir),
    }
    Path(args.output).write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
