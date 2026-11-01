package io.github.gvrulepack.module

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 模块自带的简易图片加载器（用于 changed_image 规则）。
 *
 * 目标进程里不一定有 Glide，也不应依赖目标应用的网络栈配置；
 * 这里用 HttpURLConnection + 内存缓存，失败静默跳过（只是少换一张图，不影响其它属性）。
 */
object AsyncImageLoader {

    private const val TAG = "GVRulePack"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_CACHE = 48

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "gv-rulepack-img").apply { isDaemon = true }
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_CACHE
    }

    /** 主线程调用；缓存命中直接设置，否则后台下载后 post 回主线程设置。 */
    fun load(view: ImageView, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val cached = synchronized(cache) { cache[url] }
        if (cached != null) {
            applyBitmap(view, cached)
            return
        }
        executor.execute {
            val bmp = download(url) ?: return@execute
            synchronized(cache) { cache[url] = bmp }
            view.post { runCatching { applyBitmap(view, bmp) } }
        }
    }

    private fun applyBitmap(view: ImageView, bmp: Bitmap) {
        if (!view.isAttachedToWindow) return
        val current = view.drawable
        if (current is BitmapDrawable && current.bitmap === bmp) return
        view.setImageBitmap(bmp)
    }

    private fun download(url: String): Bitmap? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                conn.inputStream.use { input ->
                    val bytes = input.readBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
