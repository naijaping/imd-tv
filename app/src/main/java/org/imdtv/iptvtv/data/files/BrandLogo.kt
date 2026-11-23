package org.imdtv.iptvtv.data.files

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The guide's brand logo (Appearance › Live TV › Brand logo): the picked image is copied into app storage, so it
 * survives the original moving or a USB stick leaving. Each copy gets a new name, so Coil never shows a cached old one.
 */
object BrandLogo {
    /** Larger than any sensible logo; stops a wrong pick (a video) filling the box's storage. */
    const val MAX_BYTES = 8L * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "brand_logo")

    /** Copies [open]'s image in and returns its path, or throws [IOException] when it can't be read or isn't an image. */
    suspend fun save(context: Context, open: () -> InputStream): String = withContext(Dispatchers.IO) {
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, "incoming.tmp")
        try {
            open().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw TooLarge()
                        out.write(buf, 0, n)
                    }
                }
            }
            // Only the header is decoded: checks it's an image BitmapFactory (and so Coil) can show.
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) throw NotImage()
            val ext = when (o.outMimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
            val dest = File(d, "logo_${System.currentTimeMillis()}.$ext")
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
            // Only the new copy stays.
            d.listFiles().orEmpty().filter { it != dest }.forEach { it.delete() }
            dest.path
        } finally {
            tmp.delete()
        }
    }

    /** Why a logo wasn't taken; the current one stays. */
    class TooLarge : IOException("too large")
    class NotImage : IOException("not an image")
    class HttpFailure(val code: Int) : IOException("HTTP $code")

    /** A typed Logo image URL when it's a usable http(s) address with a host; null otherwise. */
    fun validUrl(text: String): HttpUrl? = text.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        ?.takeIf { ' ' !in it }?.toHttpUrlOrNull()?.takeIf { it.host.isNotBlank() }

    /** Reset to default: the copy goes too. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { dir(context).deleteRecursively(); Unit }

    /** The saved path while its file is still there (app data cleared by hand: back to the default logo). */
    fun existing(path: String?): File? = path?.let(::File)?.takeIf { it.isFile }
}
