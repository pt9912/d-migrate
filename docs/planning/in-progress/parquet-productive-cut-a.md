# Parquet — Produktiver Cut A (0.9.8)

> Dokumenttyp: Per-Feature-Umbrella-Plan (Implementation)
>
> Status: In Progress (Stand 2026-06-06) — Umbrella fuer den
> produktiven Cut-A-Pfad. Plan-Doc-Phase (AP1-AP13) ist mit
> ff617d05 nach `docs/planning/done/` migriert; diese
> Closure deckt nur die Evaluierungsphase ab, nicht den Code.
>
> **Scope-/Versions-Korrektur 2026-06-06:** AP13 §5.2/§5.4/§7
> hatten „Cut B (Bundle-only Pilot) als 1.0.0" empfohlen.
> Stakeholder-Entscheid 2026-06-05 (Commit e7f3f714) folgte
> dieser Empfehlung. Am 2026-06-06 wurde die Empfehlung durch
> [`parquet-decision-template.md` §8](../done/parquet-decision-template.md)
> superseded auf **Cut A (Voller Vertrag) als 0.9.8**;
> Begruendung siehe dort §8.2.
>
> Referenz-Quellen (alle in `docs/planning/done/`):
> [`parquet-export-import-evaluation.md`](../done/parquet-export-import-evaluation.md)
> (Hauptplan + Closure),
> [`parquet-libraries.md`](../done/parquet-libraries.md)
> (Bibliotheks-/Dependency-Skizze),
> [`parquet-port-shape.md`](../done/parquet-port-shape.md)
> (AP10 Reader-Port),
> [`parquet-manifest-format.md`](../done/parquet-manifest-format.md)
> (AP7 Bundle-Manifest),
> [`parquet-directory-import.md`](../done/parquet-directory-import.md)
> (AP8 Resolver),
> [`parquet-import-input-dto.md`](../done/parquet-import-input-dto.md)
> (AP9 DTO),
> [`parquet-single-file-metadata.md`](../done/parquet-single-file-metadata.md)
> (AP11 Footer-KV),
> [`parquet-cli-wiring.md`](../done/parquet-cli-wiring.md)
> (AP12 CLI-/Factory-Wiring, **§12 Implementierungsreihenfolge
> ist bindender Sub-Slice-Plan**),
> [`parquet-decision-template.md`](../done/parquet-decision-template.md)
> (AP13; §8 ist die aktuelle normative Entscheidung).
>
> Branch: `feature/parquet-0.9.8`. Aufwand: Brutto 35-60 PT,
> Netto Voll-Scope 29.5-47 PT (AP13 §3.2 Tabelle, Cut A).

---

## 1. Trigger und Anlass

Der Umbrella entsteht aus der Beobachtung, dass die
Closure-Commit ff617d05 (`docs(planning): Parquet-Plan-Doc +
10 Sub-Docs nach done/ migriert`) **nur die
Evaluierungsphase** abgeschlossen hat. Die produktive
Cut-A-Linie ist nach Code-Sichtung 2026-06-06 noch nicht
angefangen — der Spike unter
`adapters/driven/formats-parquet/src/main/kotlin/.../spike/`
existiert, der produktive Pfad (Ports, Factories, CLI-Wiring,
Dependency-Schaerfung, Footprint-Minimierung) noch nicht.

### 1.1 Konkrete Befunde (Code-Sichtung 2026-06-06)

Vier Befunde belegen die Luecke und sind **bindender Input
fuer die Sub-Slices**:

1. **Produktiver Port-/Resolver-Vertrag (AP10 §7.1, AP12 §5)
   nicht umgesetzt.** `SeekableDataChunkReaderFactory`,
   `SeekableChunkSource`, `ParquetChunkReader`,
   `ParquetChunkWriter` fehlen.
   `hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/DataExportFormat.kt:10`
   kennt nur `JSON`/`YAML`/`CSV`.
   `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt:22`
   und `DefaultDataChunkWriterFactory.kt:23` verdrahten nur
   die drei Bestandsformate.
2. **CLI nicht auf Parquet verdrahtet — Import und Export.**
   `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt:41`
   erlaubt nur `.choice("json", "yaml", "csv")`,
   `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt:35`
   erlaubt nur `.choice("json", "yaml", "csv").required()`.
   Die in
   [`parquet-cli-wiring.md`](../done/parquet-cli-wiring.md)
   §4 geforderte Pfad-only/Stdin-Ablehnung fuer Parquet
   existiert in beiden Commands produktiv nicht.
3. **Dependency-Aussage zu stark.** `parquet-avro`,
   `parquet-protobuf`, `snappy-java` und `zstd-jni` sind
   nicht im `runtimeClasspath`. `dependencyInsight` zeigt
   aber `org.apache.avro:avro:1.9.2` transitiv ueber
   `hadoop-common` und `hadoop-mapreduce-client-core`. Die
   Aussage „kein Avro-/Protobuf-Reflection-Pfad im
   Klassenpfad" in
   [`parquet-libraries.md`](../done/parquet-libraries.md)
   §6 AP1.b (`parquet-libraries.md:344-347`) ist damit zu
   stark formuliert; sie meint korrekt „kein Avro-/
   Protobuf-Reflection-**Schreib**-Pfad", die Lese-/
   Datenklassen `org.apache.avro:avro` werden transitiv
   ueber Hadoop dennoch eingezogen. Die Constraints in
   `adapters/driven/formats-parquet/build.gradle.kts:50`
   blocken nur `org.apache.parquet:parquet-avro`, nicht
   Hadoop-Avro.
4. **Hadoop-Footprint im Default-JAR.** Der Runtime-Tree
   zieht weiterhin HDFS/YARN, Jersey, reload4j, Zookeeper
   und Netty-Pakete via Hadoop-Transitiven.
   [`parquet-libraries.md`](../done/parquet-libraries.md)
   §8 nennt das als Folgeaufgabe. Cut A **inventarisiert**
   das in S10a (Footprint-Snapshot fuer 1.0.0-Input),
   minimiert aber **nicht** in 0.9.8 — Footprint-
   Minimierung gehoert zum 1.0.0-Engineering-Reife-Punkt
   (Native-Image-Cut + Distributions-Cut + Hadoop-API-Shim,
   AP13 §8.3). Die Distributions-Frage (Default-JAR vs.
   `--parquet`-Variante) bleibt damit konsistent **nicht**
   in diesem Umbrella.

