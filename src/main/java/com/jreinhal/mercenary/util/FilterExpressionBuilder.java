package com.jreinhal.mercenary.util;

public final class FilterExpressionBuilder {
    private static final String DEPT_TEMPLATE = "dept == '%s'";
    private static final String DEPT_TYPE_TEMPLATE = "dept == '%s' && type == '%s'";
    private static final String DEPT_EXCLUDE_TYPE_TEMPLATE = "dept == '%s' && type != '%s'";

    private FilterExpressionBuilder() {
    }

    public static String forDepartment(String department) {
        return String.format(DEPT_TEMPLATE, escapeValue(department));
    }

    public static String forDepartmentAndType(String department, String type) {
        return String.format(DEPT_TYPE_TEMPLATE, escapeValue(department), escapeValue(type));
    }

    public static String forDepartmentExcludingType(String department, String excludeType) {
        return String.format(DEPT_EXCLUDE_TYPE_TEMPLATE, escapeValue(department), escapeValue(excludeType));
    }

    private static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
    }
}
