# Expected-Result-Baseline — Pagila/PostgreSQL Smoke (Phase 1)

> Plan: [`../../../docs/planning/in-progress/sample-db-integration-harness.md`](../../../docs/planning/in-progress/sample-db-integration-harness.md)
> · Stand: 2026-06-18 (lokal ermittelt, d-migrate `0.9.9-SNAPSHOT`)

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

## Gepinnte Schema-Diffs (5) — je Klasse erklärt

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

### C. 3 Funktionen „changed" (`_group_concat`, `last_day`, `rewards_report`) — Attribut-Verlust
Body identisch, aber der Round-Trip verliert Funktions-**Attribute**:
Parameternamen werden synthetisch (`p1`/`p2` statt Original), und die
Volatilitäts-/Strictness-Marker (`IMMUTABLE`, `STRICT`) fehlen im Ziel. Echter
Fidelity-Defekt der Funktions-Reverse/Generate-Kette (neu durch den Harness
aufgedeckt). Siehe Findings-Doc.

### D. 1 Trigger `~ film::film_fulltext_trigger` — Multi-Event nicht modelliert (F4)
**F1 (Trigger-Naming) ist behoben** (Generate-Pfad): 14 der 15 Trigger round-trippen
jetzt mit ihrem **bloßen** Namen (`last_updated` mehrfach über Tabellen — in PG
zulässig) statt des Modell-Composite-Keys; die 30 Trigger-Diffs (15 added + 15
removed) sind verschwunden. Der **eine** verbliebene Diff ist ein *anderer* Defekt:
Quelle feuert `BEFORE INSERT OR UPDATE`, Ziel nur `BEFORE UPDATE` — das Modell-Enum
`TriggerEvent` trägt genau **ein** Event, keine Event-**Menge**, also kollabiert
`INSERT OR UPDATE` auf ein Event. Neu aufgedeckt → **F4** in der Findings-Doc.

## Pflege

- Baseline neu pinnen: `expected/pagila-smoke.compare.txt` löschen und
  `make sample-db-smoke` einmal laufen lassen (bootstrap), Ergebnis prüfen,
  committen.
- Jede Diff-Änderung **muss** hier erklärt werden — ein unerklärter Diff ist der
  Grund, warum der Smoke rot wird.
