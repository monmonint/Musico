const test = require("node:test");
const assert = require("node:assert");
const { loadOpusDecoder, readPackets } = require("./helpers");

function dominantFreq(x, sr) { // passages par zéro
  let z = 0; for (let i = 1; i < x.length; i++) if ((x[i - 1] < 0) !== (x[i] < 0)) z++;
  return z / 2 / (x.length / sr);
}

test("le décodeur Opus embarqué décode des paquets Opus bruts (20 ms, stéréo)", async () => {
  const Lib = loadOpusDecoder();
  assert.ok(Lib && Lib.OpusDecoder, "opus-decoder non chargé");
  const dec = new Lib.OpusDecoder({ channels: 2, sampleRate: 48000, preSkip: 0 });
  await dec.ready;
  const packets = readPackets();
  assert.ok(packets.length > 250);
  const L = [], R = [];
  for (const p of packets) {
    const r = dec.decodeFrame(p);
    assert.strictEqual(r.samplesDecoded, 960, "20 ms = 960 échantillons");
    assert.strictEqual(r.channelData.length, 2);
    L.push(r.channelData[0]); R.push(r.channelData[1]);
  }
  const cat = (a) => { const o = new Float32Array(a.reduce((n, x) => n + x.length, 0)); let k = 0; for (const x of a) { o.set(x, k); k += x.length; } return o; };
  const l = cat(L).subarray(48000, 48000 * 4), r = cat(R).subarray(48000, 48000 * 4);
  const fl = dominantFreq(l, 48000), fr = dominantFreq(r, 48000);
  assert.ok(Math.abs(fl - 440) < 5, "gauche ≈ 440 Hz, obtenu " + fl);
  assert.ok(Math.abs(fr - 660) < 5, "droite ≈ 660 Hz, obtenu " + fr);
  const peak = l.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
  assert.ok(peak > 0.05 && peak <= 1.01, "amplitude " + peak);
  dec.free();
});
