# CLI-Spezifikation: d-migrate

**Kommandozeilen-Schnittstelle — Referenz für Implementierung und Nutzung**

> Dokumenttyp: Spezifikation / Referenz
>
> **Implementierungsstatus**: Aktuell ist nur `schema validate` implementiert (Milestone 0.1.0). Alle weiteren Kommandos sind für spätere Milestones geplant und hier als Spezifikation dokumentiert.

---

## 1. Allgemeine Konventionen

### 1.1 Aufruf-Syntax

```
d-migrate <command> <subcommand> [flags] [arguments]
```

- **Commands**: Oberste Ebene (`schema`, `data`, `transform`, `generate`, `export`, `validate`, `config`)
- **Subcommands**: Aktion innerhalb eines Commands (`schema validate`, `data export`)
- **Flags**: Optionen mit `--` Präfix, Kurzform mit `-` (`--format json`, `-f json`)
- **Arguments**: Positionelle Argumente (selten, nur wo eindeutig)

### 1.2 Globale Flags

Diese Flags sind bei allen Kommandos verfügbar:

| Flag | Kurzform | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--config` | `-c` | Pfad | `.d-migrate.yaml` | Pfad zur Konfigurationsdatei |
| `--lang` | | String | System-Locale | Sprache für Ausgaben (`de`, `en`) |
| `--output-format` | | String | `plain` | Ausgabeformat: `plain`, `json`, `yaml` |
| `--verbose` | `-v` | Boolean | false | Erweiterte Ausgabe (DEBUG-Level) |
| `--quiet` | `-q` | Boolean | false | Nur Fehler ausgeben |
| `--no-color` | | Boolean | false | Farbausgabe deaktivieren |
| `--no-progress` | | Boolean | false | Fortschrittsanzeige deaktivieren |
| `--yes` | `-y` | Boolean | false | Bestätigungen automatisch akzeptieren |
| `--version` | `-V` | Boolean | | Version anzeigen und beenden |
| `--help` | `-h` | Boolean | | Hilfe anzeigen und beenden |

`--verbose` und `--quiet` schließen sich gegenseitig aus.

### 1.4 Verbindungsnamen

Überall wo `--source` oder `--target` eine Datenbank-URL erwartet, kann alternativ ein benannter Verbindungsname aus der [Konfiguration](./connection-config-spec.md#5-profil-unterstützung) verwendet werden. Enthält der Wert kein URL-Schema (`://`), wird er als Verbindungsname in `database.connections` nachgeschlagen.

```bash
# Direkte URL
d-migrate data export --source postgresql://admin@localhost/mydb --format json

# Benannte Verbindung aus .d-migrate.yaml
d-migrate data export --source staging --format json
```

### 1.3 Pfad-Konventionen

- Relative Pfade werden relativ zum aktuellen Arbeitsverzeichnis aufgelöst
- `~` wird zum Home-Verzeichnis expandiert
- Pfade mit Leerzeichen müssen in Anführungszeichen stehen
- Ausgabe-Verzeichnisse werden automatisch erstellt, wenn sie nicht existieren

---

## 2. Exit-Codes

| Code | Name | Bedeutung | Beispiel |
|---|---|---|---|
| `0` | `SUCCESS` | Operation erfolgreich abgeschlossen | Schema validiert ohne Fehler |
| `1` | `ERROR` | Allgemeiner Fehler | Unerwartete Exception |
| `2` | `USAGE_ERROR` | Ungültige Argumente oder Flags | Fehlender Pflicht-Parameter |
| `3` | `VALIDATION_ERROR` | Schema- oder Daten-Validierung fehlgeschlagen | FK referenziert nicht-existierende Tabelle |
| `4` | `CONNECTION_ERROR` | Datenbankverbindung fehlgeschlagen | DB nicht erreichbar, Credentials falsch |
| `5` | `MIGRATION_ERROR` | Fehler während Daten-Migration | Constraint-Verletzung beim Import |
| `6` | `AI_ERROR` | KI-Provider nicht erreichbar oder Transformation fehlgeschlagen | Ollama nicht gestartet |
| `7` | `CONFIG_ERROR` | Konfigurationsdatei ungültig oder nicht lesbar | Ungültiges YAML in `.d-migrate.yaml` |
| `130` | `INTERRUPTED` | Durch Benutzer abgebrochen (Ctrl+C) | SIGINT empfangen |

