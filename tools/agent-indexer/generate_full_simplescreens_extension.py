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
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass
class AreaConfig:
    name: str
    screens_dir: str
    service_dirs: list[str]
    entity_files: list[str]
    view_files: list[str]
    eeca_files: list[str]


AREA_CONFIGS = [
    AreaConfig("order", "SimpleScreens/screen/SimpleScreens/Order", ["mantle-usl/service/mantle/order"], ["mantle-udm/entity/OrderEntities.xml"], ["mantle-usl/entity/OrderViewEntities.xml"], ["mantle-usl/entity/Order.eecas.xml"]),
    AreaConfig("party", "SimpleScreens/screen/SimpleScreens/Party", ["mantle-usl/service/mantle/party"], ["mantle-udm/entity/PartyEntities.xml"], ["mantle-usl/entity/PartyViewEntities.xml"], []),
    AreaConfig("customer", "SimpleScreens/screen/SimpleScreens/Customer", ["mantle-usl/service/mantle/party"], ["mantle-udm/entity/PartyEntities.xml"], ["mantle-usl/entity/PartyViewEntities.xml"], []),
    AreaConfig("supplier", "SimpleScreens/screen/SimpleScreens/Supplier", ["mantle-usl/service/mantle/party"], ["mantle-udm/entity/PartyEntities.xml"], ["mantle-usl/entity/PartyViewEntities.xml"], []),
    AreaConfig("vendor", "SimpleScreens/screen/SimpleScreens/Vendor", ["mantle-usl/service/mantle/party"], ["mantle-udm/entity/PartyEntities.xml"], ["mantle-usl/entity/PartyViewEntities.xml"], []),
    AreaConfig("catalog-product", "SimpleScreens/screen/SimpleScreens/Catalog/Product", ["mantle-usl/service/mantle/product"], ["mantle-udm/entity/ProductDefinitionEntities.xml", "mantle-udm/entity/ProductAssetEntities.xml", "mantle-udm/entity/ProductStoreEntities.xml"], ["mantle-usl/entity/ProductDefinitionViewEntities.xml", "mantle-usl/entity/ProductAssetViewEntities.xml", "mantle-usl/entity/ProductStoreViewEntities.xml"], ["mantle-usl/entity/ProductAsset.eecas.xml"]),
    AreaConfig("productstore", "SimpleScreens/screen/SimpleScreens/ProductStore", ["mantle-usl/service/mantle/product"], ["mantle-udm/entity/ProductStoreEntities.xml", "mantle-udm/entity/ProductDefinitionEntities.xml"], ["mantle-usl/entity/ProductStoreViewEntities.xml", "mantle-usl/entity/ProductDefinitionViewEntities.xml"], []),
    AreaConfig("shipment", "SimpleScreens/screen/SimpleScreens/Shipment", ["mantle-usl/service/mantle/shipment"], ["mantle-udm/entity/ShipmentEntities.xml"], ["mantle-usl/entity/ShipmentViewEntities.xml"], []),
    AreaConfig("shipping", "SimpleScreens/screen/SimpleScreens/Shipping", ["mantle-usl/service/mantle/shipment"], ["mantle-udm/entity/ShipmentEntities.xml"], ["mantle-usl/entity/ShipmentViewEntities.xml"], []),
    AreaConfig("accounting", "SimpleScreens/screen/SimpleScreens/Accounting", ["mantle-usl/service/mantle/account", "mantle-usl/service/mantle/ledger", "mantle-usl/service/mantle/other"], ["mantle-udm/entity/AccountingAccountEntities.xml", "mantle-udm/entity/AccountingLedgerEntities.xml", "mantle-udm/entity/AccountingOtherEntities.xml"], ["mantle-usl/entity/AccountingAccountViewEntities.xml", "mantle-usl/entity/AccountingLedgerViewEntities.xml", "mantle-usl/entity/AccountingOtherViewEntities.xml"], ["mantle-usl/entity/Accounting.eecas.xml"]),
    AreaConfig("request", "SimpleScreens/screen/SimpleScreens/Request", ["mantle-usl/service/mantle/request"], ["mantle-udm/entity/RequestEntities.xml"], ["mantle-usl/entity/RequestViewEntities.xml"], []),
    AreaConfig("facility", "SimpleScreens/screen/SimpleScreens/Facility", ["mantle-usl/service/mantle/facility"], ["mantle-udm/entity/FacilityEntities.xml"], ["mantle-usl/entity/FacilityViewEntities.xml"], []),
    AreaConfig("asset", "SimpleScreens/screen/SimpleScreens/Asset", ["mantle-usl/service/mantle/product"], ["mantle-udm/entity/ProductAssetEntities.xml"], ["mantle-usl/entity/ProductAssetViewEntities.xml"], ["mantle-usl/entity/ProductAsset.eecas.xml"]),
    AreaConfig("task", "SimpleScreens/screen/SimpleScreens/Task", ["mantle-usl/service/mantle/work"], ["mantle-udm/entity/WorkEffortEntities.xml"], ["mantle-usl/entity/WorkEffortViewEntities.xml"], ["mantle-usl/entity/Work.eecas.xml"]),
    AreaConfig("project", "SimpleScreens/screen/SimpleScreens/Project", ["mantle-usl/service/mantle/work"], ["mantle-udm/entity/WorkEffortEntities.xml"], ["mantle-usl/entity/WorkEffortViewEntities.xml"], ["mantle-usl/entity/Work.eecas.xml"]),
    AreaConfig("manufacturing", "SimpleScreens/screen/SimpleScreens/Manufacturing", ["mantle-usl/service/mantle/work"], ["mantle-udm/entity/WorkEffortEntities.xml", "mantle-udm/entity/ProductDefinitionEntities.xml"], ["mantle-usl/entity/WorkEffortViewEntities.xml", "mantle-usl/entity/ProductDefinitionViewEntities.xml"], ["mantle-usl/entity/Work.eecas.xml"]),
    AreaConfig("humanres", "SimpleScreens/screen/SimpleScreens/HumanRes", ["mantle-usl/service/mantle/humanres"], ["mantle-udm/entity/HumanResourcesEntities.xml", "mantle-udm/entity/WorkEffortEntities.xml"], ["mantle-usl/entity/HumanResourcesViewEntities.xml", "mantle-usl/entity/WorkEffortViewEntities.xml"], ["mantle-usl/entity/Work.eecas.xml"]),
    AreaConfig("gateway", "SimpleScreens/screen/SimpleScreens/Gateway", ["mantle-usl/service/mantle/account", "mantle-usl/service/mantle/shipment"], ["mantle-udm/entity/AccountingAccountEntities.xml", "mantle-udm/entity/ShipmentEntities.xml"], ["mantle-usl/entity/AccountingAccountViewEntities.xml", "mantle-usl/entity/ShipmentViewEntities.xml"], ["mantle-usl/entity/Accounting.eecas.xml"]),
    AreaConfig("return", "SimpleScreens/screen/SimpleScreens/Return", ["mantle-usl/service/mantle/order"], ["mantle-udm/entity/OrderEntities.xml"], ["mantle-usl/entity/OrderViewEntities.xml"], ["mantle-usl/entity/Order.eecas.xml"]),
]


