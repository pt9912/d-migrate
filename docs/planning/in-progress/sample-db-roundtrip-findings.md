# Sample-DB-Round-Trip-Findings (Pagila/PG, Phase 1)

> Status: Sammlung/Tracker (2026-06-18)
> Trigger: Der neue Sample-DB-Harness ([`sample-db-integration-harness.md`](sample-db-integration-harness.md))
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

## F3 — Funktions-Attribut-Verlust · BEHOBEN 2026-06-19

Der Funktions-Round-Trip verlor Attribute, die der Reverse **erfasst**, generate
aber **nicht emittierte**. Datenbelegt an drei Pagila-Funktionen:
- `_group_concat` (`LANGUAGE sql IMMUTABLE`) — Volatilität fiel auf VOLATILE →
  `deterministic`-Diff.
- `last_day` (`LANGUAGE sql IMMUTABLE STRICT`) — Volatilität (Diff) + Strictness
  (gar nicht erfasst, daher kein Diff, aber realer Fidelity-Verlust).
- `rewards_report` (`LANGUAGE plpgsql SECURITY DEFINER`) — `security` **war** im
  Modell, wurde aber nie emittiert → Ziel INVOKER → `security`-Diff.

**Klarstellung:** die synthetischen `p1`/`p2` sind **kein** Diff — PG-unnamed-Params
(`$1`/`$2`) haben echt keine Namen, beide Seiten synthetisieren identisch und
matchen. Der Tracker hatte das überzeichnet.

**Behoben (alle Ebenen, PG):**
- **Modell:** `FunctionDefinition.volatility: FunctionVolatility?`
  (`IMMUTABLE`/`STABLE`/`VOLATILE`) + `strict: Boolean?`
  (`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/FunctionDefinition.kt`);
  `security`/`definer` waren bereits da.
- **Reverse:** `listRoutineIdentityAttributes` liest nun `pg_proc.provolatile`
  (`i`/`s`/`v`) + `proisstrict`; der Reader befüllt `volatility`/`strict`.
- **Generate:** `PostgresRoutineDdlHelper.generateFunction` hängt nach `LANGUAGE`
  `IMMUTABLE`/`STABLE` (VOLATILE = Default, ausgelassen) + `STRICT` (wenn strict) +
  `SECURITY DEFINER` (wenn security == DEFINER) an.
- **Serialisierung + Spec:** `volatility`/`strict` serialisiert; `spec/schema.json`
  (function: `volatility`-Enum + `strict`) + `schema-reference.md` + Contract-Fixture.
- **Regressionstests:** PG-Reverse-Capture (provolatile/proisstrict),
  PG-Generate-Emit (`IMMUTABLE STRICT SECURITY DEFINER`, STABLE, VOLATILE-Auslassung),
  Serialisierungs-Round-Trip.

Harness-belegt: die 3 Funktions-Diffs verschwinden, Baseline schrumpft **4 → 1**
(verbleibend nur die fundamentale tsvector/gist-Grenze, kein Bug).

**Offene Restfläche (nicht F3-blockierend, kein Pagila-Vorkommen):** `search_path`
auf SECURITY-DEFINER-Funktionen ist im Modell erfasst, wird aber von generate noch
nicht emittiert; ebenso fehlen `security`/`definer`/`search_path`/`sql_mode` in der
`schema.json`-`function`-Definition (vorbestehender Spec-Drift).

## F4 — Multi-Event-Trigger nicht modelliert · BEHOBEN 2026-06-19

Erst nach dem F1-Fix sichtbar geworden (war vorher hinter dem Namens-Diff
verborgen): Pagilas `film_fulltext_trigger` feuert auf `BEFORE INSERT OR UPDATE`,
das Ziel nur auf `BEFORE UPDATE`. Ursache (zwei Ebenen): (1) das Modell-Enum
`TriggerEvent` (`INSERT | UPDATE | DELETE`) trug **genau ein** Event, keine
Event-**Menge**; (2) der PG-Reverse keyte `result[key] = …` je
`information_schema.triggers`-Zeile — die liefert **eine Zeile pro Event**, also
überschrieb die UPDATE-Zeile die INSERT-Zeile, statt zu aggregieren.

**Behoben (alle Ebenen):**
- **Modell:** `TriggerDefinition.event: TriggerEvent` → `events: Set<TriggerEvent>`
  (`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt`); exakter, nicht-lossiger
  Sekundär-Konstruktor `(event: TriggerEvent)` → `setOf(event)` für den
  Single-Event-Normalfall; `canonicalOrder()` + `toSqlEventClause()`-Helfer
  (Enum-Ordinal-Reihenfolge → deterministisches `INSERT OR UPDATE`). `TriggerDiff`,
  `SchemaComparator` (Set-Gleichheit = reihenfolge-unabhängig), `MigrationFingerprint`
  nachgezogen.
- **Reverse:** PG aggregiert die mehreren Zeilen pro `(table, name)`-Key zur
  Event-Menge (Ursachenfix); MySQL/SQLite bleiben Single-Event (Grammatik kennt
  kein Multi-Event) und wrappen in Singleton-Set.
- **Generate + Diff-Emit (je 3 Dialekte):** `events.toSqlEventClause()` emittiert
  PG `BEFORE INSERT OR UPDATE`; MySQL/SQLite unverändert für Single-Event (foreign
  Multi-Event ist ohnehin per `E053` gegated).
- **Serialisierung + Spec:** Schlüssel `event` ist nun **skalar-oder-array**
  (`spec/schema.json` `oneOf`); Builder schreibt Skalar bei Single (Null-Churn auf
  bestehenden Goldens) / Array bei Multi, Parser liest beides + Legacy-Skalar.
- **Regressionstests:** PG-Reverse-Aggregation (`PostgresSchemaReaderTriggerTest`),
  PG generate+diff Multi-Event-Emit, Serialisierungs-Round-Trip (multi-Array +
  single-Skalar-Garantie), Comparator-Set-Gleichheit, Core-Modell-Helfer.

Harness-belegt: der `~ film::film_fulltext_trigger`-Diff verschwindet, Baseline
schrumpft 5 → 4 Changes (verbleibend: 1 Tabelle/gist-Grenze + 3 Funktionen/F3).

## Fundamentale Grenzen (kein Defekt — bewusst, gemeldet)

- **tsvector→text** (R301) → gist-Index entfällt (W123). Kandidat für eine ADR
  „Volltext/tsvector-Round-Trip", kein Bug.
- **Leere RANGE-Partition** `payment` → als plain Tabelle erzeugt (E055).
  Daten-/dump-abhängige Eigenheit dieses Pagila-Dumps, korrekt gemeldet.
