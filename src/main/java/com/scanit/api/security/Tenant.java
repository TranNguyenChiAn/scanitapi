package com.scanit.api.security;

import com.scanit.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Cua hang (shop) cua nguoi dang goi API. Lay tu tai khoan trong DB (khong tu client gui len),
 * moi truy van du lieu nghiep vu phai loc theo gia tri nay.
 */
public final class Tenant {
    private Tenant() {
    }

    public static long shopId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return u.shopId();
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Phiên đăng nhập đã hết hạn. Đăng nhập lại.");
    }
}
