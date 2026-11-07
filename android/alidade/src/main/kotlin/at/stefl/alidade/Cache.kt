package at.stefl.alidade

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The results of the searches, kept in memory. A screen leaves the composition when the user opens
// a shot, so a search in a produceState starts again on "back". The cache gives the result at
// once, also to the first frame.

/**
 * The least recently used results, at most [maxEntries], each for at most [maxAgeS] seconds of
 * [clock]. A key is a list of the inputs of the search, so equal inputs give the same entry.
 */
class ResultCache(
    private val maxEntries: Int,
    private val maxAgeS: Double,
    private val clock: () -> Double,
) {
    private class Entry(val value: Any, val createdS: Double)

    private val entries =
        object : LinkedHashMap<Any, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Entry>?) =
                size > maxEntries
        }

    /** The value of [key], or null if the cache has no fresh value. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: Any): T? =
        synchronized(entries) {
            val entry = entries[key] ?: return null
            if (clock() - entry.createdS > maxAgeS) {
                entries.remove(key)
                null
            } else entry.value as T
        }

    fun put(key: Any, value: Any) {
        synchronized(entries) { entries[key] = Entry(value, clock()) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/**
 * The result of [compute] for [key], from [cache] if it has it, else calculated on
 * [Dispatchers.Default] and then kept. Null while the calculation runs, while [ready] is false, or
 * if the calculation failed.
 */
@Composable
fun <T : Any> rememberCached(
    cache: ResultCache,
    key: List<Any?>,
    ready: Boolean = true,
    compute: suspend () -> T,
): T? {
    val state: MutableState<T?> =
        remember(key, ready) { mutableStateOf(if (ready) cache.get(key) else null) }
    LaunchedEffect(key, ready) {
        if (!ready || state.value != null) return@LaunchedEffect
        val value =
            try {
                withContext(Dispatchers.Default) { compute() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@LaunchedEffect
            }
        cache.put(key, value)
        state.value = value
    }
    return state.value
}
