package com.scanit.api.media;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.scanit.api.common.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Chuan hoa anh tai len: xoay dung theo EXIF, thu nho (khong phong to) toi da 1200px, ma hoa WebP,
 * them ban thumbnail 400px. Anh JPEG tu dien thoai ~3-6MB thanh ~80-200KB.
 */
@Component
public class ImageProcessor {

    public static final int MAX_BYTES = 12 * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000L;
    static final int FULL_SIDE = 1200;
    static final int THUMB_SIDE = 400;
    static final int FULL_QUALITY = 80;
    static final int THUMB_QUALITY = 75;

    public record Processed(byte[] full, byte[] thumb, int width, int height, String sha256) {
    }

    public Processed process(byte[] input) {
        if (input == null || input.length == 0) throw bad("Chưa chọn ảnh.");
        if (input.length > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "Ảnh quá lớn (tối đa 12MB).");
        if (!looksLikeJpegOrPng(input)) throw bad("Chỉ nhận ảnh JPEG hoặc PNG.");
        try {
            int[] dim = readSize(input);
            if ((long) dim[0] * dim[1] > MAX_PIXELS) throw bad("Ảnh có độ phân giải quá lớn.");
            int maxSide = Math.max(dim[0], dim[1]);

            BufferedImage full = resize(input, Math.min(maxSide, FULL_SIDE));
            byte[] fullBytes = webp(full, FULL_QUALITY);
            BufferedImage thumb = Thumbnails.of(full).size(Math.min(maxSide, THUMB_SIDE), Math.min(maxSide, THUMB_SIDE))
                    .asBufferedImage();
            byte[] thumbBytes = webp(thumb, THUMB_QUALITY);
            return new Processed(fullBytes, thumbBytes, full.getWidth(), full.getHeight(), sha256(fullBytes));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("Không đọc được ảnh (tệp hỏng hoặc định dạng không hỗ trợ).");
        }
    }

    /** Doc kich thuoc tu header, khong giai ma ca anh (chong "bom" gian no pixel). */
    private int[] readSize(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw bad("Không đọc được ảnh.");
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        }
    }

    private BufferedImage resize(byte[] data, int side) throws IOException {
        // size(side, side) giu ti le va side <= canh dai nhat cua anh goc nen khong bao gio phong to; co ap EXIF orientation.
        var builder = Thumbnails.of(new ByteArrayInputStream(data)).useExifOrientation(true);
        return builder.size(side, side).asBufferedImage();
    }

    private byte[] webp(BufferedImage img, int quality) throws IOException {
        return ImmutableImage.fromAwt(img).bytes(WebpWriter.DEFAULT.withQ(quality));
    }

    static boolean looksLikeJpegOrPng(byte[] b) {
        boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean png = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        return jpeg || png;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", msg);
    }
}
