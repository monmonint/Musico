package fr.musico.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur HTTP + WebSocket minimaliste, sans dépendance (Java pur, testable sur JVM).
 *
 * - GET /chemin  -> fichier statique fourni par [assets] (null => 404)
 * - GET /ws      -> WebSocket (RFC 6455) : texte (JSON) et binaire (paquets audio)
 *
 * Chaque client a sa propre file d'envoi bornée et son thread d'écriture : un auditeur lent
 * ne bloque ni la capture ni les autres auditeurs (les paquets les plus anciens sont jetés).
 */
class HttpWsServer(
    private val requestedPort: Int,
    private val assets: (String) -> Asset?,
    private val handler: Handler,
) {
    data class Asset(val bytes: ByteArray, val contentType: String)

    interface Handler {
        fun onOpen(client: Client) {}
        fun onText(client: Client, text: String) {}
        fun onClose(client: Client) {}
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val idGen = AtomicInteger(1)

    val clientCount: Int get() = clients.size
    val clientList: List<Client> get() = clients.toList()
    var port: Int = -1
        private set

    /** Démarre le serveur ; essaie quelques ports à partir de [requestedPort]. Retourne le port. */
    fun start(): Int {
        var lastError: IOException? = null
        for (p in requestedPort until requestedPort + 10) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                serverSocket = ss
                port = ss.localPort
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val ss = serverSocket ?: throw lastError ?: IOException("Impossible d'ouvrir un port")
        running.set(true)
        Thread({ acceptLoop(ss) }, "musico-http-accept").apply { isDaemon = true }.start()
        return port
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) {}
        clients.forEach { it.close() }
        clients.clear()
    }

    fun broadcastBinary(data: ByteArray) {
        for (c in clients) if (c.ready) c.sendBinary(data)
    }

    fun broadcastText(text: String) {
        for (c in clients) c.sendText(text)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val s = try { ss.accept() } catch (e: Throwable) { if (ss.isClosed) break else continue }
            if (clients.size >= MAX_CLIENTS) { try { s.close() } catch (_: Throwable) {}; continue }
            try {
                Thread({ handleConnection(s) }, "musico-http-conn").apply { isDaemon = true }.start()
            } catch (_: Throwable) { try { s.close() } catch (_: Throwable) {} }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val requestLine = readLine(input) ?: return socket.close()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return socket.close()
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return socket.close()
            val method = parts[0]
            var path = parts[1].substringBefore('?')
            if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true && path == "/ws") {
                upgradeToWebSocket(socket, input, out, headers)
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeResponse(out, 405, "Method Not Allowed", "text/plain", "405".toByteArray(), method == "HEAD")
                return socket.close()
            }
            if (path == "/" || path.isEmpty()) path = "/index.html"
            val asset = if (path.contains("..")) null else assets(path.removePrefix("/"))
            if (asset == null) {
                writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Introuvable".toByteArray(), method == "HEAD")
            } else {
                writeResponse(out, 200, "OK", asset.contentType, asset.bytes, method == "HEAD")
            }
            socket.close()
        } catch (_: Throwable) {
            // Jamais d'exception non rattrapée dans un thread : elle tuerait toute l'appli
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, msg: String, type: String, body: ByteArray, head: Boolean) {
        val h = "HTTP/1.1 $code $msg\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(body)
        out.flush()
    }

    private fun upgradeToWebSocket(socket: Socket, input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return socket.close()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1))
        )
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(resp.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        socket.soTimeout = 0
        val client = Client(idGen.getAndIncrement(), socket, out, socket.inetAddress?.hostAddress ?: "?")
        clients.add(client)
        client.startWriter()
        try {
            handler.onOpen(client)
            readFrames(client, input)
        } catch (_: Throwable) {
        } finally {
            clients.remove(client)
            client.close()
            try { handler.onClose(client) } catch (_: Throwable) {}
        }
    }

    private fun readFrames(client: Client, input: InputStream) {
        val message = ByteArrayOutputStream()
        var messageOpcode = 0
        while (true) {
            val b0 = input.read(); if (b0 < 0) return
            val b1 = input.read(); if (b1 < 0) return
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(input).toLong() } }
            if (len > MAX_IN_FRAME) throw IOException("frame trop grande")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(len.toInt())
            readFully(input, payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x8 -> { client.sendControl(0x8, payload.copyOf(minOf(2, payload.size))); return }
                0x9 -> client.sendControl(0xA, payload)
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    if (opcode != 0) { messageOpcode = opcode; message.reset() }
                    message.write(payload)
                    if (message.size() > MAX_IN_FRAME) throw IOException("message trop grand")
                    if (fin) {
                        if (messageOpcode == 0x1) {
                            try { handler.onText(client, message.toString("UTF-8")) } catch (_: Exception) {}
                        }
                        message.reset()
                    }
                }
            }
        }
    }

    class Client internal constructor(
        val id: Int,
        private val socket: Socket,
        private val out: OutputStream,
        val address: String,
    ) {
        /** Le client ne reçoit l'audio qu'après avoir annoncé être prêt (bouton « Écouter »). */
        @Volatile var ready: Boolean = false
        @Volatile var lastStats: String? = null
        // Synchro (renseigné par StreamHub)
        @Volatile var manualMs: Double = 0.0
        @Volatile var autoMs: Double? = null
        @Volatile var measuredMs: Double? = null
        @Volatile var syncStatus: String = ""
        @Volatile var userAgent: String = ""
        @Volatile var droppedPackets: Long = 0
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        private val closed = AtomicBoolean(false)

        fun sendText(text: String) = enqueue(frame(0x1, text.toByteArray(Charsets.UTF_8)), important = true)
        fun sendBinary(data: ByteArray) = enqueue(frame(0x2, data), important = false)
        internal fun sendControl(opcode: Int, data: ByteArray) = enqueue(frame(opcode, data), important = true)

        private fun enqueue(f: ByteArray, important: Boolean) {
            if (closed.get()) return
            while (!queue.offer(f)) {
                // File pleine : on jette le plus ancien paquet (l'audio en retard est inutile)
                queue.poll() ?: break
                droppedPackets++
                if (!important) continue
            }
        }

        internal fun startWriter() {
            Thread({
                try {
                    while (!closed.get()) {
                        val f = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        out.write(f)
                        // Regroupe ce qui est déjà en attente avant de flusher
                        while (true) { val n = queue.poll() ?: break; out.write(n) }
                        out.flush()
                    }
                } catch (_: Throwable) {
                    close()
                }
            }, "musico-ws-writer-$id").apply { isDaemon = true }.start()
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                try { socket.close() } catch (_: IOException) {}
            }
        }

        val isClosed: Boolean get() = closed.get()
    }

    companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val QUEUE_CAPACITY = 200 // ~4 s d'audio en paquets de 20 ms
        const val MAX_IN_FRAME = 64 * 1024L
        const val MAX_CLIENTS = 32

        fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val headerLen = when { len < 126 -> 2; len < 65536 -> 4; else -> 10 }
            val f = ByteArray(headerLen + len)
            f[0] = (0x80 or opcode).toByte()
            when (headerLen) {
                2 -> f[1] = len.toByte()
                4 -> { f[1] = 126; f[2] = (len shr 8).toByte(); f[3] = len.toByte() }
                else -> { f[1] = 127; for (i in 0 until 8) f[2 + i] = (len.toLong() shr (8 * (7 - i))).toByte() }
            }
            System.arraycopy(payload, 0, f, headerLen, len)
            return f
        }

        private fun readByte(input: InputStream): Int {
            val b = input.read()
            if (b < 0) throw IOException("EOF")
            return b
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("EOF")
                off += n
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 8192) throw SocketException("ligne trop longue")
            }
        }

        fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "wav" -> "audio/wav"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
