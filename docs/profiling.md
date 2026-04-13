# Design-Dokument: Daten-Profiling

**Erweiterung von d-migrate um deterministisches Datenbank-Profiling**

> Dokumenttyp: Design-Spezifikation (Entwurf)
>
> Status: Entwurf. Beschreibt die Integration in die bestehende d-migrate-Architektur. Geplante Einordnung: Phase 2 (0.7.5).

---

## 1. Zielbild

Daten-Profiling ergänzt d-migrate um die Fähigkeit, relationale Datenbestände **vor einer Migration** zu analysieren. Das Ergebnis ist ein strukturiertes Profil mit Kennzahlen, Typinformationen und Qualitätswarnungen pro Tabelle und Spalte.

**Fachlicher Kontext im Migrationsprozess:**

```
  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ Profiling │────▶│ Schema   │────▶│ DDL      │────▶│ Data     │────▶│ Data     │
  │ (NEU)     │     │ Validate │     │ Generate │     │ Export   │     │ Import   │
  └──────────┘     └──────────┘     └──────────┘     └──────────┘     └──────────┘
       ▲                                                                    │
       │              Migrationsvorbereitung                                 │
       └────────────────────────────────────────────────────────────────────┘
                              Feedback-Loop
```

**Kernfähigkeiten:**

- Schema und Datentypen aus bestehender Datenbank lesen
- Tabellen und beliebige Queries profilieren (Kennzahlen je Spalte)
- Qualitätswarnungen erzeugen (Null-Anteil, Kardinalität, Platzhalter, Typ-Anomalien)
- Zieltyp-Kompatibilität prüfen (passt `VARCHAR(50)` in `integer`?)
- Ergebnis als JSON/YAML ausgeben

---

## 2. Abgrenzung zum bestehenden d-migrate

| Aspekt        | d-migrate (heute)                          | Profiling (neu)                                    |
| ------------- | ------------------------------------------ | -------------------------------------------------- |
| **Eingabe**   | Neutrale Schema-YAML-Datei                 | Live-Datenbank (JDBC)                              |
| **Fokus**     | Schema-Struktur und Datentransfer          | Daten-Inhalt und -Qualität                         |
| **Modell**    | `SchemaDefinition`, `TableDefinition`      | `DatabaseProfile`, `TableProfile`, `ColumnProfile` |
| **Typsystem** | `NeutralType` (18 Typen, schemaorientiert) | `LogicalType` (9 Typen, datenorientiert)           |
| **Ausgabe**   | DDL, Exportdateien                         | Profil-Report (JSON/YAML)                          |

Die Modelle sind bewusst getrennt: Ein Schema beschreibt die *Struktur*, ein Profil beschreibt den *Zustand der Daten*. Die Verbindung zwischen beiden ist die Zieltyp-Kompatibilitätsprüfung (`TargetTypeCompatibility`), die Profildaten gegen das neutrale Typsystem abgleicht.

---

## 3. Integration in die Hexagonale Architektur

### 3.1 Modulstruktur

Profiling wird als eigenständiges Gradle-Modul im Hexagon angelegt:

```
d-migrate/
├── hexagon/
│   ├── core/                              ← bestehend
│   ├── ports/                             ← bestehend
│   ├── application/                       ← bestehend
│   └── profiling/                         ← NEU
│       └── src/main/kotlin/dev/dmigrate/profiling/
│           ├── model/                     ← Domain-Modell (Profile, Stats, Warnings)
│           ├── rules/                     ← Warning-Regeln (Column/Table-Rules)
│           ├── types/                     ← LogicalType, Severity, WarningCode
│           ├── port/                      ← Outbound-Ports (SchemaPort, ProfilingDataPort)
│           └── service/                   ← Orchestrierung (ProfileTable, ProfileDatabase)
├── adapters/
│   └── driven/
│       ├── driver-postgresql/             ← bestehend, erweitert um Profiling-Adapter
│       ├── driver-mysql/                  ← bestehend, erweitert um Profiling-Adapter
│       ├── driver-sqlite/                 ← bestehend, erweitert um Profiling-Adapter
│       └── formats/                       ← bestehend, Serialisierung von Profil-Ergebnis
```

### 3.2 Paketstruktur

Alle Profiling-Klassen liegen unter `dev.dmigrate.profiling.*`, analog zu den bestehenden Konventionen (`dev.dmigrate.core.*`, `dev.dmigrate.driver.*`):