Diese vier Befunde sind keine Plan-Aenderungen — sie sind
**Verifikationen der bestehenden Plan-Annahmen** und werden
in den Sub-Slices unten als „Definition of Done"-Anker
benutzt.

---

## 2. Pre-Implementation-Aufgaben (aus AP13 §7, §8.5)

Aus
[`parquet-decision-template.md`](../done/parquet-decision-template.md)
§7 (⏳-Marker), in §8.5 fuer Cut A bestaetigt:

1. **Engineering-Goal-Commit fuer Cut A** — Zeitbudget
   (Brutto 35-60 PT) und Reviewer-Verfuegbarkeit beim
   0.9.8-Sprint-Planning festlegen.
2. **Sealed-`rg`-Sweep-Befehle** aus
   [`parquet-decision-template.md`](../done/parquet-decision-template.md)
   §4.1 / AP12 §8 in die PR-Checkliste oder einen
   Tooling-Hook aufnehmen (`gradle assemble
   --warning-mode=fail` ist zusaetzlich, ersetzt den Sweep
   nicht).
3. **`feature/parquet-0.9.8`-Branch** anlegen.
4. **Erster Implementierungs-Commit** mit Slice **S0**
   (AP2 `ChunkSchema`-Typ + `DataChunkWriter.begin`-
   Migration) starten — die in AP12 §12 Schritt 1
   geforderte `DataExportFormat.PARQUET`-Erweiterung
   wandert nach S3, weil ein Stopgap-`when`-Branch
   ohne Handler dem Auto-Memory-Eintrag
   [[no-carveouts]] widerspricht (Feedback-Memo des
   Users, nicht im Repo abgelegt).

Der vormalige Native-Image-Punkt ist hier gestrichen, weil
er erst nach S3 ueberhaupt sinnvoll lauft; Native-Image-
Befund-Erhebung lebt jetzt ausschliesslich als Slice-DoD
von S10b (siehe §3 / §4.2), nicht als Sprint-Start-Gate.

Status der vier Punkte wird in §5 per abgeschlossen-Marker
(`- [x]`) gefuehrt; Aenderung mit Datum + Commit-Ref im
Status-Header.

---

## 3. Sub-Slice-Plan (Cut A — Restrukturierung gegenueber AP12 §12)

Die Reihenfolge folgt grundsaetzlich
[`parquet-cli-wiring.md`](../done/parquet-cli-wiring.md)
§12, aber mit drei strukturellen Anpassungen gegenueber dem
AP12-Vorschlag (Befund-Audit 2026-06-06):

1. **S0 + S0b NEU vor S2** (Split eingefuehrt 2026-06-06,
   Befund-Audit waehrend der S0-Vorbereitung): AP2 selbst
   gibt die Reihenfolge AP2.a → AP2.b → AP2.c → AP2.d vor
   ([`parquet-schema-source.md`](../done/parquet-schema-source.md)
   §7); `ChunkColumnSchema.neutralType` ist non-null
   (§6.1). Ein einzelner S0-Slice, der gleichzeitig
   Typanlage und `begin`-Migration macht, wuerde
   `StreamingExporter` zwingen, schon vor AP2.b/c einen
   `NeutralType` pro Spalte zu erfinden — entweder per
   `NeutralType.Text()`-Default (semantisch falsche Daten)
   oder per nullable-Deviation am AP2-Vertrag. Beide
   Wege widersprechen Memo [[no-carveouts]]. Stattdessen:
   - **S0**: nur Typanlage (`ChunkSchema`,
     `ChunkColumnSchema`, `SchemaOrigin` in
     `hexagon:ports-common`) plus Dockerfile-Warmup-Fixup;
     `DataChunkWriter.begin(table, columns)` bleibt
     unveraendert.
   - **S0b**: JDBC→`NeutralType`-Mapping (AP2.b),
     Nullability-Resolver (AP2.c), `StreamingExporter`
     baut `ChunkSchema` vor dem ersten Chunk, dann
     `DataChunkWriter.begin(table, schema)`-Migration
     (AP2.d) plus JSON/YAML/CSV-Writer-Anpassung.
2. **S1 aus AP12 §12 entfaellt** als eigener Slice. AP12
   §12-Schritt 1 hatte „`DataExportFormat.PARQUET` +
   Sealed-when-Sweeps" als Vorbereitung; unter Cut A
   (kein Stopgap-Branch im Default-Factory-`when` per
   Auto-Memory-Eintrag [[no-carveouts]]) ist die
   Enum-Erweiterung
   nicht trennbar von der Handler-Registrierung. Beides
   wandert in S3, wo der Parquet-Handler tatsaechlich
   existiert.
3. **S10 in S10a/S10b gesplittet** (siehe vorhin): S10a
   vor S3 (Dependency-Hygiene + Footprint-Inventar),
   S10b nach S3 (Native-Image-Befund).

Jeder Slice ist ein eigener Closure-Plan-Doc unter
`docs/planning/done/ImpPlan-0.9.8-parquet-S<N>-…md`
(analog zur 0.9.x-Konvention) und wird beim Abschluss aus
diesem Umbrella per Commit-Tabelle referenziert.

