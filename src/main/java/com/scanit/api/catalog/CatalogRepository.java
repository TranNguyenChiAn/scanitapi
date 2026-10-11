package com.scanit.api.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanit.api.catalog.CatalogDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final MediaUrls media;

    public CatalogRepository(JdbcClient jdbc, ObjectMapper mapper, MediaUrls media) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.media = media;
    }

    public long defaultStoreId() {
        return jdbc.sql("SELECT id FROM stores WHERE is_active ORDER BY id LIMIT 1").query(Long.class).single();
    }

    /**
     * Tim chinh xac theo ma vach. Bo so 0 o dau de khop UPC-A / EAN-13 (iOS bao UPC-A thanh EAN-13).
     * Uu tien khop nguyen van truoc.
     */
    public Optional<Long> findVariantIdByBarcode(String code) {
        String normalized = code.replaceFirst("^0+", "");
        return jdbc.sql("""
                        SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id
                        WHERE v.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.status <> 'INACTIVE'
                          AND (v.barcode = :code OR (:norm <> '' AND ltrim(v.barcode, '0') = :norm))
                        ORDER BY (v.barcode = :code) DESC
                        LIMIT 1
                        """)
                .param("code", code).param("norm", normalized)
                .query(Long.class).optional();
    }

    private record Head(long variantId, String sku, String barcode, String status, long productId, long colorId,
                        String colorName, String colorHex, long sizeId, String sizeName, long costPrice) {
    }

    public Optional<VariantDetail> loadDetail(long variantId, long storeId, boolean includeCost) {
        Optional<Head> head = jdbc.sql("""
                        SELECT v.id, v.sku, v.barcode, v.status, v.product_id, v.cost_price,
                               c.id AS color_id, c.name AS color_name, c.hex_code,
                               s.id AS size_id, s.name AS size_name
                        FROM product_variants v
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        WHERE v.id = :id
                        """)
                .param("id", variantId)
                .query((rs, n) -> new Head(rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                        rs.getString("status"), rs.getLong("product_id"), rs.getLong("color_id"),
                        rs.getString("color_name"), rs.getString("hex_code"), rs.getLong("size_id"),
                        rs.getString("size_name"), rs.getLong("cost_price")))
                .optional();
        if (head.isEmpty()) return Optional.empty();
        Head h = head.get();

        ProductInfo product = jdbc.sql("""
                        SELECT p.id, p.style_code, p.name, p.description, p.material, p.season, p.product_type,
                               p.components::text AS components,
                               COALESCE((SELECT array_agg(cat.name ORDER BY cat.name)
                                         FROM product_categories pc JOIN categories cat ON cat.id = pc.category_id
                                         WHERE pc.product_id = p.id), '{}') AS categories
                        FROM products p WHERE p.id = :pid
                        """)
                .param("pid", h.productId())
                .query(this::mapProduct).single();

        List<PriceInfo> prices = jdbc.sql("""
                        SELECT price_type, min_qty, price FROM variant_prices
                        WHERE variant_id = :id ORDER BY price_type, min_qty
                        """)
                .param("id", variantId)
                .query((rs, n) -> new PriceInfo(rs.getString("price_type"), rs.getInt("min_qty"), rs.getLong("price")))
                .list();

        StockInfo stock = jdbc.sql("""
                        SELECT quantity, min_quantity FROM stock_levels WHERE store_id = :store AND variant_id = :id
                        """)
                .param("store", storeId).param("id", variantId)
                .query((rs, n) -> new StockInfo(rs.getInt("quantity"), rs.getInt("min_quantity"),
                        rs.getInt("quantity") <= rs.getInt("min_quantity")))
                .optional().orElse(new StockInfo(0, 0, true));

        List<String> images = jdbc.sql("""
                        SELECT ma.storage_provider, ma.object_key
                        FROM product_media pm JOIN media_assets ma ON ma.id = pm.asset_id
                        WHERE pm.product_id = :pid AND (pm.color_id = :cid OR pm.color_id IS NULL)
                        ORDER BY (pm.color_id IS NULL), pm.is_primary DESC, pm.sort_order, pm.id
                        """)
                .param("pid", h.productId()).param("cid", h.colorId())
                .query((rs, n) -> media.url(rs.getString(1), rs.getString(2)))
                .list().stream().filter(Objects::nonNull).toList();

        List<Sibling> siblings = jdbc.sql("""
                        SELECT v.id, v.sku, v.barcode, c.name AS color, s.name AS size,
                               COALESCE(sl.quantity, 0) AS quantity
                        FROM product_variants v
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        LEFT JOIN stock_levels sl ON sl.variant_id = v.id AND sl.store_id = :store
                        WHERE v.product_id = :pid AND v.status = 'ACTIVE'
                        ORDER BY c.name, s.sort_order, s.name
                        """)
                .param("pid", h.productId()).param("store", storeId)
                .query((rs, n) -> new Sibling(rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                        rs.getString("color"), rs.getString("size"), rs.getInt("quantity")))
                .list();

        return Optional.of(new VariantDetail(h.variantId(), h.sku(), h.barcode(), h.status(), product,
                new ColorInfo(h.colorId(), h.colorName(), h.colorHex()), new SizeInfo(h.sizeId(), h.sizeName()),
                prices, stock, images, siblings, includeCost ? h.costPrice() : null));
    }

    private ProductInfo mapProduct(ResultSet rs, int n) throws SQLException {
        List<String> categories = List.of();
        java.sql.Array arr = rs.getArray("categories");
        if (arr != null) {
            categories = List.of((String[]) arr.getArray());
        }
        return new ProductInfo(rs.getLong("id"), rs.getString("style_code"), rs.getString("name"),
                rs.getString("description"), rs.getString("material"), rs.getString("season"),
                rs.getString("product_type"), parseComponents(rs.getString("components")), categories);
    }

    private List<String> parseComponents(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Tim theo ten, ma mau, SKU hoac ma vach. */
    public PageResult<VariantSummary> search(String q, long storeId, int page, int size) {
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        String where = """
                v.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.status <> 'INACTIVE'
                AND (:q = '' OR p.name ILIKE :like OR p.style_code ILIKE :like OR v.sku ILIKE :like OR v.barcode = :q)
                """;

        long total = jdbc.sql("SELECT count(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE " + where)
                .param("q", q).param("like", like)
                .query(Long.class).single();

        List<VariantSummary> items = jdbc.sql("""
                        SELECT v.id, v.sku, v.barcode, p.name, c.name AS color, s.name AS size,
                               (SELECT price FROM variant_prices vp WHERE vp.variant_id = v.id
                                  AND vp.price_type = 'RETAIL' ORDER BY vp.min_qty LIMIT 1) AS retail_price,
                               COALESCE(sl.quantity, 0) AS quantity,
                               img.storage_provider, img.object_key
                        FROM product_variants v
                        JOIN products p ON p.id = v.product_id
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        LEFT JOIN stock_levels sl ON sl.variant_id = v.id AND sl.store_id = :store
                        LEFT JOIN LATERAL (
                            SELECT ma.storage_provider, ma.object_key
                            FROM product_media pm JOIN media_assets ma ON ma.id = pm.asset_id
                            WHERE pm.product_id = p.id AND (pm.color_id = v.color_id OR pm.color_id IS NULL)
                            ORDER BY (pm.color_id IS NULL), pm.is_primary DESC, pm.sort_order, pm.id
                            LIMIT 1
                        ) img ON TRUE
                        WHERE\s""" + where + """
                        ORDER BY p.name, c.name, s.sort_order
                        LIMIT :limit OFFSET :offset
                        """)
                .param("q", q).param("like", like).param("store", storeId)
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> {
                    long price = rs.getLong("retail_price");
                    Long retail = rs.wasNull() ? null : price;
                    return new VariantSummary(rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                            rs.getString("name"), rs.getString("color"), rs.getString("size"), retail,
                            rs.getInt("quantity"), media.url(rs.getString("storage_provider"), rs.getString("object_key")));
                })
                .list();
        return new PageResult<>(items, page, size, total);
    }

    public void logScan(long userId, long storeId, String raw, Long matchedVariantId) {
        jdbc.sql("""
                        INSERT INTO scan_events (user_id, store_id, mode, raw_value, found, matched_variant_id)
                        VALUES (:u, :s, 'BARCODE', :raw, :found, :m)
                        """)
                .param("u", userId).param("s", storeId).param("raw", raw)
                .param("found", matchedVariantId != null)
                .param("m", matchedVariantId, Types.BIGINT)
                .update();
    }
}
