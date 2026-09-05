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
#   Build the image that gets PUBLISHED (identical to `make docker-oci-build`):
#     docker build --target runtime -t dmigrate/d-migrate:latest .
#
#   Build and extract CI artifacts without host Gradle:
#     docker build --target docker-coverage-modules-html -t d-migrate:coverage-modules-html .
#     docker run --rm d-migrate:coverage-modules-html | tar xf -
#     docker build --target release-assets -t d-migrate:release-assets .
#     docker run --rm d-migrate:release-assets | tar xf -
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
#   Verify the configured Kover threshold from a fresh test run (fails the
#   Docker build if the minimum is not met):
#     docker build --target coverage-verify -t d-migrate:coverage-verify .
# ---------------------------------------------------------------------------

# ---- Stage: dependency warmup ---------------------------------------------
# Copies only Gradle metadata first so dependency resolution can be cached
# independently from source code changes.
FROM gradle:8.14-jdk21 AS deps

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
COPY --chown=gradle:gradle hexagon/ports-execute/build.gradle.kts hexagon/ports-execute/build.gradle.kts
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
COPY --chown=gradle:gradle adapters/driven/driver-mssql-profiling/build.gradle.kts adapters/driven/driver-mssql-profiling/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-mssql/build.gradle.kts adapters/driven/driver-mssql/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/driver-oracle/build.gradle.kts adapters/driven/driver-oracle/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/formats/build.gradle.kts adapters/driven/formats/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/formats-parquet/build.gradle.kts adapters/driven/formats-parquet/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/audit-logging/build.gradle.kts adapters/driven/audit-logging/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/connection-config/build.gradle.kts adapters/driven/connection-config/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/integrations/build.gradle.kts adapters/driven/integrations/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/persistence-jdbc/build.gradle.kts adapters/driven/persistence-jdbc/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/storage-file/build.gradle.kts adapters/driven/storage-file/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/storage-s3/build.gradle.kts adapters/driven/storage-s3/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/streaming/build.gradle.kts adapters/driven/streaming/build.gradle.kts
COPY --chown=gradle:gradle adapters/driven/text-icu/build.gradle.kts adapters/driven/text-icu/build.gradle.kts
COPY --chown=gradle:gradle adapters/driving/cli/build.gradle.kts adapters/driving/cli/build.gradle.kts
COPY --chown=gradle:gradle adapters/driving/mcp/build.gradle.kts adapters/driving/mcp/build.gradle.kts
COPY --chown=gradle:gradle test/integration-postgresql/build.gradle.kts test/integration-postgresql/build.gradle.kts
COPY --chown=gradle:gradle test/integration-mysql/build.gradle.kts test/integration-mysql/build.gradle.kts
COPY --chown=gradle:gradle test/integration-sqlite/build.gradle.kts test/integration-sqlite/build.gradle.kts
COPY --chown=gradle:gradle test/integration-mssql/build.gradle.kts test/integration-mssql/build.gradle.kts
COPY --chown=gradle:gradle test/integration-oracle/build.gradle.kts test/integration-oracle/build.gradle.kts
COPY --chown=gradle:gradle test/integration-server-state/build.gradle.kts test/integration-server-state/build.gradle.kts
COPY --chown=gradle:gradle test/integration-integrations/build.gradle.kts test/integration-integrations/build.gradle.kts
COPY --chown=gradle:gradle test/integration-persistence-jdbc/build.gradle.kts test/integration-persistence-jdbc/build.gradle.kts
COPY --chown=gradle:gradle test/integration-storage-s3/build.gradle.kts test/integration-storage-s3/build.gradle.kts
COPY --chown=gradle:gradle test/e2e-cli/build.gradle.kts test/e2e-cli/build.gradle.kts
COPY --chown=gradle:gradle test/consumer-read-probe/build.gradle.kts test/consumer-read-probe/build.gradle.kts
COPY --chown=gradle:gradle test/cross-dialect-matrix/build.gradle.kts test/cross-dialect-matrix/build.gradle.kts
COPY --chown=gradle:gradle test/integration-concurrency/build.gradle.kts test/integration-concurrency/build.gradle.kts
COPY --chown=gradle:gradle test/perf-large-schema/build.gradle.kts test/perf-large-schema/build.gradle.kts

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

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (cats a build artifact to stdout), never a published runtime image
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

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (cats a build artifact to stdout), never a published runtime image
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