| Slice | Inhalt | Definition of Done |
| ----- | ------ | ------------------ |
| **S0** | **AP2 ChunkSchema-Typ (nur AP2.a) + Dockerfile-Warmup-Fixup** ([`parquet-schema-source.md`](../done/parquet-schema-source.md) §6.1). `ChunkSchema` + `ChunkColumnSchema` + `SchemaOrigin` in `hexagon:ports-common` mit `neutralType: NeutralType` non-null (AP2 §6.1 bindend). `DataChunkWriter.begin(table, columns: List<ColumnDescriptor>)` bleibt **unveraendert** — die Migration ist S0b. **Zusaetzlich** Dockerfile-Warmup-Block (`Dockerfile:75-95`) um die fehlende COPY-Zeile `adapters/driven/formats-parquet/build.gradle.kts` ergaenzen — `settings.gradle.kts:24` listet das Modul, der Warmup-Layer hatte es bisher nicht, was alle `make docker-* MODULES=":adapters:driven:formats-parquet"`-Aufrufe spaeterer Slices (S10a/S3/S9a/S9b) brechen wuerde. | `ChunkSchema`/`ChunkColumnSchema`/`SchemaOrigin` existieren in `hexagon:ports-common`; **Dockerfile-Warmup enthaelt `formats-parquet`-Buildfile**; `make docker-check` (gesamtes Repo) gruen; `make docker-test MODULES=":adapters:driven:formats :adapters:driven:formats-parquet"` gruen (letzteres Spike-Tests; verifiziert Dockerfile-Fixup). |
| **S0b** | **AP2.b/c-Mapping + DataChunkWriter.begin-Migration (AP2.d)**. JDBC→`NeutralType`-Mapping (AP2.b Mapping-Tabelle aus [`parquet-schema-source.md`](../done/parquet-schema-source.md) §8) plus Nullability-Resolver mit Provenance (AP2.c §9); `StreamingExporter`/`TableExporter` bauen `ChunkSchema` aus `ResultSetMetaData` + optional `SchemaReader` vor dem ersten Chunk; `DataChunkWriter.begin(table, columns: List<ColumnDescriptor>)` wandert auf `begin(table, schema: ChunkSchema)`; JSON/YAML/CSV-Writer lesen nur Name/Nullability aus `schema.columns` (Verhalten unveraendert). | Mapping-Tabelle + Resolver in `:adapters:driven:streaming` umgesetzt; `DataChunkWriter`/JSON/YAML/CSV migriert; alle bestehenden Tests gruen; `make docker-check` (gesamtes Repo) gruen; `make docker-test MODULES=":adapters:driven:formats :adapters:driven:streaming"` gruen. |
| S2 | **Port-only**: `SeekableDataChunkReaderFactory`-Port-Interface in `hexagon:ports-read` (AP10 §4, AP12 §5.3) **plus `ResolvedTableInput`-Sealed-Restrukturierung** (`adapters/driven/streaming/.../ResolvedTableInput.kt`): aus der heutigen `data class` werden `sealed class ResolvedTableInput` + `Stream` (Bestandsverhalten, `openInput`) + `Seekable` (AP10 §3.2 — Pfad + Footer-Metadaten). **Keine Default-Impl der `SeekableDataChunkReaderFactory`** in diesem Slice — die einzige produktive Impl ist `ParquetSeekableDataChunkReaderFactory` und wandert in S3 zusammen mit dem Reader. Default-Factories und JSON/YAML/CSV-Verbraucher konsumieren `.Stream`; `.Seekable`-Konsum kommt in S7. | Port existiert in `hexagon:ports-read` (keine Impl); `ResolvedTableInput`-Sealed-Struktur existiert; bestehende Stream-Konsumenten (`TableImporter` etc.) auf `.Stream`-Subtyp angepasst, Tests gruen. |
| **S10a** | **Dependency-Hygiene + Footprint-Inventar** (siehe §4 unten) — Avro-Klemme aus Befund 3 nach Pfad A (Reject) **oder** Pfad B (akzeptierte Rest-Dependency) abschliessen; Footprint-Snapshot aus Befund 4 in `parquet-libraries.md` §8 als 1.0.0-Input zurueckspielen. **Keine** Excludes/Constraints fuer Hadoop-Footprint-Transitive (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty) in 0.9.8; Avro-Excludes aus Pfad A sind davon **ausgenommen**. | Constraint-Block in `formats-parquet/build.gradle.kts` ist entweder nach Pfad A (`parquet-avro`/`parquet-protobuf` + `org.apache.avro:avro` rejecten, plus Excludes auf den Hadoop-Deps) oder nach Pfad B (nur `parquet-avro`/`parquet-protobuf` rejecten, `org.apache.avro:avro` mit `because(...)`-Dokumentation belassen) umgesetzt — nicht beides gemischt. Footprint-Inventar als 1.0.0-Input dokumentiert. Spike-Tests (AP3/AP4/AP5/AP6) gruen via `make docker-test MODULES=":adapters:driven:formats-parquet"`. |
| S3 | `ParquetChunkReader` + `ParquetChunkWriter` produktiv (AP3-Spike-Linie + AP2 §6.1 + AP10 §3.3) plus **`ParquetChunkReaderFactory` + `ParquetChunkWriterFactory` + `ParquetSeekableDataChunkReaderFactory`** im Modul `adapters:driven:formats-parquet` (AP12 §5.2 bindend: `Default…Factory` bleibt Hadoop-/Parquet-frei) plus **`DataExportFormat.PARQUET`-Enum** plus **Contract-Branches in `DefaultDataChunkReaderFactory`/`WriterFactory`**: Reader-Seite `PARQUET -> error("DefaultDataChunkReaderFactory does not support Parquet; Parquet reads go through StreamingImporter's seekableReaderFactory (ParquetSeekableDataChunkReaderFactory)")` — Writer-Seite `PARQUET -> error("DefaultDataChunkWriterFactory does not support Parquet; use ParquetChunkWriterFactory via the CLI CompositeDataChunkWriterFactory")` — beide symmetrisch zu AP12 §5.2 `ParquetChunkWriterFactory.create`'s `require(format == PARQUET)`. Der Contract-Branch ist **keine Stopgap-Auslagerung an einen spaeteren Slice**, sondern dauerhafte Domain-Aussage: Default-Factory wird Parquet **nie** behandeln, der CLI-Composite (S6) routet `PARQUET` an die Parquet-Factory. Sealed-`when (format)`-Sweep aus AP12 §8 ist damit hier vollstaendig (Default-Factories + ggf. weitere `when (format)`-Stellen, die heute exhaustive sind). | Befund 1 (Enum) aufgeloest; produktive Reader/Writer-Klassen existieren neben dem AP3-Spike (siehe §3.3); Default-Factories haben den Contract-Branch, aber **keine** Parquet-/Hadoop-Imports oder -Dependencies (Beleg: `grep "parquet\|hadoop" adapters/driven/formats/build.gradle.kts` leer); `make docker-check` (gesamtes Repo) gruen; JSON/YAML/CSV-Tests bleiben gruen. |
| **S10b** | **Native-Image-Befund nach S3** — Sondierungslauf gegen die produktiven Klassen aus S3 + S10a-Constraints. **Kein gruenes Gate fuer 0.9.8**, nur Befund-Erhebung als 1.0.0-Input (Native-Image-Cut ist 1.0.0-Aufgabe per AP13 §8.3). | Befund (Reachability-Probleme, fehlende `reflect-config`, JNI-/Service-Loader-Loecher) in S10b-Closure-Doc dokumentiert und in `parquet-libraries.md` §8 als 1.0.0-Input zurueckgespielt. |
| S3b | `ParquetManifestWriter` + `StreamingExporter`-Bundle-Closure (AP7 §10.1; `--manifest-sha256` opt-in). | Bundle-Export schreibt `manifest.yaml` nach allen Tabellen; `ExportOutput.FilePerTable` um Closure-Hook erweitert. |
| S4 | `ParquetSingleFileManifestReader`/`Writer` + `ParquetSingleFilePreflight.phase1/phase2` (AP11). | Footer-KV `d-migrate.manifest` Round-Trip lesend/schreibend; Phase-1/2-Trennung produktiv. |
| S5a | `ParquetBundlePreflight` + `ParquetBundleResolver` + `ParquetBundleAdapter` (AP7/AP8/AP9) **plus den `ImportInputResolver`-when-Zweig fuer `ResolvedBundle`** (AP12 §5.1, `adapters/driven/streaming/.../ImportInputResolver.kt`). Liefert `ResolvedTableInput.Seekable`-Werte; **End-to-End-Konsum durch `TableImporter`/`StreamingImporter` ist S7**, nicht hier. | Sealed-when-Faelle fuer `ImportInput.ResolvedBundle` erschoepfend; Resolver produziert `Seekable`-Subtyp; `ParquetBundlePreflight` gibt erwartete Fehlerklassen (AP7); Adapter sammelt Metadaten. Bundle-Import laeuft End-to-End erst nach S7. |
| S5b | `ImportInput.ResolvedSingleFile`-Sealed-Variante + den `ImportInputResolver`-when-Zweig fuer `ResolvedSingleFile` (AP12 §5.1). **Keine** Runner-Hook-Arbeit hier (S6) und **kein** End-to-End-Konsum (S7). | Sealed-Variante existiert, `ImportPreflightValidator` kennt sie (AP12 §8.1), Resolver-when-Zweig liefert `Seekable`-Subtyp. |
| S6 | CLI-Wiring fuer **Import und Export** (`DataImportWiring` + `DataImportCommand` + `DataExportWiring` + `DataExportCommand`, AP12 §4, §5). Reader-Seite: **kein** Composite (der `DataChunkReaderFactory`-Port nimmt nur `InputStream`); stattdessen wird laut AP12 §5.1 `StreamingImporter` um den Pflicht-Konstruktor-Parameter `seekableReaderFactory: SeekableDataChunkReaderFactory` erweitert und im CLI mit `ParquetSeekableDataChunkReaderFactory()` aus S3 verdrahtet. Writer-Seite: `CompositeDataChunkWriterFactory` im CLI-Modul, das `Default…Factory` + `ParquetChunkWriterFactory` aus S3 zusammenfuehrt (AP12 §5.2). Plus **Phase-2-Hook im `DataImportRunner`** plus `--no-checkpoint`-Wiring (AP12 §4.2). S6 ist die exklusive Heimat des Composite-Writers, des `seekableReaderFactory`-Wirings, des Runner-Hooks und der CLI-Flags. | Befund 2 (CLI `--format parquet`) aufgeloest fuer **Import und Export**: `.choice(...)` beider Commands enthaelt `parquet`, Pfad-only/Stdin-Ablehnung produktiv, `StreamingImporter`-Constructor hat den neuen Pflichtparameter, `CompositeDataChunkWriterFactory` im CLI-Wiring; Phase-2-Hook im Runner mit Single-File-Fall verifiziert. |
| **S7** | **End-to-End-Integration** — `TableImporter` + `StreamingImporter`-Constructor verstehen `ResolvedTableInput.Seekable`-Subtyp; Sealed-when-Sweep auf alle `.Stream`/`.Seekable`-Konsumenten. **Seekable-Dispatch-Tests** (mit Fakes) bleiben in `:adapters:driven:streaming`, weil das Modul **keine** `formats-parquet`-Dependency hat. **Echter E2E-Pfad** (Bundle-Import + Single-File-Import gegen eine echte Tabelle, mit produktivem `ParquetSeekableDataChunkReaderFactory` aus S3) gehoert in `:test:e2e-cli`, wo CLI + Adapter (inkl. `formats-parquet` ueber das CLI-Wiring aus S6) zusammenkommen. `:test:e2e-cli:test` ist im Root-Build durch das `-PintegrationTests`-Gate geschuetzt (`build.gradle.kts:86`); ohne das Property wird der E2E-Lauf als skipped durchgereicht. | Importer-Pfad fuer `Seekable`-Subtyp produktiv; **Seekable-Dispatch-Tests** (Fakes) gruen via `make docker-test MODULES=":adapters:driven:streaming"`; **E2E-Fixture** (echter Pfad → Tabelle → Daten, Bundle und Single-File) gruen via `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"` (das Property ist Pflicht, sonst wird das Gate ohne Ausfuehrung als skipped gemeldet). |
| S8 | Checkpoint-Erweiterung (`FileCheckpointStore` + `ImportCheckpointManager` + `InputContext`, AP12 §7) **inkl. Bundle und Single-File**. | Beide Specifics persistiert; Pre-AP8-Bruch fuer Bundle dokumentiert (Release-Note). |
| S9a | Bundle-Tests: CLI-Preflight (Bundle-Codes), Format-Resolver (`manifest.yaml`-Hook), Bundle-Resume-Familie, DuckDB-/Arrow-Bundle-KV-Toleranz (AP12 §11). | Vier Test-Familien gruen via `make docker-test MODULES=":hexagon:application :adapters:driven:streaming :adapters:driven:formats-parquet :adapters:driving:cli"` (Preflight/Resume in `hexagon:application`, Resolver in `streaming`, KV-Toleranz in `formats-parquet`, CLI-Codes in `cli`). |
| S9b | Single-File-Tests: CLI-Preflight (Single-File-Codes), Phase-1/2-Tests, Single-File-Resume, DuckDB-/Arrow-Single-File-KV-Toleranz (AP12 §11). | Vier Test-Familien gruen via `make docker-test MODULES=":hexagon:application :adapters:driven:streaming :adapters:driven:formats-parquet :adapters:driving:cli"` (Phase-1/2/Resume in `hexagon:application`, Resolver in `streaming`, KV-Toleranz in `formats-parquet`, CLI-Codes in `cli`). |

