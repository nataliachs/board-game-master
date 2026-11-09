package com.boardgamemaster.ui.application;

import com.boardgamemaster.ai.GameDraft;
import com.boardgamemaster.ai.GameDraftClient;
import com.boardgamemaster.rules.application.PdfRuleExtractor;
import com.boardgamemaster.ui.application.AdminUiService.AdminFormException;
import com.boardgamemaster.ui.application.AdminUiService.LessonForm;
import com.boardgamemaster.ui.application.AdminUiService.NewGame;
import com.boardgamemaster.ui.application.AdminUiService.StepForm;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a game from a rulebook PDF: the PDF's text is extracted, an LLM drafts the catalog
 * details, setup steps and lessons, and the result is saved through the same admin services and
 * checks as hand-entered content. The game starts hidden so the admin can review it, and the
 * rulebook text itself is stored and indexed for rule questions, with page numbers.
 */
@Service
public class GameImportService {
    private static final Logger LOG = LoggerFactory.getLogger(GameImportService.class);

    public record ImportResult(UUID gameId, String name, int pages, int setupSteps, int lessons,
            int indexedPassages, int totalPassages, String indexProblem) {
        public boolean indexed() { return indexProblem == null; }
    }

    /** What gets saved, after the draft has been made to fit the app's rules. */
    record Prepared(NewGame game, StepForm steps, LessonForm lessons, int stepCount, int lessonCount) { }

    private final AdminUiService admin;
    private final PdfRuleExtractor pdfs;
    private final GameDraftClient drafts;
    private final TransactionTemplate transaction;

    public GameImportService(AdminUiService admin, PdfRuleExtractor pdfs, GameDraftClient drafts, TransactionTemplate transaction) {
        this.admin = admin; this.pdfs = pdfs; this.drafts = drafts; this.transaction = transaction;
    }

    public ImportResult importRulebook(String fileName, byte[] bytes, String nameOverride) {
        String typedName = nameOverride == null ? "" : nameOverride.strip();
        if (!typedName.isEmpty() && admin.nameTaken(typedName)) { throw taken(typedName); }
        var extracted = pdfs.extract(fileName, bytes);
        // The slow, paid step runs before anything is saved, so a failed draft leaves nothing behind.
        var draft = drafts.draftGame(GameDraftPrompt.request(new String(extracted.markdown(), StandardCharsets.UTF_8)));
        var prepared = prepare(draft, typedName);
        if (typedName.isEmpty() && admin.nameTaken(prepared.game().name())) { throw taken(prepared.game().name()); }

        record Saved(UUID gameId, UUID documentId) { }
        var saved = transaction.execute(status -> {
            UUID gameId = admin.createGame(prepared.game(), false);
            if (prepared.stepCount() > 0) { admin.publishSetup(gameId, prepared.steps()); }
            admin.publishLessons(gameId, prepared.lessons());
            UUID documentId = admin.uploadRules(gameId, "Rulebook", "RULEBOOK",
                    "Imported from " + baseName(fileName), fileName, bytes);
            return new Saved(gameId, documentId);
        });

        try {
            var index = admin.indexRules(saved.gameId(), saved.documentId());
            return new ImportResult(saved.gameId(), prepared.game().name(), extracted.pages(), prepared.stepCount(),
                    prepared.lessonCount(), index.indexedChunks(), index.totalChunks(), null);
        } catch (RuntimeException e) {
            LOG.warn("Imported game rules were saved but not indexed: {}", e.getClass().getName());
            return new ImportResult(saved.gameId(), prepared.game().name(), extracted.pages(), prepared.stepCount(),
                    prepared.lessonCount(), 0, 0, "The rulebook was saved but not indexed for questions yet");
        }
    }

