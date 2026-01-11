package com.jreinhal.mercenary.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for CAC (Common Access Card) and PIV (Personal Identity Verification) certificates.
 *
 * Extracts identity information from X.509 certificates used in DoD and federal environments.
 *
 * CAC DN Format: CN=LASTNAME.FIRSTNAME.MIDDLE.EDIPI, OU=USA, OU=PKI, OU=DoD, O=U.S. Government, C=US
 * PIV DN Format: Varies by agency, typically CN=Full Name with UUID or FASC-N in extensions
 */
@Component
public class CacCertificateParser {

    private static final Logger log = LoggerFactory.getLogger(CacCertificateParser.class);

    /**
     * Pattern to extract CN from DN.
     */
    private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Pattern for DoD CAC format: LASTNAME.FIRSTNAME.MIDDLE.EDIPI
     * EDIPI is always 10 digits.
     */
    private static final Pattern DOD_CAC_PATTERN = Pattern.compile(
            "^([A-Z'-]+)\\.([A-Z'-]+)(?:\\.([A-Z'-]*))?\\.?(\\d{10})(?:\\.\\w+)?$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract EDIPI (10-digit number) from various formats.
     */
    private static final Pattern EDIPI_PATTERN = Pattern.compile("(\\d{10})");

    /**
     * Parsed CAC/PIV identity information.
     */
    public record CacIdentity(
            String edipi,
            String firstName,
            String lastName,
            String middleName,
            String commonName,
            String email,
            String organization
    ) {
        /**
         * Generate a username from the identity.
         */
        public String toUsername() {
            if (edipi != null && !edipi.isEmpty()) {
                return edipi;
            }
            if (commonName != null) {
                return commonName.toLowerCase().replaceAll("[^a-z0-9]", "_");
            }
            return "unknown";
        }

        /**
         * Generate a display name.
         */
        public String toDisplayName() {
            if (firstName != null && lastName != null) {
                return firstName + " " + lastName;
            }
            return commonName != null ? commonName : "Unknown User";
        }
    }

    /**
     * Parse identity from a certificate.
     *
     * @param certificate The X.509 certificate
     * @return Parsed identity, or null if parsing fails
     */
    public CacIdentity parse(X509Certificate certificate) {
        if (certificate == null) {
            return null;
        }

        try {
            X500Principal principal = certificate.getSubjectX500Principal();
            String dn = principal.getName(X500Principal.RFC2253);
            return parseDn(dn);
        } catch (Exception e) {
            log.error("Failed to parse certificate: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse identity from a Distinguished Name string.
     *
     * @param dn The DN string
     * @return Parsed identity
     */
    public CacIdentity parseDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return new CacIdentity(null, null, null, null, null, null, null);
        }

        log.debug("Parsing DN: {}", dn);

        // Extract CN
        String cn = extractCn(dn);
        if (cn == null) {
            cn = dn; // Use full DN as fallback
        }

        // Try to parse as DoD CAC format
        Matcher cacMatcher = DOD_CAC_PATTERN.matcher(cn);
        if (cacMatcher.matches()) {
            String lastName = cacMatcher.group(1);
            String firstName = cacMatcher.group(2);
            String middleName = cacMatcher.group(3);
            String edipi = cacMatcher.group(4);

            log.debug("Parsed DoD CAC: EDIPI={}, Name={} {}", edipi, firstName, lastName);

            return new CacIdentity(
                    edipi,
                    capitalize(firstName),
                    capitalize(lastName),
                    middleName != null ? capitalize(middleName) : null,
                    cn,
                    extractEmail(dn),
                    extractOrganization(dn)
            );
        }

        // Fallback: Try to extract EDIPI from anywhere in CN
        String edipi = extractEdipi(cn);

        // For non-DoD certs, use CN as display name
        return new CacIdentity(
                edipi,
                null,
                null,
                null,
                cn,
                extractEmail(dn),
                extractOrganization(dn)
        );
    }

    /**
     * Extract CN from DN.
     */
    private String extractCn(String dn) {
        Matcher matcher = CN_PATTERN.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Extract EDIPI (10-digit identifier) from a string.
     */
    public String extractEdipi(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = EDIPI_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Extract email from DN (if present as E= or emailAddress=).
     */
    private String extractEmail(String dn) {
        Pattern emailPattern = Pattern.compile("(?:E|emailAddress)=([^,]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = emailPattern.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Extract organization from DN.
     */
    private String extractOrganization(String dn) {
        Pattern orgPattern = Pattern.compile("O=([^,]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = orgPattern.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Capitalize a name (first letter uppercase, rest lowercase).
     */
    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    /**
     * Check if a certificate is currently valid (not expired, not future).
     *
     * @param certificate The certificate to check
     * @return true if valid
     */
    public boolean isValid(X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }

        try {
            certificate.checkValidity(new Date());
            return true;
        } catch (Exception e) {
            log.debug("Certificate validity check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a certificate appears to be a CAC/PIV certificate.
     */
    public boolean isCacOrPiv(X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }

        String dn = certificate.getSubjectX500Principal().getName();
        String lowerDn = dn.toLowerCase();

        // Check for DoD indicators
        if (lowerDn.contains("dod") || lowerDn.contains("u.s. government") ||
            lowerDn.contains("department of")) {
            return true;
        }

        // Check for EDIPI in CN
        String cn = extractCn(dn);
        if (cn != null && extractEdipi(cn) != null) {
            return true;
        }

        return false;
    }
}
