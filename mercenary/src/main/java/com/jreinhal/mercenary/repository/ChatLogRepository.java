package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.ChatLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ChatLogRepository extends MongoRepository<ChatLog, String> {

    // Finds all chat logs for a specific department, ordered by time.
    // This effectively "Replays" the conversation history.
    List<ChatLog> findByDepartmentOrderByTimestampAsc(String department);
}