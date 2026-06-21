# Plan: Sample-DB-Harness Phase 5 — Spatial (PostGIS + MySQL native + Spatialite)

> Dokumenttyp: In-Progress-Plan (Folge-Slice von [`sample-db-integration-harness.md`](sample-db-integration-harness.md))
> Status: **In Arbeit** (seit 2026-06-21; nach `in-progress/` verschoben, ADR 0004,
> mit dem ersten Implementierungs-Commit). **VA1 (a–d) implementiert + code-review-
> gehärtet** (`25efa6b5`): `cfb7ab78` Erkennung, `0c6ee1d7`+`961d919e` Read-Projektion
> (plain WKB), `961d919e` Geometrie-Bind (`ST_GeomFromWKB`), `ca0afc26` Preflight.
> **Zwei Review-Bugs behoben:** dialekt-bewusste Erkennung (native PG-`point`/`polygon`
> nicht mehr fälschlich gewrappt) + ChunkSchema trägt echt `Geometry` (über
> `probedColumns`). Kanonisches Format = **WKB** (verlustfrei, cross-dialect).
> **Noch NICHT live-verifiziert** (nur unit/SQLite) — der VA1-Live-Smoke (vorgezogenes
> 5a/5b) ist das nächste Gate. Offen: Live-Smoke, VA2–VA5, Sub-Slices 5a–5d.
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
| **Geometrie-Erkennung im Read-Pfad** | ❌ **fehlt** (→ Text) | `JdbcToNeutralTypeMapper.mapOther` fällt für Geometrie auf `NeutralType.Text` (AP3-TODO) → Chunk-/Parquet-Header trägt Text statt Geometry |
| **Geometrie-WERT-Bindung** (`data transfer`/`export`/`import`) | ❌ **fehlt** | `--spatial-profile` **nicht** auf `DataTransferCommand`/`DataImportCommand`; kein WKB/WKT-Konverter (vgl. `JdbcForeignValueNormalizer`); kein dialekt-spezifisches Geometrie-Binding in den `*TableImportSession` |
| Import-Preflight für Geometrie | ⚠️ **permissiv** | `ImportTypeCompatibility` → `Geometry -> true` (bedingungslos; winkt auch Geometrie→Nicht-Geometrie durch) |
| **MySQL SPATIAL-Index** | ❌ **aktiv geblockt** | `MysqlDiffOtherOps.blockSpatialIndex` → `SPATIAL_INDEX_UNSUPPORTED` |
| **SQLite SpatiaLite Spatial-Index** (`CreateSpatialIndex`) | ❌ **aktiv geblockt** | `SqliteDiffSimpleOps.blockSpatialIndex` |
| **PG-Reverse SRID/Subtyp-Capture** | ❌ **fehlt** | `PostgresTypeMapping.kt:132` liefert bare `NeutralType.Geometry()` |
| **MySQL-Reverse SRID-Capture** | ❌ **fehlt** | `MysqlTypeMapping` baut `Geometry(geometryType=…)` **ohne** `srid` |
| `mod_spatialite` im CLI-Image + Extension-Loading | ❌ **fehlt** | Dockerfile runtime-Stage (`eclipse-temurin`); kein `enableLoadExtension`/`load_extension` |

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
  **VA1 (a–d) implementiert + code-review-gehärtet (`25efa6b5`):** der Geometrie-Wert
  läuft als plain WKB von der Quelle (VA1a dialekt-bewusste Erkennung + VA1b
  Read-Projektion `ST_AsBinary`) bis in die Ziel-Geometriespalte (VA1c Bind
  `ST_GeomFromWKB` + explizites `setBytes`), Preflight nur Geometry→Geometry (VA1d).
  Zwei Review-Bugs behoben (native-PG-Typen, ChunkSchema-Header). **Aber nur unit-/
  SQLite-getestet — die Live-DB-Verifikation (echtes PostGIS/MySQL) ist das nächste
  Gate** (vorgezogener VA1-Live-Smoke, dann Sub-Slices 5a–5c); erst danach gilt VA1
  als bestätigt.
- **VA2 — PG- *und MySQL*-Reverse SRID/Subtyp-Capture.** `PostgresTypeMapping`
  (liefert bare `Geometry()`) **und** `MysqlTypeMapping` (baut `Geometry` ohne
  `srid`) müssen SRID + Geometrie-Subtyp lesen (PG: `geometry_columns`/`Find_SRID`;
  MySQL: `information_schema`-SRS_ID). Sonst SRID=0/Subtyp=GEOMETRY →
  **False-Green-Risiko** (vgl. F1-Muster). Voraussetzung für ehrliche
  SRID-Erhalt-Assertions auf **beiden** Quellrichtungen.
- **VA3 — MySQL SPATIAL-Index modellieren** (neutrales Index-Modell + Emit statt
  `blockSpatialIndex`). Nur falls „SPATIAL-Index belegt" als Kriterium bleibt.
- **VA4 — SQLite SpatiaLite Spatial-Index** (`CreateSpatialIndex`/`RecoverGeometry-
  Column`) **+** `mod_spatialite` in der runtime-Dockerfile-Stage **+** Extension-
  Loading im sqlite-Treiber (`enableLoadExtension(true)` + `load_extension`), nur
  aktiv bei `--spatial-profile spatialite`. Dabei den **bestehenden** deklarativen
  `requireExtension`/`ExtensionAvailabilityStatus`-Gate (`SqliteDiffRenderContext`)
  nutzen statt einen parallelen Mechanismus zu bauen.
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
- `postgis/postgis`-Image (5a) — Pull genügt.
- **Code-Lücken (VA1–VA4)** — **zu bauen** (siehe oben).
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
