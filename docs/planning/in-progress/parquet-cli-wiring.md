# AP12: CLI- und Factory-Wiring-Skizze fuer Parquet

> Dokumenttyp: Architektur- und Implementierungs-Skizze zu
> `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05) — **bindet** die Wiring-
> Entscheidungen, die in AP7-AP11 als „AP12 macht das" angekuendigt
> wurden. Letzte Plan-Etappe vor AP13 (Entscheidungsvorlage).
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 12,
> `parquet-libraries.md` §5 (Vorentscheidung) + §8 (Gradle),
> `parquet-schema-source.md` (AP2 `ChunkSchema`),
> `parquet-manifest-format.md` (AP7 Preflight + Fehlerklassen),
> `parquet-directory-import.md` (AP8 Resolver + Resume),
> `parquet-import-input-dto.md` (AP9 Port-DTOs + Sealed-Sweeps),
> `parquet-port-shape.md` (AP10 Reader-Port),
> `parquet-single-file-metadata.md` (AP11 Footer-KV),
> `hexagon/ports-common/.../DataExportFormat.kt`,
> `hexagon/application/.../cli/commands/DataImportHelpers.kt`,
> `adapters/driving/cli/.../commands/DataImportCommand.kt`,
> `adapters/driving/cli/.../commands/DataImportWiring.kt`,
> `adapters/driven/streaming/.../streaming/StreamingImporter.kt`.

---

## 1. Ziel

Hauptplan §8 Bullet 12 verlangt: „CLI- und Factory-Wiring
skizzieren: DataExportFormat, Clikt-Choices, Reader-/Writer-
Factories, Format-Autodetection, CSV-Flag-Validierung,
Encoding-Regel, Directory-Autodetection ueber Manifest und
Checkpoint-Fingerprint."

AP12 zieht alle Vorentscheidungen aus AP1-AP11 zusammen und
liefert die konkrete Wiring-Liste, ohne dabei Inhalte zu
duplizieren — wo immer moeglich verweist der Sub-Doc auf den
jeweiligen Vor-Sub-Doc. Das Ergebnis ist ein implementations-
fertiges Skelett, das nach AP13 (Entscheidungsvorlage)
umgesetzt werden kann.

AP12 macht **keine** neuen Architekturentscheidungen — es
verteilt die bereits getroffenen auf die richtigen
Code-Stellen und schliesst die in AP7-AP11 aufgesammelten
Sweeps.

---

## 2. Ausgangslage

Bestehende CLI-/Wiring-Stellen (verifiziert via Code-Sichtung
2026-06-05):

- `hexagon/ports-common/.../DataExportFormat.kt`: enum mit
  `JSON("json", listOf("json"))`, `YAML`, `CSV`. `fromCli`
  parst CLI-Namen.
- `hexagon/application/.../cli/commands/DataImportHelpers.kt`:
  `resolveFormat` (Z. 43 ff.) plus
  `inferFormatFromExtension` (Z. 37 ff.); `toImportInput`
  (Z. 136 ff.) baut `ImportInput.SingleFile` mit
  Pflicht-`--table`.
- `hexagon/application/.../cli/commands/ImportRunnerTypes.kt`:
  `InputContext(effectiveTables, inputFilesByTable,
  fingerprint)` (Z. 127), `ImportResumeContext` (Z. 156).
- `hexagon/application/.../cli/commands/ImportCheckpointManager.kt`:
  `writeInitialManifest` (Z. 166), `validateManifest`
  (Z. 93-124), `buildCallbacks`/`saveManifest` (Z. 216 ff.).
- `hexagon/application/.../cli/commands/ImportPreflightValidator.kt`:
  drei exhaustive `when (input)` (Z. 105-122 —
  `effectiveTables`, `inputTopology`, `inputPath`).
- `adapters/driving/cli/.../commands/DataImportCommand.kt`:
  Clikt-`--format`-Option (Z. 42), `--table` (Z. 51),
  `--tables` (Z. 56), `--encoding` (Z. 91), `--csv-no-header`
  (Z. 97), `--csv-null-string` (Z. 102), `--chunk-size`
  (Z. 107), `--resume` (Z. 114).
- `adapters/driving/cli/.../commands/DataImportWiring.kt`:
  konstruiert `DefaultDataChunkReaderFactory()` (Z. 73) und
  uebergibt sie als `readerFactory` an `StreamingImporter(...)`
  (Z. 93).
- `adapters/driven/streaming/.../streaming/StreamingImporter.kt`:
  `class StreamingImporter(readerFactory:
  DataChunkReaderFactory, writerLookup, onTableOpened, ...)`
  (Z. 21); `TableImporter(readerFactory, onTableOpened)`
  intern (Z. 28).
- `adapters/driven/streaming/.../streaming/ImportInputResolver.kt`:
  `internal class`, drei `when (input)`-Faelle (Stdin /
  SingleFile / Directory), liefert `ResolvedTableInput(table,
  openInput: () -> InputStream)`.

---

## 3. `DataExportFormat`-Erweiterung

Aus AP9 §7.5 + AP10 §3.1 Vorbedingung:

```kotlin
// hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/DataExportFormat.kt
enum class DataExportFormat(val cliName: String, val fileExtensions: List<String>) {
    JSON("json", listOf("json")),
    YAML("yaml", listOf("yaml", "yml")),
    CSV("csv", listOf("csv")),
    PARQUET("parquet", listOf("parquet")),    // AP12 (2026-06-05)
    ;
    // fromCli bleibt unveraendert — entries.firstOrNull(...) faengt PARQUET automatisch.
}
```

Sealed-`when`-Sweep mit dem AP9 §7.8-Suchmuster:

```bash
rg --type kotlin -n 'is DataExportFormat\.' .
rg --type kotlin -n 'when \(.*DataExportFormat.*\)' .
```

Jede Trefferstelle bekommt einen `DataExportFormat.PARQUET`-
Zweig. Die heutige Konsumentenmenge ist klein
(`DefaultDataChunkReaderFactory.create`,
`DefaultDataChunkWriterFactory.create`, ggf.
`SchemaCodecResolver`); jeder Zweig delegiert an die
Parquet-spezifische Factory aus §5.

---

## 4. CLI-Choices und Flags

### 4.1 `--format` Clikt-Choice

`DataImportCommand.kt:42` und das spiegelbildliche
`DataExportCommand.kt`-Pendant nehmen heute den Choice-Set
`{json, yaml, csv}`. Neu: zusaetzlicher Wert `parquet`. Die
`.choice(...)`-Liste wird aus `DataExportFormat.entries.map
{ it.cliName }` abgeleitet — additive Aenderung, kein
manuelles Patchen pro Command.

### 4.2 Parquet-spezifische Flags

- `--manifest-sha256` (Boolean, default `false`) — **Export-
  Flag**. Aktiviert die SHA-256-Berechnung pro Bundle-Datei
  (AP7 §7.1). Wird im Single-File-Export ignoriert (AP11 §5.2:
  Footer-KV traegt kein `sha256`; Resume nutzt den
  Content-Hash separat).
- `--no-checkpoint` (Boolean, default `false`) — **Import-
  Flag**. Neues Feld auf `DataImportRequest`
  (`val noCheckpoint: Boolean = false`). Schaltet die
  Single-File-Content-Hash-Berechnung in
  `ParquetSingleFilePreflight.phase1` aus (AP11 §6.4 — die
  Phase liest dann **keinen** Datei-Bytestrom fuer den
  SHA-256) und unterdrueckt die
  Checkpoint-Manifest-Schreiboperationen im
  `ImportCheckpointManager`. Bundle-Imports sind in ihrem
  Hash-Vertrag nicht betroffen (der kommt aus dem Manifest,
  AP7 §7.2), aber `--no-checkpoint` schaltet auch dort die
  Checkpoint-Schreiboperationen ab.
  - Konflikt mit `--resume`: `--no-checkpoint` und
    `--resume <ref>` gleichzeitig sind widerspruechlich
    (Resume braucht einen geschriebenen Checkpoint). CLI-
    Preflight wirft `CHECKPOINT_OPTIONS_CONFLICT` (Exit 2).
  - Verhaeltnis zu `--checkpoint-dir`: das `--checkpoint-dir`-
    Flag bleibt unabhaengig und legt das Verzeichnis fest,
    in das geschrieben **wuerde**; bei `--no-checkpoint`
    bleibt das Verzeichnis ungenutzt (keine Datei
    angelegt). Damit kann ein Operator durch Weglassen von
    `--no-checkpoint` ohne weitere Flag-Aenderung wieder
    Checkpoint-Modus fahren.
- Keine neuen `--parquet-*`-Optionen fuer Row-Group-/Page-
  Size/Compression in 1.x: Default ist `GZIP` (parquet-
  libraries.md §8); Operatoren, die das aendern wollen,
  warten auf AP-spaeter.

### 4.3 CSV-Flag-Validierung bei `--format parquet`

`--csv-no-header` und `--csv-null-string` sind fuer Parquet
semantisch leer. Bindend: der CLI-Preflight lehnt sie ab,
**wenn der Operator sie explizit gesetzt hat** — der
Default-Wert bricht die Iteration ueber mehrere Formate in
einem Shell-Skript nicht.

Fuer `--csv-no-header` ist der heutige Boolean-Default
`false` ohne Presence-Information; AP12 stellt das auf eine
nullable Variable um (`val csvNoHeader: Boolean? by
option("--csv-no-header").flag().toNullableBoolean()`), so
dass „nicht gesetzt" von „explizit `false`" unterscheidbar
ist.

Fuer `--csv-null-string` ist die heutige Definition (Z. 101
`DataImportCommand.kt`):

```kotlin
val csvNullString by option("--csv-null-string", ...).default("")
```

`.default("")` ist hier zwei Mal das Default — der Wert ist
ein leerer String, ein vom Operator gesetzter leerer String
ist davon nicht unterscheidbar. AP12 stellt das auf
**nullable** um (kein `.default(...)`):

```kotlin
val csvNullString: String? by option("--csv-null-string", ...)
```

`DataImportRequest.csvNullString: String?` wird entsprechend
nullable. Konsumenten, die heute den leeren String erwarten,
mappen `null -> ""` an genau einer Stelle im
`CsvChunkReader`/`Writer`-Konstruktor. Damit ist die
AP12-Validierungsregel klar:

```kotlin
if (format == DataExportFormat.PARQUET) {
    require(request.csvNoHeader == null) {
        "--csv-no-header is not valid with --format parquet (CSV_FLAG_INVALID_FOR_PARQUET)."
    }
    require(request.csvNullString == null) {
        "--csv-null-string is not valid with --format parquet (CSV_FLAG_INVALID_FOR_PARQUET)."
    }
}
```

Code-Stelle: `DataImportCommand.run()` (oder
`DataImportHelpers.toImportInput`) prueft vor dem
Wiring-Schritt. Stiller Ignore waere die zweite Option,
faellt aber unter „bricht still ungueltige Operator-
Annahmen" und ist deshalb abgelehnt (`parquet-libraries.md`
§3.5-Stil).

### 4.4 `--encoding`-Regel

`--encoding` parametrisiert heute Text-Format-Reader. Fuer
Parquet ist es **bedeutungslos** (binaeres Format, UTF-8 ist
hartkodiert fuer Strings im Footer-KV). Bindend: silently
ignoriert, nicht abgelehnt. Begruendung gegen Ablehnung:
`--encoding` ist ein verbreiteter Default in Shell-Skripten,
die mehrere Formate iterieren; eine Ablehnung wuerde
Skript-Konsumenten brechen, die das Flag unspezifisch
setzen.

### 4.5 `--table`-Precedence bei Single-File-Parquet

Siehe AP11 §5.5 (drei Faelle, Fehler-Codes
`PARQUET_SINGLE_FILE_TABLE_MISMATCH` und
`PARQUET_SINGLE_FILE_TABLE_REQUIRED`).

### 4.6 `--resume`-Erweiterung

`--resume <ref>` (String?, schon vorhanden, Z. 114
`DataImportCommand.kt`) bleibt unveraendert. Der Resume-Pfad
greift fuer Parquet auf zwei verschiedene Checkpoint-
Variants zurueck (Bundle vs. Single-File, §7).

---

## 5. Factory-Wiring

### 5.1 Reader-Factories

Heute (Z. 73 `DataImportWiring.kt`):

```kotlin
val readerFactory = DefaultDataChunkReaderFactory()
val importer = StreamingImporter(readerFactory = readerFactory, ...)
```

Neu nach AP10 §5.4 + AP10 Befund-Rueckspiel:

```kotlin
val readerFactory = DefaultDataChunkReaderFactory()
val seekableReaderFactory = ParquetSeekableDataChunkReaderFactory()  // AP10 §4.2, public class
val importer = StreamingImporter(
    readerFactory = readerFactory,
    seekableReaderFactory = seekableReaderFactory,    // Pflichtparameter
    writerLookup = writerLookup,
    onTableOpened = callbacks.onTableOpened,
)
```

`StreamingImporter`-Constructor wird um den Pflichtparameter
erweitert (AP10 §5.4 Befund-Rueckspiel); intern reicht er
ihn an `TableImporter(readerFactory, seekableReaderFactory,
onTableOpened)` durch.

### 5.2 Writer-Factories — Composite im CLI-Wiring

`DefaultDataChunkWriterFactory` lebt in
`adapters:driven:formats`; es hat heute keine Hadoop-/
Parquet-Dependency und soll keine bekommen — sonst zieht
der formats-Stack (vom JSON/YAML/CSV-Pfad transitiv
gebraucht) den vollen Hadoop-Block in jeden Konsumenten,
auch wenn nie Parquet geschrieben wird.

Bindend: **separate `ParquetChunkWriterFactory` in
`adapters:driven:formats-parquet`**, die `DataChunkWriterFactory`
implementiert; im CLI-Wiring eine **Composite-Factory** als
Default-Wiring:

```kotlin
// adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/ParquetChunkWriterFactory.kt
class ParquetChunkWriterFactory : DataChunkWriterFactory {
    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        ...,
    ): DataChunkWriter {
        require(format == DataExportFormat.PARQUET) {
            "ParquetChunkWriterFactory does not support format=$format"
        }
        return ParquetChunkWriter(output, ...)
    }
}

// adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompositeDataChunkWriterFactory.kt
class CompositeDataChunkWriterFactory(
    private val text: DataChunkWriterFactory,            // DefaultDataChunkWriterFactory
    private val parquet: DataChunkWriterFactory,         // ParquetChunkWriterFactory
) : DataChunkWriterFactory {
    override fun create(
        format: DataExportFormat,
        output: OutputStream,
        ...,
    ): DataChunkWriter = when (format) {
        DataExportFormat.JSON,
        DataExportFormat.YAML,
        DataExportFormat.CSV     -> text.create(format, output, ...)
        DataExportFormat.PARQUET -> parquet.create(format, output, ...)
    }
}
```

`ParquetChunkWriter` wraps den `OutputStream` intern in einen
`PositionOutputStream`-Adapter (AP10 §3.4); kein neuer
Writer-Port. Footer-KV-Schreiben (`withExtraMetaData`) lebt
im `ParquetSingleFileManifestWriter` aus AP11 §7.1, der vom
`ParquetChunkWriter`-Pfad pro Tabelle aufgerufen wird.

Im `DataExportWiring`-Pendant zu `DataImportWiring` wird die
Composite-Factory analog zur Reader-Seite konstruiert; der
`formats`-Stack bleibt Hadoop-frei. Konsumenten, die
ausschliesslich JSON/YAML/CSV brauchen (z.B. Test-Suiten
gegen den heutigen Stack), verdrahten weiterhin nur die
`DefaultDataChunkWriterFactory` — sie sehen die Composite-
Schicht und damit den Parquet-Adapter nicht.

### 5.3 Bundle-Adapter-Wiring (Import)

CLI-`data import`-Pfad fuer Directory-Bundles
(`format == PARQUET` und `Files.isRegularFile(<dir>/manifest.yaml)`,
AP9 §7.5 / AP8 §9.1):

```kotlin
val bundle: ResolvedParquetBundle = ParquetBundlePreflight.run(
    bundleRoot = sourcePath,
    requireSha256Verify = !request.resume.isNullOrBlank(),  // AP9 §7.7
)
val resolver = ParquetBundleResolver(
    bundle = bundle,
    tableFilter = request.tables?.toSet(),
    tableOrder = null,                                        // CLI nicht explizit
)
val importInput: ImportInput.ResolvedBundle =
    ParquetBundleAdapter.toResolvedBundle(resolver)           // AP9 §4.3
