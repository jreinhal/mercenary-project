/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.data.annotation.Id
 *  org.springframework.data.mongodb.core.mapping.Document
 */
package com.jreinhal.mercenary.model;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="chat_history")
public class ChatLog {
    @Id
    private String id;
    private String department;
    private String role;
    private String content;
    private LocalDateTime timestamp;

    public ChatLog() {
    }

    public ChatLog(String department, String role, String content) {
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
