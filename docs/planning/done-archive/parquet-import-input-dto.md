# AP9: Importpfad-Vertrag fuer Parquet-Bundle-Imports (bindende DTO-Wahl)

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05) — **bindet** die Vorentscheidungen
> aus `parquet-manifest-format.md` §10.2 und
> `parquet-directory-import.md` §10.1.
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 9, `parquet-manifest-format.md` (AP7-Preflight und
> `ResolvedParquetBundle`-Adapter-DTO),
> `parquet-directory-import.md` (AP8-Resolver, Resume-Vertrag,
> Checkpoint-Persistenz),
> `parquet-schema-source.md` (AP2-`ChunkSchema`-Vertrag),
> `parquet-libraries.md` (AP1 §7.1 InputStream-Reader-Vertrag),
> `hexagon/ports-write/.../streaming/ImportInput.kt`,
> `hexagon/ports-write/.../streaming/checkpoint/CheckpointManifest.kt`.

---

## 1. Ziel

Arbeitspaket 9 fixiert verbindlich, ueber welches DTO der CLI-/
Resolver-Pfad ein validiertes Parquet-Bundle an den
Streaming-Layer uebergibt. AP7 und AP8 haben den Vertrag
mehrfach skizziert und immer denselben Weg empfohlen; dieser
Sub-Doc hebt die Empfehlung in die Implementierungsentscheidung.

Konkret klaert AP9:

- ob `ImportInput.Directory` um ein optionales Bundle-Feld
  erweitert oder ein neuer Subtyp eingefuehrt wird,
- wie die Port-Klassen exakt aussehen (Kotlin-Skelett mit
  Modulpfaden und Imports),
- welche Begleitentscheidungen damit zwangslaeufig kommen
  (AP2-`SchemaOrigin`-Erweiterung, AP1 §7.1
  Korrektur-Aufraeumung),
- welche bestehenden Klassen anzupassen sind und wie die
  Schnittstelle zu AP7-`ResolvedParquetBundle` (adapter-intern)
  und AP8-`ParquetBundleResolver` aussieht.

AP10 (`SeekableDataChunkReaderFactory`-Signatur) und AP12 (CLI-
Wiring) bauen darauf auf.

---

## 2. Ausgangslage

- AP7 §10.2 empfiehlt einen neuen Subtyp statt Magic-Field auf
  `Directory`. AP8 §10.1 wiederholt das mit zusaetzlicher
  Praezisierung: der Subtyp darf **nicht** Adapter-Klassen
  referenzieren — `hexagon:ports-write` darf nicht von
  `adapters:driven:formats-parquet` abhaengen
  (`spec/architecture.md`).
- AP8 §10.5 fordert eine konkrete
  `CheckpointOperationSpecifics`-Implementierung im selben Port
  fuer die Resume-Fingerprint-Persistenz.
- `parquet-libraries.md` §7.1 traegt seit dem AP8-Commit eine
  Korrektur-Notiz: die urspruengliche AP1-Aussage „`ImportInput.
  Directory` bleibt unveraendert" ist durch AP7/AP8 ueberstimmt.
  AP9 macht das jetzt endgueltig.
- AP2 §4.4 traegt heute nur `SchemaOrigin {
  JDBC_METADATA, SCHEMA_READER, MERGED }`. Fuer AP8 §6.2 ist das
  fuer `schemaSource = manifest-fallback` semantisch falsch; AP9
  zieht die AP2-Erweiterung mit.

---

## 3. Entscheidung

### 3.1 Neuer Subtyp `ImportInput.ResolvedBundle`

Bindend: `ImportInput.Directory` wird **nicht** veraendert; ein
neuer Sealed-Subtyp `ImportInput.ResolvedBundle` wird ergaenzt.
Begruendung:

- Der Bundle-Vertrag traegt Tabellenbindings, ChunkSchema und
  Resume-Fingerprint — Information, die `Directory` (Pfad +
  optionaler Filter + optionale Ordnung) konzeptionell nicht
  fuehren will.
- Magic-Felder auf `Directory` (`bundleManifest: ...?`) wuerden
  den Sealed-Vertrag der bestehenden Variante semantisch
  unsauber machen und JSON/YAML/CSV-Tests zu Pattern-Match-
  Korrekturen zwingen.
- Auto-Detection (AP8 §9.2) routet bereits im CLI-Resolver,
  bevor der Streaming-Layer den Input sieht — die Wahl zwischen
  `Directory` und `ResolvedBundle` faellt damit weit vor der
  `ImportInput`-Stelle.

### 3.2 Port-Begriffe bleiben Parquet-frei

