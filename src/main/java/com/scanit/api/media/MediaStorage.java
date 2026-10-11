package com.scanit.api.media;

import java.io.IOException;

/** Noi luu file anh. Khoa (key) la duong dan trong bucket/thu muc, vd m/<sha256>.webp. */
public interface MediaStorage {

    /** Gia tri cot media_assets.storage_provider: LOCAL | R2. */
    String provider();

    void put(String key, byte[] data, String contentType) throws IOException;

    byte[] get(String key) throws IOException;

    /** Xoa neu co; khong bao loi khi khong ton tai. */
    void delete(String key) throws IOException;
}
