package org.imdtv.iptvtv.data.support

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Keeps this app's own log across restarts: a child `logcat --pid=<us>` writes 512 KB + 1 rotated file into files/logs.
 * logcat ends by itself when our pid is gone, so nothing is left running. Before each start the previous run's files are
 * folded into prev.log (capped), so we never depend on whether logcat appends or truncates an existing file.
 */
object LogCollector {
    private const val TAG = "LogCollector"
    private const val PREV_MAX = 512 * 1024

    private fun dir(c: Context) = File(c.filesDir, "logs")

    /** Call off the main thread. Never throws. */
    fun start(c: Context) {
        try {
            val d = dir(c).apply { mkdirs() }
            val prev = File(d, "prev.log")
            val old = listOf(File(d, "app.log.1"), File(d, "app.log")).filter { it.isFile }
            if (old.isNotEmpty()) {
                val all = (listOf(prev) + old).filter { it.isFile }.joinToString("") { it.readText() }
                prev.writeText(all.takeLast(PREV_MAX))
                old.forEach { it.delete() }
            }
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-f", File(d, "app.log").path, "-r", "512", "-n", "1")
                .redirectErrorStream(true).apply { if (android.os.Build.VERSION.SDK_INT >= 26) redirectOutput(File("/dev/null")) }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Log capture not started", e)
        }
    }

    /** Oldest first: earlier runs, the rotated file, the current file. */
    fun read(c: Context): Pair<String, String> {
        val d = dir(c)
        fun t(n: String) = runCatching { File(d, n).takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        return t("prev.log") + t("app.log.1") to t("app.log")
    }
}
