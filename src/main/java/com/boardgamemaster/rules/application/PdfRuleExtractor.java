package com.boardgamemaster.rules.application;

import static com.boardgamemaster.rules.domain.RuleImportException.Code.INVALID_DOCUMENT;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.UNSUPPORTED_FILE_TYPE;

import com.boardgamemaster.rules.domain.RuleImportException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Turns a rulebook PDF into the plain text the rule pipeline already understands, with a
 * {@code <!-- page: N -->} marker before each page so citations keep their page numbers.
 * Only PDFs with selectable text are supported; scanned images have no text to extract.
 * The PDF is treated as data: nothing in it is executed or followed.
 */
@Component
public class PdfRuleExtractor {
    static final int MAX_PAGES = 300;
    private static final int MIN_TEXT_CHARACTERS = 200;

    public record Extracted(String fileName, byte[] markdown, int pages) { }

    public static boolean isPdf(String fileName) {
        return fileName != null && fileName.strip().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public Extracted extract(String fileName, byte[] bytes) {
        if (!isPdf(fileName)) { throw new RuleImportException(UNSUPPORTED_FILE_TYPE, "Choose a .pdf rulebook file"); }
        if (bytes == null || bytes.length == 0) { throw new RuleImportException(INVALID_DOCUMENT, "The PDF file is empty"); }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                throw new RuleImportException(INVALID_DOCUMENT, "This PDF is protected against copying text, so it can't be imported");
            }
            int pages = document.getNumberOfPages();
            if (pages == 0) { throw new RuleImportException(INVALID_DOCUMENT, "The PDF has no pages"); }
            if (pages > MAX_PAGES) {
                throw new RuleImportException(INVALID_DOCUMENT, "The PDF has " + pages + " pages; the limit is " + MAX_PAGES);
            }
            // Keep the PDF's own text order. Sorting by position reads straight across multi-column pages,
            // splicing unrelated columns into one line, so quotes from a column no longer match the text.
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(false);
            var text = new StringBuilder();
            int readable = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = clean(stripper.getText(document));
                if (pageText.isBlank()) { continue; }
                readable += pageText.replaceAll("\\s+", "").length();
                text.append("<!-- page: ").append(page).append(" -->\n\n").append(pageText).append("\n\n");
            }
            if (readable < MIN_TEXT_CHARACTERS) {
                throw new RuleImportException(INVALID_DOCUMENT,
                        "This PDF has almost no selectable text (it may be scanned pictures of pages). Use a PDF where you can select the text");
            }
            return new Extracted(markdownName(fileName), text.toString().getBytes(StandardCharsets.UTF_8), pages);
        } catch (IOException e) {
            throw new RuleImportException(INVALID_DOCUMENT, "The file could not be read as a PDF");
        }
    }

    /**
     * Keeps the text but removes anything the Markdown chunker would treat as structure it did not
     * come from: control characters, stray page markers and code fences.
     */
    static String clean(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .codePoints().filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\t')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("<!--")) { continue; }
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) { line = stripped.replaceAll("^[`~]+", ""); }
            out.append(line.replaceAll("[\\t ]+$", "")).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String markdownName(String fileName) {
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        base = base.substring(0, base.length() - 4);
        if (base.isBlank()) { base = "rulebook"; }
        if (base.length() > 240) { base = base.substring(0, 240); }
        return base + ".md";
    }
}
