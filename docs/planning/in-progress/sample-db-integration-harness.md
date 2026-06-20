# Plan: Sample-DB-E2E-Harness (docker-compose + Scripts)

> Dokumenttyp: Umsetzungsplan (Slice)
> Status: In Arbeit (2026-06-18). **Phase 0 + Phase 1 (Pagila/PG) erledigt**;
> Dialekt-/Spatial-Matrix dokumentiert (MySQL/SQLite/PostGIS/Spatialite geplant,
> Bau folgt). Sourcing **und** Mechanik via [ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)
> entschieden (supersedet ADR 0013).
> Roadmap-Slot: Phase 1–2b (Smoke/Compatibility, inkl. SQLite) = Test-Infrastruktur;
> Phase 3 (Scale) = 1.0.0-QA. **Phase 4 (Performance/TPC, LF 8.1/8.2) und Phase 5
> (Spatial: PostGIS/Spatialite) = eigene Folge-Slices**.
> Referenzen: [`../open/test-database-candidates.md`](../open/test-database-candidates.md)
> (Kandidaten-Katalog), [`../../operations/pilot-validation-playbook.md`](../../operations/pilot-validation-playbook.md)
> (Szenarien-Vorlage), [bi-demo](../../../examples/bi-demo/README.md) (Harness-Muster),
> ADR 0014 (Sourcing + Mechanik), ADR 0004 (Planning-Struktur).

## Ziel

Die im Pilot bewährten Sample-DBs (Pagila, Sakila; Employees für Scale) als
**reproduzierbare End-to-End-Tests gegen das echte CLI** operationalisieren:
Container hoch → Dump laden → `reverse` → `validate` → `generate --split` →
Zielschema → `data transfer` → `schema compare`. Schließt die Lücke
„Smoke/Compatibility liefen bisher nur ad-hoc im Pilot, nicht automatisiert".

## Design (ADR 0014)

- **Mechanik = docker-compose + bash-Scripts + echtes `d-migrate:dev`-Image**,
  exakt das Muster von `examples/bi-demo/` — **kein** Testcontainers, **kein**
  Gradle-Testmodul. Läuft **lokal** (`make sample-db-smoke`) *und* in CI (Workflow
  analog `bi-demo-smoke.yml`).
- **Platzierung = `examples/sample-db/`** (Geschwister von bi-demo), kein neuer
  Root. `test/` bleibt Gradle-Modulen vorbehalten.
- **Sourcing = On-Demand-Fetch**: ein Script lädt die **gepinnten**,
  **SHA256-verifizierten** Dumps in einen gitignored Cache (auch in
  `.dockerignore`). Kein Dump im Repo. Employees nur opt-in/nightly.

## Dialekt-/Spatial-Matrix (Abdeckungsziel)

d-migrate unterstützt drei Dialekte (postgresql, mysql, sqlite) und vier
Spatial-Profile (`postgis`, `native`, `spatialite`, `none`). Der Harness deckt
diese Matrix **schrittweise** ab — derselbe `examples/sample-db/`-Ordner, je Ziel
ein compose-Service (SQLite/Spatialite brauchen **keinen** — sie sind dateibasiert),
ein gepinnter Sample und eine eigene `expected/`-Baseline; `smoke.sh` parametrisiert
über Dialekt/Profil.

| Ziel | Sample (Kandidat) | Server | Phase | Status |
|---|---|---|---|---|
| PostgreSQL | Pagila | postgres | 1 | ✅ erledigt |
| MySQL | Sakila | mysql | 2 | ✅ erledigt (Sakila MySQL→PG + Pagila PG→MySQL beide grün) |
| SQLite | Chinook | — (Datei) | 2b | geplant |
| PostGIS | Spatial-Sample | PostGIS-Image | 5 | geplant |
| Spatialite | Spatial-Sample | — (`mod_spatialite` im CLI-Image) | 5 | geplant |

