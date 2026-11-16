package io.github.vrcmteam.vrcm.mobilecore

import kotlinx.serialization.json.Json

/**
 * Decoder for everything the backend or VRChat hands over.
 *
 * The backend's payloads are VRChat-shaped but not identical to this app's DTOs - the user
 * object, for instance, carries `accountStanding`, which no DTO declares. A strict `Json`
 * turns that into "Encountered an unknown key" at runtime, which is what a refresh used to
 * fail with. Being lenient here is correct: the DTOs declare what the app reads, and extra
 * fields are the server's business.
 */
internal val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
