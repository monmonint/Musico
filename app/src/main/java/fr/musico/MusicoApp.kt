package fr.musico

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistre la trace de tout plantage dans un fichier, affiché au lancement suivant :
 * Rémi peut ainsi nous la transmettre. (Les threads de Musico rattrapent déjà leurs erreurs ;
 * ceci est le dernier filet de sécurité.)
 */
class MusicoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
                crashFile(this).writeText("Musico ${BuildConfig.VERSION_NAME} — $date — thread ${t.name}\n$sw")
                StreamService.State.restoreMediaVolumeOnCrash(this)
            } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(t, e) else Log.e("Musico", "plantage", e)
        }
    }

    companion object {
        fun crashFile(app: Application): File = File(app.filesDir, "dernier_plantage.txt")
    }
}
