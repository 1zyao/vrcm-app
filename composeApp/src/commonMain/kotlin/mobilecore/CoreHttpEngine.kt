package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一个把所有 HTTP 请求转交给「进程内 Rust 核心」的 Ktor 引擎。
 *
 * ## 为什么需要它
 *
 * 这个项目以前是 app 自己拿 Ktor 去连 `api.vrchat.cloud`：自己维护 cookie jar、自己处理
 * 429/2FA/重试。现在 VRChat 的访问被集中到进程内的 Rust 核心（VRCX-0 core）里，
 * 它通过 loopback 的命令总线 `POST /api/invoke` 暴露能力，其中
 * `app__web_execute` 就是「帮我发一次 HTTP 请求」。
 *
 * 于是有一个矛盾：`di/modules/NetworkModule.kt` 里那一个共享 `HttpClient` 上已经堆了
 * 一大堆插件（默认 URL、cookie、ContentNegotiation、UserAgent、重试、超时……），
 * 上百个 API 类都依赖它；而所有的实际网络细节又必须交给核心。
 *
 * 这个引擎就是这个矛盾的答案：**保留 HttpClient 及全部插件和调用方代码不变，
 * 只把最底层的「谁来发这个请求」换成核心**。上层业务看到的仍然是普通的
 * `HttpClient` + `HttpResponse`，看不出请求其实是被转发的。
 *
 * 结果：VRCM 不再自己连 VRChat，而是经由进程内的 Rust 核心——cookie、限流、
 * 2FA 状态机都只有核心一份，app 侧不会再出现「两边登录态不一致」。
 *
 * ## 转发协议
 *
 * ```
 * 请求  -> cmd = "app__web_execute",
 *          args = { "url": 完整 URL, "method": "GET", "headers": [["k","v"], ...], "body": "<string|null>" }
 * 成功  -> { "ok": true,  "result": { "status": 200, "data": "<原始响应体>" } }
 * 失败  -> { "ok": false, "error": { "code": "...", "message": "..." } }
 * ```
 *
 * ## 失败策略
 *
 * 核心没起来、或者命令返回 `ok:false` 时，**不向上层抛异常**，而是返回一个 503
 * 加错误文本：这样每个 API 调用方拿到的是一个明确的 HTTP 状态码，可以按正常
 * 的错误分支处理，而不是在某个拦截器里炸掉整个协程。
 *
 * ## 版本依据
 *
 * 下面的签名全部按 **Ktor 3.1.1**（`gradle/libs.versions.toml` 里的 `ktor-client`）核对：
 * - `HttpResponseData(statusCode, requestTime, headers, version, body: Any, callContext)`
 *   —— 6 个位置参数，没有 `attributes` 参数（那是更高版本才加的）；
 * - `HttpRequestData(url, method, headers, body, executionContext, attributes)`；
 * - `HttpClientEngineBase(engineName)` 只提供 dispatcher/coroutineContext/close，
 *   `execute` 仍由子类实现，且带 `@io.ktor.utils.io.InternalAPI`（ERROR 级 opt-in，
 *   所以下面要写 `@OptIn(InternalAPI::class)`）；
 * - call context 取自 `io.ktor.client.engine.callContext()`（同样是 `@InternalAPI`）：
 *   3.1.x 的 `HttpStatement.cleanup` 会把 `HttpResponseData.callContext` 里的 Job
 *   当 `CompletableJob` 强转，塞调用协程自己的 Job 进去会在第一次读响应时炸。
 */
