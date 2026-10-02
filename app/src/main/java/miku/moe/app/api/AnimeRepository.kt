package miku.moe.app.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AnimeRepository(
    private val api: AnimeApiService = defaultApi
) {
    suspend fun getHomePosts(page: Int, count: Int, deviceId: String): AnimeApiResponse {
        return api.getHomePosts(page = page.toString(), count = count.toString(), deviceId = deviceId, deviceToken = deviceId)
    }

    suspend fun searchAnime(query: String, page: Int, count: Int): AnimeApiResponse {
        return api.searchAnime(search = query, page = page.toString(), count = count.toString())
    }

    suspend fun getGenreList(): GenreListResponse = api.getGenreList()

    suspend fun getAnimeByGenre(genreName: String, page: Int, count: Int): AnimeApiResponse {
        return api.getAnimeByGenre(genre1 = genreName, genre2 = genreName, page = page.toString(), count = count.toString())
    }

    suspend fun getAllAnime(page: Int, count: Int): AnimeApiResponse = api.getAllAnime(page = page.toString(), count = count.toString())

    suspend fun getSchedule(page: Int, count: Int): AnimeApiResponse = api.getSchedule(page = page.toString(), count = count.toString())

    companion object {
        private val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private val defaultApi: AnimeApiService by lazy {
            Retrofit.Builder()
                .baseUrl("https://wincamp.web.id/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AnimeApiService::class.java)
        }
    }
}
