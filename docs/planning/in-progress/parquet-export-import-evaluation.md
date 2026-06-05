# Evaluierung: Parquet-Export und -Import

> Dokumenttyp: Evaluierungs- und Architekturplan
>
> Status: In Progress (Stand 2026-06-05).
>
> AP1 (Bibliothekssichtung) und AP2 (Schemaquelle) liegen als
> Sub-Docs vor, AP3 (Round-Trip-Spike) ist mit
> `adapters/driven/formats-parquet/` + `ParquetSpike.kt` und
> Round-Trip-Test abgeschlossen. Die AP3-Befunde
> (Hadoop-API-Kanal in 1.17.1, MapReduce-Reader-Dependency,
> `.crc`-Sidecar bei `LocalFileSystem`) sind in
> `parquet-libraries.md` §5.1, §7 und §8 zurueckgespielt; das
> Status-Header-Doppel hier ist damit obsolet.
>
> AP4 (DuckDB-Akzeptanzlauf gegen Spike-Output) ist mit
> `ParquetSpikeDuckDbReadTest` erledigt (Stand 2026-06-05); DuckDB
> JDBC 1.5.3.0 liest den Spike-Output erfolgreich und meldet die
> Spike-Schema-Spalten als INTEGER/VARCHAR/BOOLEAN. Hadoop bleibt
> bewusst auf 3.4.1 — parquet-hadoop 1.17.1 ist gegen 3.3.0
> kompiliert (provided), 3.5.0 ist ungetestete Kombination und
> lohnt sich erst mit dem 1.18-Wechsel.
>
> AP5 (Arrow-Java-Metadateninspektion) ist mit
> `ParquetSpikeArrowInspectTest` erledigt (Stand 2026-06-05);
> `parquet-arrow` 1.17.1 (rein JVM, ohne `arrow-dataset`-JNI —
> vgl. `parquet-libraries.md` §3.4) konvertiert das Parquet-
> `MessageType` via `SchemaConverter#fromParquet` zu einem Arrow
> `Schema` und der Test verifiziert die drei Spike-Spalten als
> `Int(32, signed)`/`Utf8`/`Bool`.
>
> AP6 (Importpfad-Pruefung am Prototyp) ist mit
> `ParquetSpikeImportPathTest` erledigt (Stand 2026-06-05). Demonstriert
> drei Bausteine am Spike: Footer-getriebene `ColumnDescriptor`-Liste
> (`ParquetSpike.readSchemaFromFooter`), Round-Trip durch das
> neutrale `DataChunk`-Modell (`readAsChunk`) und die `.crc`-Sidecar-
> Mitigation via `fs.file.impl=RawLocalFileSystem`
> + `fs.file.impl.disable.cache=true` (Variante (a) aus
> `parquet-libraries.md` §7; AP6-Befund: beide Direktiven sind in
> Hadoop 3.4.1 noetig, weil der `FileSystem`-Service-Loader-Cache
> sonst die `LocalFileSystem`-Default-Instanz vorhaelt).
>
> AP7 (Manifest-Format und Import-Preflight) ist als Sub-Doc
> `parquet-manifest-format.md` skizziert (Stand 2026-06-05); legt
> das YAML-Schema von `manifest.yaml`, den Preflight-Vertrag
> (Validierungen + stabile Fehlerklassen), die Tabelle-zu-Datei-
> Aufloesung mit Kollisionsschutz, das SHA-256-Verfahren und die
> Formatversionierung (`MAJOR.MINOR`, Start bei 1.0) fest. Die
> finale Implementierungswahl haengt noch an AP8/AP9.
>
> AP8 (manifestgebundene Directory-Import-Aufloesung) ist als
> Sub-Doc `parquet-directory-import.md` skizziert
> (Stand 2026-06-05). Legt den `ParquetBundleResolver`-Vertrag
> fest (`resolve(): List<ParquetTableBinding>`, kein one-shot
> Iterator), klaert die Interaktion zwischen Manifest-
> Tabellenordnung und `tableFilter`/`tableOrder`, definiert den
> `ChunkSchema`-Aufbau pro Tabelle aus Manifest-Spaltenmetadaten
> (Drei-Stufen-Aufloesung neutralType -> JDBC-Hint-Tupel ->
> sqlTypeName-Heuristik; JDBC-Hints fliessen nur als Eingaben in
> die NeutralType-Ableitung, nicht in `ChunkColumnSchema`),
> waehlt strikte Mid-stream-Fehlerbehandlung, knuepft Resume an
> die Vorbedingung „Datei-Hashes vorhanden"
> (`BUNDLE_RESUME_REQUIRES_FILE_HASHES`) und trennt
> Hash-Pruefungen strikt: AP7-Live-Preflight gegen
> `tables[].sha256` erkennt Datei-Aenderungen
> (`MANIFEST_SHA256_MISMATCH`), der Resume-Checkpoint
> (`manifestSha256`, `formatVersion`, `producerVersion`,
> effektive `tableOrder`) erkennt Manifest-Edits seit Checkpoint.
> Per-Datei-Hash wird bewusst NICHT im Checkpoint persistiert
> (waere redundant zu `manifestSha256`). Definiert die
> Format-Autodetection ueber `manifest.yaml` plus die
> Vorbedingung `DataExportFormat.PARQUET` und einen
> `resolveFormat`-Hook. AP9 bestaetigt die DTO-Wahl
> (`ImportInput.ResolvedBundle`-Subtyp empfohlen; bewusst
> Parquet-frei im Port, Adapter uebersetzt am Port-Eintritt).
>
> AP9 (Importpfad-Vertrag: bindende DTO-Wahl) ist als Sub-Doc
> `parquet-import-input-dto.md` festgenagelt (Stand 2026-06-05).
> Macht die AP7-/AP8-Vorentscheidung verbindlich: neuer
> `ImportInput.ResolvedBundle`-Subtyp mit
> `ResolvedBundleTableBinding` und `BundleResumeFingerprint`
> in `hexagon:ports-write`, neue
> `BundleCheckpointSpecifics : CheckpointOperationSpecifics`,
> Adapter-Translator `ParquetBundleAdapter` als einzige Stelle,
> an der adapter-interne Manifest-Begriffe auf Port-Begriffe
> abgebildet werden. Zieht zwei Begleitentscheidungen mit:
> AP2 `SchemaOrigin` um `MANIFEST_FALLBACK` erweitert (additiv),
> AP1 §7.1 Bullet zu `ImportInput.Directory` finalisiert
> (Directory bleibt fuer JSON/YAML/CSV + Single-File-Bundles,
> Multi-Table-Bundles laufen ueber `ResolvedBundle`).
>
> AP10 (Stream-vs-Datei-Portentscheidung) ist als Sub-Doc
> `parquet-port-shape.md` festgenagelt (Stand 2026-06-05).
> Definiert die `SeekableDataChunkReaderFactory`-Signatur als
> parallelen Port neben `DataChunkReaderFactory`, bewusst
> Format-agnostisch (`PARQUET` heute, kuenftige seekable
> Formate ohne Vertragsbruch); `SeekableChunkSource` als
> Sealed-Hierarchie mit konkretem `Local(path)`-Subtyp; reine
> `InputStream`-Quellen sind kein Bestandteil der Hierarchie
> (kein Temp-Spool). `ChunkSchema` ist Pflichtparameter der
> Factory (Schema kommt vom Preflight, nicht vom Datei-Footer).
> Writer-Seite bleibt stream-basiert (PositionOutputStream-
> Wrapper im Adapter). TableImporter bekommt zwei Factory-
> Referenzen; AP12 macht das Wiring.
>
> AP11 (Single-File-Metadatenvertrag) ist als Sub-Doc
> `parquet-single-file-metadata.md` festgenagelt
> (Stand 2026-06-05). Bindende Wahl: Option A — Footer-Key-
> Value-Metadaten mit Key `d-migrate.manifest` (UTF-8-YAML-
> Bytestrom als konditionell strikte Teilmenge des AP7-Bundle-
> Manifests, genau ein `tables[]`-Eintrag, kein
> `file`/`sha256`-Feld). Damit zog AP11 zwei kleine
> Praezisierungen mit: AP7 §5.2 `tables[].file` ist jetzt
> bedingt Pflicht (Bundle ja, Single-File nein); Single-File-
> Resume nutzt einen Preflight-berechneten SHA-256 ueber den
> gesamten Dateibytestrom, persistiert via neuer
> `SingleFileCheckpointSpecifics`-Variante (Checkpoint-Schema
> bleibt rueckwaertskompatibel). Footer-Parsing lebt
> ausschliesslich im Parquet-Adapter (`ParquetSingleFilePreflight`),
> der Streaming-Layer bleibt parquet-frei und sieht nur die
> port-neutrale `ResolvedTableInput.Seekable`-Variante (AP10
> §5.4). Sidecar (Option B) bewusst abgelehnt
> (Single-Artefakt-Versprechen + zwei parallele Manifest-
> Vertraege); Option C (Footer-only) abgelehnt, weil AP2
> §4.4 Schema-vor-Chunk verletzt. Fremder Parquet-File ohne
> `d-migrate.manifest`-Key bleibt lesbar (best-effort
> Footer-`MessageType` + Ziel-JDBC-Schema, CLI-Warnung).
>
> AP12 (CLI- und Factory-Wiring-Skizze) ist als Sub-Doc
> `parquet-cli-wiring.md` festgenagelt (Stand 2026-06-05).
> Zieht alle AP1-AP11-Vorentscheidungen ins konkrete
> CLI-/Wiring-Bild: `DataExportFormat.PARQUET`-Erweiterung +
> Clikt-Choice; neue Parquet-Flags
> (`--manifest-sha256` Export, `--no-checkpoint` Import);
> CSV-Flag-Ablehnung und `--encoding`-Silent-Ignore;
> Format-Auto-Detection mit `manifest.yaml`-Hook;
> Factory-Wiring fuer `SeekableDataChunkReaderFactory` und
> `DefaultDataChunkWriterFactory`-Parquet-Zweig;
> Bundle-Adapter- und Single-File-Adapter-CLI-Skelette;
> Checkpoint-Persistenz (`FileCheckpointStore.toMap`/`fromMap`
> mit `kind`-Diskriminator, `ImportCheckpointManager`
> Bundle-/SingleFile-Resume-Pruefungen); vollstaendige
> Sealed-Sweep-Liste fuer fuenf Hierarchien (`ImportInput`,
> `SchemaOrigin`, `SeekableChunkSource`,
> `CheckpointOperationSpecifics`, `DataExportFormat`);
> Exit-Code-Mapping fuer alle Fehlerklassen aus AP7-AP11;
> Native-Image-/Hadoop-API-Shim-Empfehlung
> (Reachability-Metadaten Pflicht, eigener Shim erst nach
> GraalVM-Smoketest); Test-Strategie mit sechs
> Pflicht-Familien; bindender Implementierungsplan in neun
> Schritten.
>
> AP13 (Entscheidungsvorlage) ist als Sub-Doc
> `parquet-decision-template.md` festgenagelt
> (Stand 2026-06-05). Synthetisiert AP1-AP12 zu Aufwand
> (27-44 PT netto, 35-60 PT brutto fuer die neun AP12-
> Implementierungs-Schritte; plus 5-15 PT Native-Image,
> 5-10 PT optionaler Hadoop-API-Shim), Risiko-Gesamtbild
> (vier Risiko-Kategorien) und drei gestaffelte Scope-Cuts.
> Empfehlung: **Cut B (Bundle-Pilot ohne Single-File) als
> 1.0.0**, mit Folge-Releases 1.1.0 (Single-File +
> `--no-checkpoint`) und 1.2.0 (Native-Image-Cut + optionaler
> Hadoop-API-Shim). Cut A (voller Vertrag in 1.0) ist
> akzeptabel, traegt aber die volle Single-File-Phase-1/2-
> Komplexitaet im 1.0-Risiko; Cut C (Bundle ohne Resume)
> verworfen, weil das Wertversprechen gegenueber
> `pg_dump | psql` ohne Resume zu duenn waere.
>
> **Damit ist die Evaluierung abgeschlossen.** Plan-Doc und
> alle Sub-Docs wandern beim naechsten Schritt nach
> `docs/planning/done/` oder verbleiben in `in-progress/`
> bis der Go/No-Go-Entscheid getroffen ist (Cut B empfohlen).
>
> Referenzen: `docs/planning/in-progress/roadmap.md`, `spec/architecture.md`,
> `spec/cli-spec.md`, `spec/connection-config-spec.md`,
> `parquet-libraries.md` (AP1-Bibliothekssichtung inkl. AP3-Befund-Rueckspiel),
> `parquet-schema-source.md` (AP2-Schemaquelle),
> `parquet-manifest-format.md` (AP7-Manifest-Format und Preflight),
> `parquet-directory-import.md` (AP8-Iterator und Directory-Aufloesung),
> `parquet-import-input-dto.md` (AP9-Importpfad-Vertrag, bindend),
> `parquet-port-shape.md` (AP10-Reader-Port-Vertrag, bindend),
> `parquet-single-file-metadata.md` (AP11-Footer-KV-Vertrag, bindend),
> `parquet-cli-wiring.md` (AP12-CLI-/Factory-Wiring-Skizze, bindend),
> `parquet-decision-template.md` (AP13-Entscheidungsvorlage, Aufwand/Risiken/Scope),
> `adapters/driven/formats-parquet/` (AP3-Spike-Modul).

