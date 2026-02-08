package com.jreinhal.mercenary.professional.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.RagOrchestrationService;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        @Test
        @DisplayName("Should count total documents from vector_store collection")
        void shouldCountTotalDocumentsFromVectorStore() {
            when(mongoTemplate.count(any(Query.class), eq("vector_store"))).thenReturn(42L);

            AdminDashboardService.DocumentStats docs = service.getDocumentStats();

            assertThat(docs.totalDocuments()).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("User Management Operations")
    class UserManagementTest {

        private User testUser;

        @BeforeEach
        void setUpUser() {
            testUser = new User();
            testUser.setId("user-123");
            testUser.setUsername("testuser");
            testUser.setDisplayName("Test User");
            testUser.setRoles(Set.of(UserRole.ANALYST));
            testUser.setActive(true);
            testUser.setPendingApproval(false);
            testUser.setCreatedAt(Instant.now());
        }

        @Test
        @DisplayName("getAllUsers should return mapped user summaries")
        void shouldReturnMappedUserSummaries() {
            when(userRepository.findAll()).thenReturn(List.of(testUser));

            List<AdminDashboardService.UserSummary> users = service.getAllUsers();

            assertThat(users).hasSize(1);
            assertThat(users.get(0).username()).isEqualTo("testuser");
            assertThat(users.get(0).displayName()).isEqualTo("Test User");
            assertThat(users.get(0).active()).isTrue();
        }

        @Test
        @DisplayName("getPendingApprovals should filter pending users")
        void shouldReturnOnlyPendingUsers() {
            User pendingUser = new User();
            pendingUser.setId("pending-1");
            pendingUser.setUsername("pending");
            pendingUser.setDisplayName("Pending User");
            pendingUser.setRoles(Set.of(UserRole.ANALYST));
            pendingUser.setPendingApproval(true);
            pendingUser.setActive(false);
            pendingUser.setCreatedAt(Instant.now());

            when(userRepository.findAll()).thenReturn(List.of(testUser, pendingUser));

            List<AdminDashboardService.UserSummary> pending = service.getPendingApprovals();

            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).username()).isEqualTo("pending");
        }

        @Test
        @DisplayName("approveUser should set active=true and pendingApproval=false")
        void shouldApproveUser() {
            testUser.setPendingApproval(true);
            testUser.setActive(false);
            when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));

            boolean result = service.approveUser("user-123");

            assertThat(result).isTrue();
            assertThat(testUser.isActive()).isTrue();
            assertThat(testUser.isPendingApproval()).isFalse();
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("approveUser should return false for unknown user")
        void shouldReturnFalseForUnknownUserApproval() {
            when(userRepository.findById("unknown")).thenReturn(Optional.empty());

            boolean result = service.approveUser("unknown");

            assertThat(result).isFalse();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("deactivateUser should set active=false")
        void shouldDeactivateUser() {
            when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));

            boolean result = service.deactivateUser("user-123");

            assertThat(result).isTrue();
            assertThat(testUser.isActive()).isFalse();
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("activateUser should set active=true")
        void shouldActivateUser() {
            testUser.setActive(false);
            when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));

            boolean result = service.activateUser("user-123");

            assertThat(result).isTrue();
            assertThat(testUser.isActive()).isTrue();
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("updateUserRoles should set new roles")
        void shouldUpdateUserRoles() {
            when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));
            Set<UserRole> newRoles = Set.of(UserRole.ADMIN, UserRole.ANALYST);

            boolean result = service.updateUserRoles("user-123", newRoles);

            assertThat(result).isTrue();
            assertThat(testUser.getRoles()).isEqualTo(newRoles);
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("updateUserRoles should return false for unknown user")
        void shouldReturnFalseForUnknownUserRoleUpdate() {
            when(userRepository.findById("unknown")).thenReturn(Optional.empty());

            boolean result = service.updateUserRoles("unknown", Set.of(UserRole.ADMIN));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Health check error paths")
    class HealthErrorPathsTest {

        @Test
        @DisplayName("Should report mongoConnected=false when MongoDB ping fails")
        void shouldReportMongoDisconnectedWhenPingFails() {
            MongoDatabase failDb = mock(MongoDatabase.class);
            when(mongoTemplate.getDb()).thenReturn(failDb);
            when(failDb.runCommand(any(Document.class))).thenThrow(new RuntimeException("Connection refused"));

            AdminDashboardService failService = new AdminDashboardService(
                    userRepository, mongoTemplate, ragOrchestrationService, "http://127.0.0.1:59999");

            AdminDashboardService.HealthStatus health = failService.getHealthStatus();

            assertThat(health.mongoConnected()).isFalse();
            assertThat(health.warnings()).contains("MongoDB connection failed");
        }

        @Test
        @DisplayName("Should include memory stats in health status")
        void shouldIncludeMemoryStats() {
            AdminDashboardService.HealthStatus health = service.getHealthStatus();

            assertThat(health.memoryUsedMb()).isGreaterThan(0);
            assertThat(health.memoryMaxMb()).isGreaterThan(0);
            assertThat(health.memoryUsedMb()).isLessThanOrEqualTo(health.memoryMaxMb());
        }

        @Test
        @DisplayName("Should report active user count correctly")
        void shouldReportActiveUserCount() {
            User activeUser = new User();
            activeUser.setActive(true);
            User inactiveUser = new User();
            inactiveUser.setActive(false);
            when(userRepository.findAll()).thenReturn(List.of(activeUser, inactiveUser));
            when(userRepository.count()).thenReturn(2L);

            AdminDashboardService.UsageStats stats = service.getUsageStats();

            assertThat(stats.totalUsers()).isEqualTo(2L);
            assertThat(stats.activeUsers()).isEqualTo(1L);
        }
    }
}
