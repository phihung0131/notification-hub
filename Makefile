.PHONY: up down clean obs ps logs smoke health
up:      ## build + start core stack
	docker compose up -d --build
obs:     ## core + Prometheus/Grafana/Jaeger
	OTEL_AGENT_OPTS="-javaagent:/opt/otel-agent.jar" docker compose --profile observability up -d --build
down:    ## stop, keep data
	docker compose --profile observability --profile tools down
clean:   ## stop and DELETE all volumes
	docker compose --profile observability --profile tools down -v
ps:
	docker compose ps
logs:
	docker compose logs -f --tail=100 $(s)
smoke:
	./scripts/smoke.sh
fmt:     ## Spotless apply inside a container (no host JDK/Maven)
	docker run --rm -v "$(CURDIR)":/src -v nh-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 \
	  mvn -B -q spotless:apply -Ptenant,notification,delivery,analytics,gateway
health:
	./scripts/check-health.sh

# ---- Phase 7: load test / verify / chaos / report (see docs/BENCHMARKING.md) ----
LT_DC = docker compose -f compose.yaml -f compose.loadtest.yaml
.PHONY: bench verify chaos report lt-obs lt-chaos-up
bench:   ## k6 scenario in a container + reconciliation: make bench SCENARIO=load RATE=100 DURATION=2m
	SCENARIO=$(or $(SCENARIO),load) loadtest/bench.sh
verify:  ## re-run reconciliation on a finished run: make verify [RUN_ID=<id>] (default: last run)
	python3 verifier/verify.py --run-id $(or $(RUN_ID),$(shell cat reports/.last 2>/dev/null)) --reports reports
chaos:   ## fault injection run: make chaos FAULT=kafka[,redis-core,...] [FAULT_MODE=pause]
	chaos/run.sh
report:  ## assemble docs/reports/<date>-<run>.md: make report [RUN_ID=<id>]
	python3 loadtest/report.py $(RUN_ID)
lt-obs:  ## core observability with provisioned dashboards + k6 remote write receiver
	OTEL_AGENT_OPTS="-javaagent:/opt/otel-agent.jar" $(LT_DC) --profile observability up -d prometheus grafana
lt-chaos-up: ## start Toxiproxy
	$(LT_DC) --profile chaos up -d toxiproxy
