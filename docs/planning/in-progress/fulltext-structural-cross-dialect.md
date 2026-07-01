# Strukturelle Cross-Dialect-Volltext-Übersetzung (FTS5 / FULLTEXT)

> **Status:** In Progress. **P0+P1+P2+P3+P4 erledigt** (P2 review-gehärtet, P3+P4 live-verifiziert); P5 offen.
>
> **STAND 2026-07-01:**
> - **P4 (SQLite-Generate FTS5) live-verifiziert & DoD-komplett.** Ein neutraler `FULLTEXT`-Index
>   expandiert cross-dialect zu einer SQLite **FTS5-External-Content-Virtual-Table** + `'rebuild'`
>   + **drei Sync-Trigger** (INSERT/UPDATE/DELETE) über die Quelltext-Spalten — eine gemeinsame
>   SQL-Quelle ([`SqliteFullTextExpansion`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteFullTextExpansion.kt))
>   in **beiden** Pfaden (generate + diff/migrate, dem SpatiaLite-Präzedenzfall folgend). Neuer Live-Smoke
>   [`smoke-fulltext-sqlite.sh`](../../../examples/sample-db/scripts/smoke-fulltext-sqlite.sh)
>   (`make sample-db-fulltext-sqlite-smoke`): (A) PG-Reverse Pagila → SQLite-Generate erzeugt die FTS5-DDL
>   für `film` über (`title`,`description`); (B) `sqlite3`-Apply + kuratierte Daten → FTS5-`MATCH` liefert
>   Treffer inkl. `'rebuild'`-Befüllung UND aller drei Sync-Trigger (Insert/Update/Delete) + Nonsens-
>   Negativkontrolle; (C) `migrate --execute` legt die FTS5-Struktur real an (Diff-Pfad). Die `fulltext`-
>   Spalte degradiert weiterhin zu `TEXT` (W132), der Index degradiert **nicht** mehr.
> - **Review-gehärtet (P4-Review, 4 Finder-Angles):** (1) **generate↔diff-Namensparität** — anonyme
>   FULLTEXT-Indizes bekamen divergente FTS-Objektnamen (generate `idx_…` vs diff `…_idx`); jetzt eine
>   gemeinsame `SqliteFullTextExpansion.ftsName`; (2) **`CREATE VIRTUAL TABLE` im Rollback-Inverter** —
>   `generateRollback` ließ die Virtual-Table verwaisen (droppte nur die Trigger); `StatementInverter`
>   invertiert sie jetzt zu `DROP TABLE`; (3) **Precondition-Guard** — WITHOUT-ROWID-Basistabelle bzw.
>   reservierter FTS5-Spaltenname (`rank`/`rowid`)/Namenskollision degradieren konservativ (W132) statt
>   kaputte DDL zu emittieren, rollback-symmetrisch; (4) Smoke-Teil-C-False-Green gehärtet (verifiziert
>   den Drift-Grund), String-Literal via `quoteSqliteStringLiteral`, Test-Deckung `createIndexSql`-Skip.
> - **Bewusste P4/P5-Grenze:** der *drift-freie* migrate-Round-Trip ist **P5** — `migrate --execute`
>   endet heute mit Exit 5 (Post-Compare-Drift: Reverse sieht die FTS5-Shadow-Tabellen + Sync-Trigger
>   und rekonstruiert den Index noch nicht). Der Smoke belegt den *Apply* (Teil C, nicht-fatal), nicht
>   Drift-Freiheit. Der Rebuild-Bucket-Pfad (`createIndexSql`) degradiert bis P5 weiter (Kommentar-Marker,
>   ohne strukturierte Note — von P5 mit der Rebuild-Recreate-Arbeit geschlossen).
> - **Alle Gates grün:** `make sample-db-fulltext-sqlite-smoke` (SUCCESS A/B/C), Full-Repo
>   `make ci-build` (`build koverVerify`), `make docs-check` (0 Befunde).
> - **P3 (MySQL) live-verifiziert** (`make sample-db-cross-smoke-pg2my`), PG→PG-Baseline regressionsfrei.
> - **Sample-DB-Postgres-Container läuft evtl. noch** (`make sample-db-down` zum Abbau).
>
> **NÄCHSTER SCHRITT = P5 (Diff/Migrate-Härtung).** Reverse: die FTS5-Shadow-Tabellen
> (`_data`/`_idx`/`_docsize`/`_config`) + die drei Sync-Trigger als bekannt/nicht-driftend filtern
> **und** den `FULLTEXT`-Index aus der FTS5-Virtual-Table + Trigger-Bodies rekonstruieren (Pendant zum
> SpatiaLite-Reverse: [`SqliteTypeMapping`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
> `SPATIALITE_META_TABLES`/`isSpatiaLiteGeometryTrigger`, [`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt)).
> Erst damit wird `migrate --execute` drift-frei (Exit 0). Zusätzlich: der Table-**Rebuild**-Pfad
> ([`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt))
> muss die FTS5-Objekte als abhängig behandeln statt sie via `createIndexSql` zu degradieren. DoD in Abschnitt 5/P5.
>
> Phasen + Akzeptanzkriterien ausgearbeitet
> ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md)).
> **Herkunft:** Carve-Out aus [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md)
> (Abschnitt „Abgrenzung"), getrackt in
> [`carveout.md`](carveout.md), Abschnitt 8.

## 1. Ziel

Den neutralen Volltext-Typ `fulltext` cross-dialect **strukturell** abbilden statt ihn zu
`text` zu degradieren — die Volltext-Suchfähigkeit auf MySQL und SQLite erhalten:

- **MySQL:** eine `FULLTEXT`-**Index**-Struktur auf der/den Quelltext-Spalte(n).
- **SQLite:** eine **FTS5-Virtual-Table** plus Sync-Trigger (Quelle → FTS-Index).

Der Kern: das ist **keine** Typ-↔-Typ-Abbildung (wie `geometry` → `GEOMETRY`), sondern ein
**struktureller Umbau** — eine Spalte expandiert zu zusätzlichen DDL-Objekten anderer Art
(Index bzw. Virtual Table + Trigger). Genau deshalb ist es aus ADR 0015 ausgeschnitten.

## 2. Hintergrund (Ist-Stand im Code)

- **`NeutralType.FullText`** (parameterloses `data object`) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/NeutralType.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/NeutralType.kt);
  kanonischer Name `fulltext` in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/CanonicalPayload.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/CanonicalPayload.kt).
