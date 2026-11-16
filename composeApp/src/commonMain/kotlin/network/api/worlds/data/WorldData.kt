package io.github.vrcmteam.vrcm.network.api.worlds.data

import io.github.vrcmteam.vrcm.network.api.files.data.PlatformType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A world record.
 *
 * Every field carries a default, for the same reason [UnityPackage] does: the backend hands out
 * the world object from its own record, which is a subset of VRChat's live response (`namespace`,
 * `pluginUrl` and others are simply absent). A missing key is a hard decode error even for a
 * nullable type, and one absent field used to fail the whole instance response - which is why the
 * home screen's room cards lost their world name and cover.
 */
@Serializable
data class WorldData(
    val authorId: String = "",
    val authorName: String = "",
    val capacity: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null,
    val description: String? = null,
    val favorites: Int? = null,
    val featured: Boolean? = null,
    val heat: Int = 0,
    val id: String = "",
    val favoriteId: String? = null,
    val imageUrl: String = "",
    val labsPublicationDate: String = "",
    val name: String = "",
    val namespace: String? = null,
    val organization: String = "",
    val popularity: Int = 0,
    val previewYoutubeId: String? = null,
    val privateOccupants: Int? = null,
    val publicOccupants: Int? = null,
    val publicationDate: String = "",
    val recommendedCapacity: Int = 0,
    val releaseStatus: String = "",
    val tags: List<String> = emptyList(),
    val thumbnailImageUrl: String? = null,
    val udonProducts: List<String> = emptyList(),
    val unityPackages: List<UnityPackage> = emptyList(),
    @SerialName(value = "instances")
    @Serializable(with = InstancesSerializer::class)
    val instances: List<List<String>>? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val version: Int? = null,
    val visits: Int? = null,
    val urlList: List<String>? = emptyList(),
)

/** 世界实际发布过 Unity 包的平台，不受文件元数据查询结果影响。 */
val WorldData.supportedPlatforms: List<PlatformType>
    get() = unityPackages.map { it.platform }.distinct()
