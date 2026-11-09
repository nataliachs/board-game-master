package com.boardgamemaster.ui.api;

import com.boardgamemaster.common.exception.PresentationFailure;
import com.boardgamemaster.rules.domain.RuleImportException;
import com.boardgamemaster.ui.application.AdminUiService;
import com.boardgamemaster.ui.application.AdminUiService.*;
import com.boardgamemaster.ui.application.GameImportService;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin pages for adding games, lessons, setup steps and rule sources, including drafting a whole
 * game from a rulebook PDF. {@code AdminAccessFilter} protects them with a password when one is set.
 */
@Controller
@RequestMapping("/admin")
public class AdminUiController {
    private final AdminUiService admin;
    private final GameImportService imports;
    public AdminUiController(AdminUiService admin, GameImportService imports) { this.admin = admin; this.imports = imports; }

    @GetMapping
    public String games(Model model) {
        model.addAttribute("games", admin.games());
        return "admin";
    }

    @GetMapping("/games/new")
    public String newGame(Model model) {
        model.addAttribute("form", new NewGame("", "", "", 2, 4, 30, "", "", "", "Base game", "en"));
        return "admin-new";
    }

    @PostMapping("/games")
    public String create(@RequestParam MultiValueMap<String, String> params, Model model, RedirectAttributes flash) {
        var form = new NewGame(first(params, "name"), first(params, "slug"), first(params, "publisher"),
                integer(params, "minPlayers", 0), integer(params, "maxPlayers", 0), integer(params, "duration", 0),
                first(params, "category"), first(params, "description"), first(params, "coverImageUrl"),
                first(params, "edition"), first(params, "language"));
        try {
            UUID id = admin.createGame(form);
            flash.addFlashAttribute("notice", form.name() + " was created. Next, add its setup steps and lessons.");
            return "redirect:/admin/games/" + id;
        } catch (RuntimeException e) {
            model.addAttribute("error", message(e));
            model.addAttribute("form", form);
            return "admin-new";
        }
    }

    @PostMapping("/games/import")
    public String importRulebook(@RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String ownCopy,
            Model model, RedirectAttributes flash) throws IOException {
        try {
            if (file == null || file.isEmpty()) { throw new AdminFormException("Choose the rulebook PDF to import."); }
            if (ownCopy.isBlank()) { throw new AdminFormException("Confirm that this is your own copy of the rulebook, for private use."); }
            var result = imports.importRulebook(Objects.requireNonNullElse(file.getOriginalFilename(), "rulebook.pdf"), file.getBytes(), name);
            String rules = result.indexed()
                    ? result.indexedPassages() + " rulebook passages are indexed for questions."
                    : result.indexProblem() + ": use Index for questions in section 4.";
            flash.addFlashAttribute("notice", result.name() + " was drafted from " + result.pages() + " PDF pages: "
                    + result.setupSteps() + " setup steps and " + result.lessons() + " lessons. " + rules
                    + " The game is hidden from players: check every section, then tick Show this game to players and save.");
            return "redirect:/admin/games/" + result.gameId();
        } catch (RuntimeException e) {
            model.addAttribute("importError", message(e));
            model.addAttribute("importName", name);
            model.addAttribute("form", new NewGame("", "", "", 2, 4, 30, "", "", "", "Base game", "en"));
            return "admin-new";
        }
    }

    @GetMapping("/games/{id}")
    public String editor(@PathVariable UUID id, Model model) {
        var page = admin.editor(id);
        model.addAttribute("page", page);
        model.addAttribute("steps", page.steps());
        model.addAttribute("lessons", page.lessons());
        return "admin-game";
    }

    @PostMapping("/games/{id}/details")
    public String details(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> params, RedirectAttributes flash) {
        var form = new Details(first(params, "name"), first(params, "publisher"), integer(params, "minPlayers", 0),
                integer(params, "maxPlayers", 0), integer(params, "duration", 0), first(params, "category"),
                first(params, "description"), first(params, "coverImageUrl"), params.containsKey("active"));
        return redirectAfter(id, flash, "Game details saved.", () -> { admin.updateDetails(id, form); return null; });
    }

    @PostMapping("/games/{id}/setup")
    public String setup(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> params, Model model, RedirectAttributes flash) {
        var form = new StepForm(all(params, "stepOrder"), all(params, "stepKey"), all(params, "stepTitle"),
                all(params, "stepInstruction"), all(params, "stepNotes"), all(params, "stepWarnings"));
        try {
            var flow = admin.publishSetup(id, form);
            flash.addFlashAttribute("notice", flow.steps().size() + " setup steps saved. New sessions use them; sessions already started keep their earlier steps.");
            return "redirect:/admin/games/" + id + "#setup";
        } catch (RuntimeException e) {
            var page = admin.editor(id);
            model.addAttribute("page", page);
            model.addAttribute("steps", admin.stepRows(form));
            model.addAttribute("lessons", page.lessons());
            model.addAttribute("setupError", message(e));
            return "admin-game";
        }
    }

