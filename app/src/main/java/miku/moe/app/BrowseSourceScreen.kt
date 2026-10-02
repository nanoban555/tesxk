@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package miku.moe.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import com.google.android.material.color.MaterialColors
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.delay

sealed interface LibraryDisplayMode {
    data object CompactGrid : LibraryDisplayMode
    data object ComfortableGrid : LibraryDisplayMode
    data object List : LibraryDisplayMode
    data object CoverOnlyGrid : LibraryDisplayMode

    companion object {
        val default: LibraryDisplayMode = CompactGrid
        fun deserialize(serialized: String?): LibraryDisplayMode {
            return when (serialized) {
                "COMFORTABLE_GRID" -> ComfortableGrid
                "COMPACT_GRID" -> CompactGrid
                "COVER_ONLY_GRID" -> CoverOnlyGrid
                "LIST" -> List
                else -> default
            }
        }
    }

    fun serialize(): String {
        return when (this) {
            ComfortableGrid -> "COMFORTABLE_GRID"
            CompactGrid -> "COMPACT_GRID"
            CoverOnlyGrid -> "COVER_ONLY_GRID"
            List -> "LIST"
        }
    }
}

private fun String?.asBrowseSourceId(): String {
    val value = this?.trim().orEmpty()
    return if (MangaSettingsManager.isValidSource(value)) value else MangaSettingsManager.MANGA_SOURCE_KOMIKCAST
}

private fun String?.asBrowseSourceLabel(sourceId: String): String {
    return this?.trim().orEmpty().ifBlank { MangaSourceFactory.labelForSourceId(sourceId) }
}

