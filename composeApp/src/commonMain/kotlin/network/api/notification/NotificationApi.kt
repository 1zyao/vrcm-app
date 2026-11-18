package io.github.vrcmteam.vrcm.network.api.notification

import io.github.vrcmteam.vrcm.network.api.attributes.*
import io.github.vrcmteam.vrcm.network.api.notification.data.NotificationData
import io.github.vrcmteam.vrcm.network.api.notification.data.NotificationDataV2
import io.github.vrcmteam.vrcm.network.api.notification.data.RespondNotificationRequest
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.notificationListQuery
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.github.vrcmteam.vrcm.presentation.screens.home.data.NotificationItemData
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

class NotificationApi(
    private val client: HttpClient,
) {

    suspend fun fetchNotifications(limit: Int = 100): List<NotificationData> =
        // Read from the core's database, which holds the synced notification history, instead of
        // asking VRChat. The rows are VRCX's own projection, so they are mapped onto this app's
        // model here rather than decoded from VRChat's schema.
        notificationRows(limit = limit, filters = emptyList()).mapNotNull { it.toNotificationData() }


    suspend fun responseNotification(id: String, response: NotificationItemData.ActionData): String =
        client.post("$NOTIFICATIONS_API_PREFIX/$id/respond") {
            contentType(ContentType.Application.Json)
            setBody(
                RespondNotificationRequest(
                    responseData = response.data,
                    responseType = response.type,
                ),
            )
        }.checkSuccess { bodyAsText() }


    suspend fun fetchNotificationsV2(
        type: String = NotificationType.All.value,
        // the only type you can see hidden content on is friendRequest
        hidden: Boolean = false,
        n: Int = 100,
        offset: Int = 0,
    ): List<NotificationDataV2> =
        // Same local read; the type becomes a query filter (the database has no "hidden" flag).
        notificationRows(
            limit = n,
            filters = if (type == NotificationType.All.value) emptyList() else listOf(type),
        ).mapNotNull { it.toNotificationDataV2() }



    suspend fun acceptFriendRequest(notificationId: String): VRChatResponse =
        client.put {
            url {
                path(
                    AUTH_API_PREFIX,
                    USER_API_PREFIX,
                    NOTIFICATIONS_API_PREFIX,
                    notificationId,
                    "accept"
                )
            }
        }.checkSuccess()



    /** Deletes a notification returned by the current `/notifications` API. */
    suspend fun deleteNotificationV2(notificationId: String) {
        client.delete {
            url { path(NOTIFICATIONS_API_PREFIX, notificationId) }
        }.checkSuccess<Unit>()
    }

    /** Hides a notification returned by the legacy `/auth/user/notifications` API. */
    suspend fun deleteNotification(notificationId: String): VRChatResponse =
        client.put {
            url {
                path(
                    AUTH_API_PREFIX,
                    USER_API_PREFIX,
                    NOTIFICATIONS_API_PREFIX,
                    notificationId,
                    "hide"
                )
            }
        }.checkSuccess()


    suspend fun clearAllNotifications(): VRChatResponse =
        client.put {
            url { path(AUTH_API_PREFIX, USER_API_PREFIX, NOTIFICATIONS_API_PREFIX, "clear") }
        }.checkSuccess()

    /** Raw rows from the core's notification list query. */
    private suspend fun notificationRows(limit: Int, filters: List<String>): List<JsonObject> {
        val userId = io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
            .currentSession.value?.account?.userId
            ?: throw VrcxCoreException("noSession", "no authenticated session for notifications")
        val rows = vrcxCoreClient().notificationListQuery(userId, limit, filters)
        return runCatching { rows.jsonArray }.getOrDefault(emptyList())
            .mapNotNull { it as? JsonObject }
    }

}

/** One row of VRCX's notification projection, as this app's [NotificationData]. */
private fun JsonObject.toNotificationData(): NotificationData? {
    val id = stringOrNull("id") ?: return null
    return NotificationData(
        canDelete = false,
        category = "",
        createdAt = stringOrNull("createdAt") ?: stringOrNull("created_at").orEmpty(),
        data = runCatching {
            CoreJson.decodeFromJsonElement(
                NotificationData.Data.serializer(),
                this["data"] ?: JsonObject(emptyMap()),
            )
        }.getOrDefault(NotificationData.Data()),
        details = runCatching {
            CoreJson.decodeFromJsonElement(
                NotificationData.Data.serializer(),
                this["details"] ?: JsonObject(emptyMap()),
            )
        }.getOrNull(),
        expiresAt = stringOrNull("expiresAt").orEmpty(),
        expiryAfterSeen = null,
        id = id,
        ignoreDND = false,
        imageUrl = stringOrNull("imageUrl"),
        isSystem = false,
        link = stringOrNull("link"),
        linkText = stringOrNull("linkText"),
        linkTextKey = null,
        message = stringOrNull("message").orEmpty(),
        messageKey = null,
        receiverUserId = stringOrNull("receiverUserId").orEmpty(),
        relatedNotificationsId = null,
        requireSeen = false,
        responses = emptyList(),
        seen = booleanOrNull("seen") ?: false,
        senderUserId = stringOrNull("senderUserId"),
        senderUsername = stringOrNull("senderUsername"),
        title = stringOrNull("title"),
        titleKey = null,
        type = stringOrNull("type").orEmpty(),
        updatedAt = stringOrNull("updatedAt").orEmpty(),
        version = intOrNull("version") ?: 1,
    )
}

/** Same row as [NotificationDataV2], whose `details` is a serialized string. */
private fun JsonObject.toNotificationDataV2(): NotificationDataV2? {
    val id = stringOrNull("id") ?: return null
    val rawType = stringOrNull("type").orEmpty()
    return NotificationDataV2(
        createdAt = stringOrNull("createdAt") ?: stringOrNull("created_at").orEmpty(),
        details = (this["details"] ?: JsonObject(emptyMap())).toString(),
        id = id,
        message = stringOrNull("message").orEmpty(),
        seen = booleanOrNull("seen") ?: false,
        senderUserId = stringOrNull("senderUserId").orEmpty(),
        receiverUserId = stringOrNull("receiverUserId"),
        type = NotificationType.entries
            .firstOrNull { it.value.equals(rawType, ignoreCase = true) }
            ?: NotificationType.All,
        // Already joined onto the row by VRCX's database; without it the UI has to look the user up.
        senderUsername = stringOrNull("senderUsername")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    runCatching { (this[key] as? JsonPrimitive)?.contentOrNull }.getOrNull()

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    runCatching { (this[key] as? JsonPrimitive)?.booleanOrNull }.getOrNull()

private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { (this[key] as? JsonPrimitive)?.intOrNull }.getOrNull()

