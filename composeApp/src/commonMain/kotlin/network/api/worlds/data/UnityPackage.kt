package io.github.vrcmteam.vrcm.network.api.worlds.data

import io.github.vrcmteam.vrcm.network.api.files.data.PlatformType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A world's unity package entry.
 *
 * The optional fields carry defaults because the backend serves the world object out of its own
 * record, which omits fields VRChat's live response includes (`pluginUrl` among them) - and a
 * missing key is a hard decode error even for a nullable type. Without these the whole instance
 * response failed to decode and the home screen's room cards lost their world name and cover.
 *
 * `platform` stays required: callers group packages by it, and it is always present.
 */
@Serializable
data class UnityPackage(
    val assetUrl: String? = null,
    val assetVersion: Int? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val id: String? = null,
    val platform: PlatformType,
    val pluginUrl: String? = null,
    val unitySortNumber: Long? = null,
    val unityVersion: String? = null
)
