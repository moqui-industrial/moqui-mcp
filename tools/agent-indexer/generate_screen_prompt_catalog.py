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
Screen-derived Prompt Catalog generator (screen-first).

Primary source of prompts: screen artifacts.
Services/entities/views/ECA are enrichment only.

Outputs:
- screen-prompt-documents.jsonl
- task-group-documents.jsonl
- area-overview-documents.jsonl
- screen-prompt-eval-queries.jsonl
- support-service-documents.jsonl
- screen-prompt-catalog-report.md
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

try:
    import yaml
    _yaml_available = True
except ImportError:
    _yaml_available = False

_SCRIPT_DIR = Path(__file__).parent
_LANG_CONFIG_PATH = _SCRIPT_DIR / "prompt-language-config.yaml"


def _load_lang_config() -> dict:
    if _yaml_available and _LANG_CONFIG_PATH.exists():
        with open(_LANG_CONFIG_PATH, encoding="utf-8") as f:
            return yaml.safe_load(f) or {}
    return {}


_LANG_CFG = _load_lang_config()

VERB_SYNONYMS_EN: dict[str, list[str]] = (_LANG_CFG.get("verb_synonyms", {}).get("en") or {
    "approve": ["approve", "confirm", "authorize"],
    "cancel": ["cancel", "void"],
    "create": ["create", "add", "new"],
    "update": ["update", "edit", "modify"],
    "find": ["find", "search", "lookup"],
    "print": ["print", "export", "download"],
    "validate": ["validate", "check", "verify"],
    "receive": ["receive", "receipt"],
    "ship": ["ship", "send"],
})

VERB_SYNONYMS_IT: dict[str, list[str]] = (_LANG_CFG.get("verb_synonyms", {}).get("it") or {
    "approve": ["approva", "conferma", "autorizza"],
    "cancel": ["annulla", "cancella"],
    "create": ["crea", "aggiungi", "nuovo"],
    "update": ["aggiorna", "modifica"],
    "find": ["trova", "cerca"],
    "print": ["stampa", "esporta", "scarica"],
    "validate": ["valida", "verifica", "controlla"],
    "receive": ["ricevi"],
    "ship": ["spedisci", "invia"],
})

DOMAIN_IT_TRANSLATIONS: dict[str, str] = (_LANG_CFG.get("domain_translations", {}).get("it") or {
    "order": "ordine",
    "order part": "parte ordine",
    "order item": "riga ordine",
    "item": "riga",
    "product": "prodotto",
    "customer": "cliente",
    "supplier": "fornitore",
    "party": "anagrafica",
    "invoice": "fattura",
    "shipment": "spedizione",
    "payment": "pagamento",
    "deposit": "acconto",
    "return": "reso",
    "address": "indirizzo",
    "shipping address": "indirizzo di spedizione",
    "detail": "dettaglio",
})

_IT_MARKERS: list[str] = (_LANG_CFG.get("it_marker_words") or
    ["ordine","riga","prodotto","cliente","pagamento","spedizione","reso","indirizzo","fornitore","anagrafica","fattura"])
_EN_MARKERS: list[str] = (_LANG_CFG.get("en_marker_words") or
    ["order","item","product","customer","payment","shipment","return","address"])
_IT_VERB_MARKERS: list[str] = (_LANG_CFG.get("it_verb_markers") or
    ["approva","conferma","autorizza","annulla","cancella","crea","aggiungi","aggiorna","modifica",
     "trova","cerca","stampa","esporta","scarica","valida","verifica","controlla","ricevi","spedisci","invia"])
_EN_VERB_MARKERS: list[str] = (_LANG_CFG.get("en_verb_markers") or
    ["approve","confirm","authorize","cancel","create","update","find","search","lookup",
     "print","export","download","validate","check","verify","receive","ship","send"])
_MANUAL_OVERRIDES: dict[str, dict[str, list[str]]] = _LANG_CFG.get("manual_overrides") or {}

READ_VERBS = {"get", "find", "search", "list", "count"}
MUT_VERBS = {"create", "update", "delete", "approve", "cancel", "place", "complete", "reject", "add", "set", "ship", "receive", "send"}
KNOWN_CONTEXT_PARAMS = {
    "orderId",
    "orderPartSeqId",
    "orderItemSeqId",
    "returnId",
    "returnItemSeqId",
    "customerPartyId",
    "supplierPartyId",
    "partyId",
    "productId",
    "quantity",
    "facilityId",
    "paymentId",
    "invoiceId",
    "shipmentId",
    "postalContactMechId",
    "contactMechId",
}

QUERY_ALLOWED_VERBS = ["find", "search", "list", "view", "show", "open", "select"]
QUERY_EXCLUDED_VERBS = ["create", "update", "delete", "add", "remove", "set", "approve", "cancel", "complete"]
FOCUSED_DENSE_AREAS = {"Accounting", "Asset", "ProductStore"}
FIELD_ALIAS_OVERRIDES = {
    "fromPartyId": ["from party", "source party"],
    "toPartyId": ["to party", "destination party"],
    "dueDate": ["due date"],
    "invoiceDate": ["invoice date"],
    "statusId": ["status"],
    "referenceNum": ["reference number", "reference num"],
    "description": ["description"],
}

SCREEN_AREA_OVERRIDES = {
    "FindProductStore": "ProductStore",
    "EditProductStore": "ProductStore",
    "FindPromotion": "ProductStore",
    "FindPromoCode": "ProductStore",
    "EditPromotion": "ProductStore",
    "AssetDetail": "Asset",
    "FindAsset": "Asset",
    "FindFinancialAccount": "Accounting",
}

SUBAREA_KEYWORDS = {
    "Accounting": [
        ("FinancialAccount", ["financial account", "fin account", "account balance"]),
        ("GlAccount", ["gl account", "general ledger", "ledger account"]),
        ("Invoice", ["invoice", "billing invoice"]),
        ("Payment", ["payment", "pay method", "pay auth"]),
        ("Budget", ["budget", "budget item"]),
        ("Journal", ["journal", "acctg trans entry"]),
        ("Transaction", ["transaction", "acctg trans", "posting"]),
        ("Reconciliation", ["reconcile", "reconciliation", "statement"]),
        ("AssetAccounting", ["asset accounting", "depreciation", "fixed asset"]),
        ("Config", ["setup", "configuration", "preference", "type enum"]),
    ],
    "Asset": [
        ("AssetPool", ["asset pool"]),
        ("AssetMaintenance", ["maintenance", "service record"]),
        ("Container", ["container", "bin"]),
        ("PhysicalInventory", ["physical inventory", "count"]),
        ("AssetSummary", ["summary", "overview"]),
        ("DetailHistory", ["history", "detail history", "audit"]),
        ("Asset", ["asset", "serial"]),
    ],
    "ProductStore": [
        ("StoreCategory", ["store category", "category member"]),
        ("StoreFacility", ["store facility", "facility"]),
        ("StoreParty", ["store party", "store role"]),
        ("StoreSettings", ["setting", "preference", "configuration"]),
        ("Promotion", ["promotion", "promo action"]),
        ("PromoCode", ["promo code", "coupon"]),
        ("PromoParameter", ["promo parameter", "condition", "rule"]),
        ("Store", ["product store", "store"]),
    ],
}

DOMAIN_OBJECT_PATTERNS = {
    "Accounting": [
        (r"gl.?account.?budget", "GlAccountBudget"),
        (r"budget.?item", "BudgetItem"),
        (r"gl.?account", "GlAccount"),
        (r"financial.?account", "FinancialAccount"),
        (r"transaction.?entry", "TransactionEntry"),
        (r"transaction", "Transaction"),
        (r"invoice.?aging", "InvoiceAging"),
        (r"invoice", "Invoice"),
        (r"payment", "Payment"),
        (r"reconcil", "Reconciliation"),
        (r"journal", "Journal"),
        (r"budget", "Budget"),
    ],
    "Asset": [
        (r"asset.?maintenance|product.?maintenance", "AssetMaintenance"),
        (r"asset.?pool", "AssetPool"),
        (r"physical.?inventory", "PhysicalInventory"),
        (r"asset.?history|detail.?history|audit", "AssetHistory"),
        (r"asset.?summary|summary", "AssetSummary"),
        (r"container|bin", "Container"),
        (r"location.?asset", "LocationAsset"),
        (r"asset", "Asset"),
    ],
    "ProductStore": [
        (r"store.?party", "StoreParty"),
        (r"store.?settings?|store.?preference", "StoreSetting"),
        (r"store.?data.?document", "StoreDataDocument"),
        (r"store.?emails?", "StoreEmail"),
        (r"store.?categor", "StoreCategory"),
        (r"store.?facilit", "StoreFacility"),
        (r"ship(ping)?.?options?", "ShippingOption"),
        (r"promo.?code|coupon", "PromoCode"),
        (r"promo.?param|promo.?condition|promo.?rule", "PromoParameter"),
        (r"promotion", "Promotion"),
        (r"product.?store|store", "Store"),
    ],
}


@dataclass
class ServiceDef:
    name: str
    verb: str
    noun: str
    required: list[str]
    touched_entities: list[str]
    source_path: str
    # Derived from <actions> parsing
    auto_derivable: dict = None   # {field: "from source_field [via entity]"}
    literal_defaults: set = None  # fields with hardcoded default values in actions
    call_chain: list = None       # [service_name, ...] in execution order

    def __post_init__(self):
        if self.auto_derivable is None:
            self.auto_derivable = {}
        if self.literal_defaults is None:
            self.literal_defaults = set()
        if self.call_chain is None:
            self.call_chain = []


@dataclass
class ServiceActionIndex:
    statements_by_service: dict[str, list[dict]]
    documents_by_service: dict[str, dict]


def load_localization_catalog(path: Path | None) -> dict[str, dict]:
    if path is None or not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def message_uri(original: str) -> str:
    return f"message://{original}"


def transition_uri(screen_path: str, transition_name: str) -> str:
    return f"transition://{screen_path}#{transition_name}"


def form_uri(screen_path: str, form_name: str) -> str:
    return f"form://{screen_path}#{form_name}"


def field_uri(screen_path: str, form_name: str, field_name: str) -> str:
    return f"field://{screen_path}#{form_name}.{field_name}"


def entity_field_uri(entity_name: str, field_name: str) -> str:
    return f"entityField://{entity_name}.{field_name}"


def extract_action_patterns(actions_elem) -> tuple[dict, set, list]:
    """Parse <actions> XML and extract:
    - auto_derivable: {field: description} — fields auto-filled when another field is provided
    - literal_defaults: set of fields with hardcoded fallback values in actions
    - call_chain: list of service names called in execution order

    Vocabulary derived from xml-actions-3.xsd:
      Reads:  entity-find-one, entity-find, entity-find-count, entity-find-related-one, entity-find-related
      Writes: entity-create, entity-update, entity-delete, entity-store, entity-set, entity-make-value
      Invoke: service-call
      Control: if, else-if, else, while, iterate, return, check-errors
      Assign:  set, script
    """
    if actions_elem is None:
        return {}, set(), []

    auto_derivable: dict[str, str] = {}
    literal_defaults: set[str] = set()
    call_chain: list[str] = []

    for elem in actions_elem.iter():
        tag = elem.tag

        if tag == "if":
            condition = elem.get("condition", "")

            # Pattern 1: <if condition="!fieldA && fieldB"> → fieldA auto-derivable from fieldB
            m = re.match(r"^\s*!(\w+)\s*&&\s*(\w+)\s*$", condition)
            if m:
                auto_field, source_field = m.group(1), m.group(2)
                # Determine lookup mechanism (entity-find-one, entity-find-related-one, service-call)
                via_parts = []
                for ef in elem.findall(".//entity-find-one"):
                    via_parts.append(ef.get("entity-name", ""))
                for ef in elem.findall(".//entity-find-related-one"):
                    via_parts.append(ef.get("entity-name", "") or ef.get("relationship-name", ""))
                for sc in elem.findall(".//service-call"):
                    via_parts.append(sc.get("name", ""))
                via = " / ".join(p for p in via_parts if p) or "lookup"
                # Find the <set field="fieldA" from="..."> that delivers the derived value
                for sv in elem.findall(".//set"):
                    if sv.get("field") == auto_field:
                        from_expr = sv.get("from", "")
                        desc = f"from {source_field}"
                        if from_expr:
                            desc += f" via {from_expr}"
                        elif via:
                            desc += f" via {via}"
                        auto_derivable[auto_field] = desc
                        break

            # Pattern 2: <if condition="!fieldA"><set field="fieldA" value="literal"/></if>
            m2 = re.match(r"^\s*!(\w+)\s*$", condition)
            if m2:
                auto_field = m2.group(1)
                for sv in elem.findall(".//set"):
                    if sv.get("field") == auto_field and sv.get("value") is not None:
                        literal_defaults.add(auto_field)
                        break

            # Pattern 3: <if condition="!fieldA"><service-call .../><set field="fieldA" from="..."/>
            # Same as pattern 1 but the service-call is what provides the value (not entity-find-one)
            # This is already covered by pattern 1 (service-call is in via_parts), no extra code needed.

        # Pattern 4: <service-call name="..."/> — unconditional (top-level call chain)
        if tag == "service-call":
            svc_name = elem.get("name", "")
            if svc_name and svc_name not in call_chain:
                call_chain.append(svc_name)

    return auto_derivable, literal_defaults, call_chain


@dataclass
class EntityBom:
    """BOM (Bill of Materials) hierarchy for one entity derived from <relationship> tags."""
    entity_fqn: str              # fully qualified: "mantle.shipment.Shipment"
    parents: list[tuple[str, str]]   # [(parent_fqn, fk_field_in_this_entity), ...]  type="one"
    children: list[tuple[str, str]]  # [(child_fqn, fk_field_in_child_entity), ...]  type="many"


