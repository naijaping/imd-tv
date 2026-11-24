package org.imdtv.iptvtv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class ParallelDataSourceTest {
    private val file = Random(7).nextBytes(1_000_003)

    /** A server over [file]: honours ranges (Content-Range) when [ranges]; every [failEvery]th open drops mid-read. */
    private inner class Server(val ranges: Boolean = true, val failEvery: Int = 0) : DataSource.Factory {
        val opens = AtomicInteger()
        override fun createDataSource(): DataSource = object : DataSource {
            private var pos = 0; private var end = 0; private var fail = false
            private var headers = emptyMap<String, List<String>>()
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                val n = opens.incrementAndGet()
                fail = failEvery > 0 && n % failEvery == 0
                pos = dataSpec.position.toInt()
                end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size else minOf(file.size, pos + dataSpec.length.toInt())
                headers = if (ranges) mapOf("content-range" to listOf("bytes $pos-${end - 1}/${file.size}")) else emptyMap()
                return if (ranges || dataSpec.length == C.LENGTH_UNSET.toLong()) (end - pos).toLong() else C.LENGTH_UNSET.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= end) return C.RESULT_END_OF_INPUT
                if (fail && pos % 1000 > 500) { fail = false; throw IOException("dropped") }
                val n = minOf(length, end - pos, 3000)
                System.arraycopy(file, pos, buffer, offset, n); pos += n
                return n
            }
            override fun getUri(): Uri? = null
            override fun getResponseHeaders() = headers
            override fun close() {}
        }
    }

    private fun readAll(src: DataSource, from: Long): ByteArray {
        val len = src.open(DataSpec.Builder().setUri("http://h/f.mkv").setPosition(from).build())
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (true) { val n = src.read(buf, 0, buf.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buf, 0, n) }
        src.close()
        assertEquals(file.size - from, len)
        return out.toByteArray()
    }

    @Test fun chunksComeBackInOrder() {
        val s = Server()
        assertArrayEquals(file.copyOfRange(12_345, file.size), readAll(ParallelDataSource(s, chunkBytes = 50_000, log = {}), 12_345))
        assertTrue(s.opens.get() >= 20) // ~20 chunks, each its own request
    }

    @Test fun droppedChunksAreRetried() {
        assertArrayEquals(file, readAll(ParallelDataSource(Server(failEvery = 3), chunkBytes = 50_000, log = {}), 0))
    }

    @Test fun serverWithoutRangesGetsOnePlainRequest() {
        val s = Server(ranges = false)
        assertArrayEquals(file, readAll(ParallelDataSource(s, chunkBytes = 50_000, log = {}), 0))
        assertEquals(2, s.opens.get()) // the probe, then the plain request
    }

    @Test fun contentRangeTotal() {
        assertEquals(123456789L, ParallelDataSource.totalLength(mapOf("Content-Range" to listOf("bytes 0-2097151/123456789"))))
        assertEquals(null, ParallelDataSource.totalLength(mapOf("Content-Range" to listOf("bytes 0-1/*"))))
    }
}
