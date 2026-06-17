-- [E053] View 'computed_view' body is not portable to SQLite (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with SQLite-compatible syntax and re-run.

-- [E053] View 'heuristic_view' body is not portable to SQLite (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with SQLite-compatible syntax and re-run.

CREATE VIEW IF NOT EXISTS "dependent_view" AS
SELECT * FROM computed_view;

-- [E054] Function 'calc_total' cannot be created via DDL in SQLite.
-- Hint: Register custom functions programmatically via the SQLite C API or your application's SQLite driver.
-- Function "calc_total" is not supported in SQLite

-- [E053] Trigger 'trg_audit' was written for 'postgresql' and must be manually rewritten for SQLite.
-- Hint: Rewrite the trigger body using SQLite-compatible syntax with BEGIN...END;.
