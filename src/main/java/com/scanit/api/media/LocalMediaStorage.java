package com.scanit.api.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Luu vao thu muc tren may (mac dinh, de dev). Phuc vu qua /media/** (xem MediaWebConfig). */
public class LocalMediaStorage implements MediaStorage {

    private final Path root;

    public LocalMediaStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public String provider() {
        return "LOCAL";
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Khoa file khong hop le: " + key);
        }
        return p;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public byte[] get(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }
}
