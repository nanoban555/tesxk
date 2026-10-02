@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package miku.moe.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.GridView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.google.android.material.color.MaterialColors
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import miku.moe.app.api.AnimeRepository
import miku.moe.app.api.ApiAnimePost
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun String?.asAnimeSourceId(): String {
    val value = this?.trim().orEmpty()
    return if (AnimeSettingsManager.isValidSource(value)) value else AnimeSettingsManager.SOURCE_DEFAULT
}

private fun String?.asAnimeSourceLabel(sourceId: String): String {
    return this?.trim().orEmpty().ifBlank { AnimeSettingsManager.labelForSourceId(sourceId) }
}

class BrowseSourceAnime : Fragment() {
    companion object {
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_SOURCE_LABEL = "source_label"
        private const val ARG_QUERY = "query"
        private const val ARG_GENRE_TITLE = "genre_title"
        private const val ARG_GENRE_VALUE = "genre_value"
        private const val ARG_GENRE_ROUTE = "genre_route"

        @JvmStatic
        fun newSource(sourceId: String?, sourceLabel: String?, query: String?): BrowseSourceAnime {
            val safeSourceId = sourceId.asAnimeSourceId()
            return BrowseSourceAnime().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to safeSourceId,
                    ARG_SOURCE_LABEL to sourceLabel.asAnimeSourceLabel(safeSourceId),
                    ARG_QUERY to query.orEmpty(),
                    ARG_GENRE_TITLE to "",
                    ARG_GENRE_VALUE to "",
                    ARG_GENRE_ROUTE to false
                )
            }
        }

        @JvmStatic
        fun newGenre(sourceId: String?, sourceLabel: String?, genreTitle: String?, genreValue: String?): BrowseSourceAnime {
            val safeSourceId = sourceId.asAnimeSourceId()
            return BrowseSourceAnime().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to safeSourceId,
                    ARG_SOURCE_LABEL to sourceLabel.asAnimeSourceLabel(safeSourceId),
                    ARG_QUERY to "",
                    ARG_GENRE_TITLE to genreTitle.orEmpty().ifBlank { "Genre" },
                    ARG_GENRE_VALUE to genreValue.orEmpty().ifBlank { genreTitle.orEmpty() },
                    ARG_GENRE_ROUTE to true
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAppSystemBars()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) applyAppSystemBars()
    }

    private fun applyAppSystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val sourceId = arguments?.getString(ARG_SOURCE_ID).asAnimeSourceId()
        val sourceLabel = arguments?.getString(ARG_SOURCE_LABEL).asAnimeSourceLabel(sourceId)
        val query = arguments?.getString(ARG_QUERY).orEmpty()
        val genreTitle = arguments?.getString(ARG_GENRE_TITLE).orEmpty()
        val genreValue = arguments?.getString(ARG_GENRE_VALUE).orEmpty()
        val genreRoute = arguments?.getBoolean(ARG_GENRE_ROUTE) == true
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MikuAnimeSourceTheme {
                    val controller = remember(sourceId, query, genreValue, genreRoute) {
                        BrowseSourceAnimeController(requireContext().applicationContext, sourceId, sourceLabel, query, genreTitle, genreValue, genreRoute)
                    }
                    BrowseSourceAnimeContent(
                        controller = controller,
                        navigateUp = { closeScreen() },
                        onAnimeClick = { openAnimeDetail(it) },
                        onEpisodeClick = { openAnimeDetail(it) }
                    )
                }
            }
        }
    }

    private fun closeScreen() {
        if (!isAdded) return
        parentFragmentManager.popBackStack()
    }

    private fun openAnimeDetail(post: AnimePost) {
        when (val activity = activity) {
            is MainActivity -> activity.openAnimeDetailV2(post)
            is AnimexAll -> activity.openAnimeDetailV2(post)
        }
    }
}

