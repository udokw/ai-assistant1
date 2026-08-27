// LLM 客户端：统一封装 DeepSeek / 豆包（火山方舟）。
// 两者均兼容 OpenAI Chat Completions 协议并支持 function calling，
// 仅 baseUrl / model 不同。使用 OkHttp + kotlinx-serialization-json 直接实现，
// 不引入第三方 OpenAI SDK，依赖最小。
//
// 依赖：
//   implementation("com.squareup.okhttp3:okhttp:4.12.0")
//   implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

package com.example.aiapkanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 单个 LLM 供应商配置（DeepSeek / 豆包等）。
 *  JSON 用 snake_case（api_key/base_url），Kotlin 属性用 camelCase。 */
@Serializable
data class ProviderConfig(
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    @SerialName("api_mode") val apiMode: String = "chat"
)

/** 顶层 LLM 配置：当前 provider + 各 provider 的具体参数。 */
@Serializable
data class LlmConfig(
    val provider: String,
    val providers: Map<String, ProviderConfig>
)

/** OpenAI Chat 协议里的单条消息。
 *  - role: system / user / assistant / tool
 *  - assistant 角色可能带 tool_calls
 *  - tool 角色必须带 tool_call_id */
@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/** chat() 一次调用的解析结果（assistant 角色语义）。 */
@Serializable
data class AssistantMessage(
    val content: String?,
    val toolCalls: List<ToolCall>?
)

class LlmException(message: String) : RuntimeException(message)

/**
 * 支持 DeepSeek / 豆包(火山方舟) 的流式 LLM 客户端。
 *
 * 两者都兼容 OpenAI Chat Completions 协议：
 *  - POST {baseUrl}/chat/completions
 *  - Authorization: Bearer {apiKey}
 *  - body: model / messages / temperature / stream=true
 *  - 有 tools 时附带 tools 与 tool_choice="auto"
 *
 * 流式响应是 SSE：每行 `data: {json}`，逐行解析：
 *  - delta.content 拼接并回调 onDelta
 *  - delta.tool_calls 按 index 累积 id / name / arguments
 *  - 遇到 `data: [DONE]` 结束
 */
