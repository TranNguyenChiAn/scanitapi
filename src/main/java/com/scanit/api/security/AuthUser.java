package com.scanit.api.security;

import java.util.Set;

/** Nguoi dung dang dang nhap, nap lai tu DB moi request nen khoa tai khoan co hieu luc ngay. */
public record AuthUser(long id, String username, String fullName, String role, Long storeId, Set<String> permissions) {

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