---

## 1. Ziel

`d-migrate` unterstuetzt heute streaming-basierten Datenexport und -import
ueber JSON, YAML und CSV. Parquet soll evaluiert werden, weil es als
spaltenorientiertes Format einen direkten Anschluss an DuckDB, Apache Arrow
und spaetere Lakehouse-Ziele bietet.

Ziel dieser Evaluierung ist nicht sofort ein vollstaendiger Lakehouse-Adapter,
sondern ein belastbarer Entscheidungsrahmen:

- ob Parquet als zusaetzliches `DataExportFormat` sinnvoll ist
- welche Bibliothek in Kotlin/JVM wartbar einsetzbar ist
- wie Schema-, Typ- und Nullability-Informationen erhalten bleiben
- ob Import/Export weiterhin chunk-weise und speicherschonend bleibt
- welche Kompatibilitaet mit DuckDB und Arrow praktisch nachweisbar ist

---

## 2. Motivation

Parquet waere fuer `d-migrate` besonders nuetzlich in vier Szenarien:

- grosse Exporte, bei denen CSV/JSON zu gross oder zu langsam werden
- Analyse und Profiling exportierter Daten mit DuckDB
- reproduzierbare Migrationsartefakte fuer QA und Pilotvalidierung
- spaetere Anschlussfaehigkeit an Iceberg, Delta Lake oder andere
  Open-Table-Format-Ziele

