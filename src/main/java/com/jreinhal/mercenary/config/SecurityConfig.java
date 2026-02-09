package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.filter.CorrelationIdFilter;
import com.jreinhal.mercenary.filter.PreAuthRateLimitFilter;
import com.jreinhal.mercenary.filter.RateLimitFilter;
import com.jreinhal.mercenary.filter.SecurityFilter;
import com.jreinhal.mercenary.security.CacUserDetailsService;
import com.jreinhal.mercenary.Department;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.ChannelSecurityConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final CacUserDetailsService cacUserDetailsService;
    private final SecurityFilter securityFilter;
    private final RateLimitFilter rateLimitFilter;
    private final PreAuthRateLimitFilter preAuthRateLimitFilter;
    private final CorrelationIdFilter correlationIdFilter;
    private final com.jreinhal.mercenary.service.HipaaPolicy hipaaPolicy;
    @Value(value="${app.auth-mode:DEV}")
    private String authMode;
    @Value(value="${app.csrf.bypass-ingest:false}")
    private boolean csrfBypassIngest;

    public SecurityConfig(CacUserDetailsService cacUserDetailsService, SecurityFilter securityFilter, RateLimitFilter rateLimitFilter, PreAuthRateLimitFilter preAuthRateLimitFilter, CorrelationIdFilter correlationIdFilter, com.jreinhal.mercenary.service.HipaaPolicy hipaaPolicy) {
        this.cacUserDetailsService = cacUserDetailsService;
        this.securityFilter = securityFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.preAuthRateLimitFilter = preAuthRateLimitFilter;
        this.correlationIdFilter = correlationIdFilter;
        this.hipaaPolicy = hipaaPolicy;
    }

    @Bean
    public FilterRegistrationBean<SecurityFilter> securityFilterRegistration(SecurityFilter filter) {
        FilterRegistrationBean<SecurityFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PreAuthRateLimitFilter> preAuthRateLimitFilterRegistration(PreAuthRateLimitFilter filter) {
        FilterRegistrationBean<PreAuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile(value={"govcloud"})
    public SecurityFilterChain govSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class)
                .x509(x509 -> x509.subjectPrincipalRegex("CN=(.*?)(?:,|$)").userDetailsService((UserDetailsService)this.cacUserDetailsService))
                .requiresChannel(channel -> ((ChannelSecurityConfigurer.RequiresChannelUrl)channel.anyRequest()).requiresSecure())
                .csrf(csrf -> {
                    csrf.csrfTokenRepository((CsrfTokenRepository)CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .ignoringRequestMatchers(new String[]{"/api/health"});
                    if (this.csrfBypassIngest) {
                        csrf.ignoringRequestMatchers(new String[]{"/api/ingest/**"});
                    }
                })
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000L).includeSubDomains(true).preload(true)).frameOptions(HeadersConfigurer.FrameOptionsConfig::deny).contentTypeOptions(contentType -> {}).xssProtection(xss -> xss.disable()))
                .authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)auth.requestMatchers(new String[]{"/api/health"})).permitAll().requestMatchers(new String[]{"/", "/index.html", "/manual.html"})).permitAll().requestMatchers(new String[]{"/css/**", "/js/**", "/vendor/**", "/fonts/**", "/images/**", "/favicon.ico"})).permitAll().requestMatchers(new String[]{"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"})).authenticated().requestMatchers(new String[]{"/api/auth/csrf"})).permitAll().requestMatchers(new String[]{"/api/admin/**"})).hasAuthority("ADMIN").requestMatchers(new String[]{"/api/ingest/**"})).hasAnyAuthority(new String[]{"OPERATOR", "ADMIN"}).requestMatchers(new String[]{"/api/ask/**", "/api/reasoning/**"})).authenticated().requestMatchers(new String[]{"/api/**"})).authenticated().anyRequest()).authenticated());
        return (SecurityFilterChain)http.build();
    }

    @Bean
    @Profile(value={"dev"})
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class)
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny).contentTypeOptions(contentType -> {}).referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)).addHeaderWriter(new PermissionsPolicyHeaderWriter("geolocation=(), microphone=(), camera=()")))
                .authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)auth.anyRequest()).permitAll());
        return (SecurityFilterChain)http.build();
    }

    @Bean
    @Profile(value={"enterprise", "standard"})
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class)
                .addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class)
                .requiresChannel(channel -> {
                    if (this.hipaaPolicy.shouldEnforceTls()) {
                        channel.anyRequest().requiresSecure();
                    }
                })
                .csrf(csrf -> {
                    csrf.csrfTokenRepository((CsrfTokenRepository)CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .ignoringRequestMatchers(new String[]{"/api/health"});
                    if (this.csrfBypassIngest) {
                        csrf.ignoringRequestMatchers(new String[]{"/api/ingest/**"});
                    }
                })
                .headers(headers -> {
                    if (this.hipaaPolicy.shouldEnforceTls()) {
                        headers.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000L).includeSubDomains(true).preload(true));
                    }
                    headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                            .contentTypeOptions(contentType -> {})
                            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                            .addHeaderWriter(new PermissionsPolicyHeaderWriter("geolocation=(), microphone=(), camera=()"));
                })
                .authorizeHttpRequests(auth -> {
                    // R-03: /api/status now requires auth (exposes operational metrics)
                    auth.requestMatchers(new String[]{"/api/health"}).permitAll();
                    auth.requestMatchers(new String[]{"/api/auth/**"}).permitAll();
                    auth.requestMatchers(new String[]{"/", "/index.html", "/manual.html", "/readme.html", "/docs-index.html", "/docs-index.md"}).permitAll();
                    auth.requestMatchers(new String[]{"/css/**", "/js/**", "/images/**", "/vendor/**", "/fonts/**", "/favicon.ico"}).permitAll();
                    // M-05: Swagger/OpenAPI endpoints now require authentication in all profiles
                    // (removed permitAll for swagger — API surface should not be exposed to anonymous users)
                    auth.requestMatchers(new String[]{"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"}).authenticated();
                    auth.requestMatchers(new String[]{"/api/admin/**"}).hasAuthority("ADMIN");
                    auth.requestMatchers(new String[]{"/api/**"}).authenticated();
                    // H-07: Match govcloud posture — deny-by-default for unmapped paths
                    auth.anyRequest().authenticated();
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return (SecurityFilterChain)http.build();
    }
}
