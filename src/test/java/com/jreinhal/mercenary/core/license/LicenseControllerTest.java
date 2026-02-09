package com.jreinhal.mercenary.core.license;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class LicenseControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void statusRequiresAuthentication() {
        LicenseService licenseService = mock(LicenseService.class);
        LicenseController controller = new LicenseController(licenseService);

        ResponseEntity<LicenseService.LicenseStatus> res = controller.getStatus();
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verifyNoInteractions(licenseService);
    }

    @Test
    void featureRequiresAuthenticationAndValidParam() {
        LicenseService licenseService = mock(LicenseService.class);
        LicenseController controller = new LicenseController(licenseService);

        ResponseEntity<LicenseController.FeatureResponse> unauth = controller.checkFeature("RAG");
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        SecurityContext.setCurrentUser(User.devUser("test"));
        ResponseEntity<LicenseController.FeatureResponse> bad = controller.checkFeature("  ");
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        verifyNoInteractions(licenseService);
    }

    @Test
    void featureReturnsAvailabilityAndEdition() {
        LicenseService licenseService = mock(LicenseService.class);
        when(licenseService.hasFeature("RAG")).thenReturn(true);
        when(licenseService.getEdition()).thenReturn(LicenseService.Edition.ENTERPRISE);
        when(licenseService.getStatus()).thenReturn(new LicenseService.LicenseStatus(
                LicenseService.Edition.ENTERPRISE, true, -1, (Instant) null));

        LicenseController controller = new LicenseController(licenseService);
        SecurityContext.setCurrentUser(User.devUser("test"));

        ResponseEntity<LicenseController.FeatureResponse> res = controller.checkFeature("RAG");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("RAG", res.getBody().feature());
        assertTrue(res.getBody().available());
        assertEquals("ENTERPRISE", res.getBody().edition());
    }

    @Test
    void legacyProfessionalEditionMapsToEnterprise() {
        LicenseService licenseService = new LicenseService();
        ReflectionTestUtils.setField(licenseService, "editionString", "PROFESSIONAL");
        ReflectionTestUtils.setField(licenseService, "licenseKey", "");
        ReflectionTestUtils.setField(licenseService, "trialStartDate", "");
        ReflectionTestUtils.setField(licenseService, "trialDays", 30);
        licenseService.initialize();

        assertEquals(LicenseService.Edition.ENTERPRISE, licenseService.getEdition());
    }
}

