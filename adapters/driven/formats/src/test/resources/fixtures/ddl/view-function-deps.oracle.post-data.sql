-- [E053] Function 'calc_total' was written for 'postgresql' and must be manually rewritten for Oracle.
-- Hint: Create the function as PL/SQL (CREATE OR REPLACE ...) manually on the target.

-- [E053] View 'computed_view' body is not portable to Oracle (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with Oracle-compatible syntax and re-run.

-- [E053] View 'heuristic_view' body is not portable to Oracle (dialect-specific function(s): CALC_TOTAL); d-migrate does not translate view bodies between dialects.
-- Hint: Rewrite the view body with Oracle-compatible syntax and re-run.

CREATE OR REPLACE FORCE VIEW "dependent_view" AS
SELECT * FROM computed_view;

-- [E053] Trigger 'trg_audit' was written for 'postgresql' and must be manually rewritten for Oracle.
-- Hint: Create the trigger as PL/SQL (CREATE OR REPLACE ...) manually on the target.
