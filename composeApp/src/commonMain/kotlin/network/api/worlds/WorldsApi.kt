package io.github.vrcmteam.vrcm.network.api.worlds

import io.github.vrcmteam.vrcm.core.extensions.fetchDataList
import io.github.vrcmteam.vrcm.network.api.attributes.WORLDS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceData
import io.github.vrcmteam.vrcm.network.api.worlds.data.FavoritedWorld
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldData
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldPublishStatus
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldUpdateData
import io.github.vrcmteam.vrcm.mobilecore.favoriteWorldsGet
import io.github.vrcmteam.vrcm.mobilecore.instanceGet
import io.github.vrcmteam.vrcm.mobilecore.searchWorldsGet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.mobilecore.worldGet
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

import io.github.vrcmteam.vrcm.mobilecore.worldDelete
import io.github.vrcmteam.vrcm.mobilecore.worldPublish
import io.github.vrcmteam.vrcm.mobilecore.worldUnpublish
import io.github.vrcmteam.vrcm.mobilecore.worldSave
import kotlinx.serialization.json.encodeToJsonElement
class WorldsApi(private val client: HttpClient)  {
    /**
     * 获取单个世界的详细信息
     * 
     * @param worldId 世界ID
     * @return WorldData 世界数据对象
     */
    suspend fun getWorldById(worldId: String): WorldData =
        // Served by the core's world cache. `full = true` keeps what this call site always got
        // (capacity, occupants, instances); the cache-first summary would drop those.
        CoreJson.decodeFromString(
            WorldData.serializer(),
            vrcxCoreClient().worldGet(worldId, full = true).bodyOrThrow(),
        )

    suspend fun getWorldPublishStatus(worldId: String): WorldPublishStatus =
        client.get("$WORLDS_API_PREFIX/$worldId/publish").checkSuccess()

    suspend fun publishWorld(worldId: String) {
        // Through the core (`app__vrchat_world_publish`).
        vrcxCoreClient().worldPublish(worldId).bodyOrThrow()
    }

    suspend fun unpublishWorld(worldId: String) {
        // Through the core (`app__vrchat_world_unpublish`).
        vrcxCoreClient().worldUnpublish(worldId).bodyOrThrow()
    }

    suspend fun deleteWorld(worldId: String) {
        // Through the core (`app__vrchat_world_delete`).
        vrcxCoreClient().worldDelete(worldId).bodyOrThrow()
    }

    suspend fun updateWorld(worldId: String, update: WorldUpdateData): WorldData =
        // Through the core (`app__vrchat_world_save`); `WorldUpdateData` is the same document.
        CoreJson.decodeFromString(
            WorldData.serializer(),
            vrcxCoreClient().worldSave(worldId, CoreJson.encodeToJsonElement(update)).bodyOrThrow(),
        )

    suspend fun getRecentWorlds(
        n: Int = 50,
        offset: Int = 0,
    ): List<WorldData> =
        // Deliberately still VRChat's `/worlds/recent`.
        //
        // Driving this screen from the game log was tried and reverted. The session rows carry the
        // world id and name but no thumbnail or author, so filling a card meant one `app__world_get`
        // per row - and that read falls through to the network on a cache miss, so the page would
        // make fifty VRChat requests instead of one. That is worse than the thing it replaces.
        //
        // The recording-based version waits until the card can be drawn from local data alone, or
        // until the screen shows what the log actually has: name, time, duration and players.
        client.get("$WORLDS_API_PREFIX/recent") {
            parameter("n", n)
            parameter("offset", offset)
        }.checkSuccess()

    suspend fun getWorldInstanceById(worldId: String, instanceId: String): InstanceData =
        // `GET worlds/{worldId}/{instanceId}` and `GET instances/{worldId}:{instanceId}` are the
        // same read; the core exposes the latter as a command.
        CoreJson.decodeFromString(
            InstanceData.serializer(),
            vrcxCoreClient().instanceGet(worldId, instanceId).bodyOrThrow(),
        )
        
    /**
     * 搜索世界
     * 
     * @param search 搜索关键词
     * @param featured 是否只显示精选世界
     * @param sort 排序方式，默认为流行度
     * @param user 设置为"me"可搜索自己的世界
     * @param userId 根据用户ID过滤
     * @param n 返回结果数量，默认为60
     * @param order 结果排序顺序，默认为降序
     * @param offset 结果偏移量
     * @param releaseStatus 根据发布状态过滤
     * @param tag 包含的标签（逗号分隔）
     * @param notag 排除的标签（逗号分隔）
     * @return List<WorldData> 世界数据列表
     */
    suspend fun searchWorld(
        search: String,
        featured: Boolean? = null,
        sort: String = "popularity",
        user: String? = null,
        userId: String? = null,
        n: Int = 60,
        order: String = "descending",
        offset: Int = 0,
        releaseStatus: String? = null,
        tag: String? = null,
        notag: String? = null
    ): List<WorldData> {
        // Served by the backend's world search command. `releaseStatus` is only forwarded when the
        // caller set one: the core answers 403 for `releaseStatus=all` on a search, which is what
        // this app would otherwise send for its "all" filter.
        val params = buildJsonObject {
            put("search", search)
            featured?.let { put("featured", it) }
            put("sort", sort)
            user?.let { put("user", it) }
            userId?.let { put("userId", it) }
            put("n", n)
            put("order", order)
            put("offset", offset)
            releaseStatus?.let { put("releaseStatus", it) }
            tag?.let { put("tag", it) }
            notag?.let { put("notag", it) }
        }
        return CoreJson.decodeFromString(
            ListSerializer(WorldData.serializer()),
            vrcxCoreClient().searchWorldsGet(params).bodyOrThrow(),
        )
    }
        
