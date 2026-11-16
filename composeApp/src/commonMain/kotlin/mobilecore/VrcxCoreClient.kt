package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.github.vrcmteam.vrcm.network.api.attributes.AuthState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Client for the in-process VRCX-0 core.
 *
 * Speaks the same envelope as the desktop IPC and the cloud server:
 * `POST /api/invoke { cmd, args }` -> `{ ok: true, result }` or
 * `{ ok: false, error: { code, message } }`. One generic invoke serves every
 * command, so adding commands never touches this class.
 */
class VrcxCoreClient(
    val baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun invoke(cmd: String, args: JsonElement = buildJsonObject {}): JsonElement {
        val payload = buildJsonObject {
            put("cmd", cmd)
            put("args", args)
        }
        val response = http.post("$baseUrl/api/invoke") {
            contentType(ContentType.Application.Json)
            header("x-vrcx-token", token)
            setBody(payload.toString())
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (body["ok"]?.jsonPrimitive?.content != "true") {
            val error = body["error"]?.jsonObject
            throw VrcxCoreException(
                error?.get("code")?.jsonPrimitive?.content ?: "commandFailed",
                error?.get("message")?.jsonPrimitive?.content ?: "unknown error"
            )
        }
        return body["result"] ?: JsonNull
    }
}

class VrcxCoreException(val code: String, override val message: String) : Exception(message)

/** Feed rows for a user. Args are camelCase — the Rust side renames them. */
suspend fun VrcxCoreClient.feedRows(
    userId: String,
    maxEntries: Int = 50,
    mode: String = "lookup"
): JsonElement = invoke(
    "app__feed_rows_query",
    buildJsonObject {
        put("userId", userId)
        put("mode", mode)
        put("maxEntries", maxEntries)
    }
)

/**
 * Password login through the core.
 *
 * The core owns the HTTP call, the cookie jar and the 2FA state machine, so the
 * app never talks to VRChat directly. `mode` is the enum tag: the core's
 * `LoginSessionStartInput` is internally tagged with `mode` and camelCase fields.
 *
 * Note the args are the input struct itself — `register_typed` deserialises the
 * whole `args` object — so there is no `input` wrapper here. (Wrapping it produces
 * `missing field \`mode\`` from serde.)
 */
suspend fun VrcxCoreClient.loginBasic(
    username: String,
    password: String,
    saveCredentials: Boolean = true
): CoreLogin = invoke(
    "app__vrchat_auth_session_start",
    buildJsonObject {
        put("mode", "basic")
        put("username", username)
        put("password", password)
        put("saveCredentials", saveCredentials)
    }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}

/**
 * Answers a 2FA challenge raised by [loginBasic].
 *
 * `attemptId` comes from the login result: the core tracks the attempt, the UI only
 * carries the id. `method` is the wire value of the challenge mode
 * (`emailOtp` / `totp` / `otp`).
 */
suspend fun VrcxCoreClient.loginRespond(
    attemptId: String,
    method: String,
    code: String
): CoreLogin = invoke(
    "app__vrchat_auth_session_respond",
    buildJsonObject {
        put("attemptId", attemptId)
        put("method", method)
        put("code", code)
    }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}



/**
 * Ends the core session.
 *
 * The UI logs out locally anyway (cookie + in-memory session), but the core keeps
 * its own authenticated runtime — without this call it would still believe it is
 * logged in after a logout.
 *
 * `LoginSessionEnd` is an externally tagged enum, so the plain `Logout` variant
 * serialises as a bare string.
 */
suspend fun VrcxCoreClient.logout() {
    invoke(
        "app__vrchat_auth_session_end",
        // `LoginSessionEnd` is externally tagged: the `Logout` variant is the bare
        // string, and it *is* the args object.
        JsonPrimitive("Logout")
    )
}

/**
 * Restores a session from stored credentials.
 *
 * The core owns the saved credentials and the cookie jar, so this replaces the
 * "read AccountDto, re-apply cookies, ask VRChat for the user" dance. An empty
 * userId restores the most recent session.
 *
 * The core's `AutoLoginOutcome` is untagged: either a `LoginSessionState` (carrying
 * the user JSON) or a terminal outcome such as throttled/expired. Only the former
 * maps to a logged-in state.
 */
suspend fun VrcxCoreClient.autoLoginStart(userId: String = ""): CoreLogin = invoke(
    "app__vrchat_auth_auto_login_start",
    buildJsonObject { put("userId", userId) }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}

/** Raw VRChat GET through the core. `path` is relative to `/api/1/`. */
suspend fun VrcxCoreClient.vrchatGet(path: String): JsonElement {
    val trimmed = path.trimStart('/')
    val result = invoke(
        "app__web_execute",
        buildJsonObject {
            put("url", "https://api.vrchat.cloud/api/1/$trimmed")
            put("method", "GET")
        }
    )
    val status = result.jsonObject["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    if (status !in 200..299) {
        throw VrcxCoreException("http$status", "GET $trimmed failed with $status")
    }
    return Json.parseToJsonElement(result.jsonObject["data"]?.jsonPrimitive?.content ?: "null")
}


/** Diagnostic: publishes an event through the core so the push path can be checked. */
suspend fun VrcxCoreClient.debugEmitEvent(name: String, payload: JsonElement = JsonNull) {
    invoke(
        "app__debug_emit_event",
        buildJsonObject {
            put("name", name)
            put("payload", payload)
        }
    )
}

