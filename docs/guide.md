# Schnellstart-Anleitung

Diese Anleitung beschreibt die ersten Schritte mit d-migrate: Installation,
Schema validieren, Schemas vergleichen, DDL generieren sowie Daten exportieren
und importieren.

---

## Option A: GitHub Release Assets

Für veröffentlichte Releases stehen launcher-basierte Distributionen als ZIP/TAR
sowie ein Fat JAR auf der GitHub-Releases-Seite bereit.

```bash
# Launcher-basierte Distribution entpacken
tar -xf d-migrate-<version>.tar
./d-migrate-<version>/bin/d-migrate --help

# Alternativ das Fat JAR direkt starten
java -jar d-migrate-<version>-all.jar --help
```

Hinweis:

- Für 0.5.0 ist das der kanonische Download-Pfad neben dem OCI-Image.
- Die Homebrew-Formula wird im Repository gepflegt, aber erst mit der finalen
  Release-URL und SHA256 fertiggestellt.

## Option B: Docker (empfohlen)

Kein JDK nötig — nur Docker.

### A.1 Vorgefertigtes Image aus GHCR verwenden

```bash
# Schema validieren
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/mein-schema.yaml

# DDL generieren
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/mein-schema.yaml --target postgresql
```

### A.2 Image lokal aus dem Dockerfile bauen

Das Repository enthält ein mehrstufiges [`Dockerfile`](../Dockerfile), das den
kompletten Gradle-Build (inkl. Tests und Kover-Coverage-Verifikation) in einem
Container ausführt und die CLI in ein schlankes JRE-Runtime-Image packt:

```bash
# Vollständiger Build inkl. Tests und Coverage-Verifikation (Standard)
docker build -t d-migrate:dev .

# Test/Coverage-Lauf ohne Cache erzwingen (Docker-Layer- UND Gradle-Build-Cache umgehen)
docker build --no-cache \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Nur einen Build-Stage-Teilcheck ausführen, ohne das finale Runtime-Image zu bauen
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
  -t d-migrate:phase-a .

# Testcontainers-Integrationstests separat über einen Laufzeit-Container ausführen
./scripts/test-integration-docker.sh

# CLI aus dem lokal gebauten Image ausführen
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/mein-schema.yaml
```

Wichtig:

- Ein voller `docker build` läuft immer bis in die Runtime-Stage.
- Wenn `GRADLE_TASKS` überschrieben wird, muss für den vollständigen Multi-Stage-Build
  weiterhin `:adapters:driving:cli:installDist` enthalten sein.
- Für reine Build-/Test-Teilmengen ohne CLI-Distribution sollte `--target build`
  verwendet werden.
- Testcontainers-Tests für PostgreSQL/MySQL laufen nicht verlässlich innerhalb
  von `docker build`, weil der Build-Container keinen nutzbaren Docker-Daemon
  für `container.start()` durchreicht. Dafür gibt es
  [`scripts/test-integration-docker.sh`](../scripts/test-integration-docker.sh),
  das einen separaten JDK-Container mit gemountetem Host-Docker-Socket startet.

## Option C: Aus Quellcode bauen

### Voraussetzungen

- **JDK 21** oder neuer
- **Git**

Repository klonen:

```bash
git clone https://github.com/pt9912/d-migrate.git
cd d-migrate
```

### Projekt bauen

```bash
./gradlew build
```

Damit werden alle Module kompiliert und ihre Tests ausgeführt:

- `hexagon:core` — neutrales Schemamodell, Parser, Validator
- `hexagon:ports` — Port-Interfaces (hexagonale Architektur)
- `hexagon:application` — Runner/Use-Cases (SchemaValidateRunner, DataExportRunner, …)
- `adapters:driven:driver-common` — gemeinsame DDL-Generator- und Daten-Abstraktionen
- `adapters:driven:driver-postgresql` / `driver-mysql` / `driver-sqlite` — dialektspezifische Treiber (DDL, DataReader, DataWriter)
- `adapters:driven:formats` — YAML-/JSON-/CSV-Codecs für Schema und Daten
- `adapters:driven:streaming` — Pull-basierte Streaming-Pipeline für Export/Import
- `adapters:driving:cli` — Clikt-basiertes Command-Line-Interface

## Erstes Schema erstellen

