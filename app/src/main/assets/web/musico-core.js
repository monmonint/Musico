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
    this.maxSlewMs = opts.maxSlewMs || 0.5; // correction max par mesure (pas de saut)
    this.stepThreshold = opts.stepThreshold || 50; // au-delà : vrai changement (ex. émetteur relancé)
  }
  ClockSync.prototype.addSample = function (c, s, now) {
    var rtt = now - c;
    if (!(rtt >= 0) || rtt > 5000 || !isFinite(s)) return;
    // Rejette les mesures aberrantes (aller-retour bien pire que d'habitude)
    if (this.samples.length >= 8) {
      var rtts = this.samples.map(function (x) { return x.rtt; }).sort(function (a, b) { return a - b; });
      var med = rtts[Math.floor(rtts.length / 2)];
      if (rtt > 3 * med + 20) return;
    }
    this.samples.push({ rtt: rtt, offset: s - (c + rtt / 2), at: now });
    if (this.samples.length > this.maxSamples) this.samples.shift();
    var sorted = this.samples.slice().sort(function (a, b) { return a.rtt - b.rtt; });
    var top = sorted.slice(0, Math.min(this.best, sorted.length));
    var offs = top.map(function (x) { return x.offset; }).sort(function (a, b) { return a - b; });
    var mid = Math.floor(offs.length / 2);
    var est = offs.length % 2 ? offs[mid] : (offs[mid - 1] + offs[mid]) / 2;
    if (this.offset === null || this.samples.length <= 3 || Math.abs(est - this.offset) > this.stepThreshold) this.offset = est;
    else { // glissement borné : jamais de saut d'horloge
      var d = (est - this.offset) * 0.3;
      this.offset += Math.max(-this.maxSlewMs, Math.min(this.maxSlewMs, d));
    }
    this.rtt = top[0].rtt;
  };
  ClockSync.prototype.ready = function () { return this.samples.length >= 3; };
  ClockSync.prototype.toLocal = function (senderMs) { return senderMs - this.offset; };

  /**
   * Correspondance performance.now() (ms) -> temps de l'AudioContext (s), au point d'écoute.
   * Utilise getOutputTimestamp() si dispo (inclut la latence de sortie), sinon currentTime
   * moins la latence annoncée. Le résultat est lissé pour éviter la gigue.
   */
  function CtxClock() { this.delta = null; this.valid = false; this.source = null; this.n = 0; } // delta = ctxTime - perf/1000
  CtxClock.prototype.update = function (ctx, perfNow) {
    var d = null, src = "ts";
    // Au démarrage, currentTime peut rester figé un moment : on attend qu'il avance vraiment.
    this.valid = ctx.state === undefined || ctx.state === "running";
    if (typeof ctx.getOutputTimestamp === "function") {
      var ts = ctx.getOutputTimestamp();
      if (ts && ts.performanceTime > 0 && ts.contextTime > 0) d = ts.contextTime - ts.performanceTime / 1000;
    }
    if (d === null) {
      src = "fallback";
      if (!(ctx.currentTime > 0.2)) this.valid = false;
      var lat = (ctx.outputLatency || 0) + (ctx.baseLatency || 0);
      d = ctx.currentTime - lat - perfNow / 1000;
    }
    if (src !== this.source) { this.source = src; this.delta = null; this.n = 0; } // changement de méthode : on repart
    if (this.delta === null || Math.abs(d - this.delta) > 0.05) { this.delta = d; this.n = 1; }
    else {
      this.n++;
      // moyenne exacte au début (convergence rapide), puis lissage lent
      this.delta += (d - this.delta) * Math.max(0.05, 1 / this.n);
    }
    // stable : assez de mesures pour démarrer la lecture sans dérive initiale
    this.stable = this.valid && this.n >= 20;
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


  /**
   * Lecteur continu : tampon d'entrée (48 kHz) + rééchantillonnage maison (Catmull-Rom) vers la
   * fréquence de l'AudioContext, avec une vitesse légèrement variable (±0,5 %, ±1 % pour
   * rattraper) pour suivre l'heure cible. Les blocs de sortie sont contigus à l'échantillon près
   * (vitesse de lecture Web Audio toujours 1) : pas de trous ni de chevauchements.
   * - paquet manquant : masquage (répétition atténuée du bloc précédent, en fondu)
   * - paquet en retard / doublon : ignoré
   * - tampon vide : on reprend dès que des données arrivent, puis on rattrape par la vitesse
   * - gros écart (> resyncSec) ou changement manuel : saut avec fondu sortant/entrant
   */
  function Player(opts) {
    opts = opts || {};
    this.outRate = opts.outRate || 48000;
    this.inRate = opts.inRate || 48000;
    this.channels = opts.channels || 2;
    this.blockOut = opts.blockOut || 1024;
    this.kp = opts.kp || 2.0;              // 1/s : constante de temps 0,5 s
    this.maxRate = opts.maxRate || 0.005;  // ±0,5 % (≈ 9 cents, inaudible)
    this.catchRate = opts.catchRate || 0.01;
    this.catchThreshold = opts.catchThreshold || 0.03;
    this.resyncSec = opts.resyncSec || 0.15;
    this.minLead = opts.minLead || 0.03;
    this.flushLead = opts.flushLead || 0.12;
    this.fadeFrames = Math.round(this.outRate * 0.005);
    this.cap = this.inRate * 6;
    this.ring = [];
    for (var c = 0; c < this.channels; c++) this.ring.push(new Float32Array(this.cap));
    this.stats = { underruns: 0, resyncs: 0, concealed: 0, late: 0, rebases: 0, rate: 1, err: 0 };
    this.reset();
  }
  Player.prototype.reset = function () {
    this.writeIdx = 0; this.readPos = 0; this.baseTs = null; this.lastSeq = null;
    this.outFrame = null; this.lastBlock = null; this.fadeIn = true; this.pending = null;
    this.forceJump = false;
  };
  Player.prototype.tsAt = function (pos) { return this.baseTs + pos * 1000 / this.inRate; };
  Player.prototype.buffered = function () { return this.writeIdx - this.readPos; };
  Player.prototype._write = function (data, frames, gain) {
    for (var c = 0; c < this.channels; c++) {
      var src = data[Math.min(c, data.length - 1)], dst = this.ring[c];
      for (var i = 0; i < frames; i++) dst[(this.writeIdx + i) % this.cap] = src[i] * (gain ? gain(i) : 1);
    }
    this.writeIdx += frames;
    if (this.writeIdx - this.readPos > this.cap - 4) this.readPos = this.writeIdx - this.cap + 4;
  };
  /** Ajoute un bloc décodé (data = canaux Float32 à inRate, ts = horodatage émetteur en ms). */
  Player.prototype.push = function (seq, ts, data, frames) {
    if (this.baseTs === null) {
      this.baseTs = ts; this.writeIdx = 0; this.readPos = 0; this.lastSeq = (seq - 1) >>> 0;
    }
    var diff = (seq - this.lastSeq) | 0;
    var expectedTs = this.tsAt(this.writeIdx + (diff - 1) * frames);
    if (diff <= 0 && diff > -50 && Math.abs(ts - expectedTs) <= 40) { this.stats.late++; return; } // doublon / en retard
    if (diff > 50 || diff <= 0 || Math.abs(ts - expectedTs) > 40) {
      // Discontinuité du flux (émetteur relancé, longue coupure) : on repart de zéro
      this.stats.rebases++; this.reset(); this.push(seq, ts, data, frames); return;
    }
    if (diff > 1 && this.lastBlock) {
      // Masquage des paquets perdus : répétition du dernier bloc, de plus en plus atténuée
      var lb = this.lastBlock, n = lb[0].length;
      for (var k = 0; k < diff - 1; k++) {
        var g0 = Math.pow(0.5, k), g1 = Math.pow(0.5, k + 1);
        this._write(lb, n, function (i) { return g0 + (g1 - g0) * i / n; });
        this.stats.concealed++;
      }
    } else if (diff > 1) {
      var z = []; for (var c = 0; c < this.channels; c++) z.push(new Float32Array(frames * (diff - 1)));
      this._write(z, frames * (diff - 1));
    }
    this._write(data, frames);
    this.lastSeq = seq; this.lastBlock = data;
  };
  Player.prototype._sample = function (ring, pos) {
    var i = Math.floor(pos), t = pos - i, cap = this.cap;
    var y0 = ring[(i - 1 + cap) % cap], y1 = ring[i % cap], y2 = ring[(i + 1) % cap], y3 = ring[(i + 2) % cap];
    // Catmull-Rom
    return y1 + 0.5 * t * (y2 - y0 + t * (2 * y0 - 5 * y1 + 4 * y2 - y3 + t * (3 * (y1 - y2) + y3 - y0)));
  };
  Player.prototype._fade = function (block, fadeIn) {
    for (var c = 0; c < block.data.length; c++) {
      var d = block.data[c], n = Math.min(this.fadeFrames, d.length);
      if (fadeIn) for (var i = 0; i < n; i++) d[i] *= i / n;
      else for (var j = 0; j < n; j++) d[d.length - n + j] *= 1 - (j + 1) / n;
    }
  };
  Player.prototype._emitPending = function (out, fadeOut) {
    if (!this.pending) return;
    if (fadeOut) this._fade(this.pending, false);
    out.push(this.pending); this.pending = null;
  };
  /**
   * Produit les blocs à programmer. targetFn(tsMs) -> temps ctx (s) où l'échantillon émis à tsMs
   * doit sonner. Retourne [{frame, data}] : à jouer à frame / outRate, vitesse 1.
   */
  Player.prototype.render = function (now, targetFn) {
    var out = [];
    if (this.baseTs === null) return out;
    var step0 = this.inRate / this.outRate;
    var need = this.blockOut * step0 * (1 + this.catchRate) + 4;
    if (this.outFrame === null) {
      if (this.buffered() < need) return out;
      var tgt = targetFn(this.tsAt(this.readPos));
      if (tgt < now + this.minLead) {
        this.readPos += (now + this.minLead - tgt) * this.inRate;
        if (this.readPos > this.writeIdx) this.readPos = this.writeIdx;
        tgt = now + this.minLead;
      }
      this.outFrame = Math.round(tgt * this.outRate); this.fadeIn = true;
    }
    if (this.outFrame / this.outRate < now + this.minLead && !this.pending && this.buffered() >= need) {
      // Le tampon s'est vidé et le temps a passé : reprise (puis rattrapage par la vitesse)
      this.outFrame = Math.ceil((now + this.minLead) * this.outRate);
      this.fadeIn = true; this.stats.underruns++;
    }
    var guard = 0;
    while (this.buffered() >= need && guard++ < 200) {
      var tOut = this.outFrame / this.outRate;
      var err = tOut - targetFn(this.tsAt(this.readPos)); // > 0 : en retard
      this.stats.err = err;
      if (Math.abs(err) > this.resyncSec || (this.forceJump && Math.abs(err) > 0.003)) {
        this.forceJump = false; this.stats.resyncs++;
        this._emitPending(out, true);
        if (err > 0) { this.readPos = Math.min(this.readPos + err * this.inRate, this.writeIdx); }
        else { this.outFrame += Math.round(-err * this.outRate); }
        this.fadeIn = true;
        continue;
      }
      var lim = Math.abs(err) > this.catchThreshold ? this.catchRate : this.maxRate;
      var rate = 1 + Math.max(-lim, Math.min(lim, this.kp * err));
      this.stats.rate = rate;
      var stepv = step0 * rate, n = this.blockOut, data = [], tsStart = this.tsAt(this.readPos);
      for (var c = 0; c < this.channels; c++) {
        var o = new Float32Array(n), ring = this.ring[c], pos = this.readPos;
        for (var i = 0; i < n; i++) { o[i] = this._sample(ring, pos); pos += stepv; }
        data.push(o);
      }
      this.readPos += n * stepv;
      var block = { frame: this.outFrame, data: data, ts: tsStart };
      if (this.fadeIn) { this._fade(block, true); this.fadeIn = false; }
      this._emitPending(out, false);
      this.pending = block;
      this.outFrame += n;
    }
    // Le dernier bloc est gardé (pour pouvoir le faire fondre en cas de saut) sauf s'il doit partir
    if (this.pending && this.pending.frame / this.outRate - now < this.flushLead) {
      var starving = this.buffered() < need;
      this._emitPending(out, starving); // fondu sortant si le tampon va se vider
      if (starving) this.fadeIn = true;
    }
    return out;
  };

  return {
    Player: Player,
    CODEC_OPUS: CODEC_OPUS, CODEC_PCM16: CODEC_PCM16, HEADER_SIZE: HEADER_SIZE,
    parsePacket: parsePacket, pcm16ToFloat: pcm16ToFloat,
    ClockSync: ClockSync, CtxClock: CtxClock, Scheduler: Scheduler
  };
});