Reihenfolge `S0 → S0b → S2 → S10a → S3 → S10b → S3b → S4 → S5a →
S5b → S6 → S7 → S8 → S9a → S9b`. Slices innerhalb der
gleichen Spalte (S5a/S5b, S9a/S9b) koennen parallel laufen,
sind aber separate Plan-Closure-Docs.

### 3.4 Slice-Status (Stand 2026-06-06)

Pflege diese Tabelle mit Commit-Ref pro Slice-Closure;
**S6/S7/S8/S9a/S9b** sind die offenen Punkte.

| Slice | Status | Commit-Ref | Closure-Doc |
| ----- | ------ | ---------- | ----------- |
| S0    | closed | `9c840986` | [`ImpPlan-0.9.8-parquet-S0-chunk-schema.md`](../done/ImpPlan-0.9.8-parquet-S0-chunk-schema.md) |
| S0b   | closed | `7670a393` | [`ImpPlan-0.9.8-parquet-S0b-chunk-schema-migration.md`](../done/ImpPlan-0.9.8-parquet-S0b-chunk-schema-migration.md) |
| S2    | closed | `40d7c551` | [`ImpPlan-0.9.8-parquet-S2-seekable-port.md`](../done/ImpPlan-0.9.8-parquet-S2-seekable-port.md) |
| S10a  | closed | `2b5826d8` + `c6a09cae` (Befund-Korrektur) | [`ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md`](../done/ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md) |
| S3    | closed | `0a992c0c` | [`ImpPlan-0.9.8-parquet-S3-parquet-reader-writer.md`](../done/ImpPlan-0.9.8-parquet-S3-parquet-reader-writer.md) |
| S10b  | closed | `9ba956ff` | [`ImpPlan-0.9.8-parquet-S10b-native-image-finding.md`](../done/ImpPlan-0.9.8-parquet-S10b-native-image-finding.md) |
| S3b   | closed | `97c74757` | [`ImpPlan-0.9.8-parquet-S3b-bundle-manifest.md`](../done/ImpPlan-0.9.8-parquet-S3b-bundle-manifest.md) |
| S4    | closed | `28048ef2` | [`ImpPlan-0.9.8-parquet-S4-single-file-footer-kv.md`](../done/ImpPlan-0.9.8-parquet-S4-single-file-footer-kv.md) |
| S5a   | closed | `24cbf4c5` | [`ImpPlan-0.9.8-parquet-S5a-bundle-preflight.md`](../done/ImpPlan-0.9.8-parquet-S5a-bundle-preflight.md) |
| S5b   | closed | `4279c326` | [`ImpPlan-0.9.8-parquet-S5b-single-file-resolved.md`](../done/ImpPlan-0.9.8-parquet-S5b-single-file-resolved.md) |
| S6    | offen  | —          | — |
| S7    | offen  | —          | — |
| S8    | offen  | —          | — |
| S9a   | offen  | —          | — |
| S9b   | offen  | —          | — |

