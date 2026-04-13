# Design-Dokument: Daten-Profiling

**Erweiterung von d-migrate um deterministisches Datenbank-Profiling**

> Dokumenttyp: Design-Spezifikation (Entwurf)
>
> Status: Entwurf. Beschreibt den geplanten Soll-Zustand für d-migrate ab Phase 2 (0.7.5). Referenzen auf `SchemaReader`, `hexagon/profiling` und neue Profiling-Ports sind Zielarchitektur, nicht bereits heute im Code vorhandene APIs.

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
- Strukturprobleme erkennen (nummerierte Spaltengruppen, Denormalisierung)
- Normalisierungsvorschläge ableiten (FD-Discovery, Lookup-Extraktion, Unpivot)
- Ergebnis als JSON/YAML ausgeben

---

## 2. Abgrenzung zum bestehenden d-migrate

| Aspekt        | d-migrate (heute)                          | Profiling (neu)                                    |
| ------------- | ------------------------------------------ | -------------------------------------------------- |
| **Eingabe**   | Neutrale Schema-YAML-Datei                 | Live-Datenbank (JDBC)                              |
| **Fokus**     | Schema-Struktur und Datentransfer          | Daten-Inhalt und -Qualität                         |
| **Modell**    | `SchemaDefinition`, `TableDefinition`      | `DatabaseProfile`, `TableProfile`, `ColumnProfile` |
| **Typsystem** | `NeutralType` (18 Typen, schemaorientiert) | `LogicalType` (10 Typen, datenorientiert)          |
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
│           ├── port/                      ← Outbound-Ports (SchemaIntrospectionPort, ProfilingDataPort)
│           └── service/                   ← Orchestrierung (ProfileTableService, ProfileDatabaseService)
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
| `dev.dmigrate.profiling.model`             | `DatabaseProfile`, `TableProfile`, `ColumnProfile`, `NumericStats`, `TemporalStats`, `PatternStats`, `SpatialStats`, `BoundingBox`, `ValueFrequency`, `TargetTypeCompatibility`, `ProfileWarning` |
| `dev.dmigrate.profiling.model.constraints` | `PrimaryKeyProfile`, `ForeignKeyProfile`, `UniqueConstraintProfile`                                                                                                |
| `dev.dmigrate.profiling.types`             | `LogicalType`, `TargetLogicalType`, `Severity`, `WarningCode`                                                                                                      |
| `dev.dmigrate.profiling.rules`             | `ColumnWarningRule`, `TableWarningRule`, `WarningEvaluator`                                                                                                        |
| `dev.dmigrate.profiling.model.structural`  | `StructuralFinding`, `StructuralFindingKind`, `NormalizationProposal`, `ProposedEntity`, `ProposedLookup`, `UnpivotCandidate`                                      |
| `dev.dmigrate.profiling.port`              | `SchemaIntrospectionPort`, `ProfilingDataPort`, `LogicalTypeResolverPort`                                                                                          |
| `dev.dmigrate.profiling.service`           | `ProfileDatabaseService`, `ProfileTableService`, `QueryProfilingService`                                                                                           |

### 3.3 Wiederverwendung bestehender Infrastruktur

Die größte Integrationsersparnis liegt in der Wiederverwendung der bestehenden JDBC-Infrastruktur:

| Bestehende Komponente                       | Wiederverwendung für Profiling       |
| ------------------------------------------- | ------------------------------------ |
| `DatabaseDriver` / `DatabaseDriverRegistry` | Dialekt-Erkennung und Treiber-Lookup |
| HikariCP Connection-Pool                    | Verbindungsmanagement                |
| `NamedConnectionResolver`                   | `.d-migrate.yaml` Named Connections  |
| `LogScrubber`                               | Passwort-sichere Logs                |
| Jackson / SnakeYAML                         | JSON/YAML-Ausgabe des Profil-Reports |
| Clikt CLI-Framework                         | Neues Subcommand `data profile`      |

**Nicht wiederverwendbar** (muss neu implementiert werden):

