# Implementierungsplan: Phase E - Dialektimplementierung

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: E (Dialektimplementierung)
> **Status**: Draft (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.5.5.md` Abschnitt 2,
> Abschnitt 4.5 bis 4.8, Abschnitt 5 Phase E, Abschnitt 6.4,
> Abschnitt 7, Abschnitt 8, Abschnitt 10; `docs/ImpPlan-0.5.5-D.md`;
> `docs/change-request-spatial-types.md`; `docs/ddl-generation-rules.md`;
> `docs/cli-spec.md`

---

## 1. Ziel

Phase E implementiert die eigentliche Spatial-DDL pro unterstuetztem Dialekt.
Nach Abschluss der Phase koennen PostgreSQL/PostGIS, MySQL und
SQLite/SpatiaLite `NeutralType.Geometry` gemaess den freigegebenen 0.5.5-
Regeln generieren, einschliesslich Dialekt-spezifischer Rollback- und
Hinweispfade.

Die Phase baut inhaltlich auf Phase D auf: das Spatial-Profil und der
generatorseitige Output-Vertrag (`E052`, `W120`) muessen bereits ueber den
Port-/CLI-Pfad transportierbar sein. Phase E fuehrt selbst keine CLI-Defaults,
keine Profilmatrix und keine neuen Validierungsregeln ein.

Nach Phase E soll klar und testbar gelten:

- PostgreSQL erzeugt mit Profil `postgis` PostGIS-kompatible
  `geometry(<Type>, <SRID>)`-Spalten
- PostgreSQL blockiert mit Profil `none` die gesamte betroffene Tabelle mit
  `E052`, statt partielle Tabellen-DDL zu emittieren
- MySQL bildet `geometry_type` auf native Spatial-Typen ab und behandelt SRID
  als best effort mit `W120`, falls noetig
- SQLite erzeugt mit Profil `spatialite` den zweistufigen
  `CREATE TABLE`-/`AddGeometryColumn(...)`-Pfad
- SQLite blockiert mit Profil `none` die gesamte betroffene Tabelle mit `E052`
- Spatial-Rollback ist fuer alle drei Dialekte konsistent; insbesondere nutzt
  SQLite einen expliziten `DiscardGeometryColumn(...)`-Pfad
- bestehende nicht-spatiale Typen (`uuid`, `json`, `binary`, `array`) werden
  beim Umbau nicht verschlechtert

---

## 2. Ausgangslage

Aktueller Stand in `hexagon:core`, `adapters:driven:formats` und den
Treiberadaptern:

- Das Core-Modell kennt lokal bereits `NeutralType.Geometry`.
- `GeometryType` existiert lokal als kanonischer Werttyp fuer
  `geometry_type`.
- `YamlSchemaCodec` liest lokal bereits:
  - `type: geometry`
  - `geometry_type`
  - `srid`
- Spatial-Schemata koennen damit heute bereits bis in den Generatorpfad
  gelangen.
- `DdlGenerator` akzeptiert seit Phase D einen typisierten Optionssatz:
  - `generate(schema, options: DdlGenerationOptions)`
  - `generateRollback(schema, options: DdlGenerationOptions)`
- `SpatialProfile` wird ueber `SpatialProfilePolicy` zentral aufgeloest und
  erreicht die Generatoren als bereits verifizierter Wert.
- `AbstractDdlGenerator.generateRollback(...)` erzeugt den Down-Pfad weiterhin
  generisch ueber `invertStatement(...)` aus den Up-Statements.
- Dieser generische Invertierungsmechanismus kennt heute keine Spatial-
  Spezialfaelle wie `SELECT AddGeometryColumn(...)`.
- Alle drei aktuellen TypeMapper enthalten fuer `NeutralType.Geometry` noch
  ein explizites `TODO(...)`:
  - `PostgresTypeMapper`
  - `MysqlTypeMapper`
  - `SqliteTypeMapper`
- Die TODO-Meldungen nennen noch "Phase C/D", sind fachlich aber jetzt
  eindeutig Phase E.
- `PostgresDdlGenerator`, `MysqlDdlGenerator` und `SqliteDdlGenerator`
  generieren Tabellen heute weiterhin ueber ihre bestehenden
  `generateTable(...)`-/`generateColumnSql(...)`-Pfade ohne Spatial-
  Spezialbehandlung.
- Die Generatoren besitzen bereits etablierte Muster fuer generatorseitige
  Hinweise:
  - `E052` als `TransformationNote`
  - `SkippedObject` fuer bewusst uebersprungene Objekte
- `DdlGoldenMasterTest` laeuft derzeit nur ueber:
  - `minimal`
  - `e-commerce`
  - `all-types`
  - `full-featured`
- Der Golden-Master-Loop kennt aktuell noch kein Spatial-Fixture.
- Es existieren bereits umfangreiche treiberspezifische Generator-Tests fuer
  PostgreSQL, MySQL und SQLite, aber noch keine Spatial-Faelle.

Konsequenz fuer Phase E:

- Spatial ist im Modell und Read-Pfad bereits sichtbar, scheitert generatorseitig
  aber derzeit spaet am TypeMapper-`TODO`.
- Ohne Phase-D-Optionsvertrag wuerden die Dialekte ihr Profilwissen hart
  kodieren oder muesten unzulaessig selbst defaulten.
- PostgreSQL und MySQL koennen Spatial nicht allein ueber
  `TypeMapper.toSql(...)` abbilden, wenn generatorseitige Notes oder
  Tabellenblockierung erforderlich sind.
- SQLite kann Spatial nicht mit dem bestehenden generischen Rollback und nicht
  mit einem einfachen Column-Type-Mapping allein abdecken.

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Dialektimplementierung fuer `NeutralType.Geometry` in:
  - PostgreSQL / PostGIS
  - MySQL / native Spatial Types
  - SQLite / SpatiaLite
- PostGIS-Schreibweise der Geometry-Typnamen
- MySQL-Mapping auf native Spatial-Typen
- SQLite-`AddGeometryColumn(...)`-Strategie
- generatorseitige Tabellenblockierung fuer PostgreSQL/SQLite mit Profil
  `none`
- generatorseitige `E052`-/`W120`-Erzeugung gemaess DDL-Regeln
- Spatial-spezifische Rollback-Logik pro Dialekt
- gezielte Regression der bestehenden nicht-spatialen Typmappings in den
  betroffenen Treibern
- minimal notwendige Generator-/Helper-Anpassungen in `driver-common`, wenn
  der gemeinsame Rollback- oder Table-Scan-Pfad erweitert werden muss

### 3.2 Bewusst nicht Teil von Phase E

- CLI-Parsing, Profildefaults und Nutzungsfehler fuer `--spatial-profile`
- neue Schema- oder Modellvalidierungsregeln
- `geography`
- `z`- oder `m`-Dimensionen
- automatische Pruefung, ob PostGIS oder SpatiaLite im Zielsystem installiert
  sind
- breiter Golden-Master-Ausbau und die komplette Spatial-Testmatrix aus
  Phase F
- neues generisches Cross-Dialect-Spatial-Framework in `driver-common`, wenn
  der Mehrwert nicht klar belegt ist

---

## 4. Architekturentscheidungen

### 4.1 Phase E konsumiert den Profilvertrag aus Phase D, erfindet ihn aber nicht neu

Die Dialektgeneratoren duerfen das Spatial-Profil nicht selbst aus dem
Zieldialekt ableiten. Sie konsumieren einen bereits aufgeloesten
Optionsvertrag aus Phase D.

Verbindliche Folge:

- PostgreSQL implementiert `postgis` und `none`
- MySQL implementiert nur `native`
- SQLite implementiert `spatialite` und `none`
- unzulaessige Profil-/Dialekt-Kombinationen erreichen die Generatoren nicht

Wichtig fuer MySQL:

- Phase E implementiert **keinen** generatorinternen `none`-Pfad fuer MySQL
- Profilfehler fuer MySQL gehoeren in Phase D, nicht in den Dialektgenerator

### 4.2 Spatial-Logik bleibt primaer dialektnah

Phase E fuehrt nicht reflexhaft einen grossen gemeinsamen Spatial-Unterbau in
`driver-common` ein.

Begruendung:

- PostgreSQL nutzt PostGIS-CamelCase in `geometry(<Type>, <SRID>)`
- MySQL nutzt native OGC-Typnamen in Grossbuchstaben
- SQLite nutzt `AddGeometryColumn(...)` statt Inline-Spaltentypen
- Rollback ist zwischen PostgreSQL/MySQL und SQLite grundverschieden

Zulaessig ist nur ein kleiner gemeinsamer Helper, wenn er wirklich
dialektunabhaengig bleibt, z. B.:

- Erkennung, ob eine Tabelle Geometry-Spalten enthaelt
- kleines Datenmodell fuer Spatial-Spalten einer Tabelle
- helper fuer explizite Spatial-Rollback-Statements

### 4.3 PostgreSQL integriert Spatial als echten Spaltentyp, nicht als Nebenstatement

Mit Profil `postgis` wird `geometry` direkt im `CREATE TABLE` abgebildet:

- Syntax: `geometry(<GeometryType>, <SRID>)`
- Typname in PostGIS-Schreibweise:
  - `geometry` -> `Geometry`
  - `point` -> `Point`
  - `polygon` -> `Polygon`
  - `multipolygon` -> `MultiPolygon`
- fehlt `srid`, wird `0` verwendet

Mit Profil `none` wird die gesamte Tabelle blockiert:

- keine partielle `CREATE TABLE`-DDL ohne Spatial-Spalte
- `E052` als `action_required`-Note auf Tabellenebene
- `SkippedObject` fuer die Tabelle
- andere Tabellen laufen normal weiter

Phase E fuehrt fuer PostgreSQL bewusst **kein** automatisches
`CREATE EXTENSION IF NOT EXISTS postgis;` ein. Ein optionaler Info-Hinweis
bleibt der einzig zulaessige 0.5.5-Pfad.

### 4.4 MySQL bildet Spatial direkt auf native Typen ab und behandelt SRID als best effort

Mit Profil `native` mappt MySQL:

- `geometry` -> `GEOMETRY`
- `point` -> `POINT`
- `linestring` -> `LINESTRING`
- `polygon` -> `POLYGON`
- `multipoint` -> `MULTIPOINT`
- `multilinestring` -> `MULTILINESTRING`
- `multipolygon` -> `MULTIPOLYGON`
- `geometrycollection` -> `GEOMETRYCOLLECTION`

Wichtig:

- MySQL bekommt keinen Tabellenblockierungs-Pfad ueber `none`
- fehlende oder nicht sauber ausdrueckbare SRID fuehrt nicht zu `E052`,
  sondern zu DDL best effort plus `W120`, falls die SRID nicht exakt
  uebertragbar ist

Damit bleibt die Semantik klar:

- Profil-/Dialektfehler -> Phase D
- generatorseitige SRID-Grenze -> Phase E mit `W120`

### 4.5 SQLite nutzt mit `spatialite` immer den zweistufigen Registrierungspfad

SQLite/SpatiaLite erzeugt Geometry-Spalten nicht als normalen Inline-Typ in
`CREATE TABLE`, sondern ueber:

1. `CREATE TABLE` ohne Geometry-Spalten
2. danach pro Geometry-Spalte `SELECT AddGeometryColumn(...)`

Verbindliche Regeln:

- `geometry_type` wird fuer `AddGeometryColumn(...)` in Grossbuchstaben
  emittiert
- `coord_dimension` bleibt in Phase 1 immer `'XY'`
- fehlt `srid`, wird `0` verwendet
- Geometry-bezogene Spalten- und Tabellenmetadaten duerfen nicht still
  verschwinden. Das betrifft mindestens:
  - `required`
  - `unique`
  - `default`
  - `references`
  - `primary_key`
  - `constraints`
  - `indices`
- Fuer 0.5.5 muss fuer jeden dieser Faelle explizit festgelegt werden:
  - entweder die Semantik wird im SpatiaLite-Pfad korrekt getragen
  - oder die gesamte Tabelle wird mit `E052` blockiert
- stilles Weglassen einzelner Eigenschaften oder tabellenweiter Artefakte ist
  kein zulaessiger Phase-E-Pfad

Mit Profil `none` wird auch in SQLite die gesamte Tabelle blockiert:

- kein stiller Fallback auf `TEXT`
- keine partielle Tabellen-DDL ohne Spatial-Spalte
- `E052` als `action_required`-Note auf Tabellenebene

### 4.6 Spatial-Rollback wird explizit modelliert, nicht dem generischen Inverter ueberlassen

Der aktuelle generische Rollback aus `AbstractDdlGenerator.invertStatement(...)`
reicht fuer Spatial nicht aus.

Verbindliche Zielrichtung:

- PostgreSQL und MySQL brauchen vor der Implementierung eine explizite,
  widerspruchsfreie Festlegung ihres kanonischen Rollback-Verhaltens fuer
  Geometry-Spalten im normalen `CREATE TABLE`-Pfad
- die aktuell widerspruechlichen Hinweise zwischen spaltenbezogenen
  Rollback-Beispielen und tabellenbezogener Spatial-Rollback-Zusammenfassung
  duerfen nach Phase E nicht parallel stehen bleiben
- SQLite braucht fuer `AddGeometryColumn(...)` einen expliziten
  `DiscardGeometryColumn(...)`-Rollback
- diese `DiscardGeometryColumn(...)`-Statements muessen vor dem
  `DROP TABLE` kommen

Phase E darf deshalb nicht stillschweigend darauf vertrauen, dass
`invertStatement(...)` unbekannte Spatial-Statements irgendwann zufaellig
richtig behandelt. Fuer PostgreSQL/MySQL darf Phase E ausserdem keinen
Rollback-Pfad als Abnahmewahrheit festschreiben, solange die kanonische
Semantik nicht gegen die bestehende DDL-Dokumentation bereinigt ist.

### 4.7 Nicht-spatiale Typvertraege bleiben stabil

Phase E fasst die TypeMapper und Table-Builder in allen drei Treibern an. Dabei
duerfen die heute bereits dokumentierten Nicht-Spatial-Mappings nicht kippen.

Besonders sensibel sind:

- PostgreSQL:
  - `uuid` -> `UUID`
  - `json` -> `JSONB`
  - `binary` -> `BYTEA`
  - `array` -> `<type>[]`
- MySQL:
  - `uuid` -> `CHAR(36)`
  - `json` -> `JSON`
  - `binary` -> `BLOB`
  - `array` -> `JSON`
- SQLite:
  - `uuid` -> `TEXT`
  - `json` -> `TEXT`
  - `binary` -> `BLOB`
  - `array` -> `TEXT`

---

## 5. Arbeitspakete

### E.1 PostgreSQL / PostGIS implementieren

In `adapters:driven:driver-postgresql` sind Spatial-Regeln fuer
`NeutralType.Geometry` zu implementieren.

Mindestens erforderlich:

- PostGIS-CamelCase-Mapping fuer alle zulaessigen `geometry_type`-Werte
- `geometry(<Type>, <SRID>)` als Spalten-DDL
- Default-Handling fuer:
  - `geometry_type` -> `Geometry`
  - fehlende `srid` -> `0`
- optionale Info-Note zur PostGIS-Abhaengigkeit

Mit Profil `none` zusaetzlich:

- Geometry-Spalten einer Tabelle frueh erkennen
- gesamte Tabelle blockieren
- `E052` als `action_required`-Note und `SkippedObject` auf Tabellenebene
  erzeugen
- keine partielle Tabellen-DDL ohne Geometry-Spalte erzeugen

### E.2 MySQL / native Spatial implementieren

In `adapters:driven:driver-mysql` ist `NeutralType.Geometry` auf native
Spatial-Types abzubilden.

Mindestens erforderlich:

- Mapping aller erlaubten Geometry-Typen auf die nativen MySQL-Typnamen
- SRID-Handling gemaess dokumentiertem Best-Effort-Pfad
- `W120`, wenn SRID nicht sauber im gewaehlten DDL-Stil transportierbar ist

Wichtig:

- MySQL darf fuer validen Geometry-Input nicht mehr am TypeMapper-`TODO`
  scheitern
- Profilwissen bleibt auf `native` beschraenkt; ein `none`-Pfad wird hier
  nicht lokal nachgebaut

### E.3 SQLite / SpatiaLite implementieren

In `adapters:driven:driver-sqlite` ist der zweistufige Spatial-Pfad zu
implementieren.

Mindestens erforderlich:

- Geometry-Spalten vor dem `CREATE TABLE` separieren
- `CREATE TABLE` ohne Geometry-Spalten erzeugen
- danach je Geometry-Spalte `SELECT AddGeometryColumn(...)` emittieren
- `geometry_type` in Grossbuchstaben fuer `AddGeometryColumn(...)` abbilden
- `coord_dimension` fest auf `'XY'` setzen
- fehlende `srid` als `0` schreiben
- Geometry-bezogene Spalten- und Tabellenmetadaten explizit behandeln:
  - `required`
  - `unique`
  - `default`
  - `references`
  - `primary_key`
  - `constraints`
  - `indices`
- fuer diese Faelle entweder korrekte SpatiaLite-Semantik implementieren oder
  die gesamte Tabelle mit `E052` blockieren
- keine Geometry-bezogenen Eigenschaften oder Artefakte stillschweigend
  wegwerfen

Mit Profil `none` zusaetzlich:

- gesamte Tabelle blockieren
- `E052` als `action_required`-Note und `SkippedObject` auf Tabellenebene
  erzeugen
- keine partielle Tabellen-DDL ohne Geometry-Spalte erzeugen

### E.4 Spatial-Rollback pro Dialekt sauber ziehen

Die Rollback-Implementierung ist generatorseitig mit den neuen Spatial-
Statements zu synchronisieren.

Mindestens erforderlich:

- PostgreSQL:
  - kanonische Rollback-Semantik fuer Geometry-Spalten explizit festlegen
  - Implementierung, Tests und DDL-Dokumentation auf dieselbe Semantik ziehen
- MySQL:
  - kanonische Rollback-Semantik fuer Geometry-Spalten explizit festlegen
  - Implementierung, Tests und DDL-Dokumentation auf dieselbe Semantik ziehen
- SQLite:
  - `AddGeometryColumn(...)` bekommt explizit
    `DiscardGeometryColumn(...)`
  - die `DiscardGeometryColumn(...)`-Statements werden in umgekehrter
    Spaltenreihenfolge vor `DROP TABLE` emittiert

Wichtig:

- fuer PostgreSQL/MySQL darf nach Phase E nicht mehr gleichzeitig ein
  spaltenbezogener und ein tabellenbezogener Spatial-Rollback als
  gleichwertige Doku-Wahrheit stehen bleiben

### E.5 Regression der bestehenden Typmappings und Treiberpfade

Beim Spatial-Umbau sind die betroffenen Treiber auf bestehende Typvertraege
gegenzupruefen.

Mindestens noetig:

- Regression fuer `uuid`, `json`, `binary`, `array` in allen drei Treibern
- keine unbeabsichtigte Aenderung der bisherigen `E052`-Muster fuer andere
  unsupported Features
- keine stillen Fallbacks auf `TEXT`, `BLOB` oder generische
  Nicht-Spatial-Typen fuer `geometry`

---

## 6. Technische Zielstruktur

Eine moegliche Minimalform fuer PostgreSQL ist:

```kotlin
private fun postgresGeometrySql(type: NeutralType.Geometry): String {
    val pgType = when (type.geometryType.schemaName) {
        "geometry" -> "Geometry"
        "point" -> "Point"
        "linestring" -> "LineString"
        "polygon" -> "Polygon"
        "multipoint" -> "MultiPoint"
        "multilinestring" -> "MultiLineString"
        "multipolygon" -> "MultiPolygon"
        "geometrycollection" -> "GeometryCollection"
        else -> error("validator should have rejected unknown geometry_type")
    }
    val srid = type.srid ?: 0
    return "geometry($pgType, $srid)"
}
```

Eine moegliche Minimalform fuer MySQL ist:

```kotlin
private fun mysqlGeometrySql(type: NeutralType.Geometry): Pair<String, TransformationNote?> {
    val baseType = when (type.geometryType.schemaName) {
        "geometry" -> "GEOMETRY"
        "point" -> "POINT"
        "linestring" -> "LINESTRING"
        "polygon" -> "POLYGON"
        "multipoint" -> "MULTIPOINT"
        "multilinestring" -> "MULTILINESTRING"
        "multipolygon" -> "MULTIPOLYGON"
        "geometrycollection" -> "GEOMETRYCOLLECTION"
        else -> error("validator should have rejected unknown geometry_type")
    }

    return if (type.srid == null) {
        baseType to null
    } else {
        // Exact SRID support or best effort + W120, je nach gewaehltem Stil.
        "$baseType SRID ${type.srid}" to null
    }
}
```

Eine moegliche Minimalform fuer SQLite ist:

```kotlin
data class SpatialColumnPlan(
    val name: String,
    val geometryType: String,
    val srid: Int,
)

private fun generateSpatialiteTable(
    tableName: String,
    table: TableDefinition,
): List<DdlStatement> {
    val normalColumns = ...
    val spatialColumns = ...

    return listOf(
        createTableWithoutSpatialColumns(tableName, normalColumns),
        *spatialColumns.map {
            DdlStatement(
                "SELECT AddGeometryColumn('$tableName', '${it.name}', ${it.srid}, '${it.geometryType}', 'XY');"
            )
        }.toTypedArray(),
    )
}
```

Wichtiger als die exakte Kotlin-Form sind folgende Zielsemantiken:

- Geometry-Mapping ist fuer jeden Dialekt explizit und nicht mehr `TODO`
- PostgreSQL und SQLite koennen eine gesamte Tabelle blockieren, bevor eine
  partielle `CREATE TABLE`-DDL entsteht
- SQLite-Rollback kennt `DiscardGeometryColumn(...)` explizit
- SQLite verliert bei Geometry-bezogenen Defaults, FKs, PKs, Constraints oder
  Indizes keine Semantik stillschweigend; unsupported Kombinationen blockieren
  stattdessen die ganze Tabelle mit `E052`
- Dialektgeneratoren verlassen sich bei validem Input nicht auf
  "sollte nie passieren"-Freitextpfade, sondern auf den bereits validierten
  Geometry-Vertrag

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/AbstractDdlGeneratorTest.kt`
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGeneratorTest.kt`

Indirekt betroffen:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `docs/ImpPlan-0.5.5-D.md`, weil Phase E den dort definierten Profil- und
  Outputvertrag konsumiert
- `docs/ddl-generation-rules.md`, falls Rollback- oder SQLite-Spatialregeln
  gegen die Implementierung bereinigt werden muessen
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`,
  falls Signatur- oder Hilfsanpassungen aus Phase D/E den Aufruf der
  Generatoren beruehren
- Phase F, die Spatial-Golden-Master und die breite Testmatrix nachzieht

---

## 8. Akzeptanzkriterien

- [ ] Keiner der drei Spatial-Typmapper/Generatoren enthaelt fuer
      `NeutralType.Geometry` mehr ein produktives `TODO(...)`.
- [ ] PostgreSQL generiert mit Profil `postgis`
      `geometry(<PostGIS-Type>, <SRID>)`.
- [ ] PostgreSQL verwendet die PostGIS-Schreibweise:
      `Point`, `LineString`, `Polygon`, `MultiPolygon`, etc.
- [ ] PostgreSQL verwendet bei fehlender `srid` den Wert `0`.
- [ ] PostgreSQL erzeugt kein automatisches `CREATE EXTENSION ... postgis`.
- [ ] PostgreSQL kann optional einen Info-Hinweis zur PostGIS-Abhaengigkeit
      erzeugen, ohne die DDL zu blockieren.
- [ ] PostgreSQL blockiert mit Profil `none` die gesamte betroffene Tabelle.
- [ ] PostgreSQL erzeugt mit Profil `none` keine partielle Tabellen-DDL ohne
      Geometry-Spalte.
- [ ] PostgreSQL liefert fuer die blockierte Tabelle `E052` als
      `action_required`-Note plus `SkippedObject`.
- [ ] MySQL bildet alle erlaubten `geometry_type`-Werte auf native
      Spatial-Typen ab.
- [ ] MySQL erzeugt bei sauber ausdrueckbarer SRID ein konsistentes
      SRID-Resultat im DDL.
- [ ] MySQL erzeugt bei nicht sauber ausdrueckbarer SRID DDL best effort plus
      `W120`, statt den Vorgang mit `E052` abzubrechen.
- [ ] MySQL implementiert keinen lokalen `none`-Profilpfad fuer Spatial.
- [ ] SQLite erzeugt mit Profil `spatialite` ein `CREATE TABLE` ohne
      Geometry-Spalten plus anschliessende `AddGeometryColumn(...)`-Statements.
- [ ] SQLite verwendet in `AddGeometryColumn(...)` Grossbuchstaben fuer den
      Geometry-Typ und `'XY'` als `coord_dimension`.
- [ ] SQLite verwendet bei fehlender `srid` den Wert `0`.
- [ ] SQLite behandelt Geometry-bezogene Eigenschaften und Artefakte
      (`required`, `unique`, `default`, `references`, `primary_key`,
      `constraints`, `indices`) explizit; unsupported Faelle werden nicht
      still fallengelassen.
- [ ] SQLite blockiert mit Profil `none` die gesamte betroffene Tabelle.
- [ ] SQLite erzeugt mit Profil `none` keine partielle Tabellen-DDL ohne
      Geometry-Spalte.
- [ ] SQLite liefert fuer die blockierte Tabelle `E052` als
      `action_required`-Note plus `SkippedObject`.
- [ ] SQLite-Rollback emittiert fuer Spatial-Spalten explizit
      `DiscardGeometryColumn(...)` vor `DROP TABLE`.
- [ ] PostgreSQL und MySQL besitzen je eine explizit festgelegte,
      widerspruchsfreie Rollback-Semantik fuer Geometry-Spalten; Implementierung,
      Tests und DDL-Doku verweisen auf denselben kanonischen Pfad.
- [ ] Bei validem Spatial-Input scheitert die Generierung nicht mehr an einem
      TypeMapper-`TODO`.
- [ ] Bestehende Nicht-Spatial-Mappings fuer `uuid`, `json`, `binary` und
      `array` bleiben in allen drei Dialekten fachlich stabil.

---

## 9. Verifikation

Phase E wird primaer ueber treibernahe Generator- und Rollback-Tests
verifiziert.

Mindestumfang:

1. Gemeinsamer DDL-Unterbau und alle drei Dialektgeneratoren:

```bash
./gradlew \
  :adapters:driven:driver-common:test \
  :adapters:driven:driver-postgresql:test \
  :adapters:driven:driver-mysql:test \
  :adapters:driven:driver-sqlite:test
```

2. Falls Phase D und E in derselben technischen Aenderung zusammenlaufen oder
   Generator-Signaturen/Helper den Golden-Master-Aufruf beruehren:

```bash
./gradlew :adapters:driven:formats:test :adapters:driving:cli:test
```

3. Inhaltliche Gegenpruefung der Tests auf folgende Faelle:

- PostgreSQL:
  - `geometry(point, 4326)` wird zu `geometry(Point, 4326)`
  - `geometry` ohne `srid` wird zu `geometry(Geometry, 0)`
  - Profil `none` blockiert die gesamte Tabelle mit `E052` als
    `action_required`-Note plus `SkippedObject`
- MySQL:
  - jeder erlaubte `geometry_type` ergibt den erwarteten nativen Typ
  - SRID-Best-Effort fuehrt bei Bedarf zu `W120`, nicht zu `E052`
  - der gewaehlte Rollback-Pfad fuer Geometry-Spalten ist in Tests und Doku
    konsistent
- SQLite:
  - `CREATE TABLE` enthaelt keine Geometry-Spalte
  - `AddGeometryColumn(...)` wird danach emittiert
  - `DiscardGeometryColumn(...)` erscheint im Rollback vor `DROP TABLE`
  - Geometry-bezogene `required`-/`unique`-/`default`-/FK-/PK-/Constraint-/
    Index-Faelle sind entweder korrekt getragen oder blockieren die ganze
    Tabelle mit `E052`
  - Profil `none` blockiert die gesamte Tabelle mit `E052` als
    `action_required`-Note plus `SkippedObject`
- kein Dialekt emittiert bei blockierten Tabellen eine partielle DDL ohne die
  Geometry-Spalte
- bestehende `uuid`/`json`/`binary`/`array`-Faelle bleiben in den
  Generator-Tests stabil

4. Statische Code-Review der Zielstruktur:

- kein Dialektgenerator enthaelt mehr produktive Geometry-`TODO`s
- Profilwissen wird nicht erneut lokal defaultet
- PostgreSQL- und MySQL-Spatial werden nicht unnoetig ueber Sonderstatements
  ausserhalb des normalen Tabellenpfads gebaut
- PostgreSQL-/MySQL-Rollback ist in Implementierung, Tests und
  `docs/ddl-generation-rules.md` widerspruchsfrei auf denselben kanonischen
  Pfad gezogen
- SQLite-Spatial verlaesst sich nicht auf generische Statement-Inversion
  allein
- SQLite-Spatial laesst Geometry-bezogene Metadaten nicht still verschwinden

---

## 10. Risiken und offene Punkte

### R1 - Phase D ist lokal fachlich Voraussetzung, aber im Code noch nicht komplett gelandet

Der lokale Stand zeigt bereits Geometry im Core/Codec, aber noch keinen
umgesetzten Optionsvertrag im Port. Ohne diesen Vertrag droht Phase E, Profile
hart im Generator zu defaulten oder pro Dialekt doppelt zu validieren.

### R2 - Tabellenblockierung ist leichter zu verletzen als es aussieht

Wenn PostgreSQL oder SQLite Geometry-Spalten erst zu spaet erkennen, kann
bereits eine partielle `CREATE TABLE` ohne die Spatial-Spalte aufgebaut
werden. Das waere fachlich ein Regelbruch fuer Profil `none`.

### R3 - SQLite-Rollback ist nicht mit dem generischen Inverter erschlagen

`AddGeometryColumn(...)` ist kein normales `CREATE ...`-Statement. Wenn hier
kein expliziter Spatial-Rollback modelliert wird, fehlt der Down-Pfad trotz
formal erfolgreicher Up-Generierung.

### R4 - MySQL-SRID-Verhalten kann leicht ueberhaertet oder verwassert werden

Wenn MySQL bei SRID-Grenzen zu frueh blockiert, wird aus einem dokumentierten
Best-Effort-Pfad ein unnoetiges `E052`. Wenn es zu lax wird, verschwindet
`W120` trotz Informationsverlust.

### R5 - TypeMapper-Umbau kann bestehende Nicht-Spatial-Vertraege ankratzen

Alle drei Treiber fassen fuer Spatial dieselben Schichten an, die heute
`uuid`, `json`, `binary` oder `array` abbilden. Ohne gezielte Regressionen
koennen diese Mappings unbemerkt kippen.

---

## 11. Abschlussdefinition

Phase E ist abgeschlossen, wenn die drei Dialektgeneratoren `geometry` gemaess
den dokumentierten 0.5.5-Regeln erzeugen, PostgreSQL/SQLite die
Tabellenblockierung fuer Profil `none` sauber umsetzen, MySQL den
SRID-Best-Effort-Pfad mit `W120` traegt, SQLite einen expliziten
Spatial-Rollback besitzt und fuer validen Spatial-Input kein Geometry-`TODO`
im Generatorpfad mehr existiert.
