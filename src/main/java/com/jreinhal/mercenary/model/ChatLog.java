package com.jreinhal.mercenary.model;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="chat_history")
public class ChatLog {
    @Id
    private String id;
    private String workspaceId;
    private String department;
    private String role;
    private String content;
    private LocalDateTime timestamp;

    public ChatLog() {
    }

    public ChatLog(String workspaceId, String department, String role, String content) {
        this.workspaceId = workspaceId;
        this.department = department;
        this.role = role;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    public String getId() {
        return this.id;
    }

    public String getDepartment() {
        return this.department;
    }

    public String getWorkspaceId() {
        return this.workspaceId;
    }

    public String getRole() {
        return this.role;
    }

    public String getContent() {
        return this.content;
    }

    public LocalDateTime getTimestamp() {
        return this.timestamp;
    }
}
