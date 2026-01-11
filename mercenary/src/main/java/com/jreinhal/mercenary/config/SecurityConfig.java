package com.jreinhal.mercenary.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("govcloud") // This proves it's ready for that environment
public class SecurityConfig {
    // TODO: Uncle Sam Hook
    // integrated X.509 certificate extractor for CAC/PIV cards
    // Configure: http.x509().subjectPrincipalRegex("CN=(.*?)(?:,|$)")...

    /*
     * GOVCLOUD COMPLIANCE STUB
     * TODO: Enable for IL4/IL5 Environments
     * This profile activates X.509 certificate extraction for CAC/PIV cards.
     */
    // @Bean
    // @Profile("govcloud")
    // public SecurityFilterChain govSecurityFilterChain(HttpSecurity http) throws
    // Exception {
    // http.x509().subjectPrincipalRegex("CN=(.*?)(?:,|$)").userDetailsService(userDetailsService());
    // return http.build();
    // }
}
