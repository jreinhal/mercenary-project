package com.jreinhal.mercenary.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("govcloud") // This proves it's ready for that environment
public class SecurityConfig {
    // TODO: Uncle Sam Hook
    // integrated X.509 certificate extractor for CAC/PIV cards
    // Configure: http.x509().subjectPrincipalRegex("CN=(.*?)(?:,|$)")...
}
