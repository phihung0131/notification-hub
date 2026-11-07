// Shared helpers: env config, bootstrap through public APIs, send helper.
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';

export const env = (k, d) => (__ENV[k] !== undefined && __ENV[k] !== '' ? __ENV[k] : d);
export const num = (k, d) => Number(env(k, d));

export const cfg = {
  scenario: env('SCENARIO', 'smoke'),
  runId: env('RUN_ID', 'local'),
  tenantUrl: env('TENANT_URL', 'http://tenant-service:8080/api/v1/tenants'),
  notiUrl: env('NOTI_URL', 'http://notification-service:8080/api/v1/notifications'),
  gwUrl: env('GW_URL', 'http://gateway-service:8080'),
  keyTtlDays: num('KEY_TTL_DAYS', 30),
  password: env('TENANT_PASSWORD', 'password'),
};

// Custom metrics. nh_errors excludes 429 (throttling is an expected outcome, tracked separately).
export const errors = new Rate('nh_errors');
export const throttled = new Rate('nh_throttled');
export const uniqueKeys = new Counter('nh_unique_keys');
// per-tenant accepted counters, created lazily by alias
const accepted = {};
export function acceptedCounter(alias) {
  if (!accepted[alias]) accepted[alias] = new Counter('nh_accepted_' + alias);
  return accepted[alias];
}
export const ALIASES = ['main', 'noisy', 'victim'];
// k6 requires metrics created in init context: pre-create.
ALIASES.forEach(acceptedCounter);

const JSON_H = { 'Content-Type': 'application/json' };

// Depth-first search for a field in an arbitrarily wrapped JSON response (ApiResponse envelope or not).
export function deepFind(o, name) {
  if (o === null || typeof o !== 'object') return undefined;
  if (o[name] !== undefined && typeof o[name] !== 'object') return o[name];
  for (const k of Object.keys(o)) {
    const r = deepFind(o[k], name);
    if (r !== undefined) return r;
  }
  return undefined;
}

function must(res, what) {
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`bootstrap ${what} failed: HTTP ${res.status} ${String(res.body).slice(0, 300)}`);
  }
  return res.json();
}

export function bootstrapTenant(alias) {
  const ts = `${cfg.runId}-${alias}-${Date.now()}`.replace(/[^A-Za-z0-9-]/g, '');
  const email = `lt-${ts}@example.com`;
  const reg = must(
    http.post(`${cfg.tenantUrl}/auth/register`, JSON.stringify({ name: `LT ${ts}`, email, password: cfg.password }), { headers: JSON_H }),
    'register');
  let tenantId = deepFind(reg, 'id');
  const login = must(
    http.post(`${cfg.tenantUrl}/auth/login`, JSON.stringify({ email, password: cfg.password }), { headers: JSON_H }),
    'login');
  const token = deepFind(login, 'token');
  const auth = { headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_H) };
  const perm = must(
    http.post(`${cfg.tenantUrl}/permissions`, JSON.stringify({ name: `send-${ts}`, type: 'API', description: 'loadtest' }), auth),
    'permission');
  const permId = deepFind(perm, 'id');
  const expiredAt = new Date(Date.now() + cfg.keyTtlDays * 86400000).toISOString().replace(/\.\d+Z$/, 'Z');
  const key = must(
    http.post(`${cfg.tenantUrl}/api-keys`, JSON.stringify({ expiredAt, permissionIds: [permId] }), auth),
    'api-key');
  const apiKey = deepFind(key, 'key');
  const channel = `LT_${ts}`.replace(/-/g, '_').toUpperCase();
  must(http.post(`${cfg.notiUrl}/channels`, JSON.stringify({ name: `LT${ts}`.slice(0, 60), code: channel }), auth), 'channel');
  if (!tenantId) {
    // fall back to the internal validate endpoint which maps key -> tenantId
    const v = http.get(`${cfg.tenantUrl}/auth/internal/apikeys/validate`, { headers: { 'X-Api-Key': apiKey } });
    if (v.status === 200) tenantId = deepFind(v.json(), 'tenantId');
  }
  return { alias, tenantId, email, apiKey, channel };
}

// recipients are globally unique per (run, tenant alias, vu, iter, idx) so the verifier can reconcile.
export function recipient(alias, tag) {
  return `lt-${cfg.runId}-${alias}-${tag}@load.test`.toLowerCase();
}

export function sendBody(t, rcpt) {
  return JSON.stringify({ channel: t.channel, recipient: rcpt, subject: `lt ${cfg.runId}`, content: 'load test message' });
}

export function sendParams(t, extraHeaders, tags) {
  return {
    headers: Object.assign({ 'Content-Type': 'application/json', 'X-Api-Key': t.apiKey }, extraHeaders || {}),
    tags: Object.assign({ tenant: t.alias }, tags || {}),
    responseType: 'none',
  };
}

// Record outcome of a /send response. Returns true when accepted (2xx).
export function record(res, t) {
  const ok = res.status === 200 || res.status === 201 || res.status === 202;
  const thr = res.status === 429;
  errors.add(!ok && !thr, { tenant: t.alias });
  throttled.add(thr, { tenant: t.alias });
  if (ok) acceptedCounter(t.alias).add(1);
  return ok;
}

export function send(t, rcpt, extraHeaders) {
  const res = http.post(`${cfg.gwUrl}/api/v1/notifications/send`, sendBody(t, rcpt), sendParams(t, extraHeaders));
  record(res, t);
  return res;
}
