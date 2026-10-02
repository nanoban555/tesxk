@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package miku.moe.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnimeDetailV2Fragment : Fragment() {
    private var initialPost: AnimePost? = null
    private var systemBarsApplied = false
    private var previousStatusBarColor = 0
    private var previousNavigationBarColor = 0
    private var previousSystemUiVisibility = 0
    private var previousStatusBarContrastEnforced = true
    private var previousNavigationBarContrastEnforced = true
    private var previousNavigationBarDividerColor = 0
    private var historyRefreshVersion by mutableStateOf(0)
    private var defaultHistoryPreferences: SharedPreferences? = null
    private var animekuHistoryPreferences: SharedPreferences? = null
    private val animeHistoryChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "items") historyRefreshVersion++
    }

    companion object {
        @JvmStatic
        fun newInstance(post: AnimePost): AnimeDetailV2Fragment {
            return AnimeDetailV2Fragment().apply {
                arguments = Bundle().apply {
                    putString("source_id", post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT)
                    putString("image_url", post.imgUrl ?: "")
                    putString("title", post.categoryName ?: "")
                    putInt("category_id", post.categoryId)
                    putInt("channel_id", post.channelId)
                    putString("slug", post.slug ?: "")
                    putString("genre", post.genre ?: "")
                    putString("rating", post.rating ?: "")
                    putInt("year", post.year)
                    putString("views", post.countView ?: "")
                    putString("episode_count", post.episodeCount ?: "")
                    putString("description", post.description ?: "")
                    putString("status", post.statusVideo ?: "")
                    putBoolean("ongoing", post.ongoing)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        initialPost = AnimePost(args?.getString("image_url", "").orEmpty(), args?.getString("title", "").orEmpty(), args?.getInt("category_id", -1) ?: -1, args?.getInt("channel_id", -1) ?: -1).apply {
            sourceId = args?.getString("source_id", AnimeSettingsManager.SOURCE_DEFAULT) ?: AnimeSettingsManager.SOURCE_DEFAULT
            slug = args?.getString("slug", "").orEmpty()
            genre = args?.getString("genre", "").orEmpty()
            rating = args?.getString("rating", "").orEmpty()
            year = args?.getInt("year", 0) ?: 0
            countView = args?.getString("views", "").orEmpty()
            episodeCount = args?.getString("episode_count", "").orEmpty()
            description = args?.getString("description", "").orEmpty()
            statusVideo = args?.getString("status", "").orEmpty()
            ongoing = args?.getBoolean("ongoing", false) ?: false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        applyAnimeDetailSystemBars()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MikuAnimeDetailV2Theme {
                    AnimeDetailV2Screen(
                        initial = initialPost,
                        historyVersion = historyRefreshVersion,
                        onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                        onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val appContext = requireContext().applicationContext
        defaultHistoryPreferences = appContext.getSharedPreferences("anime_watch_history", Context.MODE_PRIVATE)
        animekuHistoryPreferences = appContext.getSharedPreferences("animeku_watch_history", Context.MODE_PRIVATE)
        defaultHistoryPreferences?.registerOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        animekuHistoryPreferences?.registerOnSharedPreferenceChangeListener(animeHistoryChangeListener)
    }

    override fun onResume() {
        super.onResume()
        historyRefreshVersion++
        view?.postDelayed({ if (isAdded && !isHidden) historyRefreshVersion++ }, 250L)
        applyAnimeDetailSystemBars()
    }

    override fun onStop() {
        defaultHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        animekuHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        defaultHistoryPreferences = null
        animekuHistoryPreferences = null
        super.onStop()
    }

    override fun onPause() {
        restoreAnimeDetailSystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreAnimeDetailSystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            restoreAnimeDetailSystemBars()
        } else {
            historyRefreshVersion++
            view?.postDelayed({ if (isAdded && !isHidden) historyRefreshVersion++ }, 250L)
            applyAnimeDetailSystemBars()
        }
    }

    private fun applyAnimeDetailSystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreAnimeDetailSystemBars() {
        val host = activity ?: return
        if (!systemBarsApplied) return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = false
    }

    private fun openGenre(sourceId: String, sourceLabel: String, title: String, value: String) {
        when (val activity = activity) {
            is MainActivity -> activity.openAnimeGenreResult(sourceId, sourceLabel, title, value)
            is AnimexAll -> activity.openAnimeGenreResult(sourceId, sourceLabel, title, value)
        }
    }
}

@Composable
private fun MikuAnimeDetailV2Theme(content: @Composable () -> Unit) {
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
        surfaceContainer = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainer)),
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
private fun AnimeDetailV2Screen(initial: AnimePost?, historyVersion: Int, onBack: () -> Unit, onGenreClick: (String, String, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(initial) { mutableStateOf(initialAnimeDetail(initial)) }
    var loading by remember { mutableStateOf(true) }
    var gridMode by remember(initial) { mutableStateOf(readAnimeEpisodeGridMode(context, initial)) }
    var newestFirst by remember(initial) { mutableStateOf(readAnimeEpisodeNewestFirst(context, initial)) }
    var playbackLoading by remember { mutableStateOf(false) }
    var pendingPlayback by remember { mutableStateOf<Pair<AnimeEpisodeItem, List<AnimeQualityOption>>?>(null) }

    LaunchedEffect(initial?.sourceId, initial?.categoryId, initial?.channelId, initial?.slug) {
        loading = true
        detail = try {
            withContext(Dispatchers.IO) { loadAnimeDetailData(initial) }
        } catch (e: Exception) {
            initialAnimeDetail(initial).copy(error = "Gagal memuat detail lengkap")
        }
        if (FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)) FavoriteManager.update(context, detail.post)
        if (detail.post.sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) AnimekuHistoryManager.updateAnimeMetadata(context, detail.post) else HistoryManager.updateAnimeMetadata(context, detail.post)
        loading = false
    }

    val episodeHistory = remember(detail.post.sourceId, detail.post.categoryId, detail.post.slug, detail.post.categoryName, detail.episodes, historyVersion) {
        loadAnimeEpisodeHistory(context, detail.post, detail.episodes)
    }
    val startEpisode = remember(detail.episodes, episodeHistory) { resolveAnimeStartEpisode(detail.episodes, episodeHistory) }
    val shownEpisodes = if (newestFirst) detail.episodes.asReversed() else detail.episodes

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailAnimeTopBar(detail, onBack) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { DetailAnimeHero(detail) }
                item {
                    DetailAnimeActions(
                        detail = detail,
                        onFavorite = {
                            FavoriteManager.toggle(context, detail.post)
                            detail = detail.copy(favoriteVersion = detail.favoriteVersion + 1)
                        },
                        onStart = {
                            val episode = startEpisode
                            if (episode == null) Toast.makeText(context, "Episode belum tersedia", Toast.LENGTH_SHORT).show()
                            else launchPlaybackWithCloudflare(scope, context, detail, episode, { pendingPlayback = it }, { playbackLoading = it })
                        }
                    )
                }
                if (detail.genres.isNotEmpty()) {
                    item { DetailAnimeGenreChips(detail, onGenreClick) }
                }
                item { DetailAnimeSynopsis(detail.description) }
                if (detail.rows.isNotEmpty()) {
                    item { AnimeInfoCard(detail.rows) }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Episode", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (shownEpisodes.isEmpty()) "" else "${shownEpisodes.size} episode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(onClick = {
                            gridMode = !gridMode
                            saveAnimeEpisodeGridMode(context, detail.post, gridMode)
                        }) {
                            Icon(if (gridMode) Icons.Filled.ViewList else Icons.Filled.GridView, contentDescription = "Mode episode")
                        }
                        FilledTonalIconButton(onClick = {
                            newestFirst = !newestFirst
                            saveAnimeEpisodeNewestFirst(context, detail.post, newestFirst)
                        }) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "Urutan episode")
                        }
                    }
                }
                if (shownEpisodes.isEmpty()) {
                    item { EmptyAnimeEpisode() }
                } else if (gridMode) {
                    shownEpisodes.chunked(2).forEachIndexed { rowIndex, rowEpisodes ->
                        item(key = "episode_grid_row_$rowIndex") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowEpisodes.forEach { episode ->
                                    AnimeEpisodeRowV2(episode, episodeHistory[episode.id], Modifier.weight(1f)) {
                                        launchPlaybackWithCloudflare(scope, context, detail, episode, { pendingPlayback = it }, { playbackLoading = it })
                                    }
                                }
                                repeat(2 - rowEpisodes.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    items(shownEpisodes, key = { it.id.toString() + it.url }) { episode ->
                        AnimeEpisodeRowV2(episode, episodeHistory[episode.id], Modifier.padding(horizontal = 16.dp)) {
                            launchPlaybackWithCloudflare(scope, context, detail, episode, { pendingPlayback = it }, { playbackLoading = it })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (loading || playbackLoading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    val playback = pendingPlayback
    if (playback != null) {
        AlertDialog(
            onDismissRequest = { pendingPlayback = null },
            title = { Text("Resolusi tidak tersedia") },
            text = { Text("Pilih resolusi yang tersedia untuk episode ini.") },
            confirmButton = {
                Column {
                    playback.second.forEach { option ->
                        TextButton(onClick = {
                            PlaybackQualityManager.setQuality(context, option.quality)
                            openAnimePlayback(context, detail, playback.first, option)
                            pendingPlayback = null
                        }) { Text(option.label) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pendingPlayback = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun DetailAnimeTopBar(detail: AnimeDetailData, onBack: () -> Unit) {
    androidx.compose.material3.TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali") } },
        title = {
            Column {
                Text(detail.post.categoryName.orEmpty().ifBlank { "Detail Anime" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(AnimeSettingsManager.labelForSourceId(detail.post.sourceId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun DetailAnimeHero(detail: AnimeDetailData) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val title = detail.post.categoryName.orEmpty()
    Box(Modifier.fillMaxWidth().height(310.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(detail.post.imgUrl).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), MaterialTheme.colorScheme.background))))
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(detail.post.imgUrl).crossfade(true).build(),
                contentDescription = detail.post.categoryName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = title.isNotBlank()) {
                    clipboard.setText(AnnotatedString(title))
                    Toast.makeText(context, "Judul disalin", Toast.LENGTH_SHORT).show()
                })
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.post.rating.isNotBlank()) InfoPillAnime("★ ${detail.post.rating}")
                    if (detail.status.isNotBlank()) InfoPillAnime(detail.status)
                    if (detail.post.year > 0) InfoPillAnime(detail.post.year.toString())
                    if (detail.episodes.isNotEmpty()) InfoPillAnime("${detail.episodes.size} episode")
                }
            }
        }
    }
}

@Composable
private fun DetailAnimeActions(detail: AnimeDetailData, onFavorite: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val favorite = FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onStart, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Mulai Nonton")
        }
        FilledTonalIconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Favorite")
        }
    }
}

@Composable
private fun DetailAnimeGenreChips(detail: AnimeDetailData, onGenreClick: (String, String, String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Genre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            detail.genres.forEach { genre ->
                AssistChip(onClick = { onGenreClick(detail.post.sourceId, AnimeSettingsManager.labelForSourceId(detail.post.sourceId), genre, genre) }, label = { Text(genre) })
            }
        }
    }
}

@Composable
private fun DetailAnimeSynopsis(description: String) {
    var expanded by remember(description) { mutableStateOf(false) }
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Deskripsi Anime", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Tutup deskripsi anime" else "Buka deskripsi anime")
                }
            }
            SelectionContainer {
                Text(
                    description.ifBlank { "Belum ada deskripsi" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AnimeInfoCard(rows: List<Pair<String, String>>) {
    var expanded by remember(rows) { mutableStateOf(false) }
    val visibleRows = if (expanded) rows else rows.take(((rows.size + 1) / 2).coerceAtLeast(1))
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Informasi Anime", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Tutup informasi anime" else "Buka informasi anime")
                }
            }
            visibleRows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    Text(row.first, modifier = Modifier.width(108.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.second, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun AnimeEpisodeRowV2(episode: AnimeEpisodeItem, history: HistoryItem?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val percent = remember(history?.position, history?.duration) { animeHistoryPercent(history) }
    val fraction = remember(history?.position, history?.duration) { animeHistoryFraction(history) }
    val hasProgress = history != null && history.duration > 0L
    val watchedPosition = if (hasProgress) history!!.position.coerceIn(0L, history.duration) else 0L
    val isFinished = hasProgress && percent >= 100
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasProgress) {
                    Text(
                        if (isFinished) "✓ Selesai" else "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            if (episode.subtitle.isNotBlank() || hasProgress) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (episode.subtitle.isNotBlank()) {
                        Text(
                            episode.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (hasProgress) {
                        Text(
                            "${formatAnimeProgressTime(watchedPosition)} / ${formatAnimeProgressTime(history!!.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            if (hasProgress) {
                LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EmptyAnimeEpisode() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Episode belum tersedia", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoPillAnime(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(999.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private data class AnimeDetailData(
    val post: AnimePost,
    val description: String,
    val status: String,
    val genres: List<String>,
    val rows: List<Pair<String, String>>,
    val episodes: List<AnimeEpisodeItem>,
    val error: String = "",
    val favoriteVersion: Int = 0
)

private data class AnimeEpisodeItem(
    val id: Int,
    val title: String,
    val subtitle: String = "",
    val sourceId: String,
    val url: String = "",
    val episodeValue: String = ""
)

private data class AnimeQualityOption(val quality: String, val label: String, val url: String)

private fun loadAnimeEpisodeHistory(context: Context, post: AnimePost, episodes: List<AnimeEpisodeItem>): Map<Int, HistoryItem> {
    if (episodes.isEmpty()) return emptyMap()
    val episodeIds = episodes.asSequence().map { it.id }.filter { it > 0 }.toHashSet()
    val history = if (post.sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) AnimekuHistoryManager.getHistory(context) else HistoryManager.getHistory(context)
    val result = LinkedHashMap<Int, HistoryItem>()
    history.asSequence()
        .filter { it.channelId in episodeIds && historyBelongsToAnime(it, post) }
        .sortedByDescending { it.lastWatched }
        .forEach { if (!result.containsKey(it.channelId)) result[it.channelId] = it }
    return result
}

private fun historyBelongsToAnime(item: HistoryItem, post: AnimePost): Boolean {
    val source = post.sourceId?.trim().orEmpty().ifBlank { AnimeSettingsManager.SOURCE_DEFAULT }
    val itemSource = item.sourceId?.trim().orEmpty().ifBlank { AnimeSettingsManager.SOURCE_DEFAULT }
    if (source != itemSource) return false
    val slug = normalizeAnimeHistorySlug(post.slug)
    val itemSlug = normalizeAnimeHistorySlug(item.slug)
    if (slug.isNotBlank() && itemSlug.isNotBlank()) return slug == itemSlug
    if (post.categoryId > 0 && item.categoryId > 0) return post.categoryId == item.categoryId
    val name = normalizeAnimeHistoryText(post.categoryName)
    return name.isNotBlank() && name == normalizeAnimeHistoryText(item.categoryName)
}

private fun resolveAnimeStartEpisode(episodes: List<AnimeEpisodeItem>, history: Map<Int, HistoryItem>): AnimeEpisodeItem? {
    if (episodes.isEmpty()) return null
    val latest = history.values.maxByOrNull { it.lastWatched }
    if (latest != null) episodes.firstOrNull { it.id == latest.channelId }?.let { return it }
    return episodes.minByOrNull { episodeIndexAnime(it.title) } ?: episodes.firstOrNull()
}

private fun animeHistoryPercent(history: HistoryItem?): Int {
    if (history == null || history.position <= 0L || history.duration <= 0L) return 0
    if (history.position >= history.duration - 5000L) return 100
    return ((history.position.toDouble() / history.duration.toDouble()) * 100.0).toInt().coerceIn(0, 100)
}

private fun animeHistoryFraction(history: HistoryItem?): Float {
    if (history == null || history.position <= 0L || history.duration <= 0L) return 0f
    if (history.position >= history.duration - 5000L) return 1f
    return (history.position.toDouble() / history.duration.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun formatAnimeProgressTime(valueMs: Long): String {
    val totalSeconds = (valueMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun normalizeAnimeHistorySlug(value: String?): String {
    return value.orEmpty().trim().trim('/').lowercase(Locale.ROOT)
}

private fun normalizeAnimeHistoryText(value: String?): String {
    return value.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

private fun animeEpisodePrefKey(post: AnimePost?): String {
    val source = post?.sourceId?.trim().orEmpty().ifBlank { AnimeSettingsManager.SOURCE_DEFAULT }
    val slug = post?.slug?.trim().orEmpty()
    val id = if (slug.isNotBlank()) slug else (post?.categoryId ?: -1).toString()
    return "$source:$id"
}

private fun readAnimeEpisodeGridMode(context: Context, post: AnimePost?): Boolean {
    val prefs = context.getSharedPreferences("anime_detail_episode_prefs", Context.MODE_PRIVATE)
    return if (prefs.contains("grid_mode")) prefs.getBoolean("grid_mode", false) else prefs.getBoolean("grid_" + animeEpisodePrefKey(post), false)
}

private fun saveAnimeEpisodeGridMode(context: Context, post: AnimePost?, value: Boolean) {
    context.getSharedPreferences("anime_detail_episode_prefs", Context.MODE_PRIVATE).edit().putBoolean("grid_mode", value).apply()
}

private fun readAnimeEpisodeNewestFirst(context: Context, post: AnimePost?): Boolean {
    val prefs = context.getSharedPreferences("anime_detail_episode_prefs", Context.MODE_PRIVATE)
    return if (prefs.contains("newest_first")) prefs.getBoolean("newest_first", false) else prefs.getBoolean("newest_" + animeEpisodePrefKey(post), false)
}

private fun saveAnimeEpisodeNewestFirst(context: Context, post: AnimePost?, value: Boolean) {
    context.getSharedPreferences("anime_detail_episode_prefs", Context.MODE_PRIVATE).edit().putBoolean("newest_first", value).apply()
}

private fun initialAnimeDetail(initial: AnimePost?): AnimeDetailData {
    val post = initial ?: AnimePost("", "", -1, -1).apply { sourceId = AnimeSettingsManager.SOURCE_DEFAULT }
    val rows = buildAnimeRows(post, emptyList())
    return AnimeDetailData(post, post.description.orEmpty(), normalizeAnimeStatus(post.statusVideo), splitGenres(post.genre), rows, emptyList())
}

private fun buildAnimeRows(post: AnimePost, episodes: List<AnimeEpisodeItem>, extra: Map<String, String> = emptyMap()): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    fun add(label: String, value: String?) {
        if (isUsefulAnime(value)) rows.add(label to value!!.trim())
    }
    add("Source", AnimeSettingsManager.labelForSourceId(post.sourceId))
    add("Status", normalizeAnimeStatus(post.statusVideo))
    add("Rating", post.rating?.let { if (it.isBlank()) "" else "★ $it" })
    add("Tahun", if (post.year > 0) post.year.toString() else "")
    add("Episode", if (episodes.isNotEmpty()) episodes.size.toString() else post.episodeCount)
    add("Views", post.countView)
    extra.forEach { add(it.key, it.value) }
    return rows
}

private suspend fun loadAnimeDetailData(initial: AnimePost?): AnimeDetailData {
    val post = initial ?: AnimePost("", "", -1, -1).apply { sourceId = AnimeSettingsManager.SOURCE_DEFAULT }
    return when (post.sourceId) {
        AnimeSettingsManager.SOURCE_ANIMEKU -> loadAnimekuDetailData(post)
        AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loadLoverzDetailData(post)
        AnimeSettingsManager.SOURCE_DRAMORA -> loadDramoraDetailData(post)
        AnimeSettingsManager.SOURCE_ANICHIN -> loadAnichinDetailData(post)
        else -> loadDefaultDetailData(post)
    }
}

private fun loadDramoraDetailData(initial: AnimePost): AnimeDetailData {
    val result = Dramora.detail(initial)
    val post = result.post
    post.sourceId = AnimeSettingsManager.SOURCE_DRAMORA
    val episodes = result.episodes.map { item ->
        AnimeEpisodeItem(item.id, item.title, item.subtitle, AnimeSettingsManager.SOURCE_DRAMORA, item.episodeId, item.episodeId)
    }
    val genres = result.genres.toList()
    return AnimeDetailData(post, result.description, normalizeAnimeStatus(post.statusVideo), genres, buildAnimeRows(post, episodes, result.rows), episodes)
}

private fun loadAnichinDetailData(initial: AnimePost): AnimeDetailData {
    val slug = initial.slug?.trim().orEmpty()
    val result = Anichin.detail(slug)
    val post = result.post
    post.sourceId = AnimeSettingsManager.SOURCE_ANICHIN
    if (post.imgUrl.isBlank()) post.imgUrl = initial.imgUrl.orEmpty()
    if (post.categoryName.isBlank()) post.categoryName = initial.categoryName.orEmpty()
    val episodes = result.episodes.map { item ->
        AnimeEpisodeItem(item.id, item.title, item.subtitle, AnimeSettingsManager.SOURCE_ANICHIN, item.episodeId, item.episodeId)
    }
    val genres = result.genres.toList()
    return AnimeDetailData(post, result.description, normalizeAnimeStatus(post.statusVideo), genres, buildAnimeRows(post, episodes, result.rows), episodes)
}

private fun loadDefaultDetailData(initial: AnimePost): AnimeDetailData {
    val body = postForm(DEFAULT_CATEGORY_URL, mapOf("id" to initial.categoryId.toString(), "isAPKvalid" to "true"), defaultHeaders())
    val json = JSONObject(body)
    val category = json.optJSONObject("category")
    val post = AnimePost(initial.imgUrl, initial.categoryName, initial.categoryId, initial.channelId).apply {
        sourceId = AnimeSettingsManager.SOURCE_DEFAULT
        slug = initial.slug
        genre = initial.genre
        rating = initial.rating
        year = initial.year
        countView = initial.countView
        episodeCount = initial.episodeCount
        description = initial.description
        statusVideo = initial.statusVideo
    }
    if (category != null) {
        post.imgUrl = firstUsefulAnime(category.optString("img_url", ""), post.imgUrl)
        post.categoryName = firstUsefulAnime(category.optString("category_name", ""), post.categoryName)
        post.genre = firstUsefulAnime(category.optString("genre", ""), post.genre)
        post.rating = firstUsefulAnime(category.optString("rating", ""), post.rating)
    }
    val rawEpisodes = ArrayList<AnimeEpisodeItem>()
    var descriptionChannelId = initial.channelId
    val posts = json.optJSONArray("posts")
    if (posts != null) {
        for (i in 0 until posts.length()) {
            val item = posts.optJSONObject(i) ?: continue
            val channelId = item.optInt("channel_id", -1)
            val title = defaultEpisodeTitle(item, i)
            if (channelId > 0) {
                if (descriptionChannelId <= 0) descriptionChannelId = channelId
                rawEpisodes.add(AnimeEpisodeItem(channelId, title, "", AnimeSettingsManager.SOURCE_DEFAULT, "", title))
            }
        }
    }
    val episodes = normalizeDefaultEpisodeItems(rawEpisodes)
    val meta = if (descriptionChannelId > 0) loadDefaultDescription(descriptionChannelId, post) else emptyMap()
    val description = meta["Synopsis"].orEmpty().ifBlank { post.description.orEmpty() }
    post.genre = firstUsefulAnime(post.genre, meta["Genres"])
    post.year = meta["Year"]?.toIntOrNull() ?: post.year
    post.description = description
    return AnimeDetailData(post, description, normalizeAnimeStatus(post.statusVideo), splitGenres(post.genre), buildAnimeRows(post, episodes, meta.filterKeys { it != "Synopsis" && it != "Genres" }), episodes.sortedBy { episodeIndexAnime(it.title) })
}

private fun defaultEpisodeTitle(item: JSONObject, index: Int): String {
    val keys = arrayOf("channel_name", "video_title", "episode_title", "post_title", "title", "name")
    for (key in keys) {
        val value = item.optString(key, "").trim()
        if (isUsefulAnime(value)) return value
    }
    return "Episode ${index + 1}"
}

private fun normalizeDefaultEpisodeItems(items: List<AnimeEpisodeItem>): List<AnimeEpisodeItem> {
    if (items.isEmpty()) return items
    val cleaned = items.map { episode ->
        val number = episodeNumberFromAnimeTitle(episode.title)
        if (number.isNotBlank()) episode.copy(title = "Episode $number", episodeValue = episode.episodeValue.ifBlank { episode.title }) else episode
    }
    val numbers = cleaned.map { episodeNumberFromAnimeTitle(it.title) }.filter { it.isNotBlank() }
    val duplicateNumbers = numbers.size != numbers.distinct().size
    val weakNumbers = numbers.distinct().size <= 1 && cleaned.size > 1
    if (!duplicateNumbers && !weakNumbers) return cleaned
    val ordered = cleaned.withIndex().sortedWith(compareBy<IndexedValue<AnimeEpisodeItem>> { if (it.value.id > 0) 0 else 1 }.thenBy { if (it.value.id > 0) it.value.id else it.index })
    val numberByIndex = HashMap<Int, Int>()
    ordered.forEachIndexed { order, indexed -> numberByIndex[indexed.index] = order + 1 }
    return cleaned.mapIndexed { index, episode ->
        val original = episode.episodeValue.ifBlank { episode.title }
        val subtitle = if (isUsefulAnime(original) && !original.equals("Episode", true) && !original.equals("Episode ${numberByIndex[index] ?: index + 1}", true)) original else episode.subtitle
        episode.copy(title = "Episode ${numberByIndex[index] ?: index + 1}", subtitle = subtitle, episodeValue = original)
    }
}

private fun loadDefaultDescription(channelId: Int, post: AnimePost): Map<String, String> {
    val body = postForm(DEFAULT_DESCRIPTION_URL, mapOf("channel_id" to channelId.toString(), "isAPKvalid" to "true"), defaultHeaders())
    val json = JSONObject(body)
    if (!json.optString("status").equals("ok", true)) return emptyMap()
    val meta = parseDefaultDescriptionHtml(json.optString("channel_description", ""))
    post.imgUrl = firstUsefulAnime(json.optString("img_url", ""), post.imgUrl)
    post.categoryName = firstUsefulAnime(json.optString("category_name", ""), post.categoryName)
    post.rating = firstUsefulAnime(json.optString("rating", ""), post.rating)
    post.year = json.optInt("years", post.year)
    if (post.year <= 0) post.year = defaultMetaYear(meta)
    post.countView = firstUsefulAnime(json.optString("count_view", ""), post.countView)
    post.genre = firstUsefulAnime(post.genre, meta["Genres"])
    post.episodeCount = firstUsefulAnime(post.episodeCount, meta["Episodes"])
    post.statusVideo = firstUsefulAnime(post.statusVideo, meta["Status"])
    return meta
}

private fun loadAnimekuDetailData(initial: AnimePost): AnimeDetailData {
    var post = initial
    var suggested: JSONArray? = null
    if (initial.categoryId > 0) {
        val json = JSONObject(getDetailText("$ANIMEKU_API_BASE/get_anime_detail?id=${initial.categoryId}&api_key=$ANIMEKU_API_KEY", animekuDetailHeaders()))
        val category = json.optJSONObject("category")
        suggested = json.optJSONArray("suggested")
        if (category != null) post = animekuCategoryPost(category, initial)
    }
    if (suggested == null && initial.channelId > 0) {
        val json = JSONObject(getDetailText("$ANIMEKU_API_BASE/get_post_detail?id=${initial.channelId}", animekuDetailHeaders()))
        val item = json.optJSONObject("post")
        if (item != null) {
            post = animekuPostPost(item, initial)
            suggested = json.optJSONArray("suggested")
        }
    }
    val episodes = parseAnimekuEpisodes(suggested, initial.channelId)
    val descriptionMeta = parseAnimekuDescriptionHtml(post.description)
    val description = animekuSynopsis(post.description, descriptionMeta)
    post.description = description
    val extra = descriptionMeta.filterKeys { it != "Synopsis" && it != "Genres" && it != "Genre" }
    post.genre = firstUsefulAnime(post.genre, firstUsefulAnime(descriptionMeta["Genres"], descriptionMeta["Genre"]))
    return AnimeDetailData(post, description, normalizeAnimeStatus(post.statusVideo), splitGenres(post.genre), buildAnimeRows(post, episodes, extra), episodes.sortedBy { episodeIndexAnime(it.title) })
}

private fun animekuCategoryPost(category: JSONObject, fallback: AnimePost): AnimePost {
    return AnimePost(imageAnimekuDetail(firstUsefulAnime(category.optString("category_image", ""), fallback.imgUrl)), cleanAnimeTitleDetail(category.optString("category_name", fallback.categoryName)), category.optInt("cid", fallback.categoryId), fallback.channelId).apply {
        sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
        genre = category.optString("genre", fallback.genre)
        rating = category.optString("rating", fallback.rating)
        year = category.optInt("year", fallback.year)
        countView = category.optString("total_views", fallback.countView)
        episodeCount = category.optString("video_count", fallback.episodeCount)
        description = category.optString("desc_anime", fallback.description)
        statusVideo = normalizeAnimeStatus(category.optString("status_video", fallback.statusVideo))
        ongoing = isOngoingDetail(statusVideo)
    }
}

private fun animekuPostPost(item: JSONObject, fallback: AnimePost): AnimePost {
    return AnimePost(imageAnimekuDetail(firstUsefulAnime(firstUsefulAnime(item.optString("category_image", ""), item.optString("video_thumbnail", "")), fallback.imgUrl)), cleanAnimeTitleDetail(item.optString("category_name", fallback.categoryName)), item.optInt("cat_id", fallback.categoryId), item.optInt("vid", fallback.channelId)).apply {
        sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
        channelName = item.optString("video_title", fallback.channelName)
        genre = item.optString("genre", fallback.genre)
        rating = item.optString("rating", fallback.rating)
        year = item.optInt("year", fallback.year)
        countView = item.optString("total_views", fallback.countView)
        episodeCount = item.optString("video_count", fallback.episodeCount)
        description = firstUsefulAnime(item.optString("video_description", ""), item.optString("desc_anime", fallback.description))
        statusVideo = normalizeAnimeStatus(item.optString("status_video", fallback.statusVideo))
        ongoing = isOngoingDetail(statusVideo)
    }
}

private fun parseAnimekuEpisodes(suggested: JSONArray?, currentVideoId: Int): List<AnimeEpisodeItem> {
    val map = LinkedHashMap<Int, AnimeEpisodeItem>()
    if (suggested != null) {
        for (i in 0 until suggested.length()) {
            val item = suggested.optJSONObject(i) ?: continue
            val id = item.optInt("vid", -1)
            if (id <= 0) continue
            val title = cleanEpisodeTitleDetail(item.optString("video_title", "Episode"))
            map[id] = AnimeEpisodeItem(id, title, "", AnimeSettingsManager.SOURCE_ANIMEKU)
        }
    }
    if (currentVideoId > 0 && !map.containsKey(currentVideoId)) map[currentVideoId] = AnimeEpisodeItem(currentVideoId, "Episode", "", AnimeSettingsManager.SOURCE_ANIMEKU)
    return map.values.toList()
}

private fun loadLoverzDetailData(initial: AnimePost): AnimeDetailData {
    val variants = slugVariantsDetail(initial.slug)
    var item: JSONObject? = null
    var resolvedSlug = initial.slug
    for (variant in variants) {
        try {
            val requestBody = JSONObject().put("get", "top").put("post_type", "1").put("post_id", variant).put("token", "").toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$ANIMELOVERZ_API_BASE/series.php?url=${Uri.encode(variant)}").headers(loverzDetailHeaders()).post(requestBody).build()
            val body = httpDetailClient.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) continue
            val json = JSONObject(body)
            val candidate = findLoverzDetailObject(json)
            if (candidate != null) {
                item = candidate
                resolvedSlug = variant.trim('/')
                break
            }
        } catch (e: Exception) {
        }
    }
    if (item == null) return initialAnimeDetail(initial)
    val data = item
    val post = AnimePost(firstUsefulAnime(firstUsefulAnime(data.optString("cover", ""), firstUsefulAnime(data.optString("thumb", ""), firstUsefulAnime(data.optString("thumbnail", ""), firstUsefulAnime(data.optString("image", ""), data.optString("poster", ""))))), initial.imgUrl), cleanAnimeTitleDetail(firstUsefulAnime(data.optString("judul", ""), firstUsefulAnime(data.optString("title", ""), firstUsefulAnime(data.optString("name", ""), data.optString("nama", initial.categoryName))))), data.optInt("id", if (initial.categoryId > 0) initial.categoryId else positiveIdDetail(resolvedSlug)), -1).apply {
        sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
        slug = resolvedSlug
        genre = firstUsefulAnime(genreFieldDetail(data, "genre"), firstUsefulAnime(genreFieldDetail(data, "genres"), genreFieldDetail(data, "genre_name")))
        rating = firstUsefulAnime(data.optString("rating", ""), firstUsefulAnime(data.optString("score", ""), data.optString("rate", initial.rating)))
        statusVideo = firstUsefulAnime(data.optString("status", ""), firstUsefulAnime(data.optString("lastup", ""), firstUsefulAnime(data.optString("release_status", ""), data.optString("anime_status", initial.statusVideo))))
        description = firstUsefulAnime(data.optString("sinopsis", ""), firstUsefulAnime(data.optString("synopsis", ""), firstUsefulAnime(data.optString("description", ""), firstUsefulAnime(data.optString("desc", ""), initial.description))))
        ongoing = !statusVideo.lowercase(Locale.ROOT).contains("complete")
    }
    val episodes = parseLoverzEpisodes(data)
    val extra = linkedMapOf<String, String>()
    extra["Type"] = firstUsefulAnime(data.optString("type", ""), data.optString("format", ""))
    extra["Rilis"] = firstUsefulAnime(data.optString("published", ""), firstUsefulAnime(data.optString("date", ""), data.optString("rilis", "")))
    extra["Studio"] = firstUsefulAnime(data.optString("author", ""), firstUsefulAnime(data.optString("studio", ""), data.optString("studios", "")))
    return AnimeDetailData(post, post.description, normalizeAnimeStatus(post.statusVideo), splitGenres(post.genre), buildAnimeRows(post, episodes, extra), episodes.sortedBy { episodeIndexAnime(it.title) })
}

private fun parseLoverzEpisodes(item: JSONObject): List<AnimeEpisodeItem> {
    val result = ArrayList<AnimeEpisodeItem>()
    val chapters = loverzChapterItems(item)
    for (raw in chapters) {
        if (raw is String) {
            val value = raw.trim()
            if (value.isNotBlank()) result.add(AnimeEpisodeItem(positiveIdDetail(value), buildLoverzEpisodeLabel(value), "", AnimeSettingsManager.SOURCE_ANIMELOVERZ, value.trim('/'), value))
            continue
        }
        val chapter = raw as? JSONObject ?: continue
        val url = firstUsefulAnime(chapter.optString("url", ""), firstUsefulAnime(chapter.optString("link", ""), firstUsefulAnime(chapter.optString("slug", ""), firstUsefulAnime(chapter.optString("permalink", ""), firstUsefulAnime(chapter.optString("episode_url", ""), chapter.optString("series_url", ""))))))
        if (!isUsefulAnime(url)) continue
        val ch = firstUsefulAnime(chapter.optString("ch", ""), firstUsefulAnime(chapter.optString("episode", ""), firstUsefulAnime(chapter.optString("eps", ""), firstUsefulAnime(chapter.optString("title", ""), firstUsefulAnime(chapter.optString("name", ""), chapter.optString("label", ""))))))
        val id = chapter.optInt("id", positiveIdDetail(url))
        val label = buildLoverzEpisodeLabel(ch)
        result.add(AnimeEpisodeItem(id, label, "", AnimeSettingsManager.SOURCE_ANIMELOVERZ, url.trim('/'), ch))
    }
    return result.distinctBy { it.url.ifBlank { it.id.toString() } }
}

private fun buildLoverzEpisodeLabel(value: String?): String {
    val text = value?.trim().orEmpty()
    if (!isUsefulAnime(text)) return "Episode"
    return if (text.lowercase(Locale.ROOT).contains("episode")) text else "Episode $text"
}

private fun handlePlaybackOptions(context: Context, detail: AnimeDetailData, episode: AnimeEpisodeItem, options: List<AnimeQualityOption>, showDialog: (Pair<AnimeEpisodeItem, List<AnimeQualityOption>>) -> Unit) {
    if (options.isEmpty()) {
        Toast.makeText(context, "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show()
        return
    }
    val selected = PlaybackQualityManager.getQuality(context)
    val option = options.firstOrNull { it.quality == selected }
    if (option != null) openAnimePlayback(context, detail, episode, option) else showDialog(episode to options)
}

/**
 * Muat opsi playback dengan penanganan Cloudflare: bila server menantang,
 * buka WebView resolver otomatis; setelah user menyelesaikan, ulangi pemutaran.
 */
private fun launchPlaybackWithCloudflare(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    detail: AnimeDetailData,
    episode: AnimeEpisodeItem,
    showDialog: (Pair<AnimeEpisodeItem, List<AnimeQualityOption>>) -> Unit,
    setLoading: (Boolean) -> Unit
) {
    scope.launch {
        setLoading(true)
        try {
            val options = withContext(Dispatchers.IO) { loadAnimePlaybackOptions(context, detail, episode) }
            handlePlaybackOptions(context, detail, episode, options, showDialog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Anichin.CloudflareChallengeException) {
            // Buka WebView untuk selesaikan tantangan Cloudflare (saran goshujin-sama).
            Toast.makeText(context, "Verifikasi Cloudflare diperlukan, membuka browser...", Toast.LENGTH_LONG).show()
            val listener = object : CloudflareHelper.SolvedListener {
                override fun onCloudflareSolved(host: String, sourceLabel: String) {
                    CloudflareHelper.removeSolvedListener(this)
                    // Coba lagi setelah tantangan diselesaikan.
                    launchPlaybackWithCloudflare(scope, context, detail, episode, showDialog, setLoading)
                }
            }
            CloudflareHelper.addSolvedListener(listener)
            try {
                CloudflareHelper.openResolverForSource(context, AnimeSettingsManager.SOURCE_ANICHIN, "Anichin")
            } catch (ex: Exception) {
                CloudflareHelper.removeSolvedListener(listener)
                Toast.makeText(context, "Gagal membuka verifikasi Cloudflare", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal memuat opsi pemutaran", Toast.LENGTH_SHORT).show()
        } finally {
            setLoading(false)
        }
    }
}

private fun loadAnimePlaybackOptions(context: Context, detail: AnimeDetailData, episode: AnimeEpisodeItem): List<AnimeQualityOption> {
    return when (detail.post.sourceId) {
        AnimeSettingsManager.SOURCE_ANIMEKU -> animekuPlaybackOptions(episode.id)
        AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loverzPlaybackOptions(detail.post.slug, episode)
        AnimeSettingsManager.SOURCE_DRAMORA -> dramoraPlaybackOptions(episode)
        AnimeSettingsManager.SOURCE_ANICHIN -> anichinPlaybackOptions(episode)
        else -> defaultPlaybackOptions(episode.id)
    }
}

private fun dramoraPlaybackOptions(episode: AnimeEpisodeItem): List<AnimeQualityOption> {
    val episodeId = episode.url.ifBlank { episode.episodeValue }
    return Dramora.playback(episodeId).map { item -> AnimeQualityOption(item.quality, item.label, item.url) }
}

private fun anichinPlaybackOptions(episode: AnimeEpisodeItem): List<AnimeQualityOption> {
    val episodeId = episode.url.ifBlank { episode.episodeValue }
    return Anichin.playback(episodeId).map { item -> AnimeQualityOption(item.quality, item.label, item.url) }
}

private fun defaultPlaybackOptions(channelId: Int): List<AnimeQualityOption> {
    val body = postForm(DEFAULT_DESCRIPTION_URL, mapOf("channel_id" to channelId.toString(), "isAPKvalid" to "true"), defaultHeaders())
    val json = JSONObject(body)
    val secretKey = json.optString("secretKey", "")
    val result = ArrayList<AnimeQualityOption>()
    addQualityDetail(
        result,
        PlaybackQualityManager.QUALITY_SD,
        PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD),
        firstPlayableDefaultDetail(json, secretKey, "channel_url", "channel_url_ori")
    )
    addQualityDetail(
        result,
        PlaybackQualityManager.QUALITY_HD,
        PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD),
        firstPlayableDefaultDetail(json, secretKey, "channel_url_hd", "channel_url_hd_ori")
    )
    addQualityDetail(
        result,
        PlaybackQualityManager.QUALITY_FHD,
        PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD),
        firstPlayableDefaultDetail(json, secretKey, "channel_url_fhd", "channel_url_fhd_ori")
    )
    return result
}

private fun firstPlayableDefaultDetail(json: JSONObject, secretKey: String, vararg keys: String): String {
    for (key in keys) {
        val decoded = AnimeXnontonUrlDecoder.decryptUrl(json.optString(key, ""), secretKey)
        if (isPlayableDetail(decoded)) return decoded
    }
    return ""
}

private fun animekuPlaybackOptions(videoId: Int): List<AnimeQualityOption> {
    val json = JSONObject(getDetailText("$ANIMEKU_API_BASE/get_post_detail?id=$videoId", animekuDetailHeaders()))
    val post = json.optJSONObject("post") ?: return emptyList()
    val result = ArrayList<AnimeQualityOption>()
    addQualityDetail(result, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), post.optString("video_url", ""))
    val hd = firstPlayableDetail(post.optString("video_url_hd", ""), post.optString("video_url_minihd", ""))
    val hdLabel = if (isPlayableDetail(post.optString("video_url_hd", ""))) PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD) else "Mini HD 480p"
    addQualityDetail(result, PlaybackQualityManager.QUALITY_HD, hdLabel, hd)
    addQualityDetail(result, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD), post.optString("video_url_fullhd", ""))
    return result
}

private fun loverzPlaybackOptions(seriesSlug: String, episode: AnimeEpisodeItem): List<AnimeQualityOption> {
    val attempts = episodeVariantsDetail(episode.url, seriesSlug)
    for (attempt in attempts) {
        try {
            val body = JSONObject().put("post_type", "2").put("post_id", attempt.first).put("series_id", attempt.second).put("series_url", attempt.second).put("episode", episode.episodeValue).put("token", "").toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$ANIMELOVERZ_API_BASE/series/episode/data.php?url=${Uri.encode(attempt.first)}").headers(loverzDetailHeaders()).post(body).build()
            val response = httpDetailClient.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (response.isBlank()) continue
            val json = JSONObject(response)
            val item = firstDataObjectDetail(json)
            val options = parseLoverzQualities(item?.optJSONObject("streams"))
            if (options.isNotEmpty()) return options
        } catch (e: Exception) {
        }
    }
    return emptyList()
}

private fun parseLoverzQualities(streams: JSONObject?): List<AnimeQualityOption> {
    val result = ArrayList<AnimeQualityOption>()
    addQualityDetail(result, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), firstStreamDetail(streams, "480p", "360p"))
    addQualityDetail(result, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD), firstStreamDetail(streams, "720p", "480p", "360p", "1080p"))
    addQualityDetail(result, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD), firstStreamDetail(streams, "1080p", "720p", "480p", "360p"))
    return result
}

private fun openAnimePlayback(context: Context, detail: AnimeDetailData, episode: AnimeEpisodeItem, option: AnimeQualityOption) {
    if (!isPlayableDetail(option.url)) {
        Toast.makeText(context, "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show()
        return
    }
    val post = detail.post
    val intent = when (post.sourceId) {
        AnimeSettingsManager.SOURCE_ANIMEKU -> Intent(context, AnimekuVideoPlayerActivity::class.java).apply {
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, option.url)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, "${episode.title} • ${option.label}")
            putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, post.imgUrl)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, post.categoryId)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, post.categoryName)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, AnimekuHistoryManager.getPositionForChannel(context, episode.id))
            putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, option.quality)
        }
        AnimeSettingsManager.SOURCE_ANIMELOVERZ -> Intent(context, AnimekuVideoPlayerActivity::class.java).apply {
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, option.url)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, "${episode.title} • ${option.label}")
            putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, post.imgUrl)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, post.categoryId)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, post.categoryName)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(context, AnimeSettingsManager.SOURCE_ANIMELOVERZ, episode.id))
            putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, option.quality)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_DISABLE_PLAYLIST, false)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SOURCE_ID, AnimeSettingsManager.SOURCE_ANIMELOVERZ)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_ANIMELOVERZ_SLUG, post.slug)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SLUG, post.slug)
        }
        AnimeSettingsManager.SOURCE_DRAMORA -> Intent(context, AnimekuVideoPlayerActivity::class.java).apply {
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, option.url)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, "${episode.title} • ${option.label}")
            putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, post.imgUrl)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, post.categoryId)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, post.categoryName)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(context, AnimeSettingsManager.SOURCE_DRAMORA, episode.id))
            putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, option.quality)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_DISABLE_PLAYLIST, true)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SOURCE_ID, AnimeSettingsManager.SOURCE_DRAMORA)
            putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SLUG, post.slug)
        }
        AnimeSettingsManager.SOURCE_ANICHIN -> Intent(context, AnichinPlayerActivity::class.java).apply {
            putExtra(AnichinPlayerActivity.EXTRA_VIDEO_URL, option.url)
            putExtra(AnichinPlayerActivity.EXTRA_VIDEO_TITLE, "${episode.title} • ${option.label}")
            putExtra(AnichinPlayerActivity.EXTRA_IMAGE_URL, post.imgUrl)
            putExtra(AnichinPlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(AnichinPlayerActivity.EXTRA_CATEGORY_ID, -1)
            putExtra(AnichinPlayerActivity.EXTRA_CATEGORY_NAME, post.categoryName)
            putExtra(AnichinPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(context, AnimeSettingsManager.SOURCE_ANICHIN, episode.id))
            putExtra(AnichinPlayerActivity.EXTRA_QUALITY, option.quality)
            putExtra(AnichinPlayerActivity.EXTRA_DISABLE_PLAYLIST, false)
            putExtra(AnichinPlayerActivity.EXTRA_HISTORY_SOURCE_ID, AnimeSettingsManager.SOURCE_ANICHIN)
            putExtra(AnichinPlayerActivity.EXTRA_HISTORY_SLUG, post.slug)
            putExtra(AnichinPlayerActivity.EXTRA_ANICHIN_EPISODE_URL, episode.url.ifBlank { episode.episodeValue })
        }
        else -> Intent(context, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, option.url)
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, "${episode.title} • ${option.label}")
            putExtra(VideoPlayerActivity.EXTRA_IMAGE_URL, post.imgUrl)
            putExtra(VideoPlayerActivity.EXTRA_CHANNEL_ID, episode.id)
            putExtra(VideoPlayerActivity.EXTRA_CATEGORY_ID, post.categoryId)
            putExtra(VideoPlayerActivity.EXTRA_CATEGORY_NAME, post.categoryName)
            putExtra(VideoPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(context, episode.id))
            putExtra(VideoPlayerActivity.EXTRA_QUALITY, option.quality)
        }
    }
    context.startActivity(intent)
}

