# Expected-Result-Baseline — Pagila/PostgreSQL Smoke (Phase 1)

> Plan: [`../../../docs/planning/in-progress/sample-db-integration-harness.md`](../../../docs/planning/in-progress/sample-db-integration-harness.md)
> · Stand: 2026-06-19 (lokal ermittelt, d-migrate `0.9.9-SNAPSHOT`; F2/F3/F4 behoben → **1 Diff**:
> nur noch die fundamentale tsvector/gist-Grenze)

Diese Datei erklärt **jeden** in `pagila-smoke.compare.txt` gepinnten
Schema-Diff. Die Baseline ist *nicht* „0 Diffs" — ein Cross-/Round-Trip über
das neutrale Schema-Modell erzeugt **legitime, erklärbare** Abweichungen. Green
heißt: `schema compare` == diese Baseline, **keine unerklärten** Diffs. Schrumpft
der Diff (z. B. nachdem ein Defekt unten behoben ist), ist das ein *gutes* Rot —
dann Baseline + diese Erklärung aktualisieren.

## Harter, deterministischer Kern (kein Diff)

- `schema validate`: **0 Errors**.
- `schema generate`: genau **2 Notes** — `E055` (leere RANGE-Partition `payment`)
  + `W123` (gist-Index auf tsvector→text).
- **Daten round-trippen vollständig**: Zeilenzahlen Quelle == Ziel für **alle 22
  Tabellen** (inkl. der 7 `payment_p2022_*`-Partitionskinder).
- **post-data wendet sauber an** (ON_ERROR_STOP=1, 0 Fehler) — seit F2 (Programmability
  in Abhängigkeitsreihenfolge: Funktionen/Aggregate vor den Views, die sie aufrufen).

## Gepinnte Schema-Diffs (1) — je Klasse erklärt

### A. `film.film_fulltext_idx [gist]` entfernt (1) — fundamentale Grenze
Pagilas `film.fulltext` ist `tsvector`; beim Reverse degradiert der Typ zu `text`
(R301), wodurch der gist-Index keine Default-Operator-Klasse mehr hat und beim
Generate übersprungen wird (`W123`). Kein Defekt — eine bewusste, gemeldete
Grenze. Eine ADR „tsvector/Volltext-Round-Trip" könnte das künftig adressieren.

### B. 3 Views (`actor_info`, `film_list`, `nicer_but_slower_film_list`) — ✅ BEHOBEN (F2), kein Diff mehr
Diese Views rufen das Aggregat `group_concat(...)` auf und wurden im `post-data`
früher **vor** der `CREATE AGGREGATE group_concat`-Definition emittiert → sie
scheiterten beim Anwenden (`function group_concat(text) does not exist`).
**F2** sortiert die Programmability nun in Abhängigkeitsreihenfolge (Views **nach**
den von ihnen aufgerufenen Routinen/Aggregaten); die drei Views round-trippen
vollständig. Details + Verbleibendes siehe
[`../../../docs/planning/in-progress/sample-db-roundtrip-findings.md`](../../../docs/planning/in-progress/sample-db-roundtrip-findings.md).

### C. 3 Funktionen (`_group_concat`, `last_day`, `rewards_report`) — ✅ BEHOBEN (F3), kein Diff mehr
Der Round-Trip verlor Funktions-**Attribute**, die der Reverse erfasst, generate
aber nicht emittierte: Volatilität (`_group_concat`/`last_day` `IMMUTABLE`),
Strictness (`last_day` `STRICT`) und `SECURITY DEFINER` (`rewards_report`). Quelle
war IMMUTABLE/DEFINER, das Ziel fiel auf VOLATILE/INVOKER zurück → `deterministic`-
bzw. `security`-Diff. **F3** erweitert das Modell um `volatility`/`strict` (Reverse
aus `pg_proc.provolatile`/`proisstrict`), und generate emittiert nun
Volatilität + `STRICT` + `SECURITY DEFINER`; alle drei round-trippen vollständig.
Die synthetischen `p1`/`p2` waren **kein** Diff (PG-unnamed-Params haben echt keine
Namen — beide Seiten synthetisieren gleich). Details siehe
[`../../../docs/planning/in-progress/sample-db-roundtrip-findings.md`](../../../docs/planning/in-progress/sample-db-roundtrip-findings.md).

### D. Trigger `film::film_fulltext_trigger` — ✅ BEHOBEN (F4), kein Diff mehr
**F1 (Trigger-Naming) ist behoben** (Generate-Pfad): alle 15 Trigger round-trippen
mit ihrem **bloßen** Namen (`last_updated` mehrfach über Tabellen — in PG zulässig)
statt des Modell-Composite-Keys; die 30 Trigger-Diffs (15 added + 15 removed) sind
verschwunden. Der danach verbliebene **eine** Diff war ein *anderer* Defekt: Quelle
feuerte `BEFORE INSERT OR UPDATE`, Ziel nur `BEFORE UPDATE` — das Modell-Enum
`TriggerEvent` trug genau **ein** Event, und der PG-Reverse überschrieb je
`information_schema.triggers`-Zeile (eine Zeile **pro** Event) statt zu aggregieren.
**F4** modelliert nun eine Event-**Menge** (`Set<TriggerEvent>`), aggregiert die
Reverse-Zeilen und emittiert `INSERT OR UPDATE` in kanonischer Reihenfolge; der
Trigger round-trippt vollständig. Details siehe
[`../../../docs/planning/in-progress/sample-db-roundtrip-findings.md`](../../../docs/planning/in-progress/sample-db-roundtrip-findings.md).

## Pflege

- Baseline neu pinnen: `expected/pagila-smoke.compare.txt` löschen und
  `make sample-db-smoke` einmal laufen lassen (bootstrap), Ergebnis prüfen,
  committen.
- Jede Diff-Änderung **muss** hier erklärt werden — ein unerklärter Diff ist der
  Grund, warum der Smoke rot wird.
