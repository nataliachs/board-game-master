package com.boardgamemaster.ui.api;

import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page a player sees when {@code PaidRequestLimitFilter} turned a question away. It only explains
 * and links back; it reads no session data, so the id is used for the link only when it is a UUID.
 */
@Controller
public class LimitPageController {
    @GetMapping("/ui/limit-reached")
    public String limitReached(@RequestParam(name = "session", required = false) String session, Model model) {
        model.addAttribute("sessionId", uuidOrNull(session));
        return "limit";
    }

    private static UUID uuidOrNull(String value) {
        try { return value == null || value.length() != 36 ? null : UUID.fromString(value); }
        catch (IllegalArgumentException e) { return null; }
    }
}
