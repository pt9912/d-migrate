# Plan: Sample-DB-Harness Phase 5 — Spatial (PostGIS + MySQL native + Spatialite)

> Dokumenttyp: Next-Plan (Folge-Slice von [`../in-progress/sample-db-integration-harness.md`](../in-progress/sample-db-integration-harness.md))
> Status: Entwurf, **überarbeitet nach Plan-Review (2026-06-21)**. Scope ausgearbeitet,
> **Bau folgt**. **Wichtigste Review-Korrektur:** Phase 5 ist **kein reiner
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
  - **VA1a Erkennung:** `JdbcToNeutralTypeMapper.mapOther` muss Geometrie
    dialektspezifisch (via `sqlTypeName`) als `NeutralType.Geometry` erkennen statt
    auf `Text` zu fallen (sonst trägt der Chunk-/Parquet-Header Text → False-Green).
  - **VA1b Read-Projektion:** typ-bewusstes **per-Spalten-Wrapping** im
    treiberspezifischen `buildSelectQuery`-Override (Geometriespalten via Metadaten
    erkennen, dann `ST_AsEWKB(col) AS col`) — die Projektion kennt heute keine
    Neutral-Typen, daher ist eine Metadaten-Vorabfrage nötig (kein simpler Konverter).
  - **VA1c Bind:** **dialekt-spezifisches** Geometrie-Binding in den
    `*TableImportSession` (PostGIS-EWKB ≠ MySQL-WKB ≠ SpatiaLite-BLOB; z. B.
    `ST_GeomFromWKB`/`ST_GeomFromText`/`GeomFromWKB(?,srid)`) **mit SRID-Erhalt**,
    analog dem K1/L1-Muster (`JdbcForeignValueNormalizer`).
  - **VA1d Preflight:** `ImportTypeCompatibility.isTypeCompatible(Geometry)` mappt
    heute unbedingt auf `true` → auf echte Ziel-Geometrie-Kompatibilität härten.
  Ohne VA1 kein einziger Spatial-Round-Trip (auch nicht gleich-dialektisch).
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
  aktiv bei `--spatial-profile spatialite`.
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
