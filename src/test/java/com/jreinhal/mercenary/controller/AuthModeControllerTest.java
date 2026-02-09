package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthModeControllerTest {

    @Test
    void returnsSsoEnabledForOidcMode() throws Exception {
        AuthModeController controller = new AuthModeController();
        setField(controller, "authMode", "OIDC");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("OIDC", body.get("mode"));
        assertEquals(true, body.get("ssoEnabled"));
        assertEquals("/api/auth/oidc/authorize", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForStandardMode() throws Exception {
        AuthModeController controller = new AuthModeController();
        setField(controller, "authMode", "STANDARD");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("STANDARD", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForDevMode() throws Exception {
        AuthModeController controller = new AuthModeController();
        setField(controller, "authMode", "DEV");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("DEV", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    @Test
    void oidcDetectionIsCaseInsensitive() throws Exception {
        AuthModeController controller = new AuthModeController();
        setField(controller, "authMode", "oidc");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("ssoEnabled"));
        assertEquals("/api/auth/oidc/authorize", body.get("authorizeUrl"));
    }

    @Test
    void returnsSsoDisabledForCacMode() throws Exception {
        AuthModeController controller = new AuthModeController();
        setField(controller, "authMode", "CAC");

        ResponseEntity<Map<String, Object>> response = controller.getMode();

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("CAC", body.get("mode"));
        assertEquals(false, body.get("ssoEnabled"));
        assertEquals("", body.get("authorizeUrl"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
