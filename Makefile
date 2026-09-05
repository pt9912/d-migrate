# Fail-closed pipes: ohne pipefail ist der Exit einer Pipe der des LETZTEN
# Glieds — `docker run … | tar`/`| jq` (golden-update, release-assets,
# coverage-modules-html, coverage-gate) würde einen `docker run`-Fehler
# verschlucken und den Target fälschlich grün melden. bash + pipefail schließt
# das. Bewusst OHNE -e/-u, um die Semantik bestehender Mehrzeilen-Rezepte nicht
# zu ändern.
SHELL := bash
.SHELLFLAGS := -o pipefail -c

GRADLE ?= ./gradlew
DOCKER ?= docker

IMAGE ?= d-migrate
IMAGE_TAG ?= dev
# Name des publizierten JVM-Images. build.yml tagt genau diesen auf die Registry-Namen um.
DOCKER_OCI_IMAGE ?= dmigrate/d-migrate:latest
DOCKER_COVERAGE_MODULES_HTML_IMAGE ?= $(IMAGE):coverage-modules-html
RELEASE_ASSETS_IMAGE ?= $(IMAGE):release-assets
RELEASE_VERSION ?= $(DMIGRATE_VERSION)
CLI_PROJECT ?= :adapters:driving:cli
CLI_BIN ?= adapters/driving/cli/build/install/d-migrate/bin/d-migrate
ARGS ?= --help
INTEGRATION_TASKS ?=
CI_BUILD_TASKS ?= build koverVerify --no-build-cache
COVERAGE_MODULES_HTML_TASKS ?= \
	:hexagon:core:koverHtmlReport \
	:hexagon:ports-common:koverHtmlReport \
	:hexagon:ports-read:koverHtmlReport \
	:hexagon:ports-write:koverHtmlReport \
	:hexagon:application:koverHtmlReport \
	:hexagon:profiling:koverHtmlReport \
	:adapters:driven:driver-common:koverHtmlReport \
	:adapters:driven:driver-postgresql:koverHtmlReport \
	:adapters:driven:driver-postgresql-profiling:koverHtmlReport \
	:adapters:driven:driver-mysql:koverHtmlReport \
	:adapters:driven:driver-mysql-profiling:koverHtmlReport \
	:adapters:driven:driver-sqlite:koverHtmlReport \
	:adapters:driven:driver-sqlite-profiling:koverHtmlReport \
	:adapters:driven:formats:koverHtmlReport \
	:adapters:driven:integrations:koverHtmlReport \
	:adapters:driven:streaming:koverHtmlReport \
	:adapters:driving:cli:koverHtmlReport

# Docker-targeted gradle runs (see docker-check / docker-test).
# MODULES is a space-separated list of project paths, e.g.
#   make docker-check MODULES=":adapters:driving:mcp :hexagon:ports-common"
# An empty MODULES runs `check` / `test` across the whole repo.
MODULES ?=
DOCKER_TAG ?= $(IMAGE):dev-targeted

# docker-perf gating. PERF_GATE=true turns the per-hotpath baseline
# assertion in PerfSpec into a hard failure (consumed via the
# `perfGate` Gradle project property by the spec). Default false so
# shared-CI runs only the runaway-Smoke guard and reports baseline
# drift as diagnostic, per
# `docs/planning/done-archive/quality-coverage-expansion-plan.md` §5.1.
PERF_GATE ?= false
PERF_GATE_ARG = $(if $(filter true,$(PERF_GATE)),-PperfGate=true,)

# Build the gradle task list for docker-check / docker-test from MODULES.
# Falls back to the full repo task when MODULES is empty.
docker_check_tasks = $(if $(strip $(MODULES)),$(addsuffix :check,$(MODULES)),check)
docker_test_tasks  = $(if $(strip $(MODULES)),$(addsuffix :test,$(MODULES)),test)
docker_perf_tasks  = $(if $(strip $(MODULES)),$(addsuffix :test,$(MODULES)),test)

.DEFAULT_GOAL := help

