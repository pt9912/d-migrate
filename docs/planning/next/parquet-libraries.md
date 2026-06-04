# AP1: Parquet-Bibliothekssichtung (JVM)

> Dokumenttyp: Evaluierungs-Sub-Doc zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-04)
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
- GraalVM: Reflection-/Service-Loader-Lasten bestehen
  (z.B. Codec-Registrierung). Machbar mit Reachability-Metadaten;
  parquet-avro waere ein Risiko und wird hier nicht benoetigt.

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

- Apache 2.0; aktuelle Release-Linie auf Maven Central
  (`blue.strategic.parquet:parquet-floor`). Die Release-Kadenz ist
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

---

## 4. Bewertungsmatrix

| Kriterium | parquet-java 1.17.1 | Carpet 0.7.1 | parquet-floor | arrow-dataset 19.0.0 | DuckDB JDBC |
| --------- | ------------------- | ------------ | ------------- | -------------------- | ----------- |
| K1 Lizenz | Apache 2.0 | Apache 2.0 | Apache 2.0 | Apache 2.0 | MIT |
| K2 Wartung | aktiv, CVE-Patches | aktiv | gepflegt, niedrige Release-Kadenz | sehr aktiv | sehr aktiv |
| K3a Writer-Streaming | `PositionOutputStream` ueber eigene `OutputFile`; nicht-seekbar moeglich (stdout-tauglich) | `OutputStream` direkt im Record-Pfad | nur `File` | dateibasiert + JNI | nur JDBC-`COPY` |
| K3b Reader-Streaming | `SeekableInputStream` ueber eigene `InputFile`; reine `InputStream`-Quellen brauchen Temp-Spool | Reader dateibasiert | nur `File` | dateibasiert + JNI | nur JDBC-`COPY` |
| K4 Hadoop-Abhaengigkeit | `parquet-hadoop`-Artefakt bleibt; Hadoop-Runtime entfaellt via `LocalOutputFile` + `PlainParquetConfiguration` | zieht `parquet-hadoop` und `hadoop-common` als Compile-Deps | kein `hadoop-common`, aber `parquet-hadoop` bleibt laut POM Dependency | nicht relevant (eigene Stack) | nicht relevant |
| K5 GraalVM | machbar mit Reachability-Metadaten | Reflection-/Record-getrieben, zusaetzliches Risiko | machbar, aber unverifiziert | JNI, deutlich aufwendiger | JNI, Quarkus-Beispiele existieren |
| K6 Dynamische Schemaerzeugung | nativ ueber `MessageType` | Record-zentriert, fuer d-migrate ungeeignet | typbeschraenkt | moeglich, aber schwergewichtig | implizit, ausserhalb d-migrate-Kontrolle |
| K7 Typabdeckung | vollstaendig kontrollierbar | dokumentiert, Decimal/Temporal konfigurierbar | beschraenkt laut README | umfassend | DuckDB-typsystem-gebunden |
| K8 Footer-Key-Value-Metadaten | direkt ueber Builder/Reader | nicht prominent | nicht dokumentiert | indirekt | nicht direkt, nur via COPY-Optionen |
| K9 Row-Group-Steuerung | `withRowGroupSize`, `withPageSize` | unterhalb Carpet identisch zu parquet-java | unklar | Scanner-Batches | abhaengig von DuckDB |
| K10 Binaerfussabdruck | mittel, reines JVM | mittel | mittel (parquet-hadoop bleibt, ohne hadoop-common) | gross, plattform-spezifisches JNI | gross, plattform-spezifisches JNI |

---

## 5. Vorentscheidung

Bibliothek der ersten Wahl fuer den Prototyp ist **`parquet-java` 1.17.1**,
eingesetzt ohne Hadoop-Runtime ueber eigene `OutputFile`/`InputFile`-
Implementierungen und `PlainParquetConfiguration`. Grund:

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

---

## 6. Offene Punkte fuer AP3 (bibliotheksbezogen)