# ---- Stage 1c: coverage modules HTML --------------------------------------
# Produces per-module Kover HTML reports and streams them as a tar archive so
# GitHub Actions can upload them from the checked-out workspace.
FROM compile AS docker-coverage-modules-html

ARG COVERAGE_MODULES_HTML_TASKS="\
:hexagon:core:koverHtmlReport \
:hexagon:ports:koverHtmlReport \
:hexagon:ports-common:koverHtmlReport \
:hexagon:ports-read:koverHtmlReport \
:hexagon:ports-write:koverHtmlReport \
:hexagon:ports-execute:koverHtmlReport \
:hexagon:application:koverHtmlReport \
:hexagon:profiling:koverHtmlReport \
:adapters:driven:driver-common:koverHtmlReport \
:adapters:driven:driver-postgresql:koverHtmlReport \
:adapters:driven:driver-postgresql-profiling:koverHtmlReport \
:adapters:driven:driver-mysql:koverHtmlReport \
:adapters:driven:driver-mysql-profiling:koverHtmlReport \
:adapters:driven:driver-sqlite:koverHtmlReport \
:adapters:driven:driver-sqlite-profiling:koverHtmlReport \
:adapters:driven:driver-mssql-profiling:koverHtmlReport \
:adapters:driven:driver-mssql:koverHtmlReport \
:adapters:driven:driver-oracle:koverHtmlReport \
:adapters:driven:formats:koverHtmlReport \
:adapters:driven:integrations:koverHtmlReport \
:adapters:driven:streaming:koverHtmlReport \
:adapters:driving:cli:koverHtmlReport \
"

RUN gradle --no-daemon ${COVERAGE_MODULES_HTML_TASKS}
RUN find /src -path "*/build/reports/kover/html/*" -type f \
      -printf '%P\n' | tar cf /src/coverage-modules-html.tar -C /src -T -

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (cats a build artifact to stdout), never a published runtime image
ENTRYPOINT ["cat", "/src/coverage-modules-html.tar"]

# ---- Stage 1d: release assets ---------------------------------------------
# Builds release assets and streams them as a tar archive for CI upload.
FROM compile AS release-assets

ARG RELEASE_VERSION=""
RUN if [ -n "${RELEASE_VERSION}" ]; then \
      gradle --no-daemon -PreleaseVersion="${RELEASE_VERSION}" :adapters:driving:cli:assembleReleaseAssets; \
    else \
      gradle --no-daemon :adapters:driving:cli:assembleReleaseAssets; \
    fi
RUN tar cf /src/release-assets.tar -C /src adapters/driving/cli/build/release

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (cats a build artifact to stdout), never a published runtime image
ENTRYPOINT ["cat", "/src/release-assets.tar"]

# ---- Stage 2: integration-test (JDK + Python + Django + Node.js) -----------
# Used by scripts/test-integration-docker.sh for the full runtime matrix.
FROM gradle:8.14-jdk21 AS integration-test

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    curl ca-certificates gnupg \
    build-essential \
    # SpatiaLite-Extension fuer die SQLite-Integrationstests. Sie laeuft ohne
    # Testcontainers gegen eine Datei, braucht die Bibliothek aber im Image:
    # `load_extension('mod_spatialite')` sucht sie im Standard-Library-Pfad.
    libsqlite3-mod-spatialite && \
    python3 -m pip install --break-system-packages --quiet django && \
    # Node 20 aus dem NodeSource-Repo (CWE-494): kein `curl | bash`. Der GPG-Key
    # wird ueber HTTPS geholt, per SHA256 gepinnt und als signed-by-Keyring
    # hinterlegt; danach installiert apt `nodejs` signaturverifiziert.
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o /tmp/nodesource.key && \
    echo "b42e0321dabdc24e892115da705cf061167eac12a317f23d329862d0aa0a271d  /tmp/nodesource.key" | sha256sum -c - && \
    gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg /tmp/nodesource.key && \
    rm /tmp/nodesource.key && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    # pnpm gepinnt wie der Node-Zweig darueber. Ungepinnt zog `npm install -g`
    # die jeweils neueste Version, und die kippte den Build gleich zweifach:
    # sie liest die Einstellung onlyBuiltDependencies nicht mehr aus der
    # package.json, und ab Hauptversion 11 verlangt sie Node >= 22.13.
    # Die beiden Pins gehoeren deshalb zusammen -- wer die Node-Zeile hebt,
    # darf pnpm mitheben, aber nicht umgekehrt.
    npm install -g pnpm@10.34.5 node-gyp && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY --chown=gradle:gradle . .

