// Agent 循环：驱动 LLM 调用 MCP 工具分析 APK。
//
// 依赖：
//   implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
//   以及 com.example.aiapkanalyzer.mcp.McpStreamableHttpClient（来自 mcp 包）

package com.example.aiapkanalyzer

import com.example.aiapkanalyzer.mcp.McpStreamableHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentException(message: String) : RuntimeException(message)

/**
 * Agent 循环：维护对话历史，反复调用 LLM，并在 LLM 触发 tool_calls 时
 * 通过 McpStreamableHttpClient 执行对应工具，将结果回填给 LLM 继续。
 *
 * @param llm 已配置 onDelta 回调的 LLM 客户端（流式输出文字给 UI）
 * @param mcp MT 管理器 MCP 客户端（listTools / callTool）
 * @param maxIter 单轮对话最多调用 LLM 的次数，避免死循环
 * @param temperature LLM 采样温度
 */
class Agent(
    private val llm: LlmClient,
    private val mcp: McpStreamableHttpClient,
    private val maxIter: Int = 25,
    private val temperature: Float = 0.2f
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 完整对话历史，初始仅含 system 提示。 */
    private val messages: MutableList<Message> = mutableListOf(
        Message(role = "system", content = SYSTEM_PROMPT)
    )

    /** 已从 MCP 拉取并转换为 OpenAI Tool 格式的工具列表。 */
    private var tools: List<Tool> = emptyList()

    /** 从 MCP 拉取工具列表并转换为 LLM 可识别的 Tool 格式。 */
    suspend fun refreshTools(): List<String> {
        val resp = mcp.listTools().jsonObject
        val toolsArr = resp["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: throw AgentException("listTools 响应缺少 result.tools: $resp")
        tools = toolsArr.map { elem -> toTool(elem.jsonObject) }
        return tools.map { it.function.name }
    }

    fun toolNames(): List<String> = tools.map { it.function.name }

    /**
     * Agent 主循环：
     *  1. 把用户消息加入 messages
     *  2. 反复调 llm.chat；如果 LLM 返回 tool_calls，则逐个调用对应 MCP 工具，
     *     把结果作为 role="tool" 的消息回填，让 LLM 看到结果继续推理
     *  3. 直到 LLM 返回普通文本回复（无 tool_calls）或达到 maxIter 上限
     */
    suspend fun run(userInput: String) {
        messages.add(Message(role = "user", content = userInput))

        var iter = 0
        while (iter < maxIter) {
            iter++
            val asst = llm.chat(messages, tools, temperature)

            // assistant 消息加入历史（携带可能的 tool_calls）
            messages.add(
                Message(
                    role = "assistant",
                    content = asst.content,
                    toolCalls = asst.toolCalls
                )
            )

            val toolCalls = asst.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                // 普通回复，本轮结束
                return
            }

            // 逐个执行 tool_call
            for (tc in toolCalls) {
                val args = parseArgs(tc.function.arguments)
                val resultText = try {
                    // callTool 返回 JsonObject: { "result": { "content": [...], "isError"? } }
                    val resp = mcp.callTool(tc.function.name, args).jsonObject
                    toolResultToString(resp)
                } catch (e: Exception) {
                    "[工具异常] ${e.message ?: e.toString()}"
                }
                messages.add(
                    Message(
                        role = "tool",
                        content = resultText,
                        toolCallId = tc.id
                    )
                )
            }
            // 继续循环：让 LLM 看到工具结果后决定下一步
        }
        // 已达最大迭代上限，本轮结束
    }

    /** 清空对话历史，仅保留 system 提示。 */
    fun clear() {
        messages.clear()
        messages.add(Message(role = "system", content = SYSTEM_PROMPT))
    }

    /** 把 MCP 工具描述（含 name/description/inputSchema）转为 OpenAI Tool 格式。 */
    private fun toTool(mcpTool: JsonObject): Tool {
        val name = mcpTool["name"]?.jsonPrimitive?.content ?: ""
        val description = mcpTool["description"]?.jsonPrimitive?.content ?: ""
        // MCP 的 input_schema 直接就是 JSON Schema，可作为 parameters
        // 注意 mcp 2.0 用 snake_case（input_schema），旧版用 inputSchema，两者都兼容
        val inputSchema = (mcpTool["input_schema"]?.let { it as? JsonObject }
            ?: mcpTool["inputSchema"]?.let { it as? JsonObject }
            ?: buildJsonObject {})
        return Tool(
            type = "function",
            function = FunctionDef(
                name = name,
                description = description,
                parameters = inputSchema
            )
        )
    }

    /** 把 tool_call.function.arguments（字符串）解析为 JsonObject，失败或空则返回空对象。 */
    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return buildJsonObject {}
        return runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: buildJsonObject {}
    }

    /**
     * 把 callTool 返回的 JsonObject 转为字符串，作为 tool 消息 content。
     * 响应形如：
     *   { "result": { "content": [ {"type":"text","text":"..."}, ... ], "isError"?: bool } }
     * 把 content 数组里所有 type=text 的 text 字段拼接；若 isError=true 加前缀。
     */
    private fun toolResultToString(resp: JsonObject): String {
        val result = resp["result"]?.jsonObject
            ?: return resp.toString()
        val isError = result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val contentArr = result["content"]?.jsonArray
            ?: return result.toString()

        val sb = StringBuilder()
        if (isError) sb.append("[工具返回错误] ")
        for (item in contentArr) {
            val obj = item.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "text") {
                obj["text"]?.jsonPrimitive?.content?.let { sb.append(it) }
            }
        }
        return sb.toString().ifEmpty { result.toString() }
    }

    companion object {
        const val SYSTEM_PROMPT: String = """你是一名资深的 Android 逆向分析助手，可通过 MT 管理器提供的 MCP 工具分析编译后的 APK。

可用工具由 MCP 服务（MT 管理器）提供，核心能力与推荐流程如下：
1. mt_apk_list_available_apks：列出可分析的 APK（含当前 APK）。目标未知时先用它。
2. mt_apk_open(path, temporary)：打开 APK 得到 workspaceId。临时分析用 temporary=true；需复用则 false。
3. mt_apk_list(workspaceId, view=zip_entries|dex_classes|resource_table)：浏览 ZIP 条目、dex 类、资源表。
4. mt_apk_search(workspaceId, target, query...)：按关键字/正则搜索。未知目标先用 target=overview。
5. mt_apk_dex_outline_class(locator=dex_class:Lcom/x/Foo;)：查看某类的字段与方法清单。
6. mt_apk_read_text(locator=...)：读取 smali / 解码后的 AXML / zip 文本条目。
7. mt_apk_dex_xref(locator, methodResolution...)：查找方法/字段/类的交叉引用。
8. mt_apk_resource_read(reads=[{locator,variant}])：批量读取 resources.arsc 资源值。
9. mt_apk_native_inspect / mt_apk_native_read_items / mt_apk_native_map_address / mt_apk_native_xref / mt_apk_native_disassemble / mt_apk_native_function_cfg：分析 .so 原生库。
10. mt_apk_continue：所有分页结果都用它翻页（原样复制 nextCursor）。

工作准则：
- 先打开 APK 再分析；分析中始终复用同一 workspaceId，editSessionId 传空字符串表示基础工作区。
- 分步取证：先列清单/搜索定位，再读具体 smali 或资源，再做交叉引用。不要一次猜测全部答案。
- 工具参数必须完整且类型正确（如 mt_apk_open 的 path 与 temporary 都是必填）。
- locator 要原样复制工具返回的字符串，不要手写或用 Java 包名充当 locator。
- boolean 参数（如 temporary、caseSensitive）必须使用真正的布尔值 true/false，不要传字符串 "true"。
- 结果分页时用 mt_apk_continue 翻页，不要把"没有更多结果"当作"不存在"。
- 用中文回答，结论先行，再附关键证据（类名/方法/字符串/资源 id）。
- 本工具只做只读分析，不修改 APK。"""
    }
}
