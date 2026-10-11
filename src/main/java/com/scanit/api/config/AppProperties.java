package com.scanit.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "scanit")
public record AppProperties(Jwt jwt, Cors cors, Media media, Recognition recognition, boolean seedDevData) {

    public record Jwt(String secret, long ttlHours) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * storage: "local" (luu vao thu muc, de thu) hoac "r2" (Cloudflare R2).
     * publicBaseUrl: goc URL cong khai cua bucket R2, vd https://pub-xxxx.r2.dev
     */
    public record Media(String cloudinaryCloudName, String storage, String localDir, String publicBaseUrl, R2 r2) {
    }

    public record R2(String endpoint, String bucket, String accessKey, String secretKey) {
    }

    /** minScore: duoi muc nay khong tra ung vien; confidentScore: tren muc nay coi la chac chan. */
    public record Recognition(String url, double minScore, double confidentScore) {
    }
}
