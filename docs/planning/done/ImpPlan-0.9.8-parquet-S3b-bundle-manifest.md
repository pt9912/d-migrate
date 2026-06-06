# S3b — ParquetManifestWriter + Bundle-Closure-Hook

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S3b).
>
> Status: Closed (2026-06-06). Bundle-Export-Manifest-Pfad
> komplett — `manifest.yaml` wird beim `FilePerTable`-Export
> automatisch geschrieben, sobald der Wiring-Pfad (S6) den
> `ParquetBundleClosure`-Hook auf `StreamingExporter.export()`
> setzt.

---

## 1. Scope

Per Umbrella §3 S3b-Cell:

> Bundle-Export schreibt `manifest.yaml` nach allen Tabellen;
> `ExportOutput.FilePerTable` um Closure-Hook erweitert.

Vier Aufgaben:

1. `ParquetBundleManifest`-Datentypen + YAML-Repraesentation
   per AP7 §5.1/§5.2/§5.4.
2. `ParquetManifestWriter` mit deterministischer
   Key-Reihenfolge und `snakeyaml-engine` 2.7.
3. Optionaler SHA-256-Streaming-Digest pro Tabelle
   (`--manifest-sha256` Wiring kommt in AP12 §4 / S6).
4. `StreamingExporter`-Bundle-Closure-Hook:
   `onBundleClosure: (BundleClosureContext) -> Unit`-
   Parameter auf `export()`, der nach Abschluss aller
   Tabellen einer `FilePerTable`-Operation aufgerufen wird.

## 2. Architektur

### 2.1 Hook-Trennung statt Direkt-Wiring

`StreamingExporter` lebt in `:adapters:driven:streaming` und
darf **keine** `formats-parquet`-Dependency haben (Bundle-
Closure ist Parquet-spezifisch, nicht generisch). Die
Loesung:

- **Generischer Hook** im streaming-Modul:
  `BundleClosureContext` + `BundleClosureTable`. Wird
  ausschliesslich bei `ExportOutput.FilePerTable` und nur
  nach Abschluss aller Tabellen aufgerufen.
- **Parquet-spezifische Hook-Implementierung** in
  `formats-parquet`:
  `ParquetBundleClosure(producerVersion, manifestSha256,
  clock)`. Implementiert `(BundleClosureContext) -> Unit`
  via Konvention (`invoke`-Operator).
- **Wiring (S6)**: CLI-Adapter instanziiert
  `ParquetBundleClosure` mit der CLI-Version und reicht ihn
  an `StreamingExporter.export()` durch. JSON/YAML/CSV-Pfade
  lassen den Default-Hook (`{}`) stehen.

Das vermeidet die in S2/S5a/S5b-Reviews aufgeworfene
Modulgrenzen-Verletzung und haelt
`StreamingExporter` formatneutral.

### 2.2 ChunkSchema-Durchreichung via internem Result

Der Hook braucht das `ChunkSchema` pro Tabelle (fuer
`manifest.yaml:tables[].columns`). Eine erste Variante hatte
das Feld auf der Public-API
`TableExportSummary.schema: ChunkSchema?` gehaengt — Review
zeigte: ~12 Bestandstests brachen, weil sie Summary-Objekte
positional konstruieren oder per `shouldContainExactly`
vergleichen.

Loesung: neuer **interner** `TableExportInternalResult`-Typ
im streaming-Modul, der `summary: TableExportSummary` plus
`schema: ChunkSchema?` traegt. `TableExporter.export()`
gibt das interne Result zurueck; `StreamingExporter`
schreibt nur das `summary`-Feld in `ExportResult.tables`
(Public-API unveraendert) und nutzt `schema` ausschliesslich
fuer den Bundle-Closure-Hook.

## 3. Lieferumfang

### 3.1 Datentypen (formats-parquet, package `manifest`)

- `ParquetBundleManifest` — Top-Level-Record mit
  `formatVersion` (`"1.0"`), `producer` (`"d-migrate"`),
  `producerVersion`, `exportedAt`, `schemaSource`,
  `tables`.
- `ManifestTable`, `ManifestColumn`, `ManifestNeutralType`,
  `ManifestSchemaSource` — Datenklassen mit dem
  Cross-Mapping `SchemaOrigin.MERGED -> jdbc-metadata`
  (AP7 §5.3 kennt nur drei Werte; `MERGED` ist
  d-migrate-intern, nicht im Manifest).

### 3.2 Konversion

- `ChunkSchemaToManifest`: `ChunkSchema -> List<ManifestColumn>`.
  Behandelt alle 19 `NeutralType`-Varianten in vier
  Gruppen (`toNumeric`/`toTextLike`/`toTemporal`/
  `toStructured`-Helfer) — kein
  `CyclomaticComplexMethod`-Detekt-Treffer.