### 2.1 Exit-Code-Regeln

- Bei **Warnungen ohne Fehler**: Exit-Code `0` (Warnungen gehen nach stderr)
- Bei **mehreren Fehlern**: Der spezifischste Exit-Code wird verwendet
- Bei `--output-format json`: Exit-Code bleibt gleich, Details im JSON-Output
- Fortschrittsanzeige geht immer nach **stderr**, Ergebnisse nach **stdout**

---

## 3. Ausgabeformate

### 3.1 Plain (Default)

Menschenlesbare Ausgabe mit optionaler Farbcodierung:

```
$ d-migrate schema validate --source schema.yaml

Validating schema 'E-Commerce System' v1.0.0...

  Tables:     12 found
  Columns:    87 found
  Indices:    15 found
  Constraints: 8 found

Results:
  ✓ Syntactic validation passed
  ✓ Referential integrity passed
  ⚠ Warning: Column 'orders.price' uses FLOAT — consider DECIMAL for monetary values
  ✗ Error: Foreign key 'orders.customer_id' references non-existent table 'clients'

Validation failed: 1 error, 1 warning
```

**Farbcodierung** (deaktivierbar mit `--no-color`):
- Grün: Erfolg (✓)
- Gelb: Warnung (⚠)
- Rot: Fehler (✗)
- Cyan: Informativ (Zahlen, Pfade)

### 3.2 JSON

Maschinenlesbare Ausgabe für CI/CD-Integration und Scripting:

```json
{
  "command": "schema.validate",
  "status": "failed",
  "exit_code": 3,
  "duration_ms": 142,
  "schema": {
    "name": "E-Commerce System",
    "version": "1.0.0"
  },
  "summary": {
    "tables": 12,
    "columns": 87,
    "indices": 15,
    "constraints": 8
  },
  "results": [
    {
      "level": "info",
      "check": "syntactic_validation",
      "passed": true
    },
    {
      "level": "info",
      "check": "referential_integrity",
      "passed": true
    },
    {
      "level": "warning",
      "object": "orders.price",
      "message": "Column uses FLOAT — consider DECIMAL for monetary values",
      "code": "W001"
    },
    {
      "level": "error",
      "object": "orders.customer_id",
      "message": "Foreign key references non-existent table 'clients'",
      "code": "E002"
    }
  ],
  "errors": 1,
  "warnings": 1
}
```

### 3.3 YAML

Wie JSON, aber im YAML-Format (nützlich für Weiterverarbeitung in Konfigurationen):

```yaml
command: schema.validate
status: failed
exit_code: 3
errors: 1
warnings: 1
results:
  - level: error
    object: orders.customer_id
    message: "Foreign key references non-existent table 'clients'"
    code: E002
```

---

## 4. Fehler- und Warnungs-Codes

### 4.1 Namensschema

```
E001 - E099: Validierungsfehler
E100 - E199: Verbindungsfehler
E200 - E299: Migrationsfehler
E300 - E399: KI-Fehler
E400 - E499: Konfigurationsfehler

W001 - W099: Validierungswarnungen
W100 - W199: Kompatibilitätswarnungen
W200 - W299: Performance-Warnungen
```

### 4.2 Validierungsfehler (E001-E099)

