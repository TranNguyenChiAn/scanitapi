package com.scanit.api.dev;

import com.scanit.api.common.IdGenerator;
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
    private final IdGenerator ids;

    public DevDataSeeder(JdbcClient jdbc, PasswordEncoder encoder, IdGenerator ids) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.ids = ids;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long users = jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
        if (users > 0) {
            log.info("Bo qua du lieu mau: bang users da co du lieu");
            return;
        }
        // Shop va chi nhanh mac dinh do migration 005 / 001 tao
        long shop = jdbc.sql("SELECT id FROM shops WHERE code = 'mei-bee'").query(Long.class).single();
        long store = jdbc.sql("SELECT id FROM stores WHERE shop_id = :shop ORDER BY id LIMIT 1")
                .param("shop", shop).query(Long.class).single();

        addUser(shop, "admin", "Quản trị viên", "ADMIN", "admin123", store);
        addUser(shop, "nhanvien", "Nhân viên bán hàng", "EMPLOYEE", "123456", store);

        long cat = ids.next();
        jdbc.sql("INSERT INTO categories (id, shop_id, name, slug) VALUES (:id, :shop, 'Set len', 'set-len')")
                .param("id", cat).param("shop", shop).update();
        long freeSize = ids.next();
        jdbc.sql("INSERT INTO sizes (id, shop_id, name, sort_order) VALUES (:id, :shop, 'Free size', 0)")
                .param("id", freeSize).param("shop", shop).update();
        long product = ids.next();
        jdbc.sql("""
                INSERT INTO products (id, shop_id, style_code, name, description, season, target_gender, product_type, components)
                VALUES (:id, :shop, '8922', 'Set len khoá kéo 8922#',
                        'Set áo khoác len mũ trùm khoá kéo phối chân váy dài chữ A.',
                        'Thu đông', 'FEMALE', 'SET', '["Áo khoác len", "Chân váy dài"]'::jsonb)""")
                .param("id", product).param("shop", shop).update();
        jdbc.sql("INSERT INTO product_categories (product_id, category_id) VALUES (:p, :c)")
                .param("p", product).param("c", cat).update();

        String[][] colors = {
                {"Xám than", "#4A4D52", "8922000001", "12", "8922-XAMTHAN"},
                {"Nâu đậm", "#4B3426", "8922000002", "3", "8922-NAUDAM"},
                {"Be", "#D9C7A7", "8922000003", "0", "8922-BE"}};
        for (int i = 0; i < colors.length; i++) {
            String[] c = colors[i];
            long color = ids.next();
            jdbc.sql("INSERT INTO colors (id, shop_id, name, hex_code) VALUES (:id, :shop, :n, :h)")
                    .param("id", color).param("shop", shop).param("n", c[0]).param("h", c[1]).update();
            long variant = ids.next();
            jdbc.sql("""
                    INSERT INTO product_variants (id, shop_id, product_id, color_id, size_id, sku, barcode, cost_price)
                    VALUES (:id, :shop, :p, :c, :s, :sku, :bc, 300000)""")
                    .param("id", variant).param("shop", shop).param("p", product).param("c", color).param("s", freeSize)
                    .param("sku", c[4]).param("bc", c[2]).update();
            jdbc.sql("INSERT INTO variant_prices (id, variant_id, price_type, min_qty, price) VALUES (:id, :v, 'WHOLESALE', 1, 410000)")
                    .param("id", ids.next()).param("v", variant).update();
            jdbc.sql("INSERT INTO variant_prices (id, variant_id, price_type, min_qty, price) VALUES (:id, :v, 'RETAIL', 1, 490000)")
                    .param("id", ids.next()).param("v", variant).update();
            jdbc.sql("INSERT INTO stock_levels (shop_id, store_id, variant_id, quantity, min_quantity) VALUES (:shop, :s, :v, :q, 5)")
                    .param("shop", shop).param("s", store).param("v", variant).param("q", Integer.parseInt(c[3])).update();
        }
        log.info("Da tao du lieu mau (cua hang mei-bee): admin/admin123, nhanvien/123456, set 8922 (3 mau)");
    }

    private void addUser(long shop, String username, String fullName, String role, String password, long store) {
        jdbc.sql("""
                INSERT INTO users (id, shop_id, store_id, role_id, username, full_name, password_hash)
                VALUES (:id, :shop, :store, (SELECT id FROM roles WHERE code = :role), :u, :n, :h)""")
                .param("id", ids.next()).param("shop", shop).param("store", store).param("role", role).param("u", username)
                .param("n", fullName).param("h", encoder.encode(password)).update();
    }
}
