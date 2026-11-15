package com.jarvis.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ok = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            JarvisHub.js("window.__micResult&&window.__micResult($ok)")
        }

    fun askPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(list.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La página empieza siempre inactiva: si quedó un servicio viejo, se cierra.
        JarvisHub.stopService()

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        JarvisHub.webView = web
        web.addJavascriptInterface(JarvisBridge(this), "AndroidJarvis")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Atrás no cierra a Jarvis: lo manda al fondo y sigue escuchando.
                moveTaskToBack(true)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        JarvisHub.appVisible = true
        JarvisHub.ifService { it.refreshOverlay() }
    }

    override fun onStop() {
        super.onStop()
        JarvisHub.appVisible = false
        JarvisHub.ifService { it.refreshOverlay() }
        // Evita que Chromium congele los temporizadores de la página en segundo plano.
        web.resumeTimers()
    }

    override fun onDestroy() {
        if (isFinishing) JarvisHub.stopService()
        JarvisHub.webView = null
        web.destroy()
        super.onDestroy()
    }
}