def build_entity_bom(entity_files: list) -> dict[str, EntityBom]:
    """Parse <relationship> tags from entity XML files and build a parent/child BOM map.

    type="one"  → FK is in THIS entity → this entity is CHILD of the related entity
    type="many" → FK is in the RELATED entity → this entity is PARENT of the related entity
    type="one-nofk" → conceptual link, no real FK — skip (doesn't affect BOM hierarchy)
    type="many-nofk" → skip for same reason

    Returns: {entity_fqn: EntityBom}
    """
    bom: dict[str, EntityBom] = {}

    for entity_file in entity_files:
        if not hasattr(entity_file, "exists"):
            entity_file = __import__("pathlib").Path(entity_file)
        if not entity_file.exists():
            continue
        try:
            root = ET.parse(entity_file).getroot()
        except Exception:
            continue

        for e in root.findall("entity"):
            pkg = e.get("package", "")
            ename = e.get("entity-name", "")
            if not ename:
                continue
            fqn = f"{pkg}.{ename}" if pkg else ename
            if fqn not in bom:
                bom[fqn] = EntityBom(entity_fqn=fqn, parents=[], children=[])

            for rel in e.findall("relationship"):
                rel_type = rel.get("type", "")
                related = rel.get("related", "")
                if not related or rel_type not in ("one", "many"):
                    continue

                # Collect key-map field names
                key_maps = [km.get("field-name", "") for km in rel.findall("key-map") if km.get("field-name")]
                fk_field = ", ".join(key_maps) if key_maps else ""

                if rel_type == "one":
                    # This entity has FK → parent is `related`
                    bom[fqn].parents.append((related, fk_field))
                    # Ensure parent has a BOM entry so we can add this as its child
                    if related not in bom:
                        bom[related] = EntityBom(entity_fqn=related, parents=[], children=[])
                    # Only add child link if fk_field is known (avoids noise from shared-PK rels)
                    if fk_field:
                        bom[related].children.append((fqn, fk_field))

                elif rel_type == "many":
                    # Related entity has FK → this entity is parent; related is child
                    if related not in bom:
                        bom[related] = EntityBom(entity_fqn=related, parents=[], children=[])
                    if fk_field:
                        bom[related].parents.append((fqn, fk_field))
                    bom[fqn].children.append((related, fk_field))

    return bom


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", (s or "")).strip()


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def pretty_token(token: str) -> str:
    token = token or ""
    token = token.replace("#", " ")
    token = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", token)
    token = token.replace("_", " ").replace("-", " ")
    return norm(token).lower()


def hash_files(paths: list[str]) -> str:
    """Content hash for artifact paths (not just path names)."""
    h = hashlib.sha256()
    for p in sorted({x for x in paths if x}):
        h.update(p.encode("utf-8"))
        fp = Path(p)
        if fp.exists() and fp.is_file():
            try:
                h.update(fp.read_bytes())
            except Exception:
                # keep deterministic path-only fallback if file is unreadable
                pass
    return h.hexdigest()[:24]


def parse_services(services_dir: Path) -> tuple[dict[str, ServiceDef], set[str]]:
    defs: dict[str, ServiceDef] = {}
    all_names: set[str] = set()
    for f in sorted(services_dir.rglob("*.xml")):
        if f.name.endswith("TestServices.xml"):
            continue
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        if root.tag != "services":
            continue
        parts = f.parts
        pkg = ""
        if "service" in parts:
            i = parts.index("service")
            pkg = ".".join(parts[i + 1 : -1])
        stem = f.stem
        for s in root.findall("service"):
            verb = s.attrib.get("verb", "")
            noun = s.attrib.get("noun", "")
            if not verb or not noun:
                continue
            name = f"{pkg}.{stem}.{verb}#{noun}" if pkg else f"{stem}.{verb}#{noun}"
            req = [p.attrib.get("name") for p in s.findall("./in-parameters/parameter") if p.attrib.get("required") == "true" and p.attrib.get("name")]
            touched = set()
            actions = s.find("actions")
            auto_derivable, literal_defaults, call_chain = extract_action_patterns(actions)
            if actions is not None:
                for el in actions.iter():
                    en = el.attrib.get("entity-name")
                    if en:
                        touched.add(en)
            defs[name] = ServiceDef(
                name=name, verb=verb.lower(), noun=noun,
                required=sorted(req), touched_entities=sorted(touched),
                source_path=str(f),
                auto_derivable=auto_derivable,
                literal_defaults=literal_defaults,
                call_chain=call_chain,
            )
            all_names.add(name)
    return defs, all_names


def parse_entities(entity_files: list[Path]) -> set[str]:
    out = set()
    for entity_file in entity_files:
        if not entity_file.exists():
            continue
        try:
            root = ET.parse(entity_file).getroot()
        except Exception:
            continue
        for e in root.findall("entity"):
            pkg = e.attrib.get("package", "")
            en = e.attrib.get("entity-name", "")
            if en:
                out.add(f"{pkg}.{en}" if pkg else en)
    return out


def parse_view_entities(view_files: list[Path]) -> set[str]:
    out = set()
    for view_file in view_files:
        if not view_file.exists():
            continue
        try:
            root = ET.parse(view_file).getroot()
        except Exception:
            continue
        for v in root.findall("view-entity"):
            pkg = v.attrib.get("package", "")
            en = v.attrib.get("entity-name", "")
            if en:
                out.add(f"{pkg}.{en}" if pkg else en)
    return out


def parse_eeca(eeca_files: list[Path]) -> list[dict]:
    out = []
    for eeca_file in eeca_files:
        if not eeca_file.exists():
            continue
        try:
            root = ET.parse(eeca_file).getroot()
        except Exception:
            continue
        for e in root.findall("eeca"):
            called = [sc.attrib.get("name", "") for sc in e.findall("./actions/service-call") if sc.attrib.get("name")]
            events = []
            for attr, name in (("on-create", "create"), ("on-update", "update"), ("on-delete", "delete")):
                if e.attrib.get(attr) == "true":
                    events.append(name)
            out.append({
                "id": e.attrib.get("id", ""),
                "entity": e.attrib.get("entity", ""),
                "events": events,
                "calledServices": called,
            })
    return out


def load_jsonl_rows(path: Path | None) -> list[dict]:
    if path is None or not path.exists():
        return []
    rows: list[dict] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def load_service_action_index(
    statement_path: Path | None,
    document_path: Path | None,
) -> ServiceActionIndex:
    statements_by_service: dict[str, list[dict]] = defaultdict(list)
    documents_by_service: dict[str, dict] = {}

    for row in load_jsonl_rows(statement_path):
        service_name = row.get("serviceName")
        if not service_name:
            continue
        statements_by_service[service_name].append(row)

    for service_name, rows in list(statements_by_service.items()):
        statements_by_service[service_name] = sorted(
            rows,
            key=lambda item: (item.get("statementPath") or "", item.get("statementId") or ""),
        )

    for row in load_jsonl_rows(document_path):
        service_name = row.get("serviceName")
        if service_name:
            documents_by_service[service_name] = row

    return ServiceActionIndex(
        statements_by_service=dict(statements_by_service),
        documents_by_service=documents_by_service,
    )


def enrich_prompt_doc_with_service_actions(doc: dict, service_action_index: ServiceActionIndex) -> None:
    bound_services = [service_name for service_name in doc.get("boundServices", []) if service_name]
    if not bound_services:
        doc["linkedServiceStatements"] = []
        doc["serviceComplements"] = []
        doc["readEntities"] = []
        doc["writtenEntities"] = []
        doc["downstreamServices"] = []
        doc["statementClasses"] = []
        doc["serviceOperationEffects"] = []
        doc["opaqueStatements"] = []
        return

    linked_statement_ids: list[str] = []
    service_complements: set[str] = set()
    read_entities: set[str] = set()
    written_entities: set[str] = set()
    downstream_services: set[str] = set()
    statement_classes: set[str] = set()
    operation_effects: set[str] = set()
    opaque_statement_ids: list[str] = []

    for service_name in bound_services:
        service_doc = service_action_index.documents_by_service.get(service_name)
        if service_doc:
            service_complements.update(service_doc.get("serviceComplements", []))
            read_entities.update(service_doc.get("readEntities", []))
            written_entities.update(service_doc.get("writtenEntities", []))
            downstream_services.update(service_doc.get("calledServices", []))
            statement_classes.update(service_doc.get("statementClasses", []))
            operation_effects.update(service_doc.get("operationEffects", []))
        for statement_doc in service_action_index.statements_by_service.get(service_name, []):
            statement_id = statement_doc.get("statementId")
            if statement_id:
                linked_statement_ids.append(statement_id)
            for complement in statement_doc.get("complements", []):
                name = complement.get("name")
                if name:
                    service_complements.add(name)
            read_entities.update(statement_doc.get("readEntities", []))
            written_entities.update(statement_doc.get("writtenEntities", []))
            called_service = statement_doc.get("calledService")
            if called_service:
                downstream_services.add(called_service)
            statement_class = statement_doc.get("statementClass")
            if statement_class:
                statement_classes.add(statement_class)
            operation_effect = statement_doc.get("operationEffect")
            if operation_effect:
                operation_effects.add(operation_effect)
            if statement_doc.get("opaque") and statement_id:
                opaque_statement_ids.append(statement_id)

    doc["linkedServiceStatements"] = sorted(dict.fromkeys(linked_statement_ids))[:40]
    doc["serviceComplements"] = sorted(service_complements)[:40]
    doc["readEntities"] = sorted(read_entities)[:20]
    doc["writtenEntities"] = sorted(written_entities)[:20]
    doc["downstreamServices"] = sorted(
        service_name for service_name in downstream_services
        if service_name and service_name not in bound_services
    )[:20]
    doc["statementClasses"] = sorted(statement_classes)[:12]
    doc["serviceOperationEffects"] = sorted(operation_effects)[:20]
    doc["opaqueStatements"] = sorted(dict.fromkeys(opaque_statement_ids))[:20]

    enrichment_parts: list[str] = []
    if doc["serviceComplements"]:
        enrichment_parts.append(
            f"Service complements include {', '.join(doc['serviceComplements'][:8])}."
        )
    if doc["readEntities"]:
        enrichment_parts.append(
            f"Read entities include {', '.join(doc['readEntities'][:6])}."
        )
    if doc["writtenEntities"]:
        enrichment_parts.append(
            f"Written entities include {', '.join(doc['writtenEntities'][:6])}."
        )
    if doc["downstreamServices"]:
        enrichment_parts.append(
            f"Downstream services include {', '.join(service.split('.')[-1] for service in doc['downstreamServices'][:6])}."
        )
    if doc["statementClasses"]:
        enrichment_parts.append(
            f"Statement classes include {', '.join(doc['statementClasses'][:6])}."
        )
    if doc["opaqueStatements"]:
        enrichment_parts.append(
            f"Opaque statements: {len(doc['opaqueStatements'])}."
        )
    if enrichment_parts:
        doc["embeddingText"] = f"{doc['embeddingText']} {' '.join(enrichment_parts)}"


def localized_label_strings(labels: dict[str, str]) -> list[str]:
    return sorted({value for locale, value in labels.items() if locale != "default" and value})


def collect_transition_localizations(
    screen_path: str,
    transition_name: str,
    form_names: list[str],
    direct_labels: list[str],
    localization_catalog: dict[str, dict],
) -> tuple[list[str], list[dict]]:
    entries: list[dict] = []
    transition_entry = localization_catalog.get(transition_uri(screen_path, transition_name))
    if transition_entry:
        entries.append(transition_entry)
    for form_name in form_names:
        form_entry = localization_catalog.get(form_uri(screen_path, form_name))
        if form_entry:
            entries.append(form_entry)
    for label in direct_labels:
        message_entry = localization_catalog.get(message_uri(label))
        if message_entry:
            entries.append(message_entry)

    localized_values: set[str] = set()
    normalized_entries: list[dict] = []
    seen_keys: set[tuple[str, str]] = set()
    for entry in entries:
        labels = entry.get("labels", {})
        for locale, value in labels.items():
            if value:
                localized_values.add(value)
                entry_key = (entry.get("technicalName", ""), locale)
                if entry_key not in seen_keys and locale != "default":
                    seen_keys.add(entry_key)
                    normalized_entries.append(
                        {
                            "technicalName": entry.get("technicalName"),
                            "locale": locale,
                            "label": value,
                            "source": entry.get("source"),
                        }
                    )
    return sorted(localized_values), normalized_entries


def collect_field_label_details(
    screen_path: str,
    forms: list[dict],
    direct_entities: set[str],
    context_entities: set[str],
    localization_catalog: dict[str, dict],
) -> list[dict]:
    field_details: dict[tuple[str, str], dict] = {}
    entity_candidates = sorted(direct_entities) + sorted(context_entities)

    for form in forms:
        form_name = form.get("name", "")
        for field_name in form.get("fields", []):
            if not form_name or not field_name:
                continue
            screen_entry = localization_catalog.get(field_uri(screen_path, form_name, field_name))
            labels: dict[str, str] = {}
            source = None
            if screen_entry:
                labels.update(screen_entry.get("labels", {}))
                source = screen_entry.get("source")
            matched_entity_name = None
            for entity_name in entity_candidates:
                entity_entry = localization_catalog.get(entity_field_uri(entity_name, field_name))
                if not entity_entry:
                    continue
                matched_entity_name = entity_name
                for locale, value in entity_entry.get("labels", {}).items():
                    if locale not in labels and value:
                        labels[locale] = value
            if not labels:
                continue
            detail_key = (field_name, matched_entity_name or "")
            detail = field_details.setdefault(
                detail_key,
                {
                    "kind": "field",
                    "name": field_name,
                    "entityName": matched_entity_name,
                    "labels": {},
                    "role": "screen_field",
                    "sourceFormName": form_name,
                    "source": source or ("localized_entity_field" if matched_entity_name else "screen_field"),
                },
            )
            for locale, value in labels.items():
                if value:
                    detail["labels"][locale] = value

    return sorted(
        field_details.values(),
        key=lambda item: (item.get("name") or "", item.get("entityName") or "", item.get("sourceFormName") or ""),
    )


