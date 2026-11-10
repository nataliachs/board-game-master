package com.boardgamemaster.rules.application;

import static com.boardgamemaster.rules.domain.RuleImportException.Code.INVALID_DOCUMENT;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.UNSUPPORTED_FILE_TYPE;

import com.boardgamemaster.rules.domain.RuleImportException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * Turns a rulebook PDF into the plain text the rule pipeline already understands, with a
 * {@code <!-- page: N -->} marker before each page so citations keep their page numbers.
 *
 * <p>Section detection: a line set clearly larger than the body text, or a short fully bold line,
 * becomes a {@code ## } heading when the line right after it in the text sits just below it on the
 * page, in the same column, and starts a new sentence. The chunker then splits passages by rule
 * topic and titles them. Layout noise (rotated letter fragments, lone image labels and page numbers)
 * is dropped. Every other line keeps its extracted text, so quotes still match the stored text.
 *
 * <p>Only PDFs with selectable text are supported; scanned images have no text to extract.
 * The PDF is treated as data: nothing in it is executed or followed.
 */
@Component
public class PdfRuleExtractor {
    static final int MAX_PAGES = 300;
    private static final int MIN_TEXT_CHARACTERS = 200;
    /** A heading's font is at least this much larger than the most common (body) font size. */
    static final double HEADING_SIZE_RATIO = 1.2;
    private static final int MAX_HEADING_CHARACTERS = 80;
    private static final int MAX_HEADING_WORDS = 10;
    private static final int MAX_BOLD_HEADING_WORDS = 5;
    /**
     * Each extracted line is tagged with its kind and position until headings are resolved:
     * TAG, 'H' (heading candidate) or 'T' (text), "x0;x1;baseline;size", END, then the line's text.
     * Private-use characters never reach the stored text: PDF text containing them is stripped.
     */
    private static final char TAG = '';
    private static final char END = '';
    static final Pattern GEOMETRY = Pattern.compile("(?m)^[HT][^\n]*");
    private static final String BULLETS = "•◦▪▫‣●○■□";
    private static final Pattern PAGE_MARKER = Pattern.compile("^<!-- page: \\d+ -->$");
    private static final Pattern BOLD_NAME = Pattern.compile(".*(bold|black|heavy|semibold|demi).*");

    enum LineKind { TEXT, HEADING, NOISE }

    public record Extracted(String fileName, byte[] markdown, int pages) { }

    /** A line's position on its page, in points from the top-left; {@code baseline} grows downwards. */
    record Geometry(double x0, double x1, double baseline, double size) { }

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
            var stripper = new LayoutStripper(bodyFontSize(document));
            var text = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = clean(stripper.getText(document));
                if (GEOMETRY.matcher(pageText).replaceAll("").isBlank()) { continue; }
                text.append("<!-- page: ").append(page).append(" -->\n\n").append(pageText).append("\n\n");
            }
            String markdown = resolveHeadings(text.toString());
            int readable = markdown.replaceAll("<!-- page: \\d+ -->", "").replaceAll("\\s+", "").length();
            if (readable < MIN_TEXT_CHARACTERS) {
                throw new RuleImportException(INVALID_DOCUMENT,
                        "This PDF has almost no selectable text (it may be scanned pictures of pages). Use a PDF where you can select the text");
            }
            return new Extracted(markdownName(fileName), markdown.getBytes(StandardCharsets.UTF_8), pages);
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

    /** The most common font size of upright text, weighted by characters: the rulebook's body text. */
    static double bodyFontSize(PDDocument document) throws IOException {
        var counts = new HashMap<Integer, Integer>();
        var sizes = new PDFTextStripper() {
            @Override protected void writeString(String text, List<TextPosition> positions) {
                for (var glyph : positions) {
                    if (glyph.getDir() != 0 || glyph.getUnicode() == null || glyph.getUnicode().isBlank()) { continue; }
                    counts.merge(Math.round(glyph.getFontSizeInPt() * 2), 1, Integer::sum);
                }
            }
        };
        sizes.setSortByPosition(false);
        sizes.getText(document);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey() / 2.0).orElse(0.0);
    }

    /** Classifies one extracted line from its text and glyphs; {@code body} is the body font size. */
    static LineKind classify(String text, List<TextPosition> positions, double body) {
        String line = text.strip();
        var glyphs = visible(positions);
        if (line.isEmpty() || glyphs.isEmpty()) { return LineKind.TEXT; }
        if (line.startsWith("<!--")) { return LineKind.NOISE; }
        String compact = line.replaceAll("\\s+", "");
        int characters = compact.codePointCount(0, compact.length());
        // Rotated text is kept when it reads as words (diagram captions can hold rules); short rotated
        // pieces are letters of vertical titles, which PDF extraction returns as two-letter fragments.
        boolean rotated = glyphs.stream().filter(g -> g.getDir() != 0).count() * 2 > glyphs.size();
        if (rotated) { return characters <= 3 ? LineKind.NOISE : LineKind.TEXT; }
        if (characters == 1 && Character.isLetterOrDigit(line.codePointAt(0))) { return LineKind.NOISE; }
        if (line.matches("\\d{1,3}")) { return LineKind.NOISE; }
        return heading(line, glyphs, body) ? LineKind.HEADING : LineKind.TEXT;
    }

    private static boolean heading(String line, List<TextPosition> glyphs, double body) {
        if (body <= 0 || line.length() > MAX_HEADING_CHARACTERS || BULLETS.indexOf(line.charAt(0)) >= 0 || line.endsWith(",")) {
            return false;
        }
        if (line.codePoints().filter(Character::isLetter).count() < 3) { return false; }
        int words = line.split("\\s+").length;
        if (words > MAX_HEADING_WORDS) { return false; }
        double size = size(glyphs);
        if (size >= body * HEADING_SIZE_RATIO) { return true; }
        // Run-in headings such as "Floor line" use the body size in bold; sentences end in punctuation.
        return words <= MAX_BOLD_HEADING_WORDS && size >= body * 0.95 && line.indexOf(',') < 0
                && ".:;!?".indexOf(line.charAt(line.length() - 1)) < 0 && glyphs.stream().allMatch(PdfRuleExtractor::bold);
    }

    private static boolean bold(TextPosition glyph) {
        var font = glyph.getFont();
        if (font == null) { return false; }
        String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
        if (BOLD_NAME.matcher(name).matches()) { return true; }
        var descriptor = font.getFontDescriptor();
        return descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
    }

    private static List<TextPosition> visible(List<TextPosition> positions) {
        return positions.stream().filter(g -> g.getUnicode() != null && !g.getUnicode().isBlank()).toList();
    }
    private static double size(List<TextPosition> glyphs) {
        return glyphs.stream().mapToDouble(TextPosition::getFontSizeInPt).average().orElse(0);
    }
    private static Geometry geometry(List<TextPosition> glyphs) {
        double x0 = Double.MAX_VALUE, x1 = 0, baseline = 0;
        for (var glyph : glyphs) {
            x0 = Math.min(x0, glyph.getXDirAdj());
            x1 = Math.max(x1, glyph.getXDirAdj() + glyph.getWidthDirAdj());
            baseline = Math.max(baseline, glyph.getYDirAdj());
        }
        return new Geometry(x0, x1, baseline, size(glyphs));
    }

    /**
     * True when {@code next} starts just below {@code heading} in the same column: within a few lines'
     * height underneath and overlapping it horizontally. PDF text order can put a heading far from
     * its section (a sidebar title, a heading stored after its text); those stay plain text.
     */
    static boolean below(Geometry heading, Geometry next) {
        double drop = next.baseline() - heading.baseline();
        double tolerance = heading.size();
        return drop > 0 && drop <= 2.5 * heading.size() + 2 * next.size()
                && next.x0() <= heading.x1() + tolerance && next.x1() >= heading.x0() - tolerance;
    }

    /**
     * Turns heading candidates into {@code ## } headings and removes every position tag, page by page.
     * A candidate heads the line of text directly below it on the page: the next line in text order
     * when that one is below it, otherwise the nearest line below it in the same column, because PDF
     * text order can store a heading away from its section. That text line must start a sentence
     * (not lowercase) and must not be another candidate. When two candidates claim the same line, the
     * nearer one wins. Unplaced candidates stay plain text where they were; only heading lines move.
     */
    static String resolveHeadings(String text) {
        var out = new ArrayList<String>();
        var page = new ArrayList<String>();
        for (String line : text.split("\n", -1)) {
            if (PAGE_MARKER.matcher(line).matches()) {
                out.addAll(resolvePage(page));
                page.clear();
                out.add(line);
            } else {
                page.add(line);
            }
        }
        out.addAll(resolvePage(page));
        return String.join("\n", out);
    }

    private record Entry(String text, Geometry geometry, boolean candidate) {
        boolean startsSection() {
            return !candidate && geometry != null && !text.isBlank() && !Character.isLowerCase(text.strip().codePointAt(0));
        }
    }

    private static List<String> resolvePage(List<String> lines) {
        var entries = lines.stream().map(PdfRuleExtractor::entry).toList();
        var headingFor = new HashMap<Integer, Integer>();
        var distance = new HashMap<Integer, Double>();
        for (int i = 0; i < entries.size(); i++) {
            var candidate = entries.get(i);
            if (!candidate.candidate() || candidate.geometry() == null) { continue; }
            int target = -1;
            int next = i + 1;
            while (next < entries.size() && entries.get(next).text().isBlank()) { next++; }
            if (next < entries.size() && entries.get(next).startsSection() && below(candidate.geometry(), entries.get(next).geometry())) {
                target = next;
            } else {
                double nearest = Double.MAX_VALUE;
                for (int j = 0; j < entries.size(); j++) {
                    var line = entries.get(j);
                    if (!line.startsSection() || !below(candidate.geometry(), line.geometry())) { continue; }
                    double drop = line.geometry().baseline() - candidate.geometry().baseline();
                    if (drop < nearest) { nearest = drop; target = j; }
                }
            }
            if (target < 0) { continue; }
            double drop = entries.get(target).geometry().baseline() - candidate.geometry().baseline();
            if (!headingFor.containsKey(target) || drop < distance.get(target)) {
                headingFor.put(target, i);
                distance.put(target, drop);
            }
        }
        var headings = new java.util.HashSet<>(headingFor.values());
        var result = new ArrayList<String>();
        for (int j = 0; j < entries.size(); j++) {
            Integer heading = headingFor.get(j);
            if (heading != null) { result.add("## " + entries.get(heading).text().strip()); }
            if (headings.contains(j)) { continue; }
            var entry = entries.get(j);
            result.add(entry.text().isBlank() ? "" : entry.text());
        }
        return result;
    }

    private static Entry entry(String line) {
        boolean tagged = line.length() > 2 && line.charAt(0) == TAG && line.indexOf(END) > 0;
        if (!tagged) { return new Entry(line, null, false); }
        return new Entry(line.substring(line.indexOf(END) + 1), parseGeometry(line.substring(2, line.indexOf(END))),
                line.charAt(1) == 'H');
    }

    private static Geometry parseGeometry(String value) {
        try {
            String[] parts = value.split(";");
            return new Geometry(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Collects each extracted line with its glyphs, then writes it tagged with its kind and position, or drops it. */
    private static final class LayoutStripper extends PDFTextStripper {
        private final double body;
        private final StringBuilder line = new StringBuilder();
        private final List<TextPosition> glyphs = new ArrayList<>();

        LayoutStripper(double body) throws IOException {
            this.body = body;
            setSortByPosition(false);
        }

        @Override protected void writeString(String text, List<TextPosition> positions) {
            line.append(text);
            glyphs.addAll(positions);
        }
        @Override protected void writeWordSeparator() { line.append(getWordSeparator()); }
        @Override protected void writeLineSeparator() throws IOException { flush(); super.writeLineSeparator(); }
        @Override protected void writeParagraphStart() throws IOException { flush(); super.writeParagraphStart(); }
        @Override protected void writeParagraphEnd() throws IOException { flush(); super.writeParagraphEnd(); }
        @Override protected void writePageEnd() throws IOException { flush(); super.writePageEnd(); }

        private void flush() throws IOException {
            String text = line.toString().replaceAll("[-&&[^]]", "");
            var kind = classify(text, glyphs, body);
            var shown = visible(glyphs);
            line.setLength(0);
            glyphs.clear();
            if (kind == LineKind.NOISE) { return; }
            if (text.isBlank() || shown.isEmpty()) { writeString(text); return; }
            var where = geometry(shown);
            String content = kind == LineKind.HEADING ? sanitize(text.strip().replaceAll("\\s+", " ")) : sanitize(text);
            writeString(String.format(Locale.ROOT, "%c%c%.1f;%.1f;%.1f;%.1f%c%s", TAG, kind == LineKind.HEADING ? 'H' : 'T',
                    where.x0(), where.x1(), where.baseline(), where.size(), END, content));
        }
    }

    /** Text starting with '#' or a code fence would read as Markdown structure the PDF never had. */
    private static String sanitize(String text) {
        String result = text.replaceFirst("^(\\s*)#+\\s*", "$1");
        String stripped = result.strip();
        if (stripped.startsWith("```") || stripped.startsWith("~~~")) { result = stripped.replaceAll("^[`~]+", ""); }
        return result;
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
