---
id: mssql-object-rename-policy-missing
title: "MSSQL hat keine ObjectRenamePolicy — jedes View-/Sequenz-/Trigger-Rename-Overlay stuerzt ab"
status: resolved
---

# `ObjectRenamePolicyRegistry` kennt MSSQL nicht

> **Erledigt, beide Richtungen.**
>
> - **Richtung 2** war bereits gebaut: `forDialect` ist total und liefert fuer
>   einen fehlenden Eintrag `UnsupportedObjectRenamePolicy` mit
>   `OBJECT_RENAME_UNSUPPORTED` statt einer `NoSuchElementException`.
> - **Richtung 1** kommt jetzt dazu: `MssqlObjectRenamePolicy` stuft Sicht,
>   Sequenz, Trigger, Funktion und Prozedur als **nativ** umbenennbar ein
>   (`sp_rename`); Body-Drift und materialisierte Sichten blocken.
>
> **Gemessen, nicht angenommen** (Container, 2026-09-07):
>
> | Beobachtung | Ergebnis |
> | --- | --- |
> | `sp_rename` auf Sicht, Sequenz, Trigger, Funktion | benennt jedes Objekt um |
> | Sequenz nach dem Rename | Startwert 5 und Schrittweite 2 unveraendert |
> | `sys.sql_modules.definition` danach | sagt weiter `CREATE VIEW v_old` (ebenso Trigger, Funktion) |
>
> Der veraltete Text war der Grund, warum die Einstufung nicht von der
> Dokumentation abgeschrieben werden konnte. Er ist fuer das neutrale Modell
> **folgenlos**: `MssqlViewDefinitionScanner` und `MssqlRoutineBody` schneiden
> alles vor dem `AS` weg, und Signatur, Tabelle und Ereignis kommen aus den
> Katalogsichten. Der Reverse liefert nach dem Rename denselben Rumpf wie
> vorher — belegt in `MssqlObjectRenameIntegrationTest`. Faellt diese Aussage,
> ist `Native` falsch, und genau das prueft die Spezifikation.
>
> Nebenbefund mitbehoben: `spec/cli-spec.md` fuehrte im Per-Dialekt-Block nur
> PostgreSQL, MySQL und SQLite — Oracles Politik (Sub-Slice 5e-1) fehlte dort
> ebenfalls.

## Befund

`ObjectRenamePolicyRegistry.policies` fuehrte nur PostgreSQL, MySQL und
SQLite. `forDialect` griff mit `getValue` zu, was bei einem fehlenden
Schluessel `NoSuchElementException` warf — kein Blocker, kein Diagnose-Code,
ein Abbruch.

`MssqlObjectRenamePolicy` existierte nicht. MSSQL war damit erreichbar
betroffen: `schema migrate` ist fuer MSSQL freigeschaltet, und
`OperationMapper` ruft `RenameObjectMapper.foldRenameViews` /
`foldRenameSequences` / `foldRenameTriggers` **ohne Dialekt-Waechter** auf.
Einziger Schutz war `mappings.isEmpty()`: solange kein Rename-Overlay eine
View, Sequenz, Routine oder einen Trigger nannte, wurde die Registry nie
befragt.

Nicht zu verwechseln mit der **anderen** Registry:
`RenameDependencyPolicy.forDialect` fuehrt MSSQL sehr wohl
(`MssqlRenameDependencyPolicy`). Es fehlte nur die Objekt-Rename-Seite.

## Herkunft

Aufgefallen beim Oracle-Sub-Slice 5e-1, der fuer Oracle denselben Eintrag
brauchte und ihn dort geschlossen hat (`OracleObjectRenamePolicy`). Dass
MSSQL dieselbe Luecke traegt, war unbekannt.
