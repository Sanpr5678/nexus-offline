package com.nexus.offline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private val engine get() = NexusApp.engine

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooserCb = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermsChanged()
    }

    private fun requiredPerms(): List<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_ADVERTISE
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
        }
        // Request COARSE_LOCATION on every Android version: Google Nearby Connections
        // can report MISSING_PERMISSION_ACCESS_COARSE_LOCATION during discovery even
        // on newer Android versions / some Google Play services implementations.
        l += Manifest.permission.ACCESS_COARSE_LOCATION

        // On Android 12L and below, location-based Bluetooth scanning also needs FINE.
        // Request COARSE first, then FINE, as required by Android's location permission flow.
        if (Build.VERSION.SDK_INT <= 32) {
            l += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return l
    }

    private fun hasPerms() = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        val all = requiredPerms().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(all.toTypedArray())
    }

    private fun startMesh() {
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
    }

    private fun stopMesh() {
        stopService(Intent(this, MeshService::class.java))
    }

    private fun onPermsChanged() {
        val ok = hasPerms()
        js("window.nexusPerm&&window.nexusPerm($ok)")
        if (ok) startMesh()
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun pushEvent(e: JSONObject) {
        js("window.nexusEvent&&window.nexusEvent(" + JSONObject.quote(e.toString()) + ")")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF080D16.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                return try {
                    chooser.launch(p.createIntent()); true
                } catch (e: Exception) {
                    chooserCb = null; false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "NexusBridge")
        web.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.nexusBack?window.nexusBack():false") { r ->
                    if (r != "true") moveTaskToBack(true) // keep the mesh node running
                }
            }
        })
    }

    override fun onDestroy() {
        engine.detach()
        web.destroy()
        super.onDestroy()
    }

    /** Everything below is invoked from JS; all engine work is posted to the main thread. */
    inner class Bridge {
        @JavascriptInterface
        fun ready() = runOnUiThread {
            engine.attach { e -> pushEvent(e) }
            val ok = hasPerms()
            js("window.nexusPerm&&window.nexusPerm($ok)")
            if (ok) startMesh() else requestPerms()
            engine.emitState()
        }

        @JavascriptInterface
        fun requestPermissions() = runOnUiThread { requestPerms() }

        @JavascriptInterface
        fun setActive(on: Boolean) = runOnUiThread { if (on) startMesh() else stopMesh() }

        @JavascriptInterface
        fun sendText(dst: String, text: String): String {
            val id = engine.newId()
            runOnUiThread { engine.sendText(dst, text, id) }
            return id
        }

        @JavascriptInterface
        fun sendFile(dst: String, name: String, mime: String, b64: String): String {
            val id = engine.newId()
            runOnUiThread {
                try {
                    engine.sendFile(dst, name, mime, Base64.decode(b64, Base64.DEFAULT), id)
                } catch (e: Exception) {
                    pushEvent(JSONObject().put("t", "error").put("m", "File send failed: ${e.message}"))
                }
            }
            return id
        }

        @JavascriptInterface fun setName(n: String) = runOnUiThread { engine.setName(n) }
        @JavascriptInterface fun setRelay(on: Boolean) = runOnUiThread { engine.setRelay(on) }
        @JavascriptInterface fun setAutoTrust(on: Boolean) = runOnUiThread { engine.setAutoTrust(on) }
        @JavascriptInterface fun setPass(p: String) = runOnUiThread { engine.setPass(p) }
        @JavascriptInterface fun approve(ep: String, ok: Boolean) = runOnUiThread { engine.approve(ep, ok) }
        @JavascriptInterface fun block(id: String) = runOnUiThread { engine.block(id) }
        @JavascriptInterface fun unblock(id: String) = runOnUiThread { engine.unblock(id) }
        @JavascriptInterface fun verify(id: String, ok: Boolean) = runOnUiThread { engine.setVerified(id, ok) }
        @JavascriptInterface fun clearTrusted() = runOnUiThread { engine.clearTrusted() }
        @JavascriptInterface fun rescan() = runOnUiThread { engine.rescan() }

        @JavascriptInterface
        fun saveFile(name: String, b64: String): String = try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }
            val f = File(dir, safe)
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            f.absolutePath
        } catch (e: Exception) {
            ""
        }
    }
}
