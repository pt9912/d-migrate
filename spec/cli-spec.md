# CLI-Spezifikation: d-migrate

**Kommandozeilen-Schnittstelle — Referenz für Implementierung und Nutzung**

> Dokumenttyp: Spezifikation / Referenz
>
> **Implementierungsstatus**: Implementiert sind `schema validate` (0.1.0), `schema generate` (0.2.0), `data export` (0.3.0), `data import` (0.4.0), `schema compare` (0.5.0 file-based, 0.6.0 mit DB-Operanden, 0.9.4 operandseitige Diagnose und `source_operand`/`target_operand`), `schema reverse` (0.6.0, 0.9.4 MySQL-Sequence-Reverse mit `W116`) und `data transfer` (0.6.0).

---

## 1. Allgemeine Konventionen

### 1.1 Aufruf-Syntax

```
d-migrate <command> <subcommand> [flags] [arguments]
```

- **Commands**: Oberste Ebene — implementiert: `schema`, `data`, `export` (seit 0.7.0); geplant: `transform` (1.1.0)
- **Subcommands**: Aktion innerhalb eines Commands (`schema validate`, `data export`)
- **Flags**: Optionen mit `--` Präfix, Kurzform mit `-` (`--format json`, `-f json`)
- **Arguments**: Positionelle Argumente (selten, nur wo eindeutig)

### 1.2 Globale Flags

Diese Flags sind bei allen Kommandos verfügbar:

| Flag | Kurzform | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--config` | `-c` | Pfad | `./.d-migrate.yaml` | Pfad zur effektiven Konfigurationsdatei; Prioritaet: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml` |
| `--lang` | | String | (kein Default; Fallback-Kette siehe unten) | Sprachwahl fuer menschenlesbare Ausgaben (seit 0.9.0 aktiv, siehe `docs/planning/ImpPlan-0.9.0-A.md` §4.1/§4.2). Akzeptiert fuer 0.9.0 nur die gebundelten Produktsprachen `de` und `en` inkl. kanonisierbarer Varianten wie `de-DE`, `de_DE`, `en-US`, `en_US`. Andere Werte fuehren zu Exit 2. Gewinnt gegen `D_MIGRATE_LANG`, `LC_ALL`/`LANG`, `i18n.default_locale` und System-Locale. Der generische Env-/Config-/System-Pfad bleibt toleranter und faellt fuer unbekannte Bundles weiterhin auf das englische Root-Bundle zurueck. |
| `--output-format` | | String | `plain` | Ausgabeformat: `plain`, `json`, `yaml` |
| `--verbose` | `-v` | Boolean | false | Erweiterte Ausgabe (DEBUG-Level) |
| `--quiet` | `-q` | Boolean | false | Nur Fehler ausgeben |
| `--no-color` | | Boolean | false | Farbausgabe deaktivieren |
| `--no-progress` | | Boolean | false | Fortschrittsanzeige deaktivieren |
| `--yes` | `-y` | Boolean | false | Bestätigungen automatisch akzeptieren |
| `--version` | `-V` | Boolean | | Version anzeigen und beenden |
| `--help` | `-h` | Boolean | | Hilfe anzeigen und beenden |

`--verbose` und `--quiet` schließen sich gegenseitig aus.

**`--lang`-Vertrag ab 0.9.0 (`docs/planning/ImpPlan-0.9.0-A.md` §4.1–§4.2)**:

- Prioritaetskette: `--lang` > `D_MIGRATE_LANG` > `LC_ALL` > `LANG` > `i18n.default_locale` > System-Locale > Fallback `en`.
- Unterstuetzte Produktsprachen sind aktuell `de` und `en`. Kanonisierbare Varianten wie `de-DE`, `de_DE`, `en-US`, `en_US` werden akzeptiert und normalisiert.
- Ein explizit gesetztes, aber nicht unterstuetztes `--lang` (z.B. `fr`, `zh`) ist ein lokaler CLI-Fehler und endet mit **Exit 2**. Es wird **nicht** still auf Englisch zurueckgefallen.
- Der generische Env-/Config-/System-Pfad bleibt toleranter: dort fuehrt eine syntaktisch gueltige, aber nicht gebundelte Sprache zum Root-Bundle-Fallback in `MessageResolver`, nicht zu einem Hard-Error.
- Strukturierte JSON-/YAML-Ausgaben bleiben sprachstabil englisch, unabhaengig von der aufgeloesten Locale.

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
| `7` | `LOCAL_ERROR` | Lokaler Konfigurations-, Parse-, Datei-, I/O-, Render- oder Kollisionsfehler | Ungültiges YAML in `.d-migrate.yaml`, Schema-Datei nicht lesbar, Ausgabepfad nicht beschreibbar |
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

Vertragsregeln fuer strukturierte Ausgabe:

- Feldnamen, Command-IDs, Statuswerte, Exit-Codes, Warning-/Error-Codes und vergleichbare API-artige Vertragsflaechen bleiben englisch und stabil.
- Freie Fehlermeldungstexte in JSON/YAML bleiben fuer 0.8.0 ebenfalls englisch und stabil, bis ein explizit versionierter Gegenvertrag definiert ist.
- Lokalisiert werden duerfen nur menschenlesbare Plain-Text-Ausgaben auf stdout/stderr.

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
| E020 | Declared view dependency references non-existent view | View-Namen prüfen |

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
| W113 | View dependencies could not be fully topologically sorted; original order is used for the remaining views |
| W120 | SRID could not be fully transferred to target dialect (spatial best-effort, `schema generate`) |

### 4.6 Kompatibilitätsfehler (E050-E069)

Nicht-automatisch auflösbare Inkompatibilitäten. Der Prozess stoppt mit Hinweis auf manuelle Aktion:

| Code | Meldung |
|---|---|
| E052 | Spatial object cannot be generated with the chosen spatial profile |
| E053 | Dialect-specific SQL content requires manual transformation or implementation |
| E054 | Object type is not supported in the target dialect |
| E055 | Partitioning is not supported in the target dialect |
| E056 | Named sequence cannot be generated natively and needs emulation/manual handling |
| E120 | Unknown `geometry_type` value (schema validation) |
| E121 | `srid` must be greater than 0 (schema validation) |

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
d-migrate schema generate --source <path> --target <dialect> [--output <path>] [--split single|pre-post]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |
| `--target` | Ja | Dialekt | Zieldatenbank (`postgresql`, `mysql`, `sqlite`) |
| `--output` | Nein | Pfad | Ausgabedatei (Default: stdout) |
| `--generate-rollback` | Nein | Boolean | Zusätzlich Rollback-DDL generieren |
| `--spatial-profile` | Nein | String | Spatial-Profil für `geometry`-Spalten (siehe unten) |
| `--split` | Nein | `single` / `pre-post` | DDL-Ausgabemodus (Default: `single`). `pre-post` erzeugt importfreundliche Artefakte (pre-data/post-data) |
| `--mysql-named-sequences` | Nein | `action_required` / `helper_table` | MySQL-Sequence-Strategie (Default: `action_required`). Nur zusammen mit `--target mysql` zulaessig; bei PostgreSQL/SQLite: Exit 2. `helper_table` emuliert benannte Sequences ueber kanonische Hilfsobjekte (`dmg_sequences`, `dmg_nextval`/`dmg_setval`, `BEFORE INSERT`-Trigger). |
| `--report` | Nein | Pfad | Transformations-Report separat speichern (Default: `<output>.report.yaml`) |

Dialekt-Aliase: `postgres` → `postgresql`, `maria` / `mariadb` → `mysql`

**`--spatial-profile`**: Steuert, wie `geometry`-Spalten in DDL ueberfuehrt werden.
Das Profil ist Generator-Konfiguration und kein Teil des neutralen Schemas.

| `--target` | Zulässige Werte | Default |
|---|---|---|
| `postgresql` | `postgis`, `none` | `postgis` |
| `mysql` | `native`, `none` | `native` |
| `sqlite` | `spatialite`, `none` | `none` |

Eine unzulässige Kombination aus `--target` und `--spatial-profile` (z.B. `--target mysql --spatial-profile postgis`) erzeugt einen Nutzungsfehler (Exit-Code 2) noch vor der DDL-Generierung. Enthält das Schema keine `geometry`-Spalten, hat `--spatial-profile` keine Wirkung.

**Ausgabeverhalten**:
- **stdout**: DDL-Output (wenn kein `--output`)
- **stderr**: Warnungen (W1xx, W114–W117, W120) und action_required-Hinweise (E052-E056, E122–E124)
- **`--output`**: DDL in Datei + automatisch `<name>.report.yaml` als Sidecar
- **`--output-format json`**: DDL + Notes + skipped_objects als JSON nach stdout

Spatial-spezifische Ausgaben:
- **E052** (Spatial-Profil blockiert Tabelle): Erscheint auf stderr und in `skipped_objects` des Reports. Die gesamte Tabelle wird uebersprungen; keine partielle DDL.
- **E053** (manuelle SQL-Transformation/Implementierung): Erscheint bei Views, Functions, Procedures oder Triggers mit nicht automatisch uebertragbarem SQL-Inhalt.
- **E054** (Objekttyp nicht unterstuetzt): Erscheint bei im Zieldialekt nicht verfuegbaren Objekten oder Constraint-Typen.
- **E055** (Partitionierung nicht unterstuetzt): Erscheint bei nicht nativ unterstuetzter Partitionierung.
- **E056** (Sequence-/Emulationsfall): Erscheint bei benannten Sequences ohne nativen Zieldialekt-Support.
- **W120** (SRID nicht vollständig übertragbar): Erscheint auf stderr und in `notes` des Reports. Die DDL-Generierung wird fortgesetzt.

Spatial-Bezug fuer `--generate-rollback`, JSON-Output und Sidecar-Report:
- **`--generate-rollback`**: Rollback-DDL enthaelt die inversen Spatial-Statements (z.B. `DiscardGeometryColumn` fuer SpatiaLite). Blockierte Tabellen (E052) erzeugen kein Rollback-DDL. Details: [DDL-Generierungsregeln §16.7](./ddl-generation-rules.md).
- **`--output-format json`**: Action-required-Eintraege (`E052`-`E056`) erscheinen in `notes` und/oder `skipped_objects`, W120 in `notes`.
- **Sidecar-Report**: Spatial-Warnungen und uebersprungene Objekte werden im Report dokumentiert wie alle anderen `action_required`-Faelle.

Sequence-spezifische Ausgaben (0.9.3, `--mysql-named-sequences`):
- **E056** (Sequence/Emulationsfall): Im `action_required`-Modus fuer uebersprungene Sequences und fuer Spalten mit `SequenceNextVal`-Default ohne Emulation.
- **E122** (Legacy-Notation): Schema verwendet die abgekuendigte `nextval(...)`-Notation statt `default: { sequence_nextval: ... }`.
- **E123** (Fehlende Sequence): `sequence_nextval` referenziert eine nicht definierte Sequence.
- **E124** (Namenskollision): Ein neutrales Schema-Objekt kollidiert mit einem reservierten Support-Objektnamen (`dmg_sequences`, `dmg_nextval`, `dmg_setval`).
- **W114** (Cache nicht emuliert): Sequence hat `cache`-Wert, der im `helper_table`-Modus nur als Metadatum gespeichert, aber nicht als Preallocation umgesetzt wird.
- **W115** (Lossy Trigger-Semantik): `SequenceNextVal` auf einer Spalte nutzt MySQL-Trigger; explizites `NULL` wird wie ein ausgelassener Wert behandelt.
- **W116** (Fehlende Support-Objekte): Sequence-Metadaten beim Reverse aus `dmg_sequences` rekonstruiert, aber erforderliche Support-Objekte (Routinen und/oder Trigger) fehlen oder sind nicht kanonisch. Die Sequence ist dann rekonstruierbar, aber nicht voll betriebsfaehig. `W116` erscheint als Reverse-Note auf Sequence- oder Spaltenebene. Bei `schema compare` bleibt `W116` eine operandseitige Diagnose: es erzeugt keinen eigenen Diff-Eintrag und beeinflusst den Exit-Code nicht (Exit folgt nur aus Validation oder echtem Schema-Diff). Aktiv seit 0.9.4.
- **W117** (Transaktionsgebundene Werte): Sequence-Werte im `helper_table`-Modus werden bei Rollback zurueckgerollt — anders als native PostgreSQL-Sequences.

**`--split`** (0.9.2): Steuert den DDL-Ausgabemodus.

| Modus | Verhalten |
|---|---|
| `single` (Default) | Gesamte DDL als ein Artefakt — identisch zum bisherigen Verhalten |
| `pre-post` | Trennung in `pre-data` (Tabellen, Constraints, Sequences) und `post-data` (Trigger, Functions, Procedures) |