| Neue Komponente                                           | Grund                                                                                                      |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Profiling-JDBC-Adapter für Aggregate-Queries              | Existiert nicht — `DataReader` streamt Zeilen, bietet aber keine Aggregat-/Profiling-Abfragen             |
| Schema-Introspection-Projektion mit rohem `dbType`        | Das neutrale Reverse-Engineering-Modell reicht für Profiling nicht aus, weil deklarierte DB-Typen erhalten bleiben müssen |
| `LogicalTypeResolver` pro Dialekt                         | Mapping von DB-Typen auf `LogicalType` — existiert nicht, da d-migrate mit `NeutralType` aus YAML arbeitet |
| Warning-Rule-Engine                                       | Fachliche Regeln für Datenqualität sind ein neues Konzept                                                  |
| Normalisierungsanalyse (FD-Discovery)                     | Functional-Dependency-Erkennung ist ein neues Konzept — kein bestehendes Pendant in d-migrate              |

Die Profiling-Introspection soll daher auf derselben JDBC-Metadatenbasis aufbauen wie das spätere Reverse-Engineering, aber nicht blind 1:1 das bestehende `SchemaReader`-Interface spiegeln: Profiling braucht zusätzlich den rohen Datenbanktyp (`dbType`) und weitere Profiling-spezifische Metadaten.

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
    val structuralFindings: List<StructuralFinding> = emptyList(),
    val normalizationProposal: NormalizationProposal? = null,
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
    val spatialStats: SpatialStats? = null,
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
    val basedOnTopValues: Boolean = true,
    val coveredRowsByTopValues: Long? = null,
    val emailLikeRowsInTopValues: Long? = null,
    val phoneLikeRowsInTopValues: Long? = null,
    val uuidLikeRowsInTopValues: Long? = null,
    val dateLikeRowsInTopValues: Long? = null
)

data class SpatialStats(
    val geometryTypes: List<ValueFrequency> = emptyList(),
    val sridValues: List<ValueFrequency> = emptyList(),
    val boundingBox: BoundingBox? = null,
    val emptyGeometryCount: Long? = null,
    val invalidGeometryCount: Long? = null
)

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
)

data class ValueFrequency(
    val value: String? = null,
    val count: Long,
    val ratio: Double
)
```

`PatternStats` ist bewusst **sample-basiert**: Die Kennzahlen beziehen sich auf die in `topValues` enthaltenen häufigsten Werte und deren Row-Abdeckung, nicht auf einen separaten Vollscan aller Werte. Dadurch bleibt die Erkennung dialektportabel und deterministisch, ohne globale Pattern-Counts vorzutäuschen.

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
    DATE, DATETIME, BINARY, JSON, GEOMETRY, UNKNOWN
}

enum class TargetLogicalType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME
}
```

### 4.5 Strukturanalyse

Die Strukturanalyse erkennt Muster in Spaltennamen, die auf Design-Probleme im Schema hindeuten. Sie wird pro Tabelle ausgewertet und als `structuralFindings` im `TableProfile` ausgegeben.

```kotlin
data class StructuralFinding(
    val kind: StructuralFindingKind,
    val columns: List<String>,
    val message: String
)

enum class StructuralFindingKind {
    /** Nummerierte Spalten: Wert_1, Wert_2, Wert_3 → vermutlich denormalisiert */
    REPEATED_COLUMN_GROUP,
    /** Gleichartige Präfix-Gruppen: phone_home, phone_work, phone_mobile */
    PARALLEL_COLUMN_GROUP
}
```

**Erkennungslogik** (Kotlin Regex auf Spaltennamen, kein DB-Zugriff nötig):

| Muster | Beispiel | Finding |
| ------ | -------- | ------- |
| `<prefix>_<N>` mit N = 1, 2, 3... | `Wert_1`, `Wert_2`, `Wert_3` | `REPEATED_COLUMN_GROUP` |
| `<prefix><N>` ohne Trenner | `addr1`, `addr2`, `addr3` | `REPEATED_COLUMN_GROUP` |
| `<prefix>_<suffix>` mit gleichem Präfix und ≥ 3 Varianten | `phone_home`, `phone_work`, `phone_mobile` | `PARALLEL_COLUMN_GROUP` |

**Beispiel-Ausgabe** (JSON):

```json
{
  "structuralFindings": [
    {
      "kind": "REPEATED_COLUMN_GROUP",
      "columns": ["Wert_1", "Wert_2", "Wert_3"],
      "message": "Nummerierte Spaltengruppe 'Wert_' (3 Spalten) — möglicherweise denormalisiert"
    }
  ]
}
```

