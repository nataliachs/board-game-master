"use strict";
// Presentation-only helpers for the game library: filtering and the selected-game panel.
// Without JavaScript the full list and the form still work.
(() => {
  const grid = document.getElementById("game-grid");
  if (!grid) return;
  const cards = Array.from(grid.querySelectorAll(".game-card"));
  const search = document.getElementById("game-search");
  const tools = document.getElementById("library-tools");
  const filters = document.getElementById("category-filters");
  const fits = document.getElementById("fits-players");
  const players = document.getElementById("players");
  const count = document.getElementById("result-count");
  const empty = document.getElementById("no-results");
  let category = "";

  const categories = Array.from(new Set(cards.map(card => card.dataset.category).filter(Boolean))).sort();
  ["All", ...categories].forEach((name, index) => {
    const chip = document.createElement("button");
    chip.type = "button"; chip.className = "chip"; chip.textContent = name;
    chip.setAttribute("aria-pressed", index === 0 ? "true" : "false");
    chip.addEventListener("click", () => {
      category = index === 0 ? "" : name;
      filters.querySelectorAll(".chip").forEach(other => other.setAttribute("aria-pressed", other === chip ? "true" : "false"));
      apply();
    });
    filters.appendChild(chip);
  });
  tools.hidden = false;

  function apply() {
    const term = (search?.value || "").trim().toLowerCase();
    const wanted = Number(players.value);
    const fitOnly = fits.getAttribute("aria-pressed") === "true";
    let shown = 0;
    cards.forEach(card => {
      const visible = (!term || card.dataset.name.includes(term))
        && (!category || card.dataset.category === category)
        && (!fitOnly || !wanted || (wanted >= Number(card.dataset.min) && wanted <= Number(card.dataset.max)));
      card.hidden = !visible;
      if (visible) shown++;
    });
    count.textContent = shown;
    document.getElementById("result-label").textContent = shown === 1 ? "game" : "games";
    empty.hidden = shown > 0;
  }

  function showSelected(input) {
    const data = input.dataset;
    document.getElementById("selected-name").textContent = data.name;
    const range = data.min === data.max ? data.min : data.min + "–" + data.max;
    document.getElementById("selected-meta").textContent =
      range + " players · about " + data.duration + " min" + (data.category ? " · " + data.category : "");
    document.getElementById("selected-description").textContent = data.description || "";
    const cover = document.getElementById("panel-cover");
    cover.className = "panel-cover " + data.coverClass;
    cover.replaceChildren();
    if (data.cover) {
      const img = document.createElement("img"); img.src = data.cover; img.alt = ""; cover.appendChild(img);
    } else {
      const span = document.createElement("span"); span.textContent = data.initials; cover.appendChild(span);
    }
    players.min = data.min; players.max = data.max;
    const value = Number(players.value);
    if (value < Number(data.min)) players.value = data.min;
    if (value > Number(data.max)) players.value = data.max;
  }

  grid.addEventListener("change", event => { if (event.target.name === "gameId") showSelected(event.target); });
  search?.addEventListener("input", apply);
  players.addEventListener("input", () => { if (fits.getAttribute("aria-pressed") === "true") apply(); });
  fits.addEventListener("click", () => {
    fits.setAttribute("aria-pressed", fits.getAttribute("aria-pressed") === "true" ? "false" : "true");
    apply();
  });
  const checked = grid.querySelector('input[name="gameId"]:checked');
  if (checked) showSelected(checked);
})();
