package com.example.oyuncevir

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

data class Area(var l: Float, var t: Float, var w: Float, var h: Float)

object Cfg {
    val SUB_DEF = Area(0.15f, 0.80f, 0.70f, 0.15f)
    val TR_DEF = Area(0.05f, 0.05f, 0.90f, 0.45f)

    @Suppress("DEPRECATION")
    fun screen(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    fun load(ctx: Context, key: String, def: Area): Area {
        val p = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)
        return Area(
            p.getFloat(key + "_l", def.l), p.getFloat(key + "_t", def.t),
            p.getFloat(key + "_w", def.w), p.getFloat(key + "_h", def.h)
        )
    }

    fun save(ctx: Context, key: String, a: Area) {
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit()
            .putFloat(key + "_l", a.l).putFloat(key + "_t", a.t)
            .putFloat(key + "_w", a.w).putFloat(key + "_h", a.h).apply()
    }
}
