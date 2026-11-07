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
