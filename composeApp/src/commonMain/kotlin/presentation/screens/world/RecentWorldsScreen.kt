package io.github.vrcmteam.vrcm.presentation.screens.world

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vrcmteam.vrcm.presentation.designsystem.*
import io.github.vrcmteam.vrcm.presentation.navigation.AppDetailRoute
import io.github.vrcmteam.vrcm.presentation.navigation.LocalNavigator
import io.github.vrcmteam.vrcm.presentation.navigation.currentOrThrow
import io.github.vrcmteam.vrcm.presentation.compoments.shouldLoadNextPage
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.screens.world.data.WorldProfileVo
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.instanceHistoryQuery
import io.github.vrcmteam.vrcm.presentation.compoments.AImage
import io.github.vrcmteam.vrcm.mobilecore.worldGet
import io.github.vrcmteam.vrcm.mobilecore.worldSummariesGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.koin.compose.viewmodel.koinViewModel

/**
 * One visit, as VRCX recorded it.
 *
 * This is the shape of the local game log, not of a world: the log knows where the owner was, when,
 * for how long and with how many people. It does not know the world's thumbnail or author, and
 * fetching those would turn one local read into one network read per row - so the card shows what
 * the recording actually has.
 */
data class RecentVisitVo(
    val worldId: String,
    val worldName: String,
    val visitedAt: String,
    val durationMinutes: Int?,
    val playerCount: Int,
    /** Sort key: the raw instant. `visitedAt` is already formatted and cannot be ordered. */
    val visitedAtMillis: Long = 0L,
    /** World cover from the local world cache; empty when that world was never cached. */
    val coverUrl: String = "",
)

class RecentWorldsScreenModel : ViewModel() {

    private val _visits = mutableStateOf<List<RecentVisitVo>>(emptyList())
    val visits by _visits

    private val _isLoading = mutableStateOf(true)
    val isLoading by _isLoading

    private val _isLoadingMore = mutableStateOf(false)
    val isLoadingMore by _isLoadingMore

    private val _endReached = mutableStateOf(false)
    val endReached by _endReached

    private var pagingState = RecentWorldPagingState<RecentVisitVo>()
    private var initialLoadStarted = false
    private var refreshInProgress = false

    /** How many sessions have been asked for so far; the query has no offset, only a limit. */
    private var requestedSessions = 0
    val loadMoreFailed: Boolean get() = pagingState.failedOffset != null

    fun loadRecentWorlds() {
        val showLoading = !initialLoadStarted
        initialLoadStarted = true
        loadPage(reset = true, showLoading = showLoading)
    }

    fun loadMoreRecentWorlds() {
        if (!pagingState.canAutoLoadNextPage()) return
        loadPage(reset = false)
    }

