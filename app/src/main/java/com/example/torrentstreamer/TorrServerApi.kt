package com.example.torrentstreamer

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface TorrServerApi {

    // ОНОВЛЕНО: Повертаємо нативний ResponseBody, щоб уникнути збоїв Gson при порожньому 200 OK від сервера Matrix
    @POST("torrents")
    suspend fun actionPost(@Body request: TorrentAction): okhttp3.ResponseBody

    @Multipart
    @POST("torrent/upload")
    suspend fun uploadTorrent(
        @Part file: MultipartBody.Part,
        @Part("title") title: RequestBody,
        @Part("poster") poster: RequestBody,
        @Part("data") data: RequestBody,
        @Part("save") save: RequestBody
    ): Any

    @POST("settings")
    suspend fun getSettings(@Body payload: SettingsPayload): TorrSettings

    @POST("settings")
    suspend fun saveSettings(@Body payload: SettingsPayload): ResponseBody

    @GET("settings")
    @Deprecated("GET settings returns 404 in TorrServer Matrix", ReplaceWith("getSettings()"))
    suspend fun getSettingsLegacy(): TorrSettings

    companion object {
        private const val BASE_URL = "http://127.0.0.1:8090/"

        fun create(): TorrServerApi {
            val logger = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TorrServerApi::class.java)
        }
    }
}