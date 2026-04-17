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
#   Extract Kover coverage reports (JSON) from the `build` stage:
#     docker build --target build \
#       --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist" \
#       -t d-migrate:build .
#     docker create --name d-migrate-tmp d-migrate:build
#     docker cp d-migrate-tmp:/src/hexagon/core/build/reports/kover/report.json ./coverage-core.json
#     docker rm d-migrate-tmp
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

# Convert Kover XML coverage reports to JSON (if they exist).
# Uses yq (https://github.com/mikefarah/yq) as a static binary.
ARG YQ_VERSION=v4.44.6
ADD https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64 /usr/local/bin/yq
RUN chmod +x /usr/local/bin/yq && \
    for xml in $(find . -path "*/kover/*.xml" -type f 2>/dev/null); do \
    json="${xml%.xml}.json"; \
    yq -p xml -o json "$xml" > "$json" 2>/dev/null || true; \
    done

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

# ---- Stage 3: runtime ------------------------------------------------------
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
