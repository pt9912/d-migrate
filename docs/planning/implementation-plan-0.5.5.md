# Implementierungsplan: Milestone 0.5.5 - Erweitertes Typsystem

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer Milestone
> 0.5.5. Es dient als laufend gepflegte Spezifikation und Review-Grundlage
> waehrend der Umsetzung.
>
> Status: Draft
> Referenzen: `docs/planning/roadmap.md` Milestone 0.5.5, `docs/planning/change-request-spatial-types.md`,
> `spec/neutral-model-spec.md`, `spec/ddl-generation-rules.md`,
> `spec/cli-spec.md` Abschnitt `schema generate`, `spec/architecture.md`.

---

## 1. Ziel

Milestone 0.5.5 zieht das neutrale Typsystem fachlich vor 0.6.0 gerade. Der
Milestone ist kein "kleiner Nachtrag" zu 0.5.0, sondern die Voraussetzung
dafuer, dass `schema reverse` ab 0.6.0 nicht gegen ein halbfertiges Modell
arbeitet.

0.5.5 liefert:

- Spatial Phase 1 gemaess CR: neutraler Typ `geometry` mit `geometry_type` und
  `srid`
- generatorseitige Spatial-Profile fuer PostgreSQL, MySQL und SQLite
- klare Schema-Validierungs-, Warning- und `action_required`-Regeln fuer
  Spatial
- konsistente DDL-Abbildung fuer PostgreSQL/PostGIS, MySQL und
  SQLite/SpatiaLite
- Haertung der bereits vorhandenen erweiterten Typen `uuid`, `json`, `binary`
  und `array`
- Golden-Master- und Negativtests fuer die neuen Typregeln
- aktualisierte Spezifikationsdokumente fuer Modell, DDL und CLI

Das Ergebnis von 0.5.5 ist ein vollstaendiges neutrales Typsystem fuer die
Generator-Richtung. Reverse-Engineering selbst bleibt 0.6.0, aber 0.6.0 muss
dann kein neues Typfundament mehr parallel bauen.

---

## 2. Ausgangslage

Stand nach 0.5.0:

- Das Kernmodell kennt bereits `uuid`, `json`, `xml`, `binary`, `enum` und
  `array`, aber noch keinen Spatial-Typ.
- `SchemaValidator` kennt bislang nur die vorhandenen Typnamen und hat fuer
  `array` bereits eine eigene Regel `E015`; Spatial-spezifische Regeln fehlen.
- `YamlSchemaCodec` kann die vorhandenen erweiterten Typen lesen, aber weder
  `type: geometry` noch `geometry_type` oder `srid`.
- Die drei TypeMapper bilden `uuid`, `json`, `binary` und `array` schon
  teilweise ab; insbesondere `array` ist noch inkonsistent, weil PostgreSQL nur
  eine kleine Teilmenge von `element_type` explizit aufloest, waehrend MySQL
  und SQLite pauschal auf JSON/Text-Fallbacks gehen.
- `spec/neutral-model-spec.md` und `spec/ddl-generation-rules.md` dokumentieren
  `uuid`/`json`/`binary`/`array` bereits, Spatial aber bisher nur im separaten
  Change Request.
- `schema generate` besitzt noch keine Generator-Option fuer ein Spatial-Profil;
  der DDL-Generator-Vertrag nimmt nur `SchemaDefinition` entgegen.
- Der Change Request `docs/planning/change-request-spatial-types.md` ist bereits als
  "Approved for Milestone 0.5.5" markiert. Die Roadmap-Aufgabe "CR in den
  Status Approved ueberfuehren" ist damit faktisch schon vorweggenommen; 0.5.5
  muss die CR-Entscheidungen jetzt in den regulaeren Spezifikationen und im
  Code produktiv machen.

0.5.5 ist damit teils neue Funktionalitaet, teils Konsolidierungsarbeit: Spatial
kommt neu dazu, die anderen erweiterten Typen muessen aus ihrem bisherigen
"funktioniert schon irgendwie"-Zustand in einen sauberen, getesteten Vertrag
ueberfuehrt werden.

---

## 3. Scope

### 3.1 In Scope fuer 0.5.5

