package com.jreinhal.mercenary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jreinhal.mercenary.Department;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MongoConversionConfigTest {
    private final MongoConversionConfig.DepartmentReadingConverter readingConverter =
            new MongoConversionConfig.DepartmentReadingConverter();
    private final MongoConversionConfig.DepartmentWritingConverter writingConverter =
            new MongoConversionConfig.DepartmentWritingConverter();

    @Test
    void readingConverterMapsLegacyAcademicToEnterprise() {
        assertEquals(Department.ENTERPRISE, readingConverter.convert("ACADEMIC"));
    }

    @Test
    void readingConverterMapsLegacyFinanceToEnterprise() {
        assertEquals(Department.ENTERPRISE, readingConverter.convert("finance"));
    }

    @Test
    void readingConverterFallsBackToEnterpriseForUnknownOrBlankValues() {
        assertEquals(Department.ENTERPRISE, readingConverter.convert("UNKNOWN_SECTOR"));
        assertEquals(Department.ENTERPRISE, readingConverter.convert("   "));
        assertEquals(Department.ENTERPRISE, readingConverter.convert(null));
    }

    @Test
    void writingConverterPersistsEnumName() {
        assertEquals("MEDICAL", writingConverter.convert(Department.MEDICAL));
        assertNull(writingConverter.convert(null));
    }

    @Test
    void readingConverterProducesNonNullDepartmentsForCollectionMapping() {
        Set<Department> converted = Stream.of("MEDICAL", "ACADEMIC", "UNKNOWN_SECTOR")
                .map(readingConverter::convert)
                .collect(Collectors.toSet());
        assertFalse(converted.contains(null));
        assertTrue(converted.contains(Department.MEDICAL));
        assertTrue(converted.contains(Department.ENTERPRISE));
    }
}
