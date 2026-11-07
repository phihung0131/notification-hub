"""Endpoint adapters. ALL knowledge of other services' APIs/DB schemas lives here.

If a service API changes, only this file (or the env overrides) must change.
Every adapter raises Unavailable when it cannot answer; verify.py then marks the check SKIPPED, never PASS.
Stdlib only (runs in python:3.12-slim with no pip install).
"""
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


class Unavailable(Exception):
    pass


def env(k, d=""):
    v = os.environ.get(k)
    return v if v not in (None, "") else d


def http_json(method, url, headers=None, timeout=30):
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            return json.loads(body) if body else None
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise Unavailable(f"{method} {url}: {e}")


def deep_find_list(o):
    """Find the first list of dicts in a possibly ApiResponse-wrapped payload."""
    if isinstance(o, list):
        return o
    if isinstance(o, dict):
        for v in o.values():
            r = deep_find_list(v)
            if r is not None:
                return r
    return None


# ---------------- analytics (HTTP, tenant scoped via X-Tenant-Id) ----------------
# GET {ANALYTICS_URL}/messages  -> list of {messageId, recipient, status, ...}
# Override the path with ANALYTICS_LIST_PATH if it changes (e.g. to add paging).
def analytics_messages(tenant_id):
    base = env("ANALYTICS_URL", "http://localhost:9004/api/v1/analytics")
    path = env("ANALYTICS_LIST_PATH", "/messages")
    data = http_json("GET", base + path, {"X-Tenant-Id": tenant_id}, timeout=120)
    lst = deep_find_list(data)
    if lst is None:
        raise Unavailable("analytics response had no list")
    return lst


# ---------------- Mailpit ----------------
def mailpit_recipients(prefix=None):
    """Return list of recipient addresses of every message (paged)."""
    base = env("MAILPIT_URL", "http://localhost:8025")
    out, start, limit = [], 0, 500
    while True:
        d = http_json("GET", f"{base}/api/v1/messages?start={start}&limit={limit}", timeout=60)
        msgs = d.get("messages") or []
        for m in msgs:
            for t in m.get("To") or []:
                a = (t.get("Address") or "").lower()
                if prefix is None or a.startswith(prefix):
                    out.append(a)
        start += len(msgs)
        if not msgs or start >= d.get("total", 0):
            return out


def mailpit_delete_all():
    base = env("MAILPIT_URL", "http://localhost:8025")
    http_json("DELETE", f"{base}/api/v1/messages")


# ---------------- SQL (host-side only, via `docker compose exec postgres psql`) ----------------
# Needed for notification-service rows and tenant quota because no read API exists for them.
# Queries are overridable via env because other agents may rename tables/columns.
SQL_NOTIFICATIONS = "select status, count(*) from notifications where tenant_id='{tenant}' group by status"
SQL_DUP_RECIPIENTS = "select count(*) from (select recipient from notifications where tenant_id='{tenant}' group by recipient having count(*)>1) x"
SQL_QUOTA = "select quota_used from tenants where id='{tenant}'"


def psql(db, user, sql):
    cmd = env("PSQL_CMD", "docker compose exec -T postgres psql -U {user} -d {db} -tA -F | -c")
    argv = cmd.format(user=user, db=db).split() + [sql]
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Unavailable(f"psql: {e}")
    if r.returncode != 0:
        raise Unavailable(f"psql failed: {r.stderr.strip()[:200]}")
    return [ln.split("|") for ln in r.stdout.strip().splitlines() if ln]


def notification_status_counts(tenant_id):
    q = env("SQL_NOTIFICATIONS", SQL_NOTIFICATIONS).format(tenant=tenant_id)
    return {row[0]: int(row[1]) for row in psql("notification_db", "notification", q)}


def notification_duplicate_recipients(tenant_id):
    q = env("SQL_DUP_RECIPIENTS", SQL_DUP_RECIPIENTS).format(tenant=tenant_id)
    return int(psql("notification_db", "notification", q)[0][0])


def quota_used(tenant_id):
    q = env("SQL_QUOTA", SQL_QUOTA).format(tenant=tenant_id)
    rows = psql("tenant_db", "tenant", q)
    if not rows:
        raise Unavailable("tenant row not found")
    return int(rows[0][0])
