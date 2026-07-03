# SQLite: Table-Rebuild einer FULLTEXT-Tabelle blocken (loud-safe Interim)

> **Status: GELIEFERT (2026-07-02) und noch am selben Tag planmäßig ABGELÖST** — der volle
> Recreate ([`sqlite-fulltext-rebuild-recreate.md`](sqlite-fulltext-rebuild-recreate.md)) hat
> den Block zurückgebaut. Siehe Closure-Notiz unten.
>
> **Trigger:** P5-Review des Fulltext-Slice
> ([`fulltext-structural-cross-dialect.md`](fulltext-structural-cross-dialect.md)).
> Interim-Sicherung gegen den stillen Rebuild-Bug; abgelöst vom vollen Recreate
> [`sqlite-fulltext-rebuild-recreate.md`](sqlite-fulltext-rebuild-recreate.md) (Punkt 2).

## Problem (still, grüner migrate)

Ein SQLite-Table-**Rebuild** (ALTER COLUMN TYPE / Constraint- oder PK-Reshape → SQLite baut die
Tabelle als `<temp>` neu) auf einer **FULLTEXT-tragenden** Tabelle hinterlässt eine **veraltete
FTS5-Struktur ohne Wartung**: die FTS5-Virtual-Table überlebt den Basistabellen-Drop, ihre drei
Sync-Trigger nicht, und der Rebuild recreatet nichts (`createIndexSql` degradiert FULLTEXT zum
Kommentar). Weil P5s Reverse den Index aus der überlebenden Virtual-Table rekonstruiert, **driftet
der Post-Compare nicht** → `migrate --execute` endet **Exit 0**, während die Volltext-Suche still
veraltete/falsche Treffer liefert und sich nicht mehr aktualisiert. Details + voller Fix: Punkt-2-Ticket.

**Erreichbarkeit:** eng (nur inkrementelle SQLite-Migration, die eine fulltext-tragende Tabelle
strukturell ALTERt); der Slice-Zielpfad (PG→SQLite *fresh* migrate) trifft es nicht. Aber die
*stille* Natur (grüner Exit trotz kaputter Suche) verletzt das „kein stiller Verlust"-Prinzip.

## Scope (klein, defensiv)

- Im SQLite-Rebuild-Pfad
  ([`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)
  / [`SqliteRebuildPlanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt)):
  wenn die zu rebuildende Tabelle einen `IndexType.FULLTEXT`-Index trägt → **`MANUAL_ACTION_REQUIRED`
  blocken** mit klarer Note (Rebuild einer FTS5-tragenden Tabelle wird bis zum vollen Recreate nicht
  unterstützt; manuell FTS5 nach dem Rebuild neu aufbauen). Kein stilles Weiterlaufen.
- **DoD:** ein `migrate --execute`, das einen Rebuild einer FULLTEXT-Tabelle auslösen würde, endet
  **loud** (Blocker + Diagnose, Exit ≠ 0), nicht still Exit 0 mit veralteter Suche. Regressionstest.

## Verhältnis zu Punkt 2

Reiner Sicherheitsnetz-Interim. Sobald der volle Recreate
([`sqlite-fulltext-rebuild-recreate.md`](sqlite-fulltext-rebuild-recreate.md))
landet, wird dieser Block durch die tatsächliche Wiederherstellung ersetzt (Block entfernen).

## Nicht-Scope

- Keine Wiederherstellung der FTS5-Struktur (das ist Punkt 2).
- Kein Reverse-/Generate-Verhalten (unverändert korrekt).

## Closure-Notiz (2026-07-02)

**Geliefert:** früher Guard in
[`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)
(analog zum Materialized-View-Blocker): trägt die zu rebuildende Tabelle auf einer der beiden
Seiten (`oldTable`/`newTable` — UP und DOWN blocken identisch) einen `IndexType.FULLTEXT`-Index,
wird jede Bucket-Operation mit Diagnostic-Code **`SQLITE_REBUILD_FULLTEXT_UNSUPPORTED`**
geskippt und der Bucket mit **`MANUAL_ACTION_REQUIRED`** geblockt. Die Message nennt die
betroffenen FTS5-Namen (via `SqliteFullTextExpansion.ftsName`, Naming-Parität mit dem
Generate-/Diff-Pfad) und die manuelle Abhilfe (FTS5-Objekte nach der Strukturänderung neu
aufbauen bzw. Index vorher droppen / nachher neu anlegen).

**DoD-Nachweis:**

- Regressionstest in `SqliteRebuildRendererTest` („rebuild of a FULLTEXT-carrying table is
  blocked loud…"): UP + DOWN blocken mit `MANUAL_ACTION_REQUIRED`, 0 Statements, Diagnose
  nennt Tabelle + FTS5-Name; der identische Rebuild ohne FULLTEXT-Index rendert weiterhin
  (bestehende Tests unverändert grün).
- **Live-verifiziert** (Runtime-Image, SQLite-file-Target): frisches `migrate --execute` eines
  FULLTEXT-Schemas bleibt drift-frei Exit 0 (P5-Round-Trip regressionsfrei); ein
  Constraint-Reshape auf derselben Tabelle (UNIQUE → Rebuild-Bucket) endet **loud Exit 8** mit
  `SQLITE_REBUILD_FULLTEXT_UNSUPPORTED` im Report, **ohne** ein Statement auszuführen —
  FTS5-Virtual-Table und alle drei Sync-Trigger bleiben unversehrt.

**Nebenbefund** (unabhängig vom Guard, eigenes Ticket
[`../in-progress/postcompare-type-canonicalization-slice.md`](../in-progress/postcompare-type-canonicalization-slice.md)):
ein frisches `migrate --execute` mit einer `smallint`-Spalte endet auf SQLite immer Exit 5
(Post-Compare-Drift-False-Positive durch Typ-Abflachung `smallint`→`INTEGER` im Generate).

**Rückbau: ERFOLGT (2026-07-02).** Der volle Recreate hat Guard + Blocker entfernt und den
Regressionstest auf das Recreate-Verhalten umgestellt; die FULLTEXT-Erkennung trug wie erwartet
1:1 weiter (Drop-Seite des Recreate).
