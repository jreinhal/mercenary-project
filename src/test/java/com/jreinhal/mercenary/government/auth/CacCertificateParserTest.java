package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.government.auth.CacCertificateParser.CacIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacCertificateParserTest {

    private CacCertificateParser parser;

    @BeforeEach
    void setUp() {
        parser = new CacCertificateParser();
    }

    @Test
    void shouldParseStandardDodCacDn() {
        String dn = "CN=DOE.JOHN.MIDDLE.1234567890,OU=USN,OU=PKI,OU=DoD,O=U.S. Government,C=US";

        CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("1234567890", identity.edipi());
        assertEquals("John", identity.firstName());
        assertEquals("Doe", identity.lastName());
        assertEquals("Middle", identity.middleName());
    }

    @Test
    void shouldExtractEdipiFromSimpleDn() {
        String dn = "CN=john.doe.1234567890";

        CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("1234567890", identity.edipi());
    }

    @Test
    void shouldHandleNullDn() {
        CacIdentity identity = parser.parseDn(null);

        assertNotNull(identity);
        assertNull(identity.edipi());
    }

    @Test
    void shouldHandleEmptyDn() {
        CacIdentity identity = parser.parseDn("");

        assertNotNull(identity);
        assertNull(identity.edipi());
    }

    @Test
    void shouldExtractEmail() {
        String dn = "CN=DOE.JOHN.1234567890,E=john.doe@mail.mil,O=U.S. Government";

        CacIdentity identity = parser.parseDn(dn);

        assertEquals("john.doe@mail.mil", identity.email());
    }

    @Test
    void shouldExtractOrganization() {
        String dn = "CN=DOE.JOHN.1234567890,O=U.S. Government,C=US";

        CacIdentity identity = parser.parseDn(dn);

        assertEquals("U.S. Government", identity.organization());
    }

    @Test
    void shouldGenerateUsernameFromEdipi() {
        String dn = "CN=DOE.JOHN.1234567890";

        CacIdentity identity = parser.parseDn(dn);

        assertEquals("1234567890", identity.toUsername());
    }

    @Test
    void shouldGenerateDisplayName() {
        String dn = "CN=DOE.JOHN.MIDDLE.1234567890,O=U.S. Government";

        CacIdentity identity = parser.parseDn(dn);

        assertEquals("John Doe", identity.toDisplayName());
    }

    @Test
    void shouldHandlePivCertificateFormat() {
        String dn = "CN=Jane Smith,OU=Employees,O=Department of Defense,C=US";

        CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("Jane Smith", identity.commonName());
    }

    @Test
    void shouldExtractEdipiFromMixedContent() {
        String text = "USER-1234567890-TEST";

        String edipi = parser.extractEdipi(text);

        assertEquals("1234567890", edipi);
    }

    @Test
    void shouldReturnNullForMissingEdipi() {
        String text = "no-edipi-here";

        String edipi = parser.extractEdipi(text);

        assertNull(edipi);
    }
}
