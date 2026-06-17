-- [E053] View 'computed_view' body is not portable to MySQL (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with MySQL-compatible syntax and re-run.

-- [E053] View 'heuristic_view' body is not portable to MySQL (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with MySQL-compatible syntax and re-run.

CREATE OR REPLACE VIEW `dependent_view` AS
SELECT * FROM computed_view;

-- [E053] Function 'calc_total' was written for 'postgresql' and must be manually rewritten for MySQL.
-- Hint: Rewrite the function body using MySQL-compatible syntax.

-- [E053] Trigger 'trg_audit' was written for 'postgresql' and must be manually rewritten for MySQL.
-- Hint: Rewrite the trigger body using MySQL-compatible syntax.
