# syntax=docker/dockerfile:1.7

# --- Build stage: compile the fat jar with Maven + JDK 21 ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependency resolution separately from source changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# --- Runtime stage: JRE only, non-root user ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1000 --create-home --home-dir /home/gateway gateway

WORKDIR /app
COPY --from=build /build/target/review-gateway-1.0.0-SNAPSHOT.jar app.jar
RUN chown gateway:gateway app.jar
USER gateway

# --- Required secrets (no default -- GatewayProperties.validateOnStartup() fails fast without
# these, each must be >=32 chars): DB_USER, DB_PASSWORD, CI_TOKEN, WORKER_TOKEN, ADMIN_TOKEN,
# GITLAB_TOKEN. Set these at `docker run -e ...` / compose / k8s Secret time -- deliberately not
# given placeholder values here so a misconfigured deployment fails loudly instead of silently
# running with a fake secret.
#
# --- Everything below has a working default from application.yml; override only if needed. ---
ENV DB_URL="jdbc:postgresql://localhost:5432/review_gateway" \
    GITLAB_BASE_URL="https://gitlab.example.com/api/v4" \
    BACKEND_ALLOWED_HOST_PATTERN=".*" \
    SERVER_PORT="8080"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
