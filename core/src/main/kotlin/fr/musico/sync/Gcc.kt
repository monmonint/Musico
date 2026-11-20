package fr.musico.sync

import kotlin.math.sqrt

/**
 * Intercorrélation généralisée avec pondération PHAT (GCC-PHAT), limitée à une bande de
 * fréquences. Pour un micro m (M échantillons) et une référence r (M + nLag - 1 échantillons) :
 * c[s] = Σ m[n]·r[n+s], s = 0..nLag-1. PHAT « blanchit » la musique : les pics deviennent
 * étroits (≈ 1 échantillon), ce qui permet de séparer des trajets décalés de quelques ms.
 */
class Gcc(private val fs: Int, private val micLen: Int, val nLag: Int, fLo: Double = 200.0, fHi: Double = 6000.0) {
    private val n = Fft.nextPow2(micLen + micLen + nLag)
    private val fft = Fft(n)
    private val aRe = DoubleArray(n); private val aIm = DoubleArray(n)
    private val bRe = DoubleArray(n); private val bIm = DoubleArray(n)
    private val kLo = (fLo * n / fs).toInt().coerceAtLeast(1)
    private val kHi = (fHi * n / fs).toInt().coerceAtMost(n / 2 - 1)
    val refLen: Int get() = micLen + nLag - 1

    fun correlate(mic: FloatArray, ref: FloatArray, out: FloatArray) {
        aRe.fill(0.0); aIm.fill(0.0); bRe.fill(0.0); bIm.fill(0.0)
        for (i in 0 until micLen) aRe[i] = mic[i].toDouble()
        for (i in 0 until refLen) bRe[i] = ref[i].toDouble()
        fft.transform(aRe, aIm, false)
        fft.transform(bRe, bIm, false)
        // X = conj(A)·B, normalisé (PHAT), hors bande = 0 ; spectre hermitien conservé
        for (k in 0..n / 2) {
            if (k < kLo || k > kHi) {
                aRe[k] = 0.0; aIm[k] = 0.0
                if (k in 1 until n / 2) { aRe[n - k] = 0.0; aIm[n - k] = 0.0 }
                continue
            }
            val xr = aRe[k] * bRe[k] + aIm[k] * bIm[k]
            val xi = aRe[k] * bIm[k] - aIm[k] * bRe[k]
            val mag = sqrt(xr * xr + xi * xi) + 1e-12
            aRe[k] = xr / mag; aIm[k] = xi / mag
            if (k in 1 until n / 2) { aRe[n - k] = aRe[k]; aIm[n - k] = -aIm[k] }
        }
        fft.transform(aRe, aIm, true)
        for (s in 0 until nLag) out[s] = aRe[s].toFloat()
    }
}
