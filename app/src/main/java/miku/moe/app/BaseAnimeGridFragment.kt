package miku.moe.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import miku.moe.app.api.AnimeRepository
import miku.moe.app.api.ApiAnimePost
import miku.moe.app.api.ApiGenre

abstract class BaseAnimeGridFragment : Fragment() {
    protected lateinit var gridView: GridView
    protected lateinit var progressBar: ProgressBar
    protected lateinit var titleTextView: TextView
    protected lateinit var searchInputLayout: TextInputLayout
    protected lateinit var searchEditText: TextInputEditText
    protected var genreScrollView: HorizontalScrollView? = null
    protected var genreChipGroup: ChipGroup? = null
    protected var searchHelperTextView: TextView? = null
    protected var homeTabLayout: TabLayout? = null
    protected var searchHeaderCard: View? = null
    protected var genreFilterButton: ImageView? = null

    protected val animePosts = ArrayList<AnimePost>()
    private val loadedKeys = HashSet<String>()
    protected lateinit var adapter: AnimeGridAdapter

    protected var currentPage = 0
    protected var isLoading = false
    protected var hasMoreData = true
    private var apiRequestJob: Job? = null
    private var apiRequestGeneration = 0

    private val repository = AnimeRepository()
    private var savedFirstVisiblePosition = 0
    private var savedTopOffset = 0
    private var lastQuery = ""
    private var selectedGenre = ""
    private var homeTabMode = 0
    private val genreMenuItems = ArrayList<ApiGenre>()
    private var genreMenuLoading = false

    protected abstract fun isSearchPage(): Boolean
    protected abstract fun screenTitle(): String
    protected open fun initialQuery(): String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(if (activity is AnimexAll) R.layout.fragment_ikiru_manga_grid else R.layout.fragment_anime_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gridView = view.findViewById(R.id.gridView)
        ViewCompat.setNestedScrollingEnabled(gridView, true)
        progressBar = view.findViewById(R.id.progressBar)
        titleTextView = view.findViewById(R.id.titleTextView)
        if (requireActivity() is AnimexAll) {
            titleTextView.visibility = View.GONE
            view.findViewById<TextView>(R.id.headerSubtitleTextView)?.visibility = View.GONE
        }
        searchInputLayout = view.findViewById(R.id.searchInputLayout)
        searchEditText = view.findViewById(R.id.searchEditText)
        genreScrollView = view.findViewById(R.id.genreScrollView)
        genreChipGroup = view.findViewById(R.id.genreChipGroup)
        searchHelperTextView = view.findViewById(R.id.searchHelperTextView)
        homeTabLayout = view.findViewById(R.id.homeTabLayout)
        searchHeaderCard = view.findViewById(R.id.searchHeaderCard)
        genreFilterButton = view.findViewById(R.id.genreFilterButton)
        titleTextView.text = screenTitle()

        adapter = AnimeGridAdapter(requireContext(), animePosts) { animePost ->
            val activity = requireActivity()
            when (activity) {
                is MainActivity -> activity.openDetail(animePost.categoryId, animePost.channelId)
                is AnimexAll -> activity.openDetail(animePost.categoryId, animePost.channelId)
            }
        }
        gridView.adapter = adapter
        setupInfiniteScroll()

        val inAnimexAll = requireActivity() is AnimexAll
        if (isSearchPage()) {
            homeTabLayout?.visibility = View.GONE
            searchHeaderCard?.visibility = View.VISIBLE
            setupSearchUi()
            applyInitialQueryIfNeeded()
        } else {
            if (inAnimexAll) {
                searchHeaderCard?.visibility = View.VISIBLE
                searchInputLayout.visibility = View.VISIBLE
                searchInputLayout.hint = screenTitle()
                searchEditText.hint = "Cari anime"
                searchHelperTextView?.visibility = View.GONE
                setupSearchUi()
            } else {
                searchHeaderCard?.visibility = View.GONE
                searchInputLayout.visibility = View.GONE
                genreScrollView?.visibility = View.GONE
                searchHelperTextView?.visibility = View.GONE
                genreFilterButton?.visibility = View.GONE
            }
            setupHomeTabs()
            if (inAnimexAll && applyInitialQueryIfNeeded()) return
            if (animePosts.isEmpty()) fetchHomeData(1, true) else {
                adapter.notifyDataSetChanged()
                restoreScrollPosition()
            }
        }
    }