private const val DEFAULT_CATEGORY_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_category_posts_secure/"
private const val DEFAULT_DESCRIPTION_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_post_description/"
private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api"
private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
private const val ANIMEKU_IMAGE_BASE_DETAIL = "http://elara.whatbox.ca:29318/Duljanah/"
private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"

private val httpDetailClient: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
}

private fun getDetailText(url: String, headers: Headers): String {
    val request = Request.Builder().url(url).headers(headers).build()
    return httpDetailClient.newCall(request).execute().use { it.body?.string().orEmpty() }
}

private fun postForm(url: String, values: Map<String, String>, headers: Headers): String {
    val form = FormBody.Builder().apply { values.forEach { add(it.key, it.value) } }.build()
    val request = Request.Builder().url(url).headers(headers).post(form).build()
    return httpDetailClient.newCall(request).execute().use { it.body?.string().orEmpty() }
}

private fun defaultHeaders(): Headers = Headers.Builder()
    .add("Cache-Control", "max-age=0")
    .add("Data-Agent", "AnimeXNonton 26.9.5/18")
    .add("Content-Type", "application/x-www-form-urlencoded")
    .add("User-Agent", "okhttp/5.5.0")
    .build()

private fun animekuDetailHeaders(): Headers = Headers.Builder()
    .add("Cache-Control", "max-age=0")
    .add("Data-Agent", "Your Videos Channel")
    .add("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)")
    .add("Accept", "application/vnd.yourapi.v1.full+json")
    .build()

