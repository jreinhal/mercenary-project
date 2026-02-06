package com.jreinhal.mercenary.government.auth;

import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CacCertificateParser {
    private static final Logger log = LoggerFactory.getLogger(CacCertificateParser.class);
    private static final Pattern DOD_CAC_PATTERN = Pattern.compile("^([A-Z'-]+)\\.([A-Z'-]+)(?:\\.([A-Z'-]*))?\\.?(\\d{10})(?:\\.\\w+)?$", 2);
    private static final Pattern EDIPI_PATTERN = Pattern.compile("(\\d{10})");

    public CacIdentity parse(X509Certificate certificate) {
        if (certificate == null) {
            return null;
        }
        try {
            X500Principal principal = certificate.getSubjectX500Principal();
            String dn = principal.getName("RFC2253");
            return this.parseDn(dn);
        }
        catch (Exception e) {
            log.error("Failed to parse certificate: {}", e.getMessage());
            return null;
        }
    }

    public CacIdentity parseDn(String dn) {
        Matcher cacMatcher;
        if (dn == null || dn.isEmpty()) {
            return new CacIdentity(null, null, null, null, null, null, null);
        }
        // DN can contain sensitive identity fields; avoid logging it even at DEBUG.
        String cn = this.extractCn(dn);
        if (cn == null) {
            cn = dn;
        }
        if ((cacMatcher = DOD_CAC_PATTERN.matcher(cn)).matches()) {
            String lastName = cacMatcher.group(1);
            String firstName = cacMatcher.group(2);
            String middleName = cacMatcher.group(3);
            String edipi = cacMatcher.group(4);
            log.debug("Parsed DoD CAC: EDIPI={}, Name={} {}", new Object[]{edipi, firstName, lastName});
            return new CacIdentity(edipi, this.capitalize(firstName), this.capitalize(lastName), middleName != null ? this.capitalize(middleName) : null, cn, this.extractEmail(dn), this.extractOrganization(dn));
        }
        String edipi = this.extractEdipi(cn);
        return new CacIdentity(edipi, null, null, null, cn, this.extractEmail(dn), this.extractOrganization(dn));
    }

    private String extractCn(String dn) {
        return this.extractDnAttribute(dn, "CN");
    }

    /**
     * Extract the Common Name (CN) from a Distinguished Name (DN) using RFC2253 parsing.
     * Returns null if the DN is invalid or does not contain a CN attribute.
     */
    public String extractCommonName(String dn) {
        return this.extractDnAttribute(dn, "CN");
    }

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

    private String extractEmail(String dn) {
        // Different environments can encode email as E=, emailAddress=, or OID 1.2.840.113549.1.9.1
        String email = this.extractDnAttribute(dn, "E", "EMAILADDRESS", "1.2.840.113549.1.9.1");
        return email != null ? email.trim() : null;
    }

    private String extractOrganization(String dn) {
        String org = this.extractDnAttribute(dn, "O");
        return org != null ? org.trim() : null;
    }

    private String extractDnAttribute(String dn, String... types) {
        if (dn == null || dn.isBlank()) {
            return null;
        }
        try {
            LdapName ldapName = new LdapName(dn);
            for (Rdn rdn : ldapName.getRdns()) {
                if (rdn == null || rdn.getType() == null) {
                    continue;
                }
                for (String type : types) {
                    if (type != null && type.equalsIgnoreCase(rdn.getType())) {
                        Object value = rdn.getValue();
                        return value != null ? value.toString() : null;
                    }
                }
            }
        } catch (InvalidNameException e) {
            log.warn("Invalid certificate DN format (unable to extract attributes)");
        }
        return null;
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    public boolean isValid(X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }
        try {
            certificate.checkValidity(new Date());
            return true;
        }
        catch (Exception e) {
            log.debug("Certificate validity check failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isCacOrPiv(X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }
        String dn = certificate.getSubjectX500Principal().getName();
        String lowerDn = dn.toLowerCase();
        if (lowerDn.contains("dod") || lowerDn.contains("u.s. government") || lowerDn.contains("department of")) {
            return true;
        }
        String cn = this.extractCn(dn);
        return cn != null && this.extractEdipi(cn) != null;
    }

    public record CacIdentity(String edipi, String firstName, String lastName, String middleName, String commonName, String email, String organization) {
        public String toUsername() {
            if (this.edipi != null && !this.edipi.isEmpty()) {
                return this.edipi;
            }
            if (this.commonName != null) {
                return this.commonName.toLowerCase().replaceAll("[^a-z0-9]", "_");
            }
            return "unknown";
        }

        public String toDisplayName() {
            if (this.firstName != null && this.lastName != null) {
                return this.firstName + " " + this.lastName;
            }
            return this.commonName != null ? this.commonName : "Unknown User";
        }
    }
}