Der Nutzen liegt primaer in Interoperabilitaet und Performance, nicht in
einem neuen fachlichen Migrationsmodell.

---

## 3. Scope

### 3.1 In Scope

- Evaluierung eines Parquet-Writers fuer `data export`
- Evaluierung eines Parquet-Readers fuer `data import`
- Abbildung des neutralen `DataChunk`-Modells auf Parquet-Spalten
- Umgang mit Decimal, Temporal, Binary, UUID, JSON, Arrays und Geometry
- Multi-Table-Export mit stabilen Dateinamen
- Kompatibilitaetsprobe mit DuckDB (`read_parquet`) und Apache Arrow
- Entscheidungsvorlage fuer einen spaeteren Implementierungsplan

### 3.2 Nicht in Scope

- Iceberg-/Delta-/Hudi-Tabellenverwaltung
- eigener Arrow-Ausfuehrungs- oder Query-Engine-Adapter
- Parquet als Ersatz fuer CSV/JSON/YAML
- automatische Schema-Evolution ueber mehrere Exportgenerationen
- Object-Storage-Implementierung; diese wird separat geplant

---

## 4. Architekturposition

Parquet sollte als Format-Adapter unter `adapters:driven:formats` beginnen.
Der bestehende Port-Schnitt bleibt massgeblich:

```text
hexagon:application
        |
        v
hexagon:ports-read / ports-write
        |
        v
adapters:driven:formats
        |
        v
ParquetChunkReader / ParquetChunkWriter
```

Ein spaeterer Arrow- oder Lakehouse-Adapter darf darauf aufbauen, sollte aber
nicht in den ersten Parquet-Schnitt hineingezogen werden.

---

## 5. Offene Architekturfragen

| Frage | Bewertungskriterium |
| ----- | ------------------- |
| Welche JVM-Bibliothek? | Wartung, Lizenz, Native-Image-Auswirkung, Streaming-Faehigkeit |
| Wie werden mehrere Tabellen abgelegt? | Verzeichnisstruktur, Sidecar-Metadaten, Import-Ergonomie |
| Wie stabil ist das Typmapping? | Round-trip PostgreSQL/MySQL/SQLite, Decimal/Temporal/UUID/Binary |
| Wie wird Geometry serialisiert? | WKB bevorzugt, optional WKT als spaeterer Modus |
| Wie wird JSON serialisiert? | String vs. Binary/JSON logical type, DuckDB-Kompatibilitaet |
| Wie wird Schema-Metadatenverlust vermieden? | Sidecar mit neutralem Schema oder TableManifest |
| Woher kommt das Export-Schema? | SchemaReader-/JDBC-Metadaten-Quelle, NeutralType-Mapping, leere Tabellen |
| Wie liest `data import` Parquet-Metadaten? | Manifest-Preflight vs. reiner `DataChunkReader`-Headervertrag |
| Reichen Stream-basierte Format-Ports? | `OutputStream`/`InputStream` vs. dateibasierte `Path`-/`InputFile`-/`OutputFile`-Ports |

