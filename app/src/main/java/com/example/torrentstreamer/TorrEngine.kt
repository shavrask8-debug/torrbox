package com.example.torrentstreamer

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class TorrEngine(private val context: Context) {
    private var process: Process? = null

    fun start() {
        if (process != null) return

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Шукаємо файл у системній папці бібліотек
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val binFile = File(nativeDir, "libtorrserver.so")

                if (!binFile.exists()) {
                    Log.e("TorrEngine", "КРИТИЧНО: Сервер не знайдено в системі!")
                    return@launch
                }

                val dbDir = File(context.filesDir, "torr_db")
                if (!dbDir.exists()) dbDir.mkdirs()

                // Запуск двигуна
                val builder = ProcessBuilder(
                    binFile.absolutePath,
                    "-p", "8090",
                    "-d", dbDir.absolutePath
                )
                builder.directory(context.filesDir)
                process = builder.start()

                // ТУТ БУЛО ПОВІДОМЛЕННЯ - ТЕПЕР ТІЛЬКИ ЛОГ
                Log.d("TorrEngine", "Двигун успішно запущено у фоні")

            } catch (e: Exception) {
                Log.e("TorrEngine", "Помилка старту двигуна: ${e.message}")
            }
        }
    }

    // Допоміжна функція залишається на випадок критичних помилок
    private fun showToast(msg: String) {
        GlobalScope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        Log.d("TorrEngine", "Двигун зупинено")
    }
}