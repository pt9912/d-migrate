---
id: mssql-srid-fidelity-data-path
title: "MSSQL-Datenpfad überträgt Geometrien mit der Typ-Default-SRID"
status: open
---

# Datenpfad überträgt Geometrien mit der Typ-Default-SRID

## Befund

`data export`/`import`/`transfer` lesen SQL-Server-Geometrien als WKB
(`.STAsBinary()`) und schreiben sie als `geometry::STGeomFromWKB(?, srid)` bzw.
`geography::STGeomFromWKB(?, srid)` zurück.

WKB trägt keine SRID. SQL Server führt sie am **Wert**, nicht an der Spalte —
beim Schreiben muss der Pfad also eine SRID setzen, und er setzt die des Typs:
0 für `geometry`, 4326 für `geography`.

Eine Tabelle, deren `geometry`-Spalte Werte in 25832 (UTM 32N) trägt, kommt am
Ziel mit SRID 0 an. Die Koordinaten stimmen, ihre Bedeutung nicht: räumliche
Prädikate zwischen zwei so übertragenen Spalten rechnen anschließend im
falschen Bezugssystem, ohne dass jemand einen Fehler sieht.

## Reichweite

Nur MSSQL als Quelle oder Ziel. PostGIS führt die SRID an der Spalte und
verliert sie deshalb nicht; SpatiaLite ebenso.

Die Eigenschaft ist in [`type-mapping.md`](../../../spec/type-mapping.md)
beschrieben — sie ist bekannt, nicht überraschend. Was fehlt, ist die
Übertragung.

## Was der Schnitt klären muss

- **Woher die SRID kommt.** `.STSrid` liefert sie je Wert; das ist eine zweite
  Projektion in der Leseabfrage und damit eine Breitenfrage, keine
  Korrektheitsfrage.
- **Was bei gemischten SRIDs in einer Spalte passiert.** SQL Server lässt sie
  zu; PostGIS mit typisierter Spalte nicht. Ein Ziel, das nur eine SRID je
  Spalte kennt, braucht eine Entscheidung — Ablehnung im Preflight oder
  Meldung je Wert.
- **Ob eine Meldung reicht.** Solange der Pfad die Default-SRID setzt, wäre ein
  W-Code an der Stelle, an der die Wert-SRID davon abweicht, die kleinere
  Stufe.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans; stand bis 2026-08-30 als
Status-Satz in `spec/type-mapping.md`, wo er nicht hingehört (die Spec ist das
Zielbild, sie führt keinen Stand).
