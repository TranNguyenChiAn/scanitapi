-- =====================================================================
-- ScanIt / Jupiter POS - Database cho cua hang quan ao
-- PostgreSQL 15+  (can extension: pg_trgm, vector/pgvector)
--
-- Quy uoc:
--   * Tien: BIGINT, don vi VND (khong co phan thap phan) - giong BE hien tai.
--   * Thoi gian: TIMESTAMPTZ.
--   * Enum: TEXT + CHECK de de migrate bang Liquibase/Flyway.
--   * Anh KHONG luu trong DB: chi luu khoa object (object_key) + metadata.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- 1. CUA HANG, NHAN VIEN, PHAN QUYEN
-- ---------------------------------------------------------------------
CREATE TABLE stores (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    address     TEXT,
    phone       TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,          -- vd: product.manage
    module      TEXT NOT NULL,                 -- vd: product
    description TEXT
);

CREATE TABLE roles (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,          -- ADMIN, EMPLOYEE, ...
    name        TEXT NOT NULL,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE -- role he thong khong cho xoa
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id      BIGINT REFERENCES stores(id),
    role_id       BIGINT NOT NULL REFERENCES roles(id),
    username      TEXT NOT NULL UNIQUE,
    email         TEXT UNIQUE,
    phone         TEXT UNIQUE,
    full_name     TEXT NOT NULL,
    gender        TEXT CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    password_hash TEXT NOT NULL,               -- bcrypt, khong bao gio luu plain text
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE TABLE audit_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id),
    action      TEXT NOT NULL,                 -- CREATE / UPDATE / DELETE / LOGIN ...
    entity_type TEXT NOT NULL,
    entity_id   BIGINT,
    changes     JSONB,
    ip_address  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_user   ON audit_logs (user_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 2. DANH MUC SAN PHAM
--    product = mau ma (vd: Set len khoa keo 8922#)
--    variant = mau + size cu the, la don vi co ma vach va ton kho
-- ---------------------------------------------------------------------
CREATE TABLE brands (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE categories (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id BIGINT REFERENCES categories(id),
    name      TEXT NOT NULL,
    slug      TEXT NOT NULL UNIQUE
);

CREATE TABLE colors (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     TEXT NOT NULL UNIQUE,             -- Xam than, Nau dam, Be
    hex_code TEXT CHECK (hex_code ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE TABLE sizes (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,           -- S, M, L, Free size
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE products (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    style_code    TEXT NOT NULL UNIQUE,        -- ma mau cua nha cung cap, vd 8922
    name          TEXT NOT NULL,
    description   TEXT,
    brand_id      BIGINT REFERENCES brands(id),
    material      TEXT,
    season        TEXT,                        -- Thu dong 2026
    target_gender TEXT CHECK (target_gender IN ('FEMALE', 'MALE', 'UNISEX', 'KIDS')),
    product_type  TEXT NOT NULL DEFAULT 'SINGLE' CHECK (product_type IN ('SINGLE', 'SET')),
    components    JSONB,                       -- set gom nhung mon nao: ["Ao khoac", "Ao ba lo", "Chan vay"]
    status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    created_by    BIGINT REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);

CREATE TABLE product_categories (
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id),
    PRIMARY KEY (product_id, category_id)
);

CREATE TABLE product_variants (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    color_id   BIGINT NOT NULL REFERENCES colors(id),
    size_id    BIGINT NOT NULL REFERENCES sizes(id),
    sku        TEXT NOT NULL UNIQUE,           -- 8922-XAMTHAN-FS
    barcode    TEXT UNIQUE,                    -- ma vach in tren tem
    cost_price BIGINT NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
    status     TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, color_id, size_id)
);
CREATE INDEX idx_variants_product ON product_variants (product_id);

-- Gia le / gia si (vd "Buon 410" -> WHOLESALE 410000), co the co bac theo so luong
CREATE TABLE variant_prices (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id BIGINT NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    price_type TEXT NOT NULL CHECK (price_type IN ('RETAIL', 'WHOLESALE')),
    min_qty    INT NOT NULL DEFAULT 1 CHECK (min_qty >= 1),
    price      BIGINT NOT NULL CHECK (price >= 0),
    UNIQUE (variant_id, price_type, min_qty)
);

-- ---------------------------------------------------------------------
-- 3. TON KHO (so cai bat bien: moi lan doi ton la 1 dong stock_movements)
-- ---------------------------------------------------------------------
CREATE TABLE stock_levels (
    store_id     BIGINT NOT NULL REFERENCES stores(id),
    variant_id   BIGINT NOT NULL REFERENCES product_variants(id),
    quantity     INT NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    min_quantity INT NOT NULL DEFAULT 0,       -- duoi muc nay thi canh bao
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, variant_id)
);

CREATE TABLE stock_movements (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id       BIGINT NOT NULL REFERENCES stores(id),
    variant_id     BIGINT NOT NULL REFERENCES product_variants(id),
    movement_type  TEXT NOT NULL CHECK (movement_type IN
                   ('PURCHASE', 'SALE', 'SALE_RETURN', 'PURCHASE_RETURN', 'ADJUSTMENT', 'TRANSFER_IN', 'TRANSFER_OUT')),
    quantity_delta INT NOT NULL CHECK (quantity_delta <> 0),   -- am = xuat, duong = nhap
    balance_after  INT NOT NULL,
    ref_type       TEXT,                       -- ORDER / PURCHASE_ORDER / STOCKTAKE
    ref_id         BIGINT,
    note           TEXT,
    created_by     BIGINT REFERENCES users(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_mv_variant ON stock_movements (variant_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 4. ANH (luu o object storage, DB chi giu khoa + metadata)
-- ---------------------------------------------------------------------
CREATE TABLE media_assets (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_provider TEXT NOT NULL CHECK (storage_provider IN ('CLOUDINARY', 'R2', 'S3', 'OCI', 'LOCAL')),
    object_key       TEXT NOT NULL,            -- duong dan/ten file tren storage
    format           TEXT NOT NULL DEFAULT 'webp',
    width            INT NOT NULL,
    height           INT NOT NULL,
    size_bytes       INT NOT NULL,
    sha256           CHAR(64) NOT NULL UNIQUE, -- chong upload trung: cung anh chi luu 1 lan
    blurhash         TEXT,                     -- placeholder mo khi anh dang tai
    created_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (storage_provider, object_key)
);

-- Anh gan theo (san pham + mau). Doi size thi anh khong doi nen khong gan theo variant.
CREATE TABLE product_media (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    color_id   BIGINT REFERENCES colors(id),   -- NULL = anh chung cua ca san pham
    asset_id   BIGINT NOT NULL REFERENCES media_assets(id),
    sort_order INT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (product_id, color_id, asset_id)
);
CREATE UNIQUE INDEX uq_product_media_primary
    ON product_media (product_id, COALESCE(color_id, 0)) WHERE is_primary;

-- Vector dac trung anh de tim san pham bang camera (CLIP/SigLIP 512 chieu)
CREATE TABLE image_embeddings (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id   BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    model      TEXT NOT NULL,                  -- ten model, de doi model van biet vector cu
    embedding  vector(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, model)
);
CREATE INDEX idx_image_embeddings_hnsw
    ON image_embeddings USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------
-- 5. DOI TAC: KHACH HANG, NHA CUNG CAP
-- ---------------------------------------------------------------------
CREATE TABLE customers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          TEXT UNIQUE,
    name          TEXT NOT NULL,
    phone         TEXT UNIQUE,
    address       TEXT,
    customer_type TEXT NOT NULL DEFAULT 'RETAIL' CHECK (customer_type IN ('RETAIL', 'WHOLESALE')),
    credit_limit  BIGINT NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),  -- han muc no
    current_debt  BIGINT NOT NULL DEFAULT 0,   -- khach dang no minh (cache, goc la debt_entries)
    total_spent   BIGINT NOT NULL DEFAULT 0,
    total_orders  INT NOT NULL DEFAULT 0,
    note          TEXT,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE suppliers (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT NOT NULL,
    phone        TEXT UNIQUE,
    address      TEXT,
    current_debt BIGINT NOT NULL DEFAULT 0,    -- minh dang no nha cung cap
    note         TEXT,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 6. BAN HANG
-- ---------------------------------------------------------------------
CREATE TABLE orders (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             TEXT NOT NULL UNIQUE,
    store_id         BIGINT NOT NULL REFERENCES stores(id),
    customer_id      BIGINT REFERENCES customers(id),         -- NULL = khach vang lai
    staff_id         BIGINT NOT NULL REFERENCES users(id),
    order_type       TEXT NOT NULL DEFAULT 'RETAIL' CHECK (order_type IN ('RETAIL', 'WHOLESALE', 'ONLINE')),
    status           TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                     ('DRAFT', 'PENDING', 'CONFIRMED', 'SHIPPING', 'DELIVERED', 'COMPLETED', 'CANCELLED')),
    subtotal         BIGINT NOT NULL DEFAULT 0,
    discount_amount  BIGINT NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    total_amount     BIGINT NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    paid_amount      BIGINT NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    payment_status   TEXT NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID', 'PARTIAL', 'PAID')),
    receiver_name    TEXT,
    receiver_phone   TEXT,
    receiver_address TEXT,
    note             TEXT,
    ordered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_customer ON orders (customer_id, ordered_at DESC);
CREATE INDEX idx_orders_status   ON orders (status, ordered_at DESC);

CREATE TABLE order_items (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    variant_id      BIGINT NOT NULL REFERENCES product_variants(id),
    quantity        INT NOT NULL CHECK (quantity > 0),
    unit_price      BIGINT NOT NULL CHECK (unit_price >= 0),   -- gia chot luc ban
    cost_price      BIGINT NOT NULL DEFAULT 0,                 -- gia von luc ban, de tinh loi nhuan
    discount_amount BIGINT NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    line_total      BIGINT GENERATED ALWAYS AS (quantity * unit_price - discount_amount) STORED,
    UNIQUE (order_id, variant_id)
);

CREATE TABLE order_status_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status TEXT,
    to_status   TEXT NOT NULL,
    changed_by  BIGINT REFERENCES users(id),
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 7. NHAP HANG
-- ---------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         TEXT NOT NULL UNIQUE,
    supplier_id  BIGINT NOT NULL REFERENCES suppliers(id),
    store_id     BIGINT NOT NULL REFERENCES stores(id),
    status       TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'RECEIVED', 'CANCELLED')),
    total_amount BIGINT NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    paid_amount  BIGINT NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    note         TEXT,
    received_at  TIMESTAMPTZ,
    created_by   BIGINT REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_order_items (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    variant_id        BIGINT NOT NULL REFERENCES product_variants(id),
    quantity          INT NOT NULL CHECK (quantity > 0),
    unit_cost         BIGINT NOT NULL CHECK (unit_cost >= 0),
    line_total        BIGINT GENERATED ALWAYS AS (quantity * unit_cost) STORED,
    UNIQUE (purchase_order_id, variant_id)
);

-- ---------------------------------------------------------------------
-- 8. THANH TOAN VA CONG NO
--    payments    = tien that su thu/chi.
--    debt_entries = so cai cong no (chi them dong moi, khong sua/xoa).
--                  amount duong = no tang, am = no giam.
--    customers.current_debt = SUM(debt_entries.amount) cua khach do.
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    direction         TEXT NOT NULL CHECK (direction IN ('IN', 'OUT')),   -- IN = thu, OUT = chi
    party_type        TEXT NOT NULL CHECK (party_type IN ('CUSTOMER', 'SUPPLIER')),
    customer_id       BIGINT REFERENCES customers(id),
    supplier_id       BIGINT REFERENCES suppliers(id),
    order_id          BIGINT REFERENCES orders(id),                       -- NULL = thu no chung, khong gan don
    purchase_order_id BIGINT REFERENCES purchase_orders(id),
    method            TEXT NOT NULL CHECK (method IN ('CASH', 'BANK_TRANSFER')),
    amount            BIGINT NOT NULL CHECK (amount > 0),
    paid_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    note              TEXT,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((party_type = 'CUSTOMER' AND customer_id IS NOT NULL AND supplier_id IS NULL)
        OR (party_type = 'SUPPLIER' AND supplier_id IS NOT NULL AND customer_id IS NULL))
);
CREATE INDEX idx_payments_customer ON payments (customer_id, paid_at DESC);
CREATE INDEX idx_payments_supplier ON payments (supplier_id, paid_at DESC);

CREATE TABLE debt_entries (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_type        TEXT NOT NULL CHECK (party_type IN ('CUSTOMER', 'SUPPLIER')),
    customer_id       BIGINT REFERENCES customers(id),
    supplier_id       BIGINT REFERENCES suppliers(id),
    entry_type        TEXT NOT NULL CHECK (entry_type IN ('ORDER_DEBT', 'PAYMENT', 'RETURN', 'ADJUSTMENT')),
    amount            BIGINT NOT NULL CHECK (amount <> 0),
    balance_after     BIGINT NOT NULL,
    order_id          BIGINT REFERENCES orders(id),
    purchase_order_id BIGINT REFERENCES purchase_orders(id),
    payment_id        BIGINT REFERENCES payments(id),
    note              TEXT,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((party_type = 'CUSTOMER' AND customer_id IS NOT NULL AND supplier_id IS NULL)
        OR (party_type = 'SUPPLIER' AND supplier_id IS NOT NULL AND customer_id IS NULL))
);
CREATE INDEX idx_debt_customer ON debt_entries (customer_id, created_at DESC);
CREATE INDEX idx_debt_supplier ON debt_entries (supplier_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 9. QUET + THONG BAO
-- ---------------------------------------------------------------------
-- Moi lan nhan vien quet (barcode hoac anh) la 1 dong. confirmed_variant_id la
-- san pham nhan vien chon dung, dung de do do chinh xac va cai thien nhan dien anh.
CREATE TABLE scan_events (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT REFERENCES users(id),
    store_id             BIGINT REFERENCES stores(id),
    mode                 TEXT NOT NULL CHECK (mode IN ('BARCODE', 'PHOTO')),
    raw_value            TEXT,                          -- ma vach doc duoc
    found                BOOLEAN NOT NULL,
    matched_variant_id   BIGINT REFERENCES product_variants(id),
    top_candidates       JSONB,                         -- [{variantId, score}, ...] khi quet anh
    confidence           REAL,
    confirmed_variant_id BIGINT REFERENCES product_variants(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_scan_events_user ON scan_events (user_id, created_at DESC);

CREATE TABLE notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    title       TEXT NOT NULL,
    body        TEXT,
    entity_type TEXT,
    entity_id   BIGINT,
    is_read     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, is_read, created_at DESC);

-- ---------------------------------------------------------------------
-- 10. DU LIEU KHOI TAO (role, quyen, cua hang mac dinh)
-- ---------------------------------------------------------------------
INSERT INTO stores (code, name) VALUES ('MAIN', 'Cua hang chinh');

INSERT INTO permissions (code, module, description) VALUES
    ('scan.use',         'scan',     'Quet ma vach / anh va xem chi tiet san pham'),
    ('product.view',     'product',  'Xem san pham'),
    ('product.manage',   'product',  'Them, sua, xoa san pham, bien the, gia, anh'),
    ('stock.view',       'stock',    'Xem ton kho'),
    ('stock.adjust',     'stock',    'Nhap kho, kiem kho, dieu chinh ton'),
    ('order.view',       'order',    'Xem don hang'),
    ('order.create',     'order',    'Tao va cap nhat don hang'),
    ('order.cancel',     'order',    'Huy don hang'),
    ('customer.view',    'customer', 'Xem khach hang'),
    ('customer.manage',  'customer', 'Them, sua khach hang, han muc no'),
    ('supplier.manage',  'supplier', 'Quan ly nha cung cap va nhap hang'),
    ('debt.view',        'debt',     'Xem cong no'),
    ('debt.collect',     'debt',     'Thu no, ghi nhan thanh toan'),
    ('user.manage',      'user',     'Quan ly nhan vien'),
    ('role.manage',      'role',     'Quan ly vai tro va phan quyen'),
    ('report.view',      'report',   'Xem bao cao thong ke');

INSERT INTO roles (code, name, is_system) VALUES
    ('ADMIN',    'Quan tri vien', TRUE),
    ('EMPLOYEE', 'Nhan vien',     TRUE);

-- ADMIN: tat ca quyen
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

-- EMPLOYEE: quet, xem san pham/ton kho, tao don, xem khach
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('scan.use', 'product.view', 'stock.view', 'order.view', 'order.create', 'customer.view')
WHERE r.code = 'EMPLOYEE';