---

## 6. Vorgeschlagener CLI-Zielvertrag

Der bestehende Format-Parameter koennte erweitert werden:

```text
d-migrate data export --source prod --tables users,orders \
  --format parquet --output out/export --split-files

d-migrate data import --target staging \
  --format parquet --source out/export
```

Fuer Multi-Table-Exporte muss der bestehende CLI-Vertrag erhalten bleiben:
`--output` ist gemeinsam mit `--split-files` ein Verzeichnis. Der Parquet-Plan
sollte nicht heimlich einen zweiten Directory-Modus neben `--split-files`
einfuehren.

```text
out/export/
  manifest.yaml
  users.parquet
  orders.parquet
```

Fuer Single-Table-Exporte ist zu pruefen, ob Parquet den heutigen stdout- und
Single-File-Vertrag technisch sauber erfuellen kann. Falls die gewaehlte
Bibliothek einen seekbaren File-Output oder Footer-Finalisierung verlangt,
sollte Parquet file-only dokumentiert und als formatspezifische CLI-Regel
validiert werden.

Fuer Parquet gilt als Zielvertrag:

- Multi-Table-/Directory-Exporte erzeugen immer ein `manifest.yaml`; ein
  Parquet-Directory-Import ohne Manifest ist kein unterstuetzter Bundle-Import.
- Single-Table-/Single-File-Exporte muessen die d-migrate-spezifischen
  Schema-Metadaten entweder in Parquet-Key-Value-Metadaten im Datei-Footer
  speichern oder, falls die gewaehlte Bibliothek das nicht verlaesslich
  unterstuetzt, als explizit dokumentierten Sidecar-Vertrag
  `<datei>.manifest.yaml` ausgeben und beim Import wieder einlesen.
- Wenn weder eingebettete Metadaten noch ein Sidecar fuer Single-File-Importe
  umgesetzt werden sollen, muss Parquet-Single-File-Import auf den reinen
  Parquet-Footer und das Ziel-JDBC-Schema beschraenkt und dieser
  Metadatenverlust als formatspezifische Einschraenkung dokumentiert werden.
- Stdout ist nur dann zulaessig, wenn die Writer-Bibliothek nicht-seekbares
  Schreiben inklusive Footer-Finalisierung sauber unterstuetzt und die
  noetigen d-migrate-Metadaten im Parquet-Footer mitgeschrieben werden.

Der bestehende Format-Port ist stream-basiert: Writer werden heute ueber einen
`OutputStream` erzeugt, Reader ueber einen `InputStream`. Die Evaluierung muss
explizit klaeren, ob die Parquet-Bibliothek damit ohne vollstaendiges
Zwischenpuffern arbeiten kann. Falls sie seekbare oder pfadbasierte APIs wie
`Path`, `InputFile`, `OutputFile`, Positionierung oder eigene
Footer-Finalisierung braucht, darf Parquet nicht in den bestehenden
`DataChunkReaderFactory`-/`DataChunkWriterFactory`-Vertrag hineingezwungen
werden. Dann braucht der Implementierungsplan entweder:

- einen erweiterten dateibasierten Format-Port fuer Formate mit
  Seek-/Footer-Anforderungen,
- eine Parquet-spezifische Adapter-Sonderbehandlung im
  Import-/Export-Resolver,
- oder eine klare Ablehnung von Parquet-Import/Export-Modi, die nur ueber
  nicht-seekbare Streams verfuegbar waeren.

Die CLI-Evaluierung muss nicht nur `DataExportFormat.PARQUET` betrachten,
sondern das vollstaendige Wiring:

- `data export --format` und `data import --format` nehmen heute explizit nur
  `json|yaml|csv` an.
- `DefaultDataChunkWriterFactory` und `DefaultDataChunkReaderFactory` muessen
  eine neue Formatvariante aufloesen.
- CSV-spezifische Flags (`--csv-*`, `--null-string`) duerfen fuer Parquet
  nicht still eine wirkungslose Bedeutung bekommen; die Evaluierung soll
  entscheiden, ob sie abgelehnt oder dokumentiert ignoriert werden.
- `--encoding` ist fuer binaeres Parquet anders zu behandeln als fuer
  Textformate.
- `ExportOutput` und die Writer-Factory muessen pruefen, ob Parquet statt
  eines generischen `OutputStream` einen dateibasierten Sink braucht.
- `ImportInput` und die Reader-Factory muessen pruefen, ob Parquet statt eines
  generischen `InputStream` eine dateibasierte Source braucht.
- Format-Autodetection muss fuer Parquet getrennt bewertet werden:
  Single-File-Import kann ueber `.parquet` erkannt werden; Directory-Import
  darf nur ueber ein gueltiges `manifest.yaml` automatisch als Parquet-Bundle
  erkannt werden. Ohne Manifest bleibt `--format parquet` zwar moeglich,
  scheitert dann aber im Parquet-Preflight.
- Resume-/Checkpoint-Fingerprints muessen Parquet-spezifische Output-Regeln,
  Manifestversion und ggf. Row-Group-/Writer-Optionen einbeziehen.

Das Manifest enthaelt mindestens:

- Formatversion
- Tabellenliste
- explizites Mapping `Tabelle -> Datei`, inklusive schema-qualifizierter
  Tabellen und Kollisionsschutz
- Spaltenreihenfolge
- neutrale Typinformationen, soweit verfuegbar
- Ursprung des Typmappings (z.B. SchemaReader, JDBC-Metadaten,
  manifestseitige Fallback-Regel)
- JDBC-/SQL-Hints, soweit fuer verlustfreie Parquet-Schemaerzeugung noetig:
  originaler SQL-Typname, JDBC-Typcode, Precision/Scale fuer Decimal,
  Temporal-/Timezone-Hinweise
