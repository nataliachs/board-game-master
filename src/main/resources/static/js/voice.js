"use strict";
// Browser recording/presentation only. Workflow stages and revisions come from the backend.
(() => {
  const panel = document.getElementById("voice");
  if (!panel) return;
  const speak = document.getElementById("speak");
  const upload = document.getElementById("upload-audio");
  if (!speak || !upload) return;
  const status = document.getElementById("recording-status");
  const player = document.getElementById("answer-audio");
  document.addEventListener("bgm-audio-start", event => { if (event.detail !== "answer") player.pause(); });
  let holding = false, busy = false, recorder = null, stream = null, timer = null, audioUrl = null, parts = [], size = 0, cancelled = false;
  const limit = Number(panel.dataset.maxBytes);
  const showError = message => {
    document.getElementById("voice-result").hidden = false;
    const error = document.getElementById("voice-error"); error.textContent = message; error.hidden = false;
  };
  function stopTracks() { if (stream) stream.getTracks().forEach(track => track.stop()); stream = null; }
  function release() {
    holding = false; clearTimeout(timer); speak.setAttribute("aria-pressed", "false");
    if (recorder && recorder.state === "recording") recorder.stop();
    stopTracks();
  }
  async function send(blob, name) {
    if (busy) return;
    if (!blob || !blob.size || blob.size > limit) { showError("Record a short, nonempty question within the upload limit."); return; }
    document.dispatchEvent(new CustomEvent("bgm-audio-start", {detail:"question"}));
    busy = true; speak.disabled = true; upload.disabled = true; status.textContent = "Transcribing and checking the rules…";
    document.getElementById("voice-error").hidden = true; document.getElementById("transcription").textContent = "";
    document.getElementById("voice-answer").textContent = ""; document.getElementById("voice-audio-notice").textContent = "";
    player.pause(); player.hidden = true; player.removeAttribute("src"); if (audioUrl) URL.revokeObjectURL(audioUrl); audioUrl = null;
    try {
      const form = new FormData(); form.append("audio", blob, name);
      const response = await fetch(panel.dataset.url, {method:"POST", body:form, headers:{"Accept":"application/json"}});
      const result = await response.json();
      document.getElementById("voice-result").hidden = false;
      document.getElementById("transcription").textContent = result.transcription || "No transcription is available.";
      if (!response.ok || result.answerError) { showError(result.answerError?.message || result.message || "Voice could not complete this question. Text is still available."); return; }
      document.getElementById("voice-answer").textContent = result.message.answer.answer;
      document.querySelectorAll('input[name="expectedRevision"]').forEach(input => { input.value = result.message.revision; });
      document.getElementById("voice-audio-notice").textContent = result.audioError?.message || "";
      if (result.audio && result.audio.mediaType === "audio/mpeg") {
        document.dispatchEvent(new CustomEvent("bgm-audio-start", {detail:"answer"}));
        const bytes = Uint8Array.from(atob(result.audio.base64), character => character.charCodeAt(0));
        audioUrl = URL.createObjectURL(new Blob([bytes], {type:"audio/mpeg"})); player.src = audioUrl; player.hidden = false;
        player.play().catch(() => { document.getElementById("voice-audio-notice").textContent = "Press Play to hear the spoken answer."; });
      }
      try {
        const history = await fetch(panel.dataset.historyUrl);
        if (history.ok) {
          const parsed = new DOMParser().parseFromString(await history.text(), "text/html");
          const conversation = parsed.getElementById("conversation");
          if (conversation) document.getElementById("conversation").replaceWith(conversation);
        }
      } catch (_) { document.getElementById("voice-audio-notice").textContent += " Refresh the session to see saved history."; }
    } catch (_) { showError("Could not reach the server. Refresh the session before retrying; a question may already have been saved."); }
    finally { busy = false; speak.disabled = !recordingSupported(); upload.disabled = false; status.textContent = "Ready. Refresh if the session changed in another tab."; }
  }
  const recordingSupported = () => !!(window.MediaRecorder && navigator.mediaDevices?.getUserMedia);
  async function press(event) {
    if (event.type === "keydown" && ![" ", "Enter"].includes(event.key)) return;
    if (event.type === "keydown" && event.repeat) return;
    if (event.type === "pointerdown" && event.button !== 0) return;
    event.preventDefault(); if (busy || holding || !recordingSupported()) return;
    document.dispatchEvent(new CustomEvent("bgm-audio-start", {detail:"microphone"}));
    holding = true; cancelled = false;
    try {
      const acquired = await navigator.mediaDevices.getUserMedia({audio:true});
      if (!holding) { acquired.getTracks().forEach(track => track.stop()); return; }
      stream = acquired;
      const type = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/mp4"].find(t => MediaRecorder.isTypeSupported(t));
      if (!type) throw new Error("unsupported recorder");
      recorder = new MediaRecorder(stream, {mimeType:type}); parts = []; size = 0;
      recorder.ondataavailable = event => { if (event.data.size) { size += event.data.size; parts.push(event.data); if (size > limit) { cancelled = true; release(); showError("Recording is too large. Ask a shorter question."); } } };
      recorder.onstop = () => { stopTracks(); if (!cancelled) send(new Blob(parts, {type}), "question." + (type.includes("ogg") ? "ogg" : type.includes("mp4") ? "mp4" : "webm")); recorder = null; };
      recorder.onerror = () => { cancelled = true; release(); showError("Recording failed. Try a file upload or type your question."); };
      recorder.start(250); speak.setAttribute("aria-pressed", "true"); status.textContent = "Recording… release to send.";
      timer = setTimeout(release, 60000);
    } catch (_) { holding = false; stopTracks(); showError("Microphone access failed. Allow access on localhost/HTTPS, upload audio, or type your question."); }
  }
  speak.addEventListener("pointerdown", press); speak.addEventListener("keydown", press);
  window.addEventListener("pointerup", release); speak.addEventListener("keyup", event => { if ([" ", "Enter"].includes(event.key)) { event.preventDefault(); release(); } });
  speak.addEventListener("pointercancel", () => { cancelled = true; release(); });
  window.addEventListener("blur", () => { cancelled = true; release(); });
  window.addEventListener("pagehide", () => { cancelled = true; release(); if (audioUrl) URL.revokeObjectURL(audioUrl); });
  upload.addEventListener("click", () => { const file = document.getElementById("audio-file").files[0]; if (file) send(file, file.name); else showError("Choose an audio recording first."); });
  if (!recordingSupported()) { speak.disabled = true; status.textContent = "Recording isn't supported here. Upload a short audio file or use text."; }
})();
