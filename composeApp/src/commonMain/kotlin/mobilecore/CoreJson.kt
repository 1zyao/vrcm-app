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
 *
 * `coerceInputValues` and `explicitNulls = false` match this app's own network `Json`. Without
 * them, a field that VRChat simply omits is a hard failure: `FileData.animationStyle` is absent for
 * gallery images, so decoding the same response through this decoder threw MissingFieldException
 * while the Ktor path accepted it. The two decoders must agree, or the same payload works on one
 * path and breaks on the other.
 */
internal val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
