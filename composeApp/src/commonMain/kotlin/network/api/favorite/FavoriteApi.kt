package io.github.vrcmteam.vrcm.network.api.favorite

import io.github.vrcmteam.vrcm.core.extensions.fetchDataList
import io.github.vrcmteam.vrcm.mobilecore.favoriteDelete
import io.github.vrcmteam.vrcm.mobilecore.favoriteGroupsGet
import io.github.vrcmteam.vrcm.mobilecore.favoriteList
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.socialFavoritesBaseline
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.mobilecore.FavoritesBaselineCache
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
            // Entries come from the backend's favourites baseline, which records them per group
            // key ("world:worlds1"), so the panel can bucket them by name exactly as it does for
            // groups. app__favorite_list was tried first and is the wrong source: its rows carry
            // VRChat's group display name ("Favorites"), so every group counted zero.
            val snapshot = FavoritesBaselineCache.snapshot(vrcxCoreClient())
            emit(snapshot.favoriteEntries(favoriteType))
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
        // Groups come from the baseline, which already carries capacity and count per group -
        // the same numbers the panel renders. The command takes no `type`, so the per-type
        // selection happens here, as it did before.
        return FavoritesBaselineCache.snapshot(vrcxCoreClient()).favoriteGroups(favoriteType)
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
        // The quota is part of the same baseline the groups come from, so this shares the cache
        // instead of issuing a second command.
        val snapshot = FavoritesBaselineCache.snapshot(vrcxCoreClient())
        val limits = snapshot["favoriteLimits"]
            ?: throw VrcxCoreException("noLimits", "the backend returned no favourite limits")
        return CoreJson.decodeFromString(FavoriteLimits.serializer(), limits.toString())
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

/** Baseline field holding this type's groups. */
private fun FavoriteType.baselineGroupsField(): String = when (this) {
    FavoriteType.Avatar -> "favoriteAvatarGroups"
    FavoriteType.World -> "favoriteWorldGroups"
    FavoriteType.Friend -> "favoriteFriendGroups"
}

/**
 * Baseline field holding this type's entries, keyed by group.
 *
 * Worlds and friends come grouped by a `type:name` key; avatars use the local map.
 */
private fun FavoriteType.baselineEntriesField(): String = when (this) {
    FavoriteType.Avatar -> "localAvatarFavorites"
    FavoriteType.World -> "groupedFavoriteWorldIdsByGroupKey"
    FavoriteType.Friend -> "groupedFavoriteFriendIdsByGroupKey"
}

private fun JsonObject.stringField(name: String): String? =
    runCatching { (this[name] as? JsonPrimitive)?.contentOrNull }.getOrNull()

/**
 * Entries as this app's [FavoriteData], tagged with the group NAME.
 *
 * `remoteFavoritesById` holds VRChat's own favourite records - each with `type`, `favoriteId` and
 * the group name in `tags` - so one path serves all three kinds. Earlier this read per-type maps
 * (`localAvatarFavorites`, `groupedFavoriteWorldIdsByGroupKey`), which are local-only views: the
 * avatar tab came out empty because remotely favourited avatars are not in the local map at all.
 */
private fun JsonObject.favoriteEntries(type: FavoriteType): List<FavoriteData> {
    val records = this["remoteFavoritesById"] as? JsonObject ?: return emptyList()
    return records.values.mapNotNull { element ->
        val record = element as? JsonObject ?: return@mapNotNull null
        if (!record.stringField("type").equals(type.value, ignoreCase = true)) return@mapNotNull null
        val entityId = record.stringField("favoriteId") ?: return@mapNotNull null
        val groupName = (record["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.firstOrNull()
        FavoriteData(
            favoriteId = entityId,
            id = entityId,
            tags = listOfNotNull(groupName),
            type = type.value,
        )
    }
}

/**
 * Groups as this app's [FavoriteGroupData].
 *
 * The baseline's group entries have no VRChat group id, but the snapshot also caches the raw
 * group records by id - so the id is recovered by name, which is what the group-edit commands need.
 */
private fun JsonObject.favoriteGroups(type: FavoriteType): List<FavoriteGroupData> {
    val groups = this[type.baselineGroupsField()] as? JsonArray ?: return emptyList()
    val cachedByName = mutableMapOf<String, JsonObject>()
    (this["cachedFavoriteGroupsById"] as? JsonObject)?.forEach { (_, value) ->
        val record = value as? JsonObject ?: return@forEach
        record.stringField("name")?.let { cachedByName[it] = record }
    }
    return groups.mapNotNull { element ->
        val group = element as? JsonObject ?: return@mapNotNull null
        val name = group.stringField("name") ?: return@mapNotNull null
        val cached = cachedByName[name]
        FavoriteGroupData(
            id = cached?.stringField("id").orEmpty(),
            ownerId = cached?.stringField("ownerId")
                ?: this.stringField("currentUserId").orEmpty(),
            type = group.stringField("type") ?: type.value,
            visibility = group.stringField("visibility") ?: "private",
            displayName = group.stringField("displayName") ?: name,
            name = name,
            ownerDisplayName = cached?.stringField("ownerDisplayName").orEmpty(),
            tags = emptyList(),
        )
    }
}