Erstelle eine Datei `mein-schema.yaml` mit folgendem Inhalt:

```yaml
schema_format: "1.0"
name: "Webshop"
version: "1.0.0"
encoding: "utf-8"

custom_types:
  order_status:
    kind: enum
    values: [pending, processing, shipped, delivered, cancelled]

tables:
  customers:
    description: "Kundenstammdaten"
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: text
        max_length: 254
        required: true
        unique: true
      name:
        type: text
        max_length: 100
        required: true
      created_at:
        type: datetime
        timezone: true
        default: current_timestamp
    primary_key: [id]
    indices:
      - name: idx_customers_email
        columns: [email]
        type: btree

  orders:
    description: "Bestellungen"
    columns:
      id:
        type: identifier
        auto_increment: true
      customer_id:
        type: integer
        required: true
        references:
          table: customers
          column: id
          on_delete: restrict
      total:
        type: decimal
        precision: 10
        scale: 2
      status:
        type: enum
        ref_type: order_status
        default: "pending"
      ordered_at:
        type: datetime
        required: true
    primary_key: [id]
    indices:
      - name: idx_orders_customer
        columns: [customer_id]
        type: btree
```

## Schema validieren

Führe die Validierung mit dem CLI aus:

```bash
./gradlew :adapters:driving:cli:run --args="schema validate --source mein-schema.yaml"
```

### Beispiel-Ausgabe (valides Schema)

```
Validating schema 'Webshop' v1.0.0...

  Tables:      2 found
  Columns:     9 found
  Indices:     2 found
  Constraints: 0 found

Results:
  ✓ Validation passed

Validation passed: 0 warning(s)
```

### Beispiel-Ausgabe (invalides Schema)

Wenn z.B. eine Fremdschlüssel-Referenz auf eine nicht existierende Tabelle zeigt:

```
Validating schema 'Webshop' v1.0.0...

  Tables:      2 found
  Columns:     9 found
  Indices:     2 found
  Constraints: 0 found

Results:
  ✗ Error [E002]: Foreign key references non-existent table 'payments'
    → tables.orders.columns.customer_id.references.table

Validation failed: 1 error(s), 0 warning(s)
```

## Schemas vergleichen

`schema compare` vergleicht zwei neutrale Schema-Dateien. Der 0.5.0-MVP ist
bewusst file-basiert; DB- oder Umgebungsvergleiche sind noch nicht Teil dieses
Slices.

```bash
# Menschenlesbare Diff-Ausgabe
./gradlew :adapters:driving:cli:run --args="schema compare --source mein-schema.yaml --target mein-schema-v2.yaml"

# Strukturiert nach JSON schreiben
./gradlew :adapters:driving:cli:run --args="--output-format json schema compare --source mein-schema.yaml --target mein-schema-v2.yaml --output diff.json"
```

Exit-Codes:

- `0`: keine Unterschiede
- `1`: Unterschiede gefunden
- `3`: Schema-Validierung fehlgeschlagen
- `7`: Datei-/Parse-/I/O-Fehler

Bei `--output-format json|yaml` bleibt die Nutzlast auf `stdout` oder in
`--output`; Fehler laufen weiter über `stderr`.

## DDL generieren

Nach erfolgreicher Validierung kann DDL für eine Zieldatenbank erzeugt werden:

```bash
# PostgreSQL
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target postgresql"

# MySQL
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target mysql"

# SQLite
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target sqlite"
```

### DDL in Datei speichern

```bash
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target postgresql --output schema.sql"
```

Erzeugt automatisch `schema.sql` (DDL) und `schema.report.yaml` (Transformations-Report).
Den Report-Pfad explizit überschreiben:

```bash
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target postgresql --output schema.sql --report mein-report.yaml"
```

### Rollback-DDL generieren

```bash
./gradlew :adapters:driving:cli:run --args="schema generate --source mein-schema.yaml --target mysql --output schema.sql --generate-rollback"
```

Erzeugt zusätzlich `schema.rollback.sql` mit den inversen DDL-Statements (DROP TABLE, DROP INDEX, etc.).

### Beispiel-Ausgabe (PostgreSQL)

