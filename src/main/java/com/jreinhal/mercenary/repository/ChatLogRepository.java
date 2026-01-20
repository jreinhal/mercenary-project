package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.ChatLog;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatLogRepository
extends MongoRepository<ChatLog, String> {
    public List<ChatLog> findByDepartmentOrderByTimestampAsc(String var1);
}
