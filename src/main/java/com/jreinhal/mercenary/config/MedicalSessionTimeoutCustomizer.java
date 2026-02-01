package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.HipaaPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

@Component
public class MedicalSessionTimeoutCustomizer implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {
    private final HipaaPolicy hipaaPolicy;
    @Value("${sentinel.hipaa.session-timeout-minutes:15}")
    private long sessionTimeoutMinutes;

    public MedicalSessionTimeoutCustomizer(HipaaPolicy hipaaPolicy) {
        this.hipaaPolicy = hipaaPolicy;
    }

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        if (!this.hipaaPolicy.isStrict(Department.MEDICAL)) {
            return;
        }
        if (this.sessionTimeoutMinutes <= 0) {
            return;
        }
        factory.setSessionTimeout(Duration.ofMinutes(this.sessionTimeoutMinutes));
    }
}
