# Vorschlag: Strukturelle Cross-Dialect-Volltext-Übersetzung (FTS5 / FULLTEXT)

> **Status:** Draft (Vorschlag, 2026-06-27)
> **Trigger:** Carve-Out aus [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md)
> (Abschnitt „Abgrenzung"), getrackt in
> [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 8, Zeile 1.
> ADR 0015 modelliert den PG-`tsvector` als first-class neutralen Typ `fulltext`, schließt
> die **strukturelle** Übersetzung nach MySQL/SQLite aber bewusst aus — bis dahin
> degradiert `fulltext` cross-dialect zu `text`.
> **Aktivierungsbedingung:** Sobald cross-dialektische Volltext-Fidelity priorisiert wird
> (z. B. ein Sample-DB-Harness-Finding, das den `text`-Degradierungspfad als unzureichend
> ausweist), wandert dieser Vorschlag nach [`../next/`](../next/) — **dort** mit
> Phasenschnitt, Akzeptanzkriterien **und einer eigenen ADR**
> ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md) reserviert
> ausgearbeitete Phasen/Akzeptanz für `next/`; ADR 0015 nennt für diesen Folge-Slice
> explizit „idealerweise mit eigener ADR"). Dieses `open/`-Dokument bleibt auf
> Vorschlags-Altitude: Ziel, Scope und die offenen Designentscheidungen.

## 1. Ziel

Den neutralen Volltext-Typ `fulltext` cross-dialect **strukturell** abbilden statt ihn zu
`text` zu degradieren — also die Volltext-Suchfähigkeit auf MySQL und SQLite erhalten:

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
- **PG-Reverse erfasst alles Nötige bereits:** `tsvector` → `FullText` in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt);
  der GiST-Index überlebt via `tsvector_ops` in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresIndexOpClass.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresIndexOpClass.kt);
  der **befüllende Trigger** (`tsvector_update_trigger(...)`) landet als roher Body in
  `TriggerDefinition.body` über
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaProgrammabilityReaders.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaProgrammabilityReaders.kt).
  **Wichtig:** Die **Quelltext-Spalten** (z. B. Pagila `title`, `description`) und die
  Text-Search-Config (`pg_catalog.english`) stehen **nur im Trigger-Body** — der `tsvector`
  selbst ist ein vorberechneter Vektor, kein lesbarer Text.
- **Cross-Dialect-Generate degradiert heute stumm zu `TEXT`** — `is NeutralType.FullText ->
  "TEXT"` in
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt)
  und
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt).
  An dieser Stelle wird **keine** Degradierungs-Note angehängt — obwohl ADR 0015 eine Note
  vorsieht. (Verifizieren und ggf. nachrüsten, siehe offene Entscheidung 5.)
- **Heute keine FULLTEXT-Index-Logik:** der MySQL-Index-Emit-Pfad
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt)
  (aufgerufen aus
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt))
  kennt SPATIAL, aber kein FULLTEXT; `FULLTEXT` ist in
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt)
  explizit als „not modelled here" vermerkt.