Die namenbasierte Erkennung ist deterministisch und braucht keinen DB-Zugriff — sie läuft auf den bereits geladenen Spaltenmetadaten.

#### Normalisierungsanalyse (datenbasiert)

Über die Namenserkennung hinaus kann das Profiling auf Basis der **tatsächlichen Daten** Normalisierungsvorschläge ableiten. Die Analyse kombiniert mehrere Techniken:

**Deterministisch per Code möglich:**

| Technik | Was sie erkennt | Beispiel |
| ------- | --------------- | -------- |
| Repeated-Column-Unpivot | `Wert_1`, `Wert_2`, `Wert_3` → Kindtabelle mit Laufnummer | Mechanisch, keine Semantik nötig |
| Functional-Dependency-Discovery (FD) | Spalte A bestimmt Spalte B → eigene Entität | Algorithmen wie HyFD, TANE — arbeiten auf echten Daten |
| Low-Cardinality-Extraktion | Spalte mit wenigen Werten → Lookup-Tabelle | `status` mit 3 Werten → `status`-Referenztabelle |
| Kookkurrenz-Analyse | Spaltengruppen mit identischem Werteverlauf → gehören zusammen | `kunde_name` + `kunde_email` immer gleich bei gleichem `kunde_id` |

**Braucht LLM oder menschliche Bewertung (§10):**

| Aspekt | Warum Code nicht reicht |
| ------ | ----------------------- |
| Entitäten benennen | Code erkennt die Gruppe `kunde_name, kunde_email, kunde_tel` — aber "Kunde" als Tabellenname ist Semantik |
| Fachliche Bedeutung | Was bedeuten `Wert_1..3`? Messwerte, Preisstufen, Bewertungen? |
| Beziehungstypen | 1:N oder M:N? Daten können es andeuten, aber nicht sicher entscheiden |
| Mehrdeutige FDs | Bei unsauberen Daten: echte Abhängigkeit oder Zufall? |

Die Normalisierungsanalyse folgt dem zweistufigen Ansatz aus §10: Code liefert den strukturellen Vorschlag, LLM (optional) ergänzt Benennung und fachliche Einordnung.

```kotlin
data class NormalizationProposal(
    val sourceTable: String,
    val proposedEntities: List<ProposedEntity>,
    val proposedLookups: List<ProposedLookup>,
    val unpivotCandidates: List<UnpivotCandidate>
)

/** Spaltengruppe, die durch FD-Analyse als eigene Entität erkannt wurde */
data class ProposedEntity(
    val determinant: List<String>,
    val dependentColumns: List<String>,
    val suggestedName: String? = null       // null = Code kann keinen Namen ableiten → LLM
)

/** Spalte mit wenigen Werten → Lookup-/Referenztabelle */
data class ProposedLookup(
    val column: String,
    val distinctValues: Long,
    val sampleValues: List<String>
)

/** Nummerierte/parallele Spaltengruppe → Unpivot in Kindtabelle */
data class UnpivotCandidate(
    val finding: StructuralFinding,
    val suggestedChildTable: String? = null  // null → LLM
)
```

Die Analyse ist optional und wird nur ausgeführt, wenn explizit angefordert (CLI-Flag `--analyze-normalization`), da die FD-Discovery auf großen Tabellen rechenintensiv sein kann. Das Feld `normalizationProposal` im `TableProfile` (§4.1) ist dann `null`, wenn die Analyse nicht angefordert wurde.

### 4.6 Verhältnis LogicalType zu NeutralType

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
| `GEOMETRY`              | `geometry`                                         |
| `UNKNOWN`               | (kein Mapping)                                    |

`LogicalType` ist gröber, weil es den *beobachteten Dateninhalt* klassifiziert, nicht die *deklarierte Struktur*. Eine Spalte vom Typ `VARCHAR(36)` kann `LogicalType.STRING` sein, obwohl die Daten UUID-Muster zeigen — die `PatternStats` machen das sichtbar.

---

## 5. Ports und Services

### 5.1 Outbound-Ports

