package io.github.vrcmteam.vrcm.network.api.invite

import io.github.vrcmteam.vrcm.network.api.attributes.INVITE_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.REQUEST_INVITE_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.VRChatResponse
import io.github.vrcmteam.vrcm.network.api.invite.data.InviteMessageData
import io.github.vrcmteam.vrcm.network.api.invite.data.InviteMessageType
import io.github.vrcmteam.vrcm.network.api.invite.data.InviteMyselfData
import io.github.vrcmteam.vrcm.network.api.invite.data.InviteResponseRequest
import io.github.vrcmteam.vrcm.network.api.invite.data.InviteUserRequest
import io.github.vrcmteam.vrcm.network.api.invite.data.RequestInviteRequest
import io.github.vrcmteam.vrcm.network.api.invite.data.UpdateInviteMessageRequest
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.mobilecore.instanceInviteSend
import io.github.vrcmteam.vrcm.mobilecore.inviteResponseSend
import io.github.vrcmteam.vrcm.mobilecore.instanceSelfInvite
import io.github.vrcmteam.vrcm.mobilecore.requestInviteSend
import io.github.vrcmteam.vrcm.mobilecore.inviteMessagesGet
import io.github.vrcmteam.vrcm.mobilecore.inviteMessageEdit
class InviteApi(private val client: HttpClient) {

    suspend fun inviteUser(
        userId: String,
        instanceId: String,
        messageSlot: Int = 0,
        worldName: String = "",
    ): VRChatResponse {
        requireValidSlot(messageSlot)
        requireValidInviteLocation(instanceId)
        // Through the social notification chain, like the desktop: the invite is sent and the local
        // notification row is written in the same step.
        vrcxCoreClient()
            .instanceInviteSend(
                receiverUserId = userId,
                location = instanceId,
                worldName = worldName,
                messageSlot = messageSlot,
            )
            .bodyOrThrow()
        // `VRChatResponse` is VRChat's error/success envelope. The core command already raised on failure,
        // so reaching here means the chain accepted the invite.
        return VRChatResponse(
            success = VRChatResponse.VRChatResult(message = "", statusCode = 200),
        )
    }

    /**
     * Sends a Gallery PNG as an invite attachment.
     *
     * Through the same social chain as [inviteUser]: the chain hands `imageData` to the core's media
     * preprocessor, so the bytes travel as the data URL it expects (`data:image/png;base64,…`) instead of a
     * multipart body assembled here.
     */
    suspend fun inviteUserWithPhoto(
        userId: String,
        instanceId: String,
        imageBytes: ByteArray,
        messageSlot: Int = 0,
        worldName: String = "",
    ) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        requireValidInviteLocation(instanceId)
        require(messageSlot in INVITE_MESSAGE_SLOT_RANGE) { "messageSlot must be between 0 and 11" }
        require(imageBytes.hasPngSignature()) { "Invite image must be a PNG file" }
        require(imageBytes.size <= MAX_INVITE_IMAGE_BYTES) {
            "Invite image exceeds $MAX_INVITE_IMAGE_BYTES bytes"
        }

