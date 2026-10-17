package com.scanit.api.catalog;

import com.scanit.api.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Doi (provider, object_key) thanh URL anh. DB khong luu URL day du de doi nha cung cap khong phai sua du lieu.
 * Anh LOCAL tra duong dan tuong doi "/media/..." (app tu noi voi dia chi may chu) tru khi co MEDIA_PUBLIC_BASE_URL.
 */
@Component
public class MediaUrls {

    private final String cloudinaryBase;
    private final String publicBase;

    public MediaUrls(AppProperties props) {
        String cloud = props.media().cloudinaryCloudName();
        this.cloudinaryBase = (cloud == null || cloud.isBlank()) ? null
                : "https://res.cloudinary.com/" + cloud.trim() + "/image/upload/";
        String pub = props.media().publicBaseUrl();
        this.publicBase = (pub == null || pub.isBlank()) ? null : pub.trim().replaceAll("/+$", "");
    }

    /** Tra null khi chua cau hinh nha cung cap tuong ung. */
    public String url(String provider, String objectKey) {
        if (objectKey == null) return null;
        return switch (provider == null ? "" : provider) {
            case "CLOUDINARY" -> cloudinaryBase == null ? null : cloudinaryBase + objectKey;
            case "R2" -> publicBase == null ? null : publicBase + "/" + objectKey;
            case "LOCAL" -> (publicBase == null ? "" : publicBase) + "/media/" + objectKey;
            default -> null;
        };
    }

    /** Anh thu nho (400px) cho danh sach; chi co voi anh do he thong tu xu ly (R2 / LOCAL). */
    public String thumbUrl(String provider, String objectKey) {
        String thumbKey = thumbKey(objectKey);
        if (thumbKey == null || !("R2".equals(provider) || "LOCAL".equals(provider))) return url(provider, objectKey);
        return url(provider, thumbKey);
    }

    public static String thumbKey(String objectKey) {
        if (objectKey == null) return null;
        if (objectKey.startsWith("m/")) return "t/" + objectKey.substring(2); // anh cu (truoc khi chia theo shop)
        if (objectKey.startsWith("s/")) { // s/<shopId>/m/<ma>.webp -> s/<shopId>/t/<ma>.webp
            int i = objectKey.indexOf("/m/");
            if (i > 1) return objectKey.substring(0, i) + "/t/" + objectKey.substring(i + 3);
        }
        return null;
    }
}
