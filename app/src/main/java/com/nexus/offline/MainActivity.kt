package com.nexus.offline

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var waitingForSettings = false
    private var pendingMic: (() -> Unit)? = null
    private val engine get() = NexusApp.engine

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooserCb = null
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingMic
        pendingMic = null
        if (ok) p?.invoke()
        else pushEvent(JSONObject().put("t", "error").put("m", "Microphone permission is needed for voice calls."))
    }

    private fun withMic(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            block()
        } else {
            pendingMic = block
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Keep the permission result handling in one place. A denied permission must never
    // silently leave the UI looking as if discovery is running.
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermsChanged()
        } else {
            onPermsChanged()
            showPermissionHelp(missing)
        }
    }

    private fun requiredPerms(): List<String> {
        val permissions = mutableListOf<String>()

        // Google Nearby Connections can explicitly fail discovery with
        // MISSING_PERMISSION_ACCESS_COARSE_LOCATION, including on Android 13-16.
        // Keep this runtime permission in the request on every supported API level.
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION

        // Nearby Connections may require FINE_LOCATION even on Android 13-16.
        permissions += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
        } else {
            // Android 12+: Nearby devices / Bluetooth runtime permissions.
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: required when Nearby Connections uses Wi-Fi transports.
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return permissions.distinct()
    }

    private fun hasPerms(): Boolean = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        // Optional extras: microphone (voice calls) and notifications (call alerts). Not needed for the mesh itself.
        val extras = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            extras += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            extras += Manifest.permission.POST_NOTIFICATIONS
        }
        val all = missing + extras
        if (all.isEmpty()) {
            onPermsChanged()
            return
        }
        permLauncher.launch(all.toTypedArray())
    }

    private fun showPermissionHelp(missing: List<String>) {
        val permanentlyDenied = missing.any {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        val message = if (permanentlyDenied) {
            "NEXUS ko device discovery ke liye Location / Nearby Devices permission chahiye. Android ne permission prompt dobara dikhana band kar diya hai. App Settings kholkar required permissions Allow karo, phir NEXUS par wapas aao."
        } else {
            "Nearby phones discover karne ke liye Location / Nearby Devices permission allow karo. Android 13-16 par bhi Google Nearby Connections kuch devices par ACCESS_COARSE_LOCATION maang sakta hai. NEXUS is permission ko sirf nearby discovery ke liye use karta hai."
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Permission required")
            .setMessage(message)
            .setNegativeButton("Cancel", null)

        if (permanentlyDenied) {
            dialog.setPositiveButton("Open app settings") { _, _ -> openAppSettings() }
        } else {
            dialog.setPositiveButton("Try again") { _, _ -> requestPerms() }
            dialog.setNeutralButton("App settings") { _, _ -> openAppSettings() }
        }
        dialog.show()
    }

    private fun openAppSettings() {
        waitingForSettings = true
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun startMesh() {
        if (!hasPerms()) {
            requestPerms()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
    }

    private fun stopMesh() {
        stopService(Intent(this, MeshService::class.java))
    }

    private fun onPermsChanged() {
        val ok = hasPerms()
        if (::web.isInitialized) js("window.nexusPerm&&window.nexusPerm($ok)")
        if (ok) {
            ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
        } else {
            stopMesh()
        }
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun pushEvent(e: JSONObject) {
        if (::web.isInitialized) {
            js("window.nexusEvent&&window.nexusEvent(" + JSONObject.quote(e.toString()) + ")")
        }
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
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        engine.visible = true
    }

    override fun onStop() {
        engine.visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // If the user granted permissions in Android Settings, start discovery on return.
        if (waitingForSettings && hasPerms()) {
            waitingForSettings = false
            if (::web.isInitialized) js("window.nexusPerm&&window.nexusPerm(true)")
            ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
        }
    }

    override fun onDestroy() {
        engine.detach()
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() = runOnUiThread {
            engine.attach { e -> pushEvent(e) }
            val ok = hasPerms()
            js("window.nexusPerm&&window.nexusPerm($ok)")
            if (ok) startMesh()
            engine.emitState()
        }

        @JavascriptInterface
        fun requestPermissions() = runOnUiThread { requestPerms() }

        @JavascriptInterface
        fun setActive(on: Boolean) = runOnUiThread {
            getSharedPreferences("nexus", Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply()
            if (on) startMesh() else stopMesh()
        }

        @JavascriptInterface fun setAutoAccept(on: Boolean) = runOnUiThread { engine.setAutoAccept(on) }

        @JavascriptInterface
        fun batterySettings() = runOnUiThread {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } else {
                    pushEvent(JSONObject().put("t", "error").put("m", "Background running is already allowed."))
                }
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { }
            }
        }

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

        // ---- groups ----
        @JavascriptInterface fun newGid(): String = engine.newGid()
        @JavascriptInterface fun createGroup(gid: String, name: String, type: String) = runOnUiThread { engine.createGroup(gid, name, type) }
        @JavascriptInterface fun joinGroup(code: String) = runOnUiThread { engine.joinGroup(code) }
        @JavascriptInterface fun leaveGroup(gid: String) = runOnUiThread { engine.leaveGroup(gid) }
        @JavascriptInterface fun invite(gid: String, to: String) = runOnUiThread { engine.invite(gid, to) }
        @JavascriptInterface fun acceptInvite(gid: String) = runOnUiThread { engine.acceptInvite(gid) }
        @JavascriptInterface fun declineInvite(gid: String) = runOnUiThread { engine.declineInvite(gid) }

        // ---- calls ----
        @JavascriptInterface fun callStart(dst: String) = runOnUiThread { withMic { engine.callStart(dst) } }
        @JavascriptInterface fun callAccept() = runOnUiThread { withMic { engine.callAccept() } }
        @JavascriptInterface fun callReject() = runOnUiThread { engine.callReject() }
        @JavascriptInterface fun callEnd() = runOnUiThread { engine.callEnd() }
        @JavascriptInterface fun callMute(on: Boolean) = runOnUiThread { engine.callMute(on) }
        @JavascriptInterface fun callSpeaker(on: Boolean) = runOnUiThread { engine.callSpeaker(on) }

        // ---- helpers ----
        @JavascriptInterface
        fun copyText(text: String) = runOnUiThread {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NEXUS", text))
        }

        @JavascriptInterface
        fun shareText(text: String) = runOnUiThread {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(i, "Share"))
        }

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
