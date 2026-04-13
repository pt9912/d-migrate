# Implementierungsplan: Phase C - CLI `schema compare`

> **Milestone**: 0.5.0 - MVP-Release
> **Phase**: C (CLI `schema compare`)
> **Status**: Draft
> **Referenz**: `implementation-plan-0.5.0.md` Abschnitt 4.1, Abschnitt 5
> Phase C, Abschnitt 7, Abschnitt 8, Abschnitt 10; `docs/cli-spec.md`
> Abschnitt `schema compare`; `docs/ImpPlan-0.5.0-B.md`

---

## 1. Ziel

Phase C produktisiert den in Phase B gebauten Core-Diff fuer die CLI. Das
Ergebnis ist ein benutzbares `d-migrate schema compare`-Kommando fuer den
file-based MVP-Slice von 0.5.0.

Der Teilplan liefert bewusst nicht nur "Clikt-Glue", sondern den vollstaendigen
CLI-Pfad:

- zwei Schema-Dateien lesen
- beide Schemata validieren
- ueber `SchemaComparator` vergleichen
- deterministische Plain-Text-Ausgabe rendern
- stabile JSON/YAML-Ausgabe fuer Scripting bereitstellen
- Exit `0` und `1` sauber von Fehlerpfaden `2`, `3`, `7` trennen

Nicht Ziel dieser Phase ist ein DB-basierter Compare oder ein Vorgriff auf
`schema migrate`.

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- Phase B hat den fachlichen Kern bereits geliefert:
  - `SchemaComparator`
  - `SchemaDiff`
  - `TableDiff`
  - `ColumnDiff`
  - `EnumTypeDiff`
  - `ViewDiff`
- Diese Typen liegen bereits in `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/`.
- Die CLI besitzt derzeit unter `schema` nur:
  - `validate`
  - `generate`
- Das bestehende Runner-Muster ist etabliert:
  - `SchemaGenerateRunner`
  - `DataExportRunner`
  - `DataImportRunner`
- `SchemaCommands.kt` greift fuer Subcommands per `currentContext.parent?.parent?`
  auf das Root-Command `DMigrate` zu und zieht daraus `CliContext`.
- `OutputFormatter` deckt heute Validation- und Error-Ausgabe ab, ist aber
  fachlich auf `schema validate` zugeschnitten.
- `YamlSchemaCodec.read(...)` existiert und wird fuer das Einlesen neutraler
  Schema-Dateien bereits produktiv genutzt.
- `YamlSchemaCodec.write(...)` ist noch nicht implementiert und ist ohnehin ein
  Schema-Codec, kein Diff-Renderer.
- `hexagon:application` darf weiterhin nur von `hexagon:core` und
  `hexagon:ports` abhaengen; Serialisierungslibraries fuer JSON/YAML gehoeren
  daher nicht in den Runner.

Konsequenz fuer Phase C:

- der CLI-Pfad muss auf dem vorhandenen Runner-Muster aufbauen
- das rohe `SchemaDiff` darf nicht blind als oeffentlicher CLI-Vertrag
  serialisiert werden
- Rendering und Dateiausgabe muessen klar vom Compare-Use-Case getrennt werden

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- neues CLI-Subcommand `schema compare`
- `SchemaCompareRequest` und `SchemaCompareRunner`
- Datei-zu-Datei-Compare fuer zwei neutrale Schema-Dateien
- Validierung beider Eingabedateien vor dem Compare
- Compare ueber `SchemaComparator`
- Plain-Text-Defaultausgabe
- `--output-format json|yaml`
- `--output <path>` fuer Datei-Ausgabe derselben Darstellung
- Exit-Code-Vertrag `0`, `1`, `2`, `3`, `7`
- Unit-Tests fuer Runner und Render-Helfer
- CLI-/E2E-nahe Tests fuer Help, Exit-Codes und Dateiausgabe

### 3.2 Bewusst nicht Teil von Phase C

- DB-URLs oder Named Connections fuer `schema compare`
- `SchemaReader` oder Reverse-Engineering
- Progress-Events oder Progress-Rendering fuer Compare
- `schema migrate` und `schema rollback`
- 1:1-Veroeffentlichung des rohen `SchemaDiff` als externes JSON/YAML-Format
- i18n, Color-Theming oder ANSI-dichte Terminaldarstellung

---

## 4. Architekturentscheidungen

### 4.1 Duenne Clikt-Schale, Runner in `hexagon:application`

