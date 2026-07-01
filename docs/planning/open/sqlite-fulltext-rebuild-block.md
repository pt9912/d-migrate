# SQLite: Table-Rebuild einer FULLTEXT-Tabelle blocken (loud-safe Interim)

> **Trigger:** P5-Review des Fulltext-Slice
> ([`../done/fulltext-structural-cross-dialect.md`](../done/fulltext-structural-cross-dialect.md)).
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
([`sqlite-fulltext-rebuild-recreate.md`](sqlite-fulltext-rebuild-recreate.md)) landet, wird dieser
Block durch die tatsächliche Wiederherstellung ersetzt (Block entfernen).

## Nicht-Scope

- Keine Wiederherstellung der FTS5-Struktur (das ist Punkt 2).
- Kein Reverse-/Generate-Verhalten (unverändert korrekt).
