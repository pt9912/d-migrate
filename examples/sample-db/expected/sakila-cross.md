# Erwartete Baseline — Sakila MySQL → PostgreSQL (Phase 2, Cross-Dialect)

Gepinnt durch `examples/sample-db/scripts/smoke-cross.sh` (`make sample-db-cross-smoke`).
Quelle: Sakila (`jOOQ/sakila@e089a5b1`, MySQL). Ziel: PostgreSQL.

Anders als der Phase-1-Round-Trip (Pagila PG→PG, `pagila-smoke.md`) ist dies ein
**Dialektwechsel**. `schema compare` zwischen MySQL-Quelle und PG-Ziel ist deshalb
**nicht** die Erwartung — Dialektunterschiede (Programmability, Typ-Degradationen)
sind legitim. Stattdessen pinnt der Harness drei Invarianten:

1. **generate-Notes == `sakila-cross.notes.txt`** (32× E053 + 6× W127 `code:`-Zeilen).
2. **Zeilen-Parität** Quelle == Ziel über alle 16 Tabellen.
3. **Schlüssel-Typ-Konvertierungen** datenbelegt (siehe unten).

## Gepinnte generate-Notes (`sakila-cross.notes.txt`)

**Wichtig zur Zahl:** Die Baseline zählt `code:`-Zeilen über **beide**
Report-Abschnitte (`notes:` + `skipped_objects:`). Der Report-Summary lautet
**22 Notes + 16 Skipped-Objects**. Die 16 E053 erscheinen **doppelt** (einmal als
strukturierter `skipped_objects:`-Eintrag, einmal als menschenlesbare `notes:`-
Erklärung), daher 16 + 16 = 32 E053 `code:`-Zeilen. **Real sind es 16 distinkte
nicht-übersetzbare Objekte.**

| Code | `code:`-Zeilen | distinkt | Klasse | Erklärung |
|---|---|---|---|---|
| `E053` | 32 | **16 Objekte** | Programmability-Skip | **7 Views** + **3 Funktionen** + **3 Prozeduren** + **3 Trigger** (`ins/upd/del_film`) — Cross-Dialect können MySQL-Bodies nicht nach PG übersetzt werden (Backtick-Quoting, `GROUP_CONCAT`/`IF`, prozedurale Syntax); d-migrate transpiliert View-/Routinen-/Trigger-Rümpfe bewusst **nicht** zwischen Dialekten. Jedes Objekt = 1 `skipped_objects:`-Eintrag + 1 `notes:`-Erklärung. |
| `W127` | 6 | 6 Indizes | Index-Rename (kein Skip) | MySQL erlaubt gleiche Index-Namen je Tabelle; PG-Index-Namen sind schema-global → `idx_fk_*` → `idx_fk_*_2`/`_3`. Die Indizes werden **erfolgreich emittiert**, nur umbenannt. |

Die hohe Zahl spiegelt **Sakilas Programmability-Reichtum** (16 Objekte) × Dialekt-
wechsel × Doppel-Listung — **kein Defekt**, sondern die korrekte, transparente
Cross-Dialect-Meldung (vgl. Phase 1 Pagila/PG-PG war `IDENTICAL`, weil same-dialect
keine Body-Übersetzung braucht). Abweichung von der Zahl = Regression (oder ein
echter Fix, der sie schrumpft → bewusst neu pinnen).

## Datenbelegte Typ-Konvertierungen

| MySQL-Typ | Spalte | PG-Ergebnis | Prüfung |
|---|---|---|---|
| `TINYINT(1)` | `customer.active` | `boolean` | `SUM(active)` Quelle == Ziel (584) + Typ `boolean` |
| `ENUM(...)` | `film.rating` | `text` (R301) | Verteilung identisch (order-unabhängig als `rating=count`-Paare; **ENUM sortiert nach Deklarations-Ordinal, `text` lexikalisch** — daher Schlüssel-Wert-Vergleich, nicht positionell) |
| `SET(...)` | `film.special_features` | `text` (R320) | `film_id=1` beidseitig `Deleted Scenes,Behind the Scenes` |

## Bekanntes Finding (nicht baseline-blockierend)

- **Y1 — YEAR-Wert-Korruption.** `film.release_year` (MySQL `YEAR`) wird beim
  Transfer zu `2006-01-01 +00` statt `2006` (Connector/J `yearIsDateType`-Default).
  Der Smoke meldet das als **NOTE** (nicht als Fehler) und verweist auf den
  Tracker. Details + Fix-Hypothese:
  [`../../../docs/planning/in-progress/sample-db-phase2-findings.md`](../../../docs/planning/in-progress/sample-db-phase2-findings.md).

## Pflege

- Notes neu pinnen: `expected/sakila-cross.notes.txt` löschen und
  `make sample-db-cross-smoke` einmal laufen lassen (Bootstrap), Ergebnis prüfen,
  committen, erneut laufen lassen (muss dann grün vergleichen).
