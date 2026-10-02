package miku.moe.app

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import miku.moe.app.api.AnimeRepository
import miku.moe.app.api.ApiAnimePost
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AnimeHomeFragment : Fragment() {
    private var sourceRecyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var defaultAdapter: AnimeHomeSectionAdapter? = null
    private var styleV1Adapter: AnimeHomeV1Adapter? = null
    private val sections = ArrayList<SourceSection>()
    private val styleV1Popular = ArrayList<AnimePost>()
    private val styleV1Latest = ArrayList<AnimePost>()
    private var generation = 0
    private var styleV1Active = false
    private var styleV1Source = AnimeSettingsManager.SOURCE_DEFAULT
    private var styleV1InitialLoading = false
    private var styleV1PopularLoading = false
    private var styleV1LatestLoading = false
    private var styleV1PopularPage = 0
    private var styleV1LatestPage = 0
    private var styleV1PopularHasMore = true
    private var styleV1LatestHasMore = true
    private var styleV1Error = ""
    private val repository = AnimeRepository()

    class SourceSection(val sourceId: String) {
        val sourceLabel: String = AnimeSettingsManager.labelForSourceId(sourceId)
        val items = ArrayList<AnimePost>()
        var loading = true
        var finished = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_anime_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        swipeRefreshLayout?.setOnRefreshListener { refreshHome(true) }
        configureHome(false)
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || view == null) return
        val desiredStyle = AnimeSettingsManager.isHomeStyleV1(requireContext())
        val desiredSource = AnimeSettingsManager.getHomeV1Source(requireContext())
        if (desiredStyle != styleV1Active || desiredStyle && desiredSource != styleV1Source) configureHome(false)
    }

    override fun onDestroyView() {
        generation++
        sourceRecyclerView?.clearOnScrollListeners()
        sourceRecyclerView?.adapter = null
        sourceRecyclerView = null
        progressBar = null
        swipeRefreshLayout = null
        defaultAdapter = null
        styleV1Adapter = null
        super.onDestroyView()
    }

    fun refreshHome() {
        refreshHome(false)
    }

    fun refreshHome(forceNetwork: Boolean) {
        if (!isAdded || view == null) return
        val desiredStyle = AnimeSettingsManager.isHomeStyleV1(requireContext())
        val desiredSource = AnimeSettingsManager.getHomeV1Source(requireContext())
        if (desiredStyle != styleV1Active || desiredStyle && desiredSource != styleV1Source) {
            configureHome(forceNetwork)
        } else if (styleV1Active) {
            loadStyleV1Initial(forceNetwork)
        } else {
            loadDefaultHome()
        }
    }

    private fun configureHome(forceNetwork: Boolean) {
        if (!isAdded || sourceRecyclerView == null) return
        generation++
        sourceRecyclerView?.clearOnScrollListeners()
        sourceRecyclerView?.adapter = null
        styleV1Active = AnimeSettingsManager.isHomeStyleV1(requireContext())
        styleV1Source = AnimeSettingsManager.getHomeV1Source(requireContext())
        if (styleV1Active) configureStyleV1(forceNetwork) else configureDefault()
    }

    private fun configureDefault() {
        val recycler = sourceRecyclerView ?: return
        recycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        recycler.setPadding(0, dp(12), 0, dp(96))
        defaultAdapter = AnimeHomeSectionAdapter(requireContext(), sections, object : AnimeHomeSectionAdapter.ActionListener {
            override fun onViewAll(section: SourceSection) {
                openViewAll(section)
            }

            override fun onAnimeClick(section: SourceSection, post: AnimePost) {
                openAnime(post)
            }
        })
        styleV1Adapter = null
        recycler.adapter = defaultAdapter
        loadDefaultHome()
    }

    private fun configureStyleV1(forceNetwork: Boolean) {
        val recycler = sourceRecyclerView ?: return
        styleV1Popular.clear()
        styleV1Latest.clear()
        styleV1Error = ""
        val grid = GridLayoutManager(requireContext(), 3)
        styleV1Adapter = AnimeHomeV1Adapter(
            requireContext(),
            AnimeSettingsManager.getHomeV1SourceLabel(requireContext()),
            styleV1Popular,
            styleV1Latest,
            object : AnimeHomeV1Adapter.Listener {
                override fun onAnimeClick(post: AnimePost) {
                    openAnime(post)
                }

                override fun onExplore(kind: String) {
                    openStyleV1Result(kind)
                }

                override fun onBrowseSource() {
                    openStyleV1Catalogue()
                }

                override fun onChangeSource() {
                    showStyleV1SourceDialog()
                }

                override fun onPopularNearEnd() {
                    loadMorePopular()
                }
            }
        )
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return styleV1Adapter?.getSpanSize(position) ?: 3
            }
        }
        recycler.layoutManager = grid
        recycler.setPadding(0, 0, 0, dp(96))
        recycler.adapter = styleV1Adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val last = grid.findLastVisibleItemPosition()
                if (last >= (styleV1Adapter?.itemCount ?: 0) - 5) loadMoreLatest()
            }
        })
        progressBar?.visibility = View.GONE
        defaultAdapter = null
        updateStyleV1State()
        loadStyleV1Initial(forceNetwork)
    }

    private fun loadDefaultHome() {
        if (!isAdded || styleV1Active) return
        val run = ++generation
        sections.clear()
        AnimeSettingsManager.getEnabledAnimeSources(requireContext()).forEach { sections.add(SourceSection(it)) }
        defaultAdapter?.notifyDataSetChanged()
        progressBar?.visibility = if (sections.isEmpty()) View.GONE else View.VISIBLE
        if (sections.isEmpty()) {
            swipeRefreshLayout?.isRefreshing = false
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val tasks = sections.mapIndexed { index, section ->
                async {
                    val data = try {
                        loadDefaultSection(section.sourceId)
                    } catch (e: Exception) {
                        Log.e("AnimeHome", "Load source error ${section.sourceId}", e)
                        emptyList()
                    }
                    index to data
                }
            }
            tasks.forEach { task ->
                val result = task.await()
                if (!isAdded || run != generation || styleV1Active) return@forEach
                val section = sections.getOrNull(result.first) ?: return@forEach
                section.items.clear()
                result.second.forEach { post ->
                    post.sourceId = section.sourceId
                    section.items.add(post)
                }
                section.loading = false
                section.finished = true
                defaultAdapter?.notifyItemChanged(result.first)
            }
            if (isAdded && run == generation && !styleV1Active) {
                progressBar?.visibility = View.GONE
                swipeRefreshLayout?.isRefreshing = false
            }
        }
    }

    private suspend fun loadDefaultSection(sourceId: String): List<AnimePost> {
        return loadHomePage(sourceId, "latest", 1).items.take(DEFAULT_HOME_LIMIT)
    }

    private fun loadStyleV1Initial(forceNetwork: Boolean) {
        if (!isAdded || !styleV1Active) return
        val run = ++generation
        styleV1Popular.clear()
        styleV1Latest.clear()
        styleV1PopularPage = 0
        styleV1LatestPage = 0
        styleV1PopularHasMore = true
        styleV1LatestHasMore = true
        styleV1Error = ""
        val cached = synchronized(styleV1Cache) { styleV1Cache[styleV1Source] }
        if (!forceNetwork && cached != null && System.currentTimeMillis() - cached.savedAt < CACHE_DURATION_MS) {
            styleV1Latest.addAll(cached.latest)
            styleV1Popular.addAll(cached.popular)
            styleV1LatestPage = 1
            styleV1PopularPage = 1
            styleV1InitialLoading = false
            styleV1LatestLoading = false
            styleV1PopularLoading = false
            swipeRefreshLayout?.isRefreshing = false
            updateStyleV1State()
            return
        }
        styleV1InitialLoading = true
        styleV1LatestLoading = true
        styleV1PopularLoading = true
        updateStyleV1State()
        viewLifecycleOwner.lifecycleScope.launch {
            var latestError: String? = null
            var popularError: String? = null
            val latestTask = async {
                try {
                    loadHomePage(styleV1Source, "latest", 1)
                } catch (e: Exception) {
                    Log.e("AnimeHomeV1", "Latest load error $styleV1Source", e)
                    latestError = e.message ?: e.javaClass.simpleName
                    HomePage(emptyList(), false)
                }
            }
            val popularTask = async {
                try {
                    loadHomePage(styleV1Source, "popular", 1)
                } catch (e: Exception) {
                    Log.e("AnimeHomeV1", "Popular load error $styleV1Source", e)
                    popularError = e.message ?: e.javaClass.simpleName
                    HomePage(emptyList(), false)
                }
            }
            val latestResult = latestTask.await()
            val popularResult = popularTask.await()
            if (!isAdded || run != generation || !styleV1Active) return@launch
            appendUnique(styleV1Latest, latestResult.items)
            appendUnique(styleV1Popular, popularResult.items)
            if (styleV1Popular.isEmpty()) appendUnique(styleV1Popular, styleV1Latest.take(HERO_FALLBACK_LIMIT))
            styleV1LatestPage = 1
            styleV1PopularPage = 1
            styleV1LatestHasMore = latestResult.hasMore
            styleV1PopularHasMore = popularResult.hasMore
            styleV1InitialLoading = false
            styleV1LatestLoading = false
            styleV1PopularLoading = false
            styleV1Error = if (styleV1Latest.isEmpty() && styleV1Popular.isEmpty()) {
                // Tampilkan alasan asli (exception) kalau ada, bukan cuma pesan generik.
                // Ini krusial untuk diagnosa: sebelumnya pesan ini selalu sama walau
                // penyebabnya bisa beda-beda (timeout, JSON berubah, dst).
                val reason = listOfNotNull(latestError, popularError).distinct().joinToString("; ")
                if (reason.isNotBlank()) "Data anime belum dapat dimuat ($reason)" else "Data anime belum dapat dimuat"
            } else ""
            // Hanya simpan ke cache kalau TIDAK ada exception di kedua request.
            // Bug lama: hasil gagal (kosong) ikut di-cache selama 5 menit, jadi walau
            // sinyal sudah pulih, layar tetap kosong sampai cache itu kedaluwarsa
            // atau user pull-to-refresh (forceNetwork).
            if (latestError == null && popularError == null) {
                synchronized(styleV1Cache) {
                    styleV1Cache[styleV1Source] = CachedHome(ArrayList(styleV1Latest), ArrayList(styleV1Popular), System.currentTimeMillis())
                }
            }
            swipeRefreshLayout?.isRefreshing = false
            updateStyleV1State()
        }
    }

    private fun loadMoreLatest() {
        if (!isAdded || !styleV1Active || styleV1InitialLoading || styleV1LatestLoading || !styleV1LatestHasMore) return
        val run = generation
        val targetPage = styleV1LatestPage + 1
        styleV1LatestLoading = true
        updateStyleV1State()
        viewLifecycleOwner.lifecycleScope.launch {
            val page = try {
                loadHomePage(styleV1Source, "latest", targetPage)
            } catch (e: Exception) {
                Log.e("AnimeHomeV1", "Latest pagination error", e)
                HomePage(emptyList(), false)
            }
            if (!isAdded || run != generation || !styleV1Active) return@launch
            val added = appendUnique(styleV1Latest, page.items)
            styleV1LatestPage = targetPage
            styleV1LatestHasMore = page.hasMore && added > 0
            styleV1LatestLoading = false
            updateStyleV1State()
        }
    }

    private fun loadMorePopular() {
        if (!isAdded || !styleV1Active || styleV1InitialLoading || styleV1PopularLoading || !styleV1PopularHasMore) return
        val run = generation
        val targetPage = styleV1PopularPage + 1
        styleV1PopularLoading = true
        updateStyleV1State()
        viewLifecycleOwner.lifecycleScope.launch {
            val page = try {
                loadHomePage(styleV1Source, "popular", targetPage)
            } catch (e: Exception) {
                Log.e("AnimeHomeV1", "Popular pagination error", e)
                HomePage(emptyList(), false)
            }
            if (!isAdded || run != generation || !styleV1Active) return@launch
            val added = appendUnique(styleV1Popular, page.items)
            styleV1PopularPage = targetPage
            styleV1PopularHasMore = page.hasMore && added > 0
            styleV1PopularLoading = false
            updateStyleV1State()
        }
    }

    private fun updateStyleV1State() {
        if (!styleV1Active) return
        styleV1Adapter?.updateState(
            latestHistory(styleV1Source),
            styleV1InitialLoading,
            styleV1PopularLoading,
            styleV1LatestLoading,
            styleV1Error
        )
    }

    private fun latestHistory(sourceId: String): HistoryItem? {
        if (!isAdded) return null
        val data = if (sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) AnimekuHistoryManager.getHistory(requireContext()) else HistoryManager.getHistory(requireContext())
        return data.firstOrNull {
            val historySource = if (AnimeSettingsManager.isValidSource(it.sourceId)) it.sourceId else AnimeSettingsManager.SOURCE_DEFAULT
            historySource == sourceId
        }
    }

    private fun appendUnique(target: ArrayList<AnimePost>, items: List<AnimePost>): Int {
        val keys = target.mapTo(HashSet()) { itemKey(it) }
        var added = 0
        for (post in items) {
            post.sourceId = styleV1Source
            val key = itemKey(post)
            if (key.isEmpty() || !keys.add(key)) continue
            target.add(post)
            added++
        }
        return added
    }

    private suspend fun loadHomePage(sourceId: String, kind: String, page: Int): HomePage {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> loadAnimekuPage(kind, page)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loadAnimeLoverzPage(kind, page)
            AnimeSettingsManager.SOURCE_DRAMORA -> loadDramoraPage(kind, page)
            AnimeSettingsManager.SOURCE_ANICHIN -> loadAnichinPage(kind, page)
            else -> loadDefaultPage(kind, page)
        }
    }

    private suspend fun loadDefaultPage(kind: String, page: Int): HomePage {
        val response = if (kind == "popular") repository.getAllAnime(page, V1_PAGE_SIZE) else repository.getHomePosts(page, V1_PAGE_SIZE, deviceId())
        // Konsisten dengan loader Animeku: kalau server membalas status != "ok" (mis. rate
        // limit, device_id ditolak, dsb), lempar exception yang jelas alih-alih diam-diam
        // melanjutkan dengan list kosong. Ini membuat pesan error di layar (lihat
        // loadStyleV1Initial) menunjukkan alasan sebenarnya, bukan sekadar "gagal dimuat".
        if (!response.status.isNullOrBlank() && !response.status.equals("ok", ignoreCase = true)) {
            throw IllegalStateException("status=${response.status}")
        }
        val raw = if (kind == "popular") response.categories.orEmpty() else response.posts.orEmpty()
        val items = defaultPosts(raw)
        val total = response.countTotal ?: -1
        val hasMore = if (total > 0) page * V1_PAGE_SIZE < total else raw.size >= V1_PAGE_SIZE
        return HomePage(items, hasMore)
    }

    private fun defaultPosts(raw: List<ApiAnimePost>): List<AnimePost> {
        // Setiap item diparse dalam try/catch sendiri-sendiri. Sebelumnya satu item
        // yang aneh/null bisa melempar exception dan membatalkan SELURUH mapNotNull,
        // sehingga satu post rusak bisa membuat seluruh halaman terlihat "gagal dimuat"
        // padahal 19 item lain di response sebenarnya valid.
        val result = ArrayList<AnimePost>(raw.size)
        for (item in raw) {
            try {
                val categoryId = item.categoryId ?: item.cid ?: -1
                val channelId = item.channelId ?: -1
                val title = cleanAnimeTitle(item.categoryName)
                if (categoryId <= 0 || title.isBlank() || channelId == BLOCKED_CHANNEL_ID) continue
                val post = AnimePost(firstUseful(item.imgUrl, item.categoryImage), title, categoryId, channelId).apply {
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
                result.add(post)
            } catch (e: Exception) {
                Log.e("AnimeHomeV1", "Skip 1 item rusak dari AnimeXNonton: ${item.categoryName}", e)
            }
        }
        return result
    }

    private suspend fun loadAnimekuPage(kind: String, page: Int): HomePage = withContext(Dispatchers.IO) {
        val endpoint = if (kind == "popular") "get_category_popular" else "get_videos"
        val url = "$ANIMEKU_API_BASE/$endpoint?page=$page&count=$V1_PAGE_SIZE&api_key=$ANIMEKU_API_KEY"
        val json = JSONObject(getText(url, animekuHeaders()))
        if (!json.optString("status").equals("ok", true)) return@withContext HomePage(emptyList(), false)
        val array = if (kind == "popular") json.optJSONArray("new_anime") else json.optJSONArray("latest_anime")
        val items = if (kind == "popular") parseAnimekuCategories(array) else parseAnimekuLatest(array)
        val total = json.optInt("count_total", -1)
        val rawCount = array?.length() ?: 0
        HomePage(items, if (total > 0) page * V1_PAGE_SIZE < total else rawCount >= V1_PAGE_SIZE)
    }

    private fun parseAnimekuLatest(array: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val categoryId = item.optInt("cat_id", -1)
            val videoId = item.optInt("vid", -1)
            val title = cleanAnimeTitle(item.optString("category_name", ""))
            if (categoryId <= 0 || videoId <= 0 || title.isEmpty()) continue
            val videoTitle = item.optString("video_title", "")
            val post = AnimePost(imageUrl(firstUseful(item.optString("category_image", ""), item.optString("video_thumbnail", ""))), title, categoryId, videoId)
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
            post.channelName = episodeLabel(videoTitle)
            post.episodeCount = episodeLabel(videoTitle)
            post.genre = item.optString("genre", "")
            post.rating = item.optString("rating", "")
            post.statusVideo = normalizeStatus(item.optString("status_video", ""))
            post.ongoing = isOngoing(post.statusVideo)
            result.add(post)
        }
        return result
    }

    private fun parseAnimekuCategories(array: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val categoryId = item.optInt("cid", item.optInt("cat_id", -1))
            val title = cleanAnimeTitle(item.optString("category_name", ""))
            if (categoryId <= 0 || title.isEmpty()) continue
            val post = AnimePost(imageUrl(item.optString("category_image", "")), title, categoryId, item.optInt("vid", -1))
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
            post.genre = item.optString("genre", "")
            post.rating = item.optString("rating", "")
            post.statusVideo = normalizeStatus(item.optString("status_video", ""))
            post.episodeCount = episodeLabel(item.optString("video_count", ""))
            post.ongoing = isOngoing(post.statusVideo)
            result.add(post)
        }
        return result
    }

    private suspend fun loadAnimeLoverzPage(kind: String, page: Int): HomePage = withContext(Dispatchers.IO) {
        val targetPage = if (kind == "latest" && page > 1) page - 1 else page
        val url = if (kind == "popular" || page > 1) "$ANIMELOVERZ_API_BASE/baruupload.php?page=$targetPage" else "$ANIMELOVERZ_API_BASE/home/ongoing.php?page=$page&type=all"
        val array = JSONArray(getText(url, loverzHeaders()))
        HomePage(parseAnimeLoverzList(array), array.length() > 0)
    }

    private fun parseAnimeLoverzList(array: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = firstUseful(item.optString("judul", ""), firstUseful(item.optString("anime_name", ""), firstUseful(item.optString("title", ""), item.optString("name", ""))))
            val slug = firstUseful(item.optString("url", ""), firstUseful(item.optString("link", ""), firstUseful(item.optString("slug", ""), item.optString("permalink", "")))).trim().trim('/')
            if (title.isBlank() || slug.isBlank()) continue
            val id = item.optString("id", "").toIntOrNull() ?: positiveId(slug)
            val post = AnimePost(firstUseful(item.optString("cover", ""), firstUseful(item.optString("thumb", ""), firstUseful(item.optString("thumbnail", ""), item.optString("image", "")))), cleanAnimeTitle(title), id, -1)
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
            post.slug = slug
            post.channelName = firstUseful(item.optString("lastch", ""), firstUseful(item.optString("episode", ""), item.optString("ch", "")))
            post.episodeCount = firstUseful(item.optString("total_episode", ""), item.optString("episode_count", ""))
            post.genre = firstUseful(joinArray(item.optJSONArray("genre")), joinArray(item.optJSONArray("genres")))
            post.rating = firstUseful(item.optString("score", ""), firstUseful(item.optString("rating", ""), item.optString("rate", "")))
            post.statusVideo = firstUseful(item.optString("status", ""), firstUseful(item.optString("release_status", ""), item.optString("anime_status", "")))
            post.description = firstUseful(item.optString("sinopsis", ""), firstUseful(item.optString("synopsis", ""), firstUseful(item.optString("description", ""), item.optString("desc", ""))))
            post.ongoing = !post.statusVideo.lowercase(Locale.ROOT).contains("complete")
            result.add(post)
        }
        return result
    }

    private suspend fun loadDramoraPage(kind: String, page: Int): HomePage = withContext(Dispatchers.IO) {
        val listing = if (kind == "popular") "china" else "korea"
        val result = Dramora.listing(listing, page)
        HomePage(result.items, result.hasMore)
    }

    private suspend fun loadAnichinPage(kind: String, page: Int): HomePage = withContext(Dispatchers.IO) {
        val listing = if (kind == "popular") "popular" else "update"
        val result = Anichin.listing(listing, page)
        HomePage(result.items, result.hasMore)
    }

    private fun showStyleV1SourceDialog() {
        if (!isAdded) return
        val sourceIds = AnimeSettingsManager.allSourceIds()
        val labels = sourceIds.map { AnimeSettingsManager.labelForSourceId(it) }.toTypedArray()
        val current = AnimeSettingsManager.getHomeV1Source(requireContext())
        val checked = sourceIds.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Source Home Anime v1")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which !in sourceIds.indices) return@setSingleChoiceItems
                AnimeSettingsManager.setHomeV1Source(requireContext(), sourceIds[which])
                styleV1Source = sourceIds[which]
                Toast.makeText(requireContext(), "Source Home Anime v1: ${labels[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                configureHome(true)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openViewAll(section: SourceSection) {
        if (!isAdded) return
        (requireActivity() as? MainActivity)?.openAnimeBrowseSource(section.sourceId, section.sourceLabel, "")
    }

    private fun openStyleV1Catalogue() {
        if (!isAdded) return
        (requireActivity() as? MainActivity)?.openAnimeBrowseSource(styleV1Source, AnimeSettingsManager.labelForSourceId(styleV1Source), "")
    }

    private fun openStyleV1Result(kind: String) {
        if (!isAdded) return
        (requireActivity() as? MainActivity)?.openAnimeStyleV1Result(styleV1Source, AnimeSettingsManager.labelForSourceId(styleV1Source), kind)
    }

    private fun openAnime(post: AnimePost) {
        if (!isAdded) return
        (requireActivity() as? MainActivity)?.openAnimeDetailV2(post)
    }

    private fun getText(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun animekuHeaders(): Map<String, String> {
        return mapOf(
            "Cache-Control" to "max-age=0",
            "Data-Agent" to "Your Videos Channel",
            "User-Agent" to "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)",
            "Accept" to "application/vnd.yourapi.v1.full+json"
        )
    }

    private fun loverzHeaders(): Map<String, String> {
        return mapOf("user-agent" to "Dart/3.9 (dart:io)", "accept" to "application/json")
    }

    private fun deviceId(): String {
        val safeContext = context ?: return ""
        return Settings.Secure.getString(safeContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    private fun itemKey(post: AnimePost): String {
        val source = post.sourceId?.trim().orEmpty()
        val slug = post.slug?.trim().orEmpty().trim('/')
        if (slug.isNotEmpty()) return "$source:slug:$slug"
        if (post.categoryId > 0) return "$source:category:${post.categoryId}:${post.channelId}"
        return "$source:title:${post.categoryName?.trim()?.lowercase(Locale.ROOT).orEmpty()}"
    }

    private fun cleanAnimeTitle(value: String?): String {
        if (value == null) return ""
        var result = value.trim().replace(Regex("\\s+"), " ")
        result = result.replace(Regex("(?i)\\bsub\\s*indo\\b"), "")
        result = result.replace(Regex("(?i)\\bsubtitle\\s*indonesia\\b"), "")
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun episodeLabel(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", true)) return ""
        val matcher = Regex("(?i)(episode|eps|ep)\\s*([0-9]+(?:\\.[0-9]+)?)").find(raw)
        if (matcher != null) return "Episode ${matcher.groupValues[2]}"
        val number = Regex("^[0-9]+(?:\\.[0-9]+)?$").find(raw)
        if (number != null) return "Episode $raw"
        return raw
    }

    private fun normalizeStatus(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", true)) return ""
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.contains("complete") || lower.contains("finished")) return "Completed"
        if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing"
        return raw
    }

    private fun isOngoing(value: String?): Boolean {
        val raw = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return raw.isNotEmpty() && !raw.contains("complete") && !raw.contains("finished") && raw != "selesai"
    }

    private fun imageUrl(image: String?): String {
        val value = image?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", true)) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return ANIMEKU_IMAGE_BASE + value
    }

    private fun positiveId(value: String?): Int {
        val hash = value?.hashCode() ?: 1
        return if (hash == Int.MIN_VALUE) 1 else abs(hash)
    }

    private fun firstUseful(primary: String?, fallback: String?): String {
        val first = primary?.trim().orEmpty()
        if (first.isNotEmpty() && !first.equals("null", true)) return first
        return fallback?.trim().orEmpty()
    }

    private fun joinArray(array: JSONArray?): String {
        if (array == null) return ""
        val values = ArrayList<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) values.add(value)
        }
        return values.joinToString(", ")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private data class HomePage(val items: List<AnimePost>, val hasMore: Boolean)
    private data class CachedHome(val latest: ArrayList<AnimePost>, val popular: ArrayList<AnimePost>, val savedAt: Long)

    companion object {
        private const val DEFAULT_HOME_LIMIT = 10
        private const val V1_PAGE_SIZE = 20
        private const val HERO_FALLBACK_LIMIT = 10
        private const val BLOCKED_CHANNEL_ID = 45784
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
        private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api"
        private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
        private const val ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/"
        private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"
        private val styleV1Cache = HashMap<String, CachedHome>()
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }
    }
}