.PHONY: help dev run integration ast-grep-build ast-grep parquet-sweep ci ci-build release-assets docker-resolve-deps docker-oci-build docker-build docker-check docker-test docker-detekt docker-coverage docker-coverage-gate docker-coverage-json docker-coverage-modules docker-coverage-modules-html docker-coverage-modules-summary docker-perf docker-smoke golden-update clean

help:
	@printf '%s\n' \
		'Targets:' \
		'  make dev              Install the local CLI distribution and run --help' \
		'  make run ARGS="..."   Run the CLI through Gradle with custom arguments' \
		'  make integration      Run Docker-backed integration tests' \
		'  make docs-check       Verify Markdown links and coverage docs' \
		'  make solid-suppression-gate  Fail on SOLID detekt suppressions in production Kotlin sources' \
		'  make parquet-sweep     Run the Parquet Cut-A sealed-when sweep (AP13 §4.1)' \
		'  make gates            Run Docker check, coverage and docs gates' \
		'  make ci               Run Docker build, coverage and docs gates' \
		'  make ci-build         Run CI build tasks inside the Docker build stage' \
		'  make release-assets   Build ZIP, TAR, fat JAR and SHA256 assets' \
		'  make docker-resolve-deps  Warm Gradle dependencies in Docker' \
		'  make docker-oci-build Build the publishable OCI image (runtime stage)' \
		'  make docker-build     Build the runtime Docker image' \
		'  make docker-check     Run :check inside Docker, targeted via MODULES' \
		'  make docker-test      Run :test inside Docker, targeted via MODULES' \
		'  make docker-detekt    Run Detekt inside Docker' \
		'  make docker-coverage  Build Kover HTML coverage image' \
		'  make docker-coverage-gate  Run Kover verification inside Docker' \
		'  make docker-coverage-json  Build Kover JSON coverage image' \
		'  make docker-coverage-modules  Build per-module Kover report image' \
		'  make docker-coverage-modules-html  Extract selected per-module Kover HTML reports' \
		'  make docker-coverage-modules-summary  Print per-module Kover summary inside Docker' \
		'  make docker-perf      Run `perf`-tagged Kotest specs (opt-in, nightly)' \
		'  make docker-smoke     Build and smoke-test the runtime Docker image' \
		'  make docker-gates     Run Docker build, coverage and smoke gates' \
		'  make docker-full-gates Run docker-gates plus Docker-backed integration tests' \
		'  make golden-update    Regenerate pinned tool-schema golden snapshots via Docker' \
		'  make clean            Run Gradle clean' \
		'' \
		'BI-Demo (examples/bi-demo, Spec: docs/planning/in-progress/bi-demo-compose.md):' \
		'  make bi-demo-pull     Pull pinned images (postgres + seaweed + aws-cli + metabase)' \
		'  make bi-demo-up       Start the stack (creates .env from .env.example if missing)' \
		'  make bi-demo-down     Stop containers (named volumes survive)' \
		'  make bi-demo-purge    Stop containers and remove all named volumes' \
		'  make bi-demo-smoke    End-to-end smoke (pull + up + d-migrate + S3-upload + verify)' \
		'' \
		'Sample-DB-Harness (examples/sample-db, Plan: docs/planning/done/sample-db-integration-harness.md):' \
		'  make sample-db-fetch  Fetch pinned + SHA256-verified dumps into gitignored .cache/' \
		'  make sample-db-up     Start postgres (source + target DB)' \
		'  make sample-db-smoke  Full E2E (Phase 1, Pagila/PG round-trip): load -> reverse/validate/generate -> transfer -> compare vs baseline' \
		'  make sample-db-cross-smoke  Cross-Dialect (Phase 2, Sakila MySQL->PG): reverse/validate/generate -> transfer -> parity + type conversions' \
		'  make sample-db-cross-smoke-pg2my  Cross-Dialect (Phase 2, Pagila PG->MySQL): symmetrischer Flow -> parity + type conversions' \
		'  make sample-db-sqlite-smoke  SQLite round-trip (Phase 2b, Chinook): serverless .db -> reverse/validate/generate/transfer -> parity + precision' \
		'  make sample-db-fulltext-sqlite-smoke  Fulltext P4 (SQLite FTS5): PG FULLTEXT -> FTS5 virtual table + sync triggers; live MATCH + diff-path apply' \
		'  make sample-db-scale-smoke  Scale (Phase 3, Employees) opt-in/nightly: export-resume + chunking + dual-target import (MySQL+PG) parity' \
		'  make sample-db-spatial-smoke  Spatial (Phase 5, VA1-Live-Smoke): geometry value round-trip PG->PG + MySQL->MySQL (+ native-point check)' \
		'  make sample-db-types-smoke    Typ-Kanonisierung: Post-Compare-Drift-Sensor (Typ-Matrix, Folds, Konvergenz, Rollback)' \
		'  make sample-db-tpch-gen  TPC-H (Phase 4, 4a Sourcing) opt-in: pinned DuckDB generates the TPC-H workload offline into .cache/tpch/ (SF=0.01 default)' \
		'  make sample-db-tpch-smoke  TPC-H (Phase 4, 4b Round-Trip) opt-in: reverse/validate/generate/transfer PG->PG + parity (8 tables + DECIMAL checksum)' \
		'  make sample-db-tpch-perf  TPC-H (Phase 4, 4c Volume) opt-in: export->import >=1M under caps 2cpu/4g; canonical-SHA256 losslessness (hard) + throughput (diagnostic) + resume' \
		'  make sample-db-tool-compare  TPC-H PG->PG throughput sanity-check (internal): COPY ceiling vs d-migrate vs pgloader, same workload/caps (diagnostic, NOT an audit benchmark)' \
		'  make sample-db-down   Stop containers (named volume survives)' \
		'  make sample-db-purge  Stop containers and remove the named volume' \
		'' \
		'MCP-E2E-Harness (examples/mcp-e2e, Plan: docs/planning/next/mcp-real-e2e-scope-matrix.md Teil B):' \
		'  make mcp-e2e-up       Start postgres (real connection for connections/list?checkLive=true)' \
		'  make mcp-e2e-smoke    Scope-matrix smoke against the real d-migrate:dev image: mcp serve --transport stdio, one representative tool per scope + connections/list checkLive' \
		'  make mcp-e2e-down     Stop containers (named volume survives)' \
		'  make mcp-e2e-purge    Stop containers and remove the named volume' \
		'' \
		'Variables:' \
		'  GRADLE=./gradlew DOCKER=docker IMAGE=d-migrate IMAGE_TAG=dev' \
		'  DOCKER_OCI_IMAGE=dmigrate/d-migrate:latest' \
		'  DOCKER_COVERAGE_MODULES_HTML_IMAGE=d-migrate:coverage-modules-html RELEASE_ASSETS_IMAGE=d-migrate:release-assets' \
		'  RELEASE_VERSION=0.9.7' \
		'  ARGS="schema validate --source schema.yaml"' \
		'  INTEGRATION_TASKS=":adapters:driven:driver-postgresql:test"' \
		'  MODULES=":adapters:driving:mcp" (docker-check / docker-test / docker-perf)' \
		'  PERF_GATE=true (docker-perf: turn baseline budget into a hard gate)' \
		'  DOCKER_TAG=d-migrate:dev-targeted'