# ---- Stage 3: coverage-build -----------------------------------------------
# Runs only the non-integration test suite plus the aggregated Kover HTML
# and XML reports so they can be published independently of the configured
# coverage threshold.
# ---- Stage: integration-test-native ----------------------------------------
# Wie integration-test, aber mit dem GraalVM-Native-Binary im Image. Die
# Subprozess-E2Es (RealCliSubprocess) fahren mit DMIGRATE_CLI_BIN gegen dieses
# Binary statt einer Kind-JVM — dieselben Tests, anderes Artefakt.
#
# Das Binary kommt per COPY --from aus dem separaten Image `d-migrate:native-build`
# (docker/native-image.Dockerfile, gebaut ueber `make native-build`). KEIN
# Host-Extract nach ./build: das Binary bleibt im Docker-Fluss, exakt wie der
# Rest der Pipeline. `COPY --from=<benanntes Image>` braucht die zwei Dockerfiles
# nicht zusammenzufuehren — es referenziert das lokal vorhandene Image direkt.
FROM integration-test AS integration-test-native
COPY --from=d-migrate:native-build \
     /src/adapters/driving/cli/build/native/nativeCompile/d-migrate /native/d-migrate
ENV DMIGRATE_CLI_BIN=/native/d-migrate

FROM gradle:8.14-jdk21 AS coverage-build

ARG COVERAGE_TASKS="test koverHtmlReport koverXmlReport"

WORKDIR /src

COPY --chown=gradle:gradle . .

RUN gradle --no-daemon ${COVERAGE_TASKS}

# yq/jq-Version + zugehoeriger SHA256 bewegen sich im Gleichschritt: ein
# Versions-Bump ohne Hash-Update laesst `sha256sum -c` unten fehlschlagen (CWE-494).
ARG YQ_VERSION=v4.44.6
ARG JQ_VERSION=jq-1.8.1
ARG YQ_SHA256=0c2b24e645b57d8e7c0566d18643a6d4f5580feeea3878127354a46f2a1e4598
ARG JQ_SHA256=020468de7539ce70ef1bceaf7cde2e8c4f2ca6c3afb84642aabc5c97d9fc2a0d
ADD https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64 /usr/local/bin/yq
ADD https://github.com/jqlang/jq/releases/download/${JQ_VERSION}/jq-linux-amd64 /usr/local/bin/jq
# SHA256 pruefen, BEVOR die Binaries ausfuehrbar gemacht und ausgefuehrt werden.
RUN echo "${YQ_SHA256}  /usr/local/bin/yq" | sha256sum -c - && \
    echo "${JQ_SHA256}  /usr/local/bin/jq" | sha256sum -c - && \
    chmod +x /usr/local/bin/yq /usr/local/bin/jq && \
    test -f /src/build/reports/kover/report.xml && \
    yq -p xml -o json /src/build/reports/kover/report.xml | \
    jq -f /src/scripts/kover-report-to-json.jq > /src/build/reports/kover/report.json

# ---- Stage 4: coverage-verify ----------------------------------------------
# Optional hard gate for CI-style coverage enforcement. Keep this on the same
# fresh test-run path as `make ci-build`; running verify after a prior report
# generation stage can leave Kover consuming a different artifact set.
#
# Der Task-Satz spiegelt `CI_BUILD_TASKS` (Makefile) — beide muessen denselben
# Graphen fahren, sonst prueft der lokale Gate etwas anderes als CI.
FROM compile AS coverage-verify

ARG COVERAGE_VERIFY_TASKS="build koverVerify --no-build-cache"

RUN gradle --no-daemon ${COVERAGE_VERIFY_TASKS}

# ---- Stage 5: coverage -----------------------------------------------------
# Serves the aggregated Kover HTML report via a simple static web server.
FROM python:3.13-slim AS coverage

WORKDIR /srv/coverage

COPY --from=coverage-build /src/build/reports/kover/html/ /srv/coverage/

EXPOSE 8080

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (serves coverage HTML locally in CI), never a published runtime image
ENTRYPOINT ["python3", "-m", "http.server", "8080", "--directory", "/srv/coverage"]

# ---- Stage 6: coverage-json ------------------------------------------------
# Prints the aggregated Kover JSON report to stdout so callers can redirect it
# into a local file.
FROM busybox:1.36 AS coverage-json

WORKDIR /srv/coverage-json

