package com.boardgamemaster.rules.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small text PDFs for tests: one inner list of lines per page. */
public final class TestPdfs {
    private TestPdfs() { }

    /** One page with two side-by-side columns, written left column first, like most rulebook layouts. */
    public static byte[] twoColumns(List<String> left, List<String> right) {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                for (var column : List.of(left, right)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.setLeading(12);
                    content.newLineAtOffset(column == left ? 40 : 320, 740);
                    for (var line : column) { content.showText(line); content.newLine(); }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line of text placed on a page: {@code top} is measured from the top edge, in points. */
    public record Placed(String text, float x, float top, float size, boolean bold, boolean rotated) {
        public static Placed body(String text, float top) { return new Placed(text, 50, top, 11, false, false); }
        public static Placed heading(String text, float top, float size) { return new Placed(text, 50, top, size, false, false); }
        public static Placed bold(String text, float top) { return new Placed(text, 50, top, 11, true, false); }
    }

    /** One page per inner list; lines are written in list order, so a heading can be stored after its text. */
    public static byte[] placed(List<List<Placed>> pages) {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (var lines : pages) {
                var page = new PDPage();
                document.addPage(page);
                float height = page.getMediaBox().getHeight();
                try (var content = new PDPageContentStream(document, page)) {
                    for (var line : lines) {
                        content.beginText();
                        content.setFont(line.bold() ? bold : regular, line.size());
                        float y = height - line.top();
                        content.setTextMatrix(line.rotated()
                                ? org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(90), line.x(), y)
                                : org.apache.pdfbox.util.Matrix.getTranslateInstance(line.x(), y));
                        content.showText(line.text());
                        content.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] pdf(List<List<String>> pages) {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (var lines : pages) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(14);
                    content.newLineAtOffset(50, 740);
                    for (var line : lines) { content.showText(line); content.newLine(); }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
