CREATE OR REPLACE VIEW "computed_view" AS
SELECT id, calc_total(id) AS total FROM orders;

CREATE OR REPLACE VIEW "heuristic_view" AS
SELECT id, calc_total(id) FROM orders;

CREATE OR REPLACE VIEW "dependent_view" AS
SELECT * FROM computed_view;

CREATE OR REPLACE FUNCTION "calc_total"("p_order_id" INTEGER) RETURNS DECIMAL(10,2) AS $$
BEGIN
    RETURN 0;
END;

$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION "trg_fn_trg_audit"() RETURNS TRIGGER AS $$
BEGIN
    -- audit logic
END;

$$ LANGUAGE plpgsql;

CREATE TRIGGER "trg_audit"
    AFTER INSERT ON "orders"
    FOR EACH ROW
    EXECUTE FUNCTION "trg_fn_trg_audit"();
