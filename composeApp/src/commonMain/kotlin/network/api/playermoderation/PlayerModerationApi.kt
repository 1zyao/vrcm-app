package io.github.vrcmteam.vrcm.network.api.playermoderation

import io.github.vrcmteam.vrcm.core.shared.AccountSessionToken
import io.github.vrcmteam.vrcm.network.api.attributes.AUTH_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.USER_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.playermoderation.data.PlayerModerationData
import io.github.vrcmteam.vrcm.network.api.playermoderation.data.PlayerModerationType
import io.github.vrcmteam.vrcm.mobilecore.localModerationList
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.Serializable

private const val PLAYER_MODERATIONS_PATH = "$AUTH_API_PREFIX/$USER_API_PREFIX/playermoderations"
private const val UNPLAYER_MODERATE_PATH = "$AUTH_API_PREFIX/$USER_API_PREFIX/unplayermoderate"

internal enum class VoiceModerationType(val apiValue: String) {
    Mute("mute"),
    Unmute("unmute"),
    ;

    companion object {
        fun fromApiValue(value: String): VoiceModerationType? =
            entries.firstOrNull { it.apiValue == value }
    }
}

@Serializable
private data class ModeratePlayerRequest(
    val moderated: String,
    val type: String,
)

/** API operations for the account's player management records and voice overrides. */
class PlayerModerationApi(private val client: HttpClient) {
    private val accountReadLock = SynchronizedObject()
    private val inFlightAccountReads =
        mutableMapOf<AccountSessionToken, CompletableDeferred<List<PlayerModerationData>>>()

    internal suspend fun getAll(): List<PlayerModerationData> =
        get()

    suspend fun get(type: PlayerModerationType? = null): List<PlayerModerationData> {
        // The core keeps block/mute state per user in its database; this app's model wants
        // moderation records (id + type + source/target), so the local rows are mapped here.
        // The command has no type filter, so filtering stays on this side.
        val ownerId = io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
            .currentSession.value?.account?.userId
            ?: return emptyList()
        val rows = io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient()
            .localModerationList(ownerId)
        val moderations = runCatching { rows.jsonArray }.getOrDefault(emptyList())
            .mapNotNull { element -> element.asLocalModeration(ownerId) }
        return if (type == null) moderations
        else moderations.filter { it.type.equals(type.apiValue, ignoreCase = true) }
    }

    internal suspend fun getForTarget(
        sessionToken: AccountSessionToken,
        targetUserId: String,
    ): List<PlayerModerationData> = getAllForSession(sessionToken).filter { moderation ->
        moderation.sourceUserId == sessionToken.userId &&
            moderation.targetUserId == targetUserId
    }

    private suspend fun getAllForSession(
        sessionToken: AccountSessionToken,
    ): List<PlayerModerationData> {
        var ownsRequest = false
        val request = synchronized(accountReadLock) {
            inFlightAccountReads[sessionToken]
                ?: CompletableDeferred<List<PlayerModerationData>>().also {
                ownsRequest = true
                inFlightAccountReads[sessionToken] = it
            }
        }
        if (!ownsRequest) return request.await()

        try {
            request.complete(getAll())
        } catch (error: Throwable) {
            request.completeExceptionally(error)
        } finally {
            synchronized(accountReadLock) {
                if (inFlightAccountReads[sessionToken] === request) {
                    inFlightAccountReads.remove(sessionToken)
                }
            }
        }
        return request.await()
    }

    internal suspend fun moderate(
        targetUserId: String,
        type: VoiceModerationType,
    ): PlayerModerationData = client.post(PLAYER_MODERATIONS_PATH) {
        contentType(ContentType.Application.Json)
        setBody(ModeratePlayerRequest(moderated = targetUserId, type = type.apiValue))
    }.checkSuccess()

    internal suspend fun remove(
        targetUserId: String,
        type: VoiceModerationType,
    ) {
        client.put(UNPLAYER_MODERATE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(ModeratePlayerRequest(moderated = targetUserId, type = type.apiValue))
        }.checkSuccess { Unit }
    }

    suspend fun remove(targetUserId: String, type: PlayerModerationType) {
        require(CLEANUP_USER_ID_PATTERN.matches(targetUserId)) { "Invalid target user ID" }
        client.put(UNPLAYER_MODERATE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(ModeratePlayerRequest(moderated = targetUserId, type = type.apiValue))
        }.checkSuccess { Unit }
    }

    private companion object {
        val CLEANUP_USER_ID_PATTERN = Regex("usr_[A-Za-z0-9_-]+")
    }
}

/**
 * One local moderation row as this app's record.
 *
 * The local store keeps two booleans per user (block, mute) and no record id or timestamp-of-record,
 * so the id is synthesised from the user and a user who is both blocked and muted surfaces as
 * "block" - the stronger of the two, which is what the moderation list means to convey.
 */
private fun JsonElement.asLocalModeration(ownerId: String): PlayerModerationData? {
    val row = runCatching { jsonObject }.getOrNull() ?: return null
    val userId = (row["userId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val blocked = (row["block"] as? JsonPrimitive)?.booleanOrNull ?: false
    val muted = (row["mute"] as? JsonPrimitive)?.booleanOrNull ?: false
    if (!blocked && !muted) return null
    return PlayerModerationData(
        created = (row["updatedAt"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        id = "local:$userId",
        sourceDisplayName = "",
        sourceUserId = ownerId,
        targetDisplayName = (row["displayName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        targetUserId = userId,
        type = if (blocked) "block" else "mute",
    )
}








