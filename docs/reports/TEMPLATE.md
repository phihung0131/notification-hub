# Benchmark report: {{SCENARIO}} / {{RUN_ID}}

Date: {{DATE}}
Host: {{HOST}}
Load generator: k6 in a container on the same host (co-located, see BENCHMARKING.md caveats)
Git commit / build: _fill in_
Stack config (partitions, JVM heap, pool sizes, replicas): _fill in_

## Goal and hypothesis

_What question does this run answer? What result did we expect and why?_

## Scenario parameters

_RATE, DURATION, WARMUP, MAX_VUS, tenants, payload. Copy the env used._

## k6 results

{{K6_SUMMARY}}

Tenants:
{{TENANTS}}

## Reconciliation

{{VERIFY}}

## Resource usage and bottleneck analysis

_Attach Grafana snapshots (overview + k6 dashboards) and `reports/<run>/docker-stats.csv` highlights._

| resource | observation |
|---|---|
| CPU (host, per container) | |
| Memory / GC | |
| Kafka lag | |
| Hikari / Redis | |
| First saturated component | |

## Chaos results (if applicable)

{{CHAOS}}

## Before / after

| run | change under test | max sustainable RPS | p95 | p99 | verifier |
|---|---|---|---|---|---|
| baseline | | | | | |
| this run | | | | | |

## Conclusions and next step

_Bottleneck found, trade-off chosen, follow-up change._
