package com.example.oyuncevir

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class BoxView(
    ctx: Context,
    label: String,
    color: Int,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val onChanged: () -> Unit
) : TextView(ctx) {

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0
    private var resizing = false

    init {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor((color and 0x00FFFFFF) or 0x55000000)
            setStroke(6, color)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = lp.x
                startY = lp.y
                startW = lp.width
                startH = lp.height
                resizing = e.x > width - 160 && e.y > height - 160
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).toInt()
                val dy = (e.rawY - downY).toInt()
                if (resizing) {
                    lp.width = (startW + dx).coerceAtLeast(200)
                    lp.height = (startH + dy).coerceAtLeast(100)
                } else {
                    lp.x = startX + dx
                    lp.y = startY + dy
                }
                wm.updateViewLayout(this, lp)
            }
            MotionEvent.ACTION_UP -> onChanged()
        }
        return true
    }
}

class EditService : Service() {
    private lateinit var wm: WindowManager
    private val views = mutableListOf<View>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (views.isNotEmpty()) return START_NOT_STICKY
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (sw, sh) = Cfg.screen(this)

        addBox("sub", "ALTYAZI ALANI\n(buradaki yazı okunacak)", Color.RED, Cfg.SUB_DEF, sw, sh)
        addBox("tr", "ÇEVİRİ KUTUSU\n(çeviri burada görünecek)", Color.BLUE, Cfg.TR_DEF, sw, sh)

        val btn = Button(this).apply {
            text = "KAYDET VE KAPAT"
            setOnClickListener { stopSelf() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        wm.addView(btn, lp)
        views.add(btn)
        return START_NOT_STICKY
    }

    private fun addBox(key: String, label: String, color: Int, def: Area, sw: Int, sh: Int) {
        val a = Cfg.load(this, key, def)
        val lp = WindowManager.LayoutParams(
            (a.w * sw).toInt(),
            (a.h * sh).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (a.l * sw).toInt()
            y = (a.t * sh).toInt()
        }
        val v = BoxView(this, label + "\n(sağ alt köşeden büyüt)", color, wm, lp) {
            Cfg.save(
                this, key,
                Area(lp.x.toFloat() / sw, lp.y.toFloat() / sh, lp.width.toFloat() / sw, lp.height.toFloat() / sh)
            )
        }
        wm.addView(v, lp)
        views.add(v)
    }

    override fun onDestroy() {
        if (::wm.isInitialized) {
            views.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        views.clear()
        super.onDestroy()
    }
}
