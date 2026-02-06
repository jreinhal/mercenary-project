package com.jreinhal.mercenary.util;

public final class FilterExpressionBuilder {
    private static final String DEPT_TEMPLATE = "dept == '%s'";
    private static final String DEPT_TYPE_TEMPLATE = "dept == '%s' && type == '%s'";
    private static final String DEPT_EXCLUDE_TYPE_TEMPLATE = "dept == '%s' && type != '%s'";
    private static final String DEPT_WORKSPACE_TEMPLATE = "dept == '%s' && workspaceId == '%s'";
    private static final String DEPT_WORKSPACE_TYPE_TEMPLATE = "dept == '%s' && workspaceId == '%s' && type == '%s'";
    private static final String DEPT_WORKSPACE_EXCLUDE_TYPE_TEMPLATE = "dept == '%s' && workspaceId == '%s' && type != '%s'";

    private FilterExpressionBuilder() {
    }

    public static String forDepartment(String department) {
        return String.format(DEPT_TEMPLATE, escapeValue(department));
    }

    public static String forDepartmentAndWorkspace(String department, String workspaceId) {
        return String.format(DEPT_WORKSPACE_TEMPLATE, escapeValue(department), escapeValue(workspaceId));
    }

    public static String forDepartmentAndType(String department, String type) {
        return String.format(DEPT_TYPE_TEMPLATE, escapeValue(department), escapeValue(type));
    }

    public static String forDepartmentAndWorkspaceAndType(String department, String workspaceId, String type) {
        return String.format(DEPT_WORKSPACE_TYPE_TEMPLATE, escapeValue(department), escapeValue(workspaceId), escapeValue(type));
    }

    public static String forDepartmentExcludingType(String department, String excludeType) {
        return String.format(DEPT_EXCLUDE_TYPE_TEMPLATE, escapeValue(department), escapeValue(excludeType));
    }

    public static String forDepartmentAndWorkspaceExcludingType(String department, String workspaceId, String excludeType) {
        return String.format(DEPT_WORKSPACE_EXCLUDE_TYPE_TEMPLATE, escapeValue(department), escapeValue(workspaceId), escapeValue(excludeType));
    }

    private static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        // M-10: Escape backslashes before single quotes to prevent escape-sequence bypass
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