    @PostMapping("/games/{id}/lessons")
    public String lessons(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> params, Model model, RedirectAttributes flash) {
        var form = new LessonForm(all(params, "lessonOrder"), all(params, "lessonKey"), all(params, "lessonTitle"),
                all(params, "lessonExplanation"), all(params, "lessonExamples"), all(params, "lessonTips"),
                all(params, "lessonReferences"), indexes(params, "lessonQuick"), indexes(params, "lessonStandard"),
                indexes(params, "lessonDetailed"), integerOrNull(first(params, "setupTrigger")));
        try {
            var flow = admin.publishLessons(id, form);
            flash.addFlashAttribute("notice", flow.sections().size() + " lessons saved. New sessions use them; sessions already started keep their earlier lessons.");
            return "redirect:/admin/games/" + id + "#lessons";
        } catch (RuntimeException e) {
            var page = admin.editor(id);
            model.addAttribute("page", page);
            model.addAttribute("steps", page.steps());
            model.addAttribute("lessons", admin.lessonRows(form));
            model.addAttribute("lessonError", message(e));
            return "admin-game";
        }
    }

    @PostMapping("/games/{id}/rules")
    public String uploadRules(@PathVariable UUID id, @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "RULEBOOK") String sourceType, @RequestParam(defaultValue = "") String sourceReference,
            RedirectAttributes flash) throws IOException {
        byte[] bytes = file.getBytes();
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "rules.txt");
        return redirectAfter(id, flash, "Rules uploaded. Index them to use them for questions.", () -> {
            admin.uploadRules(id, name, sourceType, sourceReference, fileName, bytes); return null; }, "#rules");
    }

    @PostMapping("/games/{id}/rules/{documentId}/remove")
    public String removeRules(@PathVariable UUID id, @PathVariable UUID documentId,
            @RequestParam(defaultValue = "") String confirm, RedirectAttributes flash) {
        return redirectAfter(id, flash, null, () -> {
            if (confirm.isBlank()) { throw new AdminFormException("Tick the box next to Remove to confirm."); }
            var removed = admin.removeRules(id, documentId);
            flash.addFlashAttribute("notice", "\"" + removed.documentName() + "\" was removed with its " + removed.passages()
                    + " passages. It no longer answers questions; past answers keep their quotes.");
            return null; }, "#rules");
    }

    @PostMapping("/games/{id}/rules/{documentId}/index")
    public String index(@PathVariable UUID id, @PathVariable UUID documentId, RedirectAttributes flash) {
        return redirectAfter(id, flash, null, () -> {
            var result = admin.indexRules(id, documentId);
            flash.addFlashAttribute("notice", result.indexedChunks() + " of " + result.totalChunks() + " passages are indexed for questions.");
            return null; }, "#rules");
    }

    private String redirectAfter(UUID id, RedirectAttributes flash, String notice, Supplier<Object> task) {
        return redirectAfter(id, flash, notice, task, "");
    }
    private String redirectAfter(UUID id, RedirectAttributes flash, String notice, Supplier<Object> task, String anchor) {
        try { task.get(); if (notice != null) { flash.addFlashAttribute("notice", notice); } }
        catch (RuntimeException e) { flash.addFlashAttribute("error", message(e)); }
        return "redirect:/admin/games/" + id + anchor;
    }
    private static String message(RuntimeException e) {
        if (e instanceof AdminFormException || e instanceof RuleImportException) { return e.getMessage(); }
        return PresentationFailure.from(e).message();
    }
    private static String first(MultiValueMap<String, String> params, String name) {
        var value = params.getFirst(name);
        return value == null ? "" : value;
    }
    private static List<String> all(MultiValueMap<String, String> params, String name) {
        return params.getOrDefault(name, List.of());
    }
    private static Set<Integer> indexes(MultiValueMap<String, String> params, String name) {
        var result = new HashSet<Integer>();
        for (var value : all(params, name)) { var n = integerOrNull(value); if (n != null) { result.add(n); } }
        return result;
    }
    private static int integer(MultiValueMap<String, String> params, String name, int fallback) {
        var n = integerOrNull(first(params, name));
        return n == null ? fallback : n;
    }
    private static Integer integerOrNull(String value) {
        try { return value == null || value.isBlank() ? null : Integer.valueOf(value.strip()); }
        catch (NumberFormatException e) { return null; }
    }
}