AP2 (Schemaquelle, `ChunkSchema`, Mapping-Tabelle, Nullability-Resolver)
ist als eigener Sub-Doc `parquet-schema-source.md` ausgearbeitet und
liefert dem Prototyp einen stabilen Vertrag. Die folgende Liste deckt nur
noch bibliotheks-, Streaming- und Distributions-Fragen ab, die direkt aus
der Wahl von `parquet-java` 1.17.1 fallen.

- AP3: Verifizieren, dass eine eigene `OutputFile`-Implementierung mit
  `PositionOutputStream` fuer den d-migrate-`ExportOutput`-Pfad sauber zu
  bauen ist (Footer-Finalisierung, kein Vollpuffer); insbesondere fuer
  stdout-Schreiben.
- AP3: Fuer den Importpfad entscheiden, wie reine `InputStream`-Quellen
  behandelt werden: ablehnen (nur Datei/seekbare Quelle), seekbarer Adapter
  oder explizit dokumentierter Temp-Spool. Hauptplan Abschnitt 6 spricht
  bereits den Punkt an; AP3 muss den konkreten Vertrag fixieren.
- AP3: Den tatsaechlichen Gradle-Dependency-Satz fuer den Writer-/Reader-Pfad
  ermitteln (welche `parquet-*`-Artefakte, welche Hadoop-API-Klassen sind
  unvermeidlich) und gegen GraalVM-Reachability sowie Distributionsgewicht
  abgleichen. Annahme „nur parquet-column" ist vorab nicht haltbar.
- AP3: Pruefen, ob `withExtraMetaData` zusammen mit
  `ParquetFileReader#getFileMetaData().getKeyValueMetaData()` als
  Single-File-Metadatenvertrag taugt. Falls ja, entfaellt der Sidecar fuer
  Single-File-Exporte; falls nein, gilt der Sidecar-Vertrag aus Hauptplan
  Abschnitt 6.
- AP3: GraalVM-Reachability-Metadaten fuer `parquet-java` minimieren.
  Insbesondere parquet-avro und parquet-protobuf ausschliessen, um
  Avro-/Protobuf-Angriffsflaeche, unnoetige Reflection und Dependency-
  Drift zu vermeiden. Versionsseitig ist 1.17.1 gegen CVE-2025-30065 und
  CVE-2025-46762 bereits abgesichert; relevant sind Dependency-
  Constraints, die alte parquet-avro-Versionen ausschliessen.
- AP3: DuckDB `read_parquet` als Akzeptanzwerkzeug einbinden, ohne DuckDB
  als Produktivpfad zu setzen.
- AP3: Row-Group-Akkumulation aus `DataChunk`-Stroemen so umsetzen, dass die
  konfigurierbare Obergrenze aus Hauptplan Abschnitt 9 eingehalten wird.

---

## 7. Risiken dieser Vorentscheidung

- `parquet-java` ohne Hadoop-Runtime ist gut moeglich, aber die
  `PlainParquetConfiguration`-/`LocalOutputFile`-Pfade sind juenger als die
  Hadoop-Pfade; der Prototyp muss das auf den Zielplattformen verifizieren.
  Auch ohne Hadoop-Runtime bleibt das `parquet-hadoop`-Artefakt im Klassenpfad
  und bringt Hadoop-API-Klassen mit; der GraalVM- und Distributions-
  Footprint ist deshalb nicht das eines „pure column module".
- parquet-avro ist auch bei Versionspflege weiterhin Angriffs- und
  Reflection-Flaeche. Bei strikter Pinnung auf 1.17.1 sind CVE-2025-30065
  und CVE-2025-46762 zwar geschlossen, aber die Empfehlung lautet,
  parquet-avro/parquet-protobuf gar nicht erst einzuziehen und das per
  Dependency-Constraints in Gradle abzusichern. So bleibt der Avro-Pfad
  ausserhalb der GraalVM-Reachability.
- GraalVM-Native-Image bleibt das groesste Restrisiko; eine spaete
  Migration auf einen anderen Writer waere ein Format-Adapter-Tausch,
  nicht ein Vertragsbruch fuer die Ports.
