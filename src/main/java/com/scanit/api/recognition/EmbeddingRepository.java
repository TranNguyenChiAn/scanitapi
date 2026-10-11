package com.scanit.api.recognition;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EmbeddingRepository {

    public record Hit(long productId, Long colorId, double distance) {
    }

    public record PendingAsset(long id, String objectKey) {
    }

    private final JdbcClient jdbc;

    public EmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Bang image_embeddings chi co khi pgvector da duoc bat (changeset 002). */
    public boolean available() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT to_regclass('image_embeddings') IS NOT NULL")
                .query(Boolean.class).single());
    }

    public void save(long assetId, String model, float[] vector) {
        jdbc.sql("""
                        INSERT INTO image_embeddings (asset_id, model, embedding)
                        VALUES (:a, :m, CAST(:v AS vector))
                        ON CONFLICT (asset_id, model) DO UPDATE SET embedding = EXCLUDED.embedding
                        """)
                .param("a", assetId).param("m", model).param("v", literal(vector))
                .update();
    }

    /**
     * Lay `limit` anh gan nhat theo khoang cach cosine (dung chi muc HNSW), roi gop theo (san pham, mau).
     * Khoang cach 0 = giong het; score = 1 - khoang cach.
     */
    public List<Hit> nearest(float[] query, String model, int limit) {
        return jdbc.sql("""
                        WITH nn AS (
                            SELECT asset_id, embedding <=> CAST(:q AS vector) AS dist
                            FROM image_embeddings
                            WHERE model = :m
                            ORDER BY embedding <=> CAST(:q AS vector)
                            LIMIT 40
                        )
                        SELECT pm.product_id, pm.color_id, min(nn.dist) AS dist
                        FROM nn
                        JOIN product_media pm ON pm.asset_id = nn.asset_id
                        JOIN products p ON p.id = pm.product_id
                        WHERE p.deleted_at IS NULL AND p.status = 'ACTIVE'
                        GROUP BY pm.product_id, pm.color_id
                        ORDER BY dist
                        LIMIT :k
                        """)
                .param("q", literal(query)).param("m", model).param("k", limit)
                .query((rs, n) -> {
                    long color = rs.getLong("color_id");
                    Long colorId = rs.wasNull() ? null : color;
                    return new Hit(rs.getLong("product_id"), colorId, rs.getDouble("dist"));
                })
                .list();
    }

    public List<PendingAsset> pendingAssets(String provider, boolean all, int limit) {
        return jdbc.sql("""
                        SELECT ma.id, ma.object_key FROM media_assets ma
                        WHERE ma.storage_provider = :p
                          AND (:all OR NOT EXISTS (SELECT 1 FROM image_embeddings ie WHERE ie.asset_id = ma.id))
                        ORDER BY ma.id LIMIT :limit
                        """)
                .param("p", provider).param("all", all).param("limit", limit)
                .query((rs, n) -> new PendingAsset(rs.getLong(1), rs.getString(2)))
                .list();
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