def guess_area(screen_path: str) -> str:
    p = (screen_path or "").replace("\\", "/").lower().strip("/")
    parts = p.split("/") if p else []
    if "productstore" in parts or "product-store" in parts or "store" in parts and "product" in p:
        return "ProductStore"
    if "order" in parts or p.startswith("order/") or p.endswith("/order.xml") or p == "order.xml":
        return "Order"
    if "catalog" in parts and "product" in parts:
        return "Product"
    if "product" in parts or p.startswith("product/") or p.endswith("/product.xml"):
        return "Product"
    if "party" in parts or p.startswith("party/"):
        return "Party"
    if "shipment" in parts or p.startswith("shipment/"):
        return "Shipment"
    if any(x in parts for x in ("accounting", "invoice", "payment")) or p.startswith("accounting/"):
        return "Accounting"
    return "General"


def infer_area(screen_path: str, screen_name: str, root_area: str | None = None) -> str:
    if screen_name in SCREEN_AREA_OVERRIDES:
        return SCREEN_AREA_OVERRIDES[screen_name]
    guessed = guess_area(screen_path)
    if guessed != "General":
        return guessed
    if root_area:
        ra = root_area.strip()
        if ra:
            return ra
    return "General"


def canonical_area_from_root_path(screens_dir: Path) -> str:
    stem = screens_dir.name.lower()
    mapping = {
        "accounting": "Accounting",
        "asset": "Asset",
        "catalog": "Product",
        "product": "Product",
        "productstore": "ProductStore",
        "shipment": "Shipment",
        "shipping": "Shipment",
        "party": "Party",
        "customer": "Party",
        "supplier": "Party",
        "vendor": "Party",
        "order": "Order",
        "return": "Order",
        "request": "Request",
        "facility": "Facility",
        "task": "Task",
        "project": "Project",
        "manufacturing": "Manufacturing",
        "humanres": "HumanRes",
        "gateway": "Gateway",
    }
    if stem in mapping:
        return mapping[stem]
    return screens_dir.name


def title_case_compact(s: str) -> str:
    tokens = [t for t in split_tokens(s) if t]
    if not tokens:
        return "Generic"
    return "".join(t.capitalize() for t in tokens[:4])


def infer_subarea(
    area: str,
    canonical_prompt: str,
    screen_name: str,
    transition_name: str,
    form_names: list[str],
    direct_entities: set[str],
    preferred_service: str | None,
) -> str:
    hay = " ".join(
        [
            canonical_prompt or "",
            screen_name or "",
            transition_name or "",
            " ".join(form_names or []),
            preferred_service or "",
        ]
    ).lower()
    for candidate, keys in SUBAREA_KEYWORDS.get(area, []):
        if any(k in hay for k in keys):
            return candidate
    if direct_entities:
        tail = sorted(direct_entities)[0].split(".")[-1]
        return title_case_compact(tail)
    if preferred_service:
        last = preferred_service.split(".")[-1]
        if "#" in last:
            last = last.split("#")[-1]
        return title_case_compact(last)
    if area == "ProductStore":
        return "Store"
    if area == "Accounting":
        return "FinancialAccount"
    if area == "Asset":
        return "Asset"
    return title_case_compact(screen_name or canonical_prompt or "Generic")


def subarea_to_domain_object(sub_area: str) -> str:
    return title_case_compact(sub_area)


def map_operation_to_action_verb(operation_effect: str, transition_name: str, canonical: str) -> str:
    if operation_effect == "read_query":
        return "list"
    if operation_effect == "read_detail":
        return "view"
    if operation_effect == "navigation":
        return "navigate"
    if operation_effect in {"create", "update", "delete"}:
        return operation_effect
    if operation_effect == "status_transition":
        return "status"
    if operation_effect in {"print_export", "file_download"}:
        return "print"
    if operation_effect == "email_send":
        return "send"
    if operation_effect in {"validation", "external_call"}:
        return "validate"
    return "other"


def classify_action_kind(
    doc_kind: str,
    operation_effect: str,
    canonical: str,
    transition_name: str,
    navigation_only: bool,
) -> str:
    t = (transition_name or "").lower()
    c = (canonical or "").lower()
    if operation_effect == "read_query" or doc_kind == "screen_query_prompt":
        if any(x in c or x in t for x in ["list", "find", "search", "lookup"]):
            return "list"
        return "detail"
    if operation_effect == "read_detail":
        return "detail"
    if navigation_only or operation_effect == "navigation" or doc_kind == "screen_navigation_prompt":
        return "navigate"
    if any(x in c or x in t for x in ["create", "add", "new"]):
        return "create"
    if any(x in c or x in t for x in ["update", "edit", "modify", "set"]):
        return "update"
    if any(x in c or x in t for x in ["delete", "remove"]):
        return "delete"
    if any(x in c or x in t for x in ["approve", "cancel", "reject", "complete", "status"]):
        return "status"
    if operation_effect == "create":
        return "create"
    if operation_effect == "update":
        return "update"
    if operation_effect == "delete":
        return "delete"
    if operation_effect == "status_transition":
        return "status"
    if operation_effect in {"print_export", "file_download"}:
        return "print"
    if operation_effect == "email_send":
        return "email"
    if operation_effect == "validation":
        return "validate"
    if operation_effect in {"external_call", "external_link"}:
        return "external"
    return "unresolved"


def classify_primary_screen_purpose(screen_name: str, doc_kind: str, operation_effect: str, canonical: str) -> str:
    s = (screen_name or "").lower()
    c = (canonical or "").lower()
    if doc_kind == "screen_query_prompt" or operation_effect == "read_query":
        return "find"
    if doc_kind == "screen_print_prompt" or operation_effect in {"print_export", "file_download"}:
        return "print"
    if doc_kind == "screen_email_prompt" or operation_effect == "email_send":
        return "communication"
    if s.startswith(("find", "search", "list", "select", "lookup")):
        return "find"
    if s.startswith("edit"):
        return "edit"
    if s.startswith(("view", "detail", "show")):
        return "detail"
    if s.startswith(("dashboard", "summary")) or any(x in c for x in ["dashboard", "summary", "report"]):
        return "dashboard"
    return "action"


def infer_resolution_policy(
    doc_kind: str,
    action_kind: str,
    operation_effect: str,
    screen_name: str,
    canonical: str,
    transition_name: str,
    preferred_service: str | None,
    primary_screen_purpose: str,
) -> str:
    combined = f"{screen_name} {canonical} {transition_name}".lower()
    if doc_kind == "screen_query_prompt" or operation_effect == "read_query":
        return "retrieve_or_query"
    if primary_screen_purpose == "edit" and any(x in combined for x in ["edit", "update", "modify", "change", "set"]):
        return "navigate_then_maybe_update"
    if action_kind == "navigate" or operation_effect == "navigation":
        return "navigate_only"
    if preferred_service and operation_effect in {
        "create", "update", "delete", "status_transition", "batch_update",
        "print_export", "file_download", "email_send", "validation", "external_call"
    }:
        return "execute_direct"
    if operation_effect == "unresolved_binding":
        return "ask_clarification"
    return "execute_direct" if preferred_service else "ask_clarification"


def infer_state_comparison_entity(domain_object: str, direct_entities: set[str]) -> str | None:
    if not direct_entities:
        return None
    dom = (domain_object or "").lower()
    ranked = sorted(direct_entities)
    for ent in ranked:
        if dom and dom in ent.split(".")[-1].lower():
            return ent
    return ranked[0]


def infer_state_comparison_pk_fields(execution_required_context: set[str]) -> list[str]:
    return sorted(
        x for x in execution_required_context
        if x.lower().endswith("id") or x.lower().endswith("seqid")
    )[:4]


def tokenize_query_like(text: str) -> list[str]:
    raw = [t for t in re.split(r"[^a-z0-9]+", norm(text).lower()) if t]
    out = []
    for t in raw:
        out.append(t)
        if len(t) > 4 and t.endswith("ies"):
            out.append(t[:-3] + "y")
        elif len(t) > 4 and t.endswith("s"):
            out.append(t[:-1])
    return sorted(set(out))


def field_aliases(field_name: str) -> list[str]:
    aliases = set(FIELD_ALIAS_OVERRIDES.get(field_name, []))
    pretty = pretty_token(field_name).lower()
    if pretty:
        aliases.add(pretty)
    aliases.add(field_name.lower())
    if field_name.lower().endswith("id"):
        aliases.add(pretty.replace(" id", ""))
    return sorted(x for x in aliases if x)


def classify_user_query(query_text: str, field_names: list[str] | None = None) -> dict[str, object]:
    q = norm(query_text).lower()
    field_names = field_names or []
    field_alias_hits = []
    for fn in field_names:
        aliases = field_aliases(fn)
        if any(alias and alias in q for alias in aliases):
            field_alias_hits.append(fn)
    has_value_signal = bool(
        re.search(r"\b\d{4}-\d{2}-\d{2}\b", q)
        or re.search(r"\bto\b\s+['\"]?[\w:-]+", q)
        or re.search(r"\bfrom\b\s+['\"]?[\w:-]+", q)
        or "=" in q
        or '"' in q
        or "'" in q
    )
    has_explicit_field_mutation = bool(field_alias_hits and has_value_signal)

    if any(x in q for x in ["create", "add", "new"]):
        return {"actionKindHint": "create", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "mutative"}
    if any(x in q for x in ["delete", "remove"]):
        return {"actionKindHint": "delete", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "mutative"}
    if any(x in q for x in ["approve", "cancel", "reject", "complete", "post", "reverse", "close", "void", "refund"]):
        return {"actionKindHint": "status", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "mutative"}
    if any(x in q for x in ["print", "pdf", "download", "export"]):
        return {"actionKindHint": "print", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "print"}
    if any(x in q for x in ["email", "send"]):
        return {"actionKindHint": "email", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "email"}
    if any(x in q for x in ["validate", "check", "verify"]):
        return {"actionKindHint": "validate", "resolutionPolicyHint": "execute_direct", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "validate"}
    if any(x in q for x in ["open", "go to", "view", "detail", "show"]) and not any(x in q for x in ["list", "search", "find"]):
        return {"actionKindHint": "navigate", "resolutionPolicyHint": "navigate_only", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "navigation"}
    if any(x in q for x in ["edit", "update", "modify", "change", "set"]):
        return {
            "actionKindHint": "navigate",
            "resolutionPolicyHint": "navigate_then_maybe_update",
            "requiresValueComparison": has_explicit_field_mutation,
            "hasExplicitFieldMutation": has_explicit_field_mutation,
            "candidateFieldNames": field_alias_hits,
            "queryIntentType": "mutative" if has_explicit_field_mutation else "navigation",
        }
    if any(x in q for x in ["find", "search", "list", "lookup", "show"]):
        return {"actionKindHint": "list", "resolutionPolicyHint": "retrieve_or_query", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "query"}
    return {"actionKindHint": "unresolved", "resolutionPolicyHint": "ask_clarification", "requiresValueComparison": False, "hasExplicitFieldMutation": False, "candidateFieldNames": field_alias_hits, "queryIntentType": "unresolved"}


def prompt_group_id(area: str, sub_area: str, domain_object: str, action_kind: str, action_verb: str) -> str:
    return f"{slug(area)}/{slug(sub_area)}/{slug(domain_object)}/{slug(action_kind)}/{slug(action_verb)}"


def pluralize_phrase(phrase: str) -> str:
    toks = [t for t in norm(phrase).split(" ") if t]
    if not toks:
        return phrase
    last = toks[-1]
    if last.endswith("s"):
        return " ".join(toks)
    if last.endswith("y") and len(last) > 1 and last[-2] not in "aeiou":
        toks[-1] = f"{last[:-1]}ies"
    else:
        toks[-1] = f"{last}s"
    return " ".join(toks)


def infer_domain_object(
    area: str,
    form_names: list[str],
    transition_name: str,
    service_noun: str | None,
    screen_name: str,
    section_labels: list[str],
    direct_entities: set[str],
    related_views: set[str],
    field_names: list[str],
    source_path: str,
    sub_area: str,
) -> str:
    sources = [
        " ".join(form_names or []),
        transition_name or "",
        service_noun or "",
        screen_name or "",
        " ".join(section_labels or []),
        " ".join(sorted(related_views)[:12]),
        " ".join(field_names or []),
        source_path or "",
    ]
    pats = DOMAIN_OBJECT_PATTERNS.get(area, [])
    for src in sources:
        s = norm(src).lower()
        if not s:
            continue
        for rx, obj in pats:
            if re.search(rx, s):
                return obj
    if direct_entities:
        tail = sorted(direct_entities)[0].split(".")[-1]
        tail = re.sub(r"(Header|Detail|Member|Value)$", "", tail)
        if tail:
            return title_case_compact(tail)
    if service_noun:
        return title_case_compact(service_noun)
    return subarea_to_domain_object(sub_area)


def make_dense_area_canonical(
    area: str,
    doc_kind: str,
    operation_effect: str,
    action_kind: str,
    domain_object: str,
    fallback_canonical: str,
    form_names: list[str],
    transition_name: str,
    screen_name: str,
) -> str:
    if area not in FOCUSED_DENSE_AREAS:
        return fallback_canonical
    if doc_kind != "screen_query_prompt":
        return fallback_canonical
    obj = pretty_token(domain_object)
    src = " ".join(form_names + [transition_name, screen_name, fallback_canonical]).lower()
    if doc_kind == "screen_query_prompt" or operation_effect == "read_query":
        if any(x in src for x in ["history", "audit", "detail", "summary"]):
            return f"list {obj} records"
        if any(x in src for x in ["list", "grid", "table", "find", "search"]):
            return f"list {pluralize_phrase(obj)}"
        return f"search {pluralize_phrase(obj)}"
    if action_kind == "detail":
        return f"view {obj} details"
    if action_kind == "create":
        return f"create {obj}"
    if action_kind == "update":
        return f"update {obj}"
    if action_kind == "delete":
        return f"delete {obj}"
    if action_kind == "status":
        return f"update {obj} status"
    return fallback_canonical


def is_i18n_key(label: str) -> bool:
    if not label:
        return False
    if "${" in label:
        return True
    # heuristic: compact camel case key
    return bool(re.match(r"^[A-Za-z][A-Za-z0-9_.]+$", label)) and " " not in label and any(c.isupper() for c in label[1:])