- **PG-Reverse erfasst die Bausteine bereits:** `tsvector` → `FullText` in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt);
  der GiST-Index überlebt via `tsvector_ops` in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresIndexOpClass.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresIndexOpClass.kt);
  der befüllende Trigger (`tsvector_update_trigger(...)`) landet als roher Body in
  `TriggerDefinition.body` über
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaProgrammabilityReaders.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaProgrammabilityReaders.kt).
  **Wichtig:** Die **Quelltext-Spalten** (z. B. Pagila `title`, `description`) und die
  Text-Search-Config (`pg_catalog.english`) stehen **nur im Trigger-Body** — der `tsvector`
  selbst ist ein vorberechneter Vektor, kein lesbarer Text.
- **Cross-Dialect-Generate degradiert heute stumm zu `TEXT`** — `is NeutralType.FullText ->
  "TEXT"` in
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt)
  und
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt)
  — **ohne** Degradierungs-Note, obwohl ADR 0015 eine vorsieht (Phase 0 schließt das).
- **Heute keine FULLTEXT-Index-Logik:** der MySQL-Index-Emit-Pfad
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt)
  (aus
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt))
  kennt SPATIAL, aber kein FULLTEXT;
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt)
  vermerkt `FULLTEXT` als „not modelled here".
- **Strukturelles Vorbild = SpatiaLite.** Das „eine Spalte → mehrere DDL-Objekte"-Muster
  existiert schon für Geometrie in
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSpatialDiffOps.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSpatialDiffOps.kt)
  (Bootstrap + `AddGeometryColumn` + `CreateSpatialIndex`, idempotenter Diff-Renderpfad,
  [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md)). Der SQLite-Index-Emit-Pfad
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt)
  routet Geometrie-Indizes bereits über diesen Sonderpfad — FTS5 spiegelt das Muster.
- **Modell ist tragfähig:** `TriggerDefinition` (Multi-Event-`Set`, AFTER, roher `body`) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt)
  und `IndexType` (BTREE…SPATIAL, **kein FULLTEXT**) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt).

## 3. Scope

### 3.1 In Scope

- PG → MySQL: `fulltext`-Spalte → `TEXT`-Spalte **plus** `FULLTEXT`-Index über die
  Quelltext-Spalte(n).