- neuer neutraler Typ `geometry`
- neue Typattribute `geometry_type` und `srid`
- Spatial-Profil als Generator-Option fuer `schema generate`
- zielsystembezogene Spatial-Regeln fuer PostgreSQL/PostGIS, MySQL und
  SQLite/SpatiaLite
- neue Schema-Validierungs- und Transformations-Codes `E120`, `E121`, `W120`,
  `E052`
- DDL-Generator- und Report-Verhalten fuer `action_required` bei unzulaessigem
  Spatial-Profil
- Haertung der bereits vorhandenen Typen `uuid`, `json`, `binary`, `array`
  entlang des tatsaechlichen Generator- und Dokumentationsvertrags
- Golden-Master-Tests fuer Spatial-DDL
- Negativtests fuer ungueltige `geometry_type`-/`srid`-Faelle und
  Profil-Mismatches
- Aktualisierung von `spec/neutral-model-spec.md`,
  `spec/ddl-generation-rules.md`, `spec/cli-spec.md` und `spec/architecture.md`

### 3.2 Bewusst nicht Teil von 0.5.5

- `type: geography`
- Spatial-Metadaten `z` und `m`
- Spatial-Indizes als eigener neutraler Index-Typ
- automatische Laufzeit-Erkennung oder Installation von PostGIS bzw.
  SpatiaLite
- Reverse-Engineering von Spatial-Spalten gegen Live-Datenbanken
- `schema migrate` / `schema rollback`
- Aenderungen an `data export` / `data import` fuer WKB/WKT/Warehouse-Faelle
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite
- Umgestaltung des gesamten Typmodells in rekursive oder generische AST-Form

---

## 4. Leitentscheidungen

### 4.1 0.5.5 vervollstaendigt das Typsystem vor 0.6.0

`schema reverse` soll ab 0.6.0 nicht gleichzeitig:

- DB-Metadaten lesen,
- neue neutrale Typen erfinden,
- und Dialekt-Mappings erst noch festlegen.

Deshalb zieht 0.5.5 die Typseite bewusst vor. Spatial-Phase-1 und die Haertung
der erweiterten Typen werden jetzt sauber abgeschlossen; 0.6.0 kann sich dann
auf den Read-Pfad konzentrieren.

### 4.2 `uuid`, `json`, `binary` und `array` sind in 0.5.5 keine Greenfield-Features

Diese Typen existieren bereits im Modell und in Teilen des Generatorpfads. 0.5.5
behandelt sie deshalb nicht als "neu einfuehren", sondern als:

- Dokumentationsvertrag explizit machen
- Generator-Luecken und Inkonsistenzen schliessen
- Tests auf das tatsaechliche Sollverhalten ziehen

Insbesondere `array` darf nicht laenger implizit auf eine winzige
PostgreSQL-Teilmenge zusammengeschrumpft werden, waehrend die Spezifikation
breiter klingt.

### 4.3 Spatial-Profil gehoert in die Generator-Konfiguration, nicht ins Schema

Die Verfuegbarkeit von PostGIS oder SpatiaLite ist keine Eigenschaft des
neutralen Modells, sondern des gewaehlten Generierungskontexts.

Verbindliche Konsequenz:

- `type: geometry` im Schema bleibt rein fachlich
- `--spatial-profile` wird als CLI-/Generator-Option eingefuehrt
- der DDL-Generator bekommt eine explizite Options-Struktur statt versteckter
  globaler Sonderfaelle

Vorgesehene Defaults in 0.5.5:

- PostgreSQL -> `postgis`
- MySQL -> `native`
- SQLite -> `none`

### 4.4 Spatial Phase 1 bleibt wirklich Phase 1

0.5.5 schaltet nur den freigegebenen Kern aus dem CR frei:

- `type: geometry`
- `geometry_type`
- `srid`

Nicht freigeschaltet in 0.5.5:

- `type: geography`
- `z`
- `m`

Die Dokumentation darf diese Begriffe als Vorplanung benennen, aber 0.5.5 macht
keine halbe Platzhalter-Implementierung, die spaeter wieder umgebaut werden
muss.