| Paket                                      | Inhalt                                                                                                                                                             |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `dev.dmigrate.profiling.model`             | `DatabaseProfile`, `TableProfile`, `ColumnProfile`, `NumericStats`, `TemporalStats`, `PatternStats`, `ValueFrequency`, `TargetTypeCompatibility`, `ProfileWarning` |
| `dev.dmigrate.profiling.model.constraints` | `PrimaryKeyProfile`, `ForeignKeyProfile`, `UniqueConstraintProfile`                                                                                                |
| `dev.dmigrate.profiling.types`             | `LogicalType`, `TargetLogicalType`, `Severity`, `WarningCode`                                                                                                      |
| `dev.dmigrate.profiling.rules`             | `ColumnWarningRule`, `TableWarningRule`, `WarningEvaluator`                                                                                                        |
| `dev.dmigrate.profiling.port`              | `SchemaIntrospectionPort`, `ProfilingDataPort`, `LogicalTypeResolverPort`                                                                                          |
| `dev.dmigrate.profiling.service`           | `ProfileDatabaseService`, `ProfileTableService`, `QueryProfilingService`                                                                                           |

### 3.3 Wiederverwendung bestehender Infrastruktur

Die größte Integrationsersparnis liegt in der Wiederverwendung der bestehenden JDBC-Infrastruktur:

| Bestehende Komponente                       | Wiederverwendung für Profiling       |
| ------------------------------------------- | ------------------------------------ |
| `DatabaseDriver` / `DatabaseDriverRegistry` | Dialekt-Erkennung und Treiber-Lookup |
| HikariCP Connection-Pool                    | Verbindungsmanagement                |
| `NamedConnectionResolver`                   | `.d-migrate.yaml` Named Connections  |
| `DataReader` (JDBC-Streaming)               | Basis für Aggregate-Queries          |
| `LogScrubber`                               | Passwort-sichere Logs                |
| Jackson / SnakeYAML                         | JSON/YAML-Ausgabe des Profil-Reports |
| Clikt CLI-Framework                         | Neues Subcommand `data profile`      |

**Nicht wiederverwendbar** (muss neu implementiert werden):

| Neue Komponente                                           | Grund                                                                                                      |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Aggregate-Queries (COUNT DISTINCT, MIN/MAX, TopN, Stddev) | Existiert nicht — d-migrate liest/schreibt Daten, aggregiert sie aber nicht                                |
| Schema-Introspection (PRAGMA, `information_schema`)       | Teilweise vorhanden via `TableLister`, aber nicht spaltengenau (Typ, Nullable, Constraints)                |
| `LogicalTypeResolver` pro Dialekt                         | Mapping von DB-Typen auf `LogicalType` — existiert nicht, da d-migrate mit `NeutralType` aus YAML arbeitet |
| Warning-Rule-Engine                                       | Fachliche Regeln für Datenqualität sind ein neues Konzept                                                  |

---

## 4. Domain-Modell

Das Profiling-Modell ist als Jackson-serialisierbare Kotlin-Datentypen definiert (konsistent mit dem Rest von d-migrate).

### 4.1 Kernmodell

```kotlin
package dev.dmigrate.profiling.model

data class DatabaseProfile(
    val databaseProduct: String,
    val databaseVersion: String? = null,
    val schemaName: String? = null,
    val generatedAt: String,
    val tables: List<TableProfile>
)

data class TableProfile(
    val name: String,
    val schema: String? = null,
    val rowCount: Long,
    val columns: List<ColumnProfile>,
    val primaryKey: PrimaryKeyProfile? = null,
    val foreignKeys: List<ForeignKeyProfile> = emptyList(),
    val uniqueConstraints: List<UniqueConstraintProfile> = emptyList(),
    val warnings: List<ProfileWarning> = emptyList()
)

data class ColumnProfile(
    val name: String,
    val dbType: String,
    val logicalType: LogicalType,
    val nullable: Boolean,
    val rowCount: Long,
    val nonNullCount: Long,
    val nullCount: Long,
    val emptyStringCount: Long? = null,
    val blankStringCount: Long? = null,
    val distinctCount: Long? = null,
    val duplicateValueCount: Long? = null,
    val minLength: Long? = null,
    val maxLength: Long? = null,
    val minValue: String? = null,
    val maxValue: String? = null,
    val topValues: List<ValueFrequency> = emptyList(),
    val numericStats: NumericStats? = null,
    val temporalStats: TemporalStats? = null,
    val patternStats: PatternStats? = null,
    val targetCompatibility: List<TargetTypeCompatibility> = emptyList(),
    val warnings: List<ProfileWarning> = emptyList()
)
```

