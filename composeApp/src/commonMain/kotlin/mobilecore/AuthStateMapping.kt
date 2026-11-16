package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.network.api.attributes.AuthState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // The exact wire shape is not assumed: `taggedVariant` lowercases whatever tag
    // it finds, and the raw payload is logged so a mismatch is diagnosable from
    // logcat instead of guesswork.
    println("[VrcxCore] authState raw=${payload.toString().take(300)}")

    val (variant, body) = taggedVariant(obj)
    return when (variant) {
        "authenticated" -> AuthState.Authed
        "challenge" -> challengeState(body)
        "deviceapproval" ->
            // The phone has no device-approval screen yet; surface it as a
            // message rather than pretending it succeeded.
            AuthState.Unauthorized("device approval required")
        "failed" -> AuthState.Unauthorized(
            body["reason"]?.jsonPrimitive?.content ?: "login failed"
        )
        else -> AuthState.Unauthorized("unknown login state: $variant")
    }
}

/**
 * Returns the lowercased variant name and its payload.
 *
 * Handles all three plausible encodings of the core's `LoginSessionState`:
 * externally tagged (`{"Challenge": {...}}`), adjacently/internally tagged via a
 * `kind`/`type`/`status` field, or lowercase variants from `rename_all`.
 */
private fun taggedVariant(obj: JsonObject): Pair<String, JsonObject> {
    val known = listOf("Authenticated", "Challenge", "DeviceApproval", "Failed")
    // Externally tagged, exact or any casing.
    obj.keys.firstOrNull { key -> known.any { it.equals(key, ignoreCase = true) } }
        ?.let { key ->
            val value = obj[key]
            val body = value as? JsonObject ?: obj
            return known.first { it.equals(key, ignoreCase = true) }.lowercase() to body
        }
    // Internally tagged.
    for (tagField in listOf("kind", "type", "status", "variant")) {
        val tag = (obj[tagField] as? JsonPrimitive)?.content ?: continue
        known.firstOrNull { it.equals(tag, ignoreCase = true) }?.let {
            return it.lowercase() to obj
        }
    }
    return "" to obj
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
    if (variant != "authenticated") return null
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
    if (variant != "challenge" && variant != "deviceapproval") return null
    return body["attemptId"]?.jsonPrimitive?.content
}

/** Two-factor method the core is currently asking for, as the wire value. */
fun challengeMethodFromCore(payload: JsonElement): String? {
    val obj = payload as? JsonObject ?: return null
    val (variant, body) = taggedVariant(obj)
    if (variant != "challenge") return null
    return body["mode"]?.jsonPrimitive?.content
}


