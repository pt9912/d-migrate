---
id: diff-comment-as-statement
title: "Diff-Renderer legen Erklaerungen als SQL-Kommentar in den Anweisungsstrom — der Executor fuehrt sie aus"
status: resolved
---

# SQL-Kommentare als Anweisung im Diff-Pfad

> **Erledigt, ueber Richtung 3 — Bereinigung und Netz.**
>
> - **`markRendered` steht jetzt in allen fuenf Renderer-Kontexten.** Die
>   Kommentar-Emissionen in MSSQL, MySQL, PostgreSQL und SQLite sind darauf
>   umgestellt; die Erklaerungen sind INFO-Diagnosen mit eigenem Code
>   (`*_NOT_REVERSIBLE`, `*_ATOMIC_PRESERVE_DEFERRED`,
>   `*_PRESERVE_DOWN_SKIPPED`, `MSSQL_CUSTOM_TYPE_*`,
>   `POSTGRES_FULLTEXT_INDEX_WITHOUT_VECTOR`). Kein Diff-Renderer emittiert
>   noch einen reinen Kommentar.
> - **Der Executor filtert.** Eine Anweisung, deren Text nur aus Kommentar-
>   und Leerzeilen besteht, geht nicht an die Datenbank — das Netz fuer einen
>   artefakt-deserialisierten Plan aelteren Ursprungs.
>
> **Eine Ausnahme im Netz, die der Test gefunden hat:** ein Runner-Hook
> (`-- dmigrate:runner-hook=…`) ist ebenfalls ein reiner Kommentar, aber einer
> **mit Wirkung** — `JdbcRunnerHookHandler` faengt ihn ab und loest einen
> Seiteneffekt aus, statt ihn auszufuehren. Ein pauschaler Filter haette dem
> Lauf das Sichern und Wiederherstellen des SQLite-Fremdschluessel-PRAGMA
> genommen. Der Filter fragt den Hook-Parser deshalb zuerst.
>
> **Nebenbefund:** SQLites Kontext fuehrte bereits ein `markRendered`, das
> **ohne** Risiko-Buchfuehrung arbeitet — es bucht die Operationen, die ein
> Tabellen-Neubau miterledigt, deren Risiken an der Neubau-Anweisung haengen.
> Zwei verschiedene Dinge unter einem Namen; es heisst jetzt
> `markAbsorbedByRebuild`.
>
> `PostgresDiffSqlBuilders.createIndexSql` liefert fuer den Fall ohne
> `tsvector`-Spalte jetzt `null` statt eines Kommentartexts — der Aufrufer
> bucht und begruendet.

## Befund

Mehrere Diff-Renderer emittierten ueber `ctx.emit(op, "-- …")` einen reinen
SQL-Kommentar als **Anweisung**, um eine Operation als „gerendert" zu buchen
oder eine Erklaerung im Skript zu hinterlassen.

Das war kein reines Darstellungsproblem: `JdbcMigrationStatementExecutor`
fuehrte in beiden Ausfuehrungspfaden **jede** Anweisung unveraendert aus,
ohne Filter auf leere oder kommentar-only SQL. Ein solcher Kommentar ging
also als Anweisung an die Datenbank.

**Fuer Oracle scheitert das.** Oracles JDBC lehnt eine reine
Kommentar-Anweisung mit `ORA-00900: invalid SQL statement` ab — gemessen am
Testcontainer (2026-09-06, urspruenglich am Header-Kommentar des
Generate-Pfads). T-SQL, MySQL und PostgreSQL nehmen einen Kommentar-Batch
dagegen klaglos an; dort fiel das Muster nur nicht auf.

## Warum es auch unabhaengig davon falsch lag

`MigrationDdlResult` trennt die Kanaele sauber: `statements` traegt
auszufuehrendes SQL, `diagnostics` traegt Erklaerungen, und der Report
konsumiert beide getrennt. Eine Erklaerung in den Anweisungsstrom zu legen,
vermischt sie.

Der Vertrag verlangte den Kommentar auch nicht. Die Invariante in
`MigrationDdlResult.init` ist **einseitig**: jede Anweisung braucht eine
gerenderte Operation — eine gerenderte Operation braucht **keine** Anweisung.
„Rendered mit null Statements" war also ausdruecklich zulaessig; es fehlte den
Renderer-Kontexten nur eine Methode dafuer, weil `emit()` der einzige Weg war,
`operationsRendered` zu fuellen.

## Nicht betroffen

Der **Generate-Pfad**: dort sind Kommentare
(`AbstractDdlGenerator.generateHeader`, SQLites `sqlComment`-Faelle) legitim,
weil das Ergebnis eine Skriptdatei fuer Menschen und Fremdwerkzeuge ist und
nicht anweisungsweise von uns ausgefuehrt wird.
