# Phase D: Dialektimplementierung fuer SchemaReader

## Context

Phase D setzt den Reverse-Vertrag (Phase B) und die Format-/Metadaten-
Infrastruktur (Phase C) in den drei Built-in-Dialekten konkret um.
Ergebnis sind belastbare `SchemaReader`-Implementierungen fuer PostgreSQL,
MySQL und SQLite. Kein CLI-Kommando, kein Runner, kein Compare-Pfad —
nur die Driver-Ebene.

Docker zum Bauen/Testen:
```bash
# SQLite (kein Testcontainer noetig)
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-sqlite:test" \
  -t d-migrate:phase-d-sqlite .

# PostgreSQL/MySQL Integrationstests
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
./scripts/test-integration-docker.sh :adapters:driven:driver-mysql:test

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

---

## Implementierungsschritte

### Schritt 1: Reverse-Scope-Codec in core (D.1 Voraussetzung)

**Neue Datei:** `hexagon/core/src/main/kotlin/dev/dmigrate/core/identity/ReverseScopeCodec.kt`

Kanonische `SchemaDefinition.name`/`version` fuer reverse-generierte
Schemas:

```kotlin
object ReverseScopeCodec {
    const val PREFIX = "__dmigrate_reverse__:"
    const val REVERSE_VERSION = "0.0.0-reverse"

