package com.example.torrentstreamer.tmdb

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

interface CinemetaApi {

    @GET("catalog/{type}/top/search={query}.json")
    suspend fun searchMeta(
        @Path("type") type: String, // movie або series
        @Path("query") query: String
    ): CinemetaResponse

    companion object {
        private const val BASE_URL = "https://v3-cinemeta.strem.io/"

        fun create(): CinemetaApi {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(CinemetaApi::class.java)
        }
    }
}