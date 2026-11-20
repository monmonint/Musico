package fr.musico

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/** Écran principal de l'émetteur (UI construite en code, pas de dépendance AndroidX). */
class MainActivity : Activity() {

    companion object {
        const val TAG = "MusicoUI"
        const val REQ_PERMS = 1
        const val REQ_PROJECTION = 2
        const val LATENCY_MIN = 200
        const val LATENCY_MAX = 1500
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var urlText: TextView
    private lateinit var qrView: ImageView
    private lateinit var listenersText: TextView
    private lateinit var syncSummary: TextView
    private lateinit var syncInfoText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var latencyLabel: TextView
    private lateinit var offsetLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var localSection: LinearLayout
    private var shownUrl: String? = null
    private var addressIndex = 0
    private var lastAddrScan = 0L
    private var cachedAddrs: List<NetUtils.Candidate> = emptyList()

    private val usages = listOf(
        "Flux alarme (recommandé)" to AudioAttributes.USAGE_ALARM,
        "Flux notification" to AudioAttributes.USAGE_NOTIFICATION,
        "Flux accessibilité" to AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    )

    private val prefs by lazy { getSharedPreferences("musico", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadPrefs()
        setContentView(buildUi())
        showLastCrash()
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun loadPrefs() {
        val s = StreamService.State
        if (s.running) return // le service tourne déjà avec ses réglages
        s.latencyMs = prefs.getInt("latency", 600).coerceIn(LATENCY_MIN, LATENCY_MAX)
        s.localPlayback = prefs.getBoolean("localPlayback", true)
        s.localUsage = prefs.getInt("localUsage", AudioAttributes.USAGE_ALARM)
        s.localVolume = prefs.getFloat("localVolume", 1f)
        s.localOffsetMs = prefs.getInt("localOffset", 0)
        s.muteMediaWithLocal = prefs.getBoolean("muteMedia", true)
        s.autoSync = prefs.getBoolean("autoSync", true)
    }

    private fun savePrefs() {
        val s = StreamService.State
        prefs.edit().putInt("latency", s.latencyMs).putBoolean("localPlayback", s.localPlayback)
            .putInt("localUsage", s.localUsage).putFloat("localVolume", s.localVolume)
            .putInt("localOffset", s.localOffsetMs).putBoolean("muteMedia", s.muteMediaWithLocal)
            .putBoolean("autoSync", s.autoSync).apply()
    }

    private fun settingsChanged() {
        savePrefs()
        updateLabels()
        StreamService.applySettings(this)
    }

    private fun showLastCrash() {
        try {
            val f = MusicoApp.crashFile(application)
            if (!f.exists()) return
            val txt = f.readText()
            AlertDialog.Builder(this)
                .setTitle("Musico a planté la dernière fois")
                .setMessage("Envoie ce rapport au développeur pour qu'il corrige le problème :\n\n" + txt.take(3000))
                .setPositiveButton("Partager") { _, _ ->
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Rapport de plantage Musico").putExtra(Intent.EXTRA_TEXT, txt), "Partager"))
                    } catch (_: Throwable) {}
                    f.delete()
                }
                .setNegativeButton("Ignorer") { _, _ -> f.delete() }
                .show()
        } catch (t: Throwable) { Log.w(TAG, "rapport", t) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun buildUi(): View {
        val s = StreamService.State
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(text("Musico", 30f, true))
        root.addView(text("Diffuse le son de ton téléphone vers les navigateurs autour de toi, en synchro. Version ${BuildConfig.VERSION_NAME}.", 14f))

        startButton = Button(this).apply {
            text = "Démarrer la diffusion"
            setOnClickListener { if (StreamService.State.running) stopStreaming() else startFlow() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        statusText = text("Prêt.", 16f, true)
        root.addView(statusText)
        warningText = text("", 14f).apply { setTextColor(Color.rgb(200, 60, 0)); visibility = View.GONE }
        root.addView(warningText)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(levelBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(10)))

        root.addView(text("Pour les auditeurs", 18f, true))
        root.addView(text("1. Active ton partage de connexion et fais-les s'y connecter.\n" +
            "2. Ils scannent ce QR code et appuient sur « Écouter ».\nRien à installer."))
        qrView = ImageView(this).apply {
            adjustViewBounds = true
            setOnClickListener { addressIndex++; shownUrl = null; refresh() } // change d'adresse
        }
        root.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        urlText = text("", 18f, true).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }
        root.addView(urlText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(text("Synchronisation", 18f, true))
        syncSummary = text("", 17f, true)
        root.addView(syncSummary)
        listenersText = text("", 14f)
        root.addView(listenersText)
        syncInfoText = text("", 12f).apply { setTextColor(Color.GRAY) }
        root.addView(syncInfoText)

        root.addView(Switch(this).apply {
            text = "Synchro auto par le micro (aucun son ajouté)"
            isChecked = s.autoSync
            setOnCheckedChangeListener { _, c -> s.autoSync = c; settingsChanged() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(text("Le micro de ce téléphone écoute la pièce et compare avec la musique diffusée pour mesurer l'écart réel de chaque auditeur, puis le corrige en douceur. Pose les téléphones à quelques mètres au plus. Rien n'est enregistré.", 13f))
        root.addView(Button(this).apply {
            text = "Remettre les corrections auto à 0"
            setOnClickListener { StreamService.State.resetCorrections.set(true) }
        })

        latencyLabel = text("")
        root.addView(latencyLabel)
        root.addView(seek(LATENCY_MAX - LATENCY_MIN, s.latencyMs - LATENCY_MIN) { p ->
            s.latencyMs = LATENCY_MIN + (p / 10) * 10; settingsChanged()
        })
        root.addView(text("Plus la latence est grande, plus la lecture résiste aux coupures Wi-Fi. Elle est la même sur tous les appareils.", 13f))

        root.addView(Switch(this).apply {
            text = "Synchro parfaite : couper le son direct et rejouer via Musico"
            isChecked = s.localPlayback
            setOnCheckedChangeListener { _, c -> s.localPlayback = c; localSection.visibility = if (c) View.VISIBLE else View.GONE; settingsChanged() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        localSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (s.localPlayback) View.VISIBLE else View.GONE }
        localSection.addView(text("Le volume média (Deezer) est mis à 0 pendant la diffusion ; la capture n'en dépend pas. Musico rejoue le son sur un autre flux, en même temps que les auditeurs.", 13f))
        localSection.addView(Switch(this).apply {
            text = "Mettre le volume média à 0 automatiquement"
            isChecked = s.muteMediaWithLocal
            setOnCheckedChangeListener { _, c -> s.muteMediaWithLocal = c; settingsChanged() }
        })
        localSection.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, usages.map { it.first })
            setSelection(usages.indexOfFirst { it.second == s.localUsage }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val u = usages[pos].second
                    if (u != s.localUsage) {
                        s.localUsage = u
                        try { s.localPlayer?.let { s.localPlayer = null; it.release() } } catch (_: Throwable) {}
                        settingsChanged()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        volumeLabel = text("")
        localSection.addView(volumeLabel)
        localSection.addView(seek(100, (s.localVolume * 100).toInt()) { p -> s.localVolume = p / 100f; settingsChanged() })
        offsetLabel = text("")
        localSection.addView(offsetLabel)
        localSection.addView(seek(400, s.localOffsetMs + 200) { p -> s.localOffsetMs = p - 200; settingsChanged() })
        localSection.addView(text("Avec la synchro auto, les auditeurs suivent ce téléphone : ce décalage les décale tous.", 12f))
        root.addView(localSection)

        root.addView(text("Astuces", 18f, true))
        root.addView(text("• Si rien n'est capté alors que la musique joue, l'appli source bloque la capture (protection). Essaie une autre appli.\n" +
            "• Les auditeurs doivent garder la page ouverte, écran allumé.\n" +
            "• Touche le QR code pour changer d'adresse réseau si elle ne marche pas.\n" +
            "• Sans le micro, chaque auditeur peut régler son décalage à la main sur sa page.", 13f))

        updateLabels()
        return ScrollView(this).apply {
            // Android 15+ : affichage bord à bord imposé, on évite les barres système
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
            addView(root)
        }
    }

    private fun updateLabels() {
        val s = StreamService.State
        latencyLabel.text = "Latence cible : ${s.latencyMs} ms"
        volumeLabel.text = "Volume de Musico sur ce téléphone : ${(s.localVolume * 100).toInt()} %"
        offsetLabel.text = "Décalage manuel de ce téléphone : ${if (s.localOffsetMs > 0) "+" else ""}${s.localOffsetMs} ms"
    }

    private val refresher = object : Runnable {
        override fun run() {
            try { refresh() } catch (t: Throwable) { Log.e(TAG, "refresh", t) }
            ui.postDelayed(this, 400)
        }
    }

    private fun ms(v: Double) = String.format(Locale.FRANCE, "%+.1f ms", v)

    private fun deviceName(ua: String) = when {
        ua.contains("iPhone") -> "iPhone"
        ua.contains("iPad") -> "iPad"
        ua.contains("Android") -> "Android"
        ua.isBlank() -> "navigateur"
        else -> "ordinateur"
    }

    private fun refresh() {
        val s = StreamService.State
        val running = s.running
        startButton.text = if (running) "Arrêter la diffusion" else "Démarrer la diffusion"
        val engine = s.engine
        val err = s.error ?: engine?.error
        levelBar.progress = ((engine?.level ?: 0f) * 100).toInt()
        when {
            err != null -> { statusText.text = err; warningText.visibility = View.GONE }
            !running -> { statusText.text = "Prêt."; warningText.visibility = View.GONE }
            else -> {
                statusText.text = "Diffusion en cours • ${engine?.codecName ?: ""}"
                if (engine?.silent == true) {
                    warningText.visibility = View.VISIBLE
                    warningText.text = if ((getSystemService(AUDIO_SERVICE) as AudioManager).isMusicActive)
                        "⚠ Aucun son capté alors qu'une appli joue : elle bloque probablement la capture (ex. protection anti-copie). Essaie une autre appli ou vérifie que la musique est bien lancée."
                    else "Aucun son capté : lance la musique dans Deezer (ou une autre appli)."
                } else warningText.visibility = View.GONE
            }
        }
        val server = s.server
        if (running && s.port > 0 && server != null) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastAddrScan > 3000) { cachedAddrs = NetUtils.localAddresses(); lastAddrScan = nowMs }
            val addrs = cachedAddrs
            if (addrs.isEmpty()) {
                urlText.text = "Aucun réseau : active le partage de connexion"
                qrView.setImageDrawable(null); shownUrl = null
            } else {
                val a = addrs[addressIndex % addrs.size]
                val url = "http://${a.ip}:${s.port}/"
                if (url != shownUrl) {
                    shownUrl = url
                    try { qrView.setImageBitmap(QrUtils.qrBitmap(url, 600)) } catch (t: Throwable) { Log.w(TAG, "QR", t) }
                    urlText.text = url
                }
            }
            val ready = server.clientList.filter { it.ready }
            val total = server.clientCount
            if (ready.isEmpty()) {
                syncSummary.text = "Aucun auditeur à l'écoute ($total connecté${if (total > 1) "s" else ""})"
                listenersText.text = ""
            } else {
                val measured = ready.mapNotNull { it.measuredMs }
                syncSummary.text = if (measured.isEmpty()) "Écart réel : en cours de mesure…"
                else {
                    val worst = measured.maxBy { abs(it) }
                    "Écart réel max : ${ms(worst)}" + if (abs(worst) <= 3) " ✓" else ""
                }
                listenersText.text = ready.joinToString("\n") { c ->
                    val m = c.measuredMs?.let { "écart ${ms(it)}" } ?: "écart —"
                    val corr = c.autoMs?.let { if (abs(it) > 0.05) " · correction ${ms(it)}" else "" } ?: ""
                    val man = if (abs(c.manualMs) > 0.5) " · manuel ${ms(c.manualMs)}" else ""
                    "• ${deviceName(c.userAgent)} (${c.address}) : $m$corr$man\n   ${c.syncStatus}"
                }
            }
            val mic = s.mic
            syncInfoText.text = s.syncInfo + (if (mic != null && mic.active) " · micro ${(mic.level * 100).toInt()} %" else "")
        } else {
            qrView.setImageDrawable(null); shownUrl = null
            urlText.text = ""; listenersText.text = ""; syncSummary.text = ""; syncInfoText.text = ""
        }
    }

    // --- Démarrage : permissions -> consentement MediaProjection -> service ---

    private fun startFlow() {
        try {
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else requestProjection()
        } catch (t: Throwable) {
            Log.e(TAG, "startFlow", t)
            Toast.makeText(this, "Impossible de démarrer : ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else AlertDialog.Builder(this)
            .setTitle("Autorisation audio nécessaire")
            .setMessage("Android exige l'autorisation « Micro / audio » pour capter le son des autres applis (même sans utiliser le micro). Sans elle, Musico ne peut pas diffuser. Tu peux l'accorder dans Paramètres > Applis > Musico > Autorisations.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestProjection() {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                // Écran entier : la capture audio concerne tout le téléphone
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else mpm.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PROJECTION)
        } catch (t: Throwable) {
            Log.e(TAG, "projection", t)
            Toast.makeText(this, "Capture impossible sur ce téléphone : ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("API framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture refusée.", Toast.LENGTH_SHORT).show(); return
        }
        StreamService.State.error = null
        try {
            startForegroundService(Intent(this, StreamService::class.java)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(StreamService.EXTRA_DATA, data))
        } catch (t: Throwable) {
            Log.e(TAG, "service", t)
            StreamService.State.error = "Le service n'a pas pu démarrer : ${t.message}"
        }
    }

    private fun stopStreaming() {
        try { startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)) }
        catch (t: Throwable) { Log.e(TAG, "stop", t) }
    }
}