private class BrowseSourceAnimeController(
    private val context: Context,
    val sourceId: String,
    val sourceLabel: String,
    initialQuery: String,
    private val genreTitle: String,
    genreValue: String,
    val genreRoute: Boolean
) {
    val posts = mutableStateListOf<AnimePost>()
    val genres = mutableStateListOf<String>()
    var loading by mutableStateOf(false)
    var errorMessage by mutableStateOf("")
    var hasMore by mutableStateOf(true)
    var query by mutableStateOf(initialQuery.trim())
    var toolbarQuery by mutableStateOf<String?>(if (initialQuery.trim().isBlank()) null else initialQuery.trim())
    var selectedListing by mutableStateOf(defaultListing())
    var selectedGenre by mutableStateOf(if (genreRoute) genreValue.trim() else "")
    var displayMode by mutableStateOf(readDisplayMode(context))
    var gridVersion by mutableIntStateOf(0)
    private var page = 0
    private var run = 0
    private val loadedKeys = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = AnimeRepository()

    private var started = false

    init {
        loadGenres()
    }

    fun start() {
        if (started) return
        started = true
        reload()
    }

    fun destroy() {
        scope.coroutineContext[Job]?.cancel()
    }

    fun title(): String {
        return if (genreRoute && genreTitle.isNotBlank()) genreTitle else sourceLabel
    }

    fun updateDisplayMode(mode: LibraryDisplayMode) {
        displayMode = mode
        context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).edit().putString("pref_display_mode_catalogue", mode.serialize()).apply()
        gridVersion++
    }

    fun search(value: String) {
        query = value.trim()
        toolbarQuery = query.ifBlank { null }
        selectedGenre = ""
        reload()
    }

    fun setGenre(value: String) {
        selectedGenre = value.trim()
        query = ""
        reload()
    }

    fun setListing(value: String) {
        selectedListing = value
        query = ""
        selectedGenre = ""
        reload()
    }

    fun reload() {
        run++
        page = 0
        hasMore = true
        errorMessage = ""
        loadedKeys.clear()
        posts.clear()
        loadNextPage()
    }

    fun loadNextPage() {
        if (loading || !hasMore) return
        val targetPage = page + 1
        val currentRun = run
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loadPage(targetPage) }
                if (currentRun != run) return@launch
                val added = appendPosts(result.items)
                page = targetPage
                hasMore = result.hasMore && result.rawCount > 0 && (added > 0 || result.allowDuplicatePage)
                errorMessage = ""
            } catch (e: Exception) {
                if (currentRun != run) return@launch
                if (posts.isEmpty()) errorMessage = "Gagal memuat ${sourceLabel}"
                hasMore = false
            } finally {
                if (currentRun == run) loading = false
            }
        }
    }

    fun sortOptions(): List<Pair<String, String>> {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> listOf("latest" to "Terbaru", "popular" to "Populer", "completed" to "Completed")
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> listOf("latest" to "Terbaru", "new" to "Baru Upload", "donghua" to "Donghua", "movie" to "Movie")
            AnimeSettingsManager.SOURCE_DRAMORA -> listOf("korea" to "Drama Korea", "china" to "Drama China", "thailand" to "Drama Thailand")
            AnimeSettingsManager.SOURCE_ANICHIN -> listOf("update" to "Terbaru", "popular" to "Populer", "latest" to "Baru Ditambahkan", "title" to "A-Z", "titlereverse" to "Z-A", "rating" to "Rating")
            else -> listOf("latest" to "Anime Terbaru", "schedule" to "Jadwal", "all" to "Semua Anime")
        }
    }

    fun selectedGenreLabel(): String {
        return selectedGenre.ifBlank { "Semua genre" }
    }

    fun getColumnsPreference(configuration: Configuration): Int {
        return MangaSettingsManager.getMangaGridColumns(context)
    }

    private fun appendPosts(items: List<AnimePost>): Int {
        var added = 0
        for (post in items) {
            post.sourceId = sourceId
            val key = itemKey(post)
            if (key.isBlank() || !loadedKeys.add(key)) continue
            posts.add(post)
            added++
        }
        gridVersion++
        return added
    }

    private suspend fun loadPage(page: Int): BrowseAnimePage {
        if (query.isNotBlank()) return searchPage(query, page)
        if (selectedGenre.isNotBlank()) return genrePage(selectedGenre, page)
        return listingPage(selectedListing, page)
    }

    private suspend fun listingPage(listing: String, page: Int): BrowseAnimePage {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> animekuListing(listing, page)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loverzListing(listing, page)
            AnimeSettingsManager.SOURCE_DRAMORA -> dramoraListing(listing, page)
            AnimeSettingsManager.SOURCE_ANICHIN -> anichinListing(listing, page)
            else -> defaultListing(listing, page)
        }
    }

    private suspend fun searchPage(value: String, page: Int): BrowseAnimePage {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> animekuSearch(value, page)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loverzSearch(value, page)
            AnimeSettingsManager.SOURCE_DRAMORA -> dramoraSearch(value, page)
            AnimeSettingsManager.SOURCE_ANICHIN -> anichinSearch(value, page)
            else -> defaultSearch(value, page)
        }
    }

    private suspend fun genrePage(value: String, page: Int): BrowseAnimePage {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> animekuGenre(value, page)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loverzGenre(value, page)
            AnimeSettingsManager.SOURCE_DRAMORA -> dramoraGenre(value, page)
            AnimeSettingsManager.SOURCE_ANICHIN -> anichinGenre(value, page)
            else -> defaultGenre(value, page)
        }
    }

    private suspend fun defaultListing(listing: String, page: Int): BrowseAnimePage {
        val response = when (listing) {
            "schedule" -> repository.getSchedule(page, 100)
            "all" -> repository.getAllAnime(page, PAGE_SIZE)
            else -> repository.getHomePosts(page, PAGE_SIZE, deviceId())
        }
        val raw = if (listing == "latest") response.posts.orEmpty() else response.categories.orEmpty()
        val data = raw.mapNotNull { defaultPost(it) }
        val totalItems = response.countTotal ?: -1
        val hasNext = if (totalItems > 0) posts.size + data.size < totalItems else raw.size >= PAGE_SIZE
        return BrowseAnimePage(data, hasNext, raw.size, raw.size >= PAGE_SIZE)
    }

    private suspend fun defaultSearch(value: String, page: Int): BrowseAnimePage {
        val response = repository.searchAnime(value, page, PAGE_SIZE)
        val raw = response.categories.orEmpty()
        val data = raw.mapNotNull { defaultPost(it) }
        val totalItems = response.countTotal ?: -1
        val hasNext = if (totalItems > 0) posts.size + data.size < totalItems else raw.size >= PAGE_SIZE
        return BrowseAnimePage(data, hasNext, raw.size, raw.size >= PAGE_SIZE)
    }

    private suspend fun defaultGenre(value: String, page: Int): BrowseAnimePage {
        if (page > 1) return BrowseAnimePage(emptyList(), false, 0)
        val response = repository.getAnimeByGenre(value, page, PAGE_SIZE)
        val raw = response.categories.orEmpty()
        val data = raw.mapNotNull { defaultPost(it) }
        return BrowseAnimePage(data, false, raw.size, false)
    }

    private fun defaultPost(item: ApiAnimePost): AnimePost? {
        // Dibungkus try/catch: satu item aneh dari API tidak boleh melempar exception yang
        // membatalkan seluruh mapNotNull() di pemanggil (lihat perbaikan yang sama di
        // AnimeHomeFragment.defaultPosts).
        return try {
            val categoryId = item.categoryId ?: item.cid ?: -1
            val channelId = item.channelId ?: -1
            val title = cleanTitle(item.categoryName)
            if (categoryId <= 0 || title.isBlank() || channelId == BLOCKED_CHANNEL_ID) return null
            val image = firstUseful(item.imgUrl, item.categoryImage)
            AnimePost(image, title, categoryId, channelId).apply {
                sourceId = AnimeSettingsManager.SOURCE_DEFAULT
                channelName = item.channelName.orEmpty()
                episodeCount = item.countAnime.orEmpty().ifBlank { item.channelName.orEmpty() }
                created = item.created.orEmpty()
                countView = item.countView ?: item.totalViews.orEmpty()
                ongoing = item.ongoing == 1
                hdAvailable = item.isHdAvailable == true
                fhdAvailable = item.isFhdAvailable == true
                rating = item.rating.orEmpty()
                scheduleDay = item.days ?: -1
                year = item.years?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowseSourceAnime", "Skip 1 item rusak dari AnimeXNonton: ${item.categoryName}", e)
            null
        }
    }

    private fun animekuListing(listing: String, page: Int): BrowseAnimePage {
        val endpoint = when (listing) {
            "popular" -> "get_category_popular"
            "completed" -> "get_category_complete"
            else -> "get_videos"
        }
        val json = JSONObject(getText("$ANIMEKU_API_BASE/$endpoint?page=$page&count=$PAGE_SIZE&api_key=$ANIMEKU_API_KEY", animekuHeaders()))
        if (!json.optString("status").equals("ok", true)) return BrowseAnimePage(emptyList(), false)
        val total = json.optInt("count_total", -1)
        val array = if (listing == "latest") json.optJSONArray("latest_anime") else json.optJSONArray("new_anime")
        val rawCount = array?.length() ?: 0
        val data = if (listing == "latest") parseAnimekuLatest(array) else parseAnimekuCategories(array)
        return BrowseAnimePage(data, if (total > 0) posts.size + data.size < total else rawCount >= PAGE_SIZE, rawCount)
    }

    private fun animekuSearch(value: String, page: Int): BrowseAnimePage {
        val json = JSONObject(getText("$ANIMEKU_API_BASE/get_category_genre?search=${encode(value)}&page=$page&count=$PAGE_SIZE&sort=c.category_name%20ASC&api_key=$ANIMEKU_API_KEY", animekuHeaders()))
        if (!json.optString("status").equals("ok", true)) return BrowseAnimePage(emptyList(), false)
        val array = json.optJSONArray("categories")
        val rawCount = array?.length() ?: 0
        val data = parseAnimekuCategories(array).filter { normalize(it.categoryName).contains(normalize(value)) }
        val total = json.optInt("count_total", json.optInt("count", -1))
        return BrowseAnimePage(data, if (total > 0) posts.size + data.size < total else rawCount >= PAGE_SIZE, rawCount)
    }

    private fun animekuGenre(value: String, page: Int): BrowseAnimePage {
        val json = JSONObject(getText("$ANIMEKU_API_BASE/get_category_genre?search=${encode(value)}&page=$page&count=$PAGE_SIZE&sort=c.category_name%20ASC&api_key=$ANIMEKU_API_KEY", animekuHeaders()))
        if (!json.optString("status").equals("ok", true)) return BrowseAnimePage(emptyList(), false)
        val target = normalize(value)
        val array = json.optJSONArray("categories")
        val rawCount = array?.length() ?: 0
        val data = parseAnimekuCategories(array).filter { normalize(it.genre).contains(target) || normalize(it.statusVideo).contains(target) }
        val total = json.optInt("count_total", json.optInt("count", -1))
        return BrowseAnimePage(data, if (total > 0) posts.size + data.size < total else rawCount >= PAGE_SIZE, rawCount)
    }

    private fun parseAnimekuLatest(array: JSONArray?): List<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val categoryId = item.optInt("cat_id", -1)
            val videoId = item.optInt("vid", -1)
            val title = cleanAnimeTitle(item.optString("category_name", ""))
            if (categoryId <= 0 || videoId <= 0 || title.isBlank()) continue
            val post = AnimePost(imageAnimeku(firstUseful(item.optString("category_image", ""), item.optString("video_thumbnail", ""))), title, categoryId, videoId)
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
            post.channelName = episodeLabel(item.optString("video_title", ""))
            post.episodeCount = post.channelName
            post.statusVideo = normalizeStatus(item.optString("status_video", ""))
            post.ongoing = isOngoing(post.statusVideo)
            result.add(post)
        }
        return result
    }

    private fun parseAnimekuCategories(array: JSONArray?): List<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val categoryId = item.optInt("cid", item.optInt("cat_id", -1))
            val title = cleanAnimeTitle(item.optString("category_name", ""))
            if (categoryId <= 0 || title.isBlank()) continue
            val post = AnimePost(imageAnimeku(item.optString("category_image", "")), title, categoryId, item.optInt("vid", -1))
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
            post.genre = item.optString("genre", "")
            post.rating = item.optString("rating", "")
            post.statusVideo = normalizeStatus(item.optString("status_video", ""))
            post.episodeCount = latestEpisodeLabel(item.optString("video_count", ""))
            post.ongoing = isOngoing(post.statusVideo)
            result.add(post)
        }
        return result
    }

    private fun loverzListing(listing: String, page: Int): BrowseAnimePage {
        if (page > 1 && (listing == "latest" || listing == "donghua")) {
            return loverzFallbackListing(listing, page)
        }
        val url = when (listing) {
            "new" -> "$ANIMELOVERZ_API_BASE/baruupload.php?page=$page"
            "donghua" -> "$ANIMELOVERZ_API_BASE/home/ongoing.php?page=$page&type=donghua"
            "movie" -> "$ANIMELOVERZ_API_BASE/movie.php"
            else -> "$ANIMELOVERZ_API_BASE/home/ongoing.php?page=$page&type=all"
        }
        val array = JSONArray(getText(url, loverzHeaders()))
        val data = parseLoverzList(array)
        return BrowseAnimePage(data, listing != "movie" && array.length() > 0, array.length())
    }

    private fun loverzFallbackListing(listing: String, page: Int): BrowseAnimePage {
        val fallbackPage = (page - 1).coerceAtLeast(1)
        if (listing == "donghua") {
            val json = JSONObject(getText("$ANIMELOVERZ_API_BASE/search.php?keyword=donghua&page=$fallbackPage&per_page=$PAGE_SIZE", loverzHeaders()))
            val pair = parseLoverzSearch(json, "donghua", false)
            return BrowseAnimePage(pair.first, pair.second, pair.third)
        }
        val array = JSONArray(getText("$ANIMELOVERZ_API_BASE/baruupload.php?page=$fallbackPage", loverzHeaders()))
        val data = parseLoverzList(array)
        return BrowseAnimePage(data, array.length() >= 10, array.length())
    }

    private fun loverzSearch(value: String, page: Int): BrowseAnimePage {
        val json = JSONObject(getText("$ANIMELOVERZ_API_BASE/search.php?keyword=${encode(value)}&page=$page&per_page=$PAGE_SIZE", loverzHeaders()))
        val pair = parseLoverzSearch(json, "", false)
        return BrowseAnimePage(pair.first, pair.second, pair.third)
    }

    private fun loverzGenre(value: String, page: Int): BrowseAnimePage {
        val url = genreUrl(value)
        return try {
            val array = JSONArray(getText("$ANIMELOVERZ_API_BASE/genreseries.php?page=$page&url=${encode(url)}", loverzHeaders()))
            BrowseAnimePage(parseLoverzList(array), array.length() > 0, array.length())
        } catch (e: Exception) {
            val json = JSONObject(getText("$ANIMELOVERZ_API_BASE/search.php?keyword=${encode(value)}&page=$page&per_page=$PAGE_SIZE", loverzHeaders()))
            val pair = parseLoverzSearch(json, value, true)
            BrowseAnimePage(pair.first, pair.second, pair.third)
        }
    }

    private fun parseLoverzSearch(json: JSONObject, genre: String, filterGenre: Boolean): Triple<List<AnimePost>, Boolean, Int> {
        val result = ArrayList<AnimePost>()
        var hasNext = false
        var rawCount = 0
        val data = json.optJSONArray("data") ?: return Triple(result, false, 0)
        for (d in 0 until data.length()) {
            val block = data.optJSONObject(d) ?: continue
            val items = block.optJSONArray("result") ?: continue
            rawCount += items.length()
            for (i in 0 until items.length()) {
                val post = parseLoverzPost(items.optJSONObject(i) ?: continue) ?: continue
                if (filterGenre && !normalize(post.genre).contains(normalize(genre))) continue
                result.add(post)
            }
            val pagination = block.optJSONObject("pagination")
            if (pagination != null) hasNext = pagination.optBoolean("has_next", false)
        }
        return Triple(result, hasNext, rawCount)
    }

    private fun parseLoverzList(array: JSONArray?): List<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) parseLoverzPost(array.optJSONObject(i))?.let { result.add(it) }
        return result
    }

    private fun parseLoverzPost(item: JSONObject?): AnimePost? {
        if (item == null) return null
        val title = firstUseful(item.optString("judul", ""), firstUseful(item.optString("anime_name", ""), firstUseful(item.optString("title", ""), item.optString("name", ""))))
        val slug = firstUseful(item.optString("url", ""), firstUseful(item.optString("link", ""), firstUseful(item.optString("slug", ""), item.optString("permalink", "")))).trim().trim('/')
        if (title.isBlank() || slug.isBlank()) return null
        val post = AnimePost(firstUseful(item.optString("cover", ""), firstUseful(item.optString("thumb", ""), firstUseful(item.optString("thumbnail", ""), item.optString("image", "")))), cleanTitle(title), positiveId(item.optString("id", "").ifBlank { slug }), -1)
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
        post.slug = slug
        post.channelName = firstUseful(item.optString("lastch", ""), firstUseful(item.optString("episode", ""), item.optString("ch", "")))
        post.episodeCount = firstUseful(item.optString("total_episode", ""), item.optString("episode_count", ""))
        post.genre = firstUseful(joinArray(item.optJSONArray("genre")), joinArray(item.optJSONArray("genres")))
        post.rating = firstUseful(item.optString("score", ""), firstUseful(item.optString("rating", ""), item.optString("rate", "")))
        post.statusVideo = firstUseful(item.optString("status", ""), firstUseful(item.optString("release_status", ""), item.optString("anime_status", "")))
        post.description = firstUseful(item.optString("sinopsis", ""), firstUseful(item.optString("synopsis", ""), firstUseful(item.optString("description", ""), item.optString("desc", ""))))
        post.ongoing = !post.statusVideo.lowercase(Locale.ROOT).contains("complete")
        return post
    }

    fun loadGenres() {
        scope.launch {
            val data = try {
                withContext(Dispatchers.IO) {
                    when (sourceId) {
                        AnimeSettingsManager.SOURCE_ANIMEKU -> animekuGenreList()
                        AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loverzGenreList()
                        AnimeSettingsManager.SOURCE_DRAMORA -> Dramora.genres()
                        AnimeSettingsManager.SOURCE_ANICHIN -> Anichin.genres()
                        else -> repository.getGenreList().genre.orEmpty().mapNotNull { it.genreName?.trim()?.takeIf { name -> name.isNotBlank() } }
                    }
                }
            } catch (e: Exception) {
                fallbackGenres()
            }
            genres.clear()
            genres.addAll(data.distinctBy { it.lowercase(Locale.ROOT) })
        }
    }

    private fun animekuGenreList(): List<String> {
        val body = getText("$ANIMEKU_API_BASE/get_genre_index?api_key=$ANIMEKU_API_KEY", animekuHeaders())
        val array = try { JSONArray(body) } catch (e: Exception) { regexGenreArray(body) }
        val result = ArrayList<String>()
        for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optString("genre_anime", "")?.trim().orEmpty()
            if (name.isNotBlank()) result.add(name)
        }
        return result.ifEmpty { fallbackGenres() }
    }

    private fun regexGenreArray(body: String): JSONArray {
        val array = JSONArray()
        Regex("genre_anime\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach {
            array.put(JSONObject().put("genre_anime", it.groupValues[1]))
        }
        return array
    }

    private fun loverzGenreList(): List<String> = fallbackGenres()

    private fun dramoraListing(listing: String, page: Int): BrowseAnimePage {
        val result = Dramora.listing(listing, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun dramoraSearch(value: String, page: Int): BrowseAnimePage {
        val result = Dramora.search(value, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun dramoraGenre(value: String, page: Int): BrowseAnimePage {
        val result = Dramora.genre(value, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun anichinListing(listing: String, page: Int): BrowseAnimePage {
        val result = Anichin.listing(listing, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun anichinSearch(value: String, page: Int): BrowseAnimePage {
        val result = Anichin.search(value, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun anichinGenre(value: String, page: Int): BrowseAnimePage {
        val result = Anichin.genre(value, page)
        return BrowseAnimePage(result.items, result.hasMore, result.rawCount)
    }

    private fun fallbackGenres(): List<String> = listOf(
        "Action", "Adventure", "Comedy", "Demons", "Drama", "Ecchi", "Fantasy", "Game", "Harem", "Historical",
        "Horror", "Josei", "Magic", "Martial Arts", "Mecha", "Military", "Music", "Mystery", "Psychological", "Parody",
        "Police", "Romance", "Samurai", "School", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Slice of Life",
        "Sports", "Space", "Super Power", "Supernatural", "Thriller", "Vampire", "Yaoi", "Yuri"
    )

    private fun defaultListing(): String = when (sourceId) {
        AnimeSettingsManager.SOURCE_DRAMORA -> "korea"
        AnimeSettingsManager.SOURCE_ANICHIN -> "update"
        else -> "latest"
    }

    private fun itemKey(post: AnimePost): String {
        val slug = post.slug?.trim().orEmpty().trim('/')
        if (slug.isNotBlank()) return "$sourceId:$slug"
        val inSearchOrGenre = query.isNotBlank() || selectedGenre.isNotBlank()
        if (sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) {
            if (!inSearchOrGenre && selectedListing == "latest" && post.channelId > 0 && post.categoryId > 0) return "$sourceId:latest:${post.channelId}_${post.categoryId}"
            if (post.categoryId > 0) return "$sourceId:${if (inSearchOrGenre) "category" else selectedListing}:${post.categoryId}"
        }
        if (sourceId == AnimeSettingsManager.SOURCE_DEFAULT) {
            if (!inSearchOrGenre && selectedListing == "latest" && post.channelId > 0 && post.categoryId > 0) return "$sourceId:latest:${post.channelId}_${post.categoryId}"
            if (post.categoryId > 0) return "$sourceId:${if (inSearchOrGenre || selectedListing == "schedule") "category" else selectedListing}:${post.categoryId}"
        }
        if (post.categoryId > 0) return "$sourceId:${post.categoryId}"
        val title = post.categoryName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (title.isNotBlank()) return "$sourceId:$title"
        return ""
    }

    private fun deviceId(): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    companion object {
        private const val PAGE_SIZE = 20
        private const val BLOCKED_CHANNEL_ID = 45784
        private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api"
        private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
        private const val ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/"
        private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"

        private fun readDisplayMode(context: Context): LibraryDisplayMode {
            return LibraryDisplayMode.deserialize(context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).getString("pref_display_mode_catalogue", null))
        }
    }
}

private data class BrowseAnimePage(val items: List<AnimePost>, val hasMore: Boolean, val rawCount: Int = items.size, val allowDuplicatePage: Boolean = false)

@Composable
private fun MikuAnimeSourceTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val primary = Color(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary))
    val background = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
    val colors = darkColorScheme(
        primary = primary,
        onPrimary = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary)),
        primaryContainer = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer)),
        onPrimaryContainer = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimaryContainer)),
        background = background,
        surface = background,
        surfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant)),
        onSurface = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)),
        onSurfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)),
        outline = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline))
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun BrowseSourceAnimeContent(controller: BrowseSourceAnimeController, navigateUp: () -> Unit, onAnimeClick: (AnimePost) -> Unit, onEpisodeClick: (AnimePost) -> Unit) {
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    LaunchedEffect(controller) {
        controller.start()
    }
    var showGenreSheet by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            BrowseSourceAnimeToolbar(
                title = controller.title(),
                searchQuery = controller.toolbarQuery,
                onSearchQueryChange = { controller.toolbarQuery = it },
                displayMode = controller.displayMode,
                onDisplayModeChange = controller::updateDisplayMode,
                navigateUp = navigateUp,
                onSearchClick = { controller.toolbarQuery = controller.query },
                onCloseSearch = { controller.toolbarQuery = null },
                onSearch = controller::search
            )
            if (!controller.genreRoute) {
                BrowseSourceAnimeFilterRow(
                    controller = controller,
                    onFilterClick = {
                        controller.loadGenres()
                        showGenreSheet = true
                    }
                )
            }
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            BrowseSourceAnimeContentList(
                posts = controller.posts,
                displayMode = controller.displayMode,
                loading = controller.loading,
                hasMore = controller.hasMore,
                errorMessage = controller.errorMessage,
                onLoadMore = controller::loadNextPage,
                onAnimeClick = onAnimeClick,
                onEpisodeClick = onEpisodeClick,
                controller = controller
            )
        }
    }
    if (showGenreSheet) {
        GenreAnimeSheet(controller, onDismiss = { showGenreSheet = false }, onSelect = {
            controller.setGenre(it)
            showGenreSheet = false
        })
    }
}

