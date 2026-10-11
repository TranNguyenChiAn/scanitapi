package com.scanit.api.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Du lieu mau de thu app. Chi chay khi SEED_DEV_DATA=true va bang users dang trong.
 * Tai khoan: admin / admin123 (ADMIN), nhanvien / 123456 (EMPLOYEE).
 * Gia le la so GIA DINH de thu; gia si 410.000 lay tu tem "Buon 410" cua cua hang.
 */
@Component
@ConditionalOnProperty(name = "scanit.seed-dev-data", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public DevDataSeeder(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long users = jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
        if (users > 0) {
            log.info("Bo qua du lieu mau: bang users da co du lieu");
            return;
        }
        long store = jdbc.sql("SELECT id FROM stores WHERE code = 'MAIN'").query(Long.class).single();

        addUser("admin", "Quản trị viên", "ADMIN", "admin123", store);
        addUser("nhanvien", "Nhân viên bán hàng", "EMPLOYEE", "123456", store);

        long cat = jdbc.sql("INSERT INTO categories (name, slug) VALUES ('Set len', 'set-len') RETURNING id")
                .query(Long.class).single();
        long freeSize = jdbc.sql("INSERT INTO sizes (name, sort_order) VALUES ('Free size', 0) RETURNING id")
                .query(Long.class).single();
        long product = jdbc.sql("""
                INSERT INTO products (style_code, name, description, season, target_gender, product_type, components)
                VALUES ('8922', 'Set len khoá kéo 8922#',
                        'Set áo khoác len mũ trùm khoá kéo phối chân váy dài chữ A.',
                        'Thu đông', 'FEMALE', 'SET', '["Áo khoác len", "Chân váy dài"]'::jsonb)
                RETURNING id""").query(Long.class).single();
        jdbc.sql("INSERT INTO product_categories (product_id, category_id) VALUES (:p, :c)")
                .param("p", product).param("c", cat).update();

        String[][] colors = {
                {"Xám than", "#4A4D52", "8922000001", "12", "8922-XAMTHAN"},
                {"Nâu đậm", "#4B3426", "8922000002", "3", "8922-NAUDAM"},
                {"Be", "#D9C7A7", "8922000003", "0", "8922-BE"}};
        for (int i = 0; i < colors.length; i++) {
            String[] c = colors[i];
            long color = jdbc.sql("INSERT INTO colors (name, hex_code) VALUES (:n, :h) RETURNING id")
                    .param("n", c[0]).param("h", c[1]).query(Long.class).single();
            long variant = jdbc.sql("""
                    INSERT INTO product_variants (product_id, color_id, size_id, sku, barcode, cost_price)
                    VALUES (:p, :c, :s, :sku, :bc, 300000) RETURNING id""")
                    .param("p", product).param("c", color).param("s", freeSize)
                    .param("sku", c[4])
                    .param("bc", c[2]).query(Long.class).single();
            jdbc.sql("INSERT INTO variant_prices (variant_id, price_type, min_qty, price) VALUES (:v, 'WHOLESALE', 1, 410000)")
                    .param("v", variant).update();
            jdbc.sql("INSERT INTO variant_prices (variant_id, price_type, min_qty, price) VALUES (:v, 'RETAIL', 1, 490000)")
                    .param("v", variant).update();
            jdbc.sql("INSERT INTO stock_levels (store_id, variant_id, quantity, min_quantity) VALUES (:s, :v, :q, 5)")
                    .param("s", store).param("v", variant).param("q", Integer.parseInt(c[3])).update();
        }
        log.info("Da tao du lieu mau: admin/admin123, nhanvien/123456, set 8922 (3 mau)");
    }

    private void addUser(String username, String fullName, String role, String password, long store) {
        jdbc.sql("""
                INSERT INTO users (store_id, role_id, username, full_name, password_hash)
                VALUES (:store, (SELECT id FROM roles WHERE code = :role), :u, :n, :h)""")
                .param("store", store).param("role", role).param("u", username)
                .param("n", fullName).param("h", encoder.encode(password)).update();
    }
}
