---
id: pg-create-index-concurrently
title: "PostgreSQL rendert `CREATE INDEX CONCURRENTLY` nicht"
status: resolved
---

# `CREATE INDEX CONCURRENTLY` wird nicht gerendert

> **Erledigt.** `schema migrate --pg-concurrent-indexes` rendert es, mit
> `transactionScope = NO_TRANSACTION` auf der Naht, die der
> No-Transaction-Slice gebaut hat.
>
> Die vier Fragen, gemessen beantwortet (PostgreSQL 16, live):
>
> | Frage | Entscheidung |
> | --- | --- |
> | Woher die Entscheidung kommt | Eine Option des **Laufs** (`--pg-concurrent-indexes`), global, kein Feld am Index. Per-Index waere eine Schema-Angabe — und `CONCURRENTLY` sagt nichts ueber das Schema, sondern darueber, wie migriert wird. |
> | Was nach einem Fehlschlag uebrig bleibt | Ein Index mit `pg_index.indisvalid = false` unter demselben Namen. Vor jedem nebenlaeufigen `CREATE` steht deshalb ein `DROP INDEX CONCURRENTLY IF EXISTS`: der naechste Lauf raeumt selbst auf, statt am Namen zu scheitern. Auf einem nicht vorhandenen Index kostet die Anweisung nichts (`NOTICE`, sonst nichts). |
> | Was der Rueckbau tut | Dasselbe: `DROP INDEX CONCURRENTLY`, ebenfalls ausserhalb der Transaktion. Die Option gilt fuer beide Richtungen. |
> | Wie der Report das sagt | Eine INFO-Diagnose `POSTGRES_INDEX_CONCURRENTLY` je Operation, die den `INVALID`-Rest und das Aufraeumkommando benennt — damit `PARTIAL_STATE_POSSIBLE` auf etwas Konkretes zeigt. |
>
> **Gemessen, nicht abgeschrieben:** dass die Anweisung in einer Transaktion
> abgelehnt wird, dass ein gescheiterter eindeutiger Index als `indisvalid =
> false` liegen bleibt, und dass `DROP INDEX CONCURRENTLY` derselben Regel
> unterliegt. `PostgresConcurrentIndexIntegrationTest` haelt alle drei fest,
> und dazu den Wiederanlauf: derselbe Plan ein zweites Mal scheitert an den
> Dubletten — nicht am schon vorhandenen Namen.
>
> Nicht dabei: der **Generate**-Pfad. Er schreibt eine Skriptdatei, die ein
> fremder Runner anwendet — moeglicherweise in einer Transaktion, in der die
> Anweisung scheitert. Die Option bleibt deshalb auf `schema migrate`, wo das
> Ausfuehrungsmodell bekannt ist.

## Lage

Ein Index auf einer großen, benutzten Tabelle sperrt sie beim Anlegen gegen
Schreibzugriffe. PostgreSQL bietet dafür `CREATE INDEX CONCURRENTLY`; d-migrate
rendert es nicht.

Der Grund war bis 2026-08-30 struktureller Art: die Anweisung ist in einer
offenen Transaktion verboten, und der Migrationslauf klammerte alles in eine.
Diese Naht steht jetzt — `TransactionScope.NO_TRANSACTION` ist ausführbar, und
SQL Servers Volltext-DDL nutzt sie bereits
([`no-transaction-execution-strategy.md`](../done/no-transaction-execution-strategy.md)).

## Was der Schnitt klären muss

- **Woher die Entscheidung kommt.** `CONCURRENTLY` ist kein Attribut des
  Schemas, sondern eine Aussage darüber, wie migriert werden soll — also eine
  Option des Laufs, kein Feld am Index. Ob sie global gilt oder je Index
  wählbar ist, entscheidet der Schnitt.
- **Was nach einem Fehlschlag übrig bleibt.** Ein abgebrochenes
  `CREATE INDEX CONCURRENTLY` hinterlässt einen **`INVALID`**-Index, der weder
  benutzt noch stillschweigend übergangen wird. Er muss erkannt und aufgeräumt
  werden — sonst scheitert der nächste Lauf am schon vorhandenen Namen.
- **Was der Rückbau tut.** `DROP INDEX CONCURRENTLY` gibt es ebenfalls, und es
  steht unter derselben Transaktionsregel.
- **Wie der Report das sagt.** Der Abschnitt läuft außerhalb der Transaktion;
  ein Fehlschlag danach meldet `PARTIAL_STATE_POSSIBLE`. Beim
  `CONCURRENTLY`-Index ist das nicht nur eine Warnung, sondern der Hinweis auf
  ein konkretes Aufräumen.

## Herkunft

Nicht-Scope des Slices, der die Ausführung außerhalb der Transaktion gebaut
hat. Der Renderer kennt die Klausel bis heute nicht; die Naht dafür steht.
