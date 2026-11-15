package com.khancer.tiermaker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

/** Personalização do aplicativo (cores, papel de parede) e opções de animação. */
object AppSettings {
    private var prefs: SharedPreferences? = null
    private var dir: File? = null
    private var wallpaperCache: Bitmap? = null
    private var wallpaperLoaded = false

    fun init(ctx: Context) {
        if (prefs == null) {
            val app = ctx.applicationContext
            prefs = app.getSharedPreferences("tm_app", Context.MODE_PRIVATE)
            dir = app.filesDir
        }
    }

    private fun p(): SharedPreferences = prefs!!

    var accent: Int
        get() = p().getInt("accent", 0xFFFF8A3D.toInt())
        set(v) {
            p().edit().putInt("accent", v).apply()
        }

    var bgColor: Int
        get() = p().getInt("bg", 0xFF0D0D12.toInt())
        set(v) {
            p().edit().putInt("bg", v).apply()
        }

    var panelColor: Int
        get() = p().getInt("panel", 0xFF1A1A1F.toInt())
        set(v) {
            p().edit().putInt("panel", v).apply()
        }

    var textColor: Int
        get() = p().getInt("text", Color.WHITE)
        set(v) {
            p().edit().putInt("text", v).apply()
        }

    var wallpaperDim: Int
        get() = p().getInt("dim", 35)
        set(v) {
            p().edit().putInt("dim", v).apply()
        }

    var animEnabled: Boolean
        get() = p().getBoolean("anim", true)
        set(v) {
            p().edit().putBoolean("anim", v).apply()
        }

    var animFps: Int
        get() = p().getInt("fps", 12)
        set(v) {
            p().edit().putInt("fps", v).apply()
        }

    private fun wallpaperFile(): File = File(dir!!, "wallpaper.jpg")

    fun wallpaper(): Bitmap? {
        if (!wallpaperLoaded) {
            val f = wallpaperFile()
            wallpaperCache = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            wallpaperLoaded = true
        }
        return wallpaperCache
    }

    fun setWallpaper(b: Bitmap?) {
        val f = wallpaperFile()
        if (b == null) {
            f.delete()
        } else {
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        wallpaperCache = b
        wallpaperLoaded = true
    }

    fun reset() {
        p().edit().clear().apply()
        wallpaperFile().delete()
        wallpaperCache = null
        wallpaperLoaded = true
    }
}
