const test = require("node:test");
const assert = require("node:assert");
const { loadCore } = require("./helpers");
const Core = loadCore();

function rng(seed) { return () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296); }

/**
 * Simule un auditeur : l'émetteur produit un sinus en paquets de 20 ms, le réseau les livre avec
 * gigue/coupures, le Player produit des blocs ; on reconstitue le signal de sortie et on vérifie
 * continuité (aucun trou/chevauchement, pas de discontinuité audible) et précision temporelle.
 */
function simulate(opts) {
  const o = Object.assign({ seconds: 20, outRate: 48000, latency: 0.6, jitter: 0.03, seed: 1, drift: 0,
    freq: 440, loss: [], stalls: [], offsetAt: null, dup: false, forceJumpAt: null }, opts);
  const rand = rng(o.seed);
  const P = new Core.Player({ outRate: o.outRate, inRate: 48000, channels: 1 });
  const packets = [];
  const nPk = Math.floor(o.seconds * 50);
  for (let i = 0; i < nPk; i++) {
    const tsMs = 1000 + i * 20;
    let arrive = (tsMs + 20) / 1000 + rand() * o.jitter;
    for (const [a, d] of o.stalls) if (arrive >= a && arrive < a + d) arrive = a + d + rand() * 0.005;
    packets.push({ seq: i, tsMs, arrive });
  }
  // WebSocket = TCP : les paquets arrivent toujours dans l'ordre (la gigue les regroupe)
  for (let i = 1; i < packets.length; i++) packets[i].arrive = Math.max(packets[i].arrive, packets[i - 1].arrive);
  let extra = 0; // décalage cible (ms), ex. sonde/correction auto
  // horloge locale du contexte : ctx = (temps émetteur) * (1 + drift)
  const target = (ts) => ((ts + o.latency * 1000 + extra) / 1000) * (1 + o.drift);
  const out = new Float32Array(Math.ceil((o.seconds + 3) * o.outRate * (1 + Math.abs(o.drift))));
  const written = new Uint8Array(out.length);
  let k = 0, prevEnd = null, gaps = 0, errs = [];
  for (let now = 0.9; now < o.seconds + 1.6; now += 0.005) {
    if (o.offsetAt && now >= o.offsetAt[0] && extra !== o.offsetAt[1]) extra = o.offsetAt[1];
    if (o.forceJumpAt && Math.abs(now - o.forceJumpAt[0]) < 0.0025) { extra = o.forceJumpAt[1]; P.forceJump = true; }
    while (k < packets.length && packets[k].arrive <= now) {
      const p = packets[k++];
      if (o.loss.includes(p.seq)) continue;
      const data = new Float32Array(960);
      for (let j = 0; j < 960; j++) data[j] = 0.5 * Math.sin(2 * Math.PI * o.freq * (p.tsMs / 1000 + j / 48000));
      P.push(p.seq, p.tsMs, [data], 960);
      if (o.dup) P.push(p.seq, p.tsMs, [data], 960);
    }
    for (const b of P.render(now, target)) {
      assert.ok(b.frame / o.outRate >= now, "bloc programmé dans le passé");
      if (prevEnd !== null && b.frame !== prevEnd) gaps++;
      prevEnd = b.frame + b.data[0].length;
      for (let j = 0; j < b.data[0].length; j++) {
        const f = b.frame + j;
        if (f < out.length) { if (written[f]) throw new Error("chevauchement"); out[f] = b.data[0][j]; written[f] = 1; }
      }
      if (now > 3) errs.push(Math.abs(P.stats.err));
    }
  }
  return { P, out, written, gaps, errs, outRate: o.outRate };
}

/** Plus grand saut entre échantillons consécutifs (un sinus 440 Hz à 0,5 : ≈ 0,029 à 48 kHz). */
function maxJump(r, from, to) {
  let m = 0;
  for (let f = Math.floor(from * r.outRate); f < Math.floor(to * r.outRate); f++) {
    if (!r.written[f] || !r.written[f - 1]) continue;
    m = Math.max(m, Math.abs(r.out[f] - r.out[f - 1]));
  }
  return m;
}
const sinJump = (rate) => 0.5 * 2 * Math.PI * 440 / rate * 1.05;

test("gigue réseau 30 ms : lecture continue, sans saut ni creux, erreur < 1 ms", () => {
  const r = simulate({ seconds: 20 });
  assert.strictEqual(r.P.stats.resyncs, 0);
  assert.strictEqual(r.P.stats.underruns, 0);
  assert.strictEqual(r.gaps, 0);
  assert.ok(maxJump(r, 2, 20) < sinJump(48000), "discontinuité " + maxJump(r, 2, 20));
  const tail = r.errs.slice(-200);
  assert.ok(Math.max(...tail) < 0.001, "erreur " + Math.max(...tail));
});

