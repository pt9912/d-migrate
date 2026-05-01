GRADLE ?= ./gradlew
DOCKER ?= docker

IMAGE ?= d-migrate
IMAGE_TAG ?= dev
CLI_PROJECT ?= :adapters:driving:cli
CLI_BIN ?= adapters/driving/cli/build/install/d-migrate/bin/d-migrate
ARGS ?= --help
INTEGRATION_TASKS ?=

.DEFAULT_GOAL := help

.PHONY: help resolve-deps dev run build test check lint coverage-gate coverage-report integration docs-check smoke release-assets docker-build docker-smoke clean

help:
	@printf '%s\n' \
		'Targets:' \
		'  make dev              Install the local CLI distribution and run --help' \
		'  make run ARGS="..."   Run the CLI through Gradle with custom arguments' \
		'  make build            Run the full Gradle build' \
		'  make test             Run unit tests' \
		'  make check            Run Gradle check' \
		'  make lint             Run Detekt across subprojects' \
		'  make coverage-gate    Run tests and root Kover verification' \
		'  make coverage-report  Generate Kover HTML/XML reports' \
		'  make integration      Run Docker-backed integration tests' \
		'  make docs-check       Verify Markdown links in docs/' \
		'  make smoke            Build the CLI distribution and run --version/--help' \
		'  make release-assets   Build ZIP, TAR, fat JAR and SHA256 assets' \
		'  make docker-build     Build the runtime Docker image' \
		'  make docker-smoke     Build and smoke-test the runtime Docker image' \
		'  make clean            Run Gradle clean' \
		'' \
		'Variables:' \
		'  GRADLE=./gradlew DOCKER=docker IMAGE=d-migrate IMAGE_TAG=dev' \
		'  ARGS="schema validate --source schema.yaml"' \
		'  INTEGRATION_TASKS=":adapters:driven:driver-postgresql:test"'

resolve-deps:
	$(GRADLE) resolveAllDependencies

dev:
	$(GRADLE) $(CLI_PROJECT):installDist
	$(CLI_BIN) --help

run:
	$(GRADLE) $(CLI_PROJECT):run --args="$(ARGS)"

build:
	$(GRADLE) build

test:
	$(GRADLE) test

check:
	$(GRADLE) check

lint:
	$(GRADLE) detekt

coverage-gate:
	$(GRADLE) test koverVerify

coverage-report:
	$(GRADLE) test koverHtmlReport koverXmlReport

integration:
	./scripts/test-integration-docker.sh $(INTEGRATION_TASKS)

docs-check:
	./scripts/verify-doc-refs.sh

smoke:
	$(GRADLE) $(CLI_PROJECT):installDist
	$(CLI_BIN) --version
	$(CLI_BIN) --help

release-assets:
	$(GRADLE) $(CLI_PROJECT):assembleReleaseAssets

docker-build:
	$(DOCKER) build -t $(IMAGE):$(IMAGE_TAG) .

docker-smoke: docker-build
	$(DOCKER) run --rm $(IMAGE):$(IMAGE_TAG) --version
	$(DOCKER) run --rm $(IMAGE):$(IMAGE_TAG) --help

clean:
	$(GRADLE) clean
