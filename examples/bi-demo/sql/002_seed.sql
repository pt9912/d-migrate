-- BI-Demo Seed (BD.2)
-- Spec: docs/planning/done/bi-demo-compose.md §7 + BD.2-Determinismus-Vertrag.
--
-- Determinismus-Saeulen:
--   1. SELECT setseed(0.42) am Skript-Beginn
--   2. \set demo_start_date '2026-01-01' + DATE :'demo_start_date'
--      als zeit-Anker (kein current_date, kein now())
--   3. SET timezone = 'UTC' fuer timestamptz-Konsistenz
--   4. SET max_parallel_workers_per_gather = 0 zwingt
--      single-thread, damit random()-Konsum reproduzierbar ist
--   5. Jede INSERT mit explizitem ORDER BY damit die physische
--      Heap-Reihenfolge stabil bleibt (relevant fuer pg_dump-Hash)
--
-- WICHTIG: kein SET LOCAL — das offizielle Postgres-Image fuehrt
-- /docker-entrypoint-initdb.d/-Files via `psql -f` ohne explizite
-- Transaktion aus; SET LOCAL waere ein No-op mit WARNING. Plain
-- SET reicht (session-scope == Skript-Laufzeit).

SET timezone = 'UTC';
SET max_parallel_workers_per_gather = 0;
SELECT setseed(0.42);
\set demo_start_date '2026-01-01'

-- customers (50 rows)
-- §7-Trigger:
--   - email: ~5% leerer String (ids 1-3)  -> CONTAINS_EMPTY_STRINGS
--   - middle_name: 3 NULL (ids 4-6), 5 'N/A' (ids 7-11),
--     5 'tbd' (ids 12-16)               -> POSSIBLE_PLACEHOLDER_VALUES
INSERT INTO customers (id, email, first_name, last_name, middle_name, is_active, created_at, metadata)
SELECT
    i,
    CASE WHEN i <= 3 THEN ''
         ELSE 'customer' || i || '@example.com'
    END,
    'First' || i,
    'Last' || i,
    CASE
        WHEN i BETWEEN 4  AND 6  THEN NULL
        WHEN i BETWEEN 7  AND 11 THEN 'N/A'
        WHEN i BETWEEN 12 AND 16 THEN 'tbd'
        ELSE 'M' || (i % 10)::text
    END,
    (i % 7) != 0,
    DATE :'demo_start_date' + ((i - 1) || ' days')::interval,
    jsonb_build_object(
        'signup_source',
        CASE i % 3
            WHEN 0 THEN 'web'
            WHEN 1 THEN 'mobile'
            ELSE        'partner'
        END,
        'newsletter_opt_in', (i % 2 = 0)
    )
FROM generate_series(1, 50) AS s(i)
ORDER BY i;

-- products (30 rows)
-- §7-Trigger:
--   - category: 3 distinkte Werte (Electronics/Apparel/Home) ueber
--     30 Zeilen        -> LOW_CARDINALITY + DUPLICATE_VALUES
--   - id=15 unit_price = 99999.99 als Outlier
INSERT INTO products (id, sku, name, category, unit_price, in_stock, created_at)
SELECT
    i,
    'SKU-' || lpad(i::text, 4, '0'),
    'Product ' || i,
    CASE
        WHEN i <= 10 THEN 'Electronics'
        WHEN i <= 20 THEN 'Apparel'
        ELSE              'Home'
    END,
    CASE WHEN i = 15 THEN 99999.99
         ELSE round((random() * 490 + 10)::numeric, 2)
    END,
    (i % 5) != 0,
    DATE :'demo_start_date' + ((i - 1) || ' days')::interval
FROM generate_series(1, 30) AS s(i)
ORDER BY i;

-- orders (500 rows, verteilt ueber 90 Tage)
-- §7-Trigger:
--   - status in {pending, paid, cancelled, refunded}
--   - notes: 25 NULL (5%), 16 whitespace-only (~3%), rest Mix
--     -> CONTAINS_BLANK_STRINGS + sichtbarer nullCount
INSERT INTO orders (id, customer_id, status, notes, total_amount, created_at)
SELECT
    i,
    ((i - 1) % 50) + 1,
    CASE (i % 10)
        WHEN 0 THEN 'pending'
        WHEN 1 THEN 'pending'
        WHEN 2 THEN 'paid'
        WHEN 3 THEN 'paid'
        WHEN 4 THEN 'paid'
        WHEN 5 THEN 'paid'
        WHEN 6 THEN 'paid'
        WHEN 7 THEN 'cancelled'
        WHEN 8 THEN 'cancelled'
        ELSE        'refunded'
    END,
    CASE
        WHEN i % 20 = 0 THEN NULL
        WHEN i % 31 = 0 THEN '   '
        WHEN i %  7 = 0 THEN 'Note for order ' || i
        ELSE                 ''
    END,
    round((random() * 990 + 10)::numeric, 2),
    DATE :'demo_start_date'
      + (floor(random() * 90)::int || ' days')::interval
      + (floor(random() * 86400)::int || ' seconds')::interval
FROM generate_series(1, 500) AS s(i)
ORDER BY i;

-- order_items (1500 rows, 3:1 zu orders)
-- §7-Trigger: bewusster Outlier in unit_price
INSERT INTO order_items (id, order_id, product_id, quantity, unit_price)
SELECT
    i,
    ((i - 1) / 3) + 1,
    ((i - 1) % 30) + 1,
    (i % 5) + 1,
    CASE WHEN i = 42 THEN 99999.99
         ELSE round((random() * 490 + 10)::numeric, 2)
    END
FROM generate_series(1, 1500) AS s(i)
ORDER BY i;

-- events (10000 rows, ~60 Tage, ~165/Tag)
-- Zeitreihen-Showcase fuer spaetere DuckDB/Parquet-Demos.
INSERT INTO events (id, customer_id, event_type, payload, created_at)
SELECT
    i,
    ((i - 1) % 50) + 1,
    CASE i % 4
        WHEN 0 THEN 'page_view'
        WHEN 1 THEN 'click'
        WHEN 2 THEN 'purchase'
        ELSE        'signup'
    END,
    jsonb_build_object(
        'event_id', i,
        'session',  's' || (((i - 1) / 5) + 1)::text
    ),
    DATE :'demo_start_date'
      + (floor(random() * 60)::int || ' days')::interval
      + (floor(random() * 86400)::int || ' seconds')::interval
FROM generate_series(1, 10000) AS s(i)
ORDER BY i;
