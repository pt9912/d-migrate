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

.PHONY: mcp-e2e-up mcp-e2e-down mcp-e2e-purge mcp-e2e-smoke mcp-e2e-roundtrip mcp-e2e-roundtrip-oracle

MCP_E2E_COMPOSE := docker compose -f examples/mcp-e2e/docker-compose.yml

# Startet die drei schnellen Dialekte (Postgres, MySQL, SQL Server). Oracle
# nur per `mcp-e2e-roundtrip-oracle` — sein Kaltstart dauert 2-3 Minuten.
mcp-e2e-up:
	$(MCP_E2E_COMPOSE) up -d postgres mysql mssql

mcp-e2e-down:
	$(MCP_E2E_COMPOSE) down

mcp-e2e-purge:
	$(MCP_E2E_COMPOSE) down -v

mcp-e2e-smoke:
	./examples/mcp-e2e/scripts/smoke-scope-matrix.sh

# Hin-und-Her-Migrationen ueber alle Dialekte: Schema -> generate -> anwenden
# -> reverse -> compare, je Dialekt und in beide Richtungen. Faehrt den Stack
# selbst hoch, wenn er nicht laeuft.
mcp-e2e-roundtrip:
	./examples/mcp-e2e/scripts/smoke-cross-dialect-roundtrip.sh

# Dasselbe mit Oracle (Kaltstart 2-3 Minuten zusaetzlich).
mcp-e2e-roundtrip-oracle:
	MCP_E2E_WITH_ORACLE=1 ./examples/mcp-e2e/scripts/smoke-cross-dialect-roundtrip.sh