dev:
	$(GRADLE) $(CLI_PROJECT):installDist
	$(CLI_BIN) --help

run:
	$(GRADLE) $(CLI_PROJECT):run --args="$(ARGS)"

docker-coverage-modules-html:
	$(DOCKER) build --target docker-coverage-modules-html \
	  $(if $(strip $(COVERAGE_MODULES_HTML_TASKS)),--build-arg COVERAGE_MODULES_HTML_TASKS="$(COVERAGE_MODULES_HTML_TASKS)",) \
	  -t $(DOCKER_COVERAGE_MODULES_HTML_IMAGE) .
	$(DOCKER) run --rm $(DOCKER_COVERAGE_MODULES_HTML_IMAGE) | tar xf -

integration:
	./scripts/test-integration-docker.sh $(INTEGRATION_TASKS)

# Doku-Referenz-Checks via d-check (Digest-Pin auf v0.74.1, siehe
# https://github.com/pt9912/d-check/releases/tag/v0.74.1). Die doc-*-Targets
# (doc-check/-trace/-complete/-doctor/-repair/-immutable/-commits/-planning/-tracked/-targets/-structure/-help)
# kommen aus make/d-check.mk, regeneriert via
# `docker run --rm ghcr.io/pt9912/d-check:v0.74.1 --print-mk > make/d-check.mk`;
# der Image-Pin lebt dort. DCHECK_DIGEST MUSS vor dem include stehen — die .mk
# wertet den Digest beim Parsen aus (ifeq → DCHECK_REF).
DCHECK_DIGEST = sha256:e31a372b66dbde26305982424854cfce7c9ab7ce555a94debeee7ee26e6d4641
include make/d-check.mk

