package com.jreinhal.mercenary.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger UI Configuration for SENTINEL.
 *
 * Provides interactive API documentation at /swagger-ui.html
 * Supports multiple authentication modes (Dev, OIDC, CAC, Standard)
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:SENTINEL}")
    private String appName;

    @Value("${app.auth-mode:DEV}")
    private String authMode;

    @Bean
    public OpenAPI sentinelOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .components(securityComponents())
                .security(List.of(
                        new SecurityRequirement().addList("bearerAuth"),
                        new SecurityRequirement().addList("basicAuth"),
                        new SecurityRequirement().addList("devAuth")
                ))
                .servers(List.of(
                        new Server().url("/").description("Current Server")
                ))
                .tags(List.of(
                        new Tag().name("Query").description("RAG Query Operations"),
                        new Tag().name("Ingest").description("Document Ingestion"),
                        new Tag().name("System").description("System Status & Telemetry"),
                        new Tag().name("Reasoning").description("Glass Box Reasoning Traces"),
                        new Tag().name("Admin").description("Administrative Operations")
                ));
    }

    private Info apiInfo() {
        return new Info()
                .title("SENTINEL RAG Platform API")
                .description("""
                        **SENTINEL** - Enterprise RAG Platform with Advanced Security Features

                        ## Features
                        - **HiFi-RAG**: Iterative two-pass retrieval with cross-encoder reranking
                        - **RAGPart**: Corpus poisoning defense via document partitioning
                        - **HGMem**: Hypergraph memory for multi-hop reasoning
                        - **Glass Box**: Real-time pipeline transparency and tracing
                        - **PII Redaction**: NIST 800-122, GDPR, HIPAA, PCI-DSS compliant

                        ## Authentication Modes
                        - **DEV**: Development mode with simulated authentication
                        - **OIDC**: Enterprise SSO via Azure AD, Okta, etc.
                        - **CAC/PIV**: Government X.509 certificate authentication
                        - **STANDARD**: HTTP Basic Auth with BCrypt passwords

                        ## Current Mode: `%s`
                        """.formatted(authMode))
                .version("2.0.0")
                .contact(new Contact()
                        .name("SENTINEL Support")
                        .email("support@sentinel.ai"))
                .license(new License()
                        .name("Proprietary")
                        .url("https://sentinel.ai/license"));
    }

    private Components securityComponents() {
        return new Components()
                .addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT token from OIDC provider (Azure AD, Okta, etc.)"))
                .addSecuritySchemes("basicAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Username and password (STANDARD mode)"))
                .addSecuritySchemes("devAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Dev-User")
                                .description("Development mode: Set to 'admin' or any username"));
    }
}
