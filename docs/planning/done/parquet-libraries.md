# AP1: Parquet-Bibliothekssichtung (JVM)

> Dokumenttyp: Evaluierungs-Sub-Doc zu `parquet-export-import-evaluation.md`
>
> Status: Final (Stand 2026-06-04; AP3-Befund-Rueckspiel
> 2026-06-05 via §5.1, §7 `.crc`-Block und §8 MapReduce-Dep;
> Sub-Doc seit 2026-06-05 in `docs/planning/done/`).
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8 Arbeitspaket 1,
> `spec/architecture.md`, `spec/cli-spec.md`

---

## 1. Ziel

Arbeitspaket 1 des Parquet-Evaluierungsplans verlangt eine Sichtung der
JVM-Parquet-Bibliotheken gegen Lizenz, API und Streaming-Verhalten. Dieser
Sub-Doc

- listet die in Frage kommenden Kandidaten,
- bewertet sie gegen die Kriterien aus `parquet-export-import-evaluation.md`
  Abschnitt 5 und 6,
- liefert eine Vorentscheidung fuer den Prototyp (AP3).

Die finale Bibliothekswahl ist erst nach AP2 (Schemaquelle) und AP3 (Prototyp)
bindend; dieser Sub-Doc engt den Entscheidungsraum vor.

---

## 2. Bewertungskriterien

Operationalisiert aus dem Hauptplan:

| Nr. | Kriterium | Warum relevant |
| --- | --------- | -------------- |
| K1  | Lizenz | Muss zu d-migrate (Apache 2.0) kompatibel sein. |
| K2  | Aktive Wartung | Sicherheits-Patches; relevant nach CVE-2025-30065/46762. |
| K3  | Streaming-Vertrag | `OutputStream`/`InputStream` vs. dateibasierte `OutputFile`/`InputFile`; entscheidet, ob der bestehende `DataChunkReader`-/`DataChunkWriter`-Port erhalten bleibt. |
| K4  | Hadoop-Abhaengigkeit | Hadoop-Runtime ist im d-migrate-Distributionsmodell unerwuenscht (CLI-Binary, Native-Image-Ziel). |
| K5  | GraalVM-Tauglichkeit | Roadmap-Ziel Native-Image; Reflection-/Service-Loader-/JNI-Lasten beeinflussen Reachability-Metadaten. |
| K6  | Dynamische Schemaerzeugung | `DataChunk` traegt zur Laufzeit definierte Spalten. Record-/Annotation-getriebene APIs zwingen Codegen oder Reflection auf eine Domain, die d-migrate nicht kennt. |
| K7  | Typabdeckung | Decimal mit Precision/Scale, Temporal inkl. Timezone, UUID, Binary, JSON, Arrays, nullable Spalten (vgl. Hauptplan Abschnitt 5). |
| K8  | Footer-Key-Value-Metadaten | Vertrag fuer Single-File-Metadaten ohne Sidecar (Hauptplan Abschnitt 6). |
| K9  | Row-Group-Steuerung | Begrenzter Writer-Puffer ohne vollstaendiges Tabellenbuffering (Hauptplan Abschnitt 9, Risiko Row-Group). |
| K10 | Binaerfussabdruck | Auswirkung auf Distributions-Artefakt (Fat-JAR, Native-Image). |

---

## 3. Kandidaten

### 3.1 `parquet-java` (vormals `parquet-mr`)

- Apache-Referenzimplementierung; Apache 2.0; aktiv unter
  `github.com/apache/parquet-java`.
- Zielversion fuer den Prototyp: 1.17.1 (aktuelle Linie, schliesst die
  bekannten 1.15.x-CVEs ein). CVE-Historie zur Kontextualisierung:
  CVE-2025-30065 (parquet-avro) wurde in 1.15.1 gepatcht; CVE-2025-46762
  erforderte 1.15.2 oder den dokumentierten Property-Workaround. 1.17.1
  liegt darueber; das deutet auf belastbare Sicherheits-Reaktivitaet hin.
- Definiert die Interfaces `org.apache.parquet.io.OutputFile`,
  `org.apache.parquet.io.InputFile`, `PositionOutputStream` und
  `SeekableInputStream`. Schreiben und Lesen haben unterschiedliche
  Stream-Anforderungen:
  - Writer: `OutputFile` liefert einen `PositionOutputStream`, der nur die
    aktuelle Schreibposition zaehlen muss; ein nicht-seekbarer, zaehlender
    `OutputStream` reicht prinzipiell (Header, Row Groups, Footer werden in
    Schreibreihenfolge angehaengt).
  - Reader: `InputFile` liefert einen `SeekableInputStream`. Parquet liest den
    Footer am Dateiende und springt danach in Row Groups; das erzwingt
    seekbaren Zugriff (Datei, seekbarer Adapter oder vollstaendiges
    Temp-Spool fuer reine `InputStream`-Quellen).
- PARQUET-1822 / PR 1111 (gemerged) bietet Hadoop-freie
  `LocalInputFile`/`LocalOutputFile`-Implementierungen und einen
  `PlainParquetConfiguration`. Damit ist `parquet-java` zur Laufzeit ohne
  Hadoop-Installation einsetzbar. Wichtig: der eigentliche `ParquetWriter`-
  Builder-Pfad liegt nach wie vor im Artefakt `parquet-hadoop` und Apache
  listet `parquet-hadoop` selbst als Maven-Dependency. Wegfallen kann also
  die Hadoop-Runtime, nicht das `parquet-hadoop`-Artefakt. AP3 muss den
  exakten Gradle-/Runtime-Satz (welche Artefakte, welche transitiven
  Hadoop-API-Klassen) verifizieren, damit Hadoop-Footprint und
  GraalVM-Risiko nicht unterschaetzt werden.
- Dynamische Schemaerzeugung ueber `MessageType` + `GroupWriteSupport` /
  `RecordReader`-Ableitungen; passt zum d-migrate-Modell, ist aber Low-Level.
- Footer-Key-Value-Metadaten ueber `ParquetWriter.Builder#withExtraMetaData`
  und `ParquetFileReader#getFileMetaData().getKeyValueMetaData()` direkt
  zugaenglich.
- Row-Group-Steuerung ueber `withRowGroupSize`, `withPageSize`, Validierungs-
  und Compression-Flags; deckt das Puffermodell aus Hauptplan Abschnitt 9 ab.
