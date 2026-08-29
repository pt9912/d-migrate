---
id: spatial-read-path-geometry-detection
title: "Geometrie im Lese-Pfad: der generische JDBC-Mapper macht Text daraus"
status: open
---

# Geometrie im Lese-Pfad

## Lage

`JdbcToNeutralTypeMapper.mapOther` fällt für Geometrie-Typen auf
`NeutralType.Text(maxLength = null)` zurück — der generische `else`-Zweig. Die
dialektspezifischen Reverse-Reader erkennen Geometrie (PG über
`geometry_columns`, MySQL über `srs_id`, MSSQL über den Typnamen); der
generische JDBC-Weg, den der **Datenpfad** nimmt, tut es nicht.

Zwei Folgen stehen in derselben Tabelle des abgeschlossenen Spatial-Slices:

- **Wert-Bindung fehlt:** `--spatial-profile` ist an `DataTransferCommand` und
  `DataImportCommand` nicht verdrahtet.
- **Import-Preflight ist permissiv:** `ImportTypeCompatibility` beantwortet
  `Geometry -> true` bedingungslos und winkt damit auch Geometrie → Nicht-
  Geometrie durch.

## Warum das nicht der Spatial-Slice erledigt hat

Der Slice ist über den **DDL**-Pfad gegangen: Typen rendern, Indizes rendern,
SRID zurücklesen. Die drei Punkte oben liegen im **Daten**-Pfad und sind dort
als `❌ fehlt` / `⚠️ permissiv` festgehalten, ohne eigenen Schnitt.

## Umfang

- `mapOther` um eine Geometrie-Erkennung erweitern — die Frage ist, woran:
  JDBC liefert für Geometrie je nach Treiber `OTHER`, `BINARY` oder einen
  herstellerspezifischen Typnamen. Erst messen, dann schneiden.
- `--spatial-profile` auf die beiden Datenkommandos ziehen.
- `ImportTypeCompatibility`: Geometrie gegen Nicht-Geometrie ablehnen.

## Herkunft

Aus der Statustabelle in
[`../done/spatial-harness-slice.md`](../done/spatial-harness-slice.md), dort mit
`❌`/`⚠️` markiert und nicht weiter verfolgt.