- **Strukturelles Vorbild = SpatiaLite.** Genau das „eine Spalte → mehrere DDL-Objekte"-
  Muster existiert schon für Geometrie in
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSpatialDiffOps.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSpatialDiffOps.kt):
  eine Geometrie-Spalte expandiert zu Bootstrap-Statement + `AddGeometryColumn` +
  `CreateSpatialIndex` (idempotenter Diff-Renderpfad,
  [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md)). Der SQLite-Index-Emit-Pfad
  [`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt)
  routet Geometrie-Indizes bereits über diesen Sonderpfad — FTS5 würde dasselbe Muster
  spiegeln.
- **Modell ist tragfähig:** `TriggerDefinition` (Multi-Event-`Set`, AFTER-Timing, roher
  `body`) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt)
  und `IndexDefinition`/`IndexType` (BTREE…SPATIAL, **kein FULLTEXT**) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt).

## 3. Scope

### 3.1 In Scope

- **MySQL-Generate:** `fulltext`-Spalte → reguläre `TEXT`-Spalte **plus** `FULLTEXT`-Index
  über die Quelltext-Spalte(n). Index-Emit-Pfad im `MysqlIndexPartitionDdlHelper` erweitern.
- **SQLite-Generate:** `fulltext`-Spalte → FTS5-Virtual-Table + Sync-Trigger (INSERT/UPDATE/
  DELETE), nach dem `SqliteSpatialDiffOps`-Bootstrap-Muster.
- **Modell:** ggf. `IndexType.FULLTEXT` (oder ein FULLTEXT-Flag) und ein Weg, die
  Quelltext-Spalten zu tragen (siehe offene Entscheidung 1 — sie stehen heute nur im
  Trigger-Body).
- **Degradierungs-Note** mit Hinweis auf den manuellen Pfad bleibt als Fallback, wenn die
  strukturelle Übersetzung (mangels Quelltext-Spalten o. ä.) nicht möglich ist.
- **Eigene ADR** für die Mapping-Entscheide (Quelltext-Herleitung, Trigger-Synthese,
  Diff-/Migrate-Verhalten), referenziert aus diesem Slice.

### 3.2 Nicht in Scope

- **MySQL/SQLite → PG-Reverse** struktureller Volltext-Konstrukte (FULLTEXT-Index / FTS5
  zurück zu `tsvector`) — eigene Richtung, eigene Entscheidung; hier nur PG → MySQL/SQLite.
- **Volltext-Such-Queries übersetzen** (`to_tsquery`/`MATCH … AGAINST`/`MATCH … `) — dies
  betrifft Schema-/Daten-Migration, nicht Query-Rewriting.
- Erweiterte Text-Search-Konfigurationen jenseits dessen, was aus dem Trigger-Body sauber
  ableitbar ist (Sprach-/Wörterbuch-Feinheiten).

## 4. Offene Designentscheidungen

1. **Quelltext-Herleitung (die Kernfrage).** FTS5/FULLTEXT indizieren **lesbaren
   Quelltext**, der PG-`tsvector` ist ein vorberechneter Vektor **ohne** lesbaren Text. Die
   Quelltext-Spalten (Pagila: `title`, `description`) stehen nur im
   `tsvector_update_trigger`-Body. Zu entscheiden: den Trigger-Body parsen, um die
   Quellspalten zu gewinnen, gegen eine explizitere Modellierung (das `fulltext`-Modell um
   `sourceColumns` anreichern, beim PG-Reverse aus dem Trigger gefüllt). Ohne Quelltext gibt
   es **nichts zu indizieren** — diese Entscheidung trägt den ganzen Slice.
2. **Modell-Form des Index.** `IndexType.FULLTEXT`-Enum-Variante gegen ein FULLTEXT-Flag auf
   `IndexDefinition`. Konsistenz-Anker: wie `SPATIAL` schon gelöst ist.
3. **SQLite-FTS5-Synthese.** Wie weit das `SqliteSpatialDiffOps`-Muster trägt: Virtual-Table
   anlegen, initial befüllen, drei Sync-Trigger synthetisieren — und ob das im
   Diff-Renderpfad (wie SpatiaLite-Bootstrap, ADR 0016) oder generate-seitig sitzt.
4. **Externer Content vs. eigenständige FTS5.** FTS5 kann „contentless"/„external content"
   (Index referenziert die Basistabelle) oder eigenständig sein — beeinflusst Sync-Trigger
   und Speicher. Default-Wahl festlegen.
5. **Fehlende Degradierungs-Note (Sub-Befund).** Der heutige `FullText → TEXT`-Abfall in
   MySQL/SQLite-Typmapper ist **stumm**, obwohl ADR 0015 eine Note vorsieht. Verifizieren, ob
   anderswo eine Note greift; falls nicht, sie nachrüsten — unabhängig vom (größeren)
   strukturellen Rest, ggf. als kleiner Vorab-Fix.
6. **Diff/Migrate, nicht nur Generate.** Über reines `generate` hinaus muss `diff`/`migrate`
   die FTS5-Virtual-Table + Trigger als **bekannte, nicht-driftende** Objekte behandeln
   (vgl. den SpatiaLite-Post-Compare-Filter für interne Trigger/Views), sonst meldet der
   Round-Trip Phantom-Drift.

## 5. Bezug

- ADR (Quelle): [0015](../../adr/0015-fulltext-tsvector-neutral-type.md) (Abschnitt
  „Abgrenzung").
- Carve-Out-Tracker: [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 8.
- Strukturelles Vorbild: [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md)
  (SpatiaLite-Bootstrap im Diff-Renderpfad) +
  [`../done/spatialite-migrate-roundtrip.md`](../done/spatialite-migrate-roundtrip.md).
- Sample-DB-Harness (Aktivierungs-Kontext):
  [`../done/sample-db-integration-harness.md`](../done/sample-db-integration-harness.md).
