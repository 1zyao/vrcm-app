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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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









