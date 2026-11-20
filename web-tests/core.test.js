const test = require("node:test");
const assert = require("node:assert");
const { loadCore } = require("./helpers");
const Core = loadCore();

// Générateur pseudo-aléatoire déterministe
function rng(seed) { return () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296); }

test("parsePacket lit l'en-tête binaire", () => {
  const buf = new ArrayBuffer(14 + 3), dv = new DataView(buf);
  dv.setUint8(0, 1); dv.setUint8(1, 1); dv.setUint32(2, 4000000000); dv.setFloat64(6, 123456789.125);
  new Uint8Array(buf, 14).set([7, 8, 9]);
  const p = Core.parsePacket(buf);
  assert.strictEqual(p.codec, 1); assert.strictEqual(p.seq, 4000000000);
  assert.strictEqual(p.ts, 123456789.125); assert.deepStrictEqual([...p.payload], [7, 8, 9]);
  assert.strictEqual(Core.parsePacket(new ArrayBuffer(5)), null);
});

test("pcm16ToFloat désentrelace", () => {
  const b = new Uint8Array(8), dv = new DataView(b.buffer);
  dv.setInt16(0, 16384, true); dv.setInt16(2, -32768, true); dv.setInt16(4, 0, true); dv.setInt16(6, 32767, true);
  const [l, r] = Core.pcm16ToFloat(b, 2);
  assert.deepStrictEqual([...l], [0.5, 0]); assert.strictEqual(r[0], -1); assert.ok(Math.abs(r[1] - 1) < 1e-4);
});

test("ClockSync retrouve le décalage malgré une gigue asymétrique", () => {
  const rand = rng(42);
  const trueOffset = 987654.321; // heureEmetteur - heureLocale
  const cs = new Core.ClockSync();
  let local = 1000;
  for (let i = 0; i < 40; i++) {
    const up = 2 + rand() * 60, down = 2 + rand() * 60; // ms, Wi-Fi bruité
    const c = local; const s = c + up + trueOffset; local = c + up + down;
    cs.addSample(c, s, local);
    local += 100;
  }
  assert.ok(cs.ready());
  const err = Math.abs(cs.offset - trueOffset);
  assert.ok(err < 5, "erreur de synchro trop grande: " + err.toFixed(2) + " ms");
  assert.strictEqual(cs.toLocal(trueOffset + 5000).toFixed(0), (5000 + (trueOffset - cs.offset)).toFixed(0));
});

test("Scheduler : lecture continue, sans recalage, quand les cibles sont régulières", () => {
  const s = new Core.Scheduler();
  const d = 0.02; let t0 = 1.0;
  let prevEnd = null;
  for (let i = 0; i < 100; i++) {
    const p = s.plan(i, t0 + i * d + (Math.random() - 0.5) * 0.001, d, 0.5);
    assert.ok(p);
    if (prevEnd !== null) assert.ok(Math.abs(p.startAt - prevEnd) < 1e-9, "trou ou chevauchement");
    prevEnd = p.startAt + d / p.rate;
  }
  assert.strictEqual(s.resyncs, 1);
});

test("Scheduler : rattrape une dérive d'horloge de 200 ppm par la vitesse", () => {
  const s = new Core.Scheduler();
  const d = 0.02, drift = 1.0002; let minRate = 1;
  for (let i = 0; i < 3000; i++) { // 60 s
    minRate = Math.min(minRate, s.plan(i, 1 + i * d * drift, d, 0).rate);
  }
  assert.strictEqual(s.resyncs, 1, "ne doit pas se recaler brutalement");
  const err = Math.abs(s.nextTime - (1 + 3000 * d * drift));
  assert.ok(err < 0.005, "erreur résiduelle " + (err * 1000).toFixed(2) + " ms");
  assert.ok(minRate < 1 && minRate >= 0.995, "doit ralentir légèrement (cible plus tardive)");
});

test("Scheduler : recalage franc sur gros saut, perte et paquet trop tard", () => {
  const s = new Core.Scheduler();
  s.plan(0, 1.0, 0.02, 0.5);
  s.plan(1, 1.02, 0.02, 0.5);
  const jump = s.plan(2, 1.2, 0.02, 0.5); // saut de 160 ms
  assert.strictEqual(jump.startAt, 1.2); assert.strictEqual(s.resyncs, 2);
  s.plan(5, 1.3, 0.02, 0.5); // 3,4 perdus
  assert.strictEqual(s.lost, 2); assert.strictEqual(s.resyncs, 3);
  assert.strictEqual(s.plan(6, 0.2, 0.02, 0.5), null); // trop tard
  assert.strictEqual(s.late, 1);
  const partial = s.plan(7, 0.505, 0.02, 0.5); // commence déjà : début coupé
  assert.ok(partial.offset > 0 && partial.startAt >= 0.51 - 1e-9);
});

test("CtxClock : utilise getOutputTimestamp et lisse la gigue", () => {
  const cc = new Core.CtxClock();
  const fake = (perf, jitter) => ({ getOutputTimestamp: () => ({ contextTime: 10 + perf / 1000 - 5 + jitter, performanceTime: perf }) });
  cc.update(fake(5000, 0), 5000);
  for (let i = 0; i < 50; i++) cc.update(fake(5000 + i * 100, (i % 2 ? 1 : -1) * 0.002), 5000 + i * 100);
  assert.ok(Math.abs(cc.toCtx(8000) - (10 + 8 - 5)) < 0.002);
  const fallback = new Core.CtxClock();
  fallback.update({ state: "running", currentTime: 3, outputLatency: 0.04, baseLatency: 0.01 }, 1000);
  assert.ok(Math.abs(fallback.toCtx(1000) - 2.95) < 1e-9);
});
