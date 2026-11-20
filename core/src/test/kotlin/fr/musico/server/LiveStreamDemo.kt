package fr.musico.server

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Démo/E2E manuelle : sert la vraie page d'écoute et diffuse des paquets Opus en boucle,
 * comme le ferait l'appli (horodatage = horloge monotone de l'émetteur, un paquet / 20 ms).
 * Lancée seulement si MUSICO_LIVE_SECONDS est défini, ex. :
 *   MUSICO_LIVE_SECONDS=60 ./gradlew :core:test --tests '*LiveStreamDemo*'
 * puis ouvrir http://127.0.0.1:8099/ (ou lancer web-tests/e2e-browser.js).
 */
class LiveStreamDemo {
    @Test fun stream() {
        val seconds = System.getenv("MUSICO_LIVE_SECONDS")?.toIntOrNull()
        assumeTrue(seconds != null)
        val packets = ArrayList<ByteArray>()
        DataInputStream(File("../web-tests/fixtures/sine.opuspk").inputStream().buffered()).use { inp ->
            while (inp.available() > 0) { val n = inp.readUnsignedShort(); packets.add(ByteArray(n).also { inp.readFully(it) }) }
        }
        val now = { System.nanoTime() / 1e6 }
        val hub = StreamHub(now)
        hub.preSkipMs = 6.5
        hub.latencyMs = System.getenv("MUSICO_LATENCY")?.toIntOrNull() ?: 300
        val dir = File("../app/src/main/assets/web")
        val server = HttpWsServer(8099, { p ->
            val f = File(dir, p); if (f.isFile) HttpWsServer.Asset(f.readBytes(), HttpWsServer.contentTypeFor(p)) else null
        }, hub)
        hub.server = server
        println("LIVE: http://127.0.0.1:${server.start()}/")
        val t0 = now()
        var i = 0L
        while (now() - t0 < seconds!! * 1000) {
            val ts = t0 + i * 20.0
            while (now() < ts + 20) Thread.sleep(1) // le paquet sort ~20 ms après sa capture
            hub.setStatus(true, false, "Diffusion en cours")
            hub.publishAudio(ts, packets[(i % packets.size).toInt()])
            i++
        }
        server.stop()
    }
}
