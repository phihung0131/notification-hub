#!/usr/bin/env python3
"""Assemble docs/reports/<date>-<run>.md from docs/reports/TEMPLATE.md and reports/<run>/*.
Usage: loadtest/report.py [run-id]   (default: reports/.last)
Stdlib only. Placeholders in the template are {{NAME}}.
"""
import datetime
import json
import os
import platform
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rid = sys.argv[1] if len(sys.argv) > 1 else open(os.path.join(root, "reports", ".last")).read().strip()
d = os.path.join(root, "reports", rid)


def load(name):
    try:
        return json.load(open(os.path.join(d, name)))
    except (OSError, ValueError):
        return None


summary, manifest, verify = load("summary.json"), load("manifest.json"), load("verify.json")
rows = []
if summary:
    def g(m, k):
        return (summary["metrics"].get(m, {}).get("values", {}) or {}).get(k)

    def f(v, unit="ms"):
        return "n/a" if v is None else f"{v:.1f}{unit}"

    rows = [
        "| metric | value |", "|---|---|",
        f"| requests | {g('http_reqs', 'count')} ({f(g('http_reqs', 'rate'), '/s')}) |",
        f"| latency p50 / p95 / p99 / max | {f(g('http_req_duration', 'med'))} / {f(g('http_req_duration', 'p(95)'))} / {f(g('http_req_duration', 'p(99)'))} / {f(g('http_req_duration', 'max'))} |",
        f"| http_req_failed rate | {g('http_req_failed', 'rate')} |",
        f"| nh_errors (non-2xx, excl. 429) | {g('nh_errors', 'rate')} |",
        f"| nh_throttled (429) | {g('nh_throttled', 'rate')} |",
        f"| peak VUs | {g('vus_max', 'max')} |",
    ]
tenants = ""
if manifest:
    tenants = "\n".join(f"- `{a}`: accepted={t['accepted']}, expectedUnique={t['expectedUnique']}" for a, t in manifest["tenants"].items())
    failed = manifest.get("thresholdsFailed") or []
    rows.append(f"| thresholds | {'FAILED: ' + ', '.join(failed) if failed else 'all passed'} |")

ver = "_verifier not run_"
if verify:
    md = os.path.join(d, "verify.md")
    ver = open(md).read() if os.path.exists(md) else json.dumps(verify, indent=2)

vals = {
    "RUN_ID": rid,
    "SCENARIO": (manifest or {}).get("scenario", "?"),
    "DATE": datetime.date.today().isoformat(),
    "HOST": f"{platform.node()} ({os.cpu_count()} logical CPUs, {platform.platform()})",
    "K6_SUMMARY": "\n".join(rows) or "_no k6 summary found_",
    "TENANTS": tenants or "_n/a_",
    "VERIFY": ver,
    "CHAOS": open(os.path.join(root, "reports", "chaos-matrix.md")).read() if os.path.exists(os.path.join(root, "reports", "chaos-matrix.md")) else "_no chaos run_",
}
t = open(os.path.join(root, "docs", "reports", "TEMPLATE.md")).read()
for k, v in vals.items():
    t = t.replace("{{" + k + "}}", str(v))
out = os.path.join(root, "docs", "reports", f"{vals['DATE']}-{rid}.md")
open(out, "w").write(t)
print(out)