- Nullability pro Spalte
- Exportzeitpunkt
- optional SHA-256 pro Datei

Fuer Parquet-Bundles ist dieses Manifest verpflichtend. Es ist nicht nur ein
informativer Sidecar, sondern der Preflight-Vertrag fuer Tabellenbindung,
Kollisionsschutz, Schema-Metadaten und optionale Integritaetspruefung.

Der Exportpfad darf Typen nicht nur aus den Laufzeitwerten eines Chunks
ableiten. `DataChunk`/`ColumnDescriptor` tragen heute Name, Nullability und
optional einen opaken SQL-Typnamen, aber keinen neutralen Typ, keinen
JDBC-Typcode und keine Precision-/Scale-Information. Ein Parquet-Writer braucht
sein Spaltenschema jedoch vor dem Schreiben der ersten Row Group; leere
Tabellen, komplett `NULL`-gefuellte Spalten und Decimal-/Temporal-Typen waeren
sonst nicht belastbar abbildbar. Die Evaluierung muss deshalb entscheiden, ob
der Export vor dem Streaming eine SchemaReader-basierte Typquelle nutzt, ob
JDBC-Metadaten zu einem neutralen Parquet-Typmapping erweitert werden oder ob
ein separates formatseitiges Schemaobjekt eingefuehrt wird.

Der Importpfad braucht dafuer einen expliziten Preflight-Vertrag: Heute liefert
der `DataChunkReader` file-derived Header und Rows, waehrend Typen aus dem
Ziel-JDBC-Schema kommen. Parquet darf Typ- und Nullability-Informationen daher
nicht nur implizit im Reader verstecken. Die Evaluierung muss entscheiden, ob
ein `ParquetManifestReader` vor dem Streaming-Import eingebunden wird oder ob
der generische Importvertrag um formatseitige Schema-Metadaten erweitert wird.

Fuer Parquet-Directory-Importe reicht es nicht, Parquet-Dateien nur ueber
Dateiendungen zu entdecken. Der Preflight muss das verpflichtende Manifest
validieren und daraus die stabilen Datei-Bindings ableiten:

- jede referenzierte Datei existiert, ist regulaer und liegt innerhalb des
  Importverzeichnisses
- alle importierten Parquet-Dateien sind im Manifest referenziert; unbekannte
  `.parquet`-Dateien im Bundle werden abgelehnt oder explizit ignoriert, aber
  nicht still importiert
- jede Datei ist hoechstens einer Tabelle zugeordnet
- schema-qualifizierte Tabellen und gleichnamige Tabellen in verschiedenen
  Schemas werden ueber das Manifest eindeutig aufgeloest
- optionale SHA-256-Werte werden vor dem Import geprueft
- der Streaming-Import bekommt die manifestseitig aufgeloeste Tabellenordnung
  und Datei-Zuordnung; er darf nicht erneut nur aus Dateinamen inferieren

Das erfordert voraussichtlich einen expliziten Vertragsumbau im Importpfad:
`ImportInput.Directory` traegt heute nur Verzeichnis, optionalen Tabellenfilter
und Tabellenordnung. Fuer Parquet muss der Preflight entweder ein neues
resolved DTO mit `Tabelle -> Pfad`-Bindings an den Streaming-Import uebergeben
oder `ImportInput.Directory` um manifestseitige Bindings erweitern. Diese
Entscheidung ist Teil der Evaluierung; ohne sie faellt der Importpfad wieder
auf extension-/dateinamensbasierte Erkennung zurueck.

---

## 7. Akzeptanzkriterien fuer die Evaluierung

- Ein Beispiel-Export kann mit DuckDB gelesen werden.
- Der Beispiel-Export kann mit Arrow-Werkzeugen oder Arrow-Java-Metadaten
  inspiziert werden.
- Ein Round-trip `PostgreSQL -> Parquet -> PostgreSQL` bleibt fuer
  Kern-Datentypen verlustfrei.
- Leere Tabellen und Spalten mit ausschliesslich `NULL`-Werten behalten ein
  korrektes Parquet-Schema inklusive Typ und Nullability.
- Ein Round-trip `MySQL -> Parquet -> SQLite` dokumentiert erwartete
  Typdegradierungen statt sie still zu verschweigen.
- Speicherverbrauch bleibt durch Chunk-Groesse plus konfigurierbaren
  Writer-/Row-Group-Puffer begrenzt; keine Tabelle wird vollstaendig in den
  Heap geladen.
- Ein Directory-Import nutzt manifestseitige `Tabelle -> Datei`-Bindings und
  besteht einen Test mit schema-qualifizierten Tabellen sowie kollidierenden
  unqualifizierten Namen.
- Ein Parquet-Directory-Import ohne Manifest scheitert im Preflight mit einer
  klaren formatspezifischen Fehlermeldung.
- Single-File-Importe sind entweder ueber eingebettete Parquet-Metadaten oder
  einen dokumentierten Sidecar-Vertrag abgedeckt; andernfalls ist der bewusst
  eingeschraenkte Footer-/Ziel-Schema-Modus dokumentiert und getestet.
- CLI-Preflight-Tests decken `parquet` in Export und Import ab, inklusive
  file-only/stdout-Regeln, CSV-Flag-Verhalten, Format-Autodetection und
  Checkpoint-Fingerprint-Stabilitaet.
- Die Evaluierung weist nach, ob Parquet mit den bestehenden
  `InputStream`-/`OutputStream`-Ports ohne Vollpufferung funktioniert. Falls
  nicht, liegt ein konkreter dateibasierter Port-/DTO-Vertrag fuer Reader,
  Writer und Resolver vor.
- Die Evaluierung dokumentiert klar, ob Parquet in 1.x umgesetzt werden soll.

---

## 8. Arbeitspakete

