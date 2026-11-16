package io.github.vrcmteam.vrcm.network.api.favorite

import io.github.vrcmteam.vrcm.core.extensions.fetchDataList
import io.github.vrcmteam.vrcm.mobilecore.favoriteDelete
import io.github.vrcmteam.vrcm.mobilecore.favoriteGroupsGet
import io.github.vrcmteam.vrcm.mobilecore.favoriteList
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.socialFavoritesBaseline
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import io.github.vrcmteam.vrcm.network.api.attributes.*
import io.github.vrcmteam.vrcm.network.api.favorite.data.AddFavoriteRequest
import io.github.vrcmteam.vrcm.network.api.favorite.data.FavoriteData
import io.github.vrcmteam.vrcm.network.api.favorite.data.FavoriteGroupData
import io.github.vrcmteam.vrcm.network.api.favorite.data.FavoriteLimits
import io.github.vrcmteam.vrcm.network.api.favorite.data.UpdateFavoriteGroupRequest
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toCollection

class FavoriteApi(private val client: HttpClient) {

    fun fetchFavorite(
        favoriteType: FavoriteType,
        offset: Int = 0,
        n: Int = 50,
    ): Flow<List<FavoriteData>> = flow {
            // Temporarily back on VRChat's endpoint. The backend's app__favorite_list rows
            // carry VRChat's group display name ("Favorites") rather than the group keys the
            // panel buckets by ("worlds1"), so grouping and per-group counts came out wrong
            // (0/100 for every group). The grouped form with key/count/capacity lives in the
            // social baseline; this flips again once app__social_favorites_baseline_get lands.
            fetchDataList(offset, n) { currentOffset, _ ->
                client.get(FAVORITES_API_PREFIX) {
                    parameters {
                        parameter("type", favoriteType.value)
                        parameter("offset", currentOffset.toString())
                        parameter("n", n.toString())
                    }
                }.body()
            }
        }
        
    /**
     * 添加收藏
     *
     * @param favoriteId 收藏目标ID，可以是世界ID、用户ID或头像ID
     * @param favoriteType 收藏类型
     * @param tag 标签，对于世界收藏可以是"worlds1"到"worlds4"
     * @return 返回添加的收藏数据
     */
    suspend fun addFavorite(
        favoriteId: String,
        favoriteType: FavoriteType,
        tag: String
    ): FavoriteData {
        val request = AddFavoriteRequest(
            type = favoriteType.value,
            favoriteId = favoriteId,
            tags = listOf(tag)
        )
        
        return client.post(FAVORITES_API_PREFIX) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.checkSuccess()
    }

    
    /**
     * 获取指定类型的收藏组列表
     *
     * @param favoriteType 收藏类型
     * @param n 返回的对象数量，默认60，最大100
     * @param offset 偏移量，从0开始
     * @param ownerId 获取指定用户的收藏组，留空表示获取当前用户的收藏组
     * @return 指定类型的收藏组列表
     */
    suspend fun getFavoriteGroupsByType(
        favoriteType: FavoriteType,
        n: Int = 50,
        offset: Int = 0,
        ownerId: String? = null
    ): List<FavoriteGroupData> {
        // Served by the backend. Its command takes no `type`, so the filter that this
        // method's name promises happens here instead.
        val result = vrcxCoreClient().favoriteGroupsGet(
            n = n.coerceIn(1, 100),
            offset = offset,
            ownerId = ownerId.orEmpty(),
        )
        return CoreJson
            .decodeFromString(ListSerializer(FavoriteGroupData.serializer()), result.bodyOrThrow())
            .filter { it.type.equals(favoriteType.value, ignoreCase = true) }
    }

    private suspend fun  FlowCollector<List<FavoriteGroupData>>.fetchFavoriteGroupList(
        favoriteType: FavoriteType,
        n: Int = 50,
        offset: Int = 0,
        ownerId: String? = null
    ){
        val limitedN = n.coerceIn(1, 100)
        fetchDataList(offset, limitedN){ currentOffset , _ ->
            client.get("$FAVORITE_API_PREFIX/groups") {
                // 限制n的范围为1-100
                parameters {
                    parameter("n", limitedN.toString())
                    parameter("offset", currentOffset.toString())
                    parameter("type", favoriteType.value)
                    if (ownerId != null) {
                        parameter("ownerId", ownerId)
                    }
                }
            }.checkSuccess<List<FavoriteGroupData>>()
        }
    }

    /**
     * 删除收藏
     *
     * @param favoriteId 收藏记录 ID（FavoriteData.id）
     */
    suspend fun deleteFavorite(favoriteId: String) {
        // favouriteId here is the entity id (world/avatar/user), which is exactly what the
        // backend's delete takes - same addressing as VRChat's own endpoint.
        vrcxCoreClient().favoriteDelete(favoriteId)
    }

    /** Clears every favorite relationship in an owned official group without deleting the group. */
    suspend fun clearFavoriteGroup(
        favoriteType: FavoriteType,
        favoriteGroupName: String,
        userId: String,
    ) {
        client.delete {
            url {
                path(
                    FAVORITE_API_PREFIX,
                    "group",
                    favoriteType.value,
                    favoriteGroupName,
                    userId,
                )
            }
        }.checkSuccess { Unit }
    }

    /**
     * 获取收藏限制信息
     * 
     * @return 返回用户的收藏限制数据
     */
    suspend fun getFavoriteLimits(): FavoriteLimits {
        // The quota is part of the backend's favourites baseline - the core resolves VRChat's
        // limits itself and stores them - so this app no longer asks VRChat for them.
        val userId = io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
            .currentSession.value?.account?.userId
            ?: throw VrcxCoreException("noSession", "no authenticated session for favourite limits")
        val baseline = vrcxCoreClient().socialFavoritesBaseline(userId)
        val limits = baseline.jsonObject["snapshot"]?.jsonObject?.get("favoriteLimits")
            ?: throw VrcxCoreException("noLimits", "the backend returned no favourite limits")
        return Json { ignoreUnknownKeys = true }
            .decodeFromString(FavoriteLimits.serializer(), limits.toString())
    }

    suspend fun updateFavoriteGroup(
        favoriteType: FavoriteType,
        favoriteGroupName: String,
        userId: String,
        displayName: String,
        visibility: FavoriteGroupVisibility,
    ) {
        client.put {
            url {
                path(
                    FAVORITE_API_PREFIX,
                    "group",
                    favoriteType.value,
                    favoriteGroupName,
                    userId,
                )
            }
            contentType(ContentType.Application.Json)
            setBody(UpdateFavoriteGroupRequest(displayName, visibility))
        }.checkSuccess { Unit }
    }

}


