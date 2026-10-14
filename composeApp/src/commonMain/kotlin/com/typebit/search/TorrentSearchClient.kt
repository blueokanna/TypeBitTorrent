package com.typebit.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class SearchEngineProgress(
    val name: String,
    val phase: SearchPhase,
    val count: Int = 0,
)

enum class SearchPhase { RUNNING, DONE, BLOCKED, FAILED }

// Runs all engines sequentially with a random pause between them — never a parallel burst that anti-bot
// layers fingerprint; results are deduplicated by magnet URI and filtered through [OnlineVideoFilter].
class TorrentSearchClient(private val engines: List<TorrentSearchEngine>) {
    private val rng = Random(System.currentTimeMillis())

    suspend fun search(
        query: String,
        onProgress: (SearchEngineProgress) -> Unit,
    ): List<TorrentSearchResult> {
        val out = mutableListOf<TorrentSearchResult>()
        val seen = HashSet<String>()
        for (engine in engines) {
            onProgress(SearchEngineProgress(engine.name, SearchPhase.RUNNING))
            var threw = false
            val results =
                    withContext(Dispatchers.Default) {
                        try {
                            engine.search(query)
                        } catch (t: Throwable) {
                            threw = true
                            emptyList()
                        }
                    }
            val filtered =
                    results.filter {
                        it.magnet.isNotBlank() &&
                                !OnlineVideoFilter.isOnlineVideo(it.title)
                    }
            for (r in filtered) {
                if (seen.add(r.magnet)) out += r
            }
            // An engine that threw reports FAILED; a challenge/empty body reports BLOCKED — never fabricated results.
            val phase =
                    when {
                        filtered.isNotEmpty() -> SearchPhase.DONE
                        threw -> SearchPhase.FAILED
                        else -> SearchPhase.BLOCKED
                    }
            onProgress(SearchEngineProgress(engine.name, phase, filtered.size))
            delay((900L + rng.nextInt(1800)).toLong())
        }
        return out
    }
}
