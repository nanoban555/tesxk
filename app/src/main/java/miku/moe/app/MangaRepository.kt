package miku.moe.app

import android.content.Context
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

object MangaRepository {
    data class MangaPage(val data: ArrayList<MangaPost>, val hasNext: Boolean)
    data class MangaDetailData(val detail: MangaPost?, val chapters: ArrayList<MangaChapter>, val genres: ArrayList<KomikcastClient.GenreItem>, val related: ArrayList<MangaPost>)
    data class MangaDetailCoreData(val detail: MangaPost?, val chapters: ArrayList<MangaChapter>)
    data class MangaDetailExtraData(val genres: ArrayList<KomikcastClient.GenreItem>, val related: ArrayList<MangaPost>)
    data class LatestChapterData(val chapter: String, val totalChapters: Int)

    suspend fun list(sourceId: String, page: Int, sort: String, query: String, genre: String): MangaPage = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
                override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(MangaPage(data, hasNext))
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(MangaPage(ArrayList(), false))
                }
            })
        }
    }

    suspend fun listOrThrow(sourceId: String, page: Int, sort: String, query: String, genre: String): MangaPage = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
                override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(MangaPage(data, hasNext))
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException(message.ifBlank { "Gagal memuat manga" }))
                }
            })
        }
    }

    suspend fun detail(sourceId: String, slug: String): MangaPost? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).detail(slug, object : KomikcastClient.Result<MangaPost> {
                override fun onSuccess(data: MangaPost, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(data.withSource(sourceId, MangaSourceFactory.labelForSourceId(sourceId)))
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    suspend fun chapters(sourceId: String, slug: String): ArrayList<MangaChapter> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).chapters(slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
                override fun onSuccess(data: ArrayList<MangaChapter>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(data)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(ArrayList())
                }
            })
        }
    }

    suspend fun chaptersOrThrow(sourceId: String, slug: String): ArrayList<MangaChapter> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).chapters(slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
                override fun onSuccess(data: ArrayList<MangaChapter>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(data)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException(message.ifBlank { "Gagal memuat chapter" }))
                }
            })
        }
    }

    suspend fun pages(sourceId: String, slug: String, index: Float): ArrayList<String> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).pages(slug, index, object : KomikcastClient.Result<ArrayList<String>> {
                override fun onSuccess(data: ArrayList<String>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(data)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(ArrayList())
                }
            })
        }
    }

    suspend fun genres(sourceId: String): ArrayList<KomikcastClient.GenreItem> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            MangaSourceFactory.createBySourceId(sourceId).genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
                override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>, hasNext: Boolean) {
                    if (cont.isActive) cont.resume(data)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(ArrayList())
                }
            })
        }
    }

    suspend fun related(context: Context, manga: MangaPost, genres: List<KomikcastClient.GenreItem>): ArrayList<MangaPost> = coroutineScope {
        val sourceId = manga.getSourceId()
        val sourceLabel = manga.getSourceLabel()
        val labels = manga.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        val values = labels.map { label -> genres.firstOrNull { it.title.equals(label, true) }?.value ?: label }
        val requests = ArrayList<kotlinx.coroutines.Deferred<MangaPage>>()
        values.forEach { value -> requests.add(async { list(sourceId, 1, "latest", "", value) }) }
        requests.add(async { list(sourceId, 1, "popular", "", "") })
        val out = LinkedHashMap<String, MangaPost>()
        requests.forEach { task ->
            task.await().data.forEach { post ->
                val item = post.withSource(sourceId, sourceLabel)
                MangaLabelUtils.applyHiddenLabels(context, item)
                val key = item.getSourceId() + "|" + item.slug
                if (item.slug != manga.slug && !out.containsKey(key)) out[key] = item
            }
        }
        ArrayList(out.values.take(8))
    }

    suspend fun detailCoreData(base: MangaPost): MangaDetailCoreData = coroutineScope {
        val sourceId = base.getSourceId()
        if (sourceDetailWarmsChapter(sourceId)) {
            val loaded = detail(sourceId, base.slug)
            val detail = mergeDetailWithBase(base, loaded).withSource(base.getSourceId(), base.getSourceLabel())
            val chapters = chapters(sourceId, base.slug)
            MangaDetailCoreData(detail, chapters)
        } else {
            val detailTask = async { mergeDetailWithBase(base, detail(sourceId, base.slug)) }
            val chaptersTask = async { chapters(sourceId, base.slug) }
            val detail = detailTask.await().withSource(base.getSourceId(), base.getSourceLabel())
            val chapters = chaptersTask.await()
            MangaDetailCoreData(detail, chapters)
        }
    }

    private fun mergeDetailWithBase(base: MangaPost, loaded: MangaPost?): MangaPost {
        val out = loaded ?: base
        if (out.coverImage.isNullOrBlank()) out.coverImage = base.coverImage
        if (out.title.isNullOrBlank()) out.title = base.title
        if (out.slug.isNullOrBlank()) out.slug = base.slug
        if (out.genre.isNullOrBlank()) out.genre = base.genre
        if (out.author.isNullOrBlank()) out.author = base.author
        if (out.status.isNullOrBlank()) out.status = base.status
        if (out.synopsis.isNullOrBlank()) out.synopsis = base.synopsis
        if (out.info.isNullOrBlank()) out.info = base.info
        if (out.typeLabel.isNullOrBlank()) out.typeLabel = base.typeLabel
        if (out.latestChapter.isNullOrBlank()) out.latestChapter = base.latestChapter
        if (out.latestChapterDate.isNullOrBlank()) out.latestChapterDate = base.latestChapterDate
        if (out.totalChapters <= 0) out.totalChapters = base.totalChapters
        if (out.sourceId.isNullOrBlank()) out.withSource(base.getSourceId(), base.getSourceLabel())
        return out
    }

    suspend fun detailExtraData(context: Context, base: MangaPost, includeRelated: Boolean): MangaDetailExtraData = coroutineScope {
        val sourceId = base.getSourceId()
        val genres = genres(sourceId)
        val relatedItems = if (includeRelated) related(context, base, genres) else ArrayList<MangaPost>()
        MangaDetailExtraData(genres, relatedItems)
    }

    suspend fun detailData(context: Context, base: MangaPost): MangaDetailData = coroutineScope {
        val core = detailCoreData(base)
        val detail = core.detail ?: base
        val extra = detailExtraData(context, detail, true)
        MangaDetailData(detail, core.chapters, extra.genres, extra.related)
    }

    private fun sourceDetailWarmsChapter(sourceId: String): Boolean {
        return MangaSettingsManager.MANGA_SOURCE_WESTMANGA == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_BACAKOMIK == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_KOMIKINDO == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_IKIRU == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_KOMIKU == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_MANGASUSU == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_COSMICSCANS == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_AINZSCANSS == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_APKOMIK == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_DOUJINDESU == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_COMICASO == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_CROTPEDIA == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_NGOMIK == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_MGKOMIK == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_MANHWAINDO == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_SOULSCANS == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_KUROMANGA == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_LUVYAA == sourceId ||
                MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN == sourceId
    }

    fun listFuture(sourceId: String, page: Int, sort: String, query: String, genre: String): CompletableFuture<MangaPage> {
        val future = CompletableFuture<MangaPage>()
        MangaSourceFactory.createBySourceId(sourceId).list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
            override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
                MangaCoroutines.main(Runnable { future.complete(MangaPage(data, hasNext)) })
            }

            override fun onError(message: String) {
                MangaCoroutines.main(Runnable { future.completeExceptionally(RuntimeException(message.ifBlank { "Gagal memuat manga" })) })
            }
        })
        return future
    }

    fun detailFuture(sourceId: String, slug: String): CompletableFuture<MangaPost> {
        val future = CompletableFuture<MangaPost>()
        MangaSourceFactory.createBySourceId(sourceId).detail(slug, object : KomikcastClient.Result<MangaPost> {
            override fun onSuccess(data: MangaPost, hasNext: Boolean) {
                MangaCoroutines.main(Runnable { future.complete(data.withSource(sourceId, MangaSourceFactory.labelForSourceId(sourceId))) })
            }

            override fun onError(message: String) {
                MangaCoroutines.main(Runnable { future.completeExceptionally(RuntimeException(message.ifBlank { "Gagal memuat detail" })) })
            }
        })
        return future
    }

    fun chaptersFuture(sourceId: String, slug: String): CompletableFuture<ArrayList<MangaChapter>> {
        val future = CompletableFuture<ArrayList<MangaChapter>>()
        MangaSourceFactory.createBySourceId(sourceId).chapters(slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
            override fun onSuccess(data: ArrayList<MangaChapter>, hasNext: Boolean) {
                MangaCoroutines.main(Runnable { future.complete(data) })
            }

            override fun onError(message: String) {
                MangaCoroutines.main(Runnable { future.completeExceptionally(RuntimeException(message.ifBlank { "Gagal memuat chapter" })) })
            }
        })
        return future
    }

    fun chaptersReliableFuture(sourceId: String, slug: String): CompletableFuture<ArrayList<MangaChapter>> {
        val future = CompletableFuture<ArrayList<MangaChapter>>()
        if (sourceDetailWarmsChapter(sourceId)) {
            detailFuture(sourceId, slug).whenComplete { _, _ ->
                requestReliableChapters(sourceId, slug, future, false)
            }
        } else {
            requestReliableChapters(sourceId, slug, future, true)
        }
        return future
    }

    private fun requestReliableChapters(sourceId: String, slug: String, future: CompletableFuture<ArrayList<MangaChapter>>, retryAfterDetail: Boolean) {
        if (future.isDone) return
        chaptersFuture(sourceId, slug).whenComplete { data, error ->
            if (future.isDone) return@whenComplete
            if (error == null && data != null && data.isNotEmpty()) {
                future.complete(data)
                return@whenComplete
            }
            if (retryAfterDetail) {
                detailFuture(sourceId, slug).whenComplete { _, _ ->
                    requestReliableChapters(sourceId, slug, future, false)
                }
                return@whenComplete
            }
            if (error != null) future.completeExceptionally(error)
            else future.complete(data ?: ArrayList())
        }
    }

    fun latestChapterDataFuture(sourceId: String, slug: String): CompletableFuture<LatestChapterData> {
        val future = CompletableFuture<LatestChapterData>()
        chaptersReliableFuture(sourceId, slug).whenComplete { chapters, chapterError ->
            val chapter = newestChapterLabel(chapters)
            if (chapter.isNotEmpty()) {
                future.complete(LatestChapterData(chapter, chapters?.size ?: 0))
                return@whenComplete
            }
            detailFuture(sourceId, slug).whenComplete { detail, detailError ->
                if (!future.isDone) {
                    var detailChapter = normalizeChapterLabel(detail?.latestChapter)
                    val total = maxOf(detail?.totalChapters ?: 0, chapters?.size ?: 0)
                    if (detailChapter.isEmpty() && total > 0) detailChapter = "Chapter $total"
                    if (detailChapter.isNotEmpty()) {
                        future.complete(LatestChapterData(detailChapter, total))
                    } else if (chapterError != null) {
                        future.completeExceptionally(chapterError)
                    } else if (detailError != null) {
                        future.completeExceptionally(detailError)
                    } else {
                        future.complete(LatestChapterData("", total))
                    }
                }
            }
        }
        return future
    }

    private fun newestChapterLabel(chapters: ArrayList<MangaChapter>?): String {
        if (chapters.isNullOrEmpty()) return ""
        val newest = chapters.filterNotNull().maxByOrNull { it.index } ?: return ""
        return "Chapter " + MangaChapter.formatIndex(newest.index)
    }

    private fun normalizeChapterLabel(value: String?): String {
        val match = Regex("(\\d+(?:[.,]\\d+)?)").find(value.orEmpty().trim()) ?: return ""
        return "Chapter " + match.groupValues[1].replace(',', '.')
    }

    fun pagesFuture(sourceId: String, slug: String, index: Float): CompletableFuture<ArrayList<String>> {
        val future = CompletableFuture<ArrayList<String>>()
        MangaSourceFactory.createBySourceId(sourceId).pages(slug, index, object : KomikcastClient.Result<ArrayList<String>> {
            override fun onSuccess(data: ArrayList<String>, hasNext: Boolean) {
                MangaCoroutines.main(Runnable { future.complete(data) })
            }

            override fun onError(message: String) {
                MangaCoroutines.main(Runnable { future.completeExceptionally(RuntimeException(message.ifBlank { "Gagal memuat halaman" })) })
            }
        })
        return future
    }
}