- PG → SQLite: `fulltext`-Spalte → FTS5-Virtual-Table + Sync-Trigger (INSERT/UPDATE/DELETE).
- Modell-/Reverse-Anreicherung um die **Quelltext-Spalten** + Config; `IndexType.FULLTEXT`.
- Eine eigene **ADR** für die Mapping-Entscheide (Phase 1).
- Degradierungs-Note als Fallback, wenn die strukturelle Übersetzung nicht möglich ist.

### 3.2 Nicht in Scope

- **MySQL/SQLite → PG-Reverse** struktureller Volltext-Konstrukte (FULLTEXT-Index / FTS5
  zurück zu `tsvector`) — eigene Richtung, eigene Entscheidung.
- **Volltext-Such-Queries übersetzen** (`to_tsquery`/`MATCH … AGAINST`) — Query-Rewriting,
  nicht Schema-/Daten-Migration.
- Erweiterte Text-Search-Konfigurationen jenseits dessen, was aus dem Trigger sauber
  ableitbar ist.

## 4. Designentscheidungen (Vorschlag — in der Slice-ADR, Phase 1, zu ratifizieren)

1. **Quelltext-Herleitung (Kernfrage).** FTS5/FULLTEXT indizieren **lesbaren Quelltext**, der
   `tsvector` ist ein vorberechneter Vektor ohne lesbaren Text. → **Vorschlag:** das Modell um
   `sourceColumns` (+ optional `textSearchConfig`) anreichern und beim **PG-Reverse** aus dem
   `tsvector_update_trigger`-Body bzw. einer `GENERATED … AS (to_tsvector(...))`-Expression
   füllen (Herleitung im Reverse, nicht im Generate). Ohne ableitbaren Quelltext → kein
   struktureller Umbau, sondern `text`-Degradierung + Note (Phase 0).
2. **Index-Modell.** → **Vorschlag:** neue Variante `IndexType.FULLTEXT`, analog zur bereits
   gelösten `IndexType.SPATIAL` (kein Flag-Anbau an BTREE).
3. **SQLite-FTS5-Form.** → **Entschieden & umgesetzt (P4):** **external-content-FTS5**
   (`content='<basistabelle>'`) mit implizitem `rowid` (`content_rowid` default → kein Integer-PK-
   Lookup nötig, jede rowid-Tabelle trägt) + Initial-`'rebuild'` + drei Sync-Trigger (Insert/Update/
   Delete mit `'delete'`-Kommando für Konsistenz). **Korrektur zum ursprünglichen Vorschlag:** SQLite
   hat — wie SpatiaLite — **zwei** getrennte Emit-Pfade (generate `SqliteTableDdlSupport`, diff/migrate
   `SqliteDiffSimpleOps`); FTS5 lebt in **beiden**, gespeist aus **einer gemeinsamen SQL-Quelle**
   (`SqliteFullTextExpansion`, analog zum P2-`SqliteFullTextDegradation`). Die Idee „nur im
   Diff-Renderpfad" war ungenau — sie beschrieb die **Bootstrap**-Sonderregel aus
   [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md) (nur SpatiaLite),
   nicht die Index-Emission. Deterministisches Naming (FTS-Tabelle = Indexname, Trigger `<index>_ai/ad/au`)
   macht die Objekte für den P5-Reverse-Filter identifizierbar.
4. **MySQL-FULLTEXT-Platzierung.** → **Vorschlag:** `FULLTEXT`-Index inline im
   `MysqlIndexPartitionDdlHelper`-Emit-Pfad, Quelltext-Spalten als reguläre `TEXT`-Spalten.
5. **Diff/Migrate-Bewusstsein.** Die synthetisierten FTS5-Objekte (Virtual-Table + Trigger)
   müssen `diff`/`migrate` als **bekannt, nicht-driftend** gelten — Post-Compare-Filter wie
   beim SpatiaLite-Pendant (vermeidet Phantom-Drift).

## 5. Phasen (Reihenfolge: klein/risikoarm → strukturell)

- **P0 — Vorab-Fix Degradierungs-Note. ✅ ERLEDIGT 2026-06-28.** Der stumme
  `FullText → TEXT`-Abfall in MySQL/SQLite emittiert jetzt eine explizite
  Degradierungs-Note (**W132**) mit Hinweis auf den manuellen FTS5-/FULLTEXT-Pfad.
  **DoD erfüllt:** Cross-Dialect-`generate` einer `fulltext`-Spalte emittiert W132;
  Unit-Tests (MySQL + SQLite); Build grün; W132 in beiden Ledgern registriert.