    override fun onPause() {
        saveScrollPosition()
        super.onPause()
    }

    private fun saveScrollPosition() {
        savedFirstVisiblePosition = gridView.firstVisiblePosition
        val firstChild = gridView.getChildAt(0)
        savedTopOffset = firstChild?.top?.minus(gridView.paddingTop) ?: 0
    }

    private fun restoreScrollPosition() {
        Handler(Looper.getMainLooper()).post {
            if (isAdded && view != null && ::gridView.isInitialized) gridView.setSelectionFromTop(savedFirstVisiblePosition, savedTopOffset)
        }
    }

    private fun setupInfiniteScroll() {
        gridView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount == 0 || visibleItemCount == 0) return
                val reachedBottom = firstVisibleItem + visibleItemCount >= totalItemCount - PREFETCH_DISTANCE
                if (reachedBottom && !isLoading && hasMoreData) loadNextPage()
            }
        })
    }

    private fun requestNextPageIfNeeded() {
        if (!isAdded || view == null || isLoading || !hasMoreData || !::gridView.isInitialized || !::adapter.isInitialized) return
        val total = adapter.count
        if (total <= 0) return
        val lastVisible = gridView.lastVisiblePosition
        if (lastVisible < 0) return
        if (lastVisible >= total - PREFETCH_DISTANCE) loadNextPage()
    }

    private fun requestNextPageAfterRender() {
        if (!hasMoreData || !::gridView.isInitialized) return
        gridView.post { requestNextPageIfNeeded() }
    }

    private fun setupHomeTabs() {
        val tabs = homeTabLayout ?: return
        tabs.visibility = View.VISIBLE
        if (requireActivity() is AnimexAll) {
            val lp = tabs.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.topMargin = 0
                tabs.layoutParams = lp
            }
        }
        if (tabs.tabCount == 0) {
            tabs.addTab(tabs.newTab().setText("Anime Terbaru"))
            tabs.addTab(tabs.newTab().setText("Jadwal"))
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val pos = tab.position
                    if (homeTabMode == pos && animePosts.isNotEmpty() && lastQuery.isEmpty() && selectedGenre.isEmpty()) return
                    homeTabMode = pos
                    lastQuery = ""
                    selectedGenre = ""
                    searchEditText.setText("")
                    updateGenreFilterButtonState()
                    resetPagingState()
                    if (homeTabMode == 0) {
                        titleTextView.text = "Anime Terbaru"
                        fetchHomeData(1, true)
                    } else {
                        titleTextView.text = "Jadwal Rilis"
                        fetchScheduleData(1, true)
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            })
        }
        tabs.getTabAt(homeTabMode)?.takeIf { !it.isSelected }?.select()
    }

    private fun setupSearchUi() {
        val inAnimexAll = requireActivity() is AnimexAll
        searchInputLayout.visibility = View.VISIBLE
        if (inAnimexAll) {
            genreScrollView?.visibility = View.GONE
            searchHelperTextView?.visibility = View.GONE
            setupGenreFilterMenu()
        } else {
            genreFilterButton?.visibility = View.GONE
            genreScrollView?.visibility = View.VISIBLE
            searchHelperTextView?.visibility = View.VISIBLE
            if (genreChipGroup?.childCount == 0) fetchGenreList()
        }

        searchInputLayout.setEndIconOnClickListener { startSearch() }
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else false
        }

        if (lastQuery.isNotEmpty()) searchEditText.setText(lastQuery)
        if (animePosts.isNotEmpty()) {
            adapter.notifyDataSetChanged()
            restoreScrollPosition()
        }
    }

    private fun applyInitialQueryIfNeeded(): Boolean {
        val startQuery = initialQuery().trim()
        if (startQuery.isEmpty() || lastQuery.isNotEmpty()) return false
        lastQuery = startQuery
        selectedGenre = ""
        searchEditText.setText(startQuery)
        searchEditText.setSelection(searchEditText.text?.length ?: 0)
        updateGenreFilterButtonState()
        resetPagingState()
        fetchSearchData(startQuery, 1, true)
        return true
    }

    private fun setupGenreFilterMenu() {
        val button = genreFilterButton ?: return
        button.visibility = View.VISIBLE
        button.setOnClickListener { showGenreFilterMenu() }
        if (genreMenuItems.isEmpty() && !genreMenuLoading) fetchGenreMenuList()
        updateGenreFilterButtonState()
    }

    private fun fetchGenreMenuList() {
        genreMenuLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = repository.getGenreList()
                genreMenuItems.clear()
                response.genre.orEmpty().forEach { item ->
                    val name = item.genreName?.trim().orEmpty()
                    if (name.isNotEmpty() && (item.genreStatusHide ?: 0) == 0) genreMenuItems.add(item)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Genre menu network error", e)
            } finally {
                genreMenuLoading = false
                updateGenreFilterButtonState()
            }
        }
    }

    private fun showGenreFilterMenu() {
        val button = genreFilterButton ?: return
        if (genreMenuLoading && genreMenuItems.isEmpty()) {
            Toast.makeText(requireContext(), "Memuat genre...", Toast.LENGTH_SHORT).show()
            return
        }
        val popup = PopupMenu(requireContext(), button)
        val all = popup.menu.add(0, 0, 0, "Semua")
        all.isCheckable = true
        all.isChecked = selectedGenre.isEmpty()
        genreMenuItems.forEachIndexed { index, item ->
            val name = item.genreName?.trim().orEmpty()
            if (name.isNotEmpty()) {
                val menuItem = popup.menu.add(0, index + 1, index + 1, name)
                menuItem.isCheckable = true
                menuItem.isChecked = selectedGenre.equals(name, true)
            }
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) {
                selectedGenre = ""
                updateGenreFilterButtonState()
                resetPagingState()
                if (lastQuery.isNotEmpty()) fetchSearchData(lastQuery, 1, true) else if (homeTabMode == 0) fetchHomeData(1, true) else fetchScheduleData(1, true)
                true
            } else {
                val genre = genreMenuItems.getOrNull(item.itemId - 1)?.genreName?.trim().orEmpty()
                if (genre.isNotEmpty()) {
                    selectedGenre = genre
                    lastQuery = ""
                    searchEditText.setText("")
                    updateGenreFilterButtonState()
                    resetPagingState()
                    fetchGenreData(genre, 1, true)
                }
                true
            }
        }
        popup.show()
    }

    private fun updateGenreFilterButtonState() {
        val button = genreFilterButton ?: return
        val root = view ?: return
        val active = selectedGenre.isNotEmpty()
        val color = MaterialColors.getColor(root, if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
        button.setColorFilter(color)
        button.contentDescription = if (active) "Filter genre aktif" else "Filter genre"
    }

    private fun startSearch() {
        val query = searchEditText.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "Masukkan judul anime", Toast.LENGTH_SHORT).show()
            return
        }
        lastQuery = query
        selectedGenre = ""
        genreChipGroup?.clearCheck()
        updateGenreFilterButtonState()
        resetPagingState()
        fetchSearchData(query, 1, true)
    }

    private fun resetPagingState() {
        currentPage = 0
        hasMoreData = true
        loadedKeys.clear()
        animePosts.clear()
        savedFirstVisiblePosition = 0
        savedTopOffset = 0
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    fun refreshHome() {
        if (!isAdded || isSearchPage()) return
        resetPagingState()
        if (homeTabMode == 0) fetchHomeData(1, true) else fetchScheduleData(1, true)
    }

    protected fun loadNextPage() {
        var nextPage = currentPage + 1
        if (nextPage < 1) nextPage = 1
        if (lastQuery.isNotEmpty()) {
            fetchSearchData(lastQuery, nextPage, false)
        } else if (isSearchPage()) {
            if (selectedGenre.isNotEmpty()) return
            val query = searchEditText.text?.toString()?.trim() ?: lastQuery
            if (query.isNotEmpty()) fetchSearchData(query, nextPage, false)
        } else {
            if (selectedGenre.isNotEmpty()) {
                fetchGenreData(selectedGenre, nextPage, false)
                return
            }
            if (homeTabMode == 1) fetchScheduleData(nextPage, false) else fetchHomeData(nextPage, false)
        }
    }

    protected fun fetchHomeData(page: Int, reset: Boolean) = launchApi("Home network error page=$page") { requestGeneration ->
        val response = repository.getHomePosts(page, PAGE_SIZE, getDeviceId())
        if (response.status.equals("ok", ignoreCase = true)) {
            appendPosts(response.posts, search = false, page = page, reset = reset, countTotal = response.countTotal ?: -1, requestGeneration = requestGeneration)
        } else showToast("Gagal mengambil data")
    }

    protected fun fetchScheduleData(page: Int, reset: Boolean) = launchApi("Schedule network error page=$page") { requestGeneration ->
        val response = repository.getSchedule(page, PAGE_SIZE)
        if (response.status.equals("ok", ignoreCase = true)) {
            appendPosts(response.categories, search = true, page = page, reset = reset, countTotal = response.countTotal ?: -1, requestGeneration = requestGeneration)
            if (animePosts.isEmpty()) showToast("Jadwal belum tersedia")
        } else showToast("Gagal mengambil jadwal")
    }

    protected fun fetchSearchData(query: String, page: Int, reset: Boolean) = launchApi("Search network error page=$page") { requestGeneration ->
        val response = repository.searchAnime(query, page, PAGE_SIZE)
        if (response.status.equals("ok", ignoreCase = true)) {
            appendPosts(response.categories, search = true, page = page, reset = reset, countTotal = response.countTotal ?: -1, requestGeneration = requestGeneration)
        } else showToast("Gagal mengambil data")
    }

    private fun fetchGenreList() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = repository.getGenreList()
                if (response.status.equals("ok", ignoreCase = true)) renderGenreChips(response.genre)
            } catch (e: Exception) {
                Log.e(TAG, "Genre list network error", e)
            }
        }
    }

    private fun renderGenreChips(genres: List<ApiGenre>?) {
        val group = genreChipGroup ?: return
        val safeContext = context ?: return
        if (!isAdded || view == null) return
        group.removeAllViews()

        val allChip = createGenreChip(safeContext, "Semua")
        allChip.setOnClickListener {
            selectedGenre = ""
            group.clearCheck()
            resetPagingState()
            if (lastQuery.isNotEmpty()) {
                searchEditText.setText(lastQuery)
                fetchSearchData(lastQuery, 1, true)
            }
        }
        group.addView(allChip)

        genres.orEmpty().forEach { item ->
            if ((item.genreStatusHide ?: 0) != 0) return@forEach
            val name = item.genreName?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val chip = createGenreChip(safeContext, name)
            chip.setOnClickListener {
                selectedGenre = name
                lastQuery = ""
                searchEditText.setText("")
                resetPagingState()
                fetchGenreData(name, 1, true)
            }
            group.addView(chip)
        }
    }

    private fun createGenreChip(context: Context, text: String): Chip {
        return Chip(context).apply {
            this.text = text
            isCheckable = true
            isClickable = true
            isSingleLine = true
            chipMinHeight = dp(40).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun getDeviceId(): String {
        val safeContext = context ?: return ""
        return Settings.Secure.getString(safeContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    private fun fetchGenreData(genreName: String, page: Int, reset: Boolean) = launchApi("Genre network error page=$page") { requestGeneration ->
        val response = repository.getAnimeByGenre(genreName, page, PAGE_SIZE)
        if (response.status.equals("ok", ignoreCase = true)) {
            appendPosts(response.categories, search = true, page = page, reset = reset, countTotal = response.countTotal ?: -1, requestGeneration = requestGeneration)
            if (animePosts.isEmpty()) showToast("Genre ini belum memiliki anime")
        } else showToast("Gagal mengambil genre")
    }

    private fun launchApi(errorLogMessage: String, block: suspend (Int) -> Unit) {
        val requestGeneration = ++apiRequestGeneration
        apiRequestJob?.cancel()
        isLoading = true
        showLoading(true)
        apiRequestJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded || view == null) return@launch
                block(requestGeneration)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == apiRequestGeneration) {
                    Log.e(TAG, errorLogMessage, e)
                    showToast("Kesalahan jaringan")
                }
            } finally {
                if (requestGeneration == apiRequestGeneration) finishLoading()
            }
        }
    }

    private fun appendPosts(array: List<ApiAnimePost>?, search: Boolean, page: Int, reset: Boolean, countTotal: Int, requestGeneration: Int) {
        if (requestGeneration != apiRequestGeneration || !isAdded || view == null || !::adapter.isInitialized) return
        if (reset) {
            animePosts.clear()
            loadedKeys.clear()
        }

        if (array.isNullOrEmpty()) {
            hasMoreData = false
            adapter.notifyDataSetChanged()
            return
        }

        var added = 0
        array.forEach { item ->
            if (shouldHideFromUi(item)) return@forEach

            val imgUrl = item.imgUrl.orEmpty()
            val categoryName = item.categoryName.orEmpty()
            val categoryId = if (search) item.cid ?: item.categoryId ?: -1 else item.categoryId ?: item.cid ?: -1
            val channelId = item.channelId ?: -1
            val uniqueKey = if (search) "s_$categoryId" else "h_${channelId}_$categoryId"

            if (categoryId <= 0 || loadedKeys.contains(uniqueKey)) return@forEach

            loadedKeys.add(uniqueKey)
            val post = AnimePost(imgUrl, categoryName, categoryId, channelId).apply {
                channelName = item.channelName.orEmpty()
                created = item.created.orEmpty()
                countView = item.countView ?: item.totalViews.orEmpty()
                ongoing = item.ongoing == 1
                hdAvailable = item.isHdAvailable == true
                fhdAvailable = item.isFhdAvailable == true
                rating = item.rating.orEmpty()
                genre = selectedGenre
                scheduleDay = item.days ?: -1
                episodeCount = item.countAnime.orEmpty()
                year = item.years?.toIntOrNull() ?: 0
            }
            animePosts.add(post)
            added++
        }

        currentPage = page
        hasMoreData = if (countTotal > 0) animePosts.size < countTotal else array.size >= PAGE_SIZE
        if (!reset && added == 0) hasMoreData = false

        adapter.notifyDataSetChanged()
        if (reset) restoreScrollPosition()
        requestNextPageAfterRender()
    }

    private fun shouldHideFromUi(item: ApiAnimePost): Boolean {
        val channelName = item.channelName?.trim().orEmpty()
        val categoryName = item.categoryName?.trim().orEmpty()
        return item.channelId == BLOCKED_CHANNEL_ID ||
            (channelName.equals("Trakteer kita yuk!", ignoreCase = true) && categoryName.equals("INFO SANGAT PENTING!", ignoreCase = true))
    }

    private fun showLoading(show: Boolean) {
        if (!isAdded || view == null || !::progressBar.isInitialized) return
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun finishLoading() {
        isLoading = false
        showLoading(false)
    }

    protected fun showToast(text: String) {
        val safeContext = context ?: return
        if (isAdded && view != null) Toast.makeText(safeContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AnimeGrid"
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 6
        private const val BLOCKED_CHANNEL_ID = 45784
    }
}
