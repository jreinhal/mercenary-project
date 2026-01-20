/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bson.Document
 *  org.bson.conversions.Bson
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {
    private static final Logger log = LoggerFactory.getLogger(AdminDashboardService.class);
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public AdminDashboardService(UserRepository userRepository, MongoTemplate mongoTemplate) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public List<UserSummary> getAllUsers() {
        return this.userRepository.findAll().stream().map(u -> new UserSummary(u.getId(), u.getUsername(), u.getDisplayName(), u.getRoles(), u.isActive(), u.getLastLoginAt(), u.getCreatedAt())).toList();
    }

    public List<UserSummary> getPendingApprovals() {
        return this.userRepository.findAll().stream().filter(User::isPendingApproval).map(u -> new UserSummary(u.getId(), u.getUsername(), u.getDisplayName(), u.getRoles(), u.isActive(), u.getLastLoginAt(), u.getCreatedAt())).toList();
    }

    public boolean approveUser(String userId) {
        return this.userRepository.findById(userId).map(user -> {
            user.setPendingApproval(false);
            user.setActive(true);
            this.userRepository.save(user);
            log.info("Approved user: {}", user.getUsername());
            return true;
        }).orElse(false);
    }

    public boolean deactivateUser(String userId) {
        return this.userRepository.findById(userId).map(user -> {
            user.setActive(false);
            this.userRepository.save(user);
            log.info("Deactivated user: {}", user.getUsername());
            return true;
        }).orElse(false);
    }

    public boolean activateUser(String userId) {
        return this.userRepository.findById(userId).map(user -> {
            user.setActive(true);
            this.userRepository.save(user);
            log.info("Activated user: {}", user.getUsername());
            return true;
        }).orElse(false);
    }

    public boolean updateUserRoles(String userId, Set<UserRole> newRoles) {
        return this.userRepository.findById(userId).map(user -> {
            user.setRoles(newRoles);
            this.userRepository.save(user);
            log.info("Updated roles for user {}: {}", user.getUsername(), newRoles);
            return true;
        }).orElse(false);
    }

    public UsageStats getUsageStats() {
        long totalUsers = this.userRepository.count();
        long activeUsers = this.userRepository.findAll().stream().filter(User::isActive).count();
        long totalQueries = this.countCollection("chat_logs");
        long queriesLast24h = this.countRecentEntries("chat_logs", "timestamp", 24);
        long totalDocuments = this.countCollection("vector_store");
        double avgQueryTime = this.calculateAverageQueryTime();
        Map<String, Long> queriesByDay = this.getQueriesByDay(7);
        return new UsageStats(totalUsers, activeUsers, totalQueries, queriesLast24h, totalDocuments, avgQueryTime, queriesByDay);
    }

    private long countCollection(String collectionName) {
        try {
            return this.mongoTemplate.count(new Query(), collectionName);
        }
        catch (Exception e) {
            return 0L;
        }
    }

    private long countRecentEntries(String collection, String timestampField, int hours) {
        try {
            Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
            Query query = new Query((CriteriaDefinition)Criteria.where((String)timestampField).gte(cutoff));
            return this.mongoTemplate.count(query, collection);
        }
        catch (Exception e) {
            return 0L;
        }
    }

    private double calculateAverageQueryTime() {
        return 1.5;
    }

    private Map<String, Long> getQueriesByDay(int days) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<String, Long>();
        for (int i = days - 1; i >= 0; --i) {
            Instant dayStart = Instant.now().minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1L, ChronoUnit.DAYS);
            try {
                Query query = new Query();
                query.addCriteria((CriteriaDefinition)Criteria.where((String)"timestamp").gte(dayStart).lt(dayEnd));
                long count = this.mongoTemplate.count(query, "chat_logs");
                String dateKey = dayStart.toString().substring(0, 10);
                result.put(dateKey, count);
                continue;
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return result;
    }

    public HealthStatus getHealthStatus() {
        long memoryMax;
        ArrayList<String> warnings = new ArrayList<String>();
        boolean mongoConnected = this.checkMongoConnection();
        if (!mongoConnected) {
            warnings.add("MongoDB connection failed");
        }
        boolean ollamaConnected = true;
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / 0x100000L;
        if ((double)memoryUsed > (double)(memoryMax = runtime.maxMemory() / 0x100000L) * 0.9) {
            warnings.add("Memory usage above 90%");
        }
        double cpuUsage = 0.35;
        String uptime = this.calculateUptime();
        return new HealthStatus(mongoConnected, ollamaConnected, memoryUsed, memoryMax, cpuUsage, uptime, warnings);
    }

    private boolean checkMongoConnection() {
        try {
            this.mongoTemplate.getDb().runCommand((Bson)new Document("ping", 1));
            return true;
        }
        catch (Exception e) {
            log.error("MongoDB connection check failed: {}", e.getMessage());
            return false;
        }
    }

    private String calculateUptime() {
        return "Unknown";
    }

    public DocumentStats getDocumentStats() {
        return new DocumentStats(this.countCollection("vector_store"), this.countRecentEntries("vector_store", "metadata.ingested_at", 24), Map.of("pdf", 100L, "docx", 50L, "txt", 30L), Map.of("GOVERNMENT", 80L, "FINANCE", 60L, "MEDICAL", 40L));
    }

    public record UsageStats(long totalUsers, long activeUsers, long totalQueries, long queriesLast24h, long totalDocuments, double avgQueryTime, Map<String, Long> queriesByDay) {
    }

    public record HealthStatus(boolean mongoConnected, boolean ollamaConnected, long memoryUsedMb, long memoryMaxMb, double cpuUsage, String uptime, List<String> warnings) {
    }

    public record DocumentStats(long totalDocuments, long documentsLast24h, Map<String, Long> documentsByType, Map<String, Long> documentsBySector) {
    }

    public record UserSummary(String id, String username, String displayName, Set<UserRole> roles, boolean active, Instant lastLogin, Instant createdAt) {
    }
}
