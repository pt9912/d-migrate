# Sample-DB-Cross-Dialect-Findings (Phase 2)

> Status: **In Arbeit** (2026-06-20). **Beide** Flows durchgeführt:
> Sakila MySQL→PG (Parität 16/16, 1 Defekt Y1) **und** Pagila PG→MySQL
> (Parität 22/22, 1 Defekt = Partition-Duplikation, datenbelegt Finding D).
> Trigger: Phase 2 des Sample-DB-Harness
> ([`sample-db-integration-harness.md`](sample-db-integration-harness.md)) fährt
> erstmals **echte Cross-Dialect-Transfers** (nicht Same-Dialect-Round-Trip
> wie Phase 1). Wie erwartet („jeder neue Dialekt deckt eigene Defekte auf")
> bringt jede Richtung eigene Befunde.
> Aktivierungsbedingung: jeder echte Defekt unten wird als eigener Fix-Slice nach
> `../next/` gehoben und behoben (je mit Regressionstest); danach Baseline
> `examples/sample-db/expected/` neu pinnen.

---

# Flow A — Sakila MySQL→PG

Quelle: Sakila (`jOOQ/sakila@e089a5b1`, MySQL), Ziel: PostgreSQL. Flow:
`reverse sakila_my --include-all` → `validate` (0 Errors) → `generate --target
postgresql --split pre-post` → pre-data auf `sakila_target` (PG) → `data transfer
sakila_my → sakila_pg_target`.

## Datenbelegt KORREKT (keine Defekte)

| MySQL-Typ | Spalte | PG-Ergebnis | Beleg |
|---|---|---|---|
| `TINYINT(1)` | `customer.active` | `boolean` | `SUM(active)` MySQL 584 == PG 584 |
| `ENUM(...)` | `film.rating` | `text` (R301) | Verteilung identisch (G 178 / PG 194 / PG-13 223 / R 195 / NC-17 210) |
| `SET(...)` | `film.special_features` | `text` (R320) | `film_id=1` beidseitig `Deleted Scenes,Behind the Scenes` |

`ENUM`/`SET` → `text` ist die erwartete R301/R320-Typ-Degradation (kein neutrales
Äquivalent); die **Werte** round-trippen vollständig. Zeilen-Parität über **alle
16 Tabellen** Quelle == Ziel.

## Y1 — YEAR-Wert-Korruption (Daten-Defekt) · OFFEN

`film.release_year` ist MySQL `YEAR` (R301 → `text` im Ziel, Typ-Degradation
erwartet). Aber der **Wert** wird korrumpiert:

- MySQL: `release_year = 2006`
- PG (Ziel): `release_year = '2006-01-01 +00'`

**Ursache:** `MysqlJdbcUrlBuilder.defaultParams()`
(`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlJdbcUrlBuilder.kt`,
`defaultParams()`) setzt `yearIsDateType` **nicht**. Connector/J-Default ist `yearIsDateType=true` →
`YEAR`-Spalten kommen als `java.sql.Date` (`2006` → `2006-01-01`) zurück; beim
Transfer in die `text`-Zielspalte wird das Datum (mit tz-Artefakt) stringifiziert.
Das ist **unabhängig** von der Typ-Degradation: selbst als `text` müsste der Wert
`2006` sein, nicht `2006-01-01 +00`.

**Fix-Hypothese (gescopt, eigener Slice):** `yearIsDateType=false` in
`MysqlJdbcUrlBuilder.defaultParams()` → Connector/J liefert `YEAR` als Short/Int →
Wert `2006`. Braucht: d-migrate:dev-Rebuild, Re-Run, **Regressionstest** im
driver-mysql-Modul (YEAR-Lesewert), Verifikation gegen bestehende YEAR-Pfade.
Nicht als Mid-Stream-Drive-by ohne Test — eigener Fix-Slice analog Phase-1-F1–F4.

## Erwartete Cross-Dialect-Notes (kein Defekt — Baseline-pinnbar)

`generate --target postgresql` meldet laut Report-Summary **22 Notes + 16
Skipped-Objects**, alle erwartet. Die gepinnte Baseline zählt `code:`-Zeilen über
**beide** Abschnitte = **32× E053 + 6× W127** (die 16 E053 erscheinen doppelt: je
1 strukturierter `skipped_objects:`-Eintrag + 1 `notes:`-Erklärung). **Real sind
es 16 distinkte nicht-übersetzbare Objekte:**

- **16× Objekte (E053)** — Programmability-Skips: **7 Views** + **3 Funktionen** +
  **3 Prozeduren** + **3 Trigger** (`ins/upd/del_film`). Cross-Dialect können
  MySQL-Bodies nicht nach PG übersetzt werden (Backtick-Quoting, `GROUP_CONCAT`/
  `IF`, prozedurale Syntax); d-migrate transpiliert View-/Routinen-/Trigger-Rümpfe
  bewusst nicht zwischen Dialekten (bekannte ADR-Folgearbeit „View-Bodies nicht
  transpiliert", I-09).
- **6× W127** — **Index-Renames** (kein Skip): MySQL erlaubt gleiche Index-Namen je
  Tabelle, PG ist schema-global → `idx_fk_*` → `idx_fk_*_2`/`_3`. Die Indizes
  werden erfolgreich emittiert, nur umbenannt (das ist der N8-Mechanismus).

Kein Bug — die korrekte, transparente Cross-Dialect-Meldung (Sakila ist
programmability-reich; Phase 1 Pagila/PG-PG war `IDENTICAL`, weil same-dialect
keine Body-Übersetzung braucht). Detail-Aufschlüsselung: `expected/sakila-cross.md`.

---

# Flow B — Pagila PG→MySQL

Quelle: Pagila (PostgreSQL), Ziel: MySQL. Flow: `reverse pagila_pg --include-all`
→ `validate` (0 Errors) → `generate --target mysql --split pre-post` → pre-data auf
`pagila_target` (MySQL) → `data transfer pagila_pg → pagila_my_target`. **Parität
22/22 Tabellen.**

## Datenbelegt KORREKT (keine Defekte)

| PG-Typ | Spalte | MySQL-Ergebnis | Beleg |
|---|---|---|---|
| `boolean` | `customer.activebool` | `tinyint(1)` | `count(*) FILTER (WHERE activebool)` PG 599 == MySQL `SUM` 599 |
| `text[]` (ARRAY) | `film.special_features` | `json` | PG `{"Deleted Scenes","Behind the Scenes"}` → MySQL `["Deleted Scenes", "Behind the Scenes"]` (gültiges JSON-Array; `JdbcForeignValueNormalizer` aus L1/K1) |
| `tsvector` (R301→fulltext) | `film.fulltext` | `text` | alle 1000 Filme befüllt; `film_id=1` Länge 139, `'academi':1 'battl':15…` identisch zum PG-tsvector-String |
| `timestamptz` (W100) | `rental.last_update` | `datetime` | `2022-02-15 21:30:53+00` → `2022-02-15 21:30:53` (tz weg = W100, erwartet; Wert erhalten) |

## P2-pg2my — Partition-Daten-Duplikation (Daten-Defekt) · OFFEN, getrackt im Partitions-Plan

Pagilas `payment` ist range-partitioniert (Parent + 7 Kinder `payment_p2022_01..07`).
Der Reverse modelliert sie als „partitionsloser Parent (plain, E055) + 7 lose
Standalone-Tabellen" — und der Transfer befüllt **beide**:

- MySQL `payment` (plain): **16049** Zeilen (aus PG-Parent, der alle Kind-Zeilen aggregiert)
- MySQL `payment_p2022_01..07` zusammen: **16049** Zeilen (aus den PG-Standalone-Kindern)
- **Gesamt 32098 statt 16049 — die Zahlungsdaten liegen physisch doppelt.**

Die Per-Tabelle-Parität (16049==16049, 723==723) bemerkt es **nicht**. Das ist
**kein neuer Befund**, sondern der **datenbelegte Beweis von Finding D** aus
[`../open/partition-hierarchy-reconstruction.md`](../open/partition-hierarchy-reconstruction.md)
(AP5). **Kein eigener neuer Slice** — dort getrackt; die Auflösung kommt mit der
Partitions-Hierarchie-Rekonstruktion (AP2 entfernt die Kinder aus der
Top-Level-Liste → Transfer befüllt nur noch den Parent). Bis dahin gilt: der
PG→MySQL-Smoke prüft **Per-Tabelle-Parität** und meldet die Duplikation als NOTE.

## Erwartete Cross-Dialect-Notes (kein Defekt — Baseline-pinnbar)

`generate --target mysql` meldet (PG-Features, die MySQL nicht 1:1 darstellt) —
alle erwartet:

| Code | `code:`-Zeilen | Klasse |
|---|---|---|
| `E053` | 62 (31 distinkt ×2) | Programmability-Skips (PG-Trigger/Funktionen/Views, Body nicht nach MySQL übersetzbar) |
| `E056` | 26 | PG-Sequenzen ohne MySQL-`helper_table`-Modus nicht darstellbar (`actor_actor_id_seq` …) |
| `W100` | 24 | `timestamptz` → MySQL `DATETIME` (kein tz) |
| `W118` | 8 | `AUTO_INCREMENT`-Spalte an den Anfang des Composite-PK gezogen (MySQL-Pflicht) |
| `W125` | 3 | Index auf `TEXT`/`BLOB`-Spalte ohne Präfixlänge übersprungen (I-08-Klasse) |
| `E055` | 1 | Leere RANGE-Partition `payment` → plain Tabelle (= Partitions-Grenze, siehe P2-pg2my) |
| `W102` | 1 | GiST-Index `film_fulltext_idx` in MySQL nicht unterstützt, übersprungen (koppelt an Volltext-Carveout §8) |
| `W103` | 1 | Materialized View → reguläre View |

Kein Bug — die korrekte, transparente Cross-Dialect-Degradation (PG ist
feature-reicher als MySQL).
