# Phase C: Schema-I/O, Reverse-Reports und gemeinsame JDBC-Metadatenbasis

## Context

Phase C schafft die Format-, Report- und Metadaten-Infrastruktur, auf der
die spaeteren Reverse-Phasen D-H aufbauen. Ohne produktiven Schema-Writer
bleibt Reverse ein Read-only-Prototyp; ohne getrennten Report droht ein
verunreinigtes Schema-Artefakt; ohne gemeinsame JDBC-Basis werden Phase-D-
Dialektmodule denselben SQL-Text mehrfach neu erfinden.

Docker zum Bauen/Testen:
```bash
# Core-Build (formats + driver-common + CLI)
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test :adapters:driven:driver-common:test :adapters:driving:cli:test :adapters:driven:driver-sqlite:test" \
  -t d-migrate:phase-c-core .

# Integrationstests (PostgreSQL, MySQL via Testcontainers)
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
./scripts/test-integration-docker.sh :adapters:driven:driver-mysql:test

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

---

## Implementierungsschritte

### Schritt 1: YAML-Schema-Writer produktiv machen (C.2)

**Datei editieren:** `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`

`write(output, schema)` implementieren — aktuell `TODO`. Deterministisches
YAML mit fester Feldreihenfolge via Jackson `YAMLGenerator` + `ObjectNode`-
Aufbau:

- Schema-Metafelder: `schema_format`, `name`, `version`, `description`,
  `encoding`, `locale` (nur wenn nicht-default)
- `custom_types` → sortiert nach Name, `kind` als erstes Feld
- `tables` → sortiert nach Name; pro Tabelle: `description`, `columns`
  (sortiert), `primary_key`, `indices`, `constraints`, `partitioning`,
  `metadata` (nur wenn nicht-null)
- `procedures`, `functions` → sortiert nach Key
- `views`, `triggers`, `sequences` → sortiert nach Key
- Kanonische Objekt-Keys werden als Map-Keys uebergeben (keine
  Normalisierung durch den Writer)
- Keine Notes oder `skipped_objects` im Schema-Dokument

**Neue Testdatei:** `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaWriterTest.kt`

Tests:
- Round-Trip: parse Fixture → write → re-parse → assert equal
  (mindestens: minimal.yaml, e-commerce.yaml, full-featured.yaml,
  canonical-keys.yaml, table-metadata.yaml)
- Deterministisch: zweimaliges Write ergibt byte-identisches Ergebnis
- Leere optionale Felder werden nicht geschrieben
- `metadata` erscheint nur bei Tabellen, die es haben

---

### Schritt 2: JSON-Schema-Codec einfuehren (C.3)

**Neue Datei:** `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/json/JsonSchemaCodec.kt`

Implementiert `SchemaCodec` mit Jackson `JsonFactory` (nicht `YAMLFactory`):
- `read(input)` — parst JSON in `SchemaDefinition` (gleiche Logik wie
  YAML-Parser, aber ueber Jackson JSON-Backend)
- `write(output, schema)` — serialisiert als Pretty-Printed JSON mit
  fester Feldreihenfolge

Designentscheidung: Die Parse-Logik zwischen YAML und JSON ist identisch
(beide arbeiten auf Jacksons `JsonNode`-Tree). Statt Code zu duplizieren,
wird eine gemeinsame `SchemaNodeParser`-Klasse extrahiert, die auf
`JsonNode` arbeitet. `YamlSchemaCodec` und `JsonSchemaCodec` delegieren
jeweils an denselben Parser, aber mit ihrem eigenen `ObjectMapper`.

Gleiche Loesung fuer Writer: gemeinsamer `SchemaNodeBuilder` erzeugt
`ObjectNode`-Baum, der dann von YAML- oder JSON-Mapper serialisiert wird.

**Neue Dateien:**
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
  — extrahiert aus `YamlSchemaCodec`, arbeitet auf `JsonNode`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`
  — baut deterministischen `ObjectNode`-Baum aus `SchemaDefinition`