def normalize_prompt_text(s: str) -> str:
    t = pretty_token(s)
    t = re.sub(r"\bpdf\b", "", t).strip()
    t = re.sub(r"[^a-z0-9 ]+", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def to_italian_phrase(phrase_en: str) -> str:
    out = f" {norm(phrase_en).lower()} "
    # replace longest keys first to avoid partial overlap
    for k in sorted(DOMAIN_IT_TRANSLATIONS.keys(), key=len, reverse=True):
        out = re.sub(rf"\b{re.escape(k)}\b", DOMAIN_IT_TRANSLATIONS[k], out)
    return norm(out)


def expand_prompt_variants(canonical: str, labels: list[str], transition_name: str, service_name: str | None) -> list[str]:
    v_en = {canonical}
    if transition_name:
        v_en.add(normalize_prompt_text(transition_name))
        v_en.add(norm(transition_name).lower())
    for l in labels:
        if l:
            v_en.add(norm(l).lower())
            v_en.add(normalize_prompt_text(l))
    if service_name:
        tail = service_name.split(".")[-1]
        v_en.add(pretty_token(tail))
    c = canonical.lower()
    for k, syns in VERB_SYNONYMS_EN.items():
        if re.search(rf"\b{re.escape(k)}\b", c):
            for s in syns:
                v_en.add(re.sub(rf"\b{re.escape(k)}\b", s, c))

    # keep italian variants separate (no mixed verb-language)
    v_it = set()
    for k, syns in VERB_SYNONYMS_IT.items():
        if re.search(rf"\b{re.escape(k)}\b", c):
            for s in syns:
                v_it.add(to_italian_phrase(re.sub(rf"\b{re.escape(k)}\b", s, c)))

    # common print/pdf variants
    if "print" in c and "order" in c:
        v_en.update({"print order pdf", "download order pdf", "export order"})
        v_it.update({"stampa ordine", "scarica pdf ordine", "esporta ordine"})

    # apply manual_overrides for this canonical prompt
    for override_canonical, locale_extras in _MANUAL_OVERRIDES.items():
        if c == override_canonical.lower():
            v_it.update(locale_extras.get("it", []))
            v_en.update(locale_extras.get("en", []))

    # remove too-technical / mixed forms
    _pat_en_w = re.compile(r"\b(" + "|".join(re.escape(w) for w in _EN_MARKERS) + r")\b")
    _pat_it_w = re.compile(r"\b(" + "|".join(re.escape(w) for w in _IT_MARKERS) + r")\b")
    _pat_en_v = re.compile(r"\b(" + "|".join(re.escape(w) for w in _EN_VERB_MARKERS) + r")\b")
    _pat_it_v = re.compile(r"\b(" + "|".join(re.escape(w) for w in _IT_VERB_MARKERS) + r")\b")
    merged = v_en | v_it
    cleaned = set()
    for x in merged:
        nx = norm(str(x)).lower()
        nx = nx.replace(".pdf", " pdf")
        nx = re.sub(r"^[^a-z0-9]+|[^a-z0-9]+$", "", nx)
        nx = re.sub(r"\s+", " ", nx).strip()
        if not nx:
            continue
        # drop mixed en/it hybrids like "approve ordine" / "approva order"
        has_en = bool(_pat_en_w.search(nx))
        has_it = bool(_pat_it_w.search(nx))
        if has_en and has_it:
            continue
        if _pat_en_v.search(nx) and has_it:
            continue
        if _pat_it_v.search(nx) and has_en:
            continue
        if nx:
            cleaned.add(nx)
    return sorted(cleaned)


def split_variant_buckets(variants: list[str]) -> tuple[list[str], list[str], list[str]]:
    _pat_en_w = re.compile(r"\b(" + "|".join(re.escape(w) for w in _EN_MARKERS) + r")\b")
    _pat_it_w = re.compile(r"\b(" + "|".join(re.escape(w) for w in _IT_MARKERS) + r")\b")
    _pat_it_v = re.compile(r"\b(" + "|".join(re.escape(w) for w in _IT_VERB_MARKERS) + r")\b")
    machine, en, it = [], [], []
    for v in variants:
        if re.search(r"[A-Z]|[#_/]", v) or re.search(r"[a-z]+[A-Z]", v):
            machine.append(v)
            continue
        has_it = bool(_pat_it_w.search(v))
        has_en = bool(_pat_en_w.search(v))
        if has_it and not has_en:
            it.append(v)
        elif has_en and not has_it:
            en.append(v)
        else:
            if _pat_it_v.search(v):
                it.append(v)
            else:
                en.append(v)
    return sorted(set(machine)), sorted(set(en)), sorted(set(it))


def classify_doc_kind(
    canonical: str,
    transition_name: str,
    bound_services: list[str],
    read_only: bool,
    labels: list[str],
    svc_defs: dict[str, ServiceDef],
) -> str:
    t = (transition_name or "").lower()
    c = canonical.lower()
    ltxt = " ".join(labels).lower()
    verbs = {svc_defs.get(bs).verb for bs in bound_services if svc_defs.get(bs)}
    if any(x in t or x in c or x in ltxt for x in ["print", ".pdf", "export", "download"]):
        return "screen_print_prompt"
    if any(x in t or x in c or x in ltxt for x in ["email", "send"]):
        return "screen_email_prompt"
    if any(x in t or x in c for x in ["validate", "check"]):
        return "screen_validation_prompt"
    # Service-backed mutative/status actions must not be forced into query prompts
    # just because the screen/action wording contains generic words such as "list".
    if any(v in {"approve", "cancel", "complete", "place", "reject", "hold", "ship", "receive", "post"} for v in verbs):
        return "screen_prompt"
    if any(v in {"create", "add", "update", "set", "delete"} for v in verbs):
        return "screen_prompt"
    if any(x in t or x in c for x in ["batch", "bulk", "parts status", "approve orders", "close orders"]):
        return "screen_batch_prompt"
    if any(x in t or x in c for x in ["find", "search", "list", "lookup"]):
        return "screen_query_prompt"
    if not bound_services and read_only:
        return "screen_navigation_prompt"
    return "screen_prompt"


def classify_service(service_name: str, svc_defs: dict[str, ServiceDef]) -> str | None:
    sd = svc_defs.get(service_name)
    if not sd:
        return None
    v = sd.verb
    n = sd.noun.lower()
    if v in READ_VERBS:
        if any(x in n for x in ["display", "detail", "summary"]):
            return "read-info"
        return "read-query"
    if v in {"validate", "check"}:
        return "validation-check"
    if v in {"approve", "cancel", "complete", "place", "reject", "receive", "ship", "post", "hold", "request", "propose"}:
        return "status-transition"
    if v == "create":
        return "crud-create"
    if v == "update":
        return "crud-update"
    if v == "delete":
        return "crud-delete"
    if v in {"send", "notify"}:
        return "notification"
    if any(x in service_name.lower() for x in ["print", "pdf", "export", "download"]):
        return "print-export"
    if any(x in service_name.lower() for x in ["bulk", "store", "import"]):
        return "bulk-action"
    return "business-action"


def classify_unreachable_service(service_name: str, svc_defs: dict[str, ServiceDef]) -> str:
    s = service_name.lower()
    sd = svc_defs.get(service_name)
    verb = sd.verb if sd else ""
    if "check#" in s or verb == "check":
        return "precheck_service"
    if "handle#" in s:
        return "eca_handler"
    if verb in {"get", "find", "list", "count", "search"}:
        return "info_service"
    if any(x in s for x in ["scheduler", "expire", "batch", "cleanup"]):
        return "scheduled_service"
    if any(x in s for x in ["helper", "util", "calc", "recalc"]):
        return "helper_service"
    if "returnservices" in s:
        return "return_service_not_current_flow"
    if verb in {"create", "update", "delete", "approve", "cancel", "place", "complete", "ship", "receive"}:
        return "business_service_not_exposed"
    return "internal_service"


def classify_operation_effect(
    doc_kind: str,
    transition_name: str,
    bound_services: list[str],
    svc_defs: dict[str, ServiceDef],
    navigation_only: bool,
    query_like: bool,
) -> str:
    t = (transition_name or "").lower()
    verbs = {svc_defs.get(bs).verb for bs in bound_services if svc_defs.get(bs)}
    if doc_kind == "screen_print_prompt":
        return "print_export"
    if doc_kind == "screen_email_prompt":
        return "email_send"
    if navigation_only:
        return "navigation"
    if doc_kind == "screen_validation_prompt":
        if any("carrier" in bs.lower() for bs in bound_services):
            return "external_call"
        return "validation"
    if any(v in {"approve", "cancel", "complete", "place", "reject", "hold", "ship", "receive", "post"} for v in verbs):
        return "status_transition"
    if any(v in {"create", "add"} for v in verbs):
        return "create"
    if "update" in verbs or "set" in verbs:
        return "update"
    if "delete" in verbs:
        return "delete"
    if doc_kind == "screen_query_prompt":
        return "read_query"
    if query_like:
        return "read_detail"
    if any(v in {"find", "search", "list", "count", "get"} for v in verbs):
        return "read_detail"
    if any(v in {"check", "validate"} for v in verbs):
        return "validation"
    # lexical fallback for weak/implicit bindings
    if any(x in t for x in ["create", "add", "new"]):
        return "create"
    if any(x in t for x in ["update", "edit", "modify", "set"]):
        return "update"
    if any(x in t for x in ["delete", "remove"]):
        return "delete"
    if any(x in t for x in ["approve", "cancel", "reject", "complete", "status"]):
        return "status_transition"
    if any(x in t for x in ["find", "search", "list"]):
        return "read_query"
    if doc_kind == "screen_batch_prompt":
        return "batch_update"
    if any(x in t for x in ["detail", "view"]):
        return "read_detail"
    if any(x in t for x in ["download", "file", "pdf"]):
        return "file_download"
    if any(x in t for x in ["http", "https", "external", "open"]):
        return "external_link"
    if bound_services:
        return "unresolved_binding"
    return "ui_only_action"


def classify_unbound_prompt_kind(doc_kind: str, navigation_only: bool, canonical: str, transition_name: str) -> str:
    c = (canonical or "").lower()
    t = (transition_name or "").lower()
    if navigation_only or doc_kind == "screen_navigation_prompt":
        return "navigation_only"
    if doc_kind == "screen_query_prompt":
        return "query_form"
    if doc_kind == "screen_print_prompt" or any(x in c or x in t for x in ["print", "pdf", "download", "export"]):
        return "print_download"
    return "unresolved_binding"


def infer_execution_channel(
    preferred_service: str | None,
    doc_kind: str,
    operation_effect: str,
    prompt_without_service_kind: str | None,
) -> str:
    if preferred_service:
        return "service"
    if doc_kind == "screen_query_prompt" or operation_effect == "read_query":
        return "search"
    if doc_kind == "screen_print_prompt" or operation_effect in {"print_export", "file_download"}:
        return "print"
    if doc_kind == "screen_email_prompt" or operation_effect == "email_send":
        return "email"
    if prompt_without_service_kind == "navigation_only" or operation_effect == "navigation":
        return "navigation"
    if operation_effect == "external_link":
        return "navigation"
    return "unresolved"


def area_subject(area: str) -> str:
    a = (area or "").lower()
    if a == "order":
        return "order"
    if a == "party":
        return "party"
    if a == "product":
        return "product"
    if a == "shipment":
        return "shipment"
    return "record"


def is_mutative_text(s: str) -> bool:
    t = norm(s).lower()
    return bool(re.search(r"\b(create|update|delete|add|remove|set|approve|cancel|complete)\b", t))


def split_tokens(text: str) -> list[str]:
    if not text:
        return []
    t = pretty_token(text)
    return [x for x in re.split(r"[^a-z0-9]+", t) if x]


def area_entities(all_entities: set[str], area: str) -> list[str]:
    a = (area or "").lower()
    if a == "order":
        return sorted([e for e in all_entities if ".order." in e.lower() or ".return." in e.lower()])
    return sorted([e for e in all_entities if f".{a}." in e.lower()])


def core_context_entities_for_token(tok: str, all_entities: set[str]) -> set[str]:
    t = tok.lower()
    if t == "order":
        keys = ("OrderHeader", "OrderPart", "OrderItem")
    elif t == "return":
        keys = ("ReturnHeader", "ReturnItem")
    elif t == "item":
        keys = ("OrderItem",)
    elif t == "product":
        keys = ("OrderItem", "OrderItemProduct")
    elif t == "address":
        keys = ("OrderPartContactMech",)
    elif t == "payment":
        keys = ("OrderItemBilling", "OrderPartPayment")
    else:
        keys = ()
    out = set()
    for en in all_entities:
        if any(k.lower() in en.lower() for k in keys):
            out.add(en)
    return out


def infer_direct_entities_from_hint(hint: str, all_entities: set[str]) -> set[str]:
    h = (hint or "").lower()
    keys = []
    if "orderitem" in h or "order item" in h or h.endswith("item"):
        keys = ["OrderItem"]
    elif "orderpart" in h or "order part" in h or "part" in h:
        keys = ["OrderPart", "OrderHeader"]
    elif "returnitem" in h or "return item" in h:
        keys = ["ReturnItem", "ReturnHeader"]
    elif "return" in h:
        keys = ["ReturnHeader"]
    elif "order" in h:
        keys = ["OrderHeader", "OrderPart"]
    elif "address" in h:
        keys = ["OrderPartContactMech"]
    elif "payment" in h or "billing" in h:
        keys = ["OrderItemBilling", "OrderPartPayment"]
    out = set()
    for en in all_entities:
        if any(k.lower() in en.lower() for k in keys):
            out.add(en)
    return out


def extract_path_parameters(*values: str) -> list[str]:
    params = set()
    for v in values:
        if not v:
            continue
        for m in re.finditer(r"\$\{([^}]+)\}", v):
            name = norm(m.group(1))
            if name and re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name):
                params.add(name)
    return sorted(params)


