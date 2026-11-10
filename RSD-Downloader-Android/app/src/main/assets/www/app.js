/* RSD Downloader — Android UI logic.
   Native side (Kotlin) talks to this page through window.Android (calls out)
   and window.RSD (events in). */
(function () {
  "use strict";

  const $ = id => document.getElementById(id);
  const urlEl = $("url-input"), fmtEl = $("format-sel");
  const dlBtn = $("download-btn"), cnlBtn = $("cancel-btn");
  const progW = $("progress-wrap"), barEl = $("bar"), pctEl = $("pct"), etaEl = $("eta");
  const statEl = $("status"), dotEl = $("dot"), logEl = $("log");
  const durationEl = $("duration-info");
  const fullVideoBtn = $("full-video-btn"), selectedAreaBtn = $("selected-area-btn");
  const timeFieldsEl = $("time-fields"), startTimeEl = $("start-time"), endTimeEl = $("end-time");
  const actionsEl = $("done-actions"), openBtn = $("open-btn"), shareBtn = $("share-btn");
  const updateBtn = $("update-btn"), compEl = $("comp-status"), autoPasteEl = $("auto-paste");

  const native = window.Android || null;
  function call(name) {
    const args = Array.prototype.slice.call(arguments, 1);
    if (!native || typeof native[name] !== "function") return;
    try { return native[name].apply(native, args); }
    catch (e) { log("[bridge] " + e.message + "\n", "e"); }
  }

  const store = {
    get(key, fallback) { try { const v = localStorage.getItem("rsd." + key); return v === null ? fallback : v; } catch (e) { return fallback; } },
    set(key, value) { try { localStorage.setItem("rsd." + key, value); } catch (e) {} }
  };

  let busy = false;
  let downloadMode = "full";
  let totalDurationSeconds = null;
  let metadataDebounce = null;
  let metadataRequestId = 0;
  let lastAutoLink = "";
  let lastDone = null;
  let pasteIsAuto = false;

  /* ---------- small UI helpers ---------- */

  function setStatus(msg, type) {
    statEl.textContent = msg;
    statEl.className = type || "";
    dotEl.className = "dot " + (type || "");
  }

  function setBar(v) {
    const n = Math.max(0, Math.min(100, Number(v) || 0));
    barEl.style.width = n + "%";
    pctEl.textContent = Math.round(n) + "%";
  }

  function log(txt, cls) {
    if (cls) {
      const s = document.createElement("span");
      s.className = cls; s.textContent = txt;
      logEl.appendChild(s);
    } else {
      logEl.insertAdjacentText("beforeend", txt);
    }
    // keep the log from growing without bound
    while (logEl.childNodes.length > 400) logEl.removeChild(logEl.firstChild);
    logEl.scrollTop = logEl.scrollHeight;
  }

  function setBusy(b) {
    busy = !!b;
    dlBtn.disabled = busy;
    cnlBtn.style.display = busy ? "block" : "none";
    if (busy) {
      progW.style.display = "flex";
      actionsEl.style.display = "none";
      lastDone = null;
    }
  }

  /* ---------- time range (same rules as the desktop app) ---------- */

  function parseTimecode(value) {
    const parts = String(value || "").trim().split(":");
    if (parts.length < 1 || parts.length > 3 || parts.some(p => !/^\d+$/.test(p))) return null;
    const n = parts.map(Number);
    if (parts.length === 1) return n[0];
    const seconds = n[n.length - 1];
    const minutes = n.length > 1 ? n[n.length - 2] : 0;
    if (seconds > 59 || (parts.length === 3 && minutes > 59)) return null;
    if (parts.length === 2) return minutes * 60 + seconds;
    return n[0] * 3600 + minutes * 60 + seconds;
  }

  function formatTimecode(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) + 0.5));
    return [Math.floor(total / 3600), Math.floor((total % 3600) / 60), total % 60]
      .map(v => String(v).padStart(2, "0")).join(":");
  }

  function formatDurationLabel(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) + 0.5));
    if (Math.floor(total / 3600)) return formatTimecode(total);
    return [Math.floor(total / 60), total % 60].map(v => String(v).padStart(2, "0")).join(":");
  }

  function selectedTimeRange() {
    if (totalDurationSeconds === null) { setStatus("Wait for the video duration to load", "warn"); return null; }
    const startSeconds = parseTimecode(startTimeEl.value);
    const endSeconds = parseTimecode(endTimeEl.value);
    if (startSeconds === null || endSeconds === null) { setStatus("Enter start and end as HH:MM:SS or MM:SS", "warn"); return null; }
    if (endSeconds <= startSeconds) { setStatus("End time must be after start time", "warn"); return null; }
    if (endSeconds > totalDurationSeconds + 1) { setStatus("End time exceeds the video duration", "warn"); return null; }
    return { startSeconds, endSeconds };
  }

  function setDownloadMode(mode) {
    downloadMode = mode === "selected" ? "selected" : "full";
    const selected = downloadMode === "selected";
    fullVideoBtn.classList.toggle("active", !selected);
    selectedAreaBtn.classList.toggle("active", selected);
    fullVideoBtn.setAttribute("aria-pressed", String(!selected));
    selectedAreaBtn.setAttribute("aria-pressed", String(selected));
    timeFieldsEl.classList.toggle("visible", selected);
    timeFieldsEl.setAttribute("aria-hidden", String(!selected));
  }

  /* ---------- link field ---------- */

  function firstUrl(text) {
    const m = String(text || "").match(/https?:\/\/[^\s<>"']+/i);
    return m ? m[0] : "";
  }

  function onVideoUrlChanged() {
    if (metadataDebounce) clearTimeout(metadataDebounce);
    metadataDebounce = null;
    metadataRequestId++;
    totalDurationSeconds = null;
    startTimeEl.value = "00:00:00";
    endTimeEl.value = "";

    const url = urlEl.value.trim();
    if (!url) { durationEl.textContent = "Total duration: paste a video link"; return; }
    if (!/^https?:\/\/\S+/i.test(url)) { durationEl.textContent = "Total duration: enter a valid video link"; return; }

    durationEl.textContent = "Total duration: checking…";
    const id = metadataRequestId;
    metadataDebounce = setTimeout(() => { metadataDebounce = null; call("lookupDuration", url, id); }, 650);
  }

  function pasteClipboardLink(auto) {
    pasteIsAuto = !!auto;
    call("requestClipboard");
  }

  /* ---------- events coming from Kotlin ---------- */

  function applyComponents(e) {
    compEl.textContent = "";
    const part = (label, ok, title) => {
      const s = document.createElement("span");
      s.className = ok ? "ok" : "bad";
      s.textContent = label + (ok ? " ✓" : " ✗");
      if (title) s.title = title;
      return s;
    };
    let parts;
    if (!e.ready && !e.error) {
      compEl.textContent = "Preparing components… (first launch takes a moment)";
      return;
    }
    parts = [
      part(e.ytdlp ? "yt-dlp " + e.ytdlp : "yt-dlp", !!e.ytdlp, e.error || ""),
      part("ffmpeg", !!e.ffmpeg, e.error || "")
    ];
    parts.forEach((p, i) => { if (i) compEl.appendChild(document.createTextNode(" · ")); compEl.appendChild(p); });
  }

  function showDone(d) {
    lastDone = d;
    setBar(100);
    etaEl.textContent = "";
    progW.style.display = "flex";
    if (d && d.uri) actionsEl.style.display = "flex";
  }

  function onEvent(json) {
    let e;
    try { e = typeof json === "string" ? JSON.parse(json) : json; } catch (err) { return; }
    switch (e.type) {
      case "state":
        if (e.busy) { logEl.textContent = ""; setBar(0); etaEl.textContent = ""; }
        setBusy(e.busy);
        break;
      case "status": setStatus(e.text, e.kind); break;
      case "progress": setBar(e.pct); etaEl.textContent = e.eta ? "ETA " + e.eta : ""; break;
      case "log": log(e.text); break;
      case "done": showDone(e); log("\n✓ Saved: " + e.display + "\n", "g"); break;
      case "duration":
        if (e.id !== metadataRequestId) break;
        if (typeof e.seconds === "number" && e.seconds > 0) {
          totalDurationSeconds = e.seconds;
          durationEl.textContent = "Total duration: " + formatDurationLabel(e.seconds);
          endTimeEl.value = formatTimecode(Math.ceil(e.seconds));
        } else {
          durationEl.textContent = "Total duration unavailable for this link";
        }
        break;
      case "components": applyComponents(e); break;
      case "updating":
        updateBtn.classList.toggle("disabled", !!e.on);
        updateBtn.textContent = e.on ? "UPDATING…" : "UPDATE YT-DLP";
        break;
      case "snapshot":
        setBusy(e.busy);
        if (e.status) setStatus(e.status.text, e.status.kind);
        if (e.busy) { progW.style.display = "flex"; setBar(e.pct || 0); etaEl.textContent = e.eta ? "ETA " + e.eta : ""; }
        if (e.done) showDone(e.done);
        break;
    }
  }

  /* ---------- user actions ---------- */

  dlBtn.onclick = () => {
    const url = urlEl.value.trim();
    if (!url) { setStatus("Paste a link first", "warn"); return; }
    if (!/^https?:\/\/\S+/i.test(url)) { setStatus("That doesn't look like a web link", "warn"); return; }

    let start = -1, end = -1;
    if (downloadMode === "selected") {
      const range = selectedTimeRange();
      if (!range) return;
      start = range.startSeconds; end = range.endSeconds;
    }
    call("startDownload", url, fmtEl.value, start, end);
  };

  cnlBtn.onclick = () => { call("cancelDownload"); setStatus("Cancelling…", "warn"); };
  $("log-clear").onclick = () => { logEl.textContent = ""; };
  $("paste-link-btn").onclick = () => pasteClipboardLink(false);
  openBtn.onclick = () => { if (lastDone && lastDone.uri) call("openFile", lastDone.uri); };
  shareBtn.onclick = () => { if (lastDone && lastDone.uri) call("shareFile", lastDone.uri); };
  updateBtn.onclick = () => { if (!busy) call("updateYtDlp"); };

  fullVideoBtn.onclick = () => setDownloadMode("full");
  selectedAreaBtn.onclick = () => setDownloadMode("selected");
  urlEl.addEventListener("input", onVideoUrlChanged);
  urlEl.addEventListener("keydown", e => {
    if (e.key === "Enter" && !dlBtn.disabled) { e.preventDefault(); urlEl.blur(); dlBtn.click(); }
  });

  fmtEl.value = store.get("quality", fmtEl.value);
  fmtEl.addEventListener("change", () => store.set("quality", fmtEl.value));
  autoPasteEl.checked = store.get("autoPaste", "1") === "1";
  autoPasteEl.addEventListener("change", () => store.set("autoPaste", autoPasteEl.checked ? "1" : "0"));

  /* ---------- API used by Kotlin ---------- */

  window.RSD = {
    onEvent,

    // Clipboard text arrives here after Android.requestClipboard()
    onClipboard(text) {
      const link = firstUrl(text);
      if (pasteIsAuto) {
        if (!link || link === lastAutoLink) return;
        lastAutoLink = link;
      }
      const value = link || String(text || "").split(/\r?\n/).map(l => l.trim()).find(Boolean) || "";
      if (!value) { setStatus("Clipboard has no text link", "warn"); return; }
      urlEl.value = value;
      onVideoUrlChanged();
      setStatus("Link pasted from clipboard", "ok");
    },

    // "Share" from another app (YouTube, Instagram, Facebook, X, Pinterest…)
    onShared(text) {
      const link = firstUrl(text);
      if (!link) { setStatus("The shared text has no link", "warn"); return; }
      lastAutoLink = link;
      urlEl.value = link;
      onVideoUrlChanged();
      setStatus("Link received from Share — tap Download", "ok");
    },

    // The app came to the foreground: grab a copied link if the field is empty.
    onAppFocus() {
      if (autoPasteEl.checked && !busy && !urlEl.value.trim()) pasteClipboardLink(true);
    }
  };

  setDownloadMode("full");
  call("refreshComponents");
  call("getSnapshot");
})();
