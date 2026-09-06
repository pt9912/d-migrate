---
id: mssql-object-rename-policy-missing
title: "MSSQL hat keine ObjectRenamePolicy — jedes View-/Sequenz-/Trigger-Rename-Overlay stuerzt ab"
status: open
---

# `ObjectRenamePolicyRegistry` kennt MSSQL nicht

## Befund

`ObjectRenamePolicyRegistry.policies` (`ObjectRenamePolicy.kt`) fuehrt nur
PostgreSQL, MySQL und SQLite. `forDialect` greift mit `getValue` zu, was
bei einem fehlenden Schluessel `NoSuchElementException` wirft — kein
Blocker, kein Diagnose-Code, ein Abbruch.

`MssqlObjectRenamePolicy` existiert nicht. MSSQL ist damit erreichbar
betroffen: `schema migrate` ist fuer MSSQL freigeschaltet, und
`OperationMapper` ruft `RenameObjectMapper.foldRenameViews` /
`foldRenameSequences` / `foldRenameTriggers` **ohne Dialekt-Waechter**
auf. Einziger Schutz ist `mappings.isEmpty()`: solange kein
Rename-Overlay eine View, Sequenz, Routine oder einen Trigger nennt,
wird die Registry nie befragt.

Reproduktion: `schema migrate` gegen ein MSSQL-Ziel mit einem
Rename-Overlay, das ein View-Rename mappt.

Nicht zu verwechseln mit der **anderen** Registry:
`RenameDependencyPolicy.forDialect` fuehrt MSSQL sehr wohl
(`MssqlRenameDependencyPolicy`). Es fehlt nur die Objekt-Rename-Seite.

## Herkunft

Aufgefallen beim Oracle-Sub-Slice 5e-1, der fuer Oracle denselben Eintrag
brauchte und ihn dort geschlossen hat (`OracleObjectRenamePolicy`). Dass
MSSQL dieselbe Luecke traegt, war unbekannt.

## Moegliche Loesungsrichtungen

1. `MssqlObjectRenamePolicy` bauen. SQL Server benennt ueber `sp_rename`
   um — **live zu messen**, welche Objektarten das wirklich abdeckt und
   ob der Rumpf dabei unangetastet bleibt; die vorhandene
   `MssqlRenameDependencyPolicy` hat dazu schon Befunde
   (`sys.sql_modules` behaelt den alten Text).
2. Unabhaengig davon `forDialect` total machen: ein fehlender Eintrag
   sollte ein `RenameSupport.Blocked` mit Diagnose-Code liefern, keinen
   `NoSuchElementException`. `getValue` ist an einer Registry, die pro
   Dialekt waechst, die falsche Zugriffsart.

Richtung 2 ist unabhaengig von Richtung 1 umsetzbar und wandelt den
Absturz in definiertes Verhalten.