        vrcxCoreClient()
            .instanceInviteSend(
                receiverUserId = userId,
                location = instanceId,
                worldName = worldName,
                messageSlot = messageSlot,
                imageData = "data:image/png;base64," + Base64.Default.encode(imageBytes),
            )
            .bodyOrThrow()
    }

    /**
     * Invites the signed-in user into `instanceId` (a full location).
     *
     * Through the core's `app__vrchat_instance_self_invite`: that command takes world and instance
     * separately and answers in the `{status, data}` envelope, so the location is split here and the body
     * decoded.
     */
    suspend fun inviteMyselfToInstance(instanceId: String): InviteMyselfData =
        vrcxCoreClient().instanceSelfInvite(instanceId).bodyOrThrow().let { body ->
            Json.decodeFromString<InviteMyselfData>(body)
        }

    suspend fun requestInvite(userId: String, requestSlot: Int = 0) {
        requireValidSlot(requestSlot)
        // `app__vrchat_request_invite_send`; the envelope's body is empty on success.
        vrcxCoreClient().requestInviteSend(userId, requestSlot).bodyOrThrow()
    }

    /** Responds to an invite or request-invite notification with a PNG from Gallery. */
    /**
     * Responds to an invite notification with a PNG from Gallery.
     *
     * Through the social chain, which needs the notification target. The desktop builds that from the row
     * itself (`toNotificationTarget`), so callers pass the row's type and sender rather than the id alone -
     * inventing them would mark the wrong notification resolved.
     */
    suspend fun respondInviteWithPhoto(
        notificationId: String,
        notificationType: String,
        senderUserId: String,
        responseSlot: Int,
        imageBytes: ByteArray,
    ): String {
        require(notificationId.isNotBlank()) { "notificationId must not be blank" }
        require(responseSlot in INVITE_MESSAGE_SLOT_RANGE) {
            "responseSlot must be between 0 and 11"
        }
        require(imageBytes.hasPngSignature()) { "Invite response image must be a PNG file" }

        val ownerUserId = io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
            .currentSession.value?.account?.userId
            ?: throw io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException(
                "noSession",
                "no authenticated session for invite response",
            )
        return vrcxCoreClient()
            .inviteResponseSend(
                ownerUserId = ownerUserId,
                notificationId = notificationId,
                notificationType = notificationType,
                senderUserId = senderUserId,
                responseSlot = responseSlot,
                imageData = "data:image/png;base64," + Base64.Default.encode(imageBytes),
            )
            .bodyOrThrow()
    }

    suspend fun getInviteMessages(
        userId: String,
        messageType: InviteMessageType,
    ): List<InviteMessageData> {
        requireValidUserId(userId)
        // `app__vrchat_tools_invite_messages_get`.
        return vrcxCoreClient().inviteMessagesGet(userId, messageType.pathValue)
            .bodyOrThrow()
            .let { body -> Json.decodeFromString<List<InviteMessageData>>(body) }
            .also { validateInviteMessages(it, messageType) }
    }

    private fun validateInviteMessages(
        messages: List<InviteMessageData>,
        requestedType: InviteMessageType,
    ) {
        val slots = mutableSetOf<Int>()
        messages.forEach { message ->
            check(message.messageType == requestedType) {
                "Invite message response type does not match the requested collection"
            }
            check(message.slot in INVITE_MESSAGE_SLOT_RANGE) {
                "Invite message response contains an out-of-range slot"
            }
            check(slots.add(message.slot)) {
                "Invite message response contains duplicate slots"
            }
        }
    }

    suspend fun updateInviteMessage(
        userId: String,
        messageType: InviteMessageType,
        slot: Int,
        message: String,
    ): List<InviteMessageData> {
        requireValidUserId(userId)
        requireValidSlot(slot)
        require(message.isNotBlank()) { "Invite message must not be blank" }
        require(message.inviteMessageCodePointCount() <= MAX_INVITE_MESSAGE_CODE_POINTS) {
            "Invite message must not exceed $MAX_INVITE_MESSAGE_CODE_POINTS characters"
        }
        // `app__vrchat_tools_invite_message_edit`.
        return vrcxCoreClient()
            .inviteMessageEdit(userId, messageType.pathValue, slot, message)
            .bodyOrThrow()
            .let { body -> Json.decodeFromString<List<InviteMessageData>>(body) }
            .also { validateInviteMessages(it, messageType) }
    }

    suspend fun resetInviteMessage(
        userId: String,
        messageType: InviteMessageType,
        slot: Int,
    ): List<InviteMessageData> {
        requireValidUserId(userId)
        requireValidSlot(slot)
        return client.delete("message/$userId/${messageType.pathValue}/$slot")
            .checkSuccess<List<InviteMessageData>>()
            .also { validateInviteMessages(it, messageType) }
    }

    private fun requireValidUserId(userId: String) {
        require(ID_PATTERN.matches(userId)) { "Invalid user ID" }
    }

    private fun requireValidSlot(slot: Int) {
        require(slot in INVITE_MESSAGE_SLOT_RANGE) { "Invalid invite message slot" }
    }

    companion object {
        const val MAX_INVITE_MESSAGE_CODE_POINTS = 64
        private const val MAX_INVITE_IMAGE_BYTES = 10_000_000
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val INVITE_MESSAGE_SLOT_RANGE = 0..11
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }

    private fun ByteArray.hasPngSignature(): Boolean = size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }
}

@Serializable
private data class InvitePhotoRequest(
    val instanceId: String,
    val messageSlot: Int,
)

internal fun String.inviteMessageCodePointCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val current = this[index]
        val isSurrogatePair = current in '\uD800'..'\uDBFF' &&
            index + 1 < length && this[index + 1] in '\uDC00'..'\uDFFF'
        index += if (isSurrogatePair) 2 else 1
        count++
    }
    return count
}
