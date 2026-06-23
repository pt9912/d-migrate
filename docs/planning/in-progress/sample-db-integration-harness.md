# Plan: Sample-DB-E2E-Harness (docker-compose + Scripts)

> Dokumenttyp: Umsetzungsplan (Slice)
> Status: **Abschlussreif** (Stand 2026-06-21). **Phase 0/1/2/2b/3 erledigt** —
> die **gesamte Slice-Grenze (Phase 0–3) ist DoD-komplett**. Pagila/PG-Round-Trip,
> Sakila MySQL→PG + Pagila PG→MySQL cross-dialect, Chinook/SQLite-Round-Trip,
> **Employees-Scale (export-resume + Chunking + Dual-Target-Import MySQL+PG)** —
> je grün, je gepinnte Baseline. Spatial (Phase 5: PostGIS + MySQL native +
> Spatialite) + TPC (Phase 4) = eigene Folge-Slices. Phase-2-Folgebefund **Y1 behoben** (`c9401b6f`); Phase-3-
> Folgebefund **S1 behoben** (PK-Nullability-Preflight,
> [`../done/sample-db-phase3-findings.md`](../done/sample-db-phase3-findings.md));
> Harness-Review-Härtungen getrackt in
> [`../next/sample-db-harness-review-followups.md`](../next/sample-db-harness-review-followups.md).
> Sourcing **und** Mechanik via
> [ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)
> entschieden (supersedet ADR 0013). **Folge-Hygiene:** Slice nach `done/` heben
> (Link-Sweep README/Makefile/Workflows) — separater Schritt.
> Roadmap-Slot: Phase 1–2b (Smoke/Compatibility, inkl. SQLite) = Test-Infrastruktur;
> Phase 3 (Scale) = 1.0.0-QA. **Phase 4 (Performance/TPC, LF 8.1/8.2) und Phase 5
> (Spatial: PostGIS + MySQL native + Spatialite) = eigene Folge-Slices**.
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

| Ziel (Dialekt + Spatial-Profil) | Sample (Kandidat) | Server | Phase | Status |
|---|---|---|---|---|
| PostgreSQL (`none`) | Pagila | postgres | 1 | ✅ erledigt |
| MySQL (`none`) | Sakila | mysql | 2 | ✅ erledigt (Sakila MySQL→PG + Pagila PG→MySQL beide grün) |
| SQLite (`none`) | Chinook | — (Datei) | 2b | ✅ erledigt (Round-Trip grün, Parität 11/11) |
| PostgreSQL + **`postgis`** | Spatial-Sample | PostGIS-Image | 5 | geplant |
| MySQL + **`native`** (GEOMETRY/POINT/… + SRID) | Spatial-Sample | mysql | 5 | geplant |
| SQLite + **`spatialite`** | Spatial-Sample | — (`mod_spatialite` im CLI-Image) | 5 | geplant |

**Vollständigkeit:** Alle **drei** Dialekte haben ein Spatial-Profil (PG→`postgis`,
MySQL→`native`, SQLite→`spatialite`); die **DDL-Typ-Abbildung + Profil-Policy** sind
implementiert (`SpatialProfile.defaultFor`/`allowedFor`, `NeutralType.Geometry`).
**Aber** der Spatial-*Datenpfad* (Wert-Transfer), die *Spatial-Indizes* und das
*SRID-Reverse* sind **noch nicht** implementiert — siehe Slice
[`spatial-harness-slice.md`](../done/spatial-harness-slice.md) (VA1–VA5).
Phase 5 deckt daher **drei** Round-Trips **plus** Cross-Dialect-Spatial-Transfers
(z. B. PostGIS→MySQL native, MySQL native→Spatialite) ab — nach Implementierung
der Vorarbeitspakete, nicht nur durch Harness-Verkabelung.

