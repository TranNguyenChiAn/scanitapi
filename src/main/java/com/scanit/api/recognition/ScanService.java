package com.scanit.api.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanit.api.catalog.CatalogDtos.VariantSummary;
import com.scanit.api.catalog.CatalogRepository;
import com.scanit.api.common.ApiException;
import com.scanit.api.config.AppProperties;
import com.scanit.api.recognition.ScanDtos.Candidate;
import com.scanit.api.recognition.ScanDtos.ScanImageResult;
import com.scanit.api.common.IdGenerator;
import com.scanit.api.security.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScanService {

    private static final int MAX_CANDIDATES = 5;

    private final RecognitionClient client;
    private final EmbeddingRepository embeddings;
    private final CatalogRepository catalog;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final IdGenerator ids;
    private final double minScore;
    private final double confidentScore;

    public ScanService(RecognitionClient client, EmbeddingRepository embeddings, CatalogRepository catalog,
                       JdbcClient jdbc, ObjectMapper mapper, IdGenerator ids, AppProperties props) {
        this.client = client;
        this.embeddings = embeddings;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ids = ids;
        var r = props.recognition();
        this.minScore = r != null && r.minScore() > 0 ? r.minScore() : 0.55;
        this.confidentScore = r != null && r.confidentScore() > 0 ? r.confidentScore() : 0.80;
    }

    public ScanImageResult scan(byte[] image, long userId, long storeId) {
        if (image == null || image.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Chưa có ảnh.");
        }
        if (!embeddings.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PGVECTOR_MISSING",
                    "Chưa bật tính năng quét ảnh trên máy chủ (thiếu pgvector).");
        }
        var emb = client.embed(image, "scan.jpg");
        var hits = embeddings.nearest(emb.vector(), emb.model(), 12);

        List<Candidate> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var h : hits) {
            double score = Math.round((1.0 - h.distance()) * 1000.0) / 1000.0;
            if (score < minScore) break; // hits da sap theo khoang cach tang dan
            var vid = catalog.representativeVariantId(h.productId(), h.colorId(), storeId);
            if (vid.isEmpty() || !seen.add(vid.get())) continue;
            VariantSummary s = catalog.findSummary(vid.get(), storeId).orElse(null);
            if (s == null) continue;
            out.add(new Candidate(score, s));
            if (out.size() >= MAX_CANDIDATES) break;
        }
        boolean confident = !out.isEmpty() && out.get(0).score() >= confidentScore;
        long eventId = logEvent(userId, storeId, out, confident);
        return new ScanImageResult(eventId, confident, out);
    }

    private long logEvent(long userId, long storeId, List<Candidate> cands, boolean confident) {
        String json;
        try {
            json = mapper.writeValueAsString(cands.stream()
                    .map(c -> Map.of("variantId", c.variant().id(), "score", c.score())).toList());
        } catch (Exception e) {
            json = "[]";
        }
        Long matched = cands.isEmpty() ? null : cands.get(0).variant().id();
        Double top = cands.isEmpty() ? null : cands.get(0).score();
        long id = ids.next();
        jdbc.sql("""
                        INSERT INTO scan_events (id, shop_id, user_id, store_id, mode, found, matched_variant_id, top_candidates, confidence)
                        VALUES (:id, :shop, :u, :s, 'PHOTO', :found, :m, CAST(:c AS jsonb), :conf)
                        """)
                .param("id", id).param("shop", Tenant.shopId()).param("u", userId).param("s", storeId).param("found", confident)
                .param("m", confident ? matched : null, Types.BIGINT)
                .param("c", json)
                .param("conf", top == null ? null : top.floatValue(), Types.REAL)
                .update();
        return id;
    }

    /** Nhan vien xac nhan ket qua dung -> dung de do do chinh xac / tinh chinh nguong sau nay. */
    public void confirm(long scanEventId, long variantId, long userId) {
        int n = jdbc.sql("UPDATE scan_events SET confirmed_variant_id = :v WHERE id = :id AND shop_id = :shop AND user_id = :u AND mode = 'PHOTO' "
                + "AND EXISTS (SELECT 1 FROM product_variants x WHERE x.id = :v AND x.shop_id = :shop)")
                .param("v", variantId).param("id", scanEventId).param("shop", Tenant.shopId()).param("u", userId).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy lượt quét.");
    }
}
