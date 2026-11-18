package io.github.vrcmteam.vrcm.network.api.notification.data

import io.github.vrcmteam.vrcm.network.api.attributes.NotificationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDataV2(
    @SerialName("created_at")
    val createdAt: String,
    val details: String,
    val id: String,
    val message: String,
    val seen: Boolean,
    val senderUserId: String,
    val receiverUserId: String?,
    val type: NotificationType,
    /**
     * The sender's name as the core stored it (`sender_username`).
     *
     * VRChat's own payload has no such field - it carries an id and the client is expected to look the
     * user up. VRCX's database already joins the name onto the row, which is why the desktop can render
     * "Alice sent you a friend request" without a second request. Default keeps decoding of a real
     * VRChat payload working.
     */
    val senderUsername: String? = null,
)