| Code | Meldung | Aktion |
|---|---|---|
| E001 | Table has no columns | Spalten hinzufügen |
| E002 | Foreign key references non-existent table | Tabellennamen prüfen |
| E003 | Foreign key references non-existent column | Spaltennamen prüfen |
| E004 | Duplicate column name in table | Spalte umbenennen |
| E005 | Index references non-existent column | Index-Spalten prüfen |
| E006 | Enum values must not be empty | Werte hinzufügen |
| E007 | ref_type references non-existent custom type | Typ in custom_types definieren |
| E008 | Table has no primary key | primary_key oder identifier-Spalte hinzufügen |
| E009 | Default value incompatible with column type | Default-Wert korrigieren |
| E010 | precision and scale required for decimal type | Attribute ergänzen |
| E011 | max_length must be positive | Positiven Wert setzen |
| E012 | Check expression references unknown column | Expression prüfen |
| E013 | Enum: exactly one of ref_type or values required | Eines von beiden verwenden |
| E014 | char: length is required and must be positive | Attribut ergänzen |
| E015 | array: element_type is required | Attribut ergänzen |
| E016 | Partition key references non-existent column | Spaltenname prüfen |
| E017 | Foreign key type incompatible with referenced column | Typen angleichen |
| E018 | Trigger references non-existent table | Tabellennamen prüfen |

### 4.3 Validierungswarnungen (W001-W099)

| Code | Meldung |
|---|---|
| W001 | Column uses FLOAT — consider DECIMAL for monetary values |
| W002 | Circular foreign key dependency detected |
| W003 | Table name exceeds 63 characters (PostgreSQL limit) |
| W004 | Column name is a reserved word in target database |

### 4.4 Verbindungsfehler (E100-E199)

| Code | Meldung | Kontext |
|---|---|---|
| E100 | Unknown database dialect | Unbekannter Dialekt in URL |
| E101 | Connection refused | Host/Port nicht erreichbar |
| E102 | Authentication failed | Credentials ungültig |
| E103 | Database does not exist | Datenbank nicht vorhanden |
| E104 | SSL connection failed | SSL/TLS-Handshake fehlgeschlagen |
| E105 | Connection timed out | Verbindungs-Timeout überschritten |
| E106 | Connection pool exhausted | Alle Pool-Verbindungen belegt |
| E107 | Connection lost during operation | Verbindung während Ausführung unterbrochen |

### 4.5 Kompatibilitätswarnungen (W100-W199)

| Code | Meldung |
|---|---|
| W100 | TIMESTAMP WITH TIME ZONE → DATETIME: timezone information lost |
| W101 | JSONB → JSON: GIN index not supported |
| W102 | HASH index not supported on InnoDB, using BTREE |
| W103 | Materialized View not supported, using regular View |
| W104 | XML type not supported, using TEXT fallback |

### 4.6 Kompatibilitätsfehler (E050-E069)

Nicht-automatisch auflösbare Inkompatibilitäten. Der Prozess stoppt mit Hinweis auf manuelle Aktion:

| Code | Meldung |
|---|---|
| E050 | Composite type not natively supported, manual conversion required |
| E051 | Named sequence not natively supported, manual emulation required |
| E052 | Feature requires KI-assisted transformation |

---

## 5. Fehlerausgabe

### 5.1 stderr-Format (Plain)

Fehler gehen nach stderr, Ergebnisse nach stdout. Dies ermöglicht sauberes Piping:

```
$ d-migrate schema generate --source schema.yaml --target postgres > output.sql

[ERROR] E002: Foreign key 'orders.customer_id' references non-existent table 'clients'
  → File: schema.yaml
  → Path: tables.orders.columns.customer_id.references.table
  → Hint: Available tables: customers, products, order_items

[WARNING] W001: Column 'orders.price' uses FLOAT — consider DECIMAL for monetary values
  → File: schema.yaml
  → Path: tables.orders.columns.price
```

### 5.2 Fehlerstruktur

Jede Fehlermeldung enthält:

| Feld | Beschreibung | Immer vorhanden |
|---|---|---|
| Code | Fehler-/Warnungs-Code (`E002`, `W001`) | Ja |
| Message | Lokalisierte Meldung | Ja |
| File | Quell-Datei | Wenn zutreffend |
| Path | Pfad im Schema (dot-notation) | Wenn zutreffend |
| Hint | Handlungsvorschlag | Wenn verfügbar |
| Line | Zeilennummer in der Datei | Wenn verfügbar |

---

## 6. Kommando-Referenz

### 6.1 schema

#### `schema validate` ✅

Validiert eine Schema-Definition.

