---
id: mssql-import-skip-without-pk-preflight
title: "`data import --on-conflict skip` ohne Primärschlüssel meldet sich spät"
status: resolved
---

# `--on-conflict skip` ohne Primärschlüssel meldet sich spät

> **Erledigt.** `DataImportHelpers.validateDialectCapabilities` prüft es jetzt
> mit, dort wo schon `--disable-fk-checks` und `--trigger-mode` geprüft werden
> — **vor** der ersten Verbindung. Die Meldung nennt alle betroffenen
> Tabellen auf einmal, nicht nur die erste, an der es scheitern würde.
>
> **Woher das Schema kommt:** aus der `--schema`-Datei. Sie trägt die
> Primärschlüssel, und der Import liest sie ohnehin schon im Preflight
> (`DataImportSchemaPreflight`). Ohne `--schema` gibt es vor der ersten
> Verbindung nichts zu prüfen; dort bleibt es bei der späten Meldung beim
> Öffnen der Tabelle. Das ist keine Lücke, sondern die Grenze des Dateiwegs —
> und sie ist im Code benannt.
>
> Belegt über die echte CLI (`MssqlCommandGateE2ETest`), containerlos: die
> Ziel-URL zeigt auf einen Port, an dem niemand lauscht. Exit 2 kommt vor dem
> Verbindungsversuch — ohne den Wächter endet derselbe Aufruf mit Exit 4.

## Lage

Der Transfer-Pfad lehnt `--on-conflict skip` ohne Primärschlüssel im Preflight
ab (`DialectCapabilities.requiresPrimaryKeyForSkip`). Der **Import**-Pfad hat
an dieser Stelle keinen Schema-Preflight und meldet es erst beim Öffnen der
Tabelle.

Beide Wege kommen zum selben Ergebnis; sie kommen nur unterschiedlich früh
dorthin. Ein Lauf über viele Tabellen bricht damit mittendrin ab, statt vorher.

## Was zu klären ist

Ob der Import denselben frühen Check bekommt — und wenn ja, woher er das Schema
nimmt: der Transfer kennt die Quelle, der Import liest aus einer Datei.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans, dort ohne Slice-Zuordnung.