# ── Quality-Gates ──────────────────────────────────────────────────
#
# In make/gate.mk ausgelagert: docs-check/coverage-excludes-check, semgrep(+fetch,
# SEMGREP_IMAGE), solid-suppression-gate, ports-jdbc-free-gate und die
# Aggregatoren gates/docker-gates/docker-full-gates. docs-check hängt weiterhin
# an d-checks doc-check (oben inkludiert).
include make/gate.mk

# ── Architektur-Gate (a-check) ─────────────────────────────────────
#
# Hexagon-Schicht-Regeln via a-check (extern, `pt9912/a-check`), Config in
# `.a-check.yml`. `make a-check` ist Teil von `gates`/`docker-gates`; der
# Befund-Bereinigungs-Slice ist in docs/planning/done/a-check-architecture-gate.md
# dokumentiert. make/a-check.mk ist via `a-check --print-mk` erzeugt; Digest-Pin
# (v0.12.0) lebt dort.
include make/a-check.mk

# native — GraalVM-Native-Image lokal (Linux) im Container. native-image braucht eine
# GraalVM-Toolchain, die `gradle:8.12-jdk21` nicht hat; deshalb ein eigenes Dockerfile mit
# GraalVM-Basis + Gradle aus demselben Basis-Image wie der Haupt-Build. Pin (21.0.2) lebt dort
# und muss zu .github/workflows/native-image.yml passen.
include make/native.mk

# ast-grep — syntax-bewusster (Tree-sitter) struktureller Such-/Rewrite-Helfer für
# große mechanische Umbauten (Signatur-/Rename über viele Call-Sites), wo Regex an
# Strings/Kommentaren/Formatvarianten scheitert (memory feedback_syntax_aware_refactor).
# Hermetische Stage (Dockerfile `ast-grep`), offline ausgeführt.
#
# Quoting: ARGS wird via $(value ARGS) UNEXPANDIERT an die Shell gereicht (make frisst
# `$P` sonst als $(P)). Es bleibt EINE Ebene: die ast-grep-Metavariable `$P` gegen die
# Shell schützen — `\$P` (in Doppel-Quotes) oder '$P' (Single-Quotes).
# ACHTUNG: NICHT `$$P` — die Shell liest `$$` als Prozess-ID (PID), nicht als `$`.
# Beispiele:
#   make ast-grep ARGS='run -p "\$P.borrow()" -l kotlin adapters hexagon'        # Suche
#   make ast-grep ARGS='run -p "\$A.foo(\$B)" -r "\$A.bar(\$B)" -l kotlin --update-all adapters'  # Rewrite
# Read-write-Mount (für --update-all) + Host-User-Mapping (Datei-Ownership);
# --network none, da ast-grep nach Install offline arbeitet.
AST_GREP_IMAGE ?= d-migrate-ast-grep

ast-grep-build:
	$(DOCKER) build --target ast-grep -t $(AST_GREP_IMAGE) .

ast-grep: ast-grep-build
	$(DOCKER) run --rm --network none --user "$$(id -u):$$(id -g)" \
	  -v "$(CURDIR)":/repo $(AST_GREP_IMAGE) $(value ARGS)

# Parquet Cut-A (0.9.8) — Sealed-when-Sweep aus AP13 §4.1.
# Pflicht-Lauf vor jedem Parquet-PR-Merge auf
# feature/parquet-0.9.8 (Umbrella PI-2 +
# docs/operations/parquet-pr-checklist.md).
parquet-sweep:
	./scripts/parquet-sealed-sweep.sh

ci: ci-build docs-check

