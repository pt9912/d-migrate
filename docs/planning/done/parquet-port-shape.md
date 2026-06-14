# AP10: Stream-vs-Datei-Portentscheidung fuer den Parquet-Reader

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05) — **fixiert** die Vorentscheidung
> aus `parquet-libraries.md` §7 / §7.1 und macht die
> `SeekableDataChunkReaderFactory`-Signatur bindend.
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 10 + Abschnitt 6 (Stream-vs-Datei-Port-Diskussion),
> `parquet-libraries.md` §7 (InputStream-Reader-Vertrag) und §7.1
> (Port-/Resolver-Vertrag-Skizze), `parquet-directory-import.md`
> §10.2/§10.3 (TableImporter-Doppelpfad),
> `parquet-import-input-dto.md` §4.1 + §4.3 (DTO-Vertrag),
> `hexagon/ports-read/.../DataChunkReaderFactory.kt`,
> `hexagon/ports-read/.../FormatReadOptions.kt`.

---

## 1. Ziel

Arbeitspaket 10 fixiert den Reader-Port-Vertrag fuer Parquet:
welche Factory-Signatur, welche Quellabstraktion, welches
Modul. Die strategische Entscheidung „pfadbasiert, kein
Stream, kein Temp-Spool" steht seit `parquet-libraries.md`
§7. AP10 macht daraus das endgueltige Port-Skelett und
schliesst zwei verbliebene offene Punkte:

- Ist die Factory Format-agnostisch (analog zu
  `DataChunkReaderFactory`) oder Parquet-only?
- Wie wird die seekbare Quelle modelliert, damit AP-spaeter
  ein Object-Storage-Adapter ohne Vertragsbruch hinzukommen
  kann?

AP11 (Single-File-Metadaten) und AP12 (CLI-Wiring) bauen darauf
auf; Implementierungscode folgt nach AP12.

---

## 2. Ausgangslage

- `DataChunkReaderFactory` (hexagon/ports-read/.../DataChunkReaderFactory.kt)
  ist heute **Format-agnostisch** und nimmt
  `(DataExportFormat, InputStream, table, chunkSize,
  FormatReadOptions)`. JSON/YAML/CSV-Reader teilen sich die
  Factory und werden ueber den `format`-Parameter gewaehlt.
- `parquet-libraries.md` §7 lehnt fuer Parquet `InputStream`
  ab — Parquet-Footer am Dateiende plus Row-Group-Sprung
  brauchen seekbaren Zugriff.
- `parquet-libraries.md` §7.1 skizziert eine
  `SeekableDataChunkReaderFactory` mit
  `SeekableChunkSource.Local(path)`-Eingabe und einem
  `ChunkSchema`-Parameter (AP2 §4.4).
- AP8 §10.2 sagt: heutige `ImportInputResolver`-Klasse bleibt
  fuer JSON/YAML/CSV; Bundle-Pfad bekommt eine zweite
  `ResolvedTableInput`-Variante mit Pfad statt Stream.
- AP9 §7.3 sagt: Format-Verzweigung lebt im `TableImporter`,
  nicht im `StreamingImporter`.
- AP9 §4.1 fixiert `ResolvedBundleTableBinding(table, path,
  schema, expectedSha256)` — der Path und das `ChunkSchema`
  sind also bereits port-eigen und stehen dem Reader zur
  Verfuegung, ohne dass der Adapter sie noch einmal beibringen
  muss.

---

## 3. Entscheidung

### 3.1 Eigene Factory, Format-agnostisch im Vertrag

Bindend: ein neuer Port
`hexagon.ports.read.SeekableDataChunkReaderFactory`, der
**parallel** zur bestehenden `DataChunkReaderFactory` lebt.
Format-Parameter bleibt erhalten — analog zur bestehenden
Factory.

Begruendung:

- Format-agnostisch zu sein kostet uns nichts (der
  `format`-Parameter wird im Parquet-Pfad heute auf
  `PARQUET` gesetzt) und haelt den Port offen fuer kuenftige
  seekable Formate (z.B. ein Arrow-IPC-Adapter, vgl.
  `parquet-libraries.md` §3.4).
- Symmetrie zur bestehenden Factory macht den Resolver-Sweep
  in §7.3 von AP9 (TableImporter-Doppelpfad) trivial: dieselbe
  `format`-Pattern-Match-Logik, nur andere Factory.
