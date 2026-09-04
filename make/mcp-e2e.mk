# ── MCP-E2E-Harness (examples/mcp-e2e) ──────────────────────────────
#
# Ausgelagert aus dem Haupt-Makefile (per `include make/mcp-e2e.mk`), analog
# make/sample-db.mk / make/bi-demo.mk.
#
# Echter Scope-Enforcement-Smoke gegen das gebaute d-migrate:dev-MCP-
# Server-Image (docker-compose + Bash/jq, kein Testcontainers, kein
# Gradle-Testmodul). Plan:
# docs/planning/next/mcp-real-e2e-scope-matrix.md (Teil B). Voraussetzung:
# einmaliger `make docker-build IMAGE_TAG=dev`.

.PHONY: mcp-e2e-up mcp-e2e-down mcp-e2e-purge mcp-e2e-smoke

MCP_E2E_COMPOSE := docker compose -f examples/mcp-e2e/docker-compose.yml

mcp-e2e-up:
	$(MCP_E2E_COMPOSE) up -d postgres

mcp-e2e-down:
	$(MCP_E2E_COMPOSE) down

mcp-e2e-purge:
	$(MCP_E2E_COMPOSE) down -v

mcp-e2e-smoke:
	./examples/mcp-e2e/scripts/smoke-scope-matrix.sh