private fun loverzDetailHeaders(): Headers = Headers.Builder()
    .add("user-agent", "Dart/3.9 (dart:io)")
    .add("accept", "application/json")
    .add("access-control-allow-origin", "*")
    .add("content-type", "text/plain; charset=utf-8")
    .build()

private fun parseDefaultDescriptionHtml(html: String?): Map<String, String> {
    val result = linkedMapOf<String, String>()
    if (!isUsefulAnime(html)) return result
    val document = Jsoup.parseBodyFragment(html.orEmpty())
    val body = document.body()
    val synopsisParts = ArrayList<String>()
    var synopsisMode = body.select("h1, h2, h3, h4, h5, h6").any { isDefaultSynopsisHeading(it.text()) }
    var metadataStarted = false
    val blocks = body.select("p, li")
    for (element in blocks) {
        val blockText = cleanHtmlDetail(element.html()).replace(' ', ' ').trim()
        if (!isUsefulAnime(blockText)) continue
        val strong = element.selectFirst("strong, b")
        if (strong != null) {
            val key = canonicalDefaultMetaKey(strong.text(), true)
            if (isUsefulAnime(key)) {
                val value = defaultMetaValueWithoutLabel(element, strong)
                if (key == "Synopsis") {
                    if (isUsefulAnime(value)) synopsisParts.add(value)
                } else if (isUsefulAnime(value)) {
                    putDefaultMeta(result, key, value)
                    metadataStarted = true
                    synopsisMode = false
                }
                continue
            }
        }
        val inline = parseDefaultInlineMeta(blockText)
        if (inline != null) {
            if (inline.first == "Synopsis") {
                if (isUsefulAnime(inline.second)) synopsisParts.add(inline.second)
            } else {
                putDefaultMeta(result, inline.first, inline.second)
                metadataStarted = true
                synopsisMode = false
            }
            continue
        }
        if (isDefaultSynopsisHeading(blockText)) {
            synopsisMode = true
            continue
        }
        if (synopsisMode || !metadataStarted) synopsisParts.add(blockText)
    }
    val cleanWhole = cleanHtmlDetail(html).replace(' ', ' ').trim()
    parseDefaultTextFallback(cleanWhole, result, synopsisParts)
    val synopsis = buildDefaultSynopsis(synopsisParts)
    if (isUsefulAnime(synopsis)) result["Synopsis"] = synopsis
    return result
}

