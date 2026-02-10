package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class AuthModeControllerTest {

    @Test
    void returnsSsoEnabledForOidcMode() {
        AuthModeController controller = new AuthModeController();
        ReflectionTestUtils.setField(controller, "authMode", "OIDC");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("OIDC", body.get("mode"));
        assertEquals(true, body.get("ssoEnabled"));
        assertEquals("/api/auth/oidc/authorize", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForStandardMode() {
        AuthModeController controller = new AuthModeController();
        ReflectionTestUtils.setField(controller, "authMode", "STANDARD");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("STANDARD", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForDevMode() {
        AuthModeController controller = new AuthModeController();
        ReflectionTestUtils.setField(controller, "authMode", "DEV");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("DEV", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    @Test
    void oidcDetectionIsCaseSensitive() {
        AuthModeController controller = new AuthModeController();
        ReflectionTestUtils.setField(controller, "authMode", "oidc");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        // Lowercase "oidc" should NOT match — @ConditionalOnProperty uses exact "OIDC"
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForCacMode() {
        AuthModeController controller = new AuthModeController();
        ReflectionTestUtils.setField(controller, "authMode", "CAC");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("CAC", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }
}
