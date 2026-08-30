---
id: mssql-bulk-import-fast-path
title: "MSSQL-Import läuft über gebatchte INSERTs, ohne Bulk-Pfad"
status: open
---

# MSSQL-Import ohne Bulk-Pfad

## Lage

Der MSSQL-Import schreibt über gebatchte `INSERT`s. SQL Server bietet mit
`BULK INSERT` und `SQLServerBulkCopy` einen Weg, der für große Mengen deutlich
schneller ist — analog zu PostgreSQLs `COPY`, das d-migrate dort nutzt.

Der Slice-Schnitt notierte für Slice 3 „Fast-Path später", ohne Slice-Nummer.
Damit ist es eine Durchsatz-Frage ohne Termin.

## Warum es kein Defekt ist

Der heutige Weg ist korrekt, nur langsamer. Er trägt alle Eigenschaften, die
der Import braucht — Konfliktmodi, Identity-Insert, Trigger-Verhalten. Ein
Bulk-Pfad müsste sie einzeln nachweisen, statt sie zu erben.

## Was der Schnitt klären muss

- **Welche der Import-Eigenschaften der Bulk-Weg trägt.** `SQLServerBulkCopy`
  kennt eigene Optionen für Identity und Constraints; `--on-conflict` hat dort
  keine Entsprechung.
- **Ab wann er sich lohnt.** Ein Messpunkt gegen den heutigen Weg gehört vor
  die Entscheidung, nicht danach.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans, dort ohne Slice-Zuordnung.
