package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.syncBootstrapProgress
import io.github.vrcmteam.vrcm.mobilecore.syncConfigure
import io.github.vrcmteam.vrcm.mobilecore.syncConnection
import io.github.vrcmteam.vrcm.mobilecore.syncStatus
import io.github.vrcmteam.vrcm.mobilecore.syncTestConnection
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
import kotlinx.serialization.json.longOrNull

/** One device sharing the database, as the desktop lists it. */
data class SyncDeviceVo(
    val deviceId: String,
    val name: String,
    val role: String,
    val priority: Int,
    val ipAddress: String,
    val appVersion: String,
    val lastSeenAt: String,
    val seenSecondsAgo: Int,
    val online: Boolean,
)

/** One row of "last cycle detail": how many ops this table moved. */
data class SyncTableOpsVo(val table: String, val pushed: Int, val pulled: Int)

/** One entry of the cycle history. */
data class SyncCycleVo(
    val at: String,
    val durationMs: Int,
    val pushed: Int,
    val pulled: Int,
    val error: String?,
)

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
    val enabled: Boolean = false,
    val tlsVerify: Boolean = false,
    val allowPlaintext: Boolean = true,
    val intervalSeconds: Int = 0,
    val collectorPriority: Int = 0,
    val lastCycleAt: String = "",
    val lastPulled: Int = 0,
    val lastPushed: Int = 0,
    val lastError: String? = null,
    val error: String? = null,
    /** Engine phase, e.g. idle / running / bootstrap. */
    val phase: String = "",
    val pendingOutbox: Int = 0,
    val deviceId: String = "",
    val nextCycleInSecs: Int? = null,
    val lastPushedOps: Int = 0,
    val lastPulledOps: Int = 0,
    /** Every device on this database, including this one. */
    val devices: List<SyncDeviceVo> = emptyList(),
    /** Per-table op counts of the newest cycle. */
    val lastCycleTables: List<SyncTableOpsVo> = emptyList(),
    /** Newest-first cycle history. */
    val cycles: List<SyncCycleVo> = emptyList(),
    /** First-run bootstrap, only meaningful while [bootstrapRunning]. */
    val bootstrapRunning: Boolean = false,
    val bootstrapTablesDone: Int = 0,
    val bootstrapTablesTotal: Int = 0,
    val bootstrapRowsDone: Long = 0,
    val bootstrapRowsTotal: Long = 0,
    /** Which bootstrap phase: push / merge / done. Worth showing: a long push is not a hang. */
    val bootstrapPhase: String = "",
    val bootstrapCurrentTable: String = "",
    val bootstrapCurrentTableRowsDone: Long = 0,
    val bootstrapCurrentTableRowsTotal: Long = 0,
) {
    val connected: Boolean get() = configured && error == null

    /** Table completion as a fraction, or null when the total is not known yet. */
    val bootstrapTablesFraction: Float?
        get() = if (bootstrapTablesTotal > 0) {
            (bootstrapTablesDone.toFloat() / bootstrapTablesTotal).coerceIn(0f, 1f)
        } else {
            null
        }

    /** Current table's rows as a fraction, or null when that table's size is unknown. */
    val bootstrapTableRowsFraction: Float?
        get() = if (bootstrapCurrentTableRowsTotal > 0) {
            (bootstrapCurrentTableRowsDone.toFloat() / bootstrapCurrentTableRowsTotal).coerceIn(0f, 1f)
        } else {
            null
        }

    /** Overall rows as a fraction, or null when the total is not known yet. */
    val bootstrapRowsFraction: Float?
        get() = if (bootstrapRowsTotal > 0) {
            (bootstrapRowsDone.toFloat() / bootstrapRowsTotal).coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * The answer to `sync__test_connection`: `ok` plus either a latency or a readable error.
 */
data class SyncConnectionTestVo(
    val ok: Boolean = false,
    val serverVersion: String = "",
    val latencyMs: Int = 0,
    val error: String? = null,
)

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

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<SyncConnectionTestVo?>(null)
    val testResult: StateFlow<SyncConnectionTestVo?> = _testResult.asStateFlow()

    suspend fun refresh() {
        val client = vrcxCoreClient()
        _status.value = runCatching {
            val statusRoot = client.syncStatus().jsonObject
            val connection = runCatching { client.syncConnection().jsonObject }.getOrNull()
            val parsed = parse(statusRoot, connection)
            // The first-run bootstrap only exists while it runs; asking for it always keeps the
            // readout correct when a fresh database is being backfilled.
            val progress = runCatching { client.syncBootstrapProgress().jsonObject }.getOrNull()
            if (progress == null) {
                parsed
            } else {
                parsed.copy(
                    bootstrapRunning = progress.bool("running"),
                    bootstrapTablesDone = progress.int("tablesDone"),
                    bootstrapTablesTotal = progress.int("tablesTotal"),
                    bootstrapRowsDone = progress.long("rowsDone"),
                    bootstrapRowsTotal = progress.long("rowsTotal"),
                    bootstrapPhase = progress.str("phase"),
                    bootstrapCurrentTable = progress.str("currentTable"),
                    bootstrapCurrentTableRowsDone = progress.long("currentTableRowsDone"),
                    bootstrapCurrentTableRowsTotal = progress.long("currentTableRowsTotal"),
                )
            }
        }.getOrElse { error ->
            _status.value.copy(
                error = (error as? VrcxCoreException)?.message ?: error.message ?: "sync status failed",
            )
        }
    }

    /**
     * Writes the whole connection (plus interval and collector priority), then re-reads the state.
     *
     * Every field is sent, as the desktop page does. The password is the exception: it is only sent
     * when the user typed one, because it is never read back - an empty field means "keep the stored
     * one", not "clear it".
     */
    suspend fun configure(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
        tlsVerify: Boolean,
        allowPlaintext: Boolean,
        intervalSeconds: Int,
        collectorPriority: Int,
        enabled: Boolean? = null,
    ) {
        if (_busy.value) return
        _busy.value = true
        try {
            val client = vrcxCoreClient()
            client.syncConfigure(
                host = host.trim(),
                port = port,
                database = database.trim(),
                user = user.trim(),
                password = password.trim().ifBlank { null },
                tlsVerify = tlsVerify,
                allowPlaintext = allowPlaintext,
                intervalSeconds = intervalSeconds,
                collectorPriority = collectorPriority,
                enabled = enabled,
            )
            refresh()
        } catch (error: Throwable) {
            _status.value = _status.value.copy(
                error = (error as? VrcxCoreException)?.message ?: error.message ?: "sync configure failed",
            )
        } finally {
            _busy.value = false
        }
    }

    /** Writes the two transport switches, the way the desktop's connection card does. */
    suspend fun saveSwitches(tlsVerify: Boolean, allowPlaintext: Boolean) {
        val current = _status.value
        configure(
            host = current.host,
            port = current.port,
            user = current.user,
            password = "",
            database = current.database,
            tlsVerify = tlsVerify,
            allowPlaintext = allowPlaintext,
            intervalSeconds = current.intervalSeconds,
            collectorPriority = current.collectorPriority,
            enabled = current.enabled,
        )
    }

    /**
     * Turns the engine on or off.
     *
     * The desktop's enable switch is a save: it sends the whole connection with the new flag. This
     * does the same from the values already on screen, with an empty password so the stored one is
     * kept.
     */
    suspend fun setEnabled(enabled: Boolean) {
        val current = _status.value
        configure(
            host = current.host,
            port = current.port,
            user = current.user,
            password = "",
            database = current.database,
            tlsVerify = current.tlsVerify,
            allowPlaintext = current.allowPlaintext,
            intervalSeconds = current.intervalSeconds,
            collectorPriority = current.collectorPriority,
            enabled = enabled,
        )
    }

    /** Probes the given details without saving them, as the desktop page does before a save. */
    suspend fun test(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
        tlsVerify: Boolean,
        allowPlaintext: Boolean,
    ) {
        if (_testing.value) return
        _testing.value = true
        _testResult.value = null
        try {
            val root = vrcxCoreClient().syncTestConnection(
                host = host.trim(),
                port = port,
                user = user.trim(),
                password = password.trim().ifBlank { null },
                database = database.trim(),
                tlsVerify = tlsVerify,
                allowPlaintext = allowPlaintext,
            ).jsonObject
            _testResult.value = SyncConnectionTestVo(
                ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                serverVersion = (root["serverVersion"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                latencyMs = (root["latencyMs"] as? JsonPrimitive)?.intOrNull ?: 0,
                error = (root["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            )
        } catch (error: Throwable) {
            _testResult.value = SyncConnectionTestVo(
                ok = false,
                error = (error as? VrcxCoreException)?.message ?: error.message ?: "test failed",
            )
        } finally {
            _testing.value = false
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
            enabled = statusRoot.bool("enabled"),
            host = connection?.str("host").orEmpty(),
            port = connection?.int("port") ?: 0,
            database = connection?.str("database").orEmpty(),
            user = connection?.str("user").orEmpty(),
            hasPassword = connection?.bool("hasPassword") ?: false,
            tlsVerify = connection?.bool("tlsVerify") ?: false,
            allowPlaintext = connection?.bool("allowPlaintext") ?: true,
            intervalSeconds = connection?.int("intervalSeconds") ?: 0,
            collectorPriority = connection?.int("collectorPriority") ?: 0,
            lastCycleAt = last?.str("at").orEmpty(),
            lastPulled = last?.int("pulled") ?: 0,
            lastPushed = last?.int("pushed") ?: 0,
            lastError = last?.str("error")?.takeIf { it.isNotBlank() },
            error = statusRoot.str("error")?.takeIf { it.isNotBlank() },
            phase = statusRoot.str("phase"),
            pendingOutbox = statusRoot.int("pendingOutbox"),
            deviceId = statusRoot.str("deviceId"),
            nextCycleInSecs = (statusRoot["nextCycleInSecs"] as? JsonPrimitive)?.intOrNull,
            lastPushedOps = statusRoot.int("lastPushedOps"),
            lastPulledOps = statusRoot.int("lastPulledOps"),
            devices = (statusRoot["remoteDevices"] as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.map { device ->
                    SyncDeviceVo(
                        deviceId = device.str("deviceId"),
                        name = device.str("deviceName"),
                        role = device.str("role"),
                        priority = device.int("priority"),
                        ipAddress = device.str("ipAddr"),
                        appVersion = device.str("appVersion"),
                        lastSeenAt = device.str("lastSeenAt"),
                        seenSecondsAgo = device.int("seenSecondsAgo"),
                        online = device.bool("online"),
                    )
                }
                .orEmpty(),
            lastCycleTables = (statusRoot["lastCycleTables"] as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.map { row ->
                    SyncTableOpsVo(
                        table = row.str("table"),
                        pushed = row.int("pushed"),
                        pulled = row.int("pulled"),
                    )
                }
                .orEmpty(),
            cycles = history.map { cycle ->
                SyncCycleVo(
                    at = cycle.str("at"),
                    durationMs = cycle.int("durationMs"),
                    pushed = cycle.int("pushed"),
                    pulled = cycle.int("pulled"),
                    error = cycle.str("error").takeIf { it.isNotBlank() },
                )
            },
        )
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0













