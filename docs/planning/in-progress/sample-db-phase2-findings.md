# Sample-DB-Cross-Dialect-Findings (Sakila MySQL→PG, Phase 2)

> Status: **In Arbeit** (2026-06-20). Erstlauf Sakila MySQL→PG durchgeführt;
> Zeilen-Parität 16/16, drei Typ-Konvertierungen datenbelegt korrekt, **ein**
> Daten-Fidelity-Defekt (Y1, YEAR) entdeckt.
> Trigger: Phase 2 des Sample-DB-Harness
> ([`sample-db-integration-harness.md`](sample-db-integration-harness.md)) fährt
> erstmals einen **echten Cross-Dialect-Transfer** (nicht Same-Dialect-Round-Trip
> wie Phase 1). Wie erwartet („jeder neue Dialekt deckt eigene Defekte auf")
> bringt MySQL→PG eigene Befunde.
> Aktivierungsbedingung: jeder echte Defekt unten wird als eigener Fix-Slice nach
> `../next/` gehoben und behoben (je mit Regressionstest); danach Baseline
> `examples/sample-db/expected/` neu pinnen.

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
