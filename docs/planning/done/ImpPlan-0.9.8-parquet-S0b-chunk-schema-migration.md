# S0b — JDBC-Mapping + Nullability-Resolver + DataChunkWriter-Migration

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S0b).
>
> Status: Closed (2026-06-06). Folgt auf S0 (Typanlage); macht
> die `DataChunkWriter.begin(table, columns)`-Migration auf
> den schemaorientierten Vertrag aus AP2 §6.2.

---

## 1. Scope

Drei AP2-Teilstuecke kombiniert (AP2.b + AP2.c + AP2.d), weil
sie zusammen das `DataChunkWriter.begin(table, schema)`
operativ tragen:

1. **AP2.b — JDBC→`NeutralType`-Mapping** per AP2 §8
   Mapping-Tabelle.
2. **AP2.c — Nullability-Resolver** mit Provenance per AP2 §9.
3. **AP2.d — `DataChunkWriter.begin`-Vertragswechsel** plus
   JSON/YAML/CSV-Writer-Anpassung plus `StreamingExporter`/
   `TableExporter`-Schema-Plumbing.

## 2. Architektur-Entscheidung — Schema auf `ChunkSequence`

AP2 §6.4 legt fest, dass der `StreamingExporter` `ChunkSchema`
**vor** dem ersten Chunk baut. Der `DataReader`-Port liefert
heute aber eine `ChunkSequence`, die einen offenen
`ResultSet` haelt — JDBC-Metadaten sind dort verfuegbar,
nicht im Exporter.

**Entscheidung**: `ChunkSequence` traegt eine neue
`val schema: ChunkSchema`-Property (Pflicht). `JdbcChunkSequence`
befuellt sie im Konstruktor (eager aus `rs.metaData`);
`TableExporter` liest `seq.schema` und reicht es an
`writer.begin(table, schema)` durch.

Alternativen verworfen:
- `DataChunk.schema: ChunkSchema` (75 Konstruktions-Stellen
  betroffen, JSON/YAML/CSV-Reader haetten Placeholder
  liefern muessen).
- Neue Port-Methode `DataReader.readSchema(...)` (haette
  einen zweiten ResultSet/Statement-Lifecycle erfordert,
  zusaetzliche Connection-Borrows).

## 3. Lieferumfang

### 3.1 Neue Klassen

- `adapters/driven/driver-common/.../data/JdbcToNeutralTypeMapper.kt`
  — pure `object` mit `map(jdbcType, sqlTypeName, precision,
  scale, isAutoIncrement)`-Funktion. Deckt die AP2 §8
  Mapping-Tabelle ab; unbekannte JDBC-Codes fallen auf
  `NeutralType.Text` zurueck.
- `adapters/driven/driver-common/.../data/NullabilityResolver.kt`
  — pure `object` mit `resolve(jdbcIsNullable,
  schemaReaderNullable)`-Funktion. Implementiert AP2 §9
  Regeln 1-4 inkl. `MERGED_CONFLICT`-Diagnostic. Neue
  Typen: `data class NullabilityDecision` +
  `enum class NullabilityOrigin`
  (`JDBC_METADATA`/`SCHEMA_READER`/`MERGED`/
  `MERGED_CONFLICT`/`DEFAULT_PERMISSIVE`).

### 3.2 Port-Aenderung

- `hexagon/ports-read/.../driver/data/ChunkSequence.kt`:
  neue `val schema: ChunkSchema`-Property. Vertrag-KDoc
  weist auf AP2 §6.4 hin.
- `hexagon/ports-write/.../format/data/DataChunkWriter.kt`:
  `fun begin(table, columns: List<ColumnDescriptor>)` ->
  `fun begin(table, schema: ChunkSchema)`. Vertrag-KDoc auf
  AP2 §6.3 ausgerichtet.

### 3.3 Produktive Implementierungen

- `adapters/driven/driver-common/.../JdbcChunkSequence.kt`:
  `init`-Block zieht `ResultSetMetaData` einmalig, baut
  `List<ColumnDescriptor>` (bestehend) **und** `ChunkSchema`
  (neu) parallel; verwendet `JdbcToNeutralTypeMapper` +
  `NullabilityResolver`.
- `adapters/driven/streaming/.../TableExporter.kt`:
  `writer.begin(table, chunk.columns)` -> `writer.begin(table,
  seq.schema)`.
- `adapters/driven/formats/.../json/JsonChunkWriter.kt`,
  `.../csv/CsvChunkWriter.kt`,
  `.../yaml/YamlChunkWriter.kt`: `begin`-Signatur, lesen
  intern `schema.columns` und reduzieren auf Name +
  Nullability (AP2 §6.3 — `neutralType` ignoriert).

### 3.4 Test-Bruecke

- `hexagon/ports-common/src/testFixtures/.../format/data/ChunkSchemaFixtures.kt`:
  Helper `chunkSchemaOf(table, columns, origin?,
  neutralTypes?)` baut `ChunkSchema` aus
  `List<ColumnDescriptor>` mit `NeutralType.Text` als
  Default. Genutzt von ~15 Test-Fakes als minimale
  `ChunkSequence.schema`-Quelle.
- `hexagon/ports-write/src/testFixtures/.../format/data/DataChunkWriterTestExtensions.kt`:
  Extension `DataChunkWriter.begin(table, columns:
  List<ColumnDescriptor>)` delegiert auf
  `begin(table, chunkSchemaOf(...))`. ~25 Bestandstests
  rufen damit weiter die alte Signatur und werden
  mechanisch durchgereicht.
