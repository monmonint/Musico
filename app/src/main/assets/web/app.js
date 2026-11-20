/* Musico — page d'écoute (navigateur de l'auditeur). Pas de contexte sécurisé requis :
 * pas de WebCodecs / AudioWorklet / Wake Lock ; décodage Opus en WebAssembly, lecture Web Audio. */
(function () {
  "use strict";
  var Core = window.MusicoCore;
  var $ = function (id) { return document.getElementById(id); };
  var listenBtn = $("listen"), statusEl = $("status"), warnEl = $("warn"), statsEl = $("stats");
  var offsetEl = $("offset"), offsetVal = $("offsetVal"), volumeEl = $("volume");

  var cfg = { codec: "opus", sampleRate: 48000, channels: 2, latencyMs: 300, preSkipMs: 0 };
  var clock = new Core.ClockSync();
  var ctxClock = new Core.CtxClock();
  var sched = new Core.Scheduler();
  var ws = null, ctx = null, gain = null, decoder = null, decoderReady = false;
  var listening = false, reconnectDelay = 500, pingTimer = null, pingCount = 0;
  var userOffsetMs = parseInt(localStorage.getItem("musico.offset") || "0", 10) || 0;
  var lastPacketAt = 0, received = 0, decodeErrors = 0, senderStatus = null;

  offsetEl.value = userOffsetMs; showOffset();
  offsetEl.oninput = function () { userOffsetMs = parseInt(offsetEl.value, 10); localStorage.setItem("musico.offset", userOffsetMs); showOffset(); sched.reset(); };
  $("offsetReset").onclick = function () { offsetEl.value = 0; offsetEl.oninput(); };
  volumeEl.oninput = function () { if (gain) gain.gain.value = volumeEl.value / 100; };
  function showOffset() { offsetVal.textContent = (userOffsetMs > 0 ? "+" : "") + userOffsetMs + " ms"; }

  // ---------- Décodeur ----------
  function makeDecoder() {
    if (decoder) { try { decoder.free(); } catch (e) {} }
    decoder = null; decoderReady = false;
    if (cfg.codec !== "opus") { decoderReady = true; updateButton(); return; }
    var Lib = window["opus-decoder"];
    if (!Lib) { setStatus("Décodeur Opus introuvable", true); return; }
    decoder = new Lib.OpusDecoder({ channels: cfg.channels, sampleRate: 48000, preSkip: 0 });
    decoder.ready.then(function () { decoderReady = true; updateButton(); },
      function (e) { setStatus("Impossible de charger le décodeur : " + e, true); });
  }

  // ---------- WebSocket ----------
  function connect() {
    ws = new WebSocket("ws://" + location.host + "/ws");
    ws.binaryType = "arraybuffer";
    ws.onopen = function () {
      reconnectDelay = 500; pingCount = 0;
      setStatus(listening ? "Reconnecté" : "Connecté à l'émetteur");
      schedulePing(0);
      if (listening) send({ t: "ready" });
    };
    ws.onmessage = function (ev) {
      if (typeof ev.data === "string") onText(JSON.parse(ev.data));
      else onAudio(ev.data);
    };
    ws.onclose = function () {
      clearTimeout(pingTimer);
      setStatus("Connexion perdue, nouvelle tentative…", true);
      sched.reset();
      setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 5000);
    };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }
  function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
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
      cfg.latencyMs = m.latencyMs; cfg.preSkipMs = m.preSkipMs || 0;
      if (formatChanged) makeDecoder();
      sched.reset();
    } else if (m.t === "config") {
      cfg.latencyMs = m.latencyMs; sched.reset();
    } else if (m.t === "pong") {
      clock.addSample(m.c, m.s, performance.now());
    } else if (m.t === "status") {
      senderStatus = m;
      if (m.silent || !m.capturing) { warnEl.style.display = "block"; warnEl.textContent = "Émetteur : " + m.message; }
      else warnEl.style.display = "none";
    }
  }

  function onAudio(buf) {
    if (!listening || !ctx || !clock.ready() || !decoderReady) return;
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
    var duration = frames / 48000;
    // Heure (horloge émetteur) à laquelle ce bloc doit sonner, puis conversion locale
    var senderPlayMs = p.ts - (p.codec === Core.CODEC_OPUS ? cfg.preSkipMs : 0) + cfg.latencyMs + userOffsetMs;
    var nowPerf = performance.now();
    ctxClock.update(ctx, nowPerf);
    if (!ctxClock.valid) return; // contexte audio pas encore vraiment démarré
    var target = ctxClock.toCtx(clock.toLocal(senderPlayMs));
    var plan = sched.plan(p.seq, target, duration, ctx.currentTime);
    if (!plan) return;
    var ab = ctx.createBuffer(channels.length, frames, 48000);
    for (var c = 0; c < channels.length; c++) {
      if (ab.copyToChannel) ab.copyToChannel(channels[c], c); else ab.getChannelData(c).set(channels[c]);
    }
    var src = ctx.createBufferSource();
    src.buffer = ab;
    src.playbackRate.value = plan.rate;
    src.connect(gain);
    src.start(plan.startAt, plan.offset);
    // Trace de débogage (tests automatiques) : instant absolu prévu pour chaque paquet
    debug.planned[p.seq % 1000] = { seq: p.seq, abs: performance.timeOrigin + (plan.startAt - ctxClock.delta) * 1000 };
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
      try { ctx = new AC({ latencyHint: "playback", sampleRate: 48000 }); }
      catch (e) { ctx = new AC(); }
      gain = ctx.createGain(); gain.gain.value = volumeEl.value / 100; gain.connect(ctx.destination);
    }
    if (ctx.resume) ctx.resume();
    var b = ctx.createBuffer(1, 1, 22050), s = ctx.createBufferSource();
    s.buffer = b; s.connect(ctx.destination); s.start(0);
    // iOS : un élément <audio> qui joue fait passer la page en catégorie « lecture »
    // (le son Web Audio sort alors même avec le bouton silencieux activé).
    try { if (navigator.audioSession) navigator.audioSession.type = "playback"; } catch (e) {}
    if (!keepAliveAudio) {
      keepAliveAudio = document.createElement("audio");
      keepAliveAudio.src = silentWavUrl(); keepAliveAudio.loop = true;
      keepAliveAudio.setAttribute("playsinline", "");
    }
    var pr = keepAliveAudio.play(); if (pr && pr.catch) pr.catch(function () {});
  }

  listenBtn.onclick = function () {
    if (!listening) {
      unlockAudio();
      listening = true; sched.reset();
      send({ t: "ready" });
    } else {
      listening = false;
      send({ t: "pause" });
      if (ctx && ctx.suspend) ctx.suspend();
      if (keepAliveAudio) keepAliveAudio.pause();
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
    if (document.visibilityState === "visible" && listening) {
      if (ctx && ctx.state !== "running") ctx.resume();
      sched.reset();
    }
  });

  // ---------- Statistiques ----------
  var lastStatsSent = 0;
  setInterval(function () {
    if (!ws || ws.readyState !== 1) return;
    var now = performance.now();
    if (listening) {
      if (ctx && ctx.state !== "running") {
        setStatus("Son suspendu par le navigateur : touche « Pause » puis « Écouter »", true);
      } else if (now - lastPacketAt > 2000) {
        setStatus(clock.ready() ? "En attente du son de l'émetteur…" : "Synchronisation de l'horloge…");
      } else setStatus("🔊 À l'écoute, synchronisé");
    }
    var ahead = ctx && sched.nextTime !== null ? (sched.nextTime - ctx.currentTime) * 1000 : 0;
    statsEl.textContent =
      "latence cible " + cfg.latencyMs + " ms · codec " + cfg.codec + "\n" +
      "horloge: écart " + (clock.offset === null ? "?" : clock.offset.toFixed(1)) + " ms, aller-retour " + (clock.rtt === null ? "?" : clock.rtt.toFixed(0)) + " ms\n" +
      "tampon " + ahead.toFixed(0) + " ms · reçus " + received + " · en retard " + sched.late + " · perdus " + sched.lost + " · recalages " + sched.resyncs +
      (ctx ? "\nsortie: " + ((ctx.outputLatency || 0) * 1000).toFixed(0) + " ms (+" + ((ctx.baseLatency || 0) * 1000).toFixed(0) + ")" : "");
    if (now - lastStatsSent > 3000) {
      lastStatsSent = now;
      send({ t: "stats", rtt: clock.rtt, ahead: Math.round(ahead), late: sched.late, lost: sched.lost, ua: navigator.userAgent.slice(0, 80) });
    }
  }, 500);

  var debug = window.__musico = {
    planned: {}, cfg: cfg, clock: clock, sched: sched,
    get received() { return received; }, get decodeErrors() { return decodeErrors; },
    get ctxState() { return ctx ? ctx.state : null; },
    get ahead() { return ctx && sched.nextTime !== null ? sched.nextTime - ctx.currentTime : 0; }
  };

  updateButton();
  connect();
})();
