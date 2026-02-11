package com.jreinhal.mercenary.util;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.ai.document.Document;

/**
 * Extracts document-level temporal metadata (documentYear, documentDateEpoch) from file bytes and/or text.
 *
 * Intended usage:
 * - Ingestion: compute once per uploaded file and stamp onto every emitted chunk/table.
 * - Backfill: compute from stored text content when original bytes are unavailable.
 */
public final class DocumentTemporalMetadataExtractor {
    private static final Pattern ISO_DATE = Pattern.compile("\\b((?:19|20)\\d{2})[-/](0?[1-9]|1[0-2])[-/](0?[1-9]|[12]\\d|3[01])\\b");
    private static final Pattern US_DATE = Pattern.compile("\\b(0?[1-9]|1[0-2])[/-](0?[1-9]|[12]\\d|3[01])[/-]((?:19|20)\\d{2})\\b");
    private static final Pattern MONTH_NAME_DATE = Pattern.compile(
            "\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2})(?:,)?\\s+((?:19|20)\\d{2})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_ONLY = Pattern.compile("\\b((?:19|20)\\d{2})\\b");
    private static final Pattern FILENAME_YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");

    private DocumentTemporalMetadataExtractor() {}

    public record TemporalMetadata(Long documentDateEpoch, Integer documentYear, String documentDateSource) {
        public boolean isEmpty() {
            return documentDateEpoch == null && documentYear == null;
        }
    }

    public static TemporalMetadata extract(byte[] fileBytes, String mimeType, List<Document> extractedDocs, String filename) {
        TemporalMetadata fromBytes = extractFromBytes(fileBytes, mimeType);
        if (fromBytes != null && !fromBytes.isEmpty()) {
            return fromBytes;
        }

        String text = firstNonBlankText(extractedDocs);
        TemporalMetadata fromText = extractFromText(text);
        if (fromText != null && !fromText.isEmpty()) {
            return fromText;
        }

        TemporalMetadata fromName = extractFromFilename(filename);
        if (fromName != null && !fromName.isEmpty()) {
            return fromName;
        }

        return new TemporalMetadata(null, null, null);
    }

    public static TemporalMetadata extractFromBytes(byte[] fileBytes, String mimeType) {
        if (fileBytes == null || fileBytes.length == 0) {
            return new TemporalMetadata(null, null, null);
        }
        if (mimeType == null || !mimeType.equalsIgnoreCase("application/pdf")) {
            return new TemporalMetadata(null, null, null);
        }
        try (PDDocument pd = Loader.loadPDF(fileBytes)) {
            PDDocumentInformation info = pd.getDocumentInformation();
            if (info == null) {
                return new TemporalMetadata(null, null, null);
            }
            Instant creation = calendarToInstant(info.getCreationDate());
            Instant modified = calendarToInstant(info.getModificationDate());
            Instant chosen = chooseBest(creation, modified);
            if (chosen == null) {
                return new TemporalMetadata(null, null, null);
            }
            int year = chosen.atZone(ZoneOffset.UTC).getYear();
            Integer safeYear = safeYear(year);
            if (safeYear == null) {
                return new TemporalMetadata(null, null, null);
            }
            return new TemporalMetadata(chosen.toEpochMilli(), safeYear, "pdf_metadata");
        } catch (IOException e) {
            return new TemporalMetadata(null, null, null);
        }
    }

    public static TemporalMetadata extractFromText(String text) {
        if (text == null || text.isBlank()) {
            return new TemporalMetadata(null, null, null);
        }
        String sample = text.length() > 12_000 ? text.substring(0, 12_000) : text;

        Optional<LocalDate> parsed = parseFirstDate(sample);
        if (parsed.isPresent()) {
            LocalDate date = parsed.get();
            Integer safeYear = safeYear(date.getYear());
            if (safeYear == null) {
                return new TemporalMetadata(null, null, null);
            }
            long epoch = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            return new TemporalMetadata(epoch, safeYear, "text_date");
        }

        Integer year = parseFirstYear(sample);
        if (year != null) {
            return new TemporalMetadata(null, year, "text_year");
        }

        return new TemporalMetadata(null, null, null);
    }

    private static TemporalMetadata extractFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return new TemporalMetadata(null, null, null);
        }
        Matcher m = FILENAME_YEAR.matcher(filename);
        if (!m.find()) {
            return new TemporalMetadata(null, null, null);
        }
        Integer y = safeYear(m.group(1));
        if (y == null) {
            return new TemporalMetadata(null, null, null);
        }
        return new TemporalMetadata(null, y, "filename_year");
    }

    private static String firstNonBlankText(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        for (Document doc : docs) {
            if (doc == null) {
                continue;
            }
            String content = doc.getContent();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return "";
    }

    private static Optional<LocalDate> parseFirstDate(String text) {
        // ISO date: YYYY-MM-DD
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            Integer y = safeYear(iso.group(1));
            if (y == null) {
                return Optional.empty();
            }
            int month = Integer.parseInt(iso.group(2));
            int day = Integer.parseInt(iso.group(3));
            try {
                return Optional.of(LocalDate.of(y, month, day));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        // US date: MM/DD/YYYY
        Matcher us = US_DATE.matcher(text);
        if (us.find()) {
            int month = Integer.parseInt(us.group(1));
            int day = Integer.parseInt(us.group(2));
            Integer y = safeYear(us.group(3));
            if (y == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDate.of(y, month, day));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        // Month name: Jan 2, 2021
        Matcher named = MONTH_NAME_DATE.matcher(text);
        if (named.find()) {
            Month month = parseMonth(named.group(1));
            if (month == null) {
                return Optional.empty();
            }
            int day = Integer.parseInt(named.group(2));
            Integer y = safeYear(named.group(3));
            if (y == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDate.of(y, month, day));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private static Integer parseFirstYear(String text) {
        Matcher m = YEAR_ONLY.matcher(text);
        if (!m.find()) {
            return null;
        }
        return safeYear(m.group(1));
    }

    private static Month parseMonth(String raw) {
        if (raw == null) {
            return null;
        }
        String r = raw.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("jan")) return Month.JANUARY;
        if (r.startsWith("feb")) return Month.FEBRUARY;
        if (r.startsWith("mar")) return Month.MARCH;
        if (r.startsWith("apr")) return Month.APRIL;
        if (r.equals("may")) return Month.MAY;
        if (r.startsWith("jun")) return Month.JUNE;
        if (r.startsWith("jul")) return Month.JULY;
        if (r.startsWith("aug")) return Month.AUGUST;
        if (r.startsWith("sep")) return Month.SEPTEMBER;
        if (r.startsWith("oct")) return Month.OCTOBER;
        if (r.startsWith("nov")) return Month.NOVEMBER;
        if (r.startsWith("dec")) return Month.DECEMBER;
        return null;
    }

    private static Integer safeYear(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return safeYear(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer safeYear(int year) {
        int current = Year.now().getValue();
        if (year < 1900 || year > current + 2) {
            return null;
        }
        return year;
    }

    private static Instant calendarToInstant(Calendar cal) {
        if (cal == null) {
            return null;
        }
        try {
            return Objects.requireNonNull(cal.toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant chooseBest(Instant creation, Instant modified) {
        if (creation != null) {
            return creation;
        }
        return modified;
    }
}
