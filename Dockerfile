# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# d-migrate — Dockerfile for building and testing the project
#
# Usage:
#   Build image (runs `./gradlew build`, which includes tests):
#     docker build -t d-migrate:dev .
#
#   Build image, skipping tests (faster, assembly only):
#     docker build -t d-migrate:dev --build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist" .
#
#   Run only a build-stage subset (for example Phase-A tests) without producing
#   the final runtime image:
#     docker build --target build \
#       --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
#       -t d-migrate:phase-a .
#
#   Run the CLI from the final stage:
#     docker run --rm -v "$(pwd):/work" d-migrate:dev schema validate --source /work/schema.yaml
#
#   Extract build artifacts (distribution tar) from the `build` stage:
#     docker build --target build -t d-migrate:build .
#     docker create --name d-migrate-tmp d-migrate:build
#     docker cp d-migrate-tmp:/src/adapters/driving/cli/build/distributions ./dist
#     docker rm d-migrate-tmp
#
#   Build and serve the aggregated Kover HTML coverage report:
#     docker build --target coverage -t d-migrate:coverage .
#     docker run --rm -p 8080:8080 d-migrate:coverage
#     # open http://localhost:8080
#
#   Build and print the aggregated Kover JSON report:
#     docker build --target coverage-json -t d-migrate:coverage-json .
#     docker run --rm d-migrate:coverage-json > coverage.json
#
#   Verify the configured Kover threshold (fails the Docker build if the
#   minimum is not met):
#     docker build --target coverage-verify -t d-migrate:coverage-verify .
# ---------------------------------------------------------------------------

# ---- Stage 1: build & test -------------------------------------------------
FROM gradle:8.12-jdk21 AS build

# Gradle tasks executed during the image build. Override with --build-arg to
# skip tests or run a different subset (e.g. "assemble :adapters:driving:cli:installDist"
# or "check").
# Important: a full multi-stage `docker build` reaches the runtime stage below,
# so `GRADLE_TASKS` must produce `:adapters:driving:cli:installDist`. If you want to
# run only a subset that does not build the CLI distribution, use
# `docker build --target build ...` instead.
ARG GRADLE_TASKS="build :adapters:driving:cli:installDist"

WORKDIR /src

COPY --chown=gradle:gradle . .

RUN gradle --no-daemon ${GRADLE_TASKS}

# ---- Stage 2: integration-test (JDK + Python + Django + Node.js) -----------
# Used by scripts/test-integration-docker.sh for the full runtime matrix.
FROM gradle:8.12-jdk21 AS integration-test

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    curl ca-certificates \
    build-essential && \
    python3 -m pip install --break-system-packages --quiet django && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g pnpm node-gyp && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY --chown=gradle:gradle . .

# ---- Stage 3: coverage-build -----------------------------------------------
# Runs only the non-integration test suite plus the aggregated Kover HTML
# and XML reports so they can be published independently of the configured
# coverage threshold.
FROM gradle:8.12-jdk21 AS coverage-build

ARG COVERAGE_TASKS="test koverHtmlReport koverXmlReport"

WORKDIR /src

COPY --chown=gradle:gradle . .

RUN gradle --no-daemon ${COVERAGE_TASKS}

ARG YQ_VERSION=v4.44.6
ADD https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64 /usr/local/bin/yq
RUN chmod +x /usr/local/bin/yq && \
    test -f /src/build/reports/kover/report.xml && \
    yq -p xml -o json /src/build/reports/kover/report.xml > /src/build/reports/kover/report.json

# ---- Stage 4: coverage-verify ----------------------------------------------
# Optional hard gate for CI-style coverage enforcement.
FROM coverage-build AS coverage-verify

ARG COVERAGE_VERIFY_TASKS="koverVerify"

RUN gradle --no-daemon ${COVERAGE_VERIFY_TASKS}

# ---- Stage 5: coverage -----------------------------------------------------
# Serves the aggregated Kover HTML report via a simple static web server.
FROM python:3.13-slim AS coverage

WORKDIR /srv/coverage

COPY --from=coverage-build /src/build/reports/kover/html/ /srv/coverage/

EXPOSE 8080

ENTRYPOINT ["python3", "-m", "http.server", "8080", "--directory", "/srv/coverage"]

# ---- Stage 6: coverage-json ------------------------------------------------
# Prints the aggregated Kover JSON report to stdout so callers can redirect it
# into a local file.
FROM busybox:1.36 AS coverage-json

WORKDIR /srv/coverage-json

COPY --from=coverage-build /src/build/reports/kover/report.json /srv/coverage-json/report.json

ENTRYPOINT ["cat", "/srv/coverage-json/report.json"]

# ---- Stage 7: runtime ------------------------------------------------------
# Uses the same JRE base as the Jib image produced by :adapters:driving:cli:jibDockerBuild
FROM eclipse-temurin:21-jre-noble AS runtime


LABEL org.opencontainers.image.title="d-migrate" \
    org.opencontainers.image.description="Database-agnostic CLI tool for schema migration and data management" \
    org.opencontainers.image.source="https://github.com/pt9912/d-migrate" \
    org.opencontainers.image.licenses="MIT"

WORKDIR /opt/d-migrate

# Install the distribution produced by the `application` plugin.
COPY --from=build /src/adapters/driving/cli/build/install/d-migrate/ /opt/d-migrate/

ENV PATH="/opt/d-migrate/bin:${PATH}" \
    JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"

WORKDIR /work
VOLUME ["/work"]

ENTRYPOINT ["d-migrate"]
CMD ["--help"]
