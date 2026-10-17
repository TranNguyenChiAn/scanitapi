package com.scanit.api.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanit.api.common.ApiException;
import com.scanit.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Goi dich vu Python (recognizer/) de lay vector dac trung cua anh. */
@Component
public class RecognitionClient {

    public record Embedding(String model, float[] vector) {
    }

    private static final Logger log = LoggerFactory.getLogger(RecognitionClient.class);

    // HTTP/1.1 co dinh: mac dinh JDK thu nang cap h2c tren ket noi khong ma hoa, de lam mat than yeu cau o mot so may chu Python
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public RecognitionClient(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        String u = props.recognition() == null ? null : props.recognition().url();
        this.baseUrl = (u == null || u.isBlank()) ? null : u.replaceAll("/+$", "");
    }

    /** Nem ApiException 503 neu dich vu chua chay / tra loi sai. */
    public Embedding embed(byte[] image, String filename) {
        if (baseUrl == null) throw unavailable("Chưa cấu hình dịch vụ nhận diện ảnh (RECOGNIZER_URL).", null);
        try {
            String boundary = "----scanit" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream(image.length + 256);
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(image);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/embed"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String snippet = res.body() == null ? "" : res.body().substring(0, Math.min(300, res.body().length()));
                log.warn("Recognizer tra {} cho {} byte anh: {}", res.statusCode(), image.length, snippet);
            }
            if (res.statusCode() == 400 || res.statusCode() == 422) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION",
                        "Không đọc được ảnh để nhận diện (dịch vụ trả " + res.statusCode() + ", xem log backend).");
            }
            if (res.statusCode() != 200) {
                throw unavailable("Dịch vụ nhận diện ảnh trả lỗi " + res.statusCode() + ".", null);
            }
            JsonNode json = mapper.readTree(res.body());
            JsonNode arr = json.get("embedding");
            if (arr == null || !arr.isArray() || arr.size() == 0) {
                throw unavailable("Dịch vụ nhận diện ảnh trả dữ liệu không hợp lệ.", null);
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) arr.get(i).asDouble();
            return new Embedding(json.path("model").asText("unknown"), v);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Dịch vụ nhận diện ảnh bị ngắt.", e);
        } catch (Exception e) {
            throw unavailable("Không kết nối được dịch vụ nhận diện ảnh.", e);
        }
    }

    private static ApiException unavailable(String msg, Throwable cause) {
        ApiException ex = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RECOGNIZER_DOWN", msg);
        if (cause != null) ex.initCause(cause);
        return ex;
    }
}
