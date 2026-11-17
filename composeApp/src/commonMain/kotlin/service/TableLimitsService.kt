package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.configListValues
import io.github.vrcmteam.vrcm.mobilecore.configSetValues
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** How many rows VRCX keeps per table, and how far a search scans. */
data class TableLimitsVo(
    val maxTableSize: Int = 0,
    val searchLimit: Int = 0,
    val error: String? = null,
)

/**
 * The two limits that decide how much VRCX's recording keeps.
 *
 * They are ordinary config values (`maxTableSize_v2` / `searchLimit`) and the desktop writes them
 * through `app__config_set_values`, so the phone uses the same command rather than a private shape.
 */
class TableLimitsService {

    private val _limits = MutableStateFlow(TableLimitsVo())
    val limits: StateFlow<TableLimitsVo> = _limits.asStateFlow()

    suspend fun refresh() {
        _limits.value = runCatching {
            val rows = vrcxCoreClient().configListValues().jsonArray
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            var maxTableSize = 0
            var searchLimit = 0
            rows.forEach { row ->
                val key = (row["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val value = (row["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when (key) {
                    MAX_TABLE_SIZE_KEY -> maxTableSize = value.trim().toIntOrNull() ?: 0
                    SEARCH_LIMIT_KEY -> searchLimit = value.trim().toIntOrNull() ?: 0
                }
            }
            TableLimitsVo(maxTableSize = maxTableSize, searchLimit = searchLimit)
        }.getOrElse { error ->
            TableLimitsVo(
                error = (error as? VrcxCoreException)?.message ?: error.message,
            )
        }
    }

    /**
     * Saves both limits with VRCX's own key names; the backend resolves them.
     *
     * A failure is surfaced, not swallowed: the first version wrapped this in `runCatching` and the
     * dialog closed as if saved while nothing was written. The error text goes into the state the
     * rows show as a subtitle.
     */
    suspend fun save(maxTableSize: Int, searchLimit: Int) {
        try {
            vrcxCoreClient().configSetValues(
                listOf(
                    MAX_TABLE_SIZE_NAME to maxTableSize.toString(),
                    SEARCH_LIMIT_NAME to searchLimit.toString(),
                ),
            )
        } catch (error: Throwable) {
            _limits.value = _limits.value.copy(
                error = (error as? VrcxCoreException)?.message
                    ?: error.message
                    ?: "saving table limits failed",
            )
            return
        }
        refresh()
    }

    private companion object {
        const val MAX_TABLE_SIZE_NAME = "maxTableSize_v2"
        const val SEARCH_LIMIT_NAME = "searchLimit"
        const val MAX_TABLE_SIZE_KEY = "config:vrcx_maxtablesize_v2"
        const val SEARCH_LIMIT_KEY = "config:vrcx_searchlimit"
    }
}



