package com.scanit.api.media;

import com.scanit.api.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Configuration
public class MediaConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public MediaConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    MediaStorage mediaStorage() {
        var m = props.media();
        if ("r2".equalsIgnoreCase(m.storage())) {
            var r2 = m.r2();
            if (r2 == null || isBlank(r2.endpoint()) || isBlank(r2.bucket())
                    || isBlank(r2.accessKey()) || isBlank(r2.secretKey())) {
                throw new IllegalStateException(
                        "MEDIA_STORAGE=r2 nhung thieu R2_ENDPOINT / R2_BUCKET / R2_ACCESS_KEY / R2_SECRET_KEY");
            }
            if (isBlank(m.publicBaseUrl())) {
                throw new IllegalStateException("MEDIA_STORAGE=r2 can MEDIA_PUBLIC_BASE_URL (URL cong khai cua bucket)");
            }
            return new R2MediaStorage(r2.endpoint(), r2.bucket(), r2.accessKey(), r2.secretKey());
        }
        return new LocalMediaStorage(localRoot());
    }

    /** Che do local: phuc vu file tai /media/**, cache dai vi ten file la ma bam noi dung. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!"r2".equalsIgnoreCase(props.media().storage())) {
            registry.addResourceHandler("/media/**")
                    .addResourceLocations(withSlash(localRoot().toUri().toString()))
                    .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        }
    }

    private Path localRoot() {
        String dir = props.media().localDir();
        return Path.of(isBlank(dir) ? "./data/media" : dir).toAbsolutePath().normalize();
    }

    private static String withSlash(String uri) {
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
