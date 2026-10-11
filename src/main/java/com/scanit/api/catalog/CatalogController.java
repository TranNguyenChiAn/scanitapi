package com.scanit.api.catalog;

import com.scanit.api.catalog.CatalogDtos.PageResult;
import com.scanit.api.catalog.CatalogDtos.VariantDetail;
import com.scanit.api.catalog.CatalogDtos.VariantSummary;
import com.scanit.api.common.ApiException;
import com.scanit.api.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/variants")
public class CatalogController {

    private static final int MAX_PAGE_SIZE = 50;

    private final CatalogRepository repo;

    public CatalogController(CatalogRepository repo) {
        this.repo = repo;
    }

    /** Quet ma vach: tra dung 1 bien the, hoac 404 NOT_FOUND. */
    @GetMapping("/by-barcode/{code}")
    @PreAuthorize("hasAuthority('scan.use')")
    public VariantDetail byBarcode(@PathVariable String code, @AuthenticationPrincipal AuthUser user) {
        String barcode = code.trim();
        if (barcode.isEmpty() || barcode.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Mã vạch không hợp lệ.");
        }
        long storeId = storeOf(user);
        var id = repo.findVariantIdByBarcode(barcode);
        repo.logScan(user.id(), storeId, barcode, id.orElse(null));
        return id.flatMap(v -> repo.loadDetail(v, storeId, user.can("product.manage")))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy sản phẩm với mã vạch này."));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product.view')")
    public VariantDetail get(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return repo.loadDetail(id, storeOf(user), user.can("product.manage"))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy sản phẩm."));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product.view')")
    public PageResult<VariantSummary> search(@RequestParam(defaultValue = "") String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @AuthenticationPrincipal AuthUser user) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return repo.search(q.trim(), storeOf(user), Math.max(page, 0), safeSize);
    }

    private long storeOf(AuthUser user) {
        return user.storeId() != null ? user.storeId() : repo.defaultStoreId();
    }
}
