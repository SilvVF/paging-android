package ios.silv.page4

import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.TimeSource

data class ItemWrapper<KEY : Comparable<*>, ITEM> internal constructor(
    val key: KEY,
    val offset: Int,
    internal val pager: Pager<KEY, ITEM>
) {
    fun itemKey() = "$key,$offset"
}

fun <KEY : Comparable<*>, ITEM> ItemWrapper<KEY, ITEM>.get(): ITEM? {
    val item = pager.pages[key]?.getOrNull()?.get(offset) ?: run {
        pager.startFetchingPage(key)
        null
    }

    pager.checkLoadNext(key, offset)

    return item
}

interface PagingFactory<KEY : Comparable<*>, ITEM> {
    suspend fun getNextKey(key: KEY?): KEY?
    suspend fun getPrevKey(key: KEY?): KEY?
    suspend fun getNextPage(key: KEY): Result<List<ITEM>>
}

data class PagingConfig(
    val maxPagesInMemory: Int = 10,
    val pageExpirationTime: Duration = INFINITE,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val prefetchDistance: Int = 5,
    val timeSource: TimeSource,
)

private sealed interface PageEvent {
    data object Init : PageEvent
    data object Refresh : PageEvent
    data class Loading(val key: Any) : PageEvent
    data class Loaded(val key: Any) : PageEvent
    data class Error(val key: Any, val message: String?) : PageEvent
}

sealed class PagingState<ITEM>(
    open val items: List<ITEM> = emptyList()
) {

    sealed class Refreshing<ITEM> : PagingState<ITEM>(items = emptyList()) {
        class Loading<ITEM> : Refreshing<ITEM>()
        data class Error<ITEM>(val message: String?) : Refreshing<ITEM>()
    }

    sealed class Fetching<ITEM>(override val items: List<ITEM>) : PagingState<ITEM>(items = items) {
        data class Loading<ITEM>(override val items: List<ITEM>) : Fetching<ITEM>(items = items)
        data class Success<ITEM>(override val items: List<ITEM>) : Fetching<ITEM>(items = items)
        data class Error<ITEM>(override val items: List<ITEM>, val message: String?) :
            Fetching<ITEM>(items = items)
    }
}

private fun <KEY : Comparable<*>, ITEM> getNextState(
    prev: PageEvent,
    event: PageEvent,
    pages: List<ItemWrapper<KEY, ITEM>>
): PagingState<ItemWrapper<KEY, ITEM>> {
    return when {
        prev is PageEvent.Init || prev is PageEvent.Refresh || event is PageEvent.Init || event is PageEvent.Refresh -> {
            when (event) {
                is PageEvent.Init,
                is PageEvent.Refresh,
                is PageEvent.Loading -> PagingState.Refreshing.Loading()

                is PageEvent.Loaded -> PagingState.Fetching.Success(pages)
                is PageEvent.Error -> PagingState.Refreshing.Error(event.message)
            }
        }
        else -> {
            when (event) {
                is PageEvent.Loading -> PagingState.Fetching.Loading(pages)
                is PageEvent.Loaded -> PagingState.Fetching.Success(pages)
                is PageEvent.Error -> PagingState.Fetching.Error(pages, event.message)
                else -> error("bad state")
            }
        }
    }
}

class Pager<KEY : Comparable<*>, ITEM>(
    val config: PagingConfig,
    factory: () -> PagingFactory<KEY, ITEM>,
) : PagingFactory<KEY, ITEM> by factory() {

    internal val pages = LruCache<KEY, Result<List<ITEM>>>(config.maxPagesInMemory)

    private val scope: CoroutineScope = config.scope
    private val mark = config.timeSource.markNow()
    private val running = mutableMapOf<KEY, Job?>()
    private val times = mutableMapOf<KEY, Duration?>()

    private val mutex = Mutex()

    private val lastEvent = MutableStateFlow<PageEvent>(PageEvent.Init)

    val pagerFlow = channelFlow {

        if (lastEvent.value is PageEvent.Init) {
            startFetchingPage(requireNotNull(getNextKey(null)))
        }

        var prevEvent = lastEvent.value

        lastEvent.collectLatest { event ->

            val pages = pages.snapshot().toSortedMap(compareBy { it }).map { (key, results) ->
                List(results.getOrDefault(emptyList()).size) { index: Int ->
                    ItemWrapper(
                        key = key,
                        offset = index,
                        this@Pager
                    )
                }
            }
                .flatten()

            Log.d(LOG_TAG, "PAGES RECEIVED: count=${pages.size}")

            send(getNextState(prevEvent, event.also { prevEvent = it }, pages))
        }
    }

    fun checkLoadNext(key: KEY, offset: Int) {
        scope.launch {
            mutex.withLock {
                val page = pages[key]?.getOrNull() ?: return@launch
                val left = (page.lastIndex - offset - config.prefetchDistance)
                    .coerceAtLeast(0)

                Log.d(
                    LOG_TAG,
                    "CHECKING NEXT LOAD: found page at $key offset = $offset last = ${page.lastIndex}"
                )
                if (left == 0) {
                    val next = getNextKey(key) ?: return@launch
                    Log.d(LOG_TAG, "LOADING NEXT $next")
                    startFetchingPage(next)
                } else if (page.lastIndex - offset + config.prefetchDistance >= page.lastIndex) {
                    val prev = getPrevKey(key) ?: return@launch
                    Log.d(LOG_TAG, "LOADING PREV: $prev")
                    if (pages[prev] == null) {
                        startFetchingPage(prev)
                    }
                }
            }
        }
    }

    internal fun startFetchingPage(key: KEY) {
        scope.launch {
            mutex.withLock {
                val job = running[key]
                if (job != null && job.isActive) {
                    Log.d(LOG_TAG, "FETCHING PAGE: ALREADY A JOB IN PROGRESS")
                    return@withLock
                }

                val lastTime = times[key]
                val currentTime = mark.elapsedNow()

                val ttl = if (
                    lastTime != null && lastTime.isFinite() &&
                    config.pageExpirationTime.isFinite()
                ) {
                    currentTime - lastTime
                } else {
                    INFINITE
                }

                if (
                    pages[key]?.getOrNull() != null &&
                    (ttl == INFINITE || ttl <= config.pageExpirationTime)
                ) {
                    Log.d(LOG_TAG, "CHECKING EXPIRATION: Page is not expired")
                    return@withLock
                }

                lastEvent.update { PageEvent.Loading(key) }

                running[key] = scope.launch {
                    Log.d(LOG_TAG, "FETCHING PAGE: $key")
                    try {
                        pages.put(key, getNextPage(key))
                        Log.d("FETCHED SUCCESSFULLY", key.toString())
                        times[key] = mark.elapsedNow()
                        lastEvent.update { PageEvent.Loaded(key) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e

                        pages.put(key, Result.failure(e))
                        Log.d(LOG_TAG, "FETCHED FAILED: $key")
                        lastEvent.update { PageEvent.Error(key, e.message) }

                        running[key] = null
                    }
                }
            }
        }
    }

    companion object {
        internal const val LOG_TAG = "PAGER"
    }
}