- Kein gemeinsamer Vertrag mit `DataChunkReaderFactory`: das
  waere eine Generalisierung, die heute keinen zweiten
  Konsumenten hat. Bei Bedarf kann AP-spaeter ein gemeinsames
  Supertyp-Interface eingezogen werden — AP10 macht das nicht
  spekulativ.

### 3.2 `SeekableChunkSource` als Sealed-Hierarchie

Bindend: die Quellabstraktion ist eine sealed interface mit
einem konkreten Subtyp `Local(path: Path)`. Sie lebt im
selben Modul wie der Port (`hexagon:ports-read`).

**Erweiterungsregel — bewusst Sealed:** Kotlin laesst direkte
Sealed-Subtypen seit 1.5 nur im **selben Modul** zu. Ein
externer Object-Storage-Adapter in einem Drittmodul kann
`SeekableChunkSource` deshalb **nicht** selbst implementieren.
Das ist gewollt:

- Eine neue Variante (z.B. `ObjectStorage(url, credentials)`)
  kommt als additiver Eintrag in `SeekableChunkSource.kt` im
  Port-Modul dazu. Konsumenten mit exhaustive `when` brechen
  bewusst — das ist der gewuenschte Sweep-Punkt.
- Der konkrete Storage-Adapter lebt weiterhin in einem
  separaten Modul (`adapters:driven:storage-objectstorage`
  o.ae.) und liefert nur die Implementierung der
  `SeekableInputStream`-Lese-Mechanik; die Variante selbst
  wird im Port-Modul deklariert.

Wer ein offenes Interface ohne `sealed` will, oeffnet damit
die Tuer fuer beliebige (nicht-d-migrate-internen)
Implementierungen ueber den `InputStream`-Pfad — genau das,
was `parquet-libraries.md` §7 Bullet 2 explizit ablehnt.
`sealed` ist also Teil des Vertrags, nicht ein Versehen.

`InputStream`-Quellen werden **nicht** gewrappt und auch
nicht in eine `Sealed`-Variante uebersetzt — das ist die
explizite Ablehnung aus `parquet-libraries.md` §7 Bullet 2
(kein impliziter Temp-Spool).

### 3.3 Reader-`ChunkSchema` ist Pflichtparameter

Bindend: der Reader bekommt sein `ChunkSchema` als
Pflichtparameter in `create(...)`, nicht erst spaeter via
Header. Begruendung: das Schema ist beim Bundle-Import bereits
vom Preflight aufgeloest (AP7/AP8/AP9), der Reader muss es
nicht aus dem Datei-Footer rekonstruieren. Damit ist der
Vertrag dual zum bestehenden Reader (der das Schema heute aus
dem File-Header zieht) und bricht ihn nicht.

**Minimaler Footer-vs-ChunkSchema-Konsistenzcheck.** Der
Reader liest den Parquet-Footer ohnehin (fuer Row-Group-
Iteration). Vor dem ersten `nextChunk()` muss er deshalb
einen billigen Sanity-Check fahren:

- Spaltenanzahl im Footer-`MessageType` == `schema.columns.size`.
- Spaltennamen in der Footer-Reihenfolge ==
  `schema.columns.map { it.name }`.

Mismatch ist Fehler `BUNDLE_SCHEMA_PARQUET_MISMATCH` mit
Tabellen- und Spalten-Detail in der Diagnose. Das fuellt die
Luecke, die der AP7-Live-Hash nicht abdeckt: ein vorhandener
SHA-256 beweist nur Datei-Integritaet gegen
`tables[].sha256`, nicht dass Manifest-Spaltendeklarationen
zum Footer passen — und Bundles ohne `sha256` haben gar keinen
Integritaetsschutz.

Bewusst **keine** vollstaendige Typgleichheits-Pruefung
(Decimal-Precision, LogicalType-Annotationen, Timezone): das
neutrale `ChunkSchema`-`NeutralType` und der Parquet-
Primitive-Typ stehen in einer N:M-Beziehung
(`parquet-schema-source.md` §8 Mapping-Tabelle), eine 1:1-
Validierung waere weder billig noch immer eindeutig. Reine
Namens-/Anzahlpruefung ist die kostenguenstige Variante, die
die haeufigsten Drift-Faelle (Spalte umbenannt, Spalte
hinzugefuegt/entfernt) zuverlaessig faengt; semantisches Drift
(Spalte Decimal(10,2) im Manifest, Decimal(12,4) in der Datei)
ist akzeptiertes Restrisiko und wird in §7 nochmal benannt.

### 3.4 Writer-Seite bleibt stream-basiert

