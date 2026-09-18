# Die Matrix-Fixture trägt keine Anmerkungen — was nur an ihr hängt, fällt erst in den Zellzahlen auf

> Status: **Draft (Trigger Watch)**
> Trigger: Beim Pinnen von P10 (Plan 2 des Reader-Slices,
> [`../done/reader-treue-2-meldungen.md`](../done/reader-treue-2-meldungen.md))
> bewegten sich **zwei Zellen, die niemand erwartet hatte** — die mit Quelle
> SQL Server. Niemand hatte sie vorhergesagt, weil der Silent-Loss-Check der
> Compare-Matrix nur `fixtures/seeds/` liest.
> Severity: **P3** (Diagnose-Qualität der Abnahme, kein Produktfehler).
> Aktivierungsbedingung: ein weiterer Pin-Lauf, der eine Zelle bewegt, deren
> Ursache in der Fixture liegt — oder ein Plan, der die Fixture um Spalten
> erweitert, deren Verlust gemeldet werden soll.

## Befund

Die Compare-Matrix (`examples/mcp-e2e/scripts/smoke-compare-matrix.sh`) hat
zwei Vergleichsgrundlagen:

- **`fixtures/compare-matrix.yaml`** — die dialektneutrale Fixture. Sie wird
  auf jede Quelle angewendet und bildet den Grundstock jeder Zelle.
- **`fixtures/seeds/<dialekt>.sql`** — je Dialekt das, was nur dort so
  zurückkommt. Jede Seed-Spalte trägt eine **Anmerkung** (Format im
  [README](../../../examples/mcp-e2e/README.md), Abschnitt „Native Typ-Seeds
  und der Silent-Loss-Check"): Quelle, erwartetes Ziel je Dialekt und der
  Code, der den Verlust benennen muss.

Der Silent-Loss-Check wertet **nur die Anmerkungen der Seeds** aus. Eine
Degradierung, die an einer Fixture-Spalte entsteht, ist für ihn unsichtbar:
sie lebt nur in der **Zahl** der Funde einer Zelle.

**Wie es auffiel.** `cm_order.id` der Fixture ist
`identity(by_default)`. SQL Server rendert das als `IDENTITY(1,1)`, und sein
Reverse liest daraus Modus `always` (T-SQL kennt kein `BY DEFAULT`,
[`spec/type-mapping.md`](../../../spec/type-mapping.md) 6.2). Ab dem Reverse
ist die Quelle also eine ALWAYS-Identity — und `W163` (Plan 2, P10) erscheint
zu Recht in `GEN_CODES_MSSQL_MYSQL` und `GEN_CODES_MSSQL_SQLITE`. Vorhergesagt
hatte das niemand: die Fixture sagt nichts über ihre Verluste.

## Warum das so gewollt ist — und wo die Grenze liegt

Die Fixture ist die **Vergleichsgrundlage**, kein Prüfobjekt: sie soll in allen
fünf Dialekten dasselbe bedeuten, und ein Anmerkungsblock je Spalte und Ziel
machte sie zu einer zweiten Erwartungsdatei. Die Trennung ist richtig.

Die Kosten sind trotzdem real:

- Ein Paket, das einen Code an einer **Fixture**-Eigenschaft auslöst, merkt es
  erst am roten Matrixlauf, und der sagt nur „13 statt 11".
- Der Silent-Loss-Check kann für diese Spalten nicht prüfen, ob ein Verlust
  **ohne** Code passiert — genau die Frage, für die er gebaut wurde.

## Zwei Wege (nicht entschieden)

1. **Eine schmale eigene Anmerkungsdatei für die Fixture** — etwa
   `fixtures/compare-matrix.annotations.md`, nur für die Spalten, deren
   Übersetzung je Ziel etwas verliert (heute: `cm_order.id`). Der
   Silent-Loss-Check liest beide Quellen. Kosten: eine zweite Datei, die mit
   der Fixture synchron bleiben muss.
2. **Die betroffene Spalte in die Seeds verschieben** — `cm_order.id` als
   gewöhnliche Spalte in der Fixture führen und die Identity je Dialekt im
   Seed anlegen, mit Anmerkung. Kosten: die Fixture verliert eine Eigenschaft,
   die sie heute in allen Dialekten gleich prüft.

Beide Wege ändern nur die **Abnahme**, nicht das Produkt.
