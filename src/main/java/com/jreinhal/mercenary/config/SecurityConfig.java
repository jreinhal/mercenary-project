/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.servlet.Filter
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.boot.web.servlet.FilterRegistrationBean
 *  org.springframework.boot.web.servlet.ServletRegistrationBean
 *  org.springframework.context.annotation.Bean
 *  org.springframework.context.annotation.Configuration
 *  org.springframework.context.annotation.Profile
 *  org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
 *  org.springframework.security.config.annotation.web.builders.HttpSecurity
 *  org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
 *  org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer$AuthorizedUrl
 *  org.springframework.security.config.annotation.web.configurers.ChannelSecurityConfigurer$RequiresChannelUrl
 *  org.springframework.security.config.annotation.web.configurers.HeadersConfigurer$FrameOptionsConfig
 *  org.springframework.security.core.userdetails.UserDetailsService
 *  org.springframework.security.web.SecurityFilterChain
 *  org.springframework.security.web.access.intercept.AuthorizationFilter
 *  org.springframework.security.web.authentication.AnonymousAuthenticationFilter
 *  org.springframework.security.web.context.SecurityContextHolderFilter
 *  org.springframework.security.web.csrf.CookieCsrfTokenRepository
 *  org.springframework.security.web.csrf.CsrfTokenRepository
 *  org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter$ReferrerPolicy
 */
package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.filter.PreAuthRateLimitFilter;
import com.jreinhal.mercenary.filter.RateLimitFilter;
import com.jreinhal.mercenary.filter.SecurityFilter;
import com.jreinhal.mercenary.security.CacUserDetailsService;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final CacUserDetailsService cacUserDetailsService;
    private final SecurityFilter securityFilter;
    private final RateLimitFilter rateLimitFilter;
    private final PreAuthRateLimitFilter preAuthRateLimitFilter;
    @Value(value="${app.auth-mode:DEV}")
    private String authMode;

    public SecurityConfig(CacUserDetailsService cacUserDetailsService, SecurityFilter securityFilter, RateLimitFilter rateLimitFilter, PreAuthRateLimitFilter preAuthRateLimitFilter) {
        this.cacUserDetailsService = cacUserDetailsService;
        this.securityFilter = securityFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.preAuthRateLimitFilter = preAuthRateLimitFilter;
    }

    @Bean
    public FilterRegistrationBean<SecurityFilter> securityFilterRegistration(SecurityFilter filter) {
        FilterRegistrationBean registration = new FilterRegistrationBean((Filter)filter, new ServletRegistrationBean[0]);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean registration = new FilterRegistrationBean((Filter)filter, new ServletRegistrationBean[0]);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PreAuthRateLimitFilter> preAuthRateLimitFilterRegistration(PreAuthRateLimitFilter filter) {
        FilterRegistrationBean registration = new FilterRegistrationBean((Filter)filter, new ServletRegistrationBean[0]);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile(value={"govcloud"})
    public SecurityFilterChain govSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class).addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class).addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class).x509(x509 -> x509.subjectPrincipalRegex("CN=(.*?)(?:,|$)").userDetailsService((UserDetailsService)this.cacUserDetailsService)).requiresChannel(channel -> ((ChannelSecurityConfigurer.RequiresChannelUrl)channel.anyRequest()).requiresSecure()).csrf(csrf -> csrf.csrfTokenRepository((CsrfTokenRepository)CookieCsrfTokenRepository.withHttpOnlyFalse()).ignoringRequestMatchers(new String[]{"/api/health", "/api/status"})).headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000L).includeSubDomains(true).preload(true)).frameOptions(HeadersConfigurer.FrameOptionsConfig::deny).contentTypeOptions(contentType -> {}).xssProtection(xss -> xss.disable())).authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)auth.requestMatchers(new String[]{"/api/health", "/api/status"})).permitAll().requestMatchers(new String[]{"/", "/index.html", "/manual.html"})).permitAll().requestMatchers(new String[]{"/css/**", "/js/**", "/favicon.ico"})).permitAll().requestMatchers(new String[]{"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"})).permitAll().requestMatchers(new String[]{"/api/admin/**"})).hasAuthority("ADMIN").requestMatchers(new String[]{"/api/ingest/**"})).hasAnyAuthority(new String[]{"OPERATOR", "ADMIN"}).requestMatchers(new String[]{"/api/ask/**", "/api/reasoning/**"})).authenticated().requestMatchers(new String[]{"/api/**"})).authenticated().anyRequest()).authenticated());
        return (SecurityFilterChain)http.build();
    }

    @Bean
    @Profile(value={"dev"})
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class).addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class).addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class).csrf(csrf -> csrf.disable()).headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny).contentTypeOptions(contentType -> {}).referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)).permissionsPolicy(permissions -> permissions.policy("geolocation=(), microphone=(), camera=()"))).authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)auth.anyRequest()).permitAll());
        return (SecurityFilterChain)http.build();
    }

    @Bean
    @Profile(value={"enterprise", "standard"})
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterBefore((Filter)this.preAuthRateLimitFilter, SecurityContextHolderFilter.class).addFilterBefore((Filter)this.securityFilter, AnonymousAuthenticationFilter.class).addFilterBefore((Filter)this.rateLimitFilter, AuthorizationFilter.class).csrf(csrf -> csrf.csrfTokenRepository((CsrfTokenRepository)CookieCsrfTokenRepository.withHttpOnlyFalse()).ignoringRequestMatchers(new String[]{"/api/health", "/api/status"})).headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny).contentTypeOptions(contentType -> {}).referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)).permissionsPolicy(permissions -> permissions.policy("geolocation=(), microphone=(), camera=()"))).authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)auth.requestMatchers(new String[]{"/api/health", "/api/status"})).permitAll().requestMatchers(new String[]{"/api/auth/**"})).permitAll().requestMatchers(new String[]{"/", "/index.html", "/manual.html"})).permitAll().requestMatchers(new String[]{"/css/**", "/js/**", "/images/**", "/favicon.ico"})).permitAll().requestMatchers(new String[]{"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"})).permitAll().requestMatchers(new String[]{"/api/admin/**"})).hasAuthority("ADMIN").requestMatchers(new String[]{"/api/**"})).authenticated().anyRequest()).permitAll()).formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable);
        return (SecurityFilterChain)http.build();
    }
}
