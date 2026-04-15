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
- Inkrementeller Export über `--since-column` / `--since` (LF-013)
- Line-orientierte Fortschrittsanzeige für `data export`, `data import` und `data transfer`
- CLI mit `schema validate`, `schema generate`, `schema compare`, `schema reverse`, `data export`, `data import` und `data transfer`
- OCI-Image für die Nutzung mit Docker

## Schnellstart

### Voraussetzungen

- **JDK 21** oder neuer — _oder_ Docker (siehe unten, kein lokales JDK erforderlich)

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
./gradlew build
```

#### Release-Assets lokal bauen

```bash
./gradlew :adapters:driving:cli:assembleReleaseAssets
ls -1 adapters/driving/cli/build/release
```

### CLI ausführen

```bash
# Schema validieren
./gradlew :adapters:driving:cli:run --args="schema validate --source schema.yaml"

# Zwei Schemas vergleichen
./gradlew :adapters:driving:cli:run --args="schema compare --source schema.yaml --target schema-new.yaml"

# PostgreSQL-DDL generieren
./gradlew :adapters:driving:cli:run --args="schema generate --source schema.yaml --target postgresql"

# MySQL-DDL mit Rollback generieren
./gradlew :adapters:driving:cli:run --args="schema generate --source schema.yaml --target mysql --generate-rollback"

# Schema aus bestehender Datenbank extrahieren
./gradlew :adapters:driving:cli:run --args="schema reverse --source mydb --output reverse.yaml --report reverse.report.yaml"

# DB-basierter Schema-Vergleich
./gradlew :adapters:driving:cli:run --args="schema compare --source file:schema.yaml --target db:mydb"

# DB-zu-DB Datentransfer
./gradlew :adapters:driving:cli:run --args="data transfer --source sourcedb --target targetdb --tables users,orders"
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

# Kover-Coverage-Report als JSON extrahieren
docker build --target build -t d-migrate:build .
docker create --name d-migrate-tmp d-migrate:build
docker cp d-migrate-tmp:/src/hexagon/core/build/reports/kover/report.json ./coverage-core.json
docker rm d-migrate-tmp

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

Und vergleichst zwei Versionen so:

```bash
./gradlew :adapters:driving:cli:run --args="schema compare --source schema.yaml --target schema-v2.yaml"
```

## Aktueller Stand

Aktuelles Release: **[v0.7.0](https://github.com/pt9912/d-migrate/releases/tag/v0.7.0)** — Tool-Integrationen (Flyway, Liquibase, Django, Knex Export).

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
[docs/roadmap.md](docs/roadmap.md).

## Dokumentation

Detaillierte Dokumentation findest du im [docs/](docs/)-Verzeichnis:

- [Quick Start Guide (Deutsch)](docs/guide.md)
- [Entwurf](docs/design.md) / [Architektur](docs/architecture.md)
- [Schema-YAML-Referenz](docs/schema-reference.md)
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
