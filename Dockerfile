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

# ---- Stage: dependency warmup ---------------------------------------------
# Copies only Gradle metadata first so dependency resolution can be cached
# independently from source code changes.
FROM gradle:8.12-jdk21 AS deps

WORKDIR /src

COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle gradle/ gradle/
# The following per-file COPY block is intentionally verbose so Docker can
# cache dependency resolution independently from source changes. If the build
# environment reliably supports `COPY --parents`, these entries can later be
# collapsed into one or a few grouped COPY instructions while preserving the
# directory structure.
COPY --chown=gradle:gradle hexagon/ports-common/build.gradle.kts hexagon/ports-common/build.gradle.kts
COPY --chown=gradle:gradle hexagon/ports-read/build.gradle.kts hexagon/ports-read/build.gradle.kts
COPY --chown=gradle:gradle hexagon/ports-write/build.gradle.kts hexagon/ports-write/build.gradle.kts
COPY --chown=gradle:gradle hexagon/ports/build.gradle.kts hexagon/ports/build.gradle.kts
COPY --chown=gradle:gradle hexagon/application/build.gradle.kts hexagon/application/build.gradle.kts
COPY --chown=gradle:gradle hexagon/core/build.gradle.kts hexagon/core/build.gradle.kts
COPY --chown=gradle:gradle hexagon/profiling/build.gradle.kts hexagon/profiling/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-common/build.gradle.kts adapters/driven/driver-common/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-postgresql/build.gradle.kts adapters/driven/driver-postgresql/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-postgresql-profiling/build.gradle.kts adapters/driven/driver-postgresql-profiling/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-mysql/build.gradle.kts adapters/driven/driver-mysql/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-mysql-profiling/build.gradle.kts adapters/driven/driver-mysql-profiling/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-sqlite/build.gradle.kts adapters/driven/driver-sqlite/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-sqlite-profiling/build.gradle.kts adapters/driven/driver-sqlite-profiling/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/formats/build.gradle.kts adapters/driven/formats/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/audit-logging/build.gradle.kts adapters/driven/audit-logging/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/connection-config/build.gradle.kts adapters/driven/connection-config/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/integrations/build.gradle.kts adapters/driven/integrations/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/persistence-jdbc/build.gradle.kts adapters/driven/persistence-jdbc/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/storage-file/build.gradle.kts adapters/driven/storage-file/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/streaming/build.gradle.kts adapters/driven/streaming/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/text-icu/build.gradle.kts adapters/driven/text-icu/build.gradle.kts
COPY --chown=gradle:gradle adapters/driving/cli/build.gradle.kts adapters/driving/cli/build.gradle.kts
COPY --chown=gradle:gradle adapters/driving/mcp/build.gradle.kts adapters/driving/mcp/build.gradle.kts
COPY --chown=gradle:gradle test/integration-postgresql/build.gradle.kts test/integration-postgresql/build.gradle.kts
COPY --chown=gradle:gradle test/integration-mysql/build.gradle.kts test/integration-mysql/build.gradle.kts
COPY --chown=gradle:gradle test/integration-sqlite/build.gradle.kts test/integration-sqlite/build.gradle.kts
COPY --chown=gradle:gradle test/integration-server-state/build.gradle.kts test/integration-server-state/build.gradle.kts
COPY --chown=gradle:gradle test/integration-integrations/build.gradle.kts test/integration-integrations/build.gradle.kts
COPY --chown=gradle:gradle test/integration-persistence-jdbc/build.gradle.kts test/integration-persistence-jdbc/build.gradle.kts
COPY --chown=gradle:gradle test/e2e-cli/build.gradle.kts test/e2e-cli/build.gradle.kts
COPY --chown=gradle:gradle test/consumer-read-probe/build.gradle.kts test/consumer-read-probe/build.gradle.kts

RUN gradle --no-daemon resolveAllDependencies

# ---- Stage: compile-only (production classes only, no tests) ---------------
# Fast feedback for compilation checks during development.
# Usage: docker build --target compile .
FROM deps AS compile

WORKDIR /src
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon classes

# ---- Stage: detekt-baseline ------------------------------------------------
# Helper stage for generating/exporting per-module detekt-baseline.xml files.
# This stage is intentionally non-failing so already generated baselines can
# still be extracted even when detektBaseline returns non-zero.
#
# Usage:
#   docker build --target detekt-baseline -t d-migrate:detekt-baseline .
#   docker run --rm d-migrate:detekt-baseline | tar xf -
FROM compile AS detekt-baseline

RUN gradle --no-daemon detektBaseline --continue || true
RUN find /src -name "detekt-baseline.xml" -not -path "/src/build/*" \
      -printf '%P\n' | tar cf /src/detekt-baselines.tar -C /src -T -

ENTRYPOINT ["cat", "/src/detekt-baselines.tar"]

# ---- Stage: golden-update --------------------------------------------------
# Helper stage for regenerating pinned JSON-Schema golden snapshots after a
# Phase-B/C/D/E/F/G tool-schema change. Runs the goldenness tests with
# UPDATE_GOLDEN=true (which makes the test write the regenerated file
# instead of comparing), then tars all `src/test/resources/golden/**` files
# so the host can extract them into the source tree without a volume mount.
#
# This stage MUST NOT fail on golden drift — `UPDATE_GOLDEN=true` makes the
# test return successfully after the rewrite, so no `|| true` is needed.
#
# Usage (or via `make golden-update`):
#   docker build --target golden-update -t d-migrate:golden-update .
#   docker run --rm d-migrate:golden-update | tar xf -
FROM compile AS golden-update