class BrowseSourceScreen : androidx.fragment.app.Fragment() {
    companion object {
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_SOURCE_LABEL = "source_label"
        private const val ARG_QUERY = "query"
        private const val ARG_GENRE_TITLE = "genre_title"
        private const val ARG_GENRE_VALUE = "genre_value"
        private const val ARG_GENRE_ROUTE = "genre_route"
        private const val ARG_INITIAL_SORT = "initial_sort"

        @JvmStatic
        @JvmOverloads
        fun newSource(sourceId: String?, sourceLabel: String?, query: String?, initialSort: String = "latest"): BrowseSourceScreen {
            return BrowseSourceScreen().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to sourceId.asBrowseSourceId(),
                    ARG_SOURCE_LABEL to sourceLabel.asBrowseSourceLabel(sourceId.asBrowseSourceId()),
                    ARG_QUERY to query.orEmpty(),
                    ARG_GENRE_TITLE to "",
                    ARG_GENRE_VALUE to "",
                    ARG_GENRE_ROUTE to false,
                    ARG_INITIAL_SORT to initialSort,
                )
            }
        }

        @JvmStatic
        fun newGenre(sourceId: String?, sourceLabel: String?, genreTitle: String?, genreValue: String?): BrowseSourceScreen {
            val safeSourceId = sourceId.asBrowseSourceId()
            return BrowseSourceScreen().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to safeSourceId,
                    ARG_SOURCE_LABEL to sourceLabel.asBrowseSourceLabel(safeSourceId),
                    ARG_QUERY to "",
                    ARG_GENRE_TITLE to genreTitle.orEmpty().ifBlank { "Genre" },
                    ARG_GENRE_VALUE to genreValue.orEmpty().ifBlank { genreTitle.orEmpty() },
                    ARG_GENRE_ROUTE to true,
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
        val sourceId = arguments?.getString(ARG_SOURCE_ID).asBrowseSourceId()
        val sourceLabel = arguments?.getString(ARG_SOURCE_LABEL).asBrowseSourceLabel(sourceId)
        val query = arguments?.getString(ARG_QUERY).orEmpty()
        val genreTitle = arguments?.getString(ARG_GENRE_TITLE).orEmpty()
        val genreValue = arguments?.getString(ARG_GENRE_VALUE).orEmpty()
        val genreRoute = arguments?.getBoolean(ARG_GENRE_ROUTE) == true
        val initialSort = arguments?.getString(ARG_INITIAL_SORT).orEmpty()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MikuSourceTheme {
                    val controller = remember(sourceId, query, genreValue, genreRoute) {
                        BrowseSourceController(requireContext().applicationContext, sourceId, sourceLabel, query, genreTitle, genreValue, genreRoute, initialSort)
                    }
                    BrowseSourceScreenContent(
                        controller = controller,
                        navigateUp = { closeScreen() },
                        onMangaClick = { openMangaDetail(it) },
                        onChapterClick = { openLatestChapter(it) },
                        onOpenWebView = { openSourceWebView(sourceId) },
                    )
                }
            }
        }
    }

    private fun closeScreen() {
        if (!isAdded) return
        if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack() else requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun openSourceWebView(sourceId: String) {
        if (!isAdded) return
        when (sourceId) {
            MangaSettingsManager.MANGA_SOURCE_COMICASO -> {
                Comicaso.clearSessionCaches()
                val url = MangaSettingsManager.getSourceDomain(requireContext(), MangaSettingsManager.MANGA_SOURCE_COMICASO)
                val intent = Intent(requireContext(), ComicasoWebViewActivity::class.java)
                intent.putExtra("url", url)
                intent.putExtra("sourceLabel", "Comicaso")
                startActivity(intent)
            }
            MangaSettingsManager.MANGA_SOURCE_CROTPEDIA -> {
                Crotpedia.clearSessionCaches()
                val intent = Intent(requireContext(), CrotpediaWebViewActivity::class.java)
                intent.putExtra("url", "https://crotpedia.net/login/")
                intent.putExtra("sourceLabel", "Crotpedia")
                startActivity(intent)
            }
        }
    }

    private fun openMangaDetail(post: MangaPost) {
        if (!isAdded) return
        when (val activity = requireActivity()) {
            is MainActivity -> activity.openMangaDetail(post)
            is MikuAll -> activity.openMangaDetail(post)
        }
    }

    private fun openLatestChapter(post: MangaPost) {
        if (!isAdded || post.slug.orEmpty().trim().isEmpty()) return
        MangaSourceFactory.createBySourceId(post.getSourceId()).chapters(post.slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
            override fun onSuccess(chapters: ArrayList<MangaChapter>?, hasNext: Boolean) {
                if (!isAdded) return
                val list = chapters ?: arrayListOf()
                if (list.isEmpty()) {
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show()
                    return
                }
                val pos = findChapterPosition(list, post.latestChapter)
                when (val activity = requireActivity()) {
                    is MainActivity -> activity.openMangaReader(post, ArrayList(list), pos)
                    is MikuAll -> activity.openMangaReader(post, ArrayList(list), pos)
                }
            }

            override fun onError(message: String?) {
                if (!isAdded) return
                Toast.makeText(requireContext(), message?.trim().orEmpty().ifBlank { "Gagal membuka chapter" }, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun findChapterPosition(chapters: ArrayList<MangaChapter>, latestChapter: String?): Int {
        val target = parseChapterIndex(latestChapter)
        if (target >= 0f) {
            chapters.forEachIndexed { index, chapter ->
                if (kotlin.math.abs(chapter.index - target) < 0.001f) return index
            }
        }
        var newest = 0
        for (i in 1 until chapters.size) {
            if (chapters[i].index > chapters[newest].index) newest = i
        }
        return newest
    }

    private fun parseChapterIndex(text: String?): Float {
        if (text == null) return -1f
        val matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'))
        if (!matcher.find()) return -1f
        return try { matcher.group(1)?.toFloat() ?: -1f } catch (_: Exception) { -1f }
    }

}

private data class BrowsePageCacheEntry(val posts: ArrayList<MangaPost>, val hasNext: Boolean)

private data class PreparedBrowsePage(val posts: ArrayList<MangaPost>)

private data class MangaUiFlags(val showTypeLabel: Boolean, val showLatestChapterLabel: Boolean, val boldTitle: Boolean)

private class BrowseSourceController(
    private val context: Context,
    val sourceId: String,
    val sourceLabel: String,
    initialQuery: String,
    private val genreTitle: String,
    initialGenre: String,
    initialGenreRoute: Boolean,
    initialSort: String,
) {
    val posts = mutableStateListOf<MangaPost>()
    val genreItems = mutableStateListOf<KomikcastClient.GenreItem>()
    var query by mutableStateOf(initialQuery)
    var toolbarQuery by mutableStateOf<String?>(if (initialQuery.isBlank()) null else initialQuery)
    var sort by mutableStateOf(if (initialSort in sortOptions().map { it.first }) initialSort else "latest")
    var selectedGenre by mutableStateOf(initialGenre)
    var genreRoute by mutableStateOf(initialGenreRoute)
    var selectedTypeLabel by mutableStateOf("")
    var displayMode by mutableStateOf(readDisplayMode(context))
    var loading by mutableStateOf(false)
    var loadingGenres by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var errorMessage by mutableStateOf("")
    var page by mutableIntStateOf(1)
    @Volatile private var generation = 0
    @Volatile private var destroyed = false
    private var filteredAutoLoadCount = 0
    private val loadedKeys = LinkedHashSet<String>()
    private val latestEnrichKeys = LinkedHashSet<String>()
    private val typeResolveKeys = LinkedHashSet<String>()
    private val resolvedTypeKeys = LinkedHashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MikuBrowsePage").apply { isDaemon = true } }
    private val enrichExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MikuBrowseEnrich").apply { isDaemon = true } }
    private val verifiedTypeLabels = LinkedHashMap<String, String>()
    private val pageCache = object : LinkedHashMap<String, BrowsePageCacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowsePageCacheEntry>?): Boolean {
            return size > 32
        }
    }

    init {
        reload()
    }

    fun destroy() {
        destroyed = true
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        pageExecutor.shutdownNow()
        enrichExecutor.shutdownNow()
    }

    fun title(): String {
        if (genreRoute) return genreTitle.ifBlank { "Genre" }
        return sourceLabel
    }

    fun getColumnsPreference(configuration: Configuration): GridCells {
        val columns = MangaSettingsManager.getMangaGridColumns(context)
        return GridCells.Fixed(columns)
    }

    fun setListing(value: String) {
        clearSearchState()
        genreRoute = false
        sort = value
        selectedGenre = ""
        selectedTypeLabel = ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO) {
            genreItems.clear()
        } else if (sourceId != MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) {
            val defaultType = defaultTypeForSort(value)
            if (defaultType.isNotBlank()) selectedTypeLabel = defaultType
        }
        reload()
    }

    fun setType(value: String) {
        if (usesLocalTypeFilter()) {
            selectedTypeLabel = value
            reload()
            return
        }
        clearSearchState()
        genreRoute = false
        selectedTypeLabel = value
        reload()
    }

    fun setGenre(value: String) {
        clearSearchState()
        genreRoute = false

        sort = "latest"
        selectedGenre = value
        reload()
    }

    fun clearSearch() {
        val hadSearch = query.isNotBlank() || toolbarQuery != null
        clearSearchState()
        if (hadSearch) reload()
    }

    fun search(value: String) {
        query = value.trim()
        toolbarQuery = query.ifBlank { null }
        if (query.isNotBlank()) {
            genreRoute = false
            sort = "latest"
            selectedGenre = ""
            selectedTypeLabel = ""
        }
        reload()
    }

    private fun clearSearchState() {
        query = ""
        toolbarQuery = null
    }

    fun resetToDefault(): Boolean {
        val changed = query.isNotBlank() || toolbarQuery != null || genreRoute || sort != "latest" || selectedGenre.isNotBlank() || selectedTypeLabel.isNotBlank()
        if (!changed) return false
        query = ""
        toolbarQuery = null
        genreRoute = false
        sort = "latest"
        selectedGenre = ""
        selectedTypeLabel = ""
        reload()
        return true
    }

    fun updateDisplayMode(mode: LibraryDisplayMode) {
        displayMode = mode
        context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).edit().putString("pref_display_mode_catalogue", mode.serialize()).apply()
    }

    fun reload() {
        if (destroyed) return
        generation++
        page = 1
        hasMore = true
        loading = false
        errorMessage = ""
        loadedKeys.clear()
        latestEnrichKeys.clear()
        typeResolveKeys.clear()
        verifiedTypeLabels.clear()
        resolvedTypeKeys.clear()
        filteredAutoLoadCount = 0
        posts.clear()
        // Reload eksplisit: bersihkan page cache + cache parser supaya data benar-benar dari network.
        synchronized(pageCache) { pageCache.clear() }
        MangaSourceFactory.invalidateSourceCaches(sourceId)
        loadNextPage()
    }

    fun loadNextPage() {
        if (destroyed || loading || !hasMore) return
        val run = generation
        val targetPage = page.coerceAtLeast(1)
        val sortSnapshot = sort
        val querySnapshot = query
        val typeSnapshot = selectedTypeLabel.trim()
        val loadTypeSnapshot = MangaSettingsManager.shouldLoadTypeLabel(context) || typeSnapshot.isNotEmpty()
        val requestFilter = requestFilterForSource(querySnapshot, typeSnapshot)
        val key = pageCacheKey(targetPage, requestFilter, sortSnapshot, querySnapshot, typeSnapshot)
        val cached = synchronized(pageCache) { pageCache[key] }
        if (cached != null && cached.posts.isNotEmpty()) {
            loading = true
            handlePageResultAsync(ArrayList(cached.posts), cached.hasNext, targetPage, run, typeSnapshot, loadTypeSnapshot)
            return
        }
        loading = true
        pageExecutor.execute {
            if (destroyed || run != generation) return@execute
            MangaSourceFactory.createBySourceId(sourceId).list(targetPage, sortSnapshot, querySnapshot, requestFilter, object : KomikcastClient.Result<ArrayList<MangaPost>> {
                override fun onSuccess(data: ArrayList<MangaPost>?, next: Boolean) {
                    if (destroyed || run != generation) return
                    val incoming = ArrayList<MangaPost>()
                    if (data != null) incoming.addAll(data)
                    if (incoming.isNotEmpty()) synchronized(pageCache) { pageCache[key] = BrowsePageCacheEntry(ArrayList(incoming), next) }
                    handlePageResultAsync(incoming, next, targetPage, run, typeSnapshot, loadTypeSnapshot)
                }

                override fun onError(message: String?) {
                    if (destroyed || run != generation) return
                    mainHandler.post {
                        if (destroyed || run != generation) return@post
                        loading = false
                        hasMore = false
                        errorMessage = message?.trim().orEmpty().ifBlank { "Gagal memuat manga" }
                    }
                }
            })
        }
    }

    private fun handlePageResultAsync(data: ArrayList<MangaPost>, next: Boolean, targetPage: Int, run: Int, typeFilter: String, loadTypeLabel: Boolean) {
        pageExecutor.execute {
            if (destroyed || run != generation) return@execute
            try {
                val prepared = preparePageResult(data, typeFilter, loadTypeLabel)
                mainHandler.post {
                    if (destroyed || run != generation) return@post
                    applyPageResult(prepared, next, targetPage, run, typeFilter)
                }
            } catch (_: Exception) {
                // Jangan biarkan loading stuck true (pagination mati total) bila prepare gagal
                mainHandler.post {
                    if (destroyed || run != generation) return@post
                    loading = false
                    hasMore = false
                    errorMessage = "Gagal memuat manga"
                }
            }
        }
    }

    private fun preparePageResult(data: ArrayList<MangaPost>, typeFilter: String, loadTypeLabel: Boolean): PreparedBrowsePage {
        val label = MangaSourceFactory.labelForSourceId(sourceId)
        val ready = arrayListOf<MangaPost>()
        data.forEach { post ->
            post.withSource(sourceId, label)
            if (!usesLocalTypeFilter()) sanitizeDefaultType(post)
        }
        // Luvyaa: type sudah difilter server via ?type= — jangan fetch 18x detail per halaman (lambat + blokir pagination)
        if (hasTypeFilter(typeFilter) && !usesLocalTypeFilter() && sourceId != MangaSettingsManager.MANGA_SOURCE_CROTPEDIA && sourceId != MangaSettingsManager.MANGA_SOURCE_LUVYAA && sourceId != MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN && sourceId != MangaSettingsManager.MANGA_SOURCE_KOMIKCAST && sourceId != MangaSettingsManager.MANGA_SOURCE_SHINIGAMI) resolveTypesForFilter(data, typeFilter)
        data.forEach { post ->
            if (matchesTypeFilter(post, typeFilter)) ready.add(post)
        }
        return PreparedBrowsePage(ready)
    }

    private fun applyPageResult(prepared: PreparedBrowsePage, next: Boolean, targetPage: Int, run: Int, typeFilter: String) {
        if (destroyed || run != generation) return
        loading = false
        hasMore = next
        page = targetPage + 1
        errorMessage = ""
        val ready = arrayListOf<MangaPost>()
        val appended = arrayListOf<MangaPost>()
        prepared.posts.forEach { post ->
            val key = sourceItemKey(post)
            if (key.isNotBlank() && loadedKeys.add(key)) {
                ready.add(post)
                appended.add(post)
            }
        }
        if (ready.isNotEmpty()) posts.addAll(ready)
        scheduleTypeResolution(appended, run, typeFilter)
        scheduleEnrichment(appended, run)
        if (hasTypeFilter(typeFilter) && hasMore && posts.size < 12 && filteredAutoLoadCount < 3) {
            filteredAutoLoadCount++
            loadNextPage()
        }
    }

    private fun pageCacheKey(targetPage: Int, requestFilter: String, sortValue: String, queryValue: String, typeValue: String): String {
        val typeKey = if (usesLocalTypeFilter() && sourceId != MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) "" else typeValue.trim()
        return listOf(sourceId, targetPage.toString(), sortValue, queryValue.trim(), requestFilter, typeKey).joinToString("|")
    }

    fun loadGenres() {
        if (loadingGenres || genreItems.isNotEmpty()) return
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO) {
            genreItems.clear()
            genreItems.addAll(fallbackGenres(sourceId))
            loadingGenres = false
            return
        }
        loadingGenres = true
        MangaSourceFactory.createBySourceId(sourceId).genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
            override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>?, hasNext: Boolean) {
                loadingGenres = false
                genreItems.clear()
                val clean = data.orEmpty().filter { it.title.trim().isNotEmpty() && it.value.trim().isNotEmpty() && !it.value.startsWith("type:") && !it.value.startsWith("status:") }
                if (clean.isNotEmpty()) genreItems.addAll(clean) else genreItems.addAll(fallbackGenres(sourceId))
            }

            override fun onError(message: String?) {
                loadingGenres = false
                genreItems.clear()
                genreItems.addAll(fallbackGenres(sourceId))
            }
        })
    }

    fun sortOptions(): List<Pair<String, String>> {
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST) return listOf("latest" to "Terbaru", "popular" to "Populer", "project" to "Project", "mirror" to "Miror", "bookmark" to "Most Bookmark", "newseries" to "New Series", "rating" to "Rating", "az" to "A-Z")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKTAP) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Added", "az" to "A-Z", "za" to "Z-A")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Added", "az" to "A-Z", "completed" to "Completed", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Added", "az" to "A-Z", "za" to "Z-A", "project" to "Project", "completed" to "Completed", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KUROMANGA) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Added", "az" to "A-Z", "za" to "Z-A")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Added", "az" to "A-Z", "completed" to "Completed")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MGKOMIK) return listOf("latest" to "Terbaru", "popularity" to "Populer", "new" to "New Manga", "views" to "Most Views", "az" to "A-Z", "project" to "Project", "completed" to "Completed", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO) return listOf("latest" to "Update", "new" to "New", "completed" to "Completed", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua", "adult_update" to "Dewasa Update", "adult_new" to "Dewasa New", "adult_completed" to "Dewasa Completed", "adult_manga" to "Manga Dewasa", "adult_manhwa" to "Manhwa Dewasa", "adult_manhua" to "Manhua Dewasa")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA) return listOf("latest" to "Update", "popularity" to "Populer", "rating" to "Rating", "added" to "Latest Added", "az" to "A-Z", "za" to "Z-A", "manga" to "Manga", "image-set" to "Image-set", "manhwa" to "Manhwa", "one-shot" to "One-shot", "doujinshi" to "Doujinshi")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK) return listOf("latest" to "Update", "added" to "Added", "popularity" to "Popular", "az" to "A-Z", "za" to "Z-A")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_IKIRU) return listOf("latest" to "Terbaru", "popular" to "Populer", "rating" to "Rating", "project" to "Project")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_LUVYAA) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Baru Ditambahkan", "project" to "Project", "az" to "A-Z", "za" to "Z-A", "completed" to "Selesai", "ongoing" to "Berjalan", "hiatus" to "Hiatus")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN) return listOf("latest" to "Terbaru", "popular" to "Populer", "added" to "Baru Ditambahkan", "az" to "A-Z", "za" to "Z-A")
        val list = arrayListOf("latest" to if (sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS) "Latest" else if (sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL) "Update" else "Terbaru")
        list.add(if (sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS) "views" to "Top Views" else "popularity" to "Populer")
        when (sourceId) {
            MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG -> list.addAll(listOf("title_latest" to "Judul Terbaru", "random" to "Random"))
            MangaSettingsManager.MANGA_SOURCE_KOMIKU -> list.addAll(listOf("rating" to "Rating", "ongoing" to "Ongoing", "completed" to "Completed"))
            MangaSettingsManager.MANGA_SOURCE_MANGASUSU -> list.add("added" to "Baru ditambahkan")
            MangaSettingsManager.MANGA_SOURCE_COSMICSCANS -> list.addAll(listOf("added" to "New Added", "az" to "A-Z", "za" to "Z-A", "project" to "Project"))
            MangaSettingsManager.MANGA_SOURCE_NATSU, MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL -> list.add("project" to "Project")
            MangaSettingsManager.MANGA_SOURCE_AINZSCANSS -> list.addAll(listOf("bookmark" to "Top Favorite", "rate" to "Top Rate"))
            MangaSettingsManager.MANGA_SOURCE_APKOMIK -> list.addAll(listOf("project" to "Project", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua"))
            MangaSettingsManager.MANGA_SOURCE_DOUJINDESU -> list.addAll(listOf("newest" to "Newest", "title_asc" to "A-Z", "oldest" to "Oldest", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua", "doujinshi" to "Doujinshi"))
        }
        return list
    }

    fun typeFilters(): List<Pair<String, String>> {
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SHINIGAMI) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "MANGATOON" to "Mangatoon")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "DOUJINSHI" to "Doujinshi")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "DOUJINSHI" to "Doujinshi", "IMAGE-SET" to "Image-set", "ONESHOT" to "One-shot")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MGKOMIK) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKTAP) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KUROMANGA) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_LUVYAA) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN) return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "DOUJINSHI" to "Doujinshi")
        return when (sourceId) {
            MangaSettingsManager.MANGA_SOURCE_KOMIKU,
            MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
            MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
            MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
            MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
            MangaSettingsManager.MANGA_SOURCE_NATSU,
            MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
            MangaSettingsManager.MANGA_SOURCE_APKOMIK,
            MangaSettingsManager.MANGA_SOURCE_BACAKOMIK,
            MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
            MangaSettingsManager.MANGA_SOURCE_WESTMANGA,
            MangaSettingsManager.MANGA_SOURCE_IKIRU,
            MangaSettingsManager.MANGA_SOURCE_MGKOMIK,
            MangaSettingsManager.MANGA_SOURCE_KOMIKTAP -> listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
            else -> listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "WEBTOON" to "Webtoon")
        }
    }

    private fun requestFilterForSource(queryValue: String = query, typeValue: String = selectedTypeLabel): String {
        if (queryValue.trim().isNotEmpty()) return ""
        val genre = selectedGenre.trim()
        val rawType = typeValue.trim()
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) {
            val normalizedType = normalizeTypeFilterValue(rawType)
            val typeFilter = if (normalizedType.isEmpty()) "" else "type:$normalizedType"
            return if (genre.isEmpty()) typeFilter else if (typeFilter.isEmpty()) genre else "$genre|$typeFilter"
        }
        if (usesLocalTypeFilter()) return genre
        if (rawType.isEmpty()) return genre
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SHINIGAMI) {
            val formatFilter = "format:" + normalizeTypeFilterValue(rawType)
            return if (genre.isEmpty()) formatFilter else "$genre|$formatFilter"
        }
        val supportsTypeKey = sourceId == MangaSettingsManager.MANGA_SOURCE_IKIRU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU || sourceId == MangaSettingsManager.MANGA_SOURCE_MANGASUSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG || sourceId == MangaSettingsManager.MANGA_SOURCE_COSMICSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL || sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS || sourceId == MangaSettingsManager.MANGA_SOURCE_APKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA || sourceId == MangaSettingsManager.MANGA_SOURCE_MGKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKTAP || sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KUROMANGA || sourceId == MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST || sourceId == MangaSettingsManager.MANGA_SOURCE_LUVYAA || sourceId == MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN
        if (!supportsTypeKey) return genre
        val normalizedType = normalizeTypeFilterValue(rawType)
        val typeFilter = "type:$normalizedType"
        return if (genre.isEmpty()) typeFilter else "$genre|$typeFilter"
    }

    private fun defaultTypeForSort(value: String): String {
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MGKOMIK) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKTAP) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KUROMANGA) return ""
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK) return ""
        if (sourceId != MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG && sourceId != MangaSettingsManager.MANGA_SOURCE_COSMICSCANS) return ""
        return when (value) {
            "manga" -> "MANGA"
            "manhwa" -> "MANHWA"
            "manhua" -> "MANHUA"
            "doujinshi" -> "DOUJINSHI"
            else -> ""
        }
    }

    private fun normalizeTypeFilterValue(rawType: String): String {
        return when (rawType.uppercase(Locale.ROOT)) {
            "MANHWA" -> "manhwa"
            "MANHUA" -> "manhua"
            "MANGATOON" -> "mangatoon"
            "DOUJINSHI" -> "doujinshi"
            "IMAGE-SET" -> "image-set"
            "ONESHOT", "ONE-SHOT" -> "one-shot"
            "COMIC" -> "comic"
            "NOVEL" -> "novel"
            "MANGA" -> "manga"
            else -> rawType.trim()
        }
    }

    private fun usesLocalTypeFilter(): Boolean {
        return sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU || sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO
    }

    fun selectedGenreLabel(): String {
        val value = selectedGenre.trim()
        if (value.isEmpty()) return "Filter Genre"
        return genreItems.firstOrNull { it.value == value }?.title?.trim().orEmpty().ifBlank { genreTitle.ifBlank { value } }
    }

    private fun hasTypeFilter(value: String = selectedTypeLabel): Boolean {
        return value.trim().isNotEmpty()
    }

    private fun sanitizeDefaultType(post: MangaPost) {
        val raw = post.typeLabel?.trim().orEmpty()
        if (!raw.equals("MANGA", true)) return
        val supportText = listOf(post.genre, post.status, post.info).joinToString(" ") { it.orEmpty() }
        if (normalizeBrowseType(supportText, false).isEmpty()) post.typeLabel = ""
    }

    private fun resolveTypesForFilter(data: ArrayList<MangaPost>, typeFilter: String) {
        val requested = normalizeBrowseType(typeFilter, true)
        if (requested.isEmpty()) return
        val targets = data.filter { post ->
            val slug = post.slug.orEmpty().trim()
            val key = sourceItemKey(post)
            slug.isNotEmpty() && key.isNotEmpty() && !resolvedTypeKeys.contains(key)
        }
        if (targets.isEmpty()) return
        val latch = CountDownLatch(targets.size)
        val source = MangaSourceFactory.createBySourceId(sourceId)
        val label = MangaSourceFactory.labelForSourceId(sourceId)
        targets.forEach { post ->
            val key = sourceItemKey(post)
            source.detail(post.slug.orEmpty().trim(), object : KomikcastClient.Result<MangaPost> {
                override fun onSuccess(data: MangaPost?, hasNext: Boolean) {
                    resolvedTypeKeys.add(key)
                    if (data != null) {
                        data.withSource(sourceId, label)
                        mergeDetailIntoPost(post, data)
                    } else {
                        verifiedTypeLabels.remove(key)
                    }
                    latch.countDown()
                }

                override fun onError(message: String?) {
                    resolvedTypeKeys.add(key)
                    verifiedTypeLabels.remove(key)
                    latch.countDown()
                }
            })
        }
        try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun mergeDetailIntoPost(post: MangaPost, detail: MangaPost) {
        val key = sourceItemKey(post)
        if (key.isNotEmpty()) resolvedTypeKeys.add(key)
        val type = strictBrowseTypeLabel(detail, true)
        if (type.isNotEmpty()) {
            post.typeLabel = type
            if (key.isNotEmpty()) verifiedTypeLabels[key] = type
        } else {
            post.typeLabel = ""
            if (key.isNotEmpty()) verifiedTypeLabels.remove(key)
        }
        if (post.genre.orEmpty().trim().isEmpty() && detail.genre.orEmpty().trim().isNotEmpty()) post.genre = detail.genre
        if (post.status.orEmpty().trim().isEmpty() && detail.status.orEmpty().trim().isNotEmpty()) post.status = detail.status
        if (post.synopsis.orEmpty().trim().isEmpty() && detail.synopsis.orEmpty().trim().isNotEmpty()) post.synopsis = detail.synopsis
        if (post.author.orEmpty().trim().isEmpty() && detail.author.orEmpty().trim().isNotEmpty()) post.author = detail.author
        if (post.latestChapter.orEmpty().trim().isEmpty() && detail.latestChapter.orEmpty().trim().isNotEmpty()) post.latestChapter = detail.latestChapter
        if (post.latestChapterDate.orEmpty().trim().isEmpty() && detail.latestChapterDate.orEmpty().trim().isNotEmpty()) post.latestChapterDate = detail.latestChapterDate
        if (post.totalChapters <= 0 && detail.totalChapters > 0) post.totalChapters = detail.totalChapters
    }

    private fun strictBrowseTypeLabel(post: MangaPost, trustRawManga: Boolean = false): String {
        val raw = post.typeLabel?.trim().orEmpty()
        val supportText = listOf(post.genre, post.status, post.info).joinToString(" ") { it.orEmpty() }
        val supportType = normalizeBrowseType(supportText, false)
        val rawType = normalizeBrowseType(raw, true)
        if (rawType.isEmpty()) return supportType
        if (rawType == "MANGA" && !trustRawManga && raw.equals("MANGA", true) && supportType.isEmpty()) return ""
        return rawType
    }

    private fun browseTypeLabel(post: MangaPost): String {
        val key = sourceItemKey(post)
        val verified = verifiedTypeLabels[key].orEmpty()
        if (verified.isNotEmpty()) return verified
        return strictBrowseTypeLabel(post)
    }

    private fun matchesTypeFilter(post: MangaPost, typeFilter: String = selectedTypeLabel): Boolean {
        val type = normalizeBrowseType(typeFilter, true)
        if (type.isEmpty()) return true
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA && !usesLocalTypeFilter()) return true
        // Luvyaa: type sudah difilter server via ?type=, percaya hasil server (tanpa fetch detail)
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_LUVYAA || sourceId == MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST || sourceId == MangaSettingsManager.MANGA_SOURCE_SHINIGAMI) return true
        val postType = browseTypeLabel(post)
        if (postType.equals(type, true)) return true
        if (usesLocalTypeFilter()) return false
        val key = sourceItemKey(post)
        if (key.isNotEmpty() && resolvedTypeKeys.contains(key)) return verifiedTypeLabels[key].orEmpty().equals(type, true)
        if (post.slug.orEmpty().trim().isNotEmpty()) return false
        return false
    }

    private fun scheduleTypeResolution(items: ArrayList<MangaPost>, run: Int, typeFilter: String) {
        if (items.isEmpty() || hasTypeFilter(typeFilter) || !MangaSettingsManager.shouldLoadTypeLabel(context)) return
        val targets = arrayListOf<MangaPost>()
        items.forEach { post ->
            val key = sourceItemKey(post)
            if (post.slug.orEmpty().trim().isNotEmpty() && key.isNotEmpty() && !resolvedTypeKeys.contains(key) && browseTypeLabel(post).isEmpty() && typeResolveKeys.add(key)) targets.add(post)
        }
        if (targets.isEmpty()) return
        mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { resolveTypesForAllBatch(targets, 0, run) } }, 180L)
    }

    private fun resolveTypesForAllBatch(targets: ArrayList<MangaPost>, start: Int, run: Int) {
        if (destroyed || run != generation || start >= targets.size) return
        val end = minOf(targets.size, start + 3)
        val batch = ArrayList(targets.subList(start, end))
        val source = MangaSourceFactory.createBySourceId(sourceId)
        val label = MangaSourceFactory.labelForSourceId(sourceId)
        val latch = CountDownLatch(batch.size)
        batch.forEach { post ->
            val key = sourceItemKey(post)
            source.detail(post.slug.orEmpty().trim(), object : KomikcastClient.Result<MangaPost> {
                override fun onSuccess(data: MangaPost?, hasNext: Boolean) {
                    mainHandler.post {
                        if (!destroyed && run == generation) {
                            if (data != null) {
                                data.withSource(sourceId, label)
                                mergeDetailIntoPost(post, data)
                            } else if (key.isNotEmpty()) {
                                resolvedTypeKeys.add(key)
                                verifiedTypeLabels.remove(key)
                            }
                            refreshPostsAfterEnrich(arrayListOf(post))
                        }
                    }
                    latch.countDown()
                }

                override fun onError(message: String?) {
                    mainHandler.post {
                        if (!destroyed && run == generation && key.isNotEmpty()) {
                            resolvedTypeKeys.add(key)
                            verifiedTypeLabels.remove(key)
                            refreshPostsAfterEnrich(arrayListOf(post))
                        }
                    }
                    latch.countDown()
                }
            })
        }
        try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        mainHandler.post {
            if (destroyed || run != generation) return@post
            mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { resolveTypesForAllBatch(targets, end, run) } }, 220L)
        }
    }

    private fun shouldEnrichPost(post: MangaPost): Boolean {
        val loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel(context)
        val loadType = MangaSettingsManager.shouldLoadTypeLabel(context) || hasTypeFilter()
        if (!loadChapter && !loadType) return false
        val missingChapter = loadChapter && post.latestChapter.orEmpty().trim().isEmpty()
        val rawType = post.typeLabel?.trim().orEmpty()
        val missingType = loadType && browseTypeLabel(post).isEmpty()
        val komikindoDefaultType = loadType && sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO && rawType.equals("MANGA", true)
        val enrichTypeSource = loadType && (sourceId == MangaSettingsManager.MANGA_SOURCE_MANGASUSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG || sourceId == MangaSettingsManager.MANGA_SOURCE_COSMICSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL || sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS || sourceId == MangaSettingsManager.MANGA_SOURCE_APKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA || sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KUROMANGA || sourceId == MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK)
        return missingChapter || (enrichTypeSource && (missingType || komikindoDefaultType))
    }

    private fun scheduleEnrichment(items: ArrayList<MangaPost>, run: Int) {
        if (items.isEmpty()) return
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel(context) && !MangaSettingsManager.shouldLoadTypeLabel(context) && !hasTypeFilter()) return
        val targets = arrayListOf<MangaPost>()
        items.forEach { post ->
            val key = sourceItemKey(post)
            if (post.slug.orEmpty().trim().isNotEmpty() && shouldEnrichPost(post) && key.isNotEmpty() && latestEnrichKeys.add(key)) targets.add(post)
        }
        if (targets.isEmpty()) return
        mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { enrichLatestBatch(targets, 0, run) } }, 320L)
    }

    private fun enrichLatestBatch(targets: ArrayList<MangaPost>, start: Int, run: Int) {
        if (run != generation || start >= targets.size) return
        val end = minOf(targets.size, start + 2)
        val batch = ArrayList(targets.subList(start, end))
        MangaSourceFactory.createBySourceId(sourceId).enrichLatest(batch) {
            if (run != generation) return@enrichLatest
            mainHandler.post {
                if (destroyed || run != generation) return@post
                refreshPostsAfterEnrich(batch)
                mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { enrichLatestBatch(targets, end, run) } }, 220L)
            }
        }
    }

    private fun refreshPostsAfterEnrich(items: ArrayList<MangaPost>) {
        var changed = false
        items.forEach { item ->
            val key = sourceItemKey(item)
            val index = posts.indexOfFirst { sourceItemKey(it) == key }
            if (index >= 0) {
                val type = strictBrowseTypeLabel(item, true)
                if (type.isNotEmpty()) {
                    item.typeLabel = type
                    verifiedTypeLabels[key] = type
                    resolvedTypeKeys.add(key)
                } else {
                    item.typeLabel = ""
                    verifiedTypeLabels.remove(key)
                }
                posts[index] = item
                changed = true
            }
        }
        if (!changed) return
    }

    private fun sourceItemKey(post: MangaPost): String {
        val base = post.slug.orEmpty().trim().ifBlank { post.title.orEmpty().trim() }
        if (base.isEmpty()) return ""
        return post.getSourceId() + ":" + base
    }

    private fun readDisplayMode(context: Context): LibraryDisplayMode {
        val saved = context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).getString("pref_display_mode_catalogue", null)
        return LibraryDisplayMode.deserialize(saved)
    }
}