ci-build:
	$(DOCKER) build --target build \
	  --build-arg GRADLE_TASKS="$(strip $(CI_BUILD_TASKS))" \
	  -t $(IMAGE):ci-build .

release-assets:
	$(DOCKER) build --target release-assets \
	  $(if $(strip $(RELEASE_VERSION)),--build-arg RELEASE_VERSION="$(RELEASE_VERSION)",) \
	  -t $(RELEASE_ASSETS_IMAGE) .
	$(DOCKER) run --rm $(RELEASE_ASSETS_IMAGE) | tar xf -

docker-resolve-deps:
	$(DOCKER) build --target deps -t $(IMAGE):deps .

# Baut das zu PUBLIZIERENDE JVM-Image aus der `runtime`-Stage — derselbe Weg, den das
# native Image ueber docker/native-image.Dockerfile schon geht. Bis 1.0.0-RC2 kam das
# publizierte Image aus Jib und lief deshalb als root und ohne mod_spatialite, waehrend
# `runtime` (USER dmigrate, /work gechownt, SpatiaLite) nur lokal verwendet wurde
# (ADR 0041). Ein Image-Bauweg statt zwei.
docker-oci-build:
	$(DOCKER) build --target runtime -t $(DOCKER_OCI_IMAGE) .

docker-build:
	$(DOCKER) build --target runtime -t $(IMAGE):$(IMAGE_TAG) .

# Targeted module check inside the Dockerfile `build` stage.
#   make docker-check                            # whole repo (slower than docker-build)
#   make docker-check MODULES=":adapters:driving:mcp"
#   make docker-check MODULES=":hexagon:ports-common :adapters:driving:mcp"
docker-check:
	$(DOCKER) build --target build \
	  --build-arg GRADLE_TASKS="$(strip $(docker_check_tasks))" \
	  -t $(DOCKER_TAG) .

# Targeted module test inside the Dockerfile `build` stage. Same semantics as
# docker-check but runs only the test task (no detekt / kover gates).
docker-test:
	$(DOCKER) build --target build \
	  --build-arg GRADLE_TASKS="$(strip $(docker_test_tasks))" \
	  -t $(DOCKER_TAG) .

docker-detekt: solid-suppression-gate
	$(DOCKER) build --target detekt -t $(IMAGE):detekt .

docker-coverage:
	$(DOCKER) build --target coverage -t $(IMAGE):coverage .

docker-coverage-gate:
	@if ! $(DOCKER) build --target coverage-verify -t $(IMAGE):coverage-verify .; then \
		echo ""; \
		echo "=== docker-coverage-gate FAILED — building reports for diagnosis ==="; \
		$(MAKE) docker-coverage; \
		$(MAKE) docker-coverage-json; \
		echo ""; \
		echo "=== Packages below 90% line coverage ==="; \
		$(DOCKER) run --rm $(IMAGE):coverage-json | \
			jq -r '.report.packages[] | (.counters.LINE.covered / (.counters.LINE.covered + .counters.LINE.missed) * 100) as $$p | select($$p < 90) | "\($$p | floor)% \(.name) (covered=\(.counters.LINE.covered) missed=\(.counters.LINE.missed))"' | \
			sort -n; \
		echo ""; \
		echo "Full HTML report: docker run --rm -p 8080:8080 $(IMAGE):coverage  # http://localhost:8080"; \
		exit 1; \
	fi

docker-coverage-json:
	$(DOCKER) build --target coverage-json -t $(IMAGE):coverage-json .

# Per-module Kover reports — module-isolated view to surface modules whose
# main code is covered only by cross-module tests. Aggregate verification
# (docker-coverage-gate) can pass while individual modules sit below 90%.
#
# The Dockerfile `coverage-modules` stage builds an image containing
# `<module>.xml` and `<module>.json` under `/reports`. Use
# `docker-coverage-modules-summary` to print the summary from inside Docker
# without local report extraction.
#
# Override the module list via:
#   make docker-coverage-modules COVERAGE_MODULES_TASKS=":hexagon:application:koverXmlReport :adapters:driving:cli:koverXmlReport"
COVERAGE_MODULES_TASKS ?=
COVERAGE_MODULES_THRESHOLD ?= 90
COVERAGE_MODULES_TOP ?= 10

