package io.github.vrcmteam.vrcm.network.api.friends

import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.FriendRosterCache
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import io.github.vrcmteam.vrcm.core.extensions.fetchDataList
import io.github.vrcmteam.vrcm.network.api.attributes.AUTH_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.FRIENDS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.USER_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.VRChatResponse
import io.github.vrcmteam.vrcm.network.api.attributes.LocationType
import io.github.vrcmteam.vrcm.network.api.attributes.UserStatus
import io.github.vrcmteam.vrcm.network.api.friends.date.FriendData
import io.github.vrcmteam.vrcm.network.api.notification.data.NotificationDataV2
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

class FriendsApi(private val client: HttpClient) {
    /**
     * 获取特定所有好友信息
     */
    fun friendsFlow(
        offline: Boolean = false,
        offset: Int = 0,
        n: Int = 50,
    ): Flow<List<FriendData>> =
        flow {
            fetchFriendList(
                offline = offline,
                offset = offset,
                n = n
            )
        }

    /**
     * 获取所有好友信息
     */
    fun allFriendsFlow(): Flow<List<FriendData>> =
        flow {
            fetchFriendList(offline = false)
            fetchFriendList(offline = true)
        }

    /**
     * 获取好友信息
     */
    private suspend fun FlowCollector<List<FriendData>>.fetchFriendList(
        offline: Boolean = false,
        offset: Int = 0,
        n: Int = 50,
    ) {
        // The roster baseline keeps two maps: friendsById holds the profile fields, presenceById
        // holds where each friend is. VRCX's presence view carries VRChat's own location string at
        // `view.place.location.tag` (e.g. "wrld_…:97020~group(…)~region(jp)"), so the two are
        // merged here into the shape FriendData expects. That is what the earlier attempt lacked:
        // decoding a friend on its own fails with "Field 'location' is required" because the
        // baseline simply does not put it there.
        val snapshot = FriendRosterCache.snapshot(vrcxCoreClient())
        val byId = snapshot["friendsById"] as? JsonObject ?: return
        val presenceById = snapshot["presenceById"] as? JsonObject

        val friends = byId.mapNotNull { (userId, element) ->
            val friend = element as? JsonObject ?: return@mapNotNull null
            val presence = (presenceById?.get(userId) as? JsonObject)?.get("view") as? JsonObject
            val merged = mergePresence(friend, presence)
            runCatching { CoreJson.decodeFromString(FriendData.serializer(), merged.toString()) }
                .getOrElse { error ->
                    if (decodeFailures < 3) {
                        decodeFailures++
                        println("[VrcxCore] friend decode failed: ${error.message}")
                    }
                    null
                }
        }

        emit(friends.filter { isPresent(it) == !offline })
    }

    /**
     * Friend profile + presence, as the JSON [FriendData] reads.
     *
     * Presence supplies `location`, `travelingToLocation` and the platform; the profile supplies
     * everything else. An absent presence (or `kind: offline`) means the friend is offline.
     */
    private fun mergePresence(friend: JsonObject, view: JsonObject?): JsonObject {
        val place = view?.get("place") as? JsonObject
        val location = place?.get("location") as? JsonObject
        val travelingTo = place?.get("travelingTo") as? JsonObject
        val kind = (view?.get("kind") as? JsonPrimitive)?.contentOrNull

        val locationTag = (location?.get("tag") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val travelingTag = (travelingTo?.get("tag") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        return JsonObject(
            friend + buildMap {
                put(
                    "location",
                    JsonPrimitive(
                        when {
                            kind == null || kind == "offline" -> LocationType.Offline.value
                            !locationTag.isNullOrBlank() -> locationTag
                            else -> LocationType.Offline.value
                        }
                    )
                )
                if (!travelingTag.isNullOrBlank()) {
                    put("travelingToLocation", JsonPrimitive(travelingTag))
                }
                (view?.get("platform") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("lastPlatform", JsonPrimitive(it)) }
            }
        )
    }

    /** Presence as the roster reports it: anything but the explicit offline location. */
    private fun isPresent(friend: FriendData): Boolean {
        val location = friend.location
        return location.isNotEmpty() && location != LocationType.Offline.value
    }

    private companion object {
        /** Keeps the failure log from flooding; the message is what matters. */
        var decodeFailures = 0
    }

    suspend fun sendFriendRequest(userId: String) = client.post {
        url { path(USER_API_PREFIX, userId, "friendRequest") }
    }.checkSuccess<NotificationDataV2>()

    suspend fun deleteFriendRequest(userId: String) = client.delete {
        url { path(USER_API_PREFIX, userId, "friendRequest") }
    }.checkSuccess<VRChatResponse>()

    suspend fun unfriend(userId: String) = client.delete {
        url { path(AUTH_API_PREFIX, USER_API_PREFIX, FRIENDS_API_PREFIX, userId) }
    }.checkSuccess<VRChatResponse>()

}


