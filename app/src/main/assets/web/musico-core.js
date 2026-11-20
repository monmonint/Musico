/*
 * Musico — logique pure de la page d'écoute (sans DOM), testable sous Node.
 * - ClockSync : estimation du décalage d'horloge émetteur/navigateur (type NTP via WebSocket)
 * - CtxClock  : correspondance horloge performance.now() <-> temps AudioContext
 * - Scheduler : planification des blocs audio à l'heure cible avec rattrapage de dérive
 * - parsePacket : décodage de l'en-tête binaire des paquets audio
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.MusicoCore = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var CODEC_OPUS = 1, CODEC_PCM16 = 2, HEADER_SIZE = 14;

  function parsePacket(buf) {
    var dv = new DataView(buf);
    if (buf.byteLength < HEADER_SIZE || dv.getUint8(0) !== 1) return null;
    return {
      codec: dv.getUint8(1),
      seq: dv.getUint32(2, false),
      ts: dv.getFloat64(6, false),
      payload: new Uint8Array(buf, HEADER_SIZE)
    };
  }

  /** Convertit du PCM 16 bits LE entrelacé en canaux Float32. */
  function pcm16ToFloat(bytes, channels) {
    var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var frames = Math.floor(bytes.byteLength / (2 * channels));
    var out = [];
    for (var c = 0; c < channels; c++) out.push(new Float32Array(frames));
    for (var i = 0; i < frames; i++)
      for (var ch = 0; ch < channels; ch++)
        out[ch][i] = dv.getInt16((i * channels + ch) * 2, true) / 32768;
    return out;
  }

  /**
   * Synchro d'horloge : chaque ping envoie l'heure locale c, l'émetteur répond avec son heure s.
   * offset = s - (c + rtt/2) ; on garde les échantillons au plus faible RTT (moins de gigue).
   */
  function ClockSync(opts) {
    opts = opts || {};
    this.maxSamples = opts.maxSamples || 40;
    this.best = opts.best || 6;
    this.samples = [];
    this.offset = null; // ms : heureEmetteur = heureLocale + offset
    this.rtt = null;
  }
  ClockSync.prototype.addSample = function (c, s, now) {
    var rtt = now - c;
    if (!(rtt >= 0) || rtt > 5000) return;
    this.samples.push({ rtt: rtt, offset: s - (c + rtt / 2), at: now });
    if (this.samples.length > this.maxSamples) this.samples.shift();
    var sorted = this.samples.slice().sort(function (a, b) { return a.rtt - b.rtt; });
    var top = sorted.slice(0, Math.min(this.best, sorted.length));
    var offs = top.map(function (x) { return x.offset; }).sort(function (a, b) { return a - b; });
    var mid = Math.floor(offs.length / 2);
    var est = offs.length % 2 ? offs[mid] : (offs[mid - 1] + offs[mid]) / 2;
    if (this.offset === null || Math.abs(est - this.offset) > 30) this.offset = est;
    else this.offset += (est - this.offset) * 0.3; // lissage
    this.rtt = top[0].rtt;
  };
  ClockSync.prototype.ready = function () { return this.samples.length >= 3; };
  ClockSync.prototype.toLocal = function (senderMs) { return senderMs - this.offset; };

  /**
   * Correspondance performance.now() (ms) -> temps de l'AudioContext (s), au point d'écoute.
   * Utilise getOutputTimestamp() si dispo (inclut la latence de sortie), sinon currentTime
   * moins la latence annoncée. Le résultat est lissé pour éviter la gigue.
   */
  function CtxClock() { this.delta = null; this.valid = false; } // delta = ctxTime - perf/1000
  CtxClock.prototype.update = function (ctx, perfNow) {
    var d = null;
    // Au démarrage, currentTime peut rester figé un moment : on attend qu'il avance vraiment.
    this.valid = ctx.state === undefined || ctx.state === "running";
    if (typeof ctx.getOutputTimestamp === "function") {
      var ts = ctx.getOutputTimestamp();
      if (ts && ts.performanceTime > 0 && ts.contextTime > 0) d = ts.contextTime - ts.performanceTime / 1000;
    }
    if (d === null) {
      if (!(ctx.currentTime > 0.2)) this.valid = false;
      var lat = (ctx.outputLatency || 0) + (ctx.baseLatency || 0);
      d = ctx.currentTime - lat - perfNow / 1000;
    }
    if (this.delta === null || Math.abs(d - this.delta) > 0.05) this.delta = d;
    else this.delta += (d - this.delta) * 0.05;
    return this.delta;
  };
  CtxClock.prototype.toCtx = function (perfMs) { return perfMs / 1000 + this.delta; };

  /**
   * Planificateur : place chaque bloc juste après le précédent (lecture continue) tant que
   * l'écart avec l'heure cible reste faible ; corrige la dérive par une légère variation de
   * vitesse (±0,5 %), et se recale franchement si l'écart dépasse le seuil.
   */
  function Scheduler(opts) {
    opts = opts || {};
    this.resyncThreshold = opts.resyncThreshold || 0.04; // s
    this.deadband = opts.deadband || 0.002; // s
    this.maxRateDelta = opts.maxRateDelta || 0.005;
    this.minLead = opts.minLead || 0.01; // s minimum avant currentTime
    this.reset();
  }
  Scheduler.prototype.reset = function () {
    this.nextTime = null; this.lastSeq = null;
    this.late = 0; this.lost = 0; this.resyncs = 0;
  };
  /**
   * @param target  temps ctx (s) où le bloc devrait sonner
   * @param duration durée du bloc (s)
   * @param now     ctx.currentTime
   * @returns null si le bloc est trop en retard (jeté), sinon {startAt, rate, error}
   */
  Scheduler.prototype.plan = function (seq, target, duration, now) {
    var gap = this.lastSeq !== null && seq !== ((this.lastSeq + 1) >>> 0);
    if (gap && seq > this.lastSeq) this.lost += seq - this.lastSeq - 1;
    this.lastSeq = seq;
    if (target + duration < now + this.minLead) { this.late++; this.nextTime = null; return null; }
    var startAt, rate = 1, error = 0;
    if (this.nextTime === null || gap) {
      startAt = target; this.resyncs++;
    } else {
      error = this.nextTime - target; // >0 : on joue en retard
      if (Math.abs(error) > this.resyncThreshold) { startAt = target; this.resyncs++; error = 0; }
      else {
        startAt = this.nextTime;
        if (Math.abs(error) > this.deadband)
          rate = 1 + Math.max(-this.maxRateDelta, Math.min(this.maxRateDelta, error));
      }
    }
    var offsetInBuffer = 0;
    if (startAt < now + this.minLead) { // début déjà passé : on coupe le début du bloc
      offsetInBuffer = now + this.minLead - startAt;
      startAt = now + this.minLead;
    }
    this.nextTime = startAt + (duration - offsetInBuffer) / rate;
    return { startAt: startAt, rate: rate, error: error, offset: offsetInBuffer };
  };

  return {
    CODEC_OPUS: CODEC_OPUS, CODEC_PCM16: CODEC_PCM16, HEADER_SIZE: HEADER_SIZE,
    parsePacket: parsePacket, pcm16ToFloat: pcm16ToFloat,
    ClockSync: ClockSync, CtxClock: CtxClock, Scheduler: Scheduler
  };
});
