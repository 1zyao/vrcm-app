package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.network.api.attributes.AuthState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps the core's `LoginSessionState` onto VRCM's [AuthState].
 *
 * The two enums are not the same shape: the core distinguishes four outcomes
 * (authenticated / challenge / device approval / failed) and carries the 2FA
 * method, while VRCM only has three needs-code states. Keeping the mapping here
 * means VRCM's own `AuthState` stays untouched and every existing caller keeps
 * working.
 *
 * The core serialises `LoginSessionState` as a tagged enum; the tag is read
 * defensively (`Authenticated` variant name first, then a `kind`/`type` field) so
 * the wire format can change without breaking login.
 */
fun authStateFromCore(payload: JsonElement): AuthState {
    val obj = payload as? JsonObject
        ?: return AuthState.Unauthorized("unexpected core response")

    val (variant, body) = taggedVariant(obj)
    return when (variant) {
        "Authenticated" -> AuthState.Authed
        "Challenge" -> challengeState(body)
        "DeviceApproval" ->
            // The phone has no device-approval screen yet; surface it as a
            // message rather than pretending it succeeded.
            AuthState.Unauthorized("device approval required")
        "Failed" -> AuthState.Unauthorized(
            body["reason"]?.jsonPrimitive?.content ?: "login failed"
        )
        else -> AuthState.Unauthorized("unknown login state: $variant")
    }
}

/** Returns the variant name and its payload, tolerating both tagging styles. */
private fun taggedVariant(obj: JsonObject): Pair<String, JsonObject> {
    val known = listOf("Authenticated", "Challenge", "DeviceApproval", "Failed")
    known.firstOrNull { obj.containsKey(it) }?.let { name ->
        return name to (obj[name]?.jsonObject ?: JsonObject(emptyMap()))
    }
    val tag = (obj["kind"] ?: obj["type"])?.jsonPrimitive?.content ?: return "" to obj
    return tag to obj
}

/**
 * Challenge carries `mode` (the method the user must use now) plus the full
 * `methods` list. VRCM can only express one of three prompts, so `mode` decides.
 */
private fun challengeState(body: JsonObject): AuthState {
    val mode = body["mode"]?.jsonPrimitive?.content?.lowercase()
        ?: body["methods"]?.let { methods ->
            // Fall back to the first offered method.
            runCatching { methods.toString().lowercase() }.getOrNull()
        }
    return when {
        mode == null -> AuthState.NeedTFA
        mode.contains("email") -> AuthState.NeedEmailCode
        mode.contains("totp") -> AuthState.NeedTFA
        mode.contains("otp") -> AuthState.NeedTTFA
        else -> AuthState.NeedTFA
    }
}

/**
 * The core's authenticated session carries the full VRChat user JSON
 * (`AuthenticatedRuntimeSession.current_user`), so callers can emit the session
 * without a second VRChat round trip of their own.
 */
fun currentUserJsonFromCore(payload: JsonElement): String? {
    val obj = payload as? JsonObject ?: return null
    val (variant, body) = taggedVariant(obj)
    if (variant != "Authenticated") return null
    val session = body["session"]?.jsonObject ?: return null
    return session["currentUser"]?.toString()
}

/** Result of a core login: the mapped state plus the raw user JSON, if any. */
data class CoreLogin(val state: AuthState, val userJson: String?, val attemptId: String? = null)

/**
 * Attempt id of a pending challenge. The core needs it back when the user answers,
 * so it travels with the login result instead of being asked for again.
 */
fun attemptIdFromCore(payload: JsonElement): String? {
    val obj = payload as? JsonObject ?: return null
    val (variant, body) = taggedVariant(obj)
    if (variant != "Challenge" && variant != "DeviceApproval") return null
    return body["attemptId"]?.jsonPrimitive?.content
}

/** Two-factor method the core is currently asking for, as the wire value. */
fun challengeMethodFromCore(payload: JsonElement): String? {
    val obj = payload as? JsonObject ?: return null
    val (variant, body) = taggedVariant(obj)
    if (variant != "Challenge") return null
    return body["mode"]?.jsonPrimitive?.content
}