Die `[Unreleased]`-Sektion in `CHANGELOG.md` bekommt erst
beim Umbrella-Closure (DoD §7 Punkt 4) die vollstaendige
Slice-Commit-Tabelle als `[0.9.8]`-Eintrag — siehe AP13 §6.1
zum Branch-Lifecycle (Merge in `develop` nach Schritt 9 =
S9b-Abschluss).

**Vor Mid-Stream-Review** (Engineering-Goal §5.1, von
2026-06-06): die zehn Closed-Slices oben sind der
naechste Review-Block; S6 und S7 hangen
designtechnisch davon ab (Bundle-/Single-File-Adapter,
seekableReaderFactory-Pfad), darum ist S5b der
sinnvolle Pausepunkt fuer ein `/code-review` auf die
Linie.

### 3.2 Verifikationsbefehle (Make/Docker)

Direkte `./gradlew`-Aufrufe sind im Repo gesperrt
(Auto-Memory-Eintrag [[docker-build-test]]). Bindende
DoD-Befehle pro Slice:

- **Compile/Tasks pro Modul:**
  `make docker-check MODULES=":<modul>"` —
  fuer **S0b** (Writer-Signaturmigration) und **S3**
  (Enum-Erweiterung) lohnt sich `MODULES=""` (kompletter
  Repo-Sweep, weil die Aenderungen in beliebigen Modulen
  Sealed-`when`-Folgen triggern koennen).
- **Modul-Tests:**
  `make docker-test MODULES=":<modul>"`.
- **Smoke gegen das Distributions-Artefakt:**
  `make docker-smoke`.
- **Dependency-Beleg fuer S10a** (zwei getrennte Belege,
  beide im S10a-Closure-Doc dokumentiert; **kein** direkter
  `./gradlew`-Aufruf):
  - **Avro-Klemme** (Befund 3): expliziter
    `dependencyInsight --dependency org.apache.avro:avro
    --configuration runtimeClasspath` auf
    `:adapters:driven:formats-parquet` — zeigt, ueber
    welche Transitiven Avro reinkommt und welche Version
    aufgeloest wird. Pflichtbefund fuer Pfad A/B-Wahl
    (§4.1).
  - **Footprint-Snapshot** (Befund 4): `dependencies
    --configuration runtimeClasspath` auf demselben Modul
    — komplette Transitive-Liste als 1.0.0-Input.
  Bis ein eigener Make-Target (`docker-deps` o.ae.) im
  S10a-Closure-Doc vorgeschlagen wird, laufen beide
  Befehle als einmalige `docker build --target build
  --build-arg GRADLE_TASKS=…`-Aufrufe (analog zur
  `docker-check`-Mechanik aus `Makefile`).

### 3.3 S3 — Behandlung des AP3-Spike-Codes

`adapters/driven/formats-parquet/src/main/kotlin/.../spike/`
und die zugehoerigen Tests
(`ParquetSpike*Test.kt`,
`ParquetSpikeDuckDbReadTest.kt`,
`ParquetSpikeArrowInspectTest.kt`,
`ParquetSpikeImportPathTest.kt`) bleiben grundsaetzlich
erhalten. S3 ergaenzt die produktiven Klassen
(`ParquetChunkReader`/`Writer`) **neben** dem Spike. Drei
Optionen pro Spike-Test:

