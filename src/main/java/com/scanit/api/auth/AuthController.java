package com.scanit.api.auth;

import com.scanit.api.auth.AuthDtos.LoginRequest;
import com.scanit.api.auth.AuthDtos.LoginResponse;
import com.scanit.api.auth.AuthDtos.UserView;
import com.scanit.api.common.ApiException;
import com.scanit.api.security.AuthUser;
import com.scanit.api.security.JwtService;
import com.scanit.api.security.UserLookup;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Hash gia de thoi gian phan hoi giong nhau ke ca khi tai khoan khong ton tai
    private final String dummyHash;

    private final UserLookup users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserLookup users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.dummyHash = encoder.encode("khong-ai-biet-mat-khau-nay");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        var account = req.account().trim();
        var creds = users.findCredentials(req.shop().trim(), account);

        boolean ok = encoder.matches(req.password(), creds.map(UserLookup.Credentials::passwordHash).orElse(dummyHash))
                && creds.isPresent();
        // Thong bao chung cho sai tai khoan / sai mat khau, khong lo tai khoan co ton tai hay khong
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Tài khoản hoặc mật khẩu không đúng.");
        }
        if ("SUSPENDED".equals(creds.get().shopStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_SUSPENDED", "Cửa hàng đã bị tạm khoá. Liên hệ nhà cung cấp.");
        }
        if (!creds.get().active()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Tài khoản đã bị khoá. Liên hệ quản lý.");
        }
        AuthUser user = users.findActiveById(creds.get().id())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Tài khoản đã bị khoá. Liên hệ quản lý."));
        users.touchLastLogin(user.id());
        return new LoginResponse(jwt.issue(user.id()), UserView.of(user));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser user) {
        return UserView.of(user);
    }
}
