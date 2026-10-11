-- Vector dac trung anh de tim san pham bang camera (CLIP 512 chieu).
-- Can extension pgvector da duoc cai san trong database (xem README: `CREATE EXTENSION vector;` bang tai khoan superuser).
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
