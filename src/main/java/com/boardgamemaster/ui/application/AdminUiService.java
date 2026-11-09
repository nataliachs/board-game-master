package com.boardgamemaster.ui.application;

import com.boardgamemaster.game.api.GameResponses.GameResponse;
import com.boardgamemaster.game.api.GameResponses.VersionResponse;
import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.game.domain.GameVersion;
import com.boardgamemaster.rag.application.RuleEmbeddingService;
import com.boardgamemaster.rules.application.PdfRuleExtractor;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.DocumentMetadata;
import com.boardgamemaster.rules.domain.RuleSourceType;
import com.boardgamemaster.session.application.TeachingService;
import com.boardgamemaster.session.domain.TeachingDepth;
import com.boardgamemaster.session.domain.TeachingFlow;
import com.boardgamemaster.session.domain.TeachingSection;
import com.boardgamemaster.setup.application.SetupService;
import com.boardgamemaster.setup.domain.SetupFlow;
import com.boardgamemaster.setup.domain.SetupStep;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Form-friendly adapter for the local admin pages. It only translates form rows into the existing
 * catalog, teaching, setup and rule services; publishing rules (append-only flows, version pinning)
 * stay in those services.
 */
@Service
public class AdminUiService {
    /** Blank rows appended to each editor so a game can grow without JavaScript. */
    public static final int BLANK_ROWS = 5;

    public record GameRow(GameResponse game, VersionResponse version, int lessons, int setupSteps, long ruleSources) {
        public boolean ready() { return game.active() && version != null && lessons > 0; }
    }
    public record StepRow(int index, int order, String key, String title, String instruction, String notes, String warnings) { }
    public record LessonRow(int index, int order, String key, String title, String explanation, boolean quick,
            boolean standard, boolean detailed, String examples, String tips, String references, boolean setupTrigger) { }
    public record SourceRow(UUID id, String name, String type, String fileName, int chunks) { }
    public record EditorPage(GameResponse game, VersionResponse version, List<StepRow> steps, List<LessonRow> lessons,
            List<SourceRow> sources, boolean hasSetup, boolean hasLessons) { }
    public record NewGame(String name, String slug, String publisher, int minPlayers, int maxPlayers, int duration,
            String category, String description, String coverImageUrl, String edition, String language) { }
    public record Details(String name, String publisher, int minPlayers, int maxPlayers, int duration,
            String category, String description, String coverImageUrl, boolean active) { }
    public record StepForm(List<String> order, List<String> key, List<String> title, List<String> instruction,
            List<String> notes, List<String> warnings) { }
    public record LessonForm(List<String> order, List<String> key, List<String> title, List<String> explanation,
            List<String> examples, List<String> tips, List<String> references, Set<Integer> quick, Set<Integer> standard,
            Set<Integer> detailed, Integer setupTrigger) { }

    /** A message that is safe to show on the admin page. */
    public static class AdminFormException extends RuntimeException {
        public AdminFormException(String message) { super(message); }
    }

    private final GameService games;
    private final TeachingService teaching;
    private final SetupService setups;
    private final RuleDocumentService documents;
    private final RuleEmbeddingService embeddings;
    private final PdfRuleExtractor pdfs;

    public AdminUiService(GameService games, TeachingService teaching, SetupService setups,
            RuleDocumentService documents, RuleEmbeddingService embeddings, PdfRuleExtractor pdfs) {
        this.games = games; this.teaching = teaching; this.setups = setups; this.documents = documents;
        this.embeddings = embeddings; this.pdfs = pdfs;
    }

    @Transactional(readOnly = true)
    public List<GameRow> games() {
        return games.listGames(0, 100).stream().map(game -> {
            var version = defaultVersion(game.getId());
            if (version == null) { return new GameRow(GameResponse.from(game), null, 0, 0, 0); }
            int lessons = teachingFlow(version.getId()).map(f -> f.sections().size()).orElse(0);
            int steps = setupFlow(version.getId()).map(f -> f.steps().size()).orElse(0);
            long sources = documents.listDocuments(version.getId(), 0, 1).getTotalElements();
            return new GameRow(GameResponse.from(game), VersionResponse.from(version), lessons, steps, sources);
        }).toList();
    }

