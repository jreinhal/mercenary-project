package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Admin dashboard service for system management and monitoring.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Features:
 * - User management (list, activate, deactivate, role assignment)
 * - Usage statistics and analytics
 * - System health monitoring
 * - Document ingestion status
 * - Query analytics
 */
@Service
public class AdminDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardService.class);

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public AdminDashboardService(UserRepository userRepository, MongoTemplate mongoTemplate) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    // ==================== User Management ====================

    /**
     * User summary for admin view.
     */
    public record UserSummary(
        String id,
        String username,
        String displayName,
        Set<UserRole> roles,
        boolean active,
        Instant lastLogin,
        Instant createdAt
    ) {}

    /**
     * Get all users for admin management.
     */
    public List<UserSummary> getAllUsers() {
        return userRepository.findAll().stream()
            .map(u -> new UserSummary(
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                u.getRoles(),
                u.isActive(),
                u.getLastLoginAt(),
                u.getCreatedAt()
            ))
            .toList();
    }

    /**
     * Get users pending approval.
     */
    public List<UserSummary> getPendingApprovals() {
        return userRepository.findAll().stream()
            .filter(User::isPendingApproval)
            .map(u -> new UserSummary(
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                u.getRoles(),
                u.isActive(),
                u.getLastLoginAt(),
                u.getCreatedAt()
            ))
            .toList();
    }

    /**
     * Approve a pending user.
     */
    public boolean approveUser(String userId) {
        return userRepository.findById(userId)
            .map(user -> {
                user.setPendingApproval(false);
                user.setActive(true);
                userRepository.save(user);
                log.info("Approved user: {}", user.getUsername());
                return true;
            })
            .orElse(false);
    }

    /**
     * Deactivate a user.
     */
    public boolean deactivateUser(String userId) {
        return userRepository.findById(userId)
            .map(user -> {
                user.setActive(false);
                userRepository.save(user);
                log.info("Deactivated user: {}", user.getUsername());
                return true;
            })
            .orElse(false);
    }

    /**
     * Activate a user.
     */
    public boolean activateUser(String userId) {
        return userRepository.findById(userId)
            .map(user -> {
                user.setActive(true);
                userRepository.save(user);
                log.info("Activated user: {}", user.getUsername());
                return true;
            })
            .orElse(false);
    }

    /**
     * Update user roles.
     */
    public boolean updateUserRoles(String userId, Set<UserRole> newRoles) {
        return userRepository.findById(userId)
            .map(user -> {
                user.setRoles(newRoles);
                userRepository.save(user);
                log.info("Updated roles for user {}: {}", user.getUsername(), newRoles);
                return true;
            })
            .orElse(false);
    }

    // ==================== Usage Statistics ====================

    /**
     * System usage statistics.
     */
    public record UsageStats(
        long totalUsers,
        long activeUsers,
        long totalQueries,
        long queriesLast24h,
        long totalDocuments,
        double avgQueryTime,
        Map<String, Long> queriesByDay
    ) {}

    /**
     * Get system usage statistics.
     */
    public UsageStats getUsageStats() {
        long totalUsers = userRepository.count();

        long activeUsers = userRepository.findAll().stream()
            .filter(User::isActive)
            .count();

        // Query statistics from chat logs
        long totalQueries = countCollection("chat_logs");
        long queriesLast24h = countRecentEntries("chat_logs", "timestamp", 24);

        // Document count
        long totalDocuments = countCollection("vector_store");

        // Average query time (from logs)
        double avgQueryTime = calculateAverageQueryTime();

        // Queries by day for last 7 days
        Map<String, Long> queriesByDay = getQueriesByDay(7);

        return new UsageStats(
            totalUsers,
            activeUsers,
            totalQueries,
            queriesLast24h,
            totalDocuments,
            avgQueryTime,
            queriesByDay
        );
    }

    /**
     * Count documents in a collection.
     */
    private long countCollection(String collectionName) {
        try {
            return mongoTemplate.count(new Query(), collectionName);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Count recent entries in a collection.
     */
    private long countRecentEntries(String collection, String timestampField, int hours) {
        try {
            Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
            Query query = new Query(Criteria.where(timestampField).gte(cutoff));
            return mongoTemplate.count(query, collection);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Calculate average query response time.
     */
    private double calculateAverageQueryTime() {
        // This would query actual timing data from logs
        // Placeholder implementation
        return 1.5; // seconds
    }

    /**
     * Get query counts by day.
     */
    private Map<String, Long> getQueriesByDay(int days) {
        Map<String, Long> result = new LinkedHashMap<>();

        for (int i = days - 1; i >= 0; i--) {
            Instant dayStart = Instant.now().minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);

            try {
                Query query = new Query();
                query.addCriteria(Criteria.where("timestamp").gte(dayStart).lt(dayEnd));
                long count = mongoTemplate.count(query, "chat_logs");

                String dateKey = dayStart.toString().substring(0, 10);
                result.put(dateKey, count);
            } catch (Exception e) {
                // Skip this day
            }
        }

        return result;
    }

    // ==================== System Health ====================

    /**
     * System health status.
     */
    public record HealthStatus(
        boolean mongoConnected,
        boolean ollamaConnected,
        long memoryUsedMb,
        long memoryMaxMb,
        double cpuUsage,
        String uptime,
        List<String> warnings
    ) {}

    /**
     * Get system health status.
     */
    public HealthStatus getHealthStatus() {
        List<String> warnings = new ArrayList<>();

        // MongoDB connection check
        boolean mongoConnected = checkMongoConnection();
        if (!mongoConnected) {
            warnings.add("MongoDB connection failed");
        }

        // Ollama check (placeholder - would actually ping Ollama)
        boolean ollamaConnected = true; // Assume connected

        // Memory stats
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long memoryMax = runtime.maxMemory() / (1024 * 1024);

        if (memoryUsed > memoryMax * 0.9) {
            warnings.add("Memory usage above 90%");
        }

        // CPU usage (placeholder)
        double cpuUsage = 0.35;

        // Uptime
        String uptime = calculateUptime();

        return new HealthStatus(
            mongoConnected,
            ollamaConnected,
            memoryUsed,
            memoryMax,
            cpuUsage,
            uptime,
            warnings
        );
    }

    /**
     * Check MongoDB connection.
     */
    private boolean checkMongoConnection() {
        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
            return true;
        } catch (Exception e) {
            log.error("MongoDB connection check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Calculate uptime (placeholder).
     */
    private String calculateUptime() {
        // Would need to track actual start time
        return "Unknown";
    }

    // ==================== Document Management ====================

    /**
     * Document ingestion status.
     */
    public record DocumentStats(
        long totalDocuments,
        long documentsLast24h,
        Map<String, Long> documentsByType,
        Map<String, Long> documentsBySector
    ) {}

    /**
     * Get document statistics.
     */
    public DocumentStats getDocumentStats() {
        // Placeholder - would query actual document metadata
        return new DocumentStats(
            countCollection("vector_store"),
            countRecentEntries("vector_store", "metadata.ingested_at", 24),
            Map.of("pdf", 100L, "docx", 50L, "txt", 30L),
            Map.of("GOVERNMENT", 80L, "FINANCE", 60L, "MEDICAL", 40L)
        );
    }
}