- Compression-Codecs: `parquet-hadoop:1.17.1` zieht `snappy-java` und
  `zstd-jni` als Compile-Dependencies. Beide laden plattformspezifische
  Native-Bibliotheken per JNI aus dem JAR. Damit ist die Aussage „reines
  JVM" nur richtig, wenn der Default-Codec `SNAPPY` (parquet-default) durch
  einen reinen JVM-Codec ersetzt und die JNI-Artefakte ausgeschlossen
  werden. Vorschlag fuer d-migrate: Default-Compression auf `GZIP`
  (`java.util.zip`) oder `UNCOMPRESSED` setzen und `snappy-java`/`zstd-jni`
  excluden; siehe Abschnitt 8.
- GraalVM: Reflection-/Service-Loader-Lasten bestehen
  (z.B. Codec-Registrierung). Machbar mit Reachability-Metadaten;
  parquet-avro waere ein Risiko und wird hier nicht benoetigt. JNI-Pfade
  fuer Native-Codecs sind nur dann ein Thema, wenn diese nicht
  ausgeschlossen werden.

### 3.2 `parquet-carpet` (jerolba)

- Apache 2.0; aktive Release-Linie, aktuell 0.7.1 auf Maven Central.
- Java 17+, Record-getrieben. Schema wird aus Java-Records per Reflection
  abgeleitet.
- `CarpetWriter<>(OutputStream, RecordClass)` akzeptiert direkt einen
  `OutputStream` und ist damit am naechsten am bestehenden d-migrate-Port.
- Carpet 0.7.1 zieht `parquet-hadoop` und `hadoop-common` als
  Compile-Dependencies; der Hadoop-Footprint ist also nicht eliminiert,
  sondern nur durch eine schlankere Default-Konfiguration verdeckt. Fuer
  d-migrate ist das relevant, weil GraalVM- und Distributionsfragen
  identisch zu parquet-java sind.
- Decimal-/Temporal-Konfiguration und nested Maps/Collections dokumentiert.
- Schwaeche fuer d-migrate: Carpets zentrale API ist auf statisch bekannte
  Record-Klassen ausgelegt. `DataChunk` hat zur Laufzeit definierte Spalten
  ohne korrespondierende Record-Definition. Carpet liefert zwar einen
  Low-Level-Pfad ueber `org.apache.parquet`-Typen, dann liegt aber faktisch
  wieder `parquet-java` darunter; Carpet wird damit ein duenner Layer mit
  Reflection-Overhead, der gerade die GraalVM-Story verschlechtert.
- Footer-Key-Value-Metadaten und Row-Group-Tuning sind im Record-Pfad nicht
  prominent; Erreichbarkeit per Low-Level-Pfad muesste der Prototyp pruefen.

### 3.3 `parquet-floor` (strategicblue)

- Apache 2.0; aktuelle Version laut Maven Central
  (`blue.strategic.parquet:parquet-floor:2.1`). Die Release-Kadenz ist
  ueberschaubar, aber neuer als zunaechst angenommen; das frueher
  formulierte „Wartungssignal schwach" ist nicht belastbar.
- Hadoop-Aussage praezisiert: parquet-floor verzichtet auf
  `hadoop-common`/`hadoop-mapreduce`, zieht aber laut POM weiterhin
  `org.apache.parquet:parquet-hadoop` als Dependency. Es ist also
  „kein hadoop-common, aber weiterhin parquet-hadoop-Pfad", nicht
  hadoop-frei im strengen Sinn.
- API ist dateibasiert: `ParquetWriter.writeFile(schema, file, ...)`. Es ist
  kein direkter `OutputStream`-Pfad dokumentiert; nicht-seekbare Sinks sind
  ausgeschlossen.
- Typvokabular im README explizit beschraenkt (INT32/INT64, FLOAT, DOUBLE,
  BOOLEAN, BINARY mit String-/JSON-Logical-Type, MAP). Decimal mit
  Precision/Scale, Temporal-Varianten und UUID sind nicht dokumentiert.
- Footer-Key-Value-Metadaten und Row-Group-Tuning nicht in der README
  zugesichert; muesste Code-seitig verifiziert werden.
- Die Ablehnung fuer den ersten Schnitt stuetzt sich auf API-Datei-Bindung
  (K3a/K3b) und beschraenktes Typvokabular (K7), nicht auf den frueheren
  Footprint-/Wartungseindruck.

### 3.4 Apache Arrow Java und `parquet-arrow`

Hier sind zwei unterschiedliche Artefakte sauber zu trennen, damit AP3
nicht auf falsche Gradle-Koordinaten zielt:

- **`org.apache.arrow:arrow-dataset`** (aktuell 19.0.0 auf Maven Central):
  Arrow-Dataset-Modul mit Parquet-IO ueber JNI. Apache 2.0; sehr aktiv.
  Allokationen liegen off-heap, `NativeMemoryPool` haengt am
  JNI-Bridge `libarrow_dataset_jni`; das POM bindet die
  `arrow_dataset_jni`-Nativressourcen ein. Streaming-Lesen ueber
  `ArrowReader` und `Scanner`-Batches; Schreiben dateibasiert ueber
  Datasets. Pro Ziel-OS/Architektur wird ein nativer Bibliotheksanteil
  gebraucht. Das bricht den heutigen Distributionsweg
  (plattform-agnostisches Fat-JAR) und macht GraalVM-Native-Image
  deutlich komplexer.
- **`org.apache.parquet:parquet-arrow`** (gekoppelt an die parquet-java-
  Versionslinie, also aktuell 1.17.1): reines JVM-Modul innerhalb
  parquet-java. Bindet `arrow-vector` ein, nicht das Dataset-/JNI-Modul.
  Kann als Bruecke zwischen `parquet-java`-Writer/Reader und
  Arrow-Vector-Buffern dienen, ohne JNI-Footprint. Fuer einen
  reinen Arrow-IPC-Adapter ist `parquet-arrow` allein nicht ausreichend,
  fuer eine Vector-basierte Pufferschicht aber relevant.

Bewertung fuer d-migrate:

- `arrow-dataset` ist fuer den ersten Format-Adapter-Schnitt zu schwer und
  widerspricht dem Distributions- und Native-Image-Ziel. Bleibt Kandidat
  fuer einen spaeteren Arrow-/Lakehouse-Adapter (Hauptplan Abschnitt 4).
- `parquet-arrow` ist kein eigener Bibliotheks-Kandidat im Sinne dieser
  Sichtung; es ist ein optionales Hilfsmodul oberhalb von `parquet-java`
  und wird in AP3 nur dann eingezogen, wenn die `DataChunk`-Akkumulation
  ueber Arrow-Vectors einen messbaren Vorteil bringt.

