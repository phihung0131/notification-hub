# syntax=docker/dockerfile:1.7
# Single multi-stage Dockerfile for every service.
#   docker build --build-arg SERVICE=tenant-service --build-arg PROFILE=tenant .
# No local JDK/Maven required. Maven repo is cached via BuildKit cache mount.

FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG SERVICE
ARG PROFILE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -P${PROFILE} -pl ${SERVICE} -am -DskipTests package \
    && cp "$(ls ${SERVICE}/target/*.jar | grep -v '\.original$' | head -n1)" /app.jar

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY agents/opentelemetry-javaagent.jar /opt/otel-agent.jar
USER app
EXPOSE 8080 9090
# JAVA_TOOL_OPTIONS is injected from compose (empty by default, enables OTel agent in observability runs)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