def run(cmd: list[str], cwd: Path) -> None:
    subprocess.run(cmd, cwd=str(cwd), check=True)


def existing_paths(root: Path, rels: list[str]) -> list[Path]:
    out = []
    for r in rels:
        p = root / r
        if p.exists():
            out.append(p)
    return out


def collect_service_files(root: Path, rel_dirs: list[str]) -> list[Path]:
    files: list[Path] = []
    for rd in rel_dirs:
        d = root / rd
        if not d.exists():
            continue
        files.extend(sorted(d.rglob("*.xml")))
    # remove test files
    files = [f for f in files if not f.name.endswith("TestServices.xml")]
    seen = set()
    uniq = []
    for f in files:
        if str(f) in seen:
            continue
        seen.add(str(f))
        uniq.append(f)
    return uniq


def prepare_service_workspace(tmp_base: Path, files: list[Path]) -> Path:
    if tmp_base.exists():
        shutil.rmtree(tmp_base)
    tmp_base.mkdir(parents=True, exist_ok=True)
    for src in files:
        parts = src.parts
        if "service" in parts:
            i = parts.index("service")
            rel = Path(*parts[i:])
        else:
            rel = Path(src.name)
        dst = tmp_base / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            continue
        try:
            dst.symlink_to(src)
        except Exception:
            shutil.copy2(src, dst)
    return tmp_base / "service"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def append_jsonl(source_path: Path, target_path: Path) -> None:
    if not source_path.exists():
        return
    with source_path.open("r", encoding="utf-8") as source_handle, target_path.open("a", encoding="utf-8") as target_handle:
        for line in source_handle:
            if line.strip():
                target_handle.write(line)