### 4.2 Statistik-Typen

```kotlin
data class NumericStats(
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val sum: Double? = null,
    val stddev: Double? = null,
    val zeroCount: Long? = null,
    val negativeCount: Long? = null
)

data class TemporalStats(
    val minTimestamp: String? = null,
    val maxTimestamp: String? = null
)

data class PatternStats(
    val emailLikeCount: Long? = null,
    val phoneLikeCount: Long? = null,
    val uuidLikeCount: Long? = null,
    val dateLikeCount: Long? = null
)

data class ValueFrequency(
    val value: String? = null,
    val count: Long,
    val ratio: Double
)
```

### 4.3 Zieltyp-Kompatibilität

Verbindet das Profiling-Ergebnis mit dem d-migrate-Typsystem:

```kotlin
data class TargetTypeCompatibility(
    val targetType: TargetLogicalType,
    val compatibleCount: Long,
    val incompatibleCount: Long,
    val incompatibleExamples: List<String> = emptyList()
)
```

### 4.4 Warnungen und Typen

```kotlin
data class ProfileWarning(
    val code: WarningCode,
    val message: String,
    val severity: Severity = Severity.INFO
)

enum class WarningCode {
    HIGH_NULL_RATIO,
    CONTAINS_EMPTY_STRINGS,
    CONTAINS_BLANK_STRINGS,
    HIGH_CARDINALITY,
    LOW_CARDINALITY,
    POSSIBLE_PLACEHOLDER_VALUES,
    DUPLICATE_VALUES,
    TYPE_MISMATCH,
    INVALID_TARGET_TYPE_VALUES,
    SUSPICIOUS_DATE_VALUES,
    SUSPICIOUS_NUMERIC_VALUES
}

enum class Severity { INFO, WARN, ERROR }

enum class LogicalType {
    STRING, INTEGER, DECIMAL, BOOLEAN,
    DATE, DATETIME, BINARY, JSON, UNKNOWN
}

enum class TargetLogicalType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME
}
```

### 4.5 Verhältnis LogicalType zu NeutralType

`LogicalType` (Profiling) und `NeutralType` (d-migrate Core) sind bewusst getrennte Konzepte:

| LogicalType (Profiling) | Abgedeckte NeutralTypes (Core)                    |
| ----------------------- | ------------------------------------------------- |
| `STRING`                | `text`, `char`, `email`, `xml`, `uuid`, `enum`    |
| `INTEGER`               | `identifier`, `integer`, `biginteger`, `smallint` |
| `DECIMAL`               | `decimal`, `float`                                |
| `BOOLEAN`               | `boolean`                                         |
| `DATE`                  | `date`                                            |
| `DATETIME`              | `datetime`, `time`                                |
| `BINARY`                | `binary`                                          |
| `JSON`                  | `json`, `array`                                   |
| `UNKNOWN`               | (kein Mapping)                                    |

`LogicalType` ist gröber, weil es den *beobachteten Dateninhalt* klassifiziert, nicht die *deklarierte Struktur*. Eine Spalte vom Typ `VARCHAR(36)` kann `LogicalType.STRING` sein, obwohl die Daten UUID-Muster zeigen — die `PatternStats` machen das sichtbar.

---

## 5. Ports und Services

### 5.1 Outbound-Ports

```kotlin
package dev.dmigrate.profiling.port

/** Schema-Introspection: Tabellenliste, Spaltenmetadaten, Constraints */
interface SchemaIntrospectionPort {
    fun databaseProduct(): String
    fun databaseVersion(): String?
    fun listTableNames(schemaName: String? = null): List<String>
    fun loadTableSchema(tableName: String): TableSchema
}

data class TableSchema(
    val tableName: String,
    val columns: List<ColumnSchema>,
    val primaryKey: PrimaryKeyProfile? = null,
    val foreignKeys: List<ForeignKeyProfile> = emptyList(),
    val uniqueConstraints: List<UniqueConstraintProfile> = emptyList()
)

data class ColumnSchema(
    val name: String,
    val dbType: String,
    val nullable: Boolean
)

/** Statistische Aggregate-Queries pro Spalte/Tabelle */
interface ProfilingDataPort {
    fun rowCount(tableName: String): Long
    fun profileColumn(tableName: String, columnName: String, dbType: String, nullable: Boolean): ColumnProfile
    fun profileQuery(query: String, logicalName: String): TableProfile
}

/** DB-Typ → LogicalType Auflösung, pro Dialekt implementiert */
interface LogicalTypeResolverPort {
    fun resolve(dbType: String, nullable: Boolean): LogicalType
}
```

