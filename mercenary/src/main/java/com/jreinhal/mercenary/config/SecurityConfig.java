package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.security.CacUserDetailsService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration with profile-based behavior.
 *
 * Profiles:
 * - govcloud: Full X.509/CAC authentication, HTTPS required, strict security
 * - !govcloud: Development/standard mode with relaxed security
 */
@Configuration
public class SecurityConfig {

    private final CacUserDetailsService cacUserDetailsService;

    public SecurityConfig(CacUserDetailsService cacUserDetailsService) {
        this.cacUserDetailsService = cacUserDetailsService;
    }

    /**
     * GovCloud Security Configuration (IL4/IL5 Environments)
     *
     * Features:
     * - X.509 certificate authentication (CAC/PIV)
     * - HTTPS enforcement
     * - CSRF protection with cookie repository
     * - Strict transport security headers
     * - Role-based authorization
     */
    @Bean
    @Profile("govcloud")
    public SecurityFilterChain govSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // X.509 Certificate Authentication (CAC/PIV)
            .x509(x509 -> x509
                .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                .userDetailsService(cacUserDetailsService)
            )

            // HTTPS Enforcement - Required for government environments
            .requiresChannel(channel -> channel
                .anyRequest().requiresSecure()
            )

            // CSRF Protection with cookie-based token (for SPA compatibility)
            .csrf(csrf -> csrf
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/health", "/api/status")
            )

            // Security Headers (HSTS, X-Frame-Options, etc.)
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true)
                    .preload(true)
                )
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentTypeOptions(contentType -> {})
                .xssProtection(xss -> xss.disable()) // Modern browsers don't need this
            )

            // Authorization Rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (health checks, static assets)
                .requestMatchers("/api/health", "/api/status").permitAll()
                .requestMatchers("/", "/index.html", "/manual.html").permitAll()
                .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Admin endpoints require ADMIN role
                .requestMatchers("/api/admin/**").hasAuthority("ADMIN")

                // Ingest endpoints require OPERATOR or ADMIN role
                .requestMatchers("/api/ingest/**").hasAnyAuthority("OPERATOR", "ADMIN")

                // Query endpoints require authenticated user
                .requestMatchers("/api/ask/**", "/api/reasoning/**").authenticated()

                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()

                // Any other request requires authentication
                .anyRequest().authenticated()
            );

        return http.build();
    }

    /**
     * Development Security Configuration
     *
     * Permits all requests - authentication handled by custom SecurityFilter
     * which auto-provisions a DEMO_USER.
     */
    @Bean
    @Profile("dev")
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for dev mode (simpler testing)
            .csrf(csrf -> csrf.disable())

            // Security Headers
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentTypeOptions(contentType -> {})
            )

            // Permit all requests - custom SecurityFilter handles auth
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }

    /**
     * Standard Security Configuration (Enterprise / Commercial Mode)
     *
     * Provides baseline security for non-government deployments:
     * - CSRF protection enabled
     * - Security headers (X-Frame-Options, Content-Type)
     * - Public endpoints for static assets and health checks
     * - All API endpoints require authentication
     */
    @Bean
    @Profile({"enterprise", "standard"})
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF Protection with cookie-based token (for SPA compatibility)
            .csrf(csrf -> csrf
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/health", "/api/status")
            )

            // Security Headers
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentTypeOptions(contentType -> {})
            )

            // Authorization Rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (health checks, static assets)
                .requestMatchers("/api/health", "/api/status").permitAll()
                .requestMatchers("/", "/index.html", "/manual.html").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // All API endpoints require authentication
                .requestMatchers("/api/**").authenticated()

                // Static pages are public
                .anyRequest().permitAll()
            )

            // Form login for standard deployments
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .permitAll()
            );

        return http.build();
    }
}