### 3.3 Writer + SHA-256

- `ParquetManifestWriter`: serialisiert
  `ParquetBundleManifest` ueber `snakeyaml-engine` 2.7 in
  BLOCK-Flow mit `linkedMapOf` fuer stabile Key-Reihenfolge
  (`formatVersion`, `producer`, `producerVersion`,
  `exportedAt`, `schemaSource`, `tables`).
- `Sha256DigestCalculator`: streaming-`MessageDigest`-
  Lauf ueber den fertigen Parquet-Datei-Inhalt; liefert
  64-Zeichen-Lowercase-Hex ohne `sha256:`-Praefix
  (AP7 §7.3).

### 3.4 Streaming-Hook

- `BundleClosureContext` + `BundleClosureTable` (neu im
  streaming-Modul): traegt `directory`, `format`,
  `List<BundleClosureTable>` mit `(table, file, schema,
  rowCount)`.
- `StreamingExporter.export()` neuer Pflicht-Default-
  Parameter `onBundleClosure: (BundleClosureContext) -> Unit
  = {}`; nur bei `ExportOutput.FilePerTable` aufgerufen,
  und nur wenn mindestens eine Tabelle exportiert wurde.
  Detekt-`LongMethod`-Treffer wurde durch Auslagern in
  `exportFilePerTable(...)` geheilt.
- `TableExportInternalResult` (intern) statt Schema-Feld
  auf `TableExportSummary`.

### 3.5 Parquet-Hook

- `ParquetBundleClosure(producerVersion, manifestSha256,
  clock)`: implementiert die Hook-Signatur via `invoke`.
  Schreibt `manifest.yaml` ins Bundle-Wurzelverzeichnis.
  Klockwise testbar (deterministische `exportedAt`-Zeit).

### 3.6 Tests

`adapters/driven/formats-parquet/src/test/.../ParquetBundleClosureTest.kt`:

- Round-Trip mit 2 Tabellen, 2 NeutralType-Varianten
  (BigInteger, Decimal(5,2), Float(DOUBLE)) plus
  `--manifest-sha256` aktiv -> Manifest enthaelt alle
  AP7-§5-Pflichtfelder, Lowercase-Hex-64-SHA-256,
  `kind`-Diskriminator pro NeutralType.
- Non-Parquet-Format ruft den Hook (Default `{}`) auf —
  keine `manifest.yaml`, idempotent.
- `ManifestSchemaSource.fromSchemaOrigin(MERGED) ==
  JDBC_METADATA` (AP7 §5.3 nur drei Werte).
- `Sha256DigestCalculator` ist 64-Zeichen-Hex-lowercase.
- `ChunkSchemaToManifest`-Roundtrip fuer Boolean/Integer/
  Text(maxLength)/DateTime(timezone).

## 4. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Manifest-Datentypen + Writer in `formats-parquet/manifest` | `ls adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/manifest/` | 5 Dateien |
| Bundle-Closure-Hook in streaming | `ls adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/BundleClosure.kt` | vorhanden |
| `StreamingExporter` formatneutral (kein Parquet-Import) | `grep -r "format.parquet" adapters/driven/streaming/src/main` | leer |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Modultest gruen | `make docker-test MODULES=":adapters:driven:formats-parquet"` | BUILD SUCCESSFUL; 8 neue Tests gruen |
| AP7 §10.1-Konsequenzen umgesetzt | Code-Verweise: `ParquetBundleManifest`, `ParquetManifestWriter`, Hook-Wiring | abgedeckt; `ParquetBundleReader` + `ParquetBundlePreflight` ist S5a |

## 5. Bewusst NICHT in S3b

- **Kein `ParquetManifestReader`/`ParquetBundlePreflight`**
  (Reader-Seite). Lebt in S5a (AP7 §9 + AP8).
- **Kein CLI-Wiring** des Hooks (`--manifest-sha256` Flag,
  CompositeWriterFactory). Lebt in S6.
- **Kein automatisches `--no-checkpoint`-Wiring**. Lebt in
  S5b/S6.
- **Keine Single-File-Footer-KV** (AP11). Lebt in S4.

## 6. Folgeaufgaben

- **S4** (Single-File-Footer-KV): `manifest.yaml`-Konventionen
  spiegeln teilweise im Parquet-Footer (AP11).
- **S5a** (Bundle-Reader): `ParquetManifestReader` +
  `ParquetBundlePreflight` lesen die `manifest.yaml`, die
  S3b geschrieben hat.
- **S6** (CLI-Wiring): instanziiert `ParquetBundleClosure`
  mit `producerVersion`/`manifestSha256` aus den
  CLI-Flags und reicht ihn an
  `StreamingExporter.export()` durch.