def label_matches_action(label: str, canonical: str) -> bool:
    l = norm(label).lower()
    c = norm(canonical).lower()
    if not l:
        return False
    cverb = c.split(" ")[0] if c else ""
    verb_groups = {
        "approve": {"approve", "approva", "confirm", "conferma", "authorize", "autorizza"},
        "cancel": {"cancel", "annulla", "cancella", "void"},
        "update": {"update", "aggiorna", "modifica", "edit"},
        "create": {"create", "crea", "add", "aggiungi", "new", "nuovo"},
        "print": {"print", "stampa", "export", "esporta", "download", "scarica"},
        "validate": {"validate", "valida", "verify", "verifica", "check", "controlla"},
    }
    allowed = verb_groups.get(cverb, {cverb})
    # if label has an action verb, it should be in same action family
    label_verbs = set()
    for fam in verb_groups.values():
        for v in fam:
            if re.search(rf"\b{re.escape(v)}\b", l):
                label_verbs.add(v)
    if label_verbs and not any(v in allowed for v in label_verbs):
        return False
    # keep only labels sharing at least one non-trivial token with canonical
    ct = {t for t in split_tokens(c) if len(t) > 2}
    lt = {t for t in split_tokens(l) if len(t) > 2}
    if ct and lt and not (ct & lt):
        return False
    return True


