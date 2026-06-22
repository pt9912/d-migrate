# SpatiaLite migrate-/Apply-Round-Trip (5d-Restbefunde)

> Status: **DONE** (implementiert + live-verifiziert 2026-06-22; Closure unten).
>   Vorgeschichte: Vorabklärung → review-gehärtet 2026-06-22 (Befund 3 ergänzt, Befund
>   1/2 mit Code-Stelle + Entscheidungsempfehlung geschärft, Akzeptanz um Reverse erweitert).
> Trigger: VA4 (SQLite/SpatiaLite, [`spatial-harness-slice.md`](../in-progress/spatial-harness-slice.md)) —
>   beim vorgezogenen Live-Apply-Round-Trip aufgedeckt.
> Bezug (normativer Anker): das technische
>   Geometrie-/Spatial-Profil-Modell in [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md)
>   (Geometry-Typ, `geometry_type`, `srid`, Profil-Mapping, Fehlercodes E052/E120/W120)
>   und der `spatial`-Indextyp in [`spec/schema.json`](../../../spec/schema.json); ADR 0015
>   (first-class NeutralType, kein Native-Passthrough — Präzedenz-Muster). Eine
>   spatial-spezifische ADR existiert nicht; entsteht eine (siehe Befund 1), wird sie
>   hier nachgetragen.

## Kontext

VA4 (Treiber + Infrastruktur) ist erbracht und teils live-belegt:

- `IndexType.SPATIAL`-Modell; SQLite emittiert `CreateSpatialIndex`/`DisableSpatialIndex`
  statt zu blocken (Diff `renderAddIndex`/`renderDropIndex` + FULL-Generate
  `generateIndices`).
- `mod_spatialite` ist in der runtime-Dockerfile-Stage installiert; eine SQLite-
  Connection mit `?spatialite=true` lädt es opt-in (`enable_load_extension` +
  `SELECT load_extension('mod_spatialite')`, `HikariConnectionPoolFactory`).
- `schema generate --target sqlite --spatial-profile spatialite` emittiert
  **live-verifiziert** `CREATE TABLE` + `AddGeometryColumn(..., 4326, 'POINT', 'XY')`
  + `CreateSpatialIndex('places', 'shape')` (Smoke-Abschnitt `[lite]`).
- `schema migrate --spatial-profile` ist verdrahtet; die SpatiaLite-Extension-
  Verfügbarkeit wird aus der Ziel-`?spatialite=true`-Connection als
  `VERIFIED_PRESENT` abgeleitet (hebt `EXTENSION_DEPENDENCY_UNKNOWN` auf).

Der **volle `migrate --execute`-Round-Trip** (Index real in einer frischen `.db`,
dann zurück-reverse-bar) scheitert aber noch an **drei** Restbefunden: zwei auf der
Apply-Seite (Befund 1, 2), einer auf der Reverse-Seite (Befund 3).

## Befund 1 — `InitSpatialMetaData()` fehlt

Eine **neue** SpatiaLite-DB hat keine `geometry_columns`/`spatial_ref_sys`-Metatabellen.
`AddGeometryColumn(...)` bricht dort mit `unexpected metadata layout` ab. Vor dem
ersten `AddGeometryColumn` muss einmal pro DB `SELECT InitSpatialMetaData(1);` (bzw.
`InitSpatialMetaDataFull`) ausgeführt werden. Im aktuellen Code kommt
`InitSpatialMetaData` **nirgends** vor.

**Entscheidung (Empfehlung, vor Slice-Start zu bestätigen):** Das Bootstrap-Statement
gehört in den **Execution-/Runner-Pfad**, **nicht** in den deterministischen
`schema generate`-Output. Begründung:

- Der Generate-DDL-Output muss zustandsfrei und deterministisch bleiben (DDL-Goldens,
  reproduzierbare Diffs). Ein `InitSpatialMetaData()`-Statement, das nur greift, wenn
  die Metatabellen noch fehlen, ist **zustandsabhängig** — es gehört nicht in das
  generierte DDL.
- Stattdessen prüft der Runner einmal pro Ziel-`.db` idempotent
  `CheckSpatialMetaData()` und führt `InitSpatialMetaData(1)` nur aus, wenn sie
  fehlt — analog zur bestehenden `?spatialite=true`-Extension-Verkabelung im
  `SchemaMigrateRunner`.

Diese Wahl (Generate- vs. Execution-Pfad; Idempotenz-Guard) ist architektur­relevant
und damit **mini-ADR-würdig**; bei Slice-Start als ADR festhalten und den Anker oben
nachtragen.

## Befund 2 — Diff-`CreateTable` rendert Geometrie-Index als normalen `CREATE INDEX`

