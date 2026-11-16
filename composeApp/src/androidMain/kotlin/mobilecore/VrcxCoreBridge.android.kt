package io.github.vrcmteam.vrcm.mobilecore

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
        val json = NativeCore.start(filesDir)
        val body = Json.parseToJsonElement(json).jsonObject
        val port = body["port"]!!.jsonPrimitive.content.toInt()
        val token = body["token"]!!.jsonPrimitive.content
        VrcxCoreClient("http://127.0.0.1:$port", token).also {
            endpoint = it
            Log.i("VrcxCore", "core ready on port $port")
        }
    }.onFailure { Log.e("VrcxCore", "core failed to start", it) }.getOrNull()
}

actual fun vrcxCoreClient(): VrcxCoreClient =
    requireNotNull(endpoint) { "VRCX-0 core has not been started yet" }

actual fun isVrcxCoreReady(): Boolean = endpoint != null
