package com.scanit.api.config;

import com.scanit.api.security.AuthUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mo ta API cho Swagger UI. Dang nhap: goi POST /api/auth/login, copy "token", bam Authorize va dan vao. */
@Configuration
public class OpenApiConfig {

    static {
        // Nguoi dung dang nhap do Spring Security chen vao, khong phai tham so client gui
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthUser.class);
    }

    @Bean
    OpenAPI scanItOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ScanIt API").version("1.0")
                        .description("""
                                Cách thử: gọi **POST /api/auth/login** (vd `admin` / `admin123`), copy giá trị `token` trong kết quả, \
                                bấm nút **Authorize** ở góc phải trên và dán token vào (không cần gõ chữ Bearer). \
                                Sau đó gọi được mọi API theo quyền của tài khoản. Lỗi 401 = thiếu/hết hạn token, 403 = thiếu quyền."""))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