    fun retryLoadMoreRecentWorlds() {
        if (!loadMoreFailed) return
        pagingState = prepareRecentWorldPageRetry(pagingState)
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean, showLoading: Boolean = false) {
        if (refreshInProgress || _isLoadingMore.value ||
            (!reset && (_isLoading.value || _endReached.value))
        ) return

        if (reset) {
            refreshInProgress = true
            requestedSessions = 0
            if (showLoading) {
                _visits.value = emptyList()
                _endReached.value = false
                _isLoading.value = true
            }
        } else {
            _isLoadingMore.value = true
        }

        val target = if (reset) {
            RECENT_VISITS_PAGE_SIZE
        } else {
            requestedSessions + RECENT_VISITS_PAGE_SIZE
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessions = runCatching {
                    vrcxCoreClient().instanceHistoryQuery(
                        userId = SharedFlowCentre.currentSession.value?.account?.userId.orEmpty(),
                        limit = target,
                    ).jsonArray
                }.getOrElse { error ->
                    throw error as? IllegalArgumentException
                        ?: IllegalStateException(
                            (error as? VrcxCoreException)?.message
                                ?: error.message
                                ?: "game log query failed",
                        )
                }
                requestedSessions = target

                // The backend selects the newest groups but returns their rows in ascending id
                // order (`ORDER BY group_id DESC` then `ORDER BY id ASC`), so each page arrives
                // oldest-first. Sorting here makes every page newest-first, which is also how the
                // pages concatenate.
                val page = sessions
                    .mapNotNull { element ->
                        runCatching { element.jsonObject }.getOrNull()?.let(::toVisit)
                    }
                    .sortedByDescending { it.visitedAtMillis }                // One bulk read for the page's worlds: `app__world_summaries_get` is the same command
                // the desktop page uses, and it only reads the local cache (no per-row network call -
                // fifty of those is what made an earlier attempt at this page hang).
                val covered = runCatching {
                    val ids = page.map { it.worldId }.distinct()
                    if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        val summaries = vrcxCoreClient().worldSummariesGet(ids).jsonObject
                        ids.associateWith { id ->
                            (summaries[id] as? kotlinx.serialization.json.JsonObject)
                                ?.get("imageUrl")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                                .orEmpty()
                        }
                    }
                }.getOrDefault(emptyMap())
                // Worlds the local cache has never seen: fetch them through VRCX's own cache-first
                // read (`app__world_get` writes the row back into `cache_world`, so each world costs
                // one fetch ever). Bounded on every axis so a slow network cannot stall the page:
                // at most 20 per page, 4 at a time, 3s each, and it gives up after 3 failures.
                val missing = page.map { it.worldId }
                    .distinct()
                    .filter { covered[it].isNullOrEmpty() }
                    .take(20)
                val fetched = LinkedHashMap<String, String>()
                var failures = 0
                for (batch in missing.chunked(4)) {
                    if (failures >= 3) break
                    val results = coroutineScope {
                        batch.map { id ->
                            async {
                                id to runCatching {
                                    withTimeoutOrNull(3_000L) {
                                        // `worldGet` answers the `VrchatResponse { status, data }`
                                        // envelope with `data` as a JSON *string*.
                                        val result = vrcxCoreClient().worldGet(id, full = true)
                                        val body = Json.parseToJsonElement(result.data).jsonObject
                                        (body["imageUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    }
                                }.getOrNull().orEmpty()
                            }
                        }.awaitAll()
                    }
                    for ((id, url) in results) {
                        if (url.isBlank()) failures++ else { failures = 0; fetched[id] = url }
                    }
                }
                val pageWithCovers = page.map { visit ->
                    visit.copy(
                        coverUrl = covered[visit.worldId].orEmpty()
                            .ifBlank { fetched[visit.worldId].orEmpty() },
                    )
                }
                pagingState = appendRecentWorldPage(
                    current = if (reset) RecentWorldPagingState() else pagingState,
                    page = pageWithCovers,
                    pageSize = target,
                    keySelector = { it.worldId },
                )
                _visits.value = pagingState.items
                _endReached.value = pagingState.endReached
            } catch (error: Throwable) {
                if (!reset) {
                    pagingState = markRecentWorldPageFailed(pagingState)
                }
                SharedFlowCentre.toastText.emit(ToastText.Error(error.message.toString()))
            } finally {
                if (reset) refreshInProgress = false
                _isLoading.value = false
                _isLoadingMore.value = false
            }
        }
    }

    private companion object {
        const val RECENT_VISITS_PAGE_SIZE = 50
    }
}

private val VISIT_TIME_ZONE = TimeZone.currentSystemDefault()

/**
 * Turns one session row into a visit.
 *
 * The session JSON is camelCase except `created_at`, which a serde rename keeps in snake_case - so
 * reading `world_name` here silently yields nothing while `worldName` holds the name.
 */
/**
 * One room visit from `app__instance_history_query`.
 *
 * `location` is the world id plus instance flags (`wrld_…:12345~friends(usr_…)`), and `time` is
 * milliseconds spent there - the desktop reads it the same way (`previousInstancesRows.ts`).
 * `events` counts the join/leave events recorded during the visit, which is the closest thing this
 * read has to "how many people passed through".
 */
@OptIn(ExperimentalTime::class)
private fun toVisit(row: kotlinx.serialization.json.JsonObject): RecentVisitVo? {
    val location = (row["location"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val createdAt = (row["createdAt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val worldId = location.substringBefore(':')
    if (worldId.isBlank() || createdAt.isBlank()) return null
    val name = (row["worldName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val durationMs = (row["time"] as? JsonPrimitive)?.longOrNull
    val events = (row["events"] as? JsonArray)?.size ?: 0
    return RecentVisitVo(
        worldId = worldId,
        worldName = name.ifBlank { worldId },
        visitedAt = formatVisitedAt(createdAt),
        durationMinutes = durationMs
            ?.takeIf { it > 0 }
            ?.let { (it / 60_000L).toInt().coerceAtLeast(1) },
        playerCount = events,
        visitedAtMillis = runCatching {
            kotlinx.datetime.Instant.parse(createdAt).toEpochMilliseconds()
        }.getOrDefault(0L),
    )
}

@OptIn(ExperimentalTime::class)
internal fun formatVisitedAt(value: String): String {
    val parsed = runCatching {
        Instant.parse(value).toLocalDateTime(VISIT_TIME_ZONE)
    }.getOrNull() ?: return value
    val month = parsed.monthNumber.toString().padStart(2, '0')
    val day = parsed.dayOfMonth.toString().padStart(2, '0')
    val hour = parsed.hour.toString().padStart(2, '0')
    val minute = parsed.minute.toString().padStart(2, '0')
    return "$month-$day $hour:$minute"
}

internal data class RecentWorldPagingState<T>(
    val items: List<T> = emptyList(),
    val nextOffset: Int = 0,
    val endReached: Boolean = false,
    val failedOffset: Int? = null,
)

internal fun <T, K> appendRecentWorldPage(
    current: RecentWorldPagingState<T>,
    page: List<T>,
    pageSize: Int,
    keySelector: (T) -> K,
): RecentWorldPagingState<T> = RecentWorldPagingState(
    items = (current.items + page).distinctBy(keySelector),
    nextOffset = current.nextOffset + page.size,
    endReached = page.size < pageSize,
)

internal fun <T> markRecentWorldPageFailed(
    current: RecentWorldPagingState<T>,
): RecentWorldPagingState<T> = current.copy(failedOffset = current.nextOffset)

internal fun <T> prepareRecentWorldPageRetry(
    current: RecentWorldPagingState<T>,
): RecentWorldPagingState<T> = current.copy(failedOffset = null)

internal fun <T> RecentWorldPagingState<T>.canAutoLoadNextPage(): Boolean =
    failedOffset != nextOffset
@Serializable
object RecentWorldsScreen : AppDetailRoute {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model: RecentWorldsScreenModel = koinViewModel()

        LaunchedEffect(Unit) {
            model.loadRecentWorlds()
        }

        AppScaffold(
            containerColor = AppTheme.colors.systemBackground,
            topBar = {
                AppNavBar(
                    edgeColor = AppTheme.colors.systemBackground,
                    title = { AppText(text = strings.recentWorldsTitle) },
                    navigationIcon = {
                        AppIconButton(onClick = { navigator.pop() }) {
                            AppIcon(AppIcons.ArrowBackIosNew, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            when {
                model.isLoading -> Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) { AppActivityIndicator() }

                model.visits.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    AppText(
                        text = strings.recentWorldsEmpty,
                        style = AppTheme.type.body,
                        color = AppTheme.colors.secondaryLabel,
                    )
                }

                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState) {
                        snapshotFlow {
                            val layoutInfo = listState.layoutInfo
                            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            lastVisibleIndex to model.visits.size
                        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
                            if (shouldLoadNextPage(lastVisibleIndex, totalItemsCount)) {
                                model.loadMoreRecentWorlds()
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            bottom = paddingValues.calculateBottomPadding() + 8.dp,
                        ),
                    ) {
                        items(model.visits, key = { it.worldId }) { visit ->
                            RecentVisitItem(visit) {
                                navigator.push(WorldProfileScreen(worldProfileVO = WorldProfileVo(visit.worldId)))
                            }
                        }
                        if (model.isLoadingMore || model.loadMoreFailed) {
                            item(key = "recent-visits-loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (model.isLoadingMore) {
                                        AppActivityIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        AppButton(
                                            onClick = model::retryLoadMoreRecentWorlds,
                                            style = AppButtonStyle.Plain,
                                        ) { AppText(strings.retry) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A recorded visit: where, when, how long, with how many people.
 *
 * No thumbnail on purpose - the log has none, and buying one per row with a network read is what
 * made the earlier attempt slower than the thing it replaced.
 */
@Composable
private fun RecentVisitItem(visit: RecentVisitVo, onClick: () -> Unit) {
    val meta = buildList {
        add(visit.visitedAt)
        visit.durationMinutes?.let { minutes ->
            add(
                if (minutes >= 60) {
                    strings.friendActivityDurationHours
                        .replace("%hours%", (minutes / 60).toString())
                        .replace("%minutes%", (minutes % 60).toString())
                } else {
                    strings.friendActivityDurationMinutes.replace("%minutes%", minutes.toString())
                }
            )
        }
        if (visit.playerCount > 0) {
            add(strings.worldProfileCapacityValue.replace("%count%", visit.playerCount.toString()))
        }
    }.joinToString(" · ")

    AppRow(
        title = visit.worldName,
        subtitle = meta,
        leading = {
            Box(
                modifier = Modifier
                    .size(RecentVisitIconSize)
                    .clip(AppShapes.s)
                    .background(AppTheme.colors.fill),
                contentAlignment = Alignment.Center,
            ) {
                // The world cover, read from the local world cache in one bulk call per page. A world
                // that was never cached falls back to the calendar glyph instead of fetching it.
                if (visit.coverUrl.isBlank()) {
                    AppIcon(
                        imageVector = AppIcons.DateRange,
                        contentDescription = null,
                        tint = AppTheme.colors.secondaryLabel,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    AImage(
                        imageData = visit.coverUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        chevron = true,
        onClick = onClick,
    )
}

private val RecentVisitIconSize = DpSize(44.dp, 44.dp)