### 4.5 PostgreSQL erzeugt PostGIS-kompatible DDL, aber installiert PostGIS nicht

0.5.5 emittiert kein automatisches
`CREATE EXTENSION IF NOT EXISTS postgis;`.

Begruendung:

- keine Live-Verbindung waehrend `schema generate`
- keine stillschweigende Aenderung an Zielsystemen
- konsistent mit dem CR und dem bisherigen `schema generate`-Vertrag

Stattdessen:

- DDL verwendet `geometry(<Type>, <SRID>)`
- optionaler Info-Hinweis erklaert die Abhaengigkeit von PostGIS
- bei Profil `none` wird `action_required` erzeugt

### 4.6 SQLite verwendet mit Spatial-Profil `spatialite` immer `AddGeometryColumn(...)`

SQLite/SpatiaLite bekommt keinen stillen Fallback auf `TEXT`, `BLOB` oder
freie DDL-Fragmente. Der freigegebene 0.5.5-Pfad ist:

- Tabelle ohne Geometriespalte anlegen
- Geometriespalte danach via `AddGeometryColumn(...)` registrieren

Bei Profil `none` wird kein "best effort"-DDL erzeugt, sondern ein expliziter
`action_required`-Eintrag mit `E052`.

### 4.7 Spatial-Fallbacks muessen explizit sein

Spatial-Typen duerfen nicht stillschweigend entwertet werden. Verbindliche
Regeln:

- PostgreSQL + Profil `none` -> `E052`
- SQLite + Profil `none` -> `E052`
- MySQL ohne sauber ausdrueckbare SRID -> Typ best effort + `W120`

Es gibt in 0.5.5 keinen automatischen Fallback von `geometry` nach `text`,
`json` oder `binary`.

Wichtig fuer PostgreSQL und SQLite:

- Wenn eine Tabelle mindestens eine Spatial-Spalte enthaelt, die im gewaehlten
  Profil nicht generierbar ist, wird nicht nur die Spalte uebersprungen.
- Stattdessen wird die gesamte betroffene Tabelle im Up-Pfad blockiert und mit
  `action_required` (`E052`) auf Tabellenebene reportet.
- 0.5.5 erzeugt bewusst keine partielle `CREATE TABLE`-DDL, der die
  fachlich geforderte Spatial-Spalte fehlt.

### 4.8 Spatial wird voll im bestehenden Rollback-Pfad mitgetragen

0.5.5 erweitert nicht nur den Up-Pfad. Spatial muss den bereits vorhandenen
`--generate-rollback`-Vertrag voll mittragen.

Verbindliche Konsequenzen:

- `generate(...)` und `generateRollback(...)` sehen denselben Options-Pfad
  inklusive `spatialProfile`.
- Spatial-spezifische Zusatzstatements duerfen nicht auf den generischen
  `invertStatement(...)`-Pfad allein vertrauen.
- Wo der Up-Pfad zusaetzliche Spatial-Statements erzeugt, muss der Down-Pfad
  dafuer explizite, getestete Rollback-Semantik liefern.
- Insbesondere der SQLite-/SpatiaLite-Pfad mit `AddGeometryColumn(...)` braucht
  eine bewusst spezifizierte Down-Strategie statt impliziter Inversion.

### 4.9 `array` bleibt in 0.5.5 absichtlich flach

0.5.5 fuehrt kein rekursives Array-Modell und keine Arrays von Spatial-Typen
ein. Der bestehende YAML-Vertrag bleibt:

```yaml
tags:
  type: array
  element_type: text
```

0.5.5 haertet diesen Vertrag nur:

- kanonische erlaubte `element_type`-Namen zentralisieren
- Generator-Mapping daran ausrichten
- stillschweigende Postgres-Fallbacks vermeiden

Wichtig:

- `geometry` wird zwar als neutraler Basistyp eingefuehrt, wird in 0.5.5 aber
  nicht automatisch ein zulaessiger `array.element_type`.
- Die Allowlist fuer Basistypen und die Allowlist fuer Array-Elementtypen
  muessen deshalb getrennt gefuehrt werden.

### 4.10 DDL-Generator bekommt einen expliziten Options-Pfad

