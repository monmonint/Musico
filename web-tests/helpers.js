const fs = require("fs");
const path = require("path");
const WEB = path.join(__dirname, "..", "app", "src", "main", "assets", "web");

function loadCore() { return require(path.join(WEB, "musico-core.js")); }

/** Charge opus-decoder.min.js comme dans le navigateur (chemin global, sans module). */
function loadOpusDecoder() {
  if (!globalThis["opus-decoder"]) {
    // Dans le navigateur, Worker existe (utilisé seulement par la variante WebWorker)
    if (typeof globalThis.Worker === "undefined") globalThis.Worker = class {};
    const code = fs.readFileSync(path.join(WEB, "opus-decoder.min.js"), "utf8");
    new Function("exports", "module", "define", code)(undefined, undefined, undefined);
  }
  return globalThis["opus-decoder"];
}

function readPackets() {
  const f = path.join(__dirname, "fixtures", "sine.opuspk");
  if (!fs.existsSync(f)) require("child_process").execFileSync("python3", [path.join(__dirname, "make-fixture.py"), "6"]);
  const buf = fs.readFileSync(f);
  const out = [];
  for (let pos = 0; pos < buf.length;) {
    const len = buf.readUInt16BE(pos); pos += 2;
    out.push(new Uint8Array(buf.subarray(pos, pos + len))); pos += len;
  }
  return out;
}
module.exports = { WEB, loadCore, loadOpusDecoder, readPackets };
