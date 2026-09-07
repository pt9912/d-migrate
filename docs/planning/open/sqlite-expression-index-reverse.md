---
id: sqlite-expression-index-reverse
title: "SQLite liest einen Index über einem Ausdruck unvollständig zurück"
status: open
---

# SQLite: Ausdrucks-Index kommt unvollstaendig zurueck

## Befund

`PRAGMA index_xinfo` liefert fuer eine Ausdrucksposition `name = NULL`, und
`SqliteMetadataQueries` wirft solche Positionen ueber `mapNotNull` weg. Ein
Index ueber `(a, UPPER(nm))` kommt deshalb als `(a)` zurueck — falsch, ohne
Meldung. Ein Index NUR ueber einem Ausdruck faellt ganz heraus.

Aufgefallen beim Review von Slice 6b. PostgreSQL hatte dasselbe Problem und
ist dort geloest; Oracle und MySQL ebenfalls.

## Warum es nicht mitgemacht wurde

SQLite fuehrt den Ausdruckstext **nirgends im Katalog**. Anders als
PostgreSQL (`pg_get_indexdef`), Oracle (`ALL_IND_EXPRESSIONS`) und MySQL
(`information_schema.statistics.EXPRESSION`) steht er nur im
urspruenglichen `sqlite_master.sql`-Text der `CREATE INDEX`-Anweisung. Ihn
zu gewinnen heisst, diese Anweisung zu **parsen** — ein eigener Entwurf mit
eigenem Risiko, und keiner, den man nebenbei richtig macht.

## Zu klaeren

1. Ob das Repo schon einen SQLite-DDL-Parser hat, auf dem sich aufbauen
   liesse (der Rebuild-Pfad liest `sqlite_master` bereits).
2. Ob bis dahin eine **Meldung** die bessere Antwort ist als das stille
   Verkuerzen — der Index kaeme dann weiterhin unvollstaendig zurueck, aber
   sichtbar.