test("dérive d'horloge 300 ppm : rattrapée par la vitesse, sans saut", () => {
  const r = simulate({ seconds: 40, drift: 0.0003 });
  assert.strictEqual(r.P.stats.resyncs, 0);
  assert.strictEqual(r.gaps, 0);
  const tail = r.errs.slice(-200);
  assert.ok(Math.max(...tail) < 0.001, "erreur " + Math.max(...tail));
  assert.ok(maxJump(r, 2, 40) < sinJump(48000) * 1.01);
});

test("sonde ±3 ms et correction auto de 25 ms : glissement en douceur, sans saut", () => {
  const r1 = simulate({ seconds: 15, offsetAt: [5, 3] });
  assert.strictEqual(r1.P.stats.resyncs, 0);
  assert.ok(maxJump(r1, 2, 15) < sinJump(48000) * 1.02);
  const r2 = simulate({ seconds: 20, offsetAt: [5, 25] });
  assert.strictEqual(r2.P.stats.resyncs, 0, "25 ms doit glisser, pas sauter");
  assert.strictEqual(r2.gaps, 0);
  assert.ok(Math.max(...r2.errs.slice(-100)) < 0.001);
});

test("paquets perdus : masqués, pas de saut ni de creux", () => {
  const r = simulate({ seconds: 15, loss: [200, 301, 302, 450] });
  assert.strictEqual(r.P.stats.concealed, 4);
  assert.strictEqual(r.P.stats.resyncs, 0);
  assert.strictEqual(r.gaps, 0);
});

test("doublons / paquets en retard ignorés", () => {
  const r = simulate({ seconds: 8, dup: true });
  assert.ok(r.P.stats.late > 300);
  assert.strictEqual(r.P.stats.resyncs, 0);
  assert.strictEqual(r.gaps, 0);
});

test("coupure Wi-Fi de 400 ms (< latence 600 ms) : aucun creux", () => {
  const r = simulate({ seconds: 15, stalls: [[6, 0.4]] });
  assert.strictEqual(r.P.stats.underruns, 0);
  assert.strictEqual(r.P.stats.resyncs, 0);
  assert.strictEqual(r.gaps, 0);
});

test("coupure de 1,5 s (> latence) : creux inévitable, reprise propre avec fondus", () => {
  const r = simulate({ seconds: 15, stalls: [[6, 1.5]] });
  assert.ok(r.P.stats.underruns + r.P.stats.resyncs >= 1);
  // fondus : jamais de discontinuité plus forte que le sinus lui-même
  assert.ok(maxJump(r, 2, 15) < sinJump(48000) * 1.05, "clic " + maxJump(r, 2, 15));
  assert.ok(Math.max(...r.errs.slice(-100)) < 0.002, "doit se recaler ensuite");
});

test("réglage manuel de 100 ms : un seul saut, en fondu (pas de clic)", () => {
  const r = simulate({ seconds: 12, forceJumpAt: [6, 100] });
  assert.strictEqual(r.P.stats.resyncs, 1);
  assert.ok(maxJump(r, 2, 12) < sinJump(48000) * 1.05, "clic " + maxJump(r, 2, 12));
  assert.ok(Math.max(...r.errs.slice(-100)) < 0.001);
});

test("contexte audio à 44,1 kHz (iPhone) : fréquence conservée, continu", () => {
  const r = simulate({ seconds: 10, outRate: 44100 });
  assert.strictEqual(r.gaps, 0);
  assert.strictEqual(r.P.stats.resyncs, 0);
  // fréquence par passages à zéro sur 5 s
  let z = 0; const a = 3 * 44100, b = 8 * 44100;
  for (let f = a + 1; f < b; f++) if ((r.out[f - 1] < 0) !== (r.out[f] < 0)) z++;
  const freq = z / 2 / 5;
  assert.ok(Math.abs(freq - 440) < 1, "fréquence " + freq);
  assert.ok(maxJump(r, 2, 10) < sinJump(44100));
});

test("émetteur relancé (numéros/horodatages qui repartent) : rebase sans planter", () => {
  const P = new Core.Player({ outRate: 48000, channels: 1 });
  const d = [new Float32Array(960)];
  for (let i = 0; i < 50; i++) P.push(i, 1000 + i * 20, d, 960);
  P.render(0.5, (ts) => ts / 1000 + 0.6);
  P.push(0, 99999, d, 960);
  assert.strictEqual(P.stats.rebases, 1);
  assert.doesNotThrow(() => P.render(0.6, (ts) => ts / 1000 + 0.6));
});