private fun defaultMetaValueWithoutLabel(element: Element, label: Element): String {
    val clone = element.clone()
    val labelIndex = element.select("strong, b").indexOf(label)
    val labels = clone.select("strong, b")
    if (labelIndex in 0 until labels.size) labels[labelIndex].remove() else clone.selectFirst("strong, b")?.remove()
    return cleanHtmlDetail(clone.html()).replace(' ', ' ').trim().trimStart(':', '：', '-', '–', '—').trim()
}

private fun parseDefaultInlineMeta(value: String): Pair<String, String>? {
    val colon = value.indexOfFirst { it == ':' || it == '：' }
    if (colon <= 0) return null
    val key = canonicalDefaultMetaKey(value.substring(0, colon), false)
    if (!isUsefulAnime(key)) return null
    val metaValue = value.substring(colon + 1).trim()
    if (!isUsefulAnime(metaValue)) return null
    return key to metaValue
}

private fun parseDefaultTextFallback(text: String, result: MutableMap<String, String>, synopsisParts: MutableList<String>) {
    if (!isUsefulAnime(text)) return
    var synopsisMode = false
    var metadataStarted = false
    val knownSynopsis = synopsisParts.any { isUsefulAnime(it) && !isDefaultSourceOnly(it) }
    for (raw in text.split(Regex("\\r?\\n+"))) {
        val line = raw.replace(' ', ' ').trim()
        if (!isUsefulAnime(line)) continue
        if (isDefaultSynopsisHeading(line)) {
            synopsisMode = true
            continue
        }
        val inline = parseDefaultInlineMeta(line)
        if (inline != null) {
            if (inline.first == "Synopsis") {
                if (!containsDefaultText(synopsisParts, inline.second)) synopsisParts.add(inline.second)
                synopsisMode = true
            } else {
                putDefaultMeta(result, inline.first, inline.second)
                metadataStarted = true
                synopsisMode = false
            }
            continue
        }
        if (synopsisMode && !containsDefaultText(synopsisParts, line)) synopsisParts.add(line)
        else if (!knownSynopsis && !metadataStarted && line.length >= 40 && !containsDefaultText(synopsisParts, line)) synopsisParts.add(line)
    }
}