Ausgabeartefakte für `--split pre-post`:

- **Textausgabe** (`--output out/schema.sql`):
  - `out/schema.pre-data.sql` — Tabellen, Constraints, Sequences, Views ohne Routine-Abhängigkeit
  - `out/schema.post-data.sql` — Functions, Procedures, Triggers, Views mit Routine-Abhängigkeit
  - Die Originaldatei `out/schema.sql` wird **nicht** geschrieben
  - Der Report bleibt ein einzelnes Sidecar-Artefakt (`out/schema.report.yaml`) mit `split_mode: pre-post`
- **JSON-Ausgabe** (`--output-format json`):
  - `split_mode: "pre-post"` statt `ddl`-Feld
  - `ddl_parts.pre_data` und `ddl_parts.post_data` mit dem jeweiligen DDL-String
  - `notes` und `skipped_objects` tragen optional `phase: "pre-data"` oder `phase: "post-data"` (Kebab-Case)
- **Kombination** (`--output ... --output-format json`):
  - SQL-Dateien werden geschrieben **und** JSON wird ausgegeben
  - Report bleibt ein einzelnes Sidecar-Artefakt

Einschränkungen für `--split pre-post`:
- Erfordert `--output` (Textausgabe) oder `--output-format json` (strukturierte Ausgabe). Ohne adressierbaren Ausgabeweg: Exit 2.
- Kann nicht mit `--generate-rollback` kombiniert werden. Kombination: Exit 2.
- Views, deren Phasenzuordnung nicht sicher bestimmbar ist (kein Query-Text, keine deklarierten `dependencies.functions`, aber Functions im Schema), erzeugen Exit 2 mit Fehlercode `E060`. Empfohlene Nutzeraktion: explizite `dependencies.functions` im View-Eintrag der Schema-Datei deklarieren.

**Exit-Codes**:
- `0`: DDL erfolgreich generiert (auch bei Warnungen und übersprungenen Objekten)
- `2`: Ungültiger `--target`, ungültiges Spatial-Profil oder unzulässige `--split`-Kombination
- `3`: Schema-Validierung fehlgeschlagen (DDL wird nicht erzeugt)
- `7`: Schema-Datei nicht lesbar oder ungültiges YAML

