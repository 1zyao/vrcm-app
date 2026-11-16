package io.github.vrcmteam.vrcm.network.api.auth.data

import kotlinx.serialization.Serializable

/**
 * Presence as this app receives it from the VRCX backend.
 *
 * Every field is optional with a default: the backend's user projection is not
 * byte-identical to VRChat's (it omits derived fields such as `avatarThumbnail` and
 * `profilePicOverride`), and kotlinx.serialization requires an explicit default for a
 * missing key even when the type is nullable.
 */
@Serializable
data class Presence(
    val avatarThumbnail: String? = null,
    val displayName: String? = null,
    val groups: List<String> = emptyList(),
    val id: String = "",
    val instance: String = "",
    val instanceType: String = "",
    val isRejoining: String? = null,
    val platform: String = "",
    val profilePicOverride: String? = null,
    val status: String = "",
    val travelingToInstance: String = "",
    val travelingToWorld: String = "",
    val world: String = ""
)
