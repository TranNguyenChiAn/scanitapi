package com.scanit.api.config;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Id Snowflake (~1e17..4e18) vuot 2^53 nen JavaScript (app, web) doc so se mat chinh xac.
 * Moi truong kieu long/Long ten "id" hoac ket thuc bang "Id" (colorId, productId...) duoc tra ve dang CHUOI.
 * Phia nhan van gui id dang so hoac chuoi deu duoc (Jackson tu doi chuoi -> so).
 */
@Configuration
public class JacksonConfig {

    @Bean
    Module idAsStringModule() {
        SimpleModule module = new SimpleModule("id-as-string");
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
                                                             List<BeanPropertyWriter> props) {
                for (BeanPropertyWriter w : props) {
                    Class<?> type = w.getType().getRawClass();
                    String name = w.getName();
                    if ((type == long.class || type == Long.class) && (name.equals("id") || name.endsWith("Id"))) {
                        w.assignSerializer(ToStringSerializer.instance);
                    }
                }
                return props;
            }
        });
        return module;
    }
}
