# Plan: Sample-DB-Harness Phase 5 — Spatial (PostGIS + MySQL native + Spatialite)

> Dokumenttyp: Next-Plan (Folge-Slice von [`../in-progress/sample-db-integration-harness.md`](../in-progress/sample-db-integration-harness.md))
> Status: Entwurf (2026-06-21). Scope ausgearbeitet, **Bau folgt**; zwei
> Entscheidungen vorab zu treffen (Spatial-Sample-Pin, Spatialite-Extension-Mechanik).
> Trigger: Slice-Grenze Phase 0–3 ist DoD-komplett; Phase 5 ist ein ausgegliederter
> Folge-Slice. Review-Befund 2026-06-21: die ursprüngliche Phase-5-Skizze
> („PostGIS + Spatialite") hatte das **MySQL-`native`-Profil vergessen**.
> Referenzen: ADR 0014 (Harness-Mechanik), ADR 0004 (Planning-Struktur),
> ADR 0015 (NeutralType-Präzedenz, kein Native-Passthrough),
> [`../open/test-database-candidates.md`](../open/test-database-candidates.md)
> (Kandidaten-Katalog). Nicht-blockierend für 1.0.0 (Spatial ist Zusatz-Abdeckung).

## Ziel

Den im Code bereits implementierten Spatial-Support **end-to-end gegen das echte
CLI** absichern — analog zu Phase 1–3, aber für **alle drei** Dialekt-Spatial-
Profile **plus** Cross-Dialect-Spatial. d-migrate kennt
`--spatial-profile postgis|native|spatialite|none` (auf `schema generate` und
`data export`) und modelliert Geometrie als first-class `NeutralType.Geometry`
(parameterloser Native-Passthrough-Verzicht, ADR 0015-Muster).

## Spatial-Profil-Matrix (eines je Dialekt)

| Dialekt | Profil | Server/Engine | Code-Status | Image-Arbeit? |
|---|---|---|---|---|
| PostgreSQL | `postgis` | `postgis/postgis`-Image | implementiert (`PostgresTypeMapping`, `PostgresIndexOpClass` GIST) | nein (DB-Image) |
| MySQL | `native` | `mysql:8.4` (Spatial **eingebaut**) | implementiert (`MysqlTypeMapping`, `MysqlColumnConstraintHelper` GEOMETRY+SRID) | nein |
| SQLite | `spatialite` | `mod_spatialite` (Loadable-Extension) | DDL implementiert (`SqliteDiffSimpleOps` SpatiaLite-Guards) | **ja** (CLI-Image + Extension-Loading) |

Belege: `SpatialProfile.defaultFor` (PG→POSTGIS, MySQL→NATIVE, SQLite→NONE) +
`allowedFor` (PG→{POSTGIS,NONE}, MySQL→{NATIVE,NONE}, SQLite→{SPATIALITE,NONE}).

## Scope-Skizze (Sub-Slices)

- **5a — PostGIS Round-Trip (PG).** compose-Service `postgis/postgis` (PG-Superset);
  Spatial-Sample laden → `reverse` → `validate` → `generate --target postgresql
  --spatial-profile postgis` → Zielschema → `data transfer` → Geometrie-Parität
  (Zeilen + Stichprobe via `ST_AsText`/`ST_Equals`) + räumlicher GIST-Index belegt.
- **5b — MySQL native Round-Trip (MySQL).** Bestehender `mysql:8.4`-Service (Spatial
  eingebaut, **keine** Extension); `generate --target mysql --spatial-profile native`;
  `GEOMETRY/POINT/POLYGON` + `SRID` (MySQL 8.0+) datenbelegt; SPATIAL-Index belegt.
- **5c — Cross-Dialect Spatial.** Mindestens **PostGIS → MySQL native** (Geometrie-
  Werte round-trippen über Dialektgrenze; SRID-Erhalt; erwartete Notes gepinnt).
  Optional MySQL native → PostGIS als Gegenprobe.
- **5d — Spatialite (SQLite). VORARBEIT-abhängig.** Erst das Vorarbeitspaket (siehe
  unten), dann: `.db` mit `mod_spatialite` → `generate --spatial-profile spatialite`
  → Round-Trip + Cross-Dialect (PostGIS/MySQL native → Spatialite). SpatiaLite-
  Geometrie-Metadaten (`geometry_columns`) + R-Tree-Index belegt.

### Vorarbeitspaket (Blocker für 5d): mod_spatialite

Das CLI-Runtime-Image (`eclipse-temurin:21-jre-noble`) enthält **kein**
`mod_spatialite`, und der sqlite-Treiber lädt **keine** Extension. Vor 5d:

1. **Image:** `libsqlite3-mod-spatialite` (o. ä.) in die `runtime`-Dockerfile-Stage.
2. **Treiber:** Extension-Loading im sqlite-Adapter (`enableLoadExtension(true)` +
   `SELECT load_extension('mod_spatialite')`) — nur aktiv, wenn
   `--spatial-profile spatialite`. **Offen:** ob das ein eigenständiges CLI-/Treiber-
   Feature ist oder im Harness-Connect-Pfad sitzt → eigener Sub-Entwurf.

## Spatial-Sample (extern gepinnt, ADR 0014)

**Entscheidung getroffen:** externes, auf Commit-SHA + SHA256 gepinntes Sample
(kein Dump im Repo), wie Pagila/Sakila/Chinook. Für Spatial gibt es kein
kanonisches Pagila — Kandidaten (im Kandidaten-Katalog zu ergänzen + zu pinnen):

- **PostGIS-Workshop „nyc"** (`postgis.net`/GitHub-Mirror): klein, klassisch,
  als SQL-Dump; PG/PostGIS-nativ — Cross-Dialect-Portabilität prüfen.
- **Natural Earth** (Teilmenge, GeoPackage/Shapefile): dialekt-neutraler, aber
  Lade-Pipeline (ogr2ogr) sperriger.
- **Klein-kuratiert mit WKT** (falls kein externes Sample alle drei Dialekte sauber
  bedient): als *letzte* Option — widerspricht aber der getroffenen Externe-Pin-
  Entscheidung, daher nur wenn extern nicht tragfähig.

→ **Erstes Arbeitspaket: Kandidat fixieren + pinnen + im Katalog dokumentieren.**

## Vorbedingungen

- Phase 0–3-Harness-Muster (compose + Scripts + gepinnte Baseline) — **vorhanden**.
- CLI `--spatial-profile`, `NeutralType.Geometry`, PG/MySQL-Spatial-DDL — **vorhanden**.
- `postgis/postgis`-Image (5a) — Pull genügt.
- **Offen:** Spatial-Sample-Pin (5a–5d); `mod_spatialite`-Image + Extension-Loading (5d).

## Akzeptanzkriterien (je Profil)

- **5a/5b:** Round-Trip je Dialekt grün; Geometrie-Werte round-trippen (Stichprobe
  über `ST_AsText`/`ST_Equals` bzw. MySQL-`ST_*`); SRID erhalten; räumlicher Index
  vorhanden; Zeilen-Parität Quelle == Ziel == gepinnte Baseline.
- **5c:** mindestens ein Cross-Dialect-Spatial-Transfer datenbelegt (Werte + SRID);
  erwartete Degradations-/Dialekt-Notes gegen Baseline gepinnt.
- **5d:** Spatialite-Round-Trip + ein Cross-Dialect-Transfer; `mod_spatialite` im
  Image; Extension-Loading nur bei `--spatial-profile spatialite`.
- **Gating:** wie Phase 1/2 PR-Gate, **falls** CI-Laufzeit < ~3 min/Profil; sonst
  opt-in wie Phase 3. Pro Profil ein `make sample-db-spatial-*`-Target + Workflow.
- **Übergreifend:** kein Dump im Repo (Cache gitignored + dockerignored);
  `make docs-check` grün.

## Nicht-Ziel

- TPC/Performance (Phase 4, eigener Slice).
- `--spatial-profile none`-Degradation (bereits durch Phase 1/2 abgedeckt, wo
  Geometrie zu Text/Blob fällt — kein eigenes Spatial-Sample nötig).
- Geographie-spezifische Funktionsabdeckung über Typ-/Index-Round-Trip hinaus.