def parse_screens_and_prompts(
    screens_dir: Path,
    svc_defs: dict[str, ServiceDef],
    all_entities: set[str],
    all_views: set[str],
    eecas: list[dict],
    service_action_index: ServiceActionIndex | None = None,
    localization_catalog: dict[str, dict] | None = None,
    root_area: str | None = None,
    entity_bom: dict | None = None,
) -> tuple[list[dict], dict]:
    prompt_docs: list[dict] = []
    metrics = {
        "screen_files_processed": 0,
        "transitions_total": 0,
        "transitions_with_service_binding": 0,
        "forms_total": 0,
        "form_submit_actions": 0,
        "labels_resolved": 0,
        "labels_unresolved": 0,
    }
    per_area = defaultdict(
        lambda: {
            "screen_files_processed": 0,
            "transitions_total": 0,
            "transitions_with_service_binding": 0,
            "forms_total": 0,
            "form_submit_actions": 0,
            "labels_resolved": 0,
            "labels_unresolved": 0,
            "prompt_documents_generated": 0,
        }
    )
    service_reachable = set()
    top_actions = Counter()
    prompt_without_service_breakdown = Counter()
    operation_effect_counts = Counter()
    action_kind_counts = Counter()
    localization_catalog = localization_catalog or {}

    entity_token_index: dict[str, set[str]] = defaultdict(set)
    for en in all_entities:
        tail = en.split(".")[-1]
        for tok in split_tokens(tail):
            entity_token_index[tok].add(en)

    for sx in sorted(screens_dir.rglob("*.xml")):
        try:
            root = ET.parse(sx).getroot()
        except Exception:
            continue
        if root.tag != "screen":
            continue
        metrics["screen_files_processed"] += 1

        screen_rel = str(sx.relative_to(screens_dir.parent)).replace("\\", "/")
        screen_name = sx.stem
        area = infer_area(screen_rel, screen_name, root_area=root_area)
        per_area[area]["screen_files_processed"] += 1

        links_by_url: dict[str, list[str]] = defaultdict(list)
        links_by_transition: dict[str, list[str]] = defaultdict(list)
        all_link_labels: list[str] = []
        for el in root.iter():
            if el.tag in ("link", "link-button"):
                u = el.attrib.get("url", "")
                tx = el.attrib.get("text", "")
                if u and tx and "${" not in tx:
                    links_by_url[u].append(norm(tx))
                tr = el.attrib.get("transition", "")
                if tr and tx and "${" not in tx:
                    links_by_transition[tr].append(norm(tx))
                if tx and "${" not in tx:
                    all_link_labels.append(norm(tx))

        forms = []
        for el in root.iter():
            if el.tag in ("form-single", "form-list"):
                metrics["forms_total"] += 1
                per_area[area]["forms_total"] += 1
                tr = el.attrib.get("transition", "")
                if tr:
                    metrics["form_submit_actions"] += 1
                    per_area[area]["form_submit_actions"] += 1
                fields = [f.attrib.get("name") for f in el.findall("field") if f.attrib.get("name")]
                forms.append({
                    "name": el.attrib.get("name", ""),
                    "transition": tr,
                    "fields": fields,
                    "formType": el.tag,
                })

        form_by_transition = defaultdict(list)
        for f in forms:
            if f["transition"]:
                form_by_transition[f["transition"]].append(f)

        # one prompt doc per transition
        for t in root.findall("transition"):
            metrics["transitions_total"] += 1
            per_area[area]["transitions_total"] += 1
            tname = t.attrib.get("name", "")
            read_only = t.attrib.get("read-only") == "true"
            default_resp = t.find("default-response")
            has_nav_response = default_resp is not None

            bound_services = [sc.attrib.get("name", "") for sc in t.findall(".//service-call") if sc.attrib.get("name")]
            if bound_services:
                metrics["transitions_with_service_binding"] += 1
                per_area[area]["transitions_with_service_binding"] += 1
                service_reachable.update(bound_services)

            # direct labels only for current transition
            direct_labels = []
            direct_labels += links_by_transition.get(tname, [])
            if t.attrib.get("name"):
                direct_labels.append(normalize_prompt_text(t.attrib.get("name", "")))
            if t.attrib.get("title"):
                direct_labels.append(norm(t.attrib.get("title", "")))
            if t.attrib.get("text"):
                direct_labels.append(norm(t.attrib.get("text", "")))
            direct_labels = sorted({norm(x) for x in direct_labels if norm(x)})

            # nearby labels for diagnostics only (not primary embedding)
            nearby_labels = sorted(
                {
                    norm(x)
                    for x in (all_link_labels + links_by_url.get(tname, []))
                    if norm(x) and norm(x) not in set(direct_labels)
                }
            )[:20]
            section_labels = sorted({pretty_token(screen_name), pretty_token(tname)})

            for l in direct_labels:
                if is_i18n_key(l):
                    metrics["labels_unresolved"] += 1
                    per_area[area]["labels_unresolved"] += 1
                else:
                    metrics["labels_resolved"] += 1
                    per_area[area]["labels_resolved"] += 1

            canonical = normalize_prompt_text(tname)
            direct_labels = [x for x in direct_labels if label_matches_action(x, canonical)]
            if not direct_labels:
                direct_labels = [canonical]
            preferred_service = bound_services[0] if bound_services else None
            req = []
            opt = []
            service_categories = []
            direct_entities = set()
            context_entities = set()
            related_views = set()
            related_eca = []
            service_required_parameters = []

            for bs in bound_services:
                sd = svc_defs.get(bs)
                if sd:
                    req += sd.required
                    service_required_parameters += sd.required
                    service_categories.append(classify_service(bs, svc_defs) or "business-action")
                    direct_entities.update(sd.touched_entities)
                    for en in sd.touched_entities:
                        # rough link to views if entity token appears
                        token = en.split(".")[-1].lower()
                        for v in all_views:
                            if token in v.lower():
                                related_views.add(v)
                    for e in eecas:
                        if bs in e.get("calledServices", []):
                            related_eca.append(
                                {
                                    "eecaId": e.get("id"),
                                    "entity": e.get("entity"),
                                    "events": e.get("events", []),
                                }
                            )
                    # infer direct entities from noun when actions do not declare entity-name
                    direct_entities.update(infer_direct_entities_from_hint(sd.noun, all_entities))

            # infer direct entities from transition/form/view hints when still sparse
            if not direct_entities:
                direct_entities.update(infer_direct_entities_from_hint(tname, all_entities))
            for f in form_by_transition.get(tname, []):
                direct_entities.update(infer_direct_entities_from_hint(f.get("name", ""), all_entities))
            if not direct_entities:
                for v in sorted(all_views):
                    if any(tok in v.lower() for tok in split_tokens(tname)):
                        direct_entities.update(infer_direct_entities_from_hint(v.split(".")[-1], all_entities))

            # context entities from transition/form/field tokens (screen-derived, not area-wide)
            token_sources = [tname, canonical, screen_name]
            token_sources.extend([f["name"] for f in form_by_transition.get(tname, []) if f["name"]])
            token_sources.extend([fn for f in form_by_transition.get(tname, []) for fn in f["fields"]])
            for src in token_sources:
                for tok in split_tokens(src):
                    if tok in {"order", "return", "item", "product", "customer", "supplier", "party", "shipment", "payment", "address", "invoice"}:
                        core = core_context_entities_for_token(tok, all_entities)
                        if core:
                            context_entities.update(core)
                        else:
                            context_entities.update(entity_token_index.get(tok, set()))

            # always keep context narrower than area-wide universe
            context_entities = {e for e in context_entities if e not in direct_entities}
            context_entities = set(sorted(context_entities)[:20])
            area_entities_list = area_entities(all_entities, area)

            req = sorted(set(req))
            service_required_parameters = sorted(set(service_required_parameters))
            opt = sorted(set(opt))
            service_categories = sorted({c for c in service_categories if c})
            navigation_only = bool(has_nav_response and not bound_services)
            doc_kind = classify_doc_kind(canonical, tname, bound_services, read_only, direct_labels, svc_defs)
            query_like = any(x in (canonical or "") for x in ["find", "search", "list", "detail", "view"])
            operation_effect = classify_operation_effect(doc_kind, tname, bound_services, svc_defs, navigation_only, query_like)
            action_kind = classify_action_kind(doc_kind, operation_effect, canonical, tname, navigation_only)
            primary_screen_purpose = classify_primary_screen_purpose(screen_name, doc_kind, operation_effect, canonical)
            action_verb = map_operation_to_action_verb(operation_effect, tname, canonical)
            read_only = operation_effect in {"read_query", "read_detail", "navigation", "validation", "external_call"}
            mutative = operation_effect in {"create", "update", "delete", "status_transition", "batch_update"}

            # context parameters from forms/transition/path expressions
            screen_context_parameters = set()
            for fn in [fn for f in form_by_transition.get(tname, []) for fn in f["fields"]]:
                if fn in KNOWN_CONTEXT_PARAMS or fn.lower().endswith("id") or "seqid" in fn.lower():
                    screen_context_parameters.add(fn)
            for p in t.findall(".//parameter"):
                pn = p.attrib.get("name")
                if pn:
                    screen_context_parameters.add(pn)
            path_parameters = set(
                extract_path_parameters(
                    t.attrib.get("url", ""),
                    t.attrib.get("target", ""),
                    (default_resp.attrib.get("url", "") if default_resp is not None else ""),
                )
            )
            for pp in path_parameters:
                if pp in KNOWN_CONTEXT_PARAMS or pp.lower().endswith("id") or "seqid" in pp.lower():
                    screen_context_parameters.add(pp)

            inferred_required_context = set(service_required_parameters)
            path_required_context = {
                pp for pp in path_parameters
                if pp in KNOWN_CONTEXT_PARAMS or pp.lower().endswith("id") or "seqid" in pp.lower()
            }
            canon_l = canonical.lower()

            # Only treat broad screen context as required for documents that are inherently
            # stateful or navigational. For create flows, a form often exposes optional
            # parent/context fields that should not block execution when the service itself
            # only requires a smaller set of parameters.
            if action_kind in {"update", "delete", "status", "navigate", "list", "detail"} or \
                    resolution_policy == "navigate_then_maybe_update" or \
                    operation_effect in {"read_query", "read_detail", "navigation", "validation"}:
                inferred_required_context.update(screen_context_parameters)
            else:
                inferred_required_context.update(path_required_context)

            # lightweight business inference when service definition misses required flags;
            # avoid inflating create prompts with synthetic requirements that are not
            # explicitly declared by the service.
            if action_kind != "create":
                if "order" in canon_l:
                    inferred_required_context.add("orderId")
                if "part" in canon_l:
                    inferred_required_context.add("orderPartSeqId")
                if "item" in canon_l:
                    inferred_required_context.add("orderItemSeqId")
                if "return" in canon_l:
                    inferred_required_context.add("returnId")
                if "address" in canon_l:
                    inferred_required_context.add("postalContactMechId")

            execution_required_context = set(inferred_required_context)

            # Remove fields the service auto-derives from other inputs or fills with literals
            # (parsed from <actions> <if condition="!fieldA && fieldB"> and <if condition="!fieldA"><set value=...>)
            svc_auto_derivable: dict[str, str] = {}
            svc_literal_defaults: set[str] = set()
            svc_call_chain: list[str] = []
            if preferred_service and preferred_service in svc_defs:
                sd = svc_defs[preferred_service]
                svc_auto_derivable = sd.auto_derivable or {}
                svc_literal_defaults = sd.literal_defaults or set()
                svc_call_chain = sd.call_chain or []
                execution_required_context -= set(svc_auto_derivable.keys())
                execution_required_context -= svc_literal_defaults

            # Entity BOM enrichment: find parent/child relationships for the PRIMARY entity.
            # Primary entity = the one the service creates/updates (matches service noun),
            # NOT the lookup entities touched in <actions> (e.g. Facility looked up to derive fromPartyId).
            bom_parent_strs: list[str] = []   # "Child of ParentEntity (via fkField)"
            bom_child_strs: list[str] = []    # "ChildA (via fk), ChildB (via fk)"
            if entity_bom and preferred_service and preferred_service in svc_defs:
                sd = svc_defs[preferred_service]
                svc_noun_lower = sd.noun.lower() if sd.noun else ""

                # Strategy 1: match noun to touched_entities (direct <entity-find-one entity-name=...>)
                primary_ent_fqn: str | None = None
                for tent in sd.touched_entities:
                    if tent.split(".")[-1].lower() == svc_noun_lower:
                        primary_ent_fqn = tent
                        break

                # Strategy 2: derive from service package + noun.
                # Service FQN: "pkg.ClassName.verb#Noun" → try "pkg.Noun" as entity FQN.
                # e.g. "mantle.shipment.ShipmentServices.create#Shipment" → "mantle.shipment.Shipment"
                if not primary_ent_fqn and svc_noun_lower and "." in preferred_service:
                    parts = preferred_service.split(".")
                    # Parts before the CamelCase class name are the package
                    pkg_parts = []
                    for part in parts:
                        if part and part[0].isupper() and "#" not in part:
                            break
                        pkg_parts.append(part)
                    if pkg_parts:
                        candidate = ".".join(pkg_parts) + "." + sd.noun
                        if candidate in entity_bom:
                            primary_ent_fqn = candidate

                # Strategy 3: scan call_chain for "verb#pkg.Noun" entity CRUD patterns
                # e.g. "create#mantle.shipment.Shipment" → entity FQN = "mantle.shipment.Shipment"
                if not primary_ent_fqn and svc_noun_lower:
                    for svc_call in sd.call_chain:
                        if "#" in svc_call and "." in svc_call.split("#")[1]:
                            # Looks like entity CRUD: verb#package.EntityName
                            _, entity_candidate = svc_call.split("#", 1)
                            if entity_candidate.split(".")[-1].lower() == svc_noun_lower:
                                if entity_candidate in entity_bom:
                                    primary_ent_fqn = entity_candidate
                                    break

                if primary_ent_fqn:
                    bom_entry = entity_bom.get(primary_ent_fqn)
                    if bom_entry:
                        ent_short = primary_ent_fqn.split(".")[-1]
                        # Parents with FK (type="one") — skip Enumeration/StatusItem/Uom (reference data)
                        skip_ref = {"Enumeration", "StatusItem", "Uom", "StatusFlowItem"}
                        for (parent_fqn, fk) in bom_entry.parents:
                            parent_short = parent_fqn.split(".")[-1]
                            if fk and parent_short not in skip_ref:
                                bom_parent_strs.append(f"{ent_short} child of {parent_short} (via {fk})")
                            if len(bom_parent_strs) >= 4:
                                break
                        # Children (type="many") — up to 6
                        for (child_fqn, fk) in bom_entry.children:
                            child_short = child_fqn.split(".")[-1]
                            if fk:
                                bom_child_strs.append(f"{child_short} (via {fk})")
                            if len(bom_child_strs) >= 6:
                                break
            resolution_policy = infer_resolution_policy(
                doc_kind=doc_kind,
                action_kind=action_kind,
                operation_effect=operation_effect,
                screen_name=screen_name,
                canonical=canonical,
                transition_name=tname,
                preferred_service=preferred_service,
                primary_screen_purpose=primary_screen_purpose,
            )
            mutation_requires_field_diff = resolution_policy == "navigate_then_maybe_update"

            # tighten direct entities for business actions
            primary_direct = set(infer_direct_entities_from_hint(canonical, all_entities))
            if service_categories and any(x in service_categories[0] for x in ["status-transition", "crud-update", "crud-create", "crud-delete", "business-action"]):
                if primary_direct:
                    direct_entities = set(sorted(primary_direct)[:5])
                else:
                    direct_entities = set(sorted(direct_entities)[:5])
            else:
                direct_entities = set(sorted(direct_entities)[:5])

            form_names = sorted({f["name"] for f in form_by_transition.get(tname, []) if f["name"]})
            localized_ui_labels, localized_ui_entries = collect_transition_localizations(
                screen_rel,
                tname,
                form_names,
                direct_labels,
                localization_catalog,
            )
            sub_area = infer_subarea(area, canonical, screen_name, tname, form_names, direct_entities, preferred_service)
            service_noun = None
            if preferred_service and preferred_service in svc_defs:
                service_noun = svc_defs[preferred_service].noun
            domain_object = infer_domain_object(
                area=area,
                form_names=form_names,
                transition_name=tname,
                service_noun=service_noun,
                screen_name=screen_name,
                section_labels=section_labels,
                direct_entities=direct_entities,
                related_views=related_views,
                field_names=sorted({fn for f in form_by_transition.get(tname, []) for fn in f["fields"]}),
                source_path=screen_rel,
                sub_area=sub_area,
            )
            state_comparison_entity = infer_state_comparison_entity(domain_object, direct_entities)
            state_comparison_pk_fields = infer_state_comparison_pk_fields(execution_required_context)
            canonical = make_dense_area_canonical(
                area=area,
                doc_kind=doc_kind,
                operation_effect=operation_effect,
                action_kind=action_kind,
                domain_object=domain_object,
                fallback_canonical=("print order" if canonical == "print order pdf" else canonical),
                form_names=form_names,
                transition_name=tname,
                screen_name=screen_name,
            )
            prompt_variants = expand_prompt_variants(canonical, direct_labels, tname, preferred_service)
            prompt_variants = sorted({*prompt_variants, *localized_ui_labels})
            machine_variants, english_variants, italian_variants = split_variant_buckets(prompt_variants)
            field_label_details = collect_field_label_details(
                screen_rel,
                form_by_transition.get(tname, []),
                direct_entities,
                context_entities,
                localization_catalog,
            )
            localized_field_labels = sorted({
                value
                for detail in field_label_details
                for locale, value in detail.get("labels", {}).items()
                if locale != "default" and value
            })
            field_label_summary = "; ".join(
                f"{detail['name']}: {', '.join(value for value in detail.get('labels', {}).values() if value)}"
                for detail in field_label_details[:6]
            )
            pgroup_id = prompt_group_id(area, sub_area, domain_object, action_kind, action_verb)
            area_slug = slug(area)
            screen_slug = slug(screen_name)
            id_slug = slug(tname or screen_name)
            doc_id = f"agent-prompt://{area_slug}/{screen_slug}/{id_slug}"
            top_actions[f"{area}:{canonical}"] += 1

            source_artifacts = [screen_rel, str(sx)]
            if preferred_service and preferred_service in svc_defs:
                source_artifacts.append(svc_defs[preferred_service].source_path)
            embedding_text = (
                f"This Moqui UI action lets the user {canonical}. "
                f"Use it when user asks to {canonical} or similar. "
                f"It is exposed in screen {screen_name} in area {area}. "
                f"Sub area is {sub_area}. "
                f"Domain object is {domain_object}. "
                f"Prompt group id is {pgroup_id}. "
                f"Transition name is {tname}. "
                f"Preferred service is {preferred_service or 'none'}. "
                f"Service classification is {service_categories[0] if service_categories else 'none'}. "
                f"Operation effect is {operation_effect}. "
                f"Action kind is {action_kind}. "
                f"Resolution policy is {resolution_policy}. "
                f"Primary screen purpose is {primary_screen_purpose}. "
                f"Mutation requires field difference is {'true' if mutation_requires_field_diff else 'false'}. "
                f"State comparison entity is {state_comparison_entity or 'none'}. "
                f"Direct entities are {', '.join(sorted(direct_entities)[:8]) if direct_entities else 'none'}. "
                f"Context entities are {', '.join(sorted(context_entities)[:8]) if context_entities else 'none'}. "
                f"Declared service required parameters are {', '.join(service_required_parameters) if service_required_parameters else 'none'}. "
                f"Execution requires context {', '.join(sorted(execution_required_context)) if execution_required_context else 'none'}. "
            )
            if svc_auto_derivable:
                embedding_text += (
                    f"Auto-derived fields (not needed from user): "
                    f"{', '.join(f'{k} ({v})' for k, v in sorted(svc_auto_derivable.items())[:6])}. "
                )
            if svc_literal_defaults:
                embedding_text += f"Fields with system defaults: {', '.join(sorted(svc_literal_defaults)[:6])}. "
            if svc_call_chain:
                embedding_text += f"Service execution order: {' → '.join(c.split('.')[-1] for c in svc_call_chain[:6])}. "
            if bom_parent_strs:
                embedding_text += f"Entity hierarchy (BOM): {'; '.join(bom_parent_strs[:4])}. "
            if bom_child_strs:
                embedding_text += f"Entity has child records: {', '.join(bom_child_strs[:4])}. "
            embedding_text += f"Visible UI labels are {', '.join(direct_labels[:6]) if direct_labels else 'none'}. "
            if localized_ui_labels:
                embedding_text += f"Localized UI labels are {', '.join(localized_ui_labels[:6])}. "
            if field_label_summary:
                embedding_text += f"Field labels include {field_label_summary}. "
            embedding_text += (
                f"Users may ask (EN): {', '.join(english_variants[:6]) if english_variants else 'none'}. "
                f"Users may ask (IT): {', '.join(italian_variants[:6]) if italian_variants else 'none'}."
            )

            doc = {
                "documentId": doc_id,
                "documentKind": doc_kind,
                "area": area,
                "subArea": sub_area,
                "domainObject": domain_object,
                "promptGroupId": pgroup_id,
                "actionKind": action_kind,
                "resolutionPolicy": resolution_policy,
                "primaryScreenPurpose": primary_screen_purpose,
                "mutationRequiresFieldDiff": mutation_requires_field_diff,
                "stateComparisonEntity": state_comparison_entity,
                "stateComparisonPkFields": state_comparison_pk_fields,
                "updateTransitionName": None,
                "updateServiceName": None,
                "equivalentPromptIds": [],
                "sourceScreenPath": screen_rel,
                "sourceWidgetPath": f"/screen/transition[@name='{tname}']",
                "screenName": screen_name,
                "canonicalPrompt": canonical,
                "promptVariants": prompt_variants,
                "machineVariants": machine_variants,
                "englishPromptVariants": english_variants,
                "italianPromptVariants": italian_variants,
                "uiLabels": direct_labels,
                "localizedUiLabels": localized_ui_labels,
                "localizedUiLabelEntries": localized_ui_entries,
                "nearbyLabels": nearby_labels,
                "sectionLabels": section_labels,
                "transitionNames": [tname] if tname else [],
                "formNames": form_names,
                "fieldNames": sorted({fn for f in form_by_transition.get(tname, []) for fn in f['fields']}),
                "fieldLabelDetails": field_label_details,
                "localizedFieldLabels": localized_field_labels,
                "boundServices": sorted(set(bound_services)),
                "preferredService": preferred_service,
                "serviceClassification": service_categories[0] if service_categories else None,
                "allowedIntentVerbs": (
                    QUERY_ALLOWED_VERBS if doc_kind == "screen_query_prompt" else []
                ),
                "excludedIntentVerbs": (
                    QUERY_EXCLUDED_VERBS if doc_kind == "screen_query_prompt" else []
                ),
                "requiredParameters": req,
                "declaredServiceRequiredParameters": service_required_parameters,
                "screenContextParameters": sorted(screen_context_parameters),
                "inferredRequiredContext": sorted(inferred_required_context),
                "executionRequiredContext": sorted(execution_required_context),
                "pathParameters": sorted(path_parameters),
                "optionalParameters": opt,
                "directEntities": sorted(direct_entities)[:5],
                "contextEntities": sorted(context_entities)[:8],
                "areaEntities": [],
                "relatedEntities": sorted(set(sorted(direct_entities)[:5]) | set(sorted(context_entities)[:8])),
                "relatedViewEntities": sorted(related_views),
                "relatedEca": related_eca,
                "operationEffect": operation_effect,
                "promptSourceConfidence": (
                    "direct_ui_label" if direct_labels else "transition_name"
                ),
                "executionChannel": infer_execution_channel(
                    preferred_service,
                    doc_kind,
                    operation_effect,
                    classify_unbound_prompt_kind(doc_kind, navigation_only, canonical, tname) if not preferred_service else None,
                ),
                "readOnly": read_only,
                "mutative": mutative,
                "navigationOnly": navigation_only,
                "promptWithoutServiceKind": classify_unbound_prompt_kind(doc_kind, navigation_only, canonical, tname) if not preferred_service else None,
                "runtimeExecutable": bool(preferred_service),
                "humanExplanation": f"UI action '{canonical}' from screen {screen_name} in area {area}.",
                "embeddingText": embedding_text,
                "sourceArtifacts": sorted(set(source_artifacts)),
                "artifactVersionHash": hash_files(source_artifacts),
                "generatedAt": now_utc(),
            }
            if service_action_index is not None:
                enrich_prompt_doc_with_service_actions(doc, service_action_index)
            prompt_docs.append(doc)
            per_area[area]["prompt_documents_generated"] += 1

        # optional screen_query_prompt for search/list forms
        for f in forms:
            if f["formType"] == "form-list" and f["name"]:
                form_hint = normalize_prompt_text(f["name"])
                direct_entities = set(infer_direct_entities_from_hint(form_hint, all_entities))
                sub_area = infer_subarea(area, form_hint, screen_name, f.get("transition", ""), [f["name"]], direct_entities, None)
                domain_object = infer_domain_object(
                    area=area,
                    form_names=[f["name"]],
                    transition_name=f.get("transition", ""),
                    service_noun=None,
                    screen_name=screen_name,
                    section_labels=[pretty_token(screen_name)],
                    direct_entities=direct_entities,
                    related_views=all_views,
                    field_names=f["fields"],
                    source_path=screen_rel,
                    sub_area=sub_area,
                )
                subject = pretty_token(domain_object).strip() or area_subject(area)
                src = f"{form_hint} {screen_name}".lower()
                if any(x in src for x in ["history", "audit", "detail", "summary"]):
                    canonical = f"list {subject} records"
                elif any(x in src for x in ["list", "grid", "table", "find"]):
                    canonical = f"list {pluralize_phrase(subject)}"
                else:
                    canonical = f"search {pluralize_phrase(subject)}"
                canonical = make_dense_area_canonical(
                    area=area,
                    doc_kind="screen_query_prompt",
                    operation_effect="read_query",
                    action_kind="list",
                    domain_object=domain_object,
                    fallback_canonical=canonical,
                    form_names=[f["name"]],
                    transition_name=f.get("transition", ""),
                    screen_name=screen_name,
                )
                pvars = expand_prompt_variants(canonical, [f["name"], form_hint], f.get("transition", ""), None)
                # query/list prompts must stay read-oriented
                pvars = [pv for pv in pvars if not is_mutative_text(pv)]
                if canonical not in pvars:
                    pvars = [canonical] + pvars
                doc_id = f"agent-prompt://{slug(area)}/{slug(screen_name)}/{slug(f['name'])}-search"
                top_actions[f"{area}:{canonical}"] += 1
                context_entities = set()
                for tok in split_tokens(" ".join([canonical, f["name"], " ".join(f["fields"])])):
                    context_entities.update(entity_token_index.get(tok, set()))
                context_entities = set(sorted(context_entities)[:8])
                form_hint = f["name"]
                direct_entities = set(infer_direct_entities_from_hint(form_hint, all_entities))
                direct_entities.update(infer_direct_entities_from_hint(canonical, all_entities))
                screen_context_parameters = sorted({fn for fn in f["fields"] if fn in KNOWN_CONTEXT_PARAMS or fn.lower().endswith("id") or "seqid" in fn.lower()})
                inferred_ctx = sorted(set(screen_context_parameters) | ({'orderId'} if 'order' in canonical else set()))
                execution_ctx = inferred_ctx
                field_label_details = collect_field_label_details(
                    screen_rel,
                    [f],
                    direct_entities,
                    context_entities,
                    localization_catalog,
                )
                localized_field_labels = sorted({
                    value
                    for detail in field_label_details
                    for locale, value in detail.get("labels", {}).items()
                    if locale != "default" and value
                })
                field_label_summary = "; ".join(
                    f"{detail['name']}: {', '.join(value for value in detail.get('labels', {}).values() if value)}"
                    for detail in field_label_details[:6]
                )
                machine_variants, english_variants, italian_variants = split_variant_buckets(pvars)
                action_kind = "list" if canonical.startswith(("list ", "search ", "find ")) else "detail"
                action_verb = "list" if canonical.startswith("list ") else ("search" if canonical.startswith("search ") else "view")
                pgroup_id = prompt_group_id(area, sub_area, domain_object, action_kind, action_verb)
                query_embedding_text = (
                    f"This Moqui UI query action lets the user {canonical}. "
                    f"It is exposed in screen {screen_name} (form {f['name']}). "
                    f"Sub area is {sub_area}. Domain object is {domain_object}. Prompt group id is {pgroup_id}. "
                    f"Resolution policy is retrieve_or_query. "
                    f"Execution requires context {', '.join(execution_ctx) if execution_ctx else 'none'}. "
                )
                if field_label_summary:
                    query_embedding_text += f"Field labels include {field_label_summary}. "
                query_embedding_text += f"Users may ask: {', '.join(pvars[:10])}."
                prompt_docs.append(
                    {
                        "documentId": doc_id,
                        "documentKind": "screen_query_prompt",
                        "area": area,
                        "subArea": sub_area,
                        "domainObject": domain_object,
                        "promptGroupId": pgroup_id,
                        "actionKind": action_kind,
                        "resolutionPolicy": "retrieve_or_query",
                        "primaryScreenPurpose": "find",
                        "mutationRequiresFieldDiff": False,
                        "stateComparisonEntity": None,
                        "stateComparisonPkFields": [],
                        "updateTransitionName": None,
                        "updateServiceName": None,
                        "equivalentPromptIds": [],
                        "sourceScreenPath": screen_rel,
                        "sourceWidgetPath": f"/screen/{f['formType']}[@name='{f['name']}']",
                        "screenName": screen_name,
                        "canonicalPrompt": canonical,
                        "promptVariants": pvars,
                        "machineVariants": machine_variants,
                        "englishPromptVariants": english_variants,
                        "italianPromptVariants": italian_variants,
                        "uiLabels": [f["name"]],
                        "localizedUiLabels": [],
                        "localizedUiLabelEntries": [],
                        "nearbyLabels": [],
                        "sectionLabels": [pretty_token(screen_name)],
                        "transitionNames": [f.get("transition")] if f.get("transition") else [],
                        "formNames": [f["name"]],
                        "fieldNames": sorted(set(f["fields"])),
                        "fieldLabelDetails": field_label_details,
                        "localizedFieldLabels": localized_field_labels,
                        "boundServices": [],
                        "preferredService": None,
                        "serviceClassification": "read-query",
                        "allowedIntentVerbs": QUERY_ALLOWED_VERBS,
                        "excludedIntentVerbs": QUERY_EXCLUDED_VERBS,
                        "requiredParameters": [],
                        "declaredServiceRequiredParameters": [],
                        "screenContextParameters": screen_context_parameters,
                        "inferredRequiredContext": inferred_ctx,
                        "executionRequiredContext": execution_ctx,
                        "pathParameters": [],
                        "optionalParameters": sorted(set(f["fields"])),
                        "directEntities": sorted(direct_entities)[:5],
                        "contextEntities": sorted(context_entities),
                        "areaEntities": [],
                        "relatedEntities": sorted(set(sorted(direct_entities)[:5]) | set(context_entities)),
                        "relatedViewEntities": [v for v in sorted(all_views) if "find" in v.lower() or "summary" in v.lower()][:12],
                        "relatedEca": [],
                        "operationEffect": "read_query",
                        "promptSourceConfidence": "inferred_from_field",
                        "executionChannel": "search",
                        "readOnly": True,
                        "mutative": False,
                        "navigationOnly": False,
                        "promptWithoutServiceKind": "query_form",
                        "runtimeExecutable": False,
                        "humanExplanation": f"Search/list prompt from form {f['name']} in screen {screen_name}.",
                        "embeddingText": query_embedding_text,
                        "sourceArtifacts": [screen_rel, str(sx)],
                        "artifactVersionHash": hash_files([screen_rel, str(sx)]),
                        "generatedAt": now_utc(),
                    }
                )
                if service_action_index is not None:
                    enrich_prompt_doc_with_service_actions(prompt_docs[-1], service_action_index)
                per_area[area]["prompt_documents_generated"] += 1

    # dedup by documentId
    merged = {}
    for d in prompt_docs:
        merged[d["documentId"]] = d
    prompt_docs = list(merged.values())

    # assign equivalent prompts by promptGroupId (soft-match family)
    by_group = defaultdict(list)
    for d in prompt_docs:
        by_group[d.get("promptGroupId", "")].append(d["documentId"])
    for d in prompt_docs:
        siblings = sorted(x for x in by_group.get(d.get("promptGroupId", ""), []) if x != d["documentId"])
        d["equivalentPromptIds"] = siblings[:40]

    by_area_domain = defaultdict(list)
    by_area_screen_domain = defaultdict(list)
    for d in prompt_docs:
        by_area_domain[(d.get("area"), d.get("domainObject"))].append(d)
        by_area_screen_domain[(d.get("area"), d.get("screenName"), d.get("domainObject"))].append(d)
    for d in prompt_docs:
        if d.get("resolutionPolicy") != "navigate_then_maybe_update":
            continue
        candidates = []
        for cand in by_area_screen_domain.get((d.get("area"), d.get("screenName"), d.get("domainObject")), []):
            if cand.get("operationEffect") == "update" and cand.get("preferredService"):
                candidates.append(cand)
        if not candidates:
            for cand in by_area_domain.get((d.get("area"), d.get("domainObject")), []):
                if cand.get("operationEffect") == "update" and cand.get("preferredService") and cand.get("primaryScreenPurpose") == "edit":
                    candidates.append(cand)
        if candidates:
            best = sorted(candidates, key=lambda x: (x.get("screenName") != d.get("screenName"), x.get("documentId")))[0]
            d["updateTransitionName"] = (best.get("transitionNames") or [None])[0]
            d["updateServiceName"] = best.get("preferredService")

    # recompute per-area counters after dedup
    per_area_post = defaultdict(lambda: {"prompt_documents_generated": 0})
    for d in prompt_docs:
        per_area_post[d.get("area", "General")]["prompt_documents_generated"] += 1
        operation_effect_counts[d.get("operationEffect", "unknown")] += 1
        action_kind_counts[d.get("actionKind", "unresolved")] += 1
        if not d.get("preferredService"):
            prompt_without_service_breakdown[d.get("promptWithoutServiceKind") or "unresolved_binding"] += 1

    metrics.update(
        {
            "prompt_documents_generated": len(prompt_docs),
            "prompt_with_preferred_service": len([d for d in prompt_docs if d.get("preferredService")]),
            "prompt_read_only": len([d for d in prompt_docs if d.get("readOnly")]),
            "prompt_mutative": len([d for d in prompt_docs if d.get("mutative")]),
            "prompt_navigation_only": len([d for d in prompt_docs if d.get("navigationOnly")]),
            "service_reachable_from_ui": len(service_reachable),
            "service_names_reachable": sorted(service_reachable),
            "top_actions": top_actions.most_common(20),
            "operation_effect_counts": dict(sorted(operation_effect_counts.items())),
            "action_kind_counts": dict(sorted(action_kind_counts.items())),
            "prompt_without_service_breakdown": dict(sorted(prompt_without_service_breakdown.items())),
            "per_area": {k: dict(v) for k, v in sorted(per_area.items())},
            "per_area_prompt_docs_final": {k: dict(v) for k, v in sorted(per_area_post.items())},
        }
    )

    return prompt_docs, metrics


