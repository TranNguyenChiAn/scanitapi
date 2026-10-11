package com.scanit.api.recognition;

import com.scanit.api.catalog.CatalogRepository;
import com.scanit.api.common.ApiException;
import com.scanit.api.recognition.ScanDtos.ConfirmRequest;
import com.scanit.api.recognition.ScanDtos.ScanImageResult;
import com.scanit.api.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/scan")
@PreAuthorize("hasAuthority('scan.use')")
public class ScanController {

    private final ScanService scans;
    private final CatalogRepository catalog;

    public ScanController(ScanService scans, CatalogRepository catalog) {
        this.scans = scans;
        this.catalog = catalog;
    }

    /** multipart/form-data, truong "file": anh chup san pham. Tra toi da 5 ung vien kem do giong. */
    @PostMapping(value = "/image", consumes = "multipart/form-data")
    public ScanImageResult image(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthUser user) {
        try {
            long store = user.storeId() != null ? user.storeId() : catalog.defaultStoreId();
            return scans.scan(file.getBytes(), user.id(), store);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Không đọc được ảnh tải lên.");
        }
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable long id, @Valid @RequestBody ConfirmRequest r, @AuthenticationPrincipal AuthUser user) {
        scans.confirm(id, r.variantId(), user.id());
    }
}
