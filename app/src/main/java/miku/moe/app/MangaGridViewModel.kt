package miku.moe.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.launch

class MangaGridViewModel : ViewModel() {
    fun loadList(sourceId: String, page: Int, sort: String, query: String, genre: String): CompletableFuture<MangaRepository.MangaPage> {
        val future = CompletableFuture<MangaRepository.MangaPage>()
        viewModelScope.launch {
            try {
                future.complete(MangaRepository.listOrThrow(sourceId, page, sort, query, genre))
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}
