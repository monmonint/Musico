package fr.musico

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/** Écran principal de l'émetteur (UI construite en code, pas de dépendance AndroidX). */
class MainActivity : Activity() {

    companion object {
        const val REQ_PERMS = 1
        const val REQ_PROJECTION = 2
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var urlText: TextView
    private lateinit var qrView: ImageView
    private lateinit var listenersText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var latencyLabel: TextView
    private lateinit var offsetLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var localSection: LinearLayout
    private var shownUrl: String? = null
    private var addressIndex = 0

    private val usages = listOf(
        "Flux alarme (recommandé)" to AudioAttributes.USAGE_ALARM,
        "Flux notification" to AudioAttributes.USAGE_NOTIFICATION,
        "Flux accessibilité" to AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(text("Musico", 30f, true))
        root.addView(text("Diffuse le son de ton téléphone vers les navigateurs autour de toi, en synchro."))

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
        listenersText = text("", 15f).apply { gravity = Gravity.CENTER }
        root.addView(listenersText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val s = StreamService.State
        root.addView(text("Synchronisation", 18f, true))
        latencyLabel = text("")
        root.addView(latencyLabel)
        root.addView(seek(900, s.latencyMs - 100) { p ->
            s.latencyMs = 100 + p; updateLabels(); StreamService.applySettings(this)
        })
        root.addView(text("Plus la latence est grande, plus la lecture résiste aux coupures Wi-Fi. Elle est identique sur tous les appareils.", 13f))

        val localSwitch = Switch(this).apply {
            text = "Synchro parfaite : couper le son direct et rejouer via Musico"
            isChecked = s.localPlayback
            setOnCheckedChangeListener { _, c -> s.localPlayback = c; localSection.visibility = if (c) View.VISIBLE else View.GONE; StreamService.applySettings(this@MainActivity) }
        }
        root.addView(localSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        localSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (s.localPlayback) View.VISIBLE else View.GONE }
        localSection.addView(text("Le volume média (Deezer) est mis à 0 pendant la diffusion ; la capture n'est pas affectée par le volume. Musico rejoue le son sur un autre flux, en même temps que les auditeurs. Règle le volume ici ou avec le volume du flux choisi.", 13f))
        val muteSwitch = Switch(this).apply {
            text = "Mettre le volume média à 0 automatiquement"
            isChecked = s.muteMediaWithLocal
            setOnCheckedChangeListener { _, c -> s.muteMediaWithLocal = c; StreamService.applySettings(this@MainActivity) }
        }
        localSection.addView(muteSwitch)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, usages.map { it.first })
            setSelection(usages.indexOfFirst { it.second == s.localUsage }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val u = usages[pos].second
                    if (u != s.localUsage) {
                        s.localUsage = u
                        s.localPlayer?.let { s.localPlayer = null; it.release() }
                        StreamService.applySettings(this@MainActivity)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        localSection.addView(spinner)
        volumeLabel = text("")
        localSection.addView(volumeLabel)
        localSection.addView(seek(100, (s.localVolume * 100).toInt()) { p ->
            s.localVolume = p / 100f; updateLabels(); StreamService.applySettings(this)
        })
        offsetLabel = text("")
        localSection.addView(offsetLabel)
        localSection.addView(seek(400, s.localOffsetMs + 200) { p ->
            s.localOffsetMs = p - 200; updateLabels(); StreamService.applySettings(this)
        })
        root.addView(localSection)

        root.addView(text("Astuces", 18f, true))
        root.addView(text("• Si rien n'est capté alors que la musique joue, l'appli source bloque la capture (protection). Essaie une autre appli.\n" +
            "• Les auditeurs doivent garder la page ouverte, écran allumé.\n" +
            "• Touche le QR code pour changer d'adresse réseau si elle ne marche pas.", 13f))

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
        override fun run() { refresh(); ui.postDelayed(this, 300) }
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
                    warningText.text = if ((getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isMusicActive)
                        "⚠ Aucun son capté alors qu'une appli joue : elle bloque probablement la capture (ex. protection anti-copie). Essaie une autre appli ou vérifie que la musique est bien lancée."
                    else "Aucun son capté : lance la musique dans Deezer (ou une autre appli)."
                } else warningText.visibility = View.GONE
            }
        }
        if (running && s.port > 0) {
            val addrs = NetUtils.localAddresses()
            if (addrs.isEmpty()) {
                urlText.text = "Aucun réseau : active le partage de connexion"
                qrView.setImageDrawable(null); shownUrl = null
            } else {
                val a = addrs[addressIndex % addrs.size]
                val url = "http://${a.ip}:${s.port}/"
                if (url != shownUrl) {
                    shownUrl = url
                    qrView.setImageBitmap(QrUtils.qrBitmap(url, 600))
                    urlText.text = url
                }
            }
            val n = s.server?.clientList?.count { it.ready } ?: 0
            val total = s.server?.clientCount ?: 0
            listenersText.text = "Auditeurs : $n à l'écoute ($total connecté${if (total > 1) "s" else ""})"
        } else {
            qrView.setImageDrawable(null); shownUrl = null
            urlText.text = ""; listenersText.text = ""
        }
    }

    // --- Démarrage : permissions -> consentement MediaProjection -> service ---

    private fun startFlow() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else requestProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else Toast.makeText(this, "Musico a besoin de l'autorisation audio pour capter le son.", Toast.LENGTH_LONG).show()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Écran entier : la capture audio concerne tout le téléphone
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PROJECTION)
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
        startForegroundService(Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(StreamService.EXTRA_DATA, data))
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
    }
}
