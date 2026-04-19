CREATE OR REPLACE FUNCTION "calc_total"("p_order_id" INTEGER) RETURNS DECIMAL(10,2) AS $$
BEGIN
    RETURN 0;
END;

$$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE "update_status"("p_order_id" INTEGER, "p_status" TEXT, OUT "p_affected" INTEGER) AS $$
BEGIN
    UPDATE orders SET status = p_status WHERE id = p_order_id;
END;

$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION "trg_fn_trg_updated"() RETURNS TRIGGER AS $$
NEW.updated_at = CURRENT_TIMESTAMP;

$$ LANGUAGE plpgsql;

CREATE TRIGGER "trg_updated"
    BEFORE UPDATE ON "orders"
    FOR EACH ROW
    WHEN (OLD.status != NEW.status)
    EXECUTE FUNCTION "trg_fn_trg_updated"();

-- [E053] Trigger 'trg_insert' has no body and must be manually implemented.
-- Hint: Provide a trigger body in the schema definition.
-- TODO: Implement trigger "trg_insert"