- `hexagon/ports-write/build.gradle.kts` hat neu das
  `java-test-fixtures`-Plugin.

### 3.5 Test-Fakes angepasst

`ChunkSequence`-Fakes (`schema`-Property hinzu):

- `hexagon/ports-read/.../test/.../ResumeMarkerTest.kt`
- `hexagon/application/.../test/DataTransferRunnerTest.kt`
  (Klasse `EmptyChunkSequence` + zwei anonyme Sequences)
- `hexagon/application/.../test/TransferExecutorCancelCheckpointTest.kt`
- `adapters/driven/streaming/.../test/StreamingExporterTestPart2.kt`
  (FakeChunkSequence)

`DataChunkWriter`-Fakes (`begin`-Signatur):

- 4 anonyme Writer-Klassen in
  `StreamingExporterTestPart2.kt`
  (`RecordingChunkWriterFactory`,
  `RealStreamClosingChunkWriterFactory`,
  `FailingBeginChunkWriterFactory`,
  `FailingWriteChunkWriterFactory`).

Importe der Bridge-Extension in 6 Format-Writer-Tests:
- `CsvChunkWriterTest.kt`
- `JsonChunkWriterTest.kt`
- `JsonChunkWriterEdgeCaseTest.kt`
- `YamlChunkWriterTest.kt`
- `GoldenMasterRoundTripTest.kt`
- `H3NullRowPropertyTest.kt`

### 3.6 Modul-Dependencies

- `hexagon/application/build.gradle.kts`:
  `testImplementation(testFixtures(project(":hexagon:ports-write")))`
- `adapters/driven/streaming/build.gradle.kts`: dito plus
  `testImplementation(testFixtures(project(":hexagon:ports-common")))`
- `adapters/driven/formats/build.gradle.kts`: dito beide

### 3.7 Tests fuer neue Mapper

- `adapters/driven/driver-common/src/test/.../JdbcToNeutralTypeMapperTest.kt`
  — 12 Test-Cases, deckt alle JDBC-Branches der
  Mapping-Tabelle plus den Text-Fallback fuer unbekannte
  Codes.
- `adapters/driven/driver-common/src/test/.../NullabilityResolverTest.kt`
  — 10 Test-Cases, deckt alle 3×3 Kombinationen aus
  `JdbcNullable` x `schemaReaderNullable` plus
  unbekannten JDBC-Wert.

## 4. Was bewusst NICHT in S0b ist

- **Kein Treiber-Audit fuer PG/MySQL/SQLite** (AP2 §9
  Schluss). Bleibt eigenstaendiger
  `@Tag("schema-audit")`-Test in den
  `driver-*`-Adaptern (AP2.c-Folgeaufgabe).
- **Kein `SchemaReader`-Integration im
  `StreamingExporter`**. `JdbcChunkSequence` ruft
  `NullabilityResolver.resolve(jdbcRaw)` ohne
  `schemaReaderNullable`; alle Resolver-Eintraege
  laufen aktuell ueber `JDBC_METADATA` oder
  `DEFAULT_PERMISSIVE`. Hooking-Point fuer den
  `SchemaReader` ist im JdbcChunkSequence vorhanden
  (Default-Param), aber kein produktiver Aufrufer.
- **Kein `SchemaOrigin.MERGED_CONFLICT`-Logging im
  Exporter** (AP2 §9 Regel 4). Wird mit dem
  Treiber-Audit nachgezogen.
- **Keine Mapping-Tabellen-Erweiterungen fuer Geometry/
  Enum** (AP2 §8 Hinweise). Faellt bewusst zurueck auf
  `Text`, weil dialektspezifische Erkennung in AP3
  hineingehoert.

## 5. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Mapper + Resolver in `driver-common` | `ls adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/JdbcToNeutralTypeMapper.kt NullabilityResolver.kt` | Beide vorhanden |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL (401 actionable tasks, 1m 34s) |
| Modul-Tests gruen | `make docker-test MODULES=":adapters:driven:formats :adapters:driven:streaming"` | BUILD SUCCESSFUL (26s) |
| Kover-Gate `driver-common` >= 90% | (Implizit in docker-check) | OK — Mapper- und Resolver-Tests decken alle Branches |
| Sealed-Sweep ohne Befund-Drift | `make parquet-sweep` | Keine neuen Treffer auf Sealed-Hierarchien |

## 6. Folgeaufgaben

- **S2**: `SeekableDataChunkReaderFactory`-Port +
  `ResolvedTableInput`-Sealed-Restrukturierung (Umbrella
  §3).
- **AP2.c Treiber-Audit**: gezielter `@Tag("schema-audit")`
  Test pro Driver-Adapter; Ergebnisse fliessen als
  dialektspezifische Defaults zurueck.
- **Befund-Rueckspiel-Kandidat fuer AP2 §9**: AP2-Doku
  beschreibt `NullabilityDecision` als `sealed interface`;
  S0b-Implementierung benutzt `data class` (linear, keine
  origin-spezifischen Felder noetig). Wird in einem
  spaeteren Slice nach AP2 §9 zurueckgespielt, falls die
  Sealed-Hierarchie aus AP3-Befunden gefordert wird.
