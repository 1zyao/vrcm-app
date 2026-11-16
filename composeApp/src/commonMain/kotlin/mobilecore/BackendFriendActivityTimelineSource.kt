package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.shared.AccountSessionToken
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.service.FriendActivityAccessType
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

    /**
     * The window from the newest row down to [cursor].
     *
     * The model renders this result from the top and replaces the visible list with it, so the
     * query has to be anchored at the head. Asking the backend for rows *before* the cursor (what
     * observeBefore does) replaced the list with an older page - that is why a filtered view
     * showed an older "newest" row than the unfiltered one.
     */
    override fun observeThrough(
        token: AccountSessionToken,
        types: Set<FriendActivityEventType>,
        cursor: FriendActivityTimelineCursor,
        limit: Int,
    ): Flow<List<FriendActivityEvent>> = flow {
        emit(query(token, types, limit, cursor = null))
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
                // Ask the backend for the requested kinds. The page is a fixed number of rows,
                // so filtering after the fact meant "模型" showed only the one or two avatar
                // changes that happened to sit inside an unfiltered page.
                filters = types.toFeedFilters(),
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
        if (array.isNotEmpty()) {
            val head = (array.first() as? JsonObject)
            println(
                "[VrcxCore] feed page: filters=${types.toFeedFilters()} rows=${array.size} " +
                    "firstType=${head?.str("type")} firstAt=${head?.str("created_at")} " +
                    "lastAt=${(array.last() as? JsonObject)?.str("created_at")}"
            )
        }

        // Feed rows do not carry the friend's profile icon, so it is joined in from the roster
        // baseline - the same wrapped data the friend list uses. Without this the timeline shows
        // a grey circle for every row.
        // Row icons and each friend's current status both come from the roster baseline - the
        // feed row itself records only what changed.
        var statusByUser: Map<String, String> = emptyMap()
        val iconsByUser = runCatching {
            val snapshot = FriendRosterCache.snapshot(client)
            val byId = snapshot["friendsById"] as? JsonObject ?: return@runCatching emptyMap()
            statusByUser = byId.mapNotNull { (userId, value) ->
                val status = (value as? JsonObject)?.str("status")
                if (status.isNullOrBlank()) null else userId to status
            }.toMap()
            byId.mapNotNull { (userId, value) ->
                val icon = (value as? JsonObject)?.str("iconUrl")
                    ?: (value as? JsonObject)?.str("currentAvatarImageUrl")
                if (icon.isNullOrBlank()) null else userId to icon
            }.toMap()
        }.getOrElse { emptyMap() }

        return array.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            row.toEvent(types, iconsByUser, statusByUser)
        }
    }
}

/** Maps one backend feed row onto VRCM's event type, or null when not displayed. */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun JsonObject.toEvent(
    types: Set<FriendActivityEventType>,
    iconsByUser: Map<String, String> = emptyMap(),
    statusByUser: Map<String, String> = emptyMap(),
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
    // An empty set means "no filter" - that is what the All tab sends, and treating it as
    // "nothing is allowed" made the All tab look empty while every specific tab had data.
    if (types.isNotEmpty() && type !in types) return null

    val userId = str("userId") ?: str("ownerId") ?: return null
    val displayName = str("displayName") ?: return null
    val createdAt = str("created_at") ?: return null

    // A location row carries both the raw instance string and the resolved world name; the
    // timeline shows the name through `worldName`, so `currentValue` stays null - setting both
    // made the subtitle print the same name twice.
    val worldName = str("worldName")
    val location = str("location")
    val (previousValue, currentValue) = when (type) {
        FriendActivityEventType.LocationChanged -> null to null
        FriendActivityEventType.AvatarChanged ->
            str("previousCurrentAvatarImageUrl") to str("currentAvatarImageUrl")
        else -> (str("previousStatus") ?: str("previousLocation")) to (str("status") ?: location)
    }

    return FriendActivityEvent(
        id = this["rowId"]?.jsonPrimitive?.longOrNull ?: 0L,
        friendUserId = userId,
        displayName = displayName,
        // Prefer the friend's icon from the roster, then anything the row itself carries.
        profileImageUrl = iconsByUser[userId]
            ?: str("iconUrl")
            ?: str("currentAvatarImageUrl").orEmpty(),
        type = type,
        occurredAtMillis = runCatching { Instant.parse(createdAt).toEpochMilliseconds() }
            .getOrDefault(0L),
        previousValue = previousValue,
        currentValue = currentValue,
        worldId = location?.substringBefore(':')?.takeIf { it.startsWith("wrld_") },
        worldName = worldName,
        // Only a real instance string has an access type. "private" (and "offline") mean the
        // location is withheld, so they stay null and the UI shows the private-room label instead
        // of "unknown".
        accessType = location?.takeIf { it.startsWith("wrld_") }?.toAccessType(),
        // For a status change the new status is the event's own value; every other row shows
        // where the friend stands right now.
        friendStatus = if (type == FriendActivityEventType.StatusChanged) {
            str("status")
        } else {
            statusByUser[userId]
        },
    )
}

/**
 * Access type from a VRChat location string.
 *
 * Mirrors VRCX's own parser (`shared/utils/location.ts`, applyAccessType): invite+ needs
 * `~private(` AND `~canRequestInvite` together, and the private case is tested first because one
 * string can carry several markers.
 */
private fun String.toAccessType(): FriendActivityAccessType {
    val canRequestInvite = contains("~canRequestInvite")
    return when {
        contains("~private(") ->
            if (canRequestInvite) FriendActivityAccessType.InvitePlus
            else FriendActivityAccessType.Invite
        contains("~friends(") -> FriendActivityAccessType.Friends
        contains("~hidden(") -> FriendActivityAccessType.FriendsPlus
        contains("~group(") -> FriendActivityAccessType.Group
        startsWith("wrld_") -> FriendActivityAccessType.Public
        else -> FriendActivityAccessType.Unknown
    }
}

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

/** This app's event types as the backend's own `filters` values. */
private fun Set<FriendActivityEventType>.toFeedFilters(): List<String> = buildList {
    if (FriendActivityEventType.Online in this@toFeedFilters) add("Online")
    if (FriendActivityEventType.Offline in this@toFeedFilters) add("Offline")
    if (FriendActivityEventType.LocationChanged in this@toFeedFilters) add("GPS")
    if (FriendActivityEventType.StatusChanged in this@toFeedFilters) add("Status")
    if (FriendActivityEventType.BioChanged in this@toFeedFilters) add("Bio")
    if (FriendActivityEventType.AvatarChanged in this@toFeedFilters) add("Avatar")
}