Bindend: kein neuer Writer-Port. Der bestehende
`DataChunkWriterFactory.create(OutputStream, ...)` bleibt
unveraendert. Der Parquet-Writer (AP3-Spike, Folge-Implementierung
nach AP12) wraps den `OutputStream` intern in einen
`PositionOutputStream`/`OutputFile`-Adapter. Stdout-Schreiben
funktioniert via zaehlendem, nicht-seekbarem Stream
(`parquet-libraries.md` §7 Bullet 4 + §7.1 Symmetrie-Notiz).

---

## 4. Kotlin-Skelett

### 4.1 Port (`hexagon:ports-read`)

```kotlin
// hexagon/ports-read/src/main/kotlin/dev/dmigrate/format/data/SeekableDataChunkReaderFactory.kt
package dev.dmigrate.format.data

import dev.dmigrate.ports.common.schema.ChunkSchema
import java.nio.file.Path

/**
 * AP10: Erzeugt [DataChunkReader]-Instanzen fuer formatspezifische
 * Reader, die seekbaren Zugriff brauchen (Parquet-Footer und
 * Row-Group-Sprung). Parallel zur stream-basierten
 * [DataChunkReaderFactory] (parquet-libraries.md §7.1).
 *
 * Aufrufer (TableImporter, AP9 §7.3) waehlen pro Format
 * zwischen den beiden Factories:
 *   - JSON / YAML / CSV -> DataChunkReaderFactory (InputStream)
 *   - PARQUET           -> SeekableDataChunkReaderFactory (Path)
 */
interface SeekableDataChunkReaderFactory {

    /**
     * @param format Input-Format. Heute nur PARQUET; weitere
     *   seekable Formate koennen ohne Port-Aenderung folgen.
     * @param source Seekbare Quelle. Heute nur Local(Path);
     *   kuenftige Adapter (Object-Storage, gemounteter Cache)
     *   erweitern die Sealed-Hierarchie ohne Vertragsbruch.
     * @param table Tabellenname (Diagnose).
     * @param schema Bereits aufgeloestes ChunkSchema (AP7/AP8/
     *   AP9-Preflight). Der Reader muss es NICHT aus dem
     *   Datei-Footer rekonstruieren; das Schema ist die
     *   autoritative Quelle fuer Spaltenreihenfolge,
     *   Nullability und NeutralType.
     * @param chunkSize Anzahl Zeilen pro nextChunk(). Muss > 0.
     * @param options Format-/Encoding-Optionen. Fuer Parquet
     *   ist `encoding` ohne Bedeutung; AP12 entscheidet, ob
     *   solche Felder beim Parquet-Reader silently ignored
     *   oder im CLI-Preflight abgelehnt werden.
     */
    fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions = FormatReadOptions(),
    ): DataChunkReader
}

/**
 * AP10: Sealed-Quellabstraktion fuer den seekbaren Reader-Pfad.
 * Reine InputStream-Quellen sind bewusst NICHT Teil dieser
 * Hierarchie (parquet-libraries.md §7 Bullet 2 — kein
 * impliziter Temp-Spool).
 */
sealed interface SeekableChunkSource {
    /**
     * Lokales Dateisystem. Der Reader erhaelt einen
     * regulaeren Path; Lifecycle-Verantwortung liegt
     * beim Aufrufer (Bundle-Resolver oeffnet/schliesst
     * den Reader, der Path selbst hat keinen Lifecycle).
     */
    data class Local(val path: Path) : SeekableChunkSource
}
```

### 4.2 Default-Implementation (`adapters:driven:formats-parquet`)

Im Parquet-Adapter lebt die Default-Implementation
(Modulname als Konsequenz aus `parquet-libraries.md` §3.1).
AP12 macht das Wiring; AP10 nennt nur Vertrag und Modulort:

```kotlin
// adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/ParquetSeekableDataChunkReaderFactory.kt
// Bewusst public (kein `internal`), parallel zur Konvention von
// adapters/driven/formats/.../DefaultDataChunkReaderFactory.kt:
// CLI/MCP-Wiring (z.B. `hexagon:application`-Service-Loader)
// instanziiert die Factory direkt; ein `internal`-Sichtbarkeit
// wuerde diesen Pfad zwingen, einen separaten Provider zu
// halten, ohne semantischen Gewinn.
class ParquetSeekableDataChunkReaderFactory : SeekableDataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        require(format == DataExportFormat.PARQUET) {
            "ParquetSeekableDataChunkReaderFactory does not support format=$format"
        }
        val path = when (source) {
            is SeekableChunkSource.Local -> source.path
            // Sealed-Hierarchie: kuenftige Subtypen brechen
            // diesen `when` bewusst, damit der Sweep nicht
            // ueberlesen wird.
        }
        // Folge-Implementierung (parquet-java 1.17.1 Round-
        // Trip wie im AP3-Spike, plus ChunkSchema-Mapping
        // gemaess parquet-schema-source.md §8).
        TODO("AP12-Implementation")
    }
}
```