```sql
-- Generated by d-migrate 0.4.0
-- Source: neutral schema v1.0.0 "Webshop"
-- Target: postgresql | Generated: 2026-04-05T14:30:00Z

CREATE TYPE "order_status" AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

CREATE TABLE "customers" (
    "id" SERIAL,
    "email" VARCHAR(254) NOT NULL UNIQUE,
    "name" VARCHAR(100) NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);

CREATE TABLE "orders" (
    "id" SERIAL,
    "customer_id" INTEGER NOT NULL,
    "total" DECIMAL(10,2),
    "status" "order_status" DEFAULT 'pending',
    "ordered_at" TIMESTAMP NOT NULL,
    CONSTRAINT "fk_orders_customer_id" FOREIGN KEY ("customer_id")
        REFERENCES "customers" ("id") ON DELETE RESTRICT,
    PRIMARY KEY ("id")
);
```

## Daten exportieren

Tabellendaten können aus einer Datenbank in JSON, YAML oder CSV exportiert werden:

```bash
# Eine Tabelle als JSON nach stdout
d-migrate data export --source postgresql://user@localhost/mydb --format json --tables customers

# Mehrere Tabellen als CSV in separate Dateien
d-migrate data export --source postgresql://user@localhost/mydb --format csv \
    --tables customers,orders --output ./export --split-files

# Named Connection aus .d-migrate.yaml verwenden
d-migrate data export --source staging --format yaml --tables orders --output orders.yaml
```

### Inkrementeller Export (LF-013)

Nur Datensätze exportieren, die seit einem bestimmten Zeitpunkt geändert wurden:

```bash
d-migrate data export --source staging --format json --tables orders \
    --since-column updated_at --since "2026-04-01T00:00:00" --output delta.json
```

## Daten importieren

Daten aus JSON, YAML oder CSV können transaktional in eine Datenbank importiert werden:

```bash
# JSON-Datei importieren
d-migrate data import --source customers.json --target postgresql://user@localhost/mydb \
    --format json --schema mein-schema.yaml

# Idempotenter UPSERT-Import (vorhandene Datensätze aktualisieren)
d-migrate data import --source orders.yaml --target staging \
    --format yaml --schema mein-schema.yaml --on-conflict update

# Tabelle vor dem Import leeren
d-migrate data import --source products.csv --target staging \
    --format csv --schema mein-schema.yaml --truncate

# Trigger während des Imports deaktivieren
d-migrate data import --source orders.json --target staging \
    --format json --schema mein-schema.yaml --trigger-mode disable
```

### Vollständiger Round-Trip

Export und Import können kombiniert werden, um Daten zwischen Datenbanken zu übertragen:

```bash
# Export aus PostgreSQL
d-migrate data export --source postgresql://localhost/source --format json \
    --tables customers,orders --output ./transfer --split-files

# Import in MySQL
d-migrate data import --source ./transfer --target mysql://localhost/target \
    --format json --schema mein-schema.yaml --truncate
```

## CLI-Optionen

### Globale Optionen (vor dem Subcommand)

| Option                | Beschreibung                                              |
| --------------------- | --------------------------------------------------------- |
| `-c`, `--config`      | Pfad zu einer Konfigurationsdatei                         |
| `--lang`              | Sprache der Ausgabe (`de`, `en`)                          |
| `--output-format`     | Ausgabeformat: `plain` (Standard), `json`, `yaml`         |
| `-v`, `--verbose`     | Erweiterte Ausgabe (DEBUG-Level)                          |
| `-q`, `--quiet`       | Nur Fehler ausgeben                                       |
| `--no-color`          | Farbige Ausgabe deaktivieren                              |
| `--no-progress`       | Fortschrittsanzeige deaktivieren                          |
| `-y`, `--yes`         | Rückfragen automatisch bestätigen                         |
| `--version`           | Version anzeigen                                          |
| `-h`, `--help`        | Hilfe anzeigen                                            |

`--verbose` und `--quiet` schließen sich gegenseitig aus.

### Optionen für `schema generate`

| Option                | Beschreibung                                              |
| --------------------- | --------------------------------------------------------- |
| `--source`            | Pfad zur Schema-Datei (YAML, Pflicht)                     |
| `--target`            | Zieldialekt: `postgresql`, `mysql`, `sqlite` (Pflicht)    |
| `--output`            | Ausgabedatei (Standard: stdout)                           |
| `--report`            | Report-Datei (Standard: `<output>.report.yaml`)           |
| `--generate-rollback` | Zusätzlich Rollback-DDL erzeugen                          |

