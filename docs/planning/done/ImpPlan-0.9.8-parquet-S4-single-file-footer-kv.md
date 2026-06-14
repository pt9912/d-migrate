# S4 — ParquetSingleFileManifest{Writer,Reader} + Preflight Phase-1/2

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S4).
>
> Status: Closed (2026-06-06). Footer-KV `d-migrate.manifest`
> Round-Trip lesend/schreibend; Phase-1/2-Trennung produktiv.

---

## 1. Scope

Per Umbrella §3 S4-Cell und AP11 §6/§7.1:

1. `ParquetSingleFileManifestWriter` —
   serialisiert `ChunkSchema` als
   `d-migrate.manifest`-YAML, liefert eine Provider-Lambda
   fuer den `ParquetChunkWriter`-Konstruktor.
2. `ParquetSingleFileManifestReader` — parst das Footer-KV
   und gibt `ChunkSchema` oder `null` (Key fehlt) zurueck.
3. `ParquetChunkWriter` um optionalen
   `extraMetaDataProvider: (ChunkSchema) -> Map<String,
   String>` erweitert (AP10 §3.4 / AP11 §6.1) —
   Constructor-Default ist `{ emptyMap() }`, Bundle-Pfade
   sind nicht betroffen.
4. `ParquetSingleFilePreflight.phase1/phase2` — Adapter-
   interner Eintrittspunkt fuer den Single-File-Pfad;
   liefert `ResolvedParquetSingleFile`-DTO.
5. `ParquetManifestReader` wiederverwendbar fuer Bundle
   (S5a) und Single-File-Pfad.

## 2. Architektur-Entscheidungen

### 2.1 Provider-Lambda statt Manifest-Builder im Writer

Der Parquet-`extraMetaData`-Map muss **vor** dem
`ParquetWriter.Builder.build()`-Aufruf gesetzt werden — also
beim `DataChunkWriter.begin()`, sobald das `ChunkSchema`
vorliegt. Drei Alternativen wurden bewertet:

- **A** — separate `ParquetSingleFileWriter`-Fassade, die
  den `ParquetChunkWriter` wraps. Verworfen: erzwingt einen
  zweiten `DataChunkWriter`-Implementor und doppelt den
  Streaming-Code.
- **B** — `ExportOptions` um `singleFileManifest: Boolean`
  erweitern. Verworfen: kontaminiert das port-neutrale
  `ExportOptions` mit Parquet-Spezifika.
- **C** — `ParquetChunkWriter` bekommt einen optionalen
  `extraMetaDataProvider`-Constructor-Parameter mit Default
  `{ _ -> emptyMap() }`. Bundle-Pfade verwenden den Default;
  Single-File-Pfade reichen einen
  `ParquetSingleFileManifestWriter().provider` durch.

**Gewaehlt: C.** Erlaubt Single-File-Wiring ohne
zweite Writer-Klasse und behaelt die Port-Neutralitaet von
`ExportOptions`/`DataChunkWriterFactory`.

### 2.2 ParquetChunkWriter bekommt Single-File-Spezifika erst beim Wiring

`ParquetChunkWriterFactory.create()` baut den `Writer` heute
ohne Provider (Default = leere Map). S6 wird die Factory um
einen `extraMetaDataProvider`-Parameter erweitern und
single-file-Aufrufe damit konfigurieren. S4 selbst macht den
Hook im `ParquetChunkWriter` verfuegbar.

### 2.3 Zwei-Phasen-Preflight

`phase1` ist DB-frei und kann im CLI-Resolver direkt nach
dem `--source`-Parse laufen:

- Footer oeffnen via `ParquetFileReader.open(...)`.
- `keyValueMetaData["d-migrate.manifest"]` parsen oder den
  Fallback aus AP11 §5.3 verwenden (Footer-`MessageType` ->
  `ChunkSchema` mit `origin = MANIFEST_FALLBACK`, alle
  Spalten als `NeutralType.Text`).
- Tabellennamens-Precedence per AP11 §5.5 anwenden.
- Optional `contentSha256` berechnen, wenn Resume aktiv ist.

`phase2` ist die Hook-Stelle fuer alles, was nach
`pool.connect()` passiert: heute nur Hash-Konsistenz-Check
fuer Resume (`PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`).
Der `Target-JDBC-Schema-Fallback` fuer fehlende Footer-KV
(AP11 §5.3) lebt im CLI-Wiring (S6) — der CLI hat den
Target-Pool, der Preflight nicht.

## 3. Lieferumfang

### 3.1 Manifest-Layer (Package `manifest`)

- `ParquetManifestReader` (neu): parst YAML zu
  `ParquetBundleManifest`. Context-aware (`BUNDLE` |
  `SINGLE_FILE`), weil Bundle `tables[].file` Pflicht hat,
  Single-File nicht (AP7 §5.2 +
  `parquet-manifest-format.md`-Aenderung).
- `ManifestNeutralTypeToCore`: `ManifestNeutralType ->
  NeutralType`. Detekt-konform in vier Helfer
  (`convertSimple`/`convertNumeric`/`convertTextual`/
  `convertStructured`) gesplittet.
- `ManifestTable.toChunkSchema(origin)`: Extension fuer den
  Reader-Pfad.
- `ParquetManifestParseException`: Wirft bei
  Parse-/Validierungs-Fehlern (`MANIFEST_*`-Codes).