---

## 5. Migrations- und Impact-Analyse

### 5.1 `hexagon:ports-read`

- Neue Datei `SeekableDataChunkReaderFactory.kt` (§4.1).
- Bestehende `DataChunkReaderFactory.kt` bleibt unveraendert.

### 5.2 `hexagon:ports-common`

- Keine Aenderung. `ChunkSchema` ist durch AP2 §4.4 schon
  port-common, und `DataExportFormat.PARQUET` ist die
  AP9 §7.5-Vorbedingung (AP12-Wiring).

### 5.3 `adapters:driven:formats-parquet`

- Neue Datei `ParquetSeekableDataChunkReaderFactory.kt`
  (§4.2, Skelett); konkrete Implementierung ist Folgearbeit
  nach AP12.
- Bindung an `parquet-java` 1.17.1-Pfad aus dem AP3-Spike;
  Hadoop-API-via-LocalFileSystem-Befunde aus
  `parquet-libraries.md` §5.1 / §7 / §8 fliessen in den
  Reader ein (MapReduce-Klassen vermeiden, `.crc`-Sidecar
  abschalten).

### 5.4 `adapters:driven:streaming`

- **`StreamingImporter`-Constructor (StreamingImporter.kt:21)
  bekommt die zweite Factory-Referenz.** Heute trifft der
  Konstruktor nur `readerFactory: DataChunkReaderFactory` an;
  der `TableImporter` wird **intern** in Z. 28 erzeugt
  (`private var tableImporter: TableImporter = TableImporter(
  readerFactory, onTableOpened)`). CLI- und MCP-Pfade
  konstruieren `StreamingImporter(...)`, nicht den
  `TableImporter` direkt — also muss die seekbare Factory
  durch den `StreamingImporter`-Constructor durchgereicht
  werden:

  ```kotlin
  class StreamingImporter(
      private val readerFactory: DataChunkReaderFactory,
      private val seekableReaderFactory: SeekableDataChunkReaderFactory,
      private val writerLookup: (DatabaseDialect) -> DataWriter,
      private val onTableOpened: (...) -> Unit = { _, _ -> },
  ) {
      internal var tableImporter: TableImporter =
          TableImporter(readerFactory, seekableReaderFactory, onTableOpened)
      ...
  }
  ```

  Die Frage „Default-Wert oder Pflichtparameter" ist Sache
  von AP12; AP10-Empfehlung ist Pflichtparameter, weil ein
  `null`-Default bei einem `format=PARQUET`-Lauf zu einer
  schlecht diagnostizierbaren NullPointerException fuehren
  wuerde (siehe §7 Risiko 1).
- `TableImporter` selbst (vgl. AP8 §10.3 / AP9 §7.3) faehrt
  die `when (format)`-Verzweigung:
  `PARQUET -> seekableFactory.create(...)`, sonst
  `streamFactory.create(...)`.
- `ImportInputResolver` bleibt fuer Stream-Pfad
  unveraendert; der neue `ResolvedBundle`-Pfad wird in AP9
  §7.3 schon beschrieben.

### 5.5 `hexagon:application`

- Keine Aenderung am Application-Layer durch AP10 selbst;
  die Verdrahtung der zweiten Factory in
  `TableImporter`-Konstruktor ist AP12-Wiring.

### 5.6 `adapters:driving:cli`

- Keine direkte Aenderung durch AP10. Die CLI-Aktivierung
  des seekbaren Pfads ist Folge der AP9 §7.7-Logik
  (`ImportInput.ResolvedBundle` -> `TableImporter` waehlt
  `SeekableDataChunkReaderFactory`).

---

## 6. Begruendung gegen einen vereinheitlichten Port

AP10 spielt bewusst nicht die Variante „ein gemeinsames
Reader-Factory-Interface ueber Stream und Pfad" durch.
Gruende:

