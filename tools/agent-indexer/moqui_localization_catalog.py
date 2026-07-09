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
from collections import defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET


def local_name(tag: str | None) -> str:
    if not tag:
        return ""
    return tag.split("}", 1)[-1]


def iter_xml_files(scan_roots: list[Path]) -> list[Path]:
    files: list[Path] = []
    for scan_root in scan_roots:
        if not scan_root.exists():
            continue
        files.extend(
            path for path in scan_root.rglob("*.xml")
            if all(part not in {"build", "bin", "lib", ".gradle"} for part in path.parts)
        )
    return sorted({path for path in files})


def screen_relative_path(path: Path) -> str:
    parts = list(path.parts)
    if "screen" in parts:
        idx = parts.index("screen")
        rel_parts = parts[idx + 2 :] if len(parts) > idx + 2 else parts[idx + 1 :]
        if rel_parts:
            return str(Path(*rel_parts)).replace("\\", "/")
    return path.name


def message_uri(original: str) -> str:
    return f"message://{original}"


def entity_field_uri(entity_name: str, field_name: str) -> str:
    return f"entityField://{entity_name}.{field_name}"


def entity_field_value_uri(entity_name: str, field_name: str, pk_value: str) -> str:
    return f"entityFieldValue://{entity_name}.{field_name}#{pk_value}"


def transition_uri(screen_path: str, transition_name: str) -> str:
    return f"transition://{screen_path}#{transition_name}"


def form_uri(screen_path: str, form_name: str) -> str:
    return f"form://{screen_path}#{form_name}"


def field_uri(screen_path: str, form_name: str, field_name: str) -> str:
    return f"field://{screen_path}#{form_name}.{field_name}"


def button_uri(screen_path: str, button_name: str) -> str:
    return f"button://{screen_path}#{button_name}"


def screen_uri(screen_path: str) -> str:
    return f"screen://{screen_path}"


def add_label_entry(entries: dict[str, dict], uri: str, technical_name: str, labels: dict[str, str], source: str, **extra) -> None:
    clean_labels = {locale: value for locale, value in labels.items() if value}
    if not clean_labels:
        return
    entry = entries.setdefault(
        uri,
        {
            "technicalName": technical_name,
            "labels": {},
            "source": source,
        },
    )
    entry["technicalName"] = technical_name or entry.get("technicalName")
    entry["source"] = source or entry.get("source")
    entry["labels"].update(clean_labels)
    for key, value in extra.items():
        if value not in (None, "", []):
            entry[key] = value


def load_message_catalog(xml_files: list[Path]) -> dict[str, dict[str, str]]:
    catalog: dict[str, dict[str, str]] = defaultdict(dict)
    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except Exception:
            continue
        for element in root.iter():
            tag = local_name(element.tag)
            if not (tag.endswith("LocalizedMessage") or tag == "LocalizedMessage"):
                continue
            original = element.attrib.get("original", "")
            locale = element.attrib.get("locale", "")
            localized = element.attrib.get("localized", "")
            if original and locale and localized:
                catalog[original][locale] = localized
    return dict(catalog)


def extract_default_field_label(field_element: ET.Element) -> str:
    for descendant in field_element.iter():
        if descendant is field_element:
            continue
        title = descendant.attrib.get("title")
        if title:
            return title.strip()
    return ""


