package com.scanit.api.order;

import com.scanit.api.catalog.CatalogDtos.PageResult;
import com.scanit.api.catalog.CatalogRepository;
import com.scanit.api.common.ApiException;
import com.scanit.api.common.AuditLog;
import com.scanit.api.common.IdGenerator;
import com.scanit.api.order.OrderDtos.*;
import com.scanit.api.security.AuthUser;
import com.scanit.api.security.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ban hang tai quay. Don di qua 2 buoc: DON TAM (DRAFT, ma TAM0001, khong tru kho, sua / xoa thoai mai)
 * -> CHOT (COMPLETED, ma DH000001, tru kho trong cung giao dich). Don da chot co the huy (CANCELLED, hoan kho).
 * Thanh toan nhap tay (chua tra / tra mot phan / da tra du), chua noi cong thanh toan.
 * Gia ban lay theo bang gia; chi nguoi co quyen order.price moi tu dat gia tung dong.
 * Gia von chot vao tung dong luc ban de bao cao lai lo khong bi doi khi sua gia von sau nay.
 * Nguoi khong co quyen report.view chi thay don do chinh minh tao.
 */
@Service
public class OrderService {

    private static final Set<String> PAY_STATUSES = Set.of("UNPAID", "PARTIAL", "PAID");
    private static final Set<String> TYPES = Set.of("RETAIL", "WHOLESALE");
    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final AuditLog audit;
    private final CatalogRepository catalog;

    public OrderService(JdbcClient jdbc, IdGenerator ids, AuditLog audit, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.audit = audit;
        this.catalog = catalog;
    }

    private record Pay(String status, long paid) {
    }

    private record Variant(long id, String sku, String name, String color, String size, long cost) {
        String label() {
            return name + " " + color + "/" + size;
        }
    }

    // ------------------------------------------------------------------ don tam
    @Transactional
    public OrderView create(OrderRequest r, AuthUser u) {
        long storeId = u.storeId() != null ? u.storeId() : catalog.defaultStoreId();
        long id = ids.next();
        String code = "TAM" + String.format("%04d", nextSequence("draft"));
        jdbc.sql("""
                        INSERT INTO orders (id, shop_id, code, store_id, staff_id, order_type, status, subtotal, discount_amount,
                                            total_amount, paid_amount, payment_status)
                        VALUES (:id, :shop, :code, :store, :staff, 'RETAIL', 'DRAFT', 0, 0, 0, 0, 'UNPAID')
                        """)
                .param("id", id).param("shop", Tenant.shopId()).param("code", code).param("store", storeId).param("staff", u.id())
                .update();
        writeContent(id, r, u);
        audit.record(u.id(), "CREATE", "order", id, Map.of("code", code));
        if (Boolean.TRUE.equals(r.confirm())) finalizeOrder(id, u);
        return get(id, u);
    }

    /** Luu lai toan bo noi dung don tam (thay het cac dong cu). Chot luon neu confirm = true. */
    @Transactional
    public OrderView save(long id, OrderRequest r, AuthUser u) {
        requireDraft(lockOrder(id, u), "Chỉ sửa được đơn tạm.");
        jdbc.sql("DELETE FROM order_items WHERE order_id = :id").param("id", id).update();
        writeContent(id, r, u);
        audit.record(u.id(), "UPDATE", "order", id, null);
        if (Boolean.TRUE.equals(r.confirm())) finalizeOrder(id, u);
        return get(id, u);
    }

    /** Chot don tam da luu: tru kho, doi ma sang DH... */
    @Transactional
    public OrderView confirm(long id, AuthUser u) {
        requireDraft(lockOrder(id, u), "Đơn này không còn là đơn tạm.");
        finalizeOrder(id, u);
        return get(id, u);
    }

    @Transactional
    public void delete(long id, AuthUser u) {
        var row = lockOrder(id, u);
        requireDraft(row, "Chỉ xoá được đơn tạm. Đơn đã chốt thì huỷ đơn.");
        jdbc.sql("DELETE FROM orders WHERE id = :id AND shop_id = :shop").param("id", id).param("shop", Tenant.shopId()).update();
        audit.record(u.id(), "DELETE", "order", id, Map.of("code", row.code()));
    }

