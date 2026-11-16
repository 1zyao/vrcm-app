package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Short-lived cache for the favourites baseline.
 *
 * One refresh of the favourites panel asks for both the groups and the entries, and the baseline
 * is a single command that already carries both - plus the quota. Caching for a couple of seconds
 * keeps that to one round trip per refresh instead of three, without risking stale data when the
 * user actually acts.
 *
 * `stale: true` means the backend had no active auth scope; that is reported as an error rather
 * than as "no favourites", because showing an empty list for a scoped-out backend would be a lie.
 */
internal object FavoritesBaselineCache {
    private const val TTL_MS = 2_000L

    private val mutex = Mutex()
    private var cacheUserId: String? = null
    private var cacheAge = kotlin.time.TimeSource.Monotonic.markNow()
    private var cacheSnapshot: JsonObject? = null

    suspend fun snapshot(client: VrcxCoreClient): JsonObject = mutex.withLock {
        val userId = SharedFlowCentre.currentSession.value?.account?.userId
            ?: throw VrcxCoreException("noSession", "no authenticated session for favourites")

        cacheSnapshot?.let { cached ->
            if (cacheUserId == userId && cacheAge.elapsedNow().inWholeMilliseconds < TTL_MS) {
                return cached
            }
        }

        val response = client.socialFavoritesBaseline(userId)
        val root = response.jsonObject
        val stale = root["stale"]?.toString()?.contains("true") == true
        val snapshot = root["snapshot"] as? JsonObject
        if (stale || snapshot == null) {
            throw VrcxCoreException(
                "stale",
                "the backend has no active session for favourites yet (stale baseline)"
            )
        }

        cacheUserId = userId
        cacheAge = kotlin.time.TimeSource.Monotonic.markNow()
        cacheSnapshot = snapshot
        snapshot
    }

    /** Drops the cache, e.g. after an add/delete that changes the counts. */
    suspend fun invalidate() = mutex.withLock {
        cacheSnapshot = null
    }
}