    @Transactional
    public UUID createGame(NewGame form) {
        return createGame(form, true);
    }

    /** True when a game with this name's web-address name (slug) already exists. */
    @Transactional(readOnly = true)
    public boolean nameTaken(String name) {
        return games.slugExists(slugify(name));
    }

    /** {@code visible=false} creates the game hidden from players, for example while an imported draft is reviewed. */
    @Transactional
    public UUID createGame(NewGame form, boolean visible) {
        String slug = blank(form.slug()) ? slugify(form.name()) : form.slug().strip();
        var game = games.createGame(slug, form.name(), form.publisher(), form.minPlayers(), form.maxPlayers(), form.duration(),
                form.description(), visible, form.category(), form.coverImageUrl());
        String edition = blank(form.edition()) ? "Base game" : form.edition();
        String language = blank(form.language()) ? "en" : form.language().strip().toLowerCase(Locale.ROOT);
        games.createVersion(game.getId(), edition, "base-game", language, null, true, true);
        return game.getId();
    }

    @Transactional(readOnly = true)
    public EditorPage editor(UUID gameId) {
        var game = games.getGame(gameId);
        var version = defaultVersion(gameId);
        if (version == null) {
            return new EditorPage(GameResponse.from(game), null, List.of(), List.of(), List.of(), false, false);
        }
        var setup = setupFlow(version.getId());
        var flow = teachingFlow(version.getId());
        var steps = new ArrayList<StepRow>();
        setup.ifPresent(f -> f.steps().forEach(s -> steps.add(new StepRow(steps.size(), steps.size() + 1, s.key(), s.title(),
                s.instruction(), lines(s.notes()), lines(s.warnings())))));
        var lessons = new ArrayList<LessonRow>();
        flow.ifPresent(f -> f.sections().forEach(s -> lessons.add(new LessonRow(lessons.size(), lessons.size() + 1, s.key(),
                s.title(), s.explanation(), s.depths().contains(TeachingDepth.QUICK), s.depths().contains(TeachingDepth.STANDARD),
                s.depths().contains(TeachingDepth.DETAILED), lines(s.examples()), lines(s.tips()), lines(s.references()), s.setupTrigger()))));
        var sources = documents.listDocuments(version.getId(), 0, 100).stream()
                .map(d -> new SourceRow(d.getId(), d.getName(), d.getSourceType().name(), d.getFileName(), d.getChunkCount())).toList();
        return new EditorPage(GameResponse.from(game), VersionResponse.from(version), withBlankSteps(steps),
                withBlankLessons(lessons), sources, setup.isPresent(), flow.isPresent());
    }

    /** Re-shows what the person typed after a rejected save, so nothing is lost. */
    public List<StepRow> stepRows(StepForm form) {
        var rows = new ArrayList<StepRow>();
        for (int i = 0; i < size(form.title()); i++) {
            rows.add(new StepRow(i, number(get(form.order(), i), i + 1), get(form.key(), i), get(form.title(), i),
                    get(form.instruction(), i), get(form.notes(), i), get(form.warnings(), i)));
        }
        return rows;
    }

    public List<LessonRow> lessonRows(LessonForm form) {
        var rows = new ArrayList<LessonRow>();
        for (int i = 0; i < size(form.title()); i++) {
            rows.add(new LessonRow(i, number(get(form.order(), i), i + 1), get(form.key(), i), get(form.title(), i),
                    get(form.explanation(), i), form.quick().contains(i), form.standard().contains(i), form.detailed().contains(i),
                    get(form.examples(), i), get(form.tips(), i), get(form.references(), i), Objects.equals(form.setupTrigger(), i)));
        }
        return rows;
    }

    @Transactional
    public void updateDetails(UUID gameId, Details form) {
        games.updateGameDetails(gameId, form.name(), form.publisher(), form.minPlayers(), form.maxPlayers(), form.duration(),
                form.description(), form.active(), form.category(), form.coverImageUrl());
    }