@Composable
private fun MikuSourceTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val primary = Color(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary))
    val onPrimary = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary))
    val primaryContainer = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer))
    val onPrimaryContainer = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimaryContainer))
    val background = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
    val surfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant))
    val onSurface = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface))
    val onSurfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant))
    val outline = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline))
    val scheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        background = background,
        surface = background,
        surfaceVariant = surfaceVariant,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp)
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

@Composable
private fun BrowseSourceScreenContent(controller: BrowseSourceController, navigateUp: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, onOpenWebView: () -> Unit) {
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    val handleBack = {
        if (!controller.resetToDefault()) navigateUp()
    }
    BackHandler(onBack = handleBack)
    val context = LocalContext.current
    val uiFlags = remember(controller.sourceId, controller.displayMode) {
        MangaUiFlags(
            showTypeLabel = !MangaSettingsManager.shouldHideTypeLabel(context),
            showLatestChapterLabel = !MangaSettingsManager.shouldHideLatestChapterLabel(context),
            boldTitle = MangaSettingsManager.isBoldMangaTitleEnabled(context),
        )
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            BrowseSourceToolbar(
                title = controller.title(),
                sourceId = controller.sourceId,
                searchQuery = controller.toolbarQuery,
                onSearchQueryChange = { controller.toolbarQuery = it },
                displayMode = controller.displayMode,
                onDisplayModeChange = controller::updateDisplayMode,
                navigateUp = handleBack,
                onSearchClick = { controller.toolbarQuery = controller.query },
                onCloseSearch = controller::clearSearch,
                onSearch = controller::search,
                onOpenWebView = onOpenWebView,
            )
            if (!controller.genreRoute) {
                BrowseSourceFilterRow(
                    controller = controller,
                    onFilterClick = {
                        controller.loadGenres()
                        showFilterSheet = true
                    },
                )
            }
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            BrowseSourceContent(
                posts = controller.posts,
                columns = controller.getColumnsPreference(LocalConfiguration.current),
                displayMode = controller.displayMode,
                loading = controller.loading,
                hasMore = controller.hasMore,
                errorMessage = controller.errorMessage,
                onLoadMore = controller::loadNextPage,
                onMangaClick = onMangaClick,
                onChapterClick = onChapterClick,
                uiFlags = uiFlags,
            )
        }
    }
    if (showFilterSheet) {
        GenreFilterSheet(
            controller = controller,
            onDismiss = { showFilterSheet = false },
            onSelect = {
                controller.setGenre(it)
                showFilterSheet = false
            },
        )
    }
}

