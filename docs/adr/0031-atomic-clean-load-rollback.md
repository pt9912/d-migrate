---
status: accepted
date: 2026-07-12
decision-makers: pt9912
consulted: spec/lastenheft-d-migrate.md, docs/planning/done/ImpPlan-1.0.0-RC-ln013-atomic-clean-load.md
informed: hexagon/ports-write, adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, hexagon/application, docs/planning/in-progress/roadmap.md
---

# Atomarer Rollback auf Checkpoint-Ebene: Clean-Load-Kompensation (`--atomic`, LN-013)

> **Status: accepted (2026-07-12).** `data import`/`data transfer --atomic` stellt
> „alle Tabellen oder keine" für einen **sauberen Load** her: bei einem Fehler wird
> der **vollständige** Operations-Tabellensatz per Truncate auf leer zurückgesetzt
> (Kompensation), statt einen Teil-Import stehen zu lassen.

## Kontext und Problemstellung

[`LN-013`](../../spec/lastenheft-d-migrate.md#ln-013) verlangt „atomare
Rollback-Fähigkeit auf Checkpoint-Ebene bei Fehlern"; LF 8.5 nennt „Keine
Teil-Importe bei Constraint-Verletzungen" und „Alle Tabellen oder keine bei
Multi-Table-Import".

Der Datenpfad committet aber **chunk-weise** (`AbstractTableImportSession.commitChunk`,
`conn.commit()` je Chunk) — bewusst, weil ein `>10-TB`-Streaming
([`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)) nicht alles in einer
Transaktion halten kann. Bei einem Fehler mittendrin bleiben deshalb alle bereits
committeten Chunks/Tabellen stehen (`--truncate` ist explizit **nicht-atomar**,
`ImportOptions`). Es gibt kein Undo committeter Daten und keine laufweite Transaktion.

Die vier erwogenen Modelle (per-Tabelle-Tx, Savepoint-Fenster, Staging+Swap,
Kompensation) sind im ImpPlan gegeneinander abgewogen. Nur die **Kompensation**
erfüllt beide LF-Kriterien, ohne das Streaming-Ziel zu brechen.

## Entscheidung

### D1 — `--atomic` erfordert explizit `--truncate`

`--atomic` **ohne** `--truncate` scheitert im Preflight (Exit **2**, „`--atomic`
requires `--truncate`") **vor** dem ersten Write. Kein stilles Impliziern, kein
Row-Count-Leer-Check. Begründung: `--atomic` truncatet bei Fehler ohnehin
(Kompensation) — der Nutzer hat der Ziel-Zerstörung also schon zugestimmt;
`--truncate` vorzuschreiben macht die destruktive Natur **am Call-Site sichtbar**
und vermeidet einen TOCTOU-Leer-Check (falscher Check → Kompensation könnte fremde
Daten truncaten). Der Vertrag ist ein **Superset**: „leeres Ziel auch ohne
`--truncate`" ließe sich additiv nachrüsten.

### D2 — Kompensation = Truncate des vollständigen Operations-Tabellensatzes

Bei einem Fehler (Exit **5**) eines `--atomic`-Laufs truncatet `AtomicCompensation`
**alle** Tabellen der Operation (nicht nur die gescheiterte) FK-sicher: Postgres
`TRUNCATE … RESTART IDENTITY CASCADE` in einem Statement, MySQL/SQLite FK-Checks aus
+ `DELETE` je Tabelle. Neue Port-Primitive `DataWriter.truncateTables(pool, tables)`
(je Dialekt implementiert; werfender Default analog `DataReader.streamTable(resumeMarker)`).
So wird das Ziel auf den bekannt-leeren Startzustand zurückgesetzt → „alle oder keine".
Die Kompensation ist **O(1)** in den Metadaten, unabhängig vom Datenvolumen — der
entscheidende Grund gegen die tx-/undo-log-skalierenden Modelle.

### D3 — Idempotent + resume-sicher

Die Kompensations-Truncate ist selbst nicht transaktional (wie `--truncate`), aber:
- **`--atomic` ist inkompatibel mit `--resume`** (Exit **2**) — atomar heißt
  all-or-nothing, es gibt keinen Teilzustand zum Wiederaufnehmen.
- Da `--truncate` per D1 **immer** gesetzt ist, startet **jeder** `--atomic`-Lauf
  sauber (Start-Truncate). Eine abgebrochene Kompensation wird beim Re-Run trivial
  re-cleant. Kein Leer-Check, keine TOCTOU-Frage.

### D4 — Kompensationsbasiert, checkpoint-unabhängig

Der Trigger sitzt an der **Finalize-Fehler-Naht** (Import: `DataImportRunner` nach
`finalizeAndReport`; Transfer: der `catch`-Zweig in `DataTransferRunner`), NICHT an
der Checkpoint-Maschinerie. Deshalb gilt `--atomic` auch im **checkpoint-freien
Transfer-Pfad** — der Kern-Vorteil des Kompensationsmodells.

## Konsequenzen

- **Clean-Load-Atomarität** für import + transfer: entweder der Load geht vollständig
  durch, oder das Ziel steht wieder auf leer.
- **Nicht-Scope:** Append-in-ein-nicht-leeres-Ziel (Staging + atomarer Swap) — die
  Truncate-Kompensation würde dort Fremddaten löschen; das ist ein späterer Slice
  (überlappt mit [`spec/shadow-migration.md`](../../spec/shadow-migration.md)).
  `--atomic` verbaut ihn nicht.
- **Cancel (Exit 130)** löst im ersten Slice **keine** Kompensation aus (der
  Cancel-Pfad läuft nicht durch die Finalize-Naht); ein abgebrochener `--atomic`-Lauf
  hinterlässt ggf. Teildaten, die ein Re-Run per `--truncate` re-cleant. Ausbau
  (Kompensation auch bei Cancel) = Folgearbeit.

## Referenzen

- [`LN-013`](../../spec/lastenheft-d-migrate.md#ln-013), LF 8.5 (Transaktionale
  Konsistenz), [`LN-012`](../../spec/lastenheft-d-migrate.md#ln-012) (Checkpoint/Resume, Komplement).
- [`spec/job-contract.md`](../../spec/job-contract.md) 8.1 (Exit-Codes 2/5).
- ImpPlan: [`ImpPlan-1.0.0-RC-ln013-atomic-clean-load.md`](../planning/done/ImpPlan-1.0.0-RC-ln013-atomic-clean-load.md).
