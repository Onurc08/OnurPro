package com.example.oyuncevir

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        root.addView(TextView(this).apply {
            text = "Oyun Çeviri\n\n" +
                "1) 'Alanları ayarla'ya bas, PS Remote Play'e geç.\n" +
                "   Kırmızı kutuyu altyazının çıktığı yere, mavi kutuyu çevirinin görüneceği yere taşı. " +
                "Sağ alt köşeden büyütebilirsin. Bitince 'KAYDET VE KAPAT'a bas.\n" +
                "2) Aşağıdan yazı boyutunu seç, 'Çeviriyi başlat'a bas.\n" +
                "Kırmızı ve mavi kutular üst üste gelmesin."
            textSize = 16f
        })

        root.addView(Button(this).apply {
            text = "1) Alanları ayarla"
            setOnClickListener { editAreas() }
        })

        root.addView(CheckBox(this).apply {
            text = "Oyunu gizle (sadece çeviri ve altyazı şeridi görünsün)"
            isChecked = prefs.getBoolean("cover", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("cover", c).apply() }
        })

        val tv = TextView(this).apply { text = "Çeviri yazı boyutu (üst sınır): ${prefs.getInt("size", 50)}%" }
        val sb = SeekBar(this).apply { max = 100; progress = prefs.getInt("size", 50) }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("size", p).apply()
                tv.text = "Çeviri yazı boyutu (üst sınır): $p%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(tv)
        root.addView(sb)

        root.addView(Button(this).apply {
            text = "2) Çeviriyi başlat"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TranslateService::class.java))
                stopService(Intent(this@MainActivity, EditService::class.java))
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun ensureOverlay(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        Toast.makeText(this, "Önce 'diğer uygulamaların üzerinde göster' iznini ver", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        return false
    }

    private fun editAreas() {
        if (!ensureOverlay()) return
        stopService(Intent(this, TranslateService::class.java))
        startService(Intent(this, EditService::class.java))
        Toast.makeText(this, "Remote Play'e geç, kutuları yerleştir", Toast.LENGTH_LONG).show()
    }

    private fun start() {
        if (!ensureOverlay()) return
        stopService(Intent(this, EditService::class.java))
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslateService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        }
    }
}
