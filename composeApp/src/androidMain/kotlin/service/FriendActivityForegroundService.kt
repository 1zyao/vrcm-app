package io.github.vrcmteam.vrcm.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.presentation.notifications.FriendNotificationFactory
import org.koin.core.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * User-enabled Android foreground monitor.
 *
 * The WebSocket is the real-time source. The periodic refresh is a fallback
 * and only runs while the socket is disconnected.
 */
class FriendActivityForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkWakeLock: BackgroundNetworkWakeLock? = null
    private var monitoringStartedAtMillis = 0L
    private var timerResetReceiverRegistered = false
    private val timerResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RESET_MONITORING_TIMER) return
            monitoringStartedAtMillis = System.currentTimeMillis()
            showMonitoringNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        monitoringStartedAtMillis = System.currentTimeMillis()
        showMonitoringNotification()
        networkWakeLock = BackgroundNetworkWakeLock(this)
        val koin = GlobalContext.get()
        // Nothing to switch on for realtime any more: the socket lives in the VRCX-0 core now, and the core
        // lives in this process. Keeping the process alive - which is all this service does - is therefore
        // what keeps the socket alive. The old `WebSocketApi.setBackgroundMonitoringEnabled(true)` call here
        // was a no-op: `replaceConnection` returns early whenever the core is up, because the shared Ktor
        // client is backed by `CoreHttpEngine` and declares no WebSocket capability.
        val friendService = koin.get<FriendService>()
        val notificationService = koin.get<FriendOnlineNotificationService>()
        koin.get<FriendActivityService>().onBackgroundMonitoringStarted()
        val logger = koin.get<Logger>()
        scope.launch {
            while (isActive) {
                delay(FALLBACK_REFRESH_INTERVAL_MILLIS)
                // Unconditional now. The old guard was `!webSocketApi.isConnected()`, which with the core
                // present is always true (that client never connects), so the branch ran every time anyway -
                // keeping the guard would only hide the intent. Fifteen minutes of REST refreshes is a cheap
                // backstop for a socket that the core is responsible for reconnecting.
                if (SharedFlowCentre.currentSession.value != null) {
                    runCatching { friendService.refreshFriendList() }
                        .onFailure { logger.warn("Background friend refresh failed: ${it.message.orEmpty()}") }
                    notificationService.refreshInboxNotifications()
                }
            }
        }
        scope.launch { SharedFlowCentre.logout.collect { stopSelf() } }
        ContextCompat.registerReceiver(
            this,
            timerResetReceiver,
            IntentFilter(ACTION_RESET_MONITORING_TIMER),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        timerResetReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_MONITOR_NOTIFICATION) {
            showMonitoringNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GlobalContext.getOrNull()?.let { koin ->
            // No `setBackgroundMonitoringEnabled(false)` counterpart: see `onCreate`. The core's socket is
            // tied to the process, and the process outlives this service if the UI is still alive - which is
            // the behaviour we want, since the UI reads from the same socket.
            koin.get<FriendActivityService>().onBackgroundMonitoringStopped()
        }
        scope.cancel()
        if (timerResetReceiverRegistered) {
            unregisterReceiver(timerResetReceiver)
            timerResetReceiverRegistered = false
        }
        networkWakeLock?.close()
        networkWakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun showMonitoringNotification() {
        val restoreIntent = PendingIntent.getService(
            this,
            MONITOR_ID,
            Intent(this, FriendActivityForegroundService::class.java)
                .setAction(ACTION_RESTORE_MONITOR_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            MONITOR_ID,
            FriendNotificationFactory(this).monitoringNotification(
                startedAtMillis = monitoringStartedAtMillis,
                restoreIntent = restoreIntent,
            ),
        )
    }

    companion object {
        internal const val ACTION_RESET_MONITORING_TIMER =
            "io.github.vrcmteam.vrcm.action.RESET_MONITORING_TIMER"
        private const val ACTION_RESTORE_MONITOR_NOTIFICATION =
            "io.github.vrcmteam.vrcm.action.RESTORE_MONITOR_NOTIFICATION"
        private const val MONITOR_ID = 0x5652434d
        private const val FALLBACK_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1_000L
    }
}
