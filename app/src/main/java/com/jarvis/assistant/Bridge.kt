package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Punto de encuentro entre el servicio (micrófono + voz) y la página (WebView).
 * Todo ocurre en el hilo principal.
 */
object JarvisHub {
    @Volatile var webView: WebView? = null
    @Volatile var service: JarvisService? = null
    /** true mientras la pantalla de Jarvis está a la vista (entonces la burbuja se esconde). */
    @Volatile var appVisible = false

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<(JarvisService) -> Unit>()

    /** Ejecuta JavaScript dentro de la página. */
    fun js(code: String) {
        main.post { webView?.evaluateJavascript(code, null) }
    }

    /** Evento de reconocimiento de voz hacia la página (start / result / error / end). */
    fun rec(type: String, payload: JSONObject = JSONObject()) {
        js("window.__rec&&window.__rec(${JSONObject.quote(type)},$payload)")
    }

    /** La voz terminó de hablar la frase con ese id. */
    fun ttsDone(id: String) {
        val n = id.toIntOrNull() ?: return
        js("window.__ttsDone&&window.__ttsDone($n)")
    }

    /** Ejecuta la acción cuando el servicio ya está en primer plano (la deja en espera si aún no). */
    fun withService(action: (JarvisService) -> Unit) {
        main.post {
            val s = service
            if (s != null) action(s) else pending.add(action)
        }
    }

    /** Ejecuta la acción solo si el servicio ya existe. */
    fun ifService(action: (JarvisService) -> Unit) {
        main.post { service?.let(action) }
    }

    fun clearPending() { pending.clear() }

    fun serviceReady(s: JarvisService) {
        service = s
        val list = ArrayList(pending)
        pending.clear()
        list.forEach { it(s) }
    }

    fun serviceGone() {
        service = null
        pending.clear()
    }

    fun stopService() {
        main.post { service?.shutdown() }
    }
}

/** Funciones que la página puede llamar como `AndroidJarvis.xxx()`. */
class JarvisBridge(private val act: MainActivity) {

    private fun hasMicPerm() = ContextCompat.checkSelfPermission(
        act, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface fun hasMic(): Boolean = hasMicPerm()

    @JavascriptInterface fun askMic() {
        act.runOnUiThread { act.askPermissions() }
    }

    @JavascriptInterface fun service(on: Boolean) {
        if (on) {
            if (!hasMicPerm()) return
            act.runOnUiThread {
                JarvisHub.clearPending()
                try {
                    ContextCompat.startForegroundService(
                        act,
                        Intent(act, JarvisService::class.java).setAction(JarvisService.ACTION_START)
                    )
                } catch (e: Exception) {
                    JarvisHub.js("window.__nativeStopped&&window.__nativeStopped()")
                }
            }
        } else {
            JarvisHub.stopService()
        }
    }

    @JavascriptInterface fun recStart(lang: String) {
        if (!hasMicPerm()) {
            JarvisHub.rec("error", JSONObject().put("error", "not-allowed"))
            JarvisHub.rec("end")
            return
        }
        JarvisHub.withService { it.recStart(lang) }
    }

    @JavascriptInterface fun recStop() { JarvisHub.ifService { it.recStop() } }

    @JavascriptInterface fun recAbort() { JarvisHub.ifService { it.recAbort() } }

    @JavascriptInterface fun speak(id: String, text: String) {
        JarvisHub.withService { it.speak(id, text) }
    }

    @JavascriptInterface fun stopSpeak() { JarvisHub.ifService { it.stopSpeak() } }

    /* ---------- burbuja flotante ---------- */

    @JavascriptInterface fun hasOverlay(): Boolean = Settings.canDrawOverlays(act)

    @JavascriptInterface fun askOverlay() {
        act.runOnUiThread {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))
                )
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface fun setOverlay(on: Boolean) {
        act.getSharedPreferences("jarvis", 0).edit().putBoolean("overlay", on).apply()
        JarvisHub.ifService { it.refreshOverlay() }
    }

    @JavascriptInterface fun overlay(state: String, text: String) {
        JarvisHub.ifService { it.overlaySet(state, text) }
    }

    @JavascriptInterface fun isForeground(): Boolean = JarvisHub.appVisible

    /* ---------- acciones en el teléfono ---------- */

    @JavascriptInterface fun openApp(name: String): String =
        JarvisActions.openApp(act.applicationContext, name)

    @JavascriptInterface fun playSong(query: String, app: String): String =
        JarvisActions.playSong(act.applicationContext, query, app)

    @JavascriptInterface fun media(action: String): String =
        JarvisActions.media(act.applicationContext, action)
}