Der aktuelle Generator-Vertrag `generate(schema)` reicht fuer Spatial nicht
mehr aus. 0.5.5 fuehrt deshalb eine kleine, klare Options-Struktur ein, z. B.:

```kotlin
data class GenerationOptions(
    val spatialProfile: SpatialProfile
)
```

Die genaue Typform ist Implementierungsdetail. Verbindlich ist nur:

- CLI und Tests koennen das Profil explizit setzen
- der Default wird zentral, nicht pro Driver-Klasse ad hoc, bestimmt
- spaetere Optionen fuer 0.6.0/0.7.0 koennen additiv in dieselbe Struktur
  aufgenommen werden

---

## 5. Geplante Arbeitspakete

### Phase A - Spezifikationsbereinigung und Ist-Abgleich

1. `spec/neutral-model-spec.md` um `geometry`, `geometry_type`, `srid` und
   Spatial-Profil-Hinweise erweitern.
2. `spec/ddl-generation-rules.md` fuer Spatial-DDL, `action_required` und
   `W120` konkretisieren.
3. `spec/cli-spec.md` fuer `schema generate` um `--spatial-profile` erweitern.
4. `spec/architecture.md` um den neuen Generator-Options-Pfad und die
   Typsystem-Erweiterung ergaenzen.
5. `docs/planning/change-request-spatial-types.md` nur noch als CR-Entscheidungsbasis
   referenzieren; die produktive Spezifikation sitzt danach in den regulaeren
   Docs.

Ziel von Phase A: Vor der Code-Umsetzung ist klar, was 0.5.5 wirklich liefert
und welche Spatial-Faelle explizit ausserhalb des Milestones bleiben.

### Phase B - Kernmodell und Schema-Validierung

Ziel: Das neutrale Modell kennt Spatial Phase 1 explizit und validiert sie
sauber.

Betroffene Module:

- `hexagon:core`

Arbeitspunkte:

1. `NeutralType` um `Geometry` erweitern.
2. Zulaessige `geometry_type`-Werte zentral modellieren, bevorzugt als
   kanonischer Enum-/Value-Typ statt als lose Strings.
3. `srid` als optionale positive Ganzzahl im Modell aufnehmen.
4. Basistyp-Registry um `geometry` erweitern, ohne dadurch automatisch
   `array.element_type: geometry` freizuschalten.
5. Schema-Validierungsregeln implementieren:
   - `E120`: unbekannter `geometry_type`
   - `E121`: `srid <= 0`
6. Bereits vorhandene Array-Regeln zentralisieren, damit Validator, Docs und
   TypeMapper dieselbe `element_type`-Menge verwenden.
7. Getrennte Allowlists fuer neutrale Basistypen vs. Array-Elementtypen
   einfuehren; `geometry` bleibt in 0.5.5 aus der Array-Element-Allowlist
   ausgeschlossen.

Wichtig:

- `E052` und `W120` sind keine allgemeinen `schema validate`-Fehler, sondern
  generatorseitige Transformations-Notes im gewaehlten Zielprofil.
- `schema validate` bleibt schema-zentriert; profilabhaengige Probleme entstehen
  erst bei `schema generate`.

### Phase C - Schema-Codecs und Fixtures

Ziel: Das neue Modell ist ueber YAML/JSON-Schemadateien erreichbar und testbar.

Betroffene Module:

- `adapters:driven:formats`

Arbeitspunkte:

1. `YamlSchemaCodec` um `type: geometry`, `geometry_type` und `srid` erweitern.
2. Bestehende Fixtures fuer "all types" bzw. "full featured" um die 0.5.5-Typen
   sinnvoll ergaenzen oder neue fokussierte Spatial-Fixtures anlegen.
3. Negativ-Fixtures fuer:
   - ungueltigen `geometry_type`
   - `srid: 0`
   - `srid < 0`
4. Fixtures fuer Array-Haertung anpassen, falls erlaubte `element_type`-Namen
   enger oder expliziter dokumentiert werden.

Wichtig:

- 0.5.5 fuehrt keine Schreibunterstuetzung fuer `SchemaCodec.write(...)` ein;
  der Milestone betrifft den Read-/Generate-Pfad.

