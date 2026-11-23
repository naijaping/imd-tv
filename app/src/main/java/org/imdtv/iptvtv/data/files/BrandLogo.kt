package org.imdtv.iptvtv.data.files

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

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
                        if (total > MAX_BYTES) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            // Only the header is decoded: checks it's an image BitmapFactory (and so Coil) can show.
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("not an image")
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

    /** Reset to default: the copy goes too. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { dir(context).deleteRecursively(); Unit }

    /** The saved path while its file is still there (app data cleared by hand: back to the default logo). */
    fun existing(path: String?): File? = path?.let(::File)?.takeIf { it.isFile }
}
