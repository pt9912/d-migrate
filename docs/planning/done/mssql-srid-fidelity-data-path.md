---
id: mssql-srid-fidelity-data-path
title: "MSSQL-Datenpfad überträgt Geometrien mit der Typ-Default-SRID"
status: resolved
---

# Datenpfad überträgt Geometrien mit der Typ-Default-SRID

> **Erledigt.** `data transfer` liest die SRID jetzt aus den **Werten** der
> Quelle und bindet sie am Ziel. Der Typ-Default gilt nur noch, wo es gar
> keine Quellangabe gibt — bei `data import` aus einer Datei ohne Schema.
>
> Die drei Fragen, die der Schnitt zu klären hatte, sind so entschieden:
>
> | Frage | Entscheidung |
> | ----- | ------------ |
> | Woher die SRID kommt | `.STSrid`, ein `DISTINCT` je Geometriespalte, einmal vor dem Transfer ([`MssqlDataReader.geometrySrids`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDataReader.kt)) |
> | Gemischte SRIDs in einer Spalte | Ablehnung im Preflight (Exit 3), nicht Meldung je Wert |
> | Ob eine Meldung reicht | Nein — sie hätte den Verlust benannt, aber nicht verhindert |
>
> **Warum `DISTINCT` und nicht `TOP (1)`:** die erste Zeile verriete nur,
> dass es *eine* SRID gibt, nicht dass es *nur* diese gibt. Eine gemischte
> Spalte sähe damit einheitlich aus und käme geschlossen im falschen System
> an — genau der stumme Schaden, um den es hier geht. Der Durchlauf liest
> eine `int`-Projektion einer Spalte und wiegt gegen den Transfer selbst,
> der gleich alle Spalten aller Zeilen liest, wenig.
>
> Der Weg ist nicht MSSQL-eigen: [`DataReader.geometrySrids`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt)
> ist ein Port mit leerer Default-Antwort. Die übrigen Dialekte führen die
> SRID an der Spalte, ihr Schema trägt sie ohnehin — sie liefern hier
> nichts und zahlen nichts.

## Befund

`data export`/`import`/`transfer` lesen SQL-Server-Geometrien als WKB
(`.STAsBinary()`) und schreiben sie als `geometry::STGeomFromWKB(?, srid)` bzw.
`geography::STGeomFromWKB(?, srid)` zurück.

WKB trägt keine SRID. SQL Server führt sie am **Wert**, nicht an der Spalte —
beim Schreiben muss der Pfad also eine SRID setzen, und er setzte die des Typs:
0 für `geometry`, 4326 für `geography`.

Eine Tabelle, deren `geometry`-Spalte Werte in 25832 (UTM 32N) trägt, kam am
Ziel mit SRID 0 an. Die Koordinaten stimmten, ihre Bedeutung nicht: räumliche
Prädikate zwischen zwei so übertragenen Spalten rechneten anschließend im
falschen Bezugssystem, ohne dass jemand einen Fehler sah.

## Reichweite

Nur MSSQL als Quelle oder Ziel. PostGIS führt die SRID an der Spalte und
verliert sie deshalb nicht; SpatiaLite ebenso.

## Live verifiziert

| Ebene | Spezifikation |
| ----- | ------------- |
| Adapter gegen echtes SQL Server | `MssqlGeometrySridIntegrationTest` — Spaltenmetadaten schweigen, die Werte antworten; eine Spalte mit zwei Systemen wird als solche gelesen; der Import bindet die übergebene SRID statt des Typ-Defaults |
| Kommando als eigener Prozess | `MssqlSpatialTransferE2ETest` (`test/e2e-cli`) — SQL Server → PostGIS über die echte CLI: SRID 25832 kommt an, und die gemischte Spalte endet mit Exit 3 statt mit stillem Verlust |

## Was offen bleibt

- **`data export` → `data import` über eine Datei.** Der Dateiweg trägt kein
  Quellschema; dort gilt weiter der Typ-Default. Das Chunk-Format müsste die
  SRID je Spalte mitführen — ein eigener Schnitt, siehe
  [`raw-sql-text-drift`](../open/raw-sql-text-drift.md) für die verwandte Frage, was
  ein Artefakt über seine Herkunft aussagen muss.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans; stand bis 2026-08-30 als
Status-Satz in `spec/type-mapping.md`, wo er nicht hingehört (die Spec ist das
Zielbild, sie führt keinen Stand).
