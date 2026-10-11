package com.scanit.api.common;

/** Dang loi thong nhat cho moi API: { "code": "...", "message": "..." }. */
public record ApiError(String code, String message) {
}
