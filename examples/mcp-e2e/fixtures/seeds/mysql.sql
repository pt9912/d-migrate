-- Nativer MySQL-Seed der Compare-Matrix (scripts/smoke-compare-matrix.sh).
--
-- Er kommt **nach** fixtures/compare-matrix.yaml auf denselben Server und geht
-- damit in den Reverse der Quelle ein. Die Fixture ist dialektneutral; hier
-- steht, was nur MySQL so zurueckgibt.
--
-- Jede Seed-Spalte traegt eine Anmerkung, und zwar **ausserhalb** der
-- CREATE-Anweisung: SQLite speichert Kommentare innerhalb des Tabellentextes,
-- und die Scanner dort kennen keine. Damit dieselbe Form fuer jeden Dialekt
-- gilt, steht sie hier ebenso davor. Das Format liest der Silent-Loss-Check:
--
--   -- seed: <tabelle>.<spalte> | paket: <Paket> | quelle: <neutrale Form>
--   --   [ausdruck: <erwarteter neutraler Ausdruck>]
--   --   ziel <dialekt>: <neutrale Form im Reverse des Ziels> | code: <Code|keinen>
--
-- „quelle" ist die Form, die der Reverse **dieser** Quelle liefern muss;
-- „ziel <d>" die Form, die der Reverse des Ziels danach liefert. Wo eine
-- Zielform einen Verlust darstellt, nennt „code" den Code, den der
-- Generate-Report dieser Zelle dafuer traegt — oder ausdruecklich „keinen".
--
-- P6 (Reader-Treue 1): MySQL gibt CHECK und Berechnungsausdruck in
-- Serverform zurueck (Zeichensatz-Introducer, Backslash-Escapes, Backticks,
-- das Ganze ein zweites Mal escapet). Vor P6 war jedes MySQL-Reverse damit
-- ungueltig (E012/E136) und die ganze MySQL-Zeile der Matrix unmessbar.

-- seed: sl_my_expr.id | paket: P6 | quelle: identifier(auto)
--   ziel postgresql: identifier(auto) | code: keinen
--   ziel mssql: integer | generation: identity(always) | code: keinen
--   ziel sqlite: identifier(auto) | code: keinen
-- seed: sl_my_expr.note | paket: P6 | quelle: text(40)
--   ziel postgresql: text(40) | code: keinen
--   ziel mssql: text(40) | code: keinen
--   ziel sqlite: text | code: keinen
-- seed: sl_my_expr.Menge | paket: P6 | quelle: integer
--   ziel postgresql: integer | code: keinen
--   ziel mssql: integer | code: keinen
--   ziel sqlite: integer | code: keinen
-- seed: sl_my_expr.stufe | paket: P6 | quelle: text(10)
--   ausdruck: (case when ("Menge" > 0) then 'hoch' else 'keine' end)
--   ziel postgresql: text(10) | code: keinen
--   ziel mssql: text(5) | code: keinen
--   ziel sqlite: text | code: keinen
CREATE TABLE sl_my_expr (
  id INT NOT NULL AUTO_INCREMENT,
  `Menge` INT NOT NULL,
  note VARCHAR(40) NOT NULL,
  stufe VARCHAR(10) GENERATED ALWAYS AS (CASE WHEN `Menge` > 0 THEN 'hoch' ELSE 'keine' END) STORED,
  PRIMARY KEY (id),
  CONSTRAINT ck_sl_my_expr_note CHECK (note LIKE '%-%'),
  CONSTRAINT ck_sl_my_expr_menge CHECK (`Menge` > 0)
);
