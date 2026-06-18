# Sample-DB-Round-Trip-Findings (Pagila/PG, Phase 1)

> Status: Sammlung/Tracker (2026-06-18)
> Trigger: Der neue Sample-DB-Harness ([`../next/sample-db-integration-harness.md`](../next/sample-db-integration-harness.md))
> hat beim **Erstlauf** (Pagila PG→PG-Round-Trip) echte Fidelity-Defekte
> aufgedeckt, die im synthetischen Cross-Dialect-Matrix-Modus bisher nicht
> sichtbar waren.
> Aktivierungsbedingung: jeder Defekt unten, der über „fundamentale Grenze"
> hinausgeht, wird als eigener Fix-Slice nach `../next/` gehoben und behoben
> (je mit Regressionstest). Nach Fix: Baseline `examples/sample-db/expected/`
> schrumpft → bewusst neu pinnen.

Belegt durch die gepinnte Baseline `examples/sample-db/expected/pagila-smoke.compare.txt`
und erklärt in `examples/sample-db/expected/pagila-smoke.md`. Die **Daten**
round-trippen vollständig; die Diffs sind allesamt **Schema/Programmability**.

## F1 — Trigger-Namens-Defekt (M1-Klasse) · zu beheben

`CREATE TRIGGER` emittiert den Modell-**Composite-Key** als literalen Trigger-
Namen: Ziel trägt `actor::last_updated` statt `last_updated`. Der Key (nötig, weil
`last_updated` über Tabellen nicht eindeutig ist) leckt in den DDL-Namen — exakt
die Klasse, die **M1** für Routinen gelöst hat (`ObjectKeyCodec.routineName(key)`
+ RoutineDdlHelper). Fix-Richtung: analoger `ObjectKeyCodec.triggerName(key)`,
sodass der Trigger mit seinem **bloßen** Namen generiert wird. 15/15 Pagila-Trigger
betroffen.

## F2 — Programmability-Ordering: Views vor Routinen/Aggregaten (K2-Klasse) · zu beheben

Das `post-data` emittiert Views **vor** der `CREATE AGGREGATE group_concat`-/
`CREATE FUNCTION`-Definition, die sie aufrufen → die 3 group_concat-Views scheitern
beim Anwenden (`function group_concat(text) does not exist`). Gleiche Klasse wie
der bereits getrackte P3-Residual **K2** (`--include-all`-Routinen-Ordering,
[`../done/pilot-rerun-p3-residuals.md`](../done/pilot-rerun-p3-residuals.md)). Fix-
Richtung: Programmability topologisch sortieren (Views **nach** den von ihnen
referenzierten Routinen/Aggregaten); die vorhandene `sortFunctionsByDependencies`
in `DdlGenerationSupport` auf View→Routine-Kanten ausweiten.

## F3 — Funktions-Attribut-Verlust (neu) · zu beheben

Der Funktions-Round-Trip verliert Attribute: Parameternamen werden synthetisch
(`p1`/`p2` statt Original) und Volatilität/Strictness (`IMMUTABLE`, `STRICT`)
fehlen im Ziel (Body identisch). Betrifft reverse (Capture) **und** generate
(Emit). Drei Pagila-Funktionen betroffen (`_group_concat`, `last_day`,
`rewards_report`). Fix-Richtung: Funktions-Modell um Parameternamen +
Volatilitäts-/Strictness-Marker erweitern, in reverse befüllen, in generate
emittieren.

## Fundamentale Grenzen (kein Defekt — bewusst, gemeldet)

- **tsvector→text** (R301) → gist-Index entfällt (W123). Kandidat für eine ADR
  „Volltext/tsvector-Round-Trip", kein Bug.
- **Leere RANGE-Partition** `payment` → als plain Tabelle erzeugt (E055).
  Daten-/dump-abhängige Eigenheit dieses Pagila-Dumps, korrekt gemeldet.