```

`bundleExpectedSha256ByTable` wird parallel aus
`bundle.tables[].sha256` gebaut und in den `InputContext`
gesteckt (AP9 §7.5).

### 5.4 Single-File-Adapter-Wiring (Import) — zwei Phasen

`DataImportRunner.executeWithCancel` (Z. 141-154) faehrt
heute zuerst `resolveRequest(...)` und **dann**
`connect(ctx.connectionConfig)`. Das macht den Live-DB-
Target-Schema-Lookup im Single-File-Preflight unmoeglich,
solange der Preflight in `resolveRequest` sitzen wuerde
(Befund-Rueckspiel: in der initialen AP12-Skizze stand
`resolveTargetSchema(...)` im Preflight, vor DB-Connect —
das ging nicht).

Bindend: Single-File-Parquet wird in **zwei Phasen**
preflight:

**Phase 1 — in `resolveRequest` (vor DB-Connect):**

```kotlin
val partial: PartiallyResolvedSingleFile = ParquetSingleFilePreflight.phase1(
    path = sourcePath,
    cliTable = request.table,                                 // §4.5
)
// liefert: path, resolvedTable, footerKvManifestYaml?, contentSha256
```

- Tabellen-Resolution (`--table` vs. Footer-KV) (§4.5).
- Footer-KV-Bytestrom auslesen (oder `null`, wenn fehlt).
- Content-SHA-256 berechnen, falls `--no-checkpoint` NICHT
  gesetzt (§4.6).
- Stdin-Ablehnung (`PARQUET_STDIN_NOT_SUPPORTED`).
- **Kein** `ChunkSchema`-Aufbau, weil Live-DB-Target-Spalten
  fuer den Footer-only-Fallback (§5.3) noch nicht verfuegbar
  sind.

**Phase 2 — in `runImport` (nach `connect()`):**

```kotlin
val resolved: ImportInput.ResolvedSingleFile =
    ParquetSingleFilePreflight.phase2(
        partial = partial,
        targetMetadata = DataWriterUtils.readTargetColumns(pool, partial.resolvedTable),
    )