Analog zu `schema generate` bleibt der Command in der CLI duenn:

- Clikt sammelt Optionen
- `SchemaCompareCommand` baut `SchemaCompareRequest`
- `SchemaCompareRunner` enthaelt den Ablauf
- der Runner bekommt alle externen Abhaengigkeiten injiziert

Das erhaelt die bestehende Testbarkeit:

- keine Clikt-Abhaengigkeit in Unit-Tests des Compare-Flows
- keine echte Dateisystem- oder Codec-Pflicht im Runner-Test
- Exit-Code-Mapping bleibt direkt pruefbar

### 4.2 Compare bekommt einen stabilen CLI-Output-Vertrag, nicht rohes `SchemaDiff`

Das rohe Core-Diff ist intern richtig, aber als CLI-Vertrag zu instabil:

- `SchemaDiff` enthaelt rohe `TableDefinition`-, `ViewDefinition`- und
  `CustomTypeDefinition`-Payloads
- geaenderte Spalten referenzieren `NeutralType` und `DefaultValue`
- eine blinde JSON/YAML-Serialisierung wuerde Kotlin-/Jackson-Implizitformen
  nach aussen leaken

Fuer Phase C wird daher eine eigene Compare-Projektion eingefuehrt, z. B.:

- `SchemaCompareDocument`
- `SchemaCompareSummary`
- `SchemaCompareDiffView`
- `TableDiffView`
- `ColumnDiffView`
- `ValueChangeView<T>`

Verbindliche Regel:

- das CLI-Format ist eine stabile, compare-spezifische Projektion
- das CLI serialisiert **nicht** das rohe `SchemaDiff` 1:1

### 4.3 Structured Output ist primitive-first

Die Compare-Projektion soll fuer JSON/YAML nur stabile Primitive, Listen und
Maps enthalten.

Pragmatische MVP-Regeln:

- top-level `added`/`removed`-Objekte werden mindestens ueber ihre Namen
  exponiert
- `changed`-Objekte tragen explizite `before`/`after`-Werte
- komplexe Fachobjekte werden ueber kanonische Signaturen oder schmale
  View-DTOs statt ueber rohe Kotlin-Datenklassen ausgegeben

Beispiele:

- Spaltentypen als kanonische Strings oder kleine Typ-Views
- Default-Werte als explizite View mit Art + Wert
- Indizes und Constraints als stabile Signatur-Views

Damit bleibt das JSON/YAML:

- skriptfreundlich
- diffbar
- unabhaengig von zufaelligen Serializer-Details

### 4.4 Validation-Failures sind Compare-native

`OutputFormatter.printValidationResult(...)` ist heute auf
`schema validate` zugeschnitten und nutzt dort auch den Command-Namen
`schema.validate`.

Fuer `schema compare` wird deshalb **keine** Validate-Ausgabe wiederverwendet,
die nach aussen wie ein anderer Command aussieht. Stattdessen gilt:

- beide Schemata werden, wenn parsebar, separat validiert
- Validation-Fehler stoppen den Compare mit Exit `3`
- Validation-Warnungen allein stoppen den Compare **nicht**
- Plain-/JSON-/YAML-Ausgabe bleibt trotzdem im Command-Kontext
  `schema.compare`
- das Ergebnis benennt explizit, welche Seite (`source`, `target`) invalid ist
- Validation-Warnungen parsebarer, aber gueltiger Schemas bleiben sichtbar:
  - Plain: auf `stderr`
  - JSON/YAML: im `validation`-Block des Compare-Dokuments

Wenn bereits das Einlesen einer Seite scheitert, bleibt es bei Exit `7` und
einer Fehlerausgabe auf `stderr`.

### 4.5 Plain Text rendert deterministisch und gruppiert nach Scope

Die menschenlesbare Ausgabe soll nicht nur "irgendwie Text" sein, sondern
deterministisch und snapshot-testbar.

Empfohlene Struktur:

1. Kopf mit `source`, `target` und Status
2. kompakte Summary
3. Details in fachlicher Reihenfolge:
   - Schema-Metadaten
   - Enum-Typen
   - Tabellen
   - Views

Innerhalb von Tabellen:

- Added/Removed Columns
- Changed Columns
- Primary Key
- Indizes
- Constraints

Keine ANSI-Pflicht fuer 0.5.0:

- ASCII-first
- keine Cursor-Magie
- kein neues Farbsystem