### Optionen für `data export`

| Option                | Beschreibung                                              |
| --------------------- | --------------------------------------------------------- |
| `--source`            | Connection-URL oder Name aus `.d-migrate.yaml` (Pflicht)  |
| `--format`            | Ausgabeformat: `json`, `yaml`, `csv` (Pflicht)            |
| `--output`, `-o`      | Ausgabedatei oder -verzeichnis (Standard: stdout)         |
| `--tables`            | Nur diese Tabellen (kommasepariert)                       |
| `--filter`            | Roh-WHERE-Klausel (ohne `WHERE`-Keyword)                  |
| `--since-column`      | Marker-Spalte für inkrementellen Export (LF-013)          |
| `--since`             | Untere Grenze für `--since-column`                        |
| `--split-files`       | Eine Datei pro Tabelle in `--output <dir>`                |
| `--chunk-size`        | Rows pro Streaming-Chunk (Standard: 10000)                |
| `--encoding`          | Output-Encoding (Standard: `utf-8`)                       |
| `--csv-delimiter`     | CSV-Trennzeichen (Standard: `,`)                          |
| `--csv-bom`           | UTF-8 BOM-Bytes voranstellen                              |
| `--csv-no-header`     | CSV-Kopfzeile unterdrücken                                |

### Optionen für `data import`

| Option                | Beschreibung                                              |
| --------------------- | --------------------------------------------------------- |
| `--source`            | Datendatei oder -verzeichnis (Pflicht)                    |
| `--target`            | Connection-URL oder Name aus `.d-migrate.yaml` (Pflicht)  |
| `--format`            | Eingabeformat: `json`, `yaml`, `csv` (Pflicht)            |
| `--schema`            | Schema-Datei für Preflight-Validierung                    |
| `--on-conflict`       | `abort` (Standard) oder `update` (UPSERT)                 |
| `--truncate`          | Zieltabellen vor dem Import leeren                        |
| `--trigger-mode`      | `enable` (Standard) oder `disable`                        |
| `--chunk-size`        | Datensätze pro Transaktion (Standard: 10000)              |
| `--encoding`          | Input-Encoding (Standard: auto-detect)                    |

### Beispiel: JSON-Ausgabe

```bash
./gradlew :adapters:driving:cli:run --args="--output-format json schema validate --source mein-schema.yaml"
```

## Neutrales Typsystem

d-migrate verwendet 18 neutrale Datentypen, die pro Zieldatenbank automatisch übersetzt werden:

| Typ          | Beschreibung                 | Beispiel              |
| ------------ | ---------------------------- | --------------------- |
| `identifier` | Auto-Increment Primärschlüssel | SERIAL / AUTO_INCREMENT |
| `text`       | Variabler Text               | VARCHAR(n) / TEXT     |
| `integer`    | Ganzzahl                     | INTEGER / INT         |
| `decimal`    | Dezimalzahl mit Präzision    | DECIMAL(10,2)         |
| `boolean`    | Wahrheitswert                | BOOLEAN / TINYINT(1)  |
| `datetime`   | Datum und Uhrzeit            | TIMESTAMP / DATETIME  |
| `uuid`       | Universelle ID               | UUID / CHAR(36)       |
| `json`       | JSON-Daten                   | JSONB / JSON          |
| `enum`       | Aufzählungstyp               | ENUM / CHECK          |
| ...          | [Vollständige Liste](./neutral-model-spec.md#3-neutrales-typsystem) | |

## Nächste Schritte

- [Schema-YAML-Referenz](./schema-reference.md) -- Kurzreferenz fuer das Schema-Format
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) -- Vollständige Typsystem-Referenz
- [CLI-Spezifikation](./cli-spec.md) -- Alle Kommandos und Exit-Codes
- [Design-Dokument](./design.md) -- Architektur und Designentscheidungen
- [Roadmap](./roadmap.md) -- Geplante Features und Meilensteine
- [README (English)](../README.md) -- Projektübersicht auf Englisch
- [Changelog](../CHANGELOG.md) -- Alle Änderungen im Überblick
