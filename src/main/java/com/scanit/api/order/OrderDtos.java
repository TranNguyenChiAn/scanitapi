package com.scanit.api.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() {
    }

    /** unitPrice de trong thi lay gia ban theo bang gia (le / si). */
    public record LineRequest(@NotNull(message = "Thiếu sản phẩm") Long variantId,
                              @Min(value = 1, message = "Số lượng tối thiểu là 1") @Max(value = 10000, message = "Số lượng quá lớn") int quantity,
                              @Min(value = 0, message = "Giá không được âm") @Max(value = 100_000_000_000L, message = "Giá quá lớn") Long unitPrice) {
    }

    /**
     * Don tam (DRAFT) khong tru kho, sua thoai mai; chot (confirm = true) moi tru kho va thanh don that.
     * items co the rong khi luu don tam. unitPrice chi duoc dung neu nguoi goi co quyen order.price,
     * con lai he thong tu lay gia theo bang gia.
     * paymentStatus nhap tay: UNPAID (chua tra), PARTIAL (tra mot phan, can paidAmount), PAID (da tra du).
     * orderType: RETAIL (mac dinh) hoac WHOLESALE (lay gia si neu co).
     */
    public record OrderRequest(@Size(max = 100, message = "Tối đa 100 dòng mỗi đơn") List<@Valid LineRequest> items,
                               @Min(value = 0, message = "Giảm giá không được âm") @Max(value = 100_000_000_000L, message = "Số tiền quá lớn") Long discount,
                               String orderType,
                               String paymentStatus,
                               @Min(value = 0, message = "Số tiền đã trả không được âm") @Max(value = 100_000_000_000L, message = "Số tiền quá lớn") Long paidAmount,
                               @Size(max = 120, message = "Tên khách quá dài") String customerName,
                               @Size(max = 30, message = "Số điện thoại quá dài") String customerPhone,
                               @Size(max = 500, message = "Ghi chú quá dài") String note,
                               Boolean confirm) {
    }

    public record PaymentUpdate(String paymentStatus,
                                @Min(value = 0, message = "Số tiền đã trả không được âm") @Max(value = 100_000_000_000L, message = "Số tiền quá lớn") Long paidAmount) {
    }

    /** costPrice chi co voi nguoi co quyen report.view. stock = ton hien tai cua kho dang ban. */
    public record OrderLine(long variantId, String sku, String productName, String color, String size,
                            int quantity, long unitPrice, long lineTotal, Long costPrice, int stock) {
    }

    /** profit (lai gop cua don) chi co voi nguoi co quyen report.view. */
    public record OrderView(long id, String code, String orderType, String status, String paymentStatus,
                            long subtotal, long discount, long total, long paid, long owed,
                            String customerName, String customerPhone, String note,
                            String staffName, OffsetDateTime orderedAt, int itemCount,
                            List<OrderLine> items, Long profit) {
    }

    public record OrderListItem(long id, String code, String status, String paymentStatus,
                                long total, long paid, int itemCount, String customerName,
                                String staffName, OffsetDateTime orderedAt) {
    }
}
