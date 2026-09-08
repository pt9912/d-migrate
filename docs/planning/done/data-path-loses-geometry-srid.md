---
id: data-path-loses-geometry-srid
title: "Der Datenpfad verliert die SRID einer Geometrie, ohne es zu melden"
status: resolved
---

# SRID-Verlust im Datenpfad bleibt unbemerkt

> **Erledigt, ueber Loesungsrichtung 2** — und die erwies sich als die
> kleinere: `DataTransferRunner` liest das Quellschema ohnehin schon, die
> SRID stand also bereits zur Verfuegung. Sie wandert jetzt ueber
> `ImportOptions.sourceGeometrySrids` je Tabelle zur Import-Sitzung und tritt
> dort ein, wo die Zielspalte keine fuehrt.
>
> **Der Zielwert hat Vorrang**: steht am Ziel eine SRID, ist sie die
> verbindliche Aussage ueber die Spalte, in die geschrieben wird.
>
> Richtung 1 (ein Diagnosekanal am Import-Port) wurde damit nicht noetig —
> es gibt nichts mehr zu melden, wo die Angabe nicht mehr verloren geht. Fuer
> `data import` aus einer Datei ohne Schemaangabe bleibt es beim bisherigen
> Verhalten; dort gibt es keine Quelle, aus der die SRID kaeme.

## Live verifiziert

Auf drei Ebenen, weil jede eine andere Naht traegt:

| Ebene | Spezifikation |
| ----- | ------------- |
| Adapter gegen echtes Oracle | `OracleSpatialIntegrationTest` — Reverse liest die SRID einer Quelltabelle MIT Metadatenzeile, die Import-Sitzung schreibt sie in eine quoted-lowercase Zieltabelle OHNE; eine Kontrolltabelle ohne die Angabe bleibt SRID-los |
| Kommando als eigener Prozess | `OracleSpatialTransferE2ETest` (`test/e2e-cli`) — `schema reverse` gegen PostGIS, `schema generate --target oracle`, Anwenden, `data transfer`; danach `SDO_SRID` am Ziel |
| Ausgeliefertes Image | `make sample-db-spatial-ora-smoke` — dieselbe Kette ueber `d-migrate:dev` gegen die Compose-Dienste, zusaetzlich mit einer SRID-losen Quellspalte als Gegenprobe |

Der Adaptertest wurde gegengeprueft, indem der Rueckgriff auf
`options.sourceGeometrySrids` entfernt wurde: genau diese eine Spezifikation
schlug fehl, die uebrigen sieben blieben gruen.

## Befund

`data transfer`/`data import` binden eine Geometrie als WKB und bauen sie
im Ziel ueber einen dialekteigenen Konstruktor wieder auf. WKB traegt
selbst keine SRID; sie kommt aus [`TargetColumn.srid`], das der Writer aus
den **Zielmetadaten** fuellt.

Fuer zwei der fuenf Dialekte gibt es diese Metadaten nicht:

- **Oracle**: die SRID stuende in `USER_SDO_GEOM_METADATA`. Oracle hebt
  Tabellen- und Spaltennamen in dieser Zeile bedingungslos hoch
  (`MDSYS.SDO_GEOM_TRIG_INS1`), d-migrate quotiert dagegen wortgetreu und
  erzeugt kleingeschriebene Tabellen — fuer sie laesst sich die Zeile gar
  nicht ablegen. `enrichGeometrySrid` liefert deshalb immer `null`.
- **SQL Server**: die SRID ist dort Eigenschaft des Werts, nicht der
  Spalte; `MssqlInsertSql.placeholder` setzt einen Default (0 bzw. 4326).

Folge: eine PostGIS-Spalte `geometry(Point,4326)` landet in Oracle mit
`SDO_SRID = NULL`. Die Werte bleiben raeumlich brauchbar — auch ein
Spatial-Index gelingt darauf —, aber die Zuordnung zu einem
Koordinatensystem ist weg.

**Und zwar stumm.** `W120` entsteht nur im Generate-Pfad
(`OracleColumnConstraintHelper`, `MssqlColumnConstraintHelper`); wer Daten
gegen ein bereits bestehendes Ziel transferiert, sieht nichts.

## Warum es nicht im Slice behoben wurde

Der Datenpfad fuehrt heute **keinen** Meldekanal: `FinishTableResult` traegt
nur Sequenz-Anpassungen und einen Fehler, und in den Treibern gibt es kein
Logging. Eine Warnung einzufuehren heisst, einen geteilten Port
(`hexagon/ports-write`) um einen Diagnosekanal zu erweitern und ihn durch
alle fuenf Treiber und den Runner zu ziehen — ein eigener Slice, keine
Randnotiz an Oracle Spatial.

## Moegliche Loesungsrichtung

Zwei Teile, in dieser Reihenfolge:

1. Einen Diagnosekanal am Import-Port (etwa `FinishTableResult.Success`
   um eine Notizliste erweitern), den der Runner in den Bericht faltet.
   Er nuetzt ueber Spatial hinaus — der leere String auf Oracle und die
   Identity-Umschaltung haetten dort ebenfalls etwas zu sagen.
2. Die SRID aus dem **Quellschema** nehmen statt aus den Zielmetadaten.
   `data transfer` kennt beide Seiten; heute fragt der Writer nur das
   Ziel, das sie in zwei Dialekten nicht fuehren kann.

## Verweise

- Render- und Reverse-Regeln: [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md)
  (Abschnitt Spatial), [`spec/type-mapping.md`](../../../spec/type-mapping.md)
- Betroffen: [`OracleDataWriter.kt`](../../../adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleDataWriter.kt),
  [`MssqlInsertSql.kt`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlInsertSql.kt)