// liefert: ImportInput.ResolvedSingleFile mit ChunkSchema gefuellt
```

- `ChunkSchema` aus Footer-KV-YAML bauen (AP11 §5.2), wenn
  vorhanden.
- Sonst Footer-`MessageType` + Live-Target-`TargetColumn`-
  Liste kombinieren (AP11 §5.3, jetzt mit verfuegbarem
  Target-Schema).
- AP10 §3.3 Footer-vs-ChunkSchema-Konsistenzcheck im Reader
  greift weiterhin.

**Neues `ImportInput.ResolvedSingleFile` (analog
ResolvedBundle aus AP9):**

```kotlin
// hexagon:ports-write — ergaenzt AP9 §4.1
data class ResolvedSingleFile(
    val table: String,
    val path: Path,
    val schema: ChunkSchema,
    val expectedSha256: String?,
    val resumeFingerprint: SingleFileCheckpointSpecifics?,
) : ImportInput()
```

Dadurch traegt `SchemaPreflightResult.input: ImportInput`
(ImportRunnerTypes.kt:34) und `ImportExecutionContext.input:
ImportInput` (Z. 45) die vor-resolved Form bereits — ohne
zusaetzliche DTO-Felder ueberzieht. Der
`ImportInputResolver` (Streaming-Modul) bekommt einen neuen
`when`-Zweig:

```kotlin
is ImportInput.ResolvedSingleFile -> listOf(
    ResolvedTableInput.Seekable(
        table = input.table,
        source = SeekableChunkSource.Local(input.path),
        schema = input.schema,
        expectedSha256 = input.expectedSha256,
    )
)
```

Damit ist der `StreamingImporter`-/`TableImporter`-Pfad
identisch fuer ResolvedBundle und ResolvedSingleFile —
beide produzieren `ResolvedTableInput.Seekable`.

**Phase 2 lebt aussen vom `DataImportRunner`-Pfad:** Der
Runner erhaelt nach `connect()` einen `targetMetadataLookup`
(Collaborator-Funktion, im CLI-Wiring befuellt) und ruft
Phase 2 bei Bedarf auf, bevor er den `ImportExecutionContext`
baut. Das DTO `SchemaPreflightResult` traegt in der Phase-1-
Zeit ggf. einen Phase-1-Marker (z.B.
`pendingSingleFileFinalization: PartiallyResolvedSingleFile?`),
den `runImport` in Phase 2 zu `ImportInput.ResolvedSingleFile`
aufloest. Konkrete Field-Wahl ist Implementierungs-Detail
und wird beim CLI-Wiring-Refactor entschieden; der Vertrag
oben ist bindend.

**Alternative `--schema <path>` als Pflicht fuer fremde
Parquet-Dateien:** verworfen. Das wuerde
`--schema`-Pflicht-Asymmetrie zwischen
`d-migrate.manifest`-tragenden und fremden Parquet-Dateien
einfuehren und ist fuer Operatoren irrefuehrend (sie wissen
nicht vorab, ob der Footer-KV da ist). Phase-2-Pattern ist
sauberer.

---

## 6. Format-Auto-Detection

`DataImportHelpers.resolveFormat` (Z. 43 ff.) wird gemaess
AP8 §9.2 / AP11 §5.4 erweitert. Pseudocode der neuen
Reihenfolge:

```kotlin
fun resolveFormat(request, isStdin, sourcePath, stderr): DataExportFormat? {
    // 1. Expliziter --format-Wert gewinnt.
    request.format?.let { return DataExportFormat.fromCli(it) }

    // 2. Directory mit manifest.yaml -> PARQUET (AP8 §9.2).
    if (sourcePath != null && Files.isDirectory(sourcePath)
        && Files.isRegularFile(sourcePath.resolve("manifest.yaml"))) {
        return DataExportFormat.PARQUET
    }

    // 3. Endungs-Inferenz wie heute, PLUS .parquet (AP11 §5.4).
    return sourcePath?.let(::inferFormatFromExtension)
        ?.let(DataExportFormat::fromCli)
}
```

`EXTENSION_FORMAT_MAP` (Z. 30 ff.) wird um `"parquet" to
"parquet"` erweitert.

Auto-Detection-Reihenfolge ist bewusst „expliziter Wert vor
Manifest-Heuristik vor Endung", damit CLI-Skripte mit
`--format json` ein zufaellig vorhandenes `manifest.yaml` im
Verzeichnis nicht stillschweigend umlenken (AP8 §9.4).

---

## 7. Checkpoint-Wiring

### 7.1 `CheckpointOperationSpecifics`-Persistenz

`FileCheckpointStore.toMap`/`fromMap` wird laut AP8 §10.5 +
AP9 §7.5 erweitert:

```kotlin
private fun toMap(manifest: CheckpointManifest): Map<String, Any?> = buildMap {
    // ... bestehende Felder
    manifest.operationSpecific?.let { specifics ->
        put("operationSpecific", when (specifics) {
            is BundleCheckpointSpecifics ->
                mapOf("kind" to specifics.bundleKind,         // AP9 §4.2
                      "fingerprint" to fingerprintToMap(specifics.fingerprint))
            is SingleFileCheckpointSpecifics ->                // AP11 §6.4
                mapOf("kind" to specifics.bundleKind,         // "parquet-single-file"
                      "contentSha256" to specifics.contentSha256,
                      "table" to specifics.table)
        })
    }
}
```

`fromMap` liest den `kind`-Diskriminator und instanziiert
die passende Variante; unbekannter `kind`-Wert wirft
`CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND` (AP9 §4.2).

Kein Schema-Versionsbump (`CURRENT_SCHEMA_VERSION` bleibt
`2`, AP9 §7.5): das `operationSpecific`-Feld ist optional;
pre-AP8-Checkpoints (ohne den Block) bleiben lesbar fuer
JSON/YAML/CSV.

Wenn `request.noCheckpoint == true` (§4.2): der
`FileCheckpointStore` wird **nicht aufgerufen** —
`ImportCheckpointManager.writeInitialManifest` und alle
nachfolgenden `saveManifest()`-Updates skippen die
Schreiboperation. Das `operationSpecific`-Feld wird folglich
weder fuer Bundle noch fuer Single-File persistiert.
Single-File-Phase-1 ueberspringt zusaetzlich die
Content-SHA-256-Berechnung — der `expectedSha256` im
`ResolvedTableInput.Seekable` bleibt `null`. AP10 §3.3
Footer-vs-ChunkSchema-Konsistenzcheck im Reader greift
unabhaengig davon weiter.

### 7.2 `ImportCheckpointManager`-Erweiterung

`validateManifest` (Z. 93-124) bekommt die drei
Bundle-Pruefungen aus AP9 §7.5 und die Single-File-
Pruefung aus AP11 §6.4:

```kotlin
private fun validateManifest(manifest, inputCtx): ImportResumeResult? {
    // ... bestehende Pruefungen (operationType, optionsFingerprint, tableSlices)

    when (val specifics = manifest.operationSpecific) {
        is BundleCheckpointSpecifics ->
            validateBundleResume(specifics, inputCtx)  // AP9 §7.5 Schritte 1-3
        is SingleFileCheckpointSpecifics ->
            validateSingleFileResume(specifics, inputCtx)  // AP11 §6.4 Hash-Vergleich
        null -> {
            // Pre-AP8-Checkpoint: nur OK, wenn der aktuelle Lauf NICHT
            // Bundle/SingleFile-Parquet ist. Sonst:
            // BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT (AP9)
        }
    }
}
```

`buildCallbacks` / `saveManifest()` (Z. 216 ff.) reicht das
`operationSpecific`-Objekt durch jeden Update-Aufruf
(AP9 §7.5-Befund-Rueckspiel) — Fingerprint ist Lauf-
Invariante, nicht Initial-Information.

### 7.3 `InputContext`-Erweiterung

Aus AP9 §7.5 + AP11 §6.4:

```kotlin
internal data class InputContext(
    val effectiveTables: List<String>,
    val inputFilesByTable: Map<String, String>,
    val fingerprint: String,
    val bundleExpectedSha256ByTable: Map<String, String?>? = null,  // AP9
    val singleFileContentSha256: String? = null,                    // AP11
)
```

---

## 8. Sealed-Sweep-Liste

Aus AP9 §7.8 (Suchmuster) und AP10/AP11 (neue Sealed-
Varianten). Komplette Liste der `when`-Faelle, die AP12-
Implementierung anfassen muss:

### 8.1 `ImportInput` (neu: `ResolvedBundle`, `ResolvedSingleFile`)

Sweep-Befehl:

```bash
rg --type kotlin -n 'is ImportInput\.' .
rg --type kotlin -n 'when \(' . | grep -F 'ImportInput'
```

Bekannte Stellen (Code-Sichtung 2026-06-05):

- `adapters/driven/streaming/.../ImportInputResolver.kt` —
  drei `when`-Zweige (`Stdin` / `SingleFile` / `Directory`);
  zwei neue Zweige `ResolvedBundle` (AP9 §7.3) und
  `ResolvedSingleFile` (§5.4 oben), beide produzieren
  `ResolvedTableInput.Seekable`.
- `hexagon/application/.../ImportPreflightValidator.kt` —
  Z. 105 (`effectiveTables`), Z. 113 (`inputTopology`),
  Z. 118 (`inputPath`). AP9 §7.5 nennt die Zweige im Detail;
  fuer `ResolvedSingleFile` ist `inputTopology` =
  `"single-file"`, `inputPath` =
  `input.path.toAbsolutePath().normalize().toString()`.
- Weitere via Sweep-Befehl identifizieren; ggf. Tests in
  `adapters/driving/cli/src/test/.../DataImportRunner*Test*.kt`.

### 8.2 `SchemaOrigin` (neu: `MANIFEST_FALLBACK`)

Aus AP9 §5. Sweep:

```bash
rg --type kotlin -n 'is SchemaOrigin\.' .
rg --type kotlin -n 'when \(' . | grep -F 'SchemaOrigin'
```

Heutige Konsumentenmenge ist klein
(`parquet-schema-source.md` §6 nennt den
`StreamingExporter`-Pfad), aber der Sweep ist Pflicht.

### 8.3 `SeekableChunkSource` (neu — sealed in
`hexagon:ports-read`)

AP10 §3.2: aktuell nur `Local`. Sealed-when erst in der
Default-Impl (`ParquetSeekableDataChunkReaderFactory`,
AP10 §4.2) und einem ggf. spaeteren Object-Storage-Adapter.
Heute leerer Sweep.

### 8.4 `CheckpointOperationSpecifics` (neu:
`BundleCheckpointSpecifics`, `SingleFileCheckpointSpecifics`)

Sealed in `hexagon:ports-write`. Konsumenten:
`FileCheckpointStore.toMap`/`fromMap` (§7.1),
`ImportCheckpointManager.validateManifest` (§7.2).

### 8.5 `DataExportFormat` (neu: `PARQUET`)

§3 oben.

---

## 9. Exit-Codes und Fehlerklassen-Mapping

Gesammelt aus AP7-AP11. Bindender Vorschlag fuer
Exit-Code-Familie:

| Code | Fehlerklasse(n) | Exit |
| ---- | --------------- | ---- |
| Manifest-Format/Preflight (AP7 §9.2) | `MANIFEST_NOT_FOUND`, `MANIFEST_PARSE_ERROR`, `MANIFEST_VERSION_INCOMPATIBLE`, `MANIFEST_FIELD_MISSING`, `MANIFEST_FIELD_INVALID`, `MANIFEST_TABLE_DUPLICATE`, `MANIFEST_FILE_DUPLICATE`, `MANIFEST_FILE_OUTSIDE_BUNDLE`, `MANIFEST_FILE_MISSING`, `MANIFEST_FILE_UNREFERENCED`, `MANIFEST_SHA256_MISMATCH` | 4 |
| Bundle-Resolver/Iteration (AP8 §5.2 / §7.3) | `BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`, `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE`, `BUNDLE_SCHEMA_UNRESOLVED`, `BUNDLE_TABLE_IMPORT_FAILED` | 5 |
| Bundle-Resume (AP8 §8.4) | `BUNDLE_RESUME_REQUIRES_FILE_HASHES`, `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`, `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`, `BUNDLE_TABLE_ORDER_CHANGED`, `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` | 3 (gleiche Familie wie der heutige `optionsFingerprint`-Mismatch — „Resume strukturell unmoeglich") |
| Single-File-Parquet (AP11 §5.5 / §6.3 / §6.4) | `PARQUET_SINGLE_FILE_TABLE_MISMATCH`, `PARQUET_SINGLE_FILE_TABLE_REQUIRED`, `PARQUET_SINGLE_FILE_NO_MANIFEST_USING_FOOTER`, `PARQUET_STDIN_NOT_SUPPORTED`, `PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT` | 4 (Format-Vertragsbruch) |
| Reader-Konsistenz (AP10 §3.3) | `BUNDLE_SCHEMA_PARQUET_MISMATCH` | 4 |
| Checkpoint-Format (AP9 §4.2) | `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND` | 3 |
| CLI-Flag-Konflikt (§4.3) | `CSV_FLAG_INVALID_FOR_PARQUET` | 2 (CLI-Vertragsbruch, dieselbe Familie wie heute fuer `--table` bei Directory) |
| CLI-Flag-Konflikt (§4.2) | `CHECKPOINT_OPTIONS_CONFLICT` (`--no-checkpoint` + `--resume`) | 2 |

Wortlaut-Beispiele fuer `stderr`-Ausgabe:

```
Error: Parquet bundle manifest 'out/export/manifest.yaml' does not exist (MANIFEST_NOT_FOUND).
Hint: parquet directory imports require a manifest.yaml at the bundle root.