docker-coverage-modules:
	@$(DOCKER) build --target coverage-modules \
	  $(if $(strip $(COVERAGE_MODULES_TASKS)),--build-arg COVERAGE_MODULES_TASKS="$(COVERAGE_MODULES_TASKS)",) \
	  -t $(IMAGE):coverage-modules .
	@echo "Module reports are available inside $(IMAGE):coverage-modules at /reports"

docker-coverage-modules-summary:
	@$(DOCKER) build --target coverage-modules-summary \
	  $(if $(strip $(COVERAGE_MODULES_TASKS)),--build-arg COVERAGE_MODULES_TASKS="$(COVERAGE_MODULES_TASKS)",) \
	  -t $(IMAGE):coverage-modules-summary .
	@$(DOCKER) run --rm $(IMAGE):coverage-modules-summary \
	  --threshold $(COVERAGE_MODULES_THRESHOLD) \
	  --top $(COVERAGE_MODULES_TOP) || true

# Opt-in performance run for `perf`-tagged Kotest specs (Phase A of
# the Quality-/Coverage-Expansion plan). Defaults to all modules; scope
# via MODULES to a single hotpath. Uses the Dockerfile `build` stage so
# the same compile/test toolchain is exercised as in CI, and forwards
# `-Dkotest.tags=perf` so untagged specs are skipped and tagged specs
# run.
#
#   make docker-perf                                  # every module
#   make docker-perf MODULES=":hexagon:application"   # one hotpath
#   make docker-perf PERF_GATE=true                   # baseline = hard gate
#
# Not part of `make ci` / `make gates` — runs nightly or on demand,
# per quality-coverage-expansion-plan §5.1.
docker-perf:
	$(DOCKER) build --target build \
	  --build-arg GRADLE_TASKS="-Dkotest.tags=perf $(PERF_GATE_ARG) $(strip $(docker_perf_tasks))" \
	  -t $(IMAGE):perf .

# Regenerate pinned JSON-Schema golden snapshots without volume mounts.
# Builds the `golden-update` Docker stage (which runs the goldenness tests
# with UPDATE_GOLDEN=true), then streams the tarball of refreshed
# `src/test/resources/golden/**` files into the source tree.
golden-update:
	$(DOCKER) build --target golden-update -t $(IMAGE):golden-update .
	$(DOCKER) run --rm $(IMAGE):golden-update | tar xf -

docker-smoke: docker-build
	$(DOCKER) run --rm $(IMAGE):$(IMAGE_TAG) --version
	$(DOCKER) run --rm $(IMAGE):$(IMAGE_TAG) --help
	# Exercise a real subcommand so the smoke fails loud if the image ships the
	# wrong entrypoint (e.g. the ast-grep stage): `--version`/`--help` alone pass
	# on ANY entrypoint, but `schema --help` only resolves against the real CLI.
	$(DOCKER) run --rm $(IMAGE):$(IMAGE_TAG) schema --help

clean:
	$(GRADLE) clean

# ── BI-Demo (examples/bi-demo) ─────────────────────────────────────
#
# In make/bi-demo.mk ausgelagert (Variable BI_DEMO_COMPOSE, .PHONY und alle
# bi-demo-*-Targets). `make bi-demo-…` funktioniert unverändert.
include make/bi-demo.mk

# ── Sample-DB-Harness (examples/sample-db) ─────────────────────────
#
# In make/sample-db.mk ausgelagert (Variable SAMPLE_DB_COMPOSE, .PHONY und alle
# sample-db-*-Targets). Der `include` bindet sie in dieselbe make-Invocation
# ein — `make sample-db-…` funktioniert unverändert.
include make/sample-db.mk

# ── MCP-E2E-Harness (examples/mcp-e2e) ──────────────────────────────
#
# In make/mcp-e2e.mk ausgelagert (Variable MCP_E2E_COMPOSE, .PHONY und alle
# mcp-e2e-*-Targets). Plan: docs/planning/next/mcp-real-e2e-scope-matrix.md
# Teil B.
include make/mcp-e2e.mk
