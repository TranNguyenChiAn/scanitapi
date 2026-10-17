package com.scanit.api.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class UserLookup {

    private final JdbcClient jdbc;

    public UserLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(long id, String username, String fullName, String role, long roleId, long shopId,
                       String shopCode, String shopName, Long storeId) {
    }

    private static final String SELECT = """
            SELECT u.id, u.username, u.full_name, r.code AS role, r.id AS role_id, u.store_id,
                   s.id AS shop_id, s.code AS shop_code, s.name AS shop_name
            FROM users u JOIN roles r ON r.id = u.role_id JOIN shops s ON s.id = u.shop_id
            """;

    /** Nguoi dung dang hoat dong theo id (dung cho moi request co token). */
    public Optional<AuthUser> findActiveById(long id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id AND u.is_active AND u.deleted_at IS NULL AND s.status <> 'SUSPENDED'")
                .param("id", id)
                .query(this::mapRow).optional()
                .map(this::withPermissions);
    }

    /**
     * Tim trong 1 cua hang (theo ma cua hang) bang tai khoan, so dien thoai hoac email;
     * tra ve cung mat khau da bam de kiem tra. Tai khoan chi duy nhat trong tung cua hang.
     */
    public Optional<Credentials> findCredentials(String shopCode, String account) {
        return jdbc.sql("""
                        SELECT u.id, u.password_hash, u.is_active, s.status
                        FROM users u JOIN shops s ON s.id = u.shop_id
                        WHERE lower(s.code) = lower(:shop) AND u.deleted_at IS NULL
                          AND (lower(u.username) = lower(:a) OR lower(u.email) = lower(:a) OR u.phone = :a)
                        LIMIT 1
                        """)
                .param("shop", shopCode).param("a", account)
                .query((rs, n) -> new Credentials(rs.getLong("id"), rs.getString("password_hash"),
                        rs.getBoolean("is_active"), rs.getString("status")))
                .optional();
    }

    public void touchLastLogin(long id) {
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", id).update();
    }

    public record Credentials(long id, String passwordHash, boolean active, String shopStatus) {
    }

    private Row mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Long storeId = rs.getObject("store_id", Long.class);
        return new Row(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                rs.getString("role"), rs.getLong("role_id"), rs.getLong("shop_id"), rs.getString("shop_code"),
                rs.getString("shop_name"), storeId);
    }

    private AuthUser withPermissions(Row r) {
        List<String> codes = jdbc.sql("""
                        SELECT p.code FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id = :rid
                        """)
                .param("rid", r.roleId())
                .query(String.class).list();
        return new AuthUser(r.id(), r.username(), r.fullName(), r.role(), r.shopId(), r.shopCode(), r.shopName(), r.storeId(),
                Set.copyOf(new HashSet<>(codes)));
    }
}