- **Portierung**: Test wird auf die produktive Klasse
  umgestellt, der Spike-Pfad entfaellt fuer diesen Test.
- **Doppelung als Regressionstest**: Spike-Pfad bleibt mit
  Marker (`@Tag("spike-regression")`), produktiver Test wird
  neu hinzugefuegt.
- **Entfernen**: nur, wenn der Test gegen den Spike das
  zugehoerige Plan-Doc-Detail bereits in `done/`-Sub-Doc
  zurueckgespielt hat und keinen eigenen Wert mehr hat
  (Begruendung im S3-Closure-Doc).

Die Entscheidung pro Test wird im S3-Closure-Doc
festgenagelt; der Default ist **Portierung mit Aufhebung des
Spike-Pfads im produktiven Test** plus Erhaltung des
Spike-Moduls als Smoke-Modul-Linie, solange der AP3-Spike-
Inhalt nicht durch produktive Klassen vollstaendig abgedeckt
ist.

---

## 4. S10a / S10b — Dependency-Hygiene + Footprint-Inventar (Befunde 3 + 4)

Zwei kleine Slices, weil der Runtime-Classpath den Bau der
produktiven Reader/Writer beeinflusst. **Footprint-
Minimierung (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty-
Excludes) ist 1.0.0-Aufgabe**, nicht 0.9.8 — AP13 §8.3 hat
1.0.0 explizit fuer Native-Image-Cut, Distributions-Cut und
Hadoop-API-Shim reserviert; eine Footprint-Minimierung in
0.9.8 wuerde 0.9.8 wieder an die Engineering-Reife-Themen
binden, die AP13 §8.2 davon trennen wollte. AP13 §6.2 i.V.m.
§8.4 stellt fuer 0.9.8 normativ klar: **Parquet ist Teil des
Default-JAR**, und der Distributions-Cut wird mit 1.0.0
entschieden.

### 4.1 S10a — Dependency-Hygiene + Footprint-Inventar (vor S3)

In-Scope (Befund 3):

- `dependencyInsight`-Snapshot gegen `runtimeClasspath` des
  `:adapters:driven:formats-parquet`-Moduls erzeugen
  (Befehlspfad siehe §3.2 — direkter `./gradlew`-Aufruf
  gesperrt). Snapshot dokumentiert
  `org.apache.avro:avro:1.9.2` transitiv ueber
  `hadoop-common` + `hadoop-mapreduce-client-core`.
- **Entscheidung A oder B treffen — nicht mischen:**
  - **Pfad A — Reject + Exclude.** Avro wird komplett
    aus dem `runtimeClasspath` verbannt:
    `org.apache.avro:avro` per Constraint mit
    `version { rejectAll() }` (analog
    `parquet-avro`/`parquet-protobuf`), plus explizite
    `exclude(group = "org.apache.avro")` auf
    `hadoop-common` und `hadoop-mapreduce-client-core`.
    Bedingung: AP3-Spike-Tests (`make docker-test
    MODULES=":adapters:driven:formats-parquet"`) muessen
    nach den Excludes weiterhin gruen sein — Beleg, dass
    kein konsumierter Hadoop-Code-Pfad Avro-Klassen
    referenziert. Bei roten Tests wird Pfad A verworfen
    und auf Pfad B umgeschwenkt.
  - **Pfad B — Akzeptierte Rest-Dependency.** Nur
    `parquet-avro`/`parquet-protobuf` bleiben rejected
    (Status quo); `org.apache.avro:avro` bleibt
    transitive Dependency, wird aber explizit mit
    `because("akzeptierte Rest-Dependency — Hadoop-Common/
    MapReduce ziehen Avro-Datenklassen, kein
    Reflection-Schreib-Pfad in d-migrate")` dokumentiert.
    Kein `rejectAll()` auf `org.apache.avro:avro`, kein
    Exclude.
- [`parquet-libraries.md`](../done/parquet-libraries.md)
  §6 AP1.b (Zeilen 344-347) auf
  „kein Avro-/Protobuf-Reflection-**Schreib**-Pfad im
  Klassenpfad" verschaerfen und je nach gewaehltem Pfad
  (A: Avro raus / B: Avro als Datenklassen-Dependency
  akzeptiert) klarstellen (Befund-Rueckspiel-Commit
  analog zu 5ca1497f).

In-Scope (Befund 4 — nur Inventar):

- Hadoop-Footprint-Transitive (HDFS, YARN, Jersey,
  reload4j, Zookeeper, Netty-Pakete) im `runtimeClasspath`
  inventarisieren — Snapshot in den S10a-Closure-Doc und in
  [`parquet-libraries.md`](../done/parquet-libraries.md)
  §8 als 1.0.0-Input zurueckspielen.
- **Keine** Excludes oder Constraints fuer diese
  Hadoop-**Footprint**-Transitiven (HDFS/YARN/Jersey/
  reload4j/Zookeeper/Netty) in 0.9.8 — die Minimierung
  selbst ist 1.0.0-Arbeit. Davon **ausgenommen** sind die
  Avro-Excludes aus Pfad A oben (`org.apache.avro:avro`
  auf den beiden Hadoop-Deps): diese gehoeren zur
  Dependency-Hygiene aus Befund 3, nicht zur
  Footprint-Minimierung aus Befund 4.

DoD S10a:

- Pfad A **oder** Pfad B umgesetzt — die Wahl ist im
  S10a-Closure-Doc begruendet (mit Verweis auf das
  AP3-Spike-Test-Ergebnis nach probeweisem Exclude).
  Build-Script und
  [`parquet-libraries.md`](../done/parquet-libraries.md)
  §6 AP1.b sind konsistent auf dieselbe Wahl ausgerichtet.
- Footprint-Snapshot existiert und ist in
  [`parquet-libraries.md`](../done/parquet-libraries.md)
  §8 referenziert.
- **Parquet-Pfad gruen** gegen den geschaerften Classpath:
  `make docker-test MODULES=":adapters:driven:formats-parquet"`
  (AP3/AP4/AP5/AP6-Spike-Tests). Das ist die primaere
  Verifikation, weil S10a genau diesen Classpath
  veraendert.
