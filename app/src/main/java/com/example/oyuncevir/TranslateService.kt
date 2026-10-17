package com.example.oyuncevir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class TranslateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: TextView? = null
    private val added = mutableListOf<View>()
    private lateinit var wm: WindowManager

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    private val executor = Executors.newSingleThreadExecutor()
    private var modelReady = false
    private var busy = false
    private var lastWords: Set<String> = emptySet()
    private var emptyCount = 0
    private var width = 0
    private var height = 0
    private var subX = 0
    private var subY = 0
    private var subW = 0
    private var subH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val (w, h) = Cfg.screen(this)
        width = w
        height = h
        val dpi = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "oyuncevir", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Altyazı okuma alanı (piksel)
        val sub = Cfg.load(this, "sub", Cfg.SUB_DEF)
        subX = (sub.l * width).toInt().coerceIn(0, width - 20)
        subY = (sub.t * height).toInt().coerceIn(0, height - 20)
        subW = (sub.w * width).toInt().coerceIn(20, width - subX)
        subH = (sub.h * height).toInt().coerceIn(20, height - subY)

        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)

        // Oyunu gizleyen siyah şeritler: altyazı alanı açık kalır, çünkü okunması gerekiyor
        if (prefs.getBoolean("cover", true)) {
            fun bar(x: Int, y: Int, bw: Int, bh: Int) {
                if (bw > 0 && bh > 0) {
                    addWin(View(this).apply { setBackgroundColor(Color.BLACK) }, x, y, bw, bh)
                }
            }
            bar(0, 0, width, subY)
            bar(0, subY + subH, width, height - subY - subH)
            bar(0, subY, subX, subH)
            bar(subX + subW, subY, width - subX - subW, subH)
        }

        createOverlay(prefs.getInt("size", 50))
        // Çevrimdışı yedek model arka planda iner; asıl çeviri Google ile yapılır
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true }
        showText("Hazır. Oyuna geç.")
        hideLater()

        handler.postDelayed(loop, 500)
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!busy) captureAndTranslate()
            handler.postDelayed(this, 500)
        }
    }

    private fun captureAndTranslate() {
        val image = reader?.acquireLatestImage() ?: return
        val crop: Bitmap
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            crop = Bitmap.createBitmap(full, subX, subY, subW, subH)
            full.recycle()
        } finally {
            image.close()
        }

        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val text = result.text.replace("\n", " ").trim()
                if (text.length < 3) {
                    emptyCount++
                    if (emptyCount >= 3) { overlay?.visibility = View.GONE; lastWords = emptySet() }
                    busy = false
                } else {
                    emptyCount = 0
                    val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.toSet()
                    if (isSame(words, lastWords)) {
                        busy = false
                    } else {
                        lastWords = words
                        translateText(text.take(500)) { out ->
                            if (out != null) showText(out) else lastWords = emptySet()
                            busy = false
                        }
                    }
                }
            }
            .addOnFailureListener { busy = false }
    }

    // Önce Google, olmazsa çevrimdışı ML Kit. Geri çağrı her zaman ana iş parçacığında çalışır.
    private fun translateText(text: String, cb: (String?) -> Unit) {
        executor.execute {
            val g = googleTranslate(text)
            if (g != null) {
                handler.post { cb(g) }
            } else {
                handler.post {
                    if (modelReady) {
                        translator.translate(text)
                            .addOnSuccessListener { cb(it) }
                            .addOnFailureListener { cb(null) }
                    } else {
                        cb(null)
                    }
                }
            }
        }
    }

    private fun googleTranslate(text: String): String? {
        return try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).getString(0))
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun isSame(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return inter / union > 0.8f
    }

    private fun addWin(v: View, x: Int, y: Int, w: Int, h: Int) {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        wm.addView(v, lp)
        added.add(v)
    }

    private fun createOverlay(sizePct: Int) {
        val tr = Cfg.load(this, "tr", Cfg.TR_DEF)
        val maxSp = 20 + sizePct * 80 / 100
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setAutoSizeTextTypeUniformWithConfiguration(14, maxSp, 2, TypedValue.COMPLEX_UNIT_SP)
            visibility = View.GONE
        }
        overlay = tv
        addWin(
            tv,
            (tr.l * width).toInt(), (tr.t * height).toInt(),
            (tr.w * width).toInt().coerceAtLeast(200), (tr.h * height).toInt().coerceAtLeast(100)
        )
    }

    private fun showText(t: String) {
        overlay?.text = t
        overlay?.visibility = View.VISIBLE
    }

    private fun hideLater() {
        handler.postDelayed({ if (lastWords.isEmpty()) overlay?.visibility = View.GONE }, 2500)
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("oc", "Oyun Çeviri", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "oc")
            .setContentTitle("Oyun Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        if (::wm.isInitialized) {
            added.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        added.clear()
        executor.shutdownNow()
        recognizer.close()
        translator.close()
        super.onDestroy()
    }
}
