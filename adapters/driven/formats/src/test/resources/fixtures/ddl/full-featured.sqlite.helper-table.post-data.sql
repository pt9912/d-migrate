-- [E054] Function 'calc_total' cannot be created via DDL in SQLite.
-- Hint: Register custom functions programmatically via the SQLite C API or your application's SQLite driver.
-- Function "calc_total" is not supported in SQLite

-- [E054] Procedure 'update_status' cannot be created in SQLite.
-- Hint: Implement procedure logic at the application level.
-- Procedure "update_status" is not supported in SQLite

CREATE TRIGGER "dmg_seq_orders_invoice_number_89d3849620_bi"
BEFORE INSERT ON "orders"
FOR EACH ROW
WHEN NEW."invoice_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=invoice_seq table=orders column=invoice_number */
    SELECT RAISE(ABORT, 'dmg_sequences: sequence row invoice_seq not found')
        WHERE NOT EXISTS (
            SELECT 1 FROM "dmg_sequences" WHERE "name" = 'invoice_seq'
        );
    SELECT RAISE(ABORT, 'dmg_sequences: sequence invoice_seq exhausted')
        WHERE (SELECT "exhausted" FROM "dmg_sequences" WHERE "name" = 'invoice_seq') = 1;
    UPDATE "dmg_sequences"
        SET "last_returned_value" = "next_value",
            "next_value" = CASE
                WHEN "increment_by" > 0
                     AND "next_value" > COALESCE("max_value", 9223372036854775807) - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("min_value", 1)
                WHEN "increment_by" < 0
                     AND "next_value" < COALESCE("min_value", -9223372036854775808) - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("max_value", -1)
                WHEN "increment_by" > 0
                     AND "next_value" > COALESCE("max_value", 9223372036854775807) - "increment_by"
                     AND "cycle_enabled" = 0
                THEN "next_value"
                WHEN "increment_by" < 0
                     AND "next_value" < COALESCE("min_value", -9223372036854775808) - "increment_by"
                     AND "cycle_enabled" = 0
                THEN "next_value"
                ELSE "next_value" + "increment_by"
            END,
            "exhausted" = CASE
                WHEN "cycle_enabled" = 0
                     AND (
                         ("increment_by" > 0
                          AND "next_value" > COALESCE("max_value", 9223372036854775807) - "increment_by")
                         OR
                         ("increment_by" < 0
                          AND "next_value" < COALESCE("min_value", -9223372036854775808) - "increment_by")
                     )
                THEN 1
                ELSE "exhausted"
            END
        WHERE "name" = 'invoice_seq';
END;

CREATE TRIGGER "dmg_seq_orders_invoice_number_89d3849620_ai"
AFTER INSERT ON "orders"
FOR EACH ROW
WHEN NEW."invoice_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger-post sequence=invoice_seq table=orders column=invoice_number */
    UPDATE "orders"
        SET "invoice_number" = (
            SELECT "last_returned_value" FROM "dmg_sequences" WHERE "name" = 'invoice_seq'
        )
        WHERE ROWID = NEW.ROWID;
END;

-- [E053] Trigger 'trg_updated' was written for 'postgresql' and must be manually rewritten for SQLite.
-- Hint: Rewrite the trigger body using SQLite-compatible syntax with BEGIN...END;.

-- [E053] Trigger 'trg_insert' has no body and must be manually implemented.
-- Hint: Provide a trigger body in the schema definition.
