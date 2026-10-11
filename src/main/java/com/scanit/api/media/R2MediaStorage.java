package com.scanit.api.media;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;

/** Cloudflare R2 qua giao thuc S3. File goc dat Cache-Control immutable vi ten file = sha256 noi dung. */
public class R2MediaStorage implements MediaStorage {

    private final S3Client s3;
    private final String bucket;

    public R2MediaStorage(String endpoint, String bucket, String accessKey, String secretKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public String provider() {
        return "R2";
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(contentType)
                            .cacheControl("public, max-age=31536000, immutable")
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (RuntimeException e) {
            throw new IOException("Khong ghi duoc len R2: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] get(String key) throws IOException {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (RuntimeException e) {
            throw new IOException("Khong doc duoc tu R2: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            throw new IOException("Khong xoa duoc tren R2: " + e.getMessage(), e);
        }
    }
}
