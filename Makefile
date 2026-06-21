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
DOCKER_OCI_TAR_IMAGE ?= $(IMAGE):jib-image-tar
DOCKER_OCI_TAR ?= build/docker/jib-image.tar
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

.PHONY: help dev run integration docs-check coverage-excludes-check solid-suppression-gate parquet-sweep gates ci ci-build release-assets docker-resolve-deps docker-oci-build docker-build docker-check docker-test docker-detekt docker-coverage docker-coverage-gate docker-coverage-json docker-coverage-modules docker-coverage-modules-html docker-coverage-modules-summary docker-perf docker-smoke docker-gates docker-full-gates golden-update clean bi-demo-env bi-demo-pull bi-demo-up bi-demo-down bi-demo-purge bi-demo-smoke sample-db-fetch sample-db-up sample-db-down sample-db-purge sample-db-smoke sample-db-cross-smoke sample-db-cross-smoke-pg2my sample-db-sqlite-smoke sample-db-scale-smoke

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
		'  make docker-oci-build Build the Jib OCI image via the Dockerfile stage' \
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
		'Sample-DB-Harness (examples/sample-db, Plan: docs/planning/in-progress/sample-db-integration-harness.md):' \
		'  make sample-db-fetch  Fetch pinned + SHA256-verified dumps into gitignored .cache/' \
		'  make sample-db-up     Start postgres (source + target DB)' \
		'  make sample-db-smoke  Full E2E (Phase 1, Pagila/PG round-trip): load -> reverse/validate/generate -> transfer -> compare vs baseline' \
		'  make sample-db-cross-smoke  Cross-Dialect (Phase 2, Sakila MySQL->PG): reverse/validate/generate -> transfer -> parity + type conversions' \
		'  make sample-db-cross-smoke-pg2my  Cross-Dialect (Phase 2, Pagila PG->MySQL): symmetrischer Flow -> parity + type conversions' \
		'  make sample-db-sqlite-smoke  SQLite round-trip (Phase 2b, Chinook): serverless .db -> reverse/validate/generate/transfer -> parity + precision' \
		'  make sample-db-scale-smoke  Scale (Phase 3, Employees) opt-in/nightly: export-resume + chunking + dual-target import (MySQL+PG) parity' \
		'  make sample-db-down   Stop containers (named volume survives)' \
		'  make sample-db-purge  Stop containers and remove the named volume' \
		'' \
		'Variables:' \
		'  GRADLE=./gradlew DOCKER=docker IMAGE=d-migrate IMAGE_TAG=dev' \
		'  DOCKER_OCI_TAR_IMAGE=d-migrate:jib-image-tar DOCKER_OCI_TAR=build/docker/jib-image.tar' \
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

# Doku-Referenz-Checks via d-check (Digest-Pin auf v0.9.0, siehe
# https://github.com/pt9912/d-check/releases/tag/v0.9.0); 
D_CHECK_IMAGE ?= ghcr.io/pt9912/d-check@sha256:5bccf9fb3d1c54639dec3a541771d2ea43db9a0c1c58c28b3f12f20d38133d1b

docs-check: coverage-excludes-check
	$(DOCKER) run --rm -v "$(CURDIR)":/repo:ro $(D_CHECK_IMAGE)

coverage-excludes-check:
	python3 ./scripts/verify-kover-excludes-ledger.py

# Statische Sicherheitsanalyse via semgrep — hermetisches Gate:
#  - gepinntes Regelset, on-demand gecacht (config/semgrep/, statt `--config auto`).
#    Upstream ist LGPL-2.1 + Commons Clause → NICHT vendored, sondern per
#    scripts/fetch-semgrep-rules.sh gepinnt+SHA256-verifiziert geholt (gitignored).
#  - Image per Digest gepinnt (SEMGREP_IMAGE),
#  - Scan offline (`--network none`, `--metrics off`) → reproduzierbar.
# Bewusst akzeptierte Befunde sind inline via `# nosemgrep: <rule-id>` annotiert
# (Begruendung am Fundort). Regel-/Image-Updates kommen als bewusster Pin-Bump,
# nie als spontaner Gate-Bruch. Image + Regeln werden beim Erstlauf gezogen (Pull/
# Fetch laufen ueber Host/Daemon, nicht den `--network none`-Scan-Container).
SEMGREP_IMAGE ?= semgrep/semgrep@sha256:c180f0c93a17b420c0af5006214a29d3c747c5459c732b740191adf657dd0068

semgrep-rules-fetch:
	./scripts/fetch-semgrep-rules.sh

