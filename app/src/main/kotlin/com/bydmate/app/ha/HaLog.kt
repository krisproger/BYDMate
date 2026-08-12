package com.bydmate.app.ha

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый лог для модуля HA-телеметрии. Логкат на DiLink активно
 * ротируется, поэтому для диагностики работы модуля пишем в файл
 * `Downloads/ha_telemetry.log` (кап ~[MAX_LINES] строк) — так к нему легко
 * добраться с ПК/через файловый менеджер устройства. Каждая запись
 * дублируется в logcat под тегом [TAG] для удобства adb logcat | grep.
 *
 * Потокобезопасно: append может вызываться из CoroutineScope(Dispatchers.IO)
 * и из однократных вызовов на старте/остановке.
 */
object HaLog {
    private const val TAG = "HaTelemetry"
    private const val MAX_LINES = 500
    private const val FILE_NAME = "ha_telemetry.log"
    private val lock = Any()

    /** Явно заданный каталог (для тестов без Context). */
    @Volatile private var dirOverride: File? = null

    fun setDirOverride(dir: File) {
        dirOverride = dir
    }

    fun append(context: Context, entry: String) {
        appendWithTs(context, System.currentTimeMillis(), entry)
    }

    internal fun appendWithTs(context: Context, tsMs: Long, entry: String) {
        Log.i(TAG, entry)
        synchronized(lock) {
            try {
                val dir = logDir(context)
                val file = File(dir, FILE_NAME)
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(tsMs))
                val line = "$stamp  $entry"
                val existing = if (file.exists()) file.readText().lines() else emptyList()
                val kept = existing.takeLast(MAX_LINES - 1)
                file.writeText((kept + line).joinToString("\n") + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write ha_telemetry.log: ${e.message}")
            }
        }
    }

    /** Последние строки лога (для вывода в UI диагностики). */
    fun read(context: Context): List<String> {
        synchronized(lock) {
            val dir = logDir(context)
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return emptyList()
            return file.readText().lines().filter { it.isNotBlank() }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            val dir = logDir(context)
            val file = File(dir, FILE_NAME)
            if (file.exists()) file.delete()
        }
    }

    /** Каталог лога: переопределённый (тесты) или публичный Downloads. */
    private fun logDir(context: Context): File {
        dirOverride?.let { return it }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
}