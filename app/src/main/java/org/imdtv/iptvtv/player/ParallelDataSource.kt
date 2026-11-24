package org.imdtv.iptvtv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * A media server file (Plex, Jellyfin) fetched in [chunkBytes] ranges over [connections] connections at once, passed on
 * in order. One connection to a remote server averaged ~22 Mbit/s with deep dips on the Chromecast, below a 4K file's
 * bitrate (buffering every few seconds); Plezy's parallel connections got ~33 Mbit/s on the same Wi-Fi (#32).
 * At most [ahead] chunks are fetched or held ahead of the reader. A server that ignores ranges gets one plain request.
 * Never for IPTV providers: their accounts limit connections.
 */
@OptIn(UnstableApi::class)
class ParallelDataSource(
    private val upstream: DataSource.Factory,
    private val connections: Int = 4,
    private val chunkBytes: Int = 2 shl 20,
    /** Read-ahead kept outside the Java heap (direct buffers): Plezy held ~300 MB; the app's heap limit is 256 MB. */
    private val aheadBytes: Long = 16L shl 20,
    private val log: (String) -> Unit = { android.util.Log.i("IptvPlayer", it) },
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var spec: DataSpec? = null
    private var single: DataSource? = null
    private var pool: ExecutorService? = null
    private val chunks = ArrayDeque<Future<java.nio.ByteBuffer>>()
    private var next = 0L // offset from spec.position of the next chunk to ask for
    private var end = 0L // offset where the range ends
    private var cur: java.nio.ByteBuffer? = null
    private var taken = 0 // chunks read since open: the read-ahead grows with them
    private val maxAhead get() = (aheadBytes / chunkBytes).toInt().coerceAtLeast(2)
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    @Volatile private var closed = false

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    private fun newUpstream() = upstream.createDataSource().also { s -> listeners.forEach(s::addTransferListener) }

    override fun open(dataSpec: DataSpec): Long {
        closed = false; spec = dataSpec; next = 0; cur = null; taken = 0
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length <= chunkBytes) return openSingle(dataSpec)
        val first = newUpstream()
        val firstLen = first.open(dataSpec.subrange(0, chunkBytes.toLong()))
        uri = first.uri; headers = first.responseHeaders
        val total = totalLength(headers)
        if (total == null || firstLen == C.LENGTH_UNSET.toLong()) { // no ranges: start over with one plain request
            runCatching { first.close() }
            log("one connection: the server sent no Content-Range")
            return openSingle(dataSpec)
        }
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - dataSpec.position
        pool = Executors.newFixedThreadPool(connections) { r -> Thread(r, "parallel-http").apply { isDaemon = true } }
        val len = minOf(firstLen, end).toInt()
        chunks += pool!!.submit<java.nio.ByteBuffer> { fetch(0, len, first) }
        next = len.toLong()
        fill()
        log("$connections connections, ${aheadBytes shr 20} MiB ahead, from ${dataSpec.position} (${end shr 20} MiB left of ${total shr 20})")
        return end
    }

    private fun openSingle(dataSpec: DataSpec): Long = newUpstream().let { s ->
        single = s
        s.open(dataSpec).also { uri = s.uri; headers = s.responseHeaders }
    }

    private fun fill() {
        val p = pool ?: return
        // Starts at 2 after each open: the extractor jumps around the file (index, cues) before playing straight through.
        while (chunks.size < minOf(maxAhead, 2 + 2 * taken) && next < end) {
            val at = next; val len = minOf(chunkBytes.toLong(), end - at).toInt()
            chunks += p.submit<java.nio.ByteBuffer> { fetch(at, len, null) }
            next += len
        }
    }

    /** One chunk in full; a dropped connection is retried twice from where the chunk starts. */
    private fun fetch(at: Long, len: Int, opened: DataSource?): java.nio.ByteBuffer {
        var tries = 0
        while (true) {
            val src = if (tries == 0 && opened != null) opened else newUpstream()
            try {
                if (src !== opened) src.open(spec!!.subrange(at, len.toLong()))
                val out = java.nio.ByteBuffer.allocateDirect(len)
                val buf = ByteArray(64 * 1024)
                while (out.hasRemaining()) {
                    if (closed) throw InterruptedIOException("closed")
                    val r = src.read(buf, 0, minOf(buf.size, out.remaining()))
                    if (r == C.RESULT_END_OF_INPUT) throw EOFException("chunk at $at ended after ${out.position()} of $len bytes")
                    out.put(buf, 0, r)
                }
                out.flip()
                return out
            } catch (e: IOException) {
                if (closed || ++tries > 2) throw e
            } finally {
                runCatching { src.close() }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        single?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        var c = cur
        if (c == null || !c.hasRemaining()) {
            val f = chunks.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
            c = try { f.get() } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw InterruptedIOException()
            } catch (e: ExecutionException) { throw e.cause as? IOException ?: IOException(e.cause) }
            cur = c; taken++
            fill()
        }
        val n = minOf(length, c!!.remaining())
        c.get(buffer, offset, n)
        return n
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        closed = true
        chunks.forEach { it.cancel(true) }; chunks.clear()
        pool?.shutdownNow(); pool = null
        cur = null
        single?.let { s -> single = null; s.close() }
    }

    companion object {
        /** The file's full size from "Content-Range: bytes 0-2097151/123456789"; null when the server sent none. */
        fun totalLength(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
                ?.substringAfter('/', "")?.toLongOrNull()
    }
}
