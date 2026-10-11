package com.scanit.api.auth;

import com.scanit.api.security.AuthUser;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "Nhập tài khoản") String account,
                               @NotBlank(message = "Nhập mật khẩu") String password) {
    }

    public record UserView(long id, String username, String fullName, String role, List<String> permissions) {
        public static UserView of(AuthUser u) {
            return new UserView(u.id(), u.username(), u.fullName(), u.role(), u.permissions().stream().sorted().toList());
        }
    }

    public record LoginResponse(String token, UserView user) {
    }
}
