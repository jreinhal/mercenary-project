/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.springframework.boot.loader.zip.ZipContent;

final class SecurityInfo {
    static final SecurityInfo NONE = new SecurityInfo(null, null);
    private final Certificate[][] certificateLookups;
    private final CodeSigner[][] codeSignerLookups;

    private SecurityInfo(Certificate[][] entryCertificates, CodeSigner[][] entryCodeSigners) {
        this.certificateLookups = entryCertificates;
        this.codeSignerLookups = entryCodeSigners;
    }

    Certificate[] getCertificates(ZipContent.Entry contentEntry) {
        return this.certificateLookups != null ? this.clone(this.certificateLookups[contentEntry.getLookupIndex()]) : null;
    }

    CodeSigner[] getCodeSigners(ZipContent.Entry contentEntry) {
        return this.codeSignerLookups != null ? this.clone(this.codeSignerLookups[contentEntry.getLookupIndex()]) : null;
    }

    private <T> T[] clone(T[] array) {
        return array != null ? (Object[])array.clone() : null;
    }

    static SecurityInfo get(ZipContent content) {
        if (!content.hasJarSignatureFile()) {
            return NONE;
        }
        try {
            return SecurityInfo.load(content);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static SecurityInfo load(ZipContent content) throws IOException {
        int size = content.size();
        boolean hasSecurityInfo = false;
        Certificate[][] entryCertificates = new Certificate[size][];
        CodeSigner[][] entryCodeSigners = new CodeSigner[size][];
        try (JarInputStream in = new JarInputStream(content.openRawZipData().asInputStream());){
            JarEntry jarEntry = in.getNextJarEntry();
            while (jarEntry != null) {
                ZipContent.Entry contentEntry;
                in.closeEntry();
                Certificate[] certificates = jarEntry.getCertificates();
                CodeSigner[] codeSigners = jarEntry.getCodeSigners();
                if ((certificates != null || codeSigners != null) && (contentEntry = content.getEntry(jarEntry.getName())) != null) {
                    hasSecurityInfo = true;
                    entryCertificates[contentEntry.getLookupIndex()] = certificates;
                    entryCodeSigners[contentEntry.getLookupIndex()] = codeSigners;
                }
                jarEntry = in.getNextJarEntry();
            }
            SecurityInfo securityInfo = !hasSecurityInfo ? NONE : new SecurityInfo(entryCertificates, entryCodeSigners);
            return securityInfo;
        }
    }
}

