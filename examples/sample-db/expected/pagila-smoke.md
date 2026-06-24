# Expected-Result-Baseline — Pagila/PostgreSQL Smoke (Phase 1)

> Plan: [`../../../docs/planning/in-progress/sample-db-integration-harness.md`](../../../docs/planning/in-progress/sample-db-integration-harness.md)
> · Stand: 2026-06-24 (lokal ermittelt, d-migrate `0.9.9-SNAPSHOT`; F1–F4 + ADR 0015
> behoben → **0 Diffs**, `Status: IDENTICAL`; AP1/AP2 ADR 0019: `payment` als echte
> RANGE-Partition, `E055` entfällt → **0 generate-Notes**)

Der Pagila/PostgreSQL-Round-Trip ist jetzt **vollständig verlustfrei**:
`schema compare` meldet `Status: IDENTICAL` (keine Schema-Diffs). Die Baseline
`pagila-smoke.compare.txt` pinnt genau dieses `IDENTICAL`. Green heißt:
`schema compare` == diese Baseline. Die Abschnitte unten dokumentieren die
**fünf** Round-Trip-Defekte (F1–F4 + die tsvector/gist-Grenze), die der Harness
nacheinander aufdeckte und die alle behoben sind — der historische Weg von
37 → 0 Diffs. Taucht je wieder ein Diff auf, ist das ein echter Regressions-
Befund; dann Ursache beheben (nicht die Baseline „weich" pinnen).

## Harter, deterministischer Kern (kein Diff)

- `schema validate`: **0 Errors**.
- `schema generate`: **0 Notes**. Seit AP1/AP2 ([ADR 0019](../../../docs/adr/0019-partition-hierarchy-structured-representation.md))
  erfasst der PG-Reverse die 7 `payment`-Kind-Partitionen → `payment` round-trippt
  als **echte RANGE-Partition** (`PARTITION BY RANGE … PARTITION OF …`); der frühere
  `E055` (leere RANGE-Partition) **entfällt**. Das `W123` (gist auf tsvector→text)
  entfiel bereits seit ADR 0015 — siehe A.
- **Daten round-trippen vollständig + ohne Duplikation**: Zeilenzahlen Quelle == Ziel
  für **alle 22 Tabellen** (inkl. der 7 `payment_p2022_*`-Partitionskinder). Die
  Per-Kind-Parität (`payment_p2022_NN` Quelle == Ziel) beweist zugleich, dass der
  **Bound-Parser** (AP1) die Grenzen korrekt rekonstruiert — sonst würde PG die
  INSERTs in falsche Kinder routen (oder mit „no partition found" abbrechen).
- **post-data wendet sauber an** (ON_ERROR_STOP=1, 0 Fehler) — seit F2 (Programmability
  in Abhängigkeitsreihenfolge: Funktionen/Aggregate vor den Views, die sie aufrufen).

> **Bekannte Folgearbeit (AP2a, nicht RC-blockierend):** die **kind-lokalen** Indizes
> der Partitionskinder (`idx_fk_payment_p2022_NN_*`) werden in dieser Scheibe noch
> **nicht** mit-erfasst — die Kinder werden ohne ihre lokalen Indizes emittiert. Der
> `schema compare` bleibt trotzdem `IDENTICAL`, weil die Kinder auf **beiden** Seiten
> aus der Top-Level-Liste entfernt sind (AP2) und der Comparator heute partitions-blind
> ist (AP4). Per-Partition-Index/FK-Vererbung ist als AP2a getrackt.

## Behobene Round-Trip-Defekte (0 Diffs) — je Klasse erklärt

### A. `film.film_fulltext_idx [gist]` — ✅ BEHOBEN (ADR 0015), kein Diff mehr
Pagilas `film.fulltext` ist `tsvector`; früher degradierte der Reverse den Typ zu
`text` (R301), wodurch der gist-Index keine Default-Operator-Klasse mehr hatte und
beim Generate übersprungen wurde (`W123`). War als „fundamentale Grenze" gemeldet —
bis [ADR 0015](../../../docs/adr/0015-fulltext-tsvector-neutral-type.md) `tsvector`
als **first-class neutralen Typ** `fulltext` modellierte (kein Native-Passthrough).
Jetzt: reverse `tsvector`→`fulltext`, generate `fulltext`→`tsvector`, die GiST-Op-
Class erkennt `tsvector_ops` → Spalte **und** Index round-trippen; R301/W123 entfallen.
Cross-Dialect (MySQL/SQLite) degradiert `fulltext` weiter zu `text` (Carve-Out, da
FTS5/FULLTEXT strukturell andere Mechanismen sind).

### B. 3 Views (`actor_info`, `film_list`, `nicer_but_slower_film_list`) — ✅ BEHOBEN (F2), kein Diff mehr
Diese Views rufen das Aggregat `group_concat(...)` auf und wurden im `post-data`
früher **vor** der `CREATE AGGREGATE group_concat`-Definition emittiert → sie
scheiterten beim Anwenden (`function group_concat(text) does not exist`).
**F2** sortiert die Programmability nun in Abhängigkeitsreihenfolge (Views **nach**
den von ihnen aufgerufenen Routinen/Aggregaten); die drei Views round-trippen
vollständig. Details + Verbleibendes siehe
[`../../../docs/planning/done/sample-db-roundtrip-findings.md`](../../../docs/planning/done/sample-db-roundtrip-findings.md).

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
[`../../../docs/planning/done/sample-db-roundtrip-findings.md`](../../../docs/planning/done/sample-db-roundtrip-findings.md).

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
[`../../../docs/planning/done/sample-db-roundtrip-findings.md`](../../../docs/planning/done/sample-db-roundtrip-findings.md).

## Pflege

- Baseline neu pinnen: `expected/pagila-smoke.compare.txt` löschen und
  `make sample-db-smoke` einmal laufen lassen (bootstrap), Ergebnis prüfen,
  committen.
- Jede Diff-Änderung **muss** hier erklärt werden — ein unerklärter Diff ist der
  Grund, warum der Smoke rot wird.