private fun canonicalDefaultMetaKey(value: String?, allowUnknown: Boolean): String {
    val raw = value.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim().trim(':', '：', '-', '–', '—')
    if (!isUsefulAnime(raw)) return ""
    val normalized = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    return when (normalized) {
        "sinopsis", "synopsis", "description", "deskripsi", "deskripsi anime" -> "Synopsis"
        "synonym", "synonyms" -> "Synonyms"
        "alternative", "alternative title", "alternative titles" -> "Alternative"
        "japanese", "japanese title" -> "Japanese"
        "english", "english title" -> "English"
        "type", "format" -> "Type"
        "episode", "episodes" -> "Episodes"
        "status" -> "Status"
        "aired", "release", "released", "release date" -> "Aired"
        "premiered", "premiere" -> "Premiered"
        "broadcast" -> "Broadcast"
        "previewed", "preview" -> "Previewed"
        "producer", "producers" -> "Producers"
        "licensor", "licensors" -> "Licensors"
        "studio", "studios" -> "Studios"
        "source" -> "Source"
        "genre", "genres" -> "Genres"
        "theme", "themes" -> "Themes"
        "demographic", "demographics" -> "Demographic"
        "duration" -> "Duration"
        "rating" -> "Rating"
        "score" -> "Score"
        else -> if (allowUnknown && raw.length <= 48 && normalized.isNotBlank()) raw.removeSuffix(":").trim() else ""
    }
}

