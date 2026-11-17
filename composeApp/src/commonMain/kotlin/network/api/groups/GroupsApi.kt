package io.github.vrcmteam.vrcm.network.api.groups

import io.github.vrcmteam.vrcm.network.api.attributes.GROUPS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.USERS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupData
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupGalleryImage
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupInstancesResponse
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupMember
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupPostData
import io.github.vrcmteam.vrcm.network.api.groups.data.JoinGroupRequest
import io.github.vrcmteam.vrcm.network.api.groups.data.LimitedGroup
import io.github.vrcmteam.vrcm.network.api.groups.data.UpdateGroupMemberNotificationsRequest
import io.github.vrcmteam.vrcm.network.api.groups.data.UpdateGroupRepresentationRequest
import kotlinx.serialization.builtins.ListSerializer
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.groupJoin
import io.github.vrcmteam.vrcm.mobilecore.groupLeave
import io.github.vrcmteam.vrcm.mobilecore.groupMembersGet
import io.github.vrcmteam.vrcm.mobilecore.groupGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*

class GroupsApi(private val client: HttpClient) {

    suspend fun fetchGroup(groupId: String, includeRoles: Boolean = false, purpose: String = "other") =
        // Served by the backend's own group read instead of a request from this app.
        CoreJson.decodeFromString(
            GroupData.serializer(),
            vrcxCoreClient().groupGet(groupId, includeRoles).bodyOrThrow(),
        )

    suspend fun searchGroups(
        query: String? = null,
        n: Int = 20,
        offset: Int = 0,
    ): List<LimitedGroup> =
        client.get(GROUPS_API_PREFIX) {
            query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            parameter("n", n)
            parameter("offset", offset)
        }.checkSuccess()

    suspend fun joinGroup(
        groupId: String,
        inviteId: String? = null,
        confirmOverrideBlock: Boolean? = null,
    ): GroupMember {
        // Served by the backend's join command. It has no invite or block-override parameter and no
        // caller here passes either, so both are rejected rather than silently ignored - joining via
        // an invite must not quietly become a plain join.
        require(inviteId == null) { "joining through an invite is not available through the backend" }
        require(confirmOverrideBlock == null) {
            "confirmOverrideBlock is not available through the backend"
        }
        return CoreJson.decodeFromString(
            GroupMember.serializer(),
            vrcxCoreClient().groupJoin(groupId).bodyOrThrow(),
        )
    }

    suspend fun leaveGroup(groupId: String) {
        // The backend answers with VRChat's response envelope; this call site has always discarded it.
        vrcxCoreClient().groupLeave(groupId)
    }

    suspend fun updateRepresentation(groupId: String, isRepresenting: Boolean) =
        client.put("$GROUPS_API_PREFIX/$groupId/representation") {
            contentType(ContentType.Application.Json)
            setBody(UpdateGroupRepresentationRequest(isRepresenting))
        }.checkSuccess { Unit }

    suspend fun updateGroupNotifications(
        groupId: String,
        userId: String,
        enabled: Boolean,
    ): GroupMember =
        client.put("$GROUPS_API_PREFIX/$groupId/members/$userId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateGroupMemberNotificationsRequest(enabled))
        }.checkSuccess()

    suspend fun getGroupMembers(
        groupId: String,
        n: Int = 20,
        offset: Int = 0,
        sort: String? = null,
        roleId: String? = null,
    ): List<GroupMember> {
        // Served by the backend's group members command. Its `sort` is an enum with two values
        // (`joinedAt:asc` / `joinedAt:desc`), so an unexpected value fails there with a readable
        // error instead of returning an arbitrarily ordered page.
        return CoreJson.decodeFromString(
            ListSerializer(GroupMember.serializer()),
            vrcxCoreClient().groupMembersGet(
                groupId = groupId,
                n = n,
                offset = offset,
                sort = sort ?: "joinedAt:desc",
                roleId = roleId.orEmpty(),
            ).bodyOrThrow(),
        )
    }

    suspend fun getGroupGalleryImages(
        groupId: String,
        groupGalleryId: String,
        n: Int = 20,
        offset: Int = 0,
        approved: Boolean? = null,
    ): List<GroupGalleryImage> =
        client.get("$GROUPS_API_PREFIX/$groupId/galleries/$groupGalleryId") {
            parameter("n", n)
            parameter("offset", offset)
            approved?.let { parameter("approved", it) }
        }.checkSuccess()

    suspend fun getGroupPosts(
        groupId: String,
        n: Int = 100,
        offset: Int = 0,
    ): GroupPostData =
        client.get("$GROUPS_API_PREFIX/$groupId/posts") {
            parameter("n", n)
            parameter("offset", offset)
        }.checkSuccess()

    suspend fun getGroupInstances(
        userId: String,
        groupId: String,
    ): GroupInstancesResponse =
        client.get("$USERS_API_PREFIX/$userId/instances/groups/$groupId")
            .checkSuccess()

    /** 自己加入的所有群组当前开着的房间，只有本人能查。 */
    suspend fun getUserGroupInstances(userId: String): GroupInstancesResponse =
        client.get("$USERS_API_PREFIX/$userId/instances/groups")
            .checkSuccess()

}





