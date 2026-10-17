-- Vector nhan dien gan theo shop de truy van tim anh gan nhat loc duoc ngay trong chi muc (khong tron anh cua shop khac).
-- Chay khi bang image_embeddings ton tai (pgvector bat) nhung chua co shop_id: ke ca khi pgvector duoc cai SAU cac migration khac.
ALTER TABLE image_embeddings ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE image_embeddings ADD COLUMN shop_id BIGINT;
UPDATE image_embeddings e SET shop_id = ma.shop_id FROM media_assets ma WHERE ma.id = e.asset_id;
ALTER TABLE image_embeddings ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE image_embeddings ADD CONSTRAINT fk_image_embeddings_shop FOREIGN KEY (shop_id) REFERENCES shops(id);
CREATE INDEX idx_image_embeddings_shop ON image_embeddings (shop_id, model);
