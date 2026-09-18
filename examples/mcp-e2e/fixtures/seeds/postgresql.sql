-- Nativer PostgreSQL-Seed der Compare-Matrix (scripts/smoke-compare-matrix.sh).
--
-- Er kommt **nach** fixtures/compare-matrix.yaml auf denselben Server und geht
-- damit in den Reverse der Quelle ein. Die Fixture ist dialektneutral; hier
-- steht, was nur PostgreSQL so zurueckgibt.
--
-- Anmerkungsformat siehe README („Native Typ-Seeds"); jede Anmerkung steht
-- **ausserhalb** der CREATE-Anweisung.

-- seed: sl_pg_array.id | paket: P5 | quelle: integer
--   ziel mysql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_pg_array.tags | paket: P5 | quelle: array(text)
--   ziel mysql: json | code: W162
--   ziel sqlite: text | code: W162
--   ziel mssql: text | code: W137
-- seed: sl_pg_array.counts | paket: S3 | quelle: array(integer)
--   ziel mysql: json | code: W162
--   ziel sqlite: text | code: W162
--   ziel mssql: text | code: W137
-- seed: sl_pg_array.totals | paket: S3 | quelle: array(biginteger)
--   ziel mysql: json | code: W162
--   ziel sqlite: text | code: W162
--   ziel mssql: text | code: W137
-- seed: sl_pg_array.due_dates | paket: N1 | quelle: array(text) | code: R301
--   ziel mysql: json | code: W162
--   ziel sqlite: text | code: W162
--   ziel mssql: text | code: W137
CREATE TABLE sl_pg_array (
  id integer PRIMARY KEY,
  tags text[] NOT NULL,
  counts integer[] NOT NULL,
  totals bigint[] NOT NULL,
  due_dates date[] NOT NULL
);

-- seed: sl_pg_json.id | paket: P8 | quelle: integer
--   ziel mysql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_pg_json.payload_json | paket: P8 | quelle: json | code: R402
--   ziel mysql: json | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: W137
-- seed: sl_pg_json.payload_jsonb | paket: P8 | quelle: json
--   ziel mysql: json | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: W137
CREATE TABLE sl_pg_json (
  id integer PRIMARY KEY,
  payload_json json NOT NULL,
  payload_jsonb jsonb NOT NULL
);

-- seed: sl_pg_number.id | paket: P9 | quelle: integer
--   ziel mysql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_pg_number.amount | paket: P9 | quelle: float | code: R404
--   ziel mysql: float | code: keinen
--   ziel sqlite: float | code: keinen
--   ziel mssql: float | code: keinen
-- seed: sl_pg_number.bounded | paket: D3 | quelle: decimal(12,2)
--   ziel mysql: decimal(12,2) | code: keinen
--   ziel sqlite: float | code: W200
--   ziel mssql: decimal(12,2) | code: keinen
CREATE TABLE sl_pg_number (
  id integer PRIMARY KEY,
  amount numeric NOT NULL,
  bounded numeric(12,2) NOT NULL
);

-- seed: sl_pg_text.id | paket: D3 | quelle: integer
--   ziel mysql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_pg_text.free_text | paket: D3 | quelle: text
--   ziel mysql: text | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: keinen
-- seed: sl_pg_text.span | paket: B4 | quelle: text | code: R301
--   ziel mysql: text | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: keinen
CREATE TABLE sl_pg_text (
  id integer PRIMARY KEY,
  free_text varchar NOT NULL,
  span interval NOT NULL
);

-- seed: sl_pg_identity_big.id | paket: P10 | quelle: biginteger
--   generation: identity(always)
--   ziel mysql: biginteger | generation: identity(by_default) | code: W163
--   ziel sqlite: identifier(auto) | code: W163
--   ziel mssql: biginteger | generation: identity(always) | code: keinen
-- seed: sl_pg_identity_big.label | paket: P10 | quelle: text
--   ziel mysql: text | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: keinen
CREATE TABLE sl_pg_identity_big (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  label text NOT NULL
);

-- seed: sl_pg_identity_int.id | paket: S1 | quelle: integer
--   generation: identity(always)
--   ziel mysql: identifier(auto) | code: W163
--   ziel sqlite: identifier(auto) | code: W163
--   ziel mssql: identifier(auto) | code: keinen
-- seed: sl_pg_identity_int.label | paket: S1 | quelle: text
--   ziel mysql: text | code: keinen
--   ziel sqlite: text | code: keinen
--   ziel mssql: text | code: keinen
CREATE TABLE sl_pg_identity_int (
  id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  label text NOT NULL
);

-- P4: eine PostGIS-`geography`-Spalte. Eigene Tabelle, weil SQLite ohne
-- `--spatial-profile spatialite` die **ganze** Tabelle mit E052 blockt (der
-- Matrix-Lauf generiert ohne Profil); der Generate-Report nennt sie dann
-- unter `skipped_objects`, und der Check erwartet sie im SQLite-Ziel nicht.
-- Deshalb steht hier auch keine `ziel sqlite`-Zeile.
--
-- seed: sl_pg_geography.id | paket: P4 | quelle: integer
--   ziel mysql: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_pg_geography.area | paket: P4 | quelle: geometry(srid=4326) | code: R403
--   ziel mysql: geometry(srid=4326) | code: keinen
--   ziel mssql: geometry(srid=4326) | code: keinen
CREATE TABLE sl_pg_geography (
  id integer PRIMARY KEY,
  area geography(Point, 4326)
);
