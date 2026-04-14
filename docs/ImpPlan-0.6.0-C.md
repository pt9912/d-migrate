# Implementierungsplan: Phase C - Schema-I/O, Reverse-Reports und gemeinsame JDBC-Metadatenbasis

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: C (Schema-I/O, Reverse-Reports und gemeinsame JDBC-Metadatenbasis)
> **Status**: Draft (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 4.2,
> Abschnitt 4.8 bis 4.10, Abschnitt 5 Phase C, Abschnitt 6.1 bis 6.4,
> Abschnitt 6.8, Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.6.0-B.md`; `docs/architecture.md`;
> `docs/connection-config-spec.md`

---

## 1. Ziel

Phase C schafft die Infrastruktur, auf der der spaetere Reverse-Pfad
ueberhaupt belastbar arbeiten kann:

- Schema-Dateien muessen produktiv nach YAML und JSON geschrieben werden
- diese Dateien muessen spaeter ueber die bestehenden file-based Pfade wieder
  einlesbar bleiben
- Reverse-Notes und `skipped_objects` muessen in ein getrenntes,
  maschinenlesbares Report-Artefakt gehen
- die Dialekte brauchen fuer Phase D eine gemeinsame JDBC-Metadatenbasis, die
  mehr ist als kopierter SQL-Text, aber weniger als eine plattgebuegelte
  Universalabstraktion

Der Teilplan liefert bewusst noch keinen vollstaendigen `SchemaReader` pro
Dialekt und noch kein `schema reverse`-CLI-Kommando. Er zieht die Format-,
Report- und Metadaten-Infrastruktur so gerade, dass Phase D und Phase E nicht
auf halb impliziten Dateipfaden oder duplizierten JDBC-Abfragen aufbauen
muessen.

Nach Phase C soll klar und testbar gelten:

- der Schema-Dateipfad ist formatbewusst statt YAML-hardcoded
- Reverse-Reports sind vom Schema-Artefakt getrennt und ohne Credential-Leak
  serialisierbar
- YAML- und JSON-Schema-Dateien sind fuer `schema validate`,
  `schema generate` und `schema compare` gleichwertig konsumierbar
- die Dialektmodule teilen sich eine kleine, connection-scoped
  JDBC-Metadatenbasis fuer Tabellen-, Spalten-, Key- und Indexprojektionen
- `TableLister` kann dieselbe Basis mitnutzen, ohne seinen bestehenden
  0.3.0-/0.4.0-Vertrag zu verlieren

---

## 2. Ausgangslage

Aktueller Stand in `formats`, `driver-common`, den Dialektmodulen und den
file-based Schema-Kommandos:

- `SchemaCodec` in `hexagon:ports` kennt heute nur
  `read(InputStream)`, `read(Path)` und `write(OutputStream, schema)`, aber
  keinen Format-Resolver und keinen expliziten Datei-I/O-Vertrag fuer YAML
  versus JSON.
- `YamlSchemaCodec` ist derzeit der einzige produktive Schema-Codec:
  - `read(...)` ist implementiert
  - `write(...)` ist noch `TODO`
  - die Klasse benutzt `YAMLFactory`
  - JSON wird heute hoechstens implizit ueber Jacksons YAML-Parser mitgelesen,
    nicht ueber einen ausdruecklich dokumentierten JSON-Dateipfad
- `SchemaValidateCommand`, `SchemaGenerateCommand` und
  `SchemaCompareCommand` instanziieren heute direkt `YamlSchemaCodec()` und
  dokumentieren ihre Inputs weiterhin als `(YAML)`-Dateien.
- `TransformationReportWriter` existiert bereits, ist aber klar DDL-zentriert:
  er arbeitet mit `DdlResult`, Ziel-Dialekt und Source-Datei und ist damit
  kein Reverse-Report-Writer.
- der bisherige Sidecar-Pfadhelper `SchemaGenerateHelpers.sidecarPath(...)`
  lebt im CLI-Modul und ist an den Generate-Pfad gekoppelt.
- `driver-common` besitzt heute Verbindungs- und Pool-Infrastruktur
  (`ConnectionUrlParser`, `LogScrubber`, `HikariConnectionPoolFactory`), aber
  noch keine gemeinsame JDBC-Metadaten-Schicht fuer Reverse.
- die drei `TableLister`-Implementierungen enthalten heute jeweils eigenen,
  kleinen SQL-Text fuer Tabellenlisten:
  - PostgreSQL: `information_schema.tables` plus `current_schema()`
  - MySQL: `information_schema.tables` plus `DATABASE()`
  - SQLite: `sqlite_master`
- `TableLister` dokumentiert bereits den spaeteren Uebergang zu
  `SchemaReader`, bleibt aber produktiv im Export-Pfad verankert und besitzt
  einen klaren Connection-Ownership-Vertrag: der Port leiht sich selbst eine
  Connection aus dem Pool und gibt sie nach dem Listing sofort zurueck.
- Tests decken heute:
  - YAML-Parsing und parser-negative YAML-Faelle in
    `YamlSchemaCodecTest`
  - DDL-Sidecar-Report-Struktur in `TransformationReportWriterTest`
  - `TableLister`-Verhalten in SQLite-, PostgreSQL- und MySQL-Tests
  - Hikari-/JdbcUrl-Ownership in `driver-common`
- Es gibt heute aber noch keine Tests fuer:
  - produktive Schema-Writer
  - deterministische YAML-/JSON-Serialisierung von `SchemaDefinition`
  - expliziten JSON-Dateipfad fuer `schema validate` / `generate` / `compare`
  - Reverse-Report-Writer
  - gemeinsame JDBC-Projektionen fuer spaeteren Reverse

Konsequenz fuer Phase C:

- Ohne produktiven Schema-I/O-Vertrag bleibt Reverse ein Read-only-Prototyp,
  dessen Ergebnis nicht als stabile Datei weiterverwendet werden kann.
- Ohne getrennten Reverse-Report droht entweder ein verunreinigtes
  Schema-Artefakt oder der Verlust strukturierter Reverse-Hinweise.
- Ohne gemeinsame JDBC-Basis wuerde Phase D dieselben Tabellen-, Spalten-,
  Key- und Indexqueries dialektweise mehrfach neu erfinden.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- produktiver YAML-Schreibpfad fuer `SchemaDefinition`
- expliziter JSON-Schema-Dateipfad inklusive produktivem JSON-Schreiben
- formatbewusste Schema-Datei-I/O fuer bestehende file-based Kommandos
- deterministische Serialisierung fuer YAML und JSON
- Einfuehrung eines Reverse-Report-Writers und eines stabilen Sidecar-Formats
- klare Trennung zwischen Schema-Artefakt und Reverse-Report
- Redaction/Scrubbing fuer Quellreferenzen in Reverse-Reports
- gemeinsame, connection-scoped JDBC-Metadaten-Helpers in
  `adapters:driven:driver-common`
- kleine geteilte Projektionen fuer Tabellen, Spalten, Keys, Indizes und
  gleichwertige Basiselemente
- Ausrichtung der neuen Helper auf den bestehenden Pool-/Connection-Vertrag
- Ueberfuehrung von `TableLister` auf dieselbe Query-Basis, soweit dies pro
  Dialekt sauber moeglich ist
- Tests fuer Writer, Report, Resolver und geteilte JDBC-Basis

### 3.2 Bewusst nicht Teil von Phase C

- vollstaendige `SchemaReader`-Implementierungen pro Dialekt
- Reverse-Mapping von allen DB-Objekten auf `SchemaDefinition`
- neues CLI-Kommando `schema reverse`
- `SchemaReverseRunner` oder andere Reverse-Runner
- `file/db`- oder `db/db`-Compare-Pfad
- Compare-Rendering fuer Reverse-Notes oder `skippedObjects`
- `data transfer`-Runner und Transfer-Preflight
- generischer SQL-Parser oder SQL-Datei-Reverse
- neues Port-Interface fuer 0.7.5 `SchemaIntrospectionPort`

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Schema-Dateien bleiben reine Schema-Artefakte

Die von Reverse erzeugte Datei unter `--output` bleibt ein nacktes
`SchemaDefinition`-Dokument.

Verbindliche Folge:

- Notes werden nicht in YAML oder JSON eingebettet
- `skipped_objects` werden nicht in die Schema-Datei hineingeschrieben
- dieselbe Datei bleibt spaeter direkt fuer
  `schema validate`, `schema generate` und `schema compare` wiederverwendbar

Reverse-Hinweise gehoeren stattdessen in ein separates Report-Artefakt.

### 4.2 Formataufloesung ist explizit; JSON ist kein YAML-Zufall

Phase C stuetzt den JSON-Pfad nicht auf die zufaellige Eigenschaft, dass ein
YAML-Parser eventuell auch JSON lesen kann.

Verbindlich fuer 0.6.0:

- YAML und JSON sind zwei explizite Schema-Dateiformate
- `YamlSchemaCodec` bleibt YAML-zentriert
- JSON bekommt einen eigenen produktiven Codec oder eine gleichwertig explizite
  Serializer-Komponente unter demselben Datei-I/O-Vertrag
- bestehende file-based Kommandos instanziieren nicht mehr direkt
  `YamlSchemaCodec()`, sondern nutzen einen formatbewussten Resolver
- fuer file-based Read-Pfade in 0.6.0 bleibt die Formaterkennung
  dateinamensbasiert; `schema validate`, `schema generate` und
  `schema compare` bekommen in Phase C keinen zweiten konkurrierenden
  Input-Format-Flag
- akzeptierte Dateiendungen fuer Schema-Dateien in 0.6.0:
  - `.yaml`
  - `.yml`
  - `.json`
- unbekannte oder endungslose Schema-Dateien werden nicht heuristisch
  "irgendwie" geparst, sondern fuehren ohne explizite Formatangabe zu einem
  klaren Fehlerpfad
- fuer den spaeteren Reverse-Write-Pfad bedeutet das verbindlich:
  `--format yaml|json` bestimmt zwar die Serialisierung, `--output` muss aber
  eine dazu passende Endung tragen, damit das Artefakt ueber die file-based
  Pfade wieder konsumierbar bleibt
- ein Reverse-Output wie `/tmp/reverse` ohne `.yaml`/`.yml`/`.json` ist damit
  in 0.6.0 kein kanonischer persistenter Schema-Dateipfad

Wichtig:

- Reverse `--format yaml|json` steuert spaeter nur das Schema-Artefakt
- das globale CLI-`--output-format` bleibt Darstellungsformat fuer
  Kommandoausgaben und ist kein Ersatz fuer den Schema-Dateiformatvertrag

### 4.3 Schema-Datei-I/O gehoert in `formats`, nicht ins CLI

Die produktive Serialisierung von `SchemaDefinition` gehoert in den
Formats-Schnitt und nicht in handgeschriebenen JSON- oder YAML-Baucode im
CLI- oder Runner-Layer.

Verbindliche Folge:

- YAML- und JSON-Writing sitzen in `adapters:driven:formats`
- Format-Resolver und Datei-I/O-Helfer fuer Schema-Dateien sitzen ebenfalls
  dort oder in einem gleichwertig formatnahen Subpaket
- CLI und Application koordinieren nur noch:
  - Pfade
  - Formatwahl
  - Fehlercodes
  - stdout/stderr

Der bestehende JSON-Helfer in `SchemaGenerateHelpers` bleibt damit eine
Generate-spezifische Ausnahme fuer DDL-Ausgabe und wird nicht zum Vorbild fuer
Schema-Dateien oder Reverse-Reports.

### 4.4 Reverse-Reports bekommen einen eigenen Writer und scrubben Quellen

`TransformationReportWriter` ist fachlich auf DDL zugeschnitten. Reverse braucht
einen eigenen Reportvertrag.

Verbindlich fuer Phase C:

- Reverse-Reports serialisieren einen eigenen Report-Input auf Basis von
  `SchemaReadResult`, nicht `DdlResult`
- der Writer lebt in `adapters:driven:formats`
- kanonische Mindestform fuer den Writer-Eingang:

```kotlin
data class SchemaReadReportInput(
    val source: ReverseSourceRef,
    val result: SchemaReadResult,
)

data class ReverseSourceRef(
    val kind: ReverseSourceKind, // ALIAS | URL
    val value: String,
)
```

- der Report traegt mindestens:
  - Quelle
  - Schema-Metadaten
  - Summary-Zaehler
  - `notes`
  - `skipped_objects`
- Quellreferenzen werden fuer Reports und Fehlermeldungen ueber den zentralen
  Scrubbing-Pfad maskiert

Konkret bedeutet das:

- Aliasnamen duerfen unveraendert erscheinen
- URL-basierte Quellen duerfen nie mit Klartext-Passwort im Report landen
- `LogScrubber.maskUrl(...)` oder ein gleichwertiger zentraler Pfad ist
  verpflichtend
- `SchemaReadResult` selbst bleibt quellagnostisch; Source-Kontext wird nur
  ueber den expliziten Report-Input an den Writer gegeben

Phase C legt den Report als Sidecar-Vertrag fest. Ein zweites konkurrierendes
Inline-Note-Format im Schema-Dokument ist ausgeschlossen.

### 4.5 Reverse-Report und Schema-Datei haben getrennte Formatpolitik

Fuer 0.6.0 wird bewusst nicht versucht, jede Kombination aus Schemaformat,
CLI-Ausgabeformat und Reportformat gegeneinander zu entkoppeln.

Verbindliche Folge:

- das Schema-Artefakt unterliegt dem expliziten YAML-/JSON-Dateivertrag
- der Reverse-Report wird fuer 0.6.0 als eigener strukturierter Sidecar-Pfad
  eingefuehrt
- der Default-Sidecar folgt demselben Benennungsmuster wie beim bestehenden
  Generate-Pfad
- ein spaeterer gemeinsamer Sidecar-Helper darf aus dem Generate-Pfad
  herausgezogen werden, aber der Reverse-Report bleibt semantisch ein eigener
  Dokumenttyp

### 4.6 Die gemeinsame JDBC-Basis ist connection-scoped und query-orientiert

Die neue gemeinsame Infrastruktur sitzt nicht im Runner und erfindet auch keine
zweite Pool-Abstraktion.

Verbindlich fuer Phase C:

- oeffentliche Ports arbeiten weiterhin gegen `ConnectionPool`
- `SchemaReader` und `TableLister` bleiben Owner des Borrow-/Close-Zyklus
- die geteilte JDBC-Basis arbeitet mit bereits geliehenen `Connection`-
  Instanzen
- ein Reverse-Read arbeitet spaeter pro Aufruf auf genau einer geliehenen
  Connection; Subhelper duerfen keine eigenen Pool-Borrows ausloesen

Diese Entscheidung ist wichtig fuer:

- konsistente Snapshot-Sicht waehrend eines Reads
- saubere Ownership
- Wiederverwendung durch `TableLister`
- Tests gegen das bestehende Hikari-Modell

### 4.7 Geteilte Projektionen bleiben klein und objektbezogen

Phase C fuehrt keine magische `Any`-Map und kein monolithisches
`DatabaseMetaDump`-Objekt ein.

Verbindliche Folge:

- die gemeinsame Basis liefert kleine, typisierte Projektionen fuer die
  Objektgruppen, die in allen drei Dialekten frueh gebraucht werden
- mindestens betroffen sind:
  - Tabellenreferenzen
  - Spaltenmetadaten
  - allgemeine Constraint-Metadaten
  - Primary-Key-Metadaten
  - Foreign-Key-Metadaten
  - Indexmetadaten
- Views, Routinen, Trigger, Custom Types, Sequenzen und andere spaetere
  Spezialfaelle duerfen weiterhin dialektspezifische Zusatzqueries behalten

Damit verhindert Phase C zwei Extreme:

- zu wenig Struktur, so dass Phase D wieder in String-SQL zerfaellt
- zu viel Pseudo-Abstraktion, die die Dialektbesonderheiten plattbuegelt

### 4.8 `TableLister` bleibt port-stabil, darf intern aber dieselbe Basis nutzen

`TableLister` wird in Phase C nicht still entfernt oder semantisch veraendert.

Verbindlich bleibt:

- `listTables(pool)` liefert weiter nur User-Tabellen
- Reihenfolge bleibt stabil
- jede Listing-Operation gibt ihre Connection wieder zurueck

Neu erlaubt und gewuenscht ist:

- dass dieselbe dialektspezifische Tabellenprojektion spaeter sowohl vom
  `TableLister` als auch vom `SchemaReader` benutzt wird
- dass `TableLister` nur noch ein duennes Port-Wrapper ueber diese gemeinsame
  Query-Basis wird

Nicht akzeptabel ist:

- ein zweiter konkurrierender Tabellenquery-Pfad neben der neuen Basis
- ein Helper-Layer, der `TableLister` umgeht oder dessen Ownership-Vertrag
  unterlaeuft

### 4.9 Phase C schneidet auf spaetere Wiederverwendung, aber extrahiert noch keinen neuen Port

Die gemeinsame JDBC-Metadatenbasis soll spaeter fuer 0.7.5
`SchemaIntrospectionPort` wiederverwendbar sein.

Das bedeutet fuer 0.6.0:

- fachliche Projektionen und Query-Helfer so schneiden, dass sie nicht an
  `schema reverse`-CLI-Details kleben
- aber noch keinen zusaetzlichen Introspection-Port im Hexagon einfuehren
- Wiederverwendbarkeit entsteht ueber saubere interne Schnitte, nicht ueber
  vorschnelle Port-Explosion

---

## 5. Arbeitspakete

### C.1 Formatbewussten Schema-Dateipfad einfuehren

Im Formats-Schnitt ist ein expliziter Datei-I/O-Vertrag fuer Schema-Dateien
einzufuehren.

Mindestens noetig:

- kleiner Resolver fuer Dateiformat anhand von Endung oder expliziter
  Formatangabe
- klare Zuordnung `.yaml` / `.yml` -> YAML und `.json` -> JSON
- eindeutiger Fehlerpfad fuer unbekannte Endungen
- eindeutiger Fehlerpfad fuer Write-Pfade, deren Endung nicht zum explizit
  gewaehlten Ausgabeformat passt
- Loesung, ueber die bestehende file-based Kommandos nicht mehr direkt an
  `YamlSchemaCodec` haengen

Wichtig:

- Phase C darf JSON-Einlesen nicht als versteckten Nebeneffekt eines
  YAML-Codecs "mitnehmen"
- derselbe Resolver ist spaeter auch fuer Reverse-Output wiederverwendbar
- die bestehenden file-based Kommandos bleiben in 0.6.0 bewusst
  endungsgetrieben; ein expliziter Format-Override ist nur fuer den
  Write-Pfad vorgesehen

### C.2 `YamlSchemaCodec.write(...)` produktiv machen

Der bestehende YAML-Codec muss vom Read-only-Parser zum produktiven Writer
werden.

Verbindlich:

- `write(...)` serialisiert das aktuelle `SchemaDefinition`-Modell vollstaendig
- die Ausgabe ist deterministisch genug fuer Goldens und Round-Trip-Tests
- Serialisierung respektiert die in Phase B festgezogenen kanonischen Objektkeys
- keine Reverse-Notes, `skipped_objects` oder anderen Reportfelder im
  Schema-Dokument

Die Writer-Tests muessen mindestens YAML-Round-Trip und stabile Feldreihenfolge
fuer relevante Objektgruppen absichern.

### C.3 JSON-Schema-Codec oder gleichwertigen JSON-Serializer einfuehren

Neben YAML braucht Phase C einen ausdruecklichen JSON-Pfad fuer
`SchemaDefinition`.

Verbindlich:

- JSON-Schreiben ist produktiv unter demselben Modellvertrag verfuegbar
- JSON-Dateien koennen spaeter ueber denselben formatbewussten Datei-I/O-Pfad
  wieder eingelesen werden
- JSON bleibt ein reines Schema-Artefakt ohne Reportdaten
- Pretty-Printing und Feldreihenfolge sind stabil genug fuer Goldens

Falls kein eigener `JsonSchemaCodec` eingefuehrt wird, muss die alternative
Komponente funktional gleichwertig sein und denselben Resolververtrag erfuellen.

### C.4 Bestehende file-based Schema-Kommandos auf den neuen I/O-Vertrag heben

Phase C endet nicht bei einem unbenutzten Format-Resolver im Formats-Modul.

Deshalb sind die bestehenden file-based Pfade mindestens so anzupassen, dass
sie YAML und JSON gleichwertig konsumieren koennen:

- `schema validate --source`
- `schema generate --source`
- `schema compare --source`
- `schema compare --target`

Wichtig:

- das ist keine neue Reverse-CLI
- es ist nur die noetige Nachfuehrung, damit reverse-erzeugte Dateien spaeter
  wirklich wieder konsumierbar bleiben
- Help-Texte und Fehlertexte duerfen danach nicht weiter `(YAML)` als einzigen
  Schema-Dateityp behaupten

### C.5 Reverse-Report-Writer und Sidecar-Dokument einfuehren

Auf Basis von `SchemaReadResult` aus Phase B ist ein eigener Report-Writer fuer
Reverse einzufuehren.

Der Writer arbeitet kanonisch nicht mit einem nackten `SchemaReadResult`,
sondern mit einem expliziten `SchemaReadReportInput`, das den getypten
Source-Bezug neben das Ergebnis stellt.

Der Reportvertrag muss mindestens tragen:

- `source`
- `schema`
- `summary`
- `notes`
- `skipped_objects`

Empfohlene Summary-Zaehler:

- Gesamtzahl Notes
- Warnings
- Action-Required-Hinweise
- `skipped_objects`

Wichtig:

- die Quellreferenz ist maskiert oder aliasbasiert
- der Writer ist Reverse-spezifisch und nicht nur ein umbenannter
  `TransformationReportWriter`
- der Writer bekommt die Quelle typisiert (`ALIAS` oder `URL`) und maskiert
  URL-Werte vor der Serialisierung zentral ueber `LogScrubber`
- der Sidecar-Pfad folgt dem bestehenden Muster `<basename>.report.yaml`,
  solange keine explizite Report-Datei angegeben ist

### C.6 Gemeinsame JDBC-Metadaten-Session und Basishilfen in `driver-common`
einziehen

Phase C braucht eine kleine wiederverwendbare Infrastruktur fuer
connection-scoped Metadatenabfragen.

Noetig ist mindestens:

- ein klarer Einstiegspunkt fuer Queries auf bereits geliehener `Connection`
- Helper fuer sauberes Query-Ausfuehren und ResultSet-Mapping
- keine Pool-Ownership im Helper-Layer
- keine Runner-Abhaengigkeit

Die gemeinsame Basis darf intern JDBC `DatabaseMetaData`, `information_schema`,
PRAGMA-Statements oder andere Dialektqueries kombinieren, solange die
oeffentliche Form fuer Phase D auf typisierte Projektionen hinauslaeuft.

### C.7 Kleine Projektionen fuer gemeinsame Reverse-Bausteine definieren

Fuer die in allen Dialekten frueh benoetigten Objektarten sind kleine,
typisierte Projektionen einzufuehren.

Mindestens zu schneiden:

- Tabellenreferenz
- Spalte
- allgemeine Constraint-Projektion
- Primary Key
- Foreign Key
- Index

Empfohlene Semantik:

- die Projektion beschreibt den fachlich benoetigten Kern
- dialektspezifische Rohwerte koennen als benannte Zusatzfelder mitgetragen
  werden, aber nicht als freie `Map<String, Any>`
- `CHECK`, `UNIQUE` und weitere compare-relevante Constraint-Arten duerfen
  nicht implizit aus dem Shared-Layer herausfallen; falls PK/FK als eigene
  Spezialprojektionen existieren, braucht es trotzdem einen kanonischen
  Constraint-Schnitt fuer den restlichen Constraint-Bestand
- Phase D mappt diese Projektionen anschliessend in das neutrale Modell

### C.8 `TableLister` auf dieselbe Tabellenprojektion ausrichten

Soweit technisch sauber, soll `TableLister` pro Dialekt denselben
Tabellenquery-Baustein nutzen wie der spaetere `SchemaReader`.

Verbindlich bleibt:

- keine Aenderung des Port-Signatures
- keine Aenderung des Export-Verhaltens
- keine versteckte Aenderung der Tabellenfilterung

Wenn ein Dialekt fuer 0.6.0 noch einen duennen Wrapper braucht, muss dieser an
die neue Tabellenprojektion delegieren und darf keinen zweiten parallelen
SQL-Textpfad etablieren.

### C.9 Formats- und Infrastrukturtests erweitern

Phase C braucht zusaetzliche Tests in `formats`, `driver-common`, den
Dialektmodulen und den file-based Schema-Kommandos.

Mindestens abzudecken:

- YAML-Writer-Round-Trip
- JSON-Writer-Round-Trip
- Format-Resolver fuer `.yaml`, `.yml`, `.json` und Fehlerfaelle
- getrenntes Reverse-Report-Dokument mit maskierter Quelle
- Wieder-Einlesen von YAML- und JSON-Schema-Dateien ueber die produktiven
  file-based Kommandopfade
- unveraendertes `TableLister`-Verhalten nach Anbindung an die gemeinsame
  Projektion

---

## 6. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren sind voraussichtlich:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/format/SchemaCodec.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`
- neuer JSON-Schema-Codec oder gleichwertiger JSON-Serializer unter
  `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/...`
- neuer formatbewusster Schema-Datei-I/O-Resolver unter
  `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/...`
- neuer Reverse-Report-Writer unter
  `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/...`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- neue JSON-/Resolver-/Reverse-Report-Tests im `formats`-Modul
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- gegebenenfalls ein aus `SchemaGenerateHelpers` herausgezogener gemeinsamer
  Sidecar-Pfadhelper
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/HikariConnectionPoolFactory.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/LogScrubber.kt`
- neue gemeinsame JDBC-Metadaten-Helper unter
  `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/...`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableLister.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTableLister.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableLister.kt`
- zugehoerige Tests in `driver-common`, `driver-postgresql`, `driver-mysql`,
  `driver-sqlite` und `adapters:driving:cli`

Bewusst noch nicht direkt betroffen:

- dialektspezifische `SchemaReader`-Implementierungen fuer komplette Reverse-
  Ergebnisse
- `hexagon:application`-Reverse-Runner
- `schema reverse`-Clikt-Kommando

---

## 7. Akzeptanzkriterien

Phase C ist nur abgeschlossen, wenn alle folgenden Punkte erfuellt sind:

- `YamlSchemaCodec.write(...)` ist produktiv implementiert und durch
  Round-Trip-Tests abgesichert.
- JSON-Schema-Dateien koennen produktiv geschrieben und gelesen werden; der
  JSON-Pfad ist explizit und nicht nur implizit ueber den YAML-Parser
  erreichbar.
- Die produktiven file-based Pfade von `schema validate`, `schema generate`
  und `schema compare` konsumieren Schema-Dateien formatbewusst ueber einen
  gemeinsamen Resolver statt direkt ueber `YamlSchemaCodec`.
- Der Datei-I/O-Vertrag ist fuer Reverse-kompatible Persistenz eindeutig:
  explizites Ausgabeformat und Dateiendung duerfen sich nicht widersprechen;
  endungslose Reverse-Outputs gelten nicht als kanonisch wiederverwendbare
  Schema-Dateien.
- Reverse-Reports werden in einem separaten Artefakt serialisiert; Schema-Datei
  und Reportdaten bleiben strikt getrennt.
- Reverse-Reports enthalten strukturierte `notes` und `skipped_objects` sowie
  eine maskierte oder aliasbasierte Quellreferenz.
- Der Reverse-Report-Writer arbeitet gegen einen expliziten Report-Input mit
  getypter Quelle statt gegen ein nacktes `SchemaReadResult` plus losem
  Zusatzstring.
- Der Default-Sidecar-Pfad fuer Reverse folgt dem bestehenden
  `<basename>.report.yaml`-Muster oder einer gleichwertig dokumentierten,
  einheitlichen Regel.
- Die gemeinsame JDBC-Basis arbeitet mit bereits geliehener `Connection` und
  fuehrt keine eigenen Pool-Borrows aus.
- Fuer Tabellen-, Spalten-, Constraint-, Key- und Index-Grunddaten existieren
  kleine, typisierte Projektionen statt freier Metadatenmaps.
- `TableLister` behaelt sein bisheriges fachliches Verhalten und nutzt, wo
  sinnvoll, dieselbe Tabellenprojektion statt eines zweiten parallelen
  SQL-Textpfads.
- Die neue Struktur ist so geschnitten, dass sie spaeter fuer 0.7.5
  `SchemaIntrospectionPort` wiederverwendbar ist, ohne CLI-Details
  mitzuschleppen.

---

## 8. Verifikation

Mindestens auszufuehren:

- `docker build --target build --build-arg GRADLE_TASKS=":adapters:driven:formats:test :adapters:driven:driver-common:test :adapters:driving:cli:test :adapters:driven:driver-sqlite:test" -t d-migrate:phase-c-core .`
- `./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test`
- `./scripts/test-integration-docker.sh :adapters:driven:driver-mysql:test`

Gezielt zu pruefen ist dabei:

- YAML-Round-Trip fuer representative Schema-Fixtures
- JSON-Round-Trip fuer dieselben oder gleichwertige Fixtures
- Fehlerpfad bei unbekannter Schema-Dateiendung
- Fehlerpfad bei endungslosem oder formatinkonsistentem Write-Pfad
- getrennte Reverse-Report-Serialisierung mit maskierter URL
- Reverse-Report-Serialisierung ueber den expliziten Report-Input mit
  Alias- und URL-Quelle
- Wieder-Einlesen von JSON-Schema-Dateien ueber `schema validate`
- gemischter Datei-Compare-Pfad, z.B. YAML gegen JSON
- Shared-Projektionen fuer Constraints inklusive `CHECK`/`UNIQUE`, soweit vom
  Dialekt geliefert
- unveraenderte Tabellenlisten und Connection-Return in den bestehenden
  `TableLister`-Tests

Manuelle Smoke-Pruefungen nach Implementierung:

1. `d-migrate schema validate --source /tmp/reverse.json`
2. `d-migrate schema compare --source /tmp/source.yaml --target /tmp/target.json`
3. Reverse-Sidecar-Report enthaelt keine Klartext-Credentials bei URL-Quelle

---

## 9. Risiken und offene Punkte

### R1 - JSON bleibt nur halb explizit

Wenn JSON zwar geschrieben, aber intern weiter ueber `YamlSchemaCodec`
mitgelesen wird, bleibt der Vertrag zufaellig und spaeter schwer testbar.

### R2 - Reverse-Reports leaken Verbindungsdaten

Sobald Reverse Reports rohe `--source`-URLs spiegeln, ist der Pfad fachlich
falsch, auch wenn die eigentliche Reverse-Funktion technisch arbeitet.

### R3 - Die gemeinsame JDBC-Basis wird zu generisch

Ein zu frueher Universal-Layer wuerde PostgreSQL-, MySQL- und SQLite-
Besonderheiten in einen abstrakten Nenner pressen, der in Phase D sofort
wieder aufreisst.

### R4 - `TableLister` bleibt neben der neuen Basis ein zweiter Query-Pfad

Wenn Phase C zwar eine Tabellenprojektion einfuehrt, `TableLister` aber
weiterhin separaten SQL-Text pflegt, ist der angestrebte gemeinsame Sockel
nur halb erreicht.

### R5 - Deterministische Writer-Reihenfolge bleibt implizit

Ohne feste Writer-Ordnung werden JSON- und YAML-Goldens instabil und spaetere
Reverse-Dateien diffen unnoetig.

### R6 - Formatresolver wird nur im Formats-Modul gebaut, aber nicht in die
bestehenden Kommandos verdrahtet

Dann waere der 0.6.0-Dateivertrag zwar theoretisch vorhanden, aber
reverse-erzeugte JSON-Dateien koennten praktisch noch immer nicht ueber die
produktiven file-based Kommandos genutzt werden.

### R7 - Connection-Ownership verwischt zwischen Port und Helper

Sobald Shared-Helper selbst Pools oder eigene Connections aufmachen, passen
Tests, Ownership und spaetere Reader-Semantik nicht mehr sauber zusammen.

---

## 10. Abschlussdefinition

Phase C ist abgeschlossen, wenn:

- YAML- und JSON-Schema-Dateien produktiv serialisiert und wieder eingelesen
  werden koennen,
- Reverse-Reports als eigener strukturierter Sidecar-Pfad mit maskierter
  Quelle existieren,
- bestehende file-based Schema-Kommandos den neuen Formatvertrag konsumieren,
- die dialektuebergreifende JDBC-Metadatenbasis fuer Tabellen-, Spalten-,
  Key- und Indexprojektionen steht,
- und `TableLister` den neuen Sockel mitnutzen kann, ohne seinen bisherigen
  Portvertrag zu verlieren.

Danach koennen Phase D die dialektspezifischen `SchemaReader`-Implementierungen
und Phase E den eigentlichen `schema reverse`-CLI-Pfad auf einer stabilen
Format- und Infrastrukturgrundlage aufbauen.
