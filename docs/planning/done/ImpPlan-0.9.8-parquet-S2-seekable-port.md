# S2 — SeekableDataChunkReaderFactory-Port + ResolvedTableInput-Sealed

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S2).
>
> Status: Closed (2026-06-06). Port-only — keine
> Default-Impl, der einzige produktive Implementor
> (`ParquetSeekableDataChunkReaderFactory`) wandert in S3
> zusammen mit dem Reader.

---

## 1. Scope

Drei Vertragsfestlegungen aus AP10
([`parquet-port-shape.md`](parquet-port-shape.md)):

1. **`SeekableDataChunkReaderFactory`-Port** in
   `hexagon:ports-read` (AP10 §3.1 / §4.1).
2. **`SeekableChunkSource` Sealed-Hierarchie** in
   `hexagon:ports-read` mit heute genau einem Subtyp
   `Local(path)` (AP10 §3.2).
3. **`ResolvedTableInput`-Restrukturierung** in
   `adapters:driven:streaming` von `data class` auf
   sealed parent mit `Stream` (Bestandsverhalten) und
   `Seekable` (Pfad + Preflight-Schema).

## 2. Lieferumfang

### 2.1 Neuer Port-Code

- `hexagon/ports-read/.../format/data/SeekableChunkSource.kt`
  — sealed interface mit `Local(path: Path)`. KDoc weist
  darauf hin, dass Sealed bewusst gewaehlt ist
  (Erweiterung nur im Port-Modul, AP10 §3.2 Bullet 2) und
  dass `InputStream`-Quellen explizit NICHT in eine
  Sealed-Variante uebersetzt werden
  (`parquet-libraries.md` §7 Bullet 2 — kein impliziter
  Temp-Spool).
- `hexagon/ports-read/.../format/data/SeekableDataChunkReaderFactory.kt`
  — Interface mit `create(format, source, table, schema,
  chunkSize, options)`-Signatur per AP10 §4.1.
  `ChunkSchema` ist Pflichtparameter (AP10 §3.3),
  `FormatReadOptions` default-konstruiert.

### 2.2 Streaming-Modul

- `adapters/driven/streaming/.../ResolvedTableInput.kt` neu
  (Type aus `ImportInputResolver.kt` extrahiert).
  `sealed class` mit zwei `internal data class`-Subtypen:
  - `Stream(table, openInput: () -> InputStream)` —
    Bestandsverhalten fuer JSON/YAML/CSV.
  - `Seekable(table, source: SeekableChunkSource, schema:
    ChunkSchema)` — neuer Pfad fuer Parquet; das Schema
    kommt vom Preflight (AP10 §3.3), nicht aus dem
    Datei-Footer.
- `adapters/driven/streaming/.../ImportInputResolver.kt`:
  Rueckgabetyp narrow auf
  `List<ResolvedTableInput.Stream>` (alle bestehenden
  Aufrufpfade Stdin / SingleFile / Directory produzieren
  Stream); Konstruktor-Aufrufe `ResolvedTableInput(...)`
  → `ResolvedTableInput.Stream(...)`. Die Sealed-
  Vater-Klasse wird heute von keinem produktiven Pfad
  als Rueckgabe verwendet; der `Seekable`-Pfad arbeitet
  AP12 §5.1/§7.3 zufolge ueber dedizierte Bundle-/
  Single-File-Resolver in S5a/S5b.
- `adapters/driven/streaming/.../TableImporter.kt`:
  `TableImportParams.tableInput` narrow auf
  `ResolvedTableInput.Stream`. Damit bleibt der heutige
  `.openInput()`-Code unveraendert; `Seekable`-Konsum ist
  S7.
- `adapters/driven/streaming/.../StreamingImporter.kt`:
  ungeaendert — `discoveredInputs` ist via Resolver
  bereits `List<ResolvedTableInput.Stream>`, wird direkt
  an `TableImporter` durchgereicht.

### 2.3 Test-Fakes

Zwei Konstruktor-Aufrufe in Streaming-Tests umgestellt:
- `adapters/driven/streaming/src/test/.../TableImporterTest.kt`
  (zwei Calls)
- `adapters/driven/streaming/src/test/.../TableImporterCancelCheckpointTest.kt`
  (ein Call)

`ResolvedTableInput("users") { ... }` →
`ResolvedTableInput.Stream("users") { ... }`. Keine
weiteren Test-Anpassungen noetig.

## 3. Was bewusst NICHT in S2 ist

- **Keine `ParquetSeekableDataChunkReaderFactory`-Impl**.
  Lebt nach AP10 §4.2 / Umbrella §3 in
  `adapters:driven:formats-parquet` und kommt in S3
  zusammen mit `ParquetChunkReader`.
- **Kein produktiver `ResolvedTableInput.Seekable`-Erzeuger**.
  Der `ImportInputResolver` produziert weiterhin nur
  Stream. Bundle-/Single-File-Resolver (S5a/S5b)
  erweitern den Sealed-Pfad spaeter.
- **Kein `TableImporter`-Konsum von `Seekable`**. Das ist
  S7.
- **Kein Footer-vs-Schema-Konsistenzcheck** (AP10 §3.3
  Mitte). Lebt im konkreten Reader und wird in S3
  geliefert.

## 4. Sealed-`when`-Sweep-Status

`make parquet-sweep` zeigt:

- `SeekableChunkSource` direct `is`-Checks: **leer**
  (kein Konsument bislang — erst S3 `ParquetSeekable…`
  oder S5a/S5b verbrauchen `Local`).
- `ResolvedTableInput` ist im Sweep nicht abgedeckt
  (Sealed-Hierarchie streaming-intern, AP12 §8 listet
  nur die port-level Hierarchien). Konsumenten sind
  heute auf `ResolvedTableInput.Stream` typisiert, ein
  exhaustive `when` ist nicht noetig.

Wenn S5a/S5b den Sealed-Vater-Typ aktiv konsumieren,
sollte AP12 §8 um eine `ResolvedTableInput`-Sweep-
Zeile erweitert werden (Befund-Rueckspiel-Kandidat).

## 5. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Port + Sealed-Quelle in `ports-read` | `ls hexagon/ports-read/src/main/kotlin/dev/dmigrate/format/data/Seekable*.kt` | beide Dateien vorhanden |
| `ResolvedTableInput`-Sealed in streaming | `ls adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/ResolvedTableInput.kt` | vorhanden |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL (2m 4s) |
| Streaming-Tests gruen | `make docker-test MODULES=":adapters:driven:streaming"` | BUILD SUCCESSFUL (26s) |
| Sealed-Sweep ohne Befund-Drift | `make parquet-sweep` | `SeekableChunkSource` leer (erwartet); `DataExportFormat` unveraendert |

## 6. Folgeaufgaben

- **S10a** (Dependency-Hygiene): naechster Slice — schaerft
  `formats-parquet` Constraints fuer `org.apache.avro:avro`
  und erzeugt das Footprint-Inventar als 1.0.0-Input.
- **S3**: `ParquetChunkReader`/`ParquetChunkWriter` plus
  `ParquetSeekableDataChunkReaderFactory` (Default-Impl
  des in S2 angelegten Ports) plus
  `DataExportFormat.PARQUET`-Erweiterung plus
  Default-Factory-Contract-Branches.
- **AP12 §8 Befund-Rueckspiel-Kandidat**:
  `ResolvedTableInput`-Sweep-Zeile ergaenzen, sobald der
  Sealed-Vater-Typ produktiv konsumiert wird (S5a/S5b/S7).