```kotlin
package dev.dmigrate.profiling.port

data class DatabaseProfilingRequest(
    val schemaName: String? = null,
    val tables: List<String>? = null,
    val topN: Int = 10,
    val analyzeNormalization: Boolean = false
)

data class TableProfilingOptions(
    val topN: Int = 10,
    val analyzeNormalization: Boolean = false
)

data class QueryProfilingRequest(
    val query: String,
    val logicalName: String = "query_result",
    val topN: Int = 10
)

/** Schema-Introspection: Tabellenliste, Spaltenmetadaten, Constraints */
interface SchemaIntrospectionPort {
    fun databaseProduct(): String
    fun databaseVersion(): String?
    fun listTableNames(schemaName: String? = null): List<String>
    fun loadTableSchema(tableName: String, schemaName: String? = null): TableSchema
}

data class TableSchema(
    val tableName: String,
    val schemaName: String? = null,
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

data class ColumnMetrics(
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
    val spatialStats: SpatialStats? = null
)

/** Statistische Aggregate-Queries pro Spalte/Tabelle */
interface ProfilingDataPort {
    fun rowCount(tableName: String, schemaName: String? = null): Long
    fun profileColumn(
        tableName: String,
        column: ColumnSchema,
        schemaName: String? = null,
        options: TableProfilingOptions = TableProfilingOptions()
    ): ColumnMetrics
    fun profileQuery(request: QueryProfilingRequest): TableProfile
}

/** DB-Typ → LogicalType Auflösung, pro Dialekt implementiert */
interface LogicalTypeResolverPort {
    fun resolve(dbType: String, nullable: Boolean): LogicalType
}
```

**Hinweis**: `SchemaIntrospectionPort` baut auf denselben JDBC-Metadaten-Bausteinen auf wie das Reverse-Engineering aus Milestone 0.6.0 (LF-004). Eine reine 1:1-Delegation auf `SchemaReader` reicht aber nicht aus, solange Profiling zusätzlich rohe DB-Typen (`dbType`) und Profiling-spezifische Metadaten braucht.

### 5.2 Services

```kotlin
/** Profiliert die gesamte Datenbank */
class ProfileDatabaseService(
    private val schemaPort: SchemaIntrospectionPort,
    private val profileTableService: ProfileTableService,
    private val clock: java.time.Clock = java.time.Clock.systemUTC()
) {
    fun execute(request: DatabaseProfilingRequest): DatabaseProfile
}

/** Profiliert eine einzelne Tabelle */
class ProfileTableService(
    private val schemaPort: SchemaIntrospectionPort,
    private val profilingPort: ProfilingDataPort,
    private val typeResolver: LogicalTypeResolverPort,
    private val warningEvaluator: WarningEvaluator
) {
    fun execute(
        tableName: String,
        schemaName: String? = null,
        options: TableProfilingOptions = TableProfilingOptions()
    ): TableProfile
}

/** Profiliert ein beliebiges Query-Ergebnis */
class QueryProfilingService(
    private val profilingPort: ProfilingDataPort,
    private val warningEvaluator: WarningEvaluator
) {
    fun execute(request: QueryProfilingRequest): TableProfile
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

Jeder Dialekt implementiert zwei Ports: `ProfilingDataPort` und `LogicalTypeResolverPort`. Die Schema-Introspection teilt sich die JDBC-Metadatenbasis mit dem Reverse-Engineering aus 0.6.0, liefert aber ein eigenes Profiling-Projektionsmodell (§5.1).

| Dialekt    | Aggregate-Queries                   | Typ-Resolver                    |
| ---------- | ----------------------------------- | ------------------------------- |
| PostgreSQL | Standard-SQL-Aggregate + `pg_stats` | PG-Typen → `LogicalType`        |
| MySQL      | Standard-SQL-Aggregate              | MySQL-Typen → `LogicalType`     |
| SQLite     | Standard-SQL-Aggregate + Kotlin-Fallbacks für fehlende Funktionen (z. B. `stddev`) | SQLite Affinity → `LogicalType` |

### 6.2 Profilierungsablauf

```
1. Schema introspektieren           → SchemaIntrospectionPort.loadTableSchema()
2. Spaltenmetadaten laden           → ColumnSchema (Typ, Nullable, Constraints)
3. Kennzahlen je Spalte berechnen   → ProfilingDataPort.profileColumn()
   - rowCount, nullCount, nonNullCount
   - distinctCount, duplicateValueCount, topValues
   - empty/blank Counts (nur String-Spalten)
   - numerische Stats (min, max, avg, sum, stddev wo verfügbar)
   - temporale Stats (minTimestamp, maxTimestamp)
   - Pattern-Erkennung auf `topValues` (sample-basiert: Email, UUID, Telefon, Datum)
   - Spatial Stats (Geometry Types, SRID, Bounding Box, Validierung)
