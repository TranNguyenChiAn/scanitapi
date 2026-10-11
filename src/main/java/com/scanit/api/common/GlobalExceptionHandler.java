package com.scanit.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getDefaultMessage()).orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", msg));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", "Tham số không hợp lệ: " + e.getName()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ApiError> denied(org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này."));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", "Nội dung gửi lên không đúng định dạng."));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Vi pham rang buoc du lieu: {}", e.getMostSpecificCause().getMessage());
        boolean dup = e instanceof org.springframework.dao.DuplicateKeyException;
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", dup
                ? "Dữ liệu bị trùng (mã mẫu, SKU hoặc mã vạch đã tồn tại)."
                : "Dữ liệu không hợp lệ hoặc đang được dùng ở nơi khác."));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("TOO_LARGE", "Ảnh quá lớn (tối đa 12MB)."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        // 404 (sai duong dan), 405 (sai phuong thuc)... cua Spring: giu nguyen ma trang thai
        if (e instanceof org.springframework.web.ErrorResponse er) {
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(er.getStatusCode())
                    .body(new ApiError("HTTP", detail != null ? detail : "Yêu cầu không hợp lệ."));
        }
        log.error("Loi khong mong doi", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("SERVER", "Máy chủ gặp lỗi. Thử lại sau."));
    }
}