```
d-migrate schema validate --source <path>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |

Exit: `0` bei Erfolg, `3` bei Validierungsfehlern.

#### `schema generate` ✅

Generiert datenbankspezifisches DDL aus einer Schema-Definition.

```
d-migrate schema generate --source <path> --target <dialect> [--output <path>]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |
| `--target` | Ja | Dialekt | Zieldatenbank (`postgresql`, `mysql`, `sqlite`) |
| `--output` | Nein | Pfad | Ausgabedatei (Default: stdout) |
| `--generate-rollback` | Nein | Boolean | Zusätzlich Rollback-DDL generieren |

| `--report` | Nein | Pfad | Transformations-Report separat speichern (Default: `<output>.report.yaml`) |

Dialekt-Aliase: `postgres` → `postgresql`, `maria` / `mariadb` → `mysql`

**Ausgabeverhalten**:
- **stdout**: DDL-Output (wenn kein `--output`)
- **stderr**: Warnungen (W1xx) und action_required-Hinweise (E052)
- **`--output`**: DDL in Datei + automatisch `<name>.report.yaml` als Sidecar
- **`--output-format json`**: DDL + Notes + skipped_objects als JSON nach stdout

**Exit-Codes**:
- `0`: DDL erfolgreich generiert (auch bei Warnungen und übersprungenen Objekten)
- `3`: Schema-Validierung fehlgeschlagen (DDL wird nicht erzeugt)
- `7`: Schema-Datei nicht lesbar oder ungültiges YAML