Bindend: alle Port-Klassen tragen den Begriff „Bundle", nicht
„Parquet". Der `kind`-Diskriminator im YAML-Checkpoint darf
adapter-spezifische Werte tragen (z.B. `"parquet-bundle"`), das
ist ein Inhaltswert, kein Klassenname. Damit bleibt der Port
strukturell offen fuer kuenftige Bundle-Formate (z.B. ein
Arrow-IPC-Bundle), ohne dass der aktuelle Schnitt das
mitliefert.

### 3.3 Adapter-zu-Port-Translator im Parquet-Adapter

Bindend: `adapters:driven:formats-parquet` haelt sein
reichhaltigeres `ResolvedParquetBundle` intern (AP7 §10.1) und
**uebersetzt** beim Eintritt in den Streaming-Layer in das
port-eigene `ImportInput.ResolvedBundle`. Der Translator ist die
einzige Stelle, an der adapterseitige Manifest-Begriffe
(`schemaSource`, `tables[].columns[]`, …) auf Port-Begriffe
(`ResolvedBundleTableBinding`, `BundleResumeFingerprint`)
abgebildet werden.

### 3.4 Fingerprint ist Funktion der Bindings, nicht parallele Eingabe

Bindend: `BundleResumeFingerprint.tableOrder` wird im Translator
**aus den aufgeloesten Bindings abgeleitet** (`bindings.map { it.table }`),
nicht als separater Eingabeparameter angenommen. Begruendung:
ein paralleler Fingerprint-Parameter koennte vom realen
Iterations-Output divergieren (z.B. nach AP12-Aenderungen an
Filter-/Order-Logik), und das wuerde Resume-Mismatches erzeugen,
die nicht aus echten Bundle-Aenderungen stammen, sondern aus
einem internen Vertragsbruch.

Die anderen Fingerprint-Felder (`manifestSha256`, `formatVersion`,
`producerVersion`) stammen aus dem AP7-Manifest und werden vom
Adapter direkt durchgereicht — sie haben keine Ableitungs-Quelle
aus den Bindings.

### 3.5 Resume-Enforcement sitzt im Checkpoint-Manager

Bindend: das DTO modelliert Resume-Faehigkeit **nicht** ueber
zwingende Pflicht-Hashes im `ResolvedBundleTableBinding` —
`expectedSha256` bleibt nullable. Begruendung: der Initial-Lauf
weiss formal nicht, ob ein spaeterer `--resume`-Lauf stattfinden
wird; ein Pflicht-Hash wuerde Bundles ohne `--manifest-sha256`
sofort beim Initial-Import abweisen, obwohl der Normal-Import
sie problemlos verarbeitet (AP8 §7.1).

Der harte Enforcement-Punkt sitzt deshalb im
`ImportCheckpointManager` (§7.5): beim `--resume` prueft er pro
Tabelle, ob ein non-null `expectedSha256` vorliegt; fehlt er,
faellt der Resume mit `BUNDLE_RESUME_REQUIRES_FILE_HASHES` aus.
Diese Trennung wird im DTO-Kommentar (§4.1
`ResolvedBundleTableBinding`) und in der Resume-Pflichten-
Beschreibung (§7.5) konkret gemacht; sie ist Teil des
bindenden AP9-Vertrags.

---

## 4. Kotlin-Skelett

### 4.1 Port-Subtyp und DTOs (`hexagon:ports-write`)

