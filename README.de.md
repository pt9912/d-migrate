# d-migrate

**Datenbankunabhängiges Werkzeug für Schema-Migration und Datenmanagement.**

**Deutsch** | [English](README.md)

![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

d-migrate ist ein datenbankunabhängiges Werkzeug für Schema-Migration
und Datenmanagement — bedienbar als CLI **und** als MCP-Server
(`mcp serve --transport stdio|http`, MCP 2025-11-25). Du definierst
dein Schema einmalig in einem neutralen YAML-Format und kannst es
gegen PostgreSQL, MySQL und SQLite validieren, vergleichen, als DDL
generieren und als Live-Diff-Migration ausführen. Darüber hinaus
unterstützt d-migrate Reverse-Engineering bestehender Datenbanken,
streaming-basierten Daten-Export/-Import/-Transfer zwischen Datenbanken
und den Export in bestehende Migrations-Toolchains (Flyway,
Liquibase, Django, Knex).

## Für wen ist es?

d-migrate adressiert Datenbankadministratoren, Plattform-Engineers,
Datenteams und Integratoren, die:

- ein **dialekt-agnostisches** Schema-Artefakt brauchen
  (PostgreSQL / MySQL / SQLite aus derselben YAML-Quelle)
- **reproduzierbare, signierte Migrationspläne** mit expliziten
  Rollback-Verträgen, Drift-Checks und Per-Statement-Metadaten
  wollen
- Schema- und Datenoperationen gegen bestehende Datenbanken
  ausführen — Reverse-Engineering, Vergleich, Transfer und
  inkrementeller Export — **ohne sich an ein einziges
  Hersteller-Toolchain zu binden**
- einen **KI-Agenten-aufrufbaren MCP-Server** für read-only
  Schema-Discovery (validate / compare / generate / reverse) plus
  policy-gated Job-Worker für Datenimport / -transfer / -profiling
  brauchen

Es ist (noch) keine ETL-Plattform, keine Streaming-CDC-Pipeline und
kein Ersatz für handgetunte dialekt-spezifische Migrationen — aber
es deckt die Schema- und Daten-Arbeit ab, die über diese Stacks
hinweg gemeinsam ist.

## Was kann ich heute laufen lassen?

d-migrate ist ein produktiv nutzbares Werkzeug in Version
**0.9.8** (stabil, veröffentlicht 2026-06-14).

> **In Entwicklung (`develop`, 0.9.9):** Eine mehrrundige
> End-to-End-Pilot-Validierung (PostgreSQL / MySQL / SQLite gegen
> Pagila/Sakila) hat die Cross-Dialect-Daten- und DDL-Treue
> gehärtet — alle gemeldeten P1/P2-Cross-Dialect-Blocker sind
> behoben (Transfer-Preflight strukturell aus der Ziel-Typ-Abbildung,
> Array-/`tsvector`-Wertbindung, `CURRENT_DATE`-Defaults,
> View-Portabilität, Routinen-Emission u. a.). Siehe `CHANGELOG.md`.

Die aktuellen Fähigkeiten:

- **Schema-Modell**: neutrales YAML-Schema mit 19 Typen +
  Spatial-Geometry; Validator mit 35+ Fehlercodes.
- **Schema-Operationen**: `validate`, `generate`, `compare`,
  `reverse`, `migrate`, `rollback` für PostgreSQL, MySQL, SQLite —
  file/file, file/db, db/db.
- **Diff-Migrationen**: Tabellen, Spalten, Indizes, Constraints
  inkl. CHECK/EXCLUDE mit Live-Data-Preflight, Foreign Keys,
  Sequenzen, Views, Materialized Views (PG), Trigger,
  Functions/Procedures; signiertes `migration-plan.v1`-Artefakt
  via `--plan-artefact`.
- **Renames** für Tabellen, Spalten, Views, Trigger, Functions,
  Procedures, Sequenzen — natives `RENAME`-DDL oder
  Drop+Create-Fallback je nach Dialekt; CLI-Shortcuts
  (`--rename-table`, `--rename-column`) oder File-Overlay
  (`--migration-overlay`).
- **Sequenz-Pipeline**: MySQL-Helper-Table-Emulation
  (`dmg_sequences`) mit Live-Drift-Check; opt-in
  `preserveCurrentValue` für PG / MySQL / SQLite — Probe + Restore
  in einer einzigen Transaktion unter Per-Dialekt-Lock seit 0.9.7
  (`pg_advisory_xact_lock` / `SELECT FOR UPDATE` /
  `BEGIN IMMEDIATE`); SQLite-Sequence-Emulation via
  `--sqlite-named-sequences helper_table`.
- **Spatial-DDL**: PostGIS, MySQL native, SpatiaLite
  (`--spatial-profile`); View-Query-Transformation über Dialekte
  hinweg.
- **Daten-Operationen**: streaming `data export` / `import` /
  `transfer` (JSON / YAML / CSV / Parquet) mit benannten
  Verbindungen, UPSERT, Truncate, Trigger-Handling, Reseeding,
  inkrementellem Export (`--since-column` / `--since`);
  `data profile` für Datenstatistiken.
- **Parquet & Object-Storage** (0.9.8): `data export` / `import
  --format parquet` für Bundle- (Multi-Table + `manifest.yaml`) und
  Single-File-Layout (Footer-KV) mit Checkpoint/Resume und
  `--table-order`; S3-kompatibler `ArtifactStore`
  (`artifacts.store: s3`, AWS SDK v2) für Server-Mode-Artefakte.
- **Integrationen**: `d-migrate export flyway|liquibase|django|knex`.
- **MCP-Server** (`mcp serve --transport stdio|http`, MCP
  2025-11-25): read-only Tool-Oberfläche (`schema_validate`,
  `schema_compare`, `schema_generate`, `schema_reverse_start`)
  plus policy-gated Job-Worker (`data_import_start`,
  `data_transfer_start`, `data_profile_start`,
  `procedure_transform_*`, `testdata_*`) mit Idempotency und
  JDBC-Backed-State; Auth via JWT-JWKS / RFC-7662-Introspection /
  stdio-Token-Registry.
- **CLI-UX**: i18n EN/DE mit ICU4J, explizite
  Zeitzonen-/Temporal-Policy, CSV-/BOM-Encoding-Vertrag,
  phasenbezogene DDL via `--split pre-post`.
- **OCI-Image** auf `ghcr.io/pt9912/d-migrate:<version>` und
  `:latest`.

Der einfachste Weg, das Tool auszuprobieren, ist das veröffentlichte
OCI-Image:

```bash
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema validate --source /work/schema.yaml
```

Siehe [Schnellstart](#schnellstart) weiter unten für mehr konkrete
Rezepte.

## Was macht es vertrauenswürdig?

- **≥ 90 % Line-Coverage pro Modul**, durchgesetzt von Kover
  (`minBound(90)` in der `build.gradle.kts` jedes Moduls). Der
  CI-Build bricht ab, wenn ein Modul darunter fällt.
- **Doc-Check-Gate**: jedes Markdown-Link-Ziel in
  [`docs/`](docs/), [`spec/`](spec/), [`README.md`](README.md) und
  [`CHANGELOG.md`](CHANGELOG.md) wird in jedem CI-Lauf gegen das
  Dateisystem geprüft via
  [d-check](https://github.com/pt9912/d-check) (digest-gepinntes
  Container-Image, Konfiguration in [`.d-check.yml`](.d-check.yml));
  kaputte interne Links und Anker brechen den Build.
- **Static-Analysis-Gate**: Detekt plus ein
  SOLID-Suppression-Gate
  ([`scripts/solid-suppression-gate.sh`](scripts/solid-suppression-gate.sh))
  — `@Suppress("LargeClass")` und Verwandte werden in einem
  Ledger geführt und erfordern strukturelle Fixes, keine
  Inline-Waivers.
- **Cross-Dialekt-Testmatrix**:
  [`test/cross-dialect-matrix`](test/cross-dialect-matrix) sweept
  jede Workstream × Dialekt × Test-Kind-Zelle, mit einer
  Carve-Out-Registry (`carve-outs.yaml`), die für jede
  nicht-gepinnte Zelle Reason + Plan-Doc-Referenz fordert; stille
  Carve-Outs werden beim Laden abgelehnt.
- **Live-DB-Integrationstests** gegen Testcontainers PostgreSQL
  16, MySQL 8 und file-backed SQLite — jede Diff-, Rename-,
  Sequence- und Atomic-Preserve-Pipeline läuft gegen echte Engines
  via
  [`scripts/test-integration-docker.sh`](scripts/test-integration-docker.sh).
- **Reproduzierbare Builds**: `--deterministic` plus
  `SOURCE_DATE_EPOCH` erzeugen byte-identische DDL über
  Timestamps und OS-Umgebungen hinweg.
- **Signierte Migrationspläne**: `--plan-artefact` schreibt ein
  kanonisches, signiertes `migration-plan.v1`-JSON mit stabilen
  Fingerprints, Statement-IDs und Rollback-Metadaten; manipulierte
  Artefakte werden vom `MigrationPlanArtifactValidator` abgelehnt.
- **Hexagonale Architektur**: pure-Domain
  [`hexagon:core`](hexagon/core/) plus
  `hexagon:ports-{common,read,write,execute}` mit Driving-Adaptern
  (CLI, MCP) und Driven-Adaptern (Treiber, Formate, Persistenz),
  isoliert durch explizite Interfaces. Architekturentscheidungen
  leben als ADRs in [`docs/adr/`](docs/adr/).
- **CI spiegelt local**: jedes Gate, das `make ci` lokal ausführt,
  läuft bei jedem Push auch in GitHub Actions
  ([`.github/workflows/build.yml`](.github/workflows/build.yml)).

## Status

Die vollständige Release-History (0.1.0–0.9.7) steht in
[`CHANGELOG.md`](CHANGELOG.md). Aktuelles und kommende Milestones:

- **0.9.8 Analytics- und Storage-Anschluss (Parquet Cut A + S3-
  ArtifactStore + BI-Demo)** · `Released` (2026-06-14): produktiver
  Parquet `data export` / `import` (Bundle + Single-File,
  Checkpoint/Resume, `--table-order`, Exit-Code-Vertrag);
  S3-kompatibler `ArtifactStore` (AWS SDK v2 +
  `url-connection-client`, `artifacts.store: s3`); BI-Demo-Compose-
  Stack (Postgres + Metabase + SeaweedFS). Alle Closure-Plan-Docs in
  [`docs/planning/done/`](docs/planning/done/).
- **0.9.9 Dokumentation + Pilot-Validierung** · `Geplant`.
- **1.0.0 Stable Release** · `Geplant`.

Für Per-Milestone-Tasktabellen und ADR-Verweise siehe die
kanonische Roadmap unter
[`docs/planning/in-progress/roadmap.md`](docs/planning/in-progress/roadmap.md).
ADRs leben unter [`docs/adr/`](docs/adr/) (4 Accepted Stand
2026-06-02; Index in [`docs/adr/README.md`](docs/adr/README.md)).

Alle Releases und Details:
[`CHANGELOG.md`](CHANGELOG.md) |
[GitHub Releases](https://github.com/pt9912/d-migrate/releases).

## Build, Test, Lint

Einzelne Gates für schnelle Feedback-Schleifen:

```bash
make help              # alle verfügbaren Targets anzeigen
make ci                # Docker-CI-Build + docs-check (volles lokales Gate)
make docker-build      # Runtime-Image bauen
make docker-check      # Gradle check in der Dockerfile-Build-Stage
make docker-test       # Gradle test in der Dockerfile-Build-Stage
make docker-detekt     # Detekt-Statische-Analyse
make docker-coverage-gate  # Kover ≥ 90 % pro Modul
make docs-check        # Markdown-Link-Ziele + Kover-Excludes-Ledger prüfen
make integration       # Testcontainers-Integrationssuite
make release-assets    # ZIP, TAR, Fat JAR, SHA256 Release-Assets bauen
```

Gezielte Modul-Läufe:

```bash
make docker-check MODULES=":hexagon:core :adapters:driven:driver-postgresql"
make docker-test  MODULES=":adapters:driving:mcp"
```

## Schnellstart

### Voraussetzungen

- Docker
- Optional für lokale Entwicklung ohne Container: **JDK 21** oder
  neuer

### Veröffentlichtes OCI-Image nutzen

Kein lokales JDK erforderlich — Image ziehen und ausführen:

```bash
# Validierung
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema validate --source /work/schema.yaml

# Compare (file/file)
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# DDL generieren
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema generate --source /work/schema.yaml --target postgresql

# Reverse-Engineering
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  --config /work/.d-migrate.yaml schema reverse --source mydb --output /work/reverse.yaml

# DB-zu-DB-Datentransfer
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  data transfer --source sourcedb --target targetdb --tables users,orders
```

### GitHub Release Assets

Veröffentlichte Releases liefern ZIP, TAR und ein Fat JAR auf der
[Releases-Seite](https://github.com/pt9912/d-migrate/releases).

```bash
# Launcher-basierte Distribution
tar -xf d-migrate-<version>.tar
./d-migrate-<version>/bin/d-migrate --help

# Oder Fat JAR direkt ausführen
java -jar d-migrate-<version>-all.jar --help
```

Hinweis: Die Homebrew-Formula wird ab 0.5.0 im Repository
mitgeführt und pro Release über
[`.github/workflows/verify-homebrew-formula.yml`](.github/workflows/verify-homebrew-formula.yml)
verifiziert.

### Aus Quellcode bauen

```bash
make ci-build
```

### Minimales Schema-Beispiel

Lege eine Datei `schema.yaml` an:

```yaml
schema_format: "1.0"
name: "My App"
version: "1.0.0"

tables:
  users:
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: text
        max_length: 254
        required: true
        unique: true
      created_at:
        type: datetime
        default: current_timestamp
    primary_key: [id]
```

Dann validierst du es so:

```bash
make docker-build
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml
```

Und vergleichst zwei Versionen so:

```bash
docker run --rm -v $(pwd):/work d-migrate:dev \
  schema compare --source /work/schema.yaml --target /work/schema-v2.yaml
```

### Lokal bauen und testen mit dem Dockerfile

Das Repository liefert ein Multi-Stage-[`Dockerfile`](Dockerfile),
das das Projekt im Container baut und testet und danach die
CLI-Distribution in ein schlankes JRE-Laufzeitimage verpackt. Das
ist der einfachste Weg, den vollständigen Build ohne lokale
JDK-Installation auszuführen.

<details>
<summary>Dockerfile-Stages — Überblick</summary>

- **`deps`**: Gradle-Dependency-Pre-Warm.
- **`build`**: Build, Tests, Coverage-Gate, Distribution. Mit
  `--target build --build-arg GRADLE_TASKS="..."` auf eine
  bestimmte Task-Liste einschränken.
- **`detekt`**: Detekt-Statische-Analyse.
- **`coverage`**: aggregierter Kover-HTML-Report auf Port 8080.
  `docker build --target coverage -t d-migrate:coverage .` +
  `docker run --rm -p 8080:8080 d-migrate:coverage`.
- **`coverage-json`**: Kover-JSON nach stdout via `ENTRYPOINT`.
- **`coverage-verify`**: hartes `koverVerify` (≥ 90 % pro Modul).
- **`release-assets`**: ZIP / TAR / Fat JAR / SHA256 (Target von
  `make release-assets`).
- **`jib-image-tar`**: Jib-OCI-Image als Tar (Target von
  `make docker-oci-build`).
- **`runtime`** (Default): schlankes
  `eclipse-temurin:21-jre-noble`-Runtime-Image.

</details>

<details>
<summary>Häufige Dockerfile-Rezepte</summary>

```bash
# Vollständiger Build inkl. Tests und Coverage-Validierung
docker build -t d-migrate:dev .

# Erzwungener vollständiger Test/Coverage-Lauf (Docker-Layer-Cache UND Gradle-Cache werden umgangen)
docker build --no-cache --progress=plain \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Tests überspringen — nur CLI-Distribution bauen
docker build --build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist" \
  -t d-migrate:dev .

# Nur Teil der Build-Stage laufen lassen, ohne finales Runtime-Image
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
  -t d-migrate:phase-a .

# Lokal gebaute CLI ausführen
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Testcontainers-Integrationssuite ausführen
./scripts/test-integration-docker.sh

# Oder Teilmenge der Integrationstests
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
```

</details>

## Unterstützte Datenbanken

| Datenbank  | Status                                                              |
| ---------- | ------------------------------------------------------------------- |
| PostgreSQL | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| MySQL      | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| SQLite     | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| Oracle     | Geplant                                                             |
| MSSQL      | Geplant                                                             |

## Projektstruktur

```text
.
├── .github/workflows/             ← GitHub Actions: build + release-homebrew + verify-homebrew-formula
├── CHANGELOG.md
├── Dockerfile                     ← Multi-Stage (deps, build, detekt, coverage, runtime, release-assets, jib-image-tar)
├── Makefile                       ← Build-/Test-Gates pro Dockerfile-Stage
├── README.md                      ← Englische Hauptversion
├── README.de.md                   ← Deutsche Version (dieses Dokument)
├── build.gradle.kts               ← Root-Build-Config + Modul-Aggregation
├── settings.gradle.kts            ← Gradle-Multi-Modul-Deklaration
├── gradle.properties              ← gepinnte Dependency-Versionen
├── hexagon/                       ← Pure Domain + Ports (keine Treiber-Dependencies)
│   ├── core/                      ← Neutrales Schema-Modell, Diff-Kern, Validatoren
│   ├── ports-common/              ← Übergreifende Port-Verträge
│   ├── ports-read/                ← Read-Seite-Ports (DDL-Generierung, Reverse, Capabilities)
│   ├── ports-write/               ← Write-Seite-Ports (Datenimport / -transfer)
│   ├── ports-execute/             ← Atomare-Execute-Ports (Preserve-Lock-Verträge)
│   ├── ports/                     ← Driver-Registry-Port (DatabaseDriver, DatabaseDriverRegistry, PreGenerationValidator) + Facade-Re-Export von ports-{common,read,write,execute}
│   ├── application/               ← Use-Case-Orchestrierung + Stage-Pipelines
│   └── profiling/                 ← Perf-Measurement-Infrastruktur
├── adapters/
│   ├── driven/                    ← Outbound: driver-postgresql/-mysql/-sqlite (+ -profiling),
│   │                                formats, integrations (Flyway/Liquibase/Django/Knex),
│   │                                persistence-jdbc, storage-file, streaming, text-icu,
│   │                                audit-logging, connection-config
│   └── driving/                   ← Inbound: cli, mcp
├── test/
│   ├── cross-dialect-matrix/      ← Workstream × Dialekt × Kind-Sweep + Carve-Out-Registry
│   ├── integration-postgresql/    ← Testcontainers-PG-Live-DB-Tests
│   ├── integration-mysql/         ← Testcontainers-MySQL-Live-DB-Tests
│   ├── integration-sqlite/        ← file-backed SQLite-Live-DB-Tests
│   ├── integration-concurrency/   ← Race-Condition-Reproducer (Sequence-Preserve, Atomic-Locks)
│   ├── integration-persistence-jdbc/ ← JDBC-Store + Migration-Runner-ITs
│   ├── integration-server-state/  ← MCP-Server-State-Machine-ITs
│   ├── e2e-cli/                   ← End-to-End-CLI + MCP-Harness-Szenarien
│   └── perf-large-schema/         ← N = 100 / 1000 / 10000 Perf-Skalen
├── scripts/                       ← verify-doc-refs.sh, solid-suppression-gate.sh,
│                                    test-integration-docker.sh, Kover-Utilities
├── packaging/homebrew/            ← Homebrew-Formula (d-migrate.rb)
├── spec/                          ← normative Spezifikationen (deutsch): lastenheft, architecture,
│                                    design, cli-spec, neutral-model-spec,
│                                    ddl-generation-rules, mcp-server, schema-reference,
│                                    connection-config-spec
└── docs/
    ├── adr/                       ← Architecture Decision Records (4 Accepted)
    ├── planning/
    │   ├── open/                  ← Trigger-Watch + offene Follow-ups
    │   ├── next/                  ← geplant aber noch nicht aktiv
    │   ├── in-progress/           ← aktive Roadmap + Slice-Pläne
    │   └── done/                  ← abgeschlossene Slices + Closure-Notizen
    └── user/                      ← Nutzer-/Operator-orientiert (guide.md, releasing.md)
```

**Hinweis:** Die verlinkten ADRs, Slice-Pläne und Planungsdokumente
unter [`docs/`](docs/) und [`spec/`](spec/) sind auf Deutsch. Die
englische [`README.md`](README.md) spiegelt Struktur und Kernfakten;
für Detail-Inhalte bleiben die deutschen Quelldokumente
maßgeblich.

## Dokumentation

Detaillierte Dokumentation findest du in [`docs/`](docs/) und
[`spec/`](spec/):

- [Dokumentationsübersicht](docs/user/README.md)
  - [Anwenderhandbuch](docs/user/anwenderhandbuch.md)
  - [Administrationshandbuch](docs/user/administrationshandbuch.md)
  - [Migrations-Leitfaden](docs/user/migrations-leitfaden.md)
  - [API-Referenz (CLI + MCP)](docs/user/api-referenz.md)
- [Quick Start Guide (Deutsch)](docs/user/guide.md)
- [Entwurf](spec/design.md) / [Architektur](spec/architecture.md)
- [Schema-YAML-Referenz](spec/schema-reference.md)
- [Neutrale Modell-Spezifikation](spec/neutral-model-spec.md)
- [CLI-Spezifikation](spec/cli-spec.md)
- [MCP-Server](spec/mcp-server.md)
- [DDL-Generierungsregeln](spec/ddl-generation-rules.md)
- [Verbindungs- und Konfigurationsspezifikation](spec/connection-config-spec.md)
- [Roadmap](docs/planning/in-progress/roadmap.md)
- [Release-Leitfaden](docs/user/releasing.md)
- [Lastenheft](spec/lastenheft-d-migrate.md)

## Mitmachen

Beiträge sind willkommen! Bitte öffne ein Issue oder einen Pull
Request auf [GitHub](https://github.com/pt9912/d-migrate).

1. Forke das Repository
2. Erstelle einen Feature-Branch von `develop`
3. Schreibe Tests für deine Änderungen (≥ 90 % Kover-Gate pro
   Modul gilt)
4. Stelle sicher, dass die Docker-CI-Gates grün sind (`make ci`)
5. Reiche einen Pull Request gegen `develop` ein

## Lizenz

Dieses Projekt steht unter der [MIT-Lizenz](LICENSE).