1. JVM-Parquet-Bibliotheken gegen Lizenz, API und Streaming-Verhalten pruefen.
   Erledigt als Sub-Doc `parquet-libraries.md` (Stand 2026-06-04). Vorentscheidung:
   `parquet-java` ohne Hadoop-Runtime; final nach AP2 und AP3.
2. Exportseitige Schemaquelle festlegen: SchemaReader, JDBC-Metadaten oder
   separates formatseitiges Schemaobjekt; dabei leere Tabellen,
   `NULL`-Spalten, Decimal-Precision/Scale und Temporal-Typen pruefen.
   Ausgearbeitet als Sub-Doc `parquet-schema-source.md` (Stand 2026-06-04).
   Vorentscheidung: formatseitiges `ChunkSchema` mit JDBC-Metadaten als
   Primaer- und `NeutralType` als Ergaenzungsquelle; final nach AP3.
3. Prototyp fuer `ParquetChunkWriter` mit minimalem, explizitem Typmapping
   bauen. Erledigt als Spike-Modul `adapters/driven/formats-parquet/`
   mit `ParquetSpike.kt` + Round-Trip-Test (Stand 2026-06-05).
   Spike-Befunde sind in `parquet-libraries.md` §5.1, §7 und §8
   eingearbeitet; AP1-Vorentscheidung (`parquet-java 1.17.1` ohne
   Hadoop-Cluster) ist damit bestaetigt, allerdings auf der
   Hadoop-API-Linie ueber `LocalFileSystem` statt auf
   `PlainParquetConfiguration` (1.18+-Pfad).
4. Prototyp gegen DuckDB lesen lassen und Typen inspizieren.
   Erledigt als `ParquetSpikeDuckDbReadTest` im Spike-Modul
   (Stand 2026-06-05). DuckDB JDBC 1.5.3.0 (testImplementation,
   nicht produktiv — vgl. `parquet-libraries.md` §3.5) liest den
   GZIP-komprimierten Spike-Output via
   `SELECT * FROM read_parquet(?)` und meldet die Spalten als
   `INTEGER`/`VARCHAR`/`BOOLEAN` (Akzeptanzkriterium §7 Bullet 1
   abgehakt; §7 Bullet 3 fuer das Spike-Schemafragment
   `int+UTF-8-string+boolean` implizit).
5. Prototyp gegen Arrow-Werkzeuge oder Arrow-Java-Metadateninspektion pruefen.
   Erledigt als `ParquetSpikeArrowInspectTest` im Spike-Modul
   (Stand 2026-06-05). `parquet-arrow` 1.17.1 (testImplementation,
   reines JVM-Modul — keine `arrow-dataset`-JNI-Last, vgl.
   `parquet-libraries.md` §3.4) liest den Footer ueber
   `ParquetFileReader`, konvertiert via
   `SchemaConverter#fromParquet` zu Arrow `Schema` und der Test
   verifiziert `Int(32, signed)`/`Utf8`/`Bool` plus
   `isNullable=false` fuer die drei Spike-Spalten
   (Akzeptanzkriterium §7 Bullet 2 abgehakt).
6. Importpfad fuer denselben Prototyp pruefen.
   Erledigt als `ParquetSpikeImportPathTest` im Spike-Modul
   (Stand 2026-06-05). `ParquetSpike` um drei Bausteine erweitert:
   `readSchemaFromFooter` mappt `MessageType`-Felder aus dem
   `ParquetFileReader`-Footer zu neutralen
   `dev.dmigrate.core.data.ColumnDescriptor`-Tupeln (name/nullable/
   `sqlTypeName` als opaker Parquet-Originaltyp); `readAsChunk`
   kombiniert das mit `ParquetReader`-Rows zu einem
   `dev.dmigrate.core.data.DataChunk`; `writeWithoutCrc`
   demonstriert die `.crc`-Sidecar-Mitigation aus
   `parquet-libraries.md` §7 Variante (a) via
   `fs.file.impl=RawLocalFileSystem` plus
   `fs.file.impl.disable.cache=true` (AP6-Befund: die zweite
   Direktive ist in Hadoop 3.4.1 noetig, sonst greift der
   `FileSystem`-Service-Loader-Cache).
7. Manifest-Format inklusive `Tabelle -> Datei`-Mapping und Import-Preflight
   skizzieren. Ausgearbeitet als Sub-Doc `parquet-manifest-format.md`
   (Stand 2026-06-05). Legt das YAML-Schema von `manifest.yaml`
   (`formatVersion`, `producer`, `exportedAt`, `schemaSource`,
   `tables[].{table,file,sha256?,columns}`), die Tabelle-zu-Datei-
   Aufloesung mit Kollisionsschutz K1-K5, das SHA-256-Verfahren
   (opt-in, gehasht wird der fertige Parquet-Bytestrom nach
   `close()`), die Formatversionierung (`MAJOR.MINOR`, Start bei
   1.0, additive Minor-Bumps tolerieren unbekannte Felder) und den
   Preflight-Vertrag mit elf stabilen Fehlerklassen
   (`MANIFEST_NOT_FOUND` bis `MANIFEST_SHA256_MISMATCH`) fest.
   Vorentscheidungen final nach AP8/AP9.
