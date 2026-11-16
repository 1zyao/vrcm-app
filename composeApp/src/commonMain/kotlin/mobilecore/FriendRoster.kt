package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Short-lived cache for the friend roster baseline.
 *
 * The app asks for the online and the offline list separately (and pages through both), while the
 * backend answers with one snapshot containing every friend. Caching for a couple of seconds turns
 * that into a single command per refresh.
 *
 * `stale: true` means the backend had no active auth scope and is reported as an error - an empty
 * roster would look like "you have no friends" rather than "the backend is not signed in".
 */
internal object FriendRosterCache {
    private const val TTL_MS = 2_000L

    private val mutex = Mutex()
    private var cacheUserId: String? = null
    private var cacheAge = kotlin.time.TimeSource.Monotonic.markNow()
    private var cacheSnapshot: JsonObject? = null

    suspend fun snapshot(client: VrcxCoreClient): JsonObject = mutex.withLock {
        val userId = SharedFlowCentre.currentSession.value?.account?.userId
            ?: throw VrcxCoreException("noSession", "no authenticated session for friends")

        cacheSnapshot?.let { cached ->
            if (cacheUserId == userId && cacheAge.elapsedNow().inWholeMilliseconds < TTL_MS) {
                return cached
            }
        }

        val response = client.socialFriendRosterBaseline(userId)
        val root = response.jsonObject
        val stale = root["stale"]?.toString()?.contains("true") == true
        val snapshot = root["snapshot"] as? JsonObject
        if (stale || snapshot == null) {
            throw VrcxCoreException(
                "stale",
                "the backend has no active session for the friend roster yet (stale baseline)"
            )
        }

        cacheUserId = userId
        cacheAge = kotlin.time.TimeSource.Monotonic.markNow()
        cacheSnapshot = snapshot
        snapshot
    }
}
