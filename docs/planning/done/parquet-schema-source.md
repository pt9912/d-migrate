# AP2: Parquet-Schemaquelle fuer den Export

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-04)
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8 Arbeitspaket 2,
> `parquet-libraries.md`, `spec/architecture.md`

---

## 1. Ziel

Arbeitspaket 2 des Parquet-Evaluierungsplans verlangt eine Entscheidung, woher
der Parquet-Writer sein Spaltenschema bezieht, bevor die erste Row Group
geschrieben wird. Decimal-Precision/Scale, Temporal-Timezone, Nullability und
ambivalente Typen (Geometry, JSON, Enum, UUID, Array) muessen vor dem Schreiben
festliegen, sonst lassen sich leere Tabellen und `NULL`-only-Spalten nicht
verlustfrei abbilden.

Dieser Sub-Doc engt die Quellenwahl ein, klaert die Konsequenzen fuer Ports
und Adapter und liefert eine Vorentscheidung, an der der AP3-Prototyp arbeitet.

---

## 2. Ausgangslage

Bestehende Bausteine in d-migrate (verifiziert via Code-Sichtung 2026-06-04):

- `hexagon/core/.../data/ColumnDescriptor.kt`: traegt heute `name`,
  `nullable`, `sqlTypeName` (opaker DB-Typname). **Kein** JDBC-Typcode,
  **keine** Precision/Scale.
- `hexagon/core/.../data/DataChunk.kt`: `table`, `columns`, `rows: List<Array<Any?>>`,
  `chunkIndex`. Werte liegen als native Java-Objekte vor.
- `hexagon/core/.../model/NeutralType.kt`: sealed, **vollstaendig**. Decimal
  hat `precision`/`scale`, DateTime hat `timezone`, Geometry hat
  `geometryType`/`srid`, Enum/Array/Json/Uuid/Email als eigene Varianten.
- `hexagon/ports-read/.../SchemaReader.kt` + dialektspezifische Reader
  liefern ein `SchemaReadResult` auf Basis von `NeutralType`.
- `adapters/driven/formats/.../ValueDeserializer.kt#JdbcTypeHint`: traegt
  `jdbcType`, `sqlTypeName`, `precision`, `scale`. Wird heute **nur im
  Import-Pfad** aus `TargetColumn` (Ziel-`ResultSetMetaData`) gebaut.
- JSON/YAML/CSV-Writer arbeiten **zellweise und schemafrei**: Decimal und
  Timestamp werden in `ValueSerializer` aus dem JDBC-Wert zur Laufzeit in
  `SerializedValue.PreciseDecimal(String)` etc. uebersetzt; das Format-
  Schema entsteht nur aus dem ersten Chunk-Header.

Folgerung: Im **Export**-Pfad gibt es heute keine kanonische „Schema-vor-
erstem-Chunk"-Quelle. Das ist die eigentliche Luecke, die AP2 schliessen muss.

---

## 3. Anforderungen an die Quelle

Aus Hauptplan Abschnitt 5 und 6 abgeleitet:

- A1 Schema ist vor `ParquetChunkWriter.begin(...)` vollstaendig bekannt.
- A2 Schema deckt Decimal-Precision/Scale, Temporal-Unit/Timezone,
  Nullability, UUID, Binary, JSON, Geometry (WKB), Arrays.
- A3 Schema bleibt fuer leere Tabellen und ausschliesslich `NULL`-Spalten
  belastbar.