### 3.5 DuckDB JDBC (`COPY ... FORMAT PARQUET`)

- MIT-Lizenz; JDBC-Modul wird aktiv gepflegt; bringt eine plattform-
  spezifische native Library mit (Linux x86_64/arm64, macOS, Windows).
- Schreibt Parquet ueber `COPY <stmt> TO 'file.parquet' (FORMAT PARQUET)`
  und liest ueber `SELECT * FROM read_parquet('file.parquet')`.
- Konsequenz: ein Parquet-Adapter ueber DuckDB ist faktisch ein eigener
  Embedded-Engine-Adapter. Er widerspricht dem Schnittprinzip aus Hauptplan
  Abschnitt 4 (Format-Adapter unterhalb der Ports), weil DuckDB die
  Schemaableitung, Typkonversion und Output-Wahl uebernimmt und d-migrate
  diese Informationen erst zurueckholen muesste.
- Vorteil ist die Kompatibilitaetstestbarkeit; `read_parquet` ist ohnehin
  Akzeptanzkriterium. DuckDB ist damit Validierungs-/Probierwerkzeug fuer
  Akzeptanz, nicht produktiver Writer/Reader.

### 3.6 `iceberg-parquet` (2026-06-05 ergaenzt)

- `org.apache.iceberg:iceberg-parquet` (aktive Release-Linie, Apache 2.0).
- Funktional ein **Adapter zwischen Apache-Iceberg-Tabellen und
  Parquet-Dateien**, nicht ein eigener Parquet-Writer/Reader: nutzt
  intern `parquet-java` fuer die eigentliche Datei-IO, kapselt aber
  Iceberg's Schema- und Tabellen-Modell (`org.apache.iceberg.Schema`,
  `Types.NestedField`, Manifeste, Snapshots, Schema-Evolution).
- Eigener Reader-/Writer-Builder (`Parquet.read()`, `Parquet.write()`),
  modernes Streaming-Modell mit eigenen Iceberg-Konvertern und einer
  Iceberg-`InputFile`-/`OutputFile`-Abstraktion. Bringt damit auf
  Reader-Seite **keinen MapReduce-Klassenbedarf** wie der direkte
  `ParquetReader.builder`-Pfad in 1.17.1 (siehe §5.1-Befund), zieht
  dafuer transitiv `iceberg-core`/`iceberg-data`.
- Bewertung fuer d-migrate:
  - Iceberg-/Delta-/Hudi-Tabellenverwaltung ist im Hauptplan
    `parquet-export-import-evaluation.md` §3.2 **explizit Nicht-Scope**.
    iceberg-parquet ist der Einstieg in genau diese Welt — die
    Iceberg-`Schema`/`Table`-Abstraktion ist nicht abschaltbar,
    sondern der Kernzweck der Bibliothek.
  - Hauptplan §4 verschiebt einen Lakehouse-Adapter explizit in einen
    Folge-Schritt oberhalb des Format-Adapters; iceberg-parquet gehoert
    strukturell genau dorthin.
  - Wuerde ein drittes Typsystem zwischen `ChunkSchema`/`NeutralType`
    und Parquet `MessageType` schieben (`org.apache.iceberg.Schema`),
    ohne dass dessen Mehrwert (Tabellen-Versionierung, Snapshots,
    Schema-Evolution) im aktuellen Schnitt benoetigt wird. Mehraufwand
    ohne Gegenwert.
  - GraalVM-/Distributionsfragen werden nicht besser: parquet-java
    bleibt drunter, dazu kommt der Iceberg-Stack.
- Konsequenz: **kein Kandidat fuer den ersten Parquet-Schnitt**, aber
  natuerlicher Hauptkandidat fuer einen spaeteren Lakehouse-Folgeplan
  (Iceberg-Tabellen schreiben, nicht nur Parquet-Dateien). Nicht in
  die Bewertungsmatrix Abschnitt 4 aufgenommen, weil der Ausschluss
  strukturell (Hauptplan §3.2/§4) und nicht kriteriumsgetrieben
  (K1-K10) ist.

---

## 4. Bewertungsmatrix

| Kriterium | parquet-java 1.17.1 | Carpet 0.7.1 | parquet-floor | arrow-dataset 19.0.0 | DuckDB JDBC |
| --------- | ------------------- | ------------ | ------------- | -------------------- | ----------- |
| K1 Lizenz | Apache 2.0 | Apache 2.0 | Apache 2.0 | Apache 2.0 | MIT |
| K2 Wartung | aktiv, CVE-Patches | aktiv | gepflegt, niedrige Release-Kadenz | sehr aktiv | sehr aktiv |
| K3a Writer-Streaming | `PositionOutputStream` ueber eigene `OutputFile`; nicht-seekbar moeglich (stdout-tauglich) | `OutputStream` direkt im Record-Pfad | nur `File` | dateibasiert + JNI | nur JDBC-`COPY` |
| K3b Reader-Streaming | `SeekableInputStream` ueber eigene `InputFile`; reine `InputStream`-Quellen brauchen Temp-Spool | Reader dateibasiert | nur `File` | dateibasiert + JNI | nur JDBC-`COPY` |
| K4 Hadoop-Abhaengigkeit | `parquet-hadoop`-Artefakt bleibt; Hadoop-Runtime in 1.17.1 via `LocalFileSystem` ueber `Configuration` (§5.1; `LocalOutputFile`+`PlainParquetConfiguration` kaeme erst mit 1.18+) | zieht `parquet-hadoop` und `hadoop-common` als Compile-Deps | kein `hadoop-common`, aber `parquet-hadoop` bleibt laut POM Dependency | nicht relevant (eigene Stack) | nicht relevant |
| K5 GraalVM | machbar mit Reachability-Metadaten; JNI-Compression-Codecs (snappy-java, zstd-jni) muessen ausgeschlossen oder bewusst konfiguriert werden | Reflection-/Record-getrieben, zusaetzliches Risiko; gleicher JNI-Codec-Pfad wie parquet-java | machbar, aber unverifiziert; gleicher JNI-Codec-Pfad | JNI, deutlich aufwendiger | JNI, Quarkus-Beispiele existieren |
| K6 Dynamische Schemaerzeugung | nativ ueber `MessageType` | Record-zentriert, fuer d-migrate ungeeignet | typbeschraenkt | moeglich, aber schwergewichtig | implizit, ausserhalb d-migrate-Kontrolle |
| K7 Typabdeckung | vollstaendig kontrollierbar | dokumentiert, Decimal/Temporal konfigurierbar | beschraenkt laut README | umfassend | DuckDB-typsystem-gebunden |
| K8 Footer-Key-Value-Metadaten | direkt ueber Builder/Reader | nicht prominent | nicht dokumentiert | indirekt | nicht direkt, nur via COPY-Optionen |
| K9 Row-Group-Steuerung | `withRowGroupSize`, `withPageSize` | unterhalb Carpet identisch zu parquet-java | unklar | Scanner-Batches | abhaengig von DuckDB |
| K10 Binaerfussabdruck | mittel, JVM-Klassen plus snappy-java/zstd-jni-Native-JARs (excludable bei GZIP/Uncompressed-Default) | mittel, plus identische native Codec-JARs | mittel, plus parquet-hadoop-Pfad und Codec-JARs | gross, plattform-spezifisches JNI | gross, plattform-spezifisches JNI |