### Phase D - Generator-Optionen und CLI-Pfad

Ziel: `schema generate` kann das Spatial-Profil sauber an den Generator
uebergeben.

Betroffene Module:

- `hexagon:ports`
- `hexagon:application`
- `adapters:driving:cli`

Arbeitspunkte:

1. Kleines Generator-Optionsobjekt einfuehren.
2. `DdlGenerator`-Vertrag so erweitern, dass `generate(...)` und
   gegebenenfalls `generateRollback(...)` denselben Options-Pfad sehen.
3. `SchemaGenerateRequest` um `spatialProfile` erweitern.
4. `SchemaGenerateCommand` um `--spatial-profile` erweitern.
5. Profil-Defaults zentral bestimmen:
   - PostgreSQL -> `postgis`
   - MySQL -> `native`
   - SQLite -> `none`
6. CLI-Validierung fuer unzulaessige Profilwerte pro Dialekt einfuehren, damit
   z. B. `--target mysql --spatial-profile spatialite` frueh und klar scheitert.
7. Generator-/Report-Vertrag fuer `E052` und `W120` explizit festlegen;
   profilabhaengige Spatial-Probleme entstehen hier, nicht im
   `SchemaValidator`.
8. JSON-/Sidecar-Report-Pfad unveraendert lassen; Notes und `action_required`
   sollen wie bisher ueber `DdlResult` sichtbar werden, nicht ueber einen
   zweiten Nebenkanal.

### Phase E - Dialektimplementierung

Ziel: Jeder unterstuetzte Dialekt bildet `geometry` nach den freigegebenen
0.5.5-Regeln ab.

#### E.1 PostgreSQL / PostGIS

Betroffene Module:

- `adapters:driven:driver-postgresql`

Arbeitspunkte:

1. `Geometry`-Mapping auf `geometry(<Type>, <SRID>)` implementieren.
2. PostGIS-Schreibweise der Typnamen zentral abbilden:
   - `point` -> `Point`
   - `polygon` -> `Polygon`
   - `multipolygon` -> `MultiPolygon`
   - etc.
3. Bei Profil `none` keine partielle Tabellen-DDL erzeugen; stattdessen die
   gesamte betroffene Tabelle blockieren und `action_required` mit `E052` auf
   Tabellenebene reporten.
4. Optionalen Info-Hinweis fuer PostGIS-Abhaengigkeit ergaenzen.
5. Rollback fuer Spatial-faehige Tabellen mittragen; der Down-Pfad muss mit
   denselben Generator-Optionen konsistent bleiben.
6. Bereits vorhandene Mappings fuer `uuid`, `json`, `binary`, `array`
   gegentesten und bei Bedarf vereinheitlichen.

#### E.2 MySQL

Betroffene Module:

- `adapters:driven:driver-mysql`

Arbeitspunkte:

1. `Geometry`-Mapping auf nativen Spatial-Type reduzieren:
   - `point` -> `POINT`
   - `linestring` -> `LINESTRING`
   - ...
   - `geometry` -> `GEOMETRY`
2. SRID-Behandlung als best effort umsetzen; wenn das gewaehlte DDL-Format die
   SRID nicht sauber traegt, `W120` erzeugen.
3. Rollback fuer Spatial-faehige Tabellen mittragen; MySQL-spezifische
   Spatial-Statements duerfen nicht versehentlich nur ueber generische
   Inversion abgedeckt sein.
4. Bereits vorhandene Mappings fuer `uuid`, `json`, `binary`, `array`
   gegentesten und dokumentierten Vertrag absichern.

#### E.3 SQLite / SpatiaLite

Betroffene Module:

- `adapters:driven:driver-sqlite`

Arbeitspunkte:

1. Tabellen-DDL fuer Spatial-Tabellen in zwei Schritte aufteilen:
   - `CREATE TABLE` ohne Geometriespalte
   - danach `SELECT AddGeometryColumn(...)`
2. Typnamen fuer `AddGeometryColumn(...)` im erwarteten Format emittieren.
3. Mit Profil `none` keine partielle Tabellen-DDL erzeugen, sondern die ganze
   betroffene Tabelle blockieren und `action_required` mit `E052` reporten.