- `ParquetSingleFileManifestWriter`: produziert die Provider-
  Lambda fuer `ParquetChunkWriter`-Konstruktor.
- `ParquetSingleFileManifestReader`: liest aus
  `extraMetaData`-Map; `null` bei fehlendem Key.

### 3.2 ChunkWriter-Erweiterung

`ParquetChunkWriter.kt`:

- Neuer Konstruktor-Parameter
  `extraMetaDataProvider: (ChunkSchema) -> Map<String,
  String> = { emptyMap() }`.
- `begin()` ruft den Provider und reicht das Ergebnis (falls
  nicht leer) an `ExampleParquetWriter.builder.withExtraMetaData`
  durch.

### 3.3 Preflight + DTO

`ParquetSingleFilePreflight.kt`:

- `ResolvedParquetSingleFile(path, table, schema,
  contentSha256?, manifestPresent)` — adapter-interner DTO.
- `phase1(path, explicitTable?, computeContentSha256)`:
  Footer oeffnen, Manifest oder Fallback, Table-Precedence,
  Hash optional.
- `phase2(phase1, resumeExpectedSha256?)`: Hash-Mismatch-
  Check fuer Resume.
- Drei Exception-Klassen mit AP11 §9-Fehlercodes als
  Prefix: `ParquetSingleFileResumeException`,
  `ParquetSingleFileTableMismatchException`,
  `ParquetSingleFileTableRequiredException`.
- Extension `ResolvedParquetSingleFile.toSeekableSource()`
  in derselben Datei (Streaming-Modul bleibt parquet-frei
  per AP11 §7.2).

### 3.4 Tests

`adapters/driven/formats-parquet/src/test/.../ParquetSingleFileRoundTripTest.kt`:

- Round-Trip-Test mit 4 NeutralTypes (BigInteger, Text(200),
  Decimal(12,2), Date) inkl. `--manifest-sha256` —
  Footer-KV bringt das ChunkSchema 1:1 zurueck; Phase 2 mit
  korrektem Hash passiert; falscher Hash wirft
  `PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`.
- Fallback-Test ohne Footer-KV: `manifestPresent = false`,
  Schema kommt aus Footer-MessageType mit `origin =
  MANIFEST_FALLBACK` und `NeutralType.Text` als
  Default-Type.
- Tabellennamens-Precedence: `--table` == Manifest OK,
  Mismatch wirft `PARQUET_SINGLE_FILE_TABLE_MISMATCH`,
  Manifest-only OK.
- Tabellen-Required-Fehler: weder `--table` noch Footer-KV
  -> `PARQUET_SINGLE_FILE_TABLE_REQUIRED`.
- ManifestReader liefert `null` bei fehlendem Key (auch bei
  voller Map mit anderen Keys).

## 4. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Footer-KV-Round-Trip | `ParquetSingleFileRoundTripTest > round-trip` | gruen |
| Phase-1/2-Trennung produktiv | `ls adapters/driven/formats-parquet/src/main/kotlin/.../ParquetSingleFilePreflight.kt` | vorhanden |
| ManifestReader wiederverwendbar | `grep -n "Context.BUNDLE\|Context.SINGLE_FILE" adapters/driven/formats-parquet/src/main/kotlin/.../ParquetManifestReader.kt` | beide Werte unterstuetzt |
| Streaming-Layer parquet-frei | `grep -r "format.parquet" adapters/driven/streaming/src/main` | leer |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Modultest gruen | `make docker-test MODULES=":adapters:driven:formats-parquet"` | BUILD SUCCESSFUL (29s) |
| Detekt gruen | (in docker-check enthalten) | CyclomaticComplexMethod gefixt via 4-Helfer-Split |

## 5. Bewusst NICHT in S4

- **Kein CLI-Wiring** des SingleFileManifestWriter-Providers
  an die ChunkWriterFactory. Lebt in S6 (AP12 §5.2).
- **Kein `--manifest-sha256`-Flag**. CLI-Sache in S6.
- **Kein Target-JDBC-Schema-Fallback** in Phase 2 (AP11
  §5.3). Braucht Zugriff auf den Target-Pool, der erst im
  CLI-Wiring (S6) vorhanden ist. Phase 2 ist heute
  Pass-Through plus Resume-Hash-Check.
- **Keine Checkpoint-Erweiterung** fuer
  `SingleFileCheckpointSpecifics` (AP11 §6.4). Lebt in S8.
- **Kein `ResolvedTableInput.Seekable`-Wiring** im
  Resolver. Lebt in S5b.

## 6. Folgeaufgaben

- **S5a/S5b**: Bundle-Preflight + Resolver-when-Zweige
  fuer `ResolvedBundle`/`ResolvedSingleFile`. Der S4
  `ParquetManifestReader` wird vom Bundle-Reader
  wiederverwendet (Context = `BUNDLE`).
- **S6**: CLI-Wiring instanziiert
  `ParquetSingleFileManifestWriter(producerVersion, ...)`
  und reicht den `provider` an die
  `ParquetChunkWriterFactory` durch. Plus
  `--manifest-sha256`-Flag, Phase-2-Hook fuer
  Target-JDBC-Schema-Fallback.
- **S8**: `SingleFileCheckpointSpecifics`-Persistenz im
  `FileCheckpointStore`, kombiniert mit dem
  `contentSha256`-Wert aus
  `ResolvedParquetSingleFile`.
