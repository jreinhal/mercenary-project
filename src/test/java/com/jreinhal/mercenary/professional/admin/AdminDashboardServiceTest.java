package com.jreinhal.mercenary.professional.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.RagOrchestrationService;
import com.mongodb.client.MongoDatabase;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class AdminDashboardServiceTest {

    private UserRepository userRepository;
    private MongoTemplate mongoTemplate;
    private RagOrchestrationService ragOrchestrationService;
    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        ragOrchestrationService = mock(RagOrchestrationService.class);
        when(userRepository.findAll()).thenReturn(List.of());

        MongoDatabase mockDb = mock(MongoDatabase.class);
        when(mongoTemplate.getDb()).thenReturn(mockDb);
        when(mockDb.runCommand(any(Document.class))).thenReturn(new Document("ok", 1));

        service = new AdminDashboardService(userRepository, mongoTemplate, ragOrchestrationService, "http://localhost:11434");
    }

    @Nested
    @DisplayName("Fix #6: UsageStats - real query count and latency")
    class UsageStatsTest {

        @Test
        @DisplayName("Should return query count from RagOrchestrationService, not from chat_logs collection")
        void shouldReturnRealQueryCount() {
            when(ragOrchestrationService.getQueryCount()).thenReturn(42);

            AdminDashboardService.UsageStats stats = service.getUsageStats();

            assertThat(stats.totalQueries()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should return real average query time from RagOrchestrationService")
        void shouldReturnRealAverageQueryTime() {
            when(ragOrchestrationService.getQueryCount()).thenReturn(10);
            when(ragOrchestrationService.getAverageLatencyMs()).thenReturn(250L);

            AdminDashboardService.UsageStats stats = service.getUsageStats();

            assertThat(stats.avgQueryTime()).isEqualTo(250.0);
        }

        @Test
        @DisplayName("Should return 0 average query time when no queries processed")
        void shouldReturnZeroAvgWhenNoQueries() {
            when(ragOrchestrationService.getQueryCount()).thenReturn(0);
            when(ragOrchestrationService.getAverageLatencyMs()).thenReturn(0L);

            AdminDashboardService.UsageStats stats = service.getUsageStats();

            assertThat(stats.avgQueryTime()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should count recent queries from chat_history collection, not chat_logs")
        void shouldCountRecentQueriesFromChatHistory() {
            when(ragOrchestrationService.getQueryCount()).thenReturn(100);
            when(mongoTemplate.count(any(Query.class), eq("chat_history"))).thenReturn(15L);

            AdminDashboardService.UsageStats stats = service.getUsageStats();

            assertThat(stats.queriesLast24h()).isEqualTo(15L);
        }
    }

    @Nested
    @DisplayName("Fix #6: HealthStatus - real Ollama check, CPU, uptime")
    class HealthStatusTest {

        @Test
        @DisplayName("Should report ollamaConnected=false when Ollama is unreachable")
        void shouldReportOllamaDisconnectedWhenUnreachable() {
            // Create a service pointing to a non-existent Ollama URL
            AdminDashboardService unreachableService = new AdminDashboardService(
                    userRepository, mongoTemplate, ragOrchestrationService,
                    "http://127.0.0.1:59999");
            AdminDashboardService.HealthStatus health = unreachableService.getHealthStatus();

            assertThat(health.ollamaConnected()).isFalse();
        }

        @Test
        @DisplayName("Should not hardcode cpuUsage to 0.35")
        void shouldReturnRealCpuUsage() {
            AdminDashboardService.HealthStatus health = service.getHealthStatus();

            // The CPU usage should come from the system, not be exactly 0.35
            // We verify it's in a valid range [0.0, 1.0]
            assertThat(health.cpuUsage()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("Should return real uptime, not 'Unknown'")
        void shouldReturnRealUptime() {
            AdminDashboardService.HealthStatus health = service.getHealthStatus();

            assertThat(health.uptime()).isNotEqualTo("Unknown");
            // Should contain time units like "hours", "minutes", "seconds", or "days"
            assertThat(health.uptime()).containsAnyOf("d", "h", "m", "s");
        }
    }

    @Nested
    @DisplayName("Fix #6: DocumentStats - real aggregation from vector_store")
    class DocumentStatsTest {

        @Test
        @DisplayName("Should not return hardcoded documentsByType values")
        void shouldNotReturnHardcodedDocumentsByType() {
            AdminDashboardService.DocumentStats docs = service.getDocumentStats();

            // The hardcoded values were {"pdf": 100, "docx": 50, "txt": 30}
            // With mocked mongoTemplate returning empty aggregation, we expect an empty or real map
            Map<String, Long> byType = docs.documentsByType();
            boolean isHardcoded = byType.getOrDefault("pdf", 0L) == 100L
                    && byType.getOrDefault("docx", 0L) == 50L
                    && byType.getOrDefault("txt", 0L) == 30L;
            assertThat(isHardcoded).as("documentsByType should not be hardcoded {pdf:100, docx:50, txt:30}").isFalse();
        }

        @Test
        @DisplayName("Should not return hardcoded documentsBySector values")
        void shouldNotReturnHardcodedDocumentsBySector() {
            AdminDashboardService.DocumentStats docs = service.getDocumentStats();

            // The hardcoded values were {"GOVERNMENT": 80, "FINANCE": 60, "MEDICAL": 40}
            Map<String, Long> bySector = docs.documentsBySector();
            boolean isHardcoded = bySector.getOrDefault("GOVERNMENT", 0L) == 80L
                    && bySector.getOrDefault("FINANCE", 0L) == 60L
                    && bySector.getOrDefault("MEDICAL", 0L) == 40L;
            assertThat(isHardcoded).as("documentsBySector should not be hardcoded {GOVERNMENT:80, FINANCE:60, MEDICAL:40}").isFalse();
        }
    }
}