**action_required-Objekte** (z.B. Functions mit anderem source_dialect) werden übersprungen und im Report dokumentiert. Die DDL-Generierung bricht **nicht** ab — der Exit-Code bleibt `0`. Details in [DDL-Generierungsregeln §14.3](./ddl-generation-rules.md#143-verhalten-bei-action_required).

#### `schema reverse` *(geplant: 0.6.0)*

Reverse-Engineering einer bestehenden Datenbank.

```
d-migrate schema reverse --source <url|path> [--source-dialect <dialect>] --output <path>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | URL oder Pfad | DB-Connection-URL oder SQL-DDL-Datei |
| `--source-dialect` | Nein | Dialekt | Quell-Dialekt (Auto-Erkennung wenn nicht angegeben) |
| `--output` | Ja | Pfad | Ausgabe-Schema-Datei |
| `--format` | Nein | String | Ausgabeformat: `yaml` (Default), `json` |
| `--include-procedures` | Nein | Boolean | Stored Procedures/Functions einschließen |
| `--include-views` | Nein | Boolean | Views einschließen |
| `--include-triggers` | Nein | Boolean | Triggers einschließen |
| `--include-all` | Nein | Boolean | Alle Objekte einschließen |

Exit: `0` bei Erfolg, `4` bei Verbindungsfehlern.

#### `schema compare` *(geplant: 0.5.0)*

Vergleicht zwei Schemas und zeigt Unterschiede.

```
d-migrate schema compare --source <path|url> --target <path|url>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad/URL | Erstes Schema (Datei oder DB) |
| `--target` | Ja | Pfad/URL | Zweites Schema (Datei oder DB) |
| `--output` | Nein | Pfad | Diff-Ergebnis in Datei schreiben |

Exit: `0` wenn identisch, `1` wenn Unterschiede gefunden (zur Nutzung in Scripting: `if d-migrate schema compare ...`), `3` bei Validierungsfehlern.

#### `schema migrate` *(geplant: 0.5.0)*

Generiert Migrationsskript (Up + optional Down) aus Schema-Diff.

```
d-migrate schema migrate --source <path> --target <url> [--generate-rollback]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Soll-Schema (YAML) |
| `--target` | Ja | URL | Ist-Datenbank |
| `--output` | Nein | Pfad | Migrationsskript-Ausgabe |
| `--generate-rollback` | Nein | Boolean | Down-Migration erzeugen |

Exit: `0` bei Erfolg, `4` bei Verbindungsfehlern.

#### `schema rollback` *(geplant: 0.5.0)*

Führt ein Rollback-Migrationsskript gegen eine Datenbank aus.

```
d-migrate schema rollback --source <path> --target <url>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Rollback-SQL-Datei (Down-Migration) |
| `--target` | Ja | URL | Ziel-Datenbank |
| `--dry-run` | Nein | Boolean | Nur anzeigen, nicht ausführen |

Exit: `0` bei Erfolg, `4` bei Verbindungsfehlern, `5` bei Migrationsfehlern.

### 6.2 data

#### `data export` *(0.3.0, umgesetzt)*

Streamt Tabellen aus einer Datenbank in JSON, YAML oder CSV. Pull-basiert,
chunk-weise — geeignet auch für Tabellen, die größer sind als der verfügbare
Heap (Plan §2.1, §6.4).

```
d-migrate data export --source <url-or-name> --format <format> [--output <path>]
```

**Auflösung von `--source`** (siehe §1.4 und implementation-plan-0.3.0.md §6.14):

- enthält der Wert `://`, wird er als vollständige Connection-URL behandelt
  und unverändert an den `ConnectionUrlParser` übergeben
- sonst wird er als Connection-Name interpretiert und in
  `database.connections.<name>` der `.d-migrate.yaml` aufgelöst (CLI > ENV >
  Default-Pfad-Priorität, `${ENV_VAR}`-Substitution mit `$${VAR}`-Escape)

| Flag | Pflicht | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--source` | Ja | URL oder Name | — | Connection-URL oder Name aus `.d-migrate.yaml` |
| `--format` | Ja | String | — | Ausgabeformat: `json`, `yaml`, `csv` (kein Default — explizit setzen, §6.15) |
| `--output`, `-o` | Nein | Pfad | stdout | Ziel-Datei (Single-Tabelle) oder Verzeichnis (mit `--split-files`) |
| `--tables` | Nein | Liste | alle Tabellen | Nur diese Tabellen (kommasepariert). Strikt validiert gegen `[A-Za-z_][A-Za-z0-9_]*` (optional `schema.table`); ungültige Werte → Exit 2. |
| `--filter` | Nein | String | — | Roh-WHERE-Klausel ohne `WHERE`-Keyword. **Nicht parametrisiert** — Trust-Boundary ist die lokale Shell (Plan §6.7). |
| `--encoding` | Nein | String | `utf-8` | Output-Encoding (z.B. `utf-8`, `iso-8859-1`, `utf-16`) |
| `--chunk-size` | Nein | Integer | `10000` | Rows pro Streaming-Chunk |
| `--split-files` | Nein | Boolean | aus | Eine Datei pro Tabelle in `--output <dir>`. Bei mehreren Tabellen Pflicht. |
| `--csv-delimiter` | Nein | Char | `,` | CSV-Spalten-Trennzeichen (genau ein Zeichen) |
| `--csv-bom` | Nein | Boolean | aus | UTF-8 BOM-Bytes vor dem CSV-Output schreiben |
| `--csv-no-header` | Nein | Boolean | aus | Header-Zeile bei CSV unterdrücken (Default: Header an, §6.17) |
| `--null-string` | Nein | String | `""` | CSV-NULL-Repräsentation |

**Output-Auflösung** (Plan §6.9):

| `--output` | `--split-files` | Tabellen | Resultat |
|---|---|---|---|
| nicht gesetzt | aus | 1 | stdout |
| nicht gesetzt | aus | ≥2 | **Exit 2** (Hinweis auf `--split-files`) |
| nicht gesetzt | an | beliebig | **Exit 2** (`--split-files` braucht `--output <dir>`) |
| Datei | aus | 1 | Single-File |
| Datei | aus | ≥2 | **Exit 2** |
| Verzeichnis | an | beliebig | One file per table (`<table>.<format>`) |
| Verzeichnis | aus | beliebig | **Exit 2** |

**Exit-Codes** (vereinfachte Sicht der globalen Tabelle in §2):

| Code | Trigger |
|---|---|
| `0` | Erfolg, alle Tabellen geschrieben |
| `2` | CLI-Fehler: ungültige Optionen, unzulässige Flag-Kombination, ungültiger `--csv-delimiter`/`--encoding`/`--tables`-Identifier, unverträgliche `--output`/`--split-files`-Kombi |
| `4` | Connection-Fehler (HikariCP konnte keine Connection öffnen, `TableLister` failed) |
| `5` | Export-Fehler während Streaming (SQLException, IOException, Writer-Failure, fehlende Tabelle) |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml` nicht ladbar/parsebar, unbekannter Connection-Name, fehlende ENV-Variable, kein Treiber für Dialect) |