- **P1 — ADR + Modell. ✅ ERLEDIGT 2026-06-28.**
  [ADR 0025](../../adr/0025-fulltext-source-columns-as-index.md) accepted (Quellspalten am
  `IndexType.FULLTEXT`-Index, **nicht** am Typ — `FullText` bleibt parameterlos). Modell:
  `IndexType.FULLTEXT` + optionales `IndexDefinition.textSearchConfig`; YAML-Codec
  (Serialize/Parse) + `spec/neutral-model-spec.md` + `spec/schema.json` synchron.
  **DoD erfüllt:** ADR accepted; YAML-Round-Trip-Test grün; schema.json-Contract-Fixture
  (fulltext-Index) validiert; Build grün.
- **P2 — PG-Reverse-Anreicherung. ✅ ERLEDIGT 2026-06-29 (inkl. Review-Härtung, keine Carve-Outs).**
  `tsvector_update_trigger`-Body parsen (quote-/klammer-/`''`-bewusst, links-wort-grenzen-
  geankert, GIN **und** GiST) → `sourceColumns` + Config füllen, Backing-Index durch
  `FULLTEXT`-Index ersetzen
  ([`PostgresFullTextIndexSynthesis`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresFullTextIndexSynthesis.kt),
  in [`PostgresSchemaReader`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaReader.kt)
  verdrahtet); nicht-parsebar → Modell unverändert (kein Verlust). Die Backing-`tsvector`-Spalte
  wird in `IndexDefinition.fullTextVectorColumn` getragen (ADR 0025), sodass Generate **und**
  Diff/Migrate den Index auch bei mehreren `tsvector`-Spalten je Tabelle eindeutig
  rekonstruieren. **Alle Pfade FULLTEXT-bewusst (Generate UND Diff je Dialekt):** PG expandiert
  zu `USING <gin|gist>(<vektor>)` (Original-AM erhalten via `fullTextAccessMethod`;
  `FULLTEXT_VECTOR_UNKNOWN`-Block bzw. W133/Warnung falls keine Vektorspalte — CreateTable
  warnt statt still); MySQL natives `CREATE FULLTEXT INDEX` (prefix-rule-exempt); SQLite W132 in
  **`createIndexSql`** (alle Caller, kein stiller BTREE) bis P4. **Review-Runde 2 gehärtet:**
  `fullTextVectorColumn` + `fullTextAccessMethod` sind **Generate-only-Hinweise** — im Modell, aber
  aus Comparator/Fingerprint/`CanonicalPayload` ausgeschlossen (analog `ordinal`), sonst
  Phantom-Diffs authored-vs-reversed; `textSearchConfig` bleibt semantisch (auch in
  `CanonicalPayload`). YAML-Codec + `spec/schema.json` + `neutral-model-spec.md` + `cli-spec.md`
  (`ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`) synchron. **DoD erfüllt** (live `make sample-db-smoke`):
  PG-Reverse von Pagila `film` erfasst `fulltext` + Quelltext-Spalten (`title`, `description`) +
  Config (`english`) + `gist`; PG→PG-Round-Trip 0 Diffs (`compare == baseline`, 0 Generate-Notes).
