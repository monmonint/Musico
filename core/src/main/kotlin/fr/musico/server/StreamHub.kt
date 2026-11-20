package fr.musico.server

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Logique du protocole Musico, indépendante d'Android (testée sur JVM).
 *
 * Messages texte (JSON) :
 *  serveur -> page : hello, config, pong, status
 *  page -> serveur : ping {c}, ready, stats {...}
 * Messages binaires (serveur -> page) : paquet audio
 *  [0]=1 (type audio) [1]=codec (1=Opus, 2=PCM 16 bits LE entrelacé)
 *  [2..5]=numéro de séquence (uint32 BE) [6..13]=horodatage expéditeur en ms (float64 BE)
 *  [14..]=charge utile
 */
class StreamHub(
    /** Horloge de référence de l'émetteur, en millisecondes (monotone). */
    private val nowMs: () -> Double,
) : HttpWsServer.Handler {

    @Volatile var server: HttpWsServer? = null
    @Volatile var codec: Int = CODEC_OPUS
    @Volatile var sampleRate: Int = 48000
    @Volatile var channels: Int = 2
    @Volatile var latencyMs: Int = 600
    @Volatile var preSkipMs: Double = 0.0
    @Volatile var statusJson: String = status(capturing = false, silent = false, message = "En attente")

    private var seq = 0L

    fun helloJson(): String =
        """{"t":"hello","codec":"${if (codec == CODEC_OPUS) "opus" else "pcm"}","sampleRate":$sampleRate,"channels":$channels,""" +
            """"latencyMs":$latencyMs,"preSkipMs":$preSkipMs,"serverTime":${nowMs()}}"""

    fun helloJson(client: HttpWsServer.Client): String = helloJson().dropLast(1) + ""","id":${client.id}}"""

    /** Ordre de synchro pour un auditeur : correction auto, sonde de mesure, écart mesuré. */
    fun sendOffset(client: HttpWsServer.Client, auto: Double, probe: Double, measured: Double?, status: String) {
        client.autoMs = auto
        client.measuredMs = measured
        client.syncStatus = status
        client.sendText(
            """{"t":"offset","auto":${fmt(auto)},"probe":${fmt(probe)},"measured":${measured?.let { fmt(it) } ?: "null"},"status":"${escape(status)}"}"""
        )
    }

    fun configJson(): String = """{"t":"config","latencyMs":$latencyMs}"""

    fun setLatency(ms: Int) {
        latencyMs = ms
        server?.broadcastText(configJson())
    }

    fun setStatus(capturing: Boolean, silent: Boolean, message: String) {
        val s = status(capturing, silent, message)
        if (s != statusJson) {
            statusJson = s
            server?.broadcastText(s)
        }
    }

    /** Reconfigure le flux (codec/format) et prévient les pages déjà connectées. */
    fun announceFormat() {
        server?.broadcastText(helloJson())
    }

    fun publishAudio(timestampMs: Double, payload: ByteArray, payloadOffset: Int = 0, payloadLength: Int = payload.size) {
        val srv = server ?: return
        if (srv.clientCount == 0) { seq++; return }
        srv.broadcastBinary(buildPacket(codec, seq++, timestampMs, payload, payloadOffset, payloadLength))
    }

    override fun onOpen(client: HttpWsServer.Client) {
        client.sendText(helloJson(client))
        client.sendText(statusJson)
    }

    override fun onText(client: HttpWsServer.Client, text: String) {
        val recv = nowMs()
        when (field(text, "t")) {
            "ping" -> {
                val c = field(text, "c") ?: return
                client.sendText("""{"t":"pong","c":$c,"s":$recv,"r":$recv}""")
            }
            "ready" -> {
                field(text, "manual")?.toDoubleOrNull()?.let { client.manualMs = it.coerceIn(-1000.0, 1000.0) }
                field(text, "auto")?.toDoubleOrNull()?.let { if (client.autoMs == null) client.autoMs = it.coerceIn(-1000.0, 1000.0) }
                field(text, "ua")?.let { client.userAgent = it }
                client.ready = true
            }
            "pause" -> client.ready = false
            "stats" -> {
                client.lastStats = text
                field(text, "manual")?.toDoubleOrNull()?.let { client.manualMs = it.coerceIn(-1000.0, 1000.0) }
            }
        }
    }

    override fun onClose(client: HttpWsServer.Client) {}

    companion object {
        const val CODEC_OPUS = 1
        const val CODEC_PCM16 = 2
        const val HEADER_SIZE = 14

        fun buildPacket(codec: Int, seq: Long, timestampMs: Double, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
            val bb = ByteBuffer.allocate(HEADER_SIZE + len).order(ByteOrder.BIG_ENDIAN)
            bb.put(1).put(codec.toByte()).putInt((seq and 0xFFFFFFFFL).toInt()).putDouble(timestampMs)
            bb.put(payload, off, len)
            return bb.array()
        }

        fun status(capturing: Boolean, silent: Boolean, message: String): String =
            """{"t":"status","capturing":$capturing,"silent":$silent,"message":"${escape(message)}"}"""

        fun fmt(v: Double): String = if (v.isNaN() || v.isInfinite()) "0" else String.format(java.util.Locale.ROOT, "%.3f", v)

        fun escape(s: String): String = buildString {
            for (ch in s) when (ch) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }

        private val stringField = { name: String -> Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"") }
        private val numberField = { name: String -> Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(-?[0-9.eE+-]+)") }

        /** Extraction minimale d'un champ JSON plat (chaîne ou nombre). */
        fun field(json: String, name: String): String? =
            stringField(name).find(json)?.groupValues?.get(1)
                ?: numberField(name).find(json)?.groupValues?.get(1)
    }
}
