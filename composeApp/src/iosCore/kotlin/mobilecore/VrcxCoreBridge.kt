package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.cinterop.vrcx_core_free
import io.github.vrcmteam.vrcm.core.cinterop.vrcx_core_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/**
 * iOS side of the core bridge: starts the Rust library once and hands out a [VrcxCoreClient] pointing at
 * its loopback command bus.
 *
 * The only thing that crosses the FFI boundary is the start-up call - after that the protocol is HTTP on
 * 127.0.0.1, exactly as on Android, so [VrcxCoreClient] and everything above it is shared code.
 *
 * This file is compiled only when the static library is present (`vrcx_core.a` next to the cinterop
 * definition); otherwise `src/iosStub` provides the same API with a message explaining that the core is
 * not built in. That keeps the Apple targets compiling on a machine that has no iOS toolchain.
 */
private var endpoint: VrcxCoreClient? = null

@OptIn(ExperimentalForeignApi::class)
private fun appDataDir(): String =
    NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    ).first() as String

@OptIn(ExperimentalForeignApi::class)
private fun startNativeCore(dataDir: String, deviceName: String): String {
    // cinterop maps `const char *` parameters to Kotlin `String?` by itself, so no CString juggling is
    // needed here; the returned `char *` stays a pointer and is released by `vrcx_core_free`.
    val raw = vrcx_core_start(dataDir, deviceName) ?: error("vrcx_core_start returned null")
    return try {
        raw.toKString()
    } finally {
        vrcx_core_free(raw)
    }
}

/**
 * Starts the core, returning the client or null on failure.
 *
 * Idempotent: the first caller wins and later callers reuse the same endpoint, so any number of services
 * can ask for it (same contract as the Android side).
 */
fun startVrcxCore(): VrcxCoreClient? {
    endpoint?.let { return it }
    return runCatching {
        // `UIDevice.name` is the user's device name when the app is allowed to read it and a generic
        // "iPhone"/"iPad" otherwise; either is more useful to a desktop device list than a bare id.
        val deviceName = UIDevice.currentDevice.name.ifBlank { "iOS" }
        val json = startNativeCore(appDataDir(), deviceName)
        val body = Json.parseToJsonElement(json).jsonObject
        body["error"]?.jsonPrimitive?.content?.let { error("core failed to start: $it") }
        val port = body["port"]!!.jsonPrimitive.content.toInt()
        val token = body["token"]!!.jsonPrimitive.content
        VrcxCoreClient("http://127.0.0.1:$port", token).also { endpoint = it }
    }.getOrNull()
}

actual fun vrcxCoreClient(): VrcxCoreClient {
    // A configured backend wins: the app is then a pure frontend of a standalone VRCX backend and the
    // in-process core is not used at all.
    VrcxBackend.baseUrlOverride?.takeIf { it.isNotBlank() }?.let { url ->
        return VrcxCoreClient(url.trimEnd('/'), token = "")
    }
    return requireNotNull(endpoint) { "no VRCX backend configured and the in-process core is not running" }
}

actual fun isVrcxCoreReady(): Boolean = VrcxBackend.isRemote || endpoint != null