**Beispiele**:

```bash
# stdout, eine Tabelle, JSON
d-migrate data export --source sqlite:///tmp/app.db --format json --tables users

# Named Connection aus .d-migrate.yaml
d-migrate data export --source local_pg --format yaml --tables users

# Mehrere Tabellen, eine Datei pro Tabelle
d-migrate data export --source local_pg --format csv \
    --tables users,orders,products --output ./exports --split-files

# CSV mit Semikolon-Delimiter und BOM für Excel
d-migrate data export --source local_pg --format csv --tables customers \
    --csv-delimiter ';' --csv-bom --output customers.csv

# Filter (Trust-Boundary: lokale Shell, nicht parametrisiert!)
d-migrate data export --source prod --format json --tables orders \
    --filter "created_at > '2026-01-01'" --output recent.json

# Auto-Discovery aller Tabellen mit Split-Files
d-migrate data export --source local_pg --format json \
    --output ./full-dump --split-files
```

> **0.4.0**: `--incremental` für inkrementellen Export anhand einer
> Trigger-Spalte (LF-013). In 0.3.0 nicht enthalten.

#### `data import` *(geplant: 0.4.0)*

Importiert Daten in eine Datenbank.

```
d-migrate data import --source <path> --target <url>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Datendatei(en) (JSON/YAML/CSV) |
| `--target` | Ja | URL | Ziel-Datenbank |
| `--schema` | Nein | Pfad | Schema zur Validierung |
| `--on-error` | Nein | String | `abort` (Default), `skip`, `log` |
| `--chunk-size` | Nein | Integer | Datensätze pro Transaktion (Default: 10000) |
| `--resume` | Nein | Boolean | Ab letztem Checkpoint fortsetzen |

Exit: `0` bei Erfolg, `4` bei Verbindungsfehlern, `5` bei Importfehlern.

#### `data seed` *(geplant: 1.3.0)*

Generiert Testdaten und importiert sie.

```
d-migrate data seed --schema <path> --target <url>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--schema` | Ja | Pfad | Schema-Definition |
| `--target` | Ja | URL | Ziel-Datenbank |
| `--count` | Nein | Integer | Datensätze pro Tabelle (Default: 100) |
| `--seed` | Nein | Long | Random-Seed für Reproduzierbarkeit |
| `--locale` | Nein | String | Locale für generierte Daten (Default: `en`) |
| `--rules` | Nein | Pfad | Regeldatei für Generierung |
| `--ai-backend` | Nein | String | KI-Provider für kontextrelevante Daten |

Exit: `0` bei Erfolg.

### 6.3 transform

#### `transform procedure` *(geplant: 1.1.0)*

Transformiert Stored Procedures/Functions zwischen Dialekten.

```
d-migrate transform procedure --source <path> --procedure <name> --ai-backend <provider>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei mit Prozeduren |
| `--procedure` | Ja | String | Name der Prozedur/Funktion |
| `--ai-backend` | Ja | String | KI-Provider (`ollama`, `anthropic`, `openai`, ...) |
| `--model` | Nein | String | Spezifisches Modell (überschreibt Config) |
| `--output-format` | Nein | String | `markdown` (Default), `yaml` |
| `--output` | Nein | Pfad | Ausgabedatei |
| `--compare` | Nein | Boolean/String | A/B-Test: kommaseparierte Provider-Liste |

Exit: `0` bei Erfolg, `6` bei KI-Fehlern.

### 6.4 generate

#### `generate procedure` *(geplant: 1.1.0)*

Generiert DB-spezifischen Code aus Markdown-Zwischenformat.