**Hinweis**: `SchemaIntrospectionPort` hat Überschneidungen mit dem geplanten `SchemaReader` (Roadmap 0.6.0, LF-004). Sobald `SchemaReader` implementiert ist, kann `SchemaIntrospectionPort` darauf delegieren oder als Fassade darüber liegen. Für die Profiling-V1 wird ein eigenständiger, schlanker Introspection-Adapter implementiert.

### 5.2 Services

```kotlin
/** Profiliert die gesamte Datenbank */
class ProfileDatabaseService(
    private val schemaPort: SchemaIntrospectionPort,
    private val profileTableService: ProfileTableService,
    private val clockPort: ClockPort              // bestehend aus d-migrate
) {
    fun execute(): DatabaseProfile
}

/** Profiliert eine einzelne Tabelle */
class ProfileTableService(
    private val schemaPort: SchemaIntrospectionPort,
    private val profilingPort: ProfilingDataPort,
    private val typeResolver: LogicalTypeResolverPort,
    private val warningEvaluator: WarningEvaluator
) {
    fun execute(tableName: String): TableProfile
}

/** Profiliert ein beliebiges Query-Ergebnis */
class QueryProfilingService(
    private val profilingPort: ProfilingDataPort,
    private val warningEvaluator: WarningEvaluator
) {
    fun execute(query: String, logicalName: String = "query_result"): TableProfile
}
```

### 5.3 Warning-Regeln

Die Rule-Engine ist eine einfache funktionale Abstraktion:

```kotlin
fun interface ColumnWarningRule {
    fun evaluate(profile: ColumnProfile): List<ProfileWarning>
}

fun interface TableWarningRule {
    fun evaluate(profile: TableProfile): List<ProfileWarning>
}

class WarningEvaluator(
    private val tableRules: List<TableWarningRule> = emptyList(),
    private val columnRules: List<ColumnWarningRule> = emptyList()
) {
    fun evaluateTable(table: TableProfile): List<ProfileWarning>
    fun evaluateColumn(column: ColumnProfile): List<ProfileWarning>
}
```

---

## 6. Adapter-Implementierungen

### 6.1 DB-Adapter pro Dialekt

Jeder Dialekt implementiert drei Ports: `SchemaIntrospectionPort`, `ProfilingDataPort` und `LogicalTypeResolverPort`.

| Dialekt    | Schema-Introspection                           | Aggregate-Queries                   | Typ-Resolver                    |
| ---------- | ---------------------------------------------- | ----------------------------------- | ------------------------------- |
| PostgreSQL | `information_schema` + `pg_catalog`            | Standard-SQL-Aggregate + `pg_stats` | PG-Typen → `LogicalType`        |
| MySQL      | `information_schema`                           | Standard-SQL-Aggregate              | MySQL-Typen → `LogicalType`     |
| SQLite     | `PRAGMA table_info`, `PRAGMA foreign_key_list` | Standard-SQL-Aggregate              | SQLite Affinity → `LogicalType` |

### 6.2 Profilierungsablauf

```
1. Schema introspektieren           → SchemaIntrospectionPort.loadTableSchema()
2. Spaltenmetadaten laden           → ColumnSchema (Typ, Nullable, Constraints)
3. Kennzahlen je Spalte berechnen   → ProfilingDataPort.profileColumn()
   - rowCount, nullCount, nonNullCount
   - distinctCount, duplicateCount, topValues
   - empty/blank Counts (nur String-Spalten)
   - numerische Stats (min, max, avg, stddev)
   - temporale Stats (minTimestamp, maxTimestamp)
   - Pattern-Erkennung (Email, UUID, Telefon, Datum)
4. LogicalType auflösen             → LogicalTypeResolverPort.resolve()
5. Regeln anwenden, Warnungen       → WarningEvaluator.evaluate*()
6. TableProfile / DatabaseProfile zusammenbauen
7. Serialisieren (JSON/YAML)        → bestehende Format-Adapter
```

### 6.3 Aggregate-Queries (Beispiel PostgreSQL)

