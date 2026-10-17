package com.scanit.api.report;

import com.scanit.api.common.ApiException;
import com.scanit.api.security.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bao cao doanh thu / lai lo cua shop. Doanh thu = tong tien don (sau giam gia) cua don da chot (bo don tam, don huy);
 * gia von lay tu gia chot trong tung dong don; lai gop = doanh thu - gia von.
 * Chua tinh chi phi van hanh (thue mat bang, luong...) vi he thong chua ghi nhan.
 */
@Service
public class ReportService {

    /** Mui gio cua cua hang, dung de chia ngay. */
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_DAYS = 366;

    public record DayPoint(String date, long orders, long revenue, long profit) {
    }

    public record TopProduct(long productId, String name, long quantity, long revenue, long profit) {
    }

    public record Summary(String from, String to, long orders, long itemsSold, long revenue, long cogs, long profit,
                          double marginPct, long paid, long unpaid, long discount, List<DayPoint> daily, List<TopProduct> top) {
    }

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZONE);
        if (to == null) to = today;
        if (from == null) from = to.withDayOfMonth(1);
        if (from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Ngày bắt đầu phải trước ngày kết thúc.");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Chỉ xem được tối đa " + MAX_DAYS + " ngày mỗi lần.");
        }
        OffsetDateTime start = from.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        long shop = Tenant.shopId();

        // Moi hang = 1 don; gom theo ngay o Java de dung mui gio cua cua hang
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new long[4]); // orders, revenue, cogs, (unused)
        long[] tot = new long[6]; // orders, items, revenue, cogs, paid, discount
        jdbc.sql("""
                        SELECT o.ordered_at, o.total_amount, o.paid_amount, o.discount_amount,
                               COALESCE(SUM(i.quantity), 0) AS qty, COALESCE(SUM(i.quantity * i.cost_price), 0) AS cogs
                        FROM orders o LEFT JOIN order_items i ON i.order_id = o.id
                        WHERE o.shop_id = :shop AND o.status = 'COMPLETED' AND o.ordered_at >= :s AND o.ordered_at < :e
                        GROUP BY o.id
                        """)
                .param("shop", shop).param("s", start).param("e", end)
                .query((rs, n) -> {
                    LocalDate day = rs.getObject("ordered_at", OffsetDateTime.class).atZoneSameInstant(ZONE).toLocalDate();
                    long revenue = rs.getLong("total_amount");
                    long cogs = rs.getLong("cogs");
                    long[] d = byDay.get(day);
                    if (d != null) {
                        d[0]++;
                        d[1] += revenue;
                        d[2] += cogs;
                    }
                    tot[0]++;
                    tot[1] += rs.getLong("qty");
                    tot[2] += revenue;
                    tot[3] += cogs;
                    tot[4] += rs.getLong("paid_amount");
                    tot[5] += rs.getLong("discount_amount");
                    return null;
                }).list();

        List<DayPoint> daily = new ArrayList<>();
        byDay.forEach((d, v) -> daily.add(new DayPoint(d.toString(), v[0], v[1], v[1] - v[2])));

        List<TopProduct> top = jdbc.sql("""
                        SELECT p.id, p.name, SUM(i.quantity) AS qty, SUM(i.line_total) AS revenue,
                               SUM(i.line_total - i.quantity * i.cost_price) AS profit
                        FROM order_items i
                        JOIN orders o ON o.id = i.order_id
                        JOIN product_variants v ON v.id = i.variant_id
                        JOIN products p ON p.id = v.product_id
                        WHERE o.shop_id = :shop AND o.status = 'COMPLETED' AND o.ordered_at >= :s AND o.ordered_at < :e
                        GROUP BY p.id, p.name
                        ORDER BY qty DESC, p.id LIMIT 10
                        """)
                .param("shop", shop).param("s", start).param("e", end)
                .query((rs, n) -> new TopProduct(rs.getLong("id"), rs.getString("name"), rs.getLong("qty"),
                        rs.getLong("revenue"), rs.getLong("profit")))
                .list();

        long profit = tot[2] - tot[3];
        double margin = tot[2] == 0 ? 0 : Math.round(profit * 1000.0 / tot[2]) / 10.0;
        return new Summary(from.toString(), to.toString(), tot[0], tot[1], tot[2], tot[3], profit, margin, tot[4],
                Math.max(tot[2] - tot[4], 0), tot[5], daily, top);
    }
}
