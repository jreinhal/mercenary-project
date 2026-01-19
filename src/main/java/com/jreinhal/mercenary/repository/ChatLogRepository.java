/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.ChatLog
 *  com.jreinhal.mercenary.repository.ChatLogRepository
 *  org.springframework.data.mongodb.repository.MongoRepository
 */
package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.ChatLog;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatLogRepository
extends MongoRepository<ChatLog, String> {
    public List<ChatLog> findByDepartmentOrderByTimestampAsc(String var1);
}

