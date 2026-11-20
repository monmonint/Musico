package fr.musico.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {
    private fun stereoPcm(freq: Double, frames: Int, amp: Double = 0.5): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (amp * 32767 * sin(2 * PI * freq * i / 48000)).toInt()
            for (c in 0..1) { b[i * 4 + c * 2] = v.toByte(); b[i * 4 + c * 2 + 1] = (v shr 8).toByte() }
        }
        return b
    }

    private fun rms(x: FloatArray, from: Int, n: Int) = sqrt((from until from + n).sumOf { (x[it] * x[it]).toDouble() } / n)

    @Test fun keepsPassbandAndRejectsAliasesAcrossBlocks() {
        val pcm = stereoPcm(1000.0, 48000)
        val d = Decimator(); val out = FloatArray(16000)
        var w = 0
        for (k in 0 until 50) { // blocs de 960 trames, comme l'appli
            val tmp = FloatArray(320)
            val c = d.process(pcm.copyOfRange(k * 3840, (k + 1) * 3840), 3840, tmp)
            assertEquals(320, c)
            System.arraycopy(tmp, 0, out, w, c); w += c
        }
        assertEquals(0.5 / sqrt(2.0), rms(out, 1000, 10000), 0.02)
        val d2 = Decimator(); val out2 = FloatArray(16000)
        d2.process(stereoPcm(11000.0, 48000), 48000 * 4, out2) // au-dessus de 8 kHz : atténué
        assertTrue("aliasing ${rms(out2, 1000, 10000)}", rms(out2, 1000, 10000) < 0.02)
    }
}
