// Presentation only: open the conversation box at its newest message.
// On an "Older messages" page (?beforeSequence=…) it stays at the top instead.
// The voice panel replaces #conversation after a spoken question and calls this again.
(() => {
  function showLatest() {
    const log = document.getElementById("conversation-log");
    if (!log || new URLSearchParams(location.search).has("beforeSequence")) { return; }
    log.scrollTop = log.scrollHeight;
  }
  window.bgmShowLatestMessage = showLatest;
  document.addEventListener("DOMContentLoaded", showLatest);
})();
