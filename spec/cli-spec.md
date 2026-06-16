# CLI-Spezifikation: d-migrate

**Kommandozeilen-Schnittstelle — Referenz für Implementierung und Nutzung**

> Dokumenttyp: Spezifikation / Referenz
>
> **Überblick**: Die CLI gliedert sich in die Kommandogruppen `schema`,
> `data`, `export`, `transform` sowie die operativen `mcp`-Kommandos
> (`mcp serve`, `mcp approval-grant issue`, `mcp cursor-key generate`/`validate`).
> Transport- und Tool-spezifische MCP-Verträge stehen in
> [`spec/mcp-server.md`](./mcp-server.md) und [`spec/ki-mcp.md`](./ki-mcp.md).

---

## 1. Allgemeine Konventionen

### 1.1 Aufruf-Syntax

```
d-migrate <command> <subcommand> [flags] [arguments]
```

- **Commands**: Oberste Ebene — `schema`, `data`, `export`, `transform`, `mcp`
- **Subcommands**: Aktion innerhalb eines Commands (`schema validate`, `data export`)
- **Flags**: Optionen mit `--` Präfix, Kurzform mit `-` (`--format json`, `-f json`)
- **Arguments**: Positionelle Argumente (selten, nur wo eindeutig)

### 1.2 Globale Flags

Diese Flags sind bei allen Kommandos verfügbar:

| Flag | Kurzform | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--config` | `-c` | Pfad | `./.d-migrate.yaml` | Pfad zur effektiven Konfigurationsdatei; Prioritaet: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml` | <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->
| `--lang` | | String | (kein Default; Fallback-Kette siehe unten) | Sprachwahl fuer menschenlesbare Ausgaben. Akzeptiert aktuell die gebundelten Produktsprachen `de` und `en` inkl. kanonisierbarer Varianten wie `de-DE`, `de_DE`, `en-US`, `en_US`. Andere Werte fuehren zu Exit 2. Gewinnt gegen `D_MIGRATE_LANG`, `LC_ALL`/`LANG`, `i18n.default_locale` und System-Locale. Der generische Env-/Config-/System-Pfad bleibt toleranter und faellt fuer unbekannte Bundles weiterhin auf das englische Root-Bundle zurueck. |
| `--output-format` | | String | `plain` | Ausgabeformat: `plain`, `json`, `yaml` |
| `--verbose` | `-v` | Boolean | false | Erweiterte Ausgabe (DEBUG-Level) |
| `--quiet` | `-q` | Boolean | false | Nur Fehler ausgeben |
| `--no-color` | | Boolean | false | Farbausgabe deaktivieren |
| `--no-progress` | | Boolean | false | Fortschrittsanzeige deaktivieren |
| `--yes` | `-y` | Boolean | false | Bestätigungen automatisch akzeptieren |
| `--version` | `-V` | Boolean | | Version anzeigen und beenden |
| `--help` | `-h` | Boolean | | Hilfe anzeigen und beenden |

`--verbose` und `--quiet` schließen sich gegenseitig aus.

**`--lang`-Vertrag**:

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
| `5` | `MIGRATION_ERROR` | Fehler während Daten- oder Schema-Migration nach Beginn der Ausführung | Constraint-Verletzung beim Import; DDL-Anweisung schlägt nach Beginn von `schema migrate --execute` fehl |
| `6` | `AI_ERROR` | KI-Provider nicht erreichbar oder Transformation fehlgeschlagen | Ollama nicht gestartet |
| `7` | `LOCAL_ERROR` | Lokaler Konfigurations-, Parse-, Datei-, I/O-, Render- oder Kollisionsfehler | Ungültiges YAML in `.d-migrate.yaml`, Schema-Datei nicht lesbar, Ausgabepfad nicht beschreibbar |
| `8` | `MIGRATION_BLOCKED` | Migration durch Risiko-, Rollback- oder Dialektblocker nicht renderbar (vor Ausführung) | `schema migrate` ohne `--allow-destructive` mit destruktivem Up; `--generate-rollback` mit nicht-reversibler Operation; Ziel-Dialekt rendert geplante Operation nicht |
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
- Freie Fehlermeldungstexte in JSON/YAML bleiben ebenfalls englisch und stabil, bis ein expliziter Gegenvertrag definiert ist.
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
| W114 | Sequence cache value persisted as metadata only; helper-table mode does not emulate runtime preallocation |
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

### 4.7 Sequence-Cross-Dialect-Blocker (string codes)

String-codierte Blocker-Diagnostiken aus dem Sequence-Render-Pfad
(`schema migrate`). Sie ergänzen die E0xx-Kompatibilitätsfehler aus
§4.6 um Codes, die spezifisch für den Cross-Dialect-Transfer von
Sequenzen sind. Alle Codes mappen via `PlannerBlockerClassifier` auf
genau ein `MigrationBlockedReason`; das Mapping ist Teil des aktuellen
Sequence-Migrationsvertrags und der zugehörigen ADR
`docs/adr/0003-cross-dialect-sequencing.md`.

| Code | Reason | Emittiert in | Bedeutung |
|---|---|---|---|
| `SEQUENCE_PRESERVE_PROBE_FAILED` | `MANUAL_ACTION_REQUIRED` | `SequencePreserveStage` | Live-DB-Probe für `preserve_current_value` schlug fehl; kein deterministischer Vor-Zustand für Up/Down. |
| `SEQUENCE_PRESERVE_CONFIG_INVALID` | `MANUAL_ACTION_REQUIRED` | `SequencePreserveStage` | `preserve_current_value`-Konfiguration (CLI/YAML) ist strukturell ungültig. |
| `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` | `MANUAL_ACTION_REQUIRED` | `SequencePreserveStage` | `preserve_current_value` verlangt `--execute` mit DB-Target; File-Mode blockiert vor Render. |
| `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` | `DIALECT_UNSUPPORTED_OPERATION` | `SequencePreserveStage` | Ziel-Dialekt hat heute keine Sequence-Emulation (aktuell SQLite). Kein Operator-Eingriff löst das, bis die helper-table-Emulation landet. |
| `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` | `MANUAL_ACTION_REQUIRED` | *(forward-compatible)* | Per-Attribut-Mismatch im Cross-Dialect-Transfer (z. B. künftiger SQLite-Renderer ohne `cycle`-Support). Heute kein Renderer-Pfad — der OP-level-Block für SQLite bleibt `DIALECT_UNSUPPORTED_OPERATION`, weil `supportsNamedSequences=false`. |
| `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` | `MANUAL_ACTION_REQUIRED` | *(forward-compatible)* | PG-`OWNED BY` ist nicht im Target abbildbar. Heute kein Renderer-Pfad — der PG-Reader filtert `pg_depend.deptype IN ('a','i')` aus `schema.sequences`, sodass owned sequences gar nicht ins neutrale Modell landen. Erst eine Neutralmodell-Erweiterung mit Ownership-Feld aktiviert die Emission. |

`MYSQL_SEQUENCE_DRIFT_*` (E124-Familie fuer MySQL-Sequence-Drift-Checks)
sind separat in §6.1 dokumentiert
und nicht Teil des Cross-Dialect-Capability-Vertrags.

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

#### `schema validate`

Validiert eine Schema-Definition.

```
d-migrate schema validate --source <path>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |

Exit: `0` bei Erfolg, `3` bei Validierungsfehlern.

#### `schema generate`

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
| `--deterministic` | Nein | Boolean | Lässt den DDL-Header `Generated:`-Zeile weg und nutzt einen festen Zeitstempel (siehe unten). Auch der Sidecar-Report (`<output>.report.yaml`) und das JSON-Output-Feld `generated_at` folgen derselben Policy |
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

Sequence-spezifische Ausgaben (`--mysql-named-sequences`):
- **E056** (Sequence/Emulationsfall): Im `action_required`-Modus fuer uebersprungene Sequences und fuer Spalten mit `SequenceNextVal`-Default ohne Emulation.
- **E122** (Legacy-Notation): Schema verwendet die abgekuendigte `nextval(...)`-Notation statt `default: { sequence_nextval: ... }`.
- **E123** (Fehlende Sequence): `sequence_nextval` referenziert eine nicht definierte Sequence.
- **E124** (Namenskollision): Ein neutrales Schema-Objekt kollidiert mit einem reservierten Support-Objektnamen (`dmg_sequences`, `dmg_nextval`, `dmg_setval`).
- **W114** (Cache nicht emuliert): Sequence hat `cache`-Wert, der im `helper_table`-Modus nur als Metadatum gespeichert, aber nicht als Preallocation umgesetzt wird.
- **W115** (Lossy Trigger-Semantik): `SequenceNextVal` auf einer Spalte nutzt MySQL-Trigger; explizites `NULL` wird wie ein ausgelassener Wert behandelt.
- **W116** (Fehlende Support-Objekte): Sequence-Metadaten beim Reverse aus `dmg_sequences` rekonstruiert, aber erforderliche Support-Objekte (Routinen und/oder Trigger) fehlen oder sind nicht kanonisch. Die Sequence ist dann rekonstruierbar, aber nicht voll betriebsfaehig. `W116` erscheint als Reverse-Note auf Sequence- oder Spaltenebene. Bei `schema compare` bleibt `W116` eine operandseitige Diagnose: es erzeugt keinen eigenen Diff-Eintrag und beeinflusst den Exit-Code nicht (Exit folgt nur aus Validation oder echtem Schema-Diff).
- **W117** (Transaktionsgebundene Werte): Sequence-Werte im `helper_table`-Modus werden bei Rollback zurueckgerollt — anders als native PostgreSQL-Sequences.

**`--deterministic`**: Macht den DDL- und Report-Output reproduzierbar.

- Im DDL-Header wird die `-- Generated: <timestamp>`-Zeile weggelassen.
- Wenn die Umgebungsvariable `SOURCE_DATE_EPOCH` (UNIX-Sekunden) gesetzt ist, gilt sie zusätzlich als fester Erzeugungszeitstempel für DDL-Header und den Sidecar-Report. Ein nicht-numerischer oder nicht in einen `Instant` parsbarer Wert führt zu **Exit 2**.
- Der YAML-Sidecar-Report (`<output>.report.yaml`) lässt das Feld `generated_at` weg, wenn `--deterministic` gesetzt ist; das JSON-Output-Format folgt derselben Policy.
- Rollback-DDL (`--generate-rollback`) trägt denselben deterministischen Header.

`--deterministic` ist orthogonal zu `--split` und `--generate-rollback` und kann mit beiden kombiniert werden.

**`--split`**: Steuert den DDL-Ausgabemodus.

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

#### `schema reverse`

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
| `--name` | Nein | String | Schemaname im Output statt des reverse-generierten Defaults |
| `--version` | Nein | String | Schemaversion im Output statt `0.0.0-reverse` |

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

#### `schema compare`

Vergleicht zwei Schemata im neutralen Format und zeigt Unterschiede. Der
Vergleich bleibt modellbasiert: beide Operanden werden vor dem Diff zu einer
`SchemaDefinition` aufgelöst — Compare diffed keine SQL-Texte und führt keinen
impliziten Migrationspfad ein.

**Aktueller Operandumfang**: `file/file`, `file/db` und `db/db` über
die Operandpräfixe `file:` und `db:`.

```
d-migrate schema compare --source <operand> --target <operand>
```

**Operand-Notation**:

| Präfix | Bedeutung | Beispiel |
|---|---|---|
| `file:<path>` | Schema-Datei im neutralen Format | `file:schema.yaml` |
| `db:<url-or-alias>` | Live-DB-Verbindung oder Named Connection | `db:postgresql://localhost/mydb` oder `db:staging` |

Ohne Präfix wird der Operand als Dateipfad behandelt. Named Connections
werden im Compare-Pfad als `db:<alias>` notiert,
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

