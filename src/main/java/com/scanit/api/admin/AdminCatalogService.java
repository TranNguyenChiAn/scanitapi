package com.scanit.api.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanit.api.admin.AdminDtos.*;
import com.scanit.api.catalog.CatalogDtos.ColorInfo;
import com.scanit.api.catalog.CatalogDtos.PageResult;
import com.scanit.api.catalog.CatalogDtos.PriceInfo;
import com.scanit.api.catalog.CatalogDtos.SizeInfo;
import com.scanit.api.catalog.MediaUrls;
import com.scanit.api.common.ApiException;
import com.scanit.api.common.AuditLog;
import com.scanit.api.common.Slugs;
import com.scanit.api.media.MediaService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminCatalogService {

    private static final Set<String> GENDERS = Set.of("FEMALE", "MALE", "UNISEX", "KIDS");
    private static final Set<String> PRODUCT_TYPES = Set.of("SINGLE", "SET");
    private static final Set<String> PRODUCT_STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");
    private static final Set<String> VARIANT_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> PRICE_TYPES = Set.of("RETAIL", "WHOLESALE");
    private static final Set<String> STOCK_TYPES = Set.of("PURCHASE", "ADJUSTMENT", "SALE_RETURN", "PURCHASE_RETURN");
    private static final Map<String, Integer> SIZE_ORDER = Map.ofEntries(
            Map.entry("FREESIZE", 0), Map.entry("XXS", 10), Map.entry("XS", 20), Map.entry("S", 30),
            Map.entry("M", 40), Map.entry("L", 50), Map.entry("XL", 60), Map.entry("XXL", 70),
            Map.entry("2XL", 70), Map.entry("XXXL", 80), Map.entry("3XL", 80));

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditLog audit;
    private final MediaService media;
    private final MediaUrls urls;

    public AdminCatalogService(JdbcClient jdbc, ObjectMapper mapper, AuditLog audit, MediaService media, MediaUrls urls) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.media = media;
        this.urls = urls;
    }

    public long defaultStoreId() {
        return jdbc.sql("SELECT id FROM stores WHERE is_active ORDER BY id LIMIT 1").query(Long.class).single();
    }

    // ------------------------------------------------------------------ doc
    public PageResult<ProductListItem> list(String q, String status, int page, int size, long storeId) {
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        String where = """
                p.deleted_at IS NULL AND (:st = '' OR p.status = :st)
                AND (:q = '' OR p.name ILIKE :like OR p.style_code ILIKE :like)
                """;
        long total = jdbc.sql("SELECT count(*) FROM products p WHERE " + where)
                .param("st", st).param("q", q).param("like", like).query(Long.class).single();
        List<ProductListItem> items = jdbc.sql("""
                        SELECT p.id, p.style_code, p.name, p.status, p.product_type,
                               (SELECT count(*) FROM product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS vc,
                               COALESCE((SELECT sum(sl.quantity) FROM product_variants v
                                         JOIN stock_levels sl ON sl.variant_id = v.id AND sl.store_id = :store
                                         WHERE v.product_id = p.id AND v.status = 'ACTIVE'), 0) AS qty,
                               img.storage_provider, img.object_key
                        FROM products p
                        LEFT JOIN LATERAL (
                            SELECT ma.storage_provider, ma.object_key
                            FROM product_media pm JOIN media_assets ma ON ma.id = pm.asset_id
                            WHERE pm.product_id = p.id
                            ORDER BY pm.is_primary DESC, (pm.color_id IS NULL) DESC, pm.sort_order, pm.id
                            LIMIT 1) img ON TRUE
                        WHERE\s""" + where + """
                        ORDER BY p.created_at DESC, p.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("st", st).param("q", q).param("like", like).param("store", storeId)
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new ProductListItem(rs.getLong("id"), rs.getString("style_code"), rs.getString("name"),
                        rs.getString("status"), rs.getString("product_type"), rs.getInt("vc"), rs.getLong("qty"),
                        urls.thumbUrl(rs.getString("storage_provider"), rs.getString("object_key"))))
                .list();
        return new PageResult<>(items, page, size, total);
    }

    public AdminProduct get(long id, long storeId) {
        var head = jdbc.sql("""
                        SELECT p.id, p.style_code, p.name, p.description, b.name AS brand, p.material, p.season,
                               p.target_gender, p.product_type, p.components::text AS components, p.status
                        FROM products p LEFT JOIN brands b ON b.id = p.brand_id
                        WHERE p.id = :id AND p.deleted_at IS NULL
                        """)
                .param("id", id)
                .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getString("style_code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("brand"), rs.getString("material"),
                        rs.getString("season"), rs.getString("target_gender"), rs.getString("product_type"),
                        parseList(rs.getString("components")), rs.getString("status")})
                .optional()
                .orElseThrow(() -> notFound("Không tìm thấy sản phẩm."));
        List<String> categories = jdbc.sql("""
                        SELECT c.name FROM product_categories pc JOIN categories c ON c.id = pc.category_id
                        WHERE pc.product_id = :id ORDER BY c.name
                        """).param("id", id).query(String.class).list();

        Map<Long, List<PriceInfo>> prices = new HashMap<>();
        jdbc.sql("""
                        SELECT vp.variant_id, vp.price_type, vp.min_qty, vp.price
                        FROM variant_prices vp JOIN product_variants v ON v.id = vp.variant_id
                        WHERE v.product_id = :id ORDER BY vp.price_type, vp.min_qty
                        """)
                .param("id", id)
                .query((rs, n) -> {
                    prices.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new PriceInfo(rs.getString(2), rs.getInt(3), rs.getLong(4)));
                    return 0;
                }).list();

        List<AdminVariant> variants = jdbc.sql("""
                        SELECT v.id, v.sku, v.barcode, v.status, v.cost_price,
                               c.id AS color_id, c.name AS color_name, c.hex_code,
                               s.id AS size_id, s.name AS size_name,
                               COALESCE(sl.quantity, 0) AS quantity, COALESCE(sl.min_quantity, 0) AS min_quantity
                        FROM product_variants v
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        LEFT JOIN stock_levels sl ON sl.variant_id = v.id AND sl.store_id = :store
                        WHERE v.product_id = :id
                        ORDER BY (v.status = 'ACTIVE') DESC, c.name, s.sort_order, s.name
                        """)
                .param("id", id).param("store", storeId)
                .query((rs, n) -> new AdminVariant(rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                        rs.getString("status"),
                        new ColorInfo(rs.getLong("color_id"), rs.getString("color_name"), rs.getString("hex_code")),
                        new SizeInfo(rs.getLong("size_id"), rs.getString("size_name")),
                        rs.getLong("cost_price"), prices.getOrDefault(rs.getLong("id"), List.of()),
                        rs.getInt("quantity"), rs.getInt("min_quantity")))
                .list();

        @SuppressWarnings("unchecked")
        List<String> components = (List<String>) head[9];
        return new AdminProduct((Long) head[0], (String) head[1], (String) head[2], (String) head[3], (String) head[4],
                (String) head[5], (String) head[6], (String) head[7], (String) head[8], components, (String) head[10],
                categories, variants, media.list(id));
    }

    // --------------------------------------------------------------- product
    @Transactional
    public long create(ProductRequest r, long userId, long storeId) {
        String style = r.styleCode().trim();
        if (style.contains("~del")) throw bad("Mã mẫu không hợp lệ.");
        assertStyleFree(style, null);
        String type = oneOf(r.productType(), PRODUCT_TYPES, "SINGLE", "Loại sản phẩm");
        long id = jdbc.sql("""
                        INSERT INTO products (style_code, name, description, brand_id, material, season, target_gender,
                                              product_type, components, status, created_by)
                        VALUES (:style, :name, :desc, :brand, :material, :season, :gender, :type,
                                CAST(:components AS jsonb), :status, :u)
                        RETURNING id
                        """)
                .param("style", style).param("name", r.name().trim())
                .param("desc", blankToNull(r.description()), Types.VARCHAR)
                .param("brand", upsertBrand(r.brand()), Types.BIGINT)
                .param("material", blankToNull(r.material()), Types.VARCHAR)
                .param("season", blankToNull(r.season()), Types.VARCHAR)
                .param("gender", optionalOneOf(r.targetGender(), GENDERS, "Đối tượng"), Types.VARCHAR)
                .param("type", type)
                .param("components", json(r.components()), Types.VARCHAR)
                .param("status", oneOf(r.status(), PRODUCT_STATUSES, "ACTIVE", "Trạng thái"))
                .param("u", userId)
                .query(Long.class).single();
        replaceCategories(id, r.categories());
        if (r.variants() != null) {
            for (VariantCreate v : r.variants()) createVariant(id, style, v, userId, storeId);
        }
        audit.record(userId, "CREATE", "product", id, Map.of("styleCode", style, "name", r.name().trim()));
        return id;
    }

    @Transactional
    public void update(long id, ProductRequest r, long userId) {
        requireProduct(id);
        String style = r.styleCode().trim();
        if (style.contains("~del")) throw bad("Mã mẫu không hợp lệ.");
        assertStyleFree(style, id);
        int n = jdbc.sql("""
                        UPDATE products SET style_code = :style, name = :name, description = :desc, brand_id = :brand,
                               material = :material, season = :season, target_gender = :gender, product_type = :type,
                               components = CAST(:components AS jsonb), status = :status, updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id).param("style", style).param("name", r.name().trim())
                .param("desc", blankToNull(r.description()), Types.VARCHAR)
                .param("brand", upsertBrand(r.brand()), Types.BIGINT)
                .param("material", blankToNull(r.material()), Types.VARCHAR)
                .param("season", blankToNull(r.season()), Types.VARCHAR)
                .param("gender", optionalOneOf(r.targetGender(), GENDERS, "Đối tượng"), Types.VARCHAR)
                .param("type", oneOf(r.productType(), PRODUCT_TYPES, "SINGLE", "Loại sản phẩm"))
                .param("components", json(r.components()), Types.VARCHAR)
                .param("status", oneOf(r.status(), PRODUCT_STATUSES, "ACTIVE", "Trạng thái"))
                .update();
        if (n == 0) throw notFound("Không tìm thấy sản phẩm.");
        replaceCategories(id, r.categories());
        audit.record(userId, "UPDATE", "product", id, Map.of("styleCode", style, "name", r.name().trim()));
    }

    /** Xoa mem: giu lai lich su kho/don hang. Doi ten ma de co the dung lai ma mau / SKU / ma vach. */
    @Transactional
    public void delete(long id, long userId) {
        requireProduct(id);
        jdbc.sql("""
                        UPDATE product_variants SET status = 'INACTIVE', sku = sku || '~del' || id, barcode = NULL,
                               updated_at = now()
                        WHERE product_id = :id AND sku NOT LIKE '%~del%'
                        """).param("id", id).update();
        jdbc.sql("""
                        UPDATE products SET deleted_at = now(), status = 'INACTIVE', updated_at = now(),
                               style_code = style_code || '~del' || id
                        WHERE id = :id
                        """).param("id", id).update();
        audit.record(userId, "DELETE", "product", id, null);
    }

    // --------------------------------------------------------------- variant
    @Transactional
    public long addVariant(long productId, VariantCreate v, long userId, long storeId) {
        String style = jdbc.sql("SELECT style_code FROM products WHERE id = :id AND deleted_at IS NULL")
                .param("id", productId).query(String.class).optional()
                .orElseThrow(() -> notFound("Không tìm thấy sản phẩm."));
        return createVariant(productId, style, v, userId, storeId);
    }

    private long createVariant(long productId, String styleCode, VariantCreate v, long userId, long storeId) {
        long colorId = upsertColor(v.color(), v.colorHex());
        long sizeId = upsertSize(v.size());
        String color = v.color().trim();
        String size = v.size().trim();
        String sku = blankToNull(v.sku());
        if (sku == null) sku = (Slugs.compactUpper(styleCode) + "-" + Slugs.compactUpper(color) + "-" + Slugs.compactUpper(size));
        sku = sku.trim();
        String barcode = blankToNull(v.barcode());
        long cost = v.costPrice() == null ? 0 : v.costPrice();

        var existing = jdbc.sql("SELECT id, status FROM product_variants WHERE product_id = :p AND color_id = :c AND size_id = :s")
                .param("p", productId).param("c", colorId).param("s", sizeId)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}).optional();
        long id;
        if (existing.isPresent()) {
            if ("ACTIVE".equals(existing.get()[1])) throw conflict("Sản phẩm đã có biến thể màu " + color + " / size " + size + ".");
            id = (Long) existing.get()[0]; // bien the da xoa mem: bat lai thay vi bao trung
            assertSkuFree(sku, id);
            assertBarcodeFree(barcode, id);
            jdbc.sql("UPDATE product_variants SET status = 'ACTIVE', sku = :sku, barcode = :bc, cost_price = :cost, updated_at = now() WHERE id = :id")
                    .param("sku", sku).param("bc", barcode, Types.VARCHAR).param("cost", cost).param("id", id).update();
        } else {
            assertSkuFree(sku, null);
            assertBarcodeFree(barcode, null);
            id = jdbc.sql("""
                            INSERT INTO product_variants (product_id, color_id, size_id, sku, barcode, cost_price)
                            VALUES (:p, :c, :s, :sku, :bc, :cost) RETURNING id
                            """)
                    .param("p", productId).param("c", colorId).param("s", sizeId).param("sku", sku)
                    .param("bc", barcode, Types.VARCHAR).param("cost", cost)
                    .query(Long.class).single();
        }
        if (barcode == null) setGeneratedBarcode(id);
        replacePrices(id, v.prices());

        int min = v.minQuantity() == null ? 0 : v.minQuantity();
        ensureStockRow(storeId, id);
        jdbc.sql("UPDATE stock_levels SET min_quantity = :m WHERE store_id = :s AND variant_id = :v")
                .param("m", min).param("s", storeId).param("v", id).update();
        if (v.quantity() != null && v.quantity() > 0) {
            adjustLocked(id, storeId, userId, v.quantity(), null, "ADJUSTMENT", "Tồn đầu kỳ");
        }
        audit.record(userId, "CREATE", "variant", id, Map.of("sku", sku, "productId", productId));
        return id;
    }

    @Transactional
    public long updateVariant(long variantId, VariantUpdate u, long userId, long storeId) {
        var row = jdbc.sql("SELECT product_id, sku, barcode, cost_price, status FROM product_variants WHERE id = :id AND sku NOT LIKE '%~del%' FOR UPDATE")
                .param("id", variantId)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)})
                .optional().orElseThrow(() -> notFound("Không tìm thấy biến thể."));
        String sku = u.sku() == null ? (String) row[1] : requireNonBlank(u.sku(), "SKU").trim();
        String barcode = (String) row[2];
        boolean regen = false;
        if (u.barcode() != null) {
            barcode = blankToNull(u.barcode());
            regen = barcode == null;
        }
        long cost = u.costPrice() == null ? (Long) row[3] : u.costPrice();
        String status = u.status() == null ? (String) row[4] : oneOf(u.status(), VARIANT_STATUSES, null, "Trạng thái");
        assertSkuFree(sku, variantId);
        assertBarcodeFree(barcode, variantId);
        jdbc.sql("UPDATE product_variants SET sku = :sku, barcode = :bc, cost_price = :cost, status = :st, updated_at = now() WHERE id = :id")
                .param("sku", sku).param("bc", barcode, Types.VARCHAR).param("cost", cost).param("st", status)
                .param("id", variantId).update();
        if (regen) setGeneratedBarcode(variantId);
        if (u.prices() != null) replacePrices(variantId, u.prices());
        if (u.minQuantity() != null) {
            ensureStockRow(storeId, variantId);
            jdbc.sql("UPDATE stock_levels SET min_quantity = :m, updated_at = now() WHERE store_id = :s AND variant_id = :v")
                    .param("m", u.minQuantity()).param("s", storeId).param("v", variantId).update();
        }
        audit.record(userId, "UPDATE", "variant", variantId, Map.of("sku", sku));
        return (Long) row[0];
    }

    @Transactional
    public long deleteVariant(long variantId, long userId) {
        long productId = jdbc.sql("SELECT product_id FROM product_variants WHERE id = :id AND sku NOT LIKE '%~del%' FOR UPDATE")
                .param("id", variantId).query(Long.class).optional()
                .orElseThrow(() -> notFound("Không tìm thấy biến thể."));
        jdbc.sql("UPDATE product_variants SET status = 'INACTIVE', sku = sku || '~del' || id, barcode = NULL, updated_at = now() WHERE id = :id")
                .param("id", variantId).update();
        audit.record(userId, "DELETE", "variant", variantId, null);
        return productId;
    }

    // ----------------------------------------------------------------- stock
    @Transactional
    public StockResult adjustStock(long variantId, StockAdjust a, long userId, long storeId) {
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product_variants WHERE id = :id AND sku NOT LIKE '%~del%')")
                .param("id", variantId).query(Boolean.class).single();
        if (!ok) throw notFound("Không tìm thấy biến thể.");
        if ((a.delta() == null) == (a.setTo() == null)) throw bad("Chỉ nhập một trong hai: delta (nhập/xuất) hoặc setTo (kiểm kho).");
        if (a.delta() != null && a.delta() == 0) throw bad("Số lượng thay đổi phải khác 0.");
        String type = oneOf(a.type(), STOCK_TYPES, "ADJUSTMENT", "Loại");
        ensureStockRow(storeId, variantId);
        return adjustLocked(variantId, storeId, userId, a.delta(), a.setTo(), type, blankToNull(a.note()));
    }

    private StockResult adjustLocked(long variantId, long storeId, long userId, Integer delta, Integer setTo, String type, String note) {
        ensureStockRow(storeId, variantId);
        int current = jdbc.sql("SELECT quantity FROM stock_levels WHERE store_id = :s AND variant_id = :v FOR UPDATE")
                .param("s", storeId).param("v", variantId).query(Integer.class).single();
        long target = setTo != null ? setTo : (long) current + delta;
        if (target < 0) throw conflict("Tồn kho không đủ (hiện có " + current + ").");
        if (target > 1_000_000) throw bad("Số lượng quá lớn.");
        int real = (int) (target - current);
        if (real == 0) return new StockResult(variantId, current, 0);
        jdbc.sql("UPDATE stock_levels SET quantity = :q, updated_at = now() WHERE store_id = :s AND variant_id = :v")
                .param("q", (int) target).param("s", storeId).param("v", variantId).update();
        jdbc.sql("""
                        INSERT INTO stock_movements (store_id, variant_id, movement_type, quantity_delta, balance_after, note, created_by)
                        VALUES (:s, :v, :t, :d, :b, :n, :u)
                        """)
                .param("s", storeId).param("v", variantId).param("t", type).param("d", real).param("b", (int) target)
                .param("n", note, Types.VARCHAR).param("u", userId).update();
        audit.record(userId, "STOCK", "variant", variantId, Map.of("delta", real, "balance", (int) target, "type", type));
        return new StockResult(variantId, (int) target, real);
    }

    private void ensureStockRow(long storeId, long variantId) {
        jdbc.sql("INSERT INTO stock_levels (store_id, variant_id, quantity, min_quantity) VALUES (:s, :v, 0, 0) ON CONFLICT DO NOTHING")
                .param("s", storeId).param("v", variantId).update();
    }

    // ------------------------------------------------------------- reference
    public List<NamedItem> colors() {
        return jdbc.sql("SELECT id, name, hex_code FROM colors ORDER BY name")
                .query((rs, n) -> new NamedItem(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public List<NamedItem> sizes() {
        return jdbc.sql("SELECT id, name, sort_order::text FROM sizes ORDER BY sort_order, name")
                .query((rs, n) -> new NamedItem(rs.getLong(1), rs.getString(2), null)).list();
    }

    public List<NamedItem> categories() {
        return jdbc.sql("SELECT id, name, slug FROM categories ORDER BY name")
                .query((rs, n) -> new NamedItem(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public List<NamedItem> brands() {
        return jdbc.sql("SELECT id, name FROM brands ORDER BY name")
                .query((rs, n) -> new NamedItem(rs.getLong(1), rs.getString(2), null)).list();
    }

    // --------------------------------------------------------------- helpers
    private Long upsertBrand(String name) {
        String n = blankToNull(name);
        if (n == null) return null;
        return jdbc.sql("SELECT id FROM brands WHERE lower(name) = lower(:n)").param("n", n)
                .query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("INSERT INTO brands (name) VALUES (:n) RETURNING id").param("n", n)
                        .query(Long.class).single());
    }

    private long upsertColor(String name, String hex) {
        String n = name.trim();
        String h = blankToNull(hex);
        if (h != null && !h.matches("^#[0-9A-Fa-f]{6}$")) throw bad("Mã màu phải dạng #RRGGBB.");
        var id = jdbc.sql("SELECT id FROM colors WHERE lower(name) = lower(:n)").param("n", n).query(Long.class).optional();
        if (id.isPresent()) {
            if (h != null) jdbc.sql("UPDATE colors SET hex_code = :h WHERE id = :id").param("h", h).param("id", id.get()).update();
            return id.get();
        }
        return jdbc.sql("INSERT INTO colors (name, hex_code) VALUES (:n, :h) RETURNING id")
                .param("n", n).param("h", h, Types.VARCHAR).query(Long.class).single();
    }

    private long upsertSize(String name) {
        String n = name.trim();
        var id = jdbc.sql("SELECT id FROM sizes WHERE lower(name) = lower(:n)").param("n", n).query(Long.class).optional();
        if (id.isPresent()) return id.get();
        Integer known = SIZE_ORDER.get(Slugs.compactUpper(n));
        int order = known != null ? known
                : Math.max(100, jdbc.sql("SELECT COALESCE(max(sort_order), 0) + 1 FROM sizes").query(Integer.class).single());
        return jdbc.sql("INSERT INTO sizes (name, sort_order) VALUES (:n, :o) RETURNING id")
                .param("n", n).param("o", order).query(Long.class).single();
    }

    private void replaceCategories(long productId, List<String> names) {
        jdbc.sql("DELETE FROM product_categories WHERE product_id = :p").param("p", productId).update();
        if (names == null) return;
        for (String raw : new LinkedHashSet<>(names)) {
            String name = blankToNull(raw);
            if (name == null) continue;
            String slug = Slugs.hyphen(name);
            if (slug.isEmpty()) throw bad("Tên danh mục không hợp lệ.");
            long cid = jdbc.sql("SELECT id FROM categories WHERE slug = :s").param("s", slug).query(Long.class).optional()
                    .orElseGet(() -> jdbc.sql("INSERT INTO categories (name, slug) VALUES (:n, :s) RETURNING id")
                            .param("n", name.trim()).param("s", slug).query(Long.class).single());
            jdbc.sql("INSERT INTO product_categories (product_id, category_id) VALUES (:p, :c) ON CONFLICT DO NOTHING")
                    .param("p", productId).param("c", cid).update();
        }
    }

    private void replacePrices(long variantId, List<PriceInput> prices) {
        jdbc.sql("DELETE FROM variant_prices WHERE variant_id = :v").param("v", variantId).update();
        if (prices == null) return;
        Set<String> seen = new HashSet<>();
        for (PriceInput p : prices) {
            String type = oneOf(p.type(), PRICE_TYPES, null, "Loại giá");
            int minQty = p.minQty() == null ? 1 : p.minQty();
            if (p.price() == null) throw bad("Thiếu giá.");
            if (!seen.add(type + ":" + minQty)) throw bad("Trùng bậc giá " + type + " từ " + minQty + " sản phẩm.");
            jdbc.sql("INSERT INTO variant_prices (variant_id, price_type, min_qty, price) VALUES (:v, :t, :q, :p)")
                    .param("v", variantId).param("t", type).param("q", minQty).param("p", p.price()).update();
        }
    }

    /** EAN-13 noi bo: "200" + id 9 chu so + so kiem tra. Dai 200 la dai dung trong cua hang nen khong dung voi ma hang that. */
    private void setGeneratedBarcode(long variantId) {
        jdbc.sql("UPDATE product_variants SET barcode = :b WHERE id = :id")
                .param("b", internalBarcode(variantId)).param("id", variantId).update();
    }

    static String internalBarcode(long id) {
        if (id < 0 || id > 999_999_999L) throw new IllegalArgumentException("id qua lon de sinh ma vach");
        String body = "200" + String.format("%09d", id);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = body.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        return body + ((10 - sum % 10) % 10);
    }

    private void assertStyleFree(String style, Long exceptId) {
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM products WHERE lower(style_code) = lower(:c) AND (CAST(:id AS bigint) IS NULL OR id <> :id))")
                .param("c", style).param("id", exceptId, Types.BIGINT).query(Boolean.class).single();
        if (used) throw conflict("Mã mẫu " + style + " đã tồn tại.");
    }

    private void assertSkuFree(String sku, Long exceptId) {
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product_variants WHERE lower(sku) = lower(:s) AND (CAST(:id AS bigint) IS NULL OR id <> :id))")
                .param("s", sku).param("id", exceptId, Types.BIGINT).query(Boolean.class).single();
        if (used) throw conflict("SKU " + sku + " đã tồn tại.");
    }

    private void assertBarcodeFree(String barcode, Long exceptId) {
        if (barcode == null) return;
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product_variants WHERE barcode = :b AND (CAST(:id AS bigint) IS NULL OR id <> :id))")
                .param("b", barcode).param("id", exceptId, Types.BIGINT).query(Boolean.class).single();
        if (used) throw conflict("Mã vạch " + barcode + " đã gắn với biến thể khác.");
    }

    private void requireProduct(long id) {
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM products WHERE id = :id AND deleted_at IS NULL)")
                .param("id", id).query(Boolean.class).single();
        if (!ok) throw notFound("Không tìm thấy sản phẩm.");
    }

    private String json(List<String> list) {
        if (list == null) return null;
        List<String> clean = list.stream().map(AdminCatalogService::blankToNull).filter(Objects::nonNull)
                .map(String::trim).collect(Collectors.toList());
        if (clean.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw bad("Danh sách thành phần không hợp lệ.");
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String requireNonBlank(String s, String label) {
        if (s == null || s.isBlank()) throw bad("Thiếu " + label + ".");
        return s;
    }

    private static String oneOf(String v, Set<String> allowed, String dflt, String label) {
        if (v == null || v.isBlank()) {
            if (dflt == null) throw bad("Thiếu " + label + ".");
            return dflt;
        }
        String u = v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw bad(label + " không hợp lệ (" + String.join("/", new TreeSet<>(allowed)) + ").");
        return u;
    }

    private static String optionalOneOf(String v, Set<String> allowed, String label) {
        return v == null || v.isBlank() ? null : oneOf(v, allowed, null, label);
    }

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", m);
    }

    private static ApiException conflict(String m) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", m);
    }

    private static ApiException notFound(String m) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", m);
    }
}