def build_task_groups(prompt_docs: list[dict]) -> list[dict]:
    groups = defaultdict(list)

    def group_for(doc: dict) -> str:
        c = (doc.get("canonicalPrompt") or "").lower()
        if any(x in c for x in ["find", "search", "view", "detail", "latest"]):
            return "find-view"
        if any(x in c for x in ["item", "product", "quantity"]):
            return "items"
        if any(x in c for x in ["approve", "cancel", "place", "reject", "complete", "hold", "status"]):
            return "status"
        if any(x in c for x in ["billing", "payment", "invoice", "ship", "address", "validate"]):
            return "billing-shipping"
        if any(x in c for x in ["print", "pdf", "email", "export", "download"]):
            return "print"
        if "return" in c:
            return "returns"
        if "create" in c:
            return "create"
        return "other"

    for d in prompt_docs:
        area = slug(d.get("area", "general"))
        g = group_for(d)
        groups[(area, g)].append(d)

    out = []
    for (area, g), docs in sorted(groups.items()):
        ids = sorted(d["documentId"] for d in docs)
        intents = sorted({d.get("canonicalPrompt") for d in docs if d.get("canonicalPrompt")})
        main_svcs = sorted({d.get("preferredService") for d in docs if d.get("preferredService")})
        out.append(
            {
                "documentId": f"agent-task://{area}/{g}",
                "documentKind": "task_group",
                "area": area.capitalize(),
                "promptDocumentIds": ids,
                "mainPrompts": intents[:50],
                "mainServices": main_svcs[:50],
                "mainEntities": sorted({e for d in docs for e in d.get("directEntities", [])} | {e for d in docs for e in d.get("contextEntities", [])})[:80],
                "embeddingText": f"Task group {g} in area {area}. Contains UI actions: {', '.join(intents[:20])}.",
                "generatedAt": now_utc(),
            }
        )
    return out


def build_area_overviews(prompt_docs: list[dict]) -> list[dict]:
    by_area = defaultdict(list)
    for d in prompt_docs:
        by_area[d.get("area", "General")].append(d)

    out = []
    for area, docs in sorted(by_area.items()):
        ids = sorted(d["documentId"] for d in docs)
        prompts = sorted({d.get("canonicalPrompt") for d in docs if d.get("canonicalPrompt")})
        svcs = sorted({d.get("preferredService") for d in docs if d.get("preferredService")})
        ents = sorted({e for d in docs for e in d.get("areaEntities", [])})
        direct_ents = sorted({e for d in docs for e in d.get("directEntities", [])})
        context_ents = sorted({e for d in docs for e in d.get("contextEntities", [])})
        if not ents:
            ents = sorted(set(direct_ents) | set(context_ents))
        out.append(
            {
                "documentId": f"agent-area://{slug(area)}/overview",
                "documentKind": "area_overview",
                "area": area,
                "promptDocumentIds": ids,
                "mainPrompts": prompts[:120],
                "mainServices": svcs[:120],
                "mainEntities": ents[:120],
                "mainDirectEntities": direct_ents[:120],
                "mainContextEntities": context_ents[:120],
                "embeddingText": f"Area overview for {area}. Derived from screen prompt documents. Main prompts: {', '.join(prompts[:30])}.",
                "generatedAt": now_utc(),
            }
        )
    return out


