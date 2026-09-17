-- Nativer SQL-Server-Seed der Compare-Matrix (scripts/smoke-compare-matrix.sh).
--
-- Er kommt **nach** fixtures/compare-matrix.yaml auf denselben Server und geht
-- damit in den Reverse der Quelle ein. Die Fixture ist dialektneutral; hier
-- steht, was nur SQL Server so zurueckgibt.
--
-- Anmerkungsformat siehe README („Native Typ-Seeds und der
-- Silent-Loss-Check"); jede Anmerkung steht **ausserhalb** der
-- CREATE-Anweisung.
--
-- P12 (Reader-Treue 1) und E1: SQL Server fuehrt den Berechnungsausdruck in
-- T-SQL-Oberflaechensyntax (`([Menge]*[Preis])`). Neutral steht dort
-- `"Menge"*"Preis"` — und MySQL, das `"…"` ohne `ANSI_QUOTES` als Zeichenkette
-- liest, bekommt vom Generator `` `Menge`*`Preis` ``. Ohne beides rechnete die
-- Zelle SQL Server -> MySQL still mit Zeichenketten, statt laut zu scheitern.
--
-- `SET QUOTED_IDENTIFIER ON` ist Pflicht: SQL Server legt eine Tabelle mit
-- berechneter Spalte sonst gar nicht erst an (Msg 1934). Der Client des
-- Harness (sqlcmd) hat die Option nicht von sich aus gesetzt.
SET QUOTED_IDENTIFIER ON;
GO

-- seed: sl_ms_calc.id | paket: P12 | quelle: identifier(auto)
--   ziel postgresql: identifier(auto) | code: keinen
--   ziel mysql: identifier(auto) | code: keinen
--   ziel sqlite: identifier(auto) | code: keinen
-- seed: sl_ms_calc.Menge | paket: P12 | quelle: integer
--   ziel postgresql: integer | code: keinen
--   ziel mysql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
-- seed: sl_ms_calc.Preis | paket: P12 | quelle: decimal(10,2)
--   ziel postgresql: decimal(10,2) | code: keinen
--   ziel mysql: decimal(10,2) | code: keinen
--   ziel sqlite: float | code: W200
-- seed: sl_ms_calc.Summe | paket: P12 | quelle: decimal(21,2)
--   ausdruck: "Menge"*"Preis"
--   ziel postgresql: decimal(21,2) | code: keinen
--   ziel mysql: decimal(21,2) | code: keinen
--   ziel sqlite: float | code: W200
CREATE TABLE sl_ms_calc (
  id INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
  "Menge" INT NOT NULL,
  "Preis" DECIMAL(10,2) NOT NULL,
  "Summe" AS ("Menge" * "Preis") PERSISTED
);
GO
