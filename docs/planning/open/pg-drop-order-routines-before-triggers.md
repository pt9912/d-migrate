---
id: pg-drop-order-routines-before-triggers
title: "Der Plan raeumt Routinen vor den Triggern ab, die sie brauchen"
status: open
---

# Der Plan räumt Routinen vor den Triggern ab, die sie brauchen

## Lage

Entfernt eine Migration einen Trigger **und** die Funktion, die er aufruft, so
emittiert der Plan `DROP FUNCTION` vor `DROP TRIGGER`. PostgreSQL lehnt das ab:

```
ERROR: cannot drop function touch_updated_at() because other objects depend on it
  Detail: trigger last_updated on table users depends on function touch_updated_at()
```

Gemessen gegen PostgreSQL 16 mit dem Standardfall: eine Tabelle, eine
`RETURNS TRIGGER`-Funktion, ein Trigger darauf; Zielschema ohne beide.

## Warum es die Phasen nicht abfangen

`DiffPhase.ROUTINES` steht vor `DiffPhase.TRIGGERS` — richtig fürs **Anlegen**,
denn `CREATE TRIGGER … EXECUTE FUNCTION f()` verlangt, dass `f` schon steht.
Beim **Abräumen** gilt die umgekehrte Ordnung, und die Phasen werden für Drops
nicht gedreht.

## Umfang

- Klären, wo die Ordnung sitzt: dreht der Planner die Phasenfolge für
  Drop-Operationen, oder braucht es eine Abhängigkeitskante
  Trigger → Trigger-Funktion? Die zweite Form trägt weiter, weil sie auch den
  Fall „Funktion entfernt, Trigger bleibt" richtig blockt.
- Gilt für alle Dialekte mit Trigger-Funktionen als eigenständigem Objekt — das
  ist PostgreSQL. MySQL, SQLite und MSSQL tragen den Rumpf im Trigger selbst.
- Live-Test: Trigger und seine Funktion in einem Lauf entfernen.

## Herkunft

Aufgefallen beim Live-Test zum kanonischen Key
([`pg-diff-object-key-leak.md`](pg-diff-object-key-leak.md)); dort
herausgeschnitten, weil es eine andere Wurzel hat.
