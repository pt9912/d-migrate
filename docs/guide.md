# Schnellstart-Anleitung

Diese Anleitung beschreibt die ersten Schritte mit d-migrate: Projekt bauen, ein Schema erstellen und validieren.

---

## Option A: Docker (empfohlen)

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
  --build-arg GRADLE_TASKS="build :d-migrate-cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Nur einen Build-Stage-Teilcheck ausführen, ohne das finale Runtime-Image zu bauen
docker build --target build \
  --build-arg GRADLE_TASKS=":d-migrate-core:test :d-migrate-driver-api:test" \
  -t d-migrate:phase-a .

# Testcontainers-Integrationstests separat über einen Laufzeit-Container ausführen
./scripts/test-integration-docker.sh

# CLI aus dem lokal gebauten Image ausführen
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/mein-schema.yaml
```

Wichtig:

- Ein voller `docker build` läuft immer bis in die Runtime-Stage.
- Wenn `GRADLE_TASKS` überschrieben wird, muss für den vollständigen Multi-Stage-Build
  weiterhin `:d-migrate-cli:installDist` enthalten sein.
- Für reine Build-/Test-Teilmengen ohne CLI-Distribution sollte `--target build`
  verwendet werden.
- Testcontainers-Tests für PostgreSQL/MySQL laufen nicht verlässlich innerhalb
  von `docker build`, weil der Build-Container keinen nutzbaren Docker-Daemon
  für `container.start()` durchreicht. Dafür gibt es
  [`scripts/test-integration-docker.sh`](../scripts/test-integration-docker.sh),
  das einen separaten JDK-Container mit gemountetem Host-Docker-Socket startet.

## Option B: Aus Quellcode bauen

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

Damit werden alle sieben Module kompiliert und ihre Tests ausgeführt:

- `d-migrate-core` — neutrales Schemamodell, Parser, Validator
- `d-migrate-driver-api` — gemeinsame DDL-Generator-Abstraktionen
- `d-migrate-driver-postgresql` / `-mysql` / `-sqlite` — dialektspezifische DDL-Generatoren
- `d-migrate-formats` — YAML-Codec und Report-Writer
- `d-migrate-cli` — Clikt-basiertes Command-Line-Interface

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
./gradlew :d-migrate-cli:run --args="schema validate --source mein-schema.yaml"
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

## DDL generieren

Nach erfolgreicher Validierung kann DDL für eine Zieldatenbank erzeugt werden:

```bash
# PostgreSQL
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target postgresql"

# MySQL
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target mysql"

# SQLite
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target sqlite"
```

### DDL in Datei speichern

```bash
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target postgresql --output schema.sql"
```

Erzeugt automatisch `schema.sql` (DDL) und `schema.report.yaml` (Transformations-Report).
Den Report-Pfad explizit überschreiben:

```bash
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target postgresql --output schema.sql --report mein-report.yaml"
```

### Rollback-DDL generieren

```bash
./gradlew :d-migrate-cli:run --args="schema generate --source mein-schema.yaml --target mysql --output schema.sql --generate-rollback"
```

Erzeugt zusätzlich `schema.rollback.sql` mit den inversen DDL-Statements (DROP TABLE, DROP INDEX, etc.).

### Beispiel-Ausgabe (PostgreSQL)

```sql
-- Generated by d-migrate 0.2.0
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

### Beispiel: JSON-Ausgabe

```bash
./gradlew :d-migrate-cli:run --args="--output-format json schema validate --source mein-schema.yaml"
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

- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) -- Vollständige Typsystem-Referenz
- [CLI-Spezifikation](./cli-spec.md) -- Alle Kommandos und Exit-Codes
- [Design-Dokument](./design.md) -- Architektur und Designentscheidungen
- [Roadmap](./roadmap.md) -- Geplante Features und Meilensteine
- [README (English)](../README.md) -- Projektübersicht auf Englisch
- [Changelog](../CHANGELOG.md) -- Alle Änderungen im Überblick
