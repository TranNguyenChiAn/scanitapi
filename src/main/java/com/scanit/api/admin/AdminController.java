package com.scanit.api.admin;

import com.scanit.api.admin.AdminDtos.*;
import com.scanit.api.catalog.CatalogDtos.PageResult;
import com.scanit.api.common.ApiException;
import com.scanit.api.media.MediaDtos.MediaItem;
import com.scanit.api.media.MediaDtos.MediaPatch;
import com.scanit.api.media.MediaDtos.ReindexResult;
import com.scanit.api.media.MediaService;
import com.scanit.api.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Quan tri san pham. Mac dinh can product.manage; dieu chinh ton can them stock.adjust. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('product.manage')")
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminCatalogService catalog;
    private final MediaService media;

    public AdminController(AdminCatalogService catalog, MediaService media) {
        this.catalog = catalog;
        this.media = media;
    }

    private long storeOf(AuthUser u) {
        return u.storeId() != null ? u.storeId() : catalog.defaultStoreId();
    }

    // ---------------------------------------------------------- san pham
    @GetMapping("/products")
    public PageResult<ProductListItem> list(@RequestParam(defaultValue = "") String q,
                                            @RequestParam(defaultValue = "") String status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @AuthenticationPrincipal AuthUser u) {
        return catalog.list(q.trim(), status, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), storeOf(u));
    }

    @GetMapping("/products/{id}")
    public AdminProduct get(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        return catalog.get(id, storeOf(u));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProduct create(@Valid @RequestBody ProductRequest r, @AuthenticationPrincipal AuthUser u) {
        long id = catalog.create(r, u.id(), storeOf(u));
        return catalog.get(id, storeOf(u));
    }

    @PutMapping("/products/{id}")
    public AdminProduct update(@PathVariable long id, @Valid @RequestBody ProductRequest r, @AuthenticationPrincipal AuthUser u) {
        catalog.update(id, r, u.id());
        return catalog.get(id, storeOf(u));
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        catalog.delete(id, u.id());
    }

    // ---------------------------------------------------------- bien the
    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProduct addVariant(@PathVariable long id, @Valid @RequestBody VariantCreate v, @AuthenticationPrincipal AuthUser u) {
        catalog.addVariant(id, v, u.id(), storeOf(u));
        return catalog.get(id, storeOf(u));
    }

    @PutMapping("/variants/{id}")
    public AdminProduct updateVariant(@PathVariable long id, @Valid @RequestBody VariantUpdate v, @AuthenticationPrincipal AuthUser u) {
        long productId = catalog.updateVariant(id, v, u.id(), storeOf(u));
        return catalog.get(productId, storeOf(u));
    }

    @DeleteMapping("/variants/{id}")
    public AdminProduct deleteVariant(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        long productId = catalog.deleteVariant(id, u.id());
        return catalog.get(productId, storeOf(u));
    }

    @PostMapping("/variants/{id}/stock")
    @PreAuthorize("hasAuthority('stock.adjust')")
    public StockResult stock(@PathVariable long id, @Valid @RequestBody StockAdjust a, @AuthenticationPrincipal AuthUser u) {
        return catalog.adjustStock(id, a, u.id(), storeOf(u));
    }

    // --------------------------------------------------------------- anh
    @GetMapping("/products/{id}/media")
    public List<MediaItem> listMedia(@PathVariable long id) {
        return media.list(id);
    }

    /** multipart/form-data: file (JPEG/PNG, toi da 12MB), colorId (khong bat buoc: bo trong = anh chung). */
    @PostMapping(value = "/products/{id}/media", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaItem upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
                            @RequestParam(required = false) Long colorId, @AuthenticationPrincipal AuthUser u) {
        try {
            return media.upload(id, colorId, file.getBytes(), u.id());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Không đọc được tệp ảnh tải lên.");
        }
    }

    @PatchMapping("/products/{id}/media/{mediaId}")
    public MediaItem patchMedia(@PathVariable long id, @PathVariable long mediaId, @RequestBody MediaPatch p,
                                @AuthenticationPrincipal AuthUser u) {
        return media.patch(id, mediaId, p, u.id());
    }

    @DeleteMapping("/products/{id}/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMedia(@PathVariable long id, @PathVariable long mediaId, @AuthenticationPrincipal AuthUser u) {
        media.delete(id, mediaId, u.id());
    }

    /** Tinh vector nhan dien cho anh chua co (anh upload luc dich vu nhan dien tat, hoac sau khi doi model). */
    @PostMapping("/embeddings/reindex")
    public ReindexResult reindex(@RequestParam(defaultValue = "false") boolean all,
                                 @RequestParam(defaultValue = "200") int limit) {
        return media.reindex(all, Math.min(Math.max(limit, 1), 1000));
    }

    // ----------------------------------------------------------- tham chieu
    @GetMapping("/colors")
    public List<NamedItem> colors() {
        return catalog.colors();
    }

    @GetMapping("/sizes")
    public List<NamedItem> sizes() {
        return catalog.sizes();
    }

    @GetMapping("/categories")
    public List<NamedItem> categories() {
        return catalog.categories();
    }

    @GetMapping("/brands")
    public List<NamedItem> brands() {
        return catalog.brands();
    }
}