```kotlin
// hexagon/ports-write/src/main/kotlin/dev/dmigrate/streaming/ImportInput.kt
package dev.dmigrate.streaming

import dev.dmigrate.ports.common.schema.ChunkSchema
import java.io.InputStream
import java.nio.file.Path

sealed class ImportInput {

    data class Stdin(
        val table: String,
        val input: InputStream,
    ) : ImportInput()

    data class SingleFile(
        val table: String,
        val path: Path,
    ) : ImportInput()

    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
        val tableOrder: List<String>? = null,
    ) : ImportInput()

    /**
     * AP9: Bereits aufgeloestes Bundle (Multi-Table-/Directory-
     * Import mit verpflichtendem Bundle-Manifest, vgl.
     * parquet-export-import-evaluation.md §6).
     *
     * Bewusst Parquet-frei im Vertrag: der Port spricht nur
     * "Bundle", der Adapter befuellt das mit format-spezifischer
     * Information. tables traegt die effektive, vom Resolver
     * (AP8 §4.4) aufgeloeste Reihenfolge nach Filter/Order-
     * Auswertung; der Streaming-Layer iteriert linear darueber.
     *
     * bundleRoot ist das Bundle-Wurzelverzeichnis (Pfad zur
     * `manifest.yaml`-tragenden Directory). Wird vom
     * ImportPreflightValidator als inputPath-Wert genutzt
     * (siehe §7.5); die Tabellenpfade in tables liegen
     * innerhalb dieses Verzeichnisses (AP7 K3).
     *
     * resumeFingerprint ist Pflicht, weil Bundle-Importe ohne
     * Fingerprint nicht resumable waeren (AP8 §8.1). Fuer den
     * Initial-Lauf wird der Fingerprint trotzdem berechnet und
     * persistiert, damit ein spaeterer --resume-Lauf gegen ihn
     * pruefen kann.
     */
    data class ResolvedBundle(
        val bundleRoot: Path,
        val tables: List<ResolvedBundleTableBinding>,
        val resumeFingerprint: BundleResumeFingerprint,
    ) : ImportInput()
}

/**
 * AP9: Pfad + Schema pro Tabelle. Bewusst kein InputStream-Vertrag —
 * Bundle-Reader sind seekbar (parquet-libraries.md §7.1). Der
 * konkrete Reader-Pfad ist Sache von AP10.
 *
 * expectedSha256: optionale Manifest-tables[].sha256 zur Live-
 * Integritaetspruefung im AP7-Preflight. null bedeutet: Producer
 * hat keinen Hash geschrieben, Live-Pruefung wird im Normal-
 * Import geskippt.
 *
 * Resume-Enforcement: `null` hier ist im Initial-Import erlaubt,
 * weil das DTO formal nicht weiss, ob ein spaeterer --resume
 * stattfinden wird. Der harte Enforcement-Punkt sitzt im
 * `ImportCheckpointManager`:
 *   (a) Beim Initial-Lauf wird der `BundleResumeFingerprint`
 *       bedingungslos persistiert (kein DTO-Eingriff).
 *   (b) Beim --resume prueft der Manager im Pre-Check, ob jede
 *       im Resume-Scope verbleibende Tabelle ein non-null
 *       `expectedSha256` traegt; sonst Fehler
 *       `BUNDLE_RESUME_REQUIRES_FILE_HASHES` (AP8 §8.4).
 *   (c) Bei vorhandenen Hashes laeuft AP7-`MANIFEST_SHA256_MISMATCH`-
 *       Pruefung zwangsweise (live-Digest gegen `expectedSha256`).
 * Damit ist `expectedSha256: String?` im Vertrag konsistent: das
 * Feld ist optional, die Resume-Faehigkeit haengt aber daran.
 */
data class ResolvedBundleTableBinding(
    val table: String,
    val path: Path,
    val schema: ChunkSchema,
    val expectedSha256: String? = null,
)

/**
 * AP9: Fingerprint fuer den Checkpoint-Vergleich beim
 * --resume. Wird vom CLI-Resolver aus dem aktuellen
 * Bundle-Manifest gebaut und beim Initial-Lauf in
 * BundleCheckpointSpecifics persistiert (siehe §4.2). Beim
 * --resume vergleicht der ImportCheckpointManager die
 * persistierten Werte gegen die frisch berechneten und
 * lehnt bei Mismatch mit den Codes aus
 * parquet-directory-import.md §8.4 / §10.6 ab.
 *
 * fileSha256ByTable ist bewusst NICHT Teil des Fingerprints —
 * die per-Tabelle-Hashes leben im manifest.yaml und sind
 * implizit durch manifestSha256 abgedeckt (siehe AP8 §8.2).
 */
data class BundleResumeFingerprint(
    val manifestSha256: String,
    val formatVersion: String,
    val producerVersion: String,
    val tableOrder: List<String>,
)
```

### 4.2 Checkpoint-Erweiterung (`hexagon:ports-write`)

```kotlin
// hexagon/ports-write/src/main/kotlin/dev/dmigrate/streaming/checkpoint/BundleCheckpointSpecifics.kt
package dev.dmigrate.streaming.checkpoint

import dev.dmigrate.streaming.BundleResumeFingerprint

/**
 * AP9: konkrete CheckpointOperationSpecifics-Variante fuer
 * Bundle-Importe. Bewusst Parquet-frei im Klassennamen
 * (vgl. AP8 §10.1 zur Port-Sauberkeit).
 *
 * `bundleKind` ist der serialisierungs-stabile Diskriminator,
 * der im YAML-Manifest unter `operationSpecific.kind`
 * persistiert wird. Aktuelle Werte: nur `"parquet-bundle"`.
 * Kuenftige Bundle-Formate (z.B. ein Arrow-IPC-Bundle) kommen
 * mit eigenen Diskriminator-Werten dazu, ohne den
 * Klassennamen zu aendern.
 *
 * FileCheckpointStore.toMap/fromMap muss diese Variante
 * mit-serialisieren (AP8 §10.5 Schritt 2/3): toMap schreibt
 * `{ kind: bundleKind, fingerprint: { … } }`; fromMap liest
 * den `kind`-Wert und entscheidet, welche
 * CheckpointOperationSpecifics-Variante instanziiert wird
 * (heute nur diese — Format-Stabilitaet fuer kuenftige
 * Varianten). Unbekanntes `kind` ist Fehler
 * `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND` (AP12).
 *
 * Pre-AP8-Checkpoints haben kein operationSpecific und werden
 * beim Bundle-Resume strukturell mit dem Code
 * BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT abgelehnt
 * (vgl. AP8 §10.5 Schritt 3).
 */
data class BundleCheckpointSpecifics(
    val bundleKind: String,
    val fingerprint: BundleResumeFingerprint,
) : CheckpointOperationSpecifics {
    companion object {
        /** Diskriminator-Wert fuer Parquet-Bundles. */
        const val KIND_PARQUET: String = "parquet-bundle"
    }
}
```

