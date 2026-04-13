# Change Request: Spatial Types im neutralen Modell

> Status: Approved for Milestone 0.5.5 (Phase 1 des CR)
> Ziel: Erweiterung des neutralen Modells und der DDL-Regeln um raeumliche Datentypen fuer PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite.
>
> Siehe [roadmap.md, Milestone 0.5.5 — Erweitertes Typsystem](./roadmap.md#milestone-055--erweitertes-typsystem).

> **Hinweis zur Dokumentationsrolle (ab Phase A, 0.5.5)**
>
> Dieses Dokument ist ab Abschluss von Phase A die **Entscheidungsbasis** (Entscheidungsgrundlage und Herleitung der freigegebenen Phase-1-Grenzen), nicht mehr die primaere Spezifikation.
>
> Die **massgeblichen Quellen fuer den produktiven 0.5.5-Vertrag** sind:
>
> - `docs/neutral-model-spec.md` — Typdefinition `geometry`, Attribute `geometry_type` und `srid`, Validierungsregeln E120/E121
> - `docs/ddl-generation-rules.md` — Spatial-DDL-Regeln pro Dialekt, Spatial-Profile, E052 und W120
> - `docs/cli-spec.md` — Flag `--spatial-profile`, Dialekt-Defaults, Fehlercodes
> - `docs/architecture.md` — Generator-Options-Pfad, Verortung von `spatialProfile`
>
> Implementierungsdetails gehoeren in die regulaeren Dokumente oben, nicht in diesen Change Request. Bei Widerspruechen zwischen diesem Dokument und den regulaeren Docs gelten die regulaeren Docs.

---

## 1. Ausgangslage

Im Lastenheft wurde `LF-003` erweitert, sodass raeumliche Datentypen und Geometrie-Spalten fuer unterstuetzte Zielsysteme abbildbar sein muessen:

- PostgreSQL ueber PostGIS
- MySQL ueber native Spatial Data Types
- SQLite ueber SpatiaLite, sofern die Erweiterung im Zielsystem verfuegbar ist

Das bestehende neutrale Modell kennt aktuell keine Spatial-Typen. Dadurch fehlen:

- eine ausdrueckliche YAML-Syntax fuer Geometrie-Spalten
- Validierungsregeln fuer Geometrietypen und SRIDs
- ein verbindliches Mapping auf die Zieldialekte
- ein definiertes Fallback-Verhalten bei fehlenden Erweiterungen

---

## 2. Ziel und Scope

Dieses Aenderungsdokument beschreibt:

- das langfristige Zielmodell fuer Spatial-Typen
- den freigegebenen Implementierungsumfang fuer Phase 1
- die fachlich vorgeplante Erweiterung fuer Phase 2
- die benoetigten YAML-Attribute
- Validierungsregeln
- DDL-Mapping fuer PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite
- Fallbacks und `action_required`-Faelle
- Reverse-Engineering-Regeln
- die Teststrategie

Nicht Bestandteil dieses Dokuments sind:

- Implementierungsdetails auf Klassenebene
- Migrationslogik fuer bestehende Schemas
- Runtime-Erkennung installierter DB-Erweiterungen ueber Live-Verbindungen
- raeumliche Indizes als eigener neutraler Indextyp

---

## 3. Fachliche Anforderungen

Die Erweiterung soll insgesamt mindestens folgende Anwendungsfaelle abdecken:

- Speicherung von Punkten, Linien und Polygonen
- Speicherung allgemeiner Geometrien mit festem oder offenem Subtyp
- explizite SRID-Angabe im neutralen Modell
- Roundtrip zwischen neutralem Modell und DB-spezifischen Typen
- Generierung von portabler DDL mit transparentem Fallback-Verhalten

Phase 1 deckt den portablen Kern fuer Geometriespalten ab. Phase 2 erweitert dieses Modell um spezialisierte raeumliche Semantik.

Nicht im Scope der ersten Stufe der Implementierung sind:

- automatische Pruefung, ob PostGIS oder SpatiaLite im Zielsystem bereits installiert sind
- raeumliche Operatoren in Views und Funktionen
- automatische Projektionstransformationen
- Topologie- und Raster-Typen

---

## 4. Vorschlag fuer das neutrale Modell

### 4.1 Zielmodell fuer Spatial-Typen

Das langfristige Zielmodell sieht zwei Spatial-Typen vor:

- `geometry`
- `geography`

`geometry` ist der portable Standardtyp fuer planare oder allgemein raeumliche Daten.

`geography` ist der spezialisierte Typ fuer geodaetische Daten und wird fachlich fuer Phase 2 vorgeplant.

### 4.2 Neue Typ-Attribute

Das langfristige Zielmodell sieht folgende Spatial-Attribute vor:

- `geometry_type`: konkreter Geometrietyp
- `srid`: raeumliches Referenzsystem als positive Ganzzahl
- `z`: optionale Kennzeichnung fuer 3D-Geometrien
- `m`: optionale Kennzeichnung fuer Measure-Koordinaten

### 4.3 Freigabe nach Phasen

Phase 1:

- `type: geometry`
- `geometry_type`
- `srid`

Phase 2:

- `type: geography`
- `z`
- `m`

### 4.4 Zulaessige Werte fuer `geometry_type`

- `geometry`
- `point`
- `linestring`
- `polygon`
- `multipoint`
- `multilinestring`
- `multipolygon`
- `geometrycollection`

`geometry` bedeutet "beliebiger Geometrietyp".

### 4.5 YAML-Syntax

Beispiel fuer eine portable Geometrie-Spalte:

```yaml
tables:
  places:
    columns:
      id:
        type: identifier
        auto_increment: true
      location:
        type: geometry
        geometry_type: point
        srid: 4326
    primary_key: [id]
```

Beispiel fuer eine Polygon-Spalte:

```yaml
tables:
  regions:
    columns:
      id:
        type: identifier
        auto_increment: true
      boundary:
        type: geometry
        geometry_type: polygon
        srid: 3857
    primary_key: [id]
```

Beispiel fuer die fachlich bereits vorgeplante Phase 2:

```yaml
tables:
  customers:
    columns:
      id:
        type: identifier
        auto_increment: true
      home_area:
        type: geography
        geometry_type: multipolygon
        srid: 4326
        z: false
        m: false
    primary_key: [id]
```

---

## 5. Validierungsregeln und Capability-Profil

### 5.1 Allgemeine Regeln

- `geometry_type` ist fuer `geometry` und spaeter `geography` optional. Default ist `geometry`.
- `srid` ist optional, muss aber bei Angabe eine positive Ganzzahl sein.
- `z` und `m` sind fachlich fuer das Zielmodell reserviert, werden aber erst in Phase 2 aktiviert.

### 5.2 Zielsystembezogenes Spatial-Profil

Da die DDL-Generierung fuer `schema generate` ohne Live-Verbindung erfolgt, darf die Verfuegbarkeit von PostGIS oder SpatiaLite nicht implizit erraten werden.

Stattdessen wird fuer die Generierung ein explizites Spatial-Profil verwendet:

- PostgreSQL: `postgis` oder `none`
- MySQL: `native`
- SQLite: `spatialite` oder `none`

Das Spatial-Profil ist Teil der Generator-Konfiguration, nicht Teil des neutralen Schemas. Es wird z. B. ueber CLI-Optionen oder Driver-/Generator-Settings uebergeben.

Vorgeschlagene Defaults fuer Phase 1:

- PostgreSQL -> `postgis`
- MySQL -> `native`
- SQLite -> `none`

Bedeutung:

- `postgis`: PostgreSQL-DDL wird mit PostGIS-kompatiblen Geometrietypen erzeugt
- `native`: MySQL-DDL wird mit nativen Spatial Types erzeugt
- `spatialite`: SQLite-DDL wird mit SpatiaLite-kompatibler Strategie erzeugt
- `none`: Spatial-Spalten werden nicht in ausfuehrbare DDL ueberfuehrt, sondern als `action_required` markiert

### 5.3 Zielsystembezogene Regeln fuer Phase 1

- PostgreSQL/PostGIS:
  - `geometry` ist zulaessig, wenn das Profil `postgis` ist
  - bei Profil `none` wird `action_required` erzeugt
- MySQL:
  - `geometry` ist zulaessig
  - MySQL benoetigt kein zusaetzliches Profil
- SQLite/SpatiaLite:
  - `geometry` ist nur zulaessig, wenn das Profil `spatialite` ist
  - bei Profil `none` wird `action_required` erzeugt

### 5.4 Zielsystembezogene Vorplanung fuer Phase 2

- PostgreSQL/PostGIS:
  - `geography` ist fachlich vorgesehen
  - `z` und `m` sind grundsaetzlich modellierbar, muessen aber in der SQL-Syntax noch verbindlich spezifiziert werden
- MySQL:
  - `geography` bleibt voraussichtlich `action_required`
  - fuer `z` und `m` ist zu pruefen, welche portable Semantik dokumentiert werden kann
- SQLite/SpatiaLite:
  - `geography` bleibt voraussichtlich `action_required`
  - fuer `z` und `m` ist die konkrete SpatiaLite-Strategie vor Implementierung festzulegen

### 5.5 Vorgeschlagene Fehler- und Hinweis-Codes

Die bestehenden Validierungsfehler verwenden derzeit den Bereich `E001` bis `E018`. Um Konflikte mit bereits dokumentierten oder geplanten Codes zu vermeiden, reserviert Spatial Phase 1 einen eigenen Bereich ab `E120`.

- `E120`: Unbekannter `geometry_type`
- `E121`: `srid` muss groesser als `0` sein
- `W120`: `srid` konnte nicht vollstaendig in Zieldialekt uebernommen werden
- `E052`: Spatial-Typ kann im gewaehlten Spatial-Profil nicht generiert werden und benoetigt manuelle Nacharbeit

---

## 6. Dialekt-Mapping und DDL-Regeln

Dieser Abschnitt trennt zwischen verbindlicher Phase-1-Spezifikation und fachlicher Vorplanung fuer Phase 2.

### 6.1 PostgreSQL mit PostGIS

PostgreSQL bildet `geometry` in Phase 1 ueber die PostGIS-Extension ab.

Mapping:

- `geometry` -> `geometry(<geometry_type>, <srid>)`

Beispiele:

```sql
CREATE TABLE "places" (
    "id" BIGSERIAL PRIMARY KEY,
    "location" geometry(Point, 4326)
);
```

Regeln:

- Phase 1 emittiert kein automatisches `CREATE EXTENSION IF NOT EXISTS postgis;`.
- Stattdessen wird PostGIS-kompatible DDL erzeugt und optional ein Info-Hinweis ausgegeben, dass das Zielsystem PostGIS bereitstellen muss.
- `geometry_type` wird in der PostGIS-Schreibweise emittiert, z. B. `Point`, `Polygon`, `MultiPolygon`.
- Wenn das Spatial-Profil fuer PostgreSQL auf `none` gesetzt ist, wird statt DDL `action_required` erzeugt.

Vorplanung fuer Phase 2:

- `geography` soll auf PostgreSQL/PostGIS nativ abbildbar sein.
- Z/M-Dimensionen sollen additive Erweiterungen der Spatial-Metadaten bleiben und nicht zu einem Modellbruch fuehren.

### 6.2 MySQL

MySQL unterstuetzt native Spatial Data Types.

Mapping:

- `geometry` + `geometry_type: point` -> `POINT`
- `geometry` + `geometry_type: linestring` -> `LINESTRING`
- `geometry` + `geometry_type: polygon` -> `POLYGON`
- `geometry` + `geometry_type: multipoint` -> `MULTIPOINT`
- `geometry` + `geometry_type: multilinestring` -> `MULTILINESTRING`
- `geometry` + `geometry_type: multipolygon` -> `MULTIPOLYGON`
- `geometry` + `geometry_type: geometrycollection` -> `GEOMETRYCOLLECTION`
- `geometry` + `geometry_type: geometry` -> `GEOMETRY`

Regeln:

- `geometry_type` wird auf den passenden nativen MySQL-Typ reduziert.
- Falls `srid` nicht im gewuenschten DDL-Stil ausgedrueckt werden kann, wird der Typ ohne SRID emittiert und mit `W120` markiert.

Vorplanung fuer Phase 2:

- `geography` bleibt mangels portabler nativer Semantik ausserhalb des freigegebenen Implementierungsumfangs.
- Z/M-Unterstuetzung darf das Phase-1-Modell nicht brechen und muss deshalb ueber additive Spatial-Metadaten erfolgen.

### 6.3 SQLite mit SpatiaLite

SQLite benoetigt in Phase 1 fuer Spatial-Typen explizit das Profil `spatialite`.

Mapping:

- Tabellenstruktur wird ohne Geometriespalte erzeugt
- Geometriespalten werden anschliessend ueber `AddGeometryColumn(...)` registriert

Beispiel:

```sql
CREATE TABLE "places" (
    "id" INTEGER PRIMARY KEY AUTOINCREMENT
);

SELECT AddGeometryColumn('places', 'location', 4326, 'POINT', 'XY');
```

Regeln:

- Mit Profil `spatialite` wird immer die `AddGeometryColumn(...)`-Strategie verwendet.
- Mit Profil `none` wird kein stillschweigender Fallback auf `TEXT` oder `BLOB` erzeugt.
- Stattdessen wird `action_required` mit `E052` erzeugt.

Vorplanung fuer Phase 2:

- `geography` bleibt in SQLite/SpatiaLite ausserhalb des freigegebenen Umfangs.
- Z/M-Unterstuetzung ist nur zulaessig, wenn die SpatiaLite-Strategie dafuer verbindlich dokumentiert wurde.

---

## 7. Fallbacks und `action_required`

Spatial-Typen duerfen nicht stillschweigend in untypisierte Textspalten umgewandelt werden.

Regeln:

- PostgreSQL mit Spatial-Profil `none` -> `E052`
- SQLite mit Spatial-Profil `none` -> `E052`
- nicht exakt uebertragbare SRID-Metadaten -> DDL best effort + `W120`

Beispiel fuer Report-Eintrag:

```yaml
notes:
  - type: action_required
    code: E052
    object: places.location
    message: "Spatial type cannot be generated with SQLite spatial profile 'none'"
    hint: "Use spatial profile 'spatialite' or map the column manually"
```

---

## 8. Reverse-Engineering

Beim Reverse-Engineering werden Spatial-Spalten in das neutrale Modell zurueckgefuehrt.

Ziel:

- DB-spezifische Typnamen in `geometry` ueberfuehren
- `geometry_type` soweit moeglich extrahieren
- `srid` soweit moeglich extrahieren

Beispiele:

- PostgreSQL `geometry(Point,4326)` -> `type: geometry`, `geometry_type: point`, `srid: 4326`
- MySQL `POINT SRID 4326` -> `type: geometry`, `geometry_type: point`, `srid: 4326`
- SQLite/SpatiaLite -> Mapping entsprechend Metadaten oder registrierter Geometriespalten

Wenn `geometry_type` oder `srid` nicht sicher bestimmt werden koennen:

- `geometry_type` faellt auf `geometry` zurueck
- `srid` bleibt leer
- optionaler Hinweis im Report

---

## 9. Teststrategie

### 9.1 Unit-Tests

- Parsing des neuen YAML-Typs `geometry`
- Validierung von `geometry_type` und `srid`
- Type-Mapping pro Dialekt
- Erzeugung von `action_required` bei nicht unterstuetzten Dialekten

Phase-2-Vorplanung:

- Parser- und Validator-Tests fuer `geography`, `z` und `m` werden vorbereitet, aber erst mit Phase 2 aktiviert.

### 9.2 Golden-Master-Tests

- `spatial.postgresql.sql`
- `spatial.mysql.sql`
- `spatial.sqlite.sql`

Testfaelle:

- `POINT` mit `srid: 4326`
- `POLYGON` mit projektiertem SRID
- PostgreSQL mit Profil `postgis`
- SQLite mit Profil `spatialite`
- PostgreSQL und SQLite mit Profil `none` als `action_required`

Phase-2-Vorplanung:

- eigene Golden Masters fuer `geography`
- eigene Golden Masters fuer Z/M-Varianten

### 9.3 Roundtrip-Tests

- PostgreSQL/PostGIS -> neutral -> PostgreSQL/PostGIS
- MySQL -> neutral -> MySQL
- SQLite/SpatiaLite -> neutral -> SQLite/SpatiaLite

### 9.4 Negativtests

- ungueltiger `geometry_type`
- `srid: 0`
- `srid: -1`
- `geometry` fuer PostgreSQL mit Profil `none`
- `geometry` fuer SQLite mit Profil `none`

---

## 10. Auswirkungen auf Module und Dokumentation

Folgende Artefakte muessen bei Umsetzung angepasst werden:

- `docs/neutral-model-spec.md`
- `docs/ddl-generation-rules.md`
- `docs/architecture.md`
- `hexagon:core`:
  - Erweiterung des Typsystems
  - neue Validierungsregeln
- `adapters:driven:formats`:
  - YAML-Parsing und Serialisierung
- Driver-Module:
  - TypeMapper und DDL-Generatoren
- Reverse-Engineering-Komponenten:
  - Mapping aus DB-Metadaten in Spatial-Typen
- Test-Fixtures und Golden Masters

---

## 11. Modellierungsleitlinie fuer spaetere Erweiterungen

Phase 2 ist bereits fachlich vorgesehen. Damit spaetere Erweiterungen additive Aenderungen bleiben, sollte das Spatial-Modell bereits in Phase 1 so entworfen werden, dass folgende Erweiterungen ohne Umbau des Grundkonzepts moeglich sind:

- neutraler Typ `geography`
- Z/M-Unterstuetzung
- Spatial-Indizes
- weitere Spatial-Funktionen in Views, Triggern und Routinen

Leitlinie:

- Spatial-Metadaten sollen als zusammenhaengende Erweiterung eines Spatial-Typs gedacht werden.
- Phase 1 darf deshalb nicht so modelliert werden, dass spaeter fuer `geography`, `z` oder `m` ein inkompatibler Typumbau noetig wird.
- Phase 2 soll additive Erweiterungen an Typen, Parsing, Validierung und Generatoren ermoeglichen.

---

## 12. Empfehlung

Empfohlene Umsetzung in zwei Schritten:

1. Erste Stufe:
   - `geometry` als neuer neutraler Typ
   - `geometry_type` und `srid`
   - explizites Spatial-Profil fuer PostgreSQL und SQLite
   - PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite mit klaren `action_required`-Faellen

2. Zweite Stufe:
   - `geography`
   - Z/M-Unterstuetzung
   - Spatial-Indizes und weitere Spezialfunktionen

Diese Staffelung reduziert das Risiko, 0.2.x mit einem zu breiten Modellumbau zu ueberladen.