---

## 5. Vorentscheidung

> **Verbindlicher Implementierungs-Pfad seit §5.1 (AP3-
> Spike-Befund, 2026-06-05):** der unten beschriebene
> Wortlaut „eigene `OutputFile`/`InputFile`-Implementierungen
> ueber `PlainParquetConfiguration`" beschreibt den
> parquet-java-1.18+-Pfad. In der gewaehlten Version 1.17.1
> sind diese Overloads nicht ueber die oeffentliche Builder-
> API erreichbar; AP3 hat die Vorentscheidung deshalb auf
> die **Hadoop-API ueber `LocalFileSystem`** praezisiert (§5.1).
> Die Bibliotheks-Wahl selbst (parquet-java 1.17.1 ohne
> Hadoop-Cluster) bleibt unveraendert. Bewertung und Wortlaut
> unten dokumentieren den ursprunglichen Entwurfsstand —
> §5.1 ist die heutige Form.

Bibliothek der ersten Wahl fuer den Prototyp ist **`parquet-java` 1.17.1**,
eingesetzt ohne Hadoop-Installation (kein HDFS, kein Cluster-Connector)
ueber eigene `OutputFile`/`InputFile`-Implementierungen und
`PlainParquetConfiguration`. Hadoop-API-Klassen aus `hadoop-common` bleiben
als Compile-Dependency vorhanden, weil das Artefakt `parquet-hadoop`
direkt darauf verweist; sie werden in Abschnitt 8 gepinnt und in AP3
gegen GraalVM-Reachability geschnitten. Grund fuer die Wahl:

- Es ist die einzige Option, die K3a (Writer-Streaming ueber `PositionOutputStream`,
  inklusive stdout), K3b (Reader-Streaming ueber `SeekableInputStream` mit
  bewusster Temp-Spool-Regel fuer reine `InputStream`-Quellen), K6 (dynamische
  Schemaerzeugung ohne Records) und K8 (Footer-Key-Value-Metadaten) ohne
  Verbiegung zugleich erfuellt.
- Carpet adressiert K3a am elegantesten, scheitert aber strukturell an K6:
  die Record-getriebene API passt nicht zu Laufzeit-Schemas. Der Fallback
  auf das parquet-java-Layer unter Carpet bringt keinen Mehrwert gegenueber
  direkter parquet-java-Nutzung; und weil Carpet 0.7.1 ohnehin
  `parquet-hadoop` und `hadoop-common` mitbringt (K4), verschwindet auch
  der vermutete GraalVM-/Footprint-Vorteil.
- parquet-floor scheidet wegen API-Datei-Bindung (K3a/K3b) und
  beschraenktem Typvokabular (K7) fuer den ersten Schnitt aus. Der
  Hadoop-Footprint ist niedriger als bei `parquet-java`, aber nicht null
  (`parquet-hadoop` bleibt Compile-Dependency), und die `OutputStream`-
  Frage bleibt strukturell offen.
- `arrow-dataset` (Arrow Java mit JNI) ist fuer den ersten Format-Adapter
  zu schwer und widerspricht dem Distributions- und Native-Image-Ziel.
  Bleibt Kandidat fuer einen spaeteren Arrow-/Lakehouse-Adapter
  (Hauptplan Abschnitt 4). `parquet-arrow` aus parquet-java ist davon
  klar abgegrenzt und steht als optionales Vector-Hilfsmodul oberhalb
  von `parquet-java` zur Verfuegung.
- DuckDB JDBC ist Akzeptanztest- und Inspektionswerkzeug, nicht
  produktiver Writer/Reader.

Diese Entscheidung ist nicht final. Sie wird in AP3 (Prototyp) bestaetigt
oder zurueckgenommen, sobald die folgenden offenen Punkte beantwortet sind.

### 5.1 Hadoop-API-Kanal in 1.17.1 (AP3-Spike-Praezisierung, 2026-06-05)

Der AP3-Spike (`adapters/driven/formats-parquet/`,
`ParquetSpike.kt`) hat die Vorentscheidung lauffaehig bestaetigt, aber
zwei Praezisierungen gegenueber dem urspruenglichen Wortlaut noetig
gemacht:

- `ParquetWriter.Builder.withConf` und `ParquetReader.builder`
  akzeptieren in 1.17.1 nur Hadoop-`Configuration` bzw. Hadoop-`Path`.
  Die in PARQUET-1822 / PR 1111 erwaehnten
  `PlainParquetConfiguration`-/`LocalOutputFile`-/`LocalInputFile`-
  Overloads sind in `master` vorhanden, kommen aber erst mit
  parquet-java 1.18+. In 1.17.1 sind sie nicht ueber die oeffentliche
  Builder-API erreichbar.
- Der Spike verwendet deshalb Hadoop-`Path` plus `Configuration(false)`
  (ohne Default-Resources wie `core-site.xml`) und ueberlaesst
  Hadoop-`LocalFileSystem` die Aufloesung von `file://`-URIs. Das ist
  rein NIO unter der Haube; keine HDFS-/Cluster-Pfade werden geladen.
- Die Vorentscheidung „kein Hadoop-Cluster, kein HDFS" bleibt damit
  gueltig. Praeziser formuliert: der Adapter laeuft auf der **Hadoop-API
  ueber LocalFileSystem**, nicht auf `PlainParquetConfiguration`. Bis
  ein CVE-freies 1.18.x verfuegbar ist, behaelt AP4+ diesen Pfad und
  dokumentiert ihn als bewusste 1.17.1-Variante; ein Upgrade auf 1.18.x
  ist Folgeentscheidung, kein Vertragsbruch.