**Operandseitige Diagnose**: Wenn ein Operand ein `db:`-Reverse-Ergebnis ist, koennen operandseitige Notes und uebersprungene Objekte auftreten (z.B. `W116` bei fehlenden MySQL-Sequence-Supportobjekten). Diese Diagnose wird additiv im strukturierten Output transportiert:

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
# file/file
d-migrate schema compare --source schema-v1.yaml --target schema-v2.yaml

# file/db
d-migrate schema compare --source file:schema.yaml --target db:staging

# db/db
d-migrate schema compare --source db:staging --target db:postgresql://localhost/prod
```

#### `schema migrate`

Plant einen migrationsfähigen Operationsplan aus dem Diff zwischen Soll-
und Ist-Schema und rendert dialektspezifisches Up-DDL — optional inklusive
Down-DDL — und führt es bei Bedarf gegen das Ziel-Datenbank-System aus.

```
d-migrate schema migrate --source <desired> --target <current> \
  [--dialect <id>] [--output <up.sql>] \
  [--generate-rollback --rollback-output <down.sql>] \
  [--plan-only] [--report <report.yaml>] \
  [--execute] [--allow-destructive] [--allow-extension-install] \
  [--migration-overlay <overlay.json>]... [--dry-run] \
  [--lock-timeout-ms <ms>]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Operand | Soll-Schema (Datei) |
| `--target` | Ja | Operand | Ist-Zustand: `db:<url-or-alias>` oder `file:<current.yaml>` |
| `--dialect` | Bedingt | Dialekt | Pflicht bei Datei-zu-Datei-Modus; aus Connection ableitbar bei DB-Target |
| `--output` | Nein | Pfad | Up-SQL-Ausgabe; ohne Flag bei renderbarem Dry-Run nach `stdout` |
| `--rollback-output` | Bedingt | Pfad | Down-SQL-Ausgabe; Pflicht bei `--generate-rollback` ohne `--plan-only` |
| `--generate-rollback` | Nein | Boolean | Down-Plan erzeugen und prüfen. Bei Routine-Replace (`ReplaceFunction`/`ReplaceProcedure`) blockt der Renderer mit `ROUTINE_DOWN_BODY_UNKNOWN` und `primaryBlockedReason = ROLLBACK_NOT_POSSIBLE`, wenn der alte Routine-Body nicht vollstaendig bekannt ist. Bei Datei-zu-DB darf der Reverse-Pfad alte Bodies aus der Live-DB lesen; bei Datei-zu-Datei muss der Operator den Vorbody im Schema-File mitliefern oder ohne `--generate-rollback` migrieren. Verwandte Render-Blocker ohne `--generate-rollback`-Bezug: `ROUTINE_BODY_UNKNOWN` (Up-Body fehlt), `ROUTINE_REPLACE_UP_BODY_UNKNOWN` (Replace-Up-Body fehlt), `ROUTINE_BODY_DOLLAR_TAG_COLLISION` (Body enthaelt den Renderer-Dollar-Tag `$body$`) |
| `--plan-only` | Nein | Boolean | Nur Plan-/Risiko-Report, kein SQL; in dieser Kombination ist `--rollback-output` unzulässig |
| `--report` | Bedingt | Pfad | Strukturierter Plan-/Risiko-Report; **Pflicht bei `--execute`** |
| `--plan-artefact` | Nein | Pfad | Signierter `migration-plan.v1`-JSON wird atomar an diesen Pfad geschrieben (additiv zu `--report`/`--output`/`--rollback-output`). Der Artefakt-Vertrag ist im Abschnitt **migration-plan.v1 Artefakt** unten beschrieben. Wird auch im `--plan-only`- und Exit-8-Pfad emittiert, sofern der Plan ueberhaupt berechnet werden konnte. Schreibfehler beendet mit Exit 7. |
| `--execute` | Nein | Boolean | Up-DDL nach erfolgreichem Rendern gegen DB-Target ausführen; nur mit DB-Target zulässig |
| `--lock-timeout-ms` | Nein | Millisekunden | Atomic-Preserve Lock-Acquire-Budget fuer den `--execute`-Pfad. Default: `5000`. Gueltiger Bereich: `10` bis `60000`; Werte ausserhalb des Bereichs beenden den Lauf vor der Pipeline mit Exit `2` |
| `--allow-destructive` | Nein | Boolean | Destruktive Up-Operationen erlauben |
| `--allow-extension-install` | Nein | Boolean | PostgreSQL darf benoetigte `CREATE EXTENSION IF NOT EXISTS ...`-Prerequisites fuer extension-abhaengige Migrationen rendern; ohne Flag blockieren nicht verifizierte Extensions |
| `--migration-overlay` | Nein | Pfad, wiederholbar | Versioniertes Migrations-Overlay-JSON nach `migration-overlay.v1`; vor dem Rendern gegen Quell-/Ziel-Fingerprint, Dialekt und `overlayHash` validiert |
| `--rename-table` | Nein | `<from>:<to>`, wiederholbar | Inline-Shortcut fuer Tabellen-Rename; CLI baut daraus ein synthetisches `rename-mapping`-Overlay mit `source = "cli-inline"`. Bewusst NICHT artefaktstabil — Inline-Overlays werden nicht in `migration-plan.v1` serialisiert. Fuer langlebige Plaene `--migration-overlay` mit Datei nutzen |
| `--rename-column` | Nein | `<table>.<from>:<table>.<to>`, wiederholbar | Inline-Shortcut fuer Spalten-Rename; gleiche Bedingungen wie `--rename-table`. Tabellen-Prefix muss beidseitig identisch sein, sonst Exit 2 |
| `--dry-run` | Nein | Boolean | Plan/SQL erzeugen, aber nichts ausführen; gegenseitig exklusiv mit `--execute` |
| `--debug-body` | Nein | Boolean | **UNSAFE-Override** für die Display-/Diagnostic-Plane: Routine-Bodies erscheinen unmaskiert im Report und im `--output`-Artefakt (Default ist `bodyDisplay = SCRUBBED_ONLY` über `RoutineBodyScrubber.preview(...)`; das Artefakt enthält dann gescrubbten Body-Text, und der Report die `{sqlHash, sqlLength, scrubbedPreview, scrubbingApplied}`-Metadaten plus gescrubbtem `sql`-Feld). Execution-Plane (die Statements, die der `--execute`-Pfad gegen die DB schickt) bleibt unverändert mit Rohbody; das Display-Artefakt `--output` ist explizit eine Anzeige-/Diagnoseausgabe und folgt der Display-Plane-Regel. Für Pipelines, die das `--output`-Artefakt zur Re-Execution brauchen, MUSS `--debug-body` gesetzt werden. Logging-/Runner-Trace- und DB-Adapter-Pfade greifen den `RoutineBodyLogRedactor`-Hook, der den Flag berücksichtigt — ohne `--debug-body` wird kein unmaskierter Body in Diagnostic-Logs sichtbar. Nutzung nur in kontrollierter Debug-Session sinnvoll. |
| `--routine-capability` | Nein | `<kind>:<key>=<value>[,<key>=<value>...]`, wiederholbar | Operator-Override fuer die Per-Routine-Kind-Capability von Stored Functions/Procedures. Erlaubte `<kind>`: `function`, `procedure`; erlaubte Keys: `enabled`, `minServerVersion`. Praezedenz, YAML-Aequivalent und Fehler-Routing siehe Abschnitt **Routine-Rendering** unten. |
| `--strict-gap-operations` | Nein | Boolean | Blockt Operationen, die ueber einen Multi-Statement-Fallback mit Sichtbarkeitsluecke gerendert wuerden (heute: `ReplaceTrigger` via Drop+Create auf PostgreSQL < 14, MySQL und SQLite). Default `false` (lenient): der Pfad emittiert die Drop+Create-Statements und meldet die Luecke als `W_TRIGGER_REPLACE_GAP`-Warning im Report. Mit Flag wechselt der Renderer zu `MANUAL_ACTION_REQUIRED` (Exit `8`) und gibt keine Statements fuer die betroffene Operation aus. Wirkt allgemein auf `OperationRisk.hasGap = true`-Operationen; aktuell setzt nur der Trigger-Mapper diesen Flag. |
| `--sqlite-named-sequences` | Nein | `action_required` / `helper_table` | SQLite-Sequence-Strategie fuer den `--execute`-Pfad (Default: `action_required`). Identisch zur gleichnamigen Option auf `schema generate`, aber hier auf der Migrate-Seite: nur mit `helper_table` aktiviert sich der `SequencePreserveStage`-Probe-Pfad fuer SQLite (`SqliteSequenceCurrentValueProbe` liest `dmg_sequences.next_value`); ohne Opt-in blockt jede `preserveCurrentValue`-Kandidat-Op vor der Probe-Connection mit `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` (`primaryBlockedReason = MANUAL_ACTION_REQUIRED`). |

Begriffe (vollständig in `spec/design.md`):

- **`SchemaDiff`** — struktureller Unterschied zwischen zwei Schemas
- **`DiffView`** — stabile, primitive-only Compare-Ausgabe für `schema compare`
- **`DiffResult`** — migrationsfähiger Operationsplan (Phasen, Reversibilität, Risiko)
- **`MigrationDdlResult`** — gerenderte Up-/Down-DDL plus Metadaten

Modi:

| Modus | `--source` | `--target` | `--dialect` | `--execute` |
|---|---|---|---|---|
| Datei-zu-DB | Soll-Schema | `db:<url-or-alias>` | optional, muss zur DB passen | erlaubt |
| Datei-zu-Datei | Soll-Schema | `file:<current.yaml>` | Pflicht | nicht erlaubt (Exit `2`) |

Ausgabevertrag:

- **Up-SQL**: Mit `--output` in Datei (atomar geschrieben), ohne `--output` bei renderbarem Dry-Run nach `stdout`. Bei `--execute` ohne `--output` keine Persistierung — die Ausführung gegen die DB ist das Artefakt.
- **Down-SQL**: Nur über `--rollback-output`. Wird nie nach `stdout` geschrieben und nie als zweiter Block in das Up-SQL-Artefakt eingebettet.
- **Report**: Mit `--report` in Datei, ohne bei `--plan-only` nach `stdout`. `--execute` ohne `--report` ist Exit `2` (auditpflichtig).
- **Atomare Finalisierung**: Up-SQL, Down-SQL und Reports werden in eine temporäre Datei im Zielverzeichnis geschrieben und erst nach erfolgreichem Planning, Rendering und Blocker-Check atomar verschoben. Bei Render- oder Ausführungsfehlern bleiben bestehende Zielpfade unverändert.

Routine-Rendering:

- PostgreSQL rendert Routine-Replace fuer Functions und Procedures ueber `CREATE OR REPLACE`, sofern der jeweilige Body bekannt und der Dollar-Tag konfliktfrei ist.
- MySQL-Familie unterscheidet Oracle MySQL und MariaDB. Der neutrale Datei-zu-Datei-Dialekt `mysql` verwendet Oracle-MySQL-Semantik: Stored-Routine-Replace darf kein `CREATE OR REPLACE` erzeugen und nutzt nur bei sicherem Dependency-Guard `DROP` + `CREATE`, sonst `MANUAL_ACTION_REQUIRED`. Bei Datei-zu-DB aktiviert ein live erkannter MariaDB-Vendor-String `CREATE OR REPLACE` fuer Functions/Procedures.
- **MySQL Sequence Diff-Migration**: `schema migrate` rendert
  `CreateSequence` / `AlterSequence` / `DropSequence` /
  `RenameSequence` gegen die aktuelle helper-table-Emulation
  (`dmg_sequences` + `dmg_nextval` / `dmg_setval` + per-Spalte-
  Trigger). Mode-Gate: nur mit `--mysql-named-sequences
  helper_table` aktiv; im Default `action_required`-Modus blockt
  jede Sequence-Diff-Op mit Diagnostic-Code `E056` →
  `MigrationBlockedReason.MANUAL_ACTION_REQUIRED`, kein SQL wird
  emittiert. Im `helper_table`-Modus emittieren die Renderer:
    - `CreateSequence` UP: Bootstrap einmalig pro Migrations-
      Richtung. Der Bootstrap-Block ist idempotent:
      `CREATE TABLE IF NOT EXISTS dmg_sequences (...)` + jeweils
      `DROP FUNCTION IF EXISTS dmg_nextval;` /
      `dmg_setval;` gefolgt von `CREATE FUNCTION ...`. Damit
      kollidiert eine Migration gegen eine DB, die bereits
      Helper-Objekte aus einem früheren Lauf trägt, nicht beim
      Bootstrap-Schritt. Spaltensignatur-Drift der bestehenden
      `dmg_sequences` ist nicht abgesichert; ein kuenftiger Drift-Check
      soll das vor der Ausfuehrung blockieren. Anschließend
      `INSERT INTO dmg_sequences (...) VALUES (...)`. Wer mit
      `--generate-rollback` Up + Down erzeugt, sieht den
      Bootstrap-Block in beiden Artefakten — UP und DOWN tracken
      separat. DOWN: nur
      `DELETE FROM dmg_sequences WHERE name = ...`.
    - `AlterSequence` UP/DOWN: `UPDATE dmg_sequences SET ...` auf
      den deklarativen Feldern, die sich tatsächlich zwischen
      `before` und `after` unterscheiden (`increment_by`,
      `min_value`, `max_value`, `cycle_enabled`, `cache_size`);
      `start` / `next_value` (Laufzeitstatus) bleiben unangetastet
      und werden nur ueber den separaten `preserveCurrentValue`-Pfad
      behandelt.
    - `DropSequence` UP: `DROP TRIGGER IF EXISTS …` pro per-Spalte
      gebundenem Sequence-Trigger (aus `currentSchema` ermittelt
      durch `SequenceNextVal`-Default-Walk) + `DELETE FROM
      dmg_sequences`. DOWN: Bootstrap (einmalig pro Down-Richtung)
      + `INSERT` + alle gebundenen Trigger wiederherstellen. **Beide
      Richtungen lesen die Trigger-Bindungen aus `currentSchema`**
      (Pre-Up-State, einziger Ort, an dem die Bindungen leben);
      `desiredSchema` ist Post-Up und enthält die Bindungen nicht
      mehr — analoger Reviews-Fix dokumentiert das ausdrücklich.
    - `RenameSequence`: kommt unter Normalbetrieb nicht durch —
      `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` liefert
      `RenameSupport.DropCreateFallback`, der Mapper zerlegt die
      Rename in `DropSequence(from)` + `CreateSequence(to)` mit
      gemeinsamer `RenameProvenance`. Der `SequenceDefaultReprojector`
      schreibt `SequenceNextVal("from")`-Referenzen in
      `CreateTable` / `AddColumn` / `AlterColumnDefault`-Ops auf
      `"to"` um; der `DependencyAnalyzer` setzt die Kante auf das
      Fallback-`CreateSequence`. Ein direkt emittierter
      `RenameSequence`-Op (planner regression / artefact replay)
      fällt auf eine defensive Implementierung zurück, die
      `UPDATE dmg_sequences SET name = …` plus
      `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` (mit neuem
      Body-Literal `dmg_nextval('to')`) emittiert.
    - **Spalten mit `SequenceNextVal`-Default**:
      `CreateTable` / `AddColumn` / `AlterColumnDefault` mit
      `SequenceNextVal("...")`-Default werden nicht über
      `MysqlTypeMapper.toDefaultSql` geroutet (würde crashen),
      sondern lassen den `DEFAULT`-Token in der Spaltenzeile
      aus und emittieren stattdessen einen per-Spalte
      BEFORE-INSERT-Trigger (`dmg_seq_<table16>_<column16>_<hash10>_bi`)
      über `MysqlSequenceEmulationTemplates.sequenceTriggerSql`,
      der `dmg_nextval('<seq>')` aufruft wenn der Insert-Wert
      NULL ist. Der Mode-Gate (`E056`) gilt auch für diesen
      Pfad: ohne `--mysql-named-sequences helper_table` wird die
      Op blockiert, bevor irgendein `CREATE TABLE` / `ADD COLUMN`
      emittiert wird. `AlterColumnDefault` wechselt zwischen
      Sequence- und Konstant-Defaults sauber (DROP TRIGGER vor
      `SET DEFAULT`; bzw. `DROP DEFAULT` + CREATE TRIGGER). Das
      DOWN von `DROP COLUMN` lässt MySQL den kanonisch
      benannten Trigger implizit cascadieren — kein extra DROP
      TRIGGER nötig.
    - **`AlterSequence` mit nur Runtime-State-Delta**: wenn die
      einzige Änderung der `start`-Wert ist (managed-Felder
      `increment`/`minValue`/`maxValue`/`cycle`/`cache` bleiben
      gleich), emittiert der Renderer keinen `UPDATE`, sondern
      einen INFO-severity-Diagnostic
      `MYSQL_SEQUENCE_RUNTIME_STATE_NO_OP` mit Hinweis auf den
      separaten `preserveCurrentValue`-Pfad. Die Op wird im Report
      als skipped + Info dokumentiert, ohne Migration-Blocker.
  DELIMITER-Konvention:
  Der `schema migrate --execute`-Pfad emittiert Routinen + Trigger
  **delimiterfrei** als ein einzelnes JDBC-Statement (BEGIN…END
  ist eine logische MySQL-Anweisung; die internen `;` sind Teil
  des Bodies). Der `schema generate --output file.sql`-Pfad
  wickelt die gleichen Templates mit `DELIMITER //…DELIMITER ;` für
  mysql-CLI-Konsum.
  Ausblick: Live-DB-Drift-Check gegen bestehende `dmg_sequences`-Rows
  (E124-Kollisionsprüfung gegen vorgefundene Werte und Trigger-
  Body-Marker). Der aktuelle Renderer
  prüft nur die Mode-Gate-Bedingung (`E056`); die Bootstrap-
  Idempotenz (`CREATE TABLE IF NOT EXISTS` + `DROP FUNCTION IF
  EXISTS`) schützt vor wiederholten Migrations-Läufen, fängt aber
  keine Drift zwischen erwarteten und tatsächlichen Helper-Objekt-
  Definitionen. Solche Live-Inkonsistenzen führen heute zu
  Runtime-Fehlern im `--execute`-Pfad; ein kuenftiger Drift-Check soll
  sie als `E124`-Block ausweisen.
- Operatoren koennen die Defaults pro Routine-Kind ueberschreiben — via wiederholbarer `--routine-capability`-Flag oder via `.d-migrate.yaml`-Sektion `routineCapability:`. Format des YAML-Eintrags:

  ```yaml
  routineCapability:
    function:
      enabled: true
      minServerVersion: "8.0.0"   # nur MySQL relevant; muss quoted bleiben, damit SnakeYAML nicht zu Double coerciert
    procedure:
      enabled: false
  ```

  CLI-Flag-Beispiel (entspricht dem YAML-Snippet, repeatable):

  ```
  --routine-capability=function:enabled=true,minServerVersion=8.0.0 \
  --routine-capability=procedure:enabled=false
  ```

  Praezedenz pro Routine-Kind: CLI > YAML > Dialekt-/Server-Version-Defaults. Das Merging ist **feldweise**: jedes der Felder `enabled` und `minServerVersion` wird einzeln aufgeloest. Eine CLI-Angabe wie `--routine-capability=function:minServerVersion=8.0.0` ohne `enabled` ist gueltig — `enabled` faellt dann auf den YAML-Eintrag oder, falls auch dieser keinen Wert traegt, auf den Dialekt-/Server-Version-Default zurueck. Eine fehlende Top-Level-Sektion ist gleichwertig mit "keine Override" und faellt komplett auf die Defaults zurueck. Pro `<kind>` ist maximal eine `--routine-capability`-Angabe erlaubt; doppelter Eintrag, unbekannter `<kind>`, unbekannter Key, unparsable `enabled` oder unparsable `minServerVersion` produzieren `EffectiveRoutineCapability.Invalid(reason)`, welches der MySQL-Renderer als `ROUTINE_CAPABILITY_CONFIG_INVALID` + `MANUAL_ACTION_REQUIRED` (Exit `8`) ausweist; der Reason landet als Suffix in der Diagnostik-Message. Identische Wirkung haben semantisch invalide YAML-Eintraege (z. B. unquoted Float `minServerVersion: 8.0` — SnakeYAML coerciert zu `Double`, der Parser verlangt einen quoted String wie `"8.0.0"`). Strukturell invalide YAML (`routineCapability: true`) blockiert frueher mit `ConfigResolveException`.

Trigger-Rendering:

- PostgreSQL rendert `CreateTrigger`/`ReplaceTrigger`/`DropTrigger` als native `CREATE TRIGGER ... EXECUTE FUNCTION <ref>;` / `DROP TRIGGER <name> ON <table>;`. Der Body **muss** eine strikte `[schema.]identifier([arg, ...])`-Funktionsreferenz sein — inline PL/pgSQL blockt der Renderer mit `TRIGGER_BODY_NOT_FUNCTION_REFERENCE` (Exit `8`). Replace nutzt natives `CREATE OR REPLACE TRIGGER` ab PG-14, sonst Drop+Create-Fallback.
- MySQL rendert `CREATE TRIGGER <name> <timing> <event> ON <table> FOR EACH ROW <body>;` mit inline-Body **ohne** `DELIMITER`-Wrapper (analog Routine-Rendering); `DROP TRIGGER <name>;` mit bare Triggername (`<table>.<name>` ist MySQL-Syntaxfehler, `<schema>.<name>` ist nicht abgedeckt). Replace ist immer Drop+Create. Pre-Flight-Blocker je nach Modellfeld (Validator-Reihenfolge: INSTEAD-OF zuerst, dann `condition`, dann `forEach`, dann leerer Body): `MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED` (PG-only), `MYSQL_TRIGGER_CONDITION_UNSUPPORTED` (MySQL kennt kein `WHEN` — Modellfeld `condition != null`), `MYSQL_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED` (nur Row-Trigger), `ROUTINE_BODY_UNKNOWN` (leerer Body).
- SQLite rendert `CREATE TRIGGER ... BEGIN <body> END;` mit impliziter `FOR EACH ROW`-Orientierung; `DROP TRIGGER <name>;` mit bare Triggername (SQLite-Trigger sind global). Replace ist immer Drop+Create. Pre-Flight-Blocker: `SQLITE_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED` (nur Row-Trigger), `SQLITE_TRIGGER_BODY_NOT_RENDERABLE` (`sourceDialect` ungleich `sqlite`). Trigger auf einer Tabelle, die ueber `AlterColumnType` / `AlterColumnNullability` / `AlterConstraint` einen Rebuild ausloest, werden von `SqliteRebuildPlanner` in den Rebuild-Block absorbiert; der Standalone-Renderer rendert nur Trigger ohne Rebuild-Betroffenheit.
- **Gap-Vertrag**: jede Drop+Create-Replace traegt
  `OperationRisk.hasGap = true` und emittiert einen
  `W_TRIGGER_REPLACE_GAP`-WARNING-Diagnostic im Report. Den Flag setzt
  der Mapper anhand der dialekt- und (fuer PostgreSQL) server-version-
  abhaengigen Capability, **bevor** der Renderer laeuft — damit ist
  jeder Renderer-Pfad authoritativ via `op.risks.<direction>.hasGap`
  und nicht via eigene Capability-Lookup. Mit `--strict-gap-operations`
  hebt der Renderer-Pre-Emit-Guard die Operation auf
  `MANUAL_ACTION_REQUIRED` (Exit `8`) — keine Statements werden
  emittiert, kein Gap-Warning, statt dessen ein
  `OPERATION_HAS_GAP_STRICT_BLOCKED`-Diagnostic. Die Capability-
  Entscheidung liegt in `TriggerPlanningContext`,
  `TriggerCapability` und `TriggerPlanningContextFactory`.
