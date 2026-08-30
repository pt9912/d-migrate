---
id: spatial-read-path-geometry-detection
title: "SpatiaLite fehlt im Datenpfad: Geometrie wird als BLOB gelesen"
status: done
---

# SpatiaLite fehlt im Datenpfad

> **Behoben 2026-08-30.** Lese- und Schreibpfad stehen, live belegt über einen
> Round-Trip SpatiaLite → SpatiaLite: `AsText(geom)` liefert im Ziel wieder
> `POINT(1 2)` mit SRID 4326.
>
> Beim Schreibpfad kam ein **vorbestehender** Fehler ans Licht:
> `SqliteTableImportSession.buildInsertSql` schrieb feste `?`-Platzhalter statt
> `valuePlaceholder(…)`. Damit war der Geometrie-Konstruktor-Haken der
> Basisklasse für SQLite von Anfang an wirkungslos — er fiel nur nicht auf,
> weil SQLite als einziger Dialekt bis jetzt keine Geometrie schrieb.
>
> **Neu geschnitten 2026-08-30.** Die frühere Fassung nannte drei Punkte aus der
> Statustabelle des Spatial-Slices. Nachgemessen sind alle drei überholt — was
> übrig bleibt, ist ein anderer, engerer Fall. Die widerlegten Punkte stehen
> unten unter „Was nicht mehr gilt", damit derselbe Befund nicht ein drittes Mal
> aufgeschrieben wird.

## Lage

PostgreSQL, MySQL und MS SQL Server setzen `supportsGeometryRead = true`: der
Lesepfad probt die Spalten vorab, wickelt sie in `ST_AsBinary` und markiert sie
im `ChunkSchema` als Geometrie. SQLite tut das nicht
(`SqliteDataReader.supportsGeometryRead = false`), und der Kommentar dort nennt
den Grund: ohne geladenes `mod_spatialite` ist eine SpatiaLite-Spalte gar nicht
als Geometrie erkennbar.

Damit liest ein `data transfer` aus einer SpatiaLite-Datenbank die Geometrie als
BLOB. SpatiaLite speichert ihr **eigenes** Binärformat, nicht WKB — die Bytes in
eine PostGIS-Spalte zu schreiben ergäbe keinen Punkt, sondern Müll.

Der Spatial-Smoke deckt SpatiaLite deshalb nur über `schema generate` ab; sein
Kommentar sagt es ausdrücklich: „Voller `migrate --execute`-Round-Trip = 5d-
Folgearbeit."

## Warum es nicht drei Zeilen sind

Die Erweiterungspunkte im `AbstractJdbcDataReader` sind klein — drei Overrides
(`supportsGeometryRead`, `geometryReadExpression`, `isGeometryTypeName`). Der
Aufwand liegt woanders:

- **`ST_AsBinary` setzt die geladene Extension voraus.** `supportsGeometryRead`
  darf nicht pauschal `true` sein, sondern nur, wenn die Verbindung
  `?spatialite=true` trägt — sonst scheitert jede Abfrage auf einer
  SQLite-Datei ohne Extension.
- **Die Typerkennung ist unschärfer als anderswo.** SQLite führt keine
  Typen, sondern Affinitäten; die Geometriespalte trägt den deklarierten Namen
  (`POINT`, `GEOMETRY`, …) aus dem `CREATE TABLE`. Ob das reicht oder ob die
  `geometry_columns`-Tabelle von SpatiaLite befragt werden muss, ist zu messen.
- **Es gibt kein SpatiaLite-Testgerüst.** Die drei anderen Dialekte haben
  Testcontainers; SpatiaLite wird heute nur über einen Shell-Smoke gegen das
  CLI-Image berührt. Ein Live-Beleg braucht zuerst diesen Unterbau.

## Umfang

- ~~Testgerüst für SpatiaLite~~ — **erledigt 2026-08-30**: die
  `integration-test`-Stage bringt `libsqlite3-mod-spatialite` mit, die Tests
  laufen ohne Testcontainers gegen eine Datei.
- ~~Lesepfad: Geometriespalten erkennen und als WKB liefern~~ — **erledigt**:
  `SqliteDataReader` fragt die Verbindung (`spatialite_version()`), wickelt in
  `ST_AsBinary` und erkennt die Spalte am deklarierten Typnamen. Live belegt:
  21 Byte WKB statt 60 Byte Eigenformat; ohne Extension bleibt der rohe BLOB,
  statt an einer unbekannten Funktion zu scheitern.
- ~~Schreibpfad: WKB als `GeomFromWKB(?, srid)` binden~~ — **erledigt**: die SRID
  kommt aus SpatiaLites `geometry_columns`. Ohne sie scheitert der Einsatz an
  einem Trigger (`geom-type or SRID not allowed`) — gemessen, beide Formen
  gegeneinander.

## Was nicht mehr gilt

Drei Punkte der früheren Fassung sind nachgemessen erledigt:

- **`JdbcToNeutralTypeMapper.mapOther` macht aus Geometrie `Text`.** Richtig,
  aber folgenlos: Geometrie nimmt diesen Weg nicht. Die Markierung kommt aus der
  Metadaten-Vorabfrage und überschreibt das Mapping im `ChunkSchema`. Die Datei
  trägt beide Aussagen — ein Kommentar behauptet noch die alte.
- **`--spatial-profile` hängt nicht an `data transfer`/`data import`.** Richtig,
  aber die falsche Abhilfe: die Kommandos erzeugen kein DDL. Die Schreibpfade
  holen die SRID aus dem **Zielkatalog** (`geometry_columns` bzw.
  `information_schema.columns.srs_id`) und binden `ST_GeomFromWKB(?, srid)` —
  das trägt weiter als ein Flag, das der Aufrufer richtig setzen müsste.
- **Der Import-Preflight winkt Geometrie bedingungslos durch.** Behoben: er
  verlangt ein Geometrie-Ziel und lehnt insbesondere Geometrie → Text ab, weil
  der Wertpfad WKB liefert und daraus in einer Textspalte Binärmüll würde.
