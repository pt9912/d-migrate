# Plan: Sample-DB-Harness Phase 5 — Spatial (PostGIS + MySQL native + Spatialite)

> Dokumenttyp: Done-Plan (Folge-Slice von [`sample-db-integration-harness.md`](../done/sample-db-integration-harness.md))
> Status: **DONE — Phase 5 komplett, live-verifiziert (2026-06-22; Closure am Ende).**
> Vorgeschichte (in Arbeit seit 2026-06-21). **VA1 (a–d) implementiert, code-review-
> gehärtet (`25efa6b5`) + LIVE-VERIFIZIERT (`3e57769f`)**: `cfb7ab78` Erkennung,
> `0c6ee1d7`+`961d919e` Read-Projektion (plain WKB), `961d919e` Geometrie-Bind
> (`ST_GeomFromWKB`), `ca0afc26` Preflight. **Zwei Review-Bugs behoben** (dialekt-
> bewusste Erkennung — native PG-`point`/`polygon` nicht mehr fälschlich gewrappt;
> ChunkSchema trägt echt `Geometry`). **VA1-Live-Smoke grün** (`smoke-spatial.sh`,
> vorgezogenes 5a/5b): Point+Polygon round-trippen verlustfrei PG→PG (PostGIS) **und**
> MySQL→MySQL (native), native PG point unbeschadet. Kanonisches Format = **WKB**.
> **VA2 (SRID-Reverse + Daten-Bind mit Ziel-SRID) implementiert + LIVE-VERIFIZIERT**:
> Reverse liest SRID/Subtyp (PG `geometry_columns`, MySQL `SRS_ID`), Bind nutzt
> `ST_GeomFromWKB(?, srid)`; Smoke-Abschnitt SRID 4326 grün PG→PG **und** MySQL→MySQL
> (`ST_SRID=4326`, Wert identisch). **VA2-X1 (Cross-Dialect-Achsenreihenfolge) BEHOBEN
> + LIVE-VERIFIZIERT**: Cross-Dialect-Smoke fand Achsentausch (PostGIS long-lat vs
> MySQL lat-long bei SRID 4326, False-Green bei `ST_AsText`); Fix = MySQL durchgängig
> `axis-order=long-lat` (Read+Bind), `[xd]`-Smoke grün beidseitig (semantischer
> Vergleich). **VA3 (räumliche Indizes) implementiert + LIVE-VERIFIZIERT**:
> `IndexType.SPATIAL`+`SPGIST`; MySQL `SPATIAL INDEX` reverse/generate/diff (Block raus),
> PostGIS `USING GIST`/`SPGIST` (kein `USING SPATIAL`), B-Tree-auf-Geometrie bleibt
> geblockt; `[idx]`-Smoke grün (reverse→generate→apply, realer SPATIAL-Index im Katalog).
> **VA4 (SQLite/SpatiaLite) KOMPLETT ERLEDIGT + LIVE-VERIFIZIERT (2026-06-22)**: SQLite
> `CreateSpatialIndex` (Block raus), `mod_spatialite` im Image + opt-in
> `?spatialite=true`-Loading, `migrate --spatial-profile`. **5d voll umgesetzt:** alle drei
> Restbefunde behoben (Befund 1 `InitSpatialMetaData()`-Bootstrap im Diff-Renderpfad +
> ADR 0016, Befund 2 CreateTable-Geometrie-Index→`CreateSpatialIndex`, Befund 3 Reverse
> liest SRID + `spatial_index_enabled` + filtert Metatabellen/R*Tree-Schatten). `[lite]`-Apply
> live: `migrate --execute` legt Geometrie + R*Tree-Index real an, Reverse rekonstruiert
> verlustfrei. Closure → [`done/spatialite-migrate-roundtrip.md`](../done/spatialite-migrate-roundtrip.md).
> Offen: VA5 (Sample-Pin), volle Sub-Slices 5a–5d.
> Scope dreirundig review-gehärtet. **Wichtigste Review-Korrektur:** Phase 5 ist **kein reiner
> „Absicherungs"-Slice** — der Spatial-Datenpfad (Geometrie-*Werte* transferieren)
> und die Spatial-*Indizes* sind im Code **nicht** vorhanden; nur DDL-Typ-Abbildung,
> Profil-Policy und PG-GIST-Gate sind implementiert. Phase 5 = **implementieren + absichern**.
> Trigger: Slice-Grenze Phase 0–3 ist DoD-komplett; Phase 5 ist ausgegliederter Folge-Slice.
> Review-Befund 2026-06-21 (a): die ursprüngliche Skizze hatte das **MySQL-`native`-Profil
> vergessen**; (b) der Slice unterschätzte den Implementierungsumfang (siehe „Code-Lücken").
> Referenzen: ADR 0014 (Harness-Mechanik), ADR 0004 (Planning-Struktur),
> ADR 0015 (NeutralType-Präzedenz, kein Native-Passthrough),
> [`../open/test-database-candidates.md`](../open/test-database-candidates.md) (Kandidaten-Katalog).
> Nicht-blockierend für 1.0.0 (Spatial ist Zusatz-Abdeckung).

## Ziel

Spatial-Round-Trips end-to-end gegen das echte CLI absichern — für **alle drei**
Dialekt-Spatial-Profile (`postgis`/`native`/`spatialite`) **plus** Cross-Dialect-
Spatial. d-migrate kennt `--spatial-profile postgis|native|spatialite|none` (auf
`schema generate` und den `export`-Schema-Tool-Befehlen Flyway/Liquibase/Django/Knex —
**nicht** auf dem Daten-`data export`/`transfer`/`import`) und modelliert Geometrie
als first-class `NeutralType.Geometry` (ADR 0015-Muster). **Aber:** der
*Daten*-Transfer-Pfad (Erkennung **und** Wert-Bindung) und die Spatial-Index-
Generierung fehlen — Phase 5 muss sie zuerst bauen.

## Was ist implementiert vs. Code-Lücken (Review-verifiziert)

| Baustein | Status | Beleg |
|---|---|---|
| `SpatialProfile`-Enum + Policy (`defaultFor`/`allowedFor`) | ✅ implementiert | `DdlGenerationOptions.kt` |
| PG `geometry(Subtype,srid)`-DDL + GIST-Gate | ✅ implementiert | `PostgresTypeMapper.kt`, `PostgresIndexOpClass.kt` |
| MySQL `GEOMETRY/POINT/…`-DDL + SRID-**Kommentar-Hint** | ✅ teilweise | `MysqlColumnConstraintHelper.kt` (`/*!80003 SRID … */` + Warnung W120) |
| SQLite SpatiaLite `AddGeometryColumn`-DDL | ✅ teilweise | `SqliteDiffSimpleOps.kt` |
| **Geometrie-Erkennung im Read-Pfad** | ✅ **VA1b** für PostgreSQL/MySQL/MSSQL (Metadaten-Vorabfrage + `ST_AsBinary`); SQLite offen → [`../open/spatial-read-path-geometry-detection.md`](../open/spatial-read-path-geometry-detection.md) | `AbstractJdbcDataReader.probeColumns` markiert die Spalten, `JdbcChunkSequence` überschreibt den Typ; `SqliteDataReader` probt nicht → Chunk-/Parquet-Header trägt Text statt Geometry |
| **Geometrie-WERT-Bindung** (`data transfer`/`export`/`import`) | ✅ **VA1c** — die Schreibpfade holen die SRID aus dem Zielkatalog und binden `ST_GeomFromWKB(?, srid)`; kein `--spatial-profile` nötig | `PostgresDataWriter`/`MysqlDataWriter` holen die SRID aus dem Zielkatalog und binden `ST_GeomFromWKB(?, srid)`; kein WKB/WKT-Konverter (vgl. `JdbcForeignValueNormalizer`); kein dialekt-spezifisches Geometrie-Binding in den `*TableImportSession` |
| Import-Preflight für Geometrie | ✅ **VA1d** — verlangt ein Geometrie-Ziel; Geometrie→Text wird abgelehnt | `ImportTypeCompatibility.isGeometryCompatible` verlangt ein Geometrie-Ziel (Geometrie→Nicht-Geometrie durch) |
| **MySQL SPATIAL-Index** | ✅ **VA3** | `IndexType.SPATIAL`; Reverse + `CREATE SPATIAL INDEX`-Emit (Block entfernt) |
| **SQLite SpatiaLite Spatial-Index** (`CreateSpatialIndex`) | ✅ **VA4** | `SqliteDiffSimpleOps.createSpatialIndex` + FULL-Generate `spatialIndexStatement` (Block raus) |
| **PG-Reverse SRID/Subtyp-Capture** | ✅ **VA2** | `geometry_columns` → `geometrySubtype`/`geometrySrid` |
| **MySQL-Reverse SRID-Capture** | ✅ **VA2** | `information_schema.columns.srs_id` → `ColumnInput.srsId` |
| `mod_spatialite` im CLI-Image + Extension-Loading | ✅ **VA4** | Dockerfile-runtime `libsqlite3-mod-spatialite`; opt-in `?spatialite=true` → `load_extension` |

→ **Konsequenz:** „Geometrie-Werte round-trippen" und „räumlicher Index belegt" sind
mit dem heutigen Code **nicht erfüllbar** — sie sind Implementierungs-Vorarbeit,
nicht bloße Harness-Verkabelung.

## Code-Vorarbeitspakete (vor den Harness-Sub-Slices)

- **VA1 — Geometrie-Wert-Transfer auf dem Datenpfad.** Mehr als ein Konverter —
  vier Teilstücke:
  - **VA1a Erkennung. ✅ ERLEDIGT (`cfb7ab78`, Review-Härtung `25efa6b5`).** Geometrie
    wird **dialekt-bewusst** erkannt (`isGeometryTypeName`-Hook): PostgreSQL nur
    `geometry` (NICHT die nativen PG-Typen point/polygon/line/box/path/circle/lseg —
    die heißen wie OGC-Subtypen, sind aber kein WKB), MySQL alle OGC-Namen, SQLite
    aus. Die Markierung sitzt in den `probedColumns` der Vorabfrage und fließt sowohl
    in die Projektion (VA1b) als auch ins ChunkSchema (`neutralType=Geometry`, R2) —
    der ursprüngliche dialekt-blinde Mapper-Branch ist entfernt. SRID null → VA2.
  - **VA1b Read-Projektion. ✅ ERLEDIGT (`0c6ee1d7`, Format-Korrektur `961d919e`).**
    `AbstractJdbcDataReader` macht für Treiber mit `supportsGeometryRead` eine
    Metadaten-Vorabfrage (`SELECT * … WHERE 1 = 0`), erkennt Geometriespalten und
    wrappt sie via `geometryReadExpression(col) AS col`: PG **und** MySQL
    `ST_AsBinary` (**plain WKB**). Bewusst **kein** EWKB — EWKB ist nicht
    cross-dialect-tauglich (MySQL versteht das SRID-Flag nicht); WKB ist das
    einheitliche, verlustfreie Format, SRID separat (VA2). Ohne Geometrie bleibt die
    Projektion `*` (kein Delta). SQLite aus → VA4. Pure Helfer + E2E-Test (sqlite-jdbc).
  - **VA1c Bind. ✅ ERLEDIGT (`961d919e`).** `AbstractTableImportSession.valuePlaceholder`
    wrappt Geometrie-Zielspalten als `<geometryBindConstructor>(?)` statt `?`; PG +
    MySQL → `ST_GeomFromWKB` (akzeptiert plain WKB von VA1b, auch cross-dialect).
    Das WKB-`byte[]` wird normal gebunden (PG bytea / MySQL blob); SRID 0 → VA2.
    Alle Placeholder-Stellen umgestellt; SQLite kein Konstruktor → VA4. Gewählt:
    **WKB + SRID** (verlustfrei) statt WKT (PostGIS-dokumentierter Float-Verlust).
  - **VA1d Preflight. ✅ ERLEDIGT (`ca0afc26`).** `ImportTypeCompatibility.isType-
    Compatible(Geometry)` rechnet jetzt echt statt bedingungslos `true`: kompatibel
    mit Geometrie-Ziel (typeName in `GeometryType.KNOWN_VALUES`) **oder** Text-Ziel
    (bewusste WKT-/Text-Degradation); andere Ziele (Integer/Binary/Temporal) sind
    inkompatibel. `Geometry→Text` bewusst erhalten (keine Regression gegen den
    `none`-Profil-/Text-Fallback). Test differenziert (Geometrie/Text/inkompatibel).
  **✅ VA1 (a–d) FERTIG + LIVE-VERIFIZIERT (`25efa6b5` Review-Härtung, `3e57769f`
  Live-Smoke):** der Geometrie-Wert läuft als plain WKB von der Quelle (VA1a
  dialekt-bewusste Erkennung + VA1b Read-Projektion `ST_AsBinary`) bis in die
  Ziel-Geometriespalte (VA1c Bind `ST_GeomFromWKB` + explizites `setBytes`),
  Preflight nur Geometry→Geometry (VA1d). Der **VA1-Live-Smoke** (`smoke-spatial.sh`,
  `make sample-db-spatial-smoke`) belegt gegen **echtes PostGIS + MySQL**: Point +
  Polygon round-trippen verlustfrei PG→PG und MySQL→MySQL (`ST_AsText`-Gleichheit +
  `ST_IsValid`), native PG point unbeschadet (R1). Damit ist VA1 als Geometrie-
  Wert-Transfer bestätigt; offen bleibt **SRID** (VA2) + Spatial-Indizes (VA3/VA4).
- **VA2 — PG- *und MySQL*-Reverse SRID/Subtyp-Capture + Daten-Bind mit Ziel-SRID.
  ✅ ERLEDIGT + LIVE-VERIFIZIERT.** Zwei Teile:
  - **VA2a Reverse.** `PostgresTypeMapping` und `MysqlTypeMapping` lesen jetzt SRID +
    Geometrie-Subtyp. PG: `PostgresTableMetadataQueries.listGeometryColumns`
    (`geometry_columns`-View mit `to_regclass`-Guard, liefert ohne PostGIS leer) →
    `ColumnInput.geometrySubtype`/`geometrySrid`. MySQL:
    `information_schema.columns.srs_id` → `ColumnInput.srsId`. Beide setzen
    `NeutralType.Geometry(geometryType, srid)`; SRID 0 → `null` (= unconstrained,
    konsistent über beide Dialekte). Die Generate-Seite rendert die SRID bereits aus
    `Geometry.srid` (`PostgresTypeMapper.geometryToSql`, `MysqlColumnConstraintHelper`).
  - **VA2b Daten-Bind mit Ziel-SRID.** `TargetColumn.srid` neu; die DataWriter
    reichern Geometrie-Zielspalten aus dem Ziel-Katalog an (PG `geometry_columns`,
    MySQL `SRS_ID`). `AbstractTableImportSession.valuePlaceholder` bindet dann
    `ST_GeomFromWKB(?, srid)` statt `ST_GeomFromWKB(?)` — sonst würde ein SRID-0-WKB
    in eine SRID-beschränkte Spalte am PG-typmod bzw. MySQL-`ER_WRONG_SRID` scheitern.
  - **Live-Beleg** (`smoke-spatial.sh`, neuer Abschnitt SRID 4326): PG
    `geometry(Point,4326)` und MySQL `POINT SRID 4326 NOT NULL` round-trippen
    gleich-dialektisch; `ST_SRID(target)=4326` und Wert identisch. Damit ist
    False-Green (SRID=0/Subtyp=GEOMETRY) ausgeschlossen.
  - **VA2-X1 Cross-Dialect-Achsenreihenfolge. ✅ ERLEDIGT + LIVE-VERIFIZIERT.**
    Der Cross-Dialect-Smoke deckte einen **Datenkorruptions-Bug** auf: PostGIS
    schreibt/liest WKB in OGC-X/Y (long-lat), MySQL nutzt für geografische SRS
    (4326) **lat-long** — ein PG↔MySQL-Transfer vertauschte die Achsen (datenbelegt:
    München → Indischer Ozean), **bei gleicher `ST_AsText`-Ausgabe** (deshalb wäre ein
    naiver Textvergleich False-Green). Fix: MySQL nutzt durchgängig
    `axis-order=long-lat` — Read `ST_AsBinary(col, 'axis-order=long-lat')`
    (`MysqlDataReader`), Bind `ST_GeomFromWKB(?, srid, 'axis-order=long-lat')` via
    neuem `geometryBindOptions`-Hook (`AbstractTableImportSession` +
    `MysqlTableImportSession`). Damit ist WKB durchgängig OGC-long-lat = PostGIS-
    kompatibel; SRID 0 unschädlich (no-op). PostGIS braucht keine Änderung. Beleg:
    `smoke-spatial.sh`-Abschnitt `[xd]` mit asymmetrischen Koordinaten und
    **semantischem** Vergleich (`ST_Longitude/ST_Latitude` bzw. `ST_X/ST_Y`), grün
    in beiden Richtungen.
  - **Projizierte/kartesische SRS (EPSG:25832 ETRS89/UTM32N „Rechtswert/Hochwert",
    EPSG:3857 Web Mercator, EPSG:31466 DHDN/Gauß-Krüger Zone 2). ✅ LIVE-VERIFIZIERT,
    kein Code nötig.** Anders als bei geografischen SRS gibt es hier keine
    lat-long-vs-long-lat-Frage — beide Dialekte nutzen (E,N)=(X,Y), und
    `axis-order=long-lat` ist bei projizierten/kartesischen SRS ein no-op (empirisch:
    MySQL 8.4 wirft **keinen** Fehler, anders als die Doku nahelegt). **Kritischer
    Edge-Case GK 31466:** MySQLs SRS-Definition deklariert die Achsen historisch
    `AXIS["X",NORTH]`/`AXIS["Y",EAST]` (Hochwert/Rechtswert, gedreht). **Empirisch
    dreht MySQL aber NUR geografische SRS** — projizierte (auch GK mit gedrehter AXIS)
    bleiben X/Y=(erste,zweite), WKB byte-identisch zu PostGIS; bestätigt für WKB *und*
    WKT-Eingabe (`ST_GeomFromText`). Cross-Dialect PG↔MySQL transferiert
    Rechtswert/Hochwert beidseitig verlustfrei (`smoke-spatial.sh`
    `xd_projected_roundtrip`, semantisch via `ST_X/ST_Y`). **Grenze EPSG:4937**
    (ETRS89 3D-geographic): in MySQL 8.4 NICHT registriert → `POINT SRID 4937`-Spalte
    nicht anlegbar, Cross-Dialect-Transfer nach MySQL scheitert sauber (kein stiller
    Verlust); 2D-Alternative EPSG:4258. Damit deckt der Smoke geografische **und**
    projizierte SRS (inkl. gedrehter GK-Achsen) ab.
- **VA3 — Räumliche Indizes modellieren. ✅ ERLEDIGT + LIVE-VERIFIZIERT.**
  Neutrales Index-Modell + Emit statt `blockSpatialIndex`.
  - **Modell:** `IndexType.SPATIAL` (neutraler räumlicher Index für Dialekte ohne
    Methodenwahl) **+ `IndexType.SPGIST`** (PostGIS SP-GiST, vorher Reverse-Verlust
    → BTREE). Spec `schema.json`-Enum + YAML-Codec erweitert.
  - **MySQL:** Reverse `index_type=SPATIAL` → `SPATIAL`; Generate/Diff emittiert
    `CREATE SPATIAL INDEX` (Block entfernt in `MysqlDiffOtherOps`/`MysqlDiffTableOps`/
    `MysqlIndexPartitionDdlHelper`); jeder Geometrie-Index (egal welche neutrale AM)
    wird spaltenbasiert auf SPATIAL normalisiert. INFO-Note: SPATIAL braucht NOT NULL.
  - **PostGIS:** Reverse `gist→GIST`, `spgist→SPGIST`, `brin→BRIN`; Generate via
    `pgAccessMethod` (SPATIAL→`USING GIST`, SPGIST→`USING SPGIST`, …). `USING SPATIAL`
    existiert **nicht** in PostgreSQL (empirisch + Doku-belegt) → Mapping zwingend.
    Erlaubte räumliche Methoden = GiST/SP-GiST/BRIN/SPATIAL (`pgSupportsGeometryIndex`);
    B-Tree auf Geometrie bleibt geblockt (nur Equality/Sortierung, nicht räumlich).
  - **Live-Beleg** (`smoke-spatial.sh` `[idx]`): echte MySQL `SPATIAL INDEX` →
    `schema reverse` (`type: spatial`) → `schema generate` MySQL (`SPATIAL INDEX`) +
    PostGIS (`USING GIST`) → angewandtes MySQL-DDL erzeugt real einen Index mit
    `information_schema.statistics.index_type = SPATIAL`.
- **VA4 — SQLite/SpatiaLite. ✅ KOMPLETT ERLEDIGT + LIVE-VERIFIZIERT (2026-06-22);**
  inkl. vollem `migrate --execute`-Round-Trip (5d).
  - **Spatial-Index:** SQLite emittiert `CreateSpatialIndex`/`DisableSpatialIndex`
    statt zu blocken (Diff `renderAddIndex`/`renderDropIndex` + FULL-Generate
    `generateIndices`/`spatialIndexStatement`); der „index-on-geometry"-Tabellenblock
    (E052) entfällt. Nur unter `--spatial-profile spatialite` + verfügbarer Extension.
  - **mod_spatialite:** in der runtime-Dockerfile-Stage installiert
    (`libsqlite3-mod-spatialite`); **opt-in Extension-Loading** per Connection-Param
    `?spatialite=true` → `enable_load_extension` + `SELECT load_extension('mod_spatialite')`
    (`HikariConnectionPoolFactory.isSpatialiteRequested`); ohne Flag SQLite unverändert.
  - **migrate-Verkabelung:** `schema migrate --spatial-profile` neu (analog generate,
    durch die ganze Kette); die SpatiaLite-Extension-Verfügbarkeit wird aus der Ziel-
    `?spatialite=true`-Connection als `VERIFIED_PRESENT` abgeleitet (hebt das
    `requireExtension`-`EXTENSION_DEPENDENCY_UNKNOWN` auf).
  - **5d ERLEDIGT** (alle drei Restbefunde behoben, Closure
    [`done/spatialite-migrate-roundtrip.md`](../done/spatialite-migrate-roundtrip.md)):
    (1) `InitSpatialMetaData()`-Bootstrap als deterministisch-geguardetes Statement im
    Diff-Renderpfad vor dem ersten `AddGeometryColumn` (ADR 0016);
    (2) Diff-`CreateTable` routet den Geometrie-Index über `CreateSpatialIndex`;
    (3) Reverse liest `geometry_columns` (SRID + `spatial_index_enabled`), rekonstruiert
    `IndexType.SPATIAL` und filtert SpatiaLite-Metatabellen + R*Tree-Schatten.
  - **Live-Beleg** (`smoke-spatial.sh` `[lite]`): `schema generate` emittiert
    `AddGeometryColumn(..., 4326, 'POINT', 'XY')` + `CreateSpatialIndex`; **`migrate
    --execute`** legt gegen eine frische `?spatialite=true`-`.db` Geometrie + R*Tree-
    Spatial-Index real an, und **Reverse** rekonstruiert sie verlustfrei (SRID 4326 +
    `type: spatial`, Metatabellen gefiltert).
- **VA5 — Spatial-Sample-Portabilitäts-Spike + Katalog-Eintrag** (siehe unten).

## Scope-Skizze (Harness-Sub-Slices, je nach VA)

- **5a — PostGIS Round-Trip (PG).** *braucht VA1, VA2.* `postgis/postgis`-Service;
  reverse/validate/generate `--spatial-profile postgis` → transfer → Geometrie-
  Wert-Parität (`ST_Equals`/`ST_AsText`) + SRID-Erhalt + GIST-Index.
- **5b — MySQL native Round-Trip (MySQL).** *braucht VA1, VA2 (MySQL-SRID) (+ VA3
  falls Index-Kriterium).* `mysql:8.4` (Spatial eingebaut, **keine** Extension);
  `--spatial-profile native`; `GEOMETRY` + SRID datenbelegt.
- **5c — Cross-Dialect Spatial.** *braucht VA1, VA2.* mind. PostGIS → MySQL native
  (Wert + SRID round-trippen; Notes gepinnt). Optional Gegenrichtung.
- **5d — Spatialite (SQLite).** *braucht VA4 (+ VA1).* `.db` mit `mod_spatialite`;
  `--spatial-profile spatialite`; Round-Trip + ein Cross-Dialect-Transfer.

## Spatial-Sample (extern gepinnt, ADR 0014) — Portabilität ist das Risiko

**Entscheidung:** externes, auf Commit-SHA + SHA256 gepinntes Sample. **Review-Caveat:**
ein **einziges** Sample, das über **alle drei** Dialekte sauber lädt, ist
unwahrscheinlich — PostGIS-`nyc` ist PG/PostGIS-nativ (nutzt `geometry`,
`spatial_ref_sys`, PostGIS-Funktionen), Natural Earth braucht eine `ogr2ogr`-
Pipeline. **VA5 = Portabilitäts-Spike VOR der Pin-Entscheidung**; realistisches
Ergebnis ist eher **ein Sample pro Dialekt** oder ein klein-kuratiertes WKT-Sample.
Der Kandidaten-Katalog hat **heute keinen** Spatial-Eintrag → erstes Arbeitspaket:
Katalog ergänzen + Kandidat fixieren + pinnen.

## Vorbedingungen

- Phase 0–3-Harness-Muster (compose + Scripts + gepinnte Baseline) — **vorhanden**.
- DDL-Typ-Abbildung + Profil-Policy + `NeutralType.Geometry` — **vorhanden**.
- `postgis/postgis`-Image (5a) — Pull genügt (im compose bereits verdrahtet).
- **VA1 (Wert-Transfer), VA2 (SRID + Achsen-X1), VA3 (Index)** — **erledigt + live-verifiziert**;
  **VA4 (SQLite/SpatiaLite)** — **komplett erledigt + live-verifiziert** (inkl. vollem
  `migrate --execute`-Round-Trip 5d; Closure [`done/spatialite-migrate-roundtrip.md`](../done/spatialite-migrate-roundtrip.md)).
- Spatial-Sample-Pin (VA5) — **zu entscheiden**.

## Akzeptanzkriterien (realistisch, nach Review)

- **VA1:** Geometrie-Wert-Round-Trip (gleich-dialektisch) datenbelegt grün, SRID
  erhalten; Typ-Kompatibilitäts-Preflight erkennt Geometrie auf dem Datenpfad.
- **5a/5b:** Round-Trip je Dialekt grün; Geometrie-Werte round-trippen (`ST_*`-
  Stichprobe); Zeilen-Parität Quelle == Ziel == Baseline. SRID-Erhalt **nur**, wenn
  VA2 erbracht. Index-Kriterium **nur**, wenn VA3 (MySQL) erbracht — sonst als
  erwartete `SPATIAL_INDEX_UNSUPPORTED`-Note pinnen, nicht als Erfolg.
- **5c:** mind. ein Cross-Dialect-Spatial-Transfer datenbelegt (Wert + SRID); Notes gepinnt.
- **5d:** Spatialite-Round-Trip; `mod_spatialite` im Image; Extension-Loading nur
  bei `--spatial-profile spatialite`; Index-Kriterium nur, wenn VA4-Index erbracht.
- **Gating:** PR-Gate **falls** CI-Laufzeit < ~3 min/Profil, sonst opt-in (wie Phase 3).
- **Übergreifend:** kein Dump im Repo; `make docs-check` grün.

## Nicht-Ziel

- TPC/Performance (Phase 4, eigener Slice).
- `--spatial-profile none`-Degradation (durch Phase 1/2 abgedeckt).
- Geographie-Funktionsabdeckung über Typ-/Wert-/Index-Round-Trip hinaus.

## Closure (2026-06-22)

**Phase 5 vollständig + live-verifiziert.** Alle Vorarbeitspakete und Sub-Slices erbracht:

| Einheit | Beleg |
|---|---|
| VA1 (Geometrie-Wert-Transfer, WKB) | `3e57769f` + Smoke `[pg]`/`[my]` |
| VA2 (SRID-Reverse + Bind + Achsen-X1 + projizierte SRS) | `3f9877b0`/`b4e01196` + `[xd]` |
| VA3 (räumliche Indizes MySQL/PostGIS) | `268c7d2d` + `[idx]` |
| VA4 + 5d (SpatiaLite, voller `migrate --execute`) | `13b1b7bf` + ADR 0016 + `[lite]`; Closure [`spatialite-migrate-roundtrip.md`](spatialite-migrate-roundtrip.md) |
| VA5 (Sample-Pins: echtes nyc + kuratiertes WKT) | `fetch-dumps.sh` + Katalog `2.4` |
| **5a** (echtes PostGIS-nyc, EPSG:26918) | `f4189cf9` + `[pg-nyc]`: 129 MultiPolygons, SRID + Flächen-Checksumme + GIST |
| **5b** (MySQL native: reverse→generate + transfer) | `[my]`: GEOMETRY/POINT + SRID 4326 |
| **5c** (Cross-Dialect PG↔MySQL) | `[xd]`: Wert + SRID + axis-order, projizierte SRS |

**Closure-Kriterien (beobachtbar).**
1. `FETCH_NYC=1 make sample-db-spatial-smoke` grün (Exit 0) — alle Abschnitte
   `[pg]`/`[my]`/`[xd]`/`[idx]`/`[lite]`/`[pg-nyc]`; gepinnte Erwartungen in
   [`../../../examples/sample-db/expected/spatial.md`](../../../examples/sample-db/expected/spatial.md).
2. Modul-Checks grün: `driver-sqlite:check`, `driver-postgresql:check`; `make docs-check` grün.

**Gating-Entscheidung.** Wie Phase 3 (Scale): **opt-in/nightly, kein PR-Gate** — der
Spatial-Smoke braucht den Compose-Stack (postgis+mysql) + das gdal-Loader-Image; das
echte nyc-Sample ist zusätzlich `FETCH_NYC=1`-gated (~22 MB Fetch). Damit bleibt das
PR-Gate schlank; der Slice ist ergänzende QA-Infrastruktur (kein RC-Kriterium).

**Lerneintrag.**
- *Neue Sensoren:* `[pg-nyc]`/`[lite]`-Apply + 5b-reverse→generate in `smoke-spatial.sh`;
  gepinntes `expected/spatial.md`.
- *Geschärfte Regeln:* ADR 0016 (SpatiaLite-Bootstrap im Diff-Renderpfad); PG-Reverse
  schließt Extension-eigene Objekte aus (`pg_depend`, PG-Pendant zu SQLite-Befund 3a) —
  der **reale** nyc-Datenpfad deckte diesen Bug auf, den kuratierte/manuell gebaute
  Tabellen nie trafen. Lehre: ein echtes, gepinntes Sample findet Reverse-Bugs, die
  Inline-Fixtures verstecken.
- *Benannte Folgearbeit (nicht-spatial, getrackt):*
  [`migrate-postcompare-identifier-pk-drift.md`](migrate-postcompare-identifier-pk-drift.md)
  (SQLite `migrate --execute` Post-Compare-Drift Exit 5).
