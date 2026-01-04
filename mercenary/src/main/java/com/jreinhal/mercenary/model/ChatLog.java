package com.jreinhal.mercenary.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Document(collection = "chat_history")
public class ChatLog {

    @Id
    private String id;
    private String department; // The "Silo" key (e.g., "HR")
    private String role;       // "user" or "assistant"
    private String content;    // The text message
    private LocalDateTime timestamp;

    public ChatLog() {}

    public ChatLog(String department, String role, String content) {
        this.department = department;
        this.role = role;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getDepartment() { return department; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
}