COPY --from=coverage-build /src/build/reports/kover/report.json /srv/coverage-json/report.json

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (cats a coverage report to stdout), never a published runtime image
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
:hexagon:ports:koverXmlReport \
:hexagon:ports-common:koverXmlReport \
:hexagon:ports-read:koverXmlReport \
:hexagon:ports-write:koverXmlReport \
:hexagon:ports-execute:koverXmlReport \
:adapters:driven:driver-common:koverXmlReport \
:adapters:driven:driver-postgresql:koverXmlReport \
:adapters:driven:driver-postgresql-profiling:koverXmlReport \
:adapters:driven:driver-mysql:koverXmlReport \
:adapters:driven:driver-mysql-profiling:koverXmlReport \
:adapters:driven:driver-sqlite:koverXmlReport \
:adapters:driven:driver-sqlite-profiling:koverXmlReport \
:adapters:driven:driver-mssql-profiling:koverXmlReport \
:adapters:driven:driver-mssql:koverXmlReport \
:adapters:driven:driver-oracle:koverXmlReport \
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

# defusedxml: hardened XML parse used by the summary script (semgrep
# use-defused-xml-parse).
RUN pip install --no-cache-dir defusedxml

# nosemgrep: config.semgrep.missing-user -- ephemeral CI helper stage (prints a coverage summary), never a published runtime image
ENTRYPOINT ["python3", "/usr/local/bin/kover-modules-summary.py", "/reports"]

# ---- Stage 7: runtime ------------------------------------------------------
# Das publizierte JVM-Image (ADR 0041): Ziel von `make docker-oci-build`.
FROM eclipse-temurin:21-jre-noble AS runtime


LABEL org.opencontainers.image.title="d-migrate" \
    org.opencontainers.image.description="Database-agnostic CLI tool for schema migration and data management" \
    org.opencontainers.image.source="https://github.com/pt9912/d-migrate" \
    org.opencontainers.image.licenses="MIT"

WORKDIR /opt/d-migrate

# VA4 (Spatial): SpatiaLite-Extension für das SQLite-`spatialite`-Profil. Wird zur
# Laufzeit NUR geladen, wenn eine Connection sie per `?spatialite=true` anfordert
# (`SELECT load_extension('mod_spatialite')`); ohne das Flag bleibt SQLite unberührt.
# Das Ubuntu-Noble-Paket stellt `/usr/lib/<triplet>/mod_spatialite.so` bereit, das
# `load_extension('mod_spatialite')` über den Standard-Library-Pfad findet.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libsqlite3-mod-spatialite \
    && rm -rf /var/lib/apt/lists/*

# Install the distribution produced by the `application` plugin.
COPY --from=build /src/adapters/driving/cli/build/install/d-migrate/ /opt/d-migrate/

ENV PATH="/opt/d-migrate/bin:${PATH}" \
    JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"

# Run the published image as a non-root user (semgrep
# dockerfile.security.missing-user[-entrypoint]). The data volume /work is owned
# by this uid so the CLI can write reverse/transfer output. Operators who
# bind-mount a host directory may need `--user $(id -u):$(id -g)` so writes land
# with host ownership (documented in README "Docker / Volumes").
RUN useradd --no-create-home --uid 10001 dmigrate \
    && mkdir -p /work && chown dmigrate:dmigrate /work

WORKDIR /work
VOLUME ["/work"]

USER dmigrate
ENTRYPOINT ["d-migrate"]
CMD ["--help"]

# ─────────────────────────────────────────────────────────────────────────────
# ast-grep — syntax-bewusster (Tree-sitter) struktureller Such-/Rewrite-Helfer.
# Hermetisch im Projekt-Muster (vgl. semgrep/d-check): eigene Stage, offline via
# `make ast-grep` ausgeführt. Schließt die Methodik-Lücke aus
# `memory: feedback_syntax_aware_refactor` — Regex sieht Strings/Kommentare/
# Formatvarianten nicht, ein AST schon. `ast-grep` unterstützt Kotlin built-in
# (`-l kotlin`). Entrypoint = `ast-grep`; das Repo wird unter /repo gemountet.
#
# Version gepinnt (Hermetik-Vertrag, vgl. SEMGREP_IMAGE-Digest). TODO: node-Image
# zusätzlich per Digest pinnen, sobald ein Build den Digest bestätigt.
FROM node:26-bookworm-slim AS ast-grep
RUN npm install -g @ast-grep/cli@0.44.0 \
    && npm cache clean --force
WORKDIR /repo
ENTRYPOINT ["ast-grep"]