---

## 6. Pragmatische Reihenfolge

AP2 (Schemaquelle, `ChunkSchema`, Mapping-Tabelle, Nullability-Resolver)
ist als eigener Sub-Doc `parquet-schema-source.md` ausgearbeitet und
liefert dem Prototyp einen stabilen Schema-Vertrag. Damit AP3 auch
bibliotheksseitig nicht gegen ein Provisorium laeuft, werden die
verbleibenden Punkte aus dieser Sichtung in Vorbedingungen und
Verifikationen getrennt:

1. **AP1.a — Gradle-Dependency-Skizze festschreiben.** Abschnitt 8 ist
   der bindende Ausgangspunkt; AP3 verifiziert ihn via
   `gradle dependencies` und schliesst Luecken (z.B. tatsaechlich
   benoetigte `hadoop-common`-Klassen).
2. **AP1.b — Dependency-Constraints gegen `parquet-avro` und
   `parquet-protobuf` im Build festziehen.** Versionsseitig schliesst
   1.17.1 CVE-2025-30065/46762; relevant ist, dass die Module gar nicht
   eingezogen werden (kein Avro-/Protobuf-Reflection-Pfad im
   Klassenpfad).

   **S10a-Befund-Rueckspiel (2026-06-06, Pfad A).**
   `dependencyInsight --dependency org.apache.avro:avro
   --configuration runtimeClasspath` gegen
   `:adapters:driven:formats-parquet` zeigte vor S10a
   `org.apache.avro:avro:1.9.2` transitiv ueber
   `hadoop-common` **und**
   `hadoop-mapreduce-client-core`. Die `parquet-avro`-
   Constraint allein reichte also nicht — Avro kam ueber
   Hadoop trotzdem in den Klassenpfad. S10a hat deshalb
   den Constraint-Block erweitert:
   - `org.apache.avro:avro` mit `rejectAll()` (zweigleisige
     Absicherung gegen kuenftige neue Pfade),
   - `exclude(group = "org.apache.avro")` auf beiden
     Hadoop-Deps (`hadoop-common`,
     `hadoop-mapreduce-client-core`).

   AP3-Spike-Tests bleiben nach dem Exclude gruen (Beleg
   im S10a-Closure-Doc), d.h. kein konsumierter
   Hadoop-Code-Pfad referenziert Avro-Klassen.
   `dependencyInsight` nach S10a liefert "No dependencies
   matching given input were found"; die in der
   urspruenglichen AP1.b-Aussage formulierte Garantie
   ("kein Avro-Reflection-Pfad im Klassenpfad") ist damit
   tatsaechlich erfuellt.
3. **AP1.c — InputStream-Reader-Vertrag entscheiden und in CLI-Spec
   spiegeln.** Abschnitt 7 dokumentiert die Vorentscheidung; AP3
   implementiert sie, entdeckt sie nicht.
4. **AP3 — Bibliotheksspezifischer Prototyp.** Implementiert
   `ParquetChunkWriter`/-`Reader` gegen den stabilen Build-Stand und den
   stabilen Port-Vertrag, verifiziert die Annahmen aus Abschnitten 7 und
   8, und prueft die verbleibenden Verifikationsfragen in Abschnitt 9.

---

## 7. InputStream-Reader-Vertrag (AP1-Artefakt)

Hauptplan Abschnitt 6 nennt drei moegliche Reader-Vertraege fuer
nicht-seekbare Quellen: Ablehnen, seekbarer Adapter oder Temp-Spool. Die
Vorentscheidung lautet:

- **Parquet-Reader ist datei-/pfadbasiert; reine `InputStream`-Quellen
  werden abgelehnt.** Akzeptiert sind nur seekbare Quellen, also Pfade
  auf das lokale Dateisystem und kuenftige seekbare Adapter (z.B.
  Object-Storage mit `SeekableInputStream`-Implementierung).
- **Kein impliziter Temp-Spool.** Parquet-Dateien koennen mehrere
  Gigabyte gross sein. Ein automatischer Disk- oder Heap-Spool wuerde
  den Speicher-/IO-Vertrag des restlichen Imports brechen und ist
  unsichtbar — wenn ein Aufrufer wirklich `InputStream` hat, soll er
  vor dem Import explizit auf Disk stagen und den Pfad uebergeben.
- **CLI-Spiegelung.** `data import --format parquet` akzeptiert nur
  `--source <pfad>` (Datei oder Directory-Bundle). Stdin ist explizit
  abgelehnt, mit klarer Fehlermeldung im Preflight (vgl. Hauptplan
  Abschnitt 6, formatspezifische CLI-Regel). Format-Autodetection bleibt
  wie im Hauptplan: Single-File ueber `.parquet`, Directory ueber
  `manifest.yaml`.
- **Writer-Seite bleibt unveraendert.** Stdout funktioniert via
  `PositionOutputStream` (zaehlender Stream, kein Seek noetig); das ist
  Teil der AP3-Verifikation.
- **`.crc`-Sidecar (AP3-Spike-Befund, 2026-06-05).** Hadoop-
  `LocalFileSystem` schreibt neben `<datei>.parquet` automatisch eine
  versteckte `.<datei>.parquet.crc`-Checksum-Datei und prueft sie beim
  Lesen mit. Der Spike toleriert das im Tempverzeichnis; ein produktiver
  `ParquetChunkWriter` darf den Sidecar nicht stehen lassen. Verfuegbare
  Optionen: (a) `RawLocalFileSystem` statt `LocalFileSystem` registrieren
  (kein `.crc` geschrieben, aber auch kein Schutz vor stillen Datei-
  Korruptionen), oder (b) den `.crc`-Sidecar nach `close()` aktiv
  aufraeumen und beim Lesen ueber `RawLocalFileSystem` zugreifen. AP4+
  entscheidet zwischen (a) und (b); bis dahin ist die Stdout-Variante
  davon nicht betroffen, weil sie ueber den eigenen `PositionOutputStream`
  laeuft, nicht ueber `LocalFileSystem`.
- **AP6-Spike-Praezisierung zu Variante (a) (2026-06-05).** In
  Hadoop 3.4.1 reicht `conf.set("fs.file.impl",
  "org.apache.hadoop.fs.RawLocalFileSystem")` **nicht** aus: der
  `FileSystem`-Service-Loader-Cache haelt eine vorinstanziierte
  `LocalFileSystem` fuer das `file://`-Schema vor und bedient den
  `ExampleParquetWriter`-Builder daraus, bevor die
  `fs.file.impl`-Direktive greift. Die Mitigation muss `conf.set("fs.file.impl.disable.cache",
  "true")` zusaetzlich setzen, dann waehlt Hadoop die in
  `Configuration` deklarierte `RawLocalFileSystem`-Klasse und schreibt
  keinen `.crc`-Sidecar. Verifiziert im Spike-Test
  `ParquetSpikeImportPathTest`-`writeWithoutCrc unterdrueckt den
  .crc-Sidecar`.

