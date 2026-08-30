---
status: accepted
date: 2026-08-28
decision-makers: pt9912
consulted: docs/planning/done/mssql-dialect-scoping.md
informed: hexagon/core (IndexDefinition, MigrationFingerprint), hexagon/ports-common (DialectCapabilities), spec/schema.json, CHANGELOG.md
---

# Abdeckende und clustered Indizes stehen erstklassig im neutralen Modell, `schema-fingerprint-v8` → `v9`

> **Status: accepted (2026-08-28).** `IndexDefinition` trägt
> `includeColumns` und `clustered` als eigene Felder. Beide sind
> **semantisch**: sie stehen im Comparator, im Fingerprint und im
> `CanonicalPayload`, und der Diff-Pfad führt einen Unterschied aus.
> Dialekte, die eine der beiden Eigenschaften nicht ausdrücken können,
> kanonisieren sie im Fingerprint-Pfad weg — wie in
> [ADR 0026](0026-fingerprint-kanonisierung-post-compare.md) auf diesen Pfad
> begrenzt, `schema compare` bleibt streng.

## Kontext und Problemstellung

Zwei Index-Eigenschaften trug das neutrale Modell bis hierher nicht:

- **INCLUDE-Spalten** — Nicht-Schlüsselspalten eines abdeckenden Index. Sie
  stehen nur auf der Blattebene, gehen nicht in die Sortierung ein und zählen
  bei einem `unique`-Index nicht zur Eindeutigkeit. PostgreSQL trägt sie ab
  Version 11, SQL Server seit jeher.
- **clustered** — welcher Index die physische Ablage der Tabelle bildet. Nur
  SQL Server steuert das explizit.

Ohne sie liest der Reverse weniger, als der Server hergibt, und ein
Round-Trip verliert Struktur, die niemand als verzichtbar erklärt hat.

## Betrachtete Optionen

1. **Generate-only tragen** — gelesen und beim Generate rekonstruiert, aber aus
   Comparator und Fingerprint ausgeschlossen, wie `fullTextAccessMethod`
   ([ADR 0025](0025-fulltext-source-columns-as-index.md)).
2. **Erkennen, aber blocken** — der Vergleich sieht den Unterschied,
   `schema migrate` führt ihn nicht aus, sondern meldet ihn als Blocker.
3. **Voll vergleichen und ausführen.**

## Entscheidung

**Option 3.** Der Vergleich sieht beide Felder, und der Diff-Pfad setzt einen
Unterschied um.

Option 1 fiel, weil sie einen blinden Fleck erzeugt hätte: `schema compare`
meldete zwei Schemata als gleich, die es nicht sind. Genau diese Sorte Lücke
hat [ADR 0048](0048-enum-wertevorrat-im-fingerprint.md) gerade geschlossen, und
sie noch einmal aufzumachen wäre ein Rückschritt. Der Vergleich mit
`fullTextAccessMethod` trägt nicht: die Zugriffsmethode ändert die
Volltext-*Fähigkeit* nicht, INCLUDE-Spalten und die Ablage einer Tabelle aber
sehr wohl.

Option 2 fiel, weil ein Blocker für eine Operation, die das Werkzeug
formulieren kann, der `UNSUPPORTED`-Stopgap in anderer Kleidung wäre.

## Konsequenzen

**Gut:** Reverse, Generate und Diff bilden ab, was der Server trägt. Der
Round-Trip verliert nichts, und `schema compare` sagt die Wahrheit.

**Der Preis, benannt:** Ein Wechsel der Ablage ist teuer. Der clustered Index
des Primärschlüssels muss fallen, bevor ein anderer Index clustered werden
darf — sonst antwortet SQL Server mit Msg 1902 —, und das Neuanlegen des
Primärschlüssels zieht einen Neubau jedes nonclustered Index der Tabelle nach
sich. Der Diff-Pfad rendert die Reihenfolge deshalb fest; frei wählbar ist sie
nicht.

**Kein zweites Feld am Primärschlüssel:** `TableDefinition.primaryKey` bleibt
eine `List<String>`. Dass der Primärschlüssel nonclustered ist, wird
*hergeleitet* — trägt irgendein Index der Tabelle `clustered`, dann rendert der
MSSQL-Pfad `PRIMARY KEY NONCLUSTERED`. Der Rückweg ist eindeutig, weil der
Reverse `clustered` genau dann am Index vermerkt, wenn der clustered Index
nicht der Primärschlüssel ist. Beide Richtungen schließen ohne ein zweites
Feld, das dieselbe Aussage doppelt und mit ihr driften könnte.

**Degradierung je Dialekt:** MySQL und SQLite kennen INCLUDE nicht und lassen
die Spalten beim Generate mit einer Warnung fallen. Sie werden **nicht** an die
Schlüsselspalten angehängt — bei einem `unique`-Index änderte das die
Eindeutigkeit. `clustered` legen beide unveränderlich fest (InnoDB auf den
Primärschlüssel, SQLite auf die `rowid`); PostgreSQL kennt `CLUSTER` nur als
einmalige Reorganisation, nicht als Eigenschaft.

**Fingerprint `v9`:** Beide Felder stehen in der Projektion. Damit ein
verlustfreier Round-Trip weiterhin identisch hasht, projiziert der
Fingerprint-Pfad jeden Index vorher durch die Sicht des Ziel-Dialekts —
gesteuert über `DialectCapabilities.supportsIndexIncludeColumns` und
`supportsClusteredIndexes`. Ohne das meldete der Post-Compare nach einem
`migrate --execute` gegen MySQL Drift für etwas, das der Zielserver gar nicht
ausdrücken kann.