class CoreHttpEngine(
    override val config: HttpClientEngineConfig = HttpClientEngineConfig(),
) : HttpClientEngineBase("vrcx-core") {

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        // requestTime 必须是「开始发请求」的时间，Ktor 用它算耗时，所以先取。
        val requestTime = GMTDate()
        // 取「本次调用」的 call context。
        //
        // 这里必须用 Ktor 的 callContext()（io.ktor.client.engine.callContext，
        // 3.1.1 里是 @InternalAPI）：execute 虽然跑在调用协程里，但那个协程的 Job 是
        // async 产生的 DeferredCoroutine，而 HttpResponseData.callContext 里的 Job
        // 被 Ktor 内部（HttpStatement.cleanup）当成 CompletableJob 强转——直接传
        // currentCoroutineContext() 会在第一次读取响应时抛 ClassCastException。
        // callContext() 返回的是 Ktor 为本次调用专门创建的 CompletableJob 上下文。
        val callCtx: CoroutineContext = callContext()

        // 核心还没起来时 vrcxCoreClient() 会直接 require 失败（Android 侧实现是
        // requireNotNull(endpoint)），所以必须先问一下，并且整体包在 try 里。
        if (!isVrcxCoreReady()) {
            return errorResponse(
                requestTime = requestTime,
                callContext = callCtx,
                message = "VRCX core 尚未就绪：请求未发送（${data.method.value} ${data.url}）",
            )
        }

        return try {
            val result = vrcxCoreClient().invoke(
                cmd = "app__web_execute",
                args = buildJsonObject {
                    put("url", data.url.toString())
                    put("method", data.method.value)
                    put("headers", data.headers.toJsonPairs(data.body))
                    put("body", data.body.readBodyTextOrNull())
                },
            )

            val payload = result.jsonObject
            val status = payload["status"]
                ?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull()
                ?.takeIf { it in 100..599 }
                ?.let { HttpStatusCode.fromValue(it) }
                ?: HttpStatusCode.OK
            val text = payload["data"]?.jsonPrimitive?.contentOrNull ?: ""

            // 核心目前只回 { status, data }，不带响应头。若将来它多回一个
            // contentType 就照用；否则一律按 JSON 处理——VRCM 的整个 API 面都是
            // VRChat 的 JSON 接口，而 ContentNegotiation 需要一个 content-type 才会反序列化。
            val contentType = payload["contentType"]?.jsonPrimitive?.contentOrNull
                ?: DEFAULT_CONTENT_TYPE

            textResponse(
                status = status,
                text = text,
                contentType = contentType,
                requestTime = requestTime,
                callContext = callCtx,
            )
        } catch (e: CancellationException) {
            // 取消是调用方的意图，不能被下面那个兜底 catch 吞掉，否则协程语义就坏了。
            throw e
        } catch (t: Throwable) {
            // VrcxCoreException（ok:false）以及其他任何失败都收成 503，不炸上层。
            val reason = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
            errorResponse(
                requestTime = requestTime,
                callContext = callCtx,
                message = "VRCX core 转发失败（${data.method.value} ${data.url}）：$reason",
            )
        }
    }

    private fun errorResponse(
        requestTime: GMTDate,
        callContext: CoroutineContext,
        message: String,
    ): HttpResponseData = textResponse(
        status = HttpStatusCode.ServiceUnavailable,
        text = message,
        contentType = TEXT_CONTENT_TYPE,
        requestTime = requestTime,
        callContext = callContext,
    )

    /**
     * 构造 [HttpResponseData]。
     *
     * 位置参数严格按 Ktor 3.1.1 的 6 参数构造函数（见 ktor-client-core 的
     * `HttpRequest.kt`）：`(statusCode, requestTime, headers, version, body, callContext)`。
     * 这里用位置参数而不是命名参数，是为了和 Ktor 自己 MockEngine 的写法完全一致，
     * 避免任何参数名差异带来的风险。
     *
     * body 传 [ByteReadChannel]——这是引擎层约定的响应体类型，后面的
     * DefaultTransform 会把它读成文本或 bytes。
     */
    private fun textResponse(
        status: HttpStatusCode,
        text: String,
        contentType: String,
        requestTime: GMTDate,
        callContext: CoroutineContext,
    ): HttpResponseData = HttpResponseData(
        status,
        requestTime,
        headersOf(HttpHeaders.ContentType, contentType),
        HttpProtocolVersion.HTTP_1_1,
        ByteReadChannel(text.encodeToByteArray()),
        callContext,
    )

    private companion object {
        /** 与 VrcxCoreClient 里那套 JSON 信封配合使用的默认响应类型。 */
        const val DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"

        /** 我们自己造的错误响应，用纯文本，方便直接看懂。 */
        const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
    }
}