def build_eval_queries(prompt_docs: list[dict]) -> list[dict]:
    out = []
    generic = {"find", "search", "list", "view", "detail", "orderdetail", "record", "search form", "find record", "search record"}
    mutative_re = re.compile(r"\b(create|update|delete|add|remove|set|approve|cancel|complete)\b")
    by_area_screen = defaultdict(list)
    for pd in prompt_docs:
        by_area_screen[(pd.get("area"), pd.get("screenName"))].append(pd)

    def retarget_mutative(query: str, src_doc: dict) -> str | None:
        cands = []
        area = src_doc.get("area")
        screen = src_doc.get("screenName")
        for d2 in by_area_screen.get((area, screen), []):
            if d2.get("documentKind") == "screen_query_prompt":
                continue
            c2 = (d2.get("canonicalPrompt") or "").lower()
            if mutative_re.search(c2):
                overlap = len(set(split_tokens(query)) & set(split_tokens(c2)))
                cands.append((overlap, d2.get("documentId")))
        cands.sort(reverse=True)
        if cands and cands[0][0] > 0:
            return cands[0][1]
        return None

    def retarget_navigation_first(query: str, src_doc: dict) -> str | None:
        area = src_doc.get("area")
        domain = src_doc.get("domainObject")
        query_tokens = set(tokenize_query_like(query))
        cands = []
        for d2 in prompt_docs:
            if d2.get("area") != area or d2.get("domainObject") != domain:
                continue
            if d2.get("documentKind") == "screen_query_prompt":
                continue
            if d2.get("actionKind") != "navigate":
                continue
            score = 0
            if d2.get("resolutionPolicy") == "navigate_then_maybe_update":
                score += 6
            if d2.get("primaryScreenPurpose") == "edit":
                score += 4
            if d2.get("executionChannel") == "navigation":
                score += 3
            score += len(query_tokens & set(tokenize_query_like(d2.get("canonicalPrompt", ""))))
            cands.append((score, d2.get("documentId")))
        cands.sort(reverse=True)
        if cands and cands[0][0] > 0:
            return cands[0][1]
        return None

    for d in prompt_docs:
        base = {
            "targetDocumentId": d["documentId"],
            "targetPromptGroupId": d.get("promptGroupId"),
            "expectedTopK": 3,
            "area": d.get("area"),
            "subArea": d.get("subArea"),
            "domainObject": d.get("domainObject"),
            "actionKind": d.get("actionKind"),
            "documentKind": d.get("documentKind"),
        }
        queries = []
        if d.get("canonicalPrompt"):
            queries.append(d["canonicalPrompt"])
        queries.extend(d.get("englishPromptVariants", [])[:8])
        queries.extend(d.get("italianPromptVariants", [])[:8])
        queries.extend(d.get("machineVariants", [])[:3])
        queries.extend(d.get("promptVariants", [])[:6])
        if not (d.get("documentKind") == "screen_query_prompt" and d.get("area") in FOCUSED_DENSE_AREAS):
            queries.extend(d.get("transitionNames", []))
            queries.extend(d.get("uiLabels", [])[:4])
        seen = set()
        excluded = {x.lower() for x in d.get("excludedIntentVerbs", [])}
        for q in queries:
            nq = norm(str(q))
            if not nq or nq in seen:
                continue
            if nq in generic:
                continue
            if "search form" in nq or "record by" in nq:
                continue
            if d.get("area") in FOCUSED_DENSE_AREAS and len(split_tokens(nq)) < 3:
                continue
            # avoid overly generic single-token queries except when canonical itself is single token
            if len(nq.split()) == 1 and nq != norm(d.get("canonicalPrompt", "")):
                continue
            if d.get("documentKind") == "screen_query_prompt":
                # query prompts must include domain clues
                dom_tokens = {t for t in split_tokens(d.get("domainObject", "")) if len(t) > 2}
                if dom_tokens and not (set(split_tokens(nq)) & dom_tokens):
                    continue
                if d.get("area") in FOCUSED_DENSE_AREAS and len(split_tokens(nq)) < 3:
                    continue
                if d.get("area") in FOCUSED_DENSE_AREAS and "form" in nq:
                    continue
            # do not emit mutative phrases for screen_query_prompt
            if d.get("documentKind") == "screen_query_prompt":
                toks = set(split_tokens(nq))
                if any(v in toks for v in excluded) or mutative_re.search(nq):
                    continue
            seen.add(nq)
            rec = dict(base)
            rec["query"] = nq
            qmeta = classify_user_query(nq, d.get("fieldNames", []))
            rec["queryIntentType"] = qmeta["queryIntentType"]
            rec["queryActionKindHint"] = qmeta["actionKindHint"]
            rec["queryResolutionPolicyHint"] = qmeta["resolutionPolicyHint"]
            rec["requiresValueComparison"] = qmeta["requiresValueComparison"]
            rec["candidateFieldNames"] = qmeta["candidateFieldNames"]
            if qmeta["resolutionPolicyHint"] == "navigate_then_maybe_update" and not qmeta["hasExplicitFieldMutation"]:
                alt = retarget_navigation_first(nq, d)
                if alt and alt != rec["targetDocumentId"]:
                    rec["targetDocumentId"] = alt
                    rec["targetAdjusted"] = True
                    rec["targetAdjustmentReason"] = "navigate_first_edit_update_semantics"
            elif d.get("documentKind") == "screen_query_prompt" and rec["queryIntentType"] == "mutative":
                alt = retarget_mutative(nq, d)
                if alt:
                    rec["targetDocumentId"] = alt
                    rec["targetAdjusted"] = True
                    rec["targetAdjustmentReason"] = "mutative_query_retargeted_from_query_prompt"
            out.append(rec)
    return out


def build_support_service_documents(
    all_services: set[str],
    reachable_services: set[str],
    svc_defs: dict[str, ServiceDef],
    area: str,
) -> list[dict]:
    out = []
    for s in sorted(all_services - reachable_services):
        sd = svc_defs.get(s)
        cls = classify_unreachable_service(s, svc_defs)
        out.append(
            {
                "documentId": f"agent-service-support://{slug(area)}/{slug(s.split('.')[-1])}",
                "documentKind": "support_service",
                "area": area,
                "serviceName": s,
                "supportClassification": cls,
                "serviceClassification": classify_service(s, svc_defs),
                "verb": sd.verb if sd else None,
                "noun": sd.noun if sd else None,
                "serviceRequiredParameters": (sd.required if sd else []),
                "touchedEntities": (sd.touched_entities if sd else []),
                "sourceArtifacts": [sd.source_path] if sd and sd.source_path else [],
                "embeddingText": (
                    f"Support service {s} in area {area}. "
                    f"It is not directly reachable from current UI transitions. "
                    f"Classification: {cls}. "
                    f"Use for enrichment/planning, not as primary user prompt."
                ),
                "generatedAt": now_utc(),
            }
        )
    return out


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate screen-derived prompt catalog")
    ap.add_argument("--screens-dir", required=True)
    ap.add_argument("--services-dir", required=True)
    ap.add_argument("--entities", required=True)
    ap.add_argument("--views", required=True)
    ap.add_argument("--eeca", required=True)
    ap.add_argument("--service-action-statements", default="")
    ap.add_argument("--service-action-documents", default="")
    ap.add_argument("--localization-catalog", default="")
    ap.add_argument("--output-dir", default="output")
    args = ap.parse_args()

    screens_dir = Path(args.screens_dir)
    services_dir = Path(args.services_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    def split_paths(v: str) -> list[Path]:
        return [Path(x.strip()) for x in v.split(",") if x.strip()]

    svc_defs, all_services = parse_services(services_dir)
    entity_files = split_paths(args.entities)
    all_entities = parse_entities(entity_files)
    all_views = parse_view_entities(split_paths(args.views))
    eecas = parse_eeca(split_paths(args.eeca))
    entity_bom = build_entity_bom(entity_files)
    service_action_index = load_service_action_index(
        Path(args.service_action_statements) if args.service_action_statements else None,
        Path(args.service_action_documents) if args.service_action_documents else None,
    )
    localization_catalog = load_localization_catalog(
        Path(args.localization_catalog) if args.localization_catalog else None
    )

    root_area = canonical_area_from_root_path(screens_dir)
    prompts, metrics = parse_screens_and_prompts(
        screens_dir,
        svc_defs,
        all_entities,
        all_views,
        eecas,
        service_action_index=service_action_index,
        localization_catalog=localization_catalog,
        root_area=root_area,
        entity_bom=entity_bom,
    )
    task_groups = build_task_groups(prompts)
    area_overviews = build_area_overviews(prompts)
    eval_queries = build_eval_queries(prompts)

    # compute service unreached
    reachable = set(metrics.get("service_names_reachable", []))
    unreached = sorted(all_services - reachable)
    support_service_docs = build_support_service_documents(all_services, reachable, svc_defs, root_area)

    # unique entity/view coverage
    entities_cov = sorted({e for d in prompts for e in d.get("directEntities", [])} | {e for d in prompts for e in d.get("contextEntities", [])})
    views_cov = sorted({v for d in prompts for v in d.get("relatedViewEntities", [])})

    metrics["services_unreachable_from_ui"] = len(unreached)
    metrics["service_names_unreachable"] = unreached
    metrics["entity_correlated"] = len(entities_cov)
    metrics["entity_direct_correlated"] = len(sorted({e for d in prompts for e in d.get("directEntities", [])}))
    metrics["entity_context_correlated"] = len(sorted({e for d in prompts for e in d.get("contextEntities", [])}))
    metrics["view_entities_correlated"] = len(views_cov)
    metrics["prompt_without_service"] = len([d for d in prompts if not d.get("preferredService")])
    support_cls = Counter(d.get("supportClassification") for d in support_service_docs)
    metrics["support_service_documents"] = len(support_service_docs)
    metrics["support_service_classification_counts"] = dict(sorted(support_cls.items()))
    metrics["service_action_documents_loaded"] = len(service_action_index.documents_by_service)
    metrics["service_action_statements_loaded"] = sum(
        len(rows) for rows in service_action_index.statements_by_service.values()
    )
    metrics["localization_entries_loaded"] = len(localization_catalog)
    metrics["generatedAt"] = now_utc()

    write_jsonl(output_dir / "screen-prompt-documents.jsonl", prompts)
    write_jsonl(output_dir / "task-group-documents.jsonl", task_groups)
    write_jsonl(output_dir / "area-overview-documents.jsonl", area_overviews)
    write_jsonl(output_dir / "screen-prompt-eval-queries.jsonl", eval_queries)
    write_jsonl(output_dir / "support-service-documents.jsonl", support_service_docs)

    report = []
    report.append("# Screen Prompt Catalog Report")
    report.append("")
    report.append("## Summary")
    report.append(f"- Generated at: `{now_utc()}`")
    report.append(f"- Screen files processed: `{metrics['screen_files_processed']}`")
    report.append(f"- Prompt documents generated: `{metrics['prompt_documents_generated']}`")
    report.append(f"- Task groups generated: `{len(task_groups)}`")
    report.append(f"- Area overviews generated: `{len(area_overviews)}`")
    report.append(f"- Support service documents generated: `{len(support_service_docs)}`")
    report.append("")
    report.append("## Metrics")
    report.append(f"- Transitions total: `{metrics['transitions_total']}`")
    report.append(f"- Transitions with service binding: `{metrics['transitions_with_service_binding']}`")
    report.append(f"- Forms total: `{metrics['forms_total']}`")
    report.append(f"- Form submit actions: `{metrics['form_submit_actions']}`")
    report.append(f"- Labels resolved: `{metrics['labels_resolved']}`")
    report.append(f"- Labels unresolved: `{metrics['labels_unresolved']}`")
    report.append(f"- Prompt with preferredService: `{metrics['prompt_with_preferred_service']}`")
    report.append(f"- Prompt without service: `{metrics['prompt_without_service']}`")
    report.append(f"- Prompt read-only: `{metrics['prompt_read_only']}`")
    report.append(f"- Prompt mutative: `{metrics['prompt_mutative']}`")
    report.append(f"- Prompt navigation-only: `{metrics['prompt_navigation_only']}`")
    report.append(f"- Services reachable from UI: `{metrics['service_reachable_from_ui']}`")
    report.append(f"- Services unreachable from UI: `{metrics['services_unreachable_from_ui']}`")
    report.append(f"- Entity correlate: `{metrics['entity_correlated']}`")
    report.append(f"- Entity direct correlate: `{metrics['entity_direct_correlated']}`")
    report.append(f"- Entity context correlate: `{metrics['entity_context_correlated']}`")
    report.append(f"- View-entities correlate: `{metrics['view_entities_correlated']}`")
    report.append("")
    report.append("## Operation Effects")
    for k, v in metrics.get("operation_effect_counts", {}).items():
        report.append(f"- `{k}`: `{v}`")
    report.append("")
    report.append("## Action Kinds")
    for k, v in metrics.get("action_kind_counts", {}).items():
        report.append(f"- `{k}`: `{v}`")
    report.append("")
    report.append("## Prompt Without Service Breakdown")
    for k, v in metrics.get("prompt_without_service_breakdown", {}).items():
        report.append(f"- `{k}`: `{v}`")
    report.append("")
    report.append("## Support Service Classification")
    for k, v in metrics.get("support_service_classification_counts", {}).items():
        report.append(f"- `{k}`: `{v}`")
    report.append("")
    report.append("## Metrics By Area")
    for area, m in metrics.get("per_area", {}).items():
        report.append(f"### {area}")
        report.append(f"- Screen files processed: `{m.get('screen_files_processed', 0)}`")
        report.append(f"- Prompt documents generated (pre-dedup): `{m.get('prompt_documents_generated', 0)}`")
        report.append(
            f"- Prompt documents generated (final): `{metrics.get('per_area_prompt_docs_final', {}).get(area, {}).get('prompt_documents_generated', 0)}`"
        )
        report.append(f"- Transitions total: `{m.get('transitions_total', 0)}`")
        report.append(f"- Transitions with service binding: `{m.get('transitions_with_service_binding', 0)}`")
        report.append(f"- Forms total: `{m.get('forms_total', 0)}`")
        report.append(f"- Form submit actions: `{m.get('form_submit_actions', 0)}`")
        report.append(f"- Labels resolved: `{m.get('labels_resolved', 0)}`")
        report.append(f"- Labels unresolved: `{m.get('labels_unresolved', 0)}`")
        report.append("")
    report.append("## Top 20 UI Actions")
    for name, cnt in metrics.get("top_actions", [])[:20]:
        report.append(f"- `{name}`: `{cnt}`")
    report.append("")
    report.append("## Gaps")
    report.append("- Prompt without service is now split into navigation_only/query_form/print_download/unresolved_binding.")
    report.append("- unresolved_binding does not mean unknown operation; it means no direct service binding was found in the screen artifact.")
    report.append("- Unreachable services are technical/internal services not exposed directly by current screen transitions.")
    report.append("- Unresolved labels likely i18n keys/camel-case tokens and need dictionary/resource resolution in next iteration.")
    report.append("- Keep operationEffect explicit categories to avoid fallback to unknown in future areas.")

    (output_dir / "screen-prompt-catalog-report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    (output_dir / "screen-prompt-catalog-metrics.json").write_text(json.dumps(metrics, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"Wrote {output_dir / 'screen-prompt-documents.jsonl'}")
    print(f"Wrote {output_dir / 'task-group-documents.jsonl'}")
    print(f"Wrote {output_dir / 'area-overview-documents.jsonl'}")
    print(f"Wrote {output_dir / 'screen-prompt-eval-queries.jsonl'}")
    print(f"Wrote {output_dir / 'support-service-documents.jsonl'}")
    print(f"Wrote {output_dir / 'screen-prompt-catalog-report.md'}")
    print(f"Wrote {output_dir / 'screen-prompt-catalog-metrics.json'}")


if __name__ == "__main__":
    main()
