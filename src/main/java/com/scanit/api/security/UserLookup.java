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

    private record Row(long id, String username, String fullName, String role, long roleId, Long storeId) {
    }

    private static final String SELECT = """
            SELECT u.id, u.username, u.full_name, r.code AS role, r.id AS role_id, u.store_id
            FROM users u JOIN roles r ON r.id = u.role_id
            """;

    /** Nguoi dung dang hoat dong theo id (dung cho moi request co token). */
    public Optional<AuthUser> findActiveById(long id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id AND u.is_active AND u.deleted_at IS NULL")
                .param("id", id)
                .query(this::mapRow).optional()
                .map(this::withPermissions);
    }

    /** Tim theo tai khoan, so dien thoai hoac email; tra ve cung mat khau da bam de kiem tra. */
    public Optional<Credentials> findCredentials(String account) {
        return jdbc.sql("""
                        SELECT u.id, u.password_hash, u.is_active
                        FROM users u
                        WHERE u.deleted_at IS NULL
                          AND (lower(u.username) = lower(:a) OR lower(u.email) = lower(:a) OR u.phone = :a)
                        """)
                .param("a", account)
                .query((rs, n) -> new Credentials(rs.getLong("id"), rs.getString("password_hash"), rs.getBoolean("is_active")))
                .optional();
    }

    public void touchLastLogin(long id) {
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", id).update();
    }

    public record Credentials(long id, String passwordHash, boolean active) {
    }

    private Row mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Long storeId = rs.getObject("store_id", Long.class);
        return new Row(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                rs.getString("role"), rs.getLong("role_id"), storeId);
    }

    private AuthUser withPermissions(Row r) {
        List<String> codes = jdbc.sql("""
                        SELECT p.code FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id = :rid
                        """)
                .param("rid", r.roleId())
                .query(String.class).list();
        return new AuthUser(r.id(), r.username(), r.fullName(), r.role(), r.storeId(), Set.copyOf(new HashSet<>(codes)));
    }
}
