# S3 — ParquetChunkReader/Writer + Factories + Enum-Erweiterung

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S3).
>
> Status: Closed (2026-06-06). Schwerster Slice — produktive
> Reader-/Writer-Pfade plus Default-Factory-Contract-Branches
> plus Enum.

---

## 1. Scope

Per Umbrella §3 S3-Cell, in einem Slice:

1. `DataExportFormat.PARQUET`-Enum-Erweiterung in
   `hexagon:ports-common`.
2. **Contract-Branches** in
   `DefaultDataChunkReaderFactory`/`WriterFactory`
   (dauerhafte Domain-Aussage, kein Stopgap; AP12 §5.2
   bindend: Default-Factory bleibt Hadoop-/Parquet-frei).
3. Produktiver `ParquetChunkReader` + `ParquetChunkWriter`
   (AP3-Spike-Linie + AP2 §6.1 + AP10 §3.3) im Modul
   `adapters:driven:formats-parquet`.
4. Drei Factory-Klassen in demselben Modul:
   - `ParquetChunkWriterFactory` (implementiert
     `DataChunkWriterFactory`).
   - `ParquetSeekableDataChunkReaderFactory`
     (implementiert `SeekableDataChunkReaderFactory` aus
     S2).
   - Eine `ParquetChunkReaderFactory` fuer den stream-
     basierten `DataChunkReaderFactory`-Port ist
     **bewusst nicht** angelegt, weil Parquet per AP10 §3.2
     den seekbaren Pfad fordert
     (`parquet-libraries.md` §7 Bullet 2: kein impliziter
     Temp-Spool). Der Stream-Pfad ist via Default-Factory-
     Contract-Branch geschuetzt.
5. **Sealed-`when (format)`-Sweep** komplett. Default-
   Factories sind die einzigen produktiven Stellen.

## 2. Lieferumfang

### 2.1 Enum + Contract-Branches

- `hexagon/ports-common/.../DataExportFormat.kt`: neue
  Variante `PARQUET("parquet", listOf("parquet"))`.
- `adapters/driven/formats/.../DefaultDataChunkReaderFactory.kt`:
  `PARQUET -> error("DefaultDataChunkReaderFactory does not
  support Parquet; Parquet reads go through
  StreamingImporter's seekableReaderFactory
  (ParquetSeekableDataChunkReaderFactory)")`.
- `adapters/driven/formats/.../DefaultDataChunkWriterFactory.kt`:
  `PARQUET -> error("DefaultDataChunkWriterFactory does not
  support Parquet; use ParquetChunkWriterFactory via the
  CLI CompositeDataChunkWriterFactory")`.

Symmetrisch zu AP12 §5.2
`ParquetChunkWriterFactory.create`'s
`require(format == PARQUET)`.

### 2.2 Mapping-Layer (formats-parquet)

- `ChunkSchemaToParquetMessageType.kt`: pure Funktion
  `ChunkSchema -> MessageType` per AP2 §8 Mapping-Tabelle.
  Deckt alle 19 `NeutralType`-Varianten ab; Decimal-Physik
  per Precision-Verzweigung (INT32 <= 9, INT64 <= 18, sonst
  `FIXED_LEN_BYTE_ARRAY`); Temporal-Einheit MICROS.
  Geometry/Enum/Array sind minimal als BINARY abgelegt
  (semantische Details ueber Manifest/Footer-KV — AP3
  erweitert die Tabelle bei Bedarf per AP2 §10).
- `ParquetGroupValueWriter.kt`: schreibt Spaltenwerte pro
  Row in eine Parquet-`Group`. Akzeptiert JDBC-getypte
  Eingangswerte (`Int`/`Long`/`String`/`Boolean`/
  `BigDecimal`/`LocalDate`/`LocalTime`/`Instant`/`UUID`/
  `ByteArray`/...); Null-Werte werden uebersprungen
  (Parquet-OPTIONAL).
- `ParquetGroupValueReader.kt`: liest Spaltenwerte zurueck
  ins neutrale Wertemodell, symmetrisch zum Writer.

### 2.3 Stream-zu-OutputFile-Adapter

- `OutputStreamOutputFile.kt`: wickelt einen `OutputStream`
  in das Parquet-eigene `OutputFile`-Interface (AP10 §3.4
  / AP12 §5.2). Adapter-`PositionOutputStream` zaehlt die
  geschriebenen Bytes als `getPos()` und schliesst den
  unterliegenden Stream **nicht** — Lifetime gehoert dem
  `DataChunkWriter.close()`-Aufrufer.

### 2.4 Produktive Reader/Writer

- `ParquetChunkWriter.kt`: implementiert `DataChunkWriter`.
  `begin(table, schema)` baut `MessageType` via
  `ChunkSchemaToParquetMessageType`, initialisiert
  `ExampleParquetWriter` mit `OutputStreamOutputFile` und
  GZIP-Codec. Schreibt Row pro Row als
  `SimpleGroupFactory.newGroup()`. `end()` no-op (Parquet
  hat keinen Container-End-Marker; Footer schreibt der
  Writer beim `close`).
- `ParquetChunkReader.kt`: implementiert `DataChunkReader`.
  Konstruktor fuehrt den AP10 §3.3-Konsistenzcheck durch
  (Footer-`MessageType` vs. uebergebenes `ChunkSchema`:
  Namens-/Anzahlpruefung); wirft bei Drift
  `ParquetSchemaMismatchException` mit
  `BUNDLE_SCHEMA_PARQUET_MISMATCH`-Diagnose. `nextChunk()`
  liest bis zu `chunkSize` Rows; Werte werden ueber
  `ParquetGroupValueReader` typisiert.

