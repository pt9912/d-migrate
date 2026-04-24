-- [W111] View query may contain dialect-specific functions: CALC_TOTAL
-- Hint: Review and manually adjust if needed.
CREATE OR REPLACE VIEW `computed_view` AS
SELECT id, calc_total(id) AS total FROM orders;

-- [W111] View query may contain dialect-specific functions: CALC_TOTAL
-- Hint: Review and manually adjust if needed.
CREATE OR REPLACE VIEW `heuristic_view` AS
SELECT id, calc_total(id) FROM orders;

CREATE OR REPLACE VIEW `dependent_view` AS
SELECT * FROM computed_view;

-- [E053] Function 'calc_total' was written for 'postgresql' and must be manually rewritten for MySQL.
-- Hint: Rewrite the function body using MySQL-compatible syntax.

-- [E053] Trigger 'trg_audit' was written for 'postgresql' and must be manually rewritten for MySQL.
-- Hint: Rewrite the trigger body using MySQL-compatible syntax.
