# SpatiaLite migrate-/Apply-Round-Trip (5d-Restbefunde)

> Status: Vorabklärung
> Trigger: VA4 (SQLite/SpatiaLite, [`spatial-harness-slice.md`](../in-progress/spatial-harness-slice.md)) —
>   beim vorgezogenen Live-Apply-Round-Trip aufgedeckt.
> Aktivierungsbedingung: wandert nach `../next/` bzw. in die Sub-Slice **5d**,
>   sobald der volle SpatiaLite-`migrate --execute`-Round-Trip gebaut wird.

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

Der **volle `migrate --execute`-Round-Trip** (Index real in einer frischen `.db`)
scheitert aber noch an zwei Restbefunden:

## Befund 1 — `InitSpatialMetaData()` fehlt

Eine **neue** SpatiaLite-DB hat keine `geometry_columns`/`spatial_ref_sys`-Metatabellen.
`AddGeometryColumn(...)` bricht dort mit `unexpected metadata layout` ab. Vor dem
ersten `AddGeometryColumn` muss einmal pro DB `SELECT InitSpatialMetaData(1);` (bzw.
`InitSpatialMetaDataFull`) emittiert werden — abhängig davon, ob die Metatabellen
bereits existieren. Offene Frage: Bootstrap-Statement immer voranstellen vs.
idempotent prüfen (`CheckSpatialMetaData()`), und ob das in den Generate- oder den
Execution-/Runner-Pfad gehört.

## Befund 2 — Diff-`CreateTable` rendert Geometrie-Index als normalen `CREATE INDEX`

Der **FULL-Generate**-Pfad (`schema generate`) emittiert korrekt `CreateSpatialIndex`.
Der **migrate-Diff**-Pfad rendert für eine *neu erzeugte* Tabelle den Geometrie-Index
dagegen als generischen `CREATE INDEX "idx" ON "t" ("shape")` (auf eine Spalte, die
erst per `AddGeometryColumn` entsteht). Der Diff-`CreateTable`-/`AddIndex`-Pfad muss
denselben `CreateSpatialIndex`-Zweig nehmen wie `renderAddIndex` (vermutlich greift
die `indexTouchesGeometry`-Erkennung im migrate-`CreateTable`-Kontext nicht, oder die
Indizes der neuen Tabelle laufen über einen generischen Pfad). Zu diagnostizieren.

## Akzeptanz (für 5d)

- `schema migrate --execute --spatial-profile spatialite` gegen eine frische
  `?spatialite=true`-`.db`: `status: ok`, und die `.db` trägt real die Geometriespalte
  (`geometry_columns`-Eintrag) **und** einen aktivierten Spatial-Index
  (`spatial_index_enabled = 1` bzw. R*Tree-Tabelle `idx_<t>_<col>`).
- Round-Trip-`reverse` der migrierten `.db` erkennt Geometrie + Spatial-Index.
- Aufnahme als `[lite]`-Apply-Abschnitt in `examples/sample-db/scripts/smoke-spatial.sh`.