**Datei editieren:** `YamlSchemaCodec.kt` — Parse-Logik an
`SchemaNodeParser` delegieren statt inline

**Neue Testdatei:** `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/json/JsonSchemaCodecTest.kt`

Tests:
- JSON-Round-Trip fuer alle Schema-Fixtures
- JSON-Einlesen einer manuell erstellten `.json`-Fixture
- Pretty-Printing stabil

---

### Schritt 3: Format-Resolver einfuehren (C.1)

**Neue Datei:** `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaFileResolver.kt`

```kotlin
object SchemaFileResolver {
    fun codecForPath(path: Path): SchemaCodec
    fun codecForFormat(format: String): SchemaCodec
    fun validateOutputPath(path: Path, format: String?): Unit
    fun detectFormat(path: Path): String  // "yaml" | "json"
}
```

Regeln:
- `.yaml`, `.yml` → `YamlSchemaCodec`
- `.json` → `JsonSchemaCodec`
- Unbekannte Endung → `IllegalArgumentException` mit klarer Meldung
- `validateOutputPath` prueft, ob Endung zum Format passt
  (z.B. `--format json` mit `output.yaml` → Fehler)

**Neue Testdatei:** `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/SchemaFileResolverTest.kt`

Tests:
- `.yaml` → YAML, `.yml` → YAML, `.json` → JSON
- `.txt`, `.xml`, endungslos → Fehler
- Endung/Format-Mismatch → Fehler

---

### Schritt 4: File-based CLI-Kommandos auf Format-Resolver heben (C.4)

**Datei editieren:** `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`

Alle drei Kommandos (`validate`, `generate`, `compare`) aendern:
- `YamlSchemaCodec().read(path)` → `SchemaFileResolver.codecForPath(path).read(path)`
- Help-Texte: `"(YAML)"` → `"(YAML/JSON)"`

**Datei editieren:** `SchemaGenerateRunner` und `SchemaCompareRunner`
- Die `schemaReader`-Lambda in den Runners bleibt — nur die Verdrahtung
  in SchemaCommands.kt aendert sich

**Tests:**
- Bestehende CLI-Tests bleiben unveraendert (nutzen YAML-Fixtures)
- Neuer Test: `schema validate --source valid-schema.json` funktioniert
- Neuer Test: `schema compare --source a.yaml --target b.json` (gemischt)

---

### Schritt 5: Reverse-Report-Writer einfuehren (C.5)

**Neue Dateien in `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/`:**

`ReverseReportWriter.kt`:
```kotlin
class ReverseReportWriter {
    fun write(output: Path, input: SchemaReadReportInput)
    fun render(input: SchemaReadReportInput): String
}
```

`SchemaReadReportInput.kt` (in `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/` —
Port-Vertrag neben `SchemaReadResult`):
```kotlin
data class SchemaReadReportInput(
    val source: ReverseSourceRef,
    val result: SchemaReadResult,
)

data class ReverseSourceRef(
    val kind: ReverseSourceKind,
    val value: String,
)

enum class ReverseSourceKind { ALIAS, URL }
```

Report-Format (YAML-Sidecar):
```yaml
source:
  kind: url
  value: "postgresql://admin:***@localhost/mydb"
schema:
  name: "..."
  version: "..."
summary:
  notes: 5
  warnings: 3
  action_required: 1
  skipped_objects: 2
notes:
  - severity: warning
    code: R001
    object: "spatial_ref_sys"
    message: "PostGIS system table skipped"
skipped_objects:
  - type: TABLE
    name: "sqlite_stat1"
    reason: "System table"
```

Credential-Scrubbing:
- `ReverseSourceKind.ALIAS` → Wert unveraendert
- `ReverseSourceKind.URL` → `LogScrubber.maskUrl(value)` vor
  Serialisierung

Sidecar-Pfad: `SchemaGenerateHelpers.sidecarPath()` wird nach
`adapters/driven/formats` extrahiert als gemeinsamer Helper
(`SidecarPath.kt`), damit Generate und Reverse denselben Pfad verwenden.

