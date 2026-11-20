package fr.musico.server

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.Random

/**
 * Démo/E2E manuelle : sert la vraie page d'écoute et diffuse des paquets Opus en boucle,
 * comme le ferait l'appli (horodatage = horloge monotone de l'émetteur, un paquet / 20 ms).
 * Lancée seulement si MUSICO_LIVE_SECONDS est défini, ex. :
 *   MUSICO_LIVE_SECONDS=60 ./gradlew :core:test --tests '*LiveStreamDemo*' --rerun
 * Options : MUSICO_JITTER_MS (gigue d'envoi simulée, ex. 40), MUSICO_STALL_MS (coupure
 * simulée à t=12 s), MUSICO_PROBE=1 (envoie à tous les auditeurs la même séquence d'ordres
 * de synchro : sonde +3 ms, -3 ms, puis correction auto de +20 ms, comme la mesure micro).
 */
class LiveStreamDemo {
    @Test fun stream() {
        val seconds = System.getenv("MUSICO_LIVE_SECONDS")?.toIntOrNull()
        assumeTrue(seconds != null)
        val jitter = System.getenv("MUSICO_JITTER_MS")?.toIntOrNull() ?: 0
        val stall = System.getenv("MUSICO_STALL_MS")?.toIntOrNull() ?: 0
        val probe = System.getenv("MUSICO_PROBE") == "1"
        val packets = ArrayList<ByteArray>()
        DataInputStream(File("../web-tests/fixtures/sine.opuspk").inputStream().buffered()).use { inp ->
            while (inp.available() > 0) { val n = inp.readUnsignedShort(); packets.add(ByteArray(n).also { inp.readFully(it) }) }
        }
        val now = { System.nanoTime() / 1e6 }
        val hub = StreamHub(now)
        hub.preSkipMs = 6.5
        hub.latencyMs = System.getenv("MUSICO_LATENCY")?.toIntOrNull() ?: 600
        val dir = File("../app/src/main/assets/web")
        val server = HttpWsServer(8099, { p ->
            val f = File(dir, p); if (f.isFile) HttpWsServer.Asset(f.readBytes(), HttpWsServer.contentTypeFor(p)) else null
        }, hub)
        hub.server = server
        println("LIVE: http://127.0.0.1:${server.start()}/")
        val rnd = Random(5)
        val t0 = now()
        var i = 0L
        var holdUntil = 0.0
        val schedule = listOf(10_000.0 to (0.0 to 3.0), 14_000.0 to (0.0 to -3.0), 18_000.0 to (0.0 to 0.0), 22_000.0 to (20.0 to 0.0))
        var next = 0
        while (now() - t0 < seconds!! * 1000) {
            val ts = t0 + i * 20.0
            // gigue : on retient parfois les paquets puis on les envoie en rafale
            var sendAt = ts + 20 + (if (jitter > 0) rnd.nextInt(jitter) else 0)
            if (stall > 0 && ts - t0 in 12_000.0..(12_000.0 + stall)) holdUntil = t0 + 12_000.0 + stall
            sendAt = maxOf(sendAt, holdUntil)
            while (now() < sendAt) Thread.sleep(1)
            hub.setStatus(true, false, "Diffusion en cours")
            hub.publishAudio(ts, packets[(i % packets.size).toInt()])
            i++
            if (probe && next < schedule.size && now() - t0 >= schedule[next].first) {
                val (auto, pr) = schedule[next].second
                for (c in server.clientList) hub.sendOffset(c, auto, pr, if (next == 3) 1.0 else null, "test")
                next++
            }
        }
        server.stop()
    }
}
