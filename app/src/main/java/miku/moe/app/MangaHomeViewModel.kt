package miku.moe.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.launch

class MangaHomeViewModel : ViewModel() {
    fun loadSection(sourceId: String, sort: String): CompletableFuture<MangaRepository.MangaPage> {
        val future = CompletableFuture<MangaRepository.MangaPage>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.listOrThrow(sourceId, 1, sort, "", ""))
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