Diese Entscheidung ist die strenge Variante und priorisiert
Vorhersagbarkeit ueber Bequemlichkeit. Falls ein konkreter Use-Case
spaeter einen seekbaren Adapter rechtfertigt (Object-Storage), kommt er
als zusaetzliche Adapter-Implementierung, nicht als Aufweichung des
CLI-Vertrags.

### 7.1 Port- und Resolver-Vertrag (interne Konsequenz)

Hauptplan Abschnitt 6 fordert: wenn der Reader nicht ueber den
bestehenden `InputStream`-Port abbildbar ist, muss ein konkreter
dateibasierter Port- bzw. Resolver-Vertrag vorliegen. Aus der
CLI-Entscheidung oben folgt fuer den Importpfad:

- **Bestehender `DataChunkReaderFactory.create(InputStream, ...)` bleibt
  unveraendert** und wird weiter von JSON/YAML/CSV genutzt.
- **Neuer komplementaerer Port `SeekableDataChunkReaderFactory` in
  `hexagon:ports-read`**, der eine seekbare Quelle annimmt:

  ```text
  package dev.dmigrate.ports.read

  interface SeekableDataChunkReaderFactory {
      fun create(
          format: DataExportFormat,
          source: SeekableChunkSource,
          schema: ChunkSchema,
          options: FormatReadOptions
      ): DataChunkReader
  }

  sealed interface SeekableChunkSource {
      data class Local(val path: java.nio.file.Path) : SeekableChunkSource
      // Spaetere Adapter (Object-Storage, gemounteter Cache) implementieren
      // diese Sealed-Hierarchie. Reine InputStream-Quellen werden bewusst
      // nicht gewrappt.
  }
  ```

- **Import-Resolver entscheidet pro Format**: JSON/YAML/CSV gehen ueber
  `DataChunkReaderFactory` (Stream), Parquet ueber
  `SeekableDataChunkReaderFactory` (Pfad). Es gibt keinen Fallback aus
  einem Pfad zu einem `InputStream` und keinen Fallback aus einem
  `InputStream` zu einem Pfad. Der Resolver lehnt im Preflight ab, wenn
  die Format-zu-Quelle-Kombination nicht passt.
- **`ImportInput.Directory`** bleibt fuer JSON/YAML/CSV-Directory-
  Imports und fuer kuenftige Single-File-Bundles (AP11) der DTO-Vertrag.
  Multi-Table-/Directory-Bundles mit verpflichtendem `manifest.yaml`
  laufen ueber den dedizierten Subtyp `ImportInput.ResolvedBundle`
  (`parquet-import-input-dto.md` §4.1). Die urspruengliche AP1-Aussage
  „Directory wird nicht ersetzt" ist damit prazisiert, nicht verworfen:
  `Directory` traegt weiterhin Verzeichnis + optionale Filter/Order,
  aber **nicht** die Manifest-getriebenen Tabellenbindings —
  diese leben im neuen Subtyp.
- **Symmetrie auf der Writer-Seite ist nicht noetig**: `DataChunkWriter`
  bleibt stream-basiert; der Parquet-Writer wraps den bestehenden
  `OutputStream` in einen eigenen `PositionOutputStream`/`OutputFile`-
  Adapter (zaehlend, nicht-seekbar, stdout-tauglich).

AP3 implementiert diesen Vertrag, entdeckt ihn nicht. Konkret: AP1.c
liefert die Port-Skizze, AP2.d adaptiert die `DataChunkWriter`-Seite (in
`parquet-schema-source.md` Abschnitt 6.2 bereits beschlossen),
und AP3 realisiert beide Reader-Pfade samt Resolver-Spiegelung.

---

## 8. Gradle-Dependency-Skizze (AP1-Artefakt)

Bindende Ausgangs-Koordinaten fuer den Prototyp. AP3 verifiziert via
`gradle dependencies`, ob die Liste vollstaendig ist und welche
Hadoop-API-Klassen tatsaechlich gebraucht werden.

```kotlin
// adapters:driven:formats-parquet  (neues Modul, vgl. Abschnitt 5 Diskussion)
dependencies {
    implementation("org.apache.parquet:parquet-hadoop:1.17.1")
    implementation("org.apache.parquet:parquet-column:1.17.1")

    // hadoop-common ist Compile-Zeit-Bedarf von parquet-hadoop
    // (org.apache.hadoop.fs.Path, Configuration etc.). Pinnung auf eine
    // konkrete, stabile Hadoop-3.4-Patchversion; AP3 prueft, ob der
    // Subset reicht oder ob ein eigener Hadoop-API-Shim die Dependency
    // komplett ersetzen kann.
    implementation("org.apache.hadoop:hadoop-common:3.4.1") {
        // typische Schwergewichte rausziehen, sofern nicht referenziert
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
    }

    // AP3-Spike-Befund (2026-06-05): ParquetReader.builder(
    // GroupReadSupport, Path) triggert das Laden von
    // org.apache.parquet.hadoop.ParquetInputFormat extends
    // org.apache.hadoop.mapreduce.lib.input.FileInputFormat. Ohne
    // hadoop-mapreduce-client-core bricht der Reader-Klassenladevorgang
    // mit NoClassDefFoundError. Gleiche Hadoop-Patchversion wie
    // hadoop-common, gleiche Exclusions.
    implementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1") {
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
    }
}

dependencies {
    constraints {
        // CVE- und Reflection-Vermeidung: parquet-avro/parquet-protobuf
        // sollen NIE im Klassenpfad landen.
        implementation("org.apache.parquet:parquet-avro") {
            version { rejectAll() }
            because("parquet-avro wird in d-migrate nicht benoetigt; Avro-" +
                "Reflection-Pfade und CVE-2025-30065-Klasse aus dem " +
                "Klassenpfad heraushalten.")
        }
        implementation("org.apache.parquet:parquet-protobuf") {
            version { rejectAll() }
            because("parquet-protobuf wird in d-migrate nicht benoetigt; " +
                "Protobuf-Reflection und zusaetzliche Native-Image-Last " +
                "vermeiden.")
        }
    }
}

// Native-Compression-Codecs werden bewusst ausgeschlossen, weil sie JNI-
// Bibliotheken aus dem JAR extrahieren und laden. Default-Compression fuer
// d-migrate-Parquet ist GZIP (java.util.zip, rein JVM) bzw. UNCOMPRESSED.
// SNAPPY/ZSTD koennen spaeter bewusst zugelassen werden, dann mit
// passender GraalVM-Reachability-Konfiguration.
configurations.all {
    exclude(group = "org.xerial.snappy", module = "snappy-java")
    exclude(group = "com.github.luben",  module = "zstd-jni")
}
```