### 4.6 `--output` ist byte-identisch zur stdout-Darstellung

Wie in der CLI-Spec definiert:

- ohne `--output`: Ergebnis auf stdout
- mit `--output`: dieselbe Darstellung in Datei
- `stderr` bleibt fuer Fehler reserviert
- fehlende Parent-Verzeichnisse des Output-Pfads werden vor dem Schreiben
  automatisch angelegt

Zusaetzliche Schutzregel fuer Phase C:

- `--output` darf nach Normalisierung nicht auf `--source` oder `--target`
  zeigen
- bei Kollision: Exit `2`

Damit wird verhindert, dass Compare seine Eingaben ueberschreibt.

### 4.7 Keine neue Progress-Semantik fuer Compare

`schema compare` ist in 0.5.0 ein Datei-zu-Datei-MVP ohne langlaufende
Streaming-Operation. Deshalb erzeugt Compare in Phase C:

- keine Progress-Events
- keinen Sonderfall fuer `--no-progress`

Das globale Flag bleibt gueltig, hat hier aber schlicht keinen zusaetzlichen
Effekt.

---

## 5. Betroffene Dateien und Module

### 5.1 Geaenderte Produktionsdateien

| Datei | Aenderung |
|---|---|
| `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt` | `SchemaCompareCommand` in die `schema`-Hierarchie aufnehmen |
| `adapters/driving/cli/build.gradle.kts` | falls Compare-Renderer Jackson-basiert umgesetzt werden: JSON/YAML-Serializer explizit im CLI-Modul deklarieren, statt nur implizit transitive Abhaengigkeiten zu nutzen |
| optional `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt` | nur dann aendern, wenn `printError(...)` minimal wiederverwendet werden soll; kein Aufblasen zum Compare-Monolithen |

### 5.2 Neue Produktionsdateien

