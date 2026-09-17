# Tracker: notiz-allein verworfene Objekte (Notiz ohne `skipped_objects`-Eintrag)

> **Status:** Vorabklärung / Trigger-Watch (2026-09-16)
> **Trigger:** Zwei Befunde aus Bau und DoD-Prüfung des Slices
> [`../done/konsumentenbefunde-170-skipped-schemaref-views.md`](../done/konsumentenbefunde-170-skipped-schemaref-views.md)
> (dort Abschnitt „Restflächen"). Dieser Slice hat die Klasse an **einer** Naht
> geschlossen („Objektverluste zaehlen — die Naht ersetzt die zwei Mapper",
> `ff3866e9f`); zwei Stellen fallen weiterhin notiz-allein aus.
> **Bedingung erfüllt (2026-09-16):** der Zähler trägt inzwischen den Ausgang —
> `schema generate` endet bei einem fehlenden Objekt mit Exit 8
> ([`../in-progress/action-required-bestimmt-den-ausgang.md`](../in-progress/action-required-bestimmt-den-ausgang.md),
> P1), und MCP liefert `skippedCount`. Eine notiz-allein verworfene Stelle ist
> damit ein Lauf, der als vollständig gilt, obwohl er es nicht ist. Wird nach dem
> Compare-Slice als `next/`-Plan geschnitten (der Compare-Slice ist seit
> 2026-09-17 graduiert).
> **Aktivierungsbedingung:** Wird priorisiert, sobald ein Konsument die
> **Zählung** als Vollständigkeitsmaß nutzt (MCP `skippedCount`, Exit-Code) oder
> ein Dialekt-Vergleich daran hängt — dann ein `next/`-Plan mit je Dialekt einer
> Fixture.

## Die zwei Stellen

**1 — `RawSqlExpressionPortability.indexRefusal`** (`driver-common`). Ein Index,
dessen Ausdruck sich nicht übertragen lässt, fällt mit Notiz und **ohne**
`skipped_objects`-Eintrag aus der Ausgabe. Der Helfer wird von **allen fünf**
Dialekten gerufen (u. a. `OracleIndexDdlBuilder.kt`, `MysqlIndexPartitionDdlHelper.kt`,
`MssqlIndexDdlHelper.kt`) — die Lücke ist also nicht dialektspezifisch, sondern
eine der gemeinsamen Naht.

**2 — der Zusammengesetzte Typ (E054)** fällt **dialektungleich** aus:

| Dialekt | heute |
| --- | --- |
| SQLite | **zählt** — `SkippedObject("constraint", …, code = "E054")` (`SqliteColumnConstraintHelper.kt:227`) |
| Oracle | Notiz allein — `ManualActionRequired(…).toNote()` (`OracleDdlGenerator.kt:88-94`) |
| MSSQL, MySQL | Notiz allein (dieselbe Klasse) |

Derselbe Verlust wird damit je nach Ziel einmal gezählt und einmal nicht.

## Warum das zählt

Notiz und Zähler sind zwei Auskünfte über **dasselbe** Ereignis: die Notiz sagt,
*was* nicht übertragen wurde; der Eintrag in `skipped_objects` macht es
*zählbar* — und der Zähler trägt den Ausgang (Exit 8 bei Objektverlust, s. den
`OracleTransferE2ETest`). Wo nur die Notiz entsteht, ist die Auslassung für einen
maschinellen Abnehmer unsichtbar: sie steht im Report, aber nicht in der Zahl,
und ein Konsument, der auf die Zahl sieht, hält den Lauf für vollständig.

## Nicht hier

- **Die Naht ist gebaut.** `ManualActionRequired` (`hexagon/ports-read`) bietet
  `record`/`skippedStatement`; ihr KDoc nennt die bewusste Ausnahme (der
  Fremdschlüssel-Fall in `MssqlColumnConstraintHelper`). Ob eine Stelle sie
  benutzen **soll**, ist eine Entscheidung je Stelle — nicht jede Notiz ist ein
  Objektverlust.
- **Die Anwendersicht.** `action_required` ist im Anwenderhandbuch beschrieben;
  ein Nachtrag dort gehört zum Paket, das die Stellen umstellt, nicht hierher.
- **Die Oracle-`E054`-Zeile ist kein Einzelfall:** wer die Klasse schließt, sollte
  die übrigen `toNote()`-Aufrufe im selben Zug inventarisieren, statt nur die
  beiden bekannten Stellen zu treffen.

## Referenzen

- Herkunft: [`../done/konsumentenbefunde-170-skipped-schemaref-views.md`](../done/konsumentenbefunde-170-skipped-schemaref-views.md),
  Abschnitt „Restflächen".
- Die Naht: `ManualActionRequired` in `hexagon/ports-read`.