private fun putDefaultMeta(result: MutableMap<String, String>, key: String, value: String) {
    val clean = value.replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim()
    if (!isUsefulAnime(key) || !isUsefulAnime(clean)) return
    val existing = result[key].orEmpty().trim()
    if (!isUsefulAnime(existing)) {
        result[key] = clean
        return
    }
    if (!existing.equals(clean, true) && !existing.contains(clean, true)) result[key] = "$existing, $clean"
}

private fun isDefaultSynopsisHeading(value: String?): Boolean {
    val normalized = value.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim().trim(':', '：').lowercase(Locale.ROOT)
    return normalized == "sinopsis" || normalized == "synopsis" || normalized == "deskripsi" || normalized == "deskripsi anime"
}

private fun isDefaultSourceOnly(value: String): Boolean {
    val normalized = value.replace(' ', ' ').trim().lowercase(Locale.ROOT)
    return normalized.matches(Regex("^\\(?\\s*(sumber|source)\\s*[:：].*\\)?$"))
}

private fun containsDefaultText(values: List<String>, value: String): Boolean {
    val normalized = value.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    return values.any { it.replace(' ', ' ').replace(Regex("\\s+"), " ").trim().equals(normalized, true) }
}

private fun buildDefaultSynopsis(parts: List<String>): String {
    val unique = ArrayList<String>()
    for (part in parts) {
        val clean = part.replace(' ', ' ').replace(Regex("[ \t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (!isUsefulAnime(clean) || isDefaultSynopsisHeading(clean) || containsDefaultText(unique, clean)) continue
        unique.add(clean)
    }
    if (unique.none { !isDefaultSourceOnly(it) && it.length >= 20 }) return ""
    return unique.joinToString("\n\n").trim()
}

private fun defaultMetaYear(meta: Map<String, String>): Int {
    val values = listOf(meta["Aired"], meta["Premiered"], meta["Previewed"])
    for (value in values) {
        val match = Regex("\\b(?:19|20)\\d{2}\\b").find(value.orEmpty()) ?: continue
        val year = match.value.toIntOrNull() ?: continue
        if (year in 1900..2100) return year
    }
    return 0
}

private fun animekuSynopsis(raw: String?, meta: Map<String, String>): String {
    val synopsis = meta["Synopsis"].orEmpty().trim()
    if (synopsis.isNotBlank()) return synopsis
    val clean = cleanHtmlDetail(raw).replace(' ', ' ').trim()
    if (!isUsefulAnime(clean)) return ""
    if (looksLikeAnimekuInfoOnly(clean)) return ""
    return clean
}

private fun looksLikeAnimekuInfoOnly(value: String): Boolean {
    val lines = value.split(Regex("\\r?\\n+|\\s{2,}")).map { it.trim() }.filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    var keyed = 0
    for (line in lines) {
        val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
        if (line.contains(':') && key in setOf("alternative", "english", "japanese", "synonyms", "type", "episodes", "status", "aired", "premiered", "broadcast", "producers", "licensors", "studios", "source", "genres", "genre", "duration", "rating", "score")) keyed++
    }
    return keyed >= 3 || (keyed > 0 && keyed == lines.size)
}

private fun parseAnimekuDescriptionHtml(html: String?): Map<String, String> {
    val result = linkedMapOf<String, String>()
    if (!isUsefulAnime(html)) return result
    val lines = cleanHtmlDetail(html).replace('\u00A0', ' ').split(Regex("\\r?\\n+"))
    var synopsisMode = false
    val synopsis = StringBuilder()
    for (raw in lines) {
        val line = raw.trim()
        if (!isUsefulAnime(line)) continue
        val lower = line.lowercase(Locale.ROOT)
        if (lower == "synopsis") {
            synopsisMode = true
            continue
        }
        if (lower == "alternative titles" || lower == "information") {
            synopsisMode = false
            continue
        }
        val colon = line.indexOf(':')
        if (colon > 0) {
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (isUsefulAnime(key) && isUsefulAnime(value)) result[key] = value
        }
        if (synopsisMode) {
            if (synopsis.isNotEmpty()) synopsis.append('\n')
            synopsis.append(line)
        }
    }
    if (synopsis.isNotEmpty()) result["Synopsis"] = synopsis.toString().trim()
    return result
}

private fun cleanHtmlDetail(value: String?): String {
    val normalized = value.orEmpty().replace("<br />", "\n").replace("<br/>", "\n").replace("<br>", "\n").replace("</p>", "\n")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString().trim() else Html.fromHtml(normalized).toString().trim()
}

private fun splitGenres(value: String?): List<String> {
    return value.orEmpty().replace("[", "").replace("]", "").replace("\"", "").split(',', '/', '|').map { it.trim() }.filter { it.isNotBlank() && !it.equals("null", true) }.distinctBy { it.lowercase(Locale.ROOT) }
}

private fun isUsefulAnime(value: String?): Boolean {
    val v = value?.trim().orEmpty()
    return v.isNotBlank() && !v.equals("null", true) && v != "#" && v != "-"
}

private fun firstUsefulAnime(first: String?, second: String?): String {
    val a = first?.trim().orEmpty()
    if (isUsefulAnime(a)) return a
    val b = second?.trim().orEmpty()
    return if (isUsefulAnime(b)) b else ""
}

private fun normalizeAnimeStatus(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (!isUsefulAnime(raw)) return ""
    val lower = raw.lowercase(Locale.ROOT)
    if (lower.contains("complete") || lower.contains("finished")) return "Completed"
    if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing"
    return raw
}

private fun isOngoingDetail(value: String?): Boolean {
    val raw = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return raw.isNotBlank() && !raw.contains("complete") && !raw.contains("finished") && raw != "selesai"
}

private fun imageAnimekuDetail(value: String?): String {
    val image = value?.trim().orEmpty()
    if (!isUsefulAnime(image)) return ""
    if (image.startsWith("http://") || image.startsWith("https://")) return image
    return ANIMEKU_IMAGE_BASE_DETAIL + image
}

private fun cleanAnimeTitleDetail(value: String?): String {
    var text = value?.trim().orEmpty().replace(Regex("\\s+"), " ")
    text = text.replace(Regex("(?i)\\bsub\\s*indo\\b"), "")
    text = text.replace(Regex("(?i)\\bsubtitle\\s*indonesia\\b"), "")
    text = text.replace(Regex("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$"), "")
    text = text.replace(Regex("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$"), "")
    return text.replace(Regex("\\s+"), " ").trim()
}

private fun cleanEpisodeTitleDetail(value: String?): String {
    val raw = value?.trim().orEmpty()
    val number = episodeNumberFromAnimeTitle(raw)
    if (number.isNotBlank()) return "Episode $number"
    return raw.ifBlank { "Episode" }
}

private fun episodeNumberFromAnimeTitle(value: String?): String {
    val raw = value?.trim().orEmpty().replace('_', ' ').replace(',', '.')
    if (raw.isBlank()) return ""
    val patterns = arrayOf(
        Regex("""(?i)\b(?:episode|eps|ep)\s*[.\-_:]*\s*0*(\d+(?:\.\d+)?)\b"""),
        Regex("""(?i)(?:^|[\s\-–—_\[\(])e\s*[.\-_:]*\s*0*(\d+(?:\.\d+)?)\b"""),
        Regex("""(?i)\b(?:part|pt)\s*[.\-_:]*\s*0*(\d+(?:\.\d+)?)\b""")
    )
    for (pattern in patterns) {
        val matches = pattern.findAll(raw).toList()
        val match = matches.lastOrNull() ?: continue
        return normalizeEpisodeNumberDetail(match.groupValues[1])
    }
    val tail = Regex("""(?i)(?:^|[\s\-–—_])0*(\d+(?:\.\d+)?)\s*(?:sub\s*indo|subtitle\s*indonesia)?\s*\z""").find(raw) ?: return ""
    val prefix = raw.substring(0, tail.range.first).trim()
    val lastWord = Regex("""(?i)([a-z]+)\s*$""").find(prefix)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
    if (lastWord in setOf("season", "series", "cour", "s", "seasonal")) return ""
    return normalizeEpisodeNumberDetail(tail.groupValues[1])
}

private fun normalizeEpisodeNumberDetail(value: String): String {
    val clean = value.trim().replace(',', '.')
    if (clean.isBlank()) return ""
    val parts = clean.split('.', limit = 2)
    val head = parts[0].trimStart('0').ifBlank { "0" }
    if (parts.size == 1) return head
    val tail = parts[1].trimEnd('0')
    return if (tail.isBlank()) head else "$head.$tail"
}

private fun episodeIndexAnime(text: String?): Float {
    val extracted = episodeNumberFromAnimeTitle(text)
    if (extracted.isNotBlank()) return extracted.toFloatOrNull() ?: Float.MAX_VALUE
    return Float.MAX_VALUE
}

private fun addQualityDetail(list: MutableList<AnimeQualityOption>, quality: String, label: String, url: String?) {
    if (isPlayableDetail(url)) list.add(AnimeQualityOption(quality, label, url!!.trim()))
}

private fun isPlayableDetail(url: String?): Boolean = url != null && url.trim().isNotBlank() && !url.equals("null", true) && url.startsWith("http")

private fun firstPlayableDetail(vararg urls: String?): String {
    for (url in urls) if (isPlayableDetail(url)) return url!!.trim()
    return ""
}

private fun firstStreamDetail(streams: JSONObject?, vararg keys: String): String {
    if (streams == null) return ""
    for (key in keys) {
        val array = streams.optJSONArray(key) ?: continue
        for (i in 0 until array.length()) {
            val link = array.optJSONObject(i)?.optString("link", "").orEmpty()
            if (isPlayableDetail(link)) return link
        }
    }
    return ""
}

private fun findLoverzDetailObject(json: JSONObject?): JSONObject? {
    val first = firstDataObjectDetail(json)
    if (first != null && hasLoverzEpisodes(first)) return first
    return findLoverzDetailObjectRecursive(json)
}

private fun findLoverzDetailObjectRecursive(value: Any?): JSONObject? {
    when (value) {
        is JSONObject -> {
            if (hasLoverzEpisodes(value)) return value
            val keys = value.keys()
            while (keys.hasNext()) {
                val found = findLoverzDetailObjectRecursive(value.opt(keys.next()))
                if (found != null) return found
            }
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                val found = findLoverzDetailObjectRecursive(value.opt(i))
                if (found != null) return found
            }
        }
    }
    return null
}

private fun firstDataObjectDetail(json: JSONObject?): JSONObject? {
    if (json == null) return null
    val data = json.opt("data")
    if (data is JSONArray) return if (data.length() == 0) null else data.optJSONObject(0)
    if (data is JSONObject) return data
    if (json.has("judul") || json.has("chapter") || json.has("streams")) return json
    return null
}

private fun firstArrayDetail(json: JSONObject?, vararg names: String): JSONArray? {
    if (json == null) return null
    for (name in names) json.optJSONArray(name)?.let { return it }
    return null
}

private fun loverzChapterItems(item: JSONObject?): List<Any> {
    if (item == null) return emptyList()
    val result = ArrayList<Any>()
    val names = arrayOf("chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode")
    for (name in names) appendLoverzChapterValue(result, item.opt(name))
    return result
}

private fun appendLoverzChapterValue(result: MutableList<Any>, value: Any?) {
    when (value) {
        is JSONArray -> for (i in 0 until value.length()) appendLoverzChapterValue(result, value.opt(i))
        is JSONObject -> {
            val nested = firstArrayDetail(value, "data", "result", "items", "list", "chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode")
            if (nested != null) appendLoverzChapterValue(result, nested) else result.add(value)
        }
        is String -> {
            val text = value.trim()
            if (!isUsefulAnime(text)) return
            if (text.startsWith("[") && text.endsWith("]")) {
                try {
                    appendLoverzChapterValue(result, JSONArray(text))
                    return
                } catch (e: Exception) {
                }
            }
            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    appendLoverzChapterValue(result, JSONObject(text))
                    return
                } catch (e: Exception) {
                }
            }
            result.add(text)
        }
    }
}