    /**
     * 获取收藏的世界列表（流式版本）
     * 自动分页获取所有收藏世界数据
     *
     * @param n 每页返回结果数量，默认为50
     * @param offset 偏移量，默认为0
     * @param tag 过滤标签
     * @return Flow<List<FavoritedWorld>> 收藏的世界数据列表流
     */
    fun favoritedWorldsFlow(
        n: Int = 50,
        offset: Int = 0,
        tag: String? = null,
    ): Flow<List<FavoritedWorld>> = flow {
        fetchDataList(offset = offset, n = n) { currentOffset, pageSize ->
            getFavoritedWorlds(
                n = pageSize,
                offset = currentOffset,
                tag = tag,
            )
        }
    }
    
    /**
     * 批量获取完整世界详情（含 description 等搜索API不返回的字段）
     * 使用分批并发请求（每批5个），避免触发 VRChat API 429 限流
     *
     * @param worldIds 世界ID列表
     * @return List<WorldData> 成功获取的世界数据列表（失败的会被跳过）
     */
    suspend fun fetchWorldsByIds(worldIds: List<String>): List<WorldData> {
        return coroutineScope {
            worldIds.chunked(5).flatMap { chunk ->
                chunk.map { id ->
                    async { runCatching { getWorldById(id) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
        }
    }

    /**
     * 流式获取用户创建的世界列表（自动分页）
     *
     * @param user 设置为 "me" 可查询自己的世界
     * @param userId 根据用户ID过滤
     * @param sort 排序方式，默认为更新时间
     * @param order 排序顺序，默认为降序
     * @param releaseStatus 发布状态过滤
     * @param n 每页数量
     * @return Flow<List<WorldData>> 世界数据列表流
     */
    fun userWorldsFlow(
        userId: String? = null,
        user: String? = null,
        sort: String = "updated",
        order: String = "descending",
        releaseStatus: String = "public",
        n: Int = 50,
    ): Flow<List<WorldData>> = flow {
        fetchDataList(offset = 0, n = n) { currentOffset, pageSize ->
            searchWorld(
                search = "",
                sort = sort,
                user = user,
                userId = userId,
                n = pageSize,
                order = order,
                offset = currentOffset,
                releaseStatus = releaseStatus,
            )
        }
    }

    suspend fun getFavoritedWorlds(
        featured: Boolean? = null,
        sort: String? = null,
        n: Int = 50,
        order: String? = null,
        offset: Int? = null,
        search: String? = null,
        tag: String? = null,
        notag: String? = null,
        releaseStatus: String? = null,
        maxUnityVersion: String? = null,
        minUnityVersion: String? = null,
        platform: String? = null,
        userId: String? = null,
        ownerId: String? = null
    ): List<FavoritedWorld> {
        // Served by the backend's favourite-worlds command, which takes an owner and a group tag -
        // so this covers both the signed-in user's favourites and another user's.
        //
        // The command supports the parameters below and nothing else. The rest of the signature is
        // kept so existing call sites compile, but a caller that starts using one of the
        // unsupported filters gets a loud failure instead of quietly unfiltered results.
        val unsupported = listOfNotNull(
            featured?.let { "featured" },
            sort?.let { "sort" },
            order?.let { "order" },
            search?.let { "search" },
            notag?.let { "notag" },
            releaseStatus?.let { "releaseStatus" },
            maxUnityVersion?.let { "maxUnityVersion" },
            minUnityVersion?.let { "minUnityVersion" },
            platform?.let { "platform" },
        )
        require(unsupported.isEmpty()) {
            "favourite worlds cannot be filtered by ${unsupported.joinToString()} through the backend"
        }
        return CoreJson.decodeFromString(
            ListSerializer(FavoritedWorld.serializer()),
            vrcxCoreClient().favoriteWorldsGet(
                n = n,
                offset = offset ?: 0,
                ownerId = ownerId.orEmpty(),
                userId = userId.orEmpty(),
                tag = tag.orEmpty(),
            ).bodyOrThrow(),
        )
    }
}