    fun postgresName(database: String, schema: String): String
    fun mysqlName(database: String): String
    fun sqliteName(schema: String): String
    fun isReverseGenerated(name: String, version: String): Boolean
    fun parseScope(name: String): Map<String, String>  // dialect, database, schema
}
```

- Percent-Encoding fuer Strukturtrenner (`;`, `=`, `:`, `%`) in
  Komponentenwerten via RFC-3986
- Round-Trip-sicher: encode → parse → encode ergibt denselben String

**Tests:** `hexagon/core/src/test/kotlin/dev/dmigrate/core/identity/ReverseScopeCodecTest.kt`
- Alle drei Dialekte
- Sonderzeichen in DB-/Schema-Namen
- `isReverseGenerated` erkennt gueltiges Marker-Set
- Ungueltiger Prefix ohne vollstaendiges Set wird nicht akzeptiert

**Spec-Nachzug:** `docs/neutral-model-spec.md` — `__dmigrate_reverse__:`
als reservierten Prefix dokumentieren.

---

### Schritt 2: SQLite-SchemaReader implementieren (D.4)

SQLite zuerst, weil kein Testcontainer noetig ist und der schnellste
Feedback-Loop entsteht.

**Neue Datei:** `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`

Metadatenquellen:
- `sqlite_master` — Tabellen, Views, Trigger, CREATE-Statements
- `PRAGMA table_info(t)` — Spalten, Typen, Nullability, Defaults, PK
- `PRAGMA foreign_key_list(t)` — Foreign Keys
- `PRAGMA index_list(t)` + `PRAGMA index_info(idx)` — Indizes
- `sqlite_master.sql` — fuer `WITHOUT ROWID`, `AUTOINCREMENT`, CHECK-
  Constraints (nur via CREATE TABLE-Statement parsebar)

Kernlogik:
- Connection-Ownership: `pool.borrow().use { conn → session → read }`
- `SchemaDefinition.name` = `ReverseScopeCodec.sqliteName("main")`
- `SchemaDefinition.version` = `ReverseScopeCodec.REVERSE_VERSION`

Typ-Rueckfuehrung (metadata-first, nicht TypeMapper-Inversion):
- `INTEGER` → `NeutralType.Integer` (oder `Identifier` wenn PK+AUTOINCREMENT)
- `TEXT` → `NeutralType.Text()`
- `REAL` → `NeutralType.Float(DOUBLE)`
- `BLOB` → `NeutralType.Binary`
- `VARCHAR(n)` → `NeutralType.Text(n)` (maxLength extrahieren)
- `BOOLEAN`/`TINYINT(1)` → `NeutralType.BooleanType`
- Unbekannte Affinitaet → `NeutralType.Text()` + Reverse-Note

Besonderheiten:
- `WITHOUT ROWID` — aus `sqlite_master.sql` erkennen → `TableMetadata`
- `AUTOINCREMENT` — nur wenn `sqlite_master.sql` `AUTOINCREMENT` enthaelt
  UND Spalte `INTEGER PRIMARY KEY` ist; `INTEGER PRIMARY KEY` allein
  ist KEIN AUTOINCREMENT
- Virtual Tables → `SkippedObject` mit Code `S100`
- PK-implizites `required`/`unique` wird NICHT redundant auf
  `ColumnDefinition` dupliziert
- PK-/UNIQUE-Backing-Indizes: SQLite erstellt automatische Indizes
  (`sqlite_autoindex_*`), diese werden unterdrueckt
- Views nur bei `includeViews=true`, Trigger nur bei `includeTriggers=true`
- SQLite hat keine Procedures/Functions — Include-Flags ignoriert

**Datei editieren:** `SqliteDriver.kt` — `schemaReader()` von
`throw UnsupportedOperationException` auf `SqliteSchemaReader()` aendern

**Datei editieren:** `SqliteMetadataQueries.kt` — um Spalten-, FK-, Index-
und View-/Trigger-Queries erweitern

**Neue Testdatei:** `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReaderTest.kt`

Tests (alle gegen in-memory oder temp-file SQLite):
- Basistabelle mit Spalten, PK, FK, Index
- Kein PK-redundantes `required`/`unique` auf Spaltenebene
- Einspaltiges UNIQUE auf `ColumnDefinition.unique` gehoben
- Einspaltiger FK auf `ColumnDefinition.references` gehoben
- Mehrspaltiger UNIQUE/FK bleibt auf Constraint-Ebene
- `NOT NULL` korrekt auf `required` abgebildet (aber nicht PK-implizit)
- `WITHOUT ROWID` erkannt und in `TableMetadata`
- `AUTOINCREMENT` nur bei echtem AUTOINCREMENT
- Virtual Table als `SkippedObject`
- `sqlite_autoindex_*` unterdrueckt
- Views und Triggers unter Include-Flags
- Kanonischer `name` und `version`
- Connection nach Read freigegeben

---

### Schritt 3: PostgreSQL-SchemaReader implementieren (D.2)

**Neue Datei:** `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaReader.kt`

Metadatenquellen:
- `information_schema.columns` — Spalten, Typen, Nullability, Defaults
- `information_schema.table_constraints` + `key_column_usage` +
  `constraint_column_usage` — PK, FK, UNIQUE
- `information_schema.check_constraints` — CHECK
- `pg_catalog.pg_indexes` oder `pg_index` — Indizes mit Typ
- `pg_index.indisprimary` / `pg_constraint` — Backing-Index-Erkennung
- `pg_catalog.pg_sequence` — Sequenzen
- `pg_catalog.pg_type` + `pg_enum` — ENUM Custom Types
- `pg_catalog.pg_type` (typtype='d') — DOMAINs
- `pg_catalog.pg_type` (typtype='c') — COMPOSITEs
- `information_schema.views` — Views
- `information_schema.routines` + `parameters` — Functions/Procedures
- `information_schema.triggers` — Triggers

Kernlogik:
- `SchemaDefinition.name` = `ReverseScopeCodec.postgresName(db, schema)`
- `currentSchema(conn)` fuer Schema-Filter
- Routinen → `ObjectKeyCodec.routineKey()` als Map-Key
- Trigger → `ObjectKeyCodec.triggerKey()` als Map-Key

Typ-Rueckfuehrung:
- `integer`/`int4` → `Integer`
- `bigint`/`int8` → `BigInteger`
- `smallint`/`int2` → `SmallInt`
- `serial` → `Identifier(autoIncrement=true)` (Basistyp integer)
- `bigserial` → `BigInteger` + `autoIncrement`-Erkennung via
  `pg_get_serial_sequence()` — bleibt als `BigInteger` im Modell, damit
  Generate spaeter nicht auf `SERIAL` (= integer) kollabiert; die
  Auto-Increment-Eigenschaft wird ueber eine zugehoerige Sequence und
  den Default `nextval(...)` transportiert, nicht ueber
  `NeutralType.Identifier`
- `GENERATED ALWAYS AS IDENTITY` / `GENERATED BY DEFAULT AS IDENTITY` —
  wird erkannt via `is_identity` in `information_schema.columns`;
  abgebildet auf den Basistyp (`Integer`/`BigInteger`) plus die
  zugehoerige implizite Sequence; eine Reverse-Note dokumentiert den
  Identity-Typ, damit spaeterer Generate den passenden DDL-Pfad waehlt
- `varchar(n)` → `Text(n)`
- `text` → `Text()`
- `boolean` → `BooleanType`
- `numeric(p,s)` → `Decimal(p,s)`
- `real` → `Float(SINGLE)`, `double precision` → `Float(DOUBLE)`
- `timestamp`/`timestamptz` → `DateTime(timezone=...)`
- `uuid` → `Uuid`
- `jsonb`/`json` → `Json`
- `xml` → `Xml`
- `bytea` → `Binary`
- `_type` (Array-Prefix) → `Array(elementType)`
- `geometry(...)` → `Geometry(type, srid)` (PostGIS)
- User-defined ENUM → `Enum(refType=typeName)`
- Unbekannt → `Text()` + Note

Besonderheiten:
- PK-Backing-Indizes: PostgreSQL kann via `pg_index.indisprimary` sicher
  identifizieren → unterdruecken
- UNIQUE-Constraint-Backing-Indizes: via `pg_constraint.conindid` sicher
  identifizieren → unterdruecken (aber eigenstaendige Unique-Indizes
  bleiben)
- Extensions → Notes (nicht als Modellobjekte)
- Partitionierung → `PartitionConfig` oder Note je nach Informationslage

**Datei editieren:** `PostgresDriver.kt` — `schemaReader()` produktiv
**Datei editieren:** `PostgresMetadataQueries.kt` — erweitern

**Neue Testdatei:** `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaReaderIntegrationTest.kt`

Tests (gegen Testcontainers PostgreSQL):
- Basistabelle mit allen Spaltentypen
- serial → Identifier, bigserial → BigInteger (kein Typ-Kollaps)
- GENERATED ALWAYS/BY DEFAULT AS IDENTITY korrekt erkannt
- Kein PK-redundantes `required`/`unique`
- Einspaltiges UNIQUE auf `ColumnDefinition.unique` gehoben
- Einspaltiger FK auf `ColumnDefinition.references` gehoben
- Mehrspaltiger UNIQUE/FK bleibt auf Constraint-Ebene
- Backing-Index-Unterdrueckung
- Sequenzen
- ENUM/DOMAIN/COMPOSITE Custom Types
- Views, Functions, Procedures, Triggers mit kanonischen Keys
- Include-Flags
- Geometry/PostGIS (wenn Extension verfuegbar, sonst Note)
- Reverse-Scope-Name korrekt

---

### Schritt 4: MySQL-SchemaReader implementieren (D.3)

**Neue Datei:** `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`

Metadatenquellen:
- `information_schema.columns` — Spalten, Typen, Defaults, Extra
- `information_schema.table_constraints` + `key_column_usage` +
  `referential_constraints` — PK, FK, UNIQUE
- `information_schema.statistics` — Indizes
- `information_schema.tables` — Engine (`TABLE_COMMENT`, `ENGINE`)
- `information_schema.views` — Views
- `information_schema.routines` + `parameters` — Functions/Procedures
- `information_schema.triggers` — Triggers

Kernlogik:
- `SchemaDefinition.name` = `ReverseScopeCodec.mysqlName(database)`
- `currentDatabase(conn)` + `lowerCaseTableNames(conn)` fuer Lookups

Typ-Rueckfuehrung:
- `int` → `Integer`, `bigint` → `BigInteger`, `smallint` → `SmallInt`
- `varchar(n)` → `Text(n)`, `text` → `Text()`
- `tinyint(1)` → `BooleanType`
- `decimal(p,s)` → `Decimal(p,s)`
- `json` → `Json`
- `char(36)` + UUID-Kontext → `Uuid` (oder `Char(36)` + Note)
- `blob` → `Binary`
- `enum('a','b')` → `Enum(values=[a,b])`
- `set('a','b')` → `Text()` + ACTION_REQUIRED-Note
- Spatial → `Geometry(type, srid)`
- `AUTO_INCREMENT` in `extra` — wird auf den Basistyp aus
  `DATA_TYPE`/`COLUMN_TYPE` abgebildet (z.B. `int` → `Integer`,
  `bigint` → `BigInteger`); die Auto-Increment-Eigenschaft wird ueber
  `NeutralType.Identifier(autoIncrement=true)` nur dann verwendet, wenn
  der Basistyp `int`/`integer` ist; fuer `bigint AUTO_INCREMENT` bleibt
  der Typ `BigInteger` und die AI-Eigenschaft wird als Reverse-Note
  transportiert, damit Generate spaeter nicht auf `INT AUTO_INCREMENT`
  kollabiert
- Tabellen-Engine → `TableMetadata(engine=...)`

**Datei editieren:** `MysqlDriver.kt` — `schemaReader()` produktiv
**Datei editieren:** `MysqlMetadataQueries.kt` — erweitern

**Neue Testdatei:** `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderIntegrationTest.kt`

Tests (gegen Testcontainers MySQL):
- Basistabelle, Engine, AUTO_INCREMENT (int und bigint)
- Kein PK-redundantes `required`/`unique`
- Einspaltiges UNIQUE auf `ColumnDefinition.unique` gehoben
- Einspaltiger FK auf `ColumnDefinition.references` gehoben
- Mehrspaltiger UNIQUE/FK bleibt auf Constraint-Ebene
- `SET`-Typ als Note
- Views, Functions, Procedures, Triggers
- Include-Flags
- `lower_case_table_names` — Lookup mit normalisiertem Identifier
- Reverse-Scope-Name korrekt

---

### Schritt 5: Driver-Exposure-Tests + Kover nachziehen (D.1, D.5)

**Dateien editieren:**
- `PostgresDriverExposureTest.kt` — `schemaReader()` liefert
  `PostgresSchemaReader` (nicht mehr `UnsupportedOperationException`)
- `MysqlDriverExposureTest.kt` — analog
- Neuer `SqliteDriverExposureTest.kt` — Komplettset wie PG/MySQL

**Kover-Excludes:** SchemaReader-Klassen pro Dialekt in die
Nicht-Integration-Excludes aufnehmen (wie DataReader, TableLister)

---

### Schritt 6: Spec-Nachzug (D.1)

**Datei editieren:** `docs/neutral-model-spec.md`
- `__dmigrate_reverse__:` als reservierten Prefix dokumentieren
- Reverse-generierte `name`/`version` als technische Provenienz
  beschreiben
- Klare Aussage: handgeschriebene Schemas duerfen diesen Prefix nicht
  verwenden

---

## Betroffene Dateien (Zusammenfassung)

### Neue Dateien (~8)

| Datei | Modul |
|-------|-------|
| `core/identity/ReverseScopeCodec.kt` | core |
| `core/identity/ReverseScopeCodecTest.kt` | core (test) |
| `driver-sqlite/SqliteSchemaReader.kt` | driver-sqlite |
| `driver-sqlite/SqliteSchemaReaderTest.kt` | driver-sqlite (test) |
| `driver-sqlite/SqliteDriverExposureTest.kt` | driver-sqlite (test) |
| `driver-postgresql/PostgresSchemaReader.kt` | driver-postgresql |
| `driver-postgresql/PostgresSchemaReaderIntegrationTest.kt` | driver-postgresql (test) |
| `driver-mysql/MysqlSchemaReader.kt` | driver-mysql |
| `driver-mysql/MysqlSchemaReaderIntegrationTest.kt` | driver-mysql (test) |

### Zu aendernde Dateien (~10)

| Datei | Aenderung |
|-------|-----------|
| `SqliteDriver.kt` | `schemaReader()` produktiv |
| `SqliteMetadataQueries.kt` | Spalten/FK/Index/View/Trigger-Queries |
| `PostgresDriver.kt` | `schemaReader()` produktiv |
| `PostgresMetadataQueries.kt` | Spalten/FK/Index/Sequence/Custom-Type/View/Routine/Trigger-Queries |
| `PostgresDriverExposureTest.kt` | schemaReader() liefert PostgresSchemaReader |
| `MysqlDriver.kt` | `schemaReader()` produktiv |
| `MysqlMetadataQueries.kt` | Spalten/FK/Index/Engine/View/Routine/Trigger-Queries |
| `MysqlDriverExposureTest.kt` | schemaReader() liefert MysqlSchemaReader |
| `docs/neutral-model-spec.md` | `__dmigrate_reverse__:` Prefix |
| `build.gradle.kts` (3x) | Kover-Excludes fuer SchemaReader |

---

## Abhaengigkeiten zwischen Schritten

```
Schritt 1 (ReverseScopeCodec) ── Voraussetzung fuer alle Reader
Schritt 2 (SQLite Reader)     ── abhaengig von Schritt 1
Schritt 3 (PostgreSQL Reader) ── abhaengig von Schritt 1 (parallel zu 2)
Schritt 4 (MySQL Reader)      ── abhaengig von Schritt 1 (parallel zu 2+3)
Schritt 5 (Exposure + Kover)  ── abhaengig von Schritt 2+3+4
Schritt 6 (Spec-Nachzug)      ── parallel zu allem, aber vor Commit
```

Schritt 1 zuerst, dann 2/3/4 parallel, dann 5+6 zum Abschluss.

SQLite wird als erstes implementiert (Schritt 2), weil kein
Testcontainer noetig ist und der schnellste Feedback-Loop entsteht.

---

## Verifikation

```bash
# SQLite-Reader (schnelles Feedback)
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-sqlite:test" \
  -t d-migrate:phase-d-sqlite .

# PostgreSQL-Integration
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test

# MySQL-Integration
./scripts/test-integration-docker.sh :adapters:driven:driver-mysql:test

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

Gezielt zu pruefen:
- Kanonischer `name`/`version` pro Dialekt
- Reverse-Scope mit Sonderzeichen in DB-/Schema-Namen
- Kein PK-redundantes `required`/`unique` auf Spaltenebene
- Backing-Index-Unterdrueckung
- Notes/Skips fuer nicht-neutrale Faelle
- Include-Flags fuer optionale Objekte
- Connection-Freigabe nach Read
