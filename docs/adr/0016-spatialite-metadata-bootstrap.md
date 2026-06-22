---
status: accepted
date: 2026-06-22
decision-makers: pt9912
consulted: docs/planning/in-progress/spatial-harness-slice.md (VA4/5d), spec/neutral-model-spec.md (Geometrie-/Spatial-Profil-Modell), docs/adr/0014-sample-db-harness-fetch-and-compose.md (Harness, der den Befund aufdeckte)
informed: adapters/driven/driver-sqlite, examples/sample-db/scripts/smoke-spatial.sh
---

# SpatiaLite-Metadaten-Bootstrap im Migrate-Diff-Pfad (`InitSpatialMetaData`)

## Kontext und Problemstellung

Der vorgezogene Live-Apply-Round-Trip der Spatial-Slice (VA4/5d,
[`spatial-harness-slice.md`](../planning/in-progress/spatial-harness-slice.md))
deckte auf: `schema migrate --execute --spatial-profile spatialite` gegen eine
**frische** `.db` scheitert. Eine neue SQLite-Datei hat keine SpatiaLite-
Metatabellen (`geometry_columns`, `spatial_ref_sys`); das vom Renderer emittierte
`SELECT AddGeometryColumn(...)` bricht dort mit „unexpected metadata layout" ab.
SpatiaLite verlangt einmal pro Datenbank `SELECT InitSpatialMetaData()` vor dem
ersten `AddGeometryColumn`.

Die offene Frage war **wo** dieser Bootstrap entsteht. Drei Kandidaten:

1. **Generischer Execution-/Runner-Pfad** (`SchemaMigrateExecutionStage` /
   `SegmentAwareMigrationExecutor`) — vor dem Ausführen der Up-Statements ein
   Bootstrap-Statement voranstellen.
2. **`schema generate` (Full-Generate-Pfad, `SqliteTableDdlSupport`)** — das
   Bootstrap in das generierte Standalone-DDL-Skript aufnehmen.
3. **Migrate-Diff-Renderpfad (`SqliteDiffDdlGenerator`/`SqliteDiffSimpleOps`)** —
   das Bootstrap als eigenes Statement emittieren, vor dem ersten
   `AddGeometryColumn`.

Die Vorabklärung empfahl zunächst den Execution-Pfad (Determinismus-Sorge: ein
zustandsabhängiges Statement gehört nicht ins deterministische DDL). Bei der
Umsetzung zeigte sich diese Sorge als auflösbar (siehe Entscheidung), und der
Execution-Pfad als architektonisch teurer.

## Entscheidung

Der SpatiaLite-Metadaten-Bootstrap wird im **Migrate-Diff-Renderpfad**
(`SqliteDiffSimpleOps`) als **deterministisches, idempotentes** Statement
emittiert — genau einmal pro UP-Render, vor dem ersten `AddGeometryColumn`,
nach demselben Muster wie der bestehende `dmg_sequences`-Bootstrap
(`ensureBootstrapEmitted`/Flag auf dem Renderkontext):

```sql
SELECT CASE WHEN CheckSpatialMetaData() = 0 THEN InitSpatialMetaData() END;
```

- **Idempotent:** `CheckSpatialMetaData()` gibt 0 zurück, wenn keine Metadaten
  vorliegen; nur dann läuft `InitSpatialMetaData()`. Ein erneuter Lauf gegen eine
  bereits initialisierte `.db` ist ein no-op (kein „table already exists").
- **Deterministisch:** Der SQL-**Text** ist immer identisch; nur seine Laufzeit-
  *Wirkung* ist zustandsabhängig. Damit bleibt der Diff-Output reproduzierbar und
  Dry-Run-Artefakte (`--execute` aus) stabil — die ursprüngliche Determinismus-
  Sorge entfällt.
- **Transaktionssicher:** `InitSpatialMetaData()` ohne Transaktions-Argument öffnet
  **kein** verschachteltes `BEGIN`; es läuft innerhalb der vom Runner gehaltenen
  Transaktion (`RUNNER_OWNED`, `FULLY_TRANSACTIONAL`). Die Variante
  `InitSpatialMetaData(1)` (eigene Transaktion) ist hier bewusst **nicht** gewählt,
  da SQLite keine geschachtelten Transaktionen kennt.
- **Gating:** Nur erreichbar, nachdem `guardSpatiaLite` (Profil `SPATIALITE` +
  verfügbare Extension) für die Geometriespalte bestanden hat — kein Bootstrap ohne
  Spatial-Profil.

## Verworfene/aufgeschobene Alternativen

- **Generischer Execution-Pfad (Kandidat 1): verworfen.** Er würde SpatiaLite-
  spezifisches SQL in die dialekt-agnostische `SchemaMigrateExecutionStage`
  einschleusen — ein Dialekt-Leck, das die Hexagon-Schichtung verletzt
  (dialektspezifisches Verhalten gehört in den SQLite-Treiber, nicht in die
  generische Pipeline).
- **Full-Generate-Pfad `schema generate` (Kandidat 2): aufgeschoben (bewusste
  Scope-Grenze).** 5d sichert den `migrate --execute`-Round-Trip ab; das
  `schema generate`-DDL bleibt unverändert (DDL-Goldens stabil). Konsequenz: ein
  per `schema generate --spatial-profile spatialite` erzeugtes **Standalone**-Skript
  ist gegen eine frische `.db` ohne vorangestelltes `InitSpatialMetaData()` nicht
  lauffähig — eine dokumentierte, bekannte Grenze (wie das Skript ohnehin
  `mod_spatialite` zur Anwendung voraussetzt). Eine spätere Slice kann das Bootstrap
  auch dort ergänzen (mit Golden-Neuabnahme).

## Konsequenzen

- `schema migrate --execute --spatial-profile spatialite` ist gegen eine frische
  `?spatialite=true`-`.db` selbsttragend (Geometriespalte + Spatial-Index entstehen
  real).
- Kein Dialekt-Leck in der generischen Execution-Stage; die Spatial-Logik bleibt im
  SQLite-Treiber gekapselt.
- Der `schema generate`-Standalone-DDL-Output trägt das Bootstrap (noch) nicht —
  dokumentierte Grenze, kein RC-Blocker.
- Live-Beleg: `examples/sample-db/scripts/smoke-spatial.sh` Abschnitt `[lite]`
  (Apply-Round-Trip).
