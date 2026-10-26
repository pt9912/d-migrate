# SQLite: `WITHOUT ROWID` überlebt Neubau und `schema generate`

> **Status:** In Umsetzung (2026-10-26). Befund aus dem Review zu #60 (P0-Fall 7): ein Tabellenneubau aus einer
> Datei verliert `WITHOUT ROWID`. **Vorbedingung:** keine. Kein GitHub-Issue (Entscheidung des Nutzers).

## Ziel

Eine `WITHOUT ROWID`-Tabelle bleibt es nach `schema migrate --execute` mit Tabellenneubau, und `schema generate`
schreibt das Merkmal, das das Modell als `metadata.without_rowid` kennt.

## Problem

Das Modell (`TableMetadata.withoutRowid`), der Reader (`SqliteTypeMapping.hasWithoutRowid`), Datei-Parser und
-Writer (`without_rowid`) und der Vergleich kennen das Merkmal. **Kein Renderer schreibt es:**
`SqliteTableDdlSupport.generateTable` und `SqliteRebuildRenderer.buildCreateTempSql` enden ohne `WITHOUT ROWID`.
Der Befund war damit größer als gemeldet: auch eine Datei **mit** `without_rowid: true` erzeugt eine Rowid-Tabelle.

## Phasen

* **P0 Messung** (CLI-Test, Datenbank `CREATE TABLE t (...) WITHOUT ROWID`, Neubau durch `AlterColumnNullability`):

  | Fall | Ergebnis vor der Änderung |
  | --- | --- |
  | A: Datei ohne Merkmal, `--execute` | Exit 0, 1 Operation, neue Tabelle **ohne** `WITHOUT ROWID`; zweiter Lauf 0 Operationen |
  | C: Datei mit `without_rowid: true`, `--execute` | wie A: Merkmal verloren |
  | D: Datei mit Merkmal, Datenbank ohne, `--plan-only` | 0 Operationen: der Planer ignoriert die Metadaten-Differenz |
  | B: `schema generate` mit `without_rowid: true` | `CREATE TABLE` ohne `WITHOUT ROWID` |

  Der Vergleich (`TableComparator.metadataChangeOrNull`) meldet `withoutRowid` als Metadaten-Änderung, aber
  kein Planer macht daraus eine Operation; der Fingerabdruck liest das Modell nach dem Reader (kein Renderer-Bezug).
* **P1 Entscheidung.** Verworfen: das Merkmal im Reader/Vergleich zur planbaren Änderung machen (SQLite kann es
  nicht per `ALTER` ändern, und ein Wechsel wäre ein Neubau mit Strukturänderung; eigener Slice). Gewählt:
  **die Renderer schreiben das Merkmal**, und der Neubau erhält es, wenn **eine** der beiden Seiten es trägt
  (`SqliteRebuildPlan.withoutRowid`). Grund: eine Bestandsdatei kann „fehlt" und „false" nicht unterscheiden; die
  Datenbank-Seite (Up: `oldTable`, Down: `newTable`) kommt vom Reader. Folge: Datei `true` gegen Datenbank ohne
  bei einem Neubau aus anderem Anlass legt die Tabelle mit `WITHOUT ROWID` an (die Datei verlangt es), ein
  anschließendes DOWN behält es. **Kein Abdruck-Bump, kein ADR** (Planer/Renderer, nicht das Modell nach dem Reader).
  *Nachtrag:* die Folge „ein anschließendes DOWN behält es" ist für Fall A (Datenbank Rowid, Datei `true`) in #67 korrigiert, siehe [`sqlite-without-rowid-umwandlung-produktfrage.md`](../done/sqlite-without-rowid-umwandlung-produktfrage.md).
* **P2 Umsetzung:** `SqliteTableDdlSupport` und `SqliteRebuildRenderer` schreiben `) WITHOUT ROWID;`; der
  FTS5-Check im Neubau nutzt dasselbe Merkmal. Tests: `CliSchemaMigrateWithoutRowidSqliteTest` (Bestandsdatei,
  Datei mit Merkmal, Rowid-Tabelle bleibt, Rundlauf `generate` → Datenbank → 0 Operationen, DOWN-Skript).
  Zusatz aus dem Review: `SqliteDiffRenderContext.tableWithoutRowid` (FTS5-Pruefung beim `AddIndex`) und
  `SqliteRebuildPlan.withoutRowid` folgen derselben Regel (Datei **oder** Datenbank); ohne das erzeugte ein
  neuer FULLTEXT-Index auf einer `WITHOUT ROWID`-Tabelle einer Bestandsdatei einen FTS5-Aufbau mit `rowid`-Triggern.
  Das Zuruecklesen belegt `schema reverse` (`without_rowid: true`), nicht ein Lauf mit 0 Operationen: der Planer
  macht aus der Metadaten-Differenz keine Operation.
  **Rot ohne die Aenderung:** mit den Haupt-Dateien (`SqliteTableDdlSupport`, `SqliteRebuildRenderer`,
  `SqliteDiffRenderContext`) auf dem Stand von `main` schlagen 7 neue Tests fehl (5 CLI: Bestandsdatei, Datei mit
  Merkmal, FULLTEXT, Rollback, `generate`/`reverse`; je 1 Generator- und Renderer-Test); die Tests fuer Plan-Regel und
  Rowid-Tabelle sind absichtlich gruen.
  Matrix: die Cross-Dialect-Matrix kennt das Paar nicht (SQLite-spezifisch); der Rundlauf-Test deckt es.
* **P3 Doku:** Spec `ddl-generation-rules.md` §3.5, Handbuch §3.17 (Grenze gestrichen), `CHANGELOG.md`.

## Nicht-Scope

Das Merkmal per `schema migrate` hinzufügen oder entfernen. `WITHOUT ROWID` mit `AUTOINCREMENT` oder ohne
Primärschlüssel: SQLite lehnt das selbst ab, der Lauf scheitert laut.
`WITHOUT ROWID` mit SpatiaLite-Geometriespalte oder `CreateSpatialIndex` (der R-Tree haengt an der Rowid): nicht
gemessen (kein `mod_spatialite` im Testlauf); SpatiaLite-Pfade werden von dieser Aenderung nicht beruehrt, ausser dass
`CREATE TABLE` das Merkmal schreibt.
