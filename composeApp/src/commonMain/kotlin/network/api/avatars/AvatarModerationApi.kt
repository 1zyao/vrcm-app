package io.github.vrcmteam.vrcm.network.api.avatars

import io.github.vrcmteam.vrcm.mobilecore.avatarModerationDelete
import io.github.vrcmteam.vrcm.mobilecore.avatarModerationSend
import io.github.vrcmteam.vrcm.mobilecore.avatarModerationsGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarModerationData
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Avatar blocking, through the core's `app__vrchat_avatar_moderation_*` commands.
 *
 * The three endpoints (`GET`/`POST`/`DELETE auth/user/avatarmoderations`) are what the desktop exposes as
 * `app__vrchat_avatar_moderations_get`, `_send` and `_delete`. The core builds those requests from the
 * protocol, so nothing here assembles HTTP any more; `client` stays only because the DI graph constructs
 * this class with it.
 */
internal class AvatarModerationApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAvatarModerations(): List<AvatarModerationData> =
        vrcxCoreClient().avatarModerationsGet().bodyOrThrow()
            .let { body -> json.decodeFromString<List<AvatarModerationData>>(body) }

    suspend fun blockAvatar(avatarId: String): AvatarModerationData =
        vrcxCoreClient().avatarModerationSend(avatarId).bodyOrThrow()
            .let { body -> json.decodeFromString<AvatarModerationData>(body) }

    suspend fun unblockAvatar(avatarId: String) {
        vrcxCoreClient().avatarModerationDelete(avatarId).bodyOrThrow()
    }
}
