-- Das Bein „PostGIS ausserhalb des search_path" der Compare-Matrix
-- (scripts/smoke-compare-matrix.sh, Schluessel
-- REPORT_CODES_POSTGRESQL_NOSEARCHPATH).
--
-- Es steht **neben** der Matrix: keine Zelle, kein Ziel, nur ein Reverse je
-- Lauf gegen eine zweite Datenbank desselben PostgreSQL-Dienstes. Deshalb
-- liegt es nicht unter fixtures/seeds/ — dort liest der Silent-Loss-Check je
-- Dialekt genau eine Datei, und ein zweiter PostgreSQL-Seed haette dort keinen
-- Platz.
--
-- Die Lage, die es herstellt: die Extension liegt im Schema `postgis`, und der
-- `search_path` nennt es **nicht**. Dann loest `geometry_columns` nicht auf,
-- und jede Geometriespalte kommt ohne Subtyp und ohne SRID zurueck — der
-- Reverse meldet das mit `R405`.
--
-- Der Spaltentyp ist deshalb **qualifiziert** geschrieben
-- (`postgis.geometry(...)`): ohne den Schemapraefix faende die Anweisung den
-- Typ selbst nicht.

-- Das Image installiert PostGIS per Init-Skript in die Datenbank aus
-- POSTGRES_DB. Diese hier ist eine **zweite**, frisch angelegte Datenbank; ein
-- `CREATE EXTENSION IF NOT EXISTS … SCHEMA postgis` waere aber ein stilles
-- No-op, falls sie die Extension doch schon traegt — und `postgis.geometry`
-- gaebe es dann nicht. Deshalb erst weg, dann an die richtige Stelle.
DROP EXTENSION IF EXISTS postgis CASCADE;
CREATE SCHEMA IF NOT EXISTS postgis;
CREATE EXTENSION postgis SCHEMA postgis;

CREATE TABLE sl_pg_nosp_places (
  id integer PRIMARY KEY,
  location postgis.geometry(Point, 4326) NOT NULL
);

-- Gegenprobe im selben Bein: eine Tabelle ohne Geometriespalte verliert
-- nichts und darf deshalb keine Warnung tragen.
CREATE TABLE sl_pg_nosp_plain (
  id integer PRIMARY KEY,
  label text NOT NULL
);
