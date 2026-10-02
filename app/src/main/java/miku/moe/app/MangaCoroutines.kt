package miku.moe.app

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MangaCoroutines {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @JvmStatic
    fun io(block: Runnable): Job {
        return appScope.launch(Dispatchers.IO) {
            block.run()
        }
    }

    @JvmStatic
    fun main(block: Runnable): Job {
        return appScope.launch(Dispatchers.Main.immediate) {
            block.run()
        }
    }

    @JvmStatic
    fun lifecycleIo(owner: LifecycleOwner, ioBlock: Runnable, mainBlock: Runnable?): Job {
        return owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ioBlock.run()
            }
            mainBlock?.run()
        }
    }
}
