---
id: column-generation-diff-unmapped
title: "Eine Aenderung an `generation` plant gar keine Operation"
status: open
---

# Eine Aenderung an `generation` plant gar keine Operation

## Befund

`ColumnDiff` fuehrt ein Feld `generation`, und `TableComparator` fuellt es
(`TableComparator.kt`, `generationDiff`). `OperationMapper.mapColumnChange`
liest davon aber nur `type`, `required` und `default` — **`cd.generation`
wird nirgends zu einer `DiffOperation`**. Der einzige weitere Leser ist
`RenameIntraObjectDeltaSynthesizer`, der es als Restfläche notiert
(„column generation drift … — T5"), nicht als Operation.

Folge: ein Soll-Schema, das eine Spalte von gewoehnlich auf
`generation: identity` (oder umgekehrt, oder von `ALWAYS` auf `BY DEFAULT`)
umstellt, **ohne** dabei den Typ zu aendern, plant nichts. `schema migrate`
meldet einen leeren Plan, fuehrt nichts aus — und der Post-Compare meldet
danach Drift, weil das Soll eben doch etwas anderes wollte.

Der Weg ueber den Typ funktioniert dagegen: `integer` →
`identifier + auto_increment` erzeugt ein `AlterColumnType`, und die Renderer
setzen es um (auf Oracle seit dem Tabellen-Neubau, siehe
[`oracle-add-identity-requires-rebuild.md`](../done/oracle-add-identity-requires-rebuild.md)).
Beide Schreibweisen meinen dasselbe — nur eine von beiden wird geplant.

## Warum das nicht nebenbei zu beheben ist

Es fehlt nicht nur die Zeile im Mapper, sondern die Operation selbst: es gibt
keinen `DiffOperation`-Typ fuer „Erzeugungsart einer Spalte aendern". Der
neu einzufuehrende Typ braucht

- eine Umkehrung und ein Risikoprofil je Richtung (Identity **entfernen** ist
  auf allen Dialekten leichter als sie hinzuzufuegen),
- eine Entscheidung je Dialekt, ob er sie in-place kann (Oracle: nur das
  Entfernen; SQL Server: gar nicht, dort erzwingt schon der Basistypwechsel
  einen Neubau; PostgreSQL: `ADD/DROP GENERATED`),
- und eine Antwort auf die Frage, wie sie sich zum bestehenden
  `AlterColumnType` verhaelt, das denselben Uebergang heute schon ausdrueckt.

## Aktivierungsbedingung

Ein belegter Bedarf, die Erzeugungsart **ohne** Typwechsel umzustellen — oder
ein Nutzer, der die zweite Schreibweise waehlt und einen leeren Plan
zurueckbekommt. Bis dahin ist der Weg ueber den Typ der dokumentierte.
