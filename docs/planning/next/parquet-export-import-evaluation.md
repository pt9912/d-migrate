# Evaluierung: Parquet-Export und -Import

> Dokumenttyp: Evaluierungs- und Architekturplan
>
> Status: Entwurf (2026-05-01, AP1-Update 2026-06-04, AP2-Update 2026-06-04,
> AP10-Update 2026-06-04)
>
> Referenzen: `docs/planning/in-progress/roadmap.md`, `spec/architecture.md`,
> `spec/cli-spec.md`, `spec/connection-config-spec.md`,
> `parquet-libraries.md` (AP1-Bibliothekssichtung),
> `parquet-schema-source.md` (AP2-Schemaquelle)

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
   bauen.
4. Prototyp gegen DuckDB lesen lassen und Typen inspizieren.
5. Prototyp gegen Arrow-Werkzeuge oder Arrow-Java-Metadateninspektion pruefen.
6. Importpfad fuer denselben Prototyp pruefen.
7. Manifest-Format inklusive `Tabelle -> Datei`-Mapping und Import-Preflight
   skizzieren.
8. Manifestgebundene Directory-Import-Aufloesung entwerfen, sodass der
   Streaming-Import nicht wieder nur anhand von Dateinamen/Endungen inferiert.
9. Importpfad-Vertrag fuer manifestseitige `Tabelle -> Pfad`-Bindings klaeren:
   neues resolved DTO oder Erweiterung von `ImportInput.Directory`.
10. Stream-vs-Datei-Portentscheidung fuer Parquet klaeren: bestehende
   `InputStream`-/`OutputStream`-Factories, erweiterter dateibasierter
   Format-Port oder Parquet-spezifischer Resolver-/Adapterpfad.
   Ausgearbeitet in `parquet-libraries.md` Abschnitt 7 (Stand 2026-06-04).
   Vorentscheidung: Reader file-/pfadbasiert, reine `InputStream`-Quellen
   werden abgelehnt, kein impliziter Temp-Spool; Writer-stdout bleibt via
   `PositionOutputStream` erlaubt. CLI-Spiegelung: `data import --format
   parquet` akzeptiert nur `--source <pfad>`. Final nach AP3.
11. Single-File-Metadatenvertrag klaeren: Parquet-Footer-Key-Value-Metadaten,
   expliziter Sidecar oder bewusst eingeschraenkter Footer-/Ziel-Schema-Modus.
12. CLI- und Factory-Wiring skizzieren: `DataExportFormat`, Clikt-Choices,
   Reader-/Writer-Factories, Format-Autodetection, CSV-Flag-Validierung,
   Encoding-Regel, Directory-Autodetection ueber Manifest und
   Checkpoint-Fingerprint.
13. Entscheidungsvorlage mit Aufwand, Risiken und empfohlenem Scope erstellen.

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