### 4.3 Adapter-Translator (`adapters:driven:formats-parquet`)

```kotlin
// adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/ParquetBundleAdapter.kt
package dev.dmigrate.format.parquet

import dev.dmigrate.format.parquet.preflight.ResolvedParquetBundle
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ResolvedBundleTableBinding
import java.nio.file.Path

/**
 * AP9: einzige Stelle, an der das adapter-interne
 * ResolvedParquetBundle (AP7 §10.1) in das port-eigene
 * ImportInput.ResolvedBundle (AP9 §4.1) uebersetzt wird.
 * Manifest-spezifische Begriffe (schemaSource,
 * tables[].columns[].neutralType, ...) sind ab hier nicht
 * mehr sichtbar.
 */
internal object ParquetBundleAdapter {

    /**
     * Bewusst nur EIN Eingabeparameter: der Resolver traegt das
     * ResolvedParquetBundle bereits als Konstruktor-Property
     * (AP8 §4.2), und alle abgeleiteten Werte (bundleRoot,
     * Manifest-Header-Felder, Bindings) muessen aus derselben
     * Quelle stammen — sonst koennte ein extern uebergebenes
     * Manifest gegenueber dem im Resolver gehaltenen divergieren.
     */
    fun toResolvedBundle(
        resolver: ParquetBundleResolver,                  // AP8 §4.2
    ): ImportInput.ResolvedBundle {
        val bundle = resolver.bundle                       // §4.4 + AP8 §4.2-Erweiterung
        val bindings = resolver.resolve().map { binding ->
            ResolvedBundleTableBinding(
                table = binding.table,
                path = binding.source.path,
                schema = binding.schema,
                expectedSha256 = binding.expectedSha256,
            )
        }
        // tableOrder im Fingerprint wird hier aus den
        // tatsaechlich aufgeloesten Bindings abgeleitet (§3.4),
        // damit Fingerprint und tables[]-Order strukturell nicht
        // divergieren koennen.
        val fingerprint = BundleResumeFingerprint(
            manifestSha256 = bundle.manifestSha256,
            formatVersion = bundle.formatVersion,
            producerVersion = bundle.producerVersion,
            tableOrder = bindings.map { it.table },
        )
        return ImportInput.ResolvedBundle(
            bundleRoot = bundle.bundleRoot,
            tables = bindings,
            resumeFingerprint = fingerprint,
        )
    }
}
```

### 4.4 AP7-Erweiterung: `ResolvedParquetBundle`-Felder fuer den Fingerprint

`parquet-manifest-format.md` §9.1 Schritt 9 sagt explizit: „AP9
definiert das DTO im Detail." Das Translator-Skelett in §4.3
referenziert `manifest.manifestSha256`, `manifest.formatVersion`
und `manifest.producerVersion`. Diese drei Felder muessen am
adapter-internen `ResolvedParquetBundle`-DTO explizit gefuehrt
werden — der `ParquetBundlePreflight` hat sie ohnehin bereits
verarbeitet (siehe AP7 §9.1 Schritte 2-4), sie bleiben nur
heute implizit.

Bindender Mindestumfang:

```kotlin
// adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/preflight/ResolvedParquetBundle.kt
package dev.dmigrate.format.parquet.preflight

import java.nio.file.Path

/**
 * AP7 + AP9: Adapter-internes Ergebnis-DTO des
 * ParquetBundlePreflight. Wird vom ParquetBundleAdapter (§4.3)
 * in das port-eigene ImportInput.ResolvedBundle uebersetzt;
 * Adapter-spezifische Felder (manifestSha256, schemaSource,
 * vollstaendige Spaltenmetadaten) leben hier, nicht im Port.
 */
internal data class ResolvedParquetBundle(
    val bundleRoot: Path,
    // Manifest-Header-Felder (Quelle fuer den
    // BundleResumeFingerprint, §4.3):
    val manifestSha256: String,
    val formatVersion: String,
    val producerVersion: String,
    val schemaSource: String,
    // Aufgeloeste Tabellenordnung und Datei-Bindings
    // (AP7 §9.1 Schritt 9; Form gemaess
    // parquet-directory-import.md §4.2 ParquetTableBinding):
    val tables: List<ResolvedParquetTableBinding>,
)
```

