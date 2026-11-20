package fr.musico

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Rejoue le son capturé sur le téléphone émetteur avec la même latence cible que les
 * auditeurs : c'est ce qui permet la synchro parfaite une fois le son de Deezer coupé.
 *
 * Le volume média étant à 0, on joue sur un autre flux (alarme par défaut) dont le volume
 * est indépendant. Ce flux n'est pas capturable (et notre UID est exclu de la capture),
 * donc pas de boucle.
 */
class LocalPlayer(usage: Int) {
    companion object {
        const val TAG = "MusicoLocal"
        const val SR = CaptureEngine.SAMPLE_RATE
        const val CORRECTION_THRESHOLD_MS = 10.0
    }

    @Volatile var targetLatencyMs: Int = 300
    @Volatile var offsetMs: Int = 0
    @Volatile var lastErrorMs: Double = 0.0; private set

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
        track.play()
        thread = Thread({ loop() }, "musico-local").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun setVolume(v: Float) { track.setVolume(v.coerceIn(0f, 1f)) }

    fun enqueue(tsMs: Double, pcm: ByteArray) {
        if (!queue.offer(tsMs to pcm)) { queue.poll(); queue.offer(tsMs to pcm) }
    }

    fun release() {
        running = false
        thread.join(500)
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private fun loop() {
        val ts = AudioTimestamp()
        var written = 0L // trames écrites
        val silence = ByteArray(CaptureEngine.BYTES_PER_CHUNK)
        try {
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
                if (err < -CORRECTION_THRESHOLD_MS) {
                    // En avance : on insère du silence
                    var frames = (min(-err, 2000.0) * SR / 1000).toLong()
                    while (frames > 0 && running) {
                        val f = min(frames, CaptureEngine.FRAMES_PER_CHUNK.toLong()).toInt()
                        track.write(silence, 0, f * 4, AudioTrack.WRITE_BLOCKING)
                        written += f; frames -= f
                    }
                } else if (err > CORRECTION_THRESHOLD_MS) {
                    // En retard : on saute le début du bloc
                    val skipFrames = min((err * SR / 1000).toInt(), pcm.size / 4)
                    start = skipFrames * 4
                }
                if (start < pcm.size) {
                    val n = track.write(pcm, start, pcm.size - start, AudioTrack.WRITE_BLOCKING)
                    if (n > 0) written += n / 4
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lecture locale", e)
        }
    }
}
