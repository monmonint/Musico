package fr.musico.sync

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mesure acoustique automatique de l'écart réel entre le téléphone émetteur et chaque auditeur,
 * SANS son ajouté : le micro de l'émetteur écoute la pièce et on l'intercorrèle (GCC-PHAT)
 * avec la musique diffusée, qu'on connaît exactement.
 *
 * Problème : le haut-parleur de l'émetteur, à quelques cm du micro, est 20 à 35 dB plus fort
 * qu'un auditeur à quelques mètres, et ses propres échos dans la pièce tombent dans la même
 * zone de délais. Solution : une fenêtre de mesure par auditeur avec une « sonde » temporelle.
 * Pendant la fenêtre de l'auditeur k, on décale très légèrement SA lecture de +P puis -P ms
 * (P = 3 ms, appliqué en douceur par variation de vitesse, inaudible). Seul son trajet bouge :
 * en soustrayant les intercorrélations moyennes des deux états, tout ce qui est fixe
 * (haut-parleur de l'émetteur, échos, autres auditeurs) s'annule, et il reste une paire +/-
 * centrée sur le délai de l'auditeur k. Le pic principal (somme) donne le délai de l'émetteur.
 * Écart mesuré = délai(k) - délai(émetteur), indépendant de la latence du micro.
 *
 * Classe pure (sans Android), pilotée par [step] ; non thread-safe sauf les entrées audio.
 */
class AcousticAligner(
    val fs: Int = 16000,
    private val probeMs: Double = 3.0,
    private val settleMs: Double = 3000.0,
    private val windowsPerState: Int = 4,
    private val halfRangeMs: Double = 250.0,
    private val zThreshold: Double = 7.0,
    private val maxCycles: Int = 6,
    private val maxStepMs: Double = 150.0,
) {
    class ListenerState(val id: Int) {
        var auto = 0.0            // correction automatique envoyée (ms, + = plus tard)
        var probe = 0.0           // sonde en cours (ms)
        var manual = 0.0          // décalage manuel réglé sur la page (cible)
        var measured: Double? = null // dernier écart mesuré (ms, + = auditeur en retard)
        var confidence = 0.0
        var measuredAt = Double.NaN
        var cycles = 0
        internal var candidate: Double? = null
        var status = "En attente de mesure"
        internal var accA: FloatArray? = null
        internal var accB: FloatArray? = null
        internal var sqA: FloatArray? = null
        internal var sqB: FloatArray? = null
        internal var nA = 0
        internal var nB = 0
    }

    data class Command(val id: Int, val auto: Double, val probe: Double, val measured: Double?, val confidence: Double, val status: String)

    private enum class Phase { IDLE, SETTLE_A, COLLECT_A, SETTLE_B, COLLECT_B }

    val reference = SignalRing(fs, 8)
    val mic = SignalRing(fs, 6)

    private val micLen = fs // fenêtres de 1 s
    private val nLag = (2 * halfRangeMs * fs / 1000).roundToInt() + 1
    private val gcc = Gcc(fs, micLen, nLag)
    private val micBuf = FloatArray(micLen)
    private val refBuf = FloatArray(gcc.refLen)
    private val curve = FloatArray(nLag)
    private val total = FloatArray(nLag)
    private var totalN = 0
    private var centerMs = Double.NaN // délai attendu de l'émetteur (latence + décalage local)

    val listeners = LinkedHashMap<Int, ListenerState>()
    private var phase = Phase.IDLE
    private var current: ListenerState? = null
    private var phaseStart = 0.0
    private var nextWindowTs = Double.NaN
    private var rr = 0
    private val pending = ArrayList<Command>()
    /** Ordres en attente (ex. retrait de sonde). */

    @Volatile var selfDelayMs: Double? = null; private set
    @Volatile var lastInfo: String = "Inactif"; private set
    val probeLagSamples: Int get() = (probeMs * fs / 1000).roundToInt()

    /** Liste des auditeurs à l'écoute (id -> décalage manuel, correction auto connue). */
    fun setListeners(present: Map<Int, Pair<Double, Double?>>) {
        var changed = false
        val gone = listeners.keys - present.keys
        for (id in gone) {
            listeners.remove(id); changed = true
            if (current?.id == id) { current = null; phase = Phase.IDLE }
        }
        for ((id, v) in present) {
            var st = listeners[id]
            if (st == null) {
                st = ListenerState(id).also { s -> v.second?.let { s.auto = it } }
                listeners[id] = st; changed = true
            }
            if (abs(st.manual - v.first) > 0.01) { st.manual = v.first; changed = true }
        }
        // Un auditeur arrive/part ou bouge son réglage : la scène acoustique change pendant la
        // fenêtre de mesure en cours -> on la recommence (sinon fausse détection).
        val cur = current
        if (changed && cur != null && phase != Phase.IDLE && listeners.containsKey(cur.id)) {
            resetAcc(cur); cur.cycles = 0
            cur.probe = probeMs; emit(cur)
            enter(Phase.SETTLE_A, lastNow)
        }
    }
    private var lastNow = 0.0

    /** Délai attendu de la sortie de l'émetteur : si change, les mesures en cours sont invalidées. */
    fun setExpectedSelfDelay(ms: Double) {
        if (centerMs.isNaN() || abs(ms - centerMs) > 0.5) {
            centerMs = ms
            resetAll("Réglage de latence modifié, nouvelle mesure")
        }
    }

    /** Remet toutes les corrections auto à 0 (les ordres sont envoyés par l'appelant). */
    fun resetCorrections() {
        resetAll("Corrections remises à 0")
        pending.clear()
        listeners.values.forEach { it.auto = 0.0; it.probe = 0.0; it.measured = null; it.candidate = null; it.status = "En attente de mesure" }
    }

    fun resetAll(reason: String) {
        total.fill(0f); totalN = 0
        listeners.values.forEach { resetAcc(it); it.cycles = 0 }
        current?.let { if (it.probe != 0.0) { it.probe = 0.0; emit(it) } }
        current = null; phase = Phase.IDLE
        lastInfo = reason
    }

    private fun resetAcc(s: ListenerState) { s.accA = null; s.accB = null; s.sqA = null; s.sqB = null; s.nA = 0; s.nB = 0 }

    private fun emit(s: ListenerState) {
        pending += Command(s.id, s.auto, s.probe, s.measured, s.confidence, s.status)
    }

    /** À appeler régulièrement (ex. 4 fois/s). Retourne les ordres à envoyer aux auditeurs. */
    fun step(nowMs: Double): List<Command> {
        lastNow = nowMs
        if (centerMs.isNaN()) return drain()
        when (phase) {
            Phase.IDLE -> startNext(nowMs)
            Phase.SETTLE_A -> if (nowMs - phaseStart >= settleMs) enter(Phase.COLLECT_A, nowMs)
            Phase.SETTLE_B -> if (nowMs - phaseStart >= settleMs) enter(Phase.COLLECT_B, nowMs)
            Phase.COLLECT_A, Phase.COLLECT_B -> {
                processWindows()
                val s = current
                if (s == null) phase = Phase.IDLE
                else if (phase == Phase.COLLECT_A && s.nA >= windowsPerState * (s.cycles + 1)) {
                    s.probe = -probeMs; emit(s); enter(Phase.SETTLE_B, nowMs)
                } else if (phase == Phase.COLLECT_B && s.nB >= windowsPerState * (s.cycles + 1)) {
                    analyze(s, nowMs)
                    phase = Phase.IDLE
                }
            }
        }
        if (phase == Phase.IDLE || phase == Phase.SETTLE_A || phase == Phase.SETTLE_B) idleAccumulate()
        return drain()
    }

    /** Ordres en attente sans faire avancer la mesure (ex. après resetAll). */
    fun drainPending(): List<Command> = drain()

    private fun drain(): List<Command> { val r = ArrayList(pending); pending.clear(); return r }

    private fun startNext(nowMs: Double) {
        if (listeners.isEmpty()) { lastInfo = "Aucun auditeur"; return }
        val list = listeners.values.toList()
        val s = list[rr++ % list.size]
        current = s
        s.probe = probeMs
        if (s.status == "En attente de mesure") s.status = "Mesure en cours…"
        emit(s)
        enter(Phase.SETTLE_A, nowMs)
        lastInfo = "Mesure de l'auditeur ${s.id}"
    }

    private fun enter(p: Phase, nowMs: Double) {
        phase = p; phaseStart = nowMs
        if (p == Phase.COLLECT_A || p == Phase.COLLECT_B) {
            // on ne prend que des fenêtres micro commençant après la fin de la transition
            nextWindowTs = nowMs
        }
    }

    /** Fenêtres de 1 s disponibles depuis nextWindowTs : intercorrélation et accumulation. */
    private fun processWindows() {
        val s = current ?: return
        while (true) {
            if (!computeWindow(nextWindowTs)) {
                // données trop anciennes (tampon réinitialisé…) : on repart des plus récentes
                val end = mic.endTs()
                if (!end.isNaN() && end - nextWindowTs > 3000 && end - 1000 > phaseStart) nextWindowTs = end - 1000
                return
            }
            nextWindowTs += 1000.0
            addTotal()
            val a = phase == Phase.COLLECT_A
            val acc = if (a) (s.accA ?: FloatArray(nLag).also { s.accA = it }) else (s.accB ?: FloatArray(nLag).also { s.accB = it })
            val sq = if (a) (s.sqA ?: FloatArray(nLag).also { s.sqA = it }) else (s.sqB ?: FloatArray(nLag).also { s.sqB = it })
            for (i in 0 until nLag) { val c = curve[i]; acc[i] += c; sq[i] += c * c }
            if (phase == Phase.COLLECT_A) s.nA++ else s.nB++
        }
    }

    // Hors collecte, on continue d'affiner le pic de l'émetteur (fenêtres récentes)
    private var idleNextTs = Double.NaN
    private fun idleAccumulate() {
        val end = mic.endTs()
        if (end.isNaN()) return
        if (idleNextTs.isNaN() || idleNextTs < end - 4000) idleNextTs = end - 1000
        if (computeWindow(idleNextTs)) { idleNextTs += 1000.0; addTotal() }
    }

    private fun addTotal() {
        // moyenne glissante exponentielle (la pièce peut changer)
        val a = if (totalN < 10) 1f / (totalN + 1) else 0.1f
        for (i in 0 until nLag) total[i] += (curve[i] - total[i]) * a
        totalN++
        val (idx, _) = peak(total)
        selfDelayMs = indexToDelay(idx)
    }

    private fun computeWindow(m0: Double): Boolean {
        if (m0.isNaN()) return false
        val dMax = centerMs + halfRangeMs
        if (!mic.read(m0, micBuf, micLen)) return false
        if (!reference.read(m0 - dMax, refBuf, gcc.refLen)) return false
        var energy = 0.0
        for (v in micBuf) energy += v * v
        if (energy < 1e-9 * micLen) return true.also { curve.fill(0f) } // micro muet : fenêtre neutre
        gcc.correlate(micBuf, refBuf, curve)
        return true
    }

    /** Indice s (fractionnaire) -> délai D en ms : D = Dmax - s/fs. */
    private fun indexToDelay(s: Double) = centerMs + halfRangeMs - s * 1000.0 / fs

    private fun peak(c: FloatArray): Pair<Double, Float> {
        var bi = 1
        for (i in 1 until c.size - 1) if (c[i] > c[bi]) bi = i
        return parabolic(c, bi) to c[bi]
    }

    private fun parabolic(c: FloatArray, i: Int): Double {
        if (i <= 0 || i >= c.size - 1) return i.toDouble()
        val a = c[i - 1]; val b = c[i]; val d = c[i + 1]
        val den = a - 2 * b + d
        return if (den == 0f) i.toDouble() else i + 0.5 * (a - d) / den
    }

    private fun analyze(s: ListenerState, nowMs: Double) {
        val accA = s.accA; val accB = s.accB; val sqA = s.sqA; val sqB = s.sqB
        if (accA == null || accB == null || sqA == null || sqB == null || s.nA < 2 || s.nB < 2 || totalN == 0) { resetAcc(s); return }
        val p = probeLagSamples
        val nA = s.nA.toFloat(); val nB = s.nB.toFloat()
        val diff = FloatArray(nLag) { accA[it] / nA - accB[it] / nB }
        // Variance de la moyenne à chaque délai (les fenêtres diffèrent : musique non stationnaire,
        // lobes du pic de l'émetteur…) -> statistique t par délai, robuste près du pic principal.
        val varMean = FloatArray(nLag) {
            val mA = accA[it] / nA; val mB = accB[it] / nB
            val vA = ((sqA[it] / nA - mA * mA) * nA / (nA - 1)).coerceAtLeast(0f)
            val vB = ((sqB[it] / nB - mB * mB) * nB / (nB - 1)).coerceAtLeast(0f)
            vA / nA + vB / nB
        }
        // Filtre adapté : auditeur au délai D -> +pic en (s-p) et -pic en (s+p)
        val score = FloatArray(nLag)
        for (i in p until nLag - p) score[i] = diff[i - p] - diff[i + p]
        val globalNoise = 1.4826 * mad(score, p, nLag - p)
        val t = FloatArray(nLag)
        for (i in p until nLag - p) {
            val se = kotlin.math.sqrt((varMean[i - p] + varMean[i + p]).toDouble()).coerceAtLeast(globalNoise)
            t[i] = if (se > 0) (score[i] / se).toFloat() else 0f
        }
        val (ti, tBest) = peak(t)
        val sk = parabolic(score, ti.roundToInt())
        val z = tBest.toDouble()
        val selfD = selfDelayMs ?: return
        // Le son de l'émetteur doit être resté immobile entre A et B (sinon fausse paire)
        val selfA = peak(FloatArray(nLag) { accA[it] / nA }).first
        val selfB = peak(FloatArray(nLag) { accB[it] / nB }).first
        if (abs(selfA - selfB) > 0.5 * fs / 1000.0) {
            resetAcc(s); s.cycles = 0; s.probe = 0.0; emit(s)
            lastInfo = "Sortie de l'émetteur instable, mesure recommencée"
            return
        }
        s.cycles++
        if (z >= zThreshold) {
            val m = indexToDelay(sk) - selfD
            val error = m - s.manual
            val cand = s.candidate
            if (abs(error) > 10.0 && (cand == null || abs(cand - m) > 3.0)) {
                // Grosse correction : on attend une 2e mesure concordante avant d'agir
                s.candidate = m
                s.status = "Vérification de la mesure…"
            } else {
                s.candidate = null
                s.measured = m; s.confidence = z; s.measuredAt = nowMs
                if (abs(error) > 0.5) s.auto -= error.coerceIn(-maxStepMs, maxStepMs)
                s.status = if (abs(error) <= 2.0) "Synchronisé" else "Corrigé"
            }
            resetAcc(s); s.cycles = 0
        } else if (s.cycles >= maxCycles) {
            s.status = "Non détecté (micro trop loin ou musique trop calme)"
            s.confidence = z
            resetAcc(s); s.cycles = 0
        }
        s.probe = 0.0
        emit(s)
        lastInfo = "Auditeur ${s.id} : z=${"%.1f".format(z)}"
    }

    private fun mad(a: FloatArray, from: Int, to: Int): Double {
        val v = FloatArray(to - from) { a[from + it] }
        v.sort()
        val med = v[v.size / 2]
        for (i in v.indices) v[i] = abs(v[i] - med)
        v.sort()
        return v[v.size / 2].toDouble()
    }
}
