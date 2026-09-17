# MCP `schema_compare` vergleicht ungültige Schemata, die CLI bricht ab

> **Status:** Vorabklärung / Eigner-Frage (2026-09-17).
> **Trigger:** Compare-Slice
> [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md),
> P11 („Nicht Teil von P11") und „Offen". P11 hat den drei Oberflächen von
> `schema compare` eine Semantik gegeben (ADR 0057, Entscheidung 1); die
> Validierung davor hat der Eigner ausdrücklich ausgenommen.
> **Aktivierungsbedingung:** Eigner-Entscheidung, ob die Validierung zur
> Semantik gehört. Danach ein `next/`-Plan (Vertrag in `spec/mcp-server.md`,
> Verhalten in Werkzeug und Job).

## Befund

- **CLI:** `schema compare` validiert beide Operanden; ein ungültiges Schema
  (etwa `E012`, eine CHECK-Expression mit unbekannter Spalte) beendet den Lauf
  mit **Exit 3**. Gemessen im Konsumenten-Repro: PG↔MySQL und MSSQL↔MySQL enden
  so, solange der MySQL-Reverse den Zeichensatz-Introducer trägt
  (Reader-Slice, Posten C1).
- **MCP:** `schema_compare` und `schema_compare_start` vergleichen dieselben
  Schemata trotzdem und liefern Funde.

Dasselbe Paar hat damit je Oberfläche einen anderen Ausgang — genau das, was
ADR 0057 für den Vergleich selbst ausschließt, nur eine Stufe davor.

## Die Frage an den Eigner

Gehört die Validierung zur Semantik von `schema compare`?

- **Ja:** Werkzeug und Job lehnen ein ungültiges Schema ab — Werkzeug mit
  `VALIDATION_ERROR` am `schemaRef` der betroffenen Seite (wie die halbe
  Markierung), Job mit `FAILED`. Folge: ein MCP-Abnehmer, der heute trotz
  `E012` vergleicht, bekommt keinen Vergleich mehr (Vertragswechsel,
  CHANGELOG).
- **Nein:** die CLI bleibt strenger; Spec und Handbuch sagen es ausdrücklich,
  und die Abweichung steht als Grenze in `spec/mcp-server.md`.
- **Zwischenform:** MCP vergleicht, trägt aber die Validierungsfunde im
  Ergebnis mit.

## Bezug

- Der Reader-Fix für den MySQL-Introducer (Reader-Slice
  [`reader-treue-1-matrix-abnahme.md`](../next/reader-treue-1-matrix-abnahme.md),
  P6) nimmt dem Befund seinen häufigsten Auslöser, nicht die Frage.