private fun hasLoverzEpisodes(item: JSONObject): Boolean {
    val chapters = loverzChapterItems(item)
    for (raw in chapters) {
        if (raw is String && isUsefulAnime(raw)) return true
        val chapter = raw as? JSONObject ?: continue
        val url = firstUsefulAnime(chapter.optString("url", ""), firstUsefulAnime(chapter.optString("link", ""), firstUsefulAnime(chapter.optString("slug", ""), firstUsefulAnime(chapter.optString("permalink", ""), firstUsefulAnime(chapter.optString("episode_url", ""), chapter.optString("series_url", ""))))))
        if (isUsefulAnime(url)) return true
    }
    return false
}

private fun genreFieldDetail(item: JSONObject?, key: String): String {
    if (item == null) return ""
    val array = item.optJSONArray(key)
    if (array != null) return joinArrayDetail(array)
    val value = item.opt(key)
    if (value is JSONArray) return joinArrayDetail(value)
    return value?.toString()?.replace("\\", "")?.replace("[", "")?.replace("]", "")?.replace("\"", "")?.replace(Regex("\\s*,\\s*"), ", ")?.trim().orEmpty()
}

private fun joinArrayDetail(array: JSONArray?): String {
    if (array == null) return ""
    val values = ArrayList<String>()
    for (i in 0 until array.length()) {
        val value = array.optString(i, "").trim()
        if (value.isNotBlank()) values.add(value)
    }
    return values.joinToString(", ")
}

