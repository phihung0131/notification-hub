// Notification Hub load tests. Select scenario with SCENARIO=<name>; all knobs are env vars (see docs/BENCHMARKING.md).
//   smoke | load | stress | spike | soak | noisy-neighbor | idempotency-storm | burst
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { cfg, env, num, bootstrapTenant, send, recipient, sendBody, sendParams, record, uniqueKeys, ALIASES } from './lib.js';

const S = cfg.scenario;
const P95 = num('P95_MS', 500);
const P99 = num('P99_MS', 1500);
const ERR = num('ERR_RATE', 0.01);
const RATE = num('RATE', 50); // requests/s
const DURATION = env('DURATION', '2m');
const WARMUP = env('WARMUP', '30s');
const PRE_VUS = num('PRE_VUS', 50);
const MAX_VUS = num('MAX_VUS', 400);

const arrival = (stages, extra) =>
  Object.assign({ executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, stages, exec: 'sendOne' }, extra || {});

function scenarios() {
  switch (S) {
    case 'smoke':
      return { smoke: { executor: 'constant-vus', vus: num('VUS', 1), duration: env('DURATION', '10s'), exec: 'sendOne' } };
    case 'load':
      return { load: arrival([{ target: RATE, duration: WARMUP }, { target: RATE, duration: DURATION }]) };
    case 'soak':
      return { soak: arrival([{ target: RATE, duration: WARMUP }, { target: RATE, duration: env('SOAK_DURATION', '1h') }]) };
    case 'stress': {
      const start = num('START_RATE', 20), step = num('STEP_RATE', 20), max = num('MAX_RATE', 600), dur = env('STEP_DURATION', '1m');
      const stages = [];
      for (let r = start; r <= max; r += step) stages.push({ target: r, duration: '10s' }, { target: r, duration: dur });
      return { stress: arrival(stages, { maxVUs: num('MAX_VUS', 800) }) };
    }
    case 'spike': {
      const spike = num('SPIKE_RATE', RATE * 10);
      return {
        spike: arrival([
          { target: RATE, duration: WARMUP }, { target: RATE, duration: env('BASE_DURATION', '1m') },
          { target: spike, duration: env('SPIKE_RAMP', '10s') }, { target: spike, duration: env('SPIKE_DURATION', '1m') },
          { target: RATE, duration: env('SPIKE_RAMP', '10s') }, { target: RATE, duration: env('RECOVER_DURATION', '2m') },
        ]),
      };
    }
    case 'noisy-neighbor':
      return {
        noisy: arrival([{ target: num('NOISY_RATE', 300), duration: WARMUP }, { target: num('NOISY_RATE', 300), duration: DURATION }], { exec: 'sendNoisy' }),
        victim: arrival([{ target: num('VICTIM_RATE', 10), duration: WARMUP }, { target: num('VICTIM_RATE', 10), duration: DURATION }], { exec: 'sendVictim', preAllocatedVUs: 10, maxVUs: 50 }),
      };
    case 'idempotency-storm':
      // Each iteration = one fresh Idempotency-Key fired DUP times concurrently.
      return {
        storm: {
          executor: 'constant-arrival-rate', rate: num('KEYS_PER_SEC', 10), timeUnit: '1s', duration: DURATION,
          preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, exec: 'storm',
        },
      };
    case 'burst':
      // Each iteration fires BATCH_SIZE distinct requests in parallel (http.batch) to emulate batch clients.
      return {
        burst: {
          executor: 'constant-arrival-rate', rate: num('BATCHES_PER_SEC', 2), timeUnit: '1s', duration: DURATION,
          preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, exec: 'burst',
        },
      };
    default:
      throw new Error('unknown SCENARIO ' + S);
  }
}

const thresholds = {
  'http_req_duration{tenant:main}': [`p(95)<${P95}`, `p(99)<${P99}`],
  'nh_errors{tenant:main}': [`rate<${ERR}`],
};
if (S === 'noisy-neighbor') {
  delete thresholds['http_req_duration{tenant:main}'];
  delete thresholds['nh_errors{tenant:main}'];
  thresholds['http_req_duration{tenant:victim}'] = [`p(95)<${num('VICTIM_P95_MS', P95)}`, `p(99)<${P99}`];
  thresholds['nh_errors{tenant:victim}'] = [`rate<${ERR}`];
  // noisy tenant is expected to be throttled; no threshold, only reported.
  thresholds['http_req_duration{tenant:noisy}'] = ['max>=0'];
  thresholds['nh_errors{tenant:noisy}'] = ['rate>=0'];
} else {
  thresholds['nh_throttled{tenant:main}'] = ['rate>=0'];
}
if (S === 'stress') {
  thresholds['nh_errors{tenant:main}'] = [{ threshold: `rate<${num('STRESS_ERR_RATE', 0.05)}`, abortOnFail: true, delayAbortEval: '20s' }];
  delete thresholds['http_req_duration{tenant:main}'];
  thresholds['http_req_duration{tenant:main}'] = ['p(95)>=0']; // informational; ceiling = last stage before abort
}

