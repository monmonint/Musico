/* Musico — page d'écoute (navigateur de l'auditeur). Pas de contexte sécurisé requis :
 * pas de WebCodecs / AudioWorklet / Wake Lock ; décodage Opus en WebAssembly, lecture Web Audio
 * par blocs contigus produits par notre propre rééchantillonneur (voir Player dans musico-core.js). */
(function () {
  "use strict";
  var Core = window.MusicoCore;
  var $ = function (id) { return document.getElementById(id); };
  var listenBtn = $("listen"), statusEl = $("status"), warnEl = $("warn"), statsEl = $("stats");
  var offsetEl = $("offset"), offsetVal = $("offsetVal"), volumeEl = $("volume");
  var measuredEl = $("measured"), syncStatusEl = $("syncStatus");

  var cfg = { codec: "opus", sampleRate: 48000, channels: 2, latencyMs: 600, preSkipMs: 0, id: null };
  var clock = new Core.ClockSync();
  var ctxClock = new Core.CtxClock();
  var player = null;
  var ws = null, ctx = null, gain = null, decoder = null, decoderReady = false;
  var listening = false, reconnectDelay = 500, pingTimer = null, pingCount = 0;
  function num(v, d) { v = parseFloat(v); return isFinite(v) ? v : d; }
  var userOffsetMs = num(localStorage.getItem("musico.offset"), 0);
  var autoOffsetMs = num(localStorage.getItem("musico.auto"), 0);
  var probeMs = 0, measuredMs = null, syncStatus = "";
  var lastPacketAt = 0, received = 0, decodeErrors = 0, scheduled = 0, renderErrors = 0;

  offsetEl.value = userOffsetMs; showOffset();
  offsetEl.oninput = function () {
    var v = parseInt(offsetEl.value, 10) || 0;
    if (player && Math.abs(v - userOffsetMs) >= 20) player.forceJump = true; // réglage manuel : saut en fondu
    userOffsetMs = v; localStorage.setItem("musico.offset", v); showOffset();
  };
  $("offsetReset").onclick = function () { offsetEl.value = 0; offsetEl.oninput(); };
  volumeEl.oninput = function () { if (gain) gain.gain.value = volumeEl.value / 100; };
  function showOffset() { offsetVal.textContent = (userOffsetMs > 0 ? "+" : "") + userOffsetMs + " ms"; }
  function fmtMs(v) { return (v > 0 ? "+" : "") + v.toFixed(1).replace(".", ",") + " ms"; }

  // ---------- Décodeur ----------
  function makeDecoder() {
    if (decoder) { try { decoder.free(); } catch (e) {} }
    decoder = null; decoderReady = false;
    if (cfg.codec !== "opus") { decoderReady = true; updateButton(); return; }
    var Lib = window["opus-decoder"];
    if (!Lib) { setStatus("Décodeur Opus introuvable", true); return; }
    try {
      decoder = new Lib.OpusDecoder({ channels: cfg.channels, sampleRate: 48000, preSkip: 0 });
      decoder.ready.then(function () { decoderReady = true; updateButton(); },
        function (e) { setStatus("Impossible de charger le décodeur : " + e, true); });
    } catch (e) { setStatus("Décodeur indisponible : " + e, true); }
  }
  function makePlayer() {
    player = ctx ? new Core.Player({ outRate: ctx.sampleRate, inRate: 48000, channels: cfg.channels }) : null;
  }

  // ---------- WebSocket ----------
  function connect() {
    try {
      ws = new WebSocket("ws://" + location.host + "/ws");
    } catch (e) { setTimeout(connect, 2000); return; }
    ws.binaryType = "arraybuffer";
    ws.onopen = function () {
      reconnectDelay = 500; pingCount = 0;
      setStatus(listening ? "Reconnecté" : "Connecté à l'émetteur");
      schedulePing(0);
      if (listening) sendReady();
    };
    ws.onmessage = function (ev) {
      try {
        if (typeof ev.data === "string") onText(JSON.parse(ev.data));
        else onAudio(ev.data);
      } catch (e) { renderErrors++; }
    };
    ws.onclose = function () {
      clearTimeout(pingTimer);
      setStatus("Connexion perdue, nouvelle tentative…", true);
      setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 5000);
    };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }
  function send(o) { try { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); } catch (e) {} }
  function sendReady() { probeMs = 0; send({ t: "ready", manual: userOffsetMs, auto: autoOffsetMs, ua: navigator.userAgent.replace(/"/g, "").slice(0, 120) }); }
  function schedulePing(delay) {
    clearTimeout(pingTimer);
    pingTimer = setTimeout(function () {
      send({ t: "ping", c: performance.now() });
      pingCount++;
      schedulePing(pingCount < 10 ? 100 : 1000); // rafale au début, puis 1/s
    }, delay);
  }

  function onText(m) {
    if (m.t === "hello") {
      var formatChanged = m.codec !== cfg.codec || m.channels !== cfg.channels || !decoder;
      cfg.codec = m.codec; cfg.sampleRate = m.sampleRate; cfg.channels = m.channels;
      cfg.latencyMs = num(m.latencyMs, 600); cfg.preSkipMs = num(m.preSkipMs, 0);
      if (m.id !== undefined) cfg.id = m.id;
      if (formatChanged) { makeDecoder(); makePlayer(); }
    } else if (m.t === "config") {
      cfg.latencyMs = num(m.latencyMs, cfg.latencyMs);
      if (player) player.forceJump = true; // changement volontaire de latence : saut en fondu
    } else if (m.t === "pong") {
      clock.addSample(m.c, m.s, performance.now());
    } else if (m.t === "offset") {
      autoOffsetMs = num(m.auto, autoOffsetMs); probeMs = num(m.probe, 0);
      measuredMs = m.measured === null || m.measured === undefined ? null : num(m.measured, null);
      syncStatus = m.status || "";
      localStorage.setItem("musico.auto", autoOffsetMs);
      showSync();
    } else if (m.t === "status") {
      if (m.silent || !m.capturing) { warnEl.style.display = "block"; warnEl.textContent = "Émetteur : " + m.message; }
      else warnEl.style.display = "none";
    }
  }

  function showSync() {
    measuredEl.textContent = measuredMs === null ? "—" : fmtMs(measuredMs);
    measuredEl.style.color = measuredMs !== null && Math.abs(measuredMs) <= 3 ? "#7ee2a8" : "";
    syncStatusEl.textContent = (syncStatus ? syncStatus + " · " : "") + "correction auto " + fmtMs(autoOffsetMs);
  }

  // Heure (contexte audio) à laquelle doit sonner l'échantillon capturé à tsMs (horloge émetteur)
  function targetFn(tsMs) {
    var preSkip = cfg.codec === "opus" ? cfg.preSkipMs : 0;
    return ctxClock.toCtx(clock.toLocal(tsMs - preSkip + cfg.latencyMs + userOffsetMs + autoOffsetMs + probeMs));
  }

  function onAudio(buf) {
    if (!listening || !ctx || !player || !clock.ready() || !decoderReady) return;
    var p = Core.parsePacket(buf);
    if (!p) return;
    received++; lastPacketAt = performance.now();
    var channels, frames;
    try {
      if (p.codec === Core.CODEC_OPUS) {
        var r = decoder.decodeFrame(p.payload);
        channels = r.channelData; frames = r.samplesDecoded;
      } else {
        channels = Core.pcm16ToFloat(p.payload, cfg.channels); frames = channels[0].length;
      }
    } catch (e) { decodeErrors++; return; }
    if (!frames) return;
    player.push(p.seq, p.ts, channels, frames);
    pump();
  }

  // Produit et programme les blocs prêts (appelé à chaque paquet et par une minuterie)
  function pump() {
    if (!player || !ctx || ctx.state !== "running") return;
    ctxClock.update(ctx, performance.now());
    if (!ctxClock.stable) return; // contexte audio pas encore démarré / horloge pas encore calée
    var tn = performance.now();
    if (tn - debug.lastTrace > 200) { debug.lastTrace = tn; debug.trace.push([Math.round(tn), +clock.offset.toFixed(2), +(ctxClock.delta * 1000).toFixed(2)]); if (debug.trace.length > 600) debug.trace.shift(); }
    var blocks;
    try { blocks = player.render(ctx.currentTime, targetFn); } catch (e) { renderErrors++; return; }
    for (var b = 0; b < blocks.length; b++) schedule(blocks[b]);
  }
  setInterval(pump, 25);

  var lastEnd = null, gaps = 0;
  function schedule(block) {
    var n = block.data[0].length, when = block.frame / ctx.sampleRate;
    if (when < ctx.currentTime) return; // trop tard (ne devrait pas arriver)
    var ab = ctx.createBuffer(block.data.length, n, ctx.sampleRate);
    for (var c = 0; c < block.data.length; c++) {
      if (ab.copyToChannel) ab.copyToChannel(block.data[c], c); else ab.getChannelData(c).set(block.data[c]);
    }
    var src = ctx.createBufferSource();
    src.buffer = ab;
    src.connect(gain);
    src.start(when);
    scheduled++;
    if (lastEnd !== null && block.frame !== lastEnd) gaps++;
    lastEnd = block.frame + n;
    // Trace de débogage (tests automatiques) : instant absolu prévu de ce bloc
    debug.blocks.push([block.ts, performance.timeOrigin + (when - ctxClock.delta) * 1000]);
    if (debug.blocks.length > 2000) debug.blocks.splice(0, 1000);
  }

  // ---------- Déverrouillage audio (geste utilisateur obligatoire) ----------
  function silentWavUrl() {
    var sr = 8000, n = 800, b = new ArrayBuffer(44 + n * 2), v = new DataView(b);
    function s(o, t) { for (var i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); }
    s(0, "RIFF"); v.setUint32(4, 36 + n * 2, true); s(8, "WAVE"); s(12, "fmt ");
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    s(36, "data"); v.setUint32(40, n * 2, true);
    return URL.createObjectURL(new Blob([b], { type: "audio/wav" }));
  }
  var keepAliveAudio = null;
  function unlockAudio() {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!ctx) {
      try { ctx = new AC({ latencyHint: "playback" }); } catch (e) { ctx = new AC(); }
      gain = ctx.createGain(); gain.gain.value = volumeEl.value / 100; gain.connect(ctx.destination);
      makePlayer();
    }
    if (ctx.resume) ctx.resume();
    var b = ctx.createBuffer(1, 1, ctx.sampleRate), s = ctx.createBufferSource();
    s.buffer = b; s.connect(ctx.destination); s.start(0);
    // iOS : un élément <audio> qui joue fait passer la page en catégorie « lecture »
    // (le son Web Audio sort alors même avec le bouton silencieux activé).
    try { if (navigator.audioSession) navigator.audioSession.type = "playback"; } catch (e) {}
    try {
      if (!keepAliveAudio) {
        keepAliveAudio = document.createElement("audio");
        keepAliveAudio.src = silentWavUrl(); keepAliveAudio.loop = true;
        keepAliveAudio.setAttribute("playsinline", "");
      }
      var pr = keepAliveAudio.play(); if (pr && pr.catch) pr.catch(function () {});
    } catch (e) {}
  }

  listenBtn.onclick = function () {
    if (!listening) {
      unlockAudio();
      listening = true;
      if (player) player.reset();
      sendReady();
    } else {
      listening = false;
      send({ t: "pause" });
      if (ctx && ctx.suspend) ctx.suspend();
      if (keepAliveAudio) keepAliveAudio.pause();
      if (player) player.reset();
    }
    updateButton();
  };

  function updateButton() {
    if (!decoderReady) { listenBtn.disabled = true; listenBtn.textContent = "Chargement…"; return; }
    listenBtn.disabled = false;
    listenBtn.textContent = listening ? "⏸ Pause" : "▶ Écouter";
    listenBtn.className = listening ? "on" : "";
  }

  function setStatus(t, isWarn) { statusEl.textContent = t; statusEl.style.color = isWarn ? "#ffb072" : ""; }

  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible" && listening && ctx && ctx.state !== "running") ctx.resume();
  });

  // ---------- Statistiques ----------
  var lastStatsSent = 0;
  setInterval(function () {
    var now = performance.now();
    if (listening) {
      if (!ws || ws.readyState !== 1) { /* statut déjà affiché par onclose */ }
      else if (ctx && ctx.state !== "running") setStatus("Son suspendu par le navigateur : touche « Pause » puis « Écouter »", true);
      else if (now - lastPacketAt > 2000) setStatus(clock.ready() ? "En attente du son de l'émetteur…" : "Synchronisation de l'horloge…");
      else setStatus("🔊 À l'écoute, synchronisé");
    }
    var st = player ? player.stats : null;
    var ahead = player && ctx && player.outFrame !== null ? (player.outFrame / ctx.sampleRate - ctx.currentTime) * 1000 : 0;
    statsEl.textContent =
      "latence cible " + cfg.latencyMs + " ms · codec " + cfg.codec + (ctx ? " · " + ctx.sampleRate + " Hz" : "") + "\n" +
      "horloge : aller-retour " + (clock.rtt === null ? "?" : clock.rtt.toFixed(0)) + " ms\n" +
      "tampon " + ahead.toFixed(0) + " ms · reçus " + received +
      (st ? " · écart " + (st.err * 1000).toFixed(1) + " ms · vitesse " + ((st.rate - 1) * 100).toFixed(2) + " %\n" +
        "masqués " + st.concealed + " · creux " + st.underruns + " · sauts " + st.resyncs : "");
    if (ws && ws.readyState === 1 && now - lastStatsSent > 3000) {
      lastStatsSent = now;
      send({ t: "stats", manual: userOffsetMs, auto: autoOffsetMs, rtt: clock.rtt, ahead: Math.round(ahead),
        err: st ? +(st.err * 1000).toFixed(2) : null, underruns: st ? st.underruns : 0, resyncs: st ? st.resyncs : 0,
        concealed: st ? st.concealed : 0 });
    }
  }, 500);

  var debug = window.__musico = {
    blocks: [], trace: [], lastTrace: 0, cfg: cfg, clock: clock,
    get player() { return player; }, get received() { return received; }, get decodeErrors() { return decodeErrors; },
    get renderErrors() { return renderErrors; }, get gaps() { return gaps; }, get scheduled() { return scheduled; },
    get ctxState() { return ctx ? ctx.state : null; }, get offsets() { return { manual: userOffsetMs, auto: autoOffsetMs, probe: probeMs, measured: measuredMs }; },
    get ahead() { return player && ctx && player.outFrame !== null ? player.outFrame / ctx.sampleRate - ctx.currentTime : 0; }
  };

  showSync();
  updateButton();
  connect();
})();