ENV UPDATE_GOLDEN=true

RUN gradle --no-daemon \
    :adapters:driving:mcp:test \
    --tests "*GoldenTest*" \
    --rerun-tasks

RUN find /src -path "*/src/test/resources/golden/*" -type f \
      -printf '%P\n' | tar cf /src/goldens.tar -C /src -T -

ENTRYPOINT ["cat", "/src/goldens.tar"]

# ---- Stage: detekt ---------------------------------------------------------
# Actual static-analysis gate. This stage MUST fail on detekt violations.
FROM compile AS detekt

RUN gradle --no-daemon detekt

# ---- Stage 1: build & test ------------------------------------------------
# Compiles test classes, runs tests, verifies coverage, and builds the CLI
# distribution — all in a single Gradle invocation so Kover instrumentation
# is always fresh (no stale testClasses from the compile stage).
FROM compile AS build

ARG GRADLE_TASKS="build :adapters:driving:cli:installDist"

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
ARG JQ_VERSION=jq-1.8.1
ADD https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64 /usr/local/bin/yq
ADD https://github.com/jqlang/jq/releases/download/${JQ_VERSION}/jq-linux-amd64 /usr/local/bin/jq
RUN chmod +x /usr/local/bin/yq /usr/local/bin/jq && \
    test -f /src/build/reports/kover/report.xml && \
    yq -p xml -o json /src/build/reports/kover/report.xml | \
    jq -f /src/scripts/kover-report-to-json.jq > /src/build/reports/kover/report.json

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

# ---- Stage 6b: coverage-modules --------------------------------------------
# Per-module Kover XML and JSON reports. Aggregate verification (`koverVerify`)
# can pass while individual modules still sit below 90% in isolation, because
# cross-module tests count toward the aggregate. This stage produces module-
# isolated reports so module-local coverage gaps surface as well.
#
#     docker build --target coverage-modules-summary -t d-migrate:coverage-modules-summary .
#     docker run --rm d-migrate:coverage-modules-summary --threshold 90 --top 10
#
# Or via:
#
#     make docker-coverage-modules
FROM coverage-build AS coverage-modules

# All subprojects with main sources, excluding pure integration-test runners
# (test:integration-* / test:consumer-read-probe). Override via
# `--build-arg COVERAGE_MODULES_TASKS=...` for a narrower run.
ARG COVERAGE_MODULES_TASKS="\
:hexagon:core:koverXmlReport \
:hexagon:application:koverXmlReport \
:hexagon:profiling:koverXmlReport \
:hexagon:ports-common:koverXmlReport \
:hexagon:ports-read:koverXmlReport \
:hexagon:ports-write:koverXmlReport \
:adapters:driven:driver-common:koverXmlReport \
:adapters:driven:driver-postgresql:koverXmlReport \
:adapters:driven:driver-postgresql-profiling:koverXmlReport \
:adapters:driven:driver-mysql:koverXmlReport \
:adapters:driven:driver-mysql-profiling:koverXmlReport \
:adapters:driven:driver-sqlite:koverXmlReport \
:adapters:driven:driver-sqlite-profiling:koverXmlReport \
:adapters:driven:audit-logging:koverXmlReport \
:adapters:driven:connection-config:koverXmlReport \
:adapters:driven:formats:koverXmlReport \
:adapters:driven:integrations:koverXmlReport \
:adapters:driven:persistence-jdbc:koverXmlReport \
:adapters:driven:storage-file:koverXmlReport \
:adapters:driven:streaming:koverXmlReport \
:adapters:driven:text-icu:koverXmlReport \
:adapters:driving:cli:koverXmlReport \
:adapters:driving:mcp:koverXmlReport \
"

# Tests are already up-to-date from the parent `coverage-build` stage; only the
# per-module koverXmlReport tasks need to run here.
RUN gradle --no-daemon ${COVERAGE_MODULES_TASKS} && \
    mkdir -p /reports && \
    find /src \
      \( -path '/src/build/reports' -prune \) -o \
      -path '*/build/reports/kover/report.xml' -print | \
    while IFS= read -r xml; do \
      mod=$(echo "$xml" | sed 's|^/src/||; s|/build/reports/kover/report.xml$||; s|/|_|g'); \
      cp "$xml" "/reports/${mod}.xml"; \
      yq -p xml -o json "$xml" | \
        jq -f /src/scripts/kover-report-to-json.jq > "/reports/${mod}.json"; \
    done

# ---- Stage 6c: coverage-modules-summary ------------------------------------
# Prints the per-module Kover summary from the reports generated by
# `coverage-modules`. Runtime args are forwarded to kover-modules-summary.py,
# e.g. `--threshold 90 --top 10`.
FROM python:3.13-slim AS coverage-modules-summary

WORKDIR /reports

COPY --from=coverage-modules /reports/ /reports/
COPY scripts/kover-modules-summary.py /usr/local/bin/kover-modules-summary.py

ENTRYPOINT ["python3", "/usr/local/bin/kover-modules-summary.py", "/reports"]

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