def main() -> None:
    ap = argparse.ArgumentParser(description="Run full SimpleScreens extension with per-area outputs")
    ap.add_argument("--moqui-root", required=True)
    ap.add_argument("--output-dir", default="output/full-v4_9_1")
    ap.add_argument("--only-areas", default="")
    args = ap.parse_args()

    repo_root = Path(__file__).resolve().parent
    moqui_root = Path(args.moqui_root)
    out_root = repo_root / args.output_dir
    out_root.mkdir(parents=True, exist_ok=True)
    tmp_root = out_root / "_tmp_services"
    localization_catalog_path = out_root / "global-localization-catalog.json"
    global_screen_docs_path = out_root / "global-screen-prompt-documents.jsonl"
    global_service_action_out = out_root / "service-actions-global"
    global_service_action_out.mkdir(parents=True, exist_ok=True)

    for stale_path in [
        global_screen_docs_path,
        global_service_action_out / "global-service-action-statements.jsonl",
        global_service_action_out / "global-service-action-documents.jsonl",
    ]:
        if stale_path.exists():
            stale_path.unlink()

    loc_cmd = [
        "python3",
        str(repo_root / "moqui_localization_catalog.py"),
        "--scan-root",
        str(moqui_root),
        "--scan-root",
        str(repo_root.parents[2] / "moqui-framework"),
        "--output",
        str(localization_catalog_path),
    ]
    run(loc_cmd, repo_root)

    only = {x.strip().lower() for x in args.only_areas.split(",") if x.strip()}

    global_service_action_cmd = [
        "python3",
        str(repo_root / "generate_service_action_catalog.py"),
        "--moqui-root",
        str(moqui_root),
        "--output-dir",
        str(global_service_action_out),
    ]
    run(global_service_action_cmd, repo_root)

    summary = []

    for cfg in AREA_CONFIGS:
        if only and cfg.name.lower() not in only:
            continue
        screens = moqui_root / cfg.screens_dir
        if not screens.exists():
            continue

        area_out = out_root / cfg.name
        area_out.mkdir(parents=True, exist_ok=True)

        service_files = collect_service_files(moqui_root, cfg.service_dirs)
        service_ws = prepare_service_workspace(tmp_root / cfg.name, service_files)

        ents = existing_paths(moqui_root, cfg.entity_files)
        views = existing_paths(moqui_root, cfg.view_files)
        eecas = existing_paths(moqui_root, cfg.eeca_files)
        service_action_out = area_out / "service-actions"
        service_action_out.mkdir(parents=True, exist_ok=True)

        if not ents:
            # fallback to party entities to keep parser alive
            fallback = moqui_root / "mantle-udm/entity/PartyEntities.xml"
            if fallback.exists():
                ents = [fallback]
        if not views:
            fallback_v = moqui_root / "mantle-usl/entity/PartyViewEntities.xml"
            if fallback_v.exists():
                views = [fallback_v]

        svc_action_cmd = [
            "python3",
            str(repo_root / "generate_service_action_catalog.py"),
            "--moqui-root",
            str(moqui_root),
            "--component-paths",
            str(service_ws.parent),
            "--output-dir",
            str(service_action_out),
        ]
        run(svc_action_cmd, repo_root)

        gen_cmd = [
            "python3",
            str(repo_root / "generate_screen_prompt_catalog.py"),
            "--screens-dir",
            str(screens),
            "--services-dir",
            str(service_ws),
            "--entities",
            ",".join(str(p) for p in ents),
            "--views",
            ",".join(str(p) for p in views),
            "--eeca",
            ",".join(str(p) for p in eecas),
            "--service-action-statements",
            str(service_action_out / "global-service-action-statements.jsonl"),
            "--service-action-documents",
            str(service_action_out / "global-service-action-documents.jsonl"),
            "--localization-catalog",
            str(localization_catalog_path),
            "--output-dir",
            str(area_out),
        ]
        run(gen_cmd, repo_root)
        append_jsonl(area_out / "screen-prompt-documents.jsonl", global_screen_docs_path)

        eval_cmd = [
            "python3",
            str(repo_root / "evaluate_screen_prompt_retrieval.py"),
            "--docs",
            str(area_out / "screen-prompt-documents.jsonl"),
            "--queries",
            str(area_out / "screen-prompt-eval-queries.jsonl"),
            "--mode",
            "weighted",
            "--out-json",
            str(area_out / "retrieval-weighted.json"),
            "--out-md",
            str(area_out / "retrieval-weighted.md"),
        ]
        run(eval_cmd, repo_root)

        m = load_json(area_out / "screen-prompt-catalog-metrics.json")
        r = load_json(area_out / "retrieval-weighted.json")

        fc = r.get("failureClassCounts", {})
        group_r3 = r.get("groupRecallAt3", 0.0)
        sq = r.get("screenQueryPromptMetrics", {}).get("screen_query_prompt", {})
        sq_group_r3 = sq.get("groupRecallAt3", 0.0)
        read_group_r3 = r.get("byOperationEffect", {}).get("read_query", {}).get("groupRecallAt3", 0.0)
        gate_threshold_by_area = {
            "accounting": 0.88,
            "asset": 0.88,
            "productstore": 0.88,
        }
        area_thr = gate_threshold_by_area.get(cfg.name, 0.90)
        summary.append(
            {
                "area": cfg.name,
                "screenFiles": m.get("screen_files_processed", 0),
                "promptDocuments": m.get("prompt_documents_generated", 0),
                "evalQueries": r.get("queries", 0),
                "weightedRecallAt1": r.get("recallAt1", 0.0),
                "weightedRecallAt3": r.get("recallAt3", 0.0),
                "weightedRecallAt5": r.get("recallAt5", 0.0),
                "weightedGroupRecallAt1": r.get("groupRecallAt1", 0.0),
                "weightedGroupRecallAt3": group_r3,
                "weightedGroupRecallAt5": r.get("groupRecallAt5", 0.0),
                "screenQueryRecallAt3": r.get("screenQueryPromptMetrics", {}).get("screen_query_prompt", {}).get("recallAt3", 0.0),
                "screenQueryGroupRecallAt3": sq_group_r3,
                "readQueryRecallAt3": r.get("byOperationEffect", {}).get("read_query", {}).get("recallAt3", 0.0),
                "readQueryGroupRecallAt3": read_group_r3,
                "unknownCount": m.get("operation_effect_counts", {}).get("unknown", 0),
                "unresolvedBindingCount": m.get("operation_effect_counts", {}).get("unresolved_binding", 0),
                "supportServices": m.get("support_service_documents", 0),
                "ambiguousByDesign": fc.get("ambiguous_by_design", 0),
                "trueMiss": fc.get("true_miss", 0),
                "evaluationTargetError": fc.get("evaluation_target_error", 0),
                "gatePass": (
                    group_r3 >= area_thr
                    and sq_group_r3 >= 0.80
                    and read_group_r3 >= 0.80
                    and m.get("operation_effect_counts", {}).get("unknown", 0) == 0
                ),
            }
        )

    summary_sorted = sorted(summary, key=lambda x: x["area"])
    below = [s for s in summary_sorted if not s["gatePass"]]

    (out_root / "global-area-summary.json").write_text(json.dumps({"areas": summary_sorted, "areasBelowGate": below}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = []
    lines.append("# Global Area Summary")
    lines.append("")
    lines.append("## Areas")
    for s in summary_sorted:
        lines.append(
            f"- `{s['area']}`: prompts={s['promptDocuments']}, queries={s['evalQueries']}, "
            f"r@3={s['weightedRecallAt3']}, gr@3={s['weightedGroupRecallAt3']}, "
            f"sq_r@3={s['screenQueryRecallAt3']}, sq_gr@3={s['screenQueryGroupRecallAt3']}, "
            f"read_r@3={s['readQueryRecallAt3']}, read_gr@3={s['readQueryGroupRecallAt3']}, "
            f"unknown={s['unknownCount']}, gatePass={s['gatePass']}"
        )
    lines.append("")
    lines.append("## Areas Below Gate")
    if not below:
        lines.append("- none")
    else:
        for s in below:
            lines.append(
                f"- `{s['area']}`: r@3={s['weightedRecallAt3']}, gr@3={s['weightedGroupRecallAt3']}, "
                f"sq_r@3={s['screenQueryRecallAt3']}, sq_gr@3={s['screenQueryGroupRecallAt3']}, "
                f"read_r@3={s['readQueryRecallAt3']}, read_gr@3={s['readQueryGroupRecallAt3']}, "
                f"unknown={s['unknownCount']}"
            )
    lines.append("")
    lines.append("## Support Services Summary")
    for s in summary_sorted:
        lines.append(f"- `{s['area']}`: supportServices={s['supportServices']}, unresolvedBinding={s['unresolvedBindingCount']}")
    lines.append("")
    lines.append("## Failure Class Totals")
    tot_true = sum(s["trueMiss"] for s in summary_sorted)
    tot_amb = sum(s["ambiguousByDesign"] for s in summary_sorted)
    tot_eval = sum(s["evaluationTargetError"] for s in summary_sorted)
    lines.append(f"- `true_miss`: `{tot_true}`")
    lines.append(f"- `ambiguous_by_design`: `{tot_amb}`")
    lines.append(f"- `evaluation_target_error`: `{tot_eval}`")

    (out_root / "global-area-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    knowledge_docs_path = out_root / "global-agent-knowledge-documents.jsonl"
    if global_screen_docs_path.exists():
        graph_cmd = [
            "python3",
            str(repo_root / "generate_artifact_graph.py"),
            "--screen-docs",
            str(global_screen_docs_path),
            "--service-action-statements",
            str(global_service_action_out / "global-service-action-statements.jsonl"),
            "--service-action-documents",
            str(global_service_action_out / "global-service-action-documents.jsonl"),
            "--output-dir",
            str(out_root / "graph"),
        ]
        if knowledge_docs_path.exists():
            graph_cmd.extend(["--knowledge-docs", str(knowledge_docs_path)])
        run(graph_cmd, repo_root)

    print(f"Wrote {out_root / 'global-area-summary.json'}")
    print(f"Wrote {out_root / 'global-area-summary.md'}")


if __name__ == "__main__":
    main()
