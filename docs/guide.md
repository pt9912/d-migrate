# Schnellstart-Anleitung

Diese Anleitung beschreibt die ersten Schritte mit d-migrate: Projekt bauen, ein Schema erstellen und validieren.

---

## Option A: Docker (empfohlen)

Kein JDK nötig — nur Docker:

```bash
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:0.1.0 schema validate --source /work/mein-schema.yaml
```

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

Damit werden alle drei Module (`d-migrate-core`, `d-migrate-formats`, `d-migrate-cli`) kompiliert und die Tests ausgeführt.

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

## CLI-Optionen

| Option            | Beschreibung                                      |
| ----------------- | ------------------------------------------------- |
| `--output-format` | Ausgabeformat: `plain` (Standard), `json`, `yaml` |
| `--quiet`         | Nur Fehler ausgeben, keine Zusammenfassung         |
| `--no-color`      | Farbige Ausgabe deaktivieren                       |
| `--verbose`       | Erweiterte Ausgabe (DEBUG-Level)                   |
| `--version`       | Version anzeigen                                   |

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
