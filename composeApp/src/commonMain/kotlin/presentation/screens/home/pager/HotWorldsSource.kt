package io.github.vrcmteam.vrcm.presentation.screens.home.pager

import io.github.vrcmteam.vrcm.mobilecore.instancesActiveGet
import io.github.vrcmteam.vrcm.mobilecore.instancesDiscoverGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.mobilecore.worldRecentFriendCounts
import io.github.vrcmteam.vrcm.presentation.screens.search.HotWorldVo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 探索: worlds that are populated right now.
 *
 * Separate from the query-driven tabs because it has no query and no paging: one load fills the list, and
 * reloading is the only interaction. The data comes from the core (`instances/active` +
 * `instances/discover`), not from the network layer the other tabs still use - the core is where this app
 * is headed, and it is the only place that also knows the local friend-visit history.
 */
internal class HotWorldsSource(private val logger: org.koin.core.logger.Logger) {
    private val _worlds = MutableStateFlow(emptyList<HotWorldVo>())
    val worlds = _worlds.asStateFlow()

    private val _loadState = MutableStateFlow(PublicSearchLoadState())
    val loadState = _loadState.asStateFlow()

    private var loaded = false

    /** Loads once; [force] re-reads (pull to refresh, retry after a failure). */
    suspend fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        _loadState.value = PublicSearchLoadState(SearchLoadPhase.Loading)
        runCatching {
            val client = vrcxCoreClient()
            val rows = buildList {
                addAll(client.instancesActiveGet().jsonArray.rows())
                addAll(client.instancesDiscoverGet().jsonArray.rows())
            }
            val worlds = aggregate(rows)
            _worlds.value = withFriendCounts(client, worlds)
            _loadState.value = PublicSearchLoadState(SearchLoadPhase.Success)
        }.onFailure { error ->
            // Keep whatever was shown before, like the other tabs do on a failed refresh - but say why in
            // the log: a failure that only flips a flag is invisible in the field.
            logger.error("Hot worlds load failed: ${error::class.simpleName}: ${error.message}")
            _loadState.value = PublicSearchLoadState(SearchLoadPhase.Error)
        }
    }

    private fun JsonArray?.rows(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    /**
     * Collapses per-instance rows into per-world rows.
     *
     * The busiest instance is kept for its capacity and location, so tapping a row opens where most people
     * actually are; the head count is summed, because the row answers "how many players in this world".
     */
    private fun aggregate(rows: List<JsonObject>): List<HotWorldVo> =
        rows.mapNotNull { row ->
            val worldId = row.string("worldId") ?: row.string("id")?.substringBefore(':') ?: return@mapNotNull null
            val world = row["world"] as? JsonObject
            val userCount = row.int("userCount") ?: row.int("n_users") ?: 0
            HotWorldVo(
                worldId = worldId,
                name = world?.string("name") ?: row.string("displayName") ?: row.string("name") ?: worldId,
                coverUrl = world?.string("thumbnailImageUrl")
                    ?: world?.string("imageUrl")
                    ?: "",
                userCount = userCount,
                capacity = row.int("capacity") ?: 0,
                location = row.string("location") ?: worldId,
                worldJson = world?.toString(),
            ) to userCount
        }
            .groupBy { it.first.worldId }
            .map { (_, group) ->
                val sorted = group.sortedByDescending { it.second }
                val busiest = sorted.first().first
                busiest.copy(
                    userCount = group.sumOf { it.second },
                    capacity = busiest.capacity,
                )
            }
            .sortedByDescending { it.userCount }

    /**
     * Adds "how many friends are here" to the rows that survive, one cheap local query for the whole page.
     *
     * Failure is not fatal: the count is a badge, and losing it should not empty the tab.
     */
    private suspend fun withFriendCounts(
        client: io.github.vrcmteam.vrcm.mobilecore.VrcxCoreClient,
        worlds: List<HotWorldVo>,
    ): List<HotWorldVo> {
        val ids = worlds.take(FRIEND_COUNT_WORLDS).map { it.worldId }
        if (ids.isEmpty()) return worlds
        val counts = runCatching {
            client.worldRecentFriendCounts(ids).jsonArray.associate { row ->
                val obj = row.jsonObject
                (obj.string("worldId") ?: "") to (obj.int("friendCount") ?: 0)
            }
        }.getOrDefault(emptyMap())
        if (counts.isEmpty()) return worlds
        return worlds.map { it.copy(friendCount = counts[it.worldId] ?: 0) }
    }

    private companion object {
        /** The command has a world limit upstream; asking for more would be silently truncated. */
        const val FRIEND_COUNT_WORLDS = 40
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content
        ?.toIntOrNull()