```sql
-- Basis-Kennzahlen pro Spalte
SELECT
    COUNT(*)                                    AS row_count,
    COUNT("col")                                AS non_null_count,
    COUNT(*) - COUNT("col")                     AS null_count,
    COUNT(DISTINCT "col")                       AS distinct_count,
    COUNT(*) - COUNT(DISTINCT "col")            AS duplicate_count,
    MIN(LENGTH(CAST("col" AS TEXT)))            AS min_length,
    MAX(LENGTH(CAST("col" AS TEXT)))            AS max_length,
    MIN("col")                                  AS min_value,
    MAX("col")                                  AS max_value
FROM "table_name";

-- Numerische Stats (nur für numerische Spalten)
SELECT
    MIN("col")     AS min_val,
    MAX("col")     AS max_val,
    AVG("col")     AS avg_val,
    SUM("col")     AS sum_val,
    STDDEV("col")  AS stddev_val,
    COUNT(*) FILTER (WHERE "col" = 0)  AS zero_count,
    COUNT(*) FILTER (WHERE "col" < 0)  AS negative_count
FROM "table_name" WHERE "col" IS NOT NULL;

-- Top-N Values
SELECT CAST("col" AS TEXT) AS value, COUNT(*) AS cnt
FROM "table_name"
GROUP BY "col"
ORDER BY cnt DESC
LIMIT 10;
```

---

## 7. CLI-Integration

### 7.1 Neues Subcommand

```
d-migrate data profile [Optionen]
```

| Flag       | Typ                   | Pflicht                | Beschreibung                               |
| ---------- | --------------------- | ---------------------- | ------------------------------------------ |
| `--source` | URL / Connection-Name | Ja                     | Quelldatenbank                             |
| `--format` | `json` / `yaml`       | Nein (Default: `json`) | Ausgabeformat                              |
| `--output` | Dateipfad             | Nein (Default: stdout) | Ausgabedatei                               |
| `--tables` | Komma-Liste           | Nein (Default: alle)   | Einschränkung auf bestimmte Tabellen       |
| `--schema` | String                | Nein                   | Datenbankschema (PostgreSQL)               |
| `--top-n`  | Int                   | Nein (Default: 10)     | Anzahl Top-Values pro Spalte               |
| `--query`  | SQL-String            | Nein                   | Einzelnes Query profilieren statt Tabellen |

**Beispielaufrufe:**

```bash
# Gesamte Datenbank profilieren
d-migrate data profile --source "jdbc:postgresql://localhost/mydb" --output profile.json

# Einzelne Tabellen, YAML-Ausgabe
d-migrate data profile --source staging-db --tables users,orders --format yaml

# Named Connection aus .d-migrate.yaml
d-migrate data profile --source production --output report.json

# Query profilieren
d-migrate data profile --source dev-db --query "SELECT * FROM users WHERE created_at > '2025-01-01'"
```

### 7.2 Exit-Codes

Folgt der bestehenden Matrix:

| Code | Bedeutung                                                |
| ---- | -------------------------------------------------------- |
| 0    | Profiling erfolgreich                                    |
| 2    | CLI-Fehler (fehlende Flags, ungültige Optionen)          |
| 4    | Verbindungsfehler (DB nicht erreichbar)                  |
| 6    | Profiling-Fehler (Query fehlgeschlagen, Schema-Anomalie) |

---

## 8. Fehlerbehandlung

Folgt dem bestehenden d-migrate-Muster: Adapter fangen technische Exceptions und übersetzen sie in Domain-Exceptions.

```
RuntimeException
 └── DomainException                   (bestehend in d-migrate)
      └── ProfilingException           (NEU)
           ├── SchemaIntrospectionError  — Schema/Tabelle nicht lesbar
           ├── ProfilingQueryError       — Aggregate-Query fehlgeschlagen
           └── TypeResolutionError       — DB-Typ nicht auflösbar
```

---

## 9. Teststrategie

| Ebene                | Ziel                                                                      | Werkzeug                          |
| -------------------- | ------------------------------------------------------------------------- | --------------------------------- |
| **Unit (Domain)**    | `WarningEvaluator`, `ColumnWarningRule`, `TableWarningRule`, Typ-Resolver | JUnit 5 + Kotest Assertions       |
| **Unit (Service)**   | Orchestrierungslogik, korrekte Port-Aufrufe                               | JUnit 5 + MockK                   |
| **Integration (DB)** | SQLite-Adapter gegen echte In-Memory-DB                                   | JUnit 5 + SQLite `:memory:`       |
| **Integration (DB)** | PostgreSQL/MySQL-Adapter                                                  | Testcontainers (PG 16, MySQL 8.0) |
| **End-to-End**       | Komplette Pipeline: DB → Profil → JSON, CLI Round-Trip                    | JUnit 5 + Testdatenbank           |
| **Determinismus**    | Identische Eingabe → identische Ausgabe                                   | `ClockPort`-Mock mit fixem Wert   |