@Composable
private fun BrowseSourceAnimeToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearch: (String) -> Unit
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (searchQuery != null) {
                TextField(
                    value = searchQuery,
                    onValueChange = { onSearchQueryChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    placeholder = { Text("Cari anime") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = navigateUp) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
            }
        },
        actions = {
            if (searchQuery != null) {
                IconButton(onClick = onCloseSearch) {
                    Icon(Icons.Filled.Close, contentDescription = "Tutup pencarian")
                }
            } else {
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Filled.Search, contentDescription = "Cari")
                }
            }
            Box {
                IconButton(onClick = { selectingDisplayMode = true }) {
                    Icon(if (displayMode == LibraryDisplayMode.List) Icons.Filled.ViewList else Icons.Filled.ViewModule, contentDescription = "Ubah tata letak")
                }
                DropdownMenu(expanded = selectingDisplayMode, onDismissRequest = { selectingDisplayMode = false }) {
                    RadioDisplayAnimeModeMenuItem("Grid nyaman", displayMode == LibraryDisplayMode.ComfortableGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                    }
                    RadioDisplayAnimeModeMenuItem("Grid kompak", displayMode == LibraryDisplayMode.CompactGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                    }
                    RadioDisplayAnimeModeMenuItem("Daftar", displayMode == LibraryDisplayMode.List) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.List)
                    }
                }
            }
        }
    )
}