- **P3 — MySQL-Generate (FULLTEXT). ✅ ERLEDIGT 2026-07-01 (live-verifiziert).** `fulltext` →
  `TEXT`-Spalte (W132) + nativer `FULLTEXT`-Index über die Quelltext-Spalten. Der Generate-/Diff-Code
  stand bereits aus P2-Nebenarbeit ([`MysqlIndexPartitionDdlHelper`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt)
  `IndexType.FULLTEXT`-Branch, [`MysqlDiffSqlBuilders`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt),
  prefix-rule-exempt via [`MysqlIndexPrefix`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPrefix.kt));
  P3 war die **Live-Verifikation + DoD**, kein Kotlin-Change. Neue Assertions im pg2my-Smoke
  ([`smoke-cross-pg2my.sh`](../../../examples/sample-db/scripts/smoke-cross-pg2my.sh) Abschnitt „8b"),
  Notes-Baseline neu gepinnt (**W102 → W132**). **DoD erfüllt** (live MySQL, `make sample-db-cross-smoke-pg2my`):
  `film_fulltext_idx` existiert im Katalog (`index_type=FULLTEXT` über genau `title,description`);
  `MATCH(title, description) AGAINST('Astronaut')` liefert Treffer (Test-Assertion **`> 0`**; Snapshot 78,
  = PG `to_tsquery`); Nonsens-Term → 0;
  Cross-Dialect-Smoke grün; PG→PG-Baseline regressionsfrei (`compare == baseline`); `docs-check` 0 Befunde.
- **P4 — SQLite-Generate (FTS5). ✅ ERLEDIGT 2026-07-01 (live-verifiziert).** `fulltext` →
  FTS5-External-Content-Virtual-Table + `'rebuild'` + drei Sync-Trigger über die Quelltext-Spalten
  ([`SqliteFullTextExpansion`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteFullTextExpansion.kt),
  eine SQL-Quelle für **beide** Pfade: generate
  [`SqliteTableDdlSupport`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt),
  diff/migrate [`SqliteDiffSimpleOps`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSimpleOps.kt)
  inkl. DOWN/Rollback-Symmetrie). Die `fulltext`-Spalte degradiert weiter zu `TEXT` (W132); der Index
  degradiert nicht mehr. FTS5-Statements sind **POST_DATA** (`'rebuild'` nach dem Daten-Load). **DoD
  erfüllt** (`make sample-db-fulltext-sqlite-smoke`): (A) PG-Reverse Pagila → SQLite-Generate erzeugt
  die `film`-FTS5-DDL über (`title`,`description`); (B) `sqlite3`-Apply + Daten → FTS5-`MATCH` liefert
  Treffer inkl. `'rebuild'` **und** aller drei Sync-Trigger (Insert/Update/Delete) + Nonsens-
  Negativkontrolle; (C) `migrate --execute` legt die FTS5-Struktur real in einer frischen `.db` an
  (Diff-Pfad-Apply). Unit-Tests
  [`SqliteFullTextExpansionTest`](../../../adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteFullTextExpansionTest.kt).
  **Bewusst P5** (nicht P4): der *drift-freie* migrate-Round-Trip — `migrate --execute` endet heute
  mit Exit 5 (Post-Compare-Drift), weil der Reverse die FTS5-Shadow-Tabellen/Sync-Trigger noch nicht
  filtert und den Index noch nicht rekonstruiert.
- **P5 — Diff/Migrate-Härtung.** Reverse: FTS5-Shadow-Tabellen (`_data`/`_idx`/`_docsize`/`_config`)
  + die drei Sync-Trigger als bekannt/nicht-driftend **filtern** UND den `FULLTEXT`-Index aus der
  FTS5-Virtual-Table (Spalten) + Trigger rekonstruieren (SpatiaLite-Pendant). Zusätzlich: der
  Table-**Rebuild**-Pfad (`SqliteRebuildRenderer` via `createIndexSql`) muss die FTS5-Objekte als
  abhängig recreaten statt sie zu degradieren. **DoD:** `migrate`-Round-Trip **Exit 0**, kein
  Phantom-Drift (Teil C von `smoke-fulltext-sqlite.sh` wird auf Exit-0-Assertion verschärft).

## 6. Akzeptanzkriterien

- `fulltext`-Spalten round-trippen PG→MySQL **und** PG→SQLite **strukturerhaltend** —
  Volltext-Suche funktioniert am Ziel (`MATCH … AGAINST` bzw. FTS5-`MATCH`), live im
  Sample-DB-Harness verifiziert (kein reiner Unit-Beweis).
- **Kein stiller Verlust:** wo der strukturelle Umbau nicht möglich ist, greift die explizite
  Degradierungs-Note (P0).
- PG→PG-Baseline bleibt **0 Diffs** (keine Regression der ADR 0015-Errungenschaft).
- `migrate`-Round-Trip meldet **keinen** Phantom-Drift auf den synthetisierten FTS5-Objekten.
- Slice-ADR accepted; `spec/neutral-model-spec.md` + Serialisierung + schema.json synchron;
  `make docs-check` grün.

## 7. Vorbedingungen

- [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) (accepted) — `fulltext` als
  neutraler Typ.
- Eigene Slice-ADR (Phase 1) für die strukturellen Mapping-Entscheide.
- Strukturelles Vorbild: [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md) +
  [`../done/spatialite-migrate-roundtrip.md`](../done/spatialite-migrate-roundtrip.md).
- Sample-DB-Harness für die Live-Verifikation:
  [`../done/sample-db-integration-harness.md`](../done/sample-db-integration-harness.md).

## 8. Bezug

- Carve-Out-Tracker: [`carveout.md`](carveout.md), Abschnitt 8.
- Verwandter Trigger-Watch (degradierende PG-only-Typen):
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md).
