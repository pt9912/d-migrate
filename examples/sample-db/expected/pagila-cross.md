# Erwartete Baseline — Pagila PostgreSQL → MySQL (Phase 2, Cross-Dialect)

Gepinnt durch `examples/sample-db/scripts/smoke-cross-pg2my.sh`
(`make sample-db-cross-smoke-pg2my`). Quelle: Pagila (PostgreSQL). Ziel: MySQL.

Symmetrisch zu `sakila-cross.md` (Sakila MySQL→PG), nur die Gegenrichtung. PG ist
**feature-reicher** als MySQL, daher degradieren mehr Features (Sequenzen, tz,
GiST, Materialized Views, Partitionen). `schema compare` cross-dialect ist **nicht**
die Erwartung; gepinnt werden:

1. **generate-Notes == `pagila-cross.notes.txt`**.
2. **Per-Tabelle-Parität** Quelle == Ziel (22 Tabellen).
3. **Schlüssel-Typ-Konvertierungen** datenbelegt (siehe unten).

## Gepinnte generate-Notes (`pagila-cross.notes.txt`)

| Code | `code:`-Zeilen | Klasse | Erklärung |
|---|---|---|---|
| `E053` | 62 (31 distinkt ×2) | Programmability-Skip | PG-Trigger/Funktionen/Views — Bodies nicht nach MySQL übersetzbar (je 1 Skip + 1 Note). |
| `E056` | 26 | Sequenz nicht darstellbar | PG-Sequenzen (`actor_actor_id_seq` …) ohne MySQL-`helper_table`-Modus nicht abbildbar. |
| `W100` | 24 | tz-Verlust | `timestamptz` → MySQL `DATETIME` (kein tz). Wert bleibt erhalten, nur die Zone entfällt. |
| `W118` | 8 | PK-Reorder | `AUTO_INCREMENT`-Spalte an den Anfang des Composite-PK gezogen (MySQL-Pflicht). |
| `W125` | 3 | Index-Skip | Index auf `TEXT`/`BLOB`-Spalte ohne Präfixlänge übersprungen (I-08-Klasse). |
| `E055` | 1 | Partition-Grenze | Leere RANGE-Partition `payment` → plain Tabelle (siehe Finding unten). |
| `W102` | 1 | GiST-Skip | `film_fulltext_idx` (GiST) in MySQL nicht unterstützt → übersprungen (koppelt an Volltext-Carveout §8). |
| `W103` | 1 | MatView→View | Materialized View → reguläre View. |

Alle **kein Defekt** — die korrekte, transparente Cross-Dialect-Degradation.
Abweichung von der Zahl = Regression (oder ein echter Fix → bewusst neu pinnen).

## Datenbelegte Typ-Konvertierungen

| PG-Typ | Spalte | MySQL-Ergebnis | Prüfung |
|---|---|---|---|
| `boolean` | `customer.activebool` | `tinyint(1)` | true-Count PG == MySQL `SUM` (599) + Typ `tinyint(1)` |
| `text[]` (ARRAY) | `film.special_features` | `json` | `JSON_VALID=1`; PG-Array-Literal → gültiges JSON-Array (`JdbcForeignValueNormalizer`, L1/K1) |
| `tsvector` | `film.fulltext` | `text` (R301) | alle 1000 Zeilen befüllt; tsvector-String-Repräsentation erhalten |
| `timestamptz` | `rental.last_update` | `datetime` (W100) | Wert erhalten, tz entfällt (erwartet) |

## Bekanntes Finding (nicht baseline-blockierend)

- **P2-pg2my — Partition-Daten-Duplikation.** `payment` (range-partitioniert in
  PG: Parent + 7 Kinder) wird vom Reverse als „plain Parent + 7 lose Tabellen"
  modelliert; der Transfer befüllt **beide** → MySQL hat `payment` (16049) **und**
  die 7 Kinder (zusammen 16049) = Daten doppelt. Per-Tabelle-Parität bemerkt es
  nicht; der Smoke meldet es als **NOTE**. Das ist der datenbelegte Beweis von
  **Finding D** in
  [`../../../docs/planning/open/partition-hierarchy-reconstruction.md`](../../../docs/planning/open/partition-hierarchy-reconstruction.md);
  Auflösung kommt mit der Partitions-Hierarchie-Rekonstruktion (AP2). Details:
  [`../../../docs/planning/in-progress/sample-db-phase2-findings.md`](../../../docs/planning/in-progress/sample-db-phase2-findings.md).

## Pflege

- Notes neu pinnen: `expected/pagila-cross.notes.txt` löschen,
  `make sample-db-cross-smoke-pg2my` laufen lassen (Bootstrap), prüfen, committen,
  erneut laufen lassen (muss dann grün vergleichen).
