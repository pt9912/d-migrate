# d-migrate

**Datenbankunabhängiges CLI-Tool für Schema-Migration und Datenmanagement.**

<!-- Badges -->
![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

---

## Was ist d-migrate?

d-migrate ist ein Kommandozeilenwerkzeug für datenbankunabhängige Schema-Migration und Datenmanagement. Du definierst dein Schema einmalig in einem neutralen Format (YAML) und kannst es für PostgreSQL, MySQL und SQLite validieren, vergleichen und als DDL generieren. Darüber hinaus unterstützt d-migrate Reverse-Engineering bestehender Datenbanken, streaming-basierten Datenexport/-import/-transfer zwischen Datenbanken sowie die Integration in bestehende Migrations-Toolchains (Flyway, Liquibase, Django, Knex).

**Aktuelle Fähigkeiten:**
- Phasenbezogene DDL-Ausgabe mit `--split pre-post` fuer importfreundliche Schema-Artefakte (pre-data/post-data)
- Neutrales Schemamodell mit 18 integrierten Typen plus Spatial Geometry
- YAML-basierte Schemadefinition und -parsing
- Schemagültigkeitsprüfung mit 18+ Fehlercodes (E001-E018, E120/E121)
- Schema-Vergleich mit `schema compare` (file/file, file/db, db/db)
- Reverse-Engineering bestehender Datenbanken mit `schema reverse` (PostgreSQL, MySQL, SQLite)
- DDL-Generierung für PostgreSQL, MySQL und SQLite
- Spatial-DDL: PostGIS, MySQL native, SpatiaLite (`--spatial-profile`)
- Transformation von View-Queries (17 SQL-Funktionen)
- Transformationsberichte (YAML-Seitenschatten)
- Streaming-Datenexport (JSON, YAML, CSV) mit benannten Verbindungen
- Transaktionaler Datenimport mit UPSERT, Truncate, Trigger-Handling und Reseeding
- Direkter DB-zu-DB-Datentransfer mit `data transfer`
- Inkrementeller Export über `--since-column` / `--since`
- Line-orientierte Fortschrittsanzeige für `data export`, `data import` und `data transfer`
- CLI mit `schema validate`, `schema generate`, `schema compare`, `schema reverse`, `schema migrate`, `schema rollback`, `data export`, `data import`, `data transfer` und `data profile`
- Diff-basierte Migrationen mit `schema migrate` und `schema rollback`: Plan → Render → Execute → Post-Compare → optionales Rollback-Artefakt; signiertes `migration-plan.v1`-Artefakt per `--plan-artefact`
- Live-DB-Probes für MySQL-Sequence-Migrationen: Drift-Check der `dmg_sequences`-Helper-Table-Emulation gegen die kanonische Form (`E124_MYSQL_SEQUENCE_DRIFT_*` Diagnostik) und opt-in `preserveCurrentValue` auf Sequence-Definitionen, das den runtime-Wert (PG `last_value`, MySQL/SQLite `dmg_sequences.next_value`) über `CREATE`/`ALTER`/`RENAME SEQUENCE`-Migrationen rettet (SQLite seit 0.9.7-E.3-Folge-Slice, opt-in via `--sqlite-named-sequences helper_table`; ohne Opt-in `SEQUENCE_PRESERVE_OPT_IN_REQUIRED`)
- Volle Reverse-Read-Treue für SQLite-Trigger (token-basierter Parser für `sqlite_master.sql`): WHEN-Klausel, INSTEAD OF, FOR EACH ROW und Multi-Statement-Body werden korrekt extrahiert; Schema-qualifizierte Namen werden mit `R212`-ACTION_REQUIRED ausgeschlossen, `UPDATE OF cols` mit `R213`-WARNING gemeldet. File-zu-DB- und DB-zu-DB-Diffs gegen SQLite-Trigger emittieren keine spurious Replaces mehr.
- MySQL-Routinen-Identity-Reverse-Read: `SQL SECURITY DEFINER/INVOKER`, `DEFINER`-Spec und `sql_mode`-Snapshot werden jetzt aus `information_schema.routines` projiziert; file-zu-DB-Diffs gegen MySQL-Functions/Procedures mit expliziten Identity-Attributen produzieren keine spurious-Replace-Diagnose mehr.
- MCP-Server (`d-migrate mcp serve --transport stdio|http`) per MCP 2025-11-25 mit Transport, Auth (JWT-JWKS, JWT-Introspection, stdio-Token-Registry), Discovery (`tools/list`, `resources/list`, `resources/templates/list`), JSON-Schema-Vertrag und produktiven MCP-Tool-Handlern inklusive kontrollierter Start-Tools
- Internationalisierte CLI-Ausgabe (EN/DE) mit ResourceBundle-Fallback, ICU4J-Unicode-Utilities, expliziter Zeitzonen-/Temporal-Policy und konsolidiertem CSV-/BOM-Encoding-Vertrag
- OCI-Image für die Nutzung mit Docker

## Schnellstart

### Voraussetzungen

- Docker
- Optional fuer lokale Entwicklung ohne Container: **JDK 21** oder neuer

### Installation

#### GitHub Release Assets

Für veröffentlichte Releases stehen ZIP, TAR und Fat JAR auf der
[Releases-Seite](https://github.com/pt9912/d-migrate/releases) bereit.

```bash
# Launcher-basierte Distribution entpacken
tar -xf d-migrate-<version>.tar
./d-migrate-<version>/bin/d-migrate --help

# Alternativ das Fat JAR direkt ausführen
java -jar d-migrate-<version>-all.jar --help
```

Hinweis: Die Homebrew-Formula wird in 0.5.0 im Repository mitgeführt, ist aber
noch kein vollautomatischer Standard-Installationspfad.

#### Aus Quellcode bauen

```bash
make ci-build
```

#### Makefile-Komfortziele

Das Top-Level-[`Makefile`](Makefile) ist ein duenner Wrapper um die
kanonischen Gradle-, Docker- und Script-Einstiegspunkte. Die verfuegbaren
Kurzbefehle zeigt:

```bash
make help
```

Haeufige Ziele:

```bash
make ci-build             # Build/Test/Coverage-Gate in der Dockerfile-Build-Stage
make docker-resolve-deps  # Gradle-Dependencies in der Dockerfile-Deps-Stage vorwaermen
make docker-check         # Gradle check in der Dockerfile-Build-Stage
make docker-test          # Gradle test in der Dockerfile-Build-Stage
make docker-detekt        # Detekt in der Dockerfile-Detekt-Stage
make docker-coverage-gate  # Kover-Gate in der Dockerfile-Coverage-Stage
make gates             # Docker-Check, Docker-Coverage-Gate und docs-check
make ci                # Docker-CI-Build plus docs-check
make docker-smoke      # Docker-Runtime-Image bauen und --version/--help pruefen
make integration       # Testcontainers-Integrationstests via Docker-Script
make docs-check        # Markdown-Linkziele in docs/ pruefen
make docker-gates      # Docker-Runtime-Build, Coverage-Gate und Runtime-Smoke
make docker-full-gates # docker-gates plus Docker-Integrationstests
make release-assets    # ZIP, TAR, Fat JAR und SHA256 via Dockerfile bauen
make docker-oci-build  # Jib-OCI-Image via Dockerfile bauen und docker load ausfuehren
```

#### Release-Assets lokal bauen

```bash
make release-assets
ls -1 adapters/driving/cli/build/release
```

### CLI ausführen

```bash
# Einmal lokal bauen
make docker-build

# Schema validieren
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Zwei Schemas vergleichen
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source /work/schema.yaml --target /work/schema-new.yaml

# PostgreSQL-DDL generieren
docker run --rm -v $(pwd):/work d-migrate:dev schema generate --source /work/schema.yaml --target postgresql

# MySQL-DDL mit Rollback generieren
docker run --rm -v $(pwd):/work d-migrate:dev schema generate --source /work/schema.yaml --target mysql --generate-rollback

# Schema aus bestehender Datenbank extrahieren
docker run --rm -v $(pwd):/work d-migrate:dev schema reverse --source mydb --output /work/reverse.yaml --report /work/reverse.report.yaml

# DB-basierter Schema-Vergleich
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source file:/work/schema.yaml --target db:mydb

# DB-zu-DB Datentransfer
docker run --rm -v $(pwd):/work d-migrate:dev data transfer --source sourcedb --target targetdb --tables users,orders
```

### Docker

#### Veröffentlichtes Image nutzen

Kein lokales JDK erforderlich — einfach Image ziehen und ausführen:

```bash
# Validierung
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml

# Compare (file/file)
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# DDL generieren
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/schema.yaml --target postgresql

# Reverse-Engineering
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  --config /work/.d-migrate.yaml schema reverse --source mydb --output /work/reverse.yaml
```

#### Mit Dockerfile lokal bauen und testen

Das Repository liefert ein Multi-Stage [`Dockerfile`](Dockerfile), das das Projekt im Container baut
und testet und danach die CLI-Distribution in ein schlankes JRE-Laufzeitimage verpackt. Das ist der einfachste Weg,
den vollständigen Build ohne lokale JDK-Installation auszuführen.

```bash
# Vollständiger Build inkl. Tests und Coverage-Validierung (Standard)
docker build -t d-migrate:dev .

# Erzwungener vollständiger Test/Coverage-Lauf (Docker-Layer-Cache UND Gradle-Cache werden umgangen)
docker build --no-cache \
  --progress=plain \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Aggregierten Kover-HTML-Report bauen und lokal im Browser ansehen
docker build --target coverage -t d-migrate:coverage .
docker run --rm -p 8080:8080 d-migrate:coverage
# dann http://localhost:8080 im Browser öffnen

# Aggregierten Kover-JSON-Report direkt auf stdout ausgeben
docker build --target coverage-json -t d-migrate:coverage-json .
docker run --rm d-migrate:coverage-json > coverage.json

# Optional den 90%-Kover-Gate wie in CI hart prüfen
docker build --target coverage-verify -t d-migrate:coverage-verify .

# Tests überspringen — nur CLI-Distribution erstellen
docker build --build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist" \
  -t d-migrate:dev .

# Nur einen Build-Stage-Teil ausführen, ohne finales Runtime-Image zu erzeugen
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
  -t d-migrate:phase-a .

# Lokal gebaute CLI ausführen
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Testcontainers-basierte Integrationssuite ausführen
./scripts/test-integration-docker.sh

# Oder nur eine Teilmenge der Integrationstests ausführen
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
```

### Dockerfile-Workflows (schneller Überblick)

<details>
<summary>Build- und Runtime-Stages</summary>

- Build-Stage: `gradle:8.12-jdk21`
- Runtime-Stage: `eclipse-temurin:21-jre-noble` (wie beim offiziellen Jib-OCI-Image)
- Gradle-Dependencies werden in einer eigenen `deps`-Stage vorgewärmt.
- Vollständiger `docker build` landet immer in der `runtime`-Stage.
- Bei `GRADLE_TASKS`-Überschreibung ergänzen: `:adapters:driving:cli:installDist`
- Für reinen Build/Test ohne Runtime-Image: `--target build`

</details>

<details>
<summary>Coverage-Stages</summary>

- `coverage`: `test koverHtmlReport koverXmlReport` + HTTP-Server auf Port `8080` für Root-Kover-HTML.
- `coverage`: HTML-Report wird auch bei unterschrittenem 90%-Gate erzeugt.
- `coverage-json`: identischer Root-Kover-Report als JaCoCo-ähnliches JSON auf `stdout` (via `ENTRYPOINT`).
- `coverage-verify`: hartes `koverVerify`; Build-Target bricht bei nicht erfülltem Mindestwert mit Fehler ab.
- `docker-coverage-modules-html`: per-Modul-Kover-HTML-Reports als Tar-Stream fuer `make docker-coverage-modules-html`.

</details>

<details>
<summary>Release- und OCI-Stages</summary>

- `release-assets`: baut ZIP, TAR, Fat JAR und SHA256 und streamt `adapters/driving/cli/build/release` als Tar fuer `make release-assets`.
- `jib-image-tar`: baut das Jib-OCI-Image als Tar, inklusive Jib-Labels; `make docker-oci-build` laedt es danach per `docker load`.

</details>

<details>
<summary>Dokumente und Integrationstests</summary>

- `scripts/verify-doc-refs.sh` validiert Links in `docs/`, `spec/`, `README.md`, `CHANGELOG.md` gegen das Dateisystem.
- Externe HTTP-Links werden ignoriert; kaputte interne Links liefern Exit-Code `1`.
- Testcontainers-Jobs nicht im `docker build` laufen lassen.
- Dafür nutze stattdessen [`scripts/test-integration-docker.sh`](scripts/test-integration-docker.sh), der den Host-Docker-Socket in einen JDK-Container mountet.

</details>

<details>
<summary>Build-Artefakte aus der Build-Stage exportieren</summary>

```bash
docker build --target build -t d-migrate:build .
docker create --name d-migrate-tmp d-migrate:build
docker cp d-migrate-tmp:/src/adapters/driving/cli/build/distributions ./dist
docker rm d-migrate-tmp
```

</details>

### Minimales Schema-Beispiel

Lege eine Datei namens `schema.yaml` an:

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
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source /work/schema.yaml --target /work/schema-v2.yaml
```

## Aktueller Stand

Aktuelles Release: **[v0.9.6](https://github.com/pt9912/d-migrate/releases/tag/v0.9.6)**

MCP-Server:

- Laufzeit als **Model Context Protocol v1 Server** (`stdio`, Streamable HTTP)
- Asynchrone Jobs
- Idempotenz
- Policy/Approval
- Quotas
- JDBC-Persistenz
- File-backed Artifact-Stores
- Bundle-Import
- KI-nahe Tools (`procedure_transform_*`, `testdata_*`)

Weitere Verbesserungen:

- Deterministische DDL-Generierung (`--deterministic` / `SOURCE_DATE_EPOCH`)
- BigInt Identity-Columns für PostgreSQL/MySQL
- Partial Index-Predicates
- Index-Sortierung pro Spalte
- Robusterer `schema reverse`-Pfad mit `--split=pre-post`

Alle Releases und Details: [CHANGELOG.md](CHANGELOG.md) | [GitHub Releases](https://github.com/pt9912/d-migrate/releases)

## Unterstützte Datenbanken

| Datenbank  | Status                                                              |
| ---------- | ------------------------------------------------------------------- |
| PostgreSQL | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| MySQL      | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| SQLite     | DDL-Generierung, Reverse-Engineering, Datenexport/-import/-transfer |
| Oracle     | Geplant                                                             |
| MSSQL      | Geplant                                                             |

## Roadmap

Die vollständige Roadmap und den Meilensteinplan findest du in
[docs/planning/roadmap.md](docs/planning/in-progress/roadmap.md).

## Dokumentation

Detaillierte Dokumentation findest du in [docs/](docs/) und [spec/](spec/):

- [Quick Start Guide (Deutsch)](docs/user/guide.md)
- [Entwurf](spec/design.md) / [Architektur](spec/architecture.md)
- [Schema-YAML-Referenz](spec/schema-reference.md)
- [Spezifikation des neutralen Modells](spec/neutral-model-spec.md)
- [CLI-Spezifikation](spec/cli-spec.md)
- [MCP-Server (`d-migrate mcp serve`)](spec/mcp-server.md)
- [Regeln zur DDL-Generierung](spec/ddl-generation-rules.md)
- [Verbindungs- und Konfigurationsspezifikation](spec/connection-config-spec.md)
- [Roadmap](docs/planning/in-progress/roadmap.md)
- [Release-Leitfaden](docs/user/releasing.md)
- [Lastenheft (Deutsch)](spec/lastenheft-d-migrate.md)

## Mitmachen

Beiträge sind willkommen! Bitte erstelle ein Issue oder einen Pull Request auf [GitHub](https://github.com/pt9912/d-migrate).

1. Forke das Repository
2. Erstelle einen Feature-Branch von `develop`
3. Schreibe Tests für deine Änderungen
4. Stelle sicher, dass die Docker-CI-Gates laufen (`make ci`)
5. Reiche einen Pull Request gegen `develop` ein

## Lizenz

Dieses Projekt ist unter der [MIT-Lizenz](LICENSE) lizenziert.
