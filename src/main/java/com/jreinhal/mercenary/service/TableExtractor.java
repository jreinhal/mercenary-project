package com.jreinhal.mercenary.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.RectangularTextContainer;

@Service
public class TableExtractor {
    private static final Logger log = LoggerFactory.getLogger(TableExtractor.class);

    @Value("${sentinel.ingest.tables.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.ingest.tables.max-tables-per-document:50}")
    private int maxTablesPerDocument;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Extract text-layer tables using Tabula and return them as atomic markdown documents.
     *
     * For scanned/image-only PDFs, this will generally return an empty list (handled by later phases).
     */
    public List<Document> extractTables(byte[] pdfBytes, String filename) {
        if (!this.enabled) {
            return List.of();
        }
        if (pdfBytes == null || pdfBytes.length == 0) {
            return List.of();
        }
        if (filename == null || filename.isBlank()) {
            filename = "Unknown_Document.pdf";
        }

        ArrayList<Document> out = new ArrayList<>();
        int produced = 0;

        try (PDDocument pd = Loader.loadPDF(pdfBytes);
             ObjectExtractor extractor = new ObjectExtractor(pd)) {

            SpreadsheetExtractionAlgorithm spreadsheet = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm basic = new BasicExtractionAlgorithm();

            int pageNumber = 1;
            PageIterator pages = extractor.extract();
            while (pages.hasNext()) {
                if (produced >= this.maxTablesPerDocument) {
                    break;
                }
                Page page = pages.next();

                List<? extends Table> tables = spreadsheet.extract(page);
                if (tables == null || tables.isEmpty()) {
                    tables = basic.extract(page);
                }
                if (tables == null || tables.isEmpty()) {
                    pageNumber++;
                    continue;
                }

                int tableIndexOnPage = 0;
                for (Table table : tables) {
                    if (produced >= this.maxTablesPerDocument) {
                        break;
                    }
                    if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
                        continue;
                    }

                    String markdown = toMarkdown(table);
                    if (markdown.isBlank()) {
                        continue;
                    }

                    Map<String, Object> meta = new HashMap<>();
                    meta.put("type", "table");
                    meta.put("extractor", "tabula");
                    meta.put("page_number", pageNumber);
                    meta.put("table_index", tableIndexOnPage);
                    meta.put("table_row_count", table.getRows().size());
                    meta.put("table_top", table.getTop());
                    meta.put("table_left", table.getLeft());
                    meta.put("table_width", table.getWidth());
                    meta.put("table_height", table.getHeight());

                    String content = "TABLE (extracted)\n\n" + markdown;
                    out.add(new Document(content, meta));
                    produced++;
                    tableIndexOnPage++;
                }

                pageNumber++;
            }
        } catch (Exception e) {
            // Avoid logging user-controlled strings (e.g., filename) to prevent log forging.
            log.warn("TableExtractor: failed to extract tables");
            log.debug("TableExtractor: extractTables exception", e);
            return List.of();
        }

        if (!out.isEmpty()) {
            log.info("TableExtractor: extracted {} table(s)", out.size());
        }
        return out;
    }

    private static String toMarkdown(Table table) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        int maxCols = 0;
        for (List<RectangularTextContainer> row : rows) {
            if (row != null) {
                maxCols = Math.max(maxCols, row.size());
            }
        }
        if (maxCols <= 0) {
            return "";
        }

        List<String> headerCells = rowToCells(rows.get(0), maxCols);
        StringBuilder md = new StringBuilder();
        md.append("| ").append(String.join(" | ", headerCells)).append(" |\n");
        md.append("|");
        for (int i = 0; i < maxCols; i++) {
            md.append(" --- |");
        }
        md.append("\n");

        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rowToCells(rows.get(r), maxCols);
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }

        return md.toString().trim();
    }

    private static List<String> rowToCells(List<RectangularTextContainer> row, int maxCols) {
        ArrayList<String> cells = new ArrayList<>(maxCols);
        for (int i = 0; i < maxCols; i++) {
            String text = "";
            if (row != null && i < row.size() && row.get(i) != null) {
                text = row.get(i).getText();
            }
            text = sanitizeCell(text);
            cells.add(text);
        }
        return cells;
    }

    private static String sanitizeCell(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        // Avoid breaking markdown tables when the source contains pipes.
        normalized = normalized.replace("|", "\\|");
        return normalized;
    }
}
