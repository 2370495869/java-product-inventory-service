CREATE TABLE products (
    product_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    sale_mode VARCHAR(16) NOT NULL CHECK (sale_mode IN ('REGULAR', 'PRESALE')),
    presale_limit INTEGER,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_products_presale_limit CHECK (
        (sale_mode = 'PRESALE' AND presale_limit > 0)
        OR (sale_mode = 'REGULAR' AND presale_limit IS NULL)
    )
);

CREATE TABLE inventory (
    product_id VARCHAR(64) PRIMARY KEY REFERENCES products(product_id),
    on_hand_quantity INTEGER NOT NULL DEFAULT 0 CHECK (on_hand_quantity >= 0),
    regular_reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (regular_reserved_quantity >= 0),
    presale_reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (presale_reserved_quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_regular_reservation CHECK (regular_reserved_quantity <= on_hand_quantity)
);

CREATE TABLE orders (
    order_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL CHECK (length(request_hash) = 64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders(order_id),
    product_id VARCHAR(64) NOT NULL REFERENCES products(product_id),
    product_name VARCHAR(160) NOT NULL,
    sale_mode VARCHAR(16) NOT NULL CHECK (sale_mode IN ('REGULAR', 'PRESALE')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, product_id)
);

CREATE TABLE inventory_movements (
    movement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES products(product_id),
    movement_type VARCHAR(24) NOT NULL CHECK (
        movement_type IN ('INITIAL_STOCK', 'STOCK_ADJUSTMENT', 'ORDER_RESERVED', 'ORDER_CANCELLED')
    ),
    physical_delta INTEGER NOT NULL,
    regular_reserved_delta INTEGER NOT NULL,
    presale_reserved_delta INTEGER NOT NULL,
    on_hand_after INTEGER NOT NULL CHECK (on_hand_after >= 0),
    regular_reserved_after INTEGER NOT NULL CHECK (regular_reserved_after >= 0),
    presale_reserved_after INTEGER NOT NULL CHECK (presale_reserved_after >= 0),
    reference_id UUID,
    reason VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_name_lower ON products (lower(name));
CREATE INDEX idx_inventory_movements_product_page
    ON inventory_movements (product_id, movement_id DESC);
CREATE INDEX idx_order_items_product ON order_items (product_id);
