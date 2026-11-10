"use strict";
// Runs before first paint so motion styles apply without a flash. If home.js never runs,
// the title that waits for its letter animation is shown anyway once the page has loaded.
document.documentElement.classList.add("js");
addEventListener("load", () => document.querySelectorAll("[data-split]").forEach(row => row.classList.add("split")));
