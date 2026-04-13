# Implementierungsplan: Phase D - Dialektimplementierung fuer `SchemaReader`

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: D (Dialektimplementierung fuer `SchemaReader`)
> **Status**: Draft (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 4.2 bis 4.10,
> Abschnitt 5 Phase D, Abschnitt 6.1 bis 6.4, Abschnitt 7, Abschnitt 8,
> Abschnitt 9, Abschnitt 10; `docs/ImpPlan-0.6.0-B.md`;
> `docs/ImpPlan-0.6.0-C.md`; `docs/neutral-model-spec.md`

---

## 1. Ziel

Phase D setzt den fachlichen Reverse-Vertrag aus Phase B und die Format- bzw.
Metadateninfrastruktur aus Phase C in den drei Built-in-Dialekten konkret um.

Ergebnis von Phase D sind belastbare `SchemaReader`-Implementierungen fuer:

- PostgreSQL
- MySQL
- SQLite

Der Teilplan liefert bewusst noch kein `schema reverse`-CLI-Kommando, keinen
Runner und keinen Compare-Pfad fuer DB-Operanden. Er sorgt dafuer, dass die
Treiber selbst belastbare Reverse-Ergebnisse mit strukturierten Notes und
`skippedObjects` liefern koennen.

Nach Phase D soll auf Driver-Ebene klar und testbar gelten:

- jeder Built-in-Dialekt exponiert einen produktiven `SchemaReader`
- Tabellen, Spalten, Keys, Constraints und Indizes werden gelesen
- PostgreSQL liest zusaetzlich Sequenzen und relevante Custom Types
- Views, Procedures, Functions und Triggers sind ueber Include-Flags
  kontrollierbar
- 0.5.5-Typen wie `uuid`, `json`, `binary`, `array` und `geometry` werden
  ehrlich transportiert oder als Notes/Skips sichtbar gemacht
- nicht exakt neutrale oder bewusst ausgelassene Faelle verschwinden nicht
  still

---

## 2. Ausgangslage

Aktueller Stand in den Treibermodulen:

- `DatabaseDriver` exponiert im realen Code heute noch keinen
  `schemaReader()`. Die Driver liefern nur `ddlGenerator()`, `dataReader()`,
  `tableLister()`, `dataWriter()` und `urlBuilder()`.
- Es existiert aktuell noch keine produktive `SchemaReader`-Implementierung in
  `adapters:driven:driver-postgresql`, `driver-mysql` oder `driver-sqlite`.
- Die bestehende Testabdeckung liegt heute auf:
  - `DataReader`
  - `TableLister`
  - `JdbcUrlBuilder`
  - `TypeMapper`
  - Teilen von `SchemaSync`
- Es gibt noch keine Driver-Tests fuer:
  - `SchemaReadResult`
  - Reverse-Notes / `skippedObjects`
  - Include-Flags fuer Views / Routinen / Trigger
  - verlustfreies Reverse-Mapping auf `SchemaDefinition`

Bereits vorhandene dialektspezifische Ausgangsbasis:

- PostgreSQL:
  - `PostgresTableLister` liest Basistabellen aus
    `information_schema.tables WHERE table_schema = current_schema()`
  - `PostgresDataReader` etabliert Ownership, Quoting und Cursor-Verhalten
  - `PostgresIdentifiers.kt` bringt bereits Hilfen fuer qualifizierte Namen
    und `current_schema()`
  - `PostgresSchemaSync` nutzt schon PostgreSQL-spezifische Queries wie
    `pg_get_serial_sequence(...)` und `pg_trigger`
  - `PostgresTypeMapper` kennt bereits `uuid`, `jsonb`, `xml`, `bytea`,
    `array` und `geometry`
- MySQL:
  - `MysqlTableLister` nutzt `information_schema.tables` plus `DATABASE()`
  - `MysqlDataReader` deckt serverseitiges Cursor-Streaming und Backtick-
    Quoting ab
  - `MysqlIdentifiers.kt` bringt heute schon Hilfen fuer
    `currentDatabase()`, `@@lower_case_table_names` und
    information_schema-kompatible Identifier-Normalisierung
  - `MysqlSchemaSync` liest bereits `AUTO_INCREMENT`-Metadaten aus
    `information_schema.columns`
  - `MysqlTypeMapper` kennt `json`, `enum`-Inline-Darstellung und
    Spatial-Typnamen
- SQLite:
  - `SqliteTableLister` liest Basistabellen aus `sqlite_master`
  - `SqliteDataReader` und `SqliteSchemaSync` nutzen bereits
    `sqlite_master`, `PRAGMA table_info(...)` und `sqlite_sequence`
  - `SqliteTypeMapper` zeigt die heutige starke Kollapsrate der
    SQLite-Typaffinitaet
  - der 0.5.5-Spatial-Pfad in `SqliteDdlGenerator` belegt bereits, dass
    SpatiaLite und Geometry im SQLite-Dialekt nicht wie normale portable
    Tabellenfelder behandelt werden duerfen

Wichtige Konsequenzen fuer Phase D:

- Die Driver haben bereits mehrere brauchbare Metadaten- und
  Identifier-Bausteine, aber noch keinen zusammenhaengenden Reverse-Pfad.
- Ein naiver "Invert TypeMapper / invert DDL" Ansatz waere fachlich falsch:
  - PostgreSQL hat mehr Information als der Generator ausnutzt
  - MySQL hat case- und engine-spezifische Metadaten
  - SQLite verliert ueber reine Typaffinitaet zu viel Semantik
- Phase D muss deshalb metadata-first und note-aware umgesetzt werden.

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- produktive `SchemaReader`-Implementierungen fuer PostgreSQL, MySQL und
  SQLite
- Driver-Wiring ueber `schemaReader()` in den Built-in-Drivern
- Mapping der gelesenen Dialektmetadaten auf `SchemaReadResult`
- kanonische Fuellung der Pflichtmetadaten `SchemaDefinition.name` und
  `SchemaDefinition.version` fuer live gelesene Schemas
- verpflichtender Read-Pfad fuer:
  - Tabellen
  - Spalten
  - Spaltenattribute `required`, `default`, `unique` und `references`
  - Primary Keys
  - Foreign Keys
  - weitere Constraints
  - Indizes
- PostgreSQL-spezifisch:
  - Sequenzen
  - `ENUM`, `DOMAIN`, `COMPOSITE`
  - optionale Views / Functions / Procedures / Triggers
  - Partitionierungs- und Extension-Bezug gemaess Phase-B-Vertrag
- MySQL-spezifisch:
  - `AUTO_INCREMENT`
  - Engine
  - `json`, `enum`, Spatial-Typen
  - optionale Views / Functions / Procedures / Triggers
  - sichtbare Notes fuer `SET` und andere nicht exakt neutrale Faelle
- SQLite-spezifisch:
  - `WITHOUT ROWID`
  - Virtual Tables
  - Views / Triggers
  - SQLite-Typaffinitaet mit ehrlichem Reverse-Vertrag
  - sichtbare Behandlung von SpatiaLite-/Geometry-Sonderfaellen
- Notes-/Skip-Semantik fuer best-effort, unsupported und bewusst ausgelassene
  Objekte
- Driver-nahe Unit- und Integrationstests

### 3.2 Bewusst nicht Teil von Phase D

- JSON-/YAML-Schreibpfade und Reverse-Report-Writer
- `schema reverse`-Runner oder Clikt-Kommando
- Datei- und URL-Aufloesung ueber CLI
- `file/db`- oder `db/db`-Compare
- CLI-Rendering von Reverse-Notes
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite
- automatische Installation von PostGIS, SpatiaLite oder anderen Extensions
- Parser fuer SQL-Datei-Reverse

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 Reverse ist metadata-first, nicht Generator-Inversion

Phase D baut Reverse nicht als Rueckwaertsabbildung von `DdlGenerator` oder
`TypeMapper`.

Verbindliche Folge:

- Driver lesen echte DB-Metadaten
- vorhandene `TypeMapper` helfen hoechstens als Referenz fuer heutige
  Dialekterwartungen
- sobald Reverse und Generator unterschiedliche Informationsdichte haben,
  gewinnt die DB-Metadatenquelle

Das ist zwingend, weil:

- PostgreSQL mehr native Typ- und Objektinformation traegt als der aktuelle
  Generator ausdrueckt
- MySQL mehrere nicht neutrale Faelle wie `SET`, Engine oder
  case-sensitive Identifiers hat
- SQLite ueber reine Affinitaet zu viele Semantiken kollabiert

### 4.2 Mandatory Core-Objekte duerfen nicht in Notes wegrelativiert werden

Basale Tabellenstruktur ist fuer 0.6.0 kein optionaler Komfort.

Verbindlich fuer Phase D:

- Fehler beim Lesen von Tabellen, Spalten, Keys, Constraints oder Indizes sind
  fuer den jeweiligen Read ein harter Metadatenfehler
- isolierte Probleme bei optionalen Objekten unter Include-Flags duerfen als
  Notes oder `skippedObjects` sichtbar werden, wenn das Kernschema trotzdem
  belastbar bleibt
- "best effort" bedeutet keine stillen Verluste bei Kernobjekten

### 4.3 Reverse fuellt `SchemaDefinition.name` und `version` kanonisch

`SchemaReader` darf die Pflichtfelder des neutralen Modells nicht pro Dialekt
beliebig erfinden.

Verbindlich fuer Phase D:

- `SchemaDefinition.name` bildet den logisch gelesenen DB-Scope ab, nicht
  Connection-Alias, Credentials oder Dateipfade
- PostgreSQL verwendet dafuer den stabilen Scope
  `database=<db>;schema=<schema>`
- MySQL verwendet den stabilen Scope `database=<db>`
- SQLite verwendet den stabilen Scope `schema=<schema>`, in der Regel also
  `schema=main`, niemals aber einen absoluten Dateipfad
- wenn Komponenten Trennzeichen enthalten, werden ihre Werte vor der
  Zusammensetzung percent-encoded
- `SchemaDefinition.version` verwendet in 0.6.0 mangels belastbarer
  Live-Metadaten einen festen Reverse-Platzhalter `0.0.0-reverse`
- alle drei Dialekte nutzen dieselbe Platzhalterregel, damit Compare und
  Round-Trip-Verhalten nicht von Ad-hoc-Werten abhaengen

### 4.4 Spalten werden semantisch und nicht nur typbasiert rueckgefuehrt

Fuer 0.6.0 reicht ein Reverse von Spaltennamen plus Typstrings nicht aus.

Verbindlich fuer Phase D:

- Nullability wird bewusst auf `ColumnDefinition.required` abgebildet
- Datenbank-Defaults werden bewusst auf `ColumnDefinition.default`
  transportiert, soweit die DB einen stabil lesbaren Wert oder Ausdruck
  liefert
- einspaltige `UNIQUE`-Semantik wird bei verlustfreier Zuordnung auf
  `ColumnDefinition.unique` gehoben
- einspaltige Foreign-Key-Semantik wird bei verlustfreier Zuordnung auf
  `ColumnDefinition.references` gehoben
- mehrspaltige `UNIQUE`- oder Foreign-Key-Beziehungen bleiben in
  `ConstraintDefinition` und werden nicht inkonsistent halb auf Spalten und
  halb auf Constraints verteilt
- wenn ein Dialekt nur einen Teil dieser Semantik belastbar liefert, ist eine
  sichtbare Reverse-Note Pflicht statt einer still erfundenen Standardabbildung

### 4.5 PostgreSQL darf `information_schema` und `pg_catalog` kombinieren

PostgreSQL-Introspection darf nicht kuenstlich auf nur eine Metadatenquelle
gedrueckt werden.

Verbindliche Folge:

- portable Basisdaten koennen ueber `information_schema` gelesen werden
- PostgreSQL-spezifische Informationen duerfen gezielt aus `pg_catalog` oder
  gleichwertigen Systemfunktionen kommen
- Sequenzen, Trigger, Routinen, Composite-Types, Domains oder
  Partitionierungsinformationen muessen nicht in eine kuenstliche
  `information_schema`-Erzaehlung gezwungen werden

Bereits vorhandene Hilfen wie `currentSchema(...)`, qualifizierte
Tabellenpfade und `pg_get_serial_sequence(...)` sind bewusst zu nutzen, nicht
zu duplizieren.

### 4.6 MySQL-Reverse muss Case-Semantik und Nicht-Neutralitaet offenlegen

MySQL hat Metadatendetails, die fuer 0.6.0 compare-relevant oder zumindest
sichtbar sein muessen.

Verbindlich:

- Tabellen-Engine wird als compare-relevantes Tabellenattribut gelesen
- `AUTO_INCREMENT` wird nicht nur als "identifier irgendwie erkannt", sondern
  bewusst transportiert
- `@@lower_case_table_names` wird fuer information_schema-Lookups und
  Identifier-Normalisierung beachtet
- `SET`, nicht exakt neutrale Column-Definitionen oder sonstige
  MySQL-Sonderfaelle werden nicht still auf portable Standardtypen
  zurechtgebogen

Wenn ein Fall nicht exakt neutral rueckfuehrbar ist, ist eine strukturierte
Reverse-Note Pflicht.

### 4.7 SQLite braucht `sqlite_master` plus PRAGMA und ehrliche Semantik

SQLite-Reverse darf nicht aus Typstrings alleine raten.

Verbindlich fuer Phase D:

- `sqlite_master` und relevante `PRAGMA`-Pfadteile sind gemeinsam zu nutzen
- `WITHOUT ROWID` wird explizit transportiert
- Virtual Tables werden nicht still als normale `TableDefinition`
  maskiert
- `AUTOINCREMENT` wird nur erkannt, wenn die reale Tabellendefinition dies
  traegt; `INTEGER PRIMARY KEY` allein ist nicht dasselbe
- SQLite-Typaffinitaet fuehrt nur dort zu neutralen Typen, wo die Rueckfuehrung
  fachlich belastbar ist

Kurz:

- ehrliche Notes/Skips sind besser als falsche Praezision

### 4.8 Spatial- und Extension-Faelle bleiben sichtbar statt magisch

0.5.5 hat bereits gezeigt, dass Spatial pro Dialekt stark divergiert.

Verbindliche Folge:

- PostgreSQL/PostGIS-Geometry wird als `NeutralType.Geometry` transportiert,
  benoetigte Extension- oder PostGIS-Abhaengigkeiten werden aber sichtbar
  notiert, wenn sie nicht neutral modelliert werden
- MySQL-Spatial-Typen werden korrekt rueckgefuehrt; SRID- oder serverabhaengige
  Details duerfen als Notes erscheinen, wenn das Modell sie nicht exakt traegt
- SQLite/SpatiaLite-spezifische Tabellen, Metatabellen oder Geometriepfade
  werden nicht still als normale portable Tabellen behandelt

### 4.9 Include-Flags entscheiden ueber optionale Objekte, nicht ueber Notes

Views, Procedures, Functions und Triggers sind fuer 0.6.0 optional lesbar.

Verbindlich:

- Include-Flags steuern, ob diese Objektgruppen ueberhaupt gelesen werden
- deaktivierte Objektgruppen erzeugen keine Warnungen
- aktivierte Objektgruppen duerfen bei echten Dialekt- oder
  Modellgrenzen Notes oder `skippedObjects` erzeugen
- die Semantik muss pro Dialekt gleich bleiben, auch wenn die
  Implementierungsdetails unterschiedlich sind

### 4.10 Driver-Tests bleiben dialektnah und umgehen das CLI

Phase D verifiziert die Reader auf Adapter-Ebene, nicht erst ueber spaetere
Runner.

Verbindliche Folge:

- jeder Dialekt bekommt eigene Reader-Tests
- PostgreSQL und MySQL nutzen Integrationstests gegen echte Datenbanken
- SQLite nutzt temp-file-basierte Tests
- Driver-Exposure-Tests muessen die neue `schemaReader()`-Exposition abdecken

---

## 5. Arbeitspakete

### D.1 Driver-Wiring und Exposure-Tests nachziehen

Jeder Built-in-Driver ist um `schemaReader()` zu erweitern und muss einen
produktiven Dialektreader exponieren.

Mindestens noetig:

- `PostgresDriver.schemaReader()`
- `MysqlDriver.schemaReader()`
- `SqliteDriver.schemaReader()`
- kanonische Befuellung von `SchemaDefinition.name` und
  `SchemaDefinition.version` pro Dialekt
- Exposure-Tests fuer alle drei Dialekte

Wichtig:

- SQLite hat heute keinen zusammenhaengenden Driver-Exposure-Test wie
  PostgreSQL und MySQL; Phase D soll diese Luecke schliessen
- die Driver-Exposition darf nicht auf Lazy-Stubs oder
  `TODO`-Implementierungen zeigen

### D.2 PostgreSQL-Reader implementieren

Der PostgreSQL-Pfad ist der breiteste Reverse-Slice in 0.6.0.

Mindestens umzusetzen:

- Tabellen, Spalten, Primary Keys, Foreign Keys, weitere Constraints und
  Indizes lesen
- Nullability, Defaults, einspaltige `UNIQUE`-Semantik und einspaltige
  `references` bewusst auf die Spaltenfelder rueckfuehren
- Sequenzen lesen und korrekt an Tabellen-/Spaltenbezug anbinden, soweit im
  Modell vorgesehen
- `uuid`, `json/jsonb`, `xml`, `binary`, `array` und `geometry` korrekt ins
  neutrale Modell rueckfuehren
- `ENUM`, `DOMAIN` und `COMPOSITE` lesen
- Views lesen
- Procedures, Functions und Triggers unter Include-Flags lesen

Besonders zu beachten:

- `SchemaDefinition.name` nutzt den kanonischen Scope
  `database=<db>;schema=<schema>`, `version` den festen Platzhalter
  `0.0.0-reverse`
- Ueberladene Routinen muessen auf die in Phase B festgelegten kanonischen Keys
  gemappt werden
- Partitionierungsmetadaten werden gemaess Phase-B-Modellentscheidung entweder
  modelliert oder als Note ausgegeben
- PostgreSQL-Extensions werden nicht als stiller Verlust behandelt

### D.3 MySQL-Reader implementieren

Der MySQL-Pfad muss die information_schema-Basis mit MySQL-spezifischer
Identifier- und Engine-Semantik verbinden.

Mindestens umzusetzen:

- Tabellen, Spalten, Keys, Constraints und Indizes lesen
- Nullability, Defaults, einspaltige `UNIQUE`-Semantik und einspaltige
  `references` bewusst auf die Spaltenfelder rueckfuehren
- Tabellen-Engine lesen und ins compare-relevante Tabellenmetadatenfeld
  ueberfuehren
- `AUTO_INCREMENT` erkennen
- `json`, `enum`, Spatial-Typen und weitere neutral-relevante Typen rueckfuehren
- Views, Procedures, Functions und Triggers unter Include-Flags lesen

Besonders zu beachten:

- `SchemaDefinition.name` nutzt den kanonischen Scope `database=<db>`,
  `version` den festen Platzhalter `0.0.0-reverse`
- `lower_case_table_names` darf Lookup und Rueckgabe nicht inkonsistent machen
- `SET` und andere nicht exakt neutrale Faelle muessen sichtbar notiert werden
- MySQL darf nicht aus Convenience alles auf `TEXT` oder `JSON` flatten und
  dabei Information verstecken

### D.4 SQLite-Reader implementieren

SQLite braucht den ehrlichsten Reverse-Pfad, weil Typaffinitaet und
`sqlite_master`-SQL schnell zu falscher Sicherheit fuehren.

Mindestens umzusetzen:

- Tabellen, Spalten, Primary Keys, Foreign Keys, weitere Constraints und
  Indizes ueber `sqlite_master` plus `PRAGMA` lesen
- Nullability, Defaults, einspaltige `UNIQUE`-Semantik und einspaltige
  `references` bewusst auf die Spaltenfelder rueckfuehren, soweit SQLite dies
  belastbar hergibt
- `WITHOUT ROWID` erkennen und transportieren
- `AUTOINCREMENT` nur ueber reale Tabellendefinition und nicht nur ueber
  Affinitaet erkennen
- Views und Triggers unter Include-Flags lesen

Besonders zu beachten:

- `SchemaDefinition.name` nutzt den kanonischen Scope `schema=<schema>`,
  `version` den festen Platzhalter `0.0.0-reverse`
- Virtual Tables werden nicht still als portable Basistabellen gemappt
- SpatiaLite- oder geometry-relevante Sonderfaelle erscheinen sichtbar als
  Notes oder `skippedObjects`
- SQLite-spezifische Typen duerfen nur mit belastbarer Herleitung als
  neutraler Typ ausgegeben werden

### D.5 Dialektnahe Reader-Tests aufbauen

Phase D braucht neue Testsaetze fuer alle drei Reader.

Mindestens abzudecken:

- Core-Read fuer Basisschema mit Tabellen, Keys und Indizes
- kanonische Befuellung von `SchemaDefinition.name` und `version`
- Spaltenabbildung fuer `required`, `default`, `unique` und `references`
- Notes-/Skip-Pfade fuer nicht exakt neutrale oder bewusst ausgelassene Faelle
- Include-Flags fuer Views / Procedures / Functions / Triggers
- Ownership: Connection wird nach dem Read wieder freigegeben
- keine stillen Verluste bei verpflichtenden Kernobjekten

Dialektspezifisch zusaetzlich:

- PostgreSQL:
  - Sequenzen
  - `ENUM` / `DOMAIN` / `COMPOSITE`
  - Geometry / PostGIS-bezogene Notes
- MySQL:
  - Engine
  - `AUTO_INCREMENT`
  - `lower_case_table_names`
  - `SET`-Note oder gleichwertiger Nicht-Neutralitaetspfad
- SQLite:
  - `WITHOUT ROWID`
  - `AUTOINCREMENT`
  - Virtual Table / SpatiaLite-Note bzw. Skip

---

## 6. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren sind voraussichtlich:

- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDriver.kt`
- neuer `PostgresSchemaReader` und zugehoerige Reader-Helfer unter
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/...`
- vorhandene PostgreSQL-Helfer wie:
  - `PostgresIdentifiers.kt`
  - `PostgresTableLister.kt`
  - `PostgresSchemaSync.kt`
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/...`

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDriver.kt`
- neuer `MysqlSchemaReader` und zugehoerige Reader-Helfer unter
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/...`
- vorhandene MySQL-Helfer wie:
  - `MysqlIdentifiers.kt`
  - `MysqlTableLister.kt`
  - `MysqlSchemaSync.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/...`

- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDriver.kt`
- neuer `SqliteSchemaReader` und zugehoerige Reader-Helfer unter
  `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/...`
- vorhandene SQLite-Helfer wie:
  - `SqliteTableLister.kt`
  - `SqliteSchemaSync.kt`
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/...`

Bereits aus Phase B/C vorgegeben und hier nur als Integrationsgrenze relevant:

- `hexagon/ports/.../SchemaReader`
- `hexagon/ports/.../SchemaReadOptions`
- `hexagon/ports/.../SchemaReadResult`
- gemeinsame JDBC-Metadatenprojektionen in `adapters:driven:driver-common`

Bewusst nicht direkt betroffen:

- Reverse-Report-Writer in `adapters:driven:formats`
- `schema reverse`-CLI in `hexagon:application` bzw. `adapters:driving:cli`

---

## 7. Akzeptanzkriterien

Phase D ist nur abgeschlossen, wenn alle folgenden Punkte erfuellt sind:

- Alle drei Built-in-Dialekte exponieren einen produktiven `SchemaReader`.
- PostgreSQL, MySQL und SQLite liefern fuer ein Basisschema jeweils ein
  gueltiges `SchemaReadResult` mit Tabellen, Spalten, Keys, Constraints und
  Indizes.
- Alle drei Dialekte befuellen `SchemaDefinition.name` kanonisch aus dem
  gelesenen DB-Scope und verwenden fuer `version` einheitlich
  `0.0.0-reverse`.
- `required`, `default`, `unique` und `references` werden pro Dialekt bewusst
  transportiert; mehrspaltige `UNIQUE`- und Foreign-Key-Beziehungen bleiben
  konsistent auf Constraint-Ebene.
- Fehler beim Lesen von Kernobjekten fuehren zu einem klaren Driver-Fehler und
  nicht zu einem scheinbar erfolgreichen, aber unvollstaendigen Reverse.
- Optionalobjekte (`views`, `procedures`, `functions`, `triggers`) werden nur
  bei aktivem Include-Flag gelesen.
- PostgreSQL liest Sequenzen sowie `ENUM`, `DOMAIN` und `COMPOSITE` und mappt
  Routinen/Trigger auf die in Phase B festgelegten kanonischen Keys.
- MySQL liest Tabellen-Engine und `AUTO_INCREMENT` bewusst ein und behandelt
  `SET` bzw. sonstige nicht exakt neutrale Faelle sichtbar ueber Notes.
- SQLite liest `WITHOUT ROWID` bewusst ein und behandelt Virtual Tables sowie
  SpatiaLite-/Geometry-Sonderfaelle sichtbar ueber Notes oder `skippedObjects`.
- `uuid`, `json`, `binary`, `array` und `geometry` werden pro Dialekt korrekt
  transportiert oder als bewusst nicht exakt neutral sichtbar gemacht.
- Kein Dialekt verwendet eine blinde Generator-Inversion als primaeres
  Reverse-Verfahren.
- Die Reader geben geliehene Connections nach dem Read wieder frei.

---

## 8. Verifikation

Mindestens auszufuehren:

- `./gradlew :adapters:driven:driver-postgresql:test -PintegrationTests`
- `./gradlew :adapters:driven:driver-mysql:test -PintegrationTests`
- `./gradlew :adapters:driven:driver-sqlite:test`
- `./gradlew :adapters:driven:driver-common:test`
- `./gradlew :hexagon:core:test`

Gezielt zu pruefen ist dabei:

- neue Driver-Exposure-Tests fuer `schemaReader()`
- explizite Reader-Tests fuer den kanonischen `SchemaDefinition.name`- und
  `version`-Vertrag
- PostgreSQL-Integrationstests fuer:
  - Basistabellen
  - Nullability / Defaults / einspaltige `UNIQUE` / einspaltige Foreign Keys
  - Sequenzen
  - Custom Types
  - Views / Functions / Procedures / Triggers unter Include-Flags
- MySQL-Integrationstests fuer:
  - Basistabellen inkl. `required` / `default` / `unique` / `references`
  - Engine
  - `AUTO_INCREMENT`
  - `lower_case_table_names`
  - Views / Routinen / Trigger
  - `SET`-/Nicht-Neutralitaets-Notes
- SQLite-Tests fuer:
  - Basistabellen inkl. `required` / `default` / `unique` / `references`
  - `WITHOUT ROWID`
  - `AUTOINCREMENT`
  - Virtual Tables
  - Views / Triggers
  - SpatiaLite-/Geometry-Notes oder Skips
- Ownership-Tests: nach jedem Read sind weitere Pool-Borrows moeglich

Manuelle Dialekt-Smokes nach Implementierung:

1. PostgreSQL-Testschema mit Sequence, Enum und View ruecklesen
2. MySQL-Testschema mit Engine, `AUTO_INCREMENT` und Spatial-Spalte ruecklesen
3. SQLite-Testschema mit `WITHOUT ROWID` und Trigger ruecklesen

---

## 9. Risiken und offene Punkte

### R1 - Reverse wird halb implizit aus Generator-Logik abgeleitet

Dann gehen gerade die Faelle verloren, die 0.6.0 sichtbar machen soll:
Custom Types, Engine, `WITHOUT ROWID`, `SET`, Virtual Tables oder Extensions.

### R2 - PostgreSQL wird auf `information_schema` kastriert

Wenn Sequenzen, Routinen, Trigger oder Composite-/Domain-Typen nicht gezielt
ueber PostgreSQL-Systemkataloge gelesen werden, bleibt der Reverse-Pfad zu
schmal.

### R3 - MySQL-Case-Semantik wird nicht stabil behandelt

`lower_case_table_names` und information_schema-Normalisierung koennen leicht
zu stillen Lookup-Fehlern oder falschen Rueckgaben fuehren.

### R4 - SQLite behauptet mehr Praezision als die Metadaten hergeben

Wenn Typaffinitaet, `INTEGER PRIMARY KEY` und `AUTOINCREMENT` oder normale
Tabellen und Virtual Tables nicht sauber getrennt werden, entsteht ein
irrefuehrender Reverse-Pfad.

### R5 - Optionalobjekte werden bei Fehlern still fallengelassen

Include-Flags duerfen nicht dazu fuehren, dass aktiv angeforderte Objektgruppen
bei Problemen einfach verschwinden, ohne dass Notes oder `skippedObjects`
entstehen.

### R6 - Spatial-Faelle werden pro Dialekt inkonsistent sichtbar

Gerade Geometry-/Extension-/SpatiaLite-Faelle muessen ueber alle Dialekte
hinweg nach demselben Grundsatz behandelt werden:

- ehrlich lesen, wenn neutral modellierbar
- sonst sichtbar notieren oder skippen

### R7 - Driver-Tests bleiben zu schmal

Wenn Phase D nur Basistabellen prueft, fallen die eigentlichen 0.6.0-Risiken
bei Sequenzen, Custom Types, Engine, `WITHOUT ROWID`, Virtual Tables und
Routinen spaeter im CLI-Pfad auf.

---

## 10. Abschlussdefinition

Phase D ist abgeschlossen, wenn:

- PostgreSQL, MySQL und SQLite jeweils einen produktiven `SchemaReader`
  liefern,
- die verpflichtenden Kernobjekte stabil gelesen werden,
- dialektspezifische Zusatzobjekte und Nicht-Neutralitaeten sichtbar ueber
  Modellfelder, Notes oder `skippedObjects` transportiert werden,
- und die neuen Reader ueber dialektnahe Tests ohne CLI abgesichert sind.

Danach koennen Phase E den `schema reverse`-CLI-Pfad und Phase F den
DB-Operand-Compare auf Readern aufbauen, die nicht mehr nur eine
Dokufiktion sind, sondern reale Dialektimplementierungen.