semgrep: semgrep-rules-fetch
	$(DOCKER) run --rm --network none -v "$(CURDIR)":/src:ro $(SEMGREP_IMAGE) \
	  semgrep scan --config /src/config/semgrep --error --metrics off /src

solid-suppression-gate:
	./scripts/solid-suppression-gate.sh

# Parquet Cut-A (0.9.8) — Sealed-when-Sweep aus AP13 §4.1.
# Pflicht-Lauf vor jedem Parquet-PR-Merge auf
# feature/parquet-0.9.8 (Umbrella PI-2 +
# docs/operations/parquet-pr-checklist.md).
parquet-sweep:
	./scripts/parquet-sealed-sweep.sh

gates: docker-check docker-coverage-gate docs-check semgrep

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

docker-oci-build:
	$(DOCKER) build --target jib-image-tar -t $(DOCKER_OCI_TAR_IMAGE) .
	mkdir -p $(dir $(DOCKER_OCI_TAR))
	$(DOCKER) run --rm $(DOCKER_OCI_TAR_IMAGE) > $(DOCKER_OCI_TAR)
	$(DOCKER) load -i $(DOCKER_OCI_TAR)

docker-build:
	$(DOCKER) build -t $(IMAGE):$(IMAGE_TAG) .

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

docker-gates: solid-suppression-gate docker-build docker-coverage-gate docker-smoke semgrep

docker-full-gates: docker-gates integration

clean:
	$(GRADLE) clean

# ── BI-Demo (examples/bi-demo) ─────────────────────────────────────
#
# Kapselt den langen `docker compose -f
# examples/bi-demo/docker-compose.yml ...`-Pfad. Spec siehe
# `docs/planning/in-progress/bi-demo-compose.md`. Voraussetzung
# fuer den `dmigrate`-Service: einmaliger `make docker-build
# IMAGE_TAG=dev` (baut das d-migrate:dev-Runtime-Image).
BI_DEMO_COMPOSE := docker compose -f examples/bi-demo/docker-compose.yml

bi-demo-env:
	@if [ ! -f examples/bi-demo/.env ]; then \
	  cp examples/bi-demo/.env.example examples/bi-demo/.env; \
	  echo "[bi-demo] created examples/bi-demo/.env from .env.example"; \
	fi
	@mkdir -p examples/bi-demo/out

bi-demo-pull: bi-demo-env
	$(BI_DEMO_COMPOSE) pull

bi-demo-up: bi-demo-env
	$(BI_DEMO_COMPOSE) up -d

bi-demo-down:
	$(BI_DEMO_COMPOSE) down

bi-demo-purge:
	$(BI_DEMO_COMPOSE) down -v

bi-demo-smoke:
	./examples/bi-demo/scripts/smoke.sh

# ── Sample-DB-Harness (examples/sample-db) ─────────────────────────
#
# Reproduzierbarer E2E-Smoke gegen das echte d-migrate:dev-CLI mit
# gepinnten Sample-DBs (Phase 1: Pagila/PG-Round-Trip). Plan:
# docs/planning/in-progress/sample-db-integration-harness.md. Sourcing/Mechanik:
# docs/adr/0014-sample-db-harness-fetch-and-compose.md. Voraussetzung:
# einmaliger `make docker-build IMAGE_TAG=dev`.
SAMPLE_DB_COMPOSE := docker compose -f examples/sample-db/docker-compose.yml

sample-db-fetch:
	./examples/sample-db/scripts/fetch-dumps.sh

sample-db-up:
	$(SAMPLE_DB_COMPOSE) up -d postgres

sample-db-down:
	$(SAMPLE_DB_COMPOSE) down

sample-db-purge:
	$(SAMPLE_DB_COMPOSE) down -v

sample-db-smoke:
	./examples/sample-db/scripts/smoke.sh

sample-db-cross-smoke:
	./examples/sample-db/scripts/smoke-cross.sh

sample-db-cross-smoke-pg2my:
	./examples/sample-db/scripts/smoke-cross-pg2my.sh

sample-db-sqlite-smoke:
	./examples/sample-db/scripts/smoke-sqlite.sh

# Phase 3 (Scale, Employees) — opt-in/nightly, NICHT im PR-Gate. Lädt das
# große Employees-Dataset (FETCH_EMPLOYEES=1, ~165 MiB), übt export-resume +
# Chunking + Dual-Target-Import (MySQL + PG). Laufzeit/Volumen → nur lokal
# oder im scheduled Workflow .github/workflows/sample-db-scale.yml.
sample-db-scale-smoke:
	./examples/sample-db/scripts/smoke-scale.sh
