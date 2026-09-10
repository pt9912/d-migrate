# Oracle: eine Spalte, die per ALTER zum Enum wird, verliert ihren Wertevorrat

> Status: **offen** — Befund gemessen, Schnitt nicht gemacht.
> Trigger: Nebenbefund aus
> [`../done/enum-inline-check-fidelity.md`](../done/enum-inline-check-fidelity.md).

## Der Befund

Oracle hat keinen Enum-Typ; der Wertevorrat lebt als `VARCHAR2(<längster Wert>)`
plus benannter `CHECK`. Der `CreateTable`-Pfad des Diff-Renderers schreibt beides
— er teilt sich den Spalten-Helfer mit `schema generate`
([`OracleDiffTableOps`](../../../adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleDiffTableOps.kt)
ruft `columnHelper.generateColumnSql`).

`AlterColumnType` tut es nicht: dort wird **jede** Enum-Spalte zu ungebundenem
`VARCHAR2(4000)`, mit `W134` als Hinweis. Eine Spalte, die per ALTER zum Enum
wird oder deren Werteliste sich ändert, trägt den Vorrat danach nirgends.

Das ist derselbe Riss, den PostgreSQL und SQLite hatten, nur an einer anderen
Stelle — und er wiegt seit dem Enum-Slice schwerer: der Vergleich führt den
Wertevorrat als eigene Dimension der Spalte, ein Unterschied darin ist also
sichtbar und wird geplant. Was der ALTER-Pfad nicht schreibt, plant der nächste
Lauf erneut.

## Was zu klären ist

- **Die Breite.** `VARCHAR2(<längster Wert>)` ist an den Vorrat gebunden. Eine
  Änderung der Werteliste ändert damit auch die Spaltenbreite — ein echter
  Typwechsel, anders als bei PostgreSQL und SQLite, wo die Spalte `TEXT` bleibt.
  Verkleinert sich die Breite, muss Oracle die Daten prüfen (`ORA-01441`).
- **Der Constraint-Name.** Oracle benennt über
  `OracleColumnConstraintHelper.enumCheckName` (`ck_<tabelle>_<spalte>`)
  deterministisch — das Lösen braucht also keinen Katalog-Lookup wie bei
  PostgreSQL. Zu prüfen ist, ob bestehende Datenbanken denselben Namen tragen.
- **Die Reihenfolge.** Erst den alten CHECK lösen, dann `MODIFY`, dann den neuen
  anlegen — sonst weist der alte Vorrat Werte ab, die der neue erlaubt.

## Abnahme

Live gegen `gvenzl/oracle-free:23-slim-faststart`, analog zu
`PostgresEnumMigrateConvergenceIntegrationTest`: eine Spalte per ALTER zum Enum
machen, zweiter Lauf plant null Operationen, geänderte Werteliste wird
angewendet, und die Datenbank weist einen Wert außerhalb des Vorrats ab.
