# ── Sample-DB-Harness (examples/sample-db) ─────────────────────────
#
# Ausgelagert aus dem Haupt-Makefile (per `include make/sample-db.mk`), damit die
# opt-in/nightly E2E-Smokes der Sample-DB-Harness gebündelt an einem Ort liegen.
#
# Reproduzierbarer E2E-Smoke gegen das echte d-migrate:dev-CLI mit
# gepinnten Sample-DBs (Phase 1: Pagila/PG-Round-Trip). Plan:
# docs/planning/done/sample-db-integration-harness.md. Sourcing/Mechanik:
# docs/adr/0014-sample-db-harness-fetch-and-compose.md. Voraussetzung:
# einmaliger `make docker-build IMAGE_TAG=dev`.

.PHONY: sample-db-fetch sample-db-up sample-db-down sample-db-purge sample-db-smoke sample-db-cross-smoke sample-db-cross-smoke-pg2my sample-db-cross-smoke-pg2ms sample-db-cross-smoke-ms2pg sample-db-3hop-smoke sample-db-sqlite-smoke sample-db-verify-sqlite-smoke sample-db-atomic-sqlite-smoke sample-db-parallel-pg-smoke sample-db-fulltext-sqlite-smoke sample-db-scale-smoke sample-db-spatial-smoke sample-db-types-smoke sample-db-tpch-gen sample-db-tpch-smoke sample-db-tpch-perf sample-db-tpcds-gen sample-db-tpcds-smoke sample-db-tool-compare

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

# MSSQL-Leg (ADR 0047, Slice 3b): Pagila PG -> SQL Server. Wendet das erzeugte
# Skript per sqlcmd an (Batch-Trenner/SET-Praeambel aus Slice 2a) und transferiert
# die Daten. Braucht compose postgres+mssql (~2 GB RAM, Microsoft-EULA, siehe
# docs/user/quality.md) + d-migrate:dev-Image.
sample-db-cross-smoke-pg2ms:
	./examples/sample-db/scripts/smoke-cross-pg2ms.sh

# MSSQL-Leg Gegenrichtung (ADR 0047, Slice 4): SQL Server als QUELLE. Hop 0 saet
# die Quelle mit der pg2ms-Mechanik, Hop 1 faehrt reverse/generate/transfer
# MSSQL->PG und prueft dreifache Zeilen-Paritaet gegen die Original-Pagila.
sample-db-cross-smoke-ms2pg:
	./examples/sample-db/scripts/smoke-cross-ms2pg.sh

# Lastenheft-8.6 — 3-Hop-Kette PostgreSQL -> MySQL -> SQLite als EIN verketteter
# Fluss (nicht nur paarweise): Pagila wandert PG->MySQL->SQLite, End-to-End-Paritaet
# + die drei 8.6-Typ-Transformationen (Serial/Array/ENUM). Braucht compose postgres+
# mysql + sqlite3 am Host + d-migrate:dev-Image.
sample-db-3hop-smoke:
	./examples/sample-db/scripts/smoke-3hop.sh

sample-db-sqlite-smoke:
	./examples/sample-db/scripts/smoke-sqlite.sh

# LN-009 — SQLite->SQLite `data transfer --verify` Smoke (same-dialect, byte-exakt +
# Divergenz-Erkennung). Kein DB-Container noetig; braucht sqlite3 + d-migrate:dev-Image.
sample-db-verify-sqlite-smoke:
	./examples/sample-db/scripts/smoke-verify-sqlite.sh

# LN-013 — SQLite `data transfer --atomic` Smoke (Clean-Load-Rollback: Fehler in
# Tabelle 2 rollt auch die committete Tabelle 1 zurueck). Kein DB-Container noetig.
sample-db-atomic-sqlite-smoke:
	./examples/sample-db/scripts/smoke-atomic-sqlite.sh

# LN-007/LN-008 — PostgreSQL `data transfer --parallel` Smoke: FK-sichere Topo-Ebenen
# (customer vor payment) + Partitions-Fan-out (payment pro Monats-Kind nebenlaeufig).
# Braucht den compose postgres-Service + lokal gebautes d-migrate:dev-Image.
sample-db-parallel-pg-smoke:
	./examples/sample-db/scripts/smoke-parallel-pg.sh

# Fulltext-Slice P4 (SQLite FTS5) — postgres up + Pagila-Reverse belegt PG FULLTEXT ->
# SQLite FTS5-Generate; ein self-contained Schema belegt FTS5-MATCH live (rebuild + alle
# drei Sync-Trigger + Negativkontrolle); migrate --execute belegt den Diff-Pfad-Apply.
# Voraussetzung: sqlite3 am Host + lokal gebautes d-migrate:dev-Image.
sample-db-fulltext-sqlite-smoke:
	./examples/sample-db/scripts/smoke-fulltext-sqlite.sh

