-- Da cua hang (multi-tenant): moi cua hang (shop) co du lieu rieng, dung chung 1 database.
-- Du lieu hien co duoc gan cho shop dau tien (mei-bee).
-- "stores" la CHI NHANH vat ly cua 1 shop; "shops" la khach hang thue he thong.

CREATE TABLE shops (
    id         BIGINT PRIMARY KEY,                       -- Snowflake do ung dung sinh
    code       TEXT NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,30}$'),   -- ma cua hang khi dang nhap, vd mei-bee
    name       TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO shops (id, code, name, status) VALUES (4000000000000000001, 'mei-bee', 'MEI.BEE studio', 'ACTIVE');

-- Bo dem theo shop (vd ma vach noi bo). Moi shop 1 day so rieng, tang bang UPDATE ... RETURNING (khoa dong, khong trung)
CREATE TABLE shop_sequences (
    shop_id BIGINT NOT NULL REFERENCES shops(id),
    name    TEXT NOT NULL,
    value   BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id, name)
);

-- Ma vach noi bo cu duoc sinh tu id cu (200 + 9 chu so); tiep tuc tu so lon nhat da dung
INSERT INTO shop_sequences (shop_id, name, value)
SELECT 4000000000000000001, 'barcode',
       COALESCE(max(CAST(substring(barcode FROM 4 FOR 9) AS bigint)), 0)
FROM product_variants WHERE barcode ~ '^200[0-9]{10}$';

ALTER TABLE stores           ADD COLUMN shop_id BIGINT;
ALTER TABLE users            ADD COLUMN shop_id BIGINT;
ALTER TABLE brands           ADD COLUMN shop_id BIGINT;
ALTER TABLE categories       ADD COLUMN shop_id BIGINT;
ALTER TABLE colors           ADD COLUMN shop_id BIGINT;
ALTER TABLE sizes            ADD COLUMN shop_id BIGINT;
ALTER TABLE products         ADD COLUMN shop_id BIGINT;
ALTER TABLE product_variants ADD COLUMN shop_id BIGINT;
ALTER TABLE media_assets     ADD COLUMN shop_id BIGINT;
ALTER TABLE stock_levels     ADD COLUMN shop_id BIGINT;
ALTER TABLE stock_movements  ADD COLUMN shop_id BIGINT;
ALTER TABLE scan_events      ADD COLUMN shop_id BIGINT;
ALTER TABLE audit_logs       ADD COLUMN shop_id BIGINT;
ALTER TABLE customers        ADD COLUMN shop_id BIGINT;
ALTER TABLE suppliers        ADD COLUMN shop_id BIGINT;
ALTER TABLE orders           ADD COLUMN shop_id BIGINT;
ALTER TABLE purchase_orders  ADD COLUMN shop_id BIGINT;
ALTER TABLE payments         ADD COLUMN shop_id BIGINT;
ALTER TABLE debt_entries     ADD COLUMN shop_id BIGINT;

UPDATE stores           SET shop_id = 4000000000000000001;
UPDATE users            SET shop_id = 4000000000000000001;
UPDATE brands           SET shop_id = 4000000000000000001;
UPDATE categories       SET shop_id = 4000000000000000001;
UPDATE colors           SET shop_id = 4000000000000000001;
UPDATE sizes            SET shop_id = 4000000000000000001;
UPDATE products         SET shop_id = 4000000000000000001;
UPDATE product_variants SET shop_id = 4000000000000000001;
UPDATE media_assets     SET shop_id = 4000000000000000001;
UPDATE stock_levels     SET shop_id = 4000000000000000001;
UPDATE stock_movements  SET shop_id = 4000000000000000001;
UPDATE scan_events      SET shop_id = 4000000000000000001;
UPDATE audit_logs       SET shop_id = 4000000000000000001;
UPDATE customers        SET shop_id = 4000000000000000001;
UPDATE suppliers        SET shop_id = 4000000000000000001;
UPDATE orders           SET shop_id = 4000000000000000001;
UPDATE purchase_orders  SET shop_id = 4000000000000000001;
UPDATE payments         SET shop_id = 4000000000000000001;
UPDATE debt_entries     SET shop_id = 4000000000000000001;

ALTER TABLE stores           ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE users            ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE brands           ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE categories       ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE colors           ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE sizes            ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE products         ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE product_variants ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE media_assets     ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE stock_levels     ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE stock_movements  ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE scan_events      ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE audit_logs       ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE customers        ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE suppliers        ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE orders           ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE purchase_orders  ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE payments         ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE debt_entries     ALTER COLUMN shop_id SET NOT NULL;