4. Rollback fuer den `AddGeometryColumn(...)`-Pfad explizit spezifizieren und
   testen; der generische `invertStatement(...)`-Pfad reicht dafuer nicht aus.
5. Bereits vorhandene Mappings fuer `uuid`, `json`, `binary`, `array`
   gegentesten; `json`/`array` bleiben hier dokumentierte Text-/JSON-Fallbacks
   und werden nicht als echte native Typen missverstanden.

### Phase F - Testmatrix und Golden Masters

Ziel: Das neue Typsystem ist generatorseitig stabil abgesichert.

Betroffene Module:

- `hexagon:core:test`
- `adapters:driven:formats:test`
- `adapters:driven:driver-postgresql:test`
- `adapters:driven:driver-mysql:test`
- `adapters:driven:driver-sqlite:test`
- `adapters:driving:cli:test`

Arbeitspunkte:

1. Validator-Tests fuer `E120` und `E121`.
2. Generator-Tests fuer `E052` und `W120`.
3. Golden Masters:
   - `spatial.postgresql.sql`
   - `spatial.mysql.sql`
   - `spatial.sqlite.sql`
4. CLI-Tests fuer:
   - `--spatial-profile` Help/Parsing
   - Default-Profil je Dialekt
   - Fehlkonfigurationen und Exit-Codes
   - `--output-format json` fuer Spatial-Notes
   - `--output` + Sidecar-Report
   - `--generate-rollback` fuer Spatial-Faelle
5. Tests fuer Tabellenblockierung:
   - nicht generierbare Spatial-Spalte blockiert die ganze Tabelle
   - es wird keine partielle Tabellen-DDL ohne die Spatial-Spalte emittiert
6. Regressionstests fuer `uuid`, `json`, `binary`, `array`, damit der 0.5.5-
   Umbau keine bestehenden Typen verschlechtert.

Wichtig:

- Live-DB-Reverse- oder Testcontainers-Spatial-Read-Pfade sind nicht
  Mindestumfang von 0.5.5.
- Der Fokus liegt auf neutralem Modell, Validierung und DDL-Generierung.

---

## 6. Technische Zielstruktur

### 6.1 Modell

Der Milestone fuehrt mindestens folgende neue Modellbausteine ein:

- `NeutralType.Geometry`
- kanonische Repraesentation fuer `geometry_type`
- optionale `srid`

Empfohlene Form:

```kotlin
data class Geometry(
    val geometryType: GeometryType = GeometryType.GEOMETRY,
    val srid: Int? = null,
) : NeutralType()
```

Wichtig ist weniger die exakte Kotlin-Syntax als die Semantik:

- `geometry_type` ist im Code nicht nur ein freier String
- Default ist `geometry`
- spaetere Erweiterungen fuer `geography`, `z`, `m` muessen additiv bleiben

### 6.2 Generator-Optionen

Der Generator braucht eine zentrale Options-Struktur. Empfohlene Elemente:

- `spatialProfile`

Hinweis zur Abgrenzung:

- `targetDialect` muss in der aktuellen Architektur **nicht** Teil des
  Optionsobjekts sein, wenn der Dialekt bereits ueber die Generatorwahl
  feststeht (`PostgresDdlGenerator`, `MysqlDdlGenerator`, `SqliteDdlGenerator`).
- Ein zusaetzliches `targetDialect` im Optionsobjekt ist nur dann sinnvoll,
  wenn spaeter bewusst ein generischer Multi-Dialect-Generator eingefuehrt
  wird.

Das Optionsobjekt soll:

- im CLI-Runner erzeugt werden
- in Unit-Tests direkt konstruierbar sein
- von allen DDL-Generatoren gleich verwendet werden

### 6.3 Report- und Note-Verhalten

`schema generate` behaelt seinen bestehenden Vertrag:

- Exit `0`, wenn DDL generiert werden kann, auch bei Warnings oder
  `action_required`
- Notes auf `stderr`
- Sidecar-/JSON-Report ueber `DdlResult`

0.5.5 erweitert nur die Inhalte:

- `E052` fuer nicht generierbare Spatial-Faelle
- `W120` fuer SRID-Best-Effort
- optionaler Info-Hinweis fuer PostGIS-Abhaengigkeit

Wichtig:

- `E052` fuer Spatial betrifft bei PostgreSQL/SQLite die betroffene Tabelle als
  Ganzes, nicht nur die einzelne Spalte.
- JSON-Output, Sidecar-Report und stderr muessen dieselben Spatial-Notes
  konsistent transportieren.

### 6.4 Array-Haertung

Der aktuelle Zustand ist zu implizit:

- Validator kennt eine breite Menge zulaessiger Typnamen
- PostgreSQL loest nur einzelne `element_type`-Namen explizit auf
- unbekannte Faelle landen still als `TEXT[]`

0.5.5 muss diesen Vertrag schaerfen. Verbindliche Zielrichtung:

- erlaubte Array-Elementtypen zentral festlegen
- TypeMapper arbeiten gegen dieselbe Liste
- unbekannte oder nicht unterstuetzte Faelle werden nicht still auf
  "irgendwie Text" heruntergebogen
- `geometry` bleibt in 0.5.5 trotz neuem Basistyp ausserhalb der
  Array-Element-Allowlist

Ob 0.5.5 bei unzulaessigen Array-Elementtypen hart fehlschlaegt oder
dokumentierte Warnings verwendet, ist Umsetzungsdetail. Nicht akzeptabel ist
der bisherige implizite Postgres-Fallback ohne klaren Vertrag.

---

## 7. Betroffene Artefakte

Voraussichtlich anzupassen:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/NeutralType.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- `adapters/driven/driver-postgresql/...`
- `adapters/driven/driver-mysql/...`
- `adapters/driven/driver-sqlite/...`
- `spec/neutral-model-spec.md`
- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`
- `spec/architecture.md`

Neue oder erweiterte Testartefakte:

- Golden-Master-SQL fuer Spatial
- Validator-/Codec-Negativfixtures
- CLI-Tests fuer Profil-Optionen

---

## 8. Akzeptanzkriterien

- [ ] `schema validate` akzeptiert `type: geometry` mit gueltigem
      `geometry_type` und optional positivem `srid`.
- [ ] Ungueltige `geometry_type`-Werte liefern `E120`.
- [ ] `srid <= 0` liefert `E121`.
- [ ] `E052` und `W120` entstehen im Generator-/Report-Pfad von
      `schema generate`, nicht als allgemeine `schema validate`-Fehler.
- [ ] `schema generate` akzeptiert `--spatial-profile`.
- [ ] Ohne explizite Option gelten die dokumentierten Defaults:
      PostgreSQL `postgis`, MySQL `native`, SQLite `none`.
- [ ] PostgreSQL generiert fuer Spatial-Spalten PostGIS-kompatibles DDL, aber
      kein automatisches `CREATE EXTENSION`.
- [ ] MySQL generiert native Spatial-Types fuer die erlaubten
      `geometry_type`-Werte.
- [ ] SQLite generiert mit Profil `spatialite` eine
      `AddGeometryColumn(...)`-Strategie.
- [ ] PostgreSQL und SQLite erzeugen mit Profil `none` `action_required`
      (`E052`) auf Tabellenebene statt stillschweigender Fallback-DDL oder
      partieller Tabellen-DDL ohne Spatial-Spalte.
- [ ] MySQL erzeugt bei nicht sauber ausdrueckbarer SRID `W120`.
- [ ] `array.element_type: geometry` ist in 0.5.5 weiterhin nicht zulaessig.
- [ ] `--generate-rollback` traegt Spatial-Faelle voll mit; insbesondere ist
      der SQLite-/SpatiaLite-Pfad mit expliziter Down-Strategie abgedeckt.
- [ ] `--output-format json` und Sidecar-Report transportieren `E052`/`W120`
      fuer Spatial konsistent.
- [ ] `uuid`, `json`, `binary` und `array` sind in Modell, Generator und Docs
      konsistent beschrieben und testseitig abgesichert.
- [ ] Golden Masters fuer Spatial-DDL existieren fuer PostgreSQL, MySQL und
      SQLite.
- [ ] `spec/neutral-model-spec.md`, `spec/ddl-generation-rules.md`,
      `spec/cli-spec.md` und `spec/architecture.md` sind auf demselben Stand.

---

## 9. Verifikation

Die Umsetzung wird mit den Docker-/Container-Pfaden aus dem README verifiziert.
Mindestumfang:

1. Gezielter Testlauf fuer Core, Formats, Driver und CLI:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:formats:test :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test :adapters:driven:driver-sqlite:test :adapters:driving:cli:test" \
  -t d-migrate:0.5.5-tests .
```

