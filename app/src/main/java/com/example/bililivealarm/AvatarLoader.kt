package com.example.bililivealarm
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
object AvatarLoader {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    fun load(urlRaw: String?, imageView: ImageView) {
        if (urlRaw.isNullOrEmpty()) {
            imageView.setImageResource(android.R.drawable.sym_def_app_icon)
            return
        }
        val fixedUrl = when {
            urlRaw.startsWith("//") -> "https:$urlRaw"
            urlRaw.startsWith("http://") -> urlRaw.replaceFirst("http://", "https://")
            else -> urlRaw
        }
        imageView.tag = fixedUrl
        val cached = cache.get(fixedUrl)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageResource(android.R.drawable.sym_def_app_icon)
        executor.execute {
            try {
                val conn = URL(fixedUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) {
                        val circleBmp = toCircleBitmap(bmp)
                        cache.put(fixedUrl, circleBmp)
                        mainHandler.post {
                            if (imageView.tag == fixedUrl) {
                                imageView.setImageBitmap(circleBmp)
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    private fun toCircleBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val rect = Rect(0, 0, size, size)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }
}