8. Manifestgebundene Directory-Import-Aufloesung entwerfen, sodass der
   Streaming-Import nicht wieder nur anhand von Dateinamen/Endungen inferiert.
   Ausgearbeitet als Sub-Doc `parquet-directory-import.md`
   (Stand 2026-06-05). Definiert den `ParquetBundleResolver`
   (`resolve(): List<ParquetTableBinding>` ueber
   `ResolvedParquetBundle.tables`, kein one-shot Iterable;
   `StreamingImporter` braucht `.size` fuer Progress), Tabellenordnung
   primaer aus Manifest mit `tableOrder`-Override,
   `ChunkSchema`-Konstruktion in drei Stufen
   (Manifest-`neutralType` -> JDBC-Hint-Tupel ->
   `sqlTypeName`-Heuristik -> Fehler; JDBC-Hints nur als
   Eingaben, nicht im `ChunkColumnSchema`), strikte
   Mid-stream-Fehlerbehandlung (`BUNDLE_TABLE_IMPORT_FAILED`),
   Resume-Vorbedingung "Datei-Hashes vorhanden"
   (`BUNDLE_RESUME_REQUIRES_FILE_HASHES`) plus AP7-Live-Preflight
   zwangsweise aktiv, Resume-Fingerprint mit `manifestSha256` +
   `formatVersion` + `producerVersion` + effektiver `tableOrder`
   (kein `fileSha256ByTable`, weil redundant zu `manifestSha256`),
   Format-Autodetection ueber `manifest.yaml` mit Vorbedingung
   `DataExportFormat.PARQUET` und `resolveFormat`-Hook, plus
   Code-Konsequenzen mit port-eigenem, Parquet-freiem
   `ImportInput.ResolvedBundle`-Subtyp (Adapter uebersetzt am
   Port-Eintritt).
9. Importpfad-Vertrag fuer manifestseitige `Tabelle -> Pfad`-Bindings klaeren:
   neues resolved DTO oder Erweiterung von `ImportInput.Directory`.
   Ausgearbeitet als Sub-Doc `parquet-import-input-dto.md`
   (Stand 2026-06-05). Bindende Wahl: neuer Subtyp
   `ImportInput.ResolvedBundle` mit
   `ResolvedBundleTableBinding(table, path, schema,
   expectedSha256)` und `BundleResumeFingerprint`(manifestSha256,
   formatVersion, producerVersion, tableOrder) in
   `hexagon:ports-write`. Begleit-Bausteine: neue
   `BundleCheckpointSpecifics : CheckpointOperationSpecifics`
   im selben Modul (AP8 §10.5 wird damit implementierbar),
   Adapter-Translator `ParquetBundleAdapter` als einzige Stelle
   adapter-interne -> Port-Begriffe. Zwei Begleitentscheidungen:
   AP2 `SchemaOrigin` um `MANIFEST_FALLBACK` erweitert (additiv,
   `hexagon:ports-common`), AP1 §7.1 ImportInput.Directory-
   Aussage finalisiert (`Directory` bleibt fuer JSON/YAML/CSV
   und Single-File-Bundles erhalten, Multi-Table-Bundles laufen
   ueber `ResolvedBundle`). Migrations-/Impact-Analyse fuer
   sieben Module enthalten; Implementierung folgt nach AP12.
10. Stream-vs-Datei-Portentscheidung fuer Parquet klaeren: bestehende
   `InputStream`-/`OutputStream`-Factories, erweiterter dateibasierter
   Format-Port oder Parquet-spezifischer Resolver-/Adapterpfad.
   Ausgearbeitet in `parquet-libraries.md` Abschnitt 7 (Vor-
   entscheidung 2026-06-04) und als Sub-Doc `parquet-port-shape.md`
   bindend gemacht (Stand 2026-06-05). Bindender Vertrag: neuer
   Port `SeekableDataChunkReaderFactory` in `hexagon:ports-read`
   parallel zu `DataChunkReaderFactory`; bewusst Format-agnostisch
   (`PARQUET` heute, kuenftige seekable Formate ohne Vertragsbruch);
   `SeekableChunkSource` als sealed mit `Local(path)`-Subtyp;
   reine `InputStream`-Quellen werden NICHT in die Sealed-
   Hierarchie aufgenommen (kein Temp-Spool). `ChunkSchema` ist
   Pflichtparameter der `create(...)`-Signatur; das Schema kommt
   vom Bundle-Preflight, nicht aus dem Datei-Footer. Writer-Seite
   bleibt unveraendert stream-basiert; Parquet-Writer wraps den
   `OutputStream` in einen `PositionOutputStream`-Adapter
   (stdout-tauglich). CLI-Spiegelung: `data import --format
   parquet` akzeptiert nur `--source <pfad>`. TableImporter
   bekommt eine zweite Factory-Referenz; AP12 macht das Wiring.
11. Single-File-Metadatenvertrag klaeren: Parquet-Footer-Key-Value-Metadaten,
   expliziter Sidecar oder bewusst eingeschraenkter Footer-/Ziel-Schema-Modus.
   Ausgearbeitet als Sub-Doc `parquet-single-file-metadata.md`
   (Stand 2026-06-05). Bindende Wahl: Option A — Footer-KV mit
   Key `d-migrate.manifest`, YAML-Bytestrom als strikte Teilmenge
   des AP7-Bundle-Manifests (genau ein `tables[]`-Eintrag, kein
   `file`/`sha256`-Feld, weil Datei sich selbst kennt und ein
   Hash ueber den eigenen Bytestrom inkl. Hash zirkulaer waere).
   Sidecar abgelehnt (Single-Artefakt-Versprechen, zwei parallele
   Manifest-Vertraege); Footer-only abgelehnt (AP2 §4.4 Schema-
   vor-Chunk-Vertragsbruch). Fremde Parquet-Dateien ohne den Key
   bleiben lesbar als best-effort Footer-`MessageType` +
   Ziel-JDBC-Schema (CLI-Warnung). Stdin-Single-File-Import bleibt
   ausgeschlossen (`parquet-libraries.md` §7 + AP10 §3.3).
   Implementierungscode (`ParquetSingleFileManifestWriter/Reader`,
   `TableImporter`-SingleFile-Zweig) folgt nach AP12.
