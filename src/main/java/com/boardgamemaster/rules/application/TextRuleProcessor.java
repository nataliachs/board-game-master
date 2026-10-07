package com.boardgamemaster.rules.application;

import com.boardgamemaster.rules.domain.RuleImportException;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Deterministic structural chunking. Imported text is data, never executable markup or instructions. */
@Component
public class TextRuleProcessor {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)(?:\\s+#+)?$");
    private static final Pattern PAGE = Pattern.compile("^<!-- page: ([1-9][0-9]{0,8}) -->$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private final RuleIngestionProperties limits;

    public TextRuleProcessor(RuleIngestionProperties limits) { this.limits = limits; }

    public record Chunk(String content, String sectionTitle, Integer pageNumber, int startOffset, int endOffset) { }
    public record Processed(String fileName, String text, List<Chunk> chunks) { }
    private record Part(int start, int end, String section, Integer page, int segment) { }

    public Processed process(String fileName, byte[] bytes) {
        if (fileName == null || fileName.isBlank()) { throw invalid("A .txt or .md file name is required"); }
        // Retain only the basename, never resolve an uploaded path on the server.
        String safeName = fileName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).strip();
        if (safeName.isBlank() || safeName.length() > 255 || safeName.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid file name");
        }
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw new RuleImportException(UNSUPPORTED_FILE_TYPE, "Only UTF-8 .txt and .md files are supported");
        }
        if (bytes == null || bytes.length == 0) { throw invalid("Document is empty"); }
        if (bytes.length > limits.maxBytes()) {
            throw new RuleImportException(DOCUMENT_TOO_LARGE, "Document exceeds configured byte limit");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw invalid("Document must contain valid UTF-8 text"); }
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw invalid("Document contains binary or unsupported control characters");
        }
        // Preserve indentation and paragraph structure; remove trailing whitespace and excess empty lines.
        text = text.replaceAll("(?m)[\\t ]+$", "").replaceAll("\n{3,}", "\n\n")
                .replaceAll("\\A\n+|\n+\\z", "");
        if (text.isBlank()) { throw invalid("Document has no text"); }
        List<Part> parts = paragraphs(text);
        List<Chunk> chunks = chunk(text, parts);
        if (chunks.isEmpty()) { throw invalid("Document has no rule content"); }
        return new Processed(safeName, text, List.copyOf(chunks));
    }

    private List<Part> paragraphs(String text) {
        List<Part> parts = new ArrayList<>();
        String section = null;
        Integer page = null;
        int segment = 0;
        int start = -1;
        int end = 0;
        int offset = 0;
        char fenceCharacter = 0;
        int fenceLength = 0;
        for (String line : text.split("\n", -1)) {
            var heading = HEADING.matcher(line);
            var pageMarker = PAGE.matcher(line);
            boolean outsideFence = fenceCharacter == 0;
            boolean isHeading = outsideFence && heading.matches();
            boolean isPage = outsideFence && pageMarker.matches();
            if (line.isBlank() || isHeading || isPage) {
                if (start >= 0) { split(parts, text, start, end, section, page, segment); start = -1; }
            }
            if (isPage) { page = Integer.valueOf(pageMarker.group(1)); segment++; }
            else if (outsideFence && line.strip().startsWith("<!-- page:")) { throw invalid("Page markers must be <!-- page: N --> with a positive number"); }
            else if (isHeading) {
                section = heading.group(1).strip();
                if (section.length() > 300) { throw invalid("Section titles must be at most 300 characters"); }
                segment++;
                split(parts, text, offset, offset + line.length(), section, page, segment);
            } else if (!line.isBlank()) {
                if (start < 0) { start = offset; }
                end = offset + line.length();
            }
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String delimiter = fence.group(1);
                if (outsideFence) { fenceCharacter = delimiter.charAt(0); fenceLength = delimiter.length(); }
                else if (delimiter.charAt(0) == fenceCharacter && delimiter.length() >= fenceLength
                        && line.strip().equals(delimiter)) { fenceCharacter = 0; }
            }
            offset += line.length() + 1;
        }
        if (start >= 0) { split(parts, text, start, end, section, page, segment); }
        return parts;
    }

    private void split(List<Part> parts, String text, int start, int end, String section, Integer page, int segment) {
        while (start < end) {
            int stop = Math.min(start + limits.chunkSize(), end);
            if (stop < end) {
                // Prefer a sentence end, then a word boundary. A very long token is the final fallback.
                int sentence = -1;
                int word = -1;
                for (int i = start + limits.chunkSize() / 2; i < stop; i++) {
                    if (Character.isWhitespace(text.charAt(i))) {
                        word = i;
                        if (".!?".indexOf(text.charAt(i - 1)) >= 0) { sentence = i; }
                    }
                }
                if (sentence > start) { stop = sentence; }
                else if (word > start) { stop = word; }
                if (Character.isHighSurrogate(text.charAt(stop - 1)) && Character.isLowSurrogate(text.charAt(stop))) { stop--; }
            }
            int trimmedEnd = stop;
            while (trimmedEnd > start && Character.isWhitespace(text.charAt(trimmedEnd - 1))) { trimmedEnd--; }
            if (trimmedEnd > start) {
                parts.add(new Part(start, trimmedEnd, section, page, segment));
            }
            start = stop;
            while (start < end && Character.isWhitespace(text.charAt(start))) { start++; }
        }
    }

    private List<Chunk> chunk(String text, List<Part> parts) {
        List<Chunk> result = new ArrayList<>();
        int first = 0;
        while (first < parts.size()) {
            Part initial = parts.get(first);
            int last = first;
            while (last + 1 < parts.size() && parts.get(last + 1).segment() == initial.segment()
                    && parts.get(last + 1).end() - initial.start() <= limits.chunkSize()) { last++; }
            Part finalPart = parts.get(last);
            result.add(new Chunk(text.substring(initial.start(), finalPart.end()), initial.section(), initial.page(),
                    initial.start(), finalPart.end()));
            if (result.size() > limits.maxChunks()) { throw tooMany(); }
            int next = last + 1;
            int newPart = next;
            // Overlap whole trailing paragraphs/sentences only, and always include new content next time.
            if (next < parts.size() && parts.get(next).segment() == initial.segment()) {
                for (int candidate = last; candidate > first; candidate--) {
                    if (finalPart.end() - parts.get(candidate).start() <= limits.chunkOverlap()
                            && parts.get(newPart).end() - parts.get(candidate).start() <= limits.chunkSize()) {
                        next = candidate;
                    } else { break; }
                }
            }
            first = next;
        }
        return result;
    }

    private static RuleImportException invalid(String message) { return new RuleImportException(INVALID_DOCUMENT, message); }
    private static RuleImportException tooMany() { return new RuleImportException(DOCUMENT_TOO_LARGE, "Document exceeds configured chunk limit"); }
}