Diese Felder duerfen die anderen Manifest-Inhalte (`tables[]`-
Spaltenmetadaten, `tables[].sha256`, ...) **nicht** aus dem
`ResolvedParquetBundle` herausziehen — sie bleiben dort, weil
der `ParquetBundleResolver` (AP8 §4.2) sie braucht, um die
Per-Tabellen-`ChunkSchema`-Konstruktion zu fahren. AP9 dokumentiert
lediglich, dass die drei Header-Felder explizit Teil des DTOs sind.

Begleit-Bindung an AP8: der `ParquetBundleResolver` aus AP8 §4.2
zeigt den `bundle: ResolvedParquetBundle` heute als
`private val`-Konstruktor-Property. Damit der Translator §4.3
das `bundle` als einzige Eingabequelle ableiten kann (single
source of truth, vgl. §3.4 zur Order-Konsistenz), wird die
Sichtbarkeit auf `val bundle: ResolvedParquetBundle` (oder
`internal val`) angehoben. AP12 macht die Aenderung — semantisch
ist das nur ein Visibility-Schliff am Adapter-internen Resolver.

---

## 5. AP2-Erweiterung: `SchemaOrigin.MANIFEST_FALLBACK`

AP8 §6.2 mappt den Manifest-`schemaSource`-Wert
`manifest-fallback` auf einen neuen Enum-Wert
`SchemaOrigin.MANIFEST_FALLBACK`. AP2 §4.4 definiert den
Enum heute nur als `{ JDBC_METADATA, SCHEMA_READER, MERGED }`;
`MERGED` waere fuer „best-effort" semantisch falsch (es bedeutet
„aus mehreren Quellen kombiniert").

Bindende AP9-Folge:

```kotlin
// hexagon/ports-common/.../schema/SchemaOrigin.kt
package dev.dmigrate.ports.common.schema

enum class SchemaOrigin {
    JDBC_METADATA,
    SCHEMA_READER,
    MERGED,
    MANIFEST_FALLBACK,    // AP9 (2026-06-05): hinzugefuegt fuer
                          // Bundle-Importe mit schemaSource =
                          // "manifest-fallback" (AP8 §6.2)
}
```

`parquet-schema-source.md` §4.4 wird beim AP9-Abschluss um
diesen Enum-Wert erweitert (Korrektur-Hinweis 2026-06-05). Die
Aufnahme ist additiv und **bricht keine Laufzeit-Konsumenten**:
JSON/YAML/CSV-Pfade benutzen `MANIFEST_FALLBACK` nicht und
sehen den neuen Wert auch nie. Auf der Kompilations-Ebene
brechen exhaustive `when(origin)`-Faelle aber sehr wohl — siehe
§7.2 und §8 zum Pflicht-Sweep, AP12 schliesst die Stellen.

---

## 6. Aufraeumung der Korrektur-Notiz in `parquet-libraries.md`

`parquet-libraries.md` §7.1 traegt seit dem AP8-Commit einen
Korrektur-Hinweis, dass die AP1-Aussage „`ImportInput.Directory`
bleibt unveraendert" durch AP7/AP8 ueberstimmt ist. AP9 hebt
das jetzt in die finale Aussage:

- §7.1 Bullet 4 wird von „bleibt unveraendert" auf „bleibt fuer
  Single-File-Bundles (AP11) erhalten; Multi-Table-/Directory-
  Bundles laufen ueber den neuen Subtyp `ImportInput.
  ResolvedBundle` (AP9 §4.1)" umgestellt. Die Korrektur-Notiz
  wird damit obsolet und kann beim AP9-Abschluss entfernt
  werden.

---

## 7. Migrations- und Impact-Analyse

### 7.1 `hexagon:ports-write`

- `ImportInput.kt`: neue Sealed-Variante `ResolvedBundle` (§4.1)
  und Schwester-Records.
- `checkpoint/CheckpointManifest.kt`: bleibt unveraendert; die
  bereits sealed `CheckpointOperationSpecifics`-Schnittstelle
  ist der Erweiterungspunkt fuer §4.2.
- Neue Datei `checkpoint/BundleCheckpointSpecifics.kt`.

### 7.2 `hexagon:ports-common`

- `schema/SchemaOrigin.kt`: additiver Enum-Wert
  `MANIFEST_FALLBACK` (§5). Konsumenten, die ein
  `when(origin)`-`exhaustive`-Match haben, brechen — aber das
  ist bewusst, weil ein neuer Fall sichtbar werden soll. AP12
  macht den Sweep.

