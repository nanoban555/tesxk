package miku.moe.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class AnimeGlobalSearchFragment : Fragment() {
    private var sourceRecyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var searchEditText: EditText? = null
    private var adapter: AnimeHomeSectionAdapter? = null
    private val sections = ArrayList<AnimeHomeFragment.SourceSection>()
    private val repository = AnimeRepository()
    private var generation = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manga_global_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        searchEditText = view.findViewById(R.id.searchEditText)
        searchEditText?.hint = "Cari anime"
        sourceRecyclerView?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        adapter = AnimeHomeSectionAdapter(requireContext(), sections, object : AnimeHomeSectionAdapter.ActionListener {
            override fun onViewAll(section: AnimeHomeFragment.SourceSection) { openViewAll(section) }
            override fun onAnimeClick(section: AnimeHomeFragment.SourceSection, post: AnimePost) { openAnime(post) }
        })
        sourceRecyclerView?.adapter = adapter
        searchEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll()
                true
            } else false
        }
    }

    fun refreshSourceSettings() {
        val query = searchEditText?.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty()) searchAll()
    }

    private fun searchAll() {
        if (!isAdded) return
        val query = searchEditText?.text?.toString()?.trim().orEmpty()
        val run = ++generation
        sections.clear()
        adapter?.notifyDataSetChanged()
        if (query.isEmpty()) {
            progressBar?.visibility = View.GONE
            return
        }
        progressBar?.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = AnimeSettingsManager.getEnabledAnimeSources(requireContext())
            val tasks = sources.map { sourceId ->
                async {
                    val data = try {
                        searchSource(sourceId, query)
                    } catch (e: Exception) {
                        Log.e(TAG, "Search error $sourceId", e)
                        emptyList()
                    }
                    sourceId to data
                }
            }
            tasks.forEach { task ->
                val result = task.await()
                if (!isAdded || run != generation) return@forEach
                val section = buildSection(result.first, result.second)
                if (section.items.isNotEmpty()) {
                    val index = sections.size
                    sections.add(section)
                    adapter?.notifyItemInserted(index)
                }
            }
            if (isAdded && run == generation) {
                progressBar?.visibility = View.GONE
                if (sections.isEmpty()) Toast.makeText(requireContext(), "Anime tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun searchSource(sourceId: String, query: String): List<AnimePost> {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> searchAnimeku(query)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> searchAnimeLoverz(query)
            AnimeSettingsManager.SOURCE_DRAMORA -> searchDramora(query)
            AnimeSettingsManager.SOURCE_ANICHIN -> searchAnichin(query)
            else -> searchDefault(query)
        }
    }

    private suspend fun searchDefault(query: String): List<AnimePost> {
        val response = repository.searchAnime(query, 1, SEARCH_LIMIT)
        if (!response.status.equals("ok", true)) return emptyList()
        return response.categories.orEmpty().mapNotNull { item -> defaultPost(item, query) }
    }

    private fun defaultPost(item: ApiAnimePost, query: String): AnimePost? {
        val categoryId = item.cid ?: item.categoryId ?: -1
        val title = item.categoryName.orEmpty()
        if (categoryId <= 0 || title.isBlank()) return null
        if (!title.lowercase().contains(query.lowercase())) return null
        return AnimePost(item.imgUrl.orEmpty(), cleanTitle(title), categoryId, item.channelId ?: -1).apply {
            sourceId = AnimeSettingsManager.SOURCE_DEFAULT
            channelName = ""
            created = item.created.orEmpty()
            countView = item.countView ?: item.totalViews.orEmpty()
            rating = item.rating.orEmpty()
            scheduleDay = item.days ?: -1
            year = item.years?.toIntOrNull() ?: 0
        }
    }

    private suspend fun searchAnimeku(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMEKU_API_BASE/get_category_genre?search=${encode(query)}&sort=c.category_name%20ASC&api_key=$ANIMEKU_API_KEY"
        val body = httpClient.newCall(Request.Builder().url(url).headers(animekuHeaders()).build()).execute().use { it.body?.string().orEmpty() }
        val json = JSONObject(body)
        if (!json.optString("status").equals("ok", true)) return@withContext emptyList()
        val array = json.optJSONArray("categories") ?: JSONArray()
        val result = ArrayList<AnimePost>()
        val needle = query.lowercase()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = cleanTitle(item.optString("category_name", ""))
            if (title.isBlank() || !title.lowercase().contains(needle)) continue
            val categoryId = item.optInt("cid", item.optInt("cat_id", -1))
            if (categoryId <= 0) continue
            result.add(AnimePost(imageAnimeku(item.optString("category_image", "")), title, categoryId, -1).apply {
                sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
                genre = item.optString("genre", "")
                rating = item.optString("rating", "")
                statusVideo = item.optString("status_video", "")
            })
            if (result.size >= SEARCH_LIMIT) break
        }
        result
    }

    private suspend fun searchAnimeLoverz(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMELOVERZ_API_BASE/search.php?keyword=${encode(query)}&page=1&per_page=$SEARCH_LIMIT"
        val body = httpClient.newCall(Request.Builder().url(url).headers(animeLoverzHeaders()).build()).execute().use { it.body?.string().orEmpty() }
        parseAnimeLoverzSearch(JSONObject(body).optJSONArray("data"))
    }

    private fun parseAnimeLoverzSearch(data: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (data == null) return result
        for (d in 0 until data.length()) {
            val block = data.optJSONObject(d) ?: continue
            val array = block.optJSONArray("result") ?: continue
            for (i in 0 until array.length()) {
                val post = animeLoverzPost(array.optJSONObject(i) ?: continue)
                if (post != null) result.add(post)
                if (result.size >= SEARCH_LIMIT) return result
            }
        }
        return result
    }

    private fun animeLoverzPost(item: JSONObject): AnimePost? {
        val title = item.optString("judul", "").trim()
        val slug = item.optString("url", "").trim().trim('/')
        val id = item.optString("id", "").toIntOrNull() ?: slug.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
        if (title.isEmpty() || slug.isEmpty()) return null
        return AnimePost(item.optString("cover", ""), cleanTitle(title), id, -1).apply {
            sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
            this.slug = slug
            genre = joinArray(item.optJSONArray("genre"))
            rating = item.optString("score", "")
            statusVideo = item.optString("status", "")
            description = item.optString("sinopsis", "")
            episodeCount = item.optString("total_episode", "")
        }
    }

    private suspend fun searchDramora(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        Dramora.search(query, 1).items.take(SEARCH_LIMIT)
    }

    private suspend fun searchAnichin(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        Anichin.search(query, 1).items.take(SEARCH_LIMIT)
    }

    private fun buildSection(sourceId: String, data: List<AnimePost>): AnimeHomeFragment.SourceSection {
        val section = AnimeHomeFragment.SourceSection(sourceId)
        section.loading = false
        section.finished = true
        val seen = HashSet<String>()
        for (post in data) {
            post.sourceId = sourceId
            val key = if (post.slug.isNullOrBlank()) "${post.categoryId}:${post.categoryName}" else post.slug
            if (key.isBlank() || !seen.add(key)) continue
            section.items.add(post)
            if (section.items.size >= SEARCH_LIMIT) break
        }
        return section
    }

    private fun openViewAll(section: AnimeHomeFragment.SourceSection) {
        val query = searchEditText?.text?.toString()?.trim().orEmpty()
        if (!isAdded || query.isEmpty()) return
        (requireActivity() as? MainActivity)?.openAnimeBrowseSource(section.sourceId, section.sourceLabel, query)
    }

    private fun openAnime(post: AnimePost) {
        val activity = requireActivity() as? MainActivity ?: return
        when (post.sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> activity.openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> activity.openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description)
            AnimeSettingsManager.SOURCE_DRAMORA -> activity.openAnimeDetailV2(post)
            AnimeSettingsManager.SOURCE_ANICHIN -> activity.openAnimeDetailV2(post)
            else -> activity.openDetail(post.categoryId, post.channelId)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun cleanTitle(value: String?): String {
        var text = value?.trim().orEmpty().replace(Regex("\\s+"), " ")
        text = text.replace(Regex("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$"), "").trim()
        text = text.replace(Regex("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$"), "").trim()
        return text
    }

    private fun imageAnimeku(value: String?): String {
        val image = value?.trim().orEmpty()
        if (image.isEmpty() || image.equals("null", true)) return ""
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        return ANIMEKU_IMAGE_BASE + image
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

    private fun animekuHeaders() = okhttp3.Headers.headersOf(
        "Cache-Control", "max-age=0",
        "Data-Agent", "Your Videos Channel",
        "User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)",
        "Accept", "application/vnd.yourapi.v1.full+json"
    )

    private fun animeLoverzHeaders() = okhttp3.Headers.headersOf(
        "user-agent", "Dart/3.9 (dart:io)",
        "accept", "application/json"
    )

    override fun onDestroyView() {
        generation++
        sourceRecyclerView?.adapter = null
        sourceRecyclerView = null
        progressBar = null
        searchEditText = null
        adapter = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "AnimeGlobalSearch"
        private const val SEARCH_LIMIT = 10
        private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api/"
        private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
        private const val ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/"
        private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"
        private val httpClient: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    }
}