/**
 * 请求头 -> `[["k","v"], ...]`。
 *
 * 用 Ktor 的 `mergeHeaders(requestHeaders, content)`（`@InternalAPI`，3.1.1 的
 * `io.ktor.client.engine.Utils.kt`）来拼：引擎的职责之一就是把请求头与
 * [OutgoingContent] 自带的头合并，`Content-Type` / `Content-Length` / 默认
 * `User-Agent` 常常只挂在 content 上。自己只搬 `data.headers` 会丢掉 POST 的
 * `Content-Type`，核心那边就不知道该怎么解析 body 了。
 *
 * 少数逐跳/hop-by-hop 头会被丢掉：
 * - `Host` / `Connection` / `Upgrade` / `Transfer-Encoding` / `Content-Length`：
 *   描述的是「HTTP 连接」而不是「这次调用」，核心会按自己的连接重算；
 * - `Accept-Encoding`：如果核心的 reqwest 自己解压，我们再把 gzip 传下去会让
 *   「解压后的字符串」和「声称的编码」对不上，所以交给核心决定。
 */
@OptIn(InternalAPI::class)
private fun Headers.toJsonPairs(content: OutgoingContent): JsonArray {
    // 注意：JsonArrayBuilder 的元素类型是 JsonElement，没有 add(String) 重载，
    // 所以这里用 JsonPrimitive 显式包一层（这样也不依赖具体 kotlinx-serialization 小版本）。
    val pairs = mutableListOf<JsonElement>()
    mergeHeaders(this, content) { name, value ->
        if (name.lowercase() !in SKIPPED_HEADERS) {
            pairs.add(JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value))))
        }
    }
    return JsonArray(pairs)
}

private val SKIPPED_HEADERS = setOf(
    "host",
    "connection",
    "upgrade",
    "transfer-encoding",
    "content-length",
    "accept-encoding",
)

/**
 * 取出请求体文本；没有请求体时返回 [JsonNull]（协议里是 `"body": null`）。
 *
 * 分支与 Ktor 3.1.1 自己的 MockEngine 保持一致：`TextContent` 之类本身就是
 * [OutgoingContent.ByteArrayContent]，所以「文本 / bytes」两种都由第一个分支覆盖；
 * multipart 这类是 [OutgoingContent.WriteChannelContent]，需要边写边读。
 *
 * 注意：总线只能传字符串，所以二进制请求体（例如上传头像文件）会在这里退化成
 * UTF-8 字符串，语义已经不对了——这条路只适合 JSON/表单这类文本请求。
 */
@OptIn(DelicateCoroutinesApi::class)
private suspend fun OutgoingContent.readBodyTextOrNull(): JsonElement = when (this) {
    is OutgoingContent.ContentWrapper -> delegate().readBodyTextOrNull()
    is OutgoingContent.ByteArrayContent -> bytes().toBodyElement()
    is OutgoingContent.ReadChannelContent -> readFrom().toByteArray().toBodyElement()
    is OutgoingContent.WriteChannelContent -> {
        // 写通道内容必须同时有人读，否则缓冲区写满后双方互相等待。
        // 这里照抄 MockUtils 的写法（GlobalScope + Unconfined）。
        val channel = ByteChannel()
        GlobalScope.launch(Dispatchers.Unconfined) {
            writeTo(channel)
            channel.close()
        }
        channel.toByteArray().toBodyElement()
    }
    // 含 NoContent（GET/HEAD）与 ProtocolUpgrade（WebSocket，本引擎不支持）。
    else -> JsonNull
}

private fun ByteArray.toBodyElement(): JsonElement =
    if (isEmpty()) JsonNull else JsonPrimitive(decodeToString())