    @Transactional
    public SetupFlow publishSetup(UUID gameId, StepForm form) {
        var version = requireVersion(gameId);
        var rows = stepRows(form).stream().filter(r -> !blank(r.title()) || !blank(r.instruction())).toList();
        if (rows.isEmpty()) { throw new AdminFormException("Add at least one setup step with a title and an instruction."); }
        var keys = new HashSet<String>();
        var steps = new ArrayList<SetupStep>();
        var ordered = rows.stream().sorted(Comparator.comparingInt(StepRow::order).thenComparingInt(StepRow::index)).toList();
        for (var row : ordered) {
            String label = "Setup step \"" + (blank(row.title()) ? "#" + (row.index() + 1) : row.title().strip()) + "\"";
            if (blank(row.title()) || blank(row.instruction())) { throw new AdminFormException(label + " needs both a title and an instruction."); }
            limit(row.title(), 200, label + " title"); limit(row.instruction(), 8000, label + " instruction");
            steps.add(new SetupStep(key(row.key(), row.title(), "STEP", keys), steps.size(), row.title(), row.instruction(),
                    split(row.notes(), label + " notes"), split(row.warnings(), label + " warnings")));
        }
        return setups.publish(version.getId(), games.getGame(gameId).getName() + " setup", steps);
    }

    @Transactional
    public TeachingFlow publishLessons(UUID gameId, LessonForm form) {
        var version = requireVersion(gameId);
        var rows = lessonRows(form).stream().filter(r -> !blank(r.title()) || !blank(r.explanation())).toList();
        if (rows.isEmpty()) { throw new AdminFormException("Add at least one lesson with a title and an explanation."); }
        var keys = new HashSet<String>();
        var sections = new ArrayList<TeachingSection>();
        var ordered = rows.stream().sorted(Comparator.comparingInt(LessonRow::order).thenComparingInt(LessonRow::index)).toList();
        for (var row : ordered) {
            String label = "Lesson \"" + (blank(row.title()) ? "#" + (row.index() + 1) : row.title().strip()) + "\"";
            if (blank(row.title()) || blank(row.explanation())) { throw new AdminFormException(label + " needs both a title and an explanation."); }
            limit(row.title(), 200, label + " title"); limit(row.explanation(), 8000, label + " explanation");
            var depths = EnumSet.noneOf(TeachingDepth.class);
            if (row.quick()) { depths.add(TeachingDepth.QUICK); }
            if (row.standard()) { depths.add(TeachingDepth.STANDARD); }
            if (row.detailed()) { depths.add(TeachingDepth.DETAILED); }
            if (depths.isEmpty()) { throw new AdminFormException(label + " must be shown in at least one teaching style."); }
            if (row.setupTrigger() && depths.size() < 3) {
                throw new AdminFormException(label + " starts setup, so it must be shown in all three teaching styles.");
            }
            sections.add(new TeachingSection(key(row.key(), row.title(), "LESSON", keys), sections.size(), row.title(), row.explanation(),
                    depths, split(row.examples(), label + " examples"), split(row.tips(), label + " tips"),
                    split(row.references(), label + " references"), row.setupTrigger()));
        }
        for (var depth : TeachingDepth.values()) {
            if (sections.stream().noneMatch(s -> s.depths().contains(depth))) {
                throw new AdminFormException("Every teaching style needs at least one lesson; none is shown in " + depth.name() + ".");
            }
        }
        if (sections.stream().anyMatch(TeachingSection::setupTrigger) && setupFlow(version.getId()).isEmpty()) {
            throw new AdminFormException("Save the setup steps first: the lesson marked \"Starts setup\" needs them.");
        }
        return teaching.publish(version.getId(), games.getGame(gameId).getName() + " lessons", sections);
    }

