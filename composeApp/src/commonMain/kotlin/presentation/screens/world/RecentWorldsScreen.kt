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
import io.github.vrcmteam.vrcm.mobilecore.gameLogSessionsQuery
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
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
                    vrcxCoreClient().gameLogSessionsQuery(limit = target).jsonArray
                }.getOrElse { error ->
                    throw error as? IllegalArgumentException
                        ?: IllegalStateException(
                            (error as? VrcxCoreException)?.message
                                ?: error.message
                                ?: "game log query failed",
                        )
                }
                requestedSessions = target
                val page = sessions.mapNotNull { element ->
                    runCatching { element.jsonObject }.getOrNull()?.let(::toVisit)
                }
                pagingState = appendRecentWorldPage(
                    current = if (reset) RecentWorldPagingState() else pagingState,
                    page = page,
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
private fun toVisit(row: kotlinx.serialization.json.JsonObject): RecentVisitVo? {
    val worldId = (row["worldId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val createdAt = (row["created_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (worldId.isBlank() || createdAt.isBlank()) return null
    val name = (row["worldName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val durationMs = (row["duration"] as? JsonPrimitive)?.longOrNull
    val players = (row["playerDurationRows"] as? JsonArray)?.size ?: 0
    return RecentVisitVo(
        worldId = worldId,
        worldName = name.ifBlank { worldId },
        visitedAt = formatVisitedAt(createdAt),
        durationMinutes = durationMs
            ?.takeIf { it > 0 }
            ?.let { (it / 60_000L).toInt().coerceAtLeast(1) },
        playerCount = players,
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
                AppIcon(
                    imageVector = AppIcons.DateRange,
                    contentDescription = null,
                    tint = AppTheme.colors.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        chevron = true,
        onClick = onClick,
    )
}

private val RecentVisitIconSize = DpSize(44.dp, 44.dp)


