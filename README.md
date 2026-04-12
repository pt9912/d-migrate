# d-migrate

**Datenbankunabhängiges CLI-Tool für Schema-Migration und Datenmanagement.**

<!-- Badges -->
![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

---

## Was ist d-migrate?

d-migrate ist ein Kommandozeilenwerkzeug, mit dem du dein Datenbankschema einmalig in einem neutralen, datenbankunabhängigen Format (YAML) definierst und anschließend für mehrere Zielsysteme validierst, vergleichst und DDL erzeugst. Damit entfallen getrennte Migrationsskripte pro Datenbankengine.

**Aktuelle Fähigkeiten:**
- Neutrales Schemamodell mit 18 integrierten Typen
- YAML-basierte Schemadefinition und -parsing
- Schemagültigkeitsprüfung mit 18 Fehlercodes (E001-E018)
- DDL-Generierung für PostgreSQL, MySQL und SQLite
- Transformation von View-Queries (17 SQL-Funktionen)
- Transformationsberichte (YAML-Seitenschatten)
- Streaming-Datenexport (JSON, YAML, CSV) mit benannten Verbindungen
- Transaktionaler Datenimport mit UPSERT, Truncate, Trigger-Handling und Reseeding
- Inkrementeller Export über `--since-column` / `--since` (LF-013)
- CLI mit `schema validate`, `schema generate`, `data export` und `data import`
- OCI-Image für die Nutzung mit Docker

**Geplant:**
- Schema-Diff und `schema compare` (0.5.0)
- Reverse-Engineering bestehender Datenbanken (0.6.0)

## Schnellstart

### Voraussetzungen

- **JDK 21** oder neuer — _oder_ Docker (siehe unten, kein lokales JDK erforderlich)

### Build

```bash
./gradlew build
```

### CLI ausführen

```bash
# Schema validieren
./gradlew :adapters:driving:cli:run --args="schema validate --source schema.yaml"

# PostgreSQL-DDL generieren
./gradlew :adapters:driving:cli:run --args="schema generate --source schema.yaml --target postgresql"

# MySQL-DDL mit Rollback generieren
./gradlew :adapters:driving:cli:run --args="schema generate --source schema.yaml --target mysql --generate-rollback"
```

### Docker

#### Veröffentlichtes Image nutzen

Kein lokales JDK erforderlich — einfach Image ziehen und ausführen:

```bash
# Validierung
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml

# DDL generieren
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/schema.yaml --target postgresql
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
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

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

Hinweise:

- Die Build-Stage nutzt `eclipse-temurin:21-jdk-noble` und cached Gradle-Abhängigkeiten über BuildKit-Cache-Mounts, sodass wiederholte Builds schnell sind.
- Die Runtime-Stage nutzt `eclipse-temurin:21-jre-noble` (dasselbe Basisimage wie das veröffentlichte OCI-Image aus `:adapters:driving:cli:jibDockerBuild`).
- Ein vollständiger `docker build` erreicht immer die Runtime-Stage. Wenn du `GRADLE_TASKS` überschreibst, füge `:adapters:driving:cli:installDist` hinzu; für Build-/Test-Only-Subsets nutze alternativ `--target build`.
- Testcontainers-basierte Integrationstests sollten nicht in `docker build` laufen. Nutze dafür
  [`scripts/test-integration-docker.sh`](scripts/test-integration-docker.sh),
  das einen kurzlebigen JDK-Container startet und den Docker-Socket des Hosts mountet, damit Testcontainers PostgreSQL/MySQL normal starten kann.
- Um Build-Artefakte aus der Build-Stage zu extrahieren:
  ```bash
  docker build --target build -t d-migrate:build .
  docker create --name d-migrate-tmp d-migrate:build
  docker cp d-migrate-tmp:/src/adapters/driving/cli/build/distributions ./dist
  docker rm d-migrate-tmp
  ```

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
./gradlew :adapters:driving:cli:run --args="schema validate --source schema.yaml"
```

## Aktueller Stand

**[v0.4.0](https://github.com/pt9912/d-migrate/releases/tag/v0.4.0)** veröffentlicht:

- `data import` CLI-Befehl: transaktionaler Import aus JSON/YAML/CSV in PostgreSQL, MySQL, SQLite
- UPSERT (`--on-conflict update`), Truncate (`--truncate`), Trigger-Handling (`--trigger-mode`)
- Sequence/Identity/AUTO_INCREMENT-Reseeding nach Import
- Inkrementeller Export mit `--since-column` / `--since` (LF-013)
- Hexagonale Architektur-Umstrukturierung (Ports & Adapters)
- CLI Kover-Coverage-Gate auf 90% angehoben

**[v0.3.0](https://github.com/pt9912/d-migrate/releases/tag/v0.3.0)** veröffentlicht:

- Streaming-CLI-Befehl `data export` (JSON / YAML / CSV) für PostgreSQL, MySQL, SQLite
- HikariCP-basiertes Verbindungs-Subsystem mit `ConnectionUrlParser` pro Dialekt
- Pull-basiertes `StreamingExporter` (Chunk-Streaming, kein Full-Table-Buffering)
- Performance-orientierte Formatwriter: DSL-JSON, SnakeYAML Engine, uniVocity-parsers
- Benannte Verbindungen über `.d-migrate.yaml` mit `${ENV_VAR}`-Substitution (`NamedConnectionResolver`)
- Optionen `--source`, `--format`, `--output`, `--tables`, `--filter`, `--split-files`, `--csv-*`, `--encoding`, `--chunk-size`
- §6.17 Empty-Table-Vertrag: `[]` (JSON/YAML), CSV-Kopfzeile oder leere Datei
- End-to-End-Testabdeckung via Testcontainers für PostgreSQL 16 und MySQL 8.0
- 600+ Tests, Coverage ≥ 90% (CLI ≥ 60%)

**[v0.2.0](https://github.com/pt9912/d-migrate/releases/tag/v0.2.0)** veröffentlicht:

- DDL-Generierung für PostgreSQL, MySQL, SQLite
- TypeMapper mit 18 neutralen Typen pro Dialekt
- Transformation von View-Queries (17 SQL-Funktionen)
- Transformationsberichte (YAML-Seitenschatten, JSON-Ausgabe)
- CLI-Befehl `schema generate` mit `--output`, `--generate-rollback`, `--report`
- 374 Tests, Coverage ≥ 90%

**[v0.1.0](https://github.com/pt9912/d-migrate/releases/tag/v0.1.0)** veröffentlicht:

- Neutrales Schemamodell, YAML-Parsing, Schemagültigkeitsprüfung, CLI `schema validate`

## Unterstützte Datenbanken

| Datenbank | Status                           |
|-----------|----------------------------------|
| PostgreSQL | DDL-Generierung, Datenexport, Datenimport |
| MySQL      | DDL-Generierung, Datenexport, Datenimport |
| SQLite     | DDL-Generierung, Datenexport, Datenimport |
| Oracle     | Geplant                         |
| MSSQL      | Geplant                         |

## Roadmap

Die vollständige Roadmap und den Meilensteinplan findest du in
[docs/roadmap.md](docs/roadmap.md).

## Dokumentation

Detaillierte Dokumentation findest du im [docs/](docs/)-Verzeichnis:

- [Quick Start Guide (Deutsch)](docs/guide.md)
- [Entwurf](docs/design.md) / [Architektur](docs/architecture.md)
- [Spezifikation des neutralen Modells](docs/neutral-model-spec.md)
- [CLI-Spezifikation](docs/cli-spec.md)
- [Regeln zur DDL-Generierung](docs/ddl-generation-rules.md)
- [Verbindungs- und Konfigurationsspezifikation](docs/connection-config-spec.md)
- [Roadmap](docs/roadmap.md)
- [Release-Leitfaden](docs/releasing.md)
- [Lastenheft (Deutsch)](docs/lastenheft-d-migrate.md)

## Mitmachen

Beiträge sind willkommen! Bitte erstelle ein Issue oder einen Pull Request auf [GitHub](https://github.com/pt9912/d-migrate).

1. Forke das Repository
2. Erstelle einen Feature-Branch von `develop`
3. Schreibe Tests für deine Änderungen
4. Stelle sicher, dass alle Tests laufen (`./gradlew build`)
5. Reiche einen Pull Request gegen `develop` ein

## Lizenz

Dieses Projekt ist unter der [MIT-Lizenz](LICENSE) lizenziert.
