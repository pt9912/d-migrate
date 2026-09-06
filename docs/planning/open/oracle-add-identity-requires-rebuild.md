---
id: oracle-add-identity-requires-rebuild
title: "Identity nachtraeglich hinzufuegen braucht in Oracle einen Tabellen-Neubau"
status: open
---

# Identity nachtraeglich hinzufuegen braucht in Oracle einen Tabellen-Neubau

## Befund

Oracle kann eine bestehende gewoehnliche Spalte **nicht** nachtraeglich zur
Identity-Spalte machen. `ALTER TABLE t MODIFY <col> GENERATED ALWAYS AS
IDENTITY` scheitert auf jeder Spalte, die nicht bereits Identity traegt, mit
`ORA-30673: column to be modified is not an identity column` — live gemessen
(2026-09-06, `gvenzl/oracle-free:23-slim-faststart`) in allen drei denkbaren
Auspraegungen: leere Tabelle, gefuellte Tabelle, Spalte mit NULL-Wert.

Die Gegenrichtung geht dagegen: `ALTER TABLE t MODIFY <col> DROP IDENTITY`
entfernt die Identity-Eigenschaft (verifiziert: danach nimmt die Spalte
explizite Werte an). Auch Praezisions- und Modus-Aenderungen an einer
BEREITS identity-tragenden Spalte laufen in-place (deshalb entfaellt fuer
Oracle MSSQLs Rebuild-Sub-Slice 5a-2).

Der Uebergang `Integer -> Identifier(autoIncrement = true)` ist damit der
einzige Identity-Fall, den der Diff-Pfad nicht ausdruecken kann.

## Heutiger Umgang (Sub-Slice 5a)

`OracleDiffTableOps.renderAlterColumnType` blockt diesen Fall mit
`ORACLE_ADD_IDENTITY_UNSUPPORTED` /
`MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION`, statt DDL zu
emittieren, die etwas anderes tut als die Operation behauptet (ein blankes
`MODIFY <col> NUMBER(9)` waere gueltiges SQL, das die Spalte NICHT zur
Identity-Spalte macht — ein stiller Fehlschlag). Die Blockade ist
richtungsabhaengig: die Down-Seite derselben Operation ist der
Entfernen-Fall und rendert sauber.

## Moegliche Loesung

Ein Tabellen-Neubau nach dem Muster von `MssqlRebuildPlanner`/
`MssqlRebuildRenderer` bzw. der SQLite-Rebuild-Sequenz: neue Tabelle mit
Identity-Spalte anlegen, Daten kopieren, alte Tabelle droppen, neue
umbenennen. Das ist ein eigener Renderer mit eigener Abnahme (Schluessel
und Zaehler muessen den Neubau ueberleben) — bewusst **nicht** Teil von
Slice 5, dessen Sub-Slice-Schnitt einen Rebuild-Pfad fuer Oracle explizit
als entbehrlich eingeordnet hat (was fuer alle uebrigen Identity-Faelle
auch stimmt).

Aktivierungsbedingung: ein belegter Bedarf, eine bestehende Spalte per
`schema migrate` zur Identity-Spalte zu machen. Bis dahin ist die
benannte Blockade das ehrliche Verhalten.