@Composable
private fun BrowseSourceToolbar(
    title: String,
    sourceId: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenWebView: () -> Unit,
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
                    placeholder = { Text("Cari manga") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        windowInsets = WindowInsets(0.dp),
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
                    RadioDisplayModeMenuItem("Grid nyaman", displayMode == LibraryDisplayMode.ComfortableGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                    }
                    RadioDisplayModeMenuItem("Grid kompak", displayMode == LibraryDisplayMode.CompactGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                    }
                    RadioDisplayModeMenuItem("Daftar", displayMode == LibraryDisplayMode.List) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.List)
                    }
                }
            }
            if (sourceId == MangaSettingsManager.MANGA_SOURCE_COMICASO || sourceId == MangaSettingsManager.MANGA_SOURCE_CROTPEDIA || sourceId == MangaSettingsManager.MANGA_SOURCE_NGOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_MANHWAINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_SOULSCANS) {
                IconButton(onClick = onOpenWebView) {
                    Icon(painterResource(R.drawable.ic_webview), contentDescription = "Buka WebView")
                }
            }
        },
    )
}

@Composable
private fun RadioDisplayModeMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { RadioButton(selected = checked, onClick = onClick) },
        onClick = onClick,
    )
}

@Composable
private fun BrowseSourceFilterRow(controller: BrowseSourceController, onFilterClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            controller.sortOptions().forEach { item ->
                val leadingIcon: (@Composable () -> Unit)? = when {
                    item.first == "popularity" || item.first == "views" -> ({ Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    item.first == "latest" -> ({ Icon(Icons.Outlined.NewReleases, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    else -> null
                }
                FilterChip(
                    selected = controller.sort == item.first,
                    onClick = { controller.setListing(item.first) },
                    leadingIcon = leadingIcon,
                    label = { Text(item.second, maxLines = 1) },
                )
            }
            FilterChip(
                selected = controller.selectedGenre.isNotBlank(),
                onClick = onFilterClick,
                leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(if (controller.selectedGenre.isBlank()) "Filter Genre" else "Genre: ${controller.selectedGenreLabel()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        val typeFilters = controller.typeFilters()
        if (typeFilters.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                typeFilters.forEach { item ->
                    FilterChip(
                        selected = controller.selectedTypeLabel == item.first,
                        onClick = { controller.setType(item.first) },
                        label = { Text(item.second, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreFilterSheet(controller: BrowseSourceController, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Filter Genre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (controller.loadingGenres && controller.genreItems.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item {
                        GenreSheetRow("Semua", controller.selectedGenre.isBlank()) { onSelect("") }
                    }
                    items(controller.genreItems, key = { it.value }) { item ->
                        GenreSheetRow(item.title, controller.selectedGenre == item.value) { onSelect(item.value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreSheetRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrowseSourceContent(
    posts: List<MangaPost>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    errorMessage: String,
    onLoadMore: () -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onChapterClick: (MangaPost) -> Unit,
    uiFlags: MangaUiFlags,
) {
    when {
        posts.isEmpty() && loading -> LoadingScreen()
        posts.isEmpty() -> EmptyScreen(errorMessage.ifBlank { "Tidak ada hasil" })
        else -> BrowseSourceStableMikuContent(
            posts = posts,
            displayMode = displayMode,
            loading = loading,
            hasMore = hasMore,
            onLoadMore = onLoadMore,
            onMangaClick = onMangaClick,
            onChapterClick = onChapterClick,
            uiFlags = uiFlags,
        )
    }
}

private class BrowseSourceAndroidHolder(
    var mode: Int,
    val listView: android.widget.AbsListView,
    val adapter: BrowseSourceMihonAdapter,
)

@Composable
private fun BrowseSourceStableMikuContent(
    posts: List<MangaPost>,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onChapterClick: (MangaPost) -> Unit,
    uiFlags: MangaUiFlags,
) {
    val mode = when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> BrowseSourceMihonAdapter.MODE_COMFORTABLE_GRID
        LibraryDisplayMode.List -> BrowseSourceMihonAdapter.MODE_LIST
        else -> BrowseSourceMihonAdapter.MODE_COMPACT_GRID
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { container ->
            var holder = container.getTag(R.id.browseSourceAndroidHolder) as? BrowseSourceAndroidHolder
            if (holder == null || holder.mode != mode) {
                container.removeAllViews()
                val adapter = BrowseSourceMihonAdapter(container.context, posts, { onMangaClick(it) }, { onChapterClick(it) })
                adapter.setMode(mode)
                adapter.setGridColumns(MangaSettingsManager.getMangaGridColumns(container.context))
                adapter.bindFlags(uiFlags.showTypeLabel, uiFlags.showLatestChapterLabel, uiFlags.boldTitle)
                val listView: android.widget.AbsListView = if (mode == BrowseSourceMihonAdapter.MODE_LIST) {
                    android.widget.ListView(container.context).apply {
                        divider = null
                        dividerHeight = 0
                        setPadding(0, dpValue(context, 8), 0, dpValue(context, 88))
                        clipToPadding = false
                    }
                } else {
                    android.widget.GridView(container.context).apply {
                        numColumns = MangaSettingsManager.getMangaGridColumns(container.context)
                        stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
                        // Cover tetap besar: antar cover 8dp, antar baris 14dp (item punya paddingBottom 5dp), tepi 12dp
                        horizontalSpacing = dpValue(context, 8)
                        verticalSpacing = dpValue(context, 9)
                        setPadding(dpValue(context, 12), dpValue(context, 8), dpValue(context, 12), dpValue(context, 88))
                        clipToPadding = false
                    }
                }
                listView.adapter = adapter
                listView.cacheColorHint = android.graphics.Color.TRANSPARENT
                listView.isFastScrollEnabled = false
                listView.isVerticalScrollBarEnabled = false
                container.addView(listView, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                holder = BrowseSourceAndroidHolder(mode, listView, adapter)
                container.setTag(R.id.browseSourceAndroidHolder, holder)
            }
            holder.adapter.setMode(mode)
            holder.adapter.setGridColumns(MangaSettingsManager.getMangaGridColumns(container.context))
            holder.adapter.bindFlags(uiFlags.showTypeLabel, uiFlags.showLatestChapterLabel, uiFlags.boldTitle)
            val activeListView = holder.listView
            if (activeListView is android.widget.GridView && mode != BrowseSourceMihonAdapter.MODE_LIST) {
                activeListView.numColumns = MangaSettingsManager.getMangaGridColumns(container.context)
            }
            holder.listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (hasMore && !loading && totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) onLoadMore()
                }
            })
            holder.adapter.notifyDataSetChanged()
        },
    )
}

private fun dpValue(context: Context, value: Int): Int {
    return kotlin.math.round(value * context.resources.displayMetrics.density).toInt()
}

@Composable
private fun BrowseSourceComfortableGrid(posts: List<MangaPost>, columns: GridCells, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        state = state,
        columns = columns,
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(posts.size, key = { posts[it].slug.ifBlank { posts[it].title } }) { index ->
            MangaComfortableGridItem(post = posts[index], onClick = { onMangaClick(posts[index]) }, onChapterClick = { onChapterClick(posts[index]) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

@Composable
private fun BrowseSourceCompactGrid(posts: List<MangaPost>, columns: GridCells, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        state = state,
        columns = columns,
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(posts.size, key = { posts[it].slug.ifBlank { posts[it].title } }) { index ->
            MangaCompactGridItem(post = posts[index], onClick = { onMangaClick(posts[index]) }, onChapterClick = { onChapterClick(posts[index]) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

@Composable
private fun BrowseSourceList(posts: List<MangaPost>, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyListState()
    LazyColumn(state = state, contentPadding = PaddingValues(0.dp, 8.dp, 0.dp, 88.dp)) {
        items(posts, key = { it.slug.ifBlank { it.title } }) { post ->
            MangaListItem(post = post, onClick = { onMangaClick(post) }, onChapterClick = { onChapterClick(post) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

private object CommonMangaItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp
}

@Composable
private fun MangaCompactGridItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Box(
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(4.dp),
    ) {
        MangaGridCover(post = post) {
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), uiFlags = uiFlags)
            MangaChapterBadge(post = post, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), onClick = onChapterClick, uiFlags = uiFlags)
            CoverTextOverlay(title = post.title, uiFlags = uiFlags)
        }
    }
}

@Composable
private fun MangaComfortableGridItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Column(modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(4.dp)) {
        MangaGridCover(post = post) {
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), uiFlags = uiFlags)
            MangaChapterBadge(post = post, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), onClick = onChapterClick, uiFlags = uiFlags)
        }
        GridItemTitle(
            modifier = Modifier.padding(4.dp),
            title = post.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            uiFlags = uiFlags,
        )
    }
}

@Composable
private fun MangaGridCover(post: MangaPost, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        MangaCoverImage(post = post, modifier = Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun MangaCoverImage(post: MangaPost, modifier: Modifier = Modifier) {
    val coverUrl = post.coverImage.orEmpty().trim()
    val sourceId = post.getSourceId().orEmpty()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
            }
        },
        update = { imageView ->
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            if (coverUrl.isBlank()) MangaImageLoader.clear(imageView) else MangaImageLoader.loadForSource(imageView, coverUrl, sourceId, true, null)
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverTextOverlay(title: String, uiFlags: MangaUiFlags) {
    Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.33f).background(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xAA000000)),
        ),
    )
    GridItemTitle(
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        title = title,
        style = MaterialTheme.typography.titleSmall.copy(color = Color.White),
        minLines = 1,
        maxLines = 2,
        uiFlags = uiFlags,
    )
}

@Composable
private fun MangaTypeFlag(post: MangaPost, modifier: Modifier = Modifier, uiFlags: MangaUiFlags) {
    if (!uiFlags.showTypeLabel) return
    val flagRes = typeFlagRes(post) ?: return
    val type = displayTypeLabel(post)
    Image(
        painter = painterResource(flagRes),
        contentDescription = type,
        modifier = modifier.size(width = 28.dp, height = 20.dp),
    )
}

@Composable
private fun MangaChapterBadge(post: MangaPost, modifier: Modifier = Modifier, onClick: () -> Unit, uiFlags: MangaUiFlags) {
    if (!uiFlags.showLatestChapterLabel) return
    val text = latestChapterText(post)
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xAA000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = Color.White,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun latestChapterText(post: MangaPost): String {
    return post.latestChapter.orEmpty().trim()
}

private fun typeFlagRes(post: MangaPost): Int? {
    return when (displayTypeLabel(post)) {
        "MANHUA" -> R.drawable.ic_flag_china
        "MANHWA" -> R.drawable.ic_flag_korea
        "MANGA", "DOUJINSHI", "DOUJIN", "ONESHOT", "IMAGE-SET" -> R.drawable.ic_flag_japan
        else -> null
    }
}

private fun displayTypeLabel(post: MangaPost): String {
    val raw = post.typeLabel?.trim().orEmpty()
    val supportText = listOf(post.genre, post.status, post.info).joinToString(" ") { it.orEmpty() }
    val supportType = normalizeBrowseType(supportText, false)
    val rawType = normalizeBrowseType(raw, true)
    if (rawType.isEmpty()) return supportType
    return rawType
}

private fun normalizeBrowseType(raw: String?, trustManga: Boolean): String {
    val padded = " " + raw.orEmpty().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    if (padded.trim().isEmpty()) return ""
    return when {
        padded.contains(" manhwa ") || padded.contains(" korea ") || padded.contains(" korean ") -> "MANHWA"
        padded.contains(" manhua ") || padded.contains(" china ") || padded.contains(" chinese ") -> "MANHUA"
        padded.contains(" webtoon ") || padded.contains(" web toon ") -> "WEBTOON"
        padded.contains(" image set ") || padded.contains(" imageset ") -> "IMAGE-SET"
        padded.contains(" doujinshi ") -> "DOUJINSHI"
        padded.contains(" doujin ") -> "DOUJIN"
        padded.contains(" comic ") -> "COMIC"
        padded.contains(" novel ") -> "NOVEL"
        padded.contains(" oneshot ") || padded.contains(" one shot ") -> "ONESHOT"
        padded.contains(" manga ") || padded.contains(" japan ") || padded.contains(" japanese ") -> if (trustManga) "MANGA" else ""
        else -> ""
    }
}

@Composable
private fun GridItemTitle(title: String, style: TextStyle, minLines: Int, modifier: Modifier = Modifier, maxLines: Int = 2, uiFlags: MangaUiFlags) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (uiFlags.boldTitle) FontWeight.SemiBold else FontWeight.Normal,
        style = style,
    )
}

@Composable
private fun MangaListItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MangaCoverImage(post = post, modifier = Modifier.fillMaxSize())
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(width = 18.dp, height = 13.dp), uiFlags = uiFlags)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp).weight(1f)) {
            Text(
                text = post.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (uiFlags.boldTitle) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            val chapter = latestChapterText(post)
            if (uiFlags.showLatestChapterLabel && chapter.isNotBlank()) {
                Text(
                    text = chapter,
                    modifier = Modifier.clickable(onClick = onChapterClick),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BrowseSourceLoadMoreItem(loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit) {
    LaunchedEffect(hasMore, loading) {
        if (hasMore && !loading) {
            delay(180L)
            onLoadMore()
        }
    }
    if (loading) BrowseSourceLoadingItem()
}

@Composable
private fun BrowseSourceLoadingItem() {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyScreen(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fallbackGenres(sourceId: String, sort: String = "latest"): List<KomikcastClient.GenreItem> {
    val values = when (sourceId) {
        MangaSettingsManager.MANGA_SOURCE_DOUJINDESU -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Romance" to "romance", "School Life" to "school-life", "Shounen" to "shounen", "Slice of Life" to "slice-of-life", "Doujinshi" to "doujinshi")
        MangaSettingsManager.MANGA_SOURCE_WESTMANGA -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Romance" to "romance", "School Life" to "school-life", "Seinen" to "seinen", "Shounen" to "shounen", "Supernatural" to "supernatural")
        MangaSettingsManager.MANGA_SOURCE_COSMICSCANS -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Martial Arts" to "martial-arts", "Romance" to "romance", "School Life" to "school-life", "Shounen" to "shounen", "Supernatural" to "supernatural", "System" to "system", "Thriller" to "thriller", "Murim" to "murim")
        MangaSettingsManager.MANGA_SOURCE_CROTPEDIA -> listOf("2 Penetration" to "2-penetration", "3 Penetration" to "3-penetration", "Ahegao" to "ahegao", "Anal" to "anal", "Apron" to "apron", "Bdsm" to "bdsm", "Big Breast" to "big-breast", "Big Penis" to "big-penis", "Blackmail" to "blackmail", "Bloomers" to "bloomers", "Blowjob" to "blowjob", "Body Swap" to "body-swap", "Bondage" to "bondage", "Bukkake" to "bukkake", "Bunny Girl" to "bunny-girl", "Censored" to "censored", "Cervix Penetration" to "cervix-penetration", "Cheating" to "cheating", "Chinese Dress" to "chinese-dress", "Colored" to "colored", "Condom" to "condom", "Cosplay" to "cosplay", "Crossdressing" to "crossdressing", "Dark Skin" to "dark-skin", "Deepthroat" to "deepthroat", "Double Penetration" to "double-penetration", "Drama" to "drama", "Elf" to "elf", "Emotionless" to "emotionless", "exhibitionism" to "exhibitionism", "Fanbox" to "fanbox", "Female Only" to "female-only", "Femboy" to "femboy", "Femdom" to "femdom", "FFM Threesome" to "ffm-threesome", "Filming" to "filming", "Fingering" to "fingering", "Footjob" to "footjob", "Force" to "force", "Fox Girl" to "fox-girl", "Futanari" to "futanari", "Gender Bender" to "gender-bender", "Glasses" to "glasses", "Group" to "group", "Hair Buns" to "hair-buns", "Handjob" to "handjob", "Harem" to "harem", "Impregnation" to "impregnation", "Incest" to "incest", "Inseki" to "inseki", "Kemonomimi" to "kemonomimi", "Kimono" to "kimono", "Kissing" to "kissing", "Kogal" to "kogal", "Kuudere" to "kuudere", "Lactation" to "lactation", "Lingerie" to "lingerie", "Loli" to "loli", "Maid" to "maid", "Manhwa" to "manhwa", "Masturbation" to "masturbation", "Milf" to "milf", "Mind Break" to "mind-break", "Mind Control" to "mind-control", "MMF Threesome" to "mmf-threesome", "Monster" to "monster", "Nakadashi" to "nakadashi", "Netorare" to "netorare", "Netorase" to "netorase", "Netori" to "netori", "No Penetration" to "no-penetration", "Nun" to "nun", "Nurse" to "nurse", "Office Lady" to "office-lady", "Old Man" to "old-man", "Osananajimi" to "osananajimi", "Oyakodon" to "oyakodon", "Paizuri" to "paizuri", "Pantyhose" to "pantyhose", "Parodi: Ao no Hako" to "parodi-ao-no-hako", "Parodi: Arknights" to "parodi-arknights", "Parodi: Azur Lane" to "parodi-azur-lane", "Parodi: Blue Archive" to "parodi-blue-archive", "Parodi: Bocchi the Rock!" to "parodi-bocchi-the-rock", "Parodi: Boku no Hero Academia" to "parodi-boku-no-hero-academia", "Parodi: Boku no Kokoro no Yabai Yatsu" to "parodi-boku-no-kokoro-no-yabai-yatsu", "Parodi: Bokutachi wa Benkyou ga Dekinai" to "parodi-bokutachi-wa-benkyou-ga-dekinai", "Parodi: Fate Grand Order" to "parodi-fate-grand-order", "Parodi: Genshin Impact" to "parodi-genshin-impact", "Parodi: Girls Frontline" to "parodi-girls-frontline", "Parodi: Gotoubun no Hanayome" to "parodi-gotoubun-no-hanayome", "Parodi: Hololive" to "parodi-hololive", "Parodi: Honkai Impact" to "parodi-honkai-impact", "Parodi: Honkai Star Rail" to "parodi-honkai-star-rail", "Parodi: Kantai Collection" to "parodi-kantai-collection", "Parodi: Kyoukai no Kanata" to "parodi-kyoukai-no-kanata", "Parodi: Love Live" to "parodi-love-live", "Parodi: Make Heroine ga Oosugiru" to "parodi-make-heroine-ga-oosugiru", "Parodi: Nanabun no Nijyuuni" to "parodi-nanabun-no-nijyuuni", "Parodi: Nijisanji" to "parodi-nijisanji", "Parodi: Nikke Goddes of Factory" to "parodi-nikke-goddes-of-factory", "Parodi: Princess Connect!" to "parodi-princess-connect", "Parodi: Seishun Buta Yarou wa Bunny Girl Senpai no Yume o Minai" to "parodi-seishun-buta-yarou-wa-bunny-girl-senpai-no-yume-o-minai", "Parodi: Sono Bisque Doll wa Koi o Suru" to "parodi-sono-bisque-doll-wa-koi-o-suru", "Parodi: Sousou no Frieren" to "parodi-sousou-no-frieren", "Parodi: The iDOLM@STER" to "parodi-the-idolmster", "Parodi: To Love-Ru" to "parodi-to-love-ru", "Parodi: Tokidoki Bosotto Russia-go de Dereru Tonari no Alya-san" to "parodi-tokidoki-bosotto-russia-go-de-dereru-tonari-no-alya-san", "Parodi: Touhou" to "parodi-touhou", "Parodi: Xenoblade Chronicles" to "parodi-xenoblade-chronicles", "Parodi: Zenless Zone Zero" to "parodi-zenless-zone-zero", "Pegging" to "pegging", "Pixiv" to "pixiv", "Ponytail" to "ponytail", "Possession" to "possession", "Pregnant" to "pregnant", "Prostitution" to "prostitution", "Rape" to "rape", "Reincarnation" to "reincarnation", "Rimjob" to "rimjob", "Romance" to "romance", "Shimaidon" to "shimaidon", "Shotacon" to "shotacon", "Sister" to "sister", "Sleeping" to "sleeping", "Sole Female" to "sole-female", "Sole Male" to "sole-male", "Squirting" to "squirting", "Stockings" to "stockings", "Stomach Deformation" to "stomach-deformation", "Succubus" to "succubus", "Sweating" to "sweating", "Swimsuit" to "swimsuit", "Tankoubon" to "tankoubon", "Teacher" to "teacher", "Tomboy" to "tomboy", "Toys" to "toys", "Tsundere" to "tsundere", "Twins" to "twins", "Twintails" to "twintails", "Uncensored" to "uncensored", "Uniform" to "uniform", "Vanilla" to "vanilla", "Virginity" to "virginity", "Webtoon" to "webtoon", "X-Ray" to "x-ray", "Yandere" to "yandere", "Yuri" to "yuri")
        MangaSettingsManager.MANGA_SOURCE_KOMIKTAP -> listOf("Action" to "107", "Adult" to "2", "Adventure" to "108", "Comedy" to "3", "Drama" to "16", "Ecchi" to "4", "Fantasy" to "109", "Harem" to "20", "Isekai" to "985", "Romance" to "6", "School" to "2487", "School Life" to "7", "Seinen" to "8", "Shounen" to "13", "Slice of Life" to "14", "Smut" to "285", "Supernatural" to "9", "Vanilla" to "589")
        MangaSettingsManager.MANGA_SOURCE_MANHWAINDO -> listOf("4-Koma" to "4", "Action" to "3", "Adult" to "6669", "Adventure" to "12", "Boys' Love" to "2828", "Comedy" to "5", "Cooking" to "115", "Crime" to "1764", "Crossdressing" to "7101", "Demon" to "7336", "Demon Fantasy" to "7470", "Demons" to "217", "Drama" to "18", "Ecchi" to "22", "Fantasy" to "13", "Game" to "14", "Gender Bender" to "112", "Gore" to "48", "Harem" to "23", "Historical" to "191", "Horror" to "53", "Isekai" to "28", "Josei" to "41", "Magic" to "58", "Manhwa" to "7136", "Martial Arts" to "51", "Mature" to "30", "Mecha" to "88", "Medical" to "162", "Military" to "117", "Murim" to "7103", "Music" to "577", "Mystery" to "60", "One-Shot" to "9", "Oneshot" to "4369", "Psychological" to "61", "Regression" to "7410", "Reincarnation" to "46", "Romance" to "16", "School" to "56", "School Life" to "6", "Sci-Fi" to "34", "Seinen" to "31", "Shoujo" to "125", "Shoujo Ai" to "140", "Shounen" to "10", "Shounen Ai" to "717", "Slice of Life" to "7", "Smut" to "6670", "Sports" to "276", "Super Power" to "97", "Superhero" to "522", "Supernatural" to "39", "Thriller" to "119", "Tragedy" to "42", "Vampire" to "828", "Webtoons" to "215", "Wuxia" to "520", "Yaoi" to "7185", "Yuri" to "81")
        MangaSettingsManager.MANGA_SOURCE_SOULSCANS -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Magic" to "magic", "Manhua" to "manhua", "Manhwa" to "manhwa", "Martial Arts" to "martial-arts", "Mature" to "mature", "Mystery" to "mystery", "Psychological" to "psychological", "Reincarnation" to "reincarnation", "Romance" to "romance", "School Life" to "school-life", "Sci-Fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo", "Shounen" to "shounen", "Slice of Life" to "slice-of-life", "Supernatural" to "supernatural", "Thriller" to "thriller", "Tragedy" to "tragedy", "Webtoon" to "webtoon")
        MangaSettingsManager.MANGA_SOURCE_KUROMANGA -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Magic" to "magic", "Manhua" to "manhua", "Manhwa" to "manhwa", "Martial Arts" to "martial-arts", "Mature" to "mature", "Mystery" to "mystery", "Psychological" to "psychological", "Reincarnation" to "reincarnation", "Romance" to "romance", "School Life" to "school-life", "Sci-Fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo", "Shounen" to "shounen", "Slice of Life" to "slice-of-life", "Supernatural" to "supernatural", "Thriller" to "thriller", "Tragedy" to "tragedy", "Webtoon" to "webtoon")
        MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK -> listOf("Action" to "action", "Adaptation" to "adaptation", "Adult" to "adult", "Adventure" to "adventure", "Comedy" to "comedy", "Cooking" to "cooking", "Crime" to "crime", "Cultivation" to "cultivation", "Demons" to "demons", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Full Color" to "full-color", "Game" to "game", "Gender Bender" to "gender-bender", "Gore" to "gore", "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Josei" to "josei", "Magic" to "magic", "Manhua" to "manhua", "Manhwa" to "manhwa", "Martial Arts" to "martial-arts", "Mature" to "mature", "Medical" to "medical", "Military" to "military", "Monsters" to "monsters", "Mystery" to "mystery", "Psychological" to "psychological", "Regression" to "regression", "Reincarnation" to "reincarnation", "Romance" to "romance", "School" to "school", "School Life" to "school-life", "Sci-Fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo", "Shounen" to "shounen", "Slice of Life" to "slice-of-life", "Sports" to "sports", "Supernatural" to "supernatural", "Survival" to "survival", "Thriller" to "thriller", "Time Travel" to "time-travel", "Tragedy" to "tragedy", "Villainess" to "villainess", "Wuxia" to "wuxia")
        MangaSettingsManager.MANGA_SOURCE_NGOMIK -> listOf("Action" to "action", "Adult" to "adult", "Adventure" to "adventure", "Bloody" to "bloody", "Comedy" to "comedy", "Cooking" to "cooking", "Demons" to "demons", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Game" to "game", "Gender Bender" to "gender-bender", "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Josei" to "josei", "Lolicon" to "lolicon", "Mafia" to "mafia", "Magic" to "magic", "Martial Arts" to "martial-arts", "Mature" to "mature", "Mecha" to "mecha", "Medical" to "medical", "Mystery" to "mystery", "Overpowered" to "overpowered", "Psychological" to "psychological", "Reincarnation" to "reincarnation", "Returner" to "returner", "Revenge" to "revenge", "Romance" to "romance", "School" to "school", "School Life" to "school-life", "Sci-fi" to "sci-fi", "Seinen" to "seinen", "Shotacon" to "shotacon", "Shoujo" to "shoujo", "Shoujo Ai" to "shoujo-ai", "Shounen" to "shounen", "Shounen Ai" to "shounen-ai", "Slice of Life" to "slice-of-life", "Smut" to "smut", "Sports" to "sports", "Superhero" to "superhero", "Supernatural" to "supernatural", "Thriller" to "thriller", "Tragedy" to "tragedy", "Yuri" to "yuri")
        MangaSettingsManager.MANGA_SOURCE_APKOMIK -> listOf("4-Koma" to "2268", "Action" to "14", "Adult" to "11229", "Adventure" to "15", "apocalypse" to "8996", "Comedy" to "2", "Cooking" to "772", "Crime" to "3741", "Demon" to "4641", "Demons" to "45", "Doujinshi" to "3722", "Drama" to "11", "dungeons" to "8803", "Ecchi" to "28", "Fantasy" to "16", "Game" to "84", "Gender bender" to "74", "Gore" to "3354", "Harem" to "29", "Historical" to "24", "Horror" to "136", "Isekai" to "17", "Josei" to "1155", "Lolicon" to "3991", "Magic" to "41", "Martial Arts" to "18", "Mature" to "43", "Mecha" to "111", "Medical" to "3386", "Military" to "1476", "monsters" to "9299", "Music" to "146", "Mystery" to "99", "One-Shot" to "50", "Parody" to "372", "Police" to "1048", "Post apocalyptic" to "6646", "Psychological" to "90", "Regression" to "8798", "Reincarnation" to "4017", "Romance" to "3", "School" to "12", "School Life" to "566", "Sci-Fi" to "85", "Seinen" to "36", "sepernatural" to "4626", "Shotacon" to "10359", "Shoujo" to "53", "Shoujo Ai" to "31", "Shounen" to "5", "Shounen Ai" to "2479", "Si-fi" to "3717", "Slice of Life" to "13", "Smut" to "3760", "Sports" to "265", "Super Power" to "103", "Supernatural" to "19", "Superpowers" to "6781", "Thriller" to "143", "Tragedy" to "20", "Vampire" to "634", "Yuri" to "75", "Zombies" to "3894")
        MangaSettingsManager.MANGA_SOURCE_COMICASO -> if (isComicasoAdultSort(sort)) listOf("Action" to "action", "Adaptation" to "adaptation", "Adult" to "adult", "Comedy" to "comedy", "Crime" to "crime", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Full Color" to "full-color", "Futanari" to "futanari", "Harem" to "harem", "Hentai" to "hentai", "Historical" to "historical", "Horror" to "horror", "Josei(W)" to "josei-w", "Lolicon" to "lolicon", "Mature" to "mature", "Mystery" to "mystery", "Omegaverse" to "omegaverse", "Psychological" to "psychological", "Romance" to "romance", "School Life" to "school-life", "Sci-Fi" to "sci-fi", "Seinen(M)" to "seinen-m", "Shounen ai" to "shounen-ai", "Shounen(B)" to "shounen-b", "Showbiz" to "showbiz", "Slice of Life" to "slice-of-life", "Smut" to "smut", "Sports" to "sports", "Supernatural" to "supernatural", "Yakuzas" to "yakuzas", "Yaoi(BL)" to "yaoi-bl", "Yuri(GL)" to "yuri-gl") else listOf("Action" to "action", "Adaptation" to "adaptation", "Adult" to "adult", "Adventure" to "adventure", "College Life" to "college-life", "Comedy" to "comedy", "Cooking" to "cooking", "Crime" to "crime", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Full Color" to "full-color", "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Josei(W)" to "josei-w", "Magic" to "magic", "Martial Arts" to "martial-arts", "Mature" to "mature", "Mystery" to "mystery", "Office Workers" to "office-workers", "Omegaverse" to "omegaverse", "Psychological" to "psychological", "Reincarnation" to "reincarnation", "Romance" to "romance", "School Life" to "school-life", "Seinen(M)" to "seinen-m", "Shoujo(G)" to "shoujo-g", "Shounen ai" to "shounen-ai", "Shounen(B)" to "shounen-b", "Showbiz" to "showbiz", "Slice of Life" to "slice-of-life", "Smut" to "smut", "Sports" to "sports", "Supernatural" to "supernatural", "Tragedy" to "tragedy", "Yakuzas" to "yakuzas", "Yaoi(BL)" to "yaoi-bl")
        else -> listOf("Action" to "Action", "Adventure" to "Adventure", "Comedy" to "Comedy", "Drama" to "Drama", "Fantasy" to "Fantasy", "Harem" to "Harem", "Historical" to "Historical", "Horror" to "Horror", "Isekai" to "Isekai", "Martial Arts" to "Martial Arts", "Romance" to "Romance", "School Life" to "School Life", "Seinen" to "Seinen", "Shounen" to "Shounen", "Slice of Life" to "Slice of Life", "Supernatural" to "Supernatural")
    }
    return values.map { KomikcastClient.GenreItem(it.first, it.second) }
}

private fun isComicasoAdultSort(sort: String): Boolean {
    return sort == "adult_update" || sort == "adult_new" || sort == "adult_completed" || sort == "adult_manga" || sort == "adult_manhwa" || sort == "adult_manhua"
}

