package io.github.gvrulepack.module

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 模块自带的简易图片加载器（用于 changed_image 规则）。
 *
 * 目标进程里不一定有 Glide，也不应依赖目标应用的网络栈配置；
 * 这里用 HttpURLConnection + 内存缓存，失败静默跳过（只是少换一张图，不影响其它属性）。
 *
 * 安全边界（规则可被第三方构造，本加载器运行在宿主进程，必须自防）：
 * - 下载体积上限 [MAX_BYTES]、解码分辨率上限 [MAX_DIMEN]（防 OOM 炸弹）
 * - 拒绝私网/回环/链路本地等目标（防 SSRF）：URL 字面量在主线程查，
 *   域名解析后的 IP 在后台线程二次校验（防 DNS rebinding 到内网）
 * - in-flight 去重：同一 URL 并发触发只下载一次
 */
object AsyncImageLoader {

    private const val TAG = "GVRulePack"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_CACHE = 48
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_DIMEN = 2048

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "gv-rulepack-img").apply { isDaemon = true }
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_CACHE
    }

    /** 正在下载中的 URL（与 cache 同锁保护） */
    private val inFlight = HashSet<String>()

    /** 主线程调用；缓存命中直接设置，否则后台下载后 post 回主线程设置。 */
    fun load(view: ImageView, url: String) {
        if (!schemeAllowed(url)) return
        synchronized(cache) {
            val cached = cache[url]
            if (cached != null) {
                applyBitmap(view, cached)
                return
            }
            if (!inFlight.add(url)) return
        }
        executor.execute {
            val bmp = download(url)
            synchronized(cache) {
                inFlight.remove(url)
                if (bmp != null) cache[url] = bmp
            }
            if (bmp != null) {
                view.post { runCatching { applyBitmap(view, bmp) } }
            }
        }
    }

    private fun applyBitmap(view: ImageView, bmp: Bitmap) {
        if (!view.isAttachedToWindow) return
        val current = view.drawable
        if (current is BitmapDrawable && current.bitmap === bmp) return
        view.setImageBitmap(bmp)
    }

    private fun schemeAllowed(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /** 拒绝 IP 字面量指向的私网/回环/链路本地/组播等（域名的解析后校验在 [hostAllowed]）。 */
    private fun literalHostBlocked(host: String): Boolean {
        val h = host.trim('[').trim(']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        val ip = parseIpv4(h) ?: return false // 域名交给 hostAllowed 解析后判断
        val (a, b) = ip
        return when {
            a == 0 -> true                                  // 0.0.0.0/8
            a == 10 -> true                                 // 10/8
            a == 127 -> true                                // 回环
            a == 169 && b == 254 -> true                    // 链路本地（含云 metadata 169.254.169.254）
            a == 172 && b in 16..31 -> true                 // 172.16/12
            a == 192 && b == 168 -> true                    // 192.168/16
            a == 100 && b in 64..127 -> true                // CGNAT 100.64/10
            a in 224..239 -> true                           // 组播
            a == 240 -> true                                // 保留
            else -> false
        }
    }

    private fun parseIpv4(host: String): Pair<Int, Int>? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        if (host.length > 1 && host.startsWith("0")) return null // 八进制歧义写法直接拒
        return nums[0] to nums[1]
    }

    /** 解析后校验（后台线程调用）：任一解析结果落在内网段即拒。 */
    private fun hostAllowed(host: String): Boolean {
        if (literalHostBlocked(host)) return false
        return runCatching {
            InetAddress.getAllByName(host).none { addr ->
                addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress || addr.isMulticastAddress ||
                    (addr is java.net.Inet6Address && isUniqueLocalV6(addr)) ||
                    addr.hostAddress?.let { literalHostBlocked(it) } == true
            }
        }.getOrDefault(false)
    }

    /** IPv6 唯一本地地址 fc00::/7 */
    private fun isUniqueLocalV6(addr: java.net.Inet6Address): Boolean {
        val first = addr.address.firstOrNull() ?: return false
        return (first.toInt() and 0xFE) == 0xFC
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
                // 解析后主机校验（防线 2：域名解析到内网的情况）
                if (!hostAllowed(conn.url.host ?: "")) return null
                conn.inputStream.use { input ->
                    val bytes = readCapped(input) ?: return null
                    decodeSampled(bytes)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 上限 [MAX_BYTES] 的流读取；超限返回 null（视为失败，不再继续占内存）。 */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    /** 两遍解码：先量尺寸，超过 [MAX_DIMEN] 时按 2 的幂采样。 */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMEN || bounds.outHeight / sample > MAX_DIMEN) {
            sample *= 2
            if (sample > MAX_DIMEN) break
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
