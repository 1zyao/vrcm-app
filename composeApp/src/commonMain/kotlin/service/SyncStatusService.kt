package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.syncConnection
import io.github.vrcmteam.vrcm.mobilecore.syncStatus
import io.github.vrcmteam.vrcm.mobilecore.syncTriggerNow
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What the settings screen shows about VRCX's shared-database sync.
 *
 * `configured` comes from the backend: sync is off until a shared database is set up, and that
 * state is the difference between "nothing to report" and "something is broken".
 */
data class SyncStatusVo(
    val configured: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val user: String = "",
    val hasPassword: Boolean = false,
    val intervalSeconds: Int = 0,
    val lastCycleAt: String = "",
    val lastPulled: Int = 0,
    val lastPushed: Int = 0,
    val lastError: String? = null,
    val error: String? = null,
) {
    val connected: Boolean get() = configured && error == null
}

/**
 * Reads and triggers the core's sync.
 *
 * The numbers are not inferred here: `lastCycleAt`/`lastPulled`/`lastPushed` are the newest entry of
 * the backend's own cycle history, which is what the engine actually did.
 */
class SyncStatusService {

    private val _status = MutableStateFlow(SyncStatusVo())
    val status: StateFlow<SyncStatusVo> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun refresh() {
        val client = vrcxCoreClient()
        _status.value = runCatching {
            val statusRoot = client.syncStatus().jsonObject
            val connection = runCatching { client.syncConnection().jsonObject }.getOrNull()
            parse(statusRoot, connection)
        }.getOrElse { error ->
            _status.value.copy(
                error = (error as? VrcxCoreException)?.message ?: error.message ?: "sync status failed",
            )
        }
    }

    suspend fun triggerNow() {
        if (_busy.value) return
        _busy.value = true
        try {
            val client = vrcxCoreClient()
            val statusRoot = client.syncTriggerNow().jsonObject
            val connection = runCatching { client.syncConnection().jsonObject }.getOrNull()
            _status.value = parse(statusRoot, connection)
        } catch (error: Throwable) {
            _status.value = _status.value.copy(
                error = (error as? VrcxCoreException)?.message ?: error.message ?: "sync failed",
            )
        } finally {
            _busy.value = false
        }
    }

    private fun parse(statusRoot: JsonObject, connection: JsonObject?): SyncStatusVo {
        val history = (statusRoot["cycleHistory"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .orEmpty()
        // The backend keeps the newest cycle first; fall back to the oldest if it ever stops doing so.
        val last = history.firstOrNull()
        return SyncStatusVo(
            configured = statusRoot.bool("configured"),
            host = connection?.str("host").orEmpty(),
            port = connection?.int("port") ?: 0,
            database = connection?.str("database").orEmpty(),
            user = connection?.str("user").orEmpty(),
            hasPassword = connection?.bool("hasPassword") ?: false,
            intervalSeconds = connection?.int("intervalSeconds") ?: 0,
            lastCycleAt = last?.str("at").orEmpty(),
            lastPulled = last?.int("pulled") ?: 0,
            lastPushed = last?.int("pushed") ?: 0,
            lastError = last?.str("error")?.takeIf { it.isNotBlank() },
            error = statusRoot.str("error")?.takeIf { it.isNotBlank() },
        )
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