```
d-migrate generate procedure --source <path> --target <dialect>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Markdown-Spezifikation |
| `--target` | Ja | Dialekt | Ziel-Datenbank |
| `--output` | Nein | Pfad | Ausgabedatei |

Exit: `0` bei Erfolg.

### 6.5 export (Integrationen)

#### `export flyway` / `export liquibase` / `export django` / `export knex` *(geplant: 0.7.0)*

Generiert Migrationsdateien für externe Tools.

```
d-migrate export flyway --source <path> --output <dir>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei |
| `--output` | Ja | Pfad | Ausgabeverzeichnis |
| `--target` | Nein | Dialekt | Ziel-Datenbank (Default: `postgresql`) |
| `--version` | Nein | String | Versionsnummer für Migration |

Exit: `0` bei Erfolg.

### 6.6 validate

#### `validate data` *(geplant: 0.4.0)*

Validiert Daten gegen ein Schema.

```
d-migrate validate data --source <path> --schema <path>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Datendatei (JSON/YAML/CSV) |
| `--schema` | Ja | Pfad | Schema-Definition |

Exit: `0` bei Erfolg, `3` bei Validierungsfehlern.

#### `validate procedure` *(geplant: 1.1.0)*

Validiert eine generierte Stored Procedure gegen eine Ziel-Datenbank.

```
d-migrate validate procedure --source <path> --target <url>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | SQL-Datei mit Prozedur |
| `--target` | Ja | URL | Ziel-Datenbank |
| `--check-syntax` | Nein | Boolean | Nur Syntax prüfen (Default: true) |
| `--check-equivalence` | Nein | Boolean | Semantische Äquivalenz prüfen |
| `--source-db` | Nein | URL | Quell-DB für Äquivalenzprüfung |
| `--test-data` | Nein | Pfad | Testdaten für Äquivalenzprüfung |

Exit: `0` bei Erfolg, `3` bei Validierungsfehlern.

### 6.7 config

#### `config credentials set` *(geplant: 1.0.0)*

Speichert verschlüsselte Datenbank-Credentials.

```
d-migrate config credentials set --name <connection> --user <user> --password <password>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--name` | Ja | String | Verbindungsname |
| `--user` | Ja | String | Benutzername |
| `--password` | Ja | String | Passwort (wird interaktiv abgefragt wenn nicht angegeben) |

