package com.scanit.api.catalog;

import java.util.List;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record ProductInfo(long id, String styleCode, String name, String description, String material,
                              String season, String productType, List<String> components, List<String> categories) {
    }

    public record ColorInfo(long id, String name, String hex) {
    }

    public record SizeInfo(long id, String name) {
    }

    /** type: RETAIL | WHOLESALE. minQty: ap dung tu so luong nay tro len. */
    public record PriceInfo(String type, int minQty, long price) {
    }

    public record StockInfo(int quantity, int minQuantity, boolean low) {
    }

    /** Cac mau/size khac cua cung san pham, de nhan vien biet con mau/size nao. */
    public record Sibling(long id, String sku, String barcode, String color, String size, int quantity) {
    }

    /** costPrice chi co khi nguoi goi co quyen product.manage. */
    public record VariantDetail(long id, String sku, String barcode, String status, ProductInfo product,
                                ColorInfo color, SizeInfo size, List<PriceInfo> prices, StockInfo stock,
                                List<String> images, List<Sibling> siblings, Long costPrice) {
    }

    public record VariantSummary(long id, String sku, String barcode, String productName, String color,
                                 String size, Long retailPrice, int quantity, String image) {
    }

    public record PageResult<T>(List<T> items, int page, int size, long total) {
    }
}
