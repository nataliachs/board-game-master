package com.boardgamemaster.rag.application;

import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RuleDuplicateSuppressor {
    private record Prepared(RuleVectorStore.Match match, String text, Set<String> shingles) { }
    public List<RuleVectorStore.Match> suppress(List<RuleVectorStore.Match> matches, double overlapThreshold) {
        var retained = new ArrayList<Prepared>();
        for (var candidate : matches) {
            String text = normalize(candidate.content());
            var prepared = new Prepared(candidate, text, shingles(text));
            if (retained.stream().noneMatch(previous -> duplicate(previous, prepared, overlapThreshold))) { retained.add(prepared); }
        }
        return retained.stream().map(Prepared::match).toList();
    }
    private boolean duplicate(Prepared previous, Prepared candidate, double threshold) {
        var a = previous.match(); var b = candidate.match();
        // Do not conflate languages, document versions or rulebooks with errata/FAQs/expansions.
        if (!a.gameVersionId().equals(b.gameVersionId()) || !a.language().equals(b.language())
                || a.sourceType() != b.sourceType() || !a.documentVersion().equals(b.documentVersion())) { return false; }
        String left = previous.text(), right = candidate.text();
        if (left.equals(right)) { return true; }
        if (!a.documentId().equals(b.documentId())) { return false; }
        // Never drop a longer passage in favor of a shorter one that may omit a condition.
        boolean containedRange = a.startOffset() <= b.startOffset() && a.endOffset() >= b.endOffset();
        if (!containedRange) { return false; }
        if (!left.contains(right)) { return false; }
        // Five-word shingles preserve negation/numbers/punctuation. Require substantial evidence
        // before considering overlap; tiny rule variants must not collapse into one another.
        var l = previous.shingles(); var r = candidate.shingles();
        if (l.size() < 10 || r.size() < 10) { return false; }
        long shared = l.stream().filter(r::contains).count();
        return (double) shared / Math.max(l.size(), r.size()) >= threshold;
    }
    private String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
    private Set<String> shingles(String value) {
        var words = value.split(" "); var result = new HashSet<String>();
        for (int i = 0; i + 5 <= words.length; i++) { result.add(String.join(" ", Arrays.copyOfRange(words, i, i + 5))); }
        return result;
    }
}
