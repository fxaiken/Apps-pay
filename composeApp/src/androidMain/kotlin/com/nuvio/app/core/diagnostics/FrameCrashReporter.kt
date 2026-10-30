package com.nuvio.app.core.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * Keeps the last crash on disk and shows it on the next launch, so a user without
 * developer tools can copy the error and send it. Chains to the previous handler,
 * so the system crash behaviour is unchanged.
 */
object FrameCrashReporter {
    private const val FILE_NAME = "frame_last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
    }

    /**
     * Shows the report of the last crash, if there is one, and calls [onDone] when the
     * dialog closes. Returns false (and does not call [onDone]) when there is nothing to show.
     */
    fun showPendingReport(activity: Activity, onDone: () -> Unit): Boolean {
        val file = File(activity.filesDir, FILE_NAME)
        if (!file.exists()) return false
        val report = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (report.isBlank()) return false

        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                onDone()
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("A FRAME fechou da última vez")
            .setMessage("Toque em Copiar e envie este relatório para corrigir o erro.\n\n" + report.take(6_000))
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("FRAME crash", report))
                Toast.makeText(activity, "Relatório copiado", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Fechar") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
        return true
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val root = generateSequence(error) { it.cause }.last()
                val report = buildString {
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine("Causa: $root")
                    root.stackTrace.take(15).forEach { appendLine("  at $it") }
                    appendLine()
                    append(error.stackTraceToString())
                }
                File(context.filesDir, FILE_NAME).writeText(report.take(40_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
