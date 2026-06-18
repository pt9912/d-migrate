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

## F1 — Trigger-Namens-Defekt (M1-Klasse) · GENERATE-Pfad BEHOBEN 2026-06-18

`CREATE TRIGGER` emittierte den Modell-**Composite-Key** als literalen Trigger-
Namen: Ziel trug `actor::last_updated` statt `last_updated`. Der Key (nötig, weil
`last_updated` über Tabellen nicht eindeutig ist) leckte in den DDL-Namen — exakt
die Klasse, die **M1** für Routinen gelöst hat.

**Behoben (Generate-Pfad):** neuer `ObjectKeyCodec.triggerName(key)` (dekodiert
den bloßen Namen, Fallback = Key bei Nicht-`table::name`-Form);
`PostgresRoutineDdlHelper.generateTrigger` emittiert nun den bloßen Namen (PG hat
einen **per-Tabelle**-Trigger-Namensraum), der synthetische Trigger-Funktionsname
bleibt am vollen Key eindeutig verankert. Regressionstest in
`PostgresRoutineDdlHelperTest`. Harness-belegt: die 30 Trigger-Diffs (15 added +
15 removed) verschwanden, Baseline 37 → 8 Changes.

**Hinweis (per-Dialekt):** MySQL/SQLite haben einen **schema-globalen** Trigger-
Namensraum — dort ist der disambiguierte Name nötig; der Fix ist daher
PG-spezifisch.

**Offen (Restfläche):** der Diff-/Migrate-Pfad (`schema migrate`,
`PostgresTriggerDdlHelper.emitCreate/emitDrop`) nutzt weiterhin
`op.objectRef.rootName` (= Key) als Trigger-Namen. Gleiche Wurzel, aber separate
Fläche mit eigenen Golden-Tests — nicht vom Phase-1-Harness (`schema generate`)
verifizierbar, daher als eigener Fix-Slice mit Diff-Pfad-Test nachzuziehen.

## F2 — Programmability-Ordering: Views vor Routinen/Aggregaten (K2-Klasse) · BEHOBEN 2026-06-18

Das `post-data` emittierte Views **vor** der `CREATE AGGREGATE group_concat`-/
`CREATE FUNCTION`-Definition, die sie aufrufen → die 3 group_concat-Views
scheiterten beim Anwenden (`function group_concat(text) does not exist`). Gleiche
Klasse wie der P3-Residual **K2** (`--include-all`-Routinen-Ordering,
[`../done/pilot-rerun-p3-residuals.md`](../done/pilot-rerun-p3-residuals.md)).

**Behoben:** in `AbstractDdlGenerator.generate()` werden die POST_DATA-Views nun
**nach** functions/aggregates/procedures emittiert (statt davor). Eine View wird
bei CREATE validiert und referenziert üblicherweise die Routinen/Aggregate, also
müssen diese zuerst stehen. Regressionstest in `AbstractDdlGeneratorTestPart2`
(„post-data view is emitted AFTER the function it references"); die callOrder-
Erwartungen in `AbstractDdlGeneratorTest(+Part2)` + die Golden-Master
`view-function-deps.{postgresql,mysql,sqlite}.post-data.sql` nachgezogen.
Harness-belegt: `post-data` wendet sauber an (ON_ERROR_STOP=1), Baseline 8 → 5.

## F3 — Funktions-Attribut-Verlust (neu) · zu beheben

Der Funktions-Round-Trip verliert Attribute: Parameternamen werden synthetisch
(`p1`/`p2` statt Original) und Volatilität/Strictness (`IMMUTABLE`, `STRICT`)
fehlen im Ziel (Body identisch). Betrifft reverse (Capture) **und** generate
(Emit). Drei Pagila-Funktionen betroffen (`_group_concat`, `last_day`,
`rewards_report`). Fix-Richtung: Funktions-Modell um Parameternamen +
Volatilitäts-/Strictness-Marker erweitern, in reverse befüllen, in generate
emittieren.

## F4 — Multi-Event-Trigger nicht modelliert (neu) · zu beheben

Erst nach dem F1-Fix sichtbar geworden (war vorher hinter dem Namens-Diff
verborgen): Pagilas `film_fulltext_trigger` feuert auf `BEFORE INSERT OR UPDATE`,
das Ziel nur auf `BEFORE UPDATE`. Ursache: das Modell-Enum `TriggerEvent`
(`INSERT | UPDATE | DELETE`) trägt **genau ein** Event, keine Event-**Menge** —
`INSERT OR UPDATE` kollabiert auf ein Event. Fix-Richtung: Trigger-Modell auf eine
Event-Menge (`Set<TriggerEvent>` o. ä.) erweitern, in reverse (alle Dialekte)
befüllen, in generate als `INSERT OR UPDATE …` emittieren. Betrifft Modell +
reverse + generate + Serialisierung (vergleichbarer Umfang wie F3).

## Fundamentale Grenzen (kein Defekt — bewusst, gemeldet)

- **tsvector→text** (R301) → gist-Index entfällt (W123). Kandidat für eine ADR
  „Volltext/tsvector-Round-Trip", kein Bug.
- **Leere RANGE-Partition** `payment` → als plain Tabelle erzeugt (E055).
  Daten-/dump-abhängige Eigenheit dieses Pagila-Dumps, korrekt gemeldet.