Begleitende Code-Vorgabe: Der `ParquetChunkWriter` setzt den Codec
explizit auf `CompressionCodecName.GZIP` (oder `UNCOMPRESSED` per
Konfiguration), nie auf den Parquet-Default `SNAPPY`. Damit kollidieren
die obigen Exclusions nicht mit dem Default-Codec.

Erwartete Folge-Aufgaben fuer AP4+ (AP3-Spike abgeschlossen):

- `gradle :adapters:driven:formats-parquet:dependencies` als Lock-Datei
  oder Checked-In-Snapshot, damit Drift sichtbar wird.
- Pruefen, ob ein eigener Hadoop-API-Shim (eigene minimale
  `org.apache.hadoop.fs`-Klassen) den `hadoop-common`-Block ersetzen
  kann, oder ob das aufwaendiger ist als der Subset-Pull. Diese Pruefung
  schliesst auch `hadoop-mapreduce-client-core` ein: AP4+ identifiziert
  entweder einen Reader-Pfad ohne MapReduce-Klassenbedarf oder pinnt
  den Block dauerhaft (z.B. via 1.18.x-Wechsel und reiner
  `LocalInputFile`-Nutzung).
- GraalVM-Reachability-Metadaten genau auf die tatsaechlich genutzten
  Hadoop-Klassen schneiden, nicht auf das gesamte hadoop-common-Modul.
  Inklusive der MapReduce-FileInputFormat-Hierarchie, sofern der
  Reader-Pfad sie weiterhin laedt.
- Smoke-Roundtrip mit `CompressionCodecName.GZIP` plus DuckDB
  `read_parquet` zeigt, dass die Codec-Wahl mit Standard-Lesetools
  kompatibel ist.

---

## 9. Offene Punkte fuer AP3 (Verifikation)

