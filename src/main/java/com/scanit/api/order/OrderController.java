package com.scanit.api.order;

import com.scanit.api.catalog.CatalogDtos.PageResult;
import com.scanit.api.order.OrderDtos.*;
import com.scanit.api.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Don hang: admin va nhan vien deu tao duoc don tam va chot; huy don da chot can order.cancel (chi admin). */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('order.create')")
    public OrderView create(@Valid @RequestBody OrderRequest r, @AuthenticationPrincipal AuthUser u) {
        return orders.create(r, u);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('order.create')")
    public OrderView save(@PathVariable long id, @Valid @RequestBody OrderRequest r, @AuthenticationPrincipal AuthUser u) {
        return orders.save(id, r, u);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('order.create')")
    public OrderView confirm(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        return orders.confirm(id, u);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('order.create')")
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        orders.delete(id, u);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('order.view')")
    public PageResult<OrderListItem> list(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "") String status,
                                          @RequestParam(defaultValue = "") String payment,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @AuthenticationPrincipal AuthUser u) {
        return orders.list(q, status, payment, page, size, u);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order.view')")
    public OrderView get(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        return orders.get(id, u);
    }

    @PatchMapping("/{id}/payment")
    @PreAuthorize("hasAuthority('order.create')")
    public OrderView payment(@PathVariable long id, @Valid @RequestBody PaymentUpdate p, @AuthenticationPrincipal AuthUser u) {
        return orders.updatePayment(id, p, u);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order.cancel')")
    public OrderView cancel(@PathVariable long id, @AuthenticationPrincipal AuthUser u) {
        return orders.cancel(id, u);
    }
}
