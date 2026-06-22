# Spatial-Harness — gepinnte Erwartungen (Phase 5: 5a–5d)

> Gepinntes Erwartungs-/Verhaltensdokument für `scripts/smoke-spatial.sh`.
> Anders als die Baseline-Compare-Phasen (Pagila/Sakila) prüft der Spatial-Smoke
> **semantisch** (ST_*-Funktionen), nicht per Diff gegen einen DDL-Dump — Geometrie-
> Werte/SRID/Achsen sind nur so datenbelegt verifizierbar. Dieses Dokument hält die
> erwarteten Aussagen + die nicht offensichtlichen Verhaltens-Notizen fest.

## Abgedeckte Profile/Sub-Slices

| Abschnitt | Sub-Slice | Sample | Kernaussage |
|---|---|---|---|
| `[pg]` | VA1/VA2 | inline WKT | Geometrie-Wert + SRID 4326 Round-Trip PG→PG; native PG `point` unberührt (R1) |
| `[my]` | **5b** | inline WKT | MySQL-native: Wert + SRID 4326 Round-Trip; reverse→validate→generate `--spatial-profile native` erzeugt `GEOMETRY`/`POINT` + SRID 4326 |
| `[xd]` | **5c** | inline WKT | Cross-Dialect PG↔MySQL: Wert + SRID; **axis-order** geografisch korrekt; projizierte SRS (25832/3857/31466) verlustfrei |
| `[idx]` | VA3 | inline | MySQL `SPATIAL INDEX` reverse→generate (+ cross-dialect PostGIS `USING GIST`)→apply |
| `[lite]` | **5d** | inline | SpatiaLite `migrate --execute` legt Geometrie + R*Tree-Index an; Reverse rekonstruiert verlustfrei |
| `[pg-nyc]` | **5a** | **echtes nyc** (gepinnt, opt-in `FETCH_NYC=1`) | 129 MultiPolygons (EPSG:26918) reverse→validate→generate `postgis`→transfer; Flächen-Checksumme + SRID erhalten, GIST-Index belegt |

## Nicht offensichtliche Verhaltens-Notizen (gepinnt)

- **Achsenreihenfolge (4326):** PostGIS schreibt/liest WKB OGC-X/Y (long-lat),
  MySQL nutzt für geografische SRS lat-long. Cross-Dialect erzwingt deshalb MySQL
  durchgängig `axis-order=long-lat` (Read + Bind). Ein naiver `ST_AsText`-Vergleich
  wäre False-Green → der Smoke vergleicht semantisch (`ST_Longitude`/`ST_X`).
- **Projizierte SRS (25832/3857/31466):** kein Achsenproblem (E,N = X,Y);
  `axis-order=long-lat` ist no-op. Gauß-Krüger 31466 hat in MySQL eine historisch
  **gedrehte** AXIS-Deklaration, MySQL dreht aber empirisch nur geografische SRS —
  projizierte bleiben (erste, zweite), WKB byte-identisch zu PostGIS.
- **Grenze EPSG:4937** (ETRS89 3D-geographic): in MySQL 8.4 nicht registriert →
  `POINT SRID 4937`-Spalte nicht anlegbar; Cross-Dialect nach MySQL scheitert sauber
  (kein stiller Verlust). 2D-Alternative: EPSG:4258.
- **SpatiaLite (`[lite]`):** `migrate --execute` endet mit **Exit 5** (Post-Execute-
  Compare-Drift), weil der Fingerprint `identifier`→`primary_key` asymmetrisch
  normalisiert (nur bei Schemas OHNE explizites `primary_key`; mit `primary_key: [id]`
  Exit 0) — **pre-existing, nicht-spatial** (auch ohne Geometrie reproduzierbar,
  `docs/planning/open/sqlite-migrate-postcompare-identifier-drift.md`). Die Migration
  selbst ist `status: ok`; der Smoke prüft daher den Report, nicht den Prozess-Exit.
- **PostGIS-nyc (`[pg-nyc]`):** `postgis/postgis` enthält kein `shp2pgsql`/`ogr2ogr`;
  der gepinnte `gdal`-Service lädt die Shapefile. Der PG-Reverse schließt
  Extension-eigene Objekte (`spatial_ref_sys`, `geometry_dump`, `valid_detail`) aus
  (`pg_depend.deptype='e'`), sonst kollidierte das generierte DDL im Ziel.
