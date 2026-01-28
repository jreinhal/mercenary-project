package com.jreinhal.mercenary.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback controller for client-side routing.
 *
 * Forwards unknown frontend routes to index.html so the JavaScript SPA
 * can handle routing. This prevents 404/500 errors when users directly
 * navigate to frontend routes like /files.
 *
 * Only matches paths without file extensions to avoid intercepting
 * static resources like .js, .css, .png files.
 */
@Controller
public class SpaFallbackController {

    /**
     * Forward SPA routes to index.html.
     *
     * Pattern explanation:
     * - {path:[^\\.]*} matches any path without a dot (no file extension)
     * - This ensures /files, /dashboard, etc. are forwarded
     * - But /js/app.js, /css/style.css are NOT matched (handled by static resources)
     */
    @GetMapping(value = "/{path:[^\\.]*}")
    public String forwardSingleLevel() {
        return "forward:/index.html";
    }

    /**
     * Forward nested SPA routes to index.html.
     *
     * Handles routes like /settings/profile, /admin/users, etc.
     */
    @GetMapping(value = "/{path:[^\\.]*}/{subpath:[^\\.]*}")
    public String forwardTwoLevels() {
        return "forward:/index.html";
    }
}
