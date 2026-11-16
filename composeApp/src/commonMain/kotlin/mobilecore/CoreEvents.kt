package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Push half of the core contract: `GET /api/events`.
 *
 * The core installs its runtime event bus as a loopback broadcast, so this stream
 * carries the same 46 event names the desktop receives over Tauri's `listen` -
 * friend presence, notifications, sync state, runtime phase.
 *
 * Without a subscriber the phone has no live updates at all: commands only ever
 * answer requests, they never announce changes.
 */
class CoreEvents(private val baseUrl: String) {
    private val http = HttpClient { install(WebSockets) }

    fun stream(): Flow<CoreEvent> = channelFlow {
        // Ktor only performs the upgrade for a ws:// URL; passing the http:// base
        // straight through sends a plain GET and axum answers 400.
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/api/events"
        http.webSocket(wsUrl) {
            // The block only runs once the upgrade succeeded, so this is a reliable
            // signal for callers (and for the on-device probe) that push is live.
            send(CoreEvent("connected", JsonNull))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val body = runCatching {
                    Json.parseToJsonElement(frame.readText()).jsonObject
                }.getOrNull() ?: continue
                val name = body["event"]?.jsonPrimitive?.content ?: continue
                send(CoreEvent(name, body["payload"] ?: JsonNull))
            }
        }
        awaitClose { }
    }
}

data class CoreEvent(val name: String, val payload: JsonElement)



