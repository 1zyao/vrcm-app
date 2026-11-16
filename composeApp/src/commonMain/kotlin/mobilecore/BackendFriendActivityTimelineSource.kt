package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.shared.AccountSessionToken
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.service.FriendActivityEvent
import io.github.vrcmteam.vrcm.service.FriendActivityEventType
import io.github.vrcmteam.vrcm.presentation.screens.activity.FriendActivityTimelineCursor
import io.github.vrcmteam.vrcm.presentation.screens.activity.FriendActivityTimelineSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Friend activity read from the VRCX backend instead of being computed locally.
 *
 * This is the point of the whole exercise: the rows were recorded by a node that was
 * online when the events happened and are served from the backend's SQLite, so the
 * phone shows history it could never have observed itself.
 *
 * It implements the same three methods as VRCM's service-backed source, so the
 * screen and its model stay untouched.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal class BackendFriendActivityTimelineSource(
    private val client: VrcxCoreClient,
) : FriendActivityTimelineSource {

    override val sessionTokens: Flow<AccountSessionToken?> =
        SharedFlowCentre.currentSession.map { it?.token }

    override fun observeHead(
        token: AccountSessionToken,
        types: Set<FriendActivityEventType>,
        limit: Int,
    ): Flow<List<FriendActivityEvent>> = flow {
        emit(query(token, types, limit, cursor = null))
    }

    override fun observeBefore(
        token: AccountSessionToken,
        types: Set<FriendActivityEventType>,
        cursor: FriendActivityTimelineCursor,
        limit: Int,
    ): Flow<List<FriendActivityEvent>> = flow {
        emit(query(token, types, limit, cursor))
    }

    override fun observeThrough(
        token: AccountSessionToken,
        types: Set<FriendActivityEventType>,
        cursor: FriendActivityTimelineCursor,
        limit: Int,
    ): Flow<List<FriendActivityEvent>> = flow {
        emit(query(token, types, limit, cursor))
    }

    private suspend fun query(
        token: AccountSessionToken,
        types: Set<FriendActivityEventType>,
        limit: Int,
        cursor: FriendActivityTimelineCursor?,
    ): List<FriendActivityEvent> {
        val rows = runCatching {
            client.feedQuery(
                userId = token.userId,
                mode = "lookup",
                maxEntries = limit,
                // The backend pages by (created_at, source_rank, row_id); only the first
                // two are known here, so the row id doubles as the rank tiebreaker.
                cursor = cursor?.let {
                    buildJsonObject {
                        put("createdAt", Instant.fromEpochMilliseconds(it.occurredAtMillis).toString())
                        put("sourceRank", 0)
                        put("rowId", it.id)
                    }
                },
            )
        }.getOrElse { error ->
            println("[VrcxCore] feed query failed: ${error::class.simpleName}: ${error.message}")
            return emptyList()
        }

        val array = runCatching { rows.jsonArray }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            row.toEvent(types)
        }
    }
}

/** Maps one backend feed row onto VRCM's event type, or null when not displayed. */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun JsonObject.toEvent(
    types: Set<FriendActivityEventType>,
): FriendActivityEvent? {
    val type = when (str("type")?.lowercase()) {
        "online" -> FriendActivityEventType.Online
        "offline" -> FriendActivityEventType.Offline
        "gps", "location" -> FriendActivityEventType.LocationChanged
        "status" -> FriendActivityEventType.StatusChanged
        "bio" -> FriendActivityEventType.BioChanged
        "avatar" -> FriendActivityEventType.AvatarChanged
        "displayname" -> FriendActivityEventType.DisplayNameChanged
        // Met / Left are computed by VRCM from a session's own observations; the
        // backend has no equivalent read model, so they are simply absent here.
        else -> return null
    }
    if (type !in types) return null

    val userId = str("userId") ?: str("ownerId") ?: return null
    val displayName = str("displayName") ?: return null
    val createdAt = str("created_at") ?: return null

    return FriendActivityEvent(
        id = this["rowId"]?.jsonPrimitive?.longOrNull ?: 0L,
        friendUserId = userId,
        displayName = displayName,
        profileImageUrl = str("currentAvatarImageUrl").orEmpty(),
        type = type,
        occurredAtMillis = runCatching { Instant.parse(createdAt).toEpochMilliseconds() }
            .getOrDefault(0L),
        previousValue = str("previousStatus") ?: str("previousLocation"),
        currentValue = str("status") ?: str("location"),
        worldId = str("worldId"),
        worldName = str("worldName"),
        accessType = null,
    )
}

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()


