package com.stealthcalc.core.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based logger that captures uncaught exceptions and writes them to
 * `filesDir/logs/app.log` so the user can export them from Settings even after
 * the process was killed.
 *
 * Installed from [com.stealthcalc.StealthCalcApp.onCreate] BEFORE Hilt is
 * ready, so this is an object with no DI dependencies.
 */
object AppLogger {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val ROTATED_FILE = "app.log.1"
    private const val MAX_BYTES = 1_000_000L // 1 MB

    private val isoFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    /** Returns the current log file, creating the parent directory if needed. */
    fun logFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
        return File(dir, LOG_FILE)
    }

    /**
     * Installs an uncaught-exception handler that records each crash to the
     * log file, then delegates to the previously-installed handler so the OS
     * still terminates the process normally.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        runCatching { log(appContext, "AppLogger", "Crash handler installed") }
    }

    /** Appends a timestamped log line. Never throws. */
    fun log(context: Context, tag: String, message: String) {
        runCatching {
            val file = logFile(context)
            rotateIfNeeded(file)
            FileWriter(file, true).use { writer ->
                writer.append(timestamp())
                writer.append(" [")
                writer.append(tag)
                writer.append("] ")
                writer.append(message)
                writer.append('\n')
            }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile(context)
        rotateIfNeeded(file)
        val (versionName, versionCode) = appVersion(context)
        FileWriter(file, true).use { writer ->
            PrintWriter(writer).use { pw ->
                pw.println()
                pw.println("==== [FATAL] ${timestamp()} ====")
                pw.println("app: ${context.packageName} v$versionName ($versionCode)")
                pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println("thread: ${thread.name}")
                pw.println("message: ${throwable.message}")
                throwable.printStackTrace(pw)
                pw.println("==== [/FATAL] ====")
            }
        }
    }

    private fun appVersion(context: Context): Pair<String, Long> {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            (info.versionName ?: "?") to code
        }.getOrElse { "?" to 0L }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_BYTES) {
            val rotated = File(file.parentFile, ROTATED_FILE)
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        }
    }

    private fun timestamp(): String = isoFormat.format(Date())
}