Error: Parquet bundle manifest's tableOrder differs from checkpoint (BUNDLE_TABLE_ORDER_CHANGED):
       checkpoint=[orders, items], current=[items, orders].
Hint: checkpoint is stale; rerun without --resume.

Error: Single-file Parquet has no 'd-migrate.manifest' but --table is required (PARQUET_SINGLE_FILE_TABLE_REQUIRED).
Hint: specify --table or export with the d-migrate parquet writer to embed it.
```

---

## 10. Native-Image- und Hadoop-API-Shim-Check

Aus `parquet-libraries.md` §8 (AP4+-Folge) plus AP3-Spike-
Befunde:

- **GraalVM-Reachability-Metadaten** muessen den
  Parquet-Adapter abdecken. Pflichtklassen aus dem
  Spike-Befund: `org.apache.hadoop.fs.Path`,
  `org.apache.hadoop.conf.Configuration`,
  `org.apache.hadoop.fs.LocalFileSystem` /
  `RawLocalFileSystem`, `org.apache.hadoop.fs.FileSystem`,
  `org.apache.parquet.hadoop.ParquetInputFormat` (extends
  `FileInputFormat`), `org.apache.parquet.hadoop.codec.*`
  fuer den GZIP-Codec. Vollstaendige Liste via
  `nativeCompile` + Smoketest.
- **Hadoop-API-Shim-Erwaegung**: `parquet-libraries.md` §8
  hat einen eigenen minimalen Hadoop-`fs`-Shim als
  Folge-Aufgabe markiert. AP12 macht den Shim **nicht** —
  die heutige `hadoop-common`+`hadoop-mapreduce-client-core`-
  Abhaengigkeit bleibt erhalten, bis die GraalVM-Smoketests
  zeigen, ob die Reachability-Metadaten beherrschbar sind.
  Wenn ja, ist der Shim ein optionales Folge-Refactor; wenn
  nein, wird er zur Pflichtarbeit.
- **AP6-Befund `fs.file.impl.disable.cache=true`** bleibt
  in der Writer-Code-Pfad-Konfiguration. AP12 stellt sicher,
  dass die `Configuration` an genau einer Stelle aufgebaut
  wird (Adapter-internes
  `ParquetHadoopConfigBuilder`) — keine Verstreuung ueber
  mehrere Klassen.

---

## 11. Test-Strategie

Pflichttest-Familien fuer die AP12-Implementierung:

### 11.1 CLI-Preflight-Tests

`adapters/driving/cli/src/test/.../DataImportPreflightTest.kt`
(o.ae.) deckt alle Fehlercodes aus §9 als Akzeptanztests ab.
Pro Code mindestens ein Test, der den Fehler ausloest und
den `stderr`-Wortlaut prueft (analog zum bestehenden
JSON/YAML/CSV-Preflight).

### 11.2 Format-Resolver-Tests

`hexagon/application/src/test/.../DataImportHelpersTest.kt`
deckt die §6-Auto-Detection-Reihenfolge ab. Pflicht-Cases:

- Explizit `--format json` plus Verzeichnis mit
  `manifest.yaml` -> JSON gewinnt (D8 aus AP8 §3).
- Kein `--format`, Verzeichnis mit `manifest.yaml` -> PARQUET.
- Kein `--format`, Verzeichnis ohne `manifest.yaml`, einzelne
  `users.csv` -> CSV.
- Kein `--format`, `users.parquet` als `--source` -> PARQUET
  (Endungs-Inferenz).
- Kein `--format`, Verzeichnis mit `manifest.yaml` plus
  fehlerhaftem YAML-Inhalt -> `MANIFEST_PARSE_ERROR`, **kein**
  YAML-Fallback (AP8 §9.4).

### 11.3 Resume-Akzeptanztests

`adapters/driving/cli/src/test/.../DataImportResumeTest.kt`:

- Bundle-Resume mit unveraendertem Manifest und vorhandenen
  Datei-Hashes -> OK.
- Bundle-Resume mit fehlenden Datei-Hashes ->
  `BUNDLE_RESUME_REQUIRES_FILE_HASHES`.
- Bundle-Resume mit veraendertem `manifest.yaml` ->
  `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`.
- Single-File-Resume mit unveraenderter Datei -> OK.
- Single-File-Resume mit ausgetauschter Datei ->
  `PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`.
- Pre-AP8-Checkpoint trifft Bundle-Lauf ->
  `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`.

### 11.4 DuckDB-/Arrow-Smoke-Test fuer Footer-KV

Aus AP11 §8: ein Smoke-Test, der

- eine Single-File-Parquet mit d-migrate-Footer-KV schreibt,
- mit DuckDB (`SELECT * FROM read_parquet(?)` + KV-Inspektion)
  und Arrow (`SchemaConverter`) liest,
- den Custom-Key `d-migrate.manifest` als
  toleriert/unbeachtet verifiziert.

Erweitert die bestehenden `ParquetSpikeDuckDbReadTest` /
`ParquetSpikeArrowInspectTest`-Linien (AP4/AP5).

### 11.5 Sealed-Sweep-Verifikation

`adapters/driving/cli/src/test/.../ImportInputSweepTest.kt`
(neu) faehrt zur Build-Zeit die §8-Sweep-Befehle in einem
Test als Diagnose-Hilfe — kein hartes Assert (die
Implementation hat die Sweeps schon erledigt), aber als
Dokumentation, dass das Suchmuster nicht regressiert.

---

## 12. Implementierungsreihenfolge

Bindender Vorschlag fuer AP-Folge-Implementation (entkoppelt,
nicht in einem Big-Bang-Commit):

1. `DataExportFormat.PARQUET` + Sealed-when-Sweeps (§3, §8).
   Kompiliert nach Sweep, JSON/YAML/CSV-Tests bleiben gruen.
2. `SeekableDataChunkReaderFactory`-Port + Default-Impl
   (AP10 §4, §5.3 oben).
3. `ParquetChunkReader` + `ParquetChunkWriter`-Implementation
   (AP3-Spike-Linie + AP2 §6.2 / AP10 §3.3).
4. `ParquetSingleFileManifestReader`/`Writer` +
   `ParquetSingleFilePreflight` (AP11).
5. `ParquetBundlePreflight` + `ParquetBundleResolver` +
   `ParquetBundleAdapter` (AP7/AP8/AP9). Im selben Schritt
   `ImportInput.ResolvedSingleFile`-Sealed-Variante
   ergaenzen (§5.4) plus `ParquetSingleFilePreflight.phase1`/
   `phase2`-Trennung.
6. CLI-Wiring (`DataImportWiring` + `DataImportCommand`,
   §4, §5). Composite-Writer-Factory (§5.2) verdrahten;
   `DataImportRunner` um den Phase-2-Hook fuer
   `ImportInput.ResolvedSingleFile`-Finalisierung nach
   `connect()` erweitern.
7. Resolver-Integration (`ImportInputResolver` +
   `TableImporter` + `StreamingImporter`-Constructor, §5.1).
8. Checkpoint-Erweiterung (`FileCheckpointStore` +
   `ImportCheckpointManager` + `InputContext`, §7).
9. Akzeptanztests (§11).

AP13 (Entscheidungsvorlage) bewertet, ob die volle Folge
fuer 1.x umsetzbar ist oder welche Teile spaeter kommen.

---

## 13. Risiken

- **Sweep-Vollstaendigkeit**: der Sealed-Sweep aus §8 ist
  nur so vollstaendig wie das `rg`-Pattern. Versteckte
  Konsumenten (z.B. via Java-Reflection oder
  Service-Loader) faengt er nicht; AP12-Implementierung
  sollte einen vollstaendigen `gradle assemble` als
  Fail-Sicher haben.
- **Hadoop-Footprint im Distributions-JAR**: die
  parquet-hadoop-/hadoop-common-/hadoop-mapreduce-client-
  core-Abhaengigkeiten bringen mehrere MB an Klassen mit.
  AP13 muss das im 1.x-Scope-Vorschlag erwaehnen — ggf.
  ist ein Shim-Refactor (§10) Bedingung fuer einen
  Native-Image-Cut.
- **Pre-AP8-Checkpoint-Bruch fuer Bundle-Imports**: bewusst
  hart, vgl. AP9 §8. AP12 muss eine Release-Note bauen
  („wer ein 0.9.7-Bundle-Checkpoint hat, faengt im 0.9.8-
  Bundle-Lauf neu an").
- **CSV-Flag-Ablehnung bricht Shell-Skripte**, die mehrere
  Formate iterieren und `--csv-no-header` global setzen
  (§4.3). Das ist Operator-Verantwortung, sollte aber im
  CLI-Help prominent erwaehnt sein.
- **Format-Auto-Detection-Falle** (AP8 §9.4): ein
  `manifest.yaml` mit YAML-Bundle-Inhalt (nicht Parquet)
  schlaegt mit `MANIFEST_PARSE_ERROR` fehl, statt
  stillschweigend YAML zu lesen. Das ist gewollt; AP12 muss
  die Diagnose so formulieren, dass der Operator den
  expliziten `--format yaml`-Workaround sieht.
- **Test-Last**: §11 listet sechs Test-Familien. Die volle
  Abdeckung kostet substantielle CI-Zeit; AP13 entscheidet,
  welche Familie im 1.x-Cut zwingend ist und welche
  Folge-Release.