- **Identitaets-Kollision**: zwei Trigger mit gleichem Namen auf verschiedenen Tabellen blockt der `TriggerNameCollisionDetector` mit `TRIGGER_NAME_COLLISION` (Exit `8`). Der Reader-Pfad konsultiert den Detektor vor der Map-Materialisierung; YAML-Files-mit-doppeltem-Map-Key blocken ueber Jacksons `FAIL_ON_READING_DUP_TREE_KEY` bereits im Codec.
- Body-Sanitisation findet im Renderer **nicht** statt. Display-/Report-Plane scrubbt Trigger-Bodies ueber den gemeinsamen `RoutineBodyScrubber` (E.1) — `PASSWORD '...'`-Literale und andere bekannte Patterns sind im Report maskiert, im `--output`-Artefakt scrubbed und im Live-`--execute`-Pfad raw.

Aktuell ausgeklammert: MySQL DEFINER-Rendering und MySQL `INSTEAD OF`-Trigger (Modellfeld blockt mit `MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED`, da MySQL keine INSTEAD-OF-Trigger kennt — PG-only); SQLite `INSTEAD OF`-Trigger (rendert as-is, weil SQLite das View-bezogen akzeptiert; der Renderer prueft Tabelle-vs-View nicht vor, weil das neutrale Modell den Unterschied am Trigger-Target heute nicht ausweist); schemaqualifizierter `DROP TRIGGER` und SQLite-Trigger-Reverse-Read aus `sqlite_master` sind nicht abgedeckt. Eine `TriggerDefinition`-Modellerweiterung (`events`-Liste mit Spaltenliste, `enabledState`) ist nicht Teil des aktuellen Modells.

Report-Felder für Materialized Views:

- `operations[].objectType` ist `MATERIALIZED_VIEW`, wenn die zugrunde
  liegende View-Operation `materialized: true` trägt oder eine der neuen
  `CreateMaterializedView` / `DropMaterializedView`-Ops vorliegt.
- `materializedViews[]` enthält pro betroffener Operation
  `operationId`, `action`, `path`, `dialect`, `status`,
  `stalenessAfterUp`, `refreshSteps`, `locking`, `rollback` und das
  optionale `primaryBlockedReason` (`null` bei `status=READY`).
