package io.github.vrcmteam.vrcm

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.vrcmteam.vrcm.mobilecore.CoreEvents
import io.github.vrcmteam.vrcm.mobilecore.debugEmitEvent
import io.github.vrcmteam.vrcm.mobilecore.startVrcxCore
import io.github.vrcmteam.vrcm.mobilecore.vrchatGet
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreClient
import io.github.vrcmteam.vrcm.mobilecore.feedRows
import kotlinx.coroutines.launch
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.github.vrcmteam.vrcm.service.OfficialLinkInbox
import io.github.vrcmteam.vrcm.service.NotificationLaunchDestination
import io.github.vrcmteam.vrcm.service.NotificationLaunchInbox
import io.github.vrcmteam.vrcm.service.ScanLaunchInbox

class MainActivity : ComponentActivity() {
    private val officialLinkInbox = OfficialLinkInbox()
    private val notificationLaunchInbox = NotificationLaunchInbox()
    private val scanLaunchInbox = ScanLaunchInbox()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        updateRequestedOrientation(resources.configuration)
        if (savedInstanceState == null) {
            submitOfficialLink(intent)
            intent.submitNotificationLaunch(notificationLaunchInbox)
            submitScanLaunch(intent)
        } else {
            if (savedInstanceState.getBoolean(PENDING_SCAN_KEY)) scanLaunchInbox.requestScan()
            savedInstanceState.getString(PENDING_OFFICIAL_LINK_KEY)?.let(officialLinkInbox::submit)
            val destination = savedInstanceState.getString(PENDING_NOTIFICATION_DESTINATION_KEY)
                ?.let { value -> NotificationLaunchDestination.entries.firstOrNull { it.name == value } }
            val targetId = savedInstanceState.getString(PENDING_NOTIFICATION_TARGET_ID_KEY)
            if (destination != null && targetId != null) {
                notificationLaunchInbox.submit(destination, targetId)
            }
        }
        // A backend URL in files/backend.url turns this app into a frontend of a
        // standalone VRCX node (server, or desktop on the LAN) instead of using the
        // in-process core. That is the intended topology.
        runCatching {
            val override = java.io.File(filesDir, "backend.url")
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (override != null) {
                io.github.vrcmteam.vrcm.mobilecore.VrcxBackend.baseUrlOverride = override
                Log.i("VrcxCore", "backend override: $override")
            }
        }
        // VRCX-0 core: starts the in-process Rust runtime and its loopback
        // command bus. Everything below talks HTTP to it; nothing else in the app
        // talks to VRChat directly.
        val core = startVrcxCore(filesDir.absolutePath)
        if (core != null) {
            lifecycleScope.launch {
                runCatching { core.feedRows("usr_probe", 10) }
                    .onSuccess { rows ->
                        Log.i("VrcxCore", "feedRows ok: ${rows.toString().take(300)}")
                    }
                    .onFailure { Log.e("VrcxCore", "feedRows failed", it) }

                // TEMP probe: the social baseline is the source of the favourites quota
                // (per-group capacity/count). It answers `stale` unless the auth scope is
                // set, so this checks that the session restore actually activated it.
                launch {
                    kotlinx.coroutines.delay(8_000)
                    val userId = io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
                        .currentSession.value?.account?.userId
                    if (userId == null) {
                        Log.w("VrcxCore", "baseline probe skipped: no session yet")
                        return@launch
                    }
                    runCatching {
                        core.invoke(
                            "app__social_favorites_baseline_get",
                            kotlinx.serialization.json.buildJsonObject {
                                put("input", kotlinx.serialization.json.buildJsonObject {
                                    put("userId", userId)
                                    put("endpoint", "https://api.vrchat.cloud/api/1")
                                })
                            }
                        )
                    }.onSuccess { result ->
                        // Print only what matters: the stale flag, the limits, and the
                        // per-group capacity/count the favourites panel needs.
                        val obj = result.jsonObject
                        val stale = obj["stale"]?.jsonPrimitive?.content
                        val snapshot = obj["snapshot"]?.jsonObject
                        val limits = snapshot?.get("favoriteLimits")?.toString()
                        val groups = listOf("favoriteAvatarGroups", "favoriteWorldGroups")
                            .joinToString("; ") { field ->
                                val list = snapshot?.get(field)
                                    ?.let { runCatching { it.jsonArray }.getOrNull() }
                                    ?: return@joinToString "$field=?"
                                val parts = list.mapNotNull { g ->
                                    val go = runCatching { g.jsonObject }.getOrNull() ?: return@mapNotNull null
                                    "${go["key"]?.jsonPrimitive?.content}=" +
                                        "${go["count"]?.jsonPrimitive?.content}/" +
                                        "${go["capacity"]?.jsonPrimitive?.content}"
                                }
                                "$field[$field]=" + parts.joinToString(",")
                            }
                        Log.i(
                            "VrcxCore",
                            "baseline favorites: stale=$stale count=${obj["count"]?.jsonPrimitive?.content} " +
                                "limits=$limits groups=$groups"
                        )
                    }.onFailure { Log.e("VrcxCore", "baseline favorites failed", it) }
                }

                // TEMP probe: VRChat's file images (api.vrchat.cloud/api/1/image/...) come back
                // 200 image/png to curl but fail inside Coil with a decode error, so log what the
                // app itself receives - status, content type, encoding and the first bytes.
                launch {
                    kotlinx.coroutines.delay(12_000)
                    runCatching {
                        val url = "https://api.vrchat.cloud/api/1/image/" +
                            "file_57586d9c-629a-45e9-a426-ee285195b007/1/256"
                        val response = io.ktor.client.HttpClient()
                            .get(url)
                        val bytes = response.body<ByteArray>()
                        val decoded = android.graphics.BitmapFactory
                            .decodeByteArray(bytes, 0, bytes.size)
                        Log.i(
                            "VrcxCore",
                            "image probe: status=${response.status} " +
                                "contentType=${response.headers["Content-Type"]} " +
                                "encoding=${response.headers["Content-Encoding"]} " +
                                "bytes=${bytes.size} " +
                                "head=${bytes.take(8).joinToString(",") { (it.toInt() and 0xFF).toString(16) }} " +
                                "decoded=${decoded?.let { "${it.width}x${it.height}" } ?: "null"}"
                        )
                    }.onFailure { Log.e("VrcxCore", "image probe failed", it) }
                }

                // Event channel: proves the WS half of the contract is reachable and
                // keeps logging whatever the core pushes.
                launch {
                    runCatching {
                        Log.i("VrcxCore", "event stream connecting to ${core.baseUrl}")
                        var emitted = false
                        CoreEvents(core.baseUrl).stream().collect { event ->
                            Log.i("VrcxCore", "event ${event.name}: ${event.payload.toString().take(160)}")
                            if (event.name == "connected" && !emitted) {
                                emitted = true
                                runCatching {
                                    core.debugEmitEvent("probeEvent", Json.parseToJsonElement("""{"ok":true}"""))
                                }.onFailure { Log.e("VrcxCore", "debug emit failed", it) }
                            }
                        }
                    }.onFailure { Log.e("VrcxCore", "event stream failed", it) }
                }

                // Transport probe: `config` is a public VRChat endpoint, so a 200
                // here proves the Kotlin -> core -> VRChat path without needing a
                // logged-in account.
                runCatching { core.vrchatGet("config") }
                    .onSuccess { config ->
                        Log.i("VrcxCore", "probe vrchatGet(config) ok: ${config.toString().take(160)}")
                    }
                    .onFailure { Log.e("VrcxCore", "probe vrchatGet(config) failed", it) }
            }
        }