**action_required-Objekte** (z.B. Functions mit anderem `source_dialect`, nicht unterstützte Sequences oder blockierte Spatial-Tabellen) werden übersprungen und im Report dokumentiert. Die DDL-Generierung bricht **nicht** ab — der Exit-Code bleibt `0`. Details in [DDL-Generierungsregeln §14.3](./ddl-generation-rules.md#143-verhalten-bei-action_required).

#### `schema reverse` *(0.6.0, umgesetzt)*

Reverse-Engineering einer bestehenden Datenbank ueber eine Live-Verbindung.

```
d-migrate schema reverse --source <url-or-alias> --output <path>
```

**Aufloesung von `--source`**: `--source` akzeptiert eine DB-Connection-URL oder
einen Named-Connection-Alias aus `.d-migrate.yaml`. Die Aufloesung folgt
denselben Regeln wie bei `data export` (§1.4), aber ohne impliziten
`default_source`-Fallback. Das globale `--config` wird beruecksichtigt.

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | URL oder Alias | DB-Connection-URL oder benannte Verbindung |
| `--output` | Ja | Pfad | Ausgabe-Schema-Datei (.yaml/.yml/.json) |
| `--format` | Nein | String | Format des Schema-Artefakts: `yaml` (Default), `json` |
| `--report` | Nein | Pfad | Pfad fuer Reverse-Report. Default: `<output>.report.yaml` |
| `--include-views` | Nein | Boolean | Views einschliessen |
| `--include-procedures` | Nein | Boolean | Stored Procedures einschliessen |
| `--include-functions` | Nein | Boolean | User-Defined Functions einschliessen |
| `--include-triggers` | Nein | Boolean | Triggers einschliessen |
| `--include-all` | Nein | Boolean | Alle optionalen Objekte einschliessen |

**Reverse-Ausgabe und Reverse-Report**:

`schema reverse` erzeugt zwei getrennte Artefakte:

1. **Schema-Dokument** (`--output`): Reines neutrales Schema
   (`SchemaDefinition`), ohne eingebettete Notes oder `skipped_objects`.
   Die Dateiendung muss zum Format passen (.yaml/.yml/.json).
2. **Reverse-Report** (`--report` oder Default-Sidecar): Strukturierte
   Notes und uebersprungene Objekte. Im `plain`-Modus erscheinen
   Warnungen und Skips zusaetzlich auf `stderr`.

**Ausgabeverhalten**:

- `--output-format plain` (Default): Erfolgsmeldungen auf `stdout`,
  Notes/Skips auf `stderr`
- `--output-format json|yaml`: Strukturiertes Success-Dokument auf
  `stdout`, keine stderr-Notes
- `--quiet`: Unterdrueckt alle Nicht-Fehler-Ausgaben

**Credential-Schutz**: URL-basierte Quellen werden in Report, Fehler-
und Success-Ausgaben ueber `LogScrubber.maskUrl()` maskiert. Exception-
Messages werden vor der Ausgabe zentral gescrubbt.

**Exit-Codes**:

| Code | Trigger |
|---|---|
| `0` | Reverse erfolgreich (auch bei Warnungen und uebersprungenen Objekten) |
| `2` | Ungueltige CLI-Argumente (Format/Endung-Mismatch, Output/Report-Kollision) |
| `4` | Verbindungs- oder DB-Metadatenfehler |
| `7` | Config-Aufloesung, URL-Parse oder Dateischreibfehler |

#### `schema compare` *(0.5.0 file-based, 0.6.0 mit DB-Operanden, umgesetzt)*

Vergleicht zwei Schemata im neutralen Format und zeigt Unterschiede. Der
Vergleich bleibt modellbasiert: beide Operanden werden vor dem Diff zu einer
`SchemaDefinition` aufgelöst — Compare diffed keine SQL-Texte und führt keinen
impliziten Migrationspfad ein.

**Ist-Stand (0.5.0)**: Nur `file/file`-Vergleich implementiert.

**0.6.0-Erweiterung**: Zusätzlich `file/db` und `db/db` über die neuen
Operandpräfixe `file:` und `db:`.

```
d-migrate schema compare --source <operand> --target <operand>
```

**Operand-Notation** (kanonisch ab 0.6.0):

| Präfix | Bedeutung | Beispiel |
|---|---|---|
| `file:<path>` | Schema-Datei im neutralen Format | `file:schema.yaml` |
| `db:<url-or-alias>` | Live-DB-Verbindung oder Named Connection | `db:postgresql://localhost/mydb` oder `db:staging` |

Ohne Präfix wird der Operand als Dateipfad behandelt (Rückwärtskompatibilität
mit 0.5.0). Named Connections werden im Compare-Pfad als `db:<alias>` notiert,
damit sie nicht mit Dateipfaden kollidieren.

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Operand | Erstes Schema (`file:<path>` oder `db:<url-or-alias>`) |
| `--target` | Ja | Operand | Zweites Schema (`file:<path>` oder `db:<url-or-alias>`) |
| `--output` | Nein | Pfad | Diff-Ergebnis in Datei schreiben |

**Ausgabeverhalten**:
- **stdout**: Diff-Ausgabe im Textformat (wenn kein `--output`)
- **stderr**: Fehler, Fortschrittshinweise und operandseitige Diagnose-Notes (z.B. `W116`) im Plain-Modus
- **`--output`**: Diff-Ergebnis in Datei statt stdout (Format folgt `--output-format`)
- **`--output-format json|yaml`**: Diff als strukturiertes JSON bzw. YAML

**Operandseitige Diagnose (seit 0.9.4)**: Wenn ein Operand ein `db:`-Reverse-Ergebnis ist, koennen operandseitige Notes und uebersprungene Objekte auftreten (z.B. `W116` bei fehlenden MySQL-Sequence-Supportobjekten). Diese Diagnose wird additiv im strukturierten Output transportiert:

- `source_operand` und `target_operand` sind optionale Felder im JSON-/YAML-Dokument (nur gesetzt, wenn der jeweilige Operand Metadaten traegt)
- Jedes Feld enthaelt:
  - `reference`: Operand-Referenz (Dateipfad oder Connection-URL, maskiert)
  - `notes`: Liste operandseitiger Diagnose-Eintraege (z.B. `W116`)
  - `skipped_objects`: Liste uebersprungener Objekte des Operanden
- Im Plain-Modus erscheinen operandseitige Notes zusaetzlich auf `stderr`
- Operandseitige Notes beeinflussen den Compare-Exit-Code **nicht**: `W116` allein erzeugt weder Exit 1 noch Exit 3/4/7. Exit-Codes folgen ausschliesslich aus Validation-Fehlern oder echtem Schema-Diff.

**Exit-Codes**:
- `0`: Schemas identisch (keine Unterschiede; auch bei operandseitigen Warnungen wie `W116`)
- `1`: Unterschiede gefunden (zur Nutzung in Scripting: `if d-migrate schema compare ...`)
- `2`: Ungültige CLI-Argumente
- `3`: Schema-Validierung fehlgeschlagen
- `4`: Verbindungsfehler (nur bei `db:`-Operanden)
- `7`: Datei-/Parse-/I/O-Fehler

**Beispiele**:

```bash
# file/file (0.5.0-Verhalten, weiterhin gültig)
d-migrate schema compare --source schema-v1.yaml --target schema-v2.yaml

# file/db (0.6.0)
d-migrate schema compare --source file:schema.yaml --target db:staging

# db/db (0.6.0)
d-migrate schema compare --source db:staging --target db:postgresql://localhost/prod
```

#### `schema migrate` *(geplant: späterer Milestone)*

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

Hinweis: Nicht Teil von 0.7.0. `schema migrate` wird diff-basiert auf
`DiffResult` arbeiten und ist bewusst von `export flyway|liquibase|django|knex`
(baseline-/full-state-Export aus einem einzelnen Schema) abgegrenzt.

#### `schema rollback` *(geplant: späterer Milestone)*

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

Hinweis: Nicht Teil des 0.5.0-MVP-Releases; wird zusammen mit dem
Migrations-/Rollback-Pfad in einem späteren Milestone konkretisiert.

### 6.2 data

#### `data export` *(0.3.0, umgesetzt)*

Streamt Tabellen aus einer Datenbank in JSON, YAML oder CSV. Pull-basiert,
chunk-weise — geeignet auch für Tabellen, die größer sind als der verfügbare
Heap (Plan §2.1, §6.4).

```
d-migrate data export --source <url-or-name> --format <format> [--output <path>]
```

**Auflösung von `--source`** (siehe §1.4 und `docs/planning/implementation-plan-0.3.0.md` §6.14):

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
| `--filter` | Nein | String | — | Filter-DSL-Ausdruck (seit 0.9.3). Erlaubte Operatoren: `=`, `!=`, `>`, `>=`, `<`, `<=`, `IN (...)`, `IS NULL`, `IS NOT NULL`, `AND`, `OR`, `NOT`, Klammern. Erlaubte Funktionen: `LOWER`, `UPPER`, `TRIM`, `LENGTH`, `ABS`, `ROUND`, `COALESCE`. Arithmetik (`+`, `-`, `*`, `/`) und qualifizierte Identifier (`table.column`) sind zulaessig. Alle Literale werden als Bind-Parameter an JDBC gebunden. Rohes SQL wird nicht mehr akzeptiert — nicht DSL-konforme Eingaben enden mit Exit 2. |
| `--since-column` | Nein | String | — | Marker-Spalte für inkrementellen Export (LF-013). Muss zusammen mit `--since` gesetzt werden; gleiche Identifier-Regel wie `--tables`. |
| `--since` | Nein | String | — | Untere Marker-Grenze für LF-013. Wird typisiert und parametrisiert an JDBC gebunden; nur zusammen mit `--since-column` gültig. |
| `--encoding` | Nein | String | `utf-8` | Output-Encoding (z.B. `utf-8`, `iso-8859-1`, `utf-16`) |
| `--chunk-size` | Nein | Integer | `10000` | Rows pro Streaming-Chunk |
| `--split-files` | Nein | Boolean | aus | Eine Datei pro Tabelle in `--output <dir>`. Bei mehreren Tabellen Pflicht. |
| `--csv-delimiter` | Nein | Char | `,` | CSV-Spalten-Trennzeichen (genau ein Zeichen) |
| `--csv-bom` | Nein | Boolean | aus | BOM passend zu `--encoding` vor dem CSV-Output schreiben (UTF-8, UTF-16 BE/LE). Für Encodings ohne definiertes BOM (z.B. `iso-8859-1`, `windows-1252`) ist das Flag ein No-op (seit 0.8.0). |
| `--csv-no-header` | Nein | Boolean | aus | Header-Zeile bei CSV unterdrücken (Default: Header an, §6.17) |
| `--null-string` | Nein | String | `""` | CSV-NULL-Repräsentation |
| `--resume` | Nein | String | — | Resume eines frueheren Exports aus einer Checkpoint-Referenz (seit 0.9.0 produktiv, inkl. Mid-Table-Wiederaufnahme). Wert ist eine `checkpoint-id` **oder** ein Pfad; Pfade MUESSEN innerhalb des effektiven `--checkpoint-dir` / `pipeline.checkpoint.directory` liegen (Pfade ausserhalb → Exit 7). **Nur file-basiert**: kombiniert mit stdout-Export (kein `--output`) endet der Aufruf mit Exit 2; ohne konfiguriertes Checkpoint-Verzeichnis endet der Aufruf mit Exit 7. Der Lauf uebernimmt `operationId` aus dem Manifest, skippt Tabellen mit Status `COMPLETED` und setzt unvollstaendige Tabellen fort. **Mid-Table (Phase C.2)**: ist `--since-column` gesetzt **und** hat die Tabelle einen Primaerschluessel, setzt der Lauf die Tabelle ab dem zuletzt chunk-bestaetigten Composite-Marker `(sinceColumn, PK)` lexikografisch strikt fort; fehlt der PK, fallt der Lauf mit sichtbarem stderr-Hinweis auf C.1-Verhalten (Tabelle neu exportieren) zurueck. Single-File-Ziele werden immer ueber eine Staging-Datei im Checkpoint-Verzeichnis geschrieben und erst bei Erfolg per atomic rename ersetzt; Single-File-Resume ignoriert den gespeicherten Marker und exportiert die Tabelle erneut von vorn (Mid-Table-Rebuild des Containers ist bewusst verschoben, siehe `docs/planning/ImpPlan-0.9.0-C2.md` §5.4). Kompatibilitaetsmismatch (Fingerprint inkl. PK-Signatur, Tabellenliste, Output-Modus, operationType; oder Manifest hat `resumePosition`, Request hat aber kein `--since-column`) → Exit 3. |
| `--checkpoint-dir` | Nein | Pfad | (Config `pipeline.checkpoint.directory`) | Verzeichnis fuer Checkpoints. Der CLI-Wert hat Vorrang vor `pipeline.checkpoint.directory` in `.d-migrate.yaml`. |

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
| `2` | CLI-Fehler: ungültige Optionen, unzulässige Flag-Kombination, ungültiger `--csv-delimiter`/`--encoding`/`--tables`/`--since-column`-Identifier, fehlendes Gegenstück zu `--since-column`/`--since`, nicht DSL-konformer `--filter`-Ausdruck (seit 0.9.3), unverträgliche `--output`/`--split-files`-Kombi, **oder `--resume` auf stdout-Export** (seit 0.9.0) |
| `3` | Preflight-Fehler — seit 0.9.0: semantisch inkompatible Resume-Referenz (operationType-Mismatch, Fingerprint-Mismatch aus Format/Encoding/CSV-Optionen/Filter/`--since-*`/Tabellen-Reihenfolge/Output-Modus/Output-Pfad/PK-Signatur, Tabellenliste divergiert, oder Manifest hat `resumePosition` aber aktueller Request hat kein `--since-column`). Mapping ist symmetrisch zum Import-Preflight (§4.5) |
| `4` | Connection-Fehler (HikariCP konnte keine Connection öffnen, `TableLister` failed) |
| `5` | Export-Fehler während Streaming (SQLException, IOException, Writer-Failure, fehlende Tabelle) |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml` nicht ladbar/parsebar, unbekannter Connection-Name, fehlende ENV-Variable, kein Treiber für Dialect); seit 0.9.0 zusaetzlich: `--resume` ohne konfiguriertes Checkpoint-Verzeichnis, Pfad ausserhalb des Checkpoint-Verzeichnisses, Checkpoint-Datei nicht gefunden, unlesbare Datei oder inkompatible `schemaVersion` |

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

# Filter-DSL (seit 0.9.3 — alle Literale als Bind-Parameter)
d-migrate data export --source prod --format json --tables orders \
    --filter "created_at > '2026-01-01'" --output recent.json

# Filter mit Funktionen und Arithmetik
d-migrate data export --source prod --format json --tables orders \
    --filter "LOWER(status) = 'open' AND amount * quantity > 100" --output filtered.json

# Inkrementeller Export per Marker-Spalte (LF-013)
d-migrate data export --source local_pg --format json --tables orders \
    --since-column updated_at --since "2026-01-01T00:00:00" --output orders.delta.json

# Filter-DSL kombiniert mit inkrementellem Export
d-migrate data export --source local_pg --format csv --tables orders \
    --filter "status = 'open'" \
    --since-column updated_at --since "2026-01-01T00:00:00" \
    --output orders-open.delta.csv

# Auto-Discovery aller Tabellen mit Split-Files
d-migrate data export --source local_pg --format json \
    --output ./full-dump --split-files
```

**LF-013: Inkrementeller Export via `--since-column` / `--since`**

- `--since-column` und `--since` sind nur gemeinsam gültig. Fehlt einer der beiden Werte, endet der Command mit Exit 2.
- `--since-column` folgt derselben Identifier-Regel wie `--tables`: erlaubt sind `<name>` oder `schema.column`, ohne Quotes und ohne Whitespace.
- Der `--since`-Wert wird im Runner typisiert und als JDBC-Bind-Parameter an eine `DataFilter.ParameterizedClause("<quoted-column> >= ?", [typedSince])` übergeben. Die Typisierung folgt dem 0.8.0-Phase-E-Vertrag (`docs/planning/ImpPlan-0.8.0-E.md` §4.5) und bleibt konservativ: ein Offset-haltiger ISO-String bleibt `OffsetDateTime` (§4.2), ein lokaler ISO-DateTime bleibt `LocalDateTime` (§4.3), ein ISO-Datum bleibt `LocalDate`, Integer als `Long`, Dezimalwerte als `BigDecimal`, sonst als String. Eine in der Konfiguration gesetzte `i18n.default_timezone` löst **keine** stille Zonierung eines lokalen Literals aus (§4.4).
- Wenn zusätzlich `--filter` gesetzt ist, werden beide Bedingungen intern als `DataFilter.Compound([ParameterizedClause(dsl), ParameterizedClause(since)])` kombiniert; der Reader bindet alle Parameter in stabiler Reihenfolge.
- Seit 0.9.3 erzeugt `--filter` immer eine `ParameterizedClause` via DSL-Parser. Die M-R5-Einschraenkung (kein literales `?` in `--filter`) entfaellt, da keine rohen `WhereClause`-Fragmente mehr erzeugt werden.

#### `data import` *(0.4.0, umgesetzt)*

Importiert Daten aus JSON, YAML oder CSV in eine Datenbank. Der Importpfad ist
streaming-basiert, unterstützt Datei-, Verzeichnis- und stdin-Quellen und löst
`--target` analog zu `data export` auch über benannte Verbindungen aus
`.d-migrate.yaml` auf.

```
d-migrate data import --source <path-or-dir-or-> [--target <url-or-name>]
```

| Flag | Pflicht | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--target` | Nein | URL oder Name | `database.default_target` aus Config | Ziel-Datenbank als Connection-URL oder benannte Verbindung |
| `--source` | Ja | Pfad, Verzeichnis oder `-` | — | Quelldatei, Quellverzeichnis oder stdin |
| `--format` | Nein | String | Auto-Detection nach Dateiendung | Eingabeformat: `json`, `yaml`, `csv`; bei stdin Pflicht |
| `--schema` | Nein | Pfad | — | Schema-Datei für lokalen Preflight und Tabellen-Reihenfolge bei Verzeichnisimport |
| `--table` | Nein | String | — | Zieltabelle; für stdin und Single-File-Import relevant |
| `--tables` | Nein | Liste | alle | Kommaseparierte Import-Reihenfolge; nur für Verzeichnisquellen |
| `--on-error` | Nein | String | `abort` | Chunk-Fehlerbehandlung: `abort`, `skip`, `log` |
| `--on-conflict` | Nein | String | `abort` | Konfliktbehandlung: `abort`, `skip`, `update` |
| `--trigger-mode` | Nein | String | `fire` | Trigger-Verhalten: `fire`, `disable`, `strict` |
| `--truncate` | Nein | Boolean | aus | Zieltabelle vor Import leeren |
| `--disable-fk-checks` | Nein | Boolean | aus | FK-Checks während des Imports deaktivieren (dialektabhängig) |
| `--reseed-sequences` / `--no-reseed-sequences` | Nein | Boolean | an | Identity-/Sequence-Reseed nach Import steuern |
| `--encoding` | Nein | String | `auto` | Input-Encoding. Der Default-Pfad `auto` erkennt BOM-markierte UTF-Streams (UTF-8, UTF-16 BE/LE) und fällt ohne BOM auf UTF-8 zurück; UTF-32-BOM wird mit Exit 2 abgelehnt. Für Non-UTF-Encodings (`iso-8859-1`, `windows-1252`, …) muss der Wert explizit gesetzt werden — es gibt keine Heuristik-Erkennung. Siehe 0.8.0 Phase F (`docs/planning/ImpPlan-0.8.0-F.md` §4.2/§4.3). |
| `--csv-no-header` | Nein | Boolean | aus | CSV enthält keine Header-Zeile |
| `--csv-null-string` | Nein | String | `""` | CSV-NULL-Repräsentation |
| `--chunk-size` | Nein | Integer | `10000` | Datensätze pro Chunk/Transaktion |
| `--resume` | Nein | String | — | Resume eines frueheren Imports aus einer Checkpoint-Referenz (seit 0.9.0 produktiv). Wert ist eine `checkpoint-id` **oder** ein Pfad; Pfade MUESSEN innerhalb des effektiven `--checkpoint-dir` / `pipeline.checkpoint.directory` liegen (Pfade ausserhalb → Exit 7). **Nur file-/directory-basiert**: kombiniert mit stdin-Quelle (`--source -`) endet der Aufruf mit Exit 2; ohne konfiguriertes Checkpoint-Verzeichnis → Exit 7. **Preflight** prueft `operationType == IMPORT`, den Options-Fingerprint (Format, Encoding, CSV-Header/NULL, `--on-error`/`--on-conflict`/`--trigger-mode`/`--truncate`/`--disable-fk-checks`/`--reseed-sequences`/`chunk-size`, Tabellenliste in Reihenfolge, Input-Topologie, Input-Pfad, Ziel-Dialekt und Ziel-URL; fuer Directory-Importe zusaetzlich die `table -> inputFile`-Bindung) sowie die Tabellenlisten-Gleichheit. Inkompatible Referenzen → Exit 3. **Wiederaufnahme** setzt an committed Chunk-Grenzen an: bereits als `COMPLETED` markierte Tabellen werden uebersprungen; teilweise bestaetigte Tabellen lesen die bereits bestaetigten Chunks aus dem Reader (ohne Schreib-/Commit-Aktion) und starten am naechsten offenen Chunk. `--truncate` wird fuer teilweise bestaetigte Tabellen automatisch unterbunden (sonst gingen bestaetigte Zeilen verloren). `--on-error abort/skip/log` behaelt seine Semantik auch beim Resume; nur erfolgreich committete Chunks treiben den Checkpoint vorwaerts. `failedFinish` laesst die Tabelle als `FAILED` markiert (nicht still als `COMPLETED`). Directory-Importe verlangen zusaetzlich, dass die `table -> inputFile`-Bindung des Manifests mit dem aktuellen Directory-Scan uebereinstimmt — umbenannte, hinzugefuegte oder entfernte Dateien → Exit 3. |
| `--checkpoint-dir` | Nein | Pfad | (Config `pipeline.checkpoint.directory`) | Verzeichnis fuer Checkpoints. Der CLI-Wert hat Vorrang vor `pipeline.checkpoint.directory` in `.d-migrate.yaml`. |

**Exit-Codes**:

- `0`: Erfolg
- `2`: Ungültige CLI-Argumente oder unzulässige Flag-Kombinationen (inkl. `--resume` auf stdin-Quelle; unsupported `--lang` an der Root-CLI)
- `3`: Preflight-Fehler — Header-/Schema-Mismatch, strikter Trigger, und seit 0.9.0 semantisch inkompatible Resume-Referenz (`operationType`-Mismatch, Fingerprint-Mismatch, Tabellenlisten-Divergenz)
- `4`: Verbindungsfehler
- `5`: Import-Fehler während Verarbeitung oder Commit
- `7`: Konfigurations-, Parse- oder Datei-Fehler (inkl. unlesbare Checkpoint-Datei, ungueltiges Manifest, fehlende Datei, Pfad ausserhalb des Checkpoint-Verzeichnisses oder inkompatible `schemaVersion`, seit 0.9.0)

#### `data transfer` *(0.6.0, umgesetzt)*

Direkter DB-zu-DB-Datentransfer ohne Zwischenformat. `data transfer` ist ein
eigenständiger Datenpfad, kein umbenannter Export-/Import-Umweg — Daten werden
von der Quelldatenbank direkt in die Zieldatenbank gestreamt.

```
d-migrate data transfer --source <url-or-alias> --target <url-or-alias>
```

**Auflösung von `--source` / `--target`**: Beide akzeptieren DB-Connection-URLs
oder Named-Connection-Aliase aus `.d-migrate.yaml`. Die Auflösung folgt
denselben Regeln wie bei `data export` (§1.4) und ist in der
[Connection- und Konfigurationsspezifikation](./connection-config-spec.md)
kanonisch beschrieben.

| Flag | Pflicht | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--source` | Ja | URL oder Alias | — | Quell-Datenbank |
| `--target` | Ja | URL oder Alias | — | Ziel-Datenbank |
| `--tables` | Nein | Liste | alle | Kommaseparierte Tabellenliste |
| `--filter` | Nein | String | — | Filter-DSL-Ausdruck fuer die Quellabfrage (seit 0.9.3). Gleiche DSL-Grammatik wie bei `data export --filter`. Alle Literale werden als Bind-Parameter an JDBC gebunden. |
| `--since-column` | Nein | String | — | Marker-Spalte fuer inkrementellen Transfer (LF-013) |
| `--since` | Nein | String | — | Untere Marker-Grenze (nur zusammen mit `--since-column`) |
| `--on-conflict` | Nein | String | `abort` | Konfliktbehandlung: `abort`, `skip`, `update` |
| `--trigger-mode` | Nein | String | `fire` | Trigger-Handling: `fire`, `disable`, `strict` |
| `--truncate` | Nein | Boolean | aus | Zieltabellen vor dem Transfer leeren |
| `--chunk-size` | Nein | Integer | `10000` | Rows pro Streaming-Chunk |

**Target-autoritatives Preflight**:

Vor dem ersten Daten-Write führt `data transfer` ein Preflight gegen die
Zieldatenbank durch:

- **Tabellen-/Spaltenkompatibilität**: Existieren die Zieltabellen? Stimmen
  Spaltenanzahl und -typen ausreichend überein?
- **FK-basierte Tabellenreihenfolge**: Die Transferreihenfolge wird aus den
  Foreign-Key-Beziehungen der Zieldatenbank abgeleitet (topologische
  Sortierung).
- **FK-Zyklen**: Werden zyklische FK-Beziehungen erkannt, scheitert das
  Preflight, sofern kein expliziter sicherer Bypass konfiguriert ist.

Preflight-Fehler erzeugen einen eigenen Exit-Code, damit sie vom eigentlichen
Streaming-Fehlerpfad getrennt bleiben.

**Nicht Teil von `data transfer`**:

- Routinen, Views und Trigger werden nicht implizit mitkopiert.
- Kein Zwischenformat — Daten fließen direkt von Source-`DataReader` zu
  Target-`DataWriter`.

**Exit-Codes**:

| Code | Trigger |
|---|---|
| `0` | Transfer erfolgreich |
| `2` | Ungültige CLI-Argumente |
| `3` | Preflight fehlgeschlagen (Inkompatibilität, FK-Zyklen) |
| `4` | Verbindungsfehler (Source oder Target) |
| `5` | Streaming-/Schreibfehler während Transfer |
| `7` | Konfigurationsfehler |

**Beispiele**:

```bash
# Transfer zwischen Named Connections
d-migrate data transfer --source staging --target local_pg

# Transfer mit expliziten URLs
d-migrate data transfer \
    --source postgresql://app@staging.example.com/myapp \
    --target postgresql://dev@localhost/myapp

# Nur bestimmte Tabellen
d-migrate data transfer --source staging --target local_pg \
    --tables customers,orders,order_items
```

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

#### `data profile`

Profiliert eine bestehende Datenbank: Spaltenstatistiken, Qualitaetswarnungen
und Zieltyp-Kompatibilitaet. Ergebnis ist ein JSON- oder YAML-Report.

```
d-migrate data profile --source <url-or-name> [--tables <t1,t2,...>]
  [--schema <schema>] [--top-n <n>] [--format json|yaml] [--output <path>]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | String | Datenbank-URL oder Named Connection |
| `--tables` | Nein | String | Komma-getrennte Tabellenliste (Default: alle) |
| `--schema` | Nein | String | Datenbankschema (nur PostgreSQL, Default: `public`) |
| `--top-n` | Nein | Int | Anzahl Top-Werte pro Spalte (Default: 10, Max: 1000) |
| `--format` | Nein | String | Ausgabeformat: `json` (Default), `yaml` |
| `--output` | Nein | Pfad | Ausgabedatei (Default: stdout) |

**Determinismus**: Gleiches Schema + gleiche Daten = identischer Report.
Stabile Tabellen- und Spaltenreihenfolge, stabile `topValues`-Sortierung,
kein laufzeitvariables `generatedAt`.

**Aufloesung von `--source`**: Wie bei `data export` — direkte URL oder
Named Connection aus `.d-migrate.yaml`.

**`--schema`**: Nur fuer PostgreSQL unterstuetzt. Bei MySQL oder SQLite
fuehrt ein explizites `--schema` zu Exit `2`.

Exit: `0` Erfolg, `2` ungueltige Flags (fehlendes `--source`, `--schema`
auf MySQL/SQLite, ungueltiges `--format`, `--top-n` ausserhalb 1..1000),
`4` Verbindungsfehler, `5` Profiling-Ausfuehrungsfehler,
`7` Konfigurations-/URL-/Registry-Fehler.

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

#### `export flyway` / `export liquibase` / `export django` / `export knex`

Exportiert baseline-/full-state-Migrationsdateien für externe Tools aus
einem einzelnen neutralen Schema. Dies ist kein diff-basierter
Migrationspfad — siehe §7 für die Abgrenzung zu `schema migrate`.

```
d-migrate export flyway --source schema.yaml --target postgresql --output migrations/
d-migrate export django --source schema.yaml --target mysql --version 0001 --output myapp/migrations/
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |
| `--output` | Ja | Pfad | Ausgabeverzeichnis |
| `--target` | Ja | Dialekt | Ziel-Datenbank (`postgresql`, `mysql`, `sqlite`) |
| `--version` | Flyway/Liquibase: Nein; Django/Knex: Ja | String | Versionsnummer für Migration |
| `--spatial-profile` | Nein | String | Spatial-Profil (wie bei `schema generate`) |
| `--generate-rollback` | Nein | Boolean | Tool-spezifisches Down-Artefakt erzeugen |
| `--report` | Nein | Pfad | Transformationsbericht (YAML-Sidecar) |

**Versionsstrategie**:
- Flyway/Liquibase: `--version` optional; Fallback auf `schema.version`, wenn
  tool-tauglich normalisierbar; kein impliziter Timestamp-Fallback
- Django/Knex: `--version` Pflicht; `schema.version` bleibt Metadatum im Report

**Determinismus**: Gleiches Schema + gleiche Flags = identische Artefaktinhalte.
Der DDL-Header-Timestamp (`Generated: <ISO-8601>`) wird in Tool-Artefakten
nicht übernommen; Provenienz bleibt im Report oder in stabilen Metadaten.

**Rollback**: `--generate-rollback` erzeugt tool-spezifische Down-Artefakte
(Flyway-Undo, Liquibase-Rollback-Block, Django `reverse_sql`, Knex
`exports.down`) auf Basis des bestehenden full-state-`generateRollback()`-Pfads.
Dies ist nicht der spätere diff-basierte `DiffResult`-Rollback.

**Liquibase-Format (0.7.0)**: `export liquibase` erzeugt für 0.7.0 genau einen
versionierten XML-Changelog mit genau einem deterministischen `changeSet`.
`changeSet.id` wird stabil aus Version, Slug und Dialekt abgeleitet,
`changeSet.author` ist der feste Exporter-Wert `d-migrate`, und ein
optional erzeugter Rollback wird als `<rollback>`-Block im selben Changeset
eingebettet. Ein bestehender Master-Changelog wird dabei nicht mutiert.

Exit: `0` Erfolg, `2` ungültige Flags (fehlendes `--target`, fehlendes
`--version` bei Django/Knex), `3` Schema-Validierungsfehler, `7` Parse-/I/O-Fehler.

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

### 7.1 Format (MVP 0.5.0)

`data export` und `data import` emittieren waehrend des Laufs
line-orientierte Fortschrittszeilen auf `stderr`. Die Anzeige ist
deterministisch — es gibt keine Zeitschwelle und keine Cursor-Rewrites.

Pro Event wird genau eine Zeile geschrieben. Es gibt zu jedem Zeitpunkt
hoechstens eine aktive Tabelle (sequenzielle Verarbeitung in 0.5.0).

Export-Beispiel:

```
Exporting 3 table(s)
Exporting table 'users' (1/3)
Exporting table 'users' | chunk 1 | 10,000 rows | 0.82 MB
Exported table 'users' | 12,345 rows | 2 chunks | 1.01 MB
```

Import-Beispiel:

```
Importing 2 table(s)
Importing table 'orders' (1/2)
Importing table 'orders' | chunk 1 | 10,000 rows processed | 9,980 inserted, 20 skipped
Imported table 'orders' | 12,000 inserted, 20 skipped
```

Die finale ProgressSummary (z.B. "Exported 3 table(s) (30,000 rows, 2.50 MB)
in 1.20 s") bleibt zusaetzlich erhalten.

### 7.2 Steuerung

- `--no-progress`: Unterdrueckt sowohl Zwischen-Events als auch die finale
  ProgressSummary. Nicht-progressbezogene stderr-Ausgaben (z.B. Export-Warnings)
  bleiben sichtbar.
- `--quiet`: Unterdrueckt alles ausser Fehlern (Events, Summary, Warnings).
- Fortschrittsanzeige geht ausschliesslich nach **stderr** (stdout bleibt
  sauber fuer Piping und Nutzdaten).
- Bei `--output-format json|yaml` bleibt der Progress-Pfad fuer Export/Import
  plain-text auf `stderr`. Es werden keine JSON-/YAML-Progress-Events
  eingefuehrt.

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
| `D_MIGRATE_LANG` | `--lang` | Sprache. Seit 0.9.0 liegt `--lang` in der Prioritaet vor `D_MIGRATE_LANG`. Der generische Env-Pfad behaelt den toleranteren Vertrag (syntaktisch gueltige Locales → Root-Bundle-Fallback), waehrend `--lang` strikt auf gebundelte Produktsprachen beschraenkt ist. |
| `D_MIGRATE_OUTPUT_FORMAT` | `--output-format` | Ausgabeformat |
| `D_MIGRATE_NO_COLOR` | `--no-color` | Farbausgabe deaktivieren |
| `D_MIGRATE_ASSUME_YES` | `--yes` | Bestätigungen überspringen |
| `D_MIGRATE_DB_PASSWORD` | | Datenbank-Passwort |
| `D_MIGRATE_AI_API_KEY` | | KI-Provider API-Key (Fallback) |

Prioritaet:

- fuer den effektiven Config-Pfad: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml`
- fuer allgemeine CLI-Optionen weiterhin: CLI-Argument > Umgebungsvariable > Konfigurationsdatei > Default

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
```

`-` als Pfad bedeutet stdin/stdout. Aktuell unterstützt von
`schema validate`. Für `schema reverse` gibt es in 0.6.0 keinen
stdin-/DDL-Pfad — Reverse arbeitet ausschließlich gegen Live-DB-Verbindungen.

---

## Verwandte Dokumentation

- [Design](./design.md) — CLI-Design §5, Fehlerbehandlung §8
- [Architektur](./architecture.md) — CLI-Modul, Clikt-Framework
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — Schema-Validierungsregeln §13
- [Lastenheft](./lastenheft-d-migrate.md) — LF-012 (CLI), LN-015 (Dokumentation), LN-016 (Fehlermeldungen)

---

**Version**: 1.6
**Stand**: 2026-04-21
**Status**: `schema validate` (0.1.0), `schema generate` (0.2.0), `data export` (0.3.0), `data import` (0.4.0), `schema compare` (0.5.0 file-based, 0.6.0 mit DB-Operanden, 0.9.4 operandseitige Diagnose), `schema reverse` (0.6.0, 0.9.4 MySQL-Sequence-Reverse) und `data transfer` (0.6.0) implementiert
