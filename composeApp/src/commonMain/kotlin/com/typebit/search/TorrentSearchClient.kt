package com.typebit.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Per-engine progress reported to the UI while a search runs. */
data class SearchEngineProgress(
    val name: String,
    val phase: SearchPhase,
    /** Results found by this engine so far. */
    val count: Int = 0,
)

enum class SearchPhase { RUNNING, DONE, BLOCKED, FAILED }

/**
 * Orchestrates the auto-search across all configured engines.
 *
 * * Engines run SEQUENTIALLY (a human browses one site at a time) with a
 *   random pause between them — never a parallel burst that anti-bot layers
 *   fingerprint.
 * * Results are deduplicated by magnet URI and filtered through
 *   [OnlineVideoFilter] (online-video / cloud-drive entries are dropped).
 * * An engine that hits a Cloudflare challenge or a transport error reports
 *   `BLOCKED` / `FAILED` through the callback so the UI is honest about
 *   which source worked — no fabricated results.
 */
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
            // Honest per-engine status: an engine that threw reports FAILED;
            // an engine that answered with an empty/blocked body reports
            // BLOCKED (Cloudflare challenge) — never fabricated results.
            val phase =
                    when {
                        filtered.isNotEmpty() -> SearchPhase.DONE
                        threw -> SearchPhase.FAILED
                        else -> SearchPhase.BLOCKED
                    }
            onProgress(SearchEngineProgress(engine.name, phase, filtered.size))
            // Human-like pacing between sites.
            delay((900L + rng.nextInt(1800)).toLong())
        }
        return out
    }
}
