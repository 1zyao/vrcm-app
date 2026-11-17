package io.github.vrcmteam.vrcm.network.api.avatars

import io.github.vrcmteam.vrcm.core.extensions.fetchDataList
import io.github.vrcmteam.vrcm.network.api.attributes.AVATARS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarData
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarImpostorQueueStats
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarImpostorServiceStatus
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarSelectionData
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarStyle
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarUpdateData
import io.github.vrcmteam.vrcm.network.api.auth.data.CurrentUserData
import io.github.vrcmteam.vrcm.mobilecore.FavoritesBaselineCache
import io.github.vrcmteam.vrcm.mobilecore.favoriteDetailsHydrate
import io.github.vrcmteam.vrcm.mobilecore.favoriteEntityIds
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.avatarGet
import io.github.vrcmteam.vrcm.mobilecore.avatarStylesGet
import io.github.vrcmteam.vrcm.mobilecore.avatarListByUserGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.serialization.builtins.ListSerializer
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AvatarsApi(private val client: HttpClient) {

    suspend fun getAvatarStyles(): List<AvatarStyle> =
        CoreJson.decodeFromString(
            ListSerializer(AvatarStyle.serializer()),
            vrcxCoreClient().avatarStylesGet().bodyOrThrow(),
        )

    suspend fun getAvatarById(avatarId: String): AvatarData =
        CoreJson.decodeFromString(
            AvatarData.serializer(),
            vrcxCoreClient().avatarGet(avatarId).bodyOrThrow(),
        )

    suspend fun selectAvatar(avatarId: String): AvatarSelectionData =
        client.put("$AVATARS_API_PREFIX/$avatarId/select").checkSuccess()

    suspend fun selectFallbackAvatar(avatarId: String): CurrentUserData =
        client.put("$AVATARS_API_PREFIX/$avatarId/selectFallback").checkSuccess()

    suspend fun deleteImpostor(avatarId: String) {
        client.delete("$AVATARS_API_PREFIX/$avatarId/impostor").checkSuccess { Unit }
    }

    suspend fun updateAvatar(avatarId: String, update: AvatarUpdateData): AvatarData =
        client.put("$AVATARS_API_PREFIX/$avatarId") {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.checkSuccess()

    suspend fun deleteAvatar(avatarId: String): AvatarData =
        client.delete("$AVATARS_API_PREFIX/$avatarId").checkSuccess()

    suspend fun enqueueImpostor(avatarId: String): AvatarImpostorServiceStatus =
        client.post("$AVATARS_API_PREFIX/$avatarId/impostor/enqueue").checkSuccess()

    suspend fun getImpostorQueueStats(): AvatarImpostorQueueStats =
        client.get("$AVATARS_API_PREFIX/impostor/queue/stats").checkSuccess()

    suspend fun getFavoritedAvatars(
        n: Int = 50,
        offset: Int = 0,
        tag: String? = null,
        search: String? = null,
        sort: String? = null,
        order: String? = null,
        featured: Boolean? = null,
    ): List<AvatarData> {
        // The ids come from the favourites snapshot; the details come from the backend's hydrate
        // command, which fetches them on demand (`requestedIds`) instead of asking VRChat here.
        val client = vrcxCoreClient()
        val snapshot = FavoritesBaselineCache.snapshot(client)
        val ids = snapshot.favoriteEntityIds("avatar", tag)
        val details = client.favoriteDetailsHydrate("avatar", ids)
        val rows = ids.mapNotNull { id ->
            details[id]?.let { element ->
                runCatching {
                    CoreJson.decodeFromJsonElement(AvatarData.serializer(), element)
                }.getOrNull()
            }
        }
        val filtered = if (search.isNullOrBlank()) rows
        else rows.filter { it.name.contains(search, ignoreCase = true) }
        return filtered.drop(offset).take(n)
    }

    /**
     * 获取用户的头像列表（分页）
     *
     * @param featured 是否只显示精选头像
     * @param sort 排序方式，默认为更新时间
     * @param user 设置为"me"可搜索自己的头像
     * @param userId 根据用户ID过滤
     * @param n 返回结果数量，默认为50
     * @param order 结果排序顺序，默认为降序
     * @param offset 结果偏移量
     * @param releaseStatus 根据发布状态过滤，可选 public/private/hidden/all
     * @return List<AvatarData> 头像数据列表
     */
    suspend fun getAvatars(
        featured: Boolean? = null,
        sort: String = "updated",
        user: String? = null,
        userId: String? = null,
        n: Int = 50,
        order: String = "descending",
        offset: Int = 0,
        releaseStatus: String? = null,
    ): List<AvatarData> {
        // Served by the backend's avatar list command. `featured` has no counterpart there and no
        // caller here passes it, so it is rejected rather than silently ignored.
        require(featured == null) { "featured filtering is not available through the backend" }
        val result = vrcxCoreClient().avatarListByUserGet(
            userId = userId.orEmpty(),
            user = user.orEmpty(),
            n = n,
            offset = offset,
            sort = sort,
            order = order,
            releaseStatus = releaseStatus ?: "all",
        )
        return CoreJson.decodeFromString(
            ListSerializer(AvatarData.serializer()),
            result.bodyOrThrow(),
        )
    }

    /**
     * 流式获取用户的头像列表（自动分页）
     *
     * @param user 设置为"me"可搜索自己的头像
     * @param userId 根据用户ID过滤
     * @param sort 排序方式
     * @param order 排序顺序
     * @param releaseStatus 发布状态过滤
     * @param n 每页数量
     * @return Flow<List<AvatarData>> 头像数据列表流
     */
    fun avatarsFlow(
        user: String? = null,
        userId: String? = null,
        sort: String = "updated",
        order: String = "descending",
        releaseStatus: String = "all",
        n: Int = 50,
    ): Flow<List<AvatarData>> = flow {
        fetchDataList(offset = 0, n = n) { currentOffset, pageSize ->
            getAvatars(
                user = user,
                userId = userId,
                sort = sort,
                order = order,
                releaseStatus = releaseStatus,
                n = pageSize,
                offset = currentOffset,
            )
        }
    }
}