| Datei | Zweck |
|---|---|
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt` | Runner, Request-DTO und ggf. kleine application-nahe Compare-Views |
| `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt` | Compare-spezifische Render-Helfer fuer Plain/JSON/YAML und ggf. kleine String-/Signatur-Mapper |

Hinweis:

- Ob `SchemaCompareRequest`/`SchemaCompareDocument` in derselben Datei wie der
  Runner oder in kleinen Nachbar-Dateien liegen, ist ein Implementierungsdetail.
- Verbindlich ist nur die Trennung:
  - Ablauf in `hexagon:application`
  - Rendering im CLI-Adapter

### 5.3 Neue Testdateien

| Datei | Zweck |
|---|---|
| `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt` | Runner-Unit-Tests fuer Ablauf, Exit-Codes und Routing |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpersTest.kt` | Serializer-/Plain-Render-Tests |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt` | CLI-nahe Tests fuer `schema compare` |
| optional neue Dateien unter `adapters/driving/cli/src/test/resources/` | kleine Compare-Fixtures bzw. Golden-Snapshots |

### 5.4 Bereits vorhandene Vorbedingungen

Bereits vorhanden und von Phase C zu nutzen:

- `hexagon/core/.../SchemaComparator.kt`
- `hexagon/core/.../SchemaDiff.kt`
- `adapters/driven/formats/.../YamlSchemaCodec.kt`
- `SchemaGenerateRunner` als Runner-Vorlage
- `CliHelpAndBootstrapTest.kt` fuer Help-/Bootstrap-Abdeckung

---

## 6. CLI-Vertrag

### 6.1 Aufruf

```bash
d-migrate schema compare --source schema-a.yaml --target schema-b.yaml
```

### 6.2 Request-DTO

Empfohlene Richtung:

```kotlin
data class SchemaCompareRequest(
    val source: Path,
    val target: Path,
    val output: Path?,
    val outputFormat: String,
    val quiet: Boolean,
)
```

`verbose`, `noColor` und `noProgress` muessen fuer den MVP nicht in das
Request-DTO gezogen werden, solange Compare diese Flags nicht fachlich nutzt.

### 6.3 Exit-Codes

| Code | Bedeutung |
|---|---|
| `0` | beide Schemata gueltig und ohne Unterschiede |
| `1` | beide Schemata gueltig, Unterschiede gefunden |
| `2` | ungueltige CLI-Kombination, insbesondere Output-Kollision mit Input |
| `3` | mindestens eines der beiden Schemata ist ungueltig |
| `7` | Parse-, Datei- oder Schreibfehler |

### 6.4 Strukturierte Ausgabe

Empfohlene Top-Level-Huelle:

```json
{
  "command": "schema.compare",
  "status": "identical|different|invalid",
  "exit_code": 0,
  "source": "schema-a.yaml",
  "target": "schema-b.yaml",
  "summary": {
    "tables_added": 0,
    "tables_removed": 0,
    "tables_changed": 0,
    "enum_types_added": 0,
    "enum_types_removed": 0,
    "enum_types_changed": 0,
    "views_added": 0,
    "views_removed": 0,
    "views_changed": 0
  },
  "diff": {
    "...": "stable compare projection"
  }
}
```

Fuer Exit `3` wird dieselbe Huelle genutzt, aber mit:

- `status = "invalid"`
- `diff = null` oder leer
- zusaetzlichem Block `validation`, der source/target-seitige Fehler und
  Warnungen ausweist

Fuer Exit `0` und `1` gilt zusaetzlich:

- wenn Source oder Target Validation-Warnungen haben, enthaelt das Compare-
  Dokument ebenfalls einen `validation`-Block
- dieser Block enthaelt in diesem Fall nur Warnungen, keine Fehler
- Warnungen aendern den Exit-Code nicht; massgeblich bleibt `0` oder `1`

### 6.5 Plain-Text-MVP

Empfohlene Regeln:

- Exit `0`:
  - in normalem Plain-Mode eine knappe Erfolgsmeldung mit leerer Summary
  - unter `--quiet` darf dieser reine Identical-Hinweis entfallen
  - Validation-Warnungen gueltiger Schemas gehen auf `stderr`
- Exit `1`:
  - Summary + Detailblock
  - stabile Sortierung
  - keine unnoetigen Leerzeilen oder dekorativen Trennlinien
  - Validation-Warnungen gueltiger Schemas gehen zusaetzlich auf `stderr`
- Exit `3`:
  - klar benennen, welche Seite invalid ist
- Exit `7`:
  - nur Fehlerausgabe auf `stderr`

---

## 7. Implementierung

### 7.1 `SchemaCompareCommand`

Pflichten des Commands:

- neue Option `--source`
- neue Option `--target`
- optionale Option `--output`
- `CliContext` ueber den bestehenden Zwei-Parent-Hop vom Root holen
- `SchemaCompareRequest` bauen
- `SchemaCompareRunner` mit Produktions-Abhaengigkeiten instanziieren
- `ProgramResult(exitCode)` fuer ungleich `0` werfen

`SchemaCommand` registriert danach:

```kotlin
subcommands(
    SchemaValidateCommand(),
    SchemaGenerateCommand(),
    SchemaCompareCommand(),
)
```

### 7.2 `SchemaCompareRunner`

Empfohlene Injektionen:

```kotlin
class SchemaCompareRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val ensureParentDirectories: (Path) -> Unit,
    private val fileWriter: (Path, String) -> Unit,
    private val renderPlain: (SchemaCompareDocument) -> String,
    private val renderJson: (SchemaCompareDocument) -> String,
    private val renderYaml: (SchemaCompareDocument) -> String,
    private val printError: (String, String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
)
```

Empfohlener Ablauf:

1. Output-Kollision mit `source`/`target` nach Normalisierung pruefen
2. `source` lesen
3. `target` lesen
4. beide parsebaren Schemata validieren
5. bei Validation-Fehlern Compare-Dokument mit `status = invalid` bauen
6. bei reinen Validation-Warnungen Compare trotzdem fortsetzen
7. anschliessend `SchemaComparator.compare(left, right)`
8. Summary zaehlen
9. Compare-Dokument rendern
10. vor `--output`-Write fehlende Parent-Verzeichnisse anlegen
11. stdout oder `--output` bedienen
12. Exit `0` oder `1` zurueckgeben

Wichtig:

- Compare soll beide Seiten validieren, wenn beide parsebar sind
- Validate darf nicht auf der ersten invaliden Seite blind abbrechen
- Validation-Warnungen duerfen nicht still verschwinden
- Parse-/I/O-Fehler bleiben dagegen fail-fast mit Exit `7`

### 7.3 Compare-Projektion

Die Compare-Projektion soll die Phase-B-Typen in ein CLI-stabiles Format
uebersetzen.

Mindestens noetig:

- Summary-Zaehler
- Schema-Metadaten-Diff
- Tabellennamen fuer `added`/`removed`
- Tabellen-Detailviews fuer `changed`
- Enum-Typ- und View-Diffs in analoger Form

Fuer geaenderte Tabellen:

- `columns_added`: Namen oder kleine Column-Views
- `columns_removed`: Namen oder kleine Column-Views
- `columns_changed`: explizite `before`/`after`-Werte fuer:
  - `type`
  - `required`
  - `default`
- `primary_key`: geordnete Listen in `before`/`after`
- `indices_*` und `constraints_*`: kanonische Signatur-Views statt roher
  Kotlin-Objekte

### 7.4 Renderer

Die drei Renderer muessen auf **derselben** Compare-Projektion aufbauen.

Verbindliche Regeln:

- Plain, JSON und YAML duplizieren die Business-Logik nicht
- Sortierung und Summary stammen aus derselben Datenbasis
- JSON/YAML werden aus dem Compare-Dokument gerendert, nicht direkt aus
  `SchemaDiff`

Entscheidung (verifiziert gegen Codebasis 2026-04-13):

- Compare-Dokument als einfache DTO-Struktur
- JSON/YAML werden **ohne externe Library** gerendert, analog zum
  bestehenden Muster in `SchemaGenerateHelpers` und `OutputFormatter`:
  handgeschriebenes String-Building mit `escapeJson()`/`escapeYaml()`
- Keine neue Jackson- oder sonstige Serializer-Abhaengigkeit im CLI-Modul
- Begruendung: das CLI-Modul nutzt bereits durchgaengig diesen Ansatz
  (`SchemaGenerateHelpers.formatJsonOutput`, `OutputFormatter.printJson`,
  `OutputFormatter.printYaml`); eine neue Library-Abhaengigkeit nur fuer
  Compare waere inkonsistent

### 7.5 Fehler- und Write-Pfade

Zu pruefende Fehlerpfade:

- `schemaReader(source)` wirft -> Exit `7`
- `schemaReader(target)` wirft -> Exit `7`
- `ensureParentDirectories(output)` wirft -> Exit `7`
- `fileWriter(output, rendered)` wirft -> Exit `7`
- normalisierter `output` kollidiert mit `source` oder `target` -> Exit `2`

Die Fehlerausgabe soll bei diesen Pfaden knapp bleiben:

- Mensch: `printError(...)` auf `stderr`
- keine halbgerenderte Compare-Ausgabe

Verbindlich gemaess CLI-Pfadkonvention:

- `--output` darf auch auf einen Pfad in einem noch nicht vorhandenen
  Verzeichnis zeigen
- der Compare-Pfad erzeugt dieses Parent-Verzeichnis vor dem Schreiben

---

## 8. Tests

### 8.1 Runner-Tests (`SchemaCompareRunnerTest`)

Pflichtfaelle:

| # | Testname | Pruefung |
|---|---|---|
| 1 | `identical schemas return exit 0` | leeres Diff fuehrt zu `0` |
| 2 | `different schemas return exit 1` | nicht-leeres Diff fuehrt zu `1` |
| 3 | `source parse failure returns exit 7` | Reader-Fehler fuer Quelle |
| 4 | `target parse failure returns exit 7` | Reader-Fehler fuer Ziel |
| 5 | `invalid source returns exit 3` | Validation-Fehler auf Source |
| 6 | `invalid target returns exit 3` | Validation-Fehler auf Target |
| 7 | `validation warnings do not suppress compare result` | Warnungen bleiben sichtbar, Exit bleibt `0` oder `1` |
| 8 | `both invalid schemas are reported together` | beide Seiten werden validiert und gemeinsam ausgegeben |
| 9 | `output collision with source returns exit 2` | Schutz vor Ueberschreiben |
| 10 | `creates missing parent directories before writing output` | Parent-Verzeichnis wird angelegt |
| 11 | `file writer failure returns exit 7` | Schreibfehler sauber gemappt |
| 12 | `json rendering is selected by output format` | Routing JSON |
| 13 | `yaml rendering is selected by output format` | Routing YAML |

### 8.2 Helper-/Renderer-Tests (`SchemaCompareHelpersTest`)

Pflichtfaelle:

- Summary-Zaehler stimmen fuer ein gegebenes Diff
- Plain-Text-Reihenfolge ist deterministisch
- JSON enthaelt stabilen Command-/Status-/Summary-Block
- YAML enthaelt dieselben fachlichen Felder wie JSON
- `validation`-Block wird bei Warnungen ohne Fehler fuer Exit `0`/`1`
  korrekt mitgerendert
- Sonderzeichen in Namen, Defaults und SQL-Texten werden korrekt escaped
- kanonische Signaturen fuer Indizes und Constraints sind stabil

Empfohlen:

- mindestens ein Golden-/Snapshot-Test fuer Plain-Text mit echtem Diff
- expliziter Test fuer identischen Compare (`status = identical`)
- expliziter Test fuer Validation-Envelope (`status = invalid`)

### 8.3 CLI-Tests (`CliSchemaCompareTest`)

Pflichtfaelle:

- `schema compare --help` ist erreichbar
- gueltig + identisch -> kein `ProgramResult`
- gueltig + unterschiedlich -> `ProgramResult(1)`
- broken YAML -> `ProgramResult(7)`
- invalid schema -> `ProgramResult(3)`
- Validation-Warnungen bei gueltigen Schemas bleiben sichtbar und aendern den
  Exit-Code nicht
- `--output` schreibt Datei
- `--output` in ein noch nicht vorhandenes Parent-Verzeichnis schreibt Datei
- `--output-format json`
- `--output-format yaml`

Zusaetzlich anzupassen:

- `CliHelpAndBootstrapTest.kt` muss `schema compare --help` in der
  Kommandohierarchie mit abdecken

### 8.4 Fixture-Basis

Bevorzugte Quellen fuer Tests:

- kleine CLI-eigene YAML-Resources fuer Exit-Code-Faelle
- vorhandene Compare-Fixtures aus
  `adapters/driven/formats/src/test/resources/fixtures/schemas/` fuer
  realistischere Snapshot-Faelle

---

## 9. Coverage-Ziel

Fuer Phase C gelten die bestehenden Modul-Gates weiter:

- `hexagon:application` mindestens auf bestehendem Niveau halten
- `adapters:driving:cli` mindestens auf bestehendem Niveau halten

Pragmatische Zielsetzung fuer den Compare-Slice:

- Runner: alle Exit-Code- und Routing-Pfade direkt unit-testen
- Renderer-Helfer: deterministic coverage fuer Summary und strukturierte
  Ausgabe
- CLI: mindestens ein echter Dateiausgabe-Pfad, nicht nur Fakes

Die Phase rechtfertigt keine neue Coverage-Ausnahme.

---

## 10. Build und Verifikation

Gezielter Modul-Testlauf im `build`-Stage:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test --rerun-tasks" \
  -t d-migrate:compare-cli-build .
```

