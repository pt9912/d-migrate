# Tracker: eine Grenzänderung an einer Partitionierung hat keine Operation

> **Status:** Tracker / Vorabklärung (28.08.2026)
> **Trigger:** Sub-Slice 7c des MSSQL-Dialekts sollte `SPLIT`/`MERGE RANGE`
> rendern. Es stellte sich heraus, dass dem Renderer dafür nie etwas übergeben
> wird — die Lücke liegt im Hexagon, nicht im Dialekt.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan; sonst
> Trigger-Watch.

## Befund

`TableComparator` erkennt eine Partitionierungsänderung und legt sie als
`TableDiff.partitioning` ab. `OperationMapper.mapTablePartitioning` macht daraus
**keine** Operation, sondern eine Warnung:

> `PARTITIONING_CHANGE_NOT_APPLIED` — a partitioning change was detected but not
> emitted as a migration operation.

Das gilt für **alle vier Dialekte**. Die Begründung im Code ist richtig: eine
Tabelle lässt sich nicht in place umpartitionieren, es gibt kein
`ALTER TABLE … PARTITION BY`. Nur folgt daraus nicht, dass gar nichts geht —
die Dialekte haben sehr wohl Operationen für den häufigsten Fall, das
Hinzufügen und Entfernen einzelner Partitionen:

| Dialekt | Grenze hinzufügen / entfernen |
| --- | --- |
| PostgreSQL | `ATTACH PARTITION` / `DETACH PARTITION` |
| MySQL | `ADD PARTITION` / `DROP PARTITION` / `REORGANIZE PARTITION` |
| SQL Server | `ALTER PARTITION FUNCTION … SPLIT RANGE` / `MERGE RANGE` |
| SQLite | — kennt keine Partitionierung |

Die Warnung wirft alle Fälle in einen Topf: „Strategie gewechselt" (tatsächlich
nur über Neubau erreichbar) und „eine Partition ist dazugekommen" (in jedem
Dialekt eine einzelne Anweisung).

## Warum das mehr als Bequemlichkeit ist

Rollierende Partitionierung ist der Normalfall, nicht der Sonderfall: monatlich
eine neue Partition anlegen, die älteste abhängen. Wer das mit d-migrate fährt,
bekommt heute bei **jedem** Lauf eine Warnung und muss die eine Anweisung von
Hand nachziehen. Der Post-Compare meldet die Abweichung anschließend korrekt —
das Werkzeug weiß also, dass etwas fehlt, und sagt nur nicht, was.

## Arbeitspakete (Skizze)

1. Den Änderungsfall aufteilen: hinzugefügte/entfernte Kinder gegenüber
   geänderter Strategie, geändertem Schlüssel, geänderten Grenzen bestehender
   Kinder.
2. Eine `DiffOperation` für den auflösbaren Teil (Kind hinzu/entfernt); der
   Rest behält die Warnung, jetzt aber mit benanntem Grund.
3. Renderer je Dialekt, inklusive der Reihenfolge-Eigenheiten (SQL Server muss
   vor einem `SPLIT` erst die Filegroup des Scheme setzen: `ALTER PARTITION
   SCHEME … NEXT USED`).
4. Rückbaurichtung: `DETACH` ist umkehrbar, `DROP PARTITION` in MySQL nicht —
   das ist eine destruktive Operation und muss als solche gelten.

## Reichweite

Cross-dialektal, mit Ursprung im Hexagon. Kein MSSQL-Thema, auch wenn es dort
auffiel — dass SQL Server als einziger Dialekt eine eigene Anweisung dafür hat
(`ALTER PARTITION FUNCTION`), machte nur sichtbar, dass die Operation fehlt.
