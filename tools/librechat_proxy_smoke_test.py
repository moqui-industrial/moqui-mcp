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
import sys
import urllib.error
import urllib.request
from pathlib import Path


def fetch(url: str) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"Accept": "text/html,application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.getcode(), resp.read().decode("utf-8", errors="ignore")


def check(results: list[dict], name: str, ok: bool, detail: str) -> None:
    results.append({"name": name, "ok": ok, "detail": detail})


def write_md(results: list[dict], path: Path) -> None:
    lines = ["# LibreChat Proxy Smoke", ""]
    for result in results:
        status = "PASS" if result["ok"] else "FAIL"
        lines.append(f"- `{status}` `{result['name']}`: {result['detail']}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Basic reverse-proxy smoke test for LibreChat behind Moqui")
    ap.add_argument("--base-url", required=True, help="Example: http://localhost:8080/librechat/")
    ap.add_argument("--out-json", required=True)
    ap.add_argument("--out-md", required=True)
    ap.add_argument("--allow-auth-challenge", action="store_true",
                    help="Treat HTTP 401 as an acceptable result when LibreChat is intentionally protected by auth.")
    args = ap.parse_args()

    results: list[dict] = []
    try:
        status, body = fetch(args.base_url)
        acceptable_codes = (200, 302, 401) if args.allow_auth_challenge else (200, 302)
        check(results, "base_url_http_status", status in acceptable_codes, f"status={status}")
        lowered = body.lower()
        looks_like_app = any(marker in lowered for marker in ("librechat", "<html", "__next", "<!doctype html"))
        check(results, "base_url_app_shell", looks_like_app, f"body_length={len(body)}")
        moqui_error = "error rendering screen" in lowered or "moqui" in lowered and "error" in lowered
        check(results, "base_url_not_moqui_error", not moqui_error, "response does not look like a Moqui error page")
    except urllib.error.HTTPError as exc:
        if args.allow_auth_challenge and exc.code == 401:
            check(results, "base_url_http_status", True, f"http_error={exc.code} (auth challenge accepted)")
        else:
            check(results, "base_url_http_status", False, f"http_error={exc.code}")
    except Exception as exc:
        check(results, "base_url_http_status", False, f"exception={exc!r}")

    out_json = Path(args.out_json)
    out_md = Path(args.out_md)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    payload = {"ok": all(item["ok"] for item in results), "results": results}
    out_json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_md(results, out_md)
    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")
    if not payload["ok"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
