package io.github.vrcmteam.vrcm.network.api.auth.data

import io.github.vrcmteam.vrcm.network.api.attributes.AgeVerificationStatus
import io.github.vrcmteam.vrcm.network.api.attributes.IUser
import io.github.vrcmteam.vrcm.network.api.attributes.UserStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Current user as the VRCX backend hands it over.
 *
 * Every field carries a default. The backend's projection is VRChat-shaped but not
 * byte-identical: it omits derived fields (`currentAvatarAssetUrl`, `avatarThumbnail`,
 * `profilePicOverride`, …), and kotlinx.serialization treats a missing key as an error
 * even when the type is nullable. Defaulting them keeps this DTO usable against the
 * backend without rewriting it into a second schema.
 */
@Serializable
data class CurrentUserData(
    val ageVerificationStatus: AgeVerificationStatus = AgeVerificationStatus.Hidden,
    val ageVerified: Boolean = false,
    val acceptedPrivacyVersion: Int = 0,
    val acceptedTOSVersion: Int = 0,
    val accountDeletionDate: String? = null,
    val accountDeletionLog: List<AccountDeletionLog>? = null,
    val activeFriends: List<String> = emptyList(),
    val allowAvatarCopying: Boolean = false,
    override val bio: String? = null,
    /** Optional on the auth response; profile links are not guaranteed by the public user schema. */
    override val bioLinks: List<String> = emptyList(),
    val currentAvatar: String = "",
    val currentAvatarAssetUrl: String? = null,
    override val currentAvatarImageUrl: String = "",
    override val currentAvatarTags: List<String> = emptyList(),
    override val currentAvatarThumbnailImageUrl: String? = null,
    @SerialName("date_joined")
    val dateJoined: String = "",
    override val developerType: String = "",
    override val displayName: String = "",
    val emailVerified: Boolean = false,
    val fallbackAvatar: String = "",
    val friendGroupNames: List<String> = emptyList(),
    val friendKey: String = "",
    val friends: List<String> = emptyList(),
    val googleId: String = "",
    val hasBirthday: Boolean = false,
    val hasEmail: Boolean = false,
    val hasLoggedInFromClient: Boolean = false,
    val hasPendingEmail: Boolean = false,
    val hideContentFilterSettings: Boolean = false,
    val homeLocation: String = "",
    override val id: String = "",
    override val isFriend: Boolean = false,
    val isBoopingEnabled: Boolean? = null,
    @SerialName("last_activity")
    override val lastActivity: String = "",
    @SerialName("last_login")
    override val lastLogin: String = "",
    @SerialName("last_platform")
    override val lastPlatform: String = "",
    val obfuscatedEmail: String = "",
    val obfuscatedPendingEmail: String = "",
    val oculusId: String = "",
    val offlineFriends: List<String> = emptyList(),
    val onlineFriends: List<String> = emptyList(),
    val pastDisplayNames: List<PastDisplayName> = emptyList(),
    val picoId: String = "",
    val presence: Presence = Presence(),
    /** Optional on the auth response; [IUser.profileImageUrl] falls back to the current avatar. */
    override val profilePicOverride: String = "",
    /** Canonical profile icon returned by the post-PR user schema. */
    @SerialName("iconUrl")
    val profileIconUrl: String = "",
    val state: String = "",
    override val status: UserStatus = UserStatus.Offline,
    override val statusDescription: String = "",
    val statusFirstTime: Boolean = false,
    val statusHistory: List<String> = emptyList(),
    val steamDetails: SteamDetails = SteamDetails(),
    val steamId: String = "",
    override val tags: List<String> = emptyList(),
    val twoFactorAuthEnabled: Boolean = false,
    val twoFactorAuthEnabledDate: String? = null,
    val unsubscribe: Boolean = false,
    @SerialName("updated_at")
    val updatedAt: String = "",
    /** Optional on the auth response; [IUser.iconUrl] falls back to the profile image. */
    override val userIcon: String = "",
    val userLanguage: String? = null,
    val userLanguageCode: String? = null,
    val username: String = "",
    val viveId: String = "",
    override val pronouns: String? = null,
    val bannerType: String? = null,
    val bannerUrl: String? = null,
) : IUser {
    override val iconUrl: String
        get() = profileIconUrl.ifBlank { userIcon.ifBlank { profileImageUrl } }

    override val location: String
        get() = presenceLocation(presence.world, presence.instance)
}

internal fun presenceLocation(world: String, instance: String): String = when {
    instance.startsWith("wrld_") -> instance
    world.startsWith("wrld_") && instance.isNotBlank() && instance != "offline" -> "$world:$instance"
    else -> instance
}
