# SQLite: FTS5-FULLTEXT-Objekte über den Table-Rebuild-Pfad recreaten

> **Trigger:** Aufgedeckt im P5-Review des Fulltext-Slice
> ([`in-progress/fulltext-structural-cross-dialect.md`](../in-progress/fulltext-structural-cross-dialect.md)).
> Ausgeschnitten, weil eigenständige Rebuild-Planner-Arbeit und **nicht** im P5-DoD/Slice-Akzeptanz.

## Kontext

Der Fulltext-Slice (ADR [0025](../../adr/0025-fulltext-source-columns-as-index.md)) expandiert einen
neutralen `IndexType.FULLTEXT`-Index in SQLite zu einer FTS5-External-Content-Virtual-Table +
`'rebuild'` + drei Sync-Triggern
([`SqliteFullTextExpansion`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteFullTextExpansion.kt)),
verdrahtet in den generate- und den diff/migrate-Renderpfad. P5 macht den `migrate --execute`-
Round-Trip drift-frei (Reverse filtert Shadow-Tabellen/Sync-Trigger + rekonstruiert den Index).

**Nicht abgedeckt:** der SQLite-Table-**Rebuild**-Pfad
([`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)).
Ein Rebuild entsteht bei inkrementellen Migrationen, die SQLite nicht in-place kann (ALTER COLUMN
TYPE / Constraint- oder PK-Reshape auf der Basistabelle): die Tabelle wird als `<temp>` neu erstellt,
Daten kopiert, die Originaltabelle gedroppt, `<temp>` umbenannt. Der Renderer recreatet danach die
abhängigen Indizes via `sql.createIndexSql(...)` — für einen FULLTEXT-Index liefert das den
**W132-Skip-Marker** (sichtbarer SQL-Kommentar), also **keine** Neuanlage der FTS5-Struktur.

Folge: nach einem Rebuild einer Tabelle mit FULLTEXT-Index ist die Volltext-Suche verloren (der
FTS5-Index wird nicht wiederhergestellt). Die Degradierung ist **sichtbar** (Kommentar-Marker im
Migrations-SQL), nicht still-kaputt — aber unvollständig.

## Scope

- Den Rebuild-Plan ([`SqliteRebuildPlanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt))
  + -Renderer die FTS5-Objekte als **abhängig** behandeln lassen: vor dem Basistabellen-Drop die
  (den Basistabellen-Drop überlebende) FTS5-Virtual-Table + ihre Sync-Trigger droppen, nach dem
  RENAME per `SqliteFullTextExpansion` neu anlegen (`'rebuild'` repopuliert aus der neuen Tabelle).
- DoD: ein `migrate --execute`, das einen Rebuild einer FULLTEXT-tragenden Tabelle auslöst, stellt
  die FTS5-Struktur wieder her (Index per `MATCH` abfragbar) und bleibt drift-frei (Exit 0).

## Nicht-Scope

- Kein neues Mapping-Verhalten (der generate-/diff-Renderpfad ist unverändert korrekt).
- Kein Reverse-Thema (P5 deckt Filter + Rekonstruktion bereits ab).

## Ansatz (Skizze)

Analog zu `dependentTriggersToDrop`/`dependentTriggersToRecreate`: eine
`dependentFullTextIndicesToRecreate`-Bucket im Plan, gefüllt aus den FULLTEXT-Indizes der zu
rebuildenden Tabelle; der Renderer emittiert `SqliteFullTextExpansion.dropStatements(...)` vor dem
Drop und `createStatements(...)` nach dem RENAME (Phase INDEXES). Der bestehende
`createIndexSql`-W132-Fallback bleibt als Sicherheitsnetz.
