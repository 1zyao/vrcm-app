package io.github.vrcmteam.vrcm.mobilecore

import android.os.Build
import android.util.Log
import io.github.vrcmteam.vrcm.core.NativeCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android side of the core bridge: starts the Rust library once and hands out a
 * [VrcxCoreClient] pointing at its loopback command bus.
 *
 * Starting is idempotent — the first caller wins, later callers reuse the same
 * endpoint, so any number of services can ask for the client.
 */
private var endpoint: VrcxCoreClient? = null

fun startVrcxCore(filesDir: String): VrcxCoreClient? {
    endpoint?.let { return it }
    return runCatching {
        // The core reports this to remote sync, so the desktop's device list shows a phone instead
        // of a bare id. `Build.MODEL` is the marketing name ("Pixel 7", "MuMu"); the manufacturer
        // disambiguates models that share a name.
        val deviceName = listOfNotNull(
            Build.MANUFACTURER?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
            Build.MODEL?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "Android" }
        val json = NativeCore.start(filesDir, deviceName)
        val body = Json.parseToJsonElement(json).jsonObject
        val port = body["port"]!!.jsonPrimitive.content.toInt()
        val token = body["token"]!!.jsonPrimitive.content
        VrcxCoreClient("http://127.0.0.1:$port", token).also {
            endpoint = it
            Log.i("VrcxCore", "core ready on port $port")
        }
    }.onFailure { Log.e("VrcxCore", "core failed to start", it) }.getOrNull()
}

actual fun vrcxCoreClient(): VrcxCoreClient {
    // A configured backend wins: the app is then a pure frontend of a standalone
    // VRCX backend and the in-process core is not used at all.
    VrcxBackend.baseUrlOverride?.takeIf { it.isNotBlank() }?.let { url ->
        return VrcxCoreClient(url.trimEnd('/'), token = "")
    }
    return requireNotNull(endpoint) { "no VRCX backend configured and the in-process core is not running" }
}

actual fun isVrcxCoreReady(): Boolean = VrcxBackend.isRemote || endpoint != null

