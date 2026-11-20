package fr.musico

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rejoue le son capturé sur le téléphone émetteur avec la même latence cible que les
 * auditeurs : c'est la « synchro parfaite » une fois le son direct de Deezer coupé.
 *
 * Joue sur un autre flux que le média (alarme par défaut), non capturable ; notre UID est
 * de toute façon exclu de la capture : pas de boucle.
 *
 * Capture et sortie sont cadencées par la même horloge audio du téléphone : l'écart reste
 * stable, donc on ne corrige que les gros écarts (> 30 ms, ex. au démarrage), avec un fondu
 * pour éviter les clics. Ce téléphone sert aussi de référence à la mesure acoustique : il
 * doit rester immobile dans le temps.
 */
class LocalPlayer(usage: Int) {
    companion object {
        const val TAG = "MusicoLocal"
        const val SR = CaptureEngine.SAMPLE_RATE
        const val CORRECTION_THRESHOLD_MS = 30.0
        const val FADE_FRAMES = 240 // 5 ms
    }

    @Volatile var targetLatencyMs: Int = 600
    @Volatile var offsetMs: Int = 0
    @Volatile var lastErrorMs: Double = 0.0; private set
    @Volatile var corrections = 0; private set

    private val queue = ArrayBlockingQueue<Pair<Double, ByteArray>>(150)
    @Volatile private var running = true
    private val track: AudioTrack
    private val thread: Thread

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minBuf * 2, CaptureEngine.BYTES_PER_CHUNK * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Sortie audio locale indisponible")
        }
        track.play()
        thread = Thread({
            try { loop() } catch (t: Throwable) { Log.e(TAG, "Lecture locale", t) }
        }, "musico-local").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun setVolume(v: Float) { try { track.setVolume(v.coerceIn(0f, 1f)) } catch (_: Throwable) {} }

    fun enqueue(tsMs: Double, pcm: ByteArray) {
        if (!queue.offer(tsMs to pcm)) { queue.poll(); queue.offer(tsMs to pcm) }
    }

    fun release() {
        running = false
        try { thread.join(500) } catch (_: Throwable) {}
        try { track.stop() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
    }

    private fun fade(pcm: ByteArray, startByte: Int, fadeIn: Boolean) {
        val frames = min(FADE_FRAMES, (pcm.size - startByte) / 4)
        for (f in 0 until frames) {
            val g = if (fadeIn) f.toFloat() / frames else 1f - f.toFloat() / frames
            for (c in 0..1) {
                val i = startByte + f * 4 + c * 2
                val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                val v = (s * g).toInt()
                pcm[i] = v.toByte(); pcm[i + 1] = (v shr 8).toByte()
            }
        }
    }

    private fun loop() {
        val ts = AudioTimestamp()
        var written = 0L // trames écrites
        val silence = ByteArray(CaptureEngine.BYTES_PER_CHUNK)
        var needFadeIn = true
        while (running) {
            val (chunkTs, pcm) = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val desired = chunkTs + targetLatencyMs + offsetMs
            val now = CaptureEngine.nowMs()
            // Instant prévu de sortie de la prochaine trame écrite
            val predicted = if (track.getTimestamp(ts) && ts.framePosition > 0) {
                ts.nanoTime / 1e6 + (written - ts.framePosition) * 1000.0 / SR
            } else {
                now + (written - track.playbackHeadPosition.toLong()) * 1000.0 / SR
            }
            val err = predicted - desired // >0 : en retard ; <0 : en avance
            lastErrorMs = err
            var start = 0
            if (err < -CORRECTION_THRESHOLD_MS || (written == 0L && err < -1)) {
                // En avance : on insère du silence (au démarrage ou après un gros écart)
                var frames = (min(-err, 2000.0) * SR / 1000).toLong()
                while (frames > 0 && running) {
                    val f = min(frames, CaptureEngine.FRAMES_PER_CHUNK.toLong()).toInt()
                    track.write(silence, 0, f * 4, AudioTrack.WRITE_BLOCKING)
                    written += f; frames -= f
                }
                if (written > 0 && abs(err) > CORRECTION_THRESHOLD_MS) corrections++
                needFadeIn = true
            } else if (err > CORRECTION_THRESHOLD_MS) {
                // En retard : on saute le début du bloc
                val skipFrames = min((err * SR / 1000).toInt(), pcm.size / 4)
                start = skipFrames * 4
                corrections++
                needFadeIn = true
            }
            if (start < pcm.size) {
                if (needFadeIn) { fade(pcm, start, true); needFadeIn = false }
                val n = track.write(pcm, start, pcm.size - start, AudioTrack.WRITE_BLOCKING)
                if (n > 0) written += n / 4
                if (n < 0) { Log.w(TAG, "AudioTrack.write=$n"); return }
            }
        }
    }
}