- A4 Schema-Erstellung darf nicht von Laufzeitwerten eines Chunks abhaengen.
- A5 Schema-Ursprung ist im Manifest dokumentierbar (Hauptplan Abschnitt 6:
  „Ursprung des Typmappings").
- A6 Aenderung bleibt minimal-invasiv fuer JSON/YAML/CSV — die zellweise
  Logik dort darf nicht zerstoert werden.

---

## 4. Optionen

### 4.1 Option A — `SchemaReader`/`NeutralType` als Primaerquelle

Der Export ruft vor dem Streaming `SchemaReader.read(...)` und uebersetzt
`NeutralType` direkt in Parquet-Logical-Types.

- Pro: `NeutralType` ist vollstaendig; Geometry/JSON/Enum sind explizit
  modelliert; Mapping wird in Parquet-Begriffen einmal definiert.
- Pro: Funktioniert auch fuer leere Tabellen und `NULL`-Spalten unabhaengig
  von Daten.
- Contra: Der Export-Pfad nutzt heute keinen `SchemaReader`. Einbau fuer
  alle Quell-Adapter, plus Konsistenz-Annahme zwischen `SchemaReader`-Sicht
  und tatsaechlich exportierter Query.
- Contra: Verdeckte Kopplung — wer eine eigene `SELECT`-Query exportiert
  (Tabellensplit, View, Custom-Query), bekommt aus `SchemaReader` nicht
  zwingend dieselben Spalten zurueck.

### 4.2 Option B — JDBC-Metadaten der Exportquery als Primaerquelle

Der `DataReader` oeffnet die Quell-Query, liest `ResultSetMetaData` einmal,
baut daraus ein formatseitiges Schemaobjekt und uebergibt es vor dem ersten
Chunk an den Writer. Decimal-Precision/Scale, JDBC-Typcode, `sqlTypeName`
und Nullability sind direkt verfuegbar.

- Pro: Quelle ist garantiert konsistent mit den exportierten Rows, weil sie
  aus genau derselben Query stammt.
- Pro: Symmetrisch zum Import-Pfad — dort wird heute schon aus
  `TargetColumn`-Metadaten ein `JdbcTypeHint` gebaut.
- Pro: Funktioniert ohne `SchemaReader`-Pflicht, also auch fuer Custom-
  Queries.
- Contra: Ambivalente Typen (Geometry, JSON, Enum, UUID, Array) sind in
  JDBC-Metadaten oft nur als `Types.OTHER` + dialektspezifischer
  `sqlTypeName` sichtbar. Das verlangt eine dialektspezifische Aufloesung,
  die `NeutralType` heute bereits leistet.
- Contra: Nullability aus `ResultSetMetaData` ist je nach Treiber
  konservativ („nullable unknown"); Pflichtspalten koennen faelschlich als
  nullable durchgehen.

### 4.3 Option C — `ColumnDescriptor` um `NeutralType` erweitern

`DataChunk` traegt von Anfang an reicheres Schema. Alle Format-Adapter
profitieren; Parquet bekommt das Schema „umsonst" beim ersten Chunk.

- Pro: Eine einzige Schemaquelle, alle Formate sehen dasselbe.
- Pro: Maximaler Hebel: PreciseDecimal in YAML, Timezone-aware CSV,
  konsistente Geometry-Wiedergabe.
- Contra: Greift tief in den Kernkontrakt ein. `hexagon:core`,
  `hexagon:ports-write`, alle drei Writer/Reader, `StreamingExporter`,
  `StreamingImporter` und die `JdbcTypeHint`-Brueckenklasse muessen
  geaendert werden.
- Contra: Bricht die heute saubere Trennung „Format-Adapter ist typfrei,
  konvertiert im Bedarfsfall pro Zelle". Risiko, dass JSON/YAML/CSV-
  Roundtrips sich verhalten anders als heute.
- Contra: Verlangt eine Aenderung in jedem Quell-Adapter, der heute
  `DataReader` implementiert. Hoher Migrationsbedarf fuer ein Sub-Slice,
  das nur Parquet betrifft.

### 4.4 Option D — Separates formatseitiges Schemaobjekt vor dem ersten Chunk

Formatseitiges, JDBC-neutrales Schemaobjekt in `hexagon:ports-common` (analog
zur Modulwahl bestehender Querschnitte). JDBC-spezifische Hints bleiben im
bestehenden `JdbcTypeHint` (`adapters:driven:formats`) und werden nicht in
das Schema-Datenmodell gemischt.

```text
package dev.dmigrate.ports.common.schema  // hexagon:ports-common

data class ChunkSchema(
    val table: String,
    val columns: List<ChunkColumnSchema>,
    val origin: SchemaOrigin
)

data class ChunkColumnSchema(
    val name: String,
    val nullable: Boolean,
    val neutralType: NeutralType
)

enum class SchemaOrigin {
    JDBC_METADATA,
    SCHEMA_READER,
    MERGED,
    MANIFEST_FALLBACK,    // AP9 (2026-06-05): hinzugefuegt fuer
                          // Bundle-Importe mit schemaSource =
                          // "manifest-fallback" (AP8 §6.2 +
                          // parquet-import-input-dto.md §5).
                          // Semantisch verschieden von MERGED
                          // ("aus mehreren Quellen kombiniert");
                          // MANIFEST_FALLBACK markiert best-effort-
                          // Manifest-Typen ohne SchemaReader-/
                          // JDBC-Provenance.
}
```

`NeutralType` traegt bereits Decimal-Precision/Scale, DateTime-Timezone,
Geometry/SRID und alle ambivalenten Varianten; deshalb genuegt es als
alleinige Typ-Information im Schema. Die Aufloesung „JDBC-Metadaten +
Dialekt-`sqlTypeName` -> `NeutralType`" passiert im Export-Pipeline-Layer
(`StreamingExporter`), nicht im Schema selbst.

Der `StreamingExporter` baut `ChunkSchema` einmal pro Tabelle und uebergibt
es vor dem ersten Chunk an den Writer. Der `DataChunkWriter`-Vertrag wird
dafuer von `begin(table, columns)` auf `begin(table, schema)` umgestellt
(siehe Abschnitt 5 — kein Dual-Pfad). JSON/YAML/CSV ignorieren das
zusaetzliche Typmodell intern; Parquet macht es zur Pflicht.

- Pro: Erfuellt A1–A5 ohne den Kontrakt von `DataChunk` zu veraendern.
- Pro: Erlaubt Misch-Quelle: JDBC-Metadaten als Pflicht + `NeutralType`
  als Ergaenzung fuer ambivalente Typen.
- Pro: `SchemaOrigin` dokumentiert die Herkunft fuers Manifest (Hauptplan
  Abschnitt 6).
- Contra: Ein neuer Port. Aufwand, der nicht JSON/YAML/CSV dient — aber
  isoliert von ihnen bleibt.
- Contra: `StreamingExporter` muss eine zusaetzliche Pipeline-Stufe
  fuehren („Schema vor Chunks").

---

## 5. Vorentscheidung

Vorgeschlagen wird **Option D mit B als Primaerquelle und A als Ergaenzung**.
Konkret:

- Ein neues, exportseitiges `ChunkSchema` (JDBC-neutral, in
  `hexagon:ports-common`) wird vor dem ersten Chunk pro Tabelle erzeugt
  und an den Writer uebergeben. Der Parquet-Writer macht es zur Pflicht;
  JSON/YAML/CSV ignorieren das Typmodell intern, der gemeinsame Port
  bleibt.
- Befuellt wird `ChunkSchema` aus den **JDBC-Metadaten der Exportquery**
  (Option B). `ResultSetMetaData` liefert JDBC-Typcode, `sqlTypeName`,
  Precision/Scale und Nullability. Die Nullability-Aufloesung uebernimmt
  ein expliziter Resolver mit Provenance (Abschnitt 9), nicht
  `ResultSetMetaData.isNullable` direkt.
- Wo der `SchemaReader` (Option A) bereits ein `NeutralType` kennt, wird
  es als Primaer- oder Ergaenzungsquelle herangezogen — insbesondere fuer
  Geometry, JSON, Enum, UUID und Array, die JDBC-Metadaten dialekt-
  abhaengig nicht klar beantworten. Die Mapping-Tabelle in Abschnitt 8
  fixiert die Regeln pro Spaltentyp.
- `SchemaOrigin` ist `JDBC_METADATA`, `SCHEMA_READER` oder `MERGED` und
  wird ins Manifest geschrieben (Hauptplan Abschnitt 6, „Ursprung des
  Typmappings").
- Der `DataChunkWriter`-Vertrag wird von `begin(table, columns)` auf
  `begin(table, schema)` umgestellt — Replacement, keine Koexistenz.
  Koexistenz erzeugt Drift zwischen Writern, die das alte `columns`-
  Schema sehen, und solchen, die das neue `schema` sehen. JSON/YAML/CSV
  reduzieren `schema.columns` intern auf Name und Nullability;
  Parquet liest das volle `NeutralType`.
- Option C (Kernkontrakt von `DataChunk` aendern) wird abgelehnt: der
  Eingriff ist gross und betrifft Bereiche, die nichts mit Parquet zu tun
  haben. Sollte sich in spaeteren Format-Adaptern (Avro, ORC, Iceberg)
  das Bedarfsbild vergroessern, kann C als Folgeentscheidung mit
  Migrationsplan kommen, nicht als Vorab-Investition.

Diese Vorentscheidung ist nicht „abhaengig von AP3-Befunden", sondern
liefert AP3 einen stabilen Vertrag. Die Mapping-Tabelle (Abschnitt 8) und
der Nullability-Resolver (Abschnitt 9) gehoeren zu AP2 und werden hier
festgelegt; AP3 verifiziert sie durch Golden-Roundtrips und
Treiber-Audits.

---

## 6. Konsequenzen

### 6.1 Aenderungen in `hexagon:ports-common`

- Neue Datenklassen `ChunkSchema`, `ChunkColumnSchema` und das Enum
  `SchemaOrigin` (JDBC-neutral, vgl. Abschnitt 4.4). Bewusst nicht in
  `hexagon:ports-write`: das Schema beschreibt Spalten unabhaengig von
  Lese- oder Schreibrichtung, und Abschnitt 6.4 nutzt es symmetrisch.

### 6.2 Aenderungen in `hexagon:ports-write`

- `DataChunkWriter.begin(table, columns)` wird durch `begin(table, schema)`
  ersetzt (Replacement, keine Koexistenz). `ColumnDescriptor` bleibt im
  Kern erhalten, wird aber im Writer nicht mehr separat uebergeben;
  Format-Adapter leiten sich Name/Nullability aus `schema.columns` ab.
- Die `DataChunkWriterFactory.create(...)`-Signatur wird entsprechend
  schema-aware.

### 6.3 Aenderungen in `adapters:driven:formats`

- `DefaultDataChunkWriterFactory` reicht das Schema durch.
- JSON/YAML/CSV-Writer lesen aus `schema.columns` nur Name und Nullability
  und arbeiten zellweise weiter wie heute. PreciseDecimal-Heuristiken in
  YAML koennen das `NeutralType` spaeter konsumieren — kein Bestandteil
  dieser Aenderung.
- Neuer `ParquetChunkWriter` liest `ChunkSchema` und baut daraus ein
  `org.apache.parquet.schema.MessageType` gemaess Mapping-Tabelle
  (Abschnitt 8), plus `ParquetWriter.Builder#withExtraMetaData(...)`
  fuer den Single-File-Metadatenvertrag.

### 6.4 Aenderungen in `adapters:driven:streaming`

- `StreamingExporter` baut `ChunkSchema` vor dem ersten Chunk:
  - liest `ResultSetMetaData` aus der Exportquery;
  - holt optional vom `SchemaReader` der Quelle `NeutralType` pro Spalte;
  - wendet den Nullability-Resolver (Abschnitt 9) an;
  - bildet JDBC + sqlTypeName + optional `NeutralType` auf
    `ChunkColumnSchema.neutralType` gemaess Mapping-Tabelle ab;
  - belegt `SchemaOrigin` aus den tatsaechlich genutzten Quellen.

### 6.5 Symmetrie zum Import

`ChunkSchema` liegt bewusst in `ports-common`, damit der Importpfad
dasselbe Schemaobjekt referenzieren kann (Parquet-Bundle-Manifest liefert
`ChunkSchema` an den Import). Der bestehende `JdbcTypeHint` in
`adapters:driven:formats` bleibt unveraendert; er ist JDBC-spezifisch und
gehoert nicht ins JDBC-neutrale Schemamodell. Eine Konsolidierung
JdbcTypeHint <-> ChunkColumnSchema ist nicht Ziel dieses Sub-Slices.

---

## 7. Pragmatische Reihenfolge

AP2 ist nicht abgeschlossen, sobald die Quellenwahl steht. Der Parquet-
Prototyp (AP3) darf nicht gegen einen provisorischen Vertrag laufen, der
direkt wieder umgebaut wird. Reihenfolge:

1. **AP2.a — `ChunkColumnSchema` in `ports-common` anlegen.** Datenklassen
   und `SchemaOrigin` materialisieren, Modulplatzierung beschliessen.
2. **AP2.b — Mapping-Tabelle festschreiben.** Abschnitt 8 ist der
   Mindeststand; AP3 erweitert sie nur bei nachgewiesenem Bedarf, kuerzt
   sie nicht.
3. **AP2.c — Nullability-Resolver mit Provenance bauen.** Treiber-Audit
   PG/MySQL/SQLite gegen `ResultSetMetaData.isNullable(i)` (Abschnitt 9),
   Resolver-Regel inklusive Konfliktfall festschreiben.
4. **AP2.d — `DataChunkWriter`-Port wechseln.** `begin(table, columns)` ->
   `begin(table, schema)`; JSON/YAML/CSV mechanisch adaptieren.
5. **AP3 — Parquet-Prototyp.** Implementiert `ParquetChunkWriter` und
   `ParquetChunkReader` gegen den stabilen Port, prueft die
   Mapping-Tabelle ueber Golden-Roundtrips (PG/MySQL/SQLite plus
   DuckDB-Inspektion), keine Wert-Inferenz aus dem ersten Chunk fuer
   Schemaentscheidungen.

---

## 8. Mapping-Tabelle (AP2-Artefakt)

Die folgende Tabelle ist der bindende Mindeststand fuer den Prototyp.
Sie deckt das in Hauptplan Abschnitt 5 und 7 genannte Typvokabular ab.
AP3 erweitert sie nur, wenn ein Roundtrip-Test einen Typ unvollstaendig
abbildet. Decimal-Physik richtet sich nach Precision: INT32 bis 9, INT64
bis 18, sonst `FIXED_LEN_BYTE_ARRAY`. Temporal-Einheit ist MICROS, weil
Parquet diese in allen unterstuetzten Lesern stabil verarbeitet und der
groesste Teil der JDBC-Treiber Nanosekunden ohnehin auf Micros mappt.

| JDBC-Typcode (`java.sql.Types`) | `sqlTypeName`-Hint | NeutralType | Parquet physisch | Parquet logisch | Importtyp-Ziel |
| --- | --- | --- | --- | --- | --- |
| `BIT`, `BOOLEAN` | — | `BooleanType` | `BOOLEAN` | — | BOOLEAN |
| `TINYINT`, `SMALLINT` | — | `SmallInt` | `INT32` | `INT(16, signed)` | SMALLINT |
| `INTEGER` | — | `Integer` | `INT32` | `INT(32, signed)` | INTEGER |
| `BIGINT` | — | `BigInteger` | `INT64` | `INT(64, signed)` | BIGINT |
| `REAL` | — | `Float(SINGLE)` | `FLOAT` | — | REAL |
| `FLOAT`, `DOUBLE` | — | `Float(DOUBLE)` | `DOUBLE` | — | DOUBLE |
| `DECIMAL`, `NUMERIC` | RSMD precision/scale | `Decimal(p, s)` | `INT32` (p<=9) / `INT64` (p<=18) / `FIXED_LEN_BYTE_ARRAY` | `DECIMAL(p, s)` | DECIMAL(p, s) |
| `CHAR` | length | `Char(length)` | `BINARY` | `STRING` (UTF-8) | CHAR(length) |
| `VARCHAR`, `LONGVARCHAR` | — | `Text(maxLength?)` | `BINARY` | `STRING` (UTF-8) | VARCHAR/TEXT |
| `BINARY`, `VARBINARY`, `LONGVARBINARY` | — | `Binary` | `BINARY` | — | BYTEA/BLOB |
| `DATE` | — | `Date` | `INT32` | `DATE` | DATE |
| `TIME` | — | `Time` | `INT32` | `TIME(MICROS, isAdjusted=false)` | TIME |
| `TIME_WITH_TIMEZONE` | — | `Time` (mit Tz-Hint im NeutralType-Folge-Slice) | `INT32` | `TIME(MICROS, isAdjusted=true)` | TIMETZ |
| `TIMESTAMP` | — | `DateTime(timezone=false)` | `INT64` | `TIMESTAMP(MICROS, isAdjusted=false)` | TIMESTAMP |
| `TIMESTAMP_WITH_TIMEZONE` | — | `DateTime(timezone=true)` | `INT64` | `TIMESTAMP(MICROS, isAdjusted=true)` | TIMESTAMPTZ |
| `OTHER` | `"uuid"` | `Uuid` | `FIXED_LEN_BYTE_ARRAY(16)` | `UUID` | UUID |
| `OTHER` | `"json"`, `"jsonb"` | `Json` | `BINARY` | `JSON` | JSON/JSONB |
| `OTHER` | `"xml"` | `Xml` | `BINARY` | `STRING` (UTF-8) | XML (Text-Repraesentation) |
| `OTHER` | dialektspezifischer Geometry-Name | `Geometry(type, srid)` | `BINARY` | — (Manifest-Hint: `srid`, `geometryType`) | BYTEA mit WKB + Manifest-Hint |
| `OTHER` | dialektspezifischer Enum-Name | `Enum(values?, refType?)` | `BINARY` | `ENUM`, Fallback `STRING` | TEXT/Enum |
| `ARRAY` | element type | `Array(elementType)` | group `LIST<element>` | `LIST` | ARRAY |
| `OTHER` (Email) | — | `Email` | `BINARY` | `STRING` (UTF-8), `MAX_LENGTH=254` als Manifest-Hint | VARCHAR(254) |
| `INTEGER` (autoincrement) | — | `Identifier(autoIncrement=true)` | `INT32` | `INT(32, signed)` | INTEGER (autoIncrement im Schema-Manifest, nicht in Parquet) |

Hinweise:

- `Time_with_timezone` mit `isAdjusted=true` ist eine Konvention, die der
  Parquet-Standard nicht erzwingt; AP3 prueft das gegen DuckDB
  `read_parquet`. Falls inkompatibel, faellt das Mapping auf
  `TIMESTAMP(MICROS, isAdjusted=true)` zurueck.
- `autoIncrement` und andere Schema-Eigenschaften, die in Parquet kein
  Logical-Type-Pendant haben, landen ausschliesslich im
  Bundle-Manifest (Hauptplan Abschnitt 6), nicht in der Parquet-Datei.
- Geometry wandert als WKB-Bytestrom ins Parquet, `srid` und
  `geometryType` stehen als Footer-Key-Value-Metadaten plus
  Manifest-Eintrag. Round-trip ist nur mit Manifest verlustfrei.

---

## 9. Nullability-Resolver mit Provenance

`ChunkColumnSchema.nullable` wird durch einen kleinen Resolver bestimmt,
nicht direkt aus `ResultSetMetaData`:

```text
fun resolveNullable(
    jdbc: ResultSetMetaData.IsNullable,   // NULLABLE / NOT_NULL / UNKNOWN
    schemaReader: Boolean?
): NullabilityDecision

sealed interface NullabilityDecision {
    val nullable: Boolean
    val origin: NullabilityOrigin
    val diagnostic: NullabilityDiagnostic?
}

enum class NullabilityOrigin { JDBC_METADATA, SCHEMA_READER, MERGED_CONFLICT, DEFAULT_PERMISSIVE }
```

Regeln:

1. `jdbc == NULLABLE` und `schemaReader == null` oder `schemaReader == true`
   -> `nullable=true`, `origin=JDBC_METADATA` (bzw. `MERGED` falls Quelle
   bestaetigt).
2. `jdbc == NOT_NULL` und `schemaReader == null` oder `schemaReader == false`
   -> `nullable=false`, `origin=JDBC_METADATA` (bzw. `MERGED`).
3. `jdbc == UNKNOWN` und `schemaReader` bekannt -> Resolver folgt
   `SchemaReader`, `origin=SCHEMA_READER`. Ist `schemaReader` auch
   unbekannt, faellt der Resolver konservativ auf `nullable=true`,
   `origin=DEFAULT_PERMISSIVE` mit Diagnostic-Eintrag.
4. `jdbc` bekannt und `schemaReader` bekannt und Werte widersprechen ->
   `SchemaReader` gewinnt; `origin=MERGED_CONFLICT`; `diagnostic` traegt
   beide Werte. Der Exporter schreibt einen Warn-Log mit
   Spaltenname und beiden Quellen; der Vorgang bricht nicht ab.

Der pro-Spalte `NullabilityOrigin` bzw. `MERGED_CONFLICT` ist auf
Tabellenebene zu `SchemaOrigin` aggregierbar (Mehrheit oder
`MERGED`-Eskalation).

Treiber-Audit (AP2.c-Pflicht vor AP3):

- PostgreSQL (`org.postgresql.jdbc.PgResultSetMetaData`): liefert
  Nullable/NotNull zuverlaessig fuer normale Spalten; Joins und Views
  koennen `UNKNOWN` liefern.
- MySQL (`com.mysql.cj.jdbc.result.ResultSetMetaData`): traditionell
  zuverlaessig; AP3 prueft Versionsmatrix.
- SQLite (`org.sqlite.jdbc4.JDBC4ResultSetMetaData`): bekannt fuer
  inkonsistente Antworten je nach Schema-Quelle, Fallback auf
  `SchemaReader.nullable` ist wahrscheinlich Pflicht.

Das Audit erfolgt als gezielter Test in `adapters:driven:driver-*`
(`@Tag("schema-audit")`); Ergebnisse fliessen in einen kurzen
Nullability-Status zurueck in diesen Sub-Doc.

---

## 10. Offene Punkte fuer AP3

Nach den Vorbedingungen in Abschnitt 7 verbleiben fuer AP3 nur noch
verifikations- bzw. prototyp-getriebene Fragen:

- Golden-Roundtrip-Suite fuer PG/MySQL/SQLite gegen die Mapping-Tabelle
  in Abschnitt 8 plus DuckDB-`read_parquet`-Inspektion (Hauptplan
  Abschnitt 7).
- Ergebnis des Nullability-Treiber-Audits in den Resolver einarbeiten und
  ggf. dialektspezifische Default-Regeln nachschaerfen.
- Bundle-Manifest aus Hauptplan Abschnitt 6 mit `ChunkSchema` (inklusive
  `SchemaOrigin` und Nullability-Diagnostics) befuellen.
- Geometry/Enum-Spezifika gegen reale Quellen (PostGIS, MySQL-Enum,
  PG-Enum) verifizieren; ggf. Mapping-Tabelle ergaenzen.

---

## 11. Risiken

- `ResultSetMetaData.isNullable` bleibt treiberabhaengig konservativ.
  Solange das Audit in AP2.c nicht durchgefuehrt ist, gibt es einen
  Diagnose-Pfad, aber keinen Belastbarkeitsnachweis.
- Geometry und Arrays sind nur mit Bundle-Manifest verlustfrei rund-
  trippbar. Ohne Manifest-Hint geht `srid`, `geometryType` und der
  Array-Element-Typ verloren.
- `StreamingExporter` braucht direkten Zugriff auf
  `ResultSetMetaData`. Das gilt im heutigen Code; sollte ein Quell-
  Adapter den `DataReader` abstrakter liefern, ist die Erweiterung dort
  einzubauen, nicht im Parquet-Writer.