12. CLI- und Factory-Wiring skizzieren: `DataExportFormat`, Clikt-Choices,
   Reader-/Writer-Factories, Format-Autodetection, CSV-Flag-Validierung,
   Encoding-Regel, Directory-Autodetection ueber Manifest und
   Checkpoint-Fingerprint. Ausgearbeitet als Sub-Doc
   `parquet-cli-wiring.md` (Stand 2026-06-05). Bindender Inhalt:
   `DataExportFormat.PARQUET` als additive Enum-Erweiterung;
   neue Parquet-Flags (`--manifest-sha256` Export-only,
   `--no-checkpoint` Import-only); CSV-Flag-Ablehnung bei
   `--format parquet` (`CSV_FLAG_INVALID_FOR_PARQUET`);
   `--encoding` silent-ignore (Skript-Kompatibilitaet);
   Format-Auto-Detection-Regel
   (`--format` > Verzeichnis-`manifest.yaml` > Endungs-Inferenz);
   `StreamingImporter`-Constructor mit Pflichtparameter
   `seekableReaderFactory`; `FileCheckpointStore.toMap`/`fromMap`
   mit `operationSpecific.kind`-Diskriminator (`parquet-bundle`,
   `parquet-single-file`); `ImportCheckpointManager` mit
   getrennten Bundle-/SingleFile-Resume-Validierungen;
   `InputContext` um `bundleExpectedSha256ByTable` und
   `singleFileContentSha256` erweitert; vollstaendige
   Sealed-Sweep-Liste fuer fuenf Hierarchien
   (`ImportInput`/`SchemaOrigin`/`SeekableChunkSource`/
   `CheckpointOperationSpecifics`/`DataExportFormat`);
   Exit-Code-Familien (`MANIFEST_*` 4, `BUNDLE_*` 5,
   `BUNDLE_RESUME_*` 3, `PARQUET_SINGLE_FILE_*` 4,
   `BUNDLE_SCHEMA_PARQUET_MISMATCH` 4,
   `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND` 3,
   `CSV_FLAG_INVALID_FOR_PARQUET` 2); Native-Image- und
   Hadoop-API-Shim-Empfehlung (Reachability-Metadaten Pflicht,
   eigener Shim erst nach GraalVM-Smoketest); Test-Strategie
   mit sechs Pflicht-Familien (CLI-Preflight, Format-Resolver,
   Resume, DuckDB-/Arrow-KV-Toleranz, Sealed-Sweep, plus
   bestehende AP4/AP5-Linien); bindender Implementierungsplan
   in neun entkoppelten Schritten. AP13 entscheidet, welche
   Schritte im 1.x-Cut zwingend sind.
13. Entscheidungsvorlage mit Aufwand, Risiken und empfohlenem Scope erstellen.
   Ausgearbeitet als Sub-Doc `parquet-decision-template.md`
   (Stand 2026-06-05). Aufwand pro AP12-Schritt
   (27-44 PT netto, 35-60 PT brutto inkl. Review-Zyklen);
   Risiko-Gesamtbild in vier Kategorien (wahrscheinlich-und-
   aufwaendig: Native-Image, Hadoop-Footprint, Sweep-
   Vollstaendigkeit; wahrscheinlich-und-billig:
   CSV-Flag-Skript-Bruch, Auto-Detection-Falle,
   pre-AP8-Checkpoint-Bruch; unwahrscheinlich-aber-teuer:
   parquet-java 1.18-Wechsel, CVE; akzeptiertes Restrisiko:
   semantischer Schema-Drift, Sealed-Modul-Lokalitaet,
   Single-File-Bundle-Manifest-Asymmetrie). Drei gestaffelte
   Scope-Cuts: A (voller Vertrag in 1.0), B (Bundle-Pilot in
   1.0 + Single-File in 1.1, empfohlen), C (Bundle ohne
   Resume in 1.0, verworfen). Empfehlung Cut B mit
   Folge-Releases 1.1 und 1.2. Fuenf offene Punkte vor
   Implementierung (Release-Branch-Strategie, Gradle-
   Distributions-Cut, DuckDB-/Arrow-Test-Status, MCP-
   Server-Spiegelung, Hadoop-API-Shim-Folge-Entscheidung).

---

## 9. Risiken

- Parquet-Bibliotheken koennen die GraalVM-Native-Image-Planung erschweren.
- Komplexe Typen wie Geometry, JSON und Arrays koennen ohne Sidecar
  semantische Informationen verlieren.
- Ohne klaren Manifest-Preflight kann der Importpfad Parquet-Typinformationen
  ignorieren und nur gegen das Ziel-JDBC-Schema importieren.
- Wenn Single-File-Exporte d-migrate-Metadaten weder im Parquet-Footer noch in
  einem Sidecar speichern, entsteht ein anderer Importvertrag als beim
  Directory-Bundle; dieser Unterschied muss bewusst begrenzt und dokumentiert
  werden.
- Parquet kann je nach JVM-Bibliothek seekbare oder dateibasierte Input-/Output-
  Abstraktionen verlangen. Ein erzwungener `InputStream`-/`OutputStream`-Pfad
  koennte zu Vollpufferung, kaputtem Footer-Zugriff oder nicht portabler
  Sonderlogik fuehren.
- Ohne manifestseitige `Tabelle -> Pfad`-Bindings im Import-DTO kann der
  Streaming-Import das validierte Manifest nicht nutzen und wuerde wieder
  Dateien aus Endungen und Namen ableiten.
- Parquet ist spaltenorientiert; sehr kleine Tabellen profitieren kaum.
- Eine zu fruehe Lakehouse-Abstraktion wuerde den bestehenden Format-Adapter
  unnoetig verkomplizieren.
- Die bestehende `--split-files`-Semantik erzeugt eine Datei pro Tabelle, nicht
  pro Chunk. Das Parquet-spezifische Risiko liegt daher vor allem in zu kleinen
  Row Groups, zu haeufigem Flushen oder sehr vielen kleinen Tabellen-Dateien.
  Der Prototyp sollte klaeren, wie Chunks innerhalb einer Datei pro Tabelle zu
  sinnvollen Row Groups akkumuliert werden und ob ein adaptives Commit-Muster
  (Mindestbatchgroesse vor Flush) noetig wird, sobald der Writer-Pfad
  streamingnah arbeitet. Akzeptiert wird kein vollstaendiges Tabellenbuffering,
  sondern ein begrenzter Writer-/Row-Group-Puffer mit konfigurierbarer
  Obergrenze.
