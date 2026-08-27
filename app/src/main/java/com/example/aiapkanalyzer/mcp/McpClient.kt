// MCP Streamable HTTP 客户端最小可用实现（Android / Kotlin）
// 依赖：
//   implementation("com.squareup.okhttp3:okhttp:4.12.0")
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
//   implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
//
// 目标：连接 MT 管理器 MCP 服务（http://192.168.5.30:8787/mcp）等 streamable-http 服务端
// 协议：MCP 2025-03-26 / 2025-06-18 streamable-http

package com.example.aiapkanalyzer.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 最小 MCP streamable-http 客户端。
 *
 * 关键交互点（对照 spec 2025-03-26 streamable-http）：
 *  1. POST /mcp  body=initialize  -> 200 + Mcp-Session-Id 头
 *  2. POST /mcp  body=notifications/initialized -> 202 Accepted（无 body）
 *  3. POST /mcp  body=tools/list   -> 200 JSON 或 text/event-stream
 *  4. POST /mcp  body=tools/call   -> 同上
 *
 *  客户端必须在每个后续请求带上 Accept: application/json, text/event-stream
 *  若服务端在 initialize 响应里返回了 Mcp-Session-Id，后续所有请求都要带上该头。
 *  POST 响应可能是单条 JSON，也可能是 SSE 流（每条 `data: <json>`）；用 JSON-RPC id 匹配最终响应。
 */
class McpStreamableHttpClient(
    private val endpoint: String,                 // 例如 http://192.168.5.30:8787/mcp
    private val client: OkHttpClient = defaultClient(),
    private val clientName: String = "android-mcp-client",
    private val clientVersion: String = "1.0.0",
    private val protocolVersion: String = "2025-06-18",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nextId = AtomicLong(1L)
    private var sessionId: String? = null

    /** 1. 初始化握手，建立会话 */
    suspend fun initialize(): JsonObject = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            })
        }
        // initialize 必然拿到 application/json 单条响应（spec）
        val resp = postJsonAndWaitJson(body.toString())
        // 先抓 session id（body 未关闭时 header 可读）
        sessionId = resp.header("Mcp-Session-Id")
        // 再读 body 并关闭 Response，避免连接泄漏；先检查状态码
        val result = resp.use {
            if (!it.isSuccessful) {
                val errBody = it.body?.string().orEmpty().take(500)
                throw McpException("initialize -> HTTP ${it.code}: $errBody")
            }
            parseResult(it, id)
        }
        // 2. 发送 initialized 通知（无 id，无响应；spec 要求 202 Accepted）
        sendNotification("notifications/initialized")
        result
    }

    /** 3. 列出工具 */
    suspend fun listTools(): JsonElement {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/list")
            put("params", buildJsonObject {})
        }
        return callMethod(id, body.toString())
    }

    /** 4. 调用工具 */
    suspend fun callTool(name: String, arguments: JsonObject): JsonElement {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        }
        return callMethod(id, body.toString())
    }

    /** 通用：POST 一条 JSON-RPC 请求，解析单条 JSON 或 SSE 流里的最终 response */
    private suspend fun callMethod(id: Long, requestBody: String): JsonElement =
        withContext(Dispatchers.IO) {
            val resp = postJsonOrStream(requestBody)
            resp.use {
                // 先检查 HTTP 状态码，非 2xx 抛出含响应体的异常
                if (!it.isSuccessful) {
                    val errBody = it.body?.string().orEmpty().take(500)
                    throw McpException("${it.request.method} ${it.request.url} -> HTTP ${it.code}: $errBody")
                }
                val ct = it.body?.contentType()?.toString().orEmpty()
                if (ct.contains("text/event-stream")) {
                    // 解析 SSE 流，找到 id 匹配的 JSON-RPC response
                    readSseUntilMatchedId(it, id)
                } else {
                    // 单条 application/json
                    parseResult(it, id)
                }
            }
        }

    /** 发送通知（无 id，不期望响应；服务端应返回 202 Accepted） */
    private suspend fun sendNotification(method: String, params: JsonObject = buildJsonObject {}) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
            val req = buildPost(body.toString())
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 202) {
                    throw McpException("notification $method failed: HTTP ${resp.code}")
                }
            }
        }

    /** POST 一条 JSON-RPC 消息，期望返回 application/json 单条响应 */
    private fun postJsonAndWaitJson(body: String): Response {
        val req = buildPost(body)
        return client.newCall(req).execute()
    }

    /** POST 一条 JSON-RPC 消息，响应可能是 json 或 event-stream */
    private fun postJsonOrStream(body: String): Response {
        val req = buildPost(body)
        return client.newCall(req).execute()
    }

    private fun buildPost(body: String): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            // 关键：必须声明同时接受两种 content-type
            .header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        return builder.build()
    }

    /** 从 HTTP body 解析 JSON-RPC 响应，并校验 id 与 error */
    private fun parseResult(resp: Response, expectedId: Long): JsonObject {
        val text = resp.body?.string()
            ?: throw McpException("empty response body")
        val obj = json.parseToJsonElement(text).jsonObject
        val respId = obj["id"]?.jsonPrimitive?.long
            ?: throw McpException("response without id: $text")
        if (respId != expectedId) {
            throw McpException("id mismatch: expected=$expectedId got=$respId")
        }
        obj["error"]?.let { err ->
            throw McpException("JSON-RPC error: $err")
        }
        return obj
    }

    /** 解析 SSE 流：`data: {...}` 一行一事件，找 id 匹配的 JSON-RPC response */
    private fun readSseUntilMatchedId(resp: Response, expectedId: Long): JsonObject {
        val source = resp.body?.source()
            ?: throw McpException("empty SSE body")
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            // 一个 data 行可能就是一条 JSON-RPC 消息
            val element = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrNull() ?: continue
            // 跳过服务端主动推送的 notification（无 id）
            val idField = element["id"] ?: continue
            // id 可能是数字或字符串，统一转 Long
            val idVal = idField.jsonPrimitive.content.toLongOrNull() ?: continue
            if (idVal == expectedId) {
                element["error"]?.let { throw McpException("JSON-RPC error: $it") }
                return element
            }
        }
        throw McpException("SSE stream closed without response for id=$expectedId")
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = buildPost("""{"jsonrpc":"2.0","id":9999,"method":"ping","params":{}}""")
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..299
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // 长工具调用可能慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class McpException(message: String) : RuntimeException(message)