class LlmClient(
    private val config: LlmConfig,
    private val onDelta: suspend (String) -> Unit
) {
    val provider: String
        get() = config.provider

    val model: String
        get() = current().model

    // encodeDefaults=true：确保 ToolCall.type="function" 等默认值也被序列化，
    // 否则 DeepSeek/豆包 会因 tool_calls 缺少 type 字段返回 400。
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // LLM 流式可能慢
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun current(): ProviderConfig =
        config.providers[config.provider]
            ?: throw LlmException("provider '${config.provider}' 未在 providers 中配置")

    /**
     * 发起一次流式对话。
     * @param messages 对话历史（含 system）
     * @param tools 可选的 function calling 工具定义
     * @param temperature 采样温度
     * @return AssistantMessage，含拼接后的 content 和累积的 toolCalls
     */
    suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>? = null,
        temperature: Float = 0.2f
    ): AssistantMessage = withContext(Dispatchers.IO) {
        // Responses API 模式：把 messages 转成 input 文本，调 responses()
        if (current().apiMode == "responses") {
            val inputText = messages.joinToString("\n") { msg -> "${msg.role}: ${msg.content ?: ""}" }
            val resp = responses(inputText)
            onDelta(resp)
            return@withContext AssistantMessage(content = resp, toolCalls = null)
        }
        val providerConfig = current()
        val url = providerConfig.baseUrl.trimEnd('/') + "/chat/completions"

        val body = buildJsonObject {
            put("model", providerConfig.model)
            put("messages", encodeMessages(messages))
            put("temperature", temperature)
            put("stream", true)
            if (!tools.isNullOrEmpty()) {
                put("tools", encodeTools(tools))
                put("tool_choice", "auto")
            }
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(req).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(500)
                val reqSummary = body.take(300)
                // 豆包(火山方舟) 404 特殊提示：model 未开通或需要 endpoint ID
                val hint = if (config.provider == "doubao" && resp.code == 404) {
                    "\n\n[豆包提示] 火山方舟 404 通常因为模型未开通。\n" +
                    "1. 登录火山方舟控制台 https://console.volcengine.com/ark\n" +
                    "2. 在「模型推理」→「开通模型」中开通对应模型\n" +
                    "3. 或创建「推理接入点」获得 endpoint ID（ep-xxx），填入模型字段\n" +
                    "4. 拉取在线模型按钮可查看已开通的模型列表"
                } else ""
                throw LlmException("HTTP ${resp.code}\n响应: $errBody\n请求体前300字: $reqSummary$hint")
            }
            val source = resp.body?.source()
                ?: throw LlmException("响应体为空")

            val contentBuilder = StringBuilder()
            // index -> {id, name, arguments}，按出现顺序保留 index
            val toolCallParts = LinkedHashMap<Int, ToolCallAccumulator>()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val chunk = runCatching {
                    json.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: continue

                val choices = chunk["choices"]?.jsonArray ?: continue
                if (choices.isEmpty()) continue
                val choice = choices[0].jsonObject
                val delta = choice["delta"]?.jsonObject ?: continue

                // 1. 文本增量
                delta["content"]?.let { cEl ->
                    val c = cEl.jsonPrimitive.content
                    if (c.isNotEmpty()) {
                        contentBuilder.append(c)
                        onDelta(c)
                    }
                }

                // 2. tool_calls 增量（按 index 累积）
                delta["tool_calls"]?.let { tcEl ->
                    for (tcItem in tcEl.jsonArray) {
                        val tc = tcItem.jsonObject
                        val idx = tc["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val slot = toolCallParts.getOrPut(idx) { ToolCallAccumulator() }
                        tc["id"]?.jsonPrimitive?.content?.let { slot.id = it }
                        tc["function"]?.jsonObject?.let { fn ->
                            fn["name"]?.jsonPrimitive?.content?.let { slot.name += it }
                            fn["arguments"]?.jsonPrimitive?.content?.let { slot.arguments += it }
                        }
                    }
                }
            }

            val content = contentBuilder.toString().takeIf { it.isNotEmpty() }
            val toolCalls: List<ToolCall>? = if (toolCallParts.isEmpty()) null
            else toolCallParts.entries.sortedBy { it.key }.map { (idx, acc) ->
                ToolCall(
                    id = acc.id.ifEmpty { "call_$idx" },
                    type = "function",
                    function = FunctionCall(
                        name = acc.name,
                        arguments = acc.arguments
                    )
                )
            }

            AssistantMessage(content = content, toolCalls = toolCalls)
        }
    }

    /** 查询账户余额。
     *  - DeepSeek: GET /user/balance，解析 balance_infos 数组
     *  - 豆包(火山方舟): 无公开余额 API，返回提示文本 */
    suspend fun balance(): String = withContext(Dispatchers.IO) {
        val providerConfig = current()
        if (config.provider == "doubao") {
            return@withContext "豆包暂不支持余额查询，请在火山方舟控制台查看"
        }
        val url = providerConfig.baseUrl.trimEnd('/') + "/user/balance"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        resp.use { r ->
            val bodyText = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw LlmException("HTTP ${r.code}: $bodyText")
            }
            val obj = json.parseToJsonElement(bodyText).jsonObject
            val infos = obj["balance_infos"]?.jsonArray
            if (infos.isNullOrEmpty()) {
                return@withContext bodyText
            }
            val sb = StringBuilder()
            for ((i, item) in infos.withIndex()) {
                val info = item.jsonObject
                val currency = info["currency"]?.jsonPrimitive?.content ?: ""
                val total = info["total_balance"]?.jsonPrimitive?.content ?: ""
                val granted = info["granted_balance"]?.jsonPrimitive?.content ?: ""
                val tipped = info["tipped_balance"]?.jsonPrimitive?.content ?: ""
                sb.appendLine("账户$i ($currency):")
                sb.appendLine("  总余额: $total")
                sb.appendLine("  赠送余额: $granted")
                sb.appendLine("  充值余额: $tipped")
            }
            sb.toString().trim()
        }
    }

    /** 获取可用模型列表（GET /models，提取 data[].id）。 */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val providerConfig = current()
        val url = providerConfig.baseUrl.trimEnd('/') + "/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        resp.use { r ->
            val bodyText = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw LlmException("HTTP ${r.code}: $bodyText")
            }
            val obj = json.parseToJsonElement(bodyText).jsonObject
            val data = obj["data"]?.jsonArray ?: return@withContext emptyList()
            data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                .filter { it.isNotEmpty() }
        }
    }

    /** 调用 Responses API（OpenAI 新协议），非流式。
     *  POST /responses，body: {"model": model, "input": input}
     *  返回原始响应体文本（JSON 字符串）。 */
    suspend fun responses(input: String): String = withContext(Dispatchers.IO) {
        val providerConfig = current()
        val url = providerConfig.baseUrl.trimEnd('/') + "/responses"
        val body = buildJsonObject {
            put("model", providerConfig.model)
            put("input", input)
        }.toString()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        resp.use { r ->
            val bodyText = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw LlmException("HTTP ${r.code}: $bodyText")
            }
            bodyText
        }
    }

    private fun encodeMessages(messages: List<Message>): JsonElement =
        json.encodeToJsonElement(ListSerializer(Message.serializer()), messages)

    private fun encodeTools(tools: List<Tool>): JsonElement =
        json.encodeToJsonElement(ListSerializer(Tool.serializer()), tools)

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
    }

    suspend fun chatStream(
        history: List<Message>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val providerConfig = current()
        val url = providerConfig.baseUrl.trimEnd('/') + "/chat/completions"

        val body = buildJsonObject {
            put("model", providerConfig.model)
            put("messages", encodeMessages(history))
            put("stream", true)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(req).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(500)
                throw LlmException("HTTP ${resp.code}\n响应: $errBody")
            }
            val source = resp.body?.source()
                ?: throw LlmException("响应体为空")

            val contentBuilder = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val chunk = runCatching {
                    json.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: continue

                val choices = chunk["choices"]?.jsonArray ?: continue
                if (choices.isEmpty()) continue
                val choice = choices[0].jsonObject
                val delta = choice["delta"]?.jsonObject ?: continue

                delta["content"]?.let { cEl ->
                    val c = cEl.jsonPrimitive.content
                    if (c.isNotEmpty()) {
                        contentBuilder.append(c)
                        onDelta(c)
                    }
                }
            }
            contentBuilder.toString()
        }
    }

    suspend fun chatStreamMultimodal(
        history: List<Message>,
        text: String,
        imageBase64List: List<Pair<String, String>>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val providerConfig = current()
        val url = providerConfig.baseUrl.trimEnd('/') + "/chat/completions"

        val body = buildJsonObject {
            put("model", providerConfig.model)
            put("stream", true)
            put("messages", buildJsonArray {
                val historyWithoutLast = history.dropLast(1)
                for (msg in historyWithoutLast) {
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content ?: "")
                        if (msg.toolCallId != null) put("tool_call_id", msg.toolCallId)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                        for ((mimeType, base64) in imageBase64List) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:$mimeType;base64,$base64")
                                }
                            })
                        }
                    })
                })
            })
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(req).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(500)
                throw LlmException("HTTP ${resp.code}\n响应: $errBody")
            }
            val source = resp.body?.source()
                ?: throw LlmException("响应体为空")

            val contentBuilder = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val chunk = runCatching {
                    json.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: continue

                val choices = chunk["choices"]?.jsonArray ?: continue
                if (choices.isEmpty()) continue
                val choice = choices[0].jsonObject
                val delta = choice["delta"]?.jsonObject ?: continue

                delta["content"]?.let { cEl ->
                    val c = cEl.jsonPrimitive.content
                    if (c.isNotEmpty()) {
                        contentBuilder.append(c)
                        onDelta(c)
                    }
                }
            }
            contentBuilder.toString()
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val providerConfig = current()
            val url = providerConfig.baseUrl.trimEnd('/') + "/models"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${providerConfig.apiKey}")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                r.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }
}
