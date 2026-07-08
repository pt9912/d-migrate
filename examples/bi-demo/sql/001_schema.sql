-- BI-Demo Schema (BD.2)
-- Spec: docs/planning/done-archive/bi-demo-compose.md §7

CREATE TABLE customers (
    id           integer PRIMARY KEY,
    email        text NOT NULL,
    first_name   text NOT NULL,
    last_name    text NOT NULL,
    middle_name  text,
    is_active    boolean NOT NULL DEFAULT true,
    created_at   timestamp with time zone NOT NULL,
    metadata     jsonb
);

CREATE TABLE products (
    id           integer PRIMARY KEY,
    sku          text NOT NULL UNIQUE,
    name         text NOT NULL,
    category     text NOT NULL,
    unit_price   numeric(10,2) NOT NULL,
    in_stock     boolean NOT NULL DEFAULT true,
    created_at   timestamp with time zone NOT NULL
);

CREATE TABLE orders (
    id            integer PRIMARY KEY,
    customer_id   integer NOT NULL REFERENCES customers(id),
    status        text NOT NULL,
    notes         text,
    total_amount  numeric(10,2) NOT NULL,
    created_at    timestamp with time zone NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status      ON orders(status);
CREATE INDEX idx_orders_created_at  ON orders(created_at);

CREATE TABLE order_items (
    id          integer PRIMARY KEY,
    order_id    integer NOT NULL REFERENCES orders(id),
    product_id  integer NOT NULL REFERENCES products(id),
    quantity    integer NOT NULL,
    unit_price  numeric(10,2) NOT NULL
);

CREATE INDEX idx_order_items_order_id   ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

CREATE TABLE events (
    id           integer PRIMARY KEY,
    customer_id  integer NOT NULL REFERENCES customers(id),
    event_type   text NOT NULL,
    payload      jsonb,
    created_at   timestamp with time zone NOT NULL
);

CREATE INDEX idx_events_customer_id ON events(customer_id);
CREATE INDEX idx_events_created_at  ON events(created_at);