Ergebnis: Credentials werden in `~/.d-migrate/credentials.enc` (AES-256) gespeichert. Details in der [Connection- und Konfigurationsspezifikation §4](./connection-config-spec.md#4-credential-management).

Exit: `0` bei Erfolg, `7` bei Konfigurationsfehlern.

#### `config credentials list` *(geplant: 1.0.0)*

Listet gespeicherte Verbindungsnamen (ohne Passwörter).

```
d-migrate config credentials list
```

Exit: `0` bei Erfolg.

#### `config show` *(geplant: 1.0.0)*

Zeigt die aktive Konfiguration (gemerged aus allen Quellen).

```
d-migrate config show [--section <section>]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--section` | Nein | String | Nur diesen Abschnitt zeigen (`database`, `ai`, `pipeline`, ...) |

Sensible Werte (Passwörter, API-Keys) werden maskiert als `***`.

Exit: `0` bei Erfolg, `7` bei Konfigurationsfehlern.

---

## 7. Fortschrittsanzeige

### 7.1 Format

Langläufige Operationen (>2 Sekunden) zeigen automatisch eine Fortschrittsanzeige:

```
Exporting table 'orders' [████████░░░░░░░░] 52% | 520,000/1,000,000 | ~45s remaining
```

Struktur:
```
<Operation> '<Objekt>' [<Balken>] <Prozent> | <Fortschritt>/<Gesamt> | ~<Rest> remaining
```

### 7.2 Multi-Tabellen-Fortschritt

Bei paralleler Verarbeitung mehrerer Tabellen:

```
Exporting 5 tables (2 active, 1 completed, 2 pending)
  ✓ customers       1,234 records (0.2s)
  ● orders          [████████░░░░░░░░] 52% | 520,000/1,000,000
  ● order_items     [██░░░░░░░░░░░░░░] 12% | 240,000/2,000,000
  ○ products        pending
  ○ categories      pending
```

Symbole: `✓` abgeschlossen, `●` aktiv, `○` wartend

### 7.3 Steuerung

- `--no-progress`: Keine Fortschrittsanzeige (für Scripting)
- `--quiet`: Nur Fehler
- Fortschrittsanzeige geht nach **stderr** (stdout bleibt sauber für Piping)
- Bei `--output-format json`: Fortschritt als JSON-Events auf stderr

---

## 8. Interaktiver Modus

### 8.1 Bestätigungen

Destruktive Operationen erfordern Bestätigung:

```
$ d-migrate data import --source data.json --target postgres://localhost/prod

WARNING: Target database 'prod' contains existing data.
  Tables to be modified: orders (1,234,567 rows), customers (45,678 rows)

Continue? This will insert/update data in 2 tables. [y/N]:
```

### 8.2 Nicht-interaktiver Modus

Für CI/CD-Pipelines:

```bash
# Bestätigungen automatisch akzeptieren
d-migrate data import --source data.json --target postgres://... --yes

# Oder via Umgebungsvariable
D_MIGRATE_ASSUME_YES=true d-migrate data import ...
```

### 8.3 Sensitive Eingaben

Passwörter können interaktiv abgefragt werden, wenn sie nicht in der URL enthalten sind:

```
$ d-migrate data export --source postgres://admin@localhost/mydb

Password for 'admin@localhost':
```

Alternative: Umgebungsvariable `D_MIGRATE_DB_PASSWORD` oder Konfigurationsdatei.

---

## 9. Umgebungsvariablen

| Variable | Entspricht | Beschreibung |
|---|---|---|
| `D_MIGRATE_CONFIG` | `--config` | Pfad zur Konfigurationsdatei |
| `D_MIGRATE_LANG` | `--lang` | Sprache |
| `D_MIGRATE_OUTPUT_FORMAT` | `--output-format` | Ausgabeformat |
| `D_MIGRATE_NO_COLOR` | `--no-color` | Farbausgabe deaktivieren |
| `D_MIGRATE_ASSUME_YES` | `--yes` | Bestätigungen überspringen |
| `D_MIGRATE_DB_PASSWORD` | | Datenbank-Passwort |
| `D_MIGRATE_AI_API_KEY` | | KI-Provider API-Key (Fallback) |

Priorität: CLI-Argument > Umgebungsvariable > Konfigurationsdatei > Default

---

## 10. Piping und Scripting

### 10.1 stdout/stderr-Trennung

```bash
# DDL nach stdout, Warnungen nach stderr
d-migrate schema generate --source schema.yaml --target mysql > schema.sql

# Nur Fehler sehen
d-migrate schema validate --source schema.yaml 2>&1 >/dev/null

# JSON-Output pipen
d-migrate schema validate --source schema.yaml --output-format json | jq '.errors'
```

### 10.2 Pipe-Erkennung

Wenn stdout kein Terminal ist (Pipe/Redirect):
- Farbausgabe wird automatisch deaktiviert
- Fortschrittsanzeige wird automatisch deaktiviert
- Reine Daten gehen nach stdout

### 10.3 Stdin-Unterstützung

```bash
# Schema von stdin lesen
cat schema.yaml | d-migrate schema validate --source -

# DDL von stdin parsen
cat create_tables.sql | d-migrate schema reverse --source - --source-dialect postgres --output schema.yaml
```

`-` als Pfad bedeutet stdin/stdout.

---

## Verwandte Dokumentation

- [Design](./design.md) — CLI-Design §5, Fehlerbehandlung §8
- [Architektur](./architecture.md) — CLI-Modul, Clikt-Framework
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — Schema-Validierungsregeln §13
- [Lastenheft](./lastenheft-d-migrate.md) — LF-012 (CLI), LN-015 (Dokumentation), LN-016 (Fehlermeldungen)

---

**Version**: 1.3
**Stand**: 2026-04-05
**Status**: `schema validate` (0.1.0) und `schema generate` (0.2.0) implementiert, weitere Kommandos in Planung