- PostgreSQL Create/Drop sind diff-basiert renderbar; das Vertrags-Mapping
  läuft wie folgt:

  | Op + Renderer-Ausgang | `status` | `stalenessAfterUp` | `refreshSteps` | `locking` | `rollback` |
  |---|---|---|---|---|---|
  | PG `CreateMaterializedView` rendert | `READY` | `FRESH_AFTER_INITIAL_REFRESH` | `[INITIAL_REFRESH_VIA_CREATE]` | `ACCESS_EXCLUSIVE` | `DROP_CREATED_MATERIALIZED_VIEW_REFRESH_NOT_REQUIRED` |
  | PG `ReplaceMaterializedView` rendert | `READY` | `FRESH_AFTER_REPLACE_REFRESH` | `[DROP_CREATE_INITIAL_REFRESH]` | `ACCESS_EXCLUSIVE` | `SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED` |
  | PG `DropMaterializedView` rendert, `query` bekannt | `READY` | `NOT_APPLICABLE_DROP` | `[]` | `ACCESS_EXCLUSIVE` | `SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED` |
  | MySQL/SQLite Create/Drop | `BLOCKED_DIALECT_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_DIALECT_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Refresh-Contract verlangt `CONCURRENTLY` | `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | `schema refresh materialized-view`-Intent | `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_SCHEMA_REFRESH_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | `ViewDefinition.refresh` ist gesetzt | `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` | `UNKNOWN_BLOCKED` | `[BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Live-Reverse-Read-Metadaten fehlen | `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Create ohne `query` (Planner-Blocker) | `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Drop ohne `query` (Planner-Blocker) | `BLOCKED_DOWN_QUERY_UNKNOWN` | `UNKNOWN_BLOCKED` | `[BLOCKED_DOWN_QUERY_UNKNOWN]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Replace ohne `before.query` (Down-Body fehlt) | `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` | `UNKNOWN_BLOCKED` | `[BLOCKED_REPLACE_DOWN_BODY_UNKNOWN]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | Drop/Replace/Column-Alter einer Tabelle/View/Routine ohne MV-Drop/Replace (orphaning) | `BLOCKED_DEPENDENCY_UNRESOLVED` | `UNKNOWN_BLOCKED` | `[BLOCKED_DEPENDENCY_UNRESOLVED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |
  | `View`↔`MaterializedView`-Konversion | `BLOCKED_CONVERSION_UNSUPPORTED` | `UNKNOWN_BLOCKED` | `[BLOCKED_CONVERSION_UNSUPPORTED]` | `UNKNOWN_BLOCKED` | `ROLLBACK_NOT_POSSIBLE` |

  Die Präzedenz folgt §5 des Implementierungsplans: Dialect-Block schlägt
  Concurrent-Refresh, das wiederum Schema-Refresh, View-Definition-Refresh,
  Metadata-Unsupported und Conversion schlägt; Diff-Metadata- und Down-Query-
  Blocker rangieren am unteren Ende der Priorität. `primaryBlockedReason`
  folgt den Codes aus §6.4.1 (`MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`,
  `MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED`,
  `MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED`,
  `VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED`,
  `MATERIALIZED_VIEW_METADATA_UNSUPPORTED`,
  `MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED`,
  `MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`,
  `MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN`,
  `MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN`,
  `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`).
- Bei `BLOCKED_DEPENDENCY_UNRESOLVED` enthält der `materializedViews[]`-
  Eintrag zusätzlich ein `dependencyBlockers`-Subfield. Jeder Eintrag
  listet `droppingOperationId`, `droppingPath` und `droppingKind`
  (`TABLE`, `VIEW`, `MATERIALIZED_VIEW`, `FUNCTION`, `PROCEDURE`). Wenn
  die MV selbst keine Operation im Plan hat (rein verwaiste MV),
  emittiert der Builder einen synthetischen Eintrag mit `action=ORPHAN`
  und `operationId` der droppenden Operation, damit der Operator den
  Orphan in der Report-Datei sieht.

Report-Felder für `--execute`:

- Bestehende Felder `execution.started`, `execution.completed`,
  `execution.statementsAttempted`, `execution.lastStatementOperationIds`,
  `execution.transactionRolledBack`, `execution.sideEffectsPossible` und
  `execution.executionError` bleiben unveraendert.
- `execution.statementGroups[]` enthaelt pro ausgefuehrter Statement-Gruppe
  `statementGroupId`, `operationIds`, `statementStartInclusive`,
  `statementEndExclusive`, `transactionScope` und `transactionBoundary`.
  Statement-Indizes sind nullbasiert und end-exklusiv.
- `transactionBoundary` ist `BEFORE`, `INSIDE`, `AFTER` oder `NONE` relativ
  zur effektiven Runner- oder Stream-Transaktion.
- Nach Execute-Fehlern enthaelt `execution.recoverability` eine konservative
  Einschaetzung: `FULL_ROLLBACK_CONFIRMED`, `ROLLBACK_ATTEMPTED`,
  `PARTIAL_STATE_POSSIBLE` oder `UNKNOWN`. Bei erfolgreichem Execute ist das
  Feld `null`.
- Gemischte oder nicht unterstuetzte Transaction-Scope-Streams blockieren vor
  dem ersten Statement mit `primaryBlockedReason=TRANSACTION_SCOPE_UNSUPPORTED`
  und Exit `8`.

Report-Felder für Rename-Projection (F.4):

- `renameProjections[]` enthält pro Overlay-Eintrag genau einen Eintrag,
  unabhängig davon, ob die Faltung erfolgreich war oder auf Drop+Create
  zurückfiel. Jeder Eintrag trägt:
  - `candidateId`, `objectType`
    (`table` / `column` / `view` / `trigger` / `function` / `procedure` /
    `sequence`), `fromPath`, `toPath`
  - Overlay-Provenance: `overlaySource`, `overlayEntryId`, optional `overlayHash`.
    `overlayEntryId` ist die stabile Schlüsselgrundlage: mehrere Einträge
    teilen denselben `overlayHash`, nur `overlayEntryId` identifiziert den
    autorisierenden Eintrag.
  - `renameOperationId`: bei erfolgreicher Faltung die ID der emittierten
    `RenameTable`/`RenameColumn`/`RenameView`/`RenameTrigger`/
    `RenameFunction`/`RenameProcedure`/`RenameSequence`-Operation. Bei
    Drop+Create-Fallback `null`.
  - `fallbackOperationIds`: bei Fallback die deterministischen IDs der
    regulär emittierten `Drop*`/`Create*`-Paare (resp.
    `DropColumn`+`AddColumn`). Leer bei erfolgreicher Faltung.
  - `fallbackReason`: kurze, menschenlesbare Begründung bei Fallback.
  - `automatic[]`: vom Engine ohne Folge-Operation projizierte
    Dependencies — `kind` (`FK`, `INDEX`, ...), `path`, `rationale`.
  - `explicit[]`: vom Projector emittierte Folge-Operationen (T5
    View-Reprojection: `kind = VIEW_DROP` / `VIEW_CREATE`, `path` mit
    View-Name, `operationId` auf die emittierte `DropView` / `CreateView`).
  - `blockers[]`: Projector-Blocker mit `code`, `candidateId`, `path`,
    `message`, `severity`. Heutige Policies emittieren ausschließlich
    `severity = WARNING` (Drop+Add-Fallback ist immer renderbar). Eine
    spätere Tranche kann `severity = BLOCKER` für Fälle einführen, in
    denen auch der Fallback nicht renderbar ist; der Migrate-Lauf
    endet dann mit Exit `8`.
- Report-Consumer rekonstruieren `renameProjections`-Einträge nicht aus
  `diagnostics`, Operation-IDs oder Renderer-Nebenwirkungen. Das Feld ist
  der einzige verbindliche Carrier.

Workflow für Renames jenseits von Tabelle/Spalte:

- Overlay-`objectType`-Whitelist umfasst
  `{table, column, view, trigger, function, procedure, sequence}`.
  `materialized_view` bleibt blockiert (Materialized-View-Rename folgt
  einem eigenen D.3b-Vertrag).
- Kanonische Keys sind für `trigger` und `function`/`procedure` pflicht:
  - `objectType=trigger`: `fromName` und `toName` MÜSSEN das Format
    `table::name` haben (`ObjectKeyCodec.triggerKey`). Cross-Table-Moves
    (`orders::audit_old → users::audit_new`) sind kein Rename und werden
    pre-plan blockiert mit `RENAME_OVERLAY_TRIGGER_CROSS_TABLE_REJECTED`.
    Fehlt das `::`-Trennzeichen → `RENAME_OVERLAY_TRIGGER_KEY_INVALID`.
  - `objectType=function` / `procedure`: kanonische Form
    `name(direction:type,...)` auf beiden Seiten
    (`ObjectKeyCodec.routineKey`). Unterschiedliche Signaturen zwischen
    `fromName` und `toName` sind eine Objektänderung, kein Rename, und
    werden pre-plan blockiert mit
    `RENAME_OVERLAY_ROUTINE_SIGNATURE_MISMATCH`. Fehlende Klammern
    → `RENAME_OVERLAY_ROUTINE_KEY_INVALID`.
  - `objectType=view` / `sequence`: sichtbare Namen ohne kanonischen
    Wrapper (analog zu `table` / `column`).
- Per-Dialekt-Verhalten (`ObjectRenamePolicy`):
  - **PostgreSQL**: View, Sequence, Trigger, Function, Procedure
    haben native `ALTER … RENAME`-Templates; der Renderer emittiert
    direkt ein `Rename*`. Materialized-View-Rename bleibt blockiert
    (`OBJECT_RENAME_UNSUPPORTED`). Body-Drift (View/Trigger/Routine
    mit unterschiedlichen Bodies vor/nach Rename) blockiert ebenfalls
    mit `OBJECT_RENAME_UNSUPPORTED`.
  - **MySQL**: View nutzt `RENAME TABLE` (Views liegen im Tabellen-
    Namespace). Trigger, Function, Procedure haben kein
    `ALTER … RENAME` und fallen auf Drop+Create+`RenameProvenance`
    zurück, sofern beide Bodies bekannt und identisch sind; fehlender
    Body oder Drift blockiert. Sequences fallen im MySQL-Sequence-Diff
    ebenfalls auf
    Drop+Create+`RenameProvenance` zurück (helper-table-Emulation —
    siehe MySQL-Sequence-Diff-Block weiter unten).
  - **SQLite**: View und Trigger fallen immer auf
    Drop+Create+`RenameProvenance` zurück (kein natives Rename); gleiche
    Body-Bekanntheits-Regeln wie MySQL. Function/Procedure blockieren
    (SQLite hat kein Routinen-Modell). Sequence-Rename bleibt blockiert,
    bis der SQLite-Sequence-Vertrag steht.
- Sequence-Default-Reprojection: `RenameSequence(old → new)` schreibt
  `DefaultValue.SequenceNextVal("old")`-Referenzen in
  `CreateTable`/`AddColumn`/`AlterColumnDefault`-Ops desselben Plans
  auf `"new"` um. Der `DependencyAnalyzer` erkennt `RenameSequence`
  als Sequence-Provider, sodass die topologische Sortierung den
  Rename strikt vor jeder Spalten-Op platziert. Spalten, die nur in
  der Live-Datenbank existieren und vom Plan nicht berührt werden,
  brauchen keine textuelle Reprojection — PostgreSQL speichert
  `nextval('seq')`-Defaults als OID-Referenzen und folgt dem Rename
  automatisch.
- Plan-Artefakt (`migration-plan.v1`) trägt ein
  optionales `renameProjections[]`-Feld mit denselben Rename-Provenance-
  Informationen (`candidateId`, `objectType`, `from/toPath`,
  Overlay-Provenance, `renameOperationId` oder
  `fallbackOperationIds`+`fallbackReason`). Producer MÜSSEN
  `"rename-projections.v1"` in `semanticExtensions` setzen
  (Convenience: `MigrationPlanArtifact.withRenameProjectionExtension()`),
  sonst blockt der Validator mit
  `PLAN_ARTIFACT_RENAME_PROJECTIONS_REQUIRE_EXTENSION`. Consumer ohne
  Support für die Extension lehnen das Artefakt mit
  `PLAN_ARTIFACT_UNKNOWN_SEMANTIC_EXTENSION` ab — alte Consumer dürfen
  Drop+Create-Fallbacks NICHT als gewöhnliche destruktive Operationen
  ausführen.
- CLI-Eingang: für die fünf neuen `objectType`-Werte gibt es heute
  keine `--rename-{view,trigger,…}`-Inline-Shortcuts; Renames müssen
  über `--migration-overlay <file>` mit einem `rename-mapping`-Overlay
  eingebracht werden. Die kanonischen Keys (Trigger / Routine) sind in
  einer Overlay-Datei besser handhabbar als auf der Kommandozeile.

Exit-Codes:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg (auch No-op-Lauf ohne Diff oder erfolgreicher `--plan-only`) |
| `2` | Ungültige CLI-Argumente (z.B. `--execute` mit Datei-Target, `--execute` + `--dry-run`, `--plan-only` + `--rollback-output`) |
| `3` | Schema-Validierungsfehler |
| `4` | Verbindungsfehler |
| `5` | DDL-Ausführungsfehler nach Beginn von `--execute` (`MIGRATION_ERROR`) |
| `7` | Lokale I/O-, Planungs-, Render- oder Artefaktfehler |
| `8` | Migration durch Risiko-, Rollback- oder Dialektblocker nicht renderbar (`MIGRATION_BLOCKED`) |

Exit `8` muss im strukturierten Fehler eine vollständige `blockers`-Liste und einen optionalen `primaryBlockedReason` enthalten. Mindestens unterscheidbare Fälle:

- destruktive Up-Operation ohne `--allow-destructive`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist `NOT_REVERSIBLE`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist `MANUAL_REQUIRED`
- Ziel-Dialekt kann eine geplante Operation nicht rendern
- gerenderter Execute-Stream mischt nicht gemeinsam ausfuehrbare
  `transactionScope`-Werte (`TRANSACTION_SCOPE_UNSUPPORTED`)
- F.5 CHECK / EXCLUDE-Vollscheibe (2026-05-20): sieben dedizierte
  Diagnostic-Codes pro Blocker, alle ueber den
  `PlannerBlockerClassifier` auf einen `MigrationBlockedReason`
  abgebildet (`hexagon:ports-read`):
  - `CHECK_PREFLIGHT_VIOLATIONS` → `MANUAL_ACTION_REQUIRED`: der
    `--execute`-Preflight-Probe (`SELECT count(*) FROM <t>
    WHERE NOT (<expr>)`) findet existierende Zeilen, die die neue
    restriktive CHECK verletzen. Operator muss die Daten bereinigen
    oder die Constraint locker schreiben.
  - `CHECK_PREFLIGHT_RUNTIME_ERROR` → `MANUAL_ACTION_REQUIRED`: der
    Preflight-Probe selbst wirft (Privilegien, Netzwerk, malformierter
    Expression-Text). Fehlertext steht im Report-Feld
    `checkPreflights[].problem`.
  - `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` →
    `DIALECT_UNSUPPORTED_OPERATION`: EXCLUDE ist PostgreSQL-only;
    MySQL- und SQLite-Renderer blocken den Op (auch im
    SQLite-Rebuild-Pfad, der ein bereits in der Tabelle vorhandenes
    EXCLUDE nicht durch den Rebuild rutschen lassen darf).
  - `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` →
    `MANUAL_ACTION_REQUIRED`: MySQL < 8.0.16 (bzw. MariaDB < 10.2.1)
    parst die CHECK-Klausel, evaluiert sie aber nie. Der Renderer
    weigert sich, Silent-No-Op-DDL zu emittieren; Operator muss
    upgraden oder die Constraint manuell verwalten.
  - `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` → `MANUAL_ACTION_REQUIRED`:
    `mysqlServerVersion` konnte nicht ermittelt werden (Privilegien,
    Offline-Modus); ohne diese Information wird kein
    Default-Enforcement angenommen.
  - `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED` →
    `MANUAL_ACTION_REQUIRED`: konservative Heuristik findet im
    CHECK-Ausdruck Subquery-Marker (`SELECT`, `EXISTS`, `IN (...
    SELECT`). False positives sind moeglich (Spalte heisst
    `selection_count`) — Operator kann die Constraint ueber einen
    triggerbasierten CHECK-Ersatz loesen.
  - `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` →
    `MANUAL_ACTION_REQUIRED`: die EXCLUDE-Element-Whitelist
    akzeptiert bare oder quoted Identifier bzw. balancierte
    `(expr)`-Ausdruecke vor `WITH`; custom Operator-Klassen,
    `COLLATE`, `ASC`/`DESC` und `NULLS …` blocken. Operator kann die
    Constraint mit Standard-Operator-Klassen umschreiben.

  Zusätzlich surfacieren die PostgreSQL- und MySQL-Renderer einen
  fehlenden `expression`-String beim Down-Pass von
  `DropConstraint(CHECK/EXCLUDE)` als `ROLLBACK_NOT_POSSIBLE`
  (Diagnostic-Code `CONSTRAINT_ROLLBACK_EXPRESSION_MISSING`); SQLite
  fällt im Rebuild-Pfad über den existierenden `NOT_REVERSIBLE`-Gate
  auf dieselbe Reason zurueck. Damit ist die Replace-Reversibilität
  als drei-stufiger Vertrag pro Op gepinnt
  (`AUTOMATIC` → `AUTOMATIC_WITH_DATA_RISK` → `NOT_REVERSIBLE`).
- Rename-Overlay strukturell ungueltig (`primaryBlockedReason =
  RENAME_MAPPING_INVALID`): stale Fingerprint
  (`OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT`), mehrdeutige
  Source-/Target-Namen (`OVERLAY_RENAME_MAPPING_AMBIGUOUS`),
  Case-Konflikt (`OVERLAY_RENAME_MAPPING_CASE_CONFLICT`), Chain-Rename
  im selben Overlay (`OVERLAY_RENAME_MAPPING_CHAIN_UNSUPPORTED`),
  doppelter Eintrag (`OVERLAY_RENAME_MAPPING_DUPLICATE`) oder
  unfreigeschalteter `rename-mapping.objectType` ausserhalb der
  aktuellen Whitelist `{table, column, view, trigger, function,
  procedure, sequence}` (`OVERLAY_UNKNOWN_ENTRY_KIND`, getaggt mit
  Rename-Kontext; `materialized_view` bleibt blockiert). Der
  Reason ist additiv: bestehende Reports mit
  `MANUAL_ACTION_REQUIRED` fuer dieselben Codes bleiben semantisch
  als blockiert dokumentiert. Generische Overlay-Probleme ohne
  Rename-Bezug behalten `MANUAL_ACTION_REQUIRED`.

**`migration-plan.v1`-Artefakt**:

Mit `--plan-artefact <path>` schreibt `schema migrate` einen signierten
`migration-plan.v1`-JSON neben den anderen Artefakten. Das Feld ist
opt-in und additiv zu `--report` / `--output` / `--rollback-output`;
der Artefakt wird auch im `--plan-only`- und Exit-8-Pfad emittiert,
sofern der Plan ueberhaupt berechnet werden konnte. Schreibfehler
beendet mit Exit `7` (lokaler I/O-Fehler).

Top-Level-Felder (kanonisierte JSON-Reihenfolge):

| Feld | Typ | Inhalt |
|---|---|---|
| `formatVersion` | String | Konstant `"migration-plan.v1"` |
| `dMigrateVersion` | String | Erzeuger-Version (`SchemaMigrateRunner.createdByVersion`) |
| `sourceFingerprint` / `targetFingerprint` | String | Schema-Fingerprints aus dem Diff |
| `dialect` | String | Lowercase-Dialektname (`postgresql` / `mysql` / `sqlite`) |
| `operations[]` | Array | Pro `DiffOperation`: `id`, `kind` (Subtype-Klassenname), `objectType`, `objectPath`, `phase`, `reversibility`, `upRisk`, optionales `downRisk` |
| `diagnostics[]` | Array | Pro Diagnostic: `code`, `severity`, optionales `operationId` |
| `reversibilitySummary` | Object | `fullyReversible`, `manualRequiredOperationIds[]`, `notReversibleOperationIds[]` |
| `requiredFeatures` | Array | Versionierte Pflichtfeatures, die Consumer unterstuetzen MUESSEN |
| `semanticExtensions` | Array (konditional) | Versionierte erweiternde Semantiken (z.B. `"rename-projections.v1"`); Consumer ohne Support REJECTen |
| `renderedStatements[]` | Array (konditional) | Pro Statement: `statementId` (`stmt-1`, `stmt-2`, ...), `operationIds[]`, `sqlHash`, `transactionScope` (Enum-Name: `RUNNER_OWNED` / `STREAM_OWNED` / `NO_TRANSACTION`) |
| `renameProjections[]` | Array (konditional, Gate `rename-projections.v1`) | Pro Overlay-Rename-Eintrag: `candidateId`, `objectType`, `fromPath`, `toPath`, Overlay-Provenance, `renameOperationId` ODER `fallbackOperationIds` + `fallbackReason` |
| `createdAt` | String | ISO-8601 UTC |
| `producerMetadata` | Object (konditional) | Dekorative Producer-Felder; reservierte Praefixe (`execution.`, `risk.`, `rollback.`, `locking.`, `preflights.`, `secrets.`, `sql.`) blocken; Secret-Marker (`password`, `token`, `jdbc:`, ...) blocken |
| `artifactHash` | String | SHA-256 ueber den signed Payload (ohne `artifactHash` selbst) |

Semantic-Extension-Gates (Producer setzt automatisch, Consumer muss in
`MigrationPlanArtifactValidationContext.supportedSemanticExtensions`
listen):

- `"rename-projections.v1"`: aktiv wenn `renameProjections[]` nicht
  leer. Producer-Convenience:
  `MigrationPlanArtifact.withRenameProjectionExtension()`. Alte
  Consumer ohne diesen Eintrag im Supported-Set lehnen den Artefakt
  ueber `PLAN_ARTIFACT_UNKNOWN_SEMANTIC_EXTENSION` ab — vermeidet,
  dass ein Drop+Create-Fallback als gewoehnlicher destruktiver Change
  ausgefuehrt wird.

Validator-Codes (`MigrationPlanArtifactDiagnostics`):

| Code | Bedingung |
|---|---|
| `PLAN_ARTIFACT_REQUIRED_FIELD_MISSING` | Pflichtfeld ist blank |
| `PLAN_ARTIFACT_UNKNOWN_FORMAT_VERSION` | `formatVersion` nicht in `supportedFormatVersions` |
| `PLAN_ARTIFACT_HASH_MISSING` | `artifactHash` fehlt |
| `PLAN_ARTIFACT_HASH_MISMATCH` | `artifactHash` weicht vom Re-Hash des Payloads ab |
| `PLAN_ARTIFACT_UNKNOWN_REQUIRED_FEATURE` | `requiredFeatures` enthaelt unbekannten Eintrag |
| `PLAN_ARTIFACT_UNKNOWN_SEMANTIC_EXTENSION` | `semanticExtensions` enthaelt unbekannten Eintrag |
| `PLAN_ARTIFACT_RENAME_PROJECTIONS_REQUIRE_EXTENSION` | `renameProjections` non-empty aber Gate `"rename-projections.v1"` fehlt — Producer-Bug |
| `PLAN_ARTIFACT_RESERVED_PRODUCER_METADATA` | `producerMetadata`-Key matched reserviertes Praefix |
| `PLAN_ARTIFACT_SECRET_BEARING_PRODUCER_METADATA` | Key oder Wert enthaelt Secret-Marker |
| `PLAN_ARTIFACT_REVERSIBILITY_SUMMARY_MISMATCH` | `reversibilitySummary` widerspricht den Operations-`reversibility`-Werten |
| `PLAN_ARTIFACT_UNKNOWN_REVERSIBILITY_OPERATION` | `reversibilitySummary` referenziert nicht-existente `operationId` |

Verbindlich: Drop+Create-Operationen, die ueber einen
`renameProjections[]`-Eintrag mit gefuelltem `fallbackOperationIds`
referenziert werden, sind Teil einer logischen Rename-Faltung und
duerfen vom Consumer NICHT als gewoehnliche destruktive Operation
ausgefuehrt werden. Die Drop+Create-Ops selbst tragen optional ein
internes `renameProvenance`-Metadatum (nicht im Artefakt seriell
exponiert); die fuer Consumer verbindliche Quelle bleibt
`renameProjections[]`.

Abgrenzung gegen `export flyway|liquibase|django|knex`: jene Tools-Adapter erzeugen baseline-/full-state-Exports aus einem einzelnen Schema; `schema migrate` arbeitet diff-basiert (`current → desired`).

#### `schema rollback`

Validiert und führt das von `schema migrate --generate-rollback` erzeugte
Down-SQL-Artefakt gegen eine Datenbank aus. Führt keine Live-Diff-Berechnung
durch — der Down-Plan stammt aus dem zur Migration erzeugten Artefakt.

```
d-migrate schema rollback --source <down.sql> --target <db> \
  [--execute] [--allow-destructive] [--dry-run]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Down-SQL aus `schema migrate --generate-rollback` |
| `--target` | Ja | Operand | Ziel-Datenbank (`db:<url-or-alias>`) |
| `--execute` | Nein | Boolean | Down-SQL gegen `--target` ausführen; gegenseitig exklusiv mit `--dry-run` |
| `--allow-destructive` | Nein | Boolean | Destruktive Down-Operationen erlauben |
| `--allow-partial-rollback` | Nein | Boolean | Bewusst partielle Rollback-Artefakte ausführen |
| `--dry-run` | Nein | Boolean | Validierung/Preview, keine Ausführung |

Vor jeder Ausführung prüft der Runner strikt den im Artefakt eingebetteten
`d-migrate rollback-sql`-Metadatenblock (kanonisches JSON, `artifactHash`,
`postUpFingerprint`/`allowedPostUpFingerprints`, `dialect`, Risiko-Felder).
Eine Abweichung führt vor jedem DB-Zugriff zu Exit `7` (Artefakt ungültig).

Neue Rollback-Artefakte verwenden `rollback-sql v2` mit
`formatVersion=v2`. Der Kommentar-Metadatenblock enthält zusätzlich
`statementIndex[]`, `rollbackComplete`, `partialRollback` und
`skippedOperationIds[]`; jeder `statementIndex`-Eintrag beschreibt genau einen
ausführbaren Body-Abschnitt mit `index`, `operationIds`, `phase`,
`transactionScope`, Risiko-Feldern, `startInclusive`, `endExclusive` und
`sha256`. Ranges beziehen sich auf UTF-8-Bytes des LF-normalisierten
SQL-Bodys nach dem End-Delimiter. `schema rollback --execute` führt bei v2
ausschließlich die validierten Body-Abschnitte aus und darf Statements nicht per
Leerzeilen-Split rekonstruieren. Alte `rollback-sql v1`-Artefakte bleiben
lesbar als Legacy-Pfad.

Bei `--execute`:

1. Artefakt-Hash neu berechnen und gegen den im Block gespeicherten Wert prüfen.
2. Dialekt der Ziel-Connection mit dem im Block gespeicherten Dialekt vergleichen → Exit `8` (`TARGET_DIALECT_MISMATCH`) bei Abweichung.
3. Aktuellen Zielzustand introspizieren und gegen `postUpFingerprint` (oder `allowedPostUpFingerprints` bei Recovery-Artefakten) prüfen → Exit `8` (`TARGET_STATE_MISMATCH`) bei Drift.
4. `--allow-partial-rollback` verlangen, falls `partialRollback=true` gesetzt ist → Exit `8` ohne Flag.
5. `--allow-destructive` verlangen, falls Metadatenblock destruktive Down-Operationen ausweist → Exit `8` ohne Flag.
6. Down-SQL gegen `--target` ausführen → Exit `5` bei Statement-Fehler.

Exit-Codes:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg |
| `2` | Ungültige CLI-Argumente |
| `4` | Verbindungsfehler |
| `5` | DDL-Ausführungsfehler nach Beginn von `--execute` |
| `7` | Artefakt ungültig (Hash, Format, Pflichtfelder, fehlender Metadatenblock) |
| `8` | Drift-, Dialekt- oder Freigabe-Blocker (`TARGET_STATE_MISMATCH`, `TARGET_DIALECT_MISMATCH`, fehlendes `--allow-destructive`) |

### 6.2 data

#### `data export`

Streamt Tabellen aus einer Datenbank in JSON, YAML, CSV oder Parquet. Pull-basiert,
chunk-weise — geeignet auch für Tabellen, die größer sind als der verfügbare
Heap.

```
d-migrate data export --source <url-or-name> --format <format> [--output <path>]
```

**Auflösung von `--source`** (siehe §1.4):

- enthält der Wert `://`, wird er als vollständige Connection-URL behandelt
  und unverändert an den `ConnectionUrlParser` übergeben
- sonst wird er als Connection-Name interpretiert und in
  `database.connections.<name>` der `.d-migrate.yaml` aufgelöst (CLI > ENV >
  Default-Pfad-Priorität, `${ENV_VAR}`-Substitution mit `$${VAR}`-Escape)

| Flag | Pflicht | Typ | Default | Beschreibung |
|---|---|---|---|---|
| `--source` | Ja | URL oder Name | — | Connection-URL oder Name aus `.d-migrate.yaml` |
| `--format` | Ja | String | — | Ausgabeformat: `json`, `yaml`, `csv`, `parquet` (kein Default — explizit setzen, §6.15). Parquet schreibt je nach Ziel ein Bundle (Verzeichnis + `manifest.yaml`) oder Single-File; zusätzliche Parquet-Flags siehe CHANGELOG `[0.9.8]` |
| `--output`, `-o` | Nein | Pfad | stdout | Ziel-Datei (Single-Tabelle) oder Verzeichnis (mit `--split-files`) |
| `--tables` | Nein | Liste | alle Tabellen | Nur diese Tabellen (kommasepariert). Strikt validiert gegen `[A-Za-z_][A-Za-z0-9_]*` (optional `schema.table`); ungültige Werte → Exit 2. |
| `--filter` | Nein | String | — | Filter-DSL-Ausdruck. Erlaubte Operatoren: `=`, `!=`, `>`, `>=`, `<`, `<=`, `IN (...)`, `IS NULL`, `IS NOT NULL`, `AND`, `OR`, `NOT`, Klammern. Erlaubte Funktionen: `LOWER`, `UPPER`, `TRIM`, `LENGTH`, `ABS`, `ROUND`, `COALESCE`. Arithmetik (`+`, `-`, `*`, `/`) und qualifizierte Identifier (`table.column`) sind zulaessig. Alle Literale werden als Bind-Parameter an JDBC gebunden. Rohes SQL wird nicht mehr akzeptiert — nicht DSL-konforme Eingaben enden mit Exit 2. |
| `--since-column` | Nein | String | — | Marker-Spalte für inkrementellen Export (LF-013). Muss zusammen mit `--since` gesetzt werden; gleiche Identifier-Regel wie `--tables`. |
| `--since` | Nein | String | — | Untere Marker-Grenze für LF-013. Wird typisiert und parametrisiert an JDBC gebunden; nur zusammen mit `--since-column` gültig. |
| `--encoding` | Nein | String | `utf-8` | Output-Encoding (z.B. `utf-8`, `iso-8859-1`, `utf-16`) |
| `--chunk-size` | Nein | Integer | `10000` | Rows pro Streaming-Chunk |
| `--split-files` | Nein | Boolean | aus | Eine Datei pro Tabelle in `--output <dir>`. Bei mehreren Tabellen Pflicht. |
| `--csv-delimiter` | Nein | Char | `,` | CSV-Spalten-Trennzeichen (genau ein Zeichen) |
| `--csv-bom` | Nein | Boolean | aus | BOM passend zu `--encoding` vor dem CSV-Output schreiben (UTF-8, UTF-16 BE/LE). Für Encodings ohne definiertes BOM (z.B. `iso-8859-1`, `windows-1252`) ist das Flag ein No-op. |
| `--csv-no-header` | Nein | Boolean | aus | Header-Zeile bei CSV unterdrücken (Default: Header an, §6.17) |
| `--null-string` | Nein | String | `""` | CSV-NULL-Repräsentation |
| `--resume` | Nein | String | — | Resume eines frueheren Exports aus einer Checkpoint-Referenz, inkl. Mid-Table-Wiederaufnahme. Wert ist eine `checkpoint-id` **oder** ein Pfad; Pfade MUESSEN innerhalb des effektiven `--checkpoint-dir` / `pipeline.checkpoint.directory` liegen (Pfade ausserhalb → Exit 7). **Nur file-basiert**: kombiniert mit stdout-Export (kein `--output`) endet der Aufruf mit Exit 2; ohne konfiguriertes Checkpoint-Verzeichnis endet der Aufruf mit Exit 7. Der Lauf uebernimmt `operationId` aus dem Manifest, skippt Tabellen mit Status `COMPLETED` und setzt unvollstaendige Tabellen fort. **Mid-Table**: ist `--since-column` gesetzt **und** hat die Tabelle einen Primaerschluessel, setzt der Lauf die Tabelle ab dem zuletzt chunk-bestaetigten Composite-Marker `(sinceColumn, PK)` lexikografisch strikt fort; fehlt der PK, fallt der Lauf mit sichtbarem stderr-Hinweis auf Tabellen-Reexport zurueck. Single-File-Ziele werden immer ueber eine Staging-Datei im Checkpoint-Verzeichnis geschrieben und erst bei Erfolg per atomic rename ersetzt; Single-File-Resume ignoriert den gespeicherten Marker und exportiert die Tabelle erneut von vorn. Kompatibilitaetsmismatch (Fingerprint inkl. PK-Signatur, Tabellenliste, Output-Modus, operationType; oder Manifest hat `resumePosition`, Request hat aber kein `--since-column`) → Exit 3. |
| `--checkpoint-dir` | Nein | Pfad | (Config `pipeline.checkpoint.directory`) | Verzeichnis fuer Checkpoints. Der CLI-Wert hat Vorrang vor `pipeline.checkpoint.directory` in `.d-migrate.yaml`. |

**Output-Auflösung**:

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
| `2` | CLI-Fehler: ungültige Optionen, unzulässige Flag-Kombination, ungültiger `--csv-delimiter`/`--encoding`/`--tables`/`--since-column`-Identifier, fehlendes Gegenstück zu `--since-column`/`--since`, nicht DSL-konformer `--filter`-Ausdruck, unverträgliche `--output`/`--split-files`-Kombi, **oder `--resume` auf stdout-Export** |
| `3` | Preflight-Fehler — semantisch inkompatible Resume-Referenz (operationType-Mismatch, Fingerprint-Mismatch aus Format/Encoding/CSV-Optionen/Filter/`--since-*`/Tabellen-Reihenfolge/Output-Modus/Output-Pfad/PK-Signatur, Tabellenliste divergiert, oder Manifest hat `resumePosition` aber aktueller Request hat kein `--since-column`). Mapping ist symmetrisch zum Import-Preflight (§4.5) |
| `4` | Connection-Fehler (HikariCP konnte keine Connection öffnen, `TableLister` failed) |
| `5` | Export-Fehler während Streaming (SQLException, IOException, Writer-Failure, fehlende Tabelle) |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml` nicht ladbar/parsebar, unbekannter Connection-Name, fehlende ENV-Variable, kein Treiber für Dialect, `--resume` ohne konfiguriertes Checkpoint-Verzeichnis, Pfad ausserhalb des Checkpoint-Verzeichnisses, Checkpoint-Datei nicht gefunden, unlesbare Datei oder inkompatible `schemaVersion`) |

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

# Filter-DSL — alle Literale als Bind-Parameter
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
- Der `--since`-Wert wird im Runner typisiert und als JDBC-Bind-Parameter an eine `DataFilter.ParameterizedClause("<quoted-column> >= ?", [typedSince])` übergeben. Die Typisierung bleibt konservativ: ein Offset-haltiger ISO-String bleibt `OffsetDateTime`, ein lokaler ISO-DateTime bleibt `LocalDateTime`, ein ISO-Datum bleibt `LocalDate`, Integer als `Long`, Dezimalwerte als `BigDecimal`, sonst als String. Eine in der Konfiguration gesetzte `i18n.default_timezone` löst **keine** stille Zonierung eines lokalen Literals aus.
- Wenn zusätzlich `--filter` gesetzt ist, werden beide Bedingungen intern als `DataFilter.Compound([ParameterizedClause(dsl), ParameterizedClause(since)])` kombiniert; der Reader bindet alle Parameter in stabiler Reihenfolge.
- `--filter` erzeugt immer eine `ParameterizedClause` via DSL-Parser. Literale `?` in `--filter` werden nicht als JDBC-Placeholder interpretiert, da keine rohen `WhereClause`-Fragmente erzeugt werden.

#### `data import`

Importiert Daten aus JSON, YAML, CSV oder Parquet in eine Datenbank. Der Importpfad ist
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
| `--format` | Nein | String | Auto-Detection nach Dateiendung | Eingabeformat: `json`, `yaml`, `csv`, `parquet`; bei stdin Pflicht (Parquet: Bundle-Verzeichnis oder Single-File) |
| `--schema` | Nein | Pfad | — | Schema-Datei für lokalen Preflight und Tabellen-Reihenfolge bei Verzeichnisimport |
| `--table` | Nein | String | — | Zieltabelle; für stdin und Single-File-Import relevant |
| `--tables` | Nein | Liste | alle | Kommaseparierte Import-Reihenfolge; nur für Verzeichnisquellen |
| `--on-error` | Nein | String | `abort` | Chunk-Fehlerbehandlung: `abort`, `skip`, `log` |
| `--on-conflict` | Nein | String | `abort` | Konfliktbehandlung: `abort`, `skip`, `update` |
| `--trigger-mode` | Nein | String | `fire` | Trigger-Verhalten: `fire`, `disable`, `strict` |
| `--truncate` | Nein | Boolean | aus | Zieltabelle vor Import leeren |
| `--disable-fk-checks` | Nein | Boolean | aus | FK-Checks während des Imports deaktivieren (dialektabhängig) |
| `--reseed-sequences` / `--no-reseed-sequences` | Nein | Boolean | an | Identity-/Sequence-Reseed nach Import steuern |
| `--encoding` | Nein | String | `auto` | Input-Encoding. Der Default-Pfad `auto` erkennt BOM-markierte UTF-Streams (UTF-8, UTF-16 BE/LE) und fällt ohne BOM auf UTF-8 zurück; UTF-32-BOM wird mit Exit 2 abgelehnt. Für Non-UTF-Encodings (`iso-8859-1`, `windows-1252`, …) muss der Wert explizit gesetzt werden — es gibt keine Heuristik-Erkennung. |
| `--csv-no-header` | Nein | Boolean | aus | CSV enthält keine Header-Zeile |
| `--csv-null-string` | Nein | String | `""` | CSV-NULL-Repräsentation |
| `--chunk-size` | Nein | Integer | `10000` | Datensätze pro Chunk/Transaktion |
| `--resume` | Nein | String | — | Resume eines frueheren Imports aus einer Checkpoint-Referenz. Wert ist eine `checkpoint-id` **oder** ein Pfad; Pfade MUESSEN innerhalb des effektiven `--checkpoint-dir` / `pipeline.checkpoint.directory` liegen (Pfade ausserhalb → Exit 7). **Nur file-/directory-basiert**: kombiniert mit stdin-Quelle (`--source -`) endet der Aufruf mit Exit 2; ohne konfiguriertes Checkpoint-Verzeichnis → Exit 7. **Preflight** prueft `operationType == IMPORT`, den Options-Fingerprint (Format, Encoding, CSV-Header/NULL, `--on-error`/`--on-conflict`/`--trigger-mode`/`--truncate`/`--disable-fk-checks`/`--reseed-sequences`/`chunk-size`, Tabellenliste in Reihenfolge, Input-Topologie, Input-Pfad, Ziel-Dialekt und Ziel-URL; fuer Directory-Importe zusaetzlich die `table -> inputFile`-Bindung) sowie die Tabellenlisten-Gleichheit. Inkompatible Referenzen → Exit 3. **Wiederaufnahme** setzt an committed Chunk-Grenzen an: bereits als `COMPLETED` markierte Tabellen werden uebersprungen; teilweise bestaetigte Tabellen lesen die bereits bestaetigten Chunks aus dem Reader (ohne Schreib-/Commit-Aktion) und starten am naechsten offenen Chunk. `--truncate` wird fuer teilweise bestaetigte Tabellen automatisch unterbunden (sonst gingen bestaetigte Zeilen verloren). `--on-error abort/skip/log` behaelt seine Semantik auch beim Resume; nur erfolgreich committete Chunks treiben den Checkpoint vorwaerts. `failedFinish` laesst die Tabelle als `FAILED` markiert (nicht still als `COMPLETED`). Directory-Importe verlangen zusaetzlich, dass die `table -> inputFile`-Bindung des Manifests mit dem aktuellen Directory-Scan uebereinstimmt — umbenannte, hinzugefuegte oder entfernte Dateien → Exit 3. |
| `--checkpoint-dir` | Nein | Pfad | (Config `pipeline.checkpoint.directory`) | Verzeichnis fuer Checkpoints. Der CLI-Wert hat Vorrang vor `pipeline.checkpoint.directory` in `.d-migrate.yaml`. |

**Exit-Codes**:

- `0`: Erfolg
- `2`: Ungültige CLI-Argumente oder unzulässige Flag-Kombinationen (inkl. `--resume` auf stdin-Quelle; unsupported `--lang` an der Root-CLI)
- `3`: Preflight-Fehler — Header-/Schema-Mismatch, strikter Trigger und semantisch inkompatible Resume-Referenz (`operationType`-Mismatch, Fingerprint-Mismatch, Tabellenlisten-Divergenz)
- `4`: Verbindungsfehler
- `5`: Import-Fehler während Verarbeitung oder Commit
- `7`: Konfigurations-, Parse- oder Datei-Fehler (inkl. unlesbare Checkpoint-Datei, ungueltiges Manifest, fehlende Datei, Pfad ausserhalb des Checkpoint-Verzeichnisses oder inkompatible `schemaVersion`)

#### `data transfer`

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
| `--filter` | Nein | String | — | Filter-DSL-Ausdruck fuer die Quellabfrage. Gleiche DSL-Grammatik wie bei `data export --filter`. Alle Literale werden als Bind-Parameter an JDBC gebunden. |
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

#### `data seed`

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

#### `transform procedure`

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

#### `generate procedure`

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

### 6.5 export

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

**Liquibase-Format**: `export liquibase` erzeugt genau einen
versionierten XML-Changelog mit genau einem deterministischen `changeSet`.
`changeSet.id` wird stabil aus Version, Slug und Dialekt abgeleitet,
`changeSet.author` ist der feste Exporter-Wert `d-migrate`, und ein
optional erzeugter Rollback wird als `<rollback>`-Block im selben Changeset
eingebettet. Ein bestehender Master-Changelog wird dabei nicht mutiert.

Exit: `0` Erfolg, `2` ungültige Flags (fehlendes `--target`, fehlendes
`--version` bei Django/Knex), `3` Schema-Validierungsfehler, `7` Parse-/I/O-Fehler.

### 6.6 validate

#### `validate data`

Validiert eine Datendatei
DB-frei gegen eine explizit gebundene Tabelle aus einer Schema-Definition.
v1 prüft Spaltenpräsenz, Typ, Nullability und Länge/Präzision; CHECK, FK,
Top-Level-Tabellenwrapper und headerlose CSV sind spätere Ausbaustufen.

```
d-migrate validate data --source <path> --schema <path> --table <name>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Datendatei (JSON/YAML/CSV) |
| `--schema` | Ja | Pfad | Schema-Definition |
| `--table` | Ja | String | Schema-Tabelle, gegen die die Datendatei geprüft wird |

Tabellenmatching: exakter Tabellenname gewinnt; sonst ist ein eindeutiger
unqualifizierter Treffer erlaubt. Fehlende oder mehrdeutige Schema-Tabelle
ist ein Validierungs-/Preflight-Fehler.

Exit: `0` bei Erfolg, `2` bei ungültigen Flags, `3` bei
Validierungsfehlern, `7` bei Parse-/I/O-Fehlern.

#### `validate procedure`

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

#### `config credentials set`

Speichert verschlüsselte Datenbank-Credentials.

```
d-migrate config credentials set --name <connection> --user <user> --password <password>
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--name` | Ja | String | Verbindungsname |
| `--user` | Ja | String | Benutzername |
| `--password` | Ja | String | Passwort (wird interaktiv abgefragt wenn nicht angegeben) |

Ergebnis: Credentials werden in `~/.d-migrate/credentials.enc` (AES-256) gespeichert. Details unter [Credential-Management](./connection-config-spec.md#4-credential-management).

Exit: `0` bei Erfolg, `7` bei Konfigurationsfehlern.

#### `config credentials list`

Listet gespeicherte Verbindungsnamen (ohne Passwörter).

```
d-migrate config credentials list
```

Exit: `0` bei Erfolg.

#### `config show`

Zeigt die aktive Konfiguration (gemerged aus allen Quellen).

```
d-migrate config show [--section <section>]
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--section` | Nein | String | Nur diesen Abschnitt zeigen (`database`, `ai`, `pipeline`, ...) |

Sensible Werte (Passwörter, API-Keys) werden maskiert als `***`.

Exit: `0` bei Erfolg, `7` bei Konfigurationsfehlern.

### 6.8 mcp

Stellt d-migrate als Model Context Protocol v1 Server bereit. Vollständige Tool-, Resource- und Prompt-Verträge stehen in [`spec/mcp-server.md`](./mcp-server.md) und [`spec/ki-mcp.md`](./ki-mcp.md). Dieser Abschnitt dokumentiert nur den CLI-Vertrag.

#### `mcp serve`

Startet den MCP-Server mit der vollständigen Phase-C/D/E/F/G-Dispatch-Kette. Byte-Inhalte (Upload-Segmente, Artefakt-Bodies) sind file-backed unter `--mcp-state-dir`; Phase-E-Server-State (Idempotenz, Jobs, Quotas) kann optional über `server.state` JDBC-persistent gehalten werden, sonst läuft Phase-E in-memory.

```
d-migrate mcp serve [--transport stdio|http] [--bind <addr>] [--port <n>] \
                    [--auth-mode disabled|jwt-jwks|jwt-introspection] \
                    [--issuer <uri> --jwks-url <url> --audience <aud>] \
                    [--mcp-state-dir <path>] [--cursor-keyring-file <path>] \
                    [--approval-grants-file <path>]
```

**Transport** (`--transport`):

| Wert | Beschreibung |
|---|---|
| `stdio` (Default) | Ein Server-Prozess pro Client-Sitzung, Authentifizierung via `--stdio-token-file` |
| `http` | Streamable HTTP. Loopback-`--bind` erlaubt `--auth-mode disabled`; jede nicht-loopback-Bindung verlangt einen JWT-Modus |

**Authentifizierung** (`--auth-mode`):

| Modus | Geltungsbereich | Zusätzliche Pflichtflags |
|---|---|---|
| `disabled` | Nur Loopback-HTTP oder Stdio | — |
| `jwt-jwks` (Default) | HTTP, lokale JWKS-Validierung | `--issuer`, `--jwks-url`, `--audience` |
| `jwt-introspection` | HTTP, RFC 7662 Introspection | `--issuer`, `--introspection-url`, `--audience` |

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--transport` | Nein | `stdio` / `http` | Default `stdio` |
| `--bind` | Nein | Adresse | HTTP-Bind (Default `127.0.0.1`). Nicht-loopback erfordert `--auth-mode != disabled` |
| `--port` | Nein | Integer | HTTP-Port; `0` wählt einen ephemeralen Port |
| `--public-base-url` | Nein | URL | Öffentliche Basis-URL (HTTPS). Pflicht für nicht-loopback Production-HTTP |
| `--auth-mode` | Nein | Choice | siehe oben |
| `--issuer`, `--jwks-url`, `--introspection-url`, `--audience` | Modusabhängig | URL/String | Siehe Auth-Tabelle |
| `--stdio-token-file` | Nein | Pfad | JSON/YAML-Token-Registry für Stdio (siehe `mcp-server.md` §12.10) |
| `--allow-origin` | Nein | String, mehrfach | Origin-Allowlist; Loopback-Defaults aktiv bei Loopback-Bind |
| `--mcp-state-dir` | Nein | Pfad | State-Verzeichnis für file-backed Bytes. Gewinnt gegen `$DMIGRATE_MCP_STATE_DIR`; Default ist ein CLI-eigener Tempdir, der beim Stop gelöscht wird. Operator-bereitgestellte Verzeichnisse sind single-writer (advisory `.lock`) und überleben den Prozess |
| `--mcp-state-orphan-retention` | Nein | Dauer | Retention für verwaiste Byte-Files beim Startup-Sweep. Werte: `never` (Forensik-Modus, kein Sweep), `0`/`0s` (alles löschen), `<n><ms\|s\|m\|h\|d>`, ISO-8601 `PT…`. Default 24h. Upload-Segmente ohne überlebende Session-Metadaten werden unter jeder nicht-`never`-Policy entfernt |
| `--connection-config` | Nein | Pfad | Server-/Project-YAML für Phase-D Connection-Refs ohne Secrets. Default ist der `--config`-Pfad |
| `--cursor-keyring-file` | Nein | Pfad | YAML-Keyring für HMAC-versiegelte MCP-Cursor. Pflicht für deterministische Multi-Instanz-Deployments — der DEV-Default ist HTTP-Production gesperrt |
| `--approval-grants-file` | Nein | Pfad | JSON/YAML-Store für `ApprovalGrant`-Einträge. Wird zusammen mit `mcp approval-grant issue` benutzt |
| `--operation-timeout-seconds` | Nein | Long | Timeout (Sekunden) für Upload-Finalisierungs-Leases und den Stale-Finalisation-Sweeper |

**Exit-Codes**:

| Code | Trigger |
|---|---|
| `0` | Server beendet sich regulär |
| `2` | Ungültige Konfiguration (z.B. fehlende Auth-Pflichtfelder, ungültige Retention, DEV-Cursor-Keyring im HTTP-Production-Pfad), Sweep-Fehler |
| Sonstige | Werden vom Server-Lifecycle gemappt (siehe `spec/mcp-server.md`) |

#### `mcp approval-grant issue`

Erstellt einen token-gebundenen `ApprovalGrant` für eine offene `POLICY_REQUIRED`-Challenge. Der erzeugte Token wird auf stdout ausgegeben (`approvalToken=…`), der Grant in der mit `--file` referenzierten Store-Datei abgelegt.

```
d-migrate mcp approval-grant issue --file <path> \
                                   --tenant <tenantId> --caller <principalId> \
                                   --tool <toolName> \
                                   --approval-request-id <id> \
                                   --payload-fingerprint <hex> \
                                   --scope <s> [--scope <s> …] \
                                   ( --idempotency-key <key> | --approval-key <key> )
```

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--file` | Ja | Pfad | Pfad zum Grant-Store (gleiche Datei wie `mcp serve --approval-grants-file`) |
| `--tenant` | Ja | String | Tenant-ID aus der `POLICY_REQUIRED`-Antwort |
| `--caller` | Ja | String | Principal-ID, die den Job gestartet hat |
| `--tool` | Ja | String | Tool-Name, z.B. `schema_reverse_start` |
| `--approval-request-id` | Ja | String | `approvalRequestId` aus der Challenge |
| `--payload-fingerprint` | Ja | Hex-String | `payloadFingerprint` aus der Challenge |
| `--scope` | Ja, ≥1× | String | Genehmigter Scope; einmal pro `requiredScope` aus der Challenge wiederholen |
| `--idempotency-key` | Genau einer von beiden | String | Korrelation für Job-Start-Pfade |
| `--approval-key` | Genau einer von beiden | String | Korrelation für synchrone KI-/Tool-Pfade |
| `--issuer-fingerprint` | Nein | String | Stable Issuer-Identität im Grant. Default `cli-approval-grant` |
| `--grant-source` | Nein | String | Audit-/Source-Label. Default `cli-admin` |
| `--expires-at` | Nein | RFC-3339 | Explizites Ablaufdatum; gewinnt gegen `--ttl-seconds` |
| `--ttl-seconds` | Nein | Long | Gültigkeitsdauer wenn `--expires-at` fehlt. Default 300 |
| `--token` | Nein | String | Roher Token; Default ist ein zufällig erzeugter Token (`appr_<32-byte-hex>`). Nur der Fingerprint wird im Grant gespeichert |

**Exit-Codes**:

| Code | Trigger |
|---|---|
| `0` | Grant erstellt, Token auf stdout |
| `2` | Beide oder keiner von `--idempotency-key`/`--approval-key` (Usage-Fehler) |
| `7` | Datei-/Parse-/I/O-Fehler beim Schreiben des Stores |

#### `mcp cursor-key generate` / `mcp cursor-key validate`

Erzeugt bzw. validiert YAML-Keyrings für die HMAC-Cursor von `mcp serve --cursor-keyring-file`. Diese Subkommandos sind Operations-Tools für Multi-Instanz-Deployments und werden im Detail in [`spec/mcp-server.md`](./mcp-server.md) dokumentiert.

---

## 7. Fortschrittsanzeige

### 7.1 Format

`data export` und `data import` emittieren waehrend des Laufs
line-orientierte Fortschrittszeilen auf `stderr`. Die Anzeige ist
deterministisch — es gibt keine Zeitschwelle und keine Cursor-Rewrites.

Pro Event wird genau eine Zeile geschrieben. Es gibt zu jedem Zeitpunkt
hoechstens eine aktive Tabelle (sequenzielle Verarbeitung).

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
| `D_MIGRATE_LANG` | `--lang` | Sprache. `--lang` liegt in der Prioritaet vor `D_MIGRATE_LANG`. Der generische Env-Pfad behaelt den toleranteren Vertrag (syntaktisch gueltige Locales → Root-Bundle-Fallback), waehrend `--lang` strikt auf gebundelte Produktsprachen beschraenkt ist. |
| `D_MIGRATE_OUTPUT_FORMAT` | `--output-format` | Ausgabeformat |
| `D_MIGRATE_NO_COLOR` | `--no-color` | Farbausgabe deaktivieren |
| `D_MIGRATE_ASSUME_YES` | `--yes` | Bestätigungen überspringen |
| `D_MIGRATE_DB_PASSWORD` | | Datenbank-Passwort |
| `D_MIGRATE_AI_API_KEY` | | KI-Provider API-Key (Fallback) |

Prioritaet:

- fuer den effektiven Config-Pfad: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml` <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->
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
`schema validate`. Für `schema reverse` gibt es keinen
stdin-/DDL-Pfad — Reverse arbeitet ausschließlich gegen Live-DB-Verbindungen.

---

## Verwandte Dokumentation

- [Design](./design.md) — CLI-Design §5, Fehlerbehandlung §8
- [Architektur](./architecture.md) — CLI-Modul, Clikt-Framework
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — Schema-Validierungsregeln §13
- [Lastenheft](./lastenheft-d-migrate.md) — LF-012 (CLI), LN-015 (Dokumentation), LN-016 (Fehlermeldungen)

---

**Status**: Diese Referenz beschreibt den aktuellen CLI-Vertrag
für `schema`, `data`, `export` und `mcp`.

**Ausblick**: Neue CLI-Oberflächen werden als eigene Abschnitte ergänzt,
sobald ihr Nutzervertrag feststeht. MCP-Tool-Verträge bleiben in
[`spec/mcp-server.md`](./mcp-server.md) und [`spec/ki-mcp.md`](./ki-mcp.md).
