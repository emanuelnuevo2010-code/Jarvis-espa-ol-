package com.jarvis.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.text.Normalizer

/** Acciones que Jarvis hace en el teléfono: abrir apps, poner canciones, controlar la música. */
object JarvisActions {

    private const val SPOTIFY = "com.spotify.music"
    private const val YT_MUSIC = "com.google.android.apps.youtube.music"
    private const val YOUTUBE = "com.google.android.youtube"

    data class AppInfo(val label: String, val pkg: String)

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Cómo se dicen en voz alta (o se transcriben) algunas apps → palabras que aparecen en su nombre. */
    private val aliases: Map<String, List<String>> = mapOf(
        "wasap" to listOf("whatsapp"), "guasap" to listOf("whatsapp"), "whats app" to listOf("whatsapp"),
        "wats" to listOf("whatsapp"), "watsap" to listOf("whatsapp"),
        "insta" to listOf("instagram"), "face" to listOf("facebook"), "feis" to listOf("facebook"),
        "tik tok" to listOf("tiktok"), "you tube" to listOf("youtube"), "yutub" to listOf("youtube"),
        "telegram" to listOf("telegram"), "x" to listOf("twitter"), "tuiter" to listOf("twitter"),
        "navegador" to listOf("chrome", "firefox", "browser", "navegador", "internet", "edge"),
        "internet" to listOf("chrome", "firefox", "browser", "internet"),
        "camara" to listOf("camera", "camara"), "ajustes" to listOf("settings", "ajustes", "configuracion"),
        "configuracion" to listOf("settings", "ajustes", "configuracion"),
        "galeria" to listOf("gallery", "galeria", "fotos", "photos"), "fotos" to listOf("photos", "fotos", "gallery", "galeria"),
        "mapas" to listOf("maps", "mapas"), "gmail" to listOf("gmail"), "correo" to listOf("gmail", "mail", "correo", "outlook"),
        "calculadora" to listOf("calculator", "calculadora"), "calendario" to listOf("calendar", "calendario"),
        "reloj" to listOf("clock", "reloj"), "telefono" to listOf("phone", "telefono", "dialer"),
        "llamadas" to listOf("phone", "telefono", "dialer"), "mensajes" to listOf("messages", "mensajes", "sms"),
        "contactos" to listOf("contacts", "contactos"), "musica" to listOf("spotify", "music", "musica"),
        "tienda" to listOf("play store", "tienda"), "play store" to listOf("play store", "tienda"),
        "archivos" to listOf("files", "archivos"), "notas" to listOf("keep", "notes", "notas"),
        "drive" to listOf("drive"), "netflix" to listOf("netflix"), "spotify" to listOf("spotify"),
        "disney" to listOf("disney"), "prime" to listOf("prime video"), "uber" to listOf("uber"),
        "discord" to listOf("discord"), "snapchat" to listOf("snapchat"), "linterna" to listOf("flashlight", "linterna")
    )

    fun launchables(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    private fun score(app: AppInfo, wanted: String, extra: List<String>): Int {
        val label = norm(app.label)
        val pkg = app.pkg.lowercase()
        var best = 0
        for (w in listOf(wanted) + extra) {
            if (w.isBlank()) continue
            val s = when {
                label == w -> 100
                label.startsWith("$w ") || label.endsWith(" $w") -> 85
                label.contains(w) -> 70
                w.contains(label) && label.length >= 4 -> 60
                pkg.contains(w.replace(" ", "")) -> 50
                else -> {
                    val tokens = w.split(" ").filter { it.length >= 3 }
                    if (tokens.isNotEmpty() && tokens.all { label.contains(it) }) 55 else 0
                }
            }
            if (s > best) best = s
        }
        return best
    }

    fun findApp(ctx: Context, spoken: String): AppInfo? {
        val wanted = norm(spoken)
        if (wanted.isBlank()) return null
        val extra = aliases[wanted] ?: emptyList()
        return launchables(ctx)
            .map { it to score(it, wanted, extra) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Abre una app por su nombre hablado. Devuelve JSON: {ok, label} o {ok:false, err}. */
    fun openApp(ctx: Context, spoken: String): String {
        val wanted = norm(spoken)
        val app = findApp(ctx, spoken)
        if (app != null) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            if (launch != null && start(ctx, launch)) {
                return JSONObject().put("ok", true).put("label", app.label).toString()
            }
        }
        // Atajos del sistema para lo básico si no hay app con ese nombre
        val system: Intent? = when (wanted) {
            "ajustes", "configuracion", "settings" -> Intent(Settings.ACTION_SETTINGS)
            "camara" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "navegador", "internet" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            else -> null
        }
        if (system != null && start(ctx, system)) {
            return JSONObject().put("ok", true).put("label", spoken).toString()
        }
        return JSONObject().put("ok", false).put("err", if (app == null) "not-found" else "blocked").toString()
    }

    private fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun playFromSearch(query: String, pkg: String?): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            if (pkg != null) setPackage(pkg)
        }

    /**
     * Pone una canción. `app` puede ser "spotify", "youtube music", "youtube" o vacío (elige la primera instalada).
     * Devuelve JSON: {ok, app} o {ok:false, err}.
     */
    fun playSong(ctx: Context, query: String, app: String): String {
        val pref = norm(app)
        val order = when {
            pref.contains("spotify") -> listOf(SPOTIFY)
            pref.contains("music") || pref.contains("yt") -> listOf(YT_MUSIC)
            pref.contains("youtube") -> listOf(YOUTUBE)
            else -> listOf(SPOTIFY, YT_MUSIC, YOUTUBE)
        }
        val q = query.trim()
        for (pkg in order.filter { installed(ctx, it) }) {
            val name = when (pkg) { SPOTIFY -> "Spotify"; YT_MUSIC -> "YouTube Music"; else -> "YouTube" }
            if (q.isEmpty()) {
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null && start(ctx, launch)) return ok(name)
                continue
            }
            val intent = when (pkg) {
                YOUTUBE -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                ).setPackage(YOUTUBE)
                else -> playFromSearch(q, pkg)
            }
            if (start(ctx, intent)) return ok(name)
            if (pkg == SPOTIFY && start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))))) {
                return ok(name)
            }
        }
        // Cualquier reproductor que sepa buscar y tocar
        if (q.isNotEmpty() && start(ctx, playFromSearch(q, null))) return ok("tu reproductor")
        // Último recurso: YouTube en el navegador
        if (q.isNotEmpty() &&
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        ) return ok("YouTube")
        return JSONObject().put("ok", false).put("err", "no-player").toString()
    }

    private fun ok(app: String) = JSONObject().put("ok", true).put("app", app).toString()

    /** Controla lo que esté sonando: play, pause, toggle, next, prev, vol_up, vol_down. */
    fun media(ctx: Context, action: String): String {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun key(code: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        return try {
            when (action) {
                "play" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
                "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "toggle" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
                "prev" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "vol_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "vol_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                else -> return JSONObject().put("ok", false).toString()
            }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).toString()
        }
    }
}