**Neue Testdatei:** `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/ReverseReportWriterTest.kt`

Tests:
- Report mit Alias-Quelle: Wert erscheint unmasked
- Report mit URL-Quelle: Passwort als `***` maskiert
- Report enthaelt Summary-Zaehler korrekt
- Report enthaelt Notes und skipped_objects
- Report ist valides YAML (re-parsebar)

---

### Schritt 6: Gemeinsame JDBC-Metadaten-Helpers in driver-common (C.6, C.7)

**Neue Dateien in `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/`:**

`JdbcMetadataSession.kt`:
```kotlin
class JdbcMetadataSession(private val conn: Connection) {
    // Arbeitet auf bereits geliehener Connection — kein Pool-Zugriff!
    fun queryList(sql: String, vararg params: Any?): List<Map<String, Any?>>
    fun querySingle(sql: String, vararg params: Any?): Map<String, Any?>?
}
```

Typisierte Projektionen:

`TableRef.kt`:
```kotlin
data class TableRef(
    val name: String,
    val schema: String? = null,
    val type: String = "BASE TABLE",
)
```

`ColumnProjection.kt`:
```kotlin
data class ColumnProjection(
    val name: String,
    val dataType: String,
    val isNullable: Boolean,
    val columnDefault: String?,
    val ordinalPosition: Int,
    val characterMaxLength: Int? = null,
    val numericPrecision: Int? = null,
    val numericScale: Int? = null,
    val isAutoIncrement: Boolean = false,
)
```

`PrimaryKeyProjection.kt`:
```kotlin
data class PrimaryKeyProjection(
    val columns: List<String>,
)
```

`ForeignKeyProjection.kt`:
```kotlin
data class ForeignKeyProjection(
    val name: String,
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onDelete: String? = null,
    val onUpdate: String? = null,
)
```

`IndexProjection.kt`:
```kotlin
data class IndexProjection(
    val name: String,
    val columns: List<String>,
    val isUnique: Boolean,
    val type: String? = null,
)
```

`ConstraintProjection.kt`:
```kotlin
data class ConstraintProjection(
    val name: String,
    val type: String,       // "CHECK", "UNIQUE", "EXCLUDE"
    val columns: List<String>? = null,
    val expression: String? = null,
)
```

Diese Projektionen sind reine Datentraeger — das Mapping in das neutrale
Modell geschieht spaeter in Phase D. Der Shared-Layer stellt keine
Dialekt-spezifischen Queries bereit; die Queries leben weiterhin in den
Dialektmodulen. Die Projektionen sind die gemeinsame Sprache zwischen
Query-Ergebnis und Model-Mapping.

**Neue Testdatei:** `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/metadata/JdbcMetadataSessionTest.kt`

Tests (mit SQLite in-memory):
- `queryList` liefert ResultSet als List<Map>
- `querySingle` liefert erste Zeile oder null
- Prepared-Statement-Parameter werden korrekt gebunden
- Connection wird NICHT geschlossen durch den Helper

---

### Schritt 7: TableLister auf gemeinsame Tabellenprojektion ausrichten (C.8)

**Neue Dateien pro Dialekt:**

`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresMetadataQueries.kt`:
```kotlin
object PostgresMetadataQueries {
    fun listTableRefs(session: JdbcMetadataSession, schema: String): List<TableRef>
}
```

`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`:
```kotlin
object MysqlMetadataQueries {
    fun listTableRefs(session: JdbcMetadataSession, database: String): List<TableRef>
}
```

`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteMetadataQueries.kt`:
```kotlin
object SqliteMetadataQueries {
    fun listTableRefs(session: JdbcMetadataSession): List<TableRef>
}
```

**Dateien editieren:** Bestehende TableLister-Implementierungen refactoren:
- `PostgresTableLister.listTables()` → delegiert an
  `PostgresMetadataQueries.listTableRefs()` und extrahiert `.name`
