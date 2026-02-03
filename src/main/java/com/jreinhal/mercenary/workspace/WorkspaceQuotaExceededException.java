package com.jreinhal.mercenary.workspace;

public class WorkspaceQuotaExceededException extends RuntimeException {
    private final String quotaType;

    public WorkspaceQuotaExceededException(String quotaType, String message) {
        super(message);
        this.quotaType = quotaType;
    }

    public String getQuotaType() {
        return this.quotaType;
    }
}
