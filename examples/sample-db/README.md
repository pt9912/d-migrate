# Sample-DB-E2E-Harness

Reproduzierbare End-to-End-Tests gegen das **echte `d-migrate`-CLI** mit den im
0.9.9-Pilot bewährten Sample-DBs. Mechanik = docker-compose + bash-Scripts gegen
das lokal gebaute `d-migrate:dev`-Image, exakt analog
[`../bi-demo/`](../bi-demo/README.md) — **kein** Testcontainers, **kein**
Gradle-Testmodul. Läuft **lokal *und* in CI**.

- Plan: [`../../docs/planning/in-progress/sample-db-integration-harness.md`](../../docs/planning/in-progress/sample-db-integration-harness.md)
- Sourcing/Mechanik-ADR: [`../../docs/adr/0014-sample-db-harness-fetch-and-compose.md`](../../docs/adr/0014-sample-db-harness-fetch-and-compose.md)

## Phasen & Make-Targets

| Phase | Flow | Make-Target | Gate |
| ----- | ---- | ----------- | ---- |
| 1 | Pagila PG→PG Round-Trip | `make sample-db-smoke` | PR |
| 2 | Sakila MySQL→PG Cross-Dialect | `make sample-db-cross-smoke` | PR |
| 2 | Pagila PG→MySQL Cross-Dialect | `make sample-db-cross-smoke-pg2my` | PR |
| 2b | Chinook SQLite Round-Trip | `make sample-db-sqlite-smoke` | PR |
| 3 | Employees Scale (export-resume + Chunking, MySQL→MySQL **und** MySQL→PG) | `make sample-db-scale-smoke` | **opt-in/nightly** |

Phase 3 ist wegen Laufzeit/Volumen (~4 Mio Zeilen) **nicht** im PR-Gate — nur
lokal opt-in oder nächtlich
([`sample-db-scale.yml`](../../.github/workflows/sample-db-scale.yml)).

## Sourcing (ADR 0014)

Dumps werden **on-demand** in einen **gitignored** `.cache/`-Ordner geladen —
**nie eingecheckt** (Footprint null, keine Redistribution). Die Quelle ist auf
einen **Commit-SHA** gepinnt und per **SHA256** verifiziert
(`scripts/fetch-dumps.sh`). Pagila: `neondatabase-labs/postgres-sample-dbs`
@ `ff2ccb50…` (kombinierter pg_dump, Schema + Daten).

## Was der Smoke prüft (Phase 1)

`scripts/smoke.sh` fährt den vollen Pipeline-Lauf:

```
Dump laden → schema reverse --include-all → schema validate (0 Errors)
→ schema generate --split pre-post --deterministic
→ Zielschema (pre-data) → data transfer → Zielschema (post-data)
→ schema compare gegen gepinnte Baseline
```

**Green-Kriterien** (hart): `validate` 0 Errors; `generate`-Notes == `E055`+`W123`;
Daten-Zeilenzahlen Quelle == Ziel je Tabelle; `schema compare` == Baseline
(`expected/pagila-smoke.compare.txt`). Die gepinnten Schema-Diffs sind je Klasse
in [`expected/pagila-smoke.md`](expected/pagila-smoke.md) erklärt — der Smoke ist
ein **Regressions-Baseline-Gate**, nicht „0 Diffs".

## Benutzung

```sh
make docker-build IMAGE_TAG=dev   # einmalig: d-migrate:dev-Runtime-Image
make sample-db-smoke              # fetch + up + voller E2E-Lauf
make sample-db-down               # Container stoppen (Volume bleibt)
make sample-db-purge              # Container + Volume entfernen
```

Voraussetzungen am Host: `docker`, `docker compose`, `curl`, `sha256sum`. Der
Stack bleibt nach dem Lauf stehen (Inspektion); Ports binden nur an `127.0.0.1`.

## Struktur

| Pfad | Zweck |
| ---- | ----- |
| `docker-compose.yml` | postgres + mysql (je Quelle+Ziel-DBs) + `dmigrate`-Service (profile `tools`) |
| `sql/`, `sql-mysql/` | initdb: legt die PG- bzw. MySQL-Quell-/Ziel-DBs an (frisches Volume) |
| `scripts/fetch-dumps.sh` | gepinnter, SHA256-verifizierter Dump-Fetch → `.cache/` (Employees nur bei `FETCH_EMPLOYEES=1`) |
| `scripts/smoke.sh` | Phase 1 — Pagila PG→PG Round-Trip + Baseline-Vergleich |
| `scripts/smoke-cross.sh`, `scripts/smoke-cross-pg2my.sh` | Phase 2 — Cross-Dialect (Sakila MySQL→PG, Pagila PG→MySQL) |
| `scripts/smoke-sqlite.sh` | Phase 2b — Chinook SQLite Round-Trip |
| `scripts/smoke-scale.sh` | Phase 3 — Employees export-resume + Chunking + Dual-Target-Import |
| `expected/` | gepinnte Baselines + Diff-Erklärung je Flow |
| `.cache/`, `out/` | gitignored (Dumps bzw. CLI-Artefakte) |

## Phase 3 — Scale (Employees), opt-in/nightly

`scripts/smoke-scale.sh` übt den **datei-basierten** `data export`→`import`-Pfad
(der einzige mit `--resume`) mit ~4 Mio Zeilen:

```
Employees laden (MySQL) → reverse/validate (6 Basis-Tabellen)
→ data export json --split-files --chunk-size 5000
     ↳ Pass 1 MITTEN im Stream unterbrochen (docker kill bei erstem Checkpoint)
     ↳ Pass 2 --resume <operationId> vollendet das Bundle
→ data import in employees_my_target (MySQL) UND employees_pg_target (PG)
→ Zeilen-Parität (Quelle == Ziel == Baseline) + SUM(salary)-Checksumme je Ziel
```

**Green-Kriterien** (hart): Resume vollendet alle 6 Tabellen-Dateien; Parität
über alle 6 Tabellen je Ziel; `SUM(salary)` round-trippt exakt. **Scope:** nur
Daten (Tabellen+PK via pre-data); FKs/Views (post-data) = Phase-2-Domäne, nicht
hier. Wegen Laufzeit/Volumen **nicht** im PR-Gate — `make sample-db-scale-smoke`
oder nächtlich. Aufgedeckter + behobener Defekt **S1** (PK-Nullability-Preflight)
in [`../../docs/planning/done/sample-db-phase3-findings.md`](../../docs/planning/done/sample-db-phase3-findings.md).

## Aufgedeckte Fidelity-Defekte

Der Erstlauf (Phase 1) hat echte Round-Trip-Defekte aufgedeckt (Trigger-Naming,
Funktions-Attribute, Programmability-Ordering) — getrackt in
[`../../docs/planning/done/sample-db-roundtrip-findings.md`](../../docs/planning/done/sample-db-roundtrip-findings.md).
Phase 2 brachte Y1 (YEAR-Wert) + Partitions-Befund
([`../../docs/planning/done/sample-db-phase2-findings.md`](../../docs/planning/done/sample-db-phase2-findings.md)),
Phase 3 den PK-Nullability-Preflight (S1). Genau dafür gibt es den Harness.
