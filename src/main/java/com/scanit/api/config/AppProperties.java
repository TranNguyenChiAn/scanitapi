package com.scanit.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "scanit")
public record AppProperties(Jwt jwt, Cors cors, Media media, boolean seedDevData) {

    public record Jwt(String secret, long ttlHours) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Media(String cloudinaryCloudName) {
    }
}
