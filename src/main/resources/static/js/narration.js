"use strict";
// Playback preferences only; the backend selects the current lesson/step and all spoken text.
(() => {
  const preferenceKey = "bgm.spokenGuidance";
  const read = key => { try { return sessionStorage.getItem(key); } catch (_) { return null; } };
  const write = (key, value) => { try { sessionStorage.setItem(key, value); } catch (_) { /* Controls still work without storage. */ } };
  const choice = document.getElementById("spoken-guidance-choice");
  if (choice) {
    choice.checked = read(preferenceKey) === "true";
    choice.addEventListener("change", () => write(preferenceKey, String(choice.checked)));
  }
  const panel = document.getElementById("narration");
  if (!panel) return;
  const enabled = document.getElementById("spoken-guidance");
  const listen = document.getElementById("listen-guidance");
  const stop = document.getElementById("stop-guidance");
  const player = document.getElementById("guidance-audio");
  const status = document.getElementById("guidance-status");
  const cache = new Map();
  const lastKey = "bgm.narrated:" + panel.dataset.session;
  const contentKey = panel.dataset.stage + ":" + panel.dataset.section + ":" + panel.dataset.explainAgain;
  let request = null, generation = 0, segment = 0, total = 1, stopped = true;

  function halt(message) {
    stopped = true; generation++; request?.abort(); request = null;
    player.pause(); listen.disabled = false;
    if (message) status.textContent = message;
  }
  async function play(index) {
    halt(); stopped = false;
    const token = generation; segment = index;
    listen.disabled = true; status.textContent = "Preparing the spoken explanation…";
    document.dispatchEvent(new CustomEvent("bgm-audio-start", {detail:"narration"}));
    try {
      let item = cache.get(index);
      if (!item) {
        request = new AbortController();
        const revision = Number(document.querySelector('input[name="expectedRevision"]').value);
        const response = await fetch(panel.dataset.url, {
          method:"POST", headers:{"Content-Type":"application/json", "Accept":"application/json"}, signal:request.signal,
          body:JSON.stringify({expectedRevision:revision, explainAgain:panel.dataset.explainAgain === "true", segmentIndex:index})
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || "Spoken guidance is unavailable. You can keep reading and navigating.");
        if (token !== generation) return;
        if (result.audio?.mediaType !== "audio/mpeg") throw new Error("The spoken explanation could not be played. The text is still available.");
        const bytes = Uint8Array.from(atob(result.audio.base64), character => character.charCodeAt(0));
        item = {url:URL.createObjectURL(new Blob([bytes], {type:"audio/mpeg"})), total:result.totalSegments};
        cache.set(index, item);
      }
      if (token !== generation) return;
      total = item.total; player.src = item.url; player.hidden = false;
      status.textContent = "Spoken explanation · part " + (index + 1) + " of " + total + ". Listening won't advance your session.";
      await player.play().catch(() => { status.textContent = "Press Play below to hear the explanation. Text and navigation remain available."; });
    } catch (error) {
      if (token === generation && error.name !== "AbortError") {
        halt(error instanceof TypeError ? "Could not reach the server. Use Listen to try again, or keep reading." : error.message);
      }
    } finally { if (token === generation) listen.disabled = false; }
  }
  function begin() {
    write(lastKey, contentKey); // No repeated paid request after a refresh, question or review return.
    play(0);
  }
  enabled.checked = read(preferenceKey) === "true";
  enabled.addEventListener("change", () => {
    write(preferenceKey, String(enabled.checked));
    if (enabled.checked) begin(); else halt("Spoken guidance is off. You can continue with text.");
  });
  listen.addEventListener("click", begin);
  document.querySelectorAll('form').forEach(form => form.addEventListener("submit", event => {
    if (["REPEAT", "EXPLAIN_AGAIN"].includes(event.submitter?.value)) {
      try { sessionStorage.removeItem(lastKey); } catch (_) { /* Explicit Listen remains available. */ }
    }
  }));
  stop.addEventListener("click", () => halt("Stopped. Your lesson and setup position are unchanged."));
  player.addEventListener("ended", () => {
    if (!stopped && segment + 1 < total) play(segment + 1);
    else if (!stopped) status.textContent = "Explanation finished. Continue when your table is ready.";
  });
  player.addEventListener("error", () => halt("This audio could not be played. Try Listen again, or keep reading the explanation."));
  document.addEventListener("bgm-audio-start", event => { if (event.detail !== "narration") halt("Guidance paused while you ask a question."); });
  window.addEventListener("pagehide", () => { halt(); cache.forEach(item => URL.revokeObjectURL(item.url)); cache.clear(); });
  if (enabled.checked && read(lastKey) !== contentKey) begin();
})();
