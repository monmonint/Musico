/*
 * Test de bout en bout dans un vrai Chrome (headless) contre le vrai serveur Kotlin :
 *   1) MUSICO_LIVE_SECONDS=40 ../gradlew -p .. :core:test --tests '*LiveStreamDemo*' &
 *   2) npm i --no-save puppeteer-core && CHROME=/usr/bin/google-chrome node e2e-browser.js
 * Ouvre 2 « auditeurs », appuie sur « Écouter », puis vérifie : réception, décodage, aucun
 * paquet en retard, tampon ≈ latence cible, et que les 2 pages planifient chaque paquet au
 * même instant absolu (écart de synchro entre auditeurs).
 */
const puppeteer = require("puppeteer-core");
const URL = process.env.MUSICO_URL || "http://127.0.0.1:8099/";

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || "/usr/bin/google-chrome",
    headless: true,
    args: ["--no-sandbox", "--mute-audio", "--use-fake-ui-for-media-stream"],
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
  await new Promise((r) => setTimeout(r, 8000));
  const stats = [];
  for (const p of pages) {
    stats.push(await p.evaluate(() => {
      const m = window.__musico;
      return { received: m.received, decodeErrors: m.decodeErrors, ctx: m.ctxState, ahead: m.ahead,
        late: m.sched.late, lost: m.sched.lost, resyncs: m.sched.resyncs, offset: m.clock.offset, rtt: m.clock.rtt,
        planned: m.planned, status: document.getElementById("status").textContent, latency: m.cfg.latencyMs };
    }));
  }
  let diffs = [];
  for (const k of Object.keys(stats[0].planned)) {
    const a = stats[0].planned[k], b = stats[1].planned[k];
    if (a && b && a.seq === b.seq) diffs.push(Math.abs(a.abs - b.abs));
  }
  diffs.sort((x, y) => x - y);
  stats.forEach((s, i) => { const { planned, ...rest } = s; console.log("auditeur", i, JSON.stringify(rest)); });
  const med = diffs[Math.floor(diffs.length / 2)], max = diffs[diffs.length - 1];
  console.log(`écart entre auditeurs sur ${diffs.length} paquets : médiane ${med?.toFixed(2)} ms, max ${max?.toFixed(2)} ms`);
  await browser.close();
  const ok = stats.every((s) => s.received > 300 && s.decodeErrors === 0 && s.ctx === "running" && s.late === 0 &&
    s.ahead > 0.1 && s.ahead < s.latency / 1000 + 0.05) && diffs.length > 100 && max < 5;
  console.log(ok ? "E2E OK" : "E2E ÉCHEC");
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
