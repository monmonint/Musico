package fr.musico.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Simulation de pièce en boucle fermée : musique synthétique, micro de l'émetteur qui entend
 * son propre haut-parleur (fort) + ses échos + 1 ou 2 auditeurs lointains (-28/-31 dB) + bruit.
 * Les auditeurs appliquent les ordres (auto + sonde) progressivement (10 ms/s), comme la page.
 */
class AcousticAlignerTest {
    private val fs = 16000

    private fun music(seconds: Int, seed: Long): FloatArray {
        val rnd = Random(seed)
        val n = seconds * fs
        val out = FloatArray(n)
        val noteLen = fs / 4
        var i = 0
        while (i < n) {
            val f0 = 110.0 * Math.pow(2.0, rnd.nextInt(36) / 12.0)
            val amp = 0.2 + 0.3 * rnd.nextDouble()
            for (k in 0 until noteLen) {
                if (i + k >= n) break
                val env = exp(-3.0 * k / noteLen)
                var v = 0.0
                for (h in 1..6) v += sin(2 * PI * f0 * h * (i + k) / fs) / h
                out[i + k] += (amp * env * v).toFloat()
            }
            // percussions : bruit bref
            if (rnd.nextInt(2) == 0) for (k in 0 until 800) if (i + k < n) out[i + k] += ((rnd.nextDouble() - 0.5) * 0.6 * exp(-k / 150.0)).toFloat()
            i += noteLen
        }
        return out
    }

    private class SimListener(val gain: Double, var trueError: Double) {
        var applied = 0.0 // auto + sonde réellement appliqués
        var target = 0.0
        var auto = 0.0
        fun update(dtMs: Double) {
            val maxStep = 10.0 * dtMs / 1000 // 1 % de vitesse = 10 ms/s
            applied += (target - applied).coerceIn(-maxStep, maxStep)
        }
        val delayOffset get() = trueError + applied
        val residual get() = trueError + auto // écart hors sonde
    }

    private fun simulate(listeners: List<SimListener>, seconds: Int, joinAt: List<Double> = listeners.map { 0.0 }): AcousticAligner {
        val sig = music(seconds + 5, 7)
        val rnd = Random(3)
        val aligner = AcousticAligner(fs = fs)
        val latency = 600.0
        val selfExtra = 14.0 // latence micro/sortie inconnue de l'émetteur
        aligner.setExpectedSelfDelay(latency)
        val selfD = latency + selfExtra
        val reflections = listOf(2.5 to 0.35, 6.0 to 0.2, 13.0 to 0.12, 21.0 to 0.06, 34.0 to 0.04)
        val chunk = fs / 50 // 20 ms
        val t0 = 100_000.0
        val sigStartTs = t0 - 2000.0 // horodatage de sig[0]
        fun sigAt(tsMs: Double): Float {
            val idx = ((tsMs - sigStartTs) * fs / 1000).roundToInt()
            return if (idx in sig.indices) sig[idx] else 0f
        }
        var now = t0
        val refChunk = FloatArray(chunk); val micChunk = FloatArray(chunk)
        var refTs = t0 - 1500.0
        var lastStep = now
        while (now < t0 + seconds * 1000) {
            // référence (capture) : disponible ~20 ms après
            while (refTs + 20 <= now - 20) {
                for (k in 0 until chunk) refChunk[k] = sigAt(refTs + k * 1000.0 / fs)
                aligner.reference.write(refTs, refChunk)
                refTs += 20.0
            }
            // micro : bloc [now-20, now)
            val m0 = now - 20.0
            for (k in 0 until chunk) {
                val t = m0 + k * 1000.0 / fs
                var v = sigAt(t - selfD).toDouble()
                for ((d, g) in reflections) v += g * sigAt(t - selfD - d)
                listeners.forEachIndexed { i, l ->
                    if (now - t0 >= joinAt[i] * 1000) {
                        v += l.gain * sigAt(t - selfD - l.delayOffset)
                        v += l.gain * 0.3 * sigAt(t - selfD - l.delayOffset - 4.0)
                    }
                }
                v += rnd.nextGaussian() * 0.003
                micChunk[k] = v.toFloat()
            }
            aligner.mic.write(m0, micChunk)
            listeners.forEach { it.update(20.0) }
            if (now - lastStep >= 250) {
                lastStep = now
                val present = HashMap<Int, Pair<Double, Double?>>()
                listeners.forEachIndexed { i, _ -> if (now - t0 >= joinAt[i] * 1000) present[i + 1] = 0.0 to null }
                aligner.setListeners(present)
                for (c in aligner.step(now)) { listeners[c.id - 1].target = c.auto + c.probe; listeners[c.id - 1].auto = c.auto }
            }
            now += 20.0
        }
        return aligner
    }

