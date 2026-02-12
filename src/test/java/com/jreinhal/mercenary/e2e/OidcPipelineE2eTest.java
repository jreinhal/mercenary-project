package com.jreinhal.mercenary.e2e;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.ChatLogRepository;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import com.jreinhal.mercenary.repository.ReportExportRepository;
import com.jreinhal.mercenary.repository.ReportScheduleRepository;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.repository.WorkspaceRepository;
import com.jreinhal.mercenary.workspace.Workspace;
import com.jreinhal.mercenary.workspace.Workspace.WorkspaceQuota;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"ci-e2e", "enterprise"})
@AutoConfigureMockMvc
class OidcPipelineE2eTest {

    private static final String OIDC_ISSUER = "https://issuer.e2e.local";
    private static final String OIDC_CLIENT_ID = "sentinel-e2e-client";
    private static final String OIDC_SUBJECT = "oidc-e2e-subject";
    private static final RSAKey OIDC_RSA_KEY = generateRsaKey();
    private static final Path OIDC_JWKS_PATH = writeJwks(OIDC_RSA_KEY);
    private static final String OIDC_BEARER_TOKEN = signToken(OIDC_RSA_KEY, OIDC_SUBJECT);

    @DynamicPropertySource
    static void configureOidc(DynamicPropertyRegistry registry) {
        registry.add("app.auth-mode", () -> "OIDC");
        registry.add("app.oidc.issuer", () -> OIDC_ISSUER);
        registry.add("app.oidc.client-id", () -> OIDC_CLIENT_ID);
        registry.add("app.oidc.local-jwks-path", () -> OIDC_JWKS_PATH.toString());
        registry.add("app.oidc.authorization-uri", () -> "https://idp.e2e.local/authorize");
        registry.add("app.oidc.token-uri", () -> "https://idp.e2e.local/oauth/token");
        registry.add("app.guardrails.llm-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStore vectorStore;

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ChatLogRepository chatLogRepository;

    @MockitoBean
    private FeedbackRepository feedbackRepository;

    @MockitoBean
    private ReportScheduleRepository reportScheduleRepository;

    @MockitoBean
    private ReportExportRepository reportExportRepository;

    @MockitoBean
    private WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setup() {
        Workspace defaultWorkspace = new Workspace(
                "workspace_default",
                "Default Workspace",
                "Default workspace",
                "system",
                Instant.now(),
                Instant.now(),
                WorkspaceQuota.unlimited(),
                true);

        when(mongoTemplate.getCollection(anyString()).countDocuments()).thenReturn(0L);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null || user.getId().isBlank()) {
                user.setId("oidc-e2e-user");
            }
            return user;
        });
        when(userRepository.countByWorkspaceIdsContaining(anyString())).thenReturn(0L);
        when(userRepository.findByWorkspaceIdsContaining(anyString())).thenReturn(List.of());
        when(workspaceRepository.existsById(anyString())).thenReturn(true);
        when(workspaceRepository.findById(anyString())).thenReturn(Optional.of(defaultWorkspace));
        when(workspaceRepository.findAll()).thenReturn(List.of(defaultWorkspace));
        when(workspaceRepository.findByIdIn(any())).thenReturn(List.of(defaultWorkspace));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        if (vectorStore instanceof InMemoryVectorStore store) {
            store.clear();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "oidc_e2e_seed.txt");
            metadata.put("dept", "ENTERPRISE");
            vectorStore.add(List.of(new Document("The total program budget is $150M.", metadata)));
        }
    }

    @Test
    void authModeEndpointShowsOidcSsoEnabled() throws Exception {
        mockMvc.perform(get("/api/auth/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("OIDC"))
                .andExpect(jsonPath("$.ssoEnabled").value(true))
                .andExpect(jsonPath("$.authorizeUrl").value("/api/auth/oidc/authorize"));
    }

    @Test
    void oidcAuthorizeEndpointRedirectsToProvider() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/authorize"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("https://idp.e2e.local/authorize*"));
    }

    @Test
    void oidcBearerTokenCanQueryEnhancedEndpoint() throws Exception {
        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "What is the total program budget?")
                        .param("dept", "ENTERPRISE")
                        .header("Authorization", "Bearer " + OIDC_BEARER_TOKEN)
                        .header("X-Workspace-Id", "workspace_default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.routingDecision").value("CHUNK"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
    }

    private static RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("oidc-e2e-key")
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate OIDC E2E RSA key", e);
        }
    }

    private static Path writeJwks(RSAKey rsaKey) {
        try {
            Path path = Files.createTempFile("sentinel-oidc-e2e-jwks", ".json");
            Files.writeString(path, new JWKSet(rsaKey.toPublicJWK()).toString(), StandardCharsets.UTF_8);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create OIDC E2E JWKS file", e);
        }
    }

    private static String signToken(RSAKey rsaKey, String subject) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(OIDC_ISSUER)
                    .audience(OIDC_CLIENT_ID)
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .notBeforeTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("email", "oidc-e2e-user@example.com")
                    .claim("name", "OIDC E2E User")
                    .build();
            SignedJWT signedJwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                    claims);
            signedJwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
            return signedJwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign OIDC E2E JWT", e);
        }
    }

    @TestConfiguration
    static class VectorStoreTestConfig {
        @Bean
        @Primary
        VectorStore vectorStore() {
            return new InMemoryVectorStore();
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new StubChatModel();
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }
    }

    private static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Stub response"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptionsBuilder.builder().build();
        }
    }
}
