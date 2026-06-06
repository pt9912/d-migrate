# AP13: Entscheidungsvorlage Parquet-Export und -Import

> Dokumenttyp: Entscheidungsvorlage zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05) — synthetisiert AP1-AP12 in eine
> Go/No-Go-Empfehlung mit Aufwand, Risiken und gestaffelter
> Scope-Vorschlag. Letzte Etappe vor Implementierungs-
> Entscheidung.
>
> **Praezisierung 2026-06-06:** §5.4, §6 und §7 sind durch
> §8 superseded. Cut B (Bundle-Pilot) wird auf Cut A
> (Voll-Scope) erweitert; Zielversion wechselt von 1.0.0
> auf 0.9.8. Stakeholder-Entscheid 2026-06-05 (Commit
> e7f3f714) bleibt im Log und ist bis §8 die normative
> Aussage. Umsetzung laeuft als Per-Feature-Umbrella
> [`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md).
>
> Referenzen: alle AP1-AP12-Sub-Docs
> (`parquet-libraries.md`, `parquet-schema-source.md`,
> `parquet-manifest-format.md`, `parquet-directory-import.md`,
> `parquet-import-input-dto.md`, `parquet-port-shape.md`,
> `parquet-single-file-metadata.md`, `parquet-cli-wiring.md`),
> AP3-Spike-Modul `adapters/driven/formats-parquet/`,
> AP4/AP5/AP6-Tests im selben Modul.

---

## 1. Ziel

AP13 liefert die finale Entscheidungsvorlage fuer die
Implementierung des Parquet-Adapters in d-migrate. Inhalt:

- **Aufwandschaetzung** pro AP12-Implementierungsschritt
  (`parquet-cli-wiring.md` §12, neun Schritte) — als
  Personentage-Bandbreiten, nicht als Punktwerte.
- **Risiko-Gesamtbild** aus AP1-AP12, gruppiert nach
  Treffer-Wahrscheinlichkeit und Aufwand zur Aufloesung.
- **Scope-Empfehlung** fuer einen gestaffelten Release-Pfad
  (1.0 Pilot, 1.1, 1.2), damit der erste Cut nicht an der
  Komplexitaet kippt.
- **Offene Punkte**, die **vor** der Implementierung
  beantwortet werden muessen.

AP13 trifft selbst **keine** Architekturentscheidung — alle
Vertraege liegen in den AP1-AP12-Sub-Docs. AP13 ist die
Brille, durch die das Engineering- bzw.
Stakeholder-Team auf die Vorarbeit schaut, um „ja, los", „ja,
aber geschnitten" oder „nein" zu sagen.

---

## 2. Was AP1-AP12 erreicht hat

### 2.1 Architektur-Vertraege (bindend)

- AP1: `parquet-java` 1.17.1 mit GZIP-Codec ohne SNAPPY/ZSTD-
  JNI; Hadoop-API via `LocalFileSystem`; spezifische Hadoop-
  Submodule (`hadoop-common`, `hadoop-mapreduce-client-core`).
- AP2: formatseitiges `ChunkSchema` in `hexagon:ports-common`
  als Schema-vor-Chunk-Vertrag; `SchemaOrigin`-Enum mit
  `MANIFEST_FALLBACK` (AP9-Erweiterung).
- AP7: vollstaendiges YAML-Schema fuer `manifest.yaml` plus
  Preflight-Vertrag mit elf stabilen Fehlerklassen.
- AP8: `ParquetBundleResolver.resolve(): List<ParquetTableBinding>`;
  strikte Mid-stream-Fehlerbehandlung; Resume-Pflichten.
- AP9: port-eigener Sealed-Subtyp `ImportInput.ResolvedBundle`
  plus `BundleResumeFingerprint` und `BundleCheckpointSpecifics`
  als sealed `CheckpointOperationSpecifics`-Variante.
- AP10: paralleler Reader-Port `SeekableDataChunkReaderFactory`
  in `hexagon:ports-read`; `SeekableChunkSource` sealed mit
  `Local(path)`; Footer-vs-ChunkSchema-Konsistenzcheck im
  Reader.
- AP11: Footer-Key-Value-Metadaten mit Key
  `d-migrate.manifest` als Single-File-Vertrag;
  `tables[].file` in AP7 von Pflicht auf bedingt.
- AP12: zwei-phasiger Single-File-Preflight (vor/nach
  DB-Connect); `ImportInput.ResolvedSingleFile`-Sealed-
  Variante; Composite-Writer-Factory im CLI-Modul.

### 2.2 Code-Spike (lauffaehig)

- AP3: parquet-java 1.17.1 Round-Trip im
  `adapters/driven/formats-parquet/`-Modul (verifiziert).
- AP4: DuckDB JDBC 1.5.3.0 liest den Spike-Output, Typen
  INTEGER/VARCHAR/BOOLEAN bestaetigt.
- AP5: `parquet-arrow` 1.17.1 SchemaConverter liest den
  MessageType und liefert Arrow-Schema (`Int(32, signed)`,
  `Utf8`, `Bool`); rein JVM, keine JNI.
- AP6: Spike-Erweiterung um `readSchemaFromFooter`,
  `readAsChunk`, `writeWithoutCrc`; AP3-Befund
  `fs.file.impl.disable.cache=true` als Pflicht in
  Hadoop 3.4.1.

### 2.3 Was die Vorarbeit NICHT enthaelt

- Implementierter `ParquetChunkReader`/`Writer` (nur Spike-
  Skelett).
- `ParquetBundlePreflight`, `ParquetBundleResolver`,
  `ParquetBundleAdapter` (nur Skizzen).
- `ParquetSingleFilePreflight.phase1/phase2`,
  `ParquetSingleFileManifestReader/Writer` (nur Skizzen).
- Sealed-Sweep im Code (AP12 §8 nennt die Stellen, aber
  noch unangefasst).
- `DataExportFormat.PARQUET` (nicht im Enum, AP12 §3
  Vorbedingung).

---

## 3. Aufwandschaetzung

Pro AP12 §12-Schritt, Personentage-Bandbreite. Annahme: ein
mit dem d-migrate-Code vertrauter Entwickler, ohne
zusaetzliche Reviews/Befund-Rueckspiel-Zyklen (die kommen
oben drauf, siehe §3.3).

Schritte 5 und 9 sind zur besseren Cut-Zuteilung in
Bundle- und Single-File-Anteile gesplittet (5a/5b und 9a/9b).
Schritt 3 hat einen separaten 3b-Eintrag fuer den
Bundle-Export-Manifest-Pfad, weil der heutige
`StreamingExporter` (StreamingExporter.kt:163,
`ExportOutput.FilePerTable`) nur Tabellen-Dateien schreibt
und der AP7-`ParquetManifestWriter` plus die
`StreamingExporter`-Erweiterung um „Bundle-Closure
(`manifest.yaml`-Erzeugung nach allen Tabellendateien)"
ein eigener Aufwand sind.

| AP12-Schritt | Inhalt | Aufwand (PT) | Cut-B-Anteil? |
| ------------ | ------ | ------------ | ------------- |
| 1 | `DataExportFormat.PARQUET` + Sealed-Sweeps fuer fuenf Hierarchien | 1-2 | ja |
| 2 | `SeekableDataChunkReaderFactory`-Port + Default-Impl-Skelett | 0.5-1 | ja |
| 3 | `ParquetChunkReader` + `ParquetChunkWriter` (Schema-Mapping AP2 §8, Row-Group-Akkumulation, Footer-vs-ChunkSchema-Check) | 5-8 | ja |
| 3b | `ParquetManifestWriter` + `StreamingExporter`-Bundle-Closure (AP7 §10.1; pro Tabelle Bindings sammeln, nach Iteration `manifest.yaml` im Bundle-Wurzel schreiben; optionales `--manifest-sha256`) | 2-3 | ja |
| 4 | `ParquetSingleFileManifestReader/Writer` + `ParquetSingleFilePreflight.phase1/phase2` (YAML, KV-Lesen, --table-Precedence, Content-SHA-256) | 3-5 | nein |
| 5a | **Bundle-Pfad**: `ParquetBundlePreflight` + Resolver + Adapter (K1-K5-Validierungen, Manifest-YAML, Schema-Konstruktion 3-stufig, Tabellenordnung) | 4-6 | ja |
| 5b | **Single-File-Pfad**: `ImportInput.ResolvedSingleFile`-Sealed-Variante plus zwei-phasiger Single-File-Preflight-Anschluss; `--no-checkpoint`-Wiring | 1-2 | nein |
| 6 | CLI-Wiring (`DataImportWiring` + `DataImportCommand`, Composite-Writer-Factory, neue Flags, Phase-2-Hook im `DataImportRunner`) | 3-4 | ja (Phase-2-Hook nur 5b-relevant — Cut B koennte ihn skippen, -0.5 PT) |
| 7 | Resolver-Integration (`ImportInputResolver` Sweep, `TableImporter` Composite-Pfad, `StreamingImporter`-Constructor) | 2-3 | ja |
| 8 | Checkpoint-Erweiterung (`FileCheckpointStore.toMap`/`fromMap` mit kind, `ImportCheckpointManager.validateBundle/SingleFileResume`, `InputContext`-Erweiterung, `buildCallbacks`-Fingerprint-Durchreichen) | 3-5 | nur Bundle-Anteil (`validateBundleResume`, `bundleExpectedSha256ByTable`) — 2-3 PT |
| 9a | **Bundle-Tests**: CLI-Preflight (Bundle-Codes), Format-Resolver (`manifest.yaml`-Hook), Bundle-Resume-Familie, DuckDB-/Arrow-Bundle-KV-Toleranz | 3-5 | ja |
| 9b | **Single-File-Tests**: CLI-Preflight (Single-File-Codes), Phase-1/2-Tests, Single-File-Resume, DuckDB-/Arrow-Single-File-KV-Toleranz | 2-3 | nein |
| **Summe Netto Voll-Scope (alle Schritte)** | | **29.5-47 PT** | |
| **Summe Netto Cut B** (1+2+3+3b+5a+6+7+8(Bundle)+9a) | | **20.5-32.5 PT** | |

### 3.1 Folgekosten ausserhalb der neun Schritte

- **GraalVM-Native-Image-Reachability** (`parquet-cli-wiring.md`
  §10): 5-15 PT, abhaengig von Reflection-/Service-Loader-
  Treffermenge. Iterativ: ein erster Smoketest kann nach
  Schritt 3 laufen und gibt die Groessenordnung.
- **Hadoop-API-Shim** (`parquet-libraries.md` §8, optional):
  5-10 PT. Nur noetig, wenn GraalVM-Reachability ohne ihn
  nicht beherrschbar ist.
- **Documentation/Release-Notes**: 2-3 PT (CLI-Help,
  Migration-Notes fuer pre-AP8-Checkpoints, Single-File-vs-
  Bundle-Wahl-Hilfe).

### 3.2 Review-/Befund-Aufwand

Die AP1-AP12-Sub-Doc-Phase hatte pro AP im Schnitt **vier
Findings** (zwei High, zwei Medium). Im Implementierungs-
Pfad ist die Befund-Rate erfahrungsgemaess hoeher, weil Code
mehr Schnittstellen kreuzt als Prosa. Realistische Annahme:
**+30-40% Aufwand fuer Review-Zyklen + Befund-Rueckspiel**
auf den Netto-Aufwand.

Brutto (Voll-Scope, alle Schritte inkl. 30-40%
Review-Zyklen): **38-65 PT** fuer die elf Tabellen-Eintraege,
plus Native-Image-/Shim-Aufwand.

Brutto (Cut B, nur Bundle-Anteile): **27-45 PT**.

### 3.3 Kalenderaufwand

Bei einem Entwickler in Vollzeit:

Voll-Scope:
- Optimistisch (38 PT): 8 Kalenderwochen.
- Realistisch (52 PT): 11 Kalenderwochen.
- Pessimistisch (65 PT + Hadoop-Shim 10 PT): 15
  Kalenderwochen.

Cut B (siehe §5.2):
- Optimistisch (27 PT): 5-6 Kalenderwochen.
- Realistisch (36 PT): 7-8 Kalenderwochen.
- Pessimistisch (45 PT): 9 Kalenderwochen.

Plus die einmaligen Folgekosten (Native-Image, Doku).

---

## 4. Risiko-Gesamtbild

### 4.1 Wahrscheinlich-und-aufwaendig

- **Native-Image-Reachability** (AP1 + AP12 §10): trifft
  potenziell viele Reflection-Pfade in
  `parquet-hadoop`/`parquet-column`; Aufloesung iterativ.
  Mitigation: nach Schritt 3 ein erster `nativeCompile`,
  Reachability-Metadaten generieren, einschneiden.
- **Hadoop-Footprint im Distributions-JAR**
  (`parquet-cli-wiring.md` §13): mehrere MB Klassen. Fuer
  Operatoren, die kein Parquet brauchen, ist das tote Last.
  Mitigation: Gradle-Build mit `--parquet`-Flag (separater
  Distributions-Artefakt) ist Folge-Idee, nicht 1.0-Scope.
- **Sealed-when-Sweep-Vollstaendigkeit** (AP12 §8 + §13):
  das `rg`-Pattern faengt nicht alle Code-Pfade. Konkrete
  Luecken:
  - `else`-Zweige in `when`-Statements brechen nicht, wenn
    eine neue Sealed-Variante dazukommt — die Default-
    Behandlung greift still.
  - `when`-Statements ohne Ausdruckszwang (keine
    Rueckgabewert-Verwendung) sind nicht-exhaustiv und
    erzeugen keinen Compiler-Fehler beim Sealed-Bruch.
  - Reflection-Pfade (`when (val v = obj::class)`) und
    Service-Loader (`META-INF/services/...`) sehen den
    Sealed-Vertrag gar nicht.
  - Test-Code in `src/test/...` faellt durch viele
    Quality-Gates ohne Warnung — ein Test-`when` mit
    `else -> error(...)` wird beim Sealed-Bruch nicht rot.

  `gradle assemble --warning-mode=fail` allein deckt das
  nicht ab. **Go-Bedingung: die AP12 §8 `rg`-Sweep-Befehle
  werden vor jedem PR-Merge manuell gefahren** (per
  Tooling/Hook, oder dokumentiert als Pflicht-Schritt in
  der PR-Checkliste). Konkrete Patterns pro Hierarchie:

  ```bash
  # Pro Sealed-Hierarchie (AP12 §8.1-§8.5):
  rg --type kotlin -n 'is ImportInput\.' .
  rg --type kotlin -n 'when \(' . | grep -F 'ImportInput'
  rg --type kotlin -n 'is SchemaOrigin\.' .
  rg --type kotlin -n 'when \(' . | grep -F 'SchemaOrigin'
  rg --type kotlin -n 'is SeekableChunkSource\.' .
  rg --type kotlin -n 'is CheckpointOperationSpecifics' .
  rg --type kotlin -n 'is DataExportFormat\.' .
  rg --type kotlin -n 'when \(' . | grep -F 'DataExportFormat'
  ```

  Jeder Treffer wird pro PR durchgesehen und entweder
  exhaustive gemacht oder begruendet `else`-belassen.
  `gradle assemble` mit `allWarningsAsErrors` ist
  zusaetzlich aktiviert, faengt aber nur die exhaustive-
  `when`-Subset; es ersetzt den Pattern-Sweep nicht.

### 4.2 Wahrscheinlich-und-billig

- **CSV-Flag-Ablehnung bricht Shell-Skripte**
  (`parquet-cli-wiring.md` §13): Operator-Verantwortung, in
  CLI-Help dokumentieren.
- **Format-Auto-Detection-Falle** bei zufaelliger
  `manifest.yaml`: klare Diagnose im
  `MANIFEST_PARSE_ERROR`-Pfad.
- **AP9-Pre-AP8-Checkpoint-Bruch**: bewusst hart, in
  Release-Note dokumentieren.

### 4.3 Unwahrscheinlich-aber-teuer

- **parquet-java 1.18-Wechsel waehrend der Umsetzung**: 1.18
  bringt `PlainParquetConfiguration`/`LocalInputFile/
  OutputFile`, die den Hadoop-Block kleiner machen koennten.
  Treffer-Wahrscheinlichkeit im 6-14-Wochen-Zeitfenster:
  niedrig. Falls 1.18 doch erscheint und sicher ist, ist
  ein Adapter-internes Refactor 3-5 PT (kein Vertragsbruch
  zum Hexagon).
- **CVE in parquet-hadoop**: AP1 hat 1.17.1 wegen
  CVE-2025-30065 und CVE-2025-46762 gewaehlt. Eine neue
  CVE wuerde ein Patch-Update erzwingen (1-2 PT, in 1.x
  Maintenance).

### 4.4 Akzeptierte Restrisiken

- **Semantischer Schema-Drift Manifest-vs-Datei**
  (`parquet-port-shape.md` §7): Spaltennamen und -anzahl
  werden geprueft, Decimal-Precision/Temporal-Timezone
  nicht. Producer-Verantwortung.
- **Sealed-Modul-Lokalitaet**
  (`parquet-port-shape.md` §3.2): neue Source-Varianten
  brechen exhaustive `when`-Konsumenten. Gewollt.
- **Single-File-Bundle-Manifest-Asymmetrie** (`parquet-
  manifest-format.md` §5.2): `tables[].file` ist bedingt
  Pflicht. AP11 §5.2 + AP12 §4.4 dokumentieren das.

---

## 5. Scope-Empfehlung fuer den ersten Release

Drei gestaffelte Cuts, sortiert nach „1.0-Risiko aufsteigend"
und „Operator-Mehrwert absteigend":

### 5.1 Cut A — Voller Vertrag (1.0.0)

Implementierungsschritte 1-9 in einem Release. Aufwand:
35-60 PT (§3.3). Operator-Mehrwert maximal: Bundle-Export,
Bundle-Import, Single-File-Export, Single-File-Import,
Resume.

**Vorteil:** Vertraege sind durchgaengig konsistent;
keine Vor-Release-Trade-offs.

**Nachteil:** Single-File-Phase-1/2-Komplexitaet und
Resume-Pfad sind das, was die meisten 1.0-Risiken traegt
(`--no-checkpoint`-Semantik, Content-Hash-Berechnung,
zwei-phasiger Preflight). Wenn da ein Befund-Zyklus mehr
laeuft, schiebt sich der ganze Cut nach hinten.

### 5.2 Cut B — Bundle-only Pilot (1.0.0)

Implementierungsschritte 1, 2, 3, **3b**, **5a**, 6
(`Phase-2`-Hook geskippt — Cut B braucht ihn nicht), 7, 8
(nur Bundle-Anteile), **9a**. Aufwand: 27-45 PT brutto
(§3.2 Tabelle).

In-Scope:

- Bundle-Export mit `--manifest-sha256` opt-in. Schritt 3b
  liefert den `ParquetManifestWriter` und die
  `StreamingExporter`-Bundle-Closure
  (`ExportOutput.FilePerTable` schreibt heute nur Daten-
  dateien; Bundle-Export braucht zusaetzlich das
  `manifest.yaml`-Schreiben nach allen Tabellen).
- Bundle-Import inklusive AP7-Preflight, AP8-Resolver, AP9-
  DTOs.
- Resume fuer Bundle-Imports (mit Datei-Hashes).

Out-of-Scope:

- Single-File-Export: nicht ueber d-migrate; Operatoren, die
  eine `users.parquet` aus dem d-migrate-Workflow wollen,
  exportieren als Bundle und packen die Datei manuell aus.
- Single-File-Import: scheitert mit klarer Diagnose
  `PARQUET_SINGLE_FILE_NOT_YET_SUPPORTED` (Exit 2). Fremde
  Parquet-Dateien aus Spark/Hive sind damit in 1.0 nicht
  importierbar.
- `--no-checkpoint`: nicht eingefuehrt; Bundle-Import-
  Checkpoint laeuft immer.

**Vorteil:** der Phase-1/2-Schnitt entfaellt; die Single-
File-Komplexitaet aus AP11/AP12 traegt nicht zum 1.0-
Risiko bei. Bundle-Pfad ist konzeptionell sauber und
testbar.

**Nachteil:** Operatoren erwarten oft, dass „Parquet-
Support" auch Spark-erzeugte Dateien lesen kann. Cut B
muss in der Release-Note unmissverstaendlich klarstellen,
dass Single-File-Import 1.1 ist.

### 5.3 Cut C — Bundle-only Mini-Pilot (1.0.0)

Wie Cut B, aber **ohne** Resume. Implementierungsschritte 1,
2, 3, 3b, 5a, 6 (kein Phase-2-Hook), 7, 9a (ohne
Bundle-Resume-Familie). Schritt 8 (Checkpoint-Erweiterung)
entfaellt komplett. Aufwand: 20-32 PT brutto.

In-Scope:

- Bundle-Export plus Bundle-Import; keine Hash-Persistierung,
  kein Checkpoint-Bundle-Block.
- Bei Pipeline-Fehler bricht der Import; ein erneuter Lauf
  muss komplett von vorn.

Out-of-Scope: alles aus Cut B Out-of-Scope plus Resume
(`--resume` lehnt Bundle-Imports mit
`BUNDLE_RESUME_NOT_YET_SUPPORTED` ab).

**Vorteil:** schnellster Pilot; Operator kann die Pipeline-
Form prinzipiell ausprobieren.

**Nachteil:** Resume ist genau das Feature, das d-migrate
gegen einfache Shell-Pipelines abgrenzt. Ein Bundle-Import
ohne Resume hat keinen klaren Vorteil gegenueber
`pg_dump | psql`. Cut C ist deshalb nur sinnvoll, wenn das
Engineering-Team explizit „wir wollen 1.0 noch dieses
Quartal" sagt.

### 5.4 Empfehlung

**Cut B als 1.0.0-Pilot**, mit den folgenden Folge-Releases:

- **1.1.0**: Single-File-Import + Single-File-Export plus
  `--no-checkpoint`. Aufwand Brutto 11-20 PT (Schritte 4,
  5b, 6-Phase-2-Hook, 8-SingleFile-Anteile, 9b).
- **1.2.0**: Native-Image-Cut plus optionaler Hadoop-API-
  Shim. Aufwand 10-25 PT, getrennt von der Vertragsarbeit.

Begruendung fuer Cut B statt Cut A: die Single-File-Phase-1/
2-Komplexitaet ist real (Befund-Zahl der AP12-Review-Runde
ist Beleg), aber kein Blocker fuer den Bundle-Pfad. Bundle-
Imports sind das Hauptzielbild der Parquet-Evaluierung
(Hauptplan §2 nennt „grosse Exporte", „Analyse mit DuckDB",
„reproduzierbare Migrationsartefakte"). Single-File ist ein
Bonus, der separat lieferbar ist.

Cut C wird verworfen, weil ohne Resume das Wertversprechen
gegenueber `pg_dump | psql` zu duenn ist.

---

## 6. Vor-Implementierungs-Punkte (Stand 2026-06-05: beantwortet)

Diese fuenf Punkte muessen **vor** dem ersten
Implementierungs-Commit beantwortet sein. Stakeholder-
Entscheid 2026-06-05 hat alle fuenf festgenagelt — jeweils
mit der AP13-Empfehlung. Damit ist die Bedingung „offene
Punkte beantwortet" aus §7 erfuellt.

1. **Release-Branch-Strategie** — **`feature/parquet-1.0`-
   Branch.** AP12 §12-Schritte landen als separate Commits
   auf dem Feature-Branch und werden nach Schritt 9 in
   `develop` gemergt. Begruendung: Schritt 1
   (Enum-Erweiterung) ohne Schritt 6 (CLI-Wiring) hat
   keinen Funktionsnutzen, und Zwischenmerges in `develop`
   wuerden JSON/YAML/CSV-Tests gegen Half-State stoeren.
2. **Gradle-Distributions-Cut** — **Parquet immer im
   Default-JAR.** Hadoop-Footprint (mehrere MB) ist
   bewusstes Trade-off fuer 1.0/1.1; eine separate
   `--parquet`-Variante (Gradle-Flag oder Maven-Classifier)
   wird im 1.2-Cut zusammen mit dem Native-Image-Sweep
   reevaluiert.
3. **DuckDB-/Arrow-Test-Dependencies** — **`testImplementation`
   bleiben + CI-Smoke laufen lassen.** Tests sind weiterhin
   Test-Only-Dependencies (kein Produktionspfad), laufen
   aber in CI als Smoke-Familie, die die AP4-/AP5-Linien
   plus AP12 §11.4 (Footer-KV-Toleranz) abdeckt. Heraufstufen
   zu Pflicht-Tests wird verworfen, weil das den CI-Druck
   ohne entsprechenden Korrektheitsgewinn erhoeht.
4. **MCP-Server-Spiegelung** — **Nicht in 1.0.0.** Der
   MCP-Adapter (`adapters:driving:mcp`) bleibt unangefasst,
   Parquet ist 1.0/1.1 CLI-only. Eine MCP-Exposition des
   Bundle-Export/-Import-Pfads kommt frueheste 1.1 oder
   spaeter; die Entscheidung wird beim 1.1-Planning
   getroffen.
5. **Hadoop-API-Shim-Folge-Entscheidung** — **Mit dem
   1.2.0-Cut zusammen entscheiden.** Cut B (1.0) und 1.1
   nutzen unveraendert `hadoop-common +
   hadoop-mapreduce-client-core` aus AP3-Spike-Befund. Der
   1.2-Native-Image-Smoketest liefert die Daten, an denen
   die Entscheidung „eigener Shim vs. Hadoop-Subset
   pinnen" gefaellt wird: wenn Reachability-Metadaten
   beherrschbar sind, bleibt der Block; wenn nicht, kommt
   der Shim als 1.2-Folge-Refactor (5-10 PT, AP12 §10).
   Kein harter Termin vorher — die Stakeholder-Praeferenz
   ist „Datengrundlage zuerst, Entscheidung danach".

Mit diesen fuenf Antworten ist die Bedingung „offene
Punkte beantwortet" aus §7 erfuellt; die restlichen
Bedingungen (Engineering-Goal-Commit, Native-Image-Smoketest
in Schritt 3, Sealed-`rg`-Sweep in PR-Checkliste) sind
Engineering-/Prozess-Implementierungs-Schritte beim
Cut-B-Start.

---

## 7. Empfehlung — Stand 2026-06-05: Go fuer Cut B

**Go, mit Cut B (Bundle-Pilot) als 1.0.0.**

Begruendung in drei Saetzen:

- Die AP1-AP12-Vorarbeit hat alle Vertraege bindend
  geklaert; das Implementations-Risiko liegt in
  Code-Qualitaet und Testabdeckung, nicht in
  Architekturentscheidungen.
- Cut B trennt den 1.0-Risiko (Bundle-Pfad) sauber vom
  1.1-Risiko (Single-File-Phase-1/2 + `--no-checkpoint`);
  das laesst sich an verschiedene Zeitfenster anpassen.
- Der Plan-Doc selbst und die zehn Sub-Docs sind das
  Lieferergebnis der Evaluierung — sie machen das
  Folge-Engineering bei Bedarf an andere Personen
  uebergebbar.

**Bedingungs-Status:**

- ✅ Die fuenf Punkte aus §6 sind beantwortet
  (Stakeholder-Entscheid 2026-06-05; siehe §6 fuer die
  einzelnen Festlegungen).
- ⏳ Cut B als Engineering-Goal mit Zeitbudget + Reviewer-
  Verfuegbarkeit committen — passiert beim 1.0-Sprint-
  Planning.
- ⏳ Native-Image-Smoketest in AP12-Schritt 3 verankern
  (Pflichtteil der `ParquetChunkReader`/`Writer`-PR, nicht
  ein separater Folge-Task).
- ⏳ Sealed-`rg`-Sweep-Befehle aus §4.1 / AP12 §8 in die
  PR-Checkliste aufnehmen (Tooling-Hook bevorzugt, sonst
  dokumentierter Pflicht-Schritt). `gradle assemble` mit
  `allWarningsAsErrors` ist zusaetzlich aktiviert, ersetzt
  den Sweep aber nicht (Reflection-/Service-Loader-/
  Test-Code-Luecken).

Die drei verbleibenden ⏳-Punkte sind Implementierungs-
Schritte beim Cut-B-Start, keine offenen
Entscheidungsfragen. Damit ist die Plan-Doc-Phase
abgeschlossen; der Folge-Schritt ist die
Branch-/Sprint-Vorbereitung.

**Wenn Go nicht moeglich:** die Plan-Doc und Sub-Docs
wandern unveraendert in `docs/planning/done/`, der
AP3-Spike bleibt im Repo als „untergeordnetes Modul
ohne Konsumenten". Spaetere Reaktivierung ist
unkompliziert: jeder AP1-AP12-Sub-Doc traegt seine
Vorentscheidungen mit Code-Anker und Stand-Datum.

---

## 8. Praezisierung 2026-06-06 — Scope auf Cut A, Ziel 0.9.8

Diese Sektion supersededt §5.4 (Empfehlung Cut B), §6
(Vor-Implementierungs-Antworten in 1.0.0-Sprache) und §7
(Empfehlung „Cut B als 1.0.0"). Stakeholder-Entscheid
2026-06-05 (Commit e7f3f714) bleibt als historische
Festlegung im Repo, ist aber durch diese Praezisierung
abgeloest.

### 8.1 Neue Festlegung

**Cut A (Voller Vertrag) als 0.9.8**, mit den AP12 §12-
Schritten 1, 2, 3, **3b**, **4**, **5a**, **5b**, 6
(inkl. Phase-2-Hook), 7, 8 (Bundle + SingleFile), **9a**,
**9b**. Aufwand laut §3.2: Brutto 35-60 PT, Netto Voll-
Scope 29.5-47 PT.

Release-Branch: `feature/parquet-0.9.8` (statt
`feature/parquet-1.0`). Merge in `develop` nach Schritt 9
analog AP13 §6.1.

### 8.2 Begruendung

Cut B war auf Risiko-Trennung optimiert (Bundle-Pfad
sauber vom Single-File-Phase-1/2-Risiko isolieren) und
auf einen klaren 1.0-Stamp. Mit Cut A wird die
Single-File-Vertragsarbeit aus AP4/AP5b/AP6-Phase-2-Hook/
AP8-SingleFile/AP9b in **dieselbe** Release-Linie gezogen,
weil der Operator-Mehrwert (Spark-/Hive-/DuckDB-Dateien
direkt importieren) Cut B zu eng macht, sobald der
Bundle-Pfad steht.

Die Versionsabsenkung auf 0.9.8 macht den 1.0-Stamp
explizit zum Engineering-Reife-Punkt (Native-Image,
Distributions-Cut, Footprint-Minimierung) statt zum
Feature-Vollstaendigkeits-Stamp. Cut A landet damit in der
laufenden 0.9.x-Linie und kann normal in einen
0.9.x-Patch-Pfad anschliessen, ohne dass das 1.0-Versprechen
durch noch nicht ausgereifte Native-Image-/Footprint-
Themen blockiert wird.

### 8.3 Folge-Releases (delta zu §5.4)

- **1.0.0**: Native-Image-Cut + Distributions-Cut
  (Default-JAR vs. `--parquet`-Variante) + optionaler
  Hadoop-API-Shim + **Hadoop-Footprint-Minimierung**
  (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty-Excludes
  und -Constraints; in 0.9.8 bewusst nur per S10a
  inventarisiert, vgl. §8.2). Das ist der vormalige
  1.2.0-Inhalt aus §5.4 plus die Distributions-Frage aus
  §6.2 plus der aus dem 0.9.8-Umbrella herausgehaltene
  Footprint-Cut.
- **1.0.0+ / spaeter**: MCP-Server-Spiegelung des
  Bundle-/Single-File-Pfads. Entscheidung beim
  1.0-Planning oder spaeter.
- Der vormalige **1.1.0-Single-File-Scope** entfaellt,
  weil er Bestandteil von 0.9.8 wird.

### 8.4 Was bleibt aus §6 normativ

- §6.1 Release-Branch-Strategie — Aufbau bleibt
  (Schritt-fuer-Schritt-Commits, kein Big-Bang-Merge), nur
  der Branch-Name wechselt auf `feature/parquet-0.9.8`.
- §6.2 Distributions-Cut — **Parquet immer im Default-JAR
  fuer 0.9.8.** Eine separate `--parquet`-Variante wird mit
  dem 1.0.0-Cut zusammen mit dem Native-Image-Sweep
  reevaluiert (statt 1.2.0 wie urspruenglich in §6.2).
- §6.3 DuckDB-/Arrow-Test-Dependencies — bleibt
  (`testImplementation` + CI-Smoke).
- §6.4 MCP-Server-Spiegelung — bleibt „nicht in dieser
  Release-Linie", Verschiebung von „nicht in 1.0.0" auf
  „nicht in 0.9.8"; Folge-Entscheidung weiterhin offen.
- §6.5 Hadoop-API-Shim — bleibt Folge-Thread zusammen mit
  dem 1.0.0-Cut.

### 8.5 Bedingungs-Status (delta zu §7)

Die fuenf ⏳-Punkte aus §7 bleiben offen und werden durch
den Umbrella `parquet-productive-cut-a.md` §5 (PI-1 bis
PI-5) gefuehrt.

**Der erste Implementierungs-Commit ist nicht mehr
AP12-Schritt 1**, sondern Slice **S0** des Umbrellas
(AP2 `ChunkSchema`-Typ + `DataChunkWriter.begin`-Migration
auf `ChunkSchema`). AP12 §12 Schritt 1
(`DataExportFormat.PARQUET` + Sealed-Sweeps) ist in den
Umbrella-Slice **S3** verschoben worden — Begruendung
(Umbrella §3, Befund-Audit 2026-06-06): unter Cut A ist
die Enum-Erweiterung von der Handler-Registrierung nicht
trennbar (Stopgap-`when`-Branches widersprechen dem
Auto-Memory-Eintrag [[no-carveouts]]); zudem fehlt im Code
heute der `ChunkSchema`-Typ, den S2/S3 voraussetzen. Die
Sub-Slice-Reihenfolge des Umbrellas
(`S0 → S0b → S2 → S10a → S3 → S10b → S3b → S4 → S5a → S5b
→ S6 → S7 → S8 → S9a → S9b`; S0/S0b-Split am 2026-06-06,
weil AP2.b/c-Mapping nicht in S0 stopgap-faehig ist)
supersededt damit AP12 §12 fuer diesen Cut. AP12 §12 selbst bleibt als Wiring-Sicht
gueltig.