Jeder neue Dialekt deckt **eigene** Round-Trip-Defekte auf (wie PG → F1–F3,
[`sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md));
Breite kostet daher Fix-Arbeit. Bau-Reihenfolge/-Zeitpunkt steuert die Roadmap —
heute (2026-06-18) **nur dokumentiert**, Bau folgt.

## Slice-Grenze (DoD-Boundary)

**Dieser Slice = Phase 0–3 (inkl. 2b SQLite). ✅ DoD-ERFÜLLT (2026-06-21).** Phase 4
(TPC, LF 8.1/8.2) **und Phase 5 (Spatial: PostGIS/Spatialite)** sind separate
Folge-Slices (Forward-Pointer). DoD: Phase 1+2(+2b) laufen als CI-Smoke (und lokal)
grün **und** Phase 3 (Scale) ist als opt-in/nightly verfügbar — beides erfüllt.

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
    `yearIsDateType`) **BEHOBEN `c9401b6f`** (YEAR round-trippt '2006', jetzt harte
    Assertion in `smoke-cross.sh` statt Note).
  - **Pagila PG→MySQL** (`smoke-cross-pg2my.sh`, `make sample-db-cross-smoke-pg2my`):
    Parität 22/22, boolean→TINYINT(1) / text[]→JSON / tsvector→text /
    timestamptz→DATETIME datenbelegt, Notes gepinnt (`expected/pagila-cross.*`).
    Finding **P2-pg2my** = datenbelegter Beweis von Partitions-Finding D (payment
    doppelt: 32098 vs 16049).
  Beide CI-Workflows (`sample-db-cross-smoke*.yml`). Findings in
  [`sample-db-phase2-findings.md`](../done/sample-db-phase2-findings.md). *Kein* direkter
  Pagila↔Sakila-Vergleich. **Offen (Folge-Slice, nicht Phase-2-blockierend):**
  Partitions-Hierarchie (löst P2-pg2my) →
  [`../in-progress/partition-hierarchy-reconstruction.md`](../in-progress/partition-hierarchy-reconstruction.md).
  (Y1-Fix **erledigt** `c9401b6f`.)
- **Phase 2b — SQLite-Round-Trip (Chinook). ✅ ERLEDIGT (2026-06-20).** **Kein**
  Server — die CLI arbeitet via `docker run` (Host-User) gegen eine bind-gemountete
  `.db`-Datei; `sqlite3` baut das Zielschema. Sample: Chinook
  (`lerocha/chinook-database@7f677725`, SHA256-gepinnt, 11 Tabellen, FK-reich).
  `smoke-sqlite.sh` + `make sample-db-sqlite-smoke` + CI. **Same-Dialect-Round-Trip
  deterministisch grün:** Parität 11/11, `Decimal(10,2)→REAL` ohne Datenverlust
  datenbelegt (Track.UnitPrice-Summe 3680.97), Notes gepinnt
  (`expected/chinook-sqlite.*`: W200×3 generate + R201 reverse, beide SQLite-Typ-
  Affinität, kein Defekt). **Keine** Fidelity-Findings — sauberer Round-Trip.
- **Phase 3 — Scale (Employees). ✅ ERLEDIGT (2026-06-21).** Employees-Dataset
  (`datacharmer/test_db@e324b561`, ~4 Mio Zeilen, 6 Basis-Tabellen, SHA256-gepinnt,
  nur `FETCH_EMPLOYEES=1`). Übt den **datei-basierten** `data export`→`import`-Pfad
  (der **einzige** mit `--resume`; der direkte `data transfer` hat nur `--chunk-size`):
  `smoke-scale.sh` lädt Employees → reverse/validate → `data export json --split-files
  --chunk-size 5000` mit **Mid-Stream-Interruption** (`docker kill` beim ersten
  persistierten Checkpoint) + `--resume <operationId>` → **ein** Bundle in **zwei**
  Ziele importieren (`employees_my_target` MySQL-Round-Trip **und** `employees_pg_target`
  PG-Cross-Dialect). **Deterministisch grün:** Resume vollendet alle 6 Tabellen-
  Dateien; Zeilen-Parität Quelle == Ziel == gepinnte Baseline je Ziel;
  `SUM(salary)=181480757419` round-trippt exakt. `make sample-db-scale-smoke` +
  scheduled Workflow `sample-db-scale.yml` (`workflow_dispatch` + nightly `cron`,
  **kein** push/PR-Trigger → **nicht** im PR-Gate). Aufgedeckter + behobener Defekt
  **S1** (PK-Nullability-Preflight: `ImportTableValidator` rechnet jetzt
  `required || in primaryKey`, Regressionstest) →
  [`../done/sample-db-phase3-findings.md`](../done/sample-db-phase3-findings.md).
  Scope: nur Daten (Tabellen+PK via pre-data); FKs/Views (post-data) = Phase-2-Domäne.
- **Phase 4 — Performance (TPC-H/-DS).** Eigener 1.0.0-QA-Folge-Slice (LF 8.1/8.2)
  → **geschnitten** in [`../next/tpc-performance-slice.md`](../next/tpc-performance-slice.md)
  (Sourcing/Workload/Methodik dort; LF-8.1-Verlustfreiheit durch Phase 3
  **plausibilisiert**, gemessene Abnahme — inkl. LF-8.2-Zeitbudgets — offen;
  TPC = realistische Workload).
- **Phase 5 — Spatial (PostGIS + MySQL native + Spatialite).** Eigener Folge-Slice
  → **geschnitten** in [`spatial-harness-slice.md`](../done/spatial-harness-slice.md)
  (3 Profile + Cross-Dialect; externes gepinntes Spatial-Sample; Spatialite-Vorarbeit).
  (wie Phase 4, nicht in der Phase-0–3-Grenze). Deckt **alle drei** Spatial-Profile
  end-to-end ab — eines je Dialekt:
  - **`postgis`** (PostgreSQL) = postgres-Superset-Image (`postgis/postgis`) + Spatial-Sample.
  - **`native`** (MySQL) = mysql-Service (Spatial ist eingebaut, **keine** Extension
    nötig); `GEOMETRY/POINT/POLYGON/…` + `SRID` (MySQL 8.0+). DDL-Typ-Abbildung im
    Code (`MysqlTypeMapping`/`MysqlColumnConstraintHelper`); Wert-Transfer +
    SPATIAL-Index + SRID-Reverse noch offen (Slice-VA1–VA5).
  - **`spatialite`** (SQLite) = `mod_spatialite` im CLI-Image + Spatial-Sample.
  Testet `--spatial-profile postgis|native|spatialite` end-to-end (Geometrie-/
  Geographie-Typen, räumliche GiST/R-Tree-/SpatiaLite-Indizes) — **plus
  Cross-Dialect-Spatial** (z. B. PostGIS→MySQL native, MySQL native→Spatialite).
  **Achtung Spatialite:** das CLI-Runtime-Image (eclipse-temurin) enthält heute
  **kein** `mod_spatialite` → eigenes Vorarbeitspaket (Image-Erweiterung +
  Extension-Loading im sqlite-Treiber). Sample-Kandidat + Pinning noch offen →
  Vorarbeit im Kandidaten-Katalog [`../open/test-database-candidates.md`](../open/test-database-candidates.md).

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

**Phase 3 (Scale): ✅** Employees export→import mit **Resume nach simulierter
Unterbrechung** (Mid-Stream-`docker kill` + `--resume`); **Chunking belegt**
(`--chunk-size 5000` gegen 2,84 Mio salaries); **Gating: beides** — opt-in
`make sample-db-scale-smoke` **und** scheduled Workflow `sample-db-scale.yml`
(nightly `cron` + `workflow_dispatch`), **nicht** im PR-Gate. Dual-Target-Parität
(MySQL+PG) + `SUM(salary)`-Checksumme datenbelegt.

**Phase 5 (Spatial):** in eigenem Folge-Slice — `--spatial-profile postgis`,
**`native` (MySQL)** und `spatialite` end-to-end gegen je eigene Baseline (alle
drei Dialekt-Spatial-Pfade) + mindestens ein Cross-Dialect-Spatial-Transfer;
Geometrie-Typen (inkl. SRID) + räumliche Indizes datenbelegt.

**Übergreifend:** kein Dump im Repo (Cache gitignored + dockerignored);
`make docs-check` grün.

## Nicht-Ziel

- Ersatz der menschlichen ≥5-Tester-Pilotabnahme (LF 9.2) — bleibt separat.
- TPC-Benchmarks (Phase 4 = eigener Slice).
- Direkter Pagila↔Sakila-Schemavergleich (unterschiedliche Schemata).
- Testcontainers/Gradle-Testmodul (verworfen zugunsten compose/Scripts, ADR 0014).