### 7.3 `adapters:driven:streaming`

- `ImportInputResolver.kt` (heute `internal class`): neuer
  Case `is ImportInput.ResolvedBundle -> resolveBundle(input)`
  im `when`. `ResolvedTableInput` bekommt eine zweite Variante
  `Seekable(table, source, schema, expectedSha256)` mit Pfad
  statt Stream (vgl. AP8 §10.2).
- `StreamingImporter.kt`: bleibt format-agnostisch; die
  Format-Verzweigung lebt im `TableImporter`.
- `TableImporter`: muss zwei Reader-Pfade kennen
  (`DataChunkReaderFactory.create(InputStream, ...)` vs.
  `SeekableDataChunkReaderFactory.create(SeekableChunkSource,
  ChunkSchema, ...)` — AP10).

### 7.4 `adapters:driven:streaming/checkpoint`

- `FileCheckpointStore.toMap` (Z. 150 ff.) erweitern um
  `operationSpecific`-Serialisierung mit `kind`-Diskriminator
  (AP8 §10.5 Schritt 2).
- `FileCheckpointStore.fromMap` erweitern um den Parser-Pfad
  mit Backward-Compat zu pre-AP8-Checkpoints (AP8 §10.5
  Schritt 3).

### 7.5 `hexagon:application`

- `ImportCheckpointManager.writeInitialManifest` (Z. 166 ff.):
  Signatur um `bundleFingerprint: BundleResumeFingerprint? =
  null` erweitern; bei vorhandenem Fingerprint
  `operationSpecific = BundleCheckpointSpecifics(
  bundleKind = BundleCheckpointSpecifics.KIND_PARQUET,
  fingerprint = bundleFingerprint)` setzen.
- **`ImportCheckpointManager.buildCallbacks` / `saveManifest()`
  (Z. 216-239) muss den Fingerprint fortschreiben.** Aktuell
  baut `saveManifest()` bei jedem Chunk-Commit ein **neues**
  `CheckpointManifest` ohne `operationSpecific`-Feld; das
  wuerde den im Initial-Lauf geschriebenen
  `BundleCheckpointSpecifics` sofort wieder ueberschreiben.
  AP9 fordert daher: `buildCallbacks` nimmt den aktiven
  `BundleResumeFingerprint?` aus dem Resume-Kontext (oder vom
  Initial-Aufrufer) entgegen und reicht ihn als
  `operationSpecific = bundleFingerprint?.let {
  BundleCheckpointSpecifics(
  bundleKind = BundleCheckpointSpecifics.KIND_PARQUET,
  fingerprint = it) }` in jeden `saveManifest()`-
  `CheckpointManifest(...)`-Konstruktor durch. Beim Resume
  laedt der Manager das geladene
  `BundleCheckpointSpecifics` aus dem persistierten Manifest
  und schreibt es bei jedem Update wieder mit; der
  Fingerprint ist damit eine Bundle-Lauf-Invariante, keine
  Initial-Manifest-Information.