Der **FULL-Generate**-Pfad (`schema generate`) emittiert korrekt `CreateSpatialIndex`.
Der **migrate-Diff**-Pfad rendert für eine *neu erzeugte* Tabelle den Geometrie-Index
dagegen als generischen `CREATE INDEX "idx" ON "t" ("shape")` (auf eine Spalte, die
erst per `AddGeometryColumn` entsteht).

**Ursache lokalisiert** (Hypothese aufgelöst): in
`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSimpleOps.kt`
(Z. 68–70) läuft `renderCreateTable` die Indizes über den **generischen** Pfad und ruft
`indexTouchesGeometry` **gar nicht** auf:

```kotlin
for (idx in op.table.indices) {
    ctx.emit(op, ctx.sql.createIndexSql(tableName, idx))   // generisch, kein Geometrie-Zweig
}
```

`renderAddIndex` (Z. 180/187) macht es dagegen richtig — `ctx.indexTouchesGeometry(table, op.index)`
→ `createSpatialIndex(...)`. Es ist also **nicht** so, dass die Erkennung „nicht
greift"; sie wird im `CreateTable`-Kontext schlicht nicht aufgerufen.

**Fix:** Die `CreateTable`-Index-Schleife durch denselben
`indexTouchesGeometry → createSpatialIndex`-Zweig leiten wie `renderAddIndex`.

## Befund 3 — Reverse erkennt die SpatiaLite-Metatabellen + den R*Tree-Index nicht korrekt

Das Akzeptanzkriterium „Reverse der migrierten `.db` erkennt Geometrie + Spatial-Index"
scheitert heute an zwei Reverse-seitigen Lücken — beide aufgedeckt erst durch die
Apply-Seite (Befund 1 erzeugt die Metatabellen, deren Reverse-Behandlung fehlt):

**3a — Metatabellen-Filter fehlt.** `InitSpatialMetaData()` legt ein Dutzend
Metatabellen an, die **nicht** mit `sqlite_` beginnen (`geometry_columns`,
`spatial_ref_sys`, `spatialite_history`, `views_geometry_columns`,
`virts_geometry_columns`, `spatial_ref_sys_aux`, …) **plus** die R*Tree-Index-Tabellen
`idx_<t>_<col>` (+ `_node`/`_parent`/`_rowid`). Der Reverse-Lister filtert aber nur
`name NOT LIKE 'sqlite_%'` (`SqliteMetadataQueries.kt`, Z. 18/30). → Diese Tabellen
würden als **User-Tabellen** reverse-engineered; die Zeilen-/Schema-Parität bricht.
**Fix:** SpatiaLite-System- und R*Tree-Tabellen beim Reverse ausschließen — das
Pendant zur `spatial_ref_sys`-Sonderbehandlung, die der Smoke bei PostGIS bereits macht.

**3b — Spatial-Index-Reverse-Mechanik unspezifiziert.** Der SpatiaLite-Spatial-Index
ist eine **R*Tree-Virtual-Table** + das Flag `geometry_columns.spatial_index_enabled` —
*kein* `sqlite_master`-Eintrag mit `type='index'`. Der heutige Reverse-Index-Pfad
(`type='index'`) sieht ihn nicht → der Spatial-Index auf der Geometriespalte käme gar
nicht bzw. nicht als `IndexType.SPATIAL` zurück (False-Negative). **Fix:** beim Reverse
`geometry_columns.spatial_index_enabled` (bzw. `CheckSpatialIndex()`) lesen und daraus
den Tabellen-Index mit `IndexType.SPATIAL` rekonstruieren; nur unter geladener Extension.

## DoD-Größe / Schnitt-Hinweis

5d bündelt mit Befund 3 jetzt: Bootstrap (Befund 1), CreateTable-Index-Fix (Befund 2),
Reverse-Filter + Reverse-Index-Erkennung (Befund 3), Apply-Round-Trip, Reverse-Round-Trip
und den Smoke-Abschnitt — das sind ≥4 DoD-Punkte über die Schichten Diff-Generierung,
Runner, Reverse und Harness; grenzwertig für „ein Review in einer Sitzung" (Regelwerk
Modul 5). **Erwägen, 5d zu schneiden:**

- **5d-1 (Apply):** Befund 1 (Runner-Bootstrap `CheckSpatialMetaData`/`InitSpatialMetaData`)
  + Befund 2 (CreateTable-Spatial-Index-Fix) → `migrate --execute` legt Geometriespalte
  + aktivierten Spatial-Index real an.
- **5d-2 (Reverse + Harness):** Befund 3a/3b (System-/R*Tree-Tabellen-Filter +
  Spatial-Index-Reverse) + voller Reverse-Round-Trip + `[lite]`-Apply-Smoke-Abschnitt.

