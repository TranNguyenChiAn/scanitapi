package com.scanit.api.catalog;

import com.scanit.api.config.AppProperties;
import org.springframework.stereotype.Component;

/** Doi (provider, object_key) thanh URL anh. DB khong luu URL day du de doi nha cung cap khong phai sua du lieu. */
@Component
public class MediaUrls {

    private final String cloudinaryBase;

    public MediaUrls(AppProperties props) {
        String cloud = props.media().cloudinaryCloudName();
        this.cloudinaryBase = (cloud == null || cloud.isBlank()) ? null
                : "https://res.cloudinary.com/" + cloud.trim() + "/image/upload/";
    }

    /** Tra null khi chua cau hinh nha cung cap tuong ung. */
    public String url(String provider, String objectKey) {
        if (objectKey == null) return null;
        if ("CLOUDINARY".equals(provider)) {
            return cloudinaryBase == null ? null : cloudinaryBase + objectKey;
        }
        return null;
    }
}
