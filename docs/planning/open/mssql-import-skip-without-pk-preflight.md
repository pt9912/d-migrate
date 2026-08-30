---
id: mssql-import-skip-without-pk-preflight
title: "`data import --on-conflict skip` ohne Primärschlüssel meldet sich spät"
status: open
---

# `--on-conflict skip` ohne Primärschlüssel meldet sich spät

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
