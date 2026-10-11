package com.scanit.api.media;

public final class MediaDtos {
    private MediaDtos() {
    }

    /** colorId = null: anh chung cua ca san pham. */
    public record MediaItem(long id, Long colorId, String url, String thumbUrl, boolean primary, int sortOrder,
                            int width, int height, int sizeBytes) {
    }

    /** Chi gui truong can doi. primary=true se thay anh chinh cu cua cung nhom (san pham + mau). */
    public record MediaPatch(Boolean primary, Integer sortOrder) {
    }

    public record ReindexResult(int processed, int failed, int remaining) {
    }
}
