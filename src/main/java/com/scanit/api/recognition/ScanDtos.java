package com.scanit.api.recognition;

import com.scanit.api.catalog.CatalogDtos.VariantSummary;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class ScanDtos {
    private ScanDtos() {
    }

    /** score: 0..1, cang cao cang giong. */
    public record Candidate(double score, VariantSummary variant) {
    }

    /** confident=true khi ung vien dau du tin cay de mo thang chi tiet; nguoc lai nen cho nhan vien chon. */
    public record ScanImageResult(long scanEventId, boolean confident, List<Candidate> candidates) {
    }

    public record ConfirmRequest(@NotNull(message = "Thiếu variantId") Long variantId) {
    }
}
