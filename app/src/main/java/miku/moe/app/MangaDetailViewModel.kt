package miku.moe.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.launch

class MangaDetailViewModel(application: Application) : AndroidViewModel(application) {
    fun loadDetailData(manga: MangaPost): CompletableFuture<MangaRepository.MangaDetailData> {
        val future = CompletableFuture<MangaRepository.MangaDetailData>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.detailData(getApplication(), manga))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun loadCoreDetailData(manga: MangaPost): CompletableFuture<MangaRepository.MangaDetailCoreData> {
        val future = CompletableFuture<MangaRepository.MangaDetailCoreData>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.detailCoreData(manga))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun loadDetailExtras(manga: MangaPost, includeRelated: Boolean): CompletableFuture<MangaRepository.MangaDetailExtraData> {
        val future = CompletableFuture<MangaRepository.MangaDetailExtraData>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.detailExtraData(getApplication(), manga, includeRelated))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun loadDetail(sourceId: String, slug: String): CompletableFuture<MangaPost?> {
        val future = CompletableFuture<MangaPost?>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.detail(sourceId, slug))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun loadChapters(sourceId: String, slug: String): CompletableFuture<ArrayList<MangaChapter>> {
        val future = CompletableFuture<ArrayList<MangaChapter>>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.chaptersOrThrow(sourceId, slug))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}
