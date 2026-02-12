package com.jreinhal.mercenary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jreinhal.mercenary.Department;
import org.junit.jupiter.api.Test;

class MongoConversionConfigTest {
    private final MongoConversionConfig.DepartmentReadingConverter readingConverter =
            new MongoConversionConfig.DepartmentReadingConverter();
    private final MongoConversionConfig.DepartmentWritingConverter writingConverter =
            new MongoConversionConfig.DepartmentWritingConverter();

    @Test
    void readingConverterMapsLegacyAcademicToEnterprise() {
        assertEquals(Department.ENTERPRISE, readingConverter.convert("ACADEMIC"));
        assertEquals(Department.ENTERPRISE, readingConverter.convert("finance"));
    }

    @Test
    void readingConverterReturnsNullForUnknownOrBlankValues() {
        assertNull(readingConverter.convert("UNKNOWN_SECTOR"));
        assertNull(readingConverter.convert("   "));
        assertNull(readingConverter.convert(null));
    }

    @Test
    void writingConverterPersistsEnumName() {
        assertEquals("MEDICAL", writingConverter.convert(Department.MEDICAL));
        assertNull(writingConverter.convert(null));
    }
}
