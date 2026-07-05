# SQLite: FTS5-FULLTEXT-Objekte über den Table-Rebuild-Pfad recreaten

> **Status: GELIEFERT (2026-07-02), siehe Closure-Notiz unten.**
>
> **Trigger:** Aufgedeckt im P5-Review des Fulltext-Slice
> ([`fulltext-structural-cross-dialect.md`](fulltext-structural-cross-dialect.md)).
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

**Folge (Schwere — korrigiert ggü. dem ersten Entwurf: potenziell STILL, nicht nur sichtbar):**
Die FTS5-Virtual-Table **überlebt** den Basistabellen-Drop (separates Objekt; SQLite erzwingt keine
`content=`-Integrität), aber ihre drei Sync-Trigger **sterben** mit der Basistabelle und werden nicht
neu angelegt (der Rebuild-Plan kennt sie nicht — P5 filtert sie aus dem neutralen Modell), und der
`'rebuild'` läuft nicht erneut. Zurück bleibt eine **veraltete FTS5-Tabelle ohne Wartungs-Trigger**:
die Suche liefert still veraltete/falsche Treffer und aktualisiert sich bei künftigen Writes nicht.
Weil P5s Reverse den Index aus der überlebenden Virtual-Table rekonstruiert, **driftet der
Post-Compare nicht** → `migrate` endet **Exit 0** trotz kaputter Suche. Das ist das „stiller
Verlust"-Anti-Muster — daher der defensive Interim-Block als eigenes Ticket
[`sqlite-fulltext-rebuild-block.md`](sqlite-fulltext-rebuild-block.md) (Punkt 1,
geliefert 2026-07-02: Guard `SQLITE_REBUILD_FULLTEXT_UNSUPPORTED` in
[`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)),
den **dieses** Ticket (Punkt 2, die eigentliche Lösung) abgelöst hat.

**Erreichbarkeit:** eng — nur bei einer inkrementellen SQLite-Migration, die eine fulltext-tragende
Tabelle strukturell ALTERt. Der Slice-Zielpfad (PG→SQLite *fresh* migrate = CreateTable) trifft es nicht.

## Scope

- Den Rebuild-Plan ([`SqliteRebuildPlanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt))
  + -Renderer die FTS5-Objekte als **abhängig** behandeln lassen: vor dem Basistabellen-Drop die
  (den Basistabellen-Drop überlebende) FTS5-Virtual-Table + ihre Sync-Trigger droppen, nach dem
  RENAME per `SqliteFullTextExpansion` neu anlegen (`'rebuild'` repopuliert aus der neuen Tabelle).
- DoD: ein `migrate --execute`, das einen Rebuild einer FULLTEXT-tragenden Tabelle auslöst, stellt
  die FTS5-Struktur wieder her (Index per `MATCH` abfragbar) und bleibt drift-frei (Exit 0).
- Den Interim-Block aus Punkt 1 zurückbauen: `hasFullTextIndices`-Guard +
  `SQLITE_REBUILD_FULLTEXT_UNSUPPORTED`-Blocker in `SqliteRebuildRenderer` entfernen und den
  Regressionstest („rebuild of a FULLTEXT-carrying table is blocked loud…" in
  `SqliteRebuildRendererTest`) auf das Recreate-Verhalten umstellen.

## Nicht-Scope

- Kein neues Mapping-Verhalten (der generate-/diff-Renderpfad ist unverändert korrekt).
- Kein Reverse-Thema (P5 deckt Filter + Rekonstruktion bereits ab).

## Ansatz (Skizze)

Analog zu `dependentTriggersToDrop`/`dependentTriggersToRecreate`: eine
`dependentFullTextIndicesToRecreate`-Bucket im Plan, gefüllt aus den FULLTEXT-Indizes der zu
rebuildenden Tabelle; der Renderer emittiert `SqliteFullTextExpansion.dropStatements(...)` vor dem
Drop und `createStatements(...)` nach dem RENAME (Phase INDEXES). Der bestehende
`createIndexSql`-W132-Fallback bleibt als Sicherheitsnetz.

## Closure-Notiz (2026-07-02)

**Geliefert** in
[`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt),
mit einer bewussten Abweichung von der Skizze: **kein neues Plan-Feld** — `indexesToRecreate`
(= `target.indices`) trägt den FULLTEXT-Index bereits, ein separates
`dependentFullTextIndicesToRecreate`-Bucket wäre eine zweite Wahrheitsquelle gewesen; die
Drop-Seite leitet sich aus `plan.oldTable.indices` ab, das der Plan ohnehin trägt (anders als
Views/Trigger, die H.3a aus dem Schema in den Plan heben musste).

- **Drop-Seite** (`emitDependentDropsBeforeBaseDrop`): vor dem Basistabellen-Drop
  `SqliteFullTextExpansion.dropStatements(...)` je FULLTEXT-Index der alten Tabelle — die
  Virtual-Table würde den Drop verwaist überleben, die drei Sync-Trigger sterben mit.
- **Recreate-Seite**: die INDEXES-Phase routet FULLTEXT-Indizes durch
  `SqliteFullTextExpansion.createStatements(...)` (Virtual-Table + `'rebuild'`-Repopulation +
  3 Sync-Trigger) statt durch `createIndexSql`; bei `unsupportedReason` (WITHOUT ROWID,
  reservierte/kollidierende Spaltennamen) konservative W132-Degradierung statt kaputter DDL.
  Der `createIndexSql`-Skip bleibt als Sicherheitsnetz für andere Aufrufer.
- **Interim-Block zurückgebaut** (Punkt 1, [`sqlite-fulltext-rebuild-block.md`](sqlite-fulltext-rebuild-block.md)):
  `hasFullTextIndices`-Guard + `SQLITE_REBUILD_FULLTEXT_UNSUPPORTED`-Blocker entfernt; der
  Regressionstest prüft jetzt das Recreate-Verhalten (Drop vor `DROP TABLE`, Expansion nach
  RENAME, UP+DOWN) plus den W132-Degradationsfall.

**DoD live-belegt** (Runtime-Image, `migrate --execute` mit NOT-NULL-Reshape auf einer
FULLTEXT-Tabelle mit Bestandsdaten): Exit **0** drift-frei; FTS5-Virtual-Table + alle drei
Sync-Trigger wiederhergestellt; `MATCH` findet die vor dem Rebuild eingefügten Zeilen
(`'rebuild'`-Repopulation) **und** nach dem Rebuild eingefügte Zeilen (Trigger lebendig).

**Nebenbefund** (vorbestehend, nicht durch diesen Slice verursacht — dokumentiert in
[`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md)):
ein Rebuild, der einen **benannten UNIQUE-Constraint** hinzufügt, endet Exit 5 — der Reverse
liest ihn als Spalten-`unique` statt als benannten Constraint (Post-Compare-Asymmetrie derselben
Familie wie die Typ-Abflachung; auch ohne FULLTEXT-Index reproduzierbar).
