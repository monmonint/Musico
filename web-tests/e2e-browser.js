/*
 * Test de bout en bout dans un vrai Chrome (headless) contre le vrai serveur Kotlin :
 *   1) MUSICO_LIVE_SECONDS=45 MUSICO_JITTER_MS=40 MUSICO_PROBE=1 ./gradlew :core:test --tests '*LiveStreamDemo*' --rerun &
 *   2) npm i --no-save puppeteer-core && CHROME=/usr/bin/google-chrome node e2e-browser.js
 * Ouvre 2 « auditeurs », appuie sur « Écouter », puis vérifie : réception, décodage, blocs
 * contigus (aucun trou), aucun saut ni creux malgré la gigue et les ordres de synchro, et que
 * les 2 pages jouent chaque instant du flux au même moment (écart entre auditeurs).
 */
const puppeteer = require("puppeteer-core");
const URL = process.env.MUSICO_URL || "http://127.0.0.1:8099/";
const WAIT = parseInt(process.env.E2E_WAIT_MS || "25000", 10);

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || "/usr/bin/google-chrome",
    headless: true,
    args: ["--no-sandbox", "--mute-audio"],
  });
  const pages = [];
  for (let k = 0; k < 2; k++) {
    const p = await browser.newPage();
    p.on("pageerror", (e) => console.log("pageerror", k, e.message));
    p.on("console", (m) => { if (m.type() === "error") console.log("console", k, m.text()); });
    await p.goto(URL);
    await p.waitForFunction(() => !document.getElementById("listen").disabled, { timeout: 10000 });
    await p.click("#listen");
    pages.push(p);
    await new Promise((r) => setTimeout(r, 700)); // décale volontairement les 2 auditeurs
  }
  await new Promise((r) => setTimeout(r, WAIT));
  const stats = [];
  for (const p of pages) {
    stats.push(await p.evaluate(() => {
      const m = window.__musico, st = m.player.stats;
      return { received: m.received, decodeErrors: m.decodeErrors, renderErrors: m.renderErrors, gaps: m.gaps,
        ctx: m.ctxState, ahead: +m.ahead.toFixed(3), underruns: st.underruns, resyncs: st.resyncs, concealed: st.concealed,
        errMs: +(st.err * 1000).toFixed(2), offsets: m.offsets, measuredText: document.getElementById("measured").textContent,
        blocks: m.blocks.slice(-600), trace: m.trace, latency: m.cfg.latencyMs };
    }));
  }
  // Écart entre auditeurs : pour chaque bloc de B, instant où A joue le même échantillon du flux
  const A = stats[0].blocks, B = stats[1].blocks, diffs = [], worst = [];
  for (const [ts, abs] of B) {
    for (let j = 1; j < A.length; j++) {
      if (A[j - 1][0] <= ts && A[j][0] >= ts) {
        const f = (ts - A[j - 1][0]) / (A[j][0] - A[j - 1][0] || 1);
        diffs.push(Math.abs(abs - (A[j - 1][1] + f * (A[j][1] - A[j - 1][1]))));
        if (diffs[diffs.length - 1] > 3) worst.push([+(ts - B[0][0]).toFixed(0), +diffs[diffs.length - 1].toFixed(2)]);
        break;
      }
    }
  }
  if (worst.length) console.log("écarts > 3 ms (ms depuis le début de la trace, écart):", JSON.stringify(worst.slice(0, 20)));
  diffs.sort((x, y) => x - y);
  if (process.env.E2E_TRACE) stats.forEach((s, i) => { const t = s.trace; console.log("trace", i, JSON.stringify(t.filter((x, k) => k === 0 || Math.abs(x[1] - t[k - 1][1]) > 0.3 || Math.abs(x[2] - t[k - 1][2]) > 0.3))); });
  stats.forEach((s, i) => { const { blocks, trace, ...rest } = s; console.log("auditeur", i, JSON.stringify(rest)); });
  const med = diffs[Math.floor(diffs.length / 2)], max = diffs[diffs.length - 1];
  console.log(`écart entre auditeurs sur ${diffs.length} blocs : médiane ${med?.toFixed(2)} ms, max ${max?.toFixed(2)} ms`);
  await browser.close();
  const ok = stats.every((s) => s.received > 300 && s.decodeErrors === 0 && s.renderErrors === 0 && s.ctx === "running" &&
    s.gaps === 0 && s.resyncs === 0 && s.underruns === 0 && Math.abs(s.errMs) < 2) && diffs.length > 100 && max < 5;
  console.log(ok ? "E2E OK" : "E2E ÉCHEC");
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
