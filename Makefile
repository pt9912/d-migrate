GRADLE ?= ./gradlew
DOCKER ?= docker

IMAGE ?= d-migrate
IMAGE_TAG ?= dev
DOCKER_OCI_TAR_IMAGE ?= $(IMAGE):jib-image-tar
DOCKER_OCI_TAR ?= build/docker/jib-image.tar
COVERAGE_MODULES_HTML_IMAGE ?= $(IMAGE):coverage-modules-html
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

# Build the gradle task list for docker-check / docker-test from MODULES.
# Falls back to the full repo task when MODULES is empty.
docker_check_tasks = $(if $(strip $(MODULES)),$(addsuffix :check,$(MODULES)),check)
docker_test_tasks  = $(if $(strip $(MODULES)),$(addsuffix :test,$(MODULES)),test)

.DEFAULT_GOAL := help

.PHONY: help resolve-deps dev run coverage-gate coverage-report coverage-modules-html integration docs-check smoke gates ci ci-build release-assets oci-build docker-oci-build docker-build docker-check docker-test docker-detekt docker-coverage docker-coverage-gate docker-coverage-json docker-coverage-modules docker-coverage-modules-summary docker-smoke docker-gates docker-full-gates golden-update clean

help:
	@printf '%s\n' \
		'Targets:' \
		'  make dev              Install the local CLI distribution and run --help' \
		'  make run ARGS="..."   Run the CLI through Gradle with custom arguments' \
		'  make coverage-gate    Run tests and root Kover verification' \
		'  make coverage-report  Generate Kover HTML/XML reports' \
		'  make coverage-modules-html  Generate selected per-module Kover HTML reports' \
		'  make integration      Run Docker-backed integration tests' \
		'  make docs-check       Verify Markdown links in docs/' \
		'  make smoke            Build the CLI distribution and run --version/--help' \
		'  make gates            Run Docker check, coverage and docs gates' \
		'  make ci               Run Docker build, coverage and docs gates' \
		'  make ci-build         Run CI build tasks inside the Docker build stage' \
		'  make release-assets   Build ZIP, TAR, fat JAR and SHA256 assets' \
		'  make oci-build        Build the Jib OCI image locally' \
		'  make docker-oci-build Build the Jib OCI image via the Dockerfile stage' \
		'  make docker-build     Build the runtime Docker image' \
		'  make docker-check     Run :check inside Docker, targeted via MODULES' \
		'  make docker-test      Run :test inside Docker, targeted via MODULES' \
		'  make docker-detekt    Run Detekt inside Docker' \
		'  make docker-coverage  Build Kover HTML coverage image' \
		'  make docker-coverage-gate  Run Kover verification inside Docker' \
		'  make docker-coverage-json  Build Kover JSON coverage image' \
		'  make docker-coverage-modules  Build per-module Kover report image' \
		'  make docker-coverage-modules-summary  Print per-module Kover summary inside Docker' \
		'  make docker-smoke     Build and smoke-test the runtime Docker image' \
		'  make docker-gates     Run Docker build, coverage and smoke gates' \
		'  make docker-full-gates Run docker-gates plus Docker-backed integration tests' \
		'  make golden-update    Regenerate pinned tool-schema golden snapshots via Docker' \
		'  make clean            Run Gradle clean' \
		'' \
		'Variables:' \
		'  GRADLE=./gradlew DOCKER=docker IMAGE=d-migrate IMAGE_TAG=dev' \
		'  DOCKER_OCI_TAR_IMAGE=d-migrate:jib-image-tar DOCKER_OCI_TAR=build/docker/jib-image.tar' \
		'  COVERAGE_MODULES_HTML_IMAGE=d-migrate:coverage-modules-html RELEASE_ASSETS_IMAGE=d-migrate:release-assets' \
		'  RELEASE_VERSION=0.9.7' \
		'  ARGS="schema validate --source schema.yaml"' \
		'  INTEGRATION_TASKS=":adapters:driven:driver-postgresql:test"' \
		'  MODULES=":adapters:driving:mcp" (docker-check / docker-test)' \
		'  DOCKER_TAG=d-migrate:dev-targeted'

resolve-deps:
	$(GRADLE) resolveAllDependencies

dev:
	$(GRADLE) $(CLI_PROJECT):installDist
	$(CLI_BIN) --help

run:
	$(GRADLE) $(CLI_PROJECT):run --args="$(ARGS)"

coverage-gate:
	$(GRADLE) test koverVerify

coverage-report:
	$(GRADLE) test koverHtmlReport koverXmlReport

coverage-modules-html:
	$(DOCKER) build --target coverage-modules-html \
	  $(if $(strip $(COVERAGE_MODULES_HTML_TASKS)),--build-arg COVERAGE_MODULES_HTML_TASKS="$(COVERAGE_MODULES_HTML_TASKS)",) \
	  -t $(COVERAGE_MODULES_HTML_IMAGE) .
	$(DOCKER) run --rm $(COVERAGE_MODULES_HTML_IMAGE) | tar xf -

integration:
	./scripts/test-integration-docker.sh $(INTEGRATION_TASKS)

docs-check:
	./scripts/verify-doc-refs.sh

smoke:
	$(GRADLE) $(CLI_PROJECT):installDist
	$(CLI_BIN) --version
	$(CLI_BIN) --help

gates: docker-check docker-coverage-gate docs-check

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

oci-build:
	$(GRADLE) $(CLI_PROJECT):jibDockerBuild

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

docker-detekt:
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

docker-gates: docker-build docker-coverage-gate docker-smoke

docker-full-gates: docker-gates integration

clean:
	$(GRADLE) clean
