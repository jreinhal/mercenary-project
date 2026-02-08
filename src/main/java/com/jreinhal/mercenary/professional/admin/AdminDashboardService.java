package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.RagOrchestrationService;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {
    private static final Logger log = LoggerFactory.getLogger(AdminDashboardService.class);
    private static final Instant START_TIME = Instant.now();
    private static final String CHAT_COLLECTION = "chat_history";
    private static final String VECTOR_COLLECTION = "vector_store";

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final RagOrchestrationService ragOrchestrationService;
    private final String ollamaBaseUrl;

    public AdminDashboardService(
            UserRepository userRepository,
            MongoTemplate mongoTemplate,
            RagOrchestrationService ragOrchestrationService,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.ragOrchestrationService = ragOrchestrationService;
        this.ollamaBaseUrl = ollamaBaseUrl;
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
        long totalQueries = this.ragOrchestrationService.getQueryCount();
        long queriesLast24h = this.countRecentEntries(CHAT_COLLECTION, "timestamp", 24);
        long totalDocuments = this.countCollection(VECTOR_COLLECTION);
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
        return (double) this.ragOrchestrationService.getAverageLatencyMs();
    }

    private Map<String, Long> getQueriesByDay(int days) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<String, Long>();
        for (int i = days - 1; i >= 0; --i) {
            Instant dayStart = Instant.now().minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1L, ChronoUnit.DAYS);
            try {
                Query query = new Query();
                query.addCriteria((CriteriaDefinition)Criteria.where((String)"timestamp").gte(dayStart).lt(dayEnd));
                long count = this.mongoTemplate.count(query, CHAT_COLLECTION);
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
        boolean ollamaConnected = this.checkOllamaConnection();
        if (!ollamaConnected) {
            warnings.add("Ollama LLM service unreachable");
        }
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / 0x100000L;
        if ((double)memoryUsed > (double)(memoryMax = runtime.maxMemory() / 0x100000L) * 0.9) {
            warnings.add("Memory usage above 90%");
        }
        double cpuUsage = this.getSystemCpuLoad();
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

    private boolean checkOllamaConnection() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(this.ollamaBaseUrl + "/api/tags").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200;
        } catch (Exception e) {
            log.warn("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }

    private double getSystemCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getSystemLoadAverage();
            if (load < 0) {
                // getSystemLoadAverage() returns -1 on Windows; fall back to available processors ratio
                int processors = osBean.getAvailableProcessors();
                return Math.min(1.0, Runtime.getRuntime().availableProcessors() > 0 ? 0.0 : 0.0);
            }
            int processors = osBean.getAvailableProcessors();
            return Math.min(1.0, load / Math.max(1, processors));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String calculateUptime() {
        Duration uptime = Duration.between(START_TIME, Instant.now());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }

    public DocumentStats getDocumentStats() {
        long totalDocuments = this.countCollection(VECTOR_COLLECTION);
        long documentsLast24h = this.countRecentEntries(VECTOR_COLLECTION, "metadata.ingested_at", 24);
        Map<String, Long> documentsByType = this.aggregateField(VECTOR_COLLECTION, "metadata.mimeType");
        Map<String, Long> documentsBySector = this.aggregateField(VECTOR_COLLECTION, "metadata.dept");
        return new DocumentStats(totalDocuments, documentsLast24h, documentsByType, documentsBySector);
    }

    private Map<String, Long> aggregateField(String collection, String field) {
        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.group(field).count().as("count")
            );
            AggregationResults<Document> results = this.mongoTemplate.aggregate(agg, collection, Document.class);
            LinkedHashMap<String, Long> map = new LinkedHashMap<>();
            for (Document doc : results.getMappedResults()) {
                String key = doc.getString("_id");
                if (key != null && !key.isEmpty()) {
                    Number count = doc.get("count", Number.class);
                    map.put(key, count != null ? count.longValue() : 0L);
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("Aggregation on {}.{} failed: {}", collection, field, e.getMessage());
            return Map.of();
        }
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
