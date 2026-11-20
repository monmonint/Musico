package fr.musico

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import fr.musico.server.HttpWsServer
import fr.musico.server.StreamHub

/**
 * Service de premier plan (type mediaProjection) : capture, serveur web et relecture locale.
 * L'état est exposé de façon statique (prototype) et lu par l'écran principal.
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
        @Volatile var mediaMuted = false

        // Réglages (appliqués à chaud)
        @Volatile var latencyMs = 300
        @Volatile var localPlayback = true
        @Volatile var localUsage = AudioAttributes.USAGE_ALARM
        @Volatile var localVolume = 1f
        @Volatile var localOffsetMs = 0
        @Volatile var muteMediaWithLocal = true
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

        private var savedMusicVolume = -1

        fun applySettings(context: Context) {
            State.hub?.setLatency(State.latencyMs)
            val wantLocal = State.running && State.localPlayback
            val lp = State.localPlayer
            if (wantLocal && lp == null) {
                State.localPlayer = LocalPlayer(State.localUsage)
            } else if (!wantLocal && lp != null) {
                State.localPlayer = null
                lp.release()
            }
            State.localPlayer?.apply {
                targetLatencyMs = State.latencyMs
                offsetMs = State.localOffsetMs
                setVolume(State.localVolume)
            }
            setMediaMuted(context, wantLocal && State.muteMediaWithLocal)
        }

        /** Coupe/restaure le volume média (celui de Deezer). La capture n'en dépend pas. */
        fun setMediaMuted(context: Context, mute: Boolean) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                if (mute && !State.mediaMuted) {
                    savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    State.mediaMuted = true
                } else if (!mute && State.mediaMuted) {
                    if (savedMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                    State.mediaMuted = false
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Volume non modifiable (mode Ne pas déranger ?)", e)
            }
        }
    }

    private var projection: MediaProjection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> startStreaming(intent)
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        // Android 14+ : le service doit être au premier plan AVANT getMediaProjection()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (State.running) return
        State.error = null
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try { data?.let { mpm.getMediaProjection(resultCode, it) } } catch (e: Exception) { null }
        if (proj == null) {
            State.error = "Autorisation de capture refusée"
            stopSelf(); return
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
        } catch (e: Exception) {
            State.error = "Serveur web impossible à démarrer : ${e.message}"
            proj.stop(); stopSelf(); return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val engine = CaptureEngine(proj, hub, { State.localPlayer }, { am.isMusicActive })
        State.hub = hub
        State.server = server
        State.engine = engine
        State.running = true
        acquireLocks()
        applySettings(this)
        engine.start()
    }

    private fun loadAsset(path: String): HttpWsServer.Asset? = try {
        val bytes = assets.open("web/$path").use { it.readBytes() }
        HttpWsServer.Asset(bytes, HttpWsServer.contentTypeFor(path))
    } catch (e: Exception) { null }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "musico:wifi").apply { acquire() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musico:capture").apply { acquire() }
    }

    private fun stopEverything() {
        if (!State.running && projection == null) return
        State.running = false
        State.engine?.stop(); State.engine = null
        State.server?.stop(); State.server = null
        State.hub = null
        State.localPlayer?.release(); State.localPlayer = null
        setMediaMuted(this, false)
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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