export const options = {
  scenarios: scenarios(),
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'nh-loadtest/1.0',
};

export function setup() {
  const aliases = S === 'noisy-neighbor' ? ['noisy', 'victim'] : ['main'];
  const tenants = {};
  aliases.forEach((a) => (tenants[a] = bootstrapTenant(a)));
  return { tenants, runId: cfg.runId, scenario: S, startedAt: new Date().toISOString() };
}

const uniq = () => `${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;

export function sendOne(d) {
  const t = d.tenants.main;
  const res = send(t, recipient('main', uniq()));
  check(res, { 'accepted': (r) => r.status === 200 || r.status === 202 || r.status === 201 });
}
export function sendNoisy(d) { send(d.tenants.noisy, recipient('noisy', uniq())); }
export function sendVictim(d) { send(d.tenants.victim, recipient('victim', uniq())); }

export function storm(d) {
  const t = d.tenants.main;
  const idx = exec.scenario.iterationInTest;
  const key = `storm-${cfg.runId}-${idx}`;
  const rcpt = recipient('main', 'k' + idx);
  const dup = num('DUP', 20);
  const reqs = [];
  for (let i = 0; i < dup; i++) {
    reqs.push(['POST', `${cfg.gwUrl}/api/v1/notifications/send`, sendBody(t, rcpt), sendParams(t, { 'Idempotency-Key': key })]);
  }
  http.batch(reqs).forEach((r) => record(r, t));
  uniqueKeys.add(1);
}

export function burst(d) {
  const t = d.tenants.main;
  const n = num('BATCH_SIZE', 50);
  const base = uniq();
  const reqs = [];
  for (let i = 0; i < n; i++) {
    reqs.push(['POST', `${cfg.gwUrl}/api/v1/notifications/send`, sendBody(t, recipient('main', `${base}-${i}`)), sendParams(t)]);
  }
  http.batch(reqs).forEach((r) => record(r, t));
}

export function handleSummary(data) {
  const dir = `/reports/${cfg.runId}`;
  const m = (n) => (data.metrics[n] && data.metrics[n].values) || {};
  const sd = data.setup_data || { tenants: {} };
  const tenants = {};
  Object.keys(sd.tenants || {}).forEach((a) => {
    const t = sd.tenants[a];
    tenants[a] = {
      tenantId: t.tenantId, apiKey: t.apiKey, channel: t.channel,
      accepted: m('nh_accepted_' + a).count || 0,
      // idempotency storm: expected distinct notifications = distinct keys used
      expectedUnique: S === 'idempotency-storm' ? m('nh_unique_keys').count || 0 : null,
      recipientPrefix: `lt-${cfg.runId}-${a}-`.toLowerCase(),
    };
  });
  const manifest = {
    runId: cfg.runId, scenario: S, startedAt: sd.startedAt, finishedAt: new Date().toISOString(),
    http_reqs: m('http_reqs').count, tenants,
    thresholdsFailed: Object.keys(data.metrics).filter((k) => data.metrics[k].thresholds && Object.values(data.metrics[k].thresholds).some((v) => v.ok === false)),
  };
  void ALIASES;
  const out = {};
  out[`${dir}/summary.json`] = JSON.stringify(data, null, 2);
  out[`${dir}/manifest.json`] = JSON.stringify(manifest, null, 2);
  out['stdout'] = textSummary(data, manifest);
  return out;
}

function textSummary(data, manifest) {
  const d = (data.metrics.http_req_duration || { values: {} }).values;
  const f = (v) => (v === undefined ? 'n/a' : v.toFixed(1));
  const lines = [
    `\n== ${S} run=${cfg.runId} ==`,
    `http_reqs=${manifest.http_reqs} p50=${f(d.med)}ms p95=${f(d['p(95)'])}ms p99=${f(d['p(99)'])}ms max=${f(d.max)}ms`,
  ];
  Object.keys(manifest.tenants).forEach((a) => lines.push(`tenant ${a}: accepted=${manifest.tenants[a].accepted}`));
  lines.push(manifest.thresholdsFailed.length ? `THRESHOLDS FAILED: ${manifest.thresholdsFailed.join(', ')}` : 'thresholds OK');
  return lines.join('\n') + '\n';
}