def build_catalog(scan_roots: list[Path]) -> dict[str, dict]:
    xml_files = iter_xml_files(scan_roots)
    message_catalog = load_message_catalog(xml_files)
    entries: dict[str, dict] = {}

    for original, labels in sorted(message_catalog.items()):
        add_label_entry(
            entries,
            message_uri(original),
            technical_name=original,
            labels=labels,
            source="localized_message",
            original=original,
        )

    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except Exception:
            continue

        root_tag = local_name(root.tag)

        for element in root.iter():
            tag = local_name(element.tag)
            if tag.endswith("LocalizedEntityField") or tag == "LocalizedEntityField":
                entity_name = element.attrib.get("entityName", "")
                field_name = element.attrib.get("fieldName", "")
                locale = element.attrib.get("locale", "")
                localized = element.attrib.get("localized", "")
                pk_value = element.attrib.get("pkValue", "")
                if not (entity_name and field_name and locale and localized):
                    continue
                uri = entity_field_value_uri(entity_name, field_name, pk_value) if pk_value else entity_field_uri(entity_name, field_name)
                add_label_entry(
                    entries,
                    uri,
                    technical_name=field_name,
                    labels={locale: localized},
                    source="localized_entity_field",
                    entityName=entity_name,
                    fieldName=field_name,
                    pkValue=pk_value or None,
                )

        if root_tag != "screen":
            continue

        screen_path = screen_relative_path(xml_file)
        default_screen_title = root.attrib.get("default-menu-title", "")
        if default_screen_title:
            labels = {"default": default_screen_title}
            labels.update(message_catalog.get(default_screen_title, {}))
            add_label_entry(
                entries,
                screen_uri(screen_path),
                technical_name=xml_file.stem,
                labels=labels,
                source="screen",
                screenPath=screen_path,
            )

        for transition in root.findall("transition"):
            transition_name = transition.attrib.get("name", "")
            raw_label = transition.attrib.get("text") or transition.attrib.get("title") or ""
            if raw_label:
                labels = {"default": raw_label}
                labels.update(message_catalog.get(raw_label, {}))
                add_label_entry(
                    entries,
                    transition_uri(screen_path, transition_name),
                    technical_name=transition_name,
                    labels=labels,
                    source="screen_transition",
                    screenPath=screen_path,
                    transitionName=transition_name,
                )

        for element in root.iter():
            tag = local_name(element.tag)
            if tag in {"form-single", "form-list"}:
                form_name = element.attrib.get("name", "")
                if not form_name:
                    continue
                title = element.attrib.get("title", "")
                if title:
                    labels = {"default": title}
                    labels.update(message_catalog.get(title, {}))
                    add_label_entry(
                        entries,
                        form_uri(screen_path, form_name),
                        technical_name=form_name,
                        labels=labels,
                        source="screen_form",
                        screenPath=screen_path,
                        formName=form_name,
                    )
                for field in element.findall("field"):
                    field_name = field.attrib.get("name", "")
                    if not field_name:
                        continue
                    label = extract_default_field_label(field)
                    if not label:
                        continue
                    labels = {"default": label}
                    labels.update(message_catalog.get(label, {}))
                    add_label_entry(
                        entries,
                        field_uri(screen_path, form_name, field_name),
                        technical_name=field_name,
                        labels=labels,
                        source="screen_field",
                        screenPath=screen_path,
                        formName=form_name,
                        fieldName=field_name,
                    )
            if tag == "container-dialog":
                button_text = element.attrib.get("button-text", "")
                dialog_id = element.attrib.get("id", "")
                if button_text and dialog_id:
                    labels = {"default": button_text}
                    labels.update(message_catalog.get(button_text, {}))
                    add_label_entry(
                        entries,
                        button_uri(screen_path, dialog_id),
                        technical_name=dialog_id,
                        labels=labels,
                        source="screen_button",
                        screenPath=screen_path,
                    )
            if tag in {"link", "link-button"}:
                text = element.attrib.get("text", "")
                transition_name = element.attrib.get("transition", "")
                if text and transition_name:
                    labels = {"default": text}
                    labels.update(message_catalog.get(text, {}))
                    add_label_entry(
                        entries,
                        transition_uri(screen_path, transition_name),
                        technical_name=transition_name,
                        labels=labels,
                        source="screen_transition",
                        screenPath=screen_path,
                        transitionName=transition_name,
                    )

    return dict(sorted(entries.items()))


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Moqui localization catalog from LocalizedMessage, LocalizedEntityField, and screen labels")
    parser.add_argument("--scan-root", action="append", required=True, help="Root path to scan for XML files; may be specified multiple times")
    parser.add_argument("--output", required=True, help="Output JSON file")
    args = parser.parse_args()

    scan_roots = [Path(value) for value in args.scan_root]
    catalog = build_catalog(scan_roots)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