        setContent {
            App(
                officialLinkInbox = officialLinkInbox,
                notificationLaunchInbox = notificationLaunchInbox,
                scanLaunchInbox = scanLaunchInbox,
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRequestedOrientation(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submitOfficialLink(intent)
        intent.submitNotificationLaunch(notificationLaunchInbox)
        submitScanLaunch(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PENDING_SCAN_KEY, scanLaunchInbox.pending.value)
        officialLinkInbox.pendingUrl()?.let {
            outState.putString(PENDING_OFFICIAL_LINK_KEY, it)
        }
        notificationLaunchInbox.pendingRequest.value?.let { request ->
            outState.putString(PENDING_NOTIFICATION_DESTINATION_KEY, request.destination.name)
            outState.putString(PENDING_NOTIFICATION_TARGET_ID_KEY, request.targetId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun submitOfficialLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(officialLinkInbox::submit)
    }

    private fun submitScanLaunch(intent: Intent) {
        if (intent.action == ScanShortcutActivity.ACTION_SCAN_QR_CODE) scanLaunchInbox.requestScan()
    }

    private fun updateRequestedOrientation(configuration: Configuration) {
        val smallestWidthDp = configuration.smallestScreenWidthDp
        val targetOrientation = if (
            smallestWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED &&
            smallestWidthDp < WIDE_SCREEN_MIN_WIDTH_DP
        ) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }
    }

    private companion object {
        const val WIDE_SCREEN_MIN_WIDTH_DP = 600
        const val PENDING_SCAN_KEY = "pending-scan"
        const val PENDING_OFFICIAL_LINK_KEY = "pending-official-link"
        const val PENDING_NOTIFICATION_DESTINATION_KEY = "pending-notification-destination"
        const val PENDING_NOTIFICATION_TARGET_ID_KEY = "pending-notification-target-id"
    }
}