    private void requireDraft(Locked row, String message) {
        if (!"DRAFT".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** Tinh dong hang, tong tien, thanh toan roi ghi vao don tam. Khong dong den kho. */
    private void writeContent(long orderId, OrderRequest r, AuthUser u) {
        long shop = Tenant.shopId();
        String type = r.orderType() == null || r.orderType().isBlank() ? "RETAIL" : r.orderType().trim().toUpperCase();
        if (!TYPES.contains(type)) throw bad("Loại đơn không hợp lệ (RETAIL hoặc WHOLESALE).");
        boolean mayPrice = u.can("order.price");

        // Gop cac dong trung san pham; TreeMap theo id de luon xu ly theo cung thu tu
        TreeMap<Long, Integer> qty = new TreeMap<>();
        Map<Long, Long> override = new java.util.HashMap<>();
        for (var l : r.items() == null ? List.<LineRequest>of() : r.items()) {
            qty.merge(l.variantId(), l.quantity(), Integer::sum);
            if (mayPrice && l.unitPrice() != null) override.putIfAbsent(l.variantId(), l.unitPrice());
        }
        for (int q : qty.values()) if (q > 10000) throw bad("Số lượng một sản phẩm quá lớn.");

        List<Variant> variants = qty.isEmpty() ? List.of() : jdbc.sql("""
                        SELECT v.id, v.sku, p.name, c.name AS color, s.name AS size, v.cost_price
                        FROM product_variants v
                        JOIN products p ON p.id = v.product_id
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        WHERE v.shop_id = :shop AND v.id IN (:ids) AND v.status = 'ACTIVE'
                          AND p.status = 'ACTIVE' AND v.sku NOT LIKE '%~del%'
                        ORDER BY v.id
                        """)
                .param("shop", shop).param("ids", new ArrayList<>(qty.keySet()))
                .query((rs, n) -> new Variant(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                        rs.getString("color"), rs.getString("size"), rs.getLong("cost_price")))
                .list();
        if (variants.size() != qty.size()) throw bad("Có sản phẩm đã ngừng bán hoặc không tồn tại, hãy kiểm tra lại đơn.");

        long[] unit = new long[variants.size()];
        long subtotal = 0;
        for (int i = 0; i < variants.size(); i++) {
            var v = variants.get(i);
            int q = qty.get(v.id());
            Long price = override.get(v.id());
            if (price == null) price = priceFor(v.id(), q, type);
            if (price == null) throw bad("Chưa có giá bán cho " + v.label() + ".");
            unit[i] = price;
            subtotal += price * q;
        }
        long discount = r.discount() == null ? 0 : r.discount();
        if (discount > subtotal) throw bad("Giảm giá không được lớn hơn tổng tiền hàng.");
        long total = subtotal - discount;
        Pay pay = payment(r.paymentStatus(), r.paidAmount(), total);

        jdbc.sql("""
                        UPDATE orders SET order_type = :type, subtotal = :sub, discount_amount = :disc, total_amount = :total,
                                          paid_amount = :paid, payment_status = :ps, receiver_name = :cn, receiver_phone = :cp,
                                          note = :note, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", orderId).param("type", type).param("sub", subtotal).param("disc", discount).param("total", total)
                .param("paid", pay.paid()).param("ps", pay.status())
                .param("cn", blankToNull(r.customerName()), Types.VARCHAR).param("cp", blankToNull(r.customerPhone()), Types.VARCHAR)
                .param("note", blankToNull(r.note()), Types.VARCHAR)
                .update();
        for (int i = 0; i < variants.size(); i++) {
            var v = variants.get(i);
            jdbc.sql("""
                            INSERT INTO order_items (id, order_id, variant_id, quantity, unit_price, cost_price)
                            VALUES (:id, :o, :v, :q, :p, :c)
                            """)
                    .param("id", ids.next()).param("o", orderId).param("v", v.id()).param("q", qty.get(v.id()))
                    .param("p", unit[i]).param("c", v.cost()).update();
        }
    }

    /** Chot don: tru kho nguyen tu, chot gia von, cap ma DH. Don tam phai da khoa (FOR UPDATE) trong giao dich nay. */
    private void finalizeOrder(long orderId, AuthUser u) {
        long shop = Tenant.shopId();
        long storeId = jdbc.sql("SELECT store_id FROM orders WHERE id = :id").param("id", orderId).query(Long.class).single();
        record Line(long variantId, int quantity, String label, boolean sellable, long cost) {
        }
        List<Line> lines = jdbc.sql("""
                        SELECT i.variant_id, i.quantity, p.name || ' ' || c.name || '/' || s.name AS label,
                               (v.status = 'ACTIVE' AND p.status = 'ACTIVE' AND v.sku NOT LIKE '%~del%') AS sellable, v.cost_price
                        FROM order_items i
                        JOIN product_variants v ON v.id = i.variant_id
                        JOIN products p ON p.id = v.product_id
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        WHERE i.order_id = :id ORDER BY i.variant_id
                        """)
                .param("id", orderId)
                .query((rs, n) -> new Line(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5)))
                .list();
        if (lines.isEmpty()) throw bad("Đơn chưa có sản phẩm.");
        for (Line l : lines) {
            if (!l.sellable()) throw bad(l.label() + " đã ngừng bán, hãy bỏ khỏi đơn.");
            // Tru kho nguyen tu: chi thanh cong khi con du hang
            Integer left = jdbc.sql("""
                            UPDATE stock_levels SET quantity = quantity - :q, updated_at = now()
                            WHERE shop_id = :shop AND store_id = :store AND variant_id = :v AND quantity >= :q
                            RETURNING quantity
                            """)
                    .param("q", l.quantity()).param("shop", shop).param("store", storeId).param("v", l.variantId())
                    .query(Integer.class).optional().orElse(null);
            if (left == null) {
                int cur = jdbc.sql("SELECT quantity FROM stock_levels WHERE store_id = :store AND variant_id = :v")
                        .param("store", storeId).param("v", l.variantId()).query(Integer.class).optional().orElse(0);
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Không đủ tồn kho: " + l.label() + " (còn " + cur + ").");
            }
            jdbc.sql("""
                            INSERT INTO stock_movements (id, shop_id, store_id, variant_id, movement_type, quantity_delta, balance_after,
                                                         ref_type, ref_id, created_by)
                            VALUES (:id, :shop, :store, :v, 'SALE', :d, :b, 'ORDER', :o, :u)
                            """)
                    .param("id", ids.next()).param("shop", shop).param("store", storeId).param("v", l.variantId())
                    .param("d", -l.quantity()).param("b", left).param("o", orderId).param("u", u.id()).update();
            jdbc.sql("UPDATE order_items SET cost_price = :c WHERE order_id = :o AND variant_id = :v")
                    .param("c", l.cost()).param("o", orderId).param("v", l.variantId()).update();
        }
        String code = "DH" + String.format("%06d", nextSequence("order"));
        jdbc.sql("UPDATE orders SET status = 'COMPLETED', code = :code, ordered_at = now(), updated_at = now() WHERE id = :id")
                .param("code", code).param("id", orderId).update();
        audit.record(u.id(), "CONFIRM", "order", orderId, Map.of("code", code));
    }

    // --------------------------------------------------------------------- doc
    public OrderView get(long id, AuthUser u) {
        boolean cost = u.can("report.view");
        var h = jdbc.sql("""
                        SELECT o.id, o.code, o.order_type, o.status, o.payment_status, o.subtotal, o.discount_amount, o.total_amount,
                               o.paid_amount, o.receiver_name, o.receiver_phone, o.note, us.full_name, o.ordered_at
                        FROM orders o JOIN users us ON us.id = o.staff_id
                        WHERE o.id = :id AND o.shop_id = :shop AND (:all OR o.staff_id = :u)
                        """)
                .param("id", id).param("shop", Tenant.shopId()).param("all", cost).param("u", u.id())
                .query((rs, n) -> new Object[]{
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13), rs.getObject(14, OffsetDateTime.class)})
                .optional().orElseThrow(() -> notFound());
        List<long[]> costs = new ArrayList<>();
        List<OrderLine> items = jdbc.sql("""
                        SELECT i.variant_id, v.sku, p.name, c.name AS color, s.name AS size, i.quantity, i.unit_price, i.line_total, i.cost_price,
                               COALESCE((SELECT sl.quantity FROM stock_levels sl WHERE sl.store_id = o.store_id AND sl.variant_id = i.variant_id), 0) AS stock
                        FROM order_items i
                        JOIN orders o ON o.id = i.order_id
                        JOIN product_variants v ON v.id = i.variant_id
                        JOIN products p ON p.id = v.product_id
                        JOIN colors c ON c.id = v.color_id
                        JOIN sizes s ON s.id = v.size_id
                        WHERE i.order_id = :id ORDER BY i.id
                        """)
                .param("id", id)
                .query((rs, n) -> {
                    long cp = rs.getLong("cost_price");
                    int q = rs.getInt("quantity");
                    costs.add(new long[]{q, cp});
                    return new OrderLine(rs.getLong("variant_id"), rs.getString("sku"), rs.getString("name"), rs.getString("color"),
                            rs.getString("size"), q, rs.getLong("unit_price"), rs.getLong("line_total"), cost ? cp : null, rs.getInt("stock"));
                }).list();
        long total = (Long) h[7];
        long paid = (Long) h[8];
        int count = items.stream().mapToInt(OrderLine::quantity).sum();
        Long profit = null;
        if (cost) {
            long cogs = 0;
            for (long[] c : costs) cogs += c[0] * c[1];
            profit = total - cogs;
        }
        return new OrderView((Long) h[0], (String) h[1], (String) h[2], (String) h[3], (String) h[4],
                (Long) h[5], (Long) h[6], total, paid, Math.max(total - paid, 0),
                (String) h[9], (String) h[10], (String) h[11], (String) h[12], (OffsetDateTime) h[13], count, items, profit);
    }

    public PageResult<OrderListItem> list(String q, String status, String payment, int page, int size, AuthUser u) {
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pg = Math.max(page, 0);
        String st = status == null ? "" : status.trim().toUpperCase();
        String pm = payment == null ? "" : payment.trim().toUpperCase();
        String text = q == null ? "" : q.trim();
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = """
                 WHERE o.shop_id = :shop AND (:all OR o.staff_id = :u)
                   AND (:st = '' OR o.status = :st) AND (:pm = '' OR o.payment_status = :pm)
                   AND (:q = '' OR o.code ILIKE :like OR coalesce(o.receiver_name, '') ILIKE :like OR coalesce(o.receiver_phone, '') ILIKE :like)
                """;
        long shop = Tenant.shopId();
        boolean all = u.can("report.view");
        long total = jdbc.sql("SELECT count(*) FROM orders o" + where)
                .param("shop", shop).param("all", all).param("u", u.id()).param("st", st).param("pm", pm)
                .param("q", text).param("like", like).query(Long.class).single();
        List<OrderListItem> items = jdbc.sql("""
                        SELECT o.id, o.code, o.status, o.payment_status, o.total_amount, o.paid_amount, o.receiver_name, us.full_name, o.ordered_at,
                               (SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i WHERE i.order_id = o.id) AS qty
                        FROM orders o JOIN users us ON us.id = o.staff_id
                        """ + where + """
                        ORDER BY o.ordered_at DESC, o.id DESC LIMIT :limit OFFSET :offset
                        """)
                .param("shop", shop).param("all", all).param("u", u.id()).param("st", st).param("pm", pm)
                .param("q", text).param("like", like).param("limit", sz).param("offset", (long) pg * sz)
                .query((rs, n) -> new OrderListItem(rs.getLong("id"), rs.getString("code"), rs.getString("status"),
                        rs.getString("payment_status"), rs.getLong("total_amount"), rs.getLong("paid_amount"), rs.getInt("qty"),
                        rs.getString("receiver_name"), rs.getString("full_name"), rs.getObject("ordered_at", OffsetDateTime.class)))
                .list();
        return new PageResult<>(items, pg, sz, total);
    }

    // -------------------------------------------------------------- thanh toan
    @Transactional
    public OrderView updatePayment(long id, PaymentUpdate p, AuthUser u) {
        var row = lockOrder(id, u);
        if ("CANCELLED".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Đơn đã huỷ, không cập nhật thanh toán.");
        Pay pay = payment(p.paymentStatus(), p.paidAmount(), row.total());
        jdbc.sql("UPDATE orders SET payment_status = :ps, paid_amount = :paid, updated_at = now() WHERE id = :id")
                .param("ps", pay.status()).param("paid", pay.paid()).param("id", id).update();
        audit.record(u.id(), "PAYMENT", "order", id, Map.of("status", pay.status(), "paid", pay.paid()));
        return get(id, u);
    }

    // ---------------------------------------------------------------- huy don
    @Transactional
    public OrderView cancel(long id, AuthUser u) {
        var row = lockOrder(id, u);
        if ("CANCELLED".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Đơn này đã được huỷ trước đó.");
        if ("DRAFT".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Đơn tạm chưa trừ kho nên không huỷ, hãy xoá đơn tạm.");
        long shop = Tenant.shopId();
        var lines = jdbc.sql("SELECT variant_id, quantity FROM order_items WHERE order_id = :id ORDER BY variant_id")
                .param("id", id).query((rs, n) -> new long[]{rs.getLong(1), rs.getInt(2)}).list();
        for (long[] l : lines) {
            jdbc.sql("""
                            INSERT INTO stock_levels (shop_id, store_id, variant_id, quantity, min_quantity)
                            VALUES (:shop, :store, :v, 0, 0) ON CONFLICT DO NOTHING
                            """)
                    .param("shop", shop).param("store", row.storeId()).param("v", l[0]).update();
            int after = jdbc.sql("""
                            UPDATE stock_levels SET quantity = quantity + :q, updated_at = now()
                            WHERE store_id = :store AND variant_id = :v RETURNING quantity
                            """)
                    .param("q", (int) l[1]).param("store", row.storeId()).param("v", l[0]).query(Integer.class).single();
            jdbc.sql("""
                            INSERT INTO stock_movements (id, shop_id, store_id, variant_id, movement_type, quantity_delta, balance_after,
                                                         ref_type, ref_id, note, created_by)
                            VALUES (:id, :shop, :store, :v, 'SALE_RETURN', :d, :b, 'ORDER', :o, 'Huỷ đơn', :u)
                            """)
                    .param("id", ids.next()).param("shop", shop).param("store", row.storeId()).param("v", l[0])
                    .param("d", (int) l[1]).param("b", after).param("o", id).param("u", u.id()).update();
        }
        jdbc.sql("UPDATE orders SET status = 'CANCELLED', updated_at = now() WHERE id = :id").param("id", id).update();
        audit.record(u.id(), "CANCEL", "order", id, Map.of("code", row.code()));
        return get(id, u);
    }

    // ----------------------------------------------------------------- helpers
    private record Locked(String code, String status, long total, long storeId) {
    }

    private Locked lockOrder(long id, AuthUser u) {
        return jdbc.sql("""
                        SELECT code, status, total_amount, store_id FROM orders
                        WHERE id = :id AND shop_id = :shop AND (:all OR staff_id = :u) FOR UPDATE
                        """)
                .param("id", id).param("shop", Tenant.shopId()).param("all", u.can("report.view")).param("u", u.id())
                .query((rs, n) -> new Locked(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)))
                .optional().orElseThrow(OrderService::notFound);
    }

    private Long priceFor(long variantId, int qty, String type) {
        Long p = pick(variantId, qty, type);
        return p == null && "WHOLESALE".equals(type) ? pick(variantId, qty, "RETAIL") : p;
    }

    private Long pick(long variantId, int qty, String type) {
        return jdbc.sql("""
                        SELECT price FROM variant_prices
                        WHERE variant_id = :v AND price_type = :t AND min_qty <= :q
                        ORDER BY min_qty DESC LIMIT 1
                        """)
                .param("v", variantId).param("t", type).param("q", qty).query(Long.class).optional().orElse(null);
    }

    static Pay payment(String status, Long paid, long total) {
        String s = status == null || status.isBlank() ? "UNPAID" : status.trim().toUpperCase();
        if (!PAY_STATUSES.contains(s)) throw bad("Trạng thái thanh toán không hợp lệ.");
        return switch (s) {
            case "PAID" -> new Pay(s, total);
            case "UNPAID" -> new Pay(s, 0);
            default -> {
                if (paid == null || paid <= 0 || paid >= total) {
                    throw bad("Thanh toán một phần: nhập số tiền đã trả lớn hơn 0 và nhỏ hơn tổng đơn.");
                }
                yield new Pay(s, paid);
            }
        };
    }

    private long nextSequence(String name) {
        return jdbc.sql("""
                        INSERT INTO shop_sequences (shop_id, name, value) VALUES (:shop, :n, 1)
                        ON CONFLICT (shop_id, name) DO UPDATE SET value = shop_sequences.value + 1
                        RETURNING value
                        """)
                .param("shop", Tenant.shopId()).param("n", name).query(Long.class).single();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", m);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy đơn hàng.");
    }
}