@Composable
private fun RadioDisplayAnimeModeMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { RadioButton(selected = checked, onClick = onClick) },
        onClick = onClick
    )
}

@Composable
private fun BrowseSourceAnimeFilterRow(controller: BrowseSourceAnimeController, onFilterClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        controller.sortOptions().forEach { item ->
            val leadingIcon: (@Composable () -> Unit)? = when {
                item.first == "popular" -> ({ Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp)) })
                item.first == "latest" -> ({ Icon(Icons.Outlined.NewReleases, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp)) })
                else -> null
            }
            FilterChip(
                selected = controller.selectedListing == item.first,
                onClick = { controller.setListing(item.first) },
                leadingIcon = leadingIcon,
                label = { Text(item.second, maxLines = 1) }
            )
        }
        FilterChip(
            selected = controller.selectedGenre.isNotBlank(),
            onClick = onFilterClick,
            leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp)) },
            label = { Text(if (controller.selectedGenre.isBlank()) "Filter Genre" else "Genre: ${controller.selectedGenreLabel()}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

@Composable
private fun GenreAnimeSheet(controller: BrowseSourceAnimeController, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Filter Genre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (controller.genres.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    item {
                        GenreAnimeRow("Semua", controller.selectedGenre.isBlank()) { onSelect("") }
                    }
                    items(controller.genres, key = { it }) { genre ->
                        GenreAnimeRow(genre, genre.equals(controller.selectedGenre, true)) { onSelect(genre) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreAnimeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrowseSourceAnimeContentList(
    posts: List<AnimePost>,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    errorMessage: String,
    onLoadMore: () -> Unit,
    onAnimeClick: (AnimePost) -> Unit,
    onEpisodeClick: (AnimePost) -> Unit,
    controller: BrowseSourceAnimeController
) {
    when {
        posts.isEmpty() && loading -> LoadingAnimeBrowse()
        posts.isEmpty() -> EmptyAnimeBrowse(errorMessage.ifBlank { "Tidak ada hasil" })
        else -> BrowseSourceStableAnimeContent(
            posts = posts,
            displayMode = displayMode,
            loading = loading,
            hasMore = hasMore,
            onLoadMore = onLoadMore,
            onAnimeClick = onAnimeClick,
            onEpisodeClick = onEpisodeClick,
            controller = controller
        )
    }
}

private class BrowseSourceAnimeAndroidHolder(
    var mode: Int,
    val listView: AbsListView,
    val adapter: BrowseSourceAnimeAdapter
)

@Composable
private fun BrowseSourceStableAnimeContent(
    posts: List<AnimePost>,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAnimeClick: (AnimePost) -> Unit,
    onEpisodeClick: (AnimePost) -> Unit,
    controller: BrowseSourceAnimeController
) {
    val mode = when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> BrowseSourceAnimeAdapter.MODE_COMFORTABLE_GRID
        LibraryDisplayMode.List -> BrowseSourceAnimeAdapter.MODE_LIST
        else -> BrowseSourceAnimeAdapter.MODE_COMPACT_GRID
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { container ->
            val columns = controller.getColumnsPreference(container.context.resources.configuration)
            var holder = container.getTag(R.id.browseSourceAndroidHolder) as? BrowseSourceAnimeAndroidHolder
            if (holder == null || holder.mode != mode) {
                container.removeAllViews()
                val adapter = BrowseSourceAnimeAdapter(container.context, posts, { onAnimeClick(it) }, { onEpisodeClick(it) })
                adapter.setMode(mode)
                adapter.setGridColumns(columns)
                adapter.bindFlags(false, AnimeSettingsManager.shouldShowLatestEpisodeLabel(container.context), MangaSettingsManager.isBoldMangaTitleEnabled(container.context))
                val listView: AbsListView = if (mode == BrowseSourceAnimeAdapter.MODE_LIST) {
                    android.widget.ListView(container.context).apply {
                        divider = null
                        dividerHeight = 0
                        setPadding(0, dpInt(context, 8), 0, dpInt(context, 88))
                        clipToPadding = false
                    }
                } else {
                    GridView(container.context).apply {
                        numColumns = columns
                        stretchMode = GridView.STRETCH_COLUMN_WIDTH
                        // Cover tetap besar: antar cover 8dp, antar baris 14dp (item punya paddingBottom 5dp), tepi 12dp
                        horizontalSpacing = dpInt(context, 8)
                        verticalSpacing = dpInt(context, 9)
                        setPadding(dpInt(context, 12), dpInt(context, 8), dpInt(context, 12), dpInt(context, 88))
                        clipToPadding = false
                    }
                }
                listView.adapter = adapter
                listView.cacheColorHint = android.graphics.Color.TRANSPARENT
                listView.isFastScrollEnabled = false
                listView.isVerticalScrollBarEnabled = false
                container.addView(listView, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                holder = BrowseSourceAnimeAndroidHolder(mode, listView, adapter)
                container.setTag(R.id.browseSourceAndroidHolder, holder)
            }
            holder.adapter.setMode(mode)
            holder.adapter.setGridColumns(columns)
            holder.adapter.bindFlags(false, AnimeSettingsManager.shouldShowLatestEpisodeLabel(container.context), MangaSettingsManager.isBoldMangaTitleEnabled(container.context))
            val activeListView = holder.listView
            if (activeListView is GridView && mode != BrowseSourceAnimeAdapter.MODE_LIST) {
                activeListView.numColumns = columns
            }
            holder.listView.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (hasMore && !loading && totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) onLoadMore()
                }
            })
            holder.adapter.notifyDataSetChanged()
            holder.listView.post {
                val total = holder.adapter.count
                val lastVisible = holder.listView.lastVisiblePosition
                if (hasMore && !loading && total > 0 && lastVisible >= total - 4) onLoadMore()
            }
        }
    )
}

@Composable
private fun LoadingAnimeBrowse() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyAnimeBrowse(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dpInt(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
}

private fun getText(url: String, headers: Headers): String {
    val request = Request.Builder().url(url).headers(headers).build()
    return httpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
}

private fun animekuHeaders(): Headers = Headers.Builder()
    .add("Cache-Control", "max-age=0")
    .add("Data-Agent", "Your Videos Channel")
    .add("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)")
    .add("Accept", "application/vnd.yourapi.v1.full+json")
    .build()

private fun loverzHeaders(): Headers = Headers.Builder()
    .add("user-agent", "Dart/3.9 (dart:io)")
    .add("accept", "application/json")
    .build()

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun cleanTitle(value: String?): String = value?.trim().orEmpty().replace(Regex("\\s+"), " ")

private fun cleanAnimeTitle(value: String?): String {
    var result = cleanTitle(value)
    result = result.replace(Regex("(?i)\\bsub\\s*indo\\b"), "")
    result = result.replace(Regex("(?i)\\bsubtitle\\s*indonesia\\b"), "")
    result = result.replace(Regex("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$"), "")
    result = result.replace(Regex("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$"), "")
    return result.replace(Regex("\\s+"), " ").trim()
}

private fun episodeLabel(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return ""
    val matcher = Regex("(?i)(episode|eps|ep)\\s*([0-9]+(?:\\.[0-9]+)?)").find(raw)
    if (matcher != null) return "Episode ${matcher.groupValues[2]}"
    return raw
}

private fun latestEpisodeLabel(value: String?): String {
    val raw = value?.trim().orEmpty()
    val count = raw.toIntOrNull()
    return if (count != null && count > 0) "Episode %02d".format(Locale.US, count) else raw
}

private fun imageAnimeku(value: String?): String {
    val image = value?.trim().orEmpty()
    if (image.isBlank() || image.equals("null", true)) return ""
    if (image.startsWith("http://") || image.startsWith("https://")) return image
    return "http://elara.whatbox.ca:29318/Duljanah/$image"
}

private fun normalizeStatus(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank() || raw.equals("null", true)) return ""
    val lower = raw.lowercase(Locale.ROOT)
    if (lower.contains("complete") || lower.contains("finished")) return "Completed"
    if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing"
    return raw
}

private fun isOngoing(value: String?): Boolean {
    val raw = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return raw.isNotBlank() && !raw.contains("complete") && !raw.contains("finished") && raw != "selesai"
}

private fun firstUseful(first: String?, second: String?): String {
    val a = first?.trim().orEmpty()
    if (a.isNotBlank() && !a.equals("null", true)) return a
    val b = second?.trim().orEmpty()
    return if (b.equals("null", true)) "" else b
}

private fun normalize(value: String?): String = value?.trim()?.lowercase(Locale.ROOT)?.replace("-", " ")?.replace("_", " ")?.replace(Regex("\\s+"), " ").orEmpty()

private fun joinArray(array: JSONArray?): String {
    if (array == null) return ""
    val values = ArrayList<String>()
    for (i in 0 until array.length()) {
        val value = array.optString(i, "").trim()
        if (value.isNotBlank()) values.add(value)
    }
    return values.joinToString(", ")
}

private fun positiveId(value: String?): Int {
    val raw = value?.trim().orEmpty()
    val parsed = raw.toIntOrNull()
    if (parsed != null && parsed > 0) return parsed
    val hash = raw.hashCode()
    return if (hash == Int.MIN_VALUE) 1 else kotlin.math.abs(hash)
}

private fun genreUrl(genre: String): String {
    val map = mapOf(
        "martial arts" to "martial-arts/",
        "sci fi" to "sci-fi/",
        "shoujo ai" to "shoujo-ai/",
        "slice of life" to "slice-of-life/",
        "super power" to "super-power/"
    )
    val key = normalize(genre)
    return map[key] ?: key.replace(" ", "-") + "/"
}