    /** Stores a .txt, .md or .pdf rules source; a PDF keeps its page numbers for citations. Returns the document id. */
    @Transactional
    public UUID uploadRules(UUID gameId, String name, String type, String sourceReference, String fileName, byte[] bytes) {
        var version = requireVersion(gameId);
        if (bytes == null || bytes.length == 0) { throw new AdminFormException("Choose a .pdf, .txt or .md rules file to upload."); }
        RuleSourceType sourceType;
        try { sourceType = RuleSourceType.valueOf(type); }
        catch (RuntimeException e) { throw new AdminFormException("Choose a source type."); }
        String storedName = fileName;
        byte[] text = bytes;
        if (PdfRuleExtractor.isPdf(fileName)) {
            var extracted = pdfs.extract(fileName, bytes);
            storedName = extracted.fileName();
            text = extracted.markdown();
        }
        return documents.ingest(version.getId(), new DocumentMetadata(blank(name) ? fileName : name, sourceType, "1",
                version.getLanguage(), blank(sourceReference) ? "Uploaded through the admin page" : sourceReference),
                storedName, text).getId();
    }

    public RuleEmbeddingService.Removed removeRules(UUID gameId, UUID documentId) {
        return embeddings.remove(requireVersion(gameId).getId(), documentId);
    }

    public RuleEmbeddingService.IndexResult indexRules(UUID gameId, UUID documentId) {
        return embeddings.index(requireVersion(gameId).getId(), documentId);
    }

    // --- helpers -------------------------------------------------------------------------

    private GameVersion defaultVersion(UUID gameId) {
        return games.listVersions(gameId).stream().filter(GameVersion::isDefaultVersion).findFirst().orElse(null);
    }
    private GameVersion requireVersion(UUID gameId) {
        var version = defaultVersion(gameId);
        if (version == null) { throw new AdminFormException("This game has no default version yet."); }
        return version;
    }
    private Optional<TeachingFlow> teachingFlow(UUID version) {
        return teaching.findCurrent(version);
    }
    private Optional<SetupFlow> setupFlow(UUID version) {
        return setups.findLatest(version);
    }
    private static List<StepRow> withBlankSteps(List<StepRow> rows) {
        var all = new ArrayList<>(rows);
        for (int i = 0; i < BLANK_ROWS; i++) { all.add(new StepRow(all.size(), all.size() + 1, "", "", "", "", "")); }
        return all;
    }
    private static List<LessonRow> withBlankLessons(List<LessonRow> rows) {
        var all = new ArrayList<>(rows);
        for (int i = 0; i < BLANK_ROWS; i++) {
            all.add(new LessonRow(all.size(), all.size() + 1, "", "", "", true, true, true, "", "", "", false));
        }
        return all;
    }
    static String slugify(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) { throw new AdminFormException("Enter a game name."); }
        return slug.length() > 100 ? slug.substring(0, 100).replaceAll("-+$", "") : slug;
    }
    static String key(String typed, String title, String prefix, Set<String> used) {
        String source = blank(typed) ? title : typed;
        String key = source.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) { key = prefix + "_" + key; }
        if (key.length() > 70) { key = key.substring(0, 70); }
        String unique = key; int n = 2;
        while (!used.add(unique)) { unique = key + "_" + n++; }
        return unique;
    }
    private static List<String> split(String text, String label) {
        if (blank(text)) { return List.of(); }
        var lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() > 10) { throw new AdminFormException(label + " can have at most 10 lines."); }
        lines.forEach(l -> limit(l, 2000, label));
        return lines;
    }
    private static void limit(String text, int max, String label) {
        if (text != null && text.strip().length() > max) { throw new AdminFormException(label + " must be at most " + max + " characters."); }
    }
    private static String lines(List<String> values) { return String.join("\n", values); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int size(List<String> values) { return values == null ? 0 : values.size(); }
    private static String get(List<String> values, int i) { return values != null && i < values.size() && values.get(i) != null ? values.get(i) : ""; }
    private static int number(String value, int fallback) {
        try { return blank(value) ? fallback : Math.max(0, Integer.parseInt(value.strip())); } catch (NumberFormatException e) { return fallback; }
    }
}
