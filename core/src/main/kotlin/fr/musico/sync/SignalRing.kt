package fr.musico.sync

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tampon circulaire d'un signal mono horodaté (horloge monotone de l'émetteur, en ms).
 * Les blocs doivent être continus ; un saut d'horodatage > 3 ms remet le tampon à zéro.
 */
class SignalRing(val fs: Int, seconds: Int) {
    private val cap = fs * seconds
    private val buf = FloatArray(cap)
    private var baseTs = Double.NaN // horodatage de l'échantillon d'indice absolu 0
    private var count = 0L          // nombre total d'échantillons écrits

    @Synchronized fun write(tsMs: Double, data: FloatArray, off: Int = 0, len: Int = data.size) {
        if (len <= 0) return
        val expected = baseTs + count * 1000.0 / fs
        if (baseTs.isNaN() || abs(expected - tsMs) > 3.0) { baseTs = tsMs; count = 0 }
        for (i in 0 until len) buf[((count + i) % cap).toInt()] = data[off + i]
        count += len
    }

    /** Horodatage juste après le dernier échantillon disponible (NaN si vide). */
    @Synchronized fun endTs(): Double = if (baseTs.isNaN()) Double.NaN else baseTs + count * 1000.0 / fs

    /** Copie n échantillons à partir de startTs ; false si indisponibles. */
    @Synchronized fun read(startTs: Double, out: FloatArray, n: Int = out.size): Boolean {
        if (baseTs.isNaN()) return false
        val idx = ((startTs - baseTs) * fs / 1000.0).roundToLong()
        if (idx < count - cap || idx < 0 || idx + n > count) return false
        for (i in 0 until n) out[i] = buf[((idx + i) % cap).toInt()]
        return true
    }

    @Synchronized fun clear() { baseTs = Double.NaN; count = 0 }
}