**Ziel**: >= 90% Testabdeckung pro Modul. I/O-Glue-Code wird über Port-Abstraktion testbar gemacht, nicht von der Coverage ausgenommen.

---

## 10. Semantische Analyse (LLM-Erweiterung, spätere Phase)

> **Nicht Teil der initialen Profiling-Implementierung.** Hier dokumentiert, weil das externe Design-Dokument diese Erweiterung vorsieht und die Architektur darauf vorbereitet sein soll.

### 10.1 Zielsetzung

LLM ergänzt das deterministische Kern-Profiling um semantische Einordnung:

- Spaltenbedeutung ableiten (`cust_no`, `customer_nr`, `debitor_id` als Äquivalente erkennen)
- Mapping-Vorschläge zwischen Quell- und Zielschema
- Platzhalter-Intentionen erkennen (`9999-12-31`, `0000-00-00`)
- Transformationsvorschläge (Trim, Nullisierung, Bool-Interpretation)

### 10.2 Grenzen (verbindlich)

- LLM ist **nicht** alleinige Entscheidungsinstanz
- Harte Validierung bleibt deterministisch im Kern
- Ergebnisse sind als Vorschläge zu behandeln, nicht als Fakten
- Konsistent mit d-migrates Prinzip **Privacy by Design**: LLM-Aufrufe sind opt-in

### 10.3 Zweistufiger Ansatz

1. **Stage 1** — Deterministisches Profiling (Kern, §4-6)
2. **Stage 2** — LLM bekommt verdichteten Kontext (Profil-Summary, nicht rohe Daten), liefert strukturierte Vorschläge, die gegen harte Regeln validiert werden

### 10.4 Architektonische Vorbereitung

Der LLM-Zugriff wird über einen Outbound-Port abstrahiert, analog zur bestehenden KI-Provider-Architektur in `architecture.md` §1.1:

```kotlin
package dev.dmigrate.profiling.port

interface SemanticAnalysisPort {
    suspend fun analyzeTableSemantics(request: TableSemanticRequest): TableSemanticResult
    suspend fun analyzeColumnSemantics(request: ColumnSemanticRequest): SemanticColumnAnalysis
}
```

Adapter-Implementierungen (OpenAI, Ollama, Mock) folgen dem bestehenden Muster unter `adapters/driven/`. Der Mock-Adapter ermöglicht Tests ohne LLM-Abhängigkeit.

---

## 11. Einordnung in die Roadmap

Profiling hat eine natürliche Abhängigkeit zum **Reverse-Engineering** (Milestone 0.6.0, LF-004): Beide brauchen Schema-Introspection aus einer Live-Datenbank. Profiling setzt daher auf dem fertigen `SchemaReader` aus 0.6.0 auf. Die Schema-Introspection wird nicht doppelt implementiert, und `SchemaIntrospectionPort` delegiert direkt an den `SchemaReader`. Geplanter Milestone: **0.7.0+**.

---

## 12. Design-Entscheidungen

| #   | Frage                                                            | Entscheidung                            | Begründung                                                                                                                  |
| --- | ---------------------------------------------------------------- | --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 1   | Serialisierung: Jackson oder kotlinx.serialization?              | **Jackson**                             | Konsistenz mit bestehendem d-migrate; kein zweites Serialisierungs-Framework einführen                                      |
| 2   | `LogicalType` als eigener Enum oder auf `NeutralType` mappen?    | **Eigener Enum**                        | Andere Granularität als `NeutralType` — klassifiziert beobachteten Dateninhalt, nicht deklarierte Struktur (§4.5)           |
| 3   | Profiling als eigenes Gradle-Modul oder in `hexagon/core`?       | **Eigenes Modul** (`hexagon/profiling`) | Klare Abgrenzung; Core bleibt schlank; Profiling hat eigenes Domain-Modell und eigene Ports                                 |
| 4   | LLM-Erweiterung in gleicher Phase oder bewusst getrennt?         | **Getrennt**                            | Kern-Profiling erst stabil und getestet, dann semantische Analyse als Aufbaustufe (§10)                                     |
| 5   | `PatternStats`-Erkennung: SQL-basiert oder in Kotlin nach Fetch? | **Kotlin Regex**                        | Portabler über alle Dialekte; SQLite hat kein natives REGEXP; Pattern-Erkennung läuft auf den bereits gelesenen `topValues` |
