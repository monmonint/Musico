package fr.musico

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import fr.musico.server.HttpWsServer
import fr.musico.server.StreamHub
import fr.musico.sync.AcousticAligner
import fr.musico.sync.Decimator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service de premier plan (types mediaProjection + microphone) : capture, serveur web,
 * relecture locale et mesure acoustique de synchro. État exposé statiquement (lu par l'écran).
 * Toutes les opérations risquées sont protégées : une erreur dégrade une fonction, jamais l'appli.
 */
class StreamService : Service() {

    object State {
        @Volatile var running = false
        @Volatile var port = -1
        @Volatile var error: String? = null
        @Volatile var engine: CaptureEngine? = null
        @Volatile var server: HttpWsServer? = null
        @Volatile var hub: StreamHub? = null
        @Volatile var localPlayer: LocalPlayer? = null
        @Volatile var mic: MicMonitor? = null
        @Volatile var aligner: AcousticAligner? = null
        @Volatile var mediaMuted = false
        @Volatile var syncInfo: String = ""
        val resetCorrections = AtomicBoolean(false)

        // Réglages (appliqués à chaud, sauvegardés par l'écran)
        @Volatile var latencyMs = 600
        @Volatile var localPlayback = true
        @Volatile var localUsage = AudioAttributes.USAGE_ALARM
        @Volatile var localVolume = 1f
        @Volatile var localOffsetMs = 0
        @Volatile var muteMediaWithLocal = true
        @Volatile var autoSync = true

        @Volatile internal var savedMusicVolume = -1

        fun restoreMediaVolumeOnCrash(context: Context) {
            if (mediaMuted && savedMusicVolume >= 0) {
                try {
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        const val TAG = "MusicoService"
        const val ACTION_START = "fr.musico.START"
        const val ACTION_STOP = "fr.musico.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "musico"
        const val NOTIF_ID = 1
        const val DEFAULT_PORT = 8080

        fun applySettings(context: Context) {
            try {
                State.hub?.setLatency(State.latencyMs)
                val wantLocal = State.running && State.localPlayback
                val lp = State.localPlayer
                if (wantLocal && lp == null) {
                    State.localPlayer = try { LocalPlayer(State.localUsage) } catch (t: Throwable) {
                        Log.e(TAG, "LocalPlayer", t)
                        State.error = "Relecture locale impossible : ${t.message}"
                        null
                    }
                } else if (!wantLocal && lp != null) {
                    State.localPlayer = null
                    lp.release()
                }
                State.localPlayer?.apply {
                    targetLatencyMs = State.latencyMs
                    offsetMs = State.localOffsetMs
                    setVolume(State.localVolume)
                }
                setMediaMuted(context, wantLocal && State.muteMediaWithLocal && State.localPlayer != null)
                // Micro : démarré/arrêté selon le réglage de synchro auto
                val mic = State.mic
                if (State.running && State.autoSync && mic != null && !mic.active && mic.error == null) mic.start()
                if (!State.autoSync && mic != null && mic.active) mic.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "applySettings", t)
            }
        }

        /** Coupe/restaure le volume média (celui de Deezer). La capture n'en dépend pas. */
        fun setMediaMuted(context: Context, mute: Boolean) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (mute && !State.mediaMuted) {
                    State.savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    State.mediaMuted = true
                } else if (!mute && State.mediaMuted) {
                    if (State.savedMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, State.savedMusicVolume, 0)
                    State.mediaMuted = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Volume non modifiable (mode Ne pas déranger ?)", t)
            }
        }
    }

    private var projection: MediaProjection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var syncRunning = false
    private var syncThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
                ACTION_START -> startStreaming(intent)
                else -> stopSelf() // relancé par le système sans consentement : on ne fait rien
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand", t)
            State.error = "Erreur au démarrage : ${t.message ?: t.javaClass.simpleName}"
            stopEverything(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startStreaming(intent: Intent) {
        // Android 14+ : le service doit être au premier plan AVANT getMediaProjection()
        val notif = buildNotification()
        val withMic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Le type « microphone » n'existe qu'à partir d'Android 11 (API 30) ; avant, inutile.
        var micOk = hasMicPermission()
        val micFgs = micOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        try {
            startForeground(NOTIF_ID, notif, if (micFgs) withMic else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground avec micro refusé, sans micro", t)
            micOk = false
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
        if (State.running) return
        State.error = null
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try { data?.let { mpm.getMediaProjection(resultCode, it) } } catch (t: Throwable) { null }
        if (proj == null) {
            State.error = "Autorisation de capture refusée ou expirée, réessaie"
            stopEverything(); stopSelf(); return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection arrêtée")
                Handler(Looper.getMainLooper()).post { stopEverything(); stopSelf() }
            }
        }, Handler(Looper.getMainLooper()))

        val hub = StreamHub { CaptureEngine.nowMs() }
        hub.latencyMs = State.latencyMs
        val server = HttpWsServer(DEFAULT_PORT, { path -> loadAsset(path) }, hub)
        hub.server = server
        try {
            State.port = server.start()
        } catch (t: Throwable) {
            State.error = "Serveur web impossible à démarrer : ${t.message}"
            stopEverything(); stopSelf(); return
        }
        val aligner = AcousticAligner()
        val refDecimator = Decimator(3, 2)
        val refOut = FloatArray(CaptureEngine.FRAMES_PER_CHUNK / 3 + 4)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val engine = CaptureEngine(proj, hub, { State.localPlayer }, { am.isMusicActive }) { ts, pcm ->
            val n = refDecimator.process(pcm, pcm.size, refOut)
            aligner.reference.write(ts, refOut, 0, n)
        }
        State.hub = hub
        State.server = server
        State.engine = engine
        State.aligner = aligner
        State.mic = MicMonitor(applicationContext, aligner).also { if (!micOk) it.markUnavailable() }
        State.running = true
        acquireLocks()
        applySettings(this)
        engine.start()
        startSyncLoop(aligner, hub, server)
    }

    /** Boucle de synchro acoustique (4 fois/s) : pilote les mesures et envoie les corrections. */
    private fun startSyncLoop(aligner: AcousticAligner, hub: StreamHub, server: HttpWsServer) {
        syncRunning = true
        syncThread = Thread({
            var wasActive = false
            while (syncRunning) {
                try {
                    Thread.sleep(250)
                    val mic = State.mic
                    if (State.resetCorrections.getAndSet(false)) {
                        aligner.resetCorrections()
                        for (c in server.clientList) hub.sendOffset(c, 0.0, 0.0, null, "Corrections remises à 0")
                    }
                    val active = State.autoSync && State.localPlayback && State.localPlayer != null &&
                        mic != null && mic.active && mic.error == null
                    if (!active) {
                        if (wasActive) {
                            aligner.resetAll("Synchro auto en pause")
                            send(hub, server, aligner.drainPending())
                        }
                        wasActive = false
                        State.syncInfo = when {
                            !State.autoSync -> "Synchro auto désactivée (réglage manuel)"
                            !State.localPlayback -> "Synchro auto : active « Synchro parfaite » pour mesurer"
                            mic?.error != null -> "${mic.error} — réglage manuel possible"
                            else -> "Démarrage du micro…"
                        }
                        continue
                    }
                    wasActive = true
                    aligner.setExpectedSelfDelay((State.latencyMs + State.localOffsetMs).toDouble())
                    val ready = server.clientList.filter { it.ready }
                    aligner.setListeners(ready.associate { it.id to (it.manualMs to it.autoMs) })
                    send(hub, server, aligner.step(CaptureEngine.nowMs()))
                    State.syncInfo = if (mic.silent) "Micro muet : il est peut-être utilisé par une autre appli"
                    else aligner.lastInfo
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "boucle de synchro", t)
                    State.syncInfo = "Erreur de mesure : ${t.message}"
                }
            }
        }, "musico-sync").apply { isDaemon = true; start() }
    }

    private fun send(hub: StreamHub, server: HttpWsServer, cmds: List<AcousticAligner.Command>) {
        if (cmds.isEmpty()) return
        val byId = server.clientList.associateBy { it.id }
        for (c in cmds) byId[c.id]?.let { hub.sendOffset(it, c.auto, c.probe, c.measured, c.status) }
    }

    private fun loadAsset(path: String): HttpWsServer.Asset? = try {
        val bytes = assets.open("web/$path").use { it.readBytes() }
        HttpWsServer.Asset(bytes, HttpWsServer.contentTypeFor(path))
    } catch (t: Throwable) { null }

    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "musico:wifi").apply { acquire() }
        } catch (t: Throwable) { Log.w(TAG, "wifiLock", t) }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musico:capture").apply { acquire(12 * 3600 * 1000L) }
        } catch (t: Throwable) { Log.w(TAG, "wakeLock", t) }
    }

    private fun stopEverything() {
        State.running = false
        syncRunning = false
        try { syncThread?.interrupt(); syncThread?.join(500) } catch (_: Throwable) {}
        syncThread = null
        try { State.mic?.stop() } catch (_: Throwable) {}
        State.mic = null
        try { State.engine?.stop() } catch (_: Throwable) {}
        State.engine = null
        try { State.server?.stop() } catch (_: Throwable) {}
        State.server = null
        State.hub = null
        State.aligner = null
        try { State.localPlayer?.release() } catch (_: Throwable) {}
        State.localPlayer = null
        setMediaMuted(this, false)
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifiLock = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Diffusion Musico", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Musico diffuse le son")
            .setContentText("Les auditeurs écoutent depuis leur navigateur")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
    }
}