Lauffaehiges Runtime-Image fuer die Smoke-Tests:

```bash
docker build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:compare-cli .
```

Smoke-Test identisch:

```bash
docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:compare-cli \
  schema compare --source /work/minimal.yaml --target /work/minimal.yaml
```

Smoke-Test unterschiedlich:

```bash
docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:compare-cli \
  schema compare --source /work/minimal.yaml --target /work/e-commerce.yaml
```

Dateiausgabe-Probe:

```bash
docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  -v "$(pwd)/tmp:/out" \
  d-migrate:compare-cli \
  --output-format json \
  schema compare \
  --source /work/minimal.yaml \
  --target /work/e-commerce.yaml \
  --output /out/compare.json
```

---

## 11. Abnahmekriterien

- `d-migrate schema compare` ist unter `schema` registriert und in `--help`
  sichtbar.
- Zwei gueltige identische Schema-Dateien liefern stabil Exit `0`.
- Zwei gueltige unterschiedliche Schema-Dateien liefern stabil Exit `1`.
- Invalides Schema liefert Exit `3`, ohne den Command-Namen oder das
  Ausgabeformat zu "schema validate" umzubiegen.
- Validation-Warnungen parsebarer, gueltiger Schemas bleiben sichtbar:
  - Plain auf `stderr`
  - JSON/YAML im `validation`-Block
- Validation-Warnungen allein aendern den Exit-Code nicht.
- Parse-/Datei-/Schreibfehler liefern Exit `7`.
- `--output` schreibt dieselbe Darstellung, die sonst auf stdout erschienen
  waere.
- fehlende Parent-Verzeichnisse fuer `--output` werden automatisch angelegt.
- JSON und YAML nutzen ein stabiles Compare-spezifisches Feldschema und
  serialisieren nicht blind rohe Core-Diff-Typen.
- Plain-Text-Ausgabe ist deterministisch und snapshot-testbar.
- Der Compare-Slice besteht den gezielten Build-/Testlauf aus Abschnitt 10.