- **Bestandsformate** reagieren nicht (Sanity):
  `make docker-test MODULES=":adapters:driven:formats"`
  (JSON/YAML/CSV).

### 4.2 S10b — Native-Image-Befund (nach S3)

S10b ist **kein gruenes Gate fuer 0.9.8**, sondern eine
Befund-Sondierung. Native-Image-Cut ist laut AP13 §8.3
1.0.0-Aufgabe; S10b erzeugt den 1.0.0-Input, ohne dass
0.9.8 davon abhaengt.

In-Scope:

- Native-Image-Sondierungslauf gegen die in S3 erstellten
  produktiven Klassen (`ParquetChunkReader`/`Writer`) +
  die S10a-Constraints. Ziel: festhalten, was fehlt
  (Reachability-Metadaten, `reflect-config`-Eintraege,
  JNI-/Service-Loader-Pfade, Hadoop-Footprint-Effekte),
  nicht: gruenen Build zu erzwingen.
- Befund-Dokumentation:
  - im S10b-Closure-Doc (was lief, was scheiterte,
    welche Konfigurations-Stubs entstehen),
  - Rueckspiel nach
    [`parquet-libraries.md`](../done/parquet-libraries.md)
    §8 als 1.0.0-Input (analog AP3-Befund-Rueckspiel
    `5ca1497f`).
- **Keine** CI-Job-Definition fuer einen gruenen Native-
  Image-Build in 0.9.8. Wenn der Befund unkompliziert
  gruen wird, kann der CI-Job optional als 1.0.0-Vorbote
  in 0.9.8 mitlaufen, aber **nicht als Closure-Gate**.

DoD S10b:

- Befund-Doc existiert; Reachability-Loecher pro Klasse/
  Modul aufgelistet (oder leere Liste mit Beleg).
- Rueckspiel-Commit in
  [`parquet-libraries.md`](../done/parquet-libraries.md)
  §8 ist drin.
- Wenn S10a Constraint-Aenderungen brachte: Beleg, ob das
  Native-Image-Verhalten dadurch besser/gleich/schlechter
  wird (qualitative Note, kein gruener Lauf gefordert).

---

## 5. Cut A — Aktive Pre-Implementation-Aufgaben (Status)

Pflege diese Liste mit Datum + Commit-Ref pro Abschluss:

- [x] **PI-1** Engineering-Goal-Commit (Zeitbudget,
  Reviewer) — 2026-06-06, festgenagelt in §5.1.
- [x] **PI-2** Sealed-`rg`-Sweep-Befehle in PR-Checkliste +
  Make-Target aufgenommen — 2026-06-06, siehe §5.2.
- [x] **PI-3** `feature/parquet-0.9.8`-Branch angelegt von
  diesem Commit aus — 2026-06-06.
- [x] **PI-4** Erster Implementierungs-Commit S0 (AP2.a
  `ChunkSchema`/`ChunkColumnSchema`/`SchemaOrigin` +
  Dockerfile-Warmup-Fixup) — 2026-06-06, siehe
  [`ImpPlan-0.9.8-parquet-S0-chunk-schema.md`](../done/ImpPlan-0.9.8-parquet-S0-chunk-schema.md).

### 5.1 Engineering Goal (PI-1, 2026-06-06)

**Zeitbudget: Single-Session-Durchzug, kein Sprint-
Splitting.** Cut A wird in einem Rutsch (eine
Arbeitssitzung mit pt9912 + Claude) von S0 bis S9b
durchgezogen, ohne Kalender-Splitting. Die AP13 §3.2-
Bandbreite (Brutto 35-60 PT) bleibt als **Referenz-
Aufwand**, nicht als hartes PT-Budget — es gibt keinen
Sprint-Counter, der herunterzaehlt. Stoppen-Bedingung
ist nicht das Budget, sondern eine **Risiko-Eskalation
gemaess §8** (Footprint-Ueberraschung, Plan-Doc-Drift,
Single-File-Phase-1/2-Komplexitaet). Falls eine
Eskalation triggert, gilt der Re-Scope-Pfad aus §8:
explizite Entscheidung mit Versions-Bump und DoD-Update,
kein stilles Cut-B-Fallback.

**Reviewer: pt9912 + Claude `/code-review` pro Slice-PR.**
Jeder Sub-Slice (S0, S2, S10a, S3, S10b, S3b, S4, S5a,
S5b, S6, S7, S8, S9a, S9b) landet als eigener PR auf
`feature/parquet-0.9.8`. Pro PR:

- pt9912 Code-Review (final Approve),
- `/code-review` Lauf pro PR; Level (`high` oder `ultra`)
  wird pro Slice nach Risk-Profil gewaehlt — Default
  `high`, `ultra` fuer die Integrations-Slices **S6, S7,
  S8** und die Test-Slices **S9a, S9b**, weil sie
  cross-modulare Wirkung haben.

**Konsequenzen fuer die Sub-Slices:**

- PI-3 (`feature/parquet-0.9.8`) wird sofort nach PI-2
  angelegt, kein Sprint-Planning-Vorbehalt.
- PI-4 (S0-Start) folgt unmittelbar nach PI-3.
- Slice-Closure-Plan-Docs (`ImpPlan-0.9.8-parquet-S<N>-…md`)
  werden direkt mit dem Slice-Commit gepushed, nicht
  asynchron nachgereicht — sonst verliert die Single-
  Session-Spur ihre Nachvollziehbarkeit.

### 5.2 Sealed-`rg`-Sweep-Tooling (PI-2, 2026-06-06)

Drei Artefakte tragen den Sweep aus AP13 §4.1
([`parquet-decision-template.md`](../done/parquet-decision-template.md)
Zeilen 227-237):

- `scripts/parquet-sealed-sweep.sh` — Shell-Script faehrt
  die acht `rg`-Patterns pro Sealed-Hierarchie
  (`ImportInput`, `SchemaOrigin`, `SeekableChunkSource`,
  `CheckpointOperationSpecifics`, `DataExportFormat`) und
  druckt jeden Treffer-Block. Exit-Code immer 0 — Inventar,
  kein Gate. Erkennt auch `else`-Zweige, Reflection-Pfade
  und non-exhaustive `when`-Statements, die `gradle
  assemble --warning-mode=fail` nicht faengt.