- Ein einheitliches Interface waere entweder eine sealed
  Source-Hierarchie mit `Stream`- und `Seekable`-Variante,
  was den `InputStream`-Pfad und damit die Temp-Spool-
  Versuchung wiedereroeffnet (`parquet-libraries.md` §7
  Bullet 2 lehnt das explizit ab), oder ein
  Overload-Pattern, das die Konsumenten zwingt, sich
  trotzdem im `when` zu entscheiden — kein Gewinn.
- Die Stream-Factory hat heute einen pretty narrow
  Vertrag (`InputStream`, Header-aus-Datei-Inferenz); die
  Seekable-Factory hat einen breiteren Vertrag
  (`ChunkSchema`-Pflicht, kein Header-Inferenzpfad). Ein
  gemeinsamer Vertrag wuerde eines von beiden auf das
  niedrigere Niveau ziehen.
- Falls AP-spaeter ein zweiter seekable Konsument kommt
  (Arrow-IPC), ist `SeekableDataChunkReaderFactory` der
  natuerliche Ort. Eine spaetere Vereinheitlichung waere
  ein additiver Schritt, kein Vertragsbruch.

---

## 7. Risiken

- **Zwei Factory-Referenzen im `TableImporter`.** AP12
  muss den `TableImporter`-Konstruktor erweitern, ohne
  bestehende JSON/YAML/CSV-Tests zu brechen. Wenn der
  konstruktorseitige Default fuer
  `seekableFactory: SeekableDataChunkReaderFactory? = null`
  gewaehlt wird, koennen JSON/YAML/CSV-Tests die heutige
  Konstruktor-Signatur weiterverwenden, solange sie nicht
  Parquet anfassen. Risiko: ein vergessener Bundle-Test
  bleibt mit `null` und schlaegt zur Laufzeit nicht
  diagnostizierbar fehl. AP12 muss eine klare Diagnose
  bauen, wenn `format=PARQUET` ohne `seekableFactory`
  hereinkommt.
- **`DataExportFormat`-Erweiterung.** AP12 muss den Enum
  um `PARQUET` erweitern; viele Konsumenten-`when`s
  brechen dabei (vgl. AP9 §7.8 Sweep-Vorgabe). AP10
  setzt das nur als Vorbedingung, traegt selbst keinen
  Code dazu bei.
- **`SeekableChunkSource`-Sealed ist Modul-lokal.**
  Kotlin laesst direkte Sealed-Subtypen seit 1.5 nur im
  selben Modul zu. Ein zukuenftiger Object-Storage-Adapter
  in einem separaten Gradle-Modul kann die Hierarchie
  **nicht selbst erweitern** — die neue Variante muss
  additiv im Port-Modul (`hexagon:ports-read`) eingefuegt
  werden. Das ist bewusst, vgl. §3.2 zur
  Erweiterungsregel: jede neue Variante ist ein
  exhaustive-`when`-Sweep, der Konsumenten zwingt, das
  neue Source-Format aktiv zu unterstuetzen. Ein offenes
  Interface waere die naheliegende Alternative, oeffnet
  aber die Tuer fuer beliebige nicht-d-migrate-interne
  Implementierungen ueber den Stream-/Temp-Spool-Pfad
  und widerspricht damit `parquet-libraries.md` §7
  Bullet 2.
- **Schema-Drift zwischen Manifest und Parquet-Footer.**
  AP7-Live-Hash (`MANIFEST_SHA256_MISMATCH`) deckt nur
  Datei-Integritaet gegen `tables[].sha256` — und ist nicht
  einmal Pflicht (AP7 §7.1, opt-in). AP10 schliesst die
  Luecke mit dem minimalen Footer-vs-ChunkSchema-Check aus
  §3.3 (Spaltenanzahl + -namen, `BUNDLE_SCHEMA_PARQUET_MISMATCH`).
  Akzeptiertes Restrisiko: **semantischer Drift** —
  Spaltenname und -anzahl stimmen, aber der Typ in der Datei
  weicht von `NeutralType` ab (z.B. Decimal(10,2) vs.
  Decimal(12,4)). Eine vollstaendige Typgleichheits-Pruefung
  ist nicht billig und nicht immer eindeutig
  (`parquet-schema-source.md` §8 N:M-Mapping); sie wuerde
  zudem `parquet-arrow` aus AP5 oder ein equivalentes
  Typ-Mapping-Modul in den Reader-Pfad ziehen. Trade-off
  ist bewusst zugunsten von „billiger Check faengt
  haeufige Drifts, restliche Verantwortung liegt beim
  Producer".