# Phase 3 (Scale, Employees) — opt-in/nightly, NICHT im PR-Gate. Lädt das
# große Employees-Dataset (FETCH_EMPLOYEES=1, ~165 MiB), übt export-resume +
# Chunking + Dual-Target-Import (MySQL + PG). Laufzeit/Volumen → nur lokal
# oder im scheduled Workflow .github/workflows/sample-db-scale.yml.
sample-db-scale-smoke:
	./examples/sample-db/scripts/smoke-scale.sh

# Phase 5 (Spatial) — VA1-Live-Smoke: postgis + mysql up, Geometrie-Wert-Transfer
# PG->PG und MySQL->MySQL (inkl. native-PG-point-Gegenprobe). Verifiziert die
# Spatial-VA1-Kette live gegen echte DBs. Voraussetzung: docker-build IMAGE_TAG=dev.
sample-db-spatial-smoke:
	./examples/sample-db/scripts/smoke-spatial.sh

# Typ-Kanonisierungs-Smoke (postcompare-type-canonicalization slice, AP5) — permanenter
# Sensor für die Post-Compare-Drift-Familie: SQLite-Typ-Matrix (21 Neutraltypen, je
# frisches migrate --execute → Exit 0), UNIQUE-/FK-Folds inkl. Reverse-Fidelity,
# Plan-Konvergenz (Zweitlauf = 0 Statements), Rebuild-UNIQUE, Rollback-Round-Trip
# (v7-Artefakt), schema-compare-Striktheits-Gegenprobe + PG/MySQL-Kanten-Proben.
# Voraussetzung: docker-build --target runtime (d-migrate:dev).
sample-db-types-smoke:
	./examples/sample-db/scripts/smoke-types.sh

# Phase 4 (Performance, 4a Sourcing) — opt-in, NICHT im PR-Gate. Pinnt das
# DuckDB-CLI v1.4.5 + tpch-Extension (FETCH_TPCH=1, ~50 MiB) und generiert die
# TPC-H-Workload (8 Tabellen) OFFLINE in einem digest-gepinnten Loader; kein
# Dump im Repo. SF konfigurierbar (Default 0.01): `SF=0.1 make sample-db-tpch-gen`.
sample-db-tpch-gen:
	./examples/sample-db/scripts/tpch-generate.sh

# Phase 4 (Performance, 4b Round-Trip) — opt-in, NICHT im PR-Gate. Generiert die
# TPC-H-Workload (4a) und fährt den vollen Korrektheits-Round-Trip PG->PG:
# reverse/validate/generate/transfer + Parität (8 Tabellen + DECIMAL-Checksumme).
# Voraussetzung: lokales d-migrate:dev (`make docker-build IMAGE_TAG=dev`).
sample-db-tpch-smoke:
	./examples/sample-db/scripts/smoke-tpch.sh

# Phase 4 (Performance, 4c Volumen-Abnahme Mess-Kern) — opt-in, NICHT im PR-Gate.
# Datei-basierter data export -> import (>=1 Mio, SF=0.2 default) unter Caps 2cpu/4g:
# Verlustfreiheit per kanonischem SHA-256 (HART, host-unabhängig) + Export/Import-
# Durchsatz vs LN-002/003 (diagnostisch; hart nur PERF_GATE=true auf dem designierten
# Runner) + Resume nach Mid-Stream-Abbruch. Kalibrier-Guard + Nightly-Hart-Gate = Teil 2.
sample-db-tpch-perf:
	./examples/sample-db/scripts/smoke-tpch-perf.sh

# Phase 4 (optionaler Sub-Slice 4e) — TPC-DS-Generierung (24 Tabellen) offline aus
# gepinntem DuckDB + tpcds-Extension. Opt-in, NICHT im PR-Gate.
sample-db-tpcds-gen:
	./examples/sample-db/scripts/tpcds-generate.sh

# Phase 4 (optionaler Sub-Slice 4e) — TPC-DS Round-Trip-Korrektheit PG->PG:
# reverse/validate/generate/transfer + Parität (24 Tabellen + DECIMAL-Checksumme).
# Opt-in, NICHT im PR-Gate. Voraussetzung: lokales d-migrate:dev (`make docker-build IMAGE_TAG=dev`).
sample-db-tpcds-smoke:
	./examples/sample-db/scripts/smoke-tpcds.sh

# Phase 4 (#2 Tool-Vergleich) — opt-in, NICHT im PR-Gate, INTERNER Sanity-Check (kein
# Audit-Benchmark). Bewegt dieselbe TPC-H-Workload PG->PG mit COPY (native Decke),
# d-migrate (CSV, gecappt) und optional pgloader (gepinnt, gecappt); rows/s + Anteil COPY-Decke.
# pgloader ist Default AUS (WITH_PGLOADER=1 schaltet den best-effort-Vergleich zu; sein SBCL-Heap
# kann unter den Caps reißen — diagnostisch, kein Gate). Doku: docs/planning/open/tool-comparison.md.
sample-db-tool-compare:
	./examples/sample-db/scripts/smoke-tool-compare.sh
