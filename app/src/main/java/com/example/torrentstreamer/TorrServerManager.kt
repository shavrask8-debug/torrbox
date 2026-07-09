package com.example.torrentstreamer

import android.content.Context
import android.util.Log
import java.io.File

object TorrServerManager {
    private var process: Process? = null

    fun start(context: Context) {
        if (process != null) return // Якщо вже запущений — нічого не робимо
        // Запуск в окремому потоці для очищення старих портів та безпечного старту
        Thread {
            try {
                // Перед стартом м'яко вимикаємо стару завислу копію сервера на порту 8090,
                // щоб звільнити файлові блокування SQLite та порт.
                val url = java.net.URL("http://127.0.0.1:8090/shutdown")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                connection.responseCode // Викликає з'єднання
                connection.disconnect()

                // Коротка пауза, щоб ОС встигла звільнити порт 8090
                Thread.sleep(500)
                Log.d("TorrServer", "Попередній завислий процес TorrServer успішно зупинено.")
            } catch (_: Exception) {
                // Старий сервер не був запущений — це нормальний штатний випадок
            }

            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val binaryPath = "$nativeDir/libtorrserver.so"
                val dbPath = context.filesDir.absolutePath

                val binaryFile = File(binaryPath)
                if (!binaryFile.exists()) {
                    Log.e("TorrServer", "Бінарний файл не знайдено за шляхом: $binaryPath")
                    return@Thread
                }

                Log.d("TorrServer", "Запуск рушія з: $binaryPath")
                Log.d("TorrServer", "Шлях до бази даних: $dbPath")

                // Запускаємо через ProcessBuilder
                val p = ProcessBuilder(binaryPath, "-d", dbPath)
                    .redirectErrorStream(true)
                    .start()
                process = p

                // Споживаємо лог-потік у фоновому потоці, запобігаючи переповненню буфера та зависанню Go
                Thread {
                    try {
                        p.inputStream.bufferedReader().use { reader ->
                            var line: String? = null // ВИПРАВЛЕНО: Явна ініціалізація для компілятора K2
                            while (reader.readLine().also { line = it } != null) {
                                Log.d("TorrServer_GoLog", line ?: "")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TorrServer", "Лог-потік TorrServer завершено: ${e.message}")
                    }
                }.start()

                Log.d("TorrServer", "Рушій успішно ініціалізовано на порту 8090!")
            } catch (e: Exception) {
                Log.e("TorrServer", "Критична помилка старту TorrServer: ${e.message}", e)
            }
        }.start()
    }

}