-- TUY CHON: chi chay khi Postgres da cai pgvector (image `pgvector/pgvector:pg16` hoac `CREATE EXTENSION vector`).
-- De bat: them `classpath:db/optional` vao spring.flyway.locations.
CREATE EXTENSION IF NOT EXISTS vector;

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