- Analog fuer MySQL und SQLite
- Port-Signatur und fachliches Verhalten bleiben exakt gleich
- Connection-Ownership bleibt im TableLister (borrow + use)

**Tests:**
- Bestehende TableLister-Tests bleiben unveraendert — gruener Test =
  Beweis, dass das Verhalten identisch bleibt
- Integrationstests (PostgreSQL, MySQL) ueber
  `test-integration-docker.sh`

---

## Betroffene Dateien (Zusammenfassung)

### Neue Dateien (~15)

| Datei | Modul |
|-------|-------|
| `formats/SchemaNodeParser.kt` | formats |
| `formats/SchemaNodeBuilder.kt` | formats |
| `formats/json/JsonSchemaCodec.kt` | formats |
| `formats/SchemaFileResolver.kt` | formats |
| `formats/report/ReverseReportWriter.kt` | formats |
| `ports/driver/SchemaReadReportInput.kt` | ports |
| `formats/SidecarPath.kt` | formats |
| `driver-common/metadata/JdbcMetadataSession.kt` | driver-common |
| `driver-common/metadata/TableRef.kt` | driver-common |
| `driver-common/metadata/ColumnProjection.kt` | driver-common |
| `driver-common/metadata/PrimaryKeyProjection.kt` | driver-common |
| `driver-common/metadata/ForeignKeyProjection.kt` | driver-common |
| `driver-common/metadata/IndexProjection.kt` | driver-common |
| `driver-common/metadata/ConstraintProjection.kt` | driver-common |
| `driver-{pg,mysql,sqlite}/MetadataQueries.kt` | driver-* |

### Zu aendernde Dateien (~10)

| Datei | Aenderung |
|-------|-----------|
| `formats/yaml/YamlSchemaCodec.kt` | write() implementieren, Parse-Logik nach SchemaNodeParser extrahieren |
| `cli/commands/SchemaCommands.kt` | YamlSchemaCodec → SchemaFileResolver, Help-Texte |
| `driver-postgresql/PostgresTableLister.kt` | Delegation an MetadataQueries |
| `driver-mysql/MysqlTableLister.kt` | Delegation an MetadataQueries |
| `driver-sqlite/SqliteTableLister.kt` | Delegation an MetadataQueries |
| `cli/commands/SchemaGenerateHelpers.kt` | sidecarPath nach formats/SidecarPath extrahieren |
| Tests: YamlSchemaCodecTest, CLI-Tests, TableLister-Tests | |

---

## Abhaengigkeiten zwischen Schritten

```
Schritt 1 (YAML Writer)    ─┐
Schritt 5 (Report Writer)   ├── unabhaengig, parallel moeglich
Schritt 6 (JDBC Helpers)   ─┘
Schritt 2 (JSON Codec)     ── abhaengig von Schritt 1 (gemeinsamer NodeParser/Builder)
Schritt 3 (Resolver)       ── abhaengig von Schritt 2
Schritt 4 (CLI Migration)  ── abhaengig von Schritt 3
Schritt 7 (TableLister)    ── abhaengig von Schritt 6
```

Schritte 1, 5 und 6 sind parallel ausfuehrbar. Dann 2, dann 3+7 parallel,
dann 4.

---

## Verifikation

```bash
# Primaerer Test-Lauf (ohne DB)
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test :adapters:driven:driver-common:test :adapters:driven:driver-sqlite:test :adapters:driving:cli:test" \
  -t d-migrate:phase-c .

# Integrationstests
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
./scripts/test-integration-docker.sh :adapters:driven:driver-mysql:test

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

Gezielt zu pruefen:
- YAML-Round-Trip fuer alle bestehenden Fixtures
- JSON-Round-Trip fuer dieselben Fixtures
- `schema validate --source schema.json` funktioniert
- `schema compare --source a.yaml --target b.json` (gemischt)
- Reverse-Report mit URL-Quelle maskiert Passwort
- TableLister-Verhalten unveraendert nach Refactoring