5d-1 ist eigenständig lieferbar (Apply belegbar ohne Reverse); 5d-2 baut darauf auf.

## Akzeptanz (für 5d)

- **Apply (5d-1):** `schema migrate --execute --spatial-profile spatialite` gegen eine
  frische `?spatialite=true`-`.db`: `status: ok`, und die `.db` trägt real die
  Geometriespalte (`geometry_columns`-Eintrag) **und** einen aktivierten Spatial-Index
  (`geometry_columns.spatial_index_enabled = 1` bzw. R*Tree-Tabelle `idx_<t>_<col>`).
- **Reverse (5d-2):** Round-Trip-`reverse` der migrierten `.db` liefert **nur** die
  User-Tabelle(n) (SpatiaLite-System- und R*Tree-Tabellen sind ausgeschlossen — keine
  `geometry_columns`/`spatial_ref_sys`/`idx_*` im Output) und rekonstruiert die
  Geometriespalte **mit** dem Tabellen-Index `type: spatial` (aus
  `spatial_index_enabled`), nicht als normalen Index und nicht gar nicht.
- **Determinismus:** der `schema generate`-DDL-Output bleibt unverändert (kein
  `InitSpatialMetaData` im generierten DDL — Befund-1-Entscheidung); DDL-Goldens grün.
- **Harness:** Aufnahme als `[lite]`-Apply-Abschnitt in
  `examples/sample-db/scripts/smoke-spatial.sh`.

## Closure (2026-06-22)

**Umsetzung.** Alle drei Befunde implementiert (alle im SQLite-Treiber):
- Befund 1 — `SqliteSpatialDiffOps.ensureSpatialMetadataBootstrap` emittiert vor dem
  ersten `AddGeometryColumn` einmalig `SELECT CASE WHEN CheckSpatialMetaData() = 0 THEN
  InitSpatialMetaData() END;` (deterministisch, idempotent, in der Runner-Transaktion).
  Pfad-/Determinismus-Entscheidung in **ADR 0016** (verfeinert die ursprüngliche
  Execution-Pfad-Empfehlung → Diff-Renderpfad, kein Dialekt-Leck).
- Befund 2 — `SqliteDiffSimpleOps.renderCreateTable` routet Geometrie-Indizes über
  `CreateSpatialIndex` statt generischem `CREATE INDEX`.
- Befund 3 — `SqliteSchemaReader`/`SqliteMetadataQueries` lesen `geometry_columns`
  (SRID + `spatial_index_enabled`), rekonstruieren `IndexType.SPATIAL` und schließen
  SpatiaLite-Metatabellen (exakte Liste in `SqliteTypeMapping`) + R*Tree-Schatten aus.

Begleitend: Refactor `SqliteSpatialDiffOps` (Spatial-Helfer aus `SqliteDiffSimpleOps`
ausgegliedert; löst Detekt-`TooManyFunctions` ohne `@Suppress`).

**Closure-Kriterien (beobachtbar).**
1. `make sample-db-spatial-smoke` grün — `[lite]`-Apply: `migrate --execute` legt gegen
   eine frische `?spatialite=true`-`.db` real Geometrie + R*Tree-Spatial-Index an, und
   Reverse rekonstruiert sie verlustfrei (SRID 4326 + `type: spatial`, alle Metatabellen
   gefiltert).
2. `:adapters:driven:driver-sqlite:check` grün (Unit-Tests Befund 1/2/3, detekt, kover).

**Lerneintrag.**
- *Neuer Sensor:* `[lite]`-Apply-Abschnitt in `smoke-spatial.sh` (live migrate→reverse).
- *Geschärfte Regel:* ADR 0016 — zustandsabhängige DDL gehört als deterministisch-
  geguardetes Statement in den Diff-Renderpfad, nicht in die generische Execution-Stage.
- *Benannte Spec-/Code-Lücke (Review-Fund, NICHT spatial):* `migrate --execute` gegen
  SQLite meldet stets Post-Compare-Drift (Exit 5) wegen `identifier`→`primary_key`-
  Fingerprint-Asymmetrie → eigene Folge-Slice
  [`open/sqlite-migrate-postcompare-identifier-drift.md`](../open/sqlite-migrate-postcompare-identifier-drift.md).
- *Bekannte Grenzen (bewusst außerhalb 5d):* nur R*Tree (`spatial_index_enabled = 1`);
  MbrCache (2) wird nicht modelliert. `schema generate`-Standalone-DDL trägt das
  Bootstrap (noch) nicht (ADR 0016). Composite-Index mit Geometriespalte folgt der
  bestehenden `renderAddIndex`-Semantik (Single-Geometry-Spalte).
