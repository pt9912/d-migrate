# Sample-DB-E2E-Harness

Reproduzierbare End-to-End-Tests gegen das **echte `d-migrate`-CLI** mit den im
0.9.9-Pilot bewährten Sample-DBs (Phase 1: **Pagila/PostgreSQL**). Mechanik =
docker-compose + bash-Scripts gegen das lokal gebaute `d-migrate:dev`-Image,
exakt analog [`../bi-demo/`](../bi-demo/README.md) — **kein** Testcontainers,
**kein** Gradle-Testmodul. Läuft **lokal *und* in CI**.

- Plan: [`../../docs/planning/in-progress/sample-db-integration-harness.md`](../../docs/planning/in-progress/sample-db-integration-harness.md)
- Sourcing/Mechanik-ADR: [`../../docs/adr/0014-sample-db-harness-fetch-and-compose.md`](../../docs/adr/0014-sample-db-harness-fetch-and-compose.md)

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
| `docker-compose.yml` | postgres (Quelle+Ziel-DB via `sql/`) + `dmigrate`-Service (profile `tools`) |
| `sql/00-init-databases.sql` | legt `pagila` + `pagila_target` an |
| `scripts/fetch-dumps.sh` | gepinnter, SHA256-verifizierter Dump-Fetch → `.cache/` |
| `scripts/smoke.sh` | voller E2E-Smoke + Baseline-Vergleich |
| `expected/` | gepinnte Baseline + Diff-Erklärung |
| `.cache/`, `out/` | gitignored (Dumps bzw. CLI-Artefakte) |

## Aufgedeckte Fidelity-Defekte

Der Erstlauf hat echte Round-Trip-Defekte aufgedeckt (Trigger-Naming, Funktions-
Attribute, Programmability-Ordering) — getrackt in
[`../../docs/planning/in-progress/sample-db-roundtrip-findings.md`](../../docs/planning/in-progress/sample-db-roundtrip-findings.md).
Genau dafür gibt es den Harness.
