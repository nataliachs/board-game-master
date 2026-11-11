package com.boardgamemaster.ui.api;

import com.boardgamemaster.common.config.AdminSignIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The admin sign-in page. {@code AdminAccessFilter} sends visitors here and checks the cookie this
 * page sets; the credentials and the cookie's signature belong to {@link AdminSignIn}.
 */
@Controller
public class AdminLoginController {
    private final AdminSignIn signIn;
    public AdminLoginController(AdminSignIn signIn) { this.signIn = signIn; }

    @GetMapping("/admin/login")
    public String page(HttpServletRequest request, HttpServletResponse response, Model model) {
        if (!signIn.configured()) {
            if (!signIn.required()) { return "redirect:/admin"; } // Open local admin: nothing to sign in to.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("unavailable", true);
            return "admin-login";
        }
        if (signIn.signedIn(request)) { return "redirect:/admin"; }
        return "admin-login";
    }

    @PostMapping("/admin/login")
    public String signIn(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        if (!signIn.configured()) { return page(request, response, model); }
        String client = request.getRemoteAddr();
        if (signIn.blocked(client)) {
            response.setStatus(429);
            model.addAttribute("error", "Too many wrong attempts. Wait 15 minutes, then try again.");
            return "admin-login";
        }
        if (!signIn.matches(username, password)) {
            signIn.failed(client);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            model.addAttribute("error", "That username or password is not right. Check them and try again.");
            model.addAttribute("username", username.length() > 100 ? "" : username);
            return "admin-login";
        }
        signIn.succeeded(client);
        response.addHeader(HttpHeaders.SET_COOKIE, signIn.cookie(signIn.issue(), request.isSecure()));
        return "redirect:/admin";
    }

    @PostMapping("/admin/logout")
    public String signOut(HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash) {
        if (!signIn.configured()) { return "redirect:/"; }
        response.addHeader(HttpHeaders.SET_COOKIE, signIn.clearingCookie(request.isSecure()));
        flash.addFlashAttribute("notice", "You are signed out.");
        return "redirect:/admin/login";
    }
}
