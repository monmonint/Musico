package fr.musico.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * PCM 16 bits entrelacé (1 ou 2 canaux) -> mono flottant sous-échantillonné d'un facteur entier
 * (48 kHz -> 16 kHz), avec filtre passe-bas FIR (sinc fenêtré) et état conservé entre blocs.
 */
class Decimator(private val factor: Int = 3, private val channels: Int = 2, taps: Int = 63) {
    private val h: FloatArray
    private val hist: FloatArray
    private var phase = 0

    init {
        val fc = 0.45 / factor // fréquence de coupure normalisée (cycles/échantillon)
        val m = taps - 1
        val raw = DoubleArray(taps) { n ->
            val x = n - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            sinc * (0.54 - 0.46 * cos(2 * PI * n / m))
        }
        val sum = raw.sum()
        h = FloatArray(taps) { (raw[it] / sum).toFloat() }
        hist = FloatArray(2 * taps)
    }
    private var pos = 0

    /** Retourne le nombre d'échantillons écrits dans out. */
    fun process(pcm: ByteArray, len: Int, out: FloatArray): Int {
        var o = 0
        val frameBytes = 2 * channels
        var i = 0
        val taps = h.size
        while (i + frameBytes <= len) {
            var v = 0f
            for (c in 0 until channels) {
                val b = i + 2 * c
                v += ((pcm[b].toInt() and 0xFF) or (pcm[b + 1].toInt() shl 8)).toShort() / 32768f
            }
            v /= channels
            // tampon circulaire doublé : hist[pos..pos+taps) = derniers échantillons, du plus ancien au plus récent
            hist[pos] = v; hist[pos + taps] = v
            pos = (pos + 1) % taps
            if (++phase >= factor) {
                phase = 0
                var acc = 0f
                for (k in 0 until taps) acc += h[k] * hist[pos + taps - 1 - k]
                if (o < out.size) out[o++] = acc
            }
            i += frameBytes
        }
        return o
    }
}
