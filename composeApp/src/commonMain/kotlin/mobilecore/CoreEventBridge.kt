package io.github.vrcmteam.vrcm.mobilecore

import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 把核心的推送接到应用里（`GET /api/events` → [SharedFlowCentre.coreEvents]）。
 *
 * 在此之前 `CoreEvents` 只在 `MainActivity` 里被 collect 到 logcat：事件流是通的，但界面上没有任何
 * 订阅者。结果是核心算好的好友/房间/通知变化全都停在日志里，只有手动刷新才能看到。
 *
 * 这里只转发**事件名**，不转发 payload：核心推的是 projection（`realtimeFriendProjection` 等），
 * 与 [io.github.vrcmteam.vrcm.core.shared.AccountWebSocketEvent] 那套 pipeline 事件结构不同，反解会得到
 * 语义不对的数据。订阅者把名字当「有变化」的信号，自己去拉当前状态。
 */
internal class CoreEventBridge(
    private val streamFactory: () -> CoreEvents,
    private val scope: CoroutineScope,
) {
    private var started = false

    /**
     * 启动一次；重复调用无副作用。
     *
     * 失败只记录、不抛出：事件流断开时命令通道仍然可用，界面照常能刷新，只是不会有推送。
     */
    fun start(onEvent: (CoreEvent) -> Unit = {}) {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                streamFactory().stream().collect { event ->
                    onEvent(event)
                    // `connected` 是 CoreEvents 在握手成功后自己发的伪事件，不是核心的状态变化。
                    if (event.name != "connected") {
                        SharedFlowCentre.coreEvents.tryEmit(event.name)
                    }
                }
            }
        }
    }
}
