#!/usr/bin/env python3
"""Reconciliation verifier. Usage: verify.py [--run-id ID] [--reports DIR] [--no-wait]

Reads <reports>/<run-id>/manifest.json (written by k6 handleSummary), waits for the pipeline to drain,
then reconciles per tenant:

  expected  = manifest.expectedUnique (idempotency-storm) else manifest.accepted (2xx responses)
  notifications : rows in notification_db == expected, no duplicate recipients     (SQL via docker compose exec)
  analytics     : rows (recipient prefix of this run) == expected, no duplicate messageId,
                  no non-terminal status after drain
  mailpit       : mails == analytics-successful (or expected when analytics unavailable),
                  duplicates (same recipient twice) == 0, no successful-but-missing mail
  quota         : tenant quota_used == notifications accepted by provider (SENT)                             (SQL)

Checks are selected with CHECKS=notifications,analytics,mailpit,quota (default all). A check whose backend is
unreachable is SKIPPED (STRICT=1 turns SKIPPED into FAIL). Exit code 1 on any FAIL. Writes verify.json + verify.md.
Stdlib only. See docs/BENCHMARKING.md for assumptions.
"""
import argparse
import json
import os
import sys
import time
from collections import Counter

import adapters as A

SUCCESS = set(A.env("SUCCESS_STATUSES", "SENT,DELIVERED,ACKNOWLEDGED,SUCCESS,COMPLETED").split(","))
FAILED = set(A.env("FAILED_STATUSES", "FAILED,DLQ,REJECTED").split(","))
TERMINAL = SUCCESS | FAILED


def result(name, status, detail, **kw):
    return {"check": name, "status": status, "detail": detail, **kw}


def snapshot(tenants):
    """Cheap counters used to detect 'drained' (stable for N seconds)."""
    snap = {}
    for alias, t in tenants.items():
        try:
            rows = A.analytics_messages(t["tenantId"])
            mine = [r for r in rows if (r.get("recipient") or "").lower().startswith(t["recipientPrefix"])]
            snap[alias] = (len(mine), sum(1 for r in mine if r.get("status") in TERMINAL))
        except A.Unavailable:
            snap[alias] = None
    try:
        snap["mail"] = len(A.mailpit_recipients("lt-"))
    except A.Unavailable:
        snap["mail"] = None
    return snap


def expected_of(t):
    return t["expectedUnique"] if t.get("expectedUnique") is not None else t["accepted"]


def drain(tenants, timeout, stable_for=15):
    deadline, last, since = time.time() + timeout, None, time.time()
    while time.time() < deadline:
        s = snapshot(tenants)
        done = all(
            s.get(a) is not None and s[a][0] >= expected_of(t) and s[a][0] == s[a][1] for a, t in tenants.items()
        )
        if s == last:
            if done and time.time() - since >= 3:
                return True, s
            if time.time() - since >= stable_for and not done and all(v is None for v in s.values()):
                return False, s  # nothing answers, stop waiting
        else:
            last, since = s, time.time()
        time.sleep(2)
    return False, last