private fun slugVariantsDetail(value: String?): List<String> {
    val decoded = Uri.decode(value.orEmpty()).trim()
    val raw = decoded.trimStart('/')
    val clean = raw.trimEnd('/')
    val slash = if (clean.isBlank()) "" else "$clean/"
    val result = ArrayList<String>()
    fun addVariant(value: String) {
        if (value.isNotBlank() && !result.contains(value)) result.add(value)
    }
    if (raw.endsWith("/")) {
        addVariant(slash)
        addVariant(clean)
    } else {
        addVariant(clean)
        addVariant(slash)
    }
    return result
}

private fun episodeVariantsDetail(episode: String, series: String): List<Pair<String, String>> {
    val result = ArrayList<Pair<String, String>>()
    for (ep in slugVariantsDetail(episode)) for (ser in slugVariantsDetail(series)) {
        val pair = ep to ser
        if (!result.contains(pair)) result.add(pair)
    }
    return result
}

private fun escapeJsonDetail(value: String?): String = value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")

private fun positiveIdDetail(value: String?): Int {
    val parsed = value?.trim()?.toIntOrNull()
    if (parsed != null && parsed > 0) return parsed
    val hash = value.orEmpty().hashCode()
    return if (hash == Int.MIN_VALUE) 1 else kotlin.math.abs(hash)
}