- `ImportCheckpointManager.validateManifest(...)` (Z. 93-124)
  bekommt einen Bundle-Zweig, der nach den heutigen Pruefungen
  (`optionsFingerprint`, `tableSlices`-Tabellen,
  `inputFilesByTable`-Bindings) zusaetzlich greift, sobald der
  Resume-Input ein Bundle ist (siehe `InputContext`-Erweiterung
  unten):
  1. `manifest.operationSpecific` ist
     `BundleCheckpointSpecifics`. Wenn nicht (= pre-AP8-
     Checkpoint trifft AP8-Bundle), Fehler
     `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (AP8 §10.5
     Schritt 3 + §10.6).
  2. Vergleich der persistierten Fingerprint-Felder
     (`manifestSha256`, `formatVersion`, `tableOrder`) gegen den
     aktuellen `ImportInput.ResolvedBundle.resumeFingerprint`;
     Mismatch wirft `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`,
     `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT` bzw.
     `BUNDLE_TABLE_ORDER_CHANGED` (AP8 §8.4).
  3. Fuer jede Tabelle im **Resume-Scope** muss
     `inputCtx.bundleExpectedSha256ByTable?.get(table)` non-null
     sein. Sonst Fehler `BUNDLE_RESUME_REQUIRES_FILE_HASHES`
     (AP8 §8.4). Resume-Scope ist exakt die Menge, fuer die
     `buildResumeContextFromManifest` (Z. 127 ff.) bereits einen
     `ImportTableResumeState` baut: alle `manifest.tableSlices`
     mit `status != COMPLETED && chunksProcessed > 0`.
     `COMPLETED`-Tabellen werden bewusst ausgeklammert — sie
     sind bereits durch `skippedTables` aus dem Import-Pfad
     genommen und brauchen keinen Hash-Check beim Resume; ein
     `null`-`expectedSha256` an einer COMPLETED-Tabelle darf
     nicht den Resume des restlichen Bundles blockieren.
- `InputContext` (ImportRunnerTypes.kt:127) bekommt ein
  optionales viertes Feld
  `val bundleExpectedSha256ByTable: Map<String, String?>? = null`.
  Bedeutung:
  - `null`: kein Bundle-Import (JSON/YAML/CSV) — Bundle-
    Validierungen werden geskippt.
  - `Map<String, String?>`: Bundle-Import; `null`-Werte
    markieren Tabellen ohne `manifest.tables[].sha256`. Der
    Resume-Pre-Check in `validateManifest` Schritt 3 liest
    genau diese Map. Im Normal-Lauf (ohne `--resume`) wird
    nichts darueber gemacht.
- `ImportResumeContext` (ImportRunnerTypes.kt:156) bleibt
  unveraendert; der Resume-Scope ist aus `resumeStateByTable`
  und `initialSlices` ableitbar (alle Tabellen mit
  `committedChunks > 0` oder `status == IN_PROGRESS`). Falls
  AP12 zeigt, dass eine separate `resumeScopeTables: Set<String>`
  klarer ist, kommt das additiv dazu — aber nicht zwingend
  hier in AP9.
- `ImportCheckpointManager.writeInitialManifest`-Aufrufer
  (CLI-Pfad, §7.7) befuellt `InputContext.bundleExpectedSha256ByTable`
  beim Initial-Lauf aus dem bereits geladenen Manifest. Damit
  ist die Map auch fuer den Initial-Lauf vollstaendig
  vorhanden (und wird bei einem spaeteren `--resume` gegen
  den Checkpoint geprueft).
- `DataImportHelpers.resolveFormat` (Z. 30 ff.): neuer Zweig
  vor `inferFormatFromExtension`, der Verzeichnisse mit
  `manifest.yaml` als `DataExportFormat.PARQUET` aufloest
  (AP8 §9.2). Setzt voraus, dass `DataExportFormat.PARQUET`
  existiert — `hexagon/ports-common/.../DataExportFormat.kt`
  bekommt einen neuen Enum-Wert (AP12-Wiring, dieser Sub-Doc
  legt den Vertrag nur fest).
- **`ImportPreflightValidator` (Z. 105-122) muss drei
  `ResolvedBundle`-Zweige bekommen.** Der Validator hat heute
  exhaustive `when`-Ausdruecke ueber `ImportInput`:
  - `effectiveTables` (Z. 105 ff.) — fuer `ResolvedBundle`
    liefert die Liste der Tabellen aus
    `input.tables.map { it.table }`.
  - `inputTopology` (Z. 113 ff.) — neuer Wert `"bundle"`.
  - `inputPath` (Z. 118 ff.) —
    `input.bundleRoot.toAbsolutePath().normalize().toString()`
    (§4.1 traegt `bundleRoot: Path` genau dafuer).

  Diese drei Stellen sind Sealed-`when`-Faelle und brechen die
  Kompilation, sobald `ResolvedBundle` zu `ImportInput`
  hinzukommt — der Sweep ist Pflicht, nicht optional. AP12 macht
  ihn ausschliesslich an diesen drei Stellen.

### 7.6 `adapters:driven:formats-parquet`

- Neuer Translator `ParquetBundleAdapter` (§4.3).
- Bestehende Klassen `ParquetBundlePreflight`,
  `ResolvedParquetBundle`, `ParquetBundleResolver`,
  `ChunkSchemaBuilder` aus AP7/AP8 bleiben adapter-intern.

### 7.7 `adapters:driving:cli`

- Import-Resolver: ruft `ParquetBundlePreflight`, dann
  `ParquetBundleResolver`, dann `ParquetBundleAdapter.
  toResolvedBundle(...)`, uebergibt das Ergebnis an den
  `StreamingImporter`.
- **AP7-Live-Digest-Aktivierung im Resume-Modus**: der
  `ParquetBundlePreflight` traegt heute die SHA-256-Verifikation
  als opt-in (AP7 §7.1). Der CLI-Resolver muss sie beim
  `--resume`-Lauf zwangsweise aktivieren — also den Preflight
  mit `requireSha256Verify = true` aufrufen, sobald
  `!request.resume.isNullOrBlank()` (das `resume`-Feld auf
  `DataImportRequest` ist ein `String?` mit dem Resume-Ref-
  oder Pfad-Wert, kein Boolean). Wenn dabei eine Datei nicht zu
  `tables[].sha256` passt, faellt der Resume mit AP7-Code
  `MANIFEST_SHA256_MISMATCH` (AP8 §8.4 P1). Im Normal-Import
  ist der Preflight weiterhin opt-in.
- `bundleExpectedSha256ByTable` (siehe §7.5 InputContext-
  Erweiterung) wird im CLI-Pfad aus dem Manifest gebaut und
  zusammen mit dem `InputContext` an den
  `ImportCheckpointManager` weitergereicht. Damit hat der
  Manager-Resume-Check (§7.5 Schritt 3) seinen Eingabewert.
- Beim Initial-Lauf wird der frische `BundleResumeFingerprint`
  in den Checkpoint geschrieben (siehe §7.5). Beim `--resume`
  laeuft die Validierung aus §7.5; bei Mismatch klare CLI-
  Diagnose (AP8 §8.6).

### 7.8 Konsumenten-Bruch: ehrliche Bestandsaufnahme

Die Aussage „kein Bruch fuer JSON/YAML/CSV" ist nur fuer den
**Laufzeit-Pfad** korrekt: bestehende JSON/YAML/CSV-Tests
brechen nicht durch den neuen Subtyp, weil sie auf konkrete
Pattern (`is Directory`) matchen und die `ResolvedBundle`-
Variante nie sehen.

Der **Kompilations-Pfad** ist aber spuerbar: jeder exhaustive
`when (input)` ueber `ImportInput` bricht beim Hinzufuegen der
neuen Sealed-Variante. Verifiziert via Code-Sichtung
2026-06-05:

- `ImportInputResolver.resolve` (Z. 18 ff.) — drei `when`-
  Zweige; `is ResolvedBundle` wird in §7.3 ergaenzt.
- `ImportPreflightValidator` — drei `when`-Zweige
  (`effectiveTables`, `inputTopology`, `inputPath`); siehe
  §7.5 fuer den exakten Plan.
- Ggf. weitere `when (...)` in `ImportRunner`-/CLI-Pfaden und
  in Tests; AP12 macht den Sweep mit einem robusten Suchmuster.
  Ein simples `git grep "when.*ImportInput"` ist nicht
  ausreichend, weil Stellen wie
  `when (val input = ctx.input) { is ImportInput.Stdin -> ... }`
  (Beispiel aus den CLI-Happy-Path-Tests) den `ImportInput`-
  Token nicht in derselben Zeile haben. Empfohlener Sweep
  ueber Kotlin-Quellen plus Tests:

  ```bash
  rg --type kotlin -n 'is ImportInput\.' .
  rg --type kotlin -n 'when \(' . | grep -F 'ImportInput'
  ```

  Erst die Vereinigung dieser beiden Trefferlisten gibt die
  vollstaendige Menge der zu aktualisierenden `when`-
  Ausdruecke. AP12 ergaenzt jede Trefferstelle um einen
  `is ImportInput.ResolvedBundle`-Zweig.

JSON/YAML/CSV-Verhalten bleibt zur Laufzeit unveraendert, der
Code-Sweep ist aber Pflicht und wird in AP12 mit-gezogen.

---

## 8. Risiken

- **Sealed-Bruch durch `MANIFEST_FALLBACK`**: jeder
  `exhaustive when` auf `SchemaOrigin` bricht beim
  AP9-Aufschlag. AP12 muss einen Sweep machen; die heutige
  Konsumentenmenge ist klein (parquet-schema-source.md §6
  nennt ausschliesslich den `StreamingExporter`-Pfad), aber
  der Sweep darf nicht vergessen werden.
- **Backward-Compat der Checkpoint-Manifeste**: pre-AP8-
  Checkpoints werden bewusst nicht migrierbar gemacht; ein
  Operator, der einen 0.9.7-Checkpoint mit `--resume` in
  einem 0.9.8-Bundle-Import wiederbeleben moechte, faellt
  hart auf `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`.
  Das ist gewollt, sollte aber im 0.9.8-Release-Note (AP13)
  prominent erwaehnt werden.
- **Translator-Last**: `ParquetBundleAdapter` ist eine
  einfache Mapping-Funktion, aber wenn sie wachsen sollte
  (z.B. um `JdbcTypeHint`-Anreicherung), waere das ein
  Symptom dafuer, dass die Port-DTOs zu schmal geschnitten
  sind. AP12-Implementierung sollte den Translator
  unter 50 Zeilen halten; alles drueber zurueck an AP9.
- **DataExportFormat.PARQUET nicht in §4 dieses Sub-Docs**:
  bewusst, weil es ein Enum-Wert in `hexagon:ports-common`
  ist und nicht direkt zum `ImportInput`-Vertrag gehoert. Der
  Wert wird in AP12 (CLI- und Factory-Wiring) ergaenzt; AP9
  setzt ihn als Vorbedingung voraus (siehe §7.5).
