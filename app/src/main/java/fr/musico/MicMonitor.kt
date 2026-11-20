package fr.musico

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import fr.musico.sync.AcousticAligner
import fr.musico.sync.Decimator
import kotlin.math.abs
import kotlin.math.max

/**
 * Écoute la pièce avec le micro de l'émetteur pour la mesure acoustique de synchro.
 * Rien n'est enregistré ni envoyé : le son micro (sous-échantillonné à 16 kHz) ne sert qu'à
 * l'intercorrélation locale, dans un tampon de quelques secondes.
 * Toute erreur (permission, micro occupé…) est rattrapée : l'appli continue en mode manuel.
 */
class MicMonitor(private val context: Context, private val aligner: AcousticAligner) {
    companion object {
        const val TAG = "MusicoMic"
        const val SR = 48000
        const val FRAMES = 960
    }

    @Volatile var level = 0f; private set
    @Volatile var error: String? = null; private set
    @Volatile var silent = false; private set
    @Volatile var active = false; private set
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Le service n'a pas pu obtenir le type « micro » : mesure impossible en arrière-plan. */
    fun markUnavailable() { error = "Micro non autorisé pour le service" }

    fun start() {
        if (running || error != null) return
        running = true; error = null
        thread = Thread({
            try { loop() } catch (t: Throwable) {
                Log.e(TAG, "micro", t)
                error = "Micro indisponible : ${t.message ?: t.javaClass.simpleName}"
            } finally { active = false }
        }, "musico-mic").apply { start() }
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Throwable) {}
        try { thread?.join(1000) } catch (_: Throwable) {}
        thread = null
        active = false
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // UNPROCESSED : pas de contrôle de gain ni de réduction de bruit (meilleur pour mesurer)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessed) listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else listOf(MediaRecorder.AudioSource.MIC)
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var record: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(max(minBuf * 2, FRAMES * 2 * 8))
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) { record = r; break }
                r.release()
            } catch (t: Throwable) { Log.w(TAG, "source $src", t) }
        }
        if (record == null) { error = "Micro indisponible (permission refusée ou micro occupé)"; return }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error = "Micro occupé par une autre appli"; return
            }
            active = true
            val buf = ByteArray(FRAMES * 2)
            val out = FloatArray(FRAMES / 3 + 4)
            val dec = Decimator(3, 1)
            val ts = AudioTimestamp()
            var framesRead = 0L
            var baseMs = Double.NaN
            var lastSound = CaptureEngine.nowMs()
            while (running) {
                var off = 0
                while (off < buf.size && running) {
                    val n = record.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
                    if (n < 0) { error = "Lecture micro impossible ($n)"; return }
                    off += n
                }
                if (!running) break
                val now = CaptureEngine.nowMs()
                var chunkTs = Double.NaN
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    chunkTs = ts.nanoTime / 1e6 + (framesRead - ts.framePosition) * 1000.0 / SR
                }
                if (chunkTs.isNaN()) chunkTs = now - FRAMES * 1000.0 / SR
                val expected = baseMs + framesRead * 1000.0 / SR
                if (baseMs.isNaN() || abs(expected - chunkTs) > 20.0) baseMs = chunkTs - framesRead * 1000.0 / SR
                val t0 = baseMs + framesRead * 1000.0 / SR
                framesRead += FRAMES
                var peak = 0
                var i = 0
                while (i < buf.size) {
                    val a = abs(((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt())
                    if (a > peak) peak = a
                    i += 2
                }
                level = peak / 32768f
                if (peak > 2) lastSound = now
                silent = now - lastSound > 3000
                val n = dec.process(buf, buf.size, out)
                aligner.mic.write(t0, out, 0, n)
            }
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
        }
    }
}