    /** Fits a draft to the app's limits and teaching rules; anything still wrong is caught when it is saved. */
    static Prepared prepare(GameDraft draft, String typedName) {
        String name = cut(typedName.isEmpty() ? draft.name() : typedName, 200);
        if (name.isBlank()) {
            throw new AdminFormException("The rulebook's game name couldn't be found. Type it in Game name and import again.");
        }
        int min = Math.max(1, draft.minPlayers());
        int max = Math.max(min, draft.maxPlayers());
        int minutes = draft.playTimeMinutes() > 0 ? Math.min(draft.playTimeMinutes(), 1440) : 30;
        String language = draft.language().toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*") || language.length() > 35) { language = "en"; }
        String edition = draft.edition().isBlank() ? "Base game" : cut(draft.edition(), 100);
        var game = new NewGame(name, "", cut(draft.publisher(), 200), min, max, minutes, cut(draft.category(), 40),
                cut(draft.description(), 4000), "", edition, language);

        var steps = draft.setupSteps().stream().filter(s -> !s.title().isBlank() && !s.instruction().isBlank()).limit(30).toList();
        var order = new ArrayList<String>(); var keys = new ArrayList<String>(); var titles = new ArrayList<String>();
        var instructions = new ArrayList<String>(); var notes = new ArrayList<String>(); var warnings = new ArrayList<String>();
        for (var step : steps) {
            order.add(String.valueOf(order.size() + 1)); keys.add(""); titles.add(cut(step.title(), 200));
            instructions.add(cut(step.instruction(), 8000)); notes.add(lines(step.notes())); warnings.add(lines(step.warnings()));
        }
        var stepForm = new StepForm(order, keys, titles, instructions, notes, warnings);

        var lessons = draft.lessons().stream().filter(l -> !l.title().isBlank() && !l.explanation().isBlank()).limit(40).toList();
        if (lessons.isEmpty()) { throw new AdminFormException("The AI draft had no lessons. Try the import again."); }
        boolean[] quick = new boolean[lessons.size()], standard = new boolean[lessons.size()], detailed = new boolean[lessons.size()];
        Integer trigger = null;
        for (int i = 0; i < lessons.size(); i++) {
            var lesson = lessons.get(i);
            quick[i] = lesson.quick();
            standard[i] = lesson.standard() || quick[i];
            detailed[i] = lesson.detailed() || standard[i];
            if (!quick[i] && !standard[i] && !detailed[i]) { quick[i] = standard[i] = detailed[i] = true; }
            if (lesson.startsSetup() && trigger == null && !steps.isEmpty()) {
                trigger = i;
                quick[i] = standard[i] = detailed[i] = true;
            }
        }
        if (!any(standard)) { for (int i = 0; i < lessons.size(); i++) { standard[i] = detailed[i] = true; } }
        if (!any(quick)) { for (int i = 0; i < lessons.size(); i++) { quick[i] = standard[i]; } }
        var lessonOrder = new ArrayList<String>(); var lessonKeys = new ArrayList<String>(); var lessonTitles = new ArrayList<String>();
        var explanations = new ArrayList<String>(); var examples = new ArrayList<String>(); var tips = new ArrayList<String>();
        var references = new ArrayList<String>();
        Set<Integer> q = new HashSet<>(), s = new HashSet<>(), d = new HashSet<>();
        for (int i = 0; i < lessons.size(); i++) {
            var lesson = lessons.get(i);
            lessonOrder.add(String.valueOf(i + 1)); lessonKeys.add(""); lessonTitles.add(cut(lesson.title(), 200));
            explanations.add(cut(lesson.explanation(), 8000)); examples.add(lines(lesson.examples()));
            tips.add(lines(lesson.tips())); references.add(lines(lesson.references()));
            if (quick[i]) { q.add(i); }
            if (standard[i]) { s.add(i); }
            if (detailed[i]) { d.add(i); }
        }
        var lessonForm = new LessonForm(lessonOrder, lessonKeys, lessonTitles, explanations, examples, tips, references, q, s, d, trigger);
        return new Prepared(game, stepForm, lessonForm, steps.size(), lessons.size());
    }

    private static AdminFormException taken(String name) {
        return new AdminFormException("A game called \"" + name + "\" already exists. Type a different name in Game name and import again.");
    }
    private static boolean any(boolean[] values) { for (boolean v : values) { if (v) { return true; } } return false; }
    /** One entry per line, at most 10, each on a single line, as the editor's "one per line" fields expect. */
    private static String lines(List<String> values) {
        return String.join("\n", values.stream().map(v -> cut(v.replaceAll("\\s*\\R\\s*", " "), 2000)).limit(10).toList());
    }
    private static String cut(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max).strip();
    }
    private static String baseName(String fileName) {
        String name = fileName == null ? "rulebook.pdf" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return cut(name.replaceAll("\\p{Cntrl}", ""), 300);
    }
}
