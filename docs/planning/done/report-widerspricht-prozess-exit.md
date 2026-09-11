---
id: report-widerspricht-prozess-exit
title: "Der Report sagt `ok`, der Prozess endet mit 5"
status: done
---

# Der Report sagt `ok`, der Prozess endet mit 5

## Der Befund (gemeldet aus einem Konsumentenprojekt, isoliertes Repro)

Ein `schema migrate --execute --report out/plan.yaml` endet mit **Exit 5**
(Post-execute-Drift). Die geschriebene Reportdatei sagt dazu:

```
status: ok
exitCode: 0
blockers: []
diagnostics: []
execution.completed: True
execution.executionError: None
```

Die Drift-Information existiert **ausschliesslich** im Prozess-Exit und in
einer `stderr`-Zeile. Wer den Report auswertet — und genau dafuer ist er da,
`--execute` verlangt ihn sogar (`--execute requires --report (audit-trail)`) —
bekommt die Auskunft, alles sei in Ordnung.

## Warum (gemessen im Code)

`SchemaMigrateReportBuilder` bildet `status`/`exitCode` aus dem **Plan**:
`blocked`/`8`, wenn der Render-Pfad blockt, sonst `no_op` oder `ok`/`0`. Der
Post-Compare laeuft danach. Sein Ergebnis geht als `baseExit` an
`SchemaMigrateArtefactSink.emitReportAndExit` — das den Report **unveraendert**
schreibt und `baseExit` zurueckgibt:

```kotlin
request.report?.let { reportPath ->
    if (writeReport(reportPath, finalReport, request.reportFormat) == null) return 7
}
return baseExit
```

Damit koennen Report und Prozess auseinanderlaufen, und zwar bei jedem Ausgang,
den erst der Post-Compare bestimmt: Drift (5), fehlgeschlagene
Nach-Introspektion (5) und ein fehlgeschlagener Artefakt-Schreibvorgang (7).

## Was der Schnitt herstellen muss

1. **Der Report traegt denselben Ausgang wie der Prozess.** `exitCode` im
   Report ist der Wert, mit dem der Lauf endet — nicht der, den der Plan
   vorhersagte. Das ist eine Invariante, keine Fallunterscheidung: sie gehoert
   an die eine Stelle, die beides in der Hand hat.
2. **Der Grund steht im Report, nicht nur auf `stderr`.** Ein Lauf, der an
   Drift scheitert, traegt dazu eine Diagnose mit Code und Meldung — sonst
   bleibt der Report bei jedem Ausgang ausser `blocked` aussagelos.
3. **Das Statuswort muss den Ausgang aushalten.** Heute kennt der Builder
   `ok`, `no_op` und `blocked`. Ein mit 5 beendeter Lauf ist keines davon.

## Nachbarfrage, ungemessen

`ViewDefinition.sourceDialect` fiel aus dem Post-Compare heraus und erzeugte
Scheindrift (behoben). **Funktionen, Prozeduren und Trigger tragen dasselbe
Feld**, und auch dort setzt es der Rueckleser, waehrend die Schemadatei es
nicht traegt. Ob ihr Pfad dieselbe Schieflage hat, ist nicht gemessen — es
gehoert vor die naechste Aenderung an dieser Stelle.

## Umgesetzt (2026-09-11)

Abgeglichen wird in `SchemaMigrateArtefactSink.emitReportAndExit` — der einen
Stelle, die Report und Ausgang in der Hand hat. Ein Lauf, dessen Ausgang erst
der Post-Compare bestimmt, traegt `status: failed`, denselben `exitCode` wie
der Prozess und eine Diagnose mit dem Grund (`POST_EXECUTE_DRIFT`,
`POST_EXECUTE_INTROSPECTION_FAILED`). Der Vertrag steht in `cli-spec.md` bei
den `--execute`-Report-Feldern; `SchemaMigrateReportMatchesExitTest` haelt ihn
fest und faellt, wenn man den Abgleich zur Leeroperation macht.

Die Nachbarfrage zu Funktionen, Prozeduren und Triggern bleibt offen — sie ist
unten beschrieben und wurde bewusst nicht mitgenommen.

## Herkunft

Gemeldet 2026-09-11 gegen 1.3.0, mit isoliertem Repro (frisches PostgreSQL 18,
leere Datenbank, Image per Digest gepinnt). Zweiter von zwei Befunden; der
erste — Scheindrift auf frisch angelegten Sichten — ist behoben.
