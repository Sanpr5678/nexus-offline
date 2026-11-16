package com.nexus.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera capture for video calls. Produces small upright JPEG frames (about 320x240) at a few frames per second.
 * Capture is tied to the activity lifecycle, so it pauses when the app is not on screen.
 * [params] returns (frames per second, JPEG quality) and is re-read for every frame.
 */
class VideoCam(
    private val act: AppCompatActivity,
    private val params: () -> Pair<Int, Int>,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var provider: ProcessCameraProvider? = null
    private var exec: ExecutorService? = null
    private var front = true
    @Volatile private var lastTs = 0L
    @Volatile private var active = false

    fun start() {
        active = true
        exec = Executors.newSingleThreadExecutor()
        val f = ProcessCameraProvider.getInstance(act)
        f.addListener({
            try {
                provider = f.get()
                bind()
            } catch (e: Exception) {
                onError("Camera could not start: " + e.message)
            }
        }, ContextCompat.getMainExecutor(act))
    }

    fun flip() {
        front = !front
        try {
            bind()
        } catch (e: Exception) {
            front = !front
            onError("Could not switch camera.")
        }
    }

    fun stop() {
        active = false
        try { provider?.unbindAll() } catch (e: Exception) { }
        exec?.shutdown()
        exec = null
    }

    private fun bind() {
        val p = provider ?: return
        val e = exec ?: return
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(e) { img ->
            try {
                if (active) process(img)
            } catch (x: Exception) {
                // drop this frame
            } finally {
                img.close()
            }
        }
        p.unbindAll()
        val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        p.bindToLifecycle(act, sel, analysis)
    }

    private fun process(img: ImageProxy) {
        val (fps, quality) = params()
        val now = System.currentTimeMillis()
        if (now - lastTs < 1000L / maxOf(1, fps)) return
        lastTs = now

        val w = img.width
        val h = img.height
        val nv = toNv21(img, w, h)
        val rot = img.imageInfo.rotationDegrees
        val out = ByteArrayOutputStream()
        if (rot == 0) {
            YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        } else {
            val tmp = ByteArrayOutputStream()
            YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 75, tmp)
            val src = BitmapFactory.decodeByteArray(tmp.toByteArray(), 0, tmp.size()) ?: return
            val m = Matrix()
            m.postRotate(rot.toFloat())
            val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            r.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (r !== src) r.recycle()
            src.recycle()
        }
        onFrame(out.toByteArray())
    }

    private fun toNv21(img: ImageProxy, w: Int, h: Int): ByteArray {
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val out = ByteArray(w * h * 3 / 2)
        var pos = 0
        val yb = yP.buffer
        for (row in 0 until h) {
            yb.position(row * yP.rowStride)
            yb.get(out, pos, w)
            pos += w
        }
        val ub = uP.buffer
        val vb = vP.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = vb.get(row * vP.rowStride + col * vP.pixelStride)
                out[pos++] = ub.get(row * uP.rowStride + col * uP.pixelStride)
            }
        }
        return out
    }
}