4. LogicalType auflösen             → LogicalTypeResolverPort.resolve()
5. Regeln anwenden, Warnungen       → WarningEvaluator.evaluate*()
6. Strukturanalyse (namenbasiert)   → StructuralFinding (§4.5)
7. Normalisierungsanalyse (optional)→ FD-Discovery, Lookup-/Unpivot-Erkennung (§4.5)
8. TableProfile / DatabaseProfile zusammenbauen
9. Serialisieren (JSON/YAML)        → bestehende Format-Adapter
```

### 6.3 Aggregate-Queries (Beispiel PostgreSQL)

```sql
-- Basis-Kennzahlen pro Spalte
SELECT
    COUNT(*)                                    AS row_count,
    COUNT("col")                                AS non_null_count,
    COUNT(*) - COUNT("col")                     AS null_count,
    COUNT(DISTINCT "col")                       AS distinct_count,
    COUNT("col") - COUNT(DISTINCT "col")        AS duplicate_count,
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
ORDER BY cnt DESC, value ASC
LIMIT 10;

-- Spatial Stats (nur für Geometry-Spalten, PostgreSQL/PostGIS)
SELECT
    ST_GeometryType("col")                          AS geom_type,
    COUNT(*)                                         AS cnt
FROM "table_name" WHERE "col" IS NOT NULL
GROUP BY ST_GeometryType("col");

SELECT
    ST_SRID("col")                                   AS srid,
    COUNT(*)                                         AS cnt
FROM "table_name" WHERE "col" IS NOT NULL
GROUP BY ST_SRID("col");

SELECT
    ST_XMin(ST_Extent("col"))                        AS min_x,
    ST_YMin(ST_Extent("col"))                        AS min_y,
    ST_XMax(ST_Extent("col"))                        AS max_x,
    ST_YMax(ST_Extent("col"))                        AS max_y,
    COUNT(*) FILTER (WHERE ST_IsEmpty("col"))        AS empty_count,
    COUNT(*) FILTER (WHERE NOT ST_IsValid("col"))    AS invalid_count
FROM "table_name" WHERE "col" IS NOT NULL;
```

`duplicate_count` zählt nur **nicht-null** Duplikat-Zeilen jenseits der ersten Ausprägung. `NULL`-Werte werden nicht als Dubletten gewertet.

Für SQLite gilt: `AVG`, `SUM`, `MIN`, `MAX` sind direkt verfügbar; Kennzahlen wie `stddev` brauchen entweder einen Kotlin-Fallback oder bleiben `null`. Das Feld `NumericStats.stddev` ist daher optional und darf bei Dialekten ohne native Unterstützung leer bleiben.

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
| `--top-n`  | Int                   | Nein (Default: 10)     | Anzahl Top-Values pro Spalte; steuert auch die sample-basierte `PatternStats`-Abdeckung |
| `--query`  | SQL-String            | Nein                   | Einzelnes Query profilieren statt Tabellen |
| `--analyze-normalization` | Flag       | Nein (Default: aus)    | FD-Discovery und Normalisierungsvorschläge aktivieren (§4.5) |

`--query` ist exklusiv zu `--tables` und `--analyze-normalization`. `--schema` wirkt nur beim Tabellen-/Datenbank-Profiling, nicht im Query-Modus.

**Beispielaufrufe:**

```bash
# Gesamte Datenbank profilieren
d-migrate data profile --source "postgresql://localhost/mydb" --output profile.json

# Einzelne Tabellen, YAML-Ausgabe
d-migrate data profile --source staging-db --tables users,orders --format yaml

# Named Connection aus .d-migrate.yaml
d-migrate data profile --source production --output report.json

# Query profilieren
d-migrate data profile --source dev-db --query "SELECT * FROM users WHERE created_at > '2025-01-01'"