def check_tenant(alias, t, checks):
    exp = expected_of(t)
    tid, prefix = t["tenantId"], t["recipientPrefix"]
    out = []
    rows, mail = None, None

    if "analytics" in checks:
        try:
            all_rows = A.analytics_messages(tid)
            rows = [r for r in all_rows if (r.get("recipient") or "").lower().startswith(prefix)]
            ids = Counter(r.get("messageId") for r in rows)
            dup_ids = sum(1 for c in ids.values() if c > 1)
            stuck = [r for r in rows if r.get("status") not in TERMINAL]
            by_status = dict(Counter(r.get("status") for r in rows))
            problems = []
            if len(rows) < exp:
                problems.append(f"lost {exp - len(rows)}")
            if len(rows) > exp:
                problems.append(f"surplus {len(rows) - exp}")
            if dup_ids:
                problems.append(f"{dup_ids} duplicate messageIds")
            if stuck:
                problems.append(f"{len(stuck)} not terminal after drain")
            out.append(result("analytics", "FAIL" if problems else "PASS",
                              f"expected={exp} rows={len(rows)} statuses={by_status}" + (" :: " + "; ".join(problems) if problems else ""),
                              expected=exp, observed=len(rows), statuses=by_status))
        except A.Unavailable as e:
            out.append(result("analytics", "SKIPPED", str(e)))

    if "notifications" in checks:
        try:
            counts = A.notification_status_counts(tid)
            total = sum(counts.values())
            dups = A.notification_duplicate_recipients(tid)
            problems = []
            if total < exp:
                problems.append(f"lost {exp - total}")
            if total > exp:
                problems.append(f"surplus {total - exp} (duplicate processing?)")
            if dups:
                problems.append(f"{dups} recipients with >1 notification")
            out.append(result("notifications", "FAIL" if problems else "PASS",
                              f"expected={exp} rows={total} statuses={counts}" + (" :: " + "; ".join(problems) if problems else ""),
                              expected=exp, observed=total, statuses=counts))
        except A.Unavailable as e:
            out.append(result("notifications", "SKIPPED", str(e)))

    if "mailpit" in checks:
        try:
            got = A.mailpit_recipients(prefix)
            c = Counter(got)
            dups = sum(v - 1 for v in c.values() if v > 1)
            problems = []
            if dups:
                problems.append(f"{dups} duplicate mails (same recipient delivered more than once)")
            if rows is not None:
                ok_rcpt = {(r.get("recipient") or "").lower() for r in rows if r.get("status") in SUCCESS}
                missing = ok_rcpt - set(c)
                regressed = [r for r in rows if r.get("status") in FAILED and (r.get("recipient") or "").lower() in c]
                if missing:
                    problems.append(f"{len(missing)} analytics-successful but no mail")
                if regressed:
                    problems.append(f"{len(regressed)} mail delivered but analytics says failed (regressed status)")
                want = f"successful={len(ok_rcpt)}"
            else:
                if len(c) < exp:
                    problems.append(f"lost {exp - len(c)} (assuming all succeed; analytics unavailable)")
                want = f"expected={exp}"
            out.append(result("mailpit", "FAIL" if problems else "PASS",
                              f"{want} distinct_mail={len(c)} total_mail={len(got)}" + (" :: " + "; ".join(problems) if problems else ""),
                              observed=len(got), distinct=len(c), duplicates=dups))
        except A.Unavailable as e:
            out.append(result("mailpit", "SKIPPED", str(e)))

    if "quota" in checks:
        try:
            used = A.quota_used(tid)
            # Charged on provider acceptance (notification SENT); a later bounce is not refunded (ADR 0004).
            try:
                sent = A.notification_status_counts(tid).get("SENT", 0)
            except Exception:
                sent = sum(1 for r in rows if r.get("status") in SUCCESS) if rows is not None else exp
            problems = []
            if used != sent:
                problems.append(f"quota_used={used} but successful sends={sent}")
            out.append(result("quota", "FAIL" if problems else "PASS",
                              f"quota_used={used} successful={sent}" + (" :: " + "; ".join(problems) if problems else ""),
                              observed=used, expected=sent))
        except A.Unavailable as e:
            out.append(result("quota", "SKIPPED", str(e)))
    return out


def to_markdown(rep):
    L = [f"# Reconciliation: {rep['runId']} ({rep['scenario']})", "",
         f"Verdict: **{rep['verdict']}**  (drained={rep['drained']})", "",
         "| tenant | check | status | detail |", "|---|---|---|---|"]
    for a, checks in rep["tenants"].items():
        for c in checks:
            L.append(f"| {a} | {c['check']} | {c['status']} | {c['detail'].replace('|', '/')} |")
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", default=A.env("RUN_ID", "local"))
    ap.add_argument("--reports", default=A.env("REPORTS_DIR", "reports"))
    ap.add_argument("--no-wait", action="store_true")
    ap.add_argument("--clear-mailpit", action="store_true", help="delete all Mailpit messages and exit")
    a = ap.parse_args()
    if a.clear_mailpit:
        A.mailpit_delete_all()
        print("mailpit cleared")
        return 0
    d = os.path.join(a.reports, a.run_id)
    manifest = json.load(open(os.path.join(d, "manifest.json")))
    tenants = manifest["tenants"]
    checks = set(A.env("CHECKS", "notifications,analytics,quota").split(","))
    drained, _ = (True, None) if a.no_wait else drain(tenants, float(A.env("DRAIN_TIMEOUT", "120")))
    if not drained:
        print("WARNING: pipeline did not fully drain before timeout; results may show loss", file=sys.stderr)
    rep = {"runId": manifest["runId"], "scenario": manifest["scenario"], "drained": drained,
           "tenants": {al: check_tenant(al, t, checks) for al, t in tenants.items()}}
    flat = [c for cs in rep["tenants"].values() for c in cs]
    strict = A.env("STRICT") == "1"
    failed = any(c["status"] == "FAIL" or (strict and c["status"] == "SKIPPED") for c in flat)
    ran = any(c["status"] != "SKIPPED" for c in flat)
    rep["verdict"] = "FAIL" if failed else ("PASS" if ran else "INCONCLUSIVE")
    json.dump(rep, open(os.path.join(d, "verify.json"), "w"), indent=2)
    open(os.path.join(d, "verify.md"), "w").write(to_markdown(rep))
    print(to_markdown(rep))
    return 1 if failed or not ran else 0


if __name__ == "__main__":
    sys.exit(main())