ALTER TABLE stores           ADD CONSTRAINT fk_stores_shop           FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE users            ADD CONSTRAINT fk_users_shop            FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE brands           ADD CONSTRAINT fk_brands_shop           FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE categories       ADD CONSTRAINT fk_categories_shop       FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE colors           ADD CONSTRAINT fk_colors_shop           FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE sizes            ADD CONSTRAINT fk_sizes_shop            FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE products         ADD CONSTRAINT fk_products_shop         FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE product_variants ADD CONSTRAINT fk_variants_shop         FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE media_assets     ADD CONSTRAINT fk_media_assets_shop     FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE stock_levels     ADD CONSTRAINT fk_stock_levels_shop     FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE stock_movements  ADD CONSTRAINT fk_stock_movements_shop  FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE scan_events      ADD CONSTRAINT fk_scan_events_shop      FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE audit_logs       ADD CONSTRAINT fk_audit_logs_shop       FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE customers        ADD CONSTRAINT fk_customers_shop        FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE suppliers        ADD CONSTRAINT fk_suppliers_shop        FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE orders           ADD CONSTRAINT fk_orders_shop           FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE purchase_orders  ADD CONSTRAINT fk_purchase_orders_shop  FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE payments         ADD CONSTRAINT fk_payments_shop         FOREIGN KEY (shop_id) REFERENCES shops(id);
ALTER TABLE debt_entries     ADD CONSTRAINT fk_debt_entries_shop     FOREIGN KEY (shop_id) REFERENCES shops(id);

-- Rang buoc duy nhat: tu "toan he thong" thanh "trong tung shop"
ALTER TABLE stores           DROP CONSTRAINT stores_code_key;
ALTER TABLE users            DROP CONSTRAINT users_username_key;
ALTER TABLE users            DROP CONSTRAINT users_email_key;
ALTER TABLE users            DROP CONSTRAINT users_phone_key;
ALTER TABLE brands           DROP CONSTRAINT brands_name_key;
ALTER TABLE categories       DROP CONSTRAINT categories_slug_key;
ALTER TABLE colors           DROP CONSTRAINT colors_name_key;
ALTER TABLE sizes            DROP CONSTRAINT sizes_name_key;
ALTER TABLE products         DROP CONSTRAINT products_style_code_key;
ALTER TABLE product_variants DROP CONSTRAINT product_variants_sku_key;
ALTER TABLE product_variants DROP CONSTRAINT product_variants_barcode_key;
ALTER TABLE media_assets     DROP CONSTRAINT media_assets_sha256_key;
ALTER TABLE customers        DROP CONSTRAINT customers_code_key;
ALTER TABLE customers        DROP CONSTRAINT customers_phone_key;
ALTER TABLE suppliers        DROP CONSTRAINT suppliers_phone_key;
ALTER TABLE orders           DROP CONSTRAINT orders_code_key;
ALTER TABLE purchase_orders  DROP CONSTRAINT purchase_orders_code_key;

CREATE UNIQUE INDEX uq_stores_shop_code          ON stores (shop_id, code);
CREATE UNIQUE INDEX uq_users_shop_username       ON users (shop_id, lower(username));
CREATE UNIQUE INDEX uq_users_shop_email          ON users (shop_id, lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_shop_phone          ON users (shop_id, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_brands_shop_name          ON brands (shop_id, lower(name));
CREATE UNIQUE INDEX uq_categories_shop_slug      ON categories (shop_id, slug);
CREATE UNIQUE INDEX uq_colors_shop_name          ON colors (shop_id, lower(name));
CREATE UNIQUE INDEX uq_sizes_shop_name           ON sizes (shop_id, lower(name));
CREATE UNIQUE INDEX uq_products_shop_style       ON products (shop_id, lower(style_code));
CREATE UNIQUE INDEX uq_variants_shop_sku         ON product_variants (shop_id, lower(sku));
CREATE UNIQUE INDEX uq_variants_shop_barcode     ON product_variants (shop_id, barcode) WHERE barcode IS NOT NULL;
CREATE UNIQUE INDEX uq_media_assets_shop_sha     ON media_assets (shop_id, sha256);
CREATE UNIQUE INDEX uq_customers_shop_code       ON customers (shop_id, code) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX uq_customers_shop_phone      ON customers (shop_id, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_suppliers_shop_phone      ON suppliers (shop_id, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_orders_shop_code          ON orders (shop_id, code);
CREATE UNIQUE INDEX uq_purchase_orders_shop_code ON purchase_orders (shop_id, code);

-- Chi muc cho cac truy van hay gap: shop_id luon dung dau de loc theo shop truoc
DROP INDEX idx_variants_barcode_norm;
CREATE INDEX idx_variants_shop_barcode_norm ON product_variants (shop_id, ltrim(barcode, '0'));
CREATE INDEX idx_products_shop_created      ON products (shop_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_media_assets_shop          ON media_assets (shop_id);
CREATE INDEX idx_stock_movements_shop       ON stock_movements (shop_id, created_at DESC);
CREATE INDEX idx_scan_events_shop           ON scan_events (shop_id, created_at DESC);
CREATE INDEX idx_audit_shop                 ON audit_logs (shop_id, created_at DESC);
CREATE INDEX idx_orders_shop                ON orders (shop_id, ordered_at DESC);
CREATE INDEX idx_customers_shop             ON customers (shop_id);
