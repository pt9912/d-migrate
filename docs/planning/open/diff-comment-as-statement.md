---
id: diff-comment-as-statement
title: "Diff-Renderer legen Erklaerungen als SQL-Kommentar in den Anweisungsstrom — der Executor fuehrt sie aus"
status: open
---

# SQL-Kommentare als Anweisung im Diff-Pfad

## Befund

Mehrere Diff-Renderer emittieren ueber `ctx.emit(op, "-- …")` einen reinen
SQL-Kommentar als **Anweisung**, um eine Operation als „gerendert" zu buchen
oder eine Erklaerung im Skript zu hinterlassen:

| Stelle | Zweck |
| --- | --- |
| `MssqlDiffTableOps.kt:141` / `:194` | `DropTable`/`DropColumn` ohne Umkehrung |
| `MysqlDiffTableOps.kt:189` | dito |
| `MysqlDiffSequenceOps.kt:287` / `:303` | Atomic-Preserve-Audit, uebersprungener Down |
| `PostgresDiffSequenceOps.kt:99` / `:127` | dito |
| `PostgresDiffSqlBuilders.kt:100` | uebersprungener FULLTEXT-Index |
| *(MSSQL `MssqlDiffCustomTypeOps`)* | Typ „wird an der Spalte erledigt" |

Das ist kein reines Darstellungsproblem: `JdbcMigrationStatementExecutor`
(`JdbcMigrationStatementExecutor.kt`) fuehrt in beiden
Ausfuehrungspfaden **jede** Anweisung unveraendert aus —
`jdbcStmt.execute(stmt.sql)`, Zeile 120 bzw. 154, ohne Filter auf leere oder
kommentar-only SQL. Ein solcher Kommentar geht also als Anweisung an die
Datenbank.

**Fuer Oracle scheitert das.** Oracles JDBC lehnt eine reine
Kommentar-Anweisung mit `ORA-00900: invalid SQL statement` ab — gemessen am
Testcontainer (2026-09-06, urspruenglich am Header-Kommentar des
Generate-Pfads, Oracle-Slice-4a-Integrationstest). T-SQL, MySQL und
PostgreSQL nehmen einen Kommentar-Batch dagegen klaglos an; dort faellt das
Muster nur nicht auf.

## Warum es auch unabhaengig davon falsch liegt

`MigrationDdlResult` trennt die Kanaele sauber: `statements` traegt
auszufuehrendes SQL, `diagnostics` traegt Erklaerungen, und der Report
konsumiert beide getrennt. Eine Erklaerung in den Anweisungsstrom zu legen,
vermischt sie.

Der Vertrag verlangt den Kommentar auch nicht. Die Invariante in
`MigrationDdlResult.init` ist **einseitig**:

```kotlin
require(statements.flatMap { it.operationIds }.toSet().subtract(operationsRendered).isEmpty())
```

Jede Anweisung braucht eine gerenderte Operation — eine gerenderte Operation
braucht **keine** Anweisung. „Rendered mit null Statements" ist also
ausdruecklich zulaessig; es fehlte den Renderer-Kontexten nur eine Methode
dafuer, weil `emit()` der einzige Weg war, `operationsRendered` zu fuellen.

## Was Oracle bereits tut

`OracleDiffRenderContext.markRendered(op)` (Sub-Slice 5c) bucht eine
Operation als erledigt, ohne eine Anweisung zu erzeugen, und fuehrt die
Risiko-Buchhaltung wie `emit()` weiter (die Risiken stehen an der Operation,
nicht am Dialekt, und duerfen nicht verschwinden, nur weil dieser Dialekt
nichts auszufuehren hat). Die Begruendung geht als INFO-Diagnose in
`diagnostics`. Der Oracle-Treiber enthaelt seit 5c **keine**
Kommentar-Anweisung mehr — auch die unerreichbare `DropTable`-Down-Attrappe
wurde durch einen echten `skip` + `ROLLBACK_NOT_POSSIBLE` ersetzt.

## Reichweite und Dringlichkeit

Die meisten der oben genannten Stellen sind **ueber den Planner
unerreichbar**: die `NOT_REVERSIBLE`-Attrappen werden vom Dispatcher
abgefangen, bevor der Renderer laeuft (PostgreSQLs eigener Kommentar sagt
das ausdruecklich: „render-down is filtered upstream; the placeholder keeps
the emit path total"). Erreichbar sind die Sequenz-Audit-Kommentare
(MySQL/PostgreSQL) und der FULLTEXT-Fall (PostgreSQL) — dort werden sie
heute ausgefuehrt und von der jeweiligen Engine toleriert.

Damit ist es kein akuter Produktionsfehler, aber:

- ein handgebautes `DiffResult` (Artefakt-Deserialisierung) kann die
  Attrappen erreichen;
- jeder kuenftige Dialekt mit Oracles Strenge erbt den Fehler;
- die Skript-Ausgabe (`migrate --output`) enthaelt Pseudo-Anweisungen, die
  ein Operator nicht von echten unterscheiden kann.

## Moegliche Loesungsrichtungen

1. **`markRendered` in die uebrigen Renderer-Kontexte ziehen** (MSSQL,
   MySQL, PostgreSQL, SQLite) und die Kommentar-Emissionen darauf umstellen;
   Erklaerungen als INFO-Diagnose. Analog zur Oracle-Fassung.
2. **Im Executor filtern**: kommentar-only Anweisungen vor
   `jdbcStmt.execute` ueberspringen. Behebt das Ausfuehrungsproblem, nicht
   die Kanal-Vermischung — und verdeckt kuenftige Faelle.
3. **Beides**: (1) als Bereinigung, (2) als Netz.

Nicht betroffen ist der **Generate-Pfad**: dort sind Kommentare
(`AbstractDdlGenerator.generateHeader`, SQLites `sqlComment`-Faelle) legitim,
weil das Ergebnis eine Skriptdatei fuer Menschen und Fremdwerkzeuge ist und
nicht anweisungsweise von uns ausgefuehrt wird.

Aktivierungsbedingung: bevor ein weiterer Dialekt mit Oracles Strenge
hinzukommt, oder wenn ein Artefakt-deserialisiertes `DiffResult` je
ausgefuehrt werden soll.