    @Test fun selfPeakFound() {
        val a = simulate(listOf(SimListener(0.04, 30.0)), 20)
        val self = a.selfDelayMs
        assertNotNull(self)
        assertEquals(614.0, self!!, 0.3)
    }

    @Test fun singleListenerConvergesToZero() {
        val l = SimListener(0.04, 37.3)
        val a = simulate(listOf(l), 150)
        val st = a.listeners[1]!!
        println("1 auditeur : erreur finale ${"%.2f".format(l.residual)} ms, mesuré ${st.measured}, z=${st.confidence}, ${st.status}")
        assertTrue("erreur résiduelle ${l.residual}", abs(l.residual) < 1.0)
        assertNotNull(st.measured)
        assertTrue(abs(st.measured!!) < 2.0)
    }

    @Test fun listenerAlreadyAlignedIsMeasuredNearZero() {
        val l = SimListener(0.04, 0.0)
        val a = simulate(listOf(l), 90)
        val st = a.listeners[1]!!
        println("aligné : mesuré=${st.measured} z=${st.confidence} ${st.status}")
        assertNotNull("doit être mesurable même aligné (la sonde le détache du pic principal)", st.measured)
        assertTrue(abs(st.measured!!) < 1.0)
        assertTrue(abs(l.residual) < 1.0)
    }

    @Test fun twoListenersEarlyAndLateBothConverge() {
        val l1 = SimListener(0.04, 42.0)
        val l2 = SimListener(0.028, -55.0)
        val a = simulate(listOf(l1, l2), 240, joinAt = listOf(0.0, 20.0))
        a.listeners.values.forEach { println("  #${it.id} mesuré=${it.measured} z=${it.confidence} ${it.status}") }
        println("2 auditeurs : erreurs finales ${"%.2f".format(l1.residual)} / ${"%.2f".format(l2.residual)} ms")
        assertTrue("auditeur 1 : ${l1.residual}", abs(l1.residual) < 1.5)
        assertTrue("auditeur 2 : ${l2.residual}", abs(l2.residual) < 1.5)
    }

    @Test fun noListenerAudibleMeansNoCorrection() {
        val l = SimListener(0.0, 25.0) // l'auditeur est hors de portée du micro
        val a = simulate(listOf(l), 120)
        val st = a.listeners[1]!!
        println("inaudible : auto=${st.auto} z=${st.confidence} ${st.status}")
        assertEquals(0.0, st.auto, 1e-9) // jamais de correction sur du bruit
        assertTrue(st.status, st.status.startsWith("Non détecté") || st.status.startsWith("Mesure"))
    }

    @Test fun gccFindsKnownDelay() {
        val sig = music(3, 11)
        val g = Gcc(fs, fs, 801)
        val mic = FloatArray(fs) { sig[it + 8000 - 123] } // délai de 123 échantillons dans la plage
        val ref = FloatArray(g.refLen) { sig[it + 8000 - 400] }
        val out = FloatArray(801)
        g.correlate(mic, ref, out)
        var bi = 0; for (i in out.indices) if (out[i] > out[bi]) bi = i
        assertEquals(400 - 123, bi)
    }
}