Jeder neue Dialekt deckt **eigene** Round-Trip-Defekte auf (wie PG → F1–F3,
[`sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md));
Breite kostet daher Fix-Arbeit. Bau-Reihenfolge/-Zeitpunkt steuert die Roadmap —
heute (2026-06-18) **nur dokumentiert**, Bau folgt.

## Slice-Grenze (DoD-Boundary)

**Dieser Slice = Phase 0–3 (inkl. 2b SQLite).** Phase 4 (TPC, LF 8.1/8.2) **und
Phase 5 (Spatial: PostGIS/Spatialite)** sind separate Folge-Slices
(Forward-Pointer). Fertig, wenn Phase 1+2(+2b) als CI-Smoke (und lokal) grün laufen
und Phase 3 (Scale) als opt-in/nightly verfügbar ist.

## Objekt-Scope der Assertion (in-scope vs. erwarteter Skip)

Pagila/Sakila enthalten Views/Funktionen/Trigger; Cross-Dialect-Round-Trips
erzeugen reihenweise **legitime** `E053`/`W`-Notes. „`schema compare` clean" ist
daher die falsche Erwartung.

- **In-scope (muss round-trippen):** Tabellen, Spalten/Typen, PK/FK/UNIQUE/CHECK,
  Indizes, Daten (Zeilenzahl + Stichprobe), Sequenzen.
- **Erwarteter Skip (mit Note):** Views/Funktionen/Trigger bei Cross-Dialect
  (`E053`), dialekt-spezifische Index-/Constraint-Grenzen (`W103`/`E056`/`W123`),
  Präfixlängen auf PK/Constraint (ADR 0012-Lücke).

→ **Kernarbeitspaket: ein gepinnter Expected-Result-Baseline je Sample-DB** (die
erwarteten Exit-Codes/Notes/Skips). **Weil der Harness lokal läuft, wird die
Baseline lokal ermittelt und gepinnt** — kein mehrrundiger CI-Zyklus.

## Scope-Skizze (Phasen)

- **Phase 0 — Sourcing + Mechanik. ✅ ERLEDIGT 2026-06-18**
  ([ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)).
- **Phase 1 — Smoke (Pagila/PG). ✅ ERLEDIGT 2026-06-18.** Harness-Ordner
  `examples/sample-db/` analog `examples/bi-demo/`: `docker-compose.yml` (postgres
  mit Quell- + Ziel-DB), `examples/sample-db/scripts/fetch-dumps.sh` (Pagila auf
  Commit-SHA gepinnt, SHA256-verifiziert → `.cache/`),
  `examples/sample-db/scripts/smoke.sh` (echtes CLI: Dump laden →
  reverse `--include-all` → validate (0 Errors) → generate `--split pre-post
  --deterministic` → Zielschema → `data transfer` → `schema compare` gegen die
  gepinnte Baseline `expected/`). `make sample-db-smoke` + CI-Workflow
  `sample-db-smoke.yml`. **Lokal zweifach grün (deterministisch); Baseline lokal
  gepinnt + je Diff-Klasse erklärt** (`expected/pagila-smoke.md`). Der Erstlauf hat
  echte Round-Trip-Defekte aufgedeckt → [`sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md).
- **Phase 2 — Compatibility (Cross-Dialect je DB). ✅ ERLEDIGT (2026-06-20).**
  mysql-Service (`mysql:8.4`) + Sakila-Pin (`jOOQ/sakila@e089a5b1`, SHA256) +
  PG `sakila_target`/MySQL `pagila_target` ergänzt. **Beide Flows deterministisch
  grün, je gegen eigene Quelle, je eigene gepinnte Baseline:**
  - **Sakila MySQL→PG** (`smoke-cross.sh`, `make sample-db-cross-smoke`):
    Parität 16/16, TINYINT(1)→boolean / ENUM→text / SET→text datenbelegt,
    Notes gepinnt (`expected/sakila-cross.*`). Finding **Y1** (YEAR-Wert-Korruption,
    `yearIsDateType`).
  - **Pagila PG→MySQL** (`smoke-cross-pg2my.sh`, `make sample-db-cross-smoke-pg2my`):
    Parität 22/22, boolean→TINYINT(1) / text[]→JSON / tsvector→text /
    timestamptz→DATETIME datenbelegt, Notes gepinnt (`expected/pagila-cross.*`).
    Finding **P2-pg2my** = datenbelegter Beweis von Partitions-Finding D (payment
    doppelt: 32098 vs 16049).
  Beide CI-Workflows (`sample-db-cross-smoke*.yml`). Findings in
  [`sample-db-phase2-findings.md`](sample-db-phase2-findings.md). *Kein* direkter
  Pagila↔Sakila-Vergleich. **Offen (Folge-Slices, nicht Phase-2-blockierend):**
  Y1-Fix, Partitions-Hierarchie (löst P2-pg2my).
- **Phase 2b — SQLite-Round-Trip (Chinook).** **Kein** Server — die CLI arbeitet
  gegen eine bind-gemountete `.db`-Datei. Sample: Chinook (klein, FK-reich). Deckt
  die SQLite-Eigenheiten ab (Named-Sequence `helper_table`, EXCLUDE blockiert,
  AUTOINCREMENT, schema-globaler Trigger-Namensraum). Eigene `expected/`-Baseline.
- **Phase 3 — Scale (Employees/MySQL).** Streaming/Chunking/Resume; **opt-in/nightly**.
  **Achtung:** es gibt heute keinen `schedule:`/`cron:`-Workflow — Phase 3 legt
  entweder einen scheduled Workflow an (eigenes Arbeitspaket) oder bleibt reines
  opt-in-`make`-Target, **nicht** im PR-Gate.
- **Phase 4 — Performance (TPC-H/-DS).** Eigener 1.0.0-QA-Folge-Slice
  (LF 8.1/8.2), nur Forward-Pointer.
- **Phase 5 — Spatial (PostGIS + Spatialite).** Eigener Folge-Slice (wie Phase 4,
  nicht in der Phase-0–3-Grenze). PostGIS = postgres-Superset-Image + Spatial-
  Sample; Spatialite = `mod_spatialite` im CLI-Image + Spatial-Sample. Testet
  `--spatial-profile postgis|spatialite` end-to-end (Geometrie-/Geographie-Typen,
  räumliche GiST/R-Tree-Indizes). Sample-Kandidat + Pinning noch offen → Vorarbeit
  im Kandidaten-Katalog [`../open/test-database-candidates.md`](../open/test-database-candidates.md).

## CI-Laufzeit-Budget

- **PR-Gate (Phase 1/2):** Schema-Smoke + Stichproben-Zeilenzahlen, Ziel < ~3 min
  Zusatzlast; Fetch über Actions-Cache (gekeyt auf Pin) → Download nur bei
  Cache-Miss.
- **Voller Daten-Transfer / Scale (Phase 3):** opt-in/nightly, nicht im PR-Gate.

## Vorbedingungen

- ADR 0014 (entschieden).
- Vorhanden: `examples/bi-demo/` als Harness-Muster, `make docker-build` (CLI-Image),
  docker compose, Pilot-Szenarien-Vorlage.
- **Nicht vorhanden (Phase 3):** ein scheduled/nightly CI-Workflow — ggf. zu bauen.

## Akzeptanzkriterien (je Phase)

**Phase 0:** ADR 0014 `accepted` (Sourcing + Mechanik + Platzierung festgeschrieben). ✅

**Phase 1 (Pagila Smoke):** `make sample-db-smoke` lädt Pagila in den Cache
(gepinnt, SHA256), fährt compose hoch, reverse/validate ohne offene Errors,
generate split, `schema compare` == Expected-Baseline (keine *unerklärten* Diffs),
Stichproben-Zeilenzahlen Quelle = Ziel; **läuft lokal *und* im CI-Workflow grün**.

**Phase 2 (Compatibility):** Pagila PG→MySQL und Sakila MySQL→PG je gegen eigene
Quelle abgenommen; erwartete E053/W-Notes gegen Baseline gepinnt;
TINYINT(1)↔BOOLEAN + Enum-Case datenbelegt.

**Phase 2b (SQLite):** Chinook gegen eine `.db`-Datei reverse/validate/generate;
SQLite-Eigenheiten (helper_table-Sequenzen, EXCLUDE-Block, AUTOINCREMENT) gegen
eigene Baseline gepinnt; Daten-Zeilenzahlen Quelle = Ziel.

**Phase 3 (Scale):** Employees-Transfer mit Resume nach simulierter Unterbrechung;
Chunking belegt; Gating (scheduled Workflow oder opt-in-Target) dokumentiert;
**nicht** im PR-Gate.

**Phase 5 (Spatial):** in eigenem Folge-Slice — `--spatial-profile postgis` und
`spatialite` end-to-end gegen je eigene Baseline; Geometrie-Typen + räumliche
Indizes datenbelegt.

**Übergreifend:** kein Dump im Repo (Cache gitignored + dockerignored);
`make docs-check` grün.

## Nicht-Ziel

- Ersatz der menschlichen ≥5-Tester-Pilotabnahme (LF 9.2) — bleibt separat.
- TPC-Benchmarks (Phase 4 = eigener Slice).
- Direkter Pagila↔Sakila-Schemavergleich (unterschiedliche Schemata).
- Testcontainers/Gradle-Testmodul (verworfen zugunsten compose/Scripts, ADR 0014).