> **Stand 2026-06-05:** der AP3-Code-Spike (`ParquetSpike.kt`,
> Commit `3b051ec`) verifiziert die Bibliotheks-Wahl und den
> GZIP-Roundtrip — die unten gelisteten Punkte sind im
> Spike **bewusst NICHT** gepruft (siehe Code-Kommentar dort:
> „Nicht-Scope: Schema-Discovery, NeutralType-Mapping,
> ChunkSchema-Vertrag, Streaming-Pages, Decimal-/Temporal-
> Typen, Footer-Metadaten"). Sie wandern damit in den
> Cut-B-Implementierungs-Pfad: AP12 §11.4 (DuckDB-/Arrow-KV-
> Toleranz-Smoke), §11.1 (CLI-Preflight-Tests fuer
> Footer-/Row-Group-/stdout-Wege) und §10
> (GraalVM-Reachability beim `ParquetChunkReader`/`Writer`-
> PR). Die hier gelisteten Punkte sind also nicht „AP3 ist
> nicht fertig", sondern „Verifikation kommt mit dem
> produktiven Reader/Writer in der Cut-B-Implementierung".

Nach den Vorbedingungen in Abschnitten 6, 7 und 8 verbleiben fuer AP3
nur noch prototyp-getriebene Verifikationen:

- Eigene `OutputFile`-Implementierung mit `PositionOutputStream` baut
  Parquet-Dateien sauber inklusive Footer-Finalisierung, auch fuer
  stdout-Schreiben (kein Vollpuffer).
- `withExtraMetaData` zusammen mit
  `ParquetFileReader#getFileMetaData().getKeyValueMetaData()` traegt
  einen Roundtrip fuer Single-File-Metadaten. Falls nein: Sidecar-Vertrag
  aus Hauptplan Abschnitt 6 greift.
- GraalVM-Reachability-Metadaten fuer den tatsaechlich verwendeten
  `parquet-java`-/Hadoop-Subset minimieren; Build gegen
  `nativeCompile` plus Smoketest erfolgreich.
- DuckDB `read_parquet` als Akzeptanzwerkzeug einbinden (Hauptplan
  Abschnitt 7), ohne DuckDB als Produktivpfad zu setzen.
- Row-Group-Akkumulation aus `DataChunk`-Stroemen so umsetzen, dass die
  konfigurierbare Obergrenze aus Hauptplan Abschnitt 9 eingehalten wird;
  Mindestbatchgroesse vor Flush parametrisierbar.

---

## 10. Risiken dieser Vorentscheidung

- `parquet-java` ohne Hadoop-Installation ist gut moeglich, aber die
  `PlainParquetConfiguration`-/`LocalOutputFile`-Pfade sind juenger als die
  Hadoop-Pfade; der Prototyp muss das auf den Zielplattformen verifizieren.
  `parquet-hadoop` plus eine gepinnte `hadoop-common`-Version bleiben
  Compile-Dependencies. Der Runtime-Footprint ist damit nicht der eines
  „pure column module"; ein eigener Hadoop-API-Shim koennte das spaeter
  reduzieren (Folge-Aufgabe AP3).
- Native-Compression-Codecs (`snappy-java`, `zstd-jni`) bringen JNI-
  Bibliotheken pro Plattform mit. d-migrate setzt die Default-
  Compression deshalb auf `GZIP` (rein JVM) und excluded beide Codec-
  Artefakte (Abschnitt 8). Wer spaeter `SNAPPY` oder `ZSTD` braucht,
  muss die Codec-Module bewusst zulassen und die GraalVM-Reachability-
  Konfiguration nachziehen — das ist explizit, nicht versehentlich.
- parquet-avro ist auch bei Versionspflege weiterhin Angriffs- und
  Reflection-Flaeche. Bei strikter Pinnung auf 1.17.1 sind CVE-2025-30065
  und CVE-2025-46762 zwar geschlossen, aber die Empfehlung lautet,
  parquet-avro/parquet-protobuf gar nicht erst einzuziehen und das per
  Dependency-Constraints in Gradle abzusichern. So bleibt der Avro-Pfad
  ausserhalb der GraalVM-Reachability.
- GraalVM-Native-Image bleibt das groesste Restrisiko; eine spaete
  Migration auf einen anderen Writer waere ein Format-Adapter-Tausch,
  nicht ein Vertragsbruch fuer die Ports.

---

## 11. Footprint-Inventar (S10a-Befund 2026-06-06)

S10a hat `dependencies --configuration runtimeClasspath`
gegen `:adapters:driven:formats-parquet` aufgenommen, um
das tatsaechliche Footprint-Bild als Input fuer den
1.0.0-Cut (Distributions-Cut + Hadoop-Footprint-
Minimierung, AP13 §8.3) festzuhalten. Dieses Inventar
selbst macht **keine** Excludes — Minimierung ist
ausschliesslich 1.0.0-Aufgabe; in 0.9.8 bleibt der
Default-JAR Parquet-tauglich (AP13 §6.2 / §8.4).

### 11.1 Reproduzierbare Methode

Snapshot per `gradle :adapters:driven:formats-parquet:dependencies
--configuration runtimeClasspath`. Indirekt via Docker
(direkter `./gradlew`-Aufruf gesperrt):

```bash
docker build --no-cache --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats-parquet:dependencies --configuration runtimeClasspath" \
  -t d-migrate:s10a-deps-snapshot .
```

Zaehlmethode fuer die hier genannten Zahlen: aus dem
Gradle-Dependency-Tree alle Zeilen mit Tree-Prefix
`+--- ` oder `\\--- ` ziehen, Versionssuffix abschneiden,
auf `group:artifact` deduplizieren. Konkrete Pipeline:
`grep -oE '[+\\\\]--- [^ ]+:[^ ]+:'` plus `sort -u`. Die
vollstaendige Liste (`group:artifact` ohne Version) ist in
[`ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md`](ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md)
§8 abgelegt.

### 11.2 Gesamtzahl

- **Resolved Runtime-Classpath**: **129 externe
  `group:artifact`-Koordinaten** plus **4 interne
  Projekt-Module** (`:hexagon:core`, `:hexagon:ports-common`,
  `:hexagon:ports-read`, `:hexagon:ports-write`) =
  **133 resolved Knoten** im Gesamtbaum.
- Davon dem Hadoop-Footprint zugeordnet (verifizierte
  Gruppen-Zaehlung mit derselben Methode):
  - `org.apache.hadoop`: **8**
    (`hadoop-annotations`, `hadoop-auth`,
    `hadoop-common`, `hadoop-hdfs-client`,
    `hadoop-mapreduce-client-core`, `hadoop-yarn-api`,
    `hadoop-yarn-client`, `hadoop-yarn-common`).
  - Jersey-1-Stack
    (`com.sun.jersey*` + `com.github.pjfanning:jersey-json`
    + `org.codehaus.jettison`): **7**.
  - `io.netty`: **31** (Codec-Familien, Transports,
    Resolver, Macos-Natives — vollstaendige Liste im
    Closure-Doc §8).
  - Guice/Servlet/JS-RS
    (`com.google.inject*`, `javax.ws.rs`,
    `javax.servlet.jsp`): **4**.
  - Kerby/Auth
    (`org.apache.kerby:*`, `com.nimbusds`, `com.jcraft`,
    `dnsjava`): **10**.
  - Zookeeper/Curator
    (`org.apache.zookeeper:*`, `org.apache.curator:*`):
    **5**.
  - Logging-Legacy (`ch.qos.reload4j`): **1**.

Zusammen: **66 Footprint-Eintraege**, die der
1.0.0-Distributions-Cut adressieren muss.

### 11.3 Hadoop-Footprint-Schwergewichte (Pruefreihenfolge)

Auswahl der wichtigsten Bloecke; vollstaendige Listen in
[`ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md`](ImpPlan-0.9.8-parquet-S10a-dependency-hygiene.md)
§8.

- HDFS-/Server-State-Pfad: `org.apache.hadoop:hadoop-hdfs-client`,
  `org.apache.curator:curator-{client,framework,recipes}`,
  `org.apache.zookeeper:zookeeper`, `org.apache.zookeeper:zookeeper-jute`.
- YARN-Pfad (in Hadoop 3.4.1 transitiv ueber
  `hadoop-mapreduce-client-core` mitgezogen — verifizierte
  Module): `org.apache.hadoop:hadoop-yarn-api`,
  `org.apache.hadoop:hadoop-yarn-client`,
  `org.apache.hadoop:hadoop-yarn-common`. Kein
  `hadoop-yarn-server-common` im aktuellen
  runtimeClasspath; in Hadoop 3.4.1 ist es **nicht**
  transitiv praesent.
- Jersey-1-Stack: `com.sun.jersey:jersey-{client,core,server,servlet}`,
  `com.sun.jersey.contribs:jersey-guice`,
  `com.github.pjfanning:jersey-json`,
  `org.codehaus.jettison:jettison`.
- Netty: `io.netty:netty-all` plus 30 weitere
  `io.netty:netty-*`-Module (Codec-Familien, Transports,
  Resolver, Macos-Natives; vollstaendige Liste im
  Closure-Doc §8).
- Auth/Security: `org.apache.kerby:kerb-{core,crypto,util}`,
  `org.apache.kerby:kerby-{asn1,config,pkix,util}`,
  `com.nimbusds:nimbus-jose-jwt`,
  `com.jcraft:jsch`, `dnsjava:dnsjava`.
- Container/Servlet: `com.google.inject:guice`,
  `com.google.inject.extensions:guice-servlet`.
- Logging-Legacy: `ch.qos.reload4j:reload4j`
  (`hadoop-common` zieht das mit, parallel zu unserer
  Logback-Linie).

### 11.4 Erwartete 1.0.0-Maßnahmen

- **Distributions-Cut entscheiden** (AP13 §8.3): bleibt
  Default-JAR mit Parquet, kommt eine `--parquet`-
  Variante oder beides? Inventar oben ist der Input.
- **Hadoop-Footprint-Minimierung** durch Excludes auf den
  oben gelisteten Gruppen, soweit der Parquet-Reader-/
  Writer-Pfad sie nicht ueber Code-Pfade verlangt
  (analog zu Avro/Pfad A hier).
- **Optionaler Hadoop-API-Shim** (AP13 §8.3): nur die
  von `parquet-hadoop` referenzierten Hadoop-Klassen
  reimplementieren, statt `hadoop-common` voll zu
  ziehen.

Diese drei Punkte gehoeren explizit nicht in den
0.9.8-Cut.
