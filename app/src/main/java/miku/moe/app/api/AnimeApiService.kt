package miku.moe.app.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

private const val DATA_AGENT = "AnimeXNonton 26.9.5/18"

interface AnimeApiService {
    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api/get_posts/")
    suspend fun getHomePosts(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("page") page: String,
        @Field("count") count: String,
        @Field("device_id") deviceId: String,
        @Field("device_token") deviceToken: String
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api/search_category_collection/")
    suspend fun searchAnime(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("search") search: String,
        @Field("page") page: String,
        @Field("count") count: String,
        @Field("lang") lang: String = "ID"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "User-Agent: okhttp/5.5.0"
    )
    @GET("animexnonton/api/phalcon/api/get_anime_genre_list/")
    suspend fun getGenreList(): GenreListResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api/get_anime_by_genre/")
    suspend fun getAnimeByGenre(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("genre1") genre1: String,
        @Field("genre2") genre2: String,
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID",
        @Field("sort") sort: String = "ASC"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api/get_category_not_ongoing/")
    suspend fun getAllAnime(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api/get_category_ongoing/")
    suspend fun getSchedule(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID"
    ): AnimeApiResponse
}

data class AnimeApiResponse(
    val status: String? = null,
    val count: Int? = null,
    @SerializedName("count_total") val countTotal: Int? = null,
    val pages: Int? = null,
    val posts: List<ApiAnimePost>? = null,
    val categories: List<ApiAnimePost>? = null
)

data class GenreListResponse(
    val status: String? = null,
    val genre: List<ApiGenre>? = null
)

data class ApiGenre(
    @SerializedName("genre_name") val genreName: String? = null,
    @SerializedName("genre_status_hide") val genreStatusHide: Int? = null
)

data class ApiAnimePost(
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("category_id") val categoryId: Int? = null,
    val cid: Int? = null,
    @SerializedName("channel_name") val channelName: String? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("category_image") val categoryImage: String? = null,
    @SerializedName("img_url") val imgUrl: String? = null,
    val created: String? = null,
    @SerializedName("count_view") val countView: String? = null,
    @SerializedName("total_views") val totalViews: String? = null,
    val ongoing: Int? = null,
    @SerializedName("is_hd_available") val isHdAvailable: Boolean? = null,
    @SerializedName("is_fhd_available") val isFhdAvailable: Boolean? = null,
    val rating: String? = null,
    val years: String? = null,
    val days: Int? = null,
    @SerializedName("count_anime") val countAnime: String? = null
)
