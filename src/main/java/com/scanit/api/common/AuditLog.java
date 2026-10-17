package com.scanit.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanit.api.security.Tenant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.Map;

/** Ghi nhat ky thao tac quan tri (ai lam gi voi doi tuong nao). Chay chung giao dich voi thao tac goi no. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final IdGenerator ids;

    public AuditLog(JdbcClient jdbc, ObjectMapper mapper, IdGenerator ids) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ids = ids;
    }

    public void record(long userId, String action, String entityType, Long entityId, Map<String, ?> changes) {
        String json;
        try {
            json = changes == null ? null : mapper.writeValueAsString(changes);
        } catch (Exception e) {
            json = null;
        }
        jdbc.sql("""
                        INSERT INTO audit_logs (id, shop_id, user_id, action, entity_type, entity_id, changes)
                        VALUES (:id, :shop, :u, :a, :t, :i, CAST(:c AS jsonb))
                        """)
                .param("id", ids.next()).param("shop", Tenant.shopId()).param("u", userId).param("a", action).param("t", entityType)
                .param("i", entityId, Types.BIGINT).param("c", json, Types.VARCHAR)
                .update();
    }
}
