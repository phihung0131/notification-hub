'use strict';
// Tiny controllable mock for SMS / push / chat providers. Zero dependencies, ~30MB RSS.
//
//   POST /v1/<channel>/send     provider API (Idempotency-Key honoured, Bearer ignored)
//   GET  /admin/config          current knobs
//   PUT  /admin/config          merge knobs (JSON), takes effect immediately
//   POST /admin/reset           restore defaults + clear stats
//   GET  /admin/stats           accepted / duplicates / by-outcome counters
//   GET  /health
//
// Knobs: latencyMs, jitterMs, errorRate (0..1 -> 500), status5xxRate (0..1 -> 502/503),
//        rate429 (0..1), retryAfterSec, timeoutRate (0..1, never answers), badRequestRate (0..1 -> 400),
//        webhook: {url, secret, deliveredRate, bouncedRate, delayMs, duplicateRate, outOfOrderRate}
const http = require('http');
const crypto = require('crypto');

const DEFAULTS = {
  latencyMs: 20,
  jitterMs: 10,
  errorRate: 0,
  status5xxRate: 0,
  rate429: 0,
  retryAfterSec: 2,
  timeoutRate: 0,
  badRequestRate: 0,
  webhook: {
    url: process.env.WEBHOOK_URL || '',
    secret: process.env.WEBHOOK_SECRET || 'dev-webhook-secret',
    deliveredRate: 0.9,
    bouncedRate: 0.1,
    delayMs: 200,
    duplicateRate: 0,
    outOfOrderRate: 0,
  },
};

let cfg = JSON.parse(JSON.stringify(DEFAULTS));
let stats;
const seen = new Map(); // Idempotency-Key -> response body (bounded)
const MAX_SEEN = 100000;

function resetStats() {
  stats = { requests: 0, accepted: 0, duplicates: 0, errors500: 0, errors5xx: 0, limited429: 0,
    timeouts: 0, badRequests: 0, webhooksSent: 0, webhookFailures: 0 };
}
resetStats();

const rnd = (p) => p > 0 && Math.random() < p;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const json = (res, code, body, headers = {}) => {
  const s = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(s), ...headers });
  res.end(s);
};

function merge(target, patch) {
  for (const [k, v] of Object.entries(patch)) {
    if (v && typeof v === 'object' && !Array.isArray(v)) merge((target[k] ||= {}), v);
    else target[k] = v;
  }
}

async function postWebhook(event) {
  const w = cfg.webhook;
  if (!w.url) return;
  const body = JSON.stringify(event);
  const sig = crypto.createHmac('sha256', w.secret).update(body).digest('hex');
  try {
    const r = await fetch(w.url, { method: 'POST', body,
      headers: { 'Content-Type': 'application/json', 'X-Signature': sig },
      signal: AbortSignal.timeout(3000) });
    stats.webhooksSent++;
    if (!r.ok) stats.webhookFailures++;
  } catch (e) {
    stats.webhookFailures++;
  }
}

// After accepting a message, call back like a real provider: sent, then delivered|bounced.
// duplicateRate resends an event with the same eventId; outOfOrderRate sends the terminal event first.
function scheduleCallbacks(notificationId) {
  const w = cfg.webhook;
  if (!w.url) return;
  const id = () => crypto.randomUUID();
  const sent = { eventId: id(), notificationId, type: 'sent', occurredAt: Date.now() };
  const terminalType = rnd(w.bouncedRate / Math.max(w.deliveredRate + w.bouncedRate, 1e-9)) ? 'bounced' : 'delivered';
  const terminal = { eventId: id(), notificationId, type: terminalType, occurredAt: Date.now() + 1 };
  const order = rnd(w.outOfOrderRate) ? [terminal, sent] : [sent, terminal];
  setTimeout(async () => {
    for (const ev of order) {
      await postWebhook(ev);
      if (rnd(w.duplicateRate)) await postWebhook(ev);
    }
  }, w.delayMs);
}

async function handleSend(req, res, channel, raw) {
  stats.requests++;
  let body = {};
  try { body = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'invalid json' }); }
  const key = req.headers['idempotency-key'] || body.notificationId;

  if (key && seen.has(key)) { // idempotent replay: same answer, no second "send"
    stats.duplicates++;
    return json(res, 200, { ...seen.get(key), duplicate: true });
  }
  if (rnd(cfg.timeoutRate)) { stats.timeouts++; return; } // hang: client must time out
  await sleep(cfg.latencyMs + Math.random() * cfg.jitterMs);
  if (rnd(cfg.rate429)) {
    stats.limited429++;
    return json(res, 429, { error: 'rate limited' }, { 'Retry-After': String(cfg.retryAfterSec) });
  }
  if (rnd(cfg.status5xxRate)) { stats.errors5xx++; return json(res, Math.random() < 0.5 ? 502 : 503, { error: 'unavailable' }); }
  if (rnd(cfg.errorRate)) { stats.errors500++; return json(res, 500, { error: 'internal' }); }
  if (rnd(cfg.badRequestRate)) { stats.badRequests++; return json(res, 400, { error: 'bad request' }); }

  const out = { id: 'mock-' + crypto.randomUUID(), channel, status: 'accepted' };
  if (key) {
    if (seen.size >= MAX_SEEN) seen.delete(seen.keys().next().value);
    seen.set(key, out);
  }
  stats.accepted++;
  json(res, 200, out);
  if (body.notificationId) scheduleCallbacks(body.notificationId);
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', async () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const url = new URL(req.url, 'http://x');
    try {
      let m;
      if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { status: 'UP' });
      if (url.pathname === '/admin/config') {
        if (req.method === 'PUT') merge(cfg, JSON.parse(raw || '{}'));
        return json(res, 200, cfg);
      }
      if (req.method === 'POST' && url.pathname === '/admin/reset') {
        cfg = JSON.parse(JSON.stringify(DEFAULTS)); resetStats(); seen.clear();
        return json(res, 200, cfg);
      }
      if (req.method === 'GET' && url.pathname === '/admin/stats') return json(res, 200, { ...stats, distinctKeys: seen.size });
      if (req.method === 'POST' && (m = url.pathname.match(/^\/v1\/([a-z]+)\/send$/))) {
        return await handleSend(req, res, m[1], raw);
      }
      json(res, 404, { error: 'not found' });
    } catch (e) {
      json(res, 500, { error: String(e) });
    }
  });
});

server.keepAliveTimeout = 30000;
server.listen(Number(process.env.PORT || 8090), () => console.log('mock-provider on', server.address().port));
