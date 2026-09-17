-- Nativer SQLite-Seed der Compare-Matrix (scripts/smoke-compare-matrix.sh).
--
-- Er kommt **nach** fixtures/compare-matrix.yaml auf dieselbe Datei und geht
-- damit in den Reverse der Quelle ein. Die Fixture ist dialektneutral; hier
-- steht, was nur SQLite so zurueckgibt.
--
-- Anmerkungsformat siehe README („Native Typ-Seeds"); jede Anmerkung steht
-- **ausserhalb** der CREATE-Anweisung. Das ist bei SQLite keine Kosmetik:
-- SQLite speichert einen Kommentar **innerhalb** des `CREATE TABLE`-Textes
-- mit, und die Scanner des Readers kennen keine Kommentare (Posten M8 des
-- Reader-Plans 1) — eine Anmerkung in der Anweisung veraenderte also, was der
-- Reverse liest.
--
-- P11 traegt die Namen von Constraints: SQLite fuehrt sie nur im
-- `CREATE TABLE`-Text, der Katalog nummeriert sie durch (`fk_0`, `uq_0` je
-- Tabelle). Zwei unbenannte mehrspaltige UNIQUE-Klauseln in **zwei** Tabellen
-- ergeben deshalb zweimal `uq_0` — SQLite -> PostgreSQL scheitert daran, bis
-- P11 kommt.

-- seed: sl_sq_parent.id | paket: P11 | quelle: identifier(auto)
--   ziel postgresql: identifier(auto) | code: keinen
--   ziel mysql: identifier(auto) | code: keinen
--   ziel mssql: identifier(auto) | code: keinen
-- seed: sl_sq_parent.code | paket: P11 | quelle: text
--   ziel postgresql: text | code: keinen
--   ziel mysql: text | code: keinen
--   ziel mssql: text | code: keinen
-- seed: sl_sq_parent.region | paket: P11 | quelle: text
--   ziel postgresql: text | code: keinen
--   ziel mysql: text | code: keinen
--   ziel mssql: text | code: keinen
CREATE TABLE sl_sq_parent (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL,
  region TEXT NOT NULL,
  UNIQUE (code, region)
);

-- seed: sl_sq_child.id | paket: P11 | quelle: identifier(auto)
--   ziel postgresql: identifier(auto) | code: keinen
--   ziel mysql: identifier(auto) | code: keinen
--   ziel mssql: identifier(auto) | code: keinen
-- seed: sl_sq_child.parent_id | paket: P11 | quelle: integer
--   ziel postgresql: integer | code: keinen
--   ziel mysql: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_sq_child.owner_id | paket: P11 | quelle: integer
--   ziel postgresql: integer | code: keinen
--   ziel mysql: integer | code: keinen
--   ziel mssql: integer | code: keinen
-- seed: sl_sq_child.code | paket: P11 | quelle: text
--   ziel postgresql: text | code: keinen
--   ziel mysql: text | code: keinen
--   ziel mssql: text | code: keinen
-- seed: sl_sq_child.region | paket: P11 | quelle: text
--   ziel postgresql: text | code: keinen
--   ziel mysql: text | code: keinen
--   ziel mssql: text | code: keinen
-- seed: sl_sq_child.amount | paket: P9 | quelle: float | code: R221
--   ziel postgresql: float | code: keinen
--   ziel mysql: float | code: keinen
--   ziel mssql: float | code: keinen
CREATE TABLE sl_sq_child (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id INTEGER NOT NULL REFERENCES sl_sq_parent(id),
  owner_id INTEGER NOT NULL CONSTRAINT fk_sl_sq_child_owner REFERENCES sl_sq_parent(id),
  code TEXT NOT NULL,
  region TEXT NOT NULL,
  amount NUMERIC NOT NULL,
  UNIQUE (code, region),
  FOREIGN KEY (code, region) REFERENCES sl_sq_parent(code, region) ON DELETE CASCADE
);
