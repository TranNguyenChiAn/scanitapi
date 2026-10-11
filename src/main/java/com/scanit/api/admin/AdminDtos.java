package com.scanit.api.admin;

import com.scanit.api.catalog.CatalogDtos.ColorInfo;
import com.scanit.api.catalog.CatalogDtos.PriceInfo;
import com.scanit.api.catalog.CatalogDtos.SizeInfo;
import com.scanit.api.media.MediaDtos.MediaItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record PriceInput(@NotBlank(message = "Thiếu loại giá") String type,
                             @Min(value = 1, message = "Số lượng tối thiểu của bậc giá phải từ 1") Integer minQty,
                             @Min(value = 0, message = "Giá không được âm") @Max(value = 100_000_000_000L, message = "Giá quá lớn") Long price) {
    }

    /** color/size: nhap ten, chua co thi he thong tao moi. sku/barcode de trong thi tu sinh. */
    public record VariantCreate(@NotBlank(message = "Thiếu màu") @Size(max = 60, message = "Tên màu quá dài") String color,
                                @Size(max = 9, message = "Mã màu dạng #RRGGBB") String colorHex,
                                @NotBlank(message = "Thiếu size") @Size(max = 30, message = "Tên size quá dài") String size,
                                @Size(max = 80, message = "SKU quá dài") String sku,
                                @Size(max = 64, message = "Mã vạch quá dài") String barcode,
                                @Min(value = 0, message = "Giá vốn không được âm") Long costPrice,
                                @Valid List<PriceInput> prices,
                                @Min(value = 0, message = "Tồn đầu kỳ không được âm") Integer quantity,
                                @Min(value = 0, message = "Mức cảnh báo không được âm") Integer minQuantity) {
    }

    /** Truong null = giu nguyen. prices != null = thay toan bo bang gia. Khong doi mau/size (de giu lich su kho). */
    public record VariantUpdate(@Size(max = 80, message = "SKU quá dài") String sku,
                                @Size(max = 64, message = "Mã vạch quá dài") String barcode,
                                @Min(value = 0, message = "Giá vốn không được âm") Long costPrice,
                                String status,
                                @Valid List<PriceInput> prices,
                                @Min(value = 0, message = "Mức cảnh báo không được âm") Integer minQuantity) {
    }

    public record ProductRequest(@NotBlank(message = "Thiếu mã mẫu") @Size(max = 60, message = "Mã mẫu quá dài") String styleCode,
                                 @NotBlank(message = "Thiếu tên sản phẩm") @Size(max = 200, message = "Tên quá dài") String name,
                                 @Size(max = 5000, message = "Mô tả quá dài") String description,
                                 @Size(max = 100, message = "Tên thương hiệu quá dài") String brand,
                                 @Size(max = 200, message = "Chất liệu quá dài") String material,
                                 @Size(max = 100, message = "Mùa quá dài") String season,
                                 String targetGender,
                                 String productType,
                                 @Size(max = 30, message = "Quá nhiều thành phần") List<@Size(max = 100) String> components,
                                 String status,
                                 @Size(max = 20, message = "Quá nhiều danh mục") List<@Size(max = 100) String> categories,
                                 @Valid @Size(max = 200, message = "Quá nhiều biến thể") List<VariantCreate> variants) {
    }

    public record AdminVariant(long id, String sku, String barcode, String status, ColorInfo color, SizeInfo size,
                               long costPrice, List<PriceInfo> prices, int quantity, int minQuantity) {
    }

    public record AdminProduct(long id, String styleCode, String name, String description, String brand,
                               String material, String season, String targetGender, String productType,
                               List<String> components, String status, List<String> categories,
                               List<AdminVariant> variants, List<MediaItem> media) {
    }

    public record ProductListItem(long id, String styleCode, String name, String status, String productType,
                                  int variantCount, long totalQuantity, String image) {
    }

    /** Dat dung 1 trong: delta (+nhap / -xuat) hoac setTo (kiem kho: dat ton bang gia tri nay). */
    public record StockAdjust(Integer delta, @Min(value = 0, message = "Tồn không được âm") Integer setTo,
                              String type, @Size(max = 300, message = "Ghi chú quá dài") String note) {
    }

    public record StockResult(long variantId, int quantity, int delta) {
    }

    public record NamedItem(long id, String name, String extra) {
    }
}
