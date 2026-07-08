# Erwartete Baseline — Pagila PostgreSQL → MySQL (Phase 2, Cross-Dialect)

Gepinnt durch `examples/sample-db/scripts/smoke-cross-pg2my.sh`
(`make sample-db-cross-smoke-pg2my`). Quelle: Pagila (PostgreSQL). Ziel: MySQL.

Symmetrisch zu `sakila-cross.md` (Sakila MySQL→PG), nur die Gegenrichtung. PG ist
**feature-reicher** als MySQL, daher degradieren mehr Features (Sequenzen, tz,
GiST, Materialized Views). `schema compare` cross-dialect ist **nicht**
die Erwartung; gepinnt werden:

1. **generate-Notes == `pagila-cross.notes.txt`**.
2. **Per-Tabelle-Parität** Quelle == Ziel über die **15 logischen Tabellen**
   (Partitionskinder zählen nicht separat — `payment` ist EINE partitionierte
   MySQL-Tabelle und wird als Ganzes verglichen).
3. **Schlüssel-Typ-Konvertierungen** datenbelegt (siehe unten).
4. **Partitions-Integrität**: `payment` round-trippt als EINE `RANGE COLUMNS`-Tabelle
   (16049 Zeilen, 7 MySQL-Partitionen, keine Kind-Duplikation, keine FKs).

## Gepinnte generate-Notes (`pagila-cross.notes.txt`)

| Code | `code:`-Zeilen | Klasse | Erklärung |
|---|---|---|---|
| `E053` | 62 (31 distinkt ×2) | Programmability-Skip | PG-Trigger/Funktionen/Views — Bodies nicht nach MySQL übersetzbar (je 1 Skip + 1 Note). |
| `E056` | 26 | Sequenz nicht darstellbar | PG-Sequenzen (`actor_actor_id_seq` …) ohne MySQL-`helper_table`-Modus nicht abbildbar. |
| `W100` | 17 | tz-Verlust | `timestamptz` → MySQL `DATETIME` (kein tz). Wert bleibt erhalten, nur die Zone entfällt. **(24→17: die 7 `payment`-Kinder werden nicht mehr als lose Tabellen dupliziert.)** |
| `W125` | 3 | Index-Skip | Index auf `TEXT`/`BLOB`-Spalte ohne Präfixlänge übersprungen (I-08-Klasse). |
| `W102` | 1 | GiST-Skip | `film_fulltext_idx` (GiST) in MySQL nicht unterstützt → übersprungen (koppelt an Volltext-Carveout §8). |
| `W103` | 1 | MatView→View | Materialized View → reguläre View. |
| `W112` | 1 | RANGE-`from`-Verwurf | `payment` RANGE → MySQL `RANGE COLUMNS` behält nur die Obergrenze (`VALUES LESS THAN`); die `from`-Grenze entfällt (AP6.2, ADR 0020 §1). |
| `W118` | 1 | PK-Reorder | `AUTO_INCREMENT`-Spalte an den Anfang des Composite-PK gezogen (MySQL-Pflicht). **(8→1: nur noch der `payment`-Parent, nicht je Kind.)** |
| `W129` | 1 | timestamptz-Grenze→UTC | `payment`-Partitionsgrenzen (`timestamptz`) auf UTC normalisiert (tz-Suffix entfernt) für MySQL-`DATETIME` (AP6.2, ADR 0020 §2). |

Kein `E055` mehr (die Partition wird jetzt EMITTIERT, nicht übersprungen). Alle Codes
**kein Defekt** — die korrekte, transparente Cross-Dialect-Degradation.
Abweichung von der Zahl = Regression (oder ein echter Fix → bewusst neu pinnen).

> **INFO-Notes (nicht in der Code-Baseline gezählt — string-codiert, nicht `W`/`E`):**
> `PARTITION_INDEX_LIFTED` — die kind-lokalen FK-Backing-Indizes (`idx_fk_payment_*_customer_id`,
> `*_staff_id`) werden auf die `payment`-Tabelle gehoben und signatur-dedupliziert (7 Kinder → 1 je
> Spalte; AP6.3, ADR 0020 §5).

## Datenbelegte Typ-Konvertierungen

| PG-Typ | Spalte | MySQL-Ergebnis | Prüfung |
|---|---|---|---|
| `boolean` | `customer.activebool` | `tinyint(1)` | true-Count PG == MySQL `SUM` (599) + Typ `tinyint(1)` |
| `text[]` (ARRAY) | `film.special_features` | `json` | `JSON_VALID=1`; PG-Array-Literal → gültiges JSON-Array (`JdbcForeignValueNormalizer`, L1/K1) |
| `tsvector` | `film.fulltext` | `text` (R301) | alle 1000 Zeilen befüllt; tsvector-String-Repräsentation erhalten |
| `timestamptz` | `rental.last_update` | `datetime` (W100) | Wert erhalten, tz entfällt (erwartet) |

## Partitions-Integrität (früheres Finding P2-pg2my — GELÖST)

Das frühere Finding **P2-pg2my** (Partition-Daten-Duplikation: `payment` lag im Ziel
doppelt, weil der Reverse Parent + 7 Kinder als lose Tabellen modellierte) ist mit der
Partitions-Hierarchie-Rekonstruktion (AP1/AP2) und dem Cross-Dialect-Generate (AP6) **gelöst**:

- **Reverse (AP2):** `payment` wird als EIN partitionierter Parent + Kind-Partitionen
  erfasst; die Kinder werden aus der Tabellenliste gefiltert → keine losen Tabellen,
  kein doppelter Transfer. Belegt: `payment` = **16049** Zeilen im Ziel (nicht 32098),
  0 Kind-Tabellen.
- **Generate (AP6.2):** MySQL `PARTITION BY RANGE COLUMNS (payment_date)` mit
  UTC-normalisierten Grenzen (W112/W129); 7 Partitionen wenden sauber an.
- **Index (AP6.3):** kind-lokale Indizes auf die Tabelle gehoben (`PARTITION_INDEX_LIFTED`).

Der Smoke prüft das jetzt als **hartes Gate** (Schritt 9): Zeilen-Parität `payment`
Quelle==Ziel, ≥1 MySQL-Partition, 0 Kind-Tabellen, 0 FKs.

### FK-Anmerkung (kind-lokal, keine `E065`-Note)

Pagila deklariert die `payment`-FKs (`customer_id`/`staff_id`/`rental_id`) **auf den
Kind-Partitionen**, nicht am Parent — PG erlaubt das. Der partitions-bewusste Reverse
kollabiert die Kinder (AP2a erfasst kind-lokale **Indizes**, aber keine kind-lokalen
**FK-Constraints**), daher trägt der Parent `payment` keine FKs ins Modell und es gibt
keine `E065`-Note. Das MySQL-Ergebnis ist dennoch **korrekt** (MySQL/InnoDB verbietet FKs
auf partitionierten Tabellen ohnehin). Der `E065`-Carve-Out (AP6.3-FK, ADR 0020 §5) greift
für **am Parent deklarierte** FKs (unit-getestet, `MysqlPartitionForeignKeyTest`). Das
Erfassen+Melden kind-lokaler FKs ist als Folgearbeit getrackt
([`../../../docs/planning/in-progress/cross-dialect-partitioning.md`](../../../docs/planning/in-progress/cross-dialect-partitioning.md)).

## Pflege

- Notes neu pinnen: `expected/pagila-cross.notes.txt` löschen,
  `make sample-db-cross-smoke-pg2my` laufen lassen (Bootstrap), prüfen, committen,
  erneut laufen lassen (muss dann grün vergleichen).