### 2.5 Factories

- `ParquetChunkWriterFactory.kt`: `require(format ==
  PARQUET)`, gibt `ParquetChunkWriter` zurueck.
- `ParquetSeekableDataChunkReaderFactory.kt`:
  `require(format == PARQUET)` + `require(schema.table ==
  table)`. `when (source) { is Local -> source.path }`
  ist der einzige existierende Sealed-`SeekableChunkSource`-
  `is`-Check im Repo (bewusst — neue Subtypen brechen den
  `when` und triggern den Sealed-Sweep).

### 2.6 Smoke-Tests

`adapters/driven/formats-parquet/src/test/.../ParquetChunkRoundTripTest.kt`:

- Round-Trip-Test mit 6 NeutralType-Varianten (Integer,
  Text, BooleanType, Decimal(10,2), Date, DateTime), 2
  Rows inkl. Null-Werten. Verifiziert: Werte kommen
  byte-identisch zurueck (BigDecimal mit korrekter Scale,
  LocalDate, Instant).
- Schema-Mismatch-Test: Writer schreibt 1-Spalten-Schema,
  Reader baut mit 2-Spalten-Schema -> wirft
  `ParquetSchemaMismatchException` mit
  `BUNDLE_SCHEMA_PARQUET_MISMATCH`.
- Default-Factory-Contract-Branch-Test: beide Factories
  werfen die erwarteten Messages.

## 3. AP3-Spike-Behandlung

Per Umbrella §3.3 (Spike-Code-Behandlung):

- Spike-Modul bleibt unangetastet
  (`adapters/driven/formats-parquet/src/main/kotlin/.../spike/ParquetSpike.kt`).
- AP3/AP4/AP5/AP6-Spike-Tests
  (`ParquetSpike*Test.kt`) bleiben aktiv als
  Smoke-Modul-Linie. Sie laufen weiterhin gegen den
  Spike-Pfad; produktive Klassen existieren parallel
  daneben und werden vom neuen `ParquetChunkRoundTripTest`
  abgedeckt.
- Aufhebung des Spike-Pfads pro Spike-Test ist optional —
  S3 zieht ihn nicht ein. Sollte ein spaeterer Slice
  (z.B. S10b Native-Image-Befund) Reflection-Konfigurationen
  pro Test einfuehren, kann dann pro Test entschieden
  werden.

## 4. Sealed-`when`-Sweep nach S3

`make parquet-sweep`:

- `SeekableChunkSource` — 1 Treffer (genau die Stelle in
  `ParquetSeekableDataChunkReaderFactory.kt:44`), wie
  vorgesehen.
- `DataExportFormat` — 2 Treffer (beide Default-Factories),
  beide exhaustive mit PARQUET-Contract-Branch.
- Keine neuen `ImportInput`-/`SchemaOrigin`-/
  `CheckpointOperationSpecifics`-Treffer.

## 5. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Enum erweitert | `grep PARQUET hexagon/ports-common/.../DataExportFormat.kt` | Variante vorhanden |
| Default-Factories Contract-Branch | `grep error.*Parquet adapters/driven/formats/.../Default*Factory.kt` | beide vorhanden |
| Default-Factories ohne Parquet-/Hadoop-Imports | `grep -r "import.*parquet\\|import.*hadoop" adapters/driven/formats/src/main` | leer |
| `formats/build.gradle.kts` ohne Parquet-/Hadoop-Deps | `grep -i parquet\\|hadoop adapters/driven/formats/build.gradle.kts` | nur Kommentar (S0b-Bridge-Note), keine Dependency |
| Produktive Klassen existieren | `ls adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/*.kt` | 7 Dateien (Reader/Writer/Factories + Mapping/Adapter) |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Parquet-Modultest gruen | `make docker-test MODULES=":adapters:driven:formats-parquet"` | BUILD SUCCESSFUL (26s); Round-Trip + Schema-Mismatch + Contract-Branch alle gruen |
| Sealed-Sweep | `make parquet-sweep` | nur erwartete Treffer (siehe §4) |

## 6. Bewusst NICHT in S3

- **Keine `ParquetChunkReaderFactory`** (Stream-basierter
  Reader-Factory-Port). Parquet ist ausschliesslich
  seekbar; der Stream-Pfad wird per Default-Factory-
  Contract-Branch geblockt.
- **Kein `--no-checkpoint`-Wiring** (S5b/S6).
- **Kein CLI-`--format parquet`** (S6).
- **Kein Bundle-/Manifest-Pfad** (S3b/S5a).
- **Kein Single-File-Footer-KV** (S4).
- **Kein Native-Image-Smoketest** (S10b).
- **Keine Geometry/Enum/Array-Mapping-Erweiterungen** —
  S3 mappt minimal auf BINARY mit Stringform-Roundtrip;
  AP3-Mapping-Tabellen-Erweiterung kann das spaeter
  praezisieren (AP2 §10).

## 7. Folgeaufgaben

- **S10b** (Native-Image-Befund): Sondierungslauf gegen
  die in S3 erstellten produktiven Klassen + S10a-
  Constraints.
- **S3b**: `ParquetManifestWriter` +
  `StreamingExporter`-Bundle-Closure (AP7 §10.1;
  `--manifest-sha256` opt-in).
- **AP3-Mapping-Tabellen-Erweiterung** (AP2 §10): falls
  Roundtrip-Tests in S9a/S9b Lucken aufzeigen,
  spezifische Geometry/Enum/Array-Behandlung
  implementieren.
