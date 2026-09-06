---
id: single-column-constraint-synthetic-name
title: "Einspaltige UNIQUE-/FK-Constraints werden mit erfundenen Namen gedroppt (dialektuebergreifend)"
status: open
---

# Einspaltige UNIQUE-/FK-Constraints werden mit erfundenen Namen gedroppt

## Befund

`TableComparator.normalizeConstraints`
(`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableComparator.kt`)
zieht **jedes** einspaltige UNIQUE und **jeden** einspaltigen Fremdschluessel
auf `singleColumnUnique`/`singleColumnForeignKeys` zusammen — nicht nur die,
die als `column.unique`/`column.references` modelliert sind, sondern auch
**benannte Tabellen-Constraints**. Der Name geht dabei verloren.

`compareConstraints` materialisiert das Delta anschliessend ueber
`syntheticUniqueConstraint`/`syntheticFkConstraint` neu und erfindet dafuer
die Namen `_unique_<spalte>` bzw. `_fk_<spalte>`. Nichts bildet sie auf den
Katalognamen zurueck (repo-weite Suche nach `_unique_` trifft nur diese eine
Datei).

Ein `DropConstraint` fuer ein einspaltiges UNIQUE rendert deshalb:

```sql
ALTER TABLE "users" DROP CONSTRAINT "_unique_email";
```

— gegen eine echte Datenbank `ORA-02443` (Oracle), analog in den anderen
Dialekten. Die Down-Richtung ist namenssymmetrisch, aber beide Seiten sind
von der Datenbank entkoppelt. Mehrspaltige Constraints sind nicht betroffen:
sie behalten ihren echten Namen.

## Reichweite: dialektuebergreifend, aelter als Oracle

Der Renderer ist nicht die Ursache — alle drei bereits ausgelieferten
Dialekte geben denselben Payload-Namen aus
(`PostgresDiffOtherOps`, `MssqlDiffObjectOps`), und der echte Name geht
schon beim Reverse verloren (`PostgresSchemaStructureReaders`,
`MssqlSchemaReader`, `MysqlSchemaReader`, `OracleSchemaReader`). Oracle
Sub-Slice 5b ist nur der Slice, in dem der Dialekt das Verhalten erbt; die
Entscheidung, einspaltige Constraints auf Spalteneigenschaften zu
normalisieren, ist aelter und bewusst (sie macht `column.unique` und einen
gleichwertigen Tabellen-Constraint vergleichbar).

## Moegliche Loesungsrichtungen (nicht vorentschieden)

1. **Namen mitfuehren statt erfinden**: `NormalizedConstraints` um den
   Ursprungsnamen erweitern, damit `syntheticUniqueConstraint` ihn
   wiederverwenden kann, wenn es einen gab. Loest den Fall „benannter
   Tabellen-Constraint", nicht den Fall `column.unique` (dort gibt es
   keinen Namen im Modell).
2. **Katalog-Lookup im Renderer**, wie MSSQL ihn fuer Default-Constraints
   schon faehrt (`sys.default_constraints`): den tatsaechlichen Namen zur
   Renderzeit nachschlagen. Loest beide Faelle, macht den Renderer aber
   verbindungsabhaengig.
3. **Constraint spaltenweise droppen**, wo der Dialekt das kann — fuer
   Oracle gibt es kein `DROP UNIQUE (spalte)`-Aequivalent, das schliesst
   den Weg dort aus.

Aktivierungsbedingung: ein `schema migrate --execute`, das einen
einspaltigen UNIQUE-/FK-Constraint entfernt. Bis dahin blockt nichts —
der Fehler entsteht erst beim Ausfuehren.
