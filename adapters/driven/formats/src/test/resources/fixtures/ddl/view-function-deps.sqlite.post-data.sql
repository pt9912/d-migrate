-- [W111] View query may contain dialect-specific functions: CALC_TOTAL
-- Hint: Review and manually adjust if needed.
CREATE VIEW IF NOT EXISTS "computed_view" AS
SELECT id, calc_total(id) AS total FROM orders;

-- [W111] View query may contain dialect-specific functions: CALC_TOTAL
-- Hint: Review and manually adjust if needed.
CREATE VIEW IF NOT EXISTS "heuristic_view" AS
SELECT id, calc_total(id) FROM orders;

CREATE VIEW IF NOT EXISTS "dependent_view" AS
SELECT * FROM computed_view;

-- [E054] Function 'calc_total' cannot be created via DDL in SQLite.
-- Hint: Register custom functions programmatically via the SQLite C API or your application's SQLite driver.
-- Function "calc_total" is not supported in SQLite

-- [E053] Trigger 'trg_audit' was written for 'postgresql' and must be manually rewritten for SQLite.
-- Hint: Rewrite the trigger body using SQLite-compatible syntax with BEGIN...END;.
-- TODO: Rewrite trigger "trg_audit" for SQLite (source dialect: postgresql)
