package fr.musico

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import fr.musico.server.StreamHub
import kotlin.math.abs
import kotlin.math.max

/**
 * Capture le son joué par les autres applis (AudioPlaybackCapture), l'horodate avec l'horloge
 * de l'émetteur, l'encode en Opus (MediaCodec) et le publie vers les auditeurs.
 * Le PCM brut est aussi transmis au lecteur local (synchro parfaite sur le téléphone émetteur).
 */
class CaptureEngine(
    private val projection: MediaProjection,
    private val hub: StreamHub,
    private val localPlayer: () -> LocalPlayer?,
    private val isMusicActive: () -> Boolean,
) {
    companion object {
        const val TAG = "MusicoCapture"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAME_MS = 20
        const val FRAMES_PER_CHUNK = SAMPLE_RATE * FRAME_MS / 1000 // 960
        const val BYTES_PER_CHUNK = FRAMES_PER_CHUNK * CHANNELS * 2 // 3840
        const val SILENCE_ALERT_MS = 3000.0
        const val OPUS_BITRATE = 128_000

        fun nowMs(): Double = System.nanoTime() / 1_000_000.0
    }

    @Volatile var level: Float = 0f; private set
    @Volatile var silent: Boolean = false; private set
    @Volatile var codecName: String = "?"; private set
    @Volatile var error: String? = null; private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ runLoop() }, "musico-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Ne jamais se capturer soi-même (sinon boucle avec la relecture locale)
            .excludeUid(Process.myUid())
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf * 2, BYTES_PER_CHUNK * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun buildOpusEncoder(): MediaCodec? = try {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BYTES_PER_CHUNK * 2)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        codec
    } catch (e: Exception) {
        Log.w(TAG, "Encodeur Opus indisponible, repli en PCM", e)
        null
    }

    private fun runLoop() {
        val record = try { buildRecord() } catch (e: Exception) {
            error = "Impossible de démarrer la capture : ${e.message}"
            Log.e(TAG, "AudioRecord", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            error = "Capture audio refusée par le système"
            record.release()
            return
        }
        val encoder = buildOpusEncoder()
        hub.codec = if (encoder != null) StreamHub.CODEC_OPUS else StreamHub.CODEC_PCM16
        hub.sampleRate = SAMPLE_RATE
        hub.channels = CHANNELS
        hub.preSkipMs = if (encoder != null) 6.5 else 0.0
        codecName = if (encoder != null) "Opus ${OPUS_BITRATE / 1000} kbit/s" else "PCM 16 bits (repli)"
        hub.announceFormat()

        val buf = ByteArray(BYTES_PER_CHUNK)
        val info = MediaCodec.BufferInfo()
        val ts = AudioTimestamp()
        var framesRead = 0L
        var baseMs = Double.NaN // horodatage de la trame 0
        var lastSoundMs = nowMs()
        var chunkCount = 0L

        record.startRecording()
        try {
            while (running) {
                var off = 0
                while (off < BYTES_PER_CHUNK && running) {
                    val n = record.read(buf, off, BYTES_PER_CHUNK - off, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    off += n
                }
                if (!running) break
                val now = nowMs()

                // --- Horodatage : instant de capture de la 1re trame du bloc (horloge monotone) ---
                var chunkTs = Double.NaN
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    chunkTs = ts.nanoTime / 1e6 + (framesRead - ts.framePosition) * 1000.0 / SAMPLE_RATE
                }
                if (chunkTs.isNaN()) chunkTs = now - FRAME_MS
                val expected = baseMs + framesRead * 1000.0 / SAMPLE_RATE
                if (baseMs.isNaN() || abs(expected - chunkTs) > 20.0) {
                    baseMs = chunkTs - framesRead * 1000.0 / SAMPLE_RATE // (re)calage
                }
                val chunkMs = baseMs + framesRead * 1000.0 / SAMPLE_RATE
                framesRead += FRAMES_PER_CHUNK

                // --- Niveau et détection de silence (capture bloquée par l'appli source ?) ---
                var peak = 0
                var i = 0
                while (i < BYTES_PER_CHUNK) {
                    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    val a = abs(s.toShort().toInt())
                    if (a > peak) peak = a
                    i += 2
                }
                level = peak / 32768f
                if (peak > 1) lastSoundMs = now
                val silentFor = now - lastSoundMs
                silent = silentFor > SILENCE_ALERT_MS
                if (chunkCount++ % 25 == 0L) {
                    val msg = when {
                        !silent -> "Diffusion en cours"
                        isMusicActive() -> "Aucun son capté alors qu'une appli joue de la musique : elle bloque probablement la capture (protection anti-copie)."
                        else -> "Silence : lance la musique (Deezer, YouTube…)"
                    }
                    hub.setStatus(capturing = true, silent = silent, message = msg)
                }

                // --- Relecture locale (synchro parfaite côté émetteur) ---
                localPlayer()?.enqueue(chunkMs, buf.copyOf())

                // --- Encodage + diffusion ---
                if (encoder != null) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = encoder.getInputBuffer(inIdx)!!
                        ib.clear(); ib.put(buf)
                        encoder.queueInputBuffer(inIdx, 0, BYTES_PER_CHUNK, (chunkMs * 1000).toLong(), 0)
                    }
                    while (true) {
                        val outIdx = encoder.dequeueOutputBuffer(info, 0)
                        if (outIdx < 0) break
                        val ob = encoder.getOutputBuffer(outIdx)!!
                        val data = ByteArray(info.size)
                        ob.position(info.offset); ob.get(data)
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            parsePreSkip(data)?.let { hub.preSkipMs = it; hub.announceFormat() }
                            continue
                        }
                        if (data.isNotEmpty()) hub.publishAudio(info.presentationTimeUs / 1000.0, data)
                    }
                } else {
                    hub.publishAudio(chunkMs, buf)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boucle de capture", e)
            error = "Capture interrompue : ${e.message}"
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { encoder?.stop() } catch (_: Exception) {}
            encoder?.release()
            hub.setStatus(capturing = false, silent = false, message = "Diffusion arrêtée")
        }
    }

    /** Lit le pré-skip (délai de l'encodeur) dans l'en-tête OpusHead, en ms. */
    private fun parsePreSkip(csd: ByteArray): Double? {
        val magic = "OpusHead".toByteArray()
        outer@ for (i in 0..csd.size - 12) {
            for (j in magic.indices) if (csd[i + j] != magic[j]) continue@outer
            val preSkip = (csd[i + 10].toInt() and 0xFF) or ((csd[i + 11].toInt() and 0xFF) shl 8)
            return preSkip * 1000.0 / SAMPLE_RATE
        }
        return null
    }
}
