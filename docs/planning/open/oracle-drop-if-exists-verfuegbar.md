# Oracle kennt `DROP … IF EXISTS` — der Generator behauptet das Gegenteil

## Befund

`OracleDdlGenerator.invertStatement` trägt die Begründung, Oracle kenne kein
`DROP … IF EXISTS` „auch nicht 23ai", und lässt die Klausel deshalb bei allen
Rücknahme-Anweisungen weg.

Gegen den Container gemessen, den die Integrationstests ziehen
(`gvenzl/oracle-free:23-slim-faststart`, Banner „Oracle AI Database 26ai Free
Release 23.26.3.0.0"), funktionieren **alle sechs** Formen:

```
DROP TABLE IF EXISTS …      Table dropped.
DROP VIEW IF EXISTS …       View dropped.
DROP SEQUENCE IF EXISTS …   Sequence dropped.
DROP INDEX IF EXISTS …      Index dropped.
DROP FUNCTION IF EXISTS …   Function dropped.
DROP TRIGGER IF EXISTS …    Trigger dropped.
```

Die Klausel kam mit Oracle 23ai; für ältere Bestände (19c und davor) gilt die
Aussage weiterhin.

## Warum das zählt

Ein Rücknahme-Skript ohne `IF EXISTS` bricht ab, sobald ein Objekt bereits
fehlt — genau der Fall, in dem man ein Rücknahme-Skript fährt. Der generische
Inverter in `AbstractDdlGenerator` setzt die Klausel aus diesem Grund; Oracle
ist der einzige Dialekt, der sie herausnimmt.

## Was zu entscheiden ist

Die Klausel ist **nicht** bedingungslos einsetzbar: d-migrate unterstützt
Oracle ab einer Version, die im Repo nicht festgelegt ist. Vor einer Änderung
zu klären:

1. Ab welcher Oracle-Version d-migrate Unterstützung zusagt. Steht das auf 23ai
   oder höher, kann der Inverter die Klausel schlicht setzen.
2. Sonst braucht es eine Fallunterscheidung an der Serverversion — und damit
   eine Information, die der Generator heute nicht hat: er rendert ohne
   Verbindung.

Option 3 wäre, es zu lassen und nur die Begründung im Code zu korrigieren,
damit sie nicht das Gegenteil des Messbaren behauptet.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleDdlGenerator.kt`
  (`invertStatement` samt Begründung)
- `adapters/driven/driver-oracle/src/test/kotlin/dev/dmigrate/driver/oracle/OracleDdlGeneratorObjectsTest.kt`
  (der Test „rollback drops CREATE TABLE/INDEX/SEQUENCE/VIEW without IF EXISTS
  (Oracle has no such clause)" pinnt die heutige Form samt Begründung)
