package com.boardgamemaster.ui.api;

import com.boardgamemaster.ui.application.DemoUiService;
import com.boardgamemaster.session.application.SessionService;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.setup.application.SetupService;
import com.boardgamemaster.common.exception.PresentationFailure;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DemoUiController {
    private final DemoUiService views;
    private final SessionService sessions;
    private final SetupService setups;
    public DemoUiController(DemoUiService views, SessionService sessions, SetupService setups) { this.views = views; this.sessions = sessions; this.setups = setups; }
    @GetMapping("/")
    public String home(@RequestParam(defaultValue = "0") int page, Model model) {
        var result = views.games(page); model.addAttribute("games", result.games()); model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("gamePage", page); model.addAttribute("voice", views.capabilities()); return "home";
    }
    @PostMapping("/ui/sessions")
    public String create(@RequestParam UUID gameId, @RequestParam int playerCount,
            @RequestParam(defaultValue = "STANDARD") TeachingDepth teachingDepth, RedirectAttributes flash) {
        try { return "redirect:/sessions/" + sessions.create(gameId, playerCount, teachingDepth).session().id(); }
        catch (RuntimeException e) { flash.addFlashAttribute("error", PresentationFailure.from(e).message()); return "redirect:/"; }
    }
    @GetMapping("/sessions/{id}")
    public String current(@PathVariable UUID id, @RequestParam(required = false) Long beforeSequence, Model model) {
        model.addAttribute("page", views.page(id, beforeSequence)); return "session";
    }
    @GetMapping("/sessions/{id}/review")
    public String review(@PathVariable UUID id, Model model) { model.addAttribute("page", views.page(id, null)); return "review"; }
    @GetMapping("/sessions/{id}/conversation")
    public String conversation(@PathVariable UUID id, Model model) { model.addAttribute("page", views.page(id, null)); return "fragments :: conversation"; }
    @PostMapping("/ui/sessions/{id}/teaching")
    public String teaching(@PathVariable UUID id, @RequestParam GameSession.Action action, @RequestParam long expectedRevision, RedirectAttributes flash) {
        return action(id, flash, () -> { var view = sessions.navigate(id, action, expectedRevision);
            if (action == GameSession.Action.EXPLAIN_AGAIN) { flash.addFlashAttribute("explanation", view.explanation()); } });
    }
    @PostMapping("/ui/sessions/{id}/setup")
    public String setup(@PathVariable UUID id, @RequestParam SetupService.Action action, @RequestParam long expectedRevision, RedirectAttributes flash) {
        return action(id, flash, () -> setups.act(id, action, expectedRevision));
    }
    @PostMapping("/ui/sessions/{id}/messages")
    public String ask(@PathVariable UUID id, @RequestParam String question, RedirectAttributes flash) {
        return action(id, flash, () -> { sessions.ask(id, question); flash.addFlashAttribute("notice", "Answer saved. Your lesson and setup position are unchanged."); });
    }
    @PostMapping("/ui/sessions/{id}/complete")
    public String complete(@PathVariable UUID id, @RequestParam long expectedRevision, RedirectAttributes flash) {
        return action(id, flash, () -> sessions.complete(id, expectedRevision));
    }
    private String action(UUID id, RedirectAttributes flash, Runnable task) {
        try { task.run(); } catch (RuntimeException e) { flash.addFlashAttribute("error", PresentationFailure.from(e).message()); }
        return "redirect:/sessions/" + id;
    }
}
