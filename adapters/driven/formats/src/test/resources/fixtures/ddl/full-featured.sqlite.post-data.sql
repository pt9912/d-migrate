-- [E054] Function 'calc_total' cannot be created via DDL in SQLite.
-- Hint: Register custom functions programmatically via the SQLite C API or your application's SQLite driver.
-- Function "calc_total" is not supported in SQLite

-- [E054] Procedure 'update_status' cannot be created in SQLite.
-- Hint: Implement procedure logic at the application level.
-- Procedure "update_status" is not supported in SQLite

-- [E053] Trigger 'trg_updated' was written for 'postgresql' and must be manually rewritten for SQLite.
-- Hint: Rewrite the trigger body using SQLite-compatible syntax with BEGIN...END;.
-- TODO: Rewrite trigger "trg_updated" for SQLite (source dialect: postgresql)

-- [E053] Trigger 'trg_insert' has no body and must be manually implemented.
-- Hint: Provide a trigger body in the schema definition.
-- TODO: Implement trigger "trg_insert"
