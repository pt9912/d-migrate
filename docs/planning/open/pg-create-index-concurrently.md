---
id: pg-create-index-concurrently
title: "PostgreSQL rendert `CREATE INDEX CONCURRENTLY` nicht"
status: open
---

# `CREATE INDEX CONCURRENTLY` wird nicht gerendert

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
