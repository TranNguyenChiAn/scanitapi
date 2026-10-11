package com.scanit.api.media;

import com.scanit.api.catalog.MediaUrls;
import com.scanit.api.common.ApiException;
import com.scanit.api.common.AuditLog;
import com.scanit.api.media.ImageProcessor.Processed;
import com.scanit.api.media.MediaDtos.MediaItem;
import com.scanit.api.media.MediaDtos.MediaPatch;
import com.scanit.api.media.MediaDtos.ReindexResult;
import com.scanit.api.recognition.EmbeddingRepository;
import com.scanit.api.recognition.RecognitionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final int MAX_PHOTOS_PER_PRODUCT = 40;

    private final JdbcClient jdbc;
    private final MediaStorage storage;
    private final ImageProcessor processor;
    private final MediaUrls urls;
    private final EmbeddingRepository embeddings;
    private final RecognitionClient recognizer;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    public MediaService(JdbcClient jdbc, MediaStorage storage, ImageProcessor processor, MediaUrls urls,
                        EmbeddingRepository embeddings, RecognitionClient recognizer, AuditLog audit,
                        PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.processor = processor;
        this.urls = urls;
        this.embeddings = embeddings;
        this.recognizer = recognizer;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    private static final String ITEM_SQL = """
            SELECT pm.id, pm.color_id, pm.is_primary, pm.sort_order, ma.storage_provider, ma.object_key,
                   ma.width, ma.height, ma.size_bytes
            FROM product_media pm JOIN media_assets ma ON ma.id = pm.asset_id
            """;

    private MediaItem mapItem(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long color = rs.getLong("color_id");
        Long colorId = rs.wasNull() ? null : color;
        String provider = rs.getString("storage_provider");
        String key = rs.getString("object_key");
        return new MediaItem(rs.getLong("id"), colorId, urls.url(provider, key), urls.thumbUrl(provider, key),
                rs.getBoolean("is_primary"), rs.getInt("sort_order"), rs.getInt("width"), rs.getInt("height"),
                rs.getInt("size_bytes"));
    }

    public List<MediaItem> list(long productId) {
        requireProduct(productId);
        return jdbc.sql(ITEM_SQL + " WHERE pm.product_id = :p ORDER BY (pm.color_id IS NULL) DESC, pm.color_id, pm.sort_order, pm.id")
                .param("p", productId).query(this::mapItem).list();
    }

    /** Xu ly + luu anh + gan vao san pham (+ mau) + tinh vector nhan dien (loi vector khong lam hong viec upload). */
    public MediaItem upload(long productId, Long colorId, byte[] bytes, long userId) {
        requireProduct(productId);
        if (colorId != null) {
            boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM colors WHERE id = :c)").param("c", colorId)
                    .query(Boolean.class).single();
            if (!ok) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Màu không tồn tại.");
        }
        long count = jdbc.sql("SELECT count(*) FROM product_media WHERE product_id = :p").param("p", productId)
                .query(Long.class).single();
        if (count >= MAX_PHOTOS_PER_PRODUCT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION",
                    "Mỗi sản phẩm tối đa " + MAX_PHOTOS_PER_PRODUCT + " ảnh.");
        }

        Processed p = processor.process(bytes);
        long assetId = ensureAsset(p, userId);
        long linkId = link(productId, colorId, assetId);
        embedQuietly(assetId, p.thumb());
        audit.record(userId, "UPLOAD", "product_media", linkId,
                Map.of("productId", productId, "assetId", assetId, "bytes", p.full().length));
        return jdbc.sql(ITEM_SQL + " WHERE pm.id = :id").param("id", linkId).query(this::mapItem).single();
    }

    private long ensureAsset(Processed p, long userId) {
        var existing = jdbc.sql("SELECT id FROM media_assets WHERE sha256 = :h").param("h", p.sha256())
                .query(Long.class).optional();
        if (existing.isPresent()) return existing.get(); // cung anh da co: khong luu lai

        String key = "m/" + p.sha256() + ".webp";
        String thumbKey = MediaUrls.thumbKey(key);
        try {
            storage.put(key, p.full(), "image/webp");
            storage.put(thumbKey, p.thumb(), "image/webp");
        } catch (IOException e) {
            log.error("Luu anh that bai", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE", "Không lưu được ảnh lên kho lưu trữ. Thử lại sau.");
        }
        try {
            return jdbc.sql("""
                            INSERT INTO media_assets (storage_provider, object_key, format, width, height, size_bytes, sha256, created_by)
                            VALUES (:prov, :key, 'webp', :w, :h, :size, :sha, :u) RETURNING id
                            """)
                    .param("prov", storage.provider()).param("key", key).param("w", p.width()).param("h", p.height())
                    .param("size", p.full().length).param("sha", p.sha256()).param("u", userId)
                    .query(Long.class).single();
        } catch (DuplicateKeyException race) { // hai nguoi cung up 1 anh cung luc
            return jdbc.sql("SELECT id FROM media_assets WHERE sha256 = :h").param("h", p.sha256())
                    .query(Long.class).single();
        }
    }

    private long link(long productId, Long colorId, long assetId) {
        var existing = jdbc.sql("""
                        SELECT id FROM product_media
                        WHERE product_id = :p AND color_id IS NOT DISTINCT FROM CAST(:c AS bigint) AND asset_id = :a
                        """)
                .param("p", productId).param("c", colorId, Types.BIGINT).param("a", assetId)
                .query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        return jdbc.sql("""
                        INSERT INTO product_media (product_id, color_id, asset_id, sort_order, is_primary)
                        SELECT :p, CAST(:c AS bigint), :a, COALESCE(max(sort_order), -1) + 1,
                               NOT EXISTS (SELECT 1 FROM product_media x WHERE x.product_id = :p
                                           AND x.color_id IS NOT DISTINCT FROM CAST(:c AS bigint) AND x.is_primary)
                        FROM product_media WHERE product_id = :p AND color_id IS NOT DISTINCT FROM CAST(:c AS bigint)
                        RETURNING id
                        """)
                .param("p", productId).param("c", colorId, Types.BIGINT).param("a", assetId)
                .query(Long.class).single();
    }

    private void embedQuietly(long assetId, byte[] thumb) {
        try {
            if (!embeddings.available()) return;
            var e = recognizer.embed(thumb, "photo.webp");
            embeddings.save(assetId, e.model(), e.vector());
        } catch (Exception e) {
            log.warn("Chua tinh duoc vector nhan dien cho anh {} (se bo sung bang reindex): {}", assetId, e.getMessage());
        }
    }

    public MediaItem patch(long productId, long mediaId, MediaPatch patch, long userId) {
        tx.executeWithoutResult(s -> {
            var row = jdbc.sql("SELECT color_id FROM product_media WHERE id = :id AND product_id = :p FOR UPDATE")
                    .param("id", mediaId).param("p", productId)
                    .query((rs, n) -> {
                        long c = rs.getLong(1);
                        return new Object[]{rs.wasNull() ? null : c};
                    }).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy ảnh."));
            Long colorId = (Long) row[0];
            if (patch.sortOrder() != null) {
                jdbc.sql("UPDATE product_media SET sort_order = :s WHERE id = :id")
                        .param("s", patch.sortOrder()).param("id", mediaId).update();
            }
            if (Boolean.TRUE.equals(patch.primary())) {
                jdbc.sql("""
                                UPDATE product_media SET is_primary = FALSE
                                WHERE product_id = :p AND color_id IS NOT DISTINCT FROM CAST(:c AS bigint) AND is_primary
                                """)
                        .param("p", productId).param("c", colorId, Types.BIGINT).update();
                jdbc.sql("UPDATE product_media SET is_primary = TRUE WHERE id = :id").param("id", mediaId).update();
            }
        });
        audit.record(userId, "UPDATE", "product_media", mediaId, Map.of("productId", productId));
        return jdbc.sql(ITEM_SQL + " WHERE pm.id = :id").param("id", mediaId).query(this::mapItem).single();
    }

    private record Orphan(String provider, String key) {
    }

    public void delete(long productId, long mediaId, long userId) {
        Orphan orphan = tx.execute(s -> {
            var row = jdbc.sql("SELECT color_id, asset_id, is_primary FROM product_media WHERE id = :id AND product_id = :p FOR UPDATE")
                    .param("id", mediaId).param("p", productId)
                    .query((rs, n) -> {
                        long c = rs.getLong(1);
                        Long color = rs.wasNull() ? null : c;
                        return new Object[]{color, rs.getLong(2), rs.getBoolean(3)};
                    }).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy ảnh."));
            Long colorId = (Long) row[0];
            long assetId = (Long) row[1];
            boolean wasPrimary = (Boolean) row[2];

            jdbc.sql("DELETE FROM product_media WHERE id = :id").param("id", mediaId).update();
            if (wasPrimary) { // chon anh khac lam anh chinh cua nhom
                jdbc.sql("""
                                UPDATE product_media SET is_primary = TRUE WHERE id = (
                                    SELECT id FROM product_media
                                    WHERE product_id = :p AND color_id IS NOT DISTINCT FROM CAST(:c AS bigint)
                                    ORDER BY sort_order, id LIMIT 1)
                                """)
                        .param("p", productId).param("c", colorId, Types.BIGINT).update();
            }
            long refs = jdbc.sql("SELECT count(*) FROM product_media WHERE asset_id = :a").param("a", assetId)
                    .query(Long.class).single();
            if (refs > 0) return null; // anh con dung o san pham khac (trung noi dung)
            Orphan o = jdbc.sql("SELECT storage_provider, object_key FROM media_assets WHERE id = :a").param("a", assetId)
                    .query((rs, n) -> new Orphan(rs.getString(1), rs.getString(2))).single();
            jdbc.sql("DELETE FROM media_assets WHERE id = :a").param("a", assetId).update(); // vector xoa theo (CASCADE)
            return o;
        });
        if (orphan != null && storage.provider().equals(orphan.provider())) {
            for (String k : new String[]{orphan.key(), MediaUrls.thumbKey(orphan.key())}) {
                if (k == null) continue;
                try {
                    storage.delete(k);
                } catch (IOException e) {
                    log.warn("Khong xoa duoc file {} tren kho luu tru: {}", k, e.getMessage());
                }
            }
        }
        audit.record(userId, "DELETE", "product_media", mediaId, Map.of("productId", productId));
    }

    /** Tinh vector cho anh chua co (hoac tinh lai tat ca khi doi model). Moi lan toi da `limit` anh. */
    public ReindexResult reindex(boolean all, int limit) {
        if (!embeddings.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PGVECTOR_MISSING",
                    "Chưa bật pgvector trong database (xem README: CREATE EXTENSION vector).");
        }
        List<EmbeddingRepository.PendingAsset> batch = embeddings.pendingAssets(storage.provider(), all, limit);
        int ok = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (var a : batch) {
            try {
                String thumbKey = MediaUrls.thumbKey(a.objectKey());
                if (thumbKey == null) continue;
                var e = recognizer.embed(storage.get(thumbKey), "photo.webp");
                embeddings.save(a.id(), e.model(), e.vector());
                ok++;
            } catch (ApiException e) {
                if ("RECOGNIZER_DOWN".equals(e.code())) throw e; // dich vu tat: dung som
                failed++;
            } catch (Exception e) {
                failed++;
                errors.add(a.id() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) log.warn("Reindex loi: {}", errors);
        int remaining = embeddings.pendingAssets(storage.provider(), false, 100000).size();
        return new ReindexResult(ok, failed, remaining);
    }

    private void requireProduct(long productId) {
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM products WHERE id = :p AND deleted_at IS NULL)")
                .param("p", productId).query(Boolean.class).single();
        if (!ok) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy sản phẩm.");
    }
}
