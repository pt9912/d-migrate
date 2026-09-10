# Oracle: eine Spalte, die per ALTER zum Enum wird, verliert ihren Wertevorrat

> Status: **GEBAUT** (2026-09-10).
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

## Gemessen (live, `gvenzl/oracle-free:23-slim-faststart`)

| Lauf | vorher |
| --- | --- |
| Textspalte anlegen | Exit 0 |
| dieselbe Spalte zum Enum machen | `MODIFY "mood" VARCHAR2(4000)`, kein CHECK → Exit 5, die Spalte wird auf 4000 **verbreitert** |
| derselbe Lauf noch einmal | plant dieselbe Anweisung erneut — konvergiert nie |
| direkt als Enum anlegen | Exit 0, `VARCHAR2(5)` + `ck_mood_probe_mood` |

Es fehlte also nicht nur der CHECK, sondern auch die Breite: `toSql` rendert
ein Enum als ungebundenes `VARCHAR2(4000)` — das ist der Fall **ohne** Werte,
den der Spalten-Helfer nie erreicht.

## Was gebaut wurde

Der ALTER-Pfad schreibt dieselbe Form wie `CREATE TABLE`, aus denselben
Bausteinen (`enumValuesOf` für die Auflösung, `OracleTypeMapper.enumWidth` für
die Breite, `enumCheckClause` für den Constraint):

1. den bestehenden Wertevorrat-`CHECK` lösen — nur wenn einer dasteht, denn
   Oracle kennt kein `DROP CONSTRAINT IF EXISTS` und liefe sonst in ORA-02443.
   Gefragt wird das zurückgelesene Schema, nicht der Spaltentyp: dort steht der
   Vorrat als eigener Constraint, während die Spalte nur `VARCHAR2` ist;
2. `MODIFY` auf `VARCHAR2(<längster Wert>)`;
3. den neuen `CHECK` anlegen.

`W134` bleibt für den Rest: ein Enum ohne Werte, oder ein `ref_type` auf eine
`DOMAIN` (die als CLOB dasteht) — dort gibt es nichts durchzusetzen.

Abgenommen live (`OracleAlterColumnEnumIntegrationTest`): Text→Enum ergibt
begrenzte Spalte + CHECK und konvergiert, eine geänderte Werteliste löst den
alten CHECK und legt den neuen an, die Datenbank weist einen Wert außerhalb des
Vorrats ab, und der ALTER-Pfad endet bei genau derselben Spalte wie
`CREATE TABLE`.

## Was zu klären war

- **Die Breite.** `VARCHAR2(<längster Wert>)` ist an den Vorrat gebunden; eine
  geänderte Werteliste ändert damit auch die Spaltenbreite — anders als bei
  PostgreSQL und SQLite, wo die Spalte `TEXT` bleibt. Verkleinert sie sich und
  passen die Daten nicht, lehnt Oracle mit `ORA-01441` ab. Das bleibt so: der
  Lauf scheitert dann laut, statt Daten zu verlieren.
- **Der Constraint-Name.** Oracle benennt deterministisch
  (`ck_<tabelle>_<spalte>`), das Lösen braucht also keinen erratenen Namen wie
  bei PostgreSQL. Gefragt wird trotzdem der Katalog: ein Constraint, der nicht
  von d-migrate stammt, trägt einen anderen Namen, und ein `DROP` darauf liefe
  in ORA-02443.
- **Die Reihenfolge.** Erst den alten CHECK lösen, dann `MODIFY`, dann den neuen
  anlegen — sonst weist der alte Vorrat Werte ab, die der neue erlaubt.