2. Vollbuild mit CLI-Distribution:

```bash
docker build --no-cache \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:0.5.5 .
```

3. Falls fuer die Driver-Tests echte Container gebraucht werden, separate
   Integration ueber das bestehende Script:

```bash
./scripts/test-integration-docker.sh
```

4. Manuelle Smoke-Proben gegen `schema generate`:

```bash
docker run --rm -v $(pwd):/work d-migrate:0.5.5 \
  schema generate --source /work/path/to/spatial.yaml --target postgresql

docker run --rm -v $(pwd):/work d-migrate:0.5.5 \
  schema generate --source /work/path/to/spatial.yaml --target sqlite --spatial-profile spatialite

docker run --rm -v $(pwd):/work d-migrate:0.5.5 \
  schema generate --source /work/path/to/spatial.yaml --target sqlite --spatial-profile none

docker run --rm -v $(pwd):/work d-migrate:0.5.5 \
  --output-format json schema generate --source /work/path/to/spatial.yaml --target mysql

docker run --rm -v $(pwd):/work d-migrate:0.5.5 \
  schema generate --source /work/path/to/spatial.yaml --target sqlite --spatial-profile spatialite --output /work/out/spatial.sqlite.sql --generate-rollback
```

Dabei explizit pruefen:

- bei PostgreSQL/SQLite mit unzulaessigem Profil wird die ganze Tabelle
  blockiert; es erscheint keine partielle `CREATE TABLE`-DDL ohne
  Spatial-Spalte
- JSON-Output enthaelt Spatial-Notes konsistent
- `--output` schreibt DDL, Sidecar-Report und Rollback-Datei fuer den
  Spatial-Fall

---

## 10. Risiken und offene Punkte

### R1 - Generator-Vertrag aendert sich

Die Einfuehrung von Generator-Optionen beruehrt alle Dialekte. Das ist fachlich
notwendig, sollte aber als kontrollierter API-Schnitt behandelt werden, nicht
als "kleiner Parameter nebenbei".

### R2 - Array-Scope kann ausufern

`array` ist bereits vorhanden, aber unterdefiniert. 0.5.5 soll den Vertrag
haerten, nicht ein neues generisches Collection-Modell bauen.

### R3 - SQLite/SpatiaLite-Syntax ist versionssensitiv

`AddGeometryColumn(...)` ist der freigegebene Pfad, aber Details koennen je nach
Umgebung leicht variieren. Golden Masters muessen deshalb auf die bewusst
unterstuetzte Strategie festgelegt werden.

### R4 - Spatial-Docs duerfen 0.5.5 nicht ueberverkaufen

0.5.5 implementiert `geometry` Phase 1. `geography`, `z`, `m`,
Reverse-Engineering und Spatial-Indizes bleiben spaetere Arbeit.

---

## 11. Abschlussdefinition

Milestone 0.5.5 ist abgeschlossen, wenn:

- der neue Typ `geometry` generatorseitig in allen drei Dialekten getragen wird,
- Profil- und Fallback-Regeln konsistent ueber CLI, Generator und Report
  laufen,
- die vorhandenen erweiterten Typen `uuid`, `json`, `binary`, `array` keinen
  impliziten Dokumentationsschulden-Zustand mehr haben,
- und die Spezifikationsdokumente den tatsaechlichen Codevertrag sauber
  beschreiben.

Danach kann 0.6.0 `schema reverse` auf einem stabilen Typfundament aufbauen,
statt waehrend des Read-Pfads noch das Modell nachzuziehen.
