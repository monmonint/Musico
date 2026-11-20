package fr.musico.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HttpWsServerTest {
    private val assetDir = File("../app/src/main/assets/web")
    private lateinit var hub: StreamHub
    private lateinit var server: HttpWsServer
    private var port = 0
    private val http = HttpClient.newHttpClient()

    @Before fun setUp() {
        hub = StreamHub { System.nanoTime() / 1e6 }
        server = HttpWsServer(18080, { p ->
            val f = File(assetDir, p)
            if (f.isFile) HttpWsServer.Asset(f.readBytes(), HttpWsServer.contentTypeFor(p)) else null
        }, hub)
        hub.server = server
        port = server.start()
    }

    @After fun tearDown() = server.stop()

    private class Recorder : WebSocket.Listener {
        val texts = LinkedBlockingQueue<String>()
        val binaries = LinkedBlockingQueue<ByteArray>()
        private val sb = StringBuilder()
        private var bin = java.io.ByteArrayOutputStream()
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            sb.append(data); if (last) { texts.add(sb.toString()); sb.setLength(0) }
            ws.request(1); return null
        }
        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val b = ByteArray(data.remaining()); data.get(b); bin.write(b)
            if (last) { binaries.add(bin.toByteArray()); bin = java.io.ByteArrayOutputStream() }
            ws.request(1); return null
        }
        fun nextText(type: String): String {
            while (true) {
                val t = texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("pas de message $type")
                if (t.contains("\"t\":\"$type\"")) return t
            }
        }
    }

    private fun connect(): Pair<WebSocket, Recorder> {
        val rec = Recorder()
        val ws = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port/ws"), rec).get(3, TimeUnit.SECONDS)
        return ws to rec
    }

    @Test fun servesListenerPage() {
        val r = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("content-type").get().startsWith("text/html"))
        assertTrue(r.body().contains("Écouter") || r.body().contains("couter"))
        val js = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/opus-decoder.min.js")).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, js.statusCode())
        assertTrue(js.body().size > 50_000)
        val missing = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/../secret")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, missing.statusCode())
    }

    @Test fun helloPingAndReadyGating() {
        val (ws, rec) = connect()
        val hello = rec.nextText("hello")
        assertTrue(hello.contains("\"codec\":\"opus\""))
        assertTrue(hello.contains("\"latencyMs\":600"))
        rec.nextText("status")

        ws.sendText("""{"t":"ping","c":1234.5}""", true).get()
        val pong = rec.nextText("pong")
        assertEquals("1234.5", StreamHub.field(pong, "c"))
        assertNotNull(StreamHub.field(pong, "s"))

        // Pas d'audio tant que la page n'a pas dit "ready"
        hub.publishAudio(1000.0, byteArrayOf(1, 2, 3))
        assertEquals(null, rec.binaries.poll(300, TimeUnit.MILLISECONDS))

        ws.sendText("""{"t":"ready"}""", true).get()
        Thread.sleep(200)
        val payload = ByteArray(300) { it.toByte() }
        hub.publishAudio(4242.25, payload)
        val pkt = rec.binaries.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("pas de paquet")
        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, bb.get().toInt())
        assertEquals(StreamHub.CODEC_OPUS, bb.get().toInt())
        bb.getInt() // seq
        assertEquals(4242.25, bb.getDouble(), 0.0)
        assertEquals(300, pkt.size - StreamHub.HEADER_SIZE)
        assertEquals(payload.toList(), pkt.copyOfRange(StreamHub.HEADER_SIZE, pkt.size).toList())

        hub.setLatency(450)
        assertTrue(rec.nextText("config").contains("450"))
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get()
    }

    @Test fun multipleListenersReceiveSamePackets() {
        val clients = (1..5).map { connect() }
        clients.forEach { (ws, rec) -> rec.nextText("hello"); ws.sendText("""{"t":"ready"}""", true).get() }
        Thread.sleep(300)
        assertEquals(5, server.clientCount)
        repeat(50) { i -> hub.publishAudio(i * 20.0, ByteArray(200) { i.toByte() }) }
        clients.forEach { (_, rec) ->
            val seqs = (1..50).map {
                val p = rec.binaries.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("paquet manquant")
                ByteBuffer.wrap(p).getInt(2)
            }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(50, seqs.toSet().size)
        }
        clients.forEach { it.first.sendClose(WebSocket.NORMAL_CLOSURE, "").get() }
        Thread.sleep(300)
        assertEquals(0, server.clientCount)
    }

    @Test fun largeFrameEncoding() {
        val big = ByteArray(70_000) { (it % 251).toByte() }
        val f = HttpWsServer.frame(2, big)
        assertEquals(127, f[1].toInt())
        assertEquals(10 + big.size, f.size)
        val mid = HttpWsServer.frame(2, ByteArray(1000))
        assertEquals(126, mid[1].toInt())
        assertEquals(4 + 1000, mid.size)
    }

    @Test fun jsonFieldParsing() {
        assertEquals("ping", StreamHub.field("""{"t":"ping","c":12.5}""", "t"))
        assertEquals("12.5", StreamHub.field("""{"t":"ping","c":12.5}""", "c"))
        assertEquals("-3e-2", StreamHub.field("""{"c": -3e-2}""", "c"))
        assertEquals("a \\\"b\\\"", StreamHub.escape("a \"b\"").let { it })
    }

    @Test fun readyCarriesOffsetsAndOffsetMessageIsSent() {
        val (ws, rec) = connect()
        val hello = rec.nextText("hello")
        val id = StreamHub.field(hello, "id")!!.toInt()
        ws.sendText("""{"t":"ready","manual":-12.5,"auto":33.25,"ua":"Test"}""", true).get()
        Thread.sleep(200)
        val c = server.clientList.first { it.id == id }
        assertTrue(c.ready)
        assertEquals(-12.5, c.manualMs, 1e-9)
        assertEquals(33.25, c.autoMs!!, 1e-9)
        hub.sendOffset(c, 30.0, 3.0, 1.5, "Corrigé")
        val off = rec.nextText("offset")
        assertEquals("30.000", StreamHub.field(off, "auto"))
        assertEquals("3.000", StreamHub.field(off, "probe"))
        assertEquals("1.500", StreamHub.field(off, "measured"))
        ws.sendText("""{"t":"stats","manual":7}""", true).get()
        Thread.sleep(200)
        assertEquals(7.0, c.manualMs, 1e-9)
    }

    @Test fun garbageAndBadJsonDoNotKillServer() {
        // Requête HTTP invalide, octets aléatoires, JSON cassé : le serveur survit
        java.net.Socket("127.0.0.1", port).use { it.getOutputStream().write(ByteArray(5000) { (it * 7).toByte() }) }
        java.net.Socket("127.0.0.1", port).use { it.getOutputStream().write("GET\r\n\r\n".toByteArray()) }
        val (ws, rec) = connect()
        rec.nextText("hello")
        ws.sendText("{pas du json", true).get()
        ws.sendText("""{"t":"ping","c":"abc"}""", true).get()
        ws.sendText("""{"t":"ready","manual":"NaN"}""", true).get()
        val (ws2, rec2) = connect()
        rec2.nextText("hello")
        ws2.sendText("""{"t":"ping","c":1}""", true).get()
        rec2.nextText("pong")
        val r = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode())
    }

    @Test fun handlerExceptionIsContained() {
        val bad = object : HttpWsServer.Handler {
            override fun onText(client: HttpWsServer.Client, text: String) { throw IllegalStateException("boum") }
        }
        val s2 = HttpWsServer(18200, { null }, bad)
        val p2 = s2.start()
        try {
            val rec = Recorder()
            val ws = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$p2/ws"), rec).get(3, TimeUnit.SECONDS)
            repeat(5) { ws.sendText("x", true).get() }
            Thread.sleep(200)
            assertEquals(1, s2.clientCount) // toujours connecté
        } finally { s2.stop() }
    }
}