# Mit Normalisierungsanalyse
d-migrate data profile --source legacy-db --tables master_data --analyze-normalization --output proposal.json
```

### 7.2 Exit-Codes

Folgt der bestehenden CLI-Matrix für Datenpfade:

| Code | Bedeutung                                                |
| ---- | -------------------------------------------------------- |
| 0    | Profiling erfolgreich                                    |
| 2    | CLI-Fehler (fehlende Flags, ungültige Optionen)          |
| 4    | Verbindungsfehler (DB nicht erreichbar)                  |
| 5    | Profiling-Ausführung fehlgeschlagen (z. B. Query, Aggregate, Normalisierungsanalyse) |
| 7    | Konfigurations-/URL-/Registry-Fehler                     |

---

## 8. Fehlerbehandlung

Profiling folgt dem bestehenden Runner-Muster: technische Fehler werden in eine lokale Profiling-Exception-Hierarchie übersetzt und im CLI-Layer auf die vorhandenen Exit-Codes gemappt. Eine globale `DomainException`-Basisklasse existiert im aktuellen Projektstand noch nicht.

```
RuntimeException
 └── ProfilingException                 (NEU)
      ├── SchemaIntrospectionError      — Schema/Tabelle nicht lesbar
      ├── ProfilingQueryError           — Aggregate-Query fehlgeschlagen
      ├── TypeResolutionError           — DB-Typ nicht auflösbar
      └── NormalizationAnalysisError    — FD-/Lookup-Analyse fehlgeschlagen
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
| **Determinismus**    | Identische Eingabe → identische Ausgabe                                   | `java.time.Clock.fixed(...)` und deterministische Sortierung (`ORDER BY cnt DESC, value ASC`) |
| **Strukturanalyse**  | Regex-Patterns für `REPEATED_COLUMN_GROUP`, `PARALLEL_COLUMN_GROUP`       | JUnit 5 (parametrisiert)          |
| **Normalisierung**   | FD-Discovery, Lookup-Erkennung, Unpivot-Kandidaten                       | JUnit 5 + SQLite `:memory:`       |
| **Spatial**          | Bounding Box, Geometry Types, SRID, Validierung                          | Testcontainers (PostGIS), SpatiaLite |

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
- Normalisierungsvorschläge benennen und fachlich einordnen (§4.5): Entitätsnamen, Beziehungstypen, mehrdeutige FDs bewerten

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

Profiling hat eine natürliche Abhängigkeit zum **Reverse-Engineering** (Milestone 0.6.0, LF-004): Beide brauchen Schema-Introspection aus einer Live-Datenbank. Profiling soll daher auf derselben JDBC-Metadatenbasis aufsetzen wie `SchemaReader`, aber ein eigenes Projektionsmodell für rohe DB-Typen und Profiling-spezifische Metadaten bereitstellen. Geplanter Milestone: **0.7.5**.

---

## 12. Design-Entscheidungen

| #   | Frage                                                            | Entscheidung                            | Begründung                                                                                                                  |
| --- | ---------------------------------------------------------------- | --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 1   | Serialisierung: Jackson oder kotlinx.serialization?              | **Jackson**                             | Konsistenz mit bestehendem d-migrate; kein zweites Serialisierungs-Framework einführen                                      |
| 2   | `LogicalType` als eigener Enum oder auf `NeutralType` mappen?    | **Eigener Enum**                        | Andere Granularität als `NeutralType` — klassifiziert beobachteten Dateninhalt, nicht deklarierte Struktur (§4.6)           |
| 3   | Profiling als eigenes Gradle-Modul oder in `hexagon/core`?       | **Eigenes Modul** (`hexagon/profiling`) | Klare Abgrenzung; Core bleibt schlank; Profiling hat eigenes Domain-Modell und eigene Ports                                 |
| 4   | LLM-Erweiterung in gleicher Phase oder bewusst getrennt?         | **Getrennt**                            | Kern-Profiling erst stabil und getestet, dann semantische Analyse als Aufbaustufe (§10)                                     |
| 5   | `PatternStats`-Erkennung: SQL-basiert oder in Kotlin nach Fetch? | **Kotlin Regex auf Top-Values-Sample** | Portabler über alle Dialekte; SQLite hat kein natives REGEXP; die Statistik ist explizit sample-basiert und auf die durch `topValues` abgedeckten Zeilen begrenzt |
