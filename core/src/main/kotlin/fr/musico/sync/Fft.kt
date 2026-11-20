package fr.musico.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complexe radix-2 en place (taille puissance de 2), tables précalculées. */
class Fft(val n: Int) {
    init { require(n >= 2 && (n and (n - 1)) == 0) { "taille non puissance de 2: $n" } }
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    companion object {
        fun nextPow2(v: Int): Int { var p = 1; while (p < v) p = p shl 1; return p }
    }
}
