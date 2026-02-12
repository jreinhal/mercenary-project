package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

@Configuration
public class MongoConversionConfig {
    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new DepartmentReadingConverter(), new DepartmentWritingConverter()));
    }

    @ReadingConverter
    static final class DepartmentReadingConverter implements Converter<String, Department> {
        private static final Logger log = LoggerFactory.getLogger(DepartmentReadingConverter.class);

        @Override
        public Department convert(String source) {
            if (source == null || source.isBlank()) {
                return Department.ENTERPRISE;
            }
            try {
                return Department.fromString(source);
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown department value '{}' from persisted user record. Falling back to ENTERPRISE.", source);
                return Department.ENTERPRISE;
            }
        }
    }

    @WritingConverter
    static final class DepartmentWritingConverter implements Converter<Department, String> {
        @Override
        public String convert(Department source) {
            return source == null ? null : source.name();
        }
    }
}