- `make parquet-sweep` — Make-Target wraps das Script
  (Memo [[prefer-make]]).
- `docs/operations/parquet-pr-checklist.md` — PR-
  Checkliste, die `make parquet-sweep` als Pflicht-Check
  vor Merge auf `feature/parquet-0.9.8` benennt; pro
  Treffer entscheidet der PR-Reviewer (exhaustive oder
  begruendet `else`-belassen). Enthaelt zusaetzliche
  Slice-spezifische Checks (S10a-Dependency-Belege,
  S3-Hadoop-Freiheit, S6-Phase-2-Hook, S7-
  `-PintegrationTests`-Pflicht).

---

## 6. Aktive Folge-Threads ausserhalb Cut A

Aus AP13 §8.3 (Delta zu §5.4):

- **1.0.0**: Native-Image-Cut + Distributions-Cut
  (Default-JAR vs. `--parquet`-Variante) + optionaler
  Hadoop-API-Shim + **Hadoop-Footprint-Minimierung**
  (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty-Excludes,
  vormals geplant fuer das alte S10) (vormals 1.2.0-Scope +
  §6.2-Distributions-Frage + Footprint-Cut). Nutzt das
  S10a-Footprint-Inventar als Input.
- **MCP-Server-Spiegelung**: Bundle-/Single-File-Pfad ueber
  MCP-Tools exponieren — Entscheidung beim 1.0-Planning
  oder spaeter; frueheste Lieferung 1.0.0+.

Der vormalige **1.1.0-Single-File-Scope** entfaellt, weil er
Bestandteil von 0.9.8 wird (siehe AP13 §8.3).

---

## 7. Definition of Done — Umbrella-Closure

Der Umbrella wandert nach `docs/planning/done/`, wenn:

1. Alle vier Pre-Impl-Aufgaben (§5) abgehakt.
2. Alle Sub-Slices S0, S0b, S2, S10a, S3, S10b, S3b, S4,
   S5a, S5b, S6, S7, S8, S9a, S9b mit Closure-Plan-Doc unter
   `docs/planning/done/ImpPlan-0.9.8-parquet-S<N>-…md`
   abgeschlossen.
3. Befunde 1-3 aus §1.1 sind verifizierbar geschlossen
   (Re-Sichtung der zitierten Code-Stellen
   `DataExportFormat.kt:10`, `DataImportCommand.kt:41`,
   `DataExportCommand.kt:35`,
   `build.gradle.kts:50`). **Befund 4 (Hadoop-Footprint)
   ist bewusst nicht „geschlossen", sondern per
   S10a-Snapshot in
   [`parquet-libraries.md`](../done/parquet-libraries.md)
   §8 als 1.0.0-Folgeinput dokumentiert** — Minimierung
   ist 1.0.0-Aufgabe (AP13 §8.3).
4. CHANGELOG-Entry `[0.9.8]` traegt die finale
   Commit-Tabelle aller Slice-Commits in chronologischer
   Reihenfolge — analog zur Closure-Konvention der
   0.9.x-Aggregator-Plaene.
5. `feature/parquet-0.9.8` ist nach `develop` gemerged; Tag
   `v0.9.8` gesetzt.

---

## 8. Risiken (delta zu AP12 §13 + AP13 §4)

AP12 §13 und AP13 §4 bleiben gueltig. Zusaetzliche Risiken
durch die Befund-/Cut-A-Sicht 2026-06-06:

- **Footprint-Inventar-Druck in S10a**: das tatsaechliche
  Inventar der Hadoop-Transitiven (HDFS/YARN/Jersey/
  reload4j/Zookeeper/Netty) kann den Reflex ausloesen,
  doch in 0.9.8 zu minimieren — bewusst nicht in Scope
  (siehe §4 und AP13 §8.3). S10a-Aufwand ist deshalb
  klein gehalten (Annahme: 1-2 PT, plus 0.5-1 PT fuer
  S10b-Recheck, im 35-60-PT-Cut-A-Budget enthalten, aber
  nicht explizit ausgewiesen).
- **Plan-Doc-Drift**: die Closure-Migration nach `done/`
  kann den Eindruck erwecken, die Sub-Docs seien
  „eingefroren". Spike-Code laeuft aber gegen die
  Plan-Annahmen — wenn S0-S9b Abweichungen finden, gehoeren
  diese als Befund-Rueckspiel-Commit in die `done/`-Sub-Docs
  (analog AP10-Befund-Rueckspiel f89e2920). Keine
  Re-Aktivierung der `in-progress/`-Pfade.
- **Single-File-Phase-1/2-Komplexitaet (AP13 §5.1
  Risiko-Treiber)**: war der Hauptgrund fuer Cut B. Mit
  Cut A kommt das Risiko zurueck. Mitigation: S4/S5b/S9b
  sind eigenstaendige Slices mit eigener Closure. Eine
  Risiko-getriebene Reduktion auf den Bundle-Pfad ist
  moeglich, aber **kein stilles Fallback** — sie verlangt
  eine explizite Re-Scope-Entscheidung, die folgendes
  alles in einem Schritt updated:
  - AP13 §8 erweitern (analog zur 2026-06-06-Praezisierung)
    mit neuer Empfehlung „Cut A reduziert" + Begruendung,
  - **Versions-Bump** (typischerweise 0.9.8-bundle-only auf
    einer separaten Nummer wie 0.9.8.1 oder Verschieben des
    Single-File-Scopes auf einen 0.9.9-Folge-Cut — keine
    stille Auslieferung „0.9.8 = Cut B"),
  - DoD §7 dieses Umbrellas auf den reduzierten
    Slice-Satz angepasst (S4/S5b/S9b explizit aus der
    Closure-Liste entfernt),
  - CHANGELOG-Entry und `in-progress/README.md` analog
    nachgezogen.

---

## 9. Verweis-Konvention

Dieser Umbrella ist die einzige Stelle, an der
`in-progress/`-Status fuer Parquet gefuehrt wird. Sub-Slice-
Closures wandern direkt nach
`docs/planning/done/ImpPlan-0.9.8-parquet-S<N>-…md` und
werden hier per Commit-Tabelle referenziert. Der Umbrella
selbst bleibt bis Closure (§7) hier liegen.
