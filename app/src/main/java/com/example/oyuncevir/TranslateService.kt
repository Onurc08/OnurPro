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
import android.widget.LinearLayout
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
    private var nullCount = 0
    private var paused = false
    private var editing = false
    private val editViews = mutableListOf<View>()
    private var editBtn: TextView? = null
    private var panelRow: View? = null
    private val coverViews = mutableListOf<View>()
    private var pauseBtn: TextView? = null
    private var debug = true
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

        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        debug = prefs.getBoolean("debug", true)

        loadSub()
        buildCover()

        createOverlay(prefs.getInt("size", 50))
        addControlPanel()
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
            if (!busy && !paused) captureAndTranslate()
            handler.postDelayed(this, 500)
        }
    }

    private fun captureAndTranslate() {
        val image = reader?.acquireLatestImage()
        if (image == null) {
            nullCount++
            if (debug && nullCount == 8) showText("Görüntü akışı gelmiyor")
            return
        }
        nullCount = 0
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

        if (isBlack(crop)) {
            if (debug) showText("Yakalanan görüntü SİYAH: Remote Play ekran yakalamayı engelliyor olabilir")
            return
        }

        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val text = result.text.replace("\n", " ").trim()
                if (text.length < 3) {
                    emptyCount++
                    if (emptyCount >= 3) {
                        lastWords = emptySet()
                        if (debug) showText("Altyazı okunamadı (OCR boş)") else overlay?.visibility = View.GONE
                    }
                    busy = false
                } else {
                    emptyCount = 0
                    val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.toSet()
                    if (isSame(words, lastWords)) {
                        busy = false
                    } else {
                        lastWords = words
                        if (debug) showText("OCR: " + text.take(120))
                        translateText(text.take(500)) { out ->
                            if (out != null) {
                                showText(out)
                            } else {
                                lastWords = emptySet()
                                if (debug) showText("Çeviri başarısız (internet?): " + text.take(80))
                            }
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

    private fun isBlack(b: Bitmap): Boolean {
        val stepX = maxOf(1, b.width / 24)
        val stepY = maxOf(1, b.height / 8)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 30) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun addControlPanel() {
        fun btn(label: String, color: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(color)
                textSize = 14f
                setPadding(28, 18, 28, 18)
                setOnClickListener { onClick() }
            }
        }
        val pause = btn("⏸ DURAKLAT", 0xCC1565C0.toInt()) { togglePause() }
        pauseBtn = pause
        val edit = btn("✎ DÜZENLE", 0xCC2E7D32.toInt()) { toggleEdit() }
        editBtn = edit
        val stop = btn("✕ KAPAT", 0xCCB00020.toInt()) { stopSelf() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pause)
            addView(edit)
            addView(stop)
        }
        panelRow = row
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(row, lp)
        added.add(row)
    }

    private fun loadSub() {
        val sub = Cfg.load(this, "sub", Cfg.SUB_DEF)
        subX = (sub.l * width).toInt().coerceIn(0, width - 20)
        subY = (sub.t * height).toInt().coerceIn(0, height - 20)
        subW = (sub.w * width).toInt().coerceIn(20, width - subX)
        subH = (sub.h * height).toInt().coerceIn(20, height - subY)
    }

    // Oyunu gizleyen siyah şeritler: altyazı alanı açık kalır, çünkü okunması gerekiyor
    private fun buildCover() {
        coverViews.forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
            added.remove(it)
        }
        coverViews.clear()
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (prefs.getBoolean("cover", true)) {
            fun bar(x: Int, y: Int, bw: Int, bh: Int) {
                if (bw > 0 && bh > 0) {
                    val v = View(this).apply { setBackgroundColor(Color.BLACK) }
                    addWin(v, x, y, bw, bh)
                    coverViews.add(v)
                }
            }
            bar(0, 0, width, subY)
            bar(0, subY + subH, width, height - subY - subH)
            bar(0, subY, subX, subH)
            bar(subX + subW, subY, width - subX - subW, subH)
        }
        // Yeni şeritler üstte kalmasın: çeviri kutusu ve panel yeniden en üste alınır
        front(overlay)
        front(panelRow)
    }

    private fun front(v: View?) {
        if (v == null) return
        val lp = v.layoutParams ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        wm.addView(v, lp)
    }

    private fun placeOverlay() {
        val tr = Cfg.load(this, "tr", Cfg.TR_DEF)
        val v = overlay ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (tr.l * width).toInt()
        lp.y = (tr.t * height).toInt()
        lp.width = (tr.w * width).toInt().coerceAtLeast(200)
        lp.height = (tr.h * height).toInt().coerceAtLeast(100)
        wm.updateViewLayout(v, lp)
    }

    private fun addEditBox(key: String, label: String, color: Int, def: Area) {
        val a = Cfg.load(this, key, def)
        val lp = WindowManager.LayoutParams(
            (a.w * width).toInt(),
            (a.h * height).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (a.l * width).toInt()
            y = (a.t * height).toInt()
        }
        val v = BoxView(this, label, color, wm, lp) {
            Cfg.save(
                this, key,
                Area(lp.x.toFloat() / width, lp.y.toFloat() / height, lp.width.toFloat() / width, lp.height.toFloat() / height)
            )
        }
        wm.addView(v, lp)
        editViews.add(v)
    }

    private fun toggleEdit() {
        if (!editing) {
            editing = true
            paused = true
            coverViews.forEach { it.visibility = View.GONE }
            overlay?.visibility = View.GONE
            editBtn?.text = "✔ KAYDET"
            addEditBox("sub", "ALTYAZI ALANI\n(buradaki yazı okunacak)\n(sağ alt köşeden büyüt)", Color.RED, Cfg.SUB_DEF)
            addEditBox("tr", "ÇEVİRİ KUTUSU\n(çeviri burada görünecek)\n(sağ alt köşeden büyüt)", Color.BLUE, Cfg.TR_DEF)
            front(panelRow)
        } else {
            editViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
            editViews.clear()
            editing = false
            loadSub()
            buildCover()
            placeOverlay()
            paused = false
            lastWords = emptySet()
            emptyCount = 0
            editBtn?.text = "✎ DÜZENLE"
        }
    }

    private fun togglePause() {
        if (editing) return
        paused = !paused
        if (paused) {
            coverViews.forEach { it.visibility = View.GONE }
            overlay?.visibility = View.GONE
            pauseBtn?.text = "▶ DEVAM"
        } else {
            coverViews.forEach { it.visibility = View.VISIBLE }
            lastWords = emptySet()
            emptyCount = 0
            pauseBtn?.text = "⏸ DURAKLAT"
        }
    }

    private fun showText(t: String) {
        if (paused) return
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
            editViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
            added.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        added.clear()
        executor.shutdownNow()
        recognizer.close()
        translator.close()
        super.onDestroy()
    }
}
