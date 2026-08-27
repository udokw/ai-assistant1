import Foundation

/// Agent 循环：驱动 LLM 调用 MCP 工具
class Agent {
    private let llm: LlmClient
    private let mcp: McpStreamableHttpClient
    private let maxIter: Int
    private let temperature: Double

    private var messages: [Message] = []
    private var tools: [Tool] = []

    init(llm: LlmClient, mcp: McpStreamableHttpClient, maxIter: Int = 25, temperature: Double = 0.2) {
        self.llm = llm
        self.mcp = mcp
        self.maxIter = maxIter
        self.temperature = temperature
        self.messages = [Message(role: "system", content: Agent.systemPrompt)]
    }

    /// 从 MCP 拉取工具列表并转换为 OpenAI Tool 格式
    func refreshTools() async throws -> [String] {
        let resp = try await mcp.listTools()
        guard let result = resp["result"] as? [String: Any],
              let toolsArr = result["tools"] as? [[String: Any]] else {
            throw AgentError("listTools 响应缺少 result.tools: \(resp)")
        }
        tools = toolsArr.compactMap { toTool($0) }
        return tools.map { $0.function.name }
    }

    func toolNames() -> [String] { tools.map { $0.function.name } }

    /// Agent 主循环
    func run(userInput: String) async throws {
        messages.append(Message(role: "user", content: userInput))

        var iter = 0
        while iter < maxIter {
            iter += 1
            let asst = try await llm.chat(messages: messages, tools: tools, temperature: temperature)

            messages.append(Message(
                role: "assistant",
                content: asst.content,
                toolCalls: asst.toolCalls
            ))

            guard let toolCalls = asst.toolCalls, !toolCalls.isEmpty else {
                // 普通回复，结束
                return
            }

            // 逐个执行 tool_call
            for tc in toolCalls {
                let args = parseArgs(tc.function.arguments)
                var resultText = ""
                do {
                    let resp = try await mcp.callTool(name: tc.function.name, arguments: args)
                    resultText = toolResultToString(resp)
                } catch {
                    resultText = "[工具异常] \(error.localizedDescription)"
                }
                messages.append(Message(
                    role: "tool",
                    content: resultText,
                    toolCallId: tc.id
                ))
            }
            // 继续循环让 LLM 看到工具结果
        }
    }

    func clear() {
        messages = [Message(role: "system", content: Agent.systemPrompt)]
    }

    // MARK: - 私有方法

    private func toTool(_ mcpTool: [String: Any]) -> Tool? {
        guard let name = mcpTool["name"] as? String else { return nil }
        let description = mcpTool["description"] as? String ?? ""
        let inputSchema = (mcpTool["input_schema"] as? [String: Any])
            ?? (mcpTool["inputSchema"] as? [String: Any])
            ?? [:]

        // 将 [String: Any] 转换为 [String: AnyJSON]
        let params = inputSchema.mapValues { AnyJSON.from($0) }

        return Tool(type: "function", function: FunctionDef(
            name: name,
            description: description,
            parameters: params
        ))
    }

    private func parseArgs(_ raw: String) -> [String: Any] {
        guard !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return json
    }

    private func toolResultToString(_ resp: [String: Any]) -> String {
        guard let result = resp["result"] as? [String: Any] else {
            return "\(resp)"
        }
        let isError = (result["isError"] as? Bool) ?? false
        guard let contentArr = result["content"] as? [[String: Any]] else {
            return "\(result)"
        }

        var sb = ""
        if isError { sb += "[工具返回错误] " }
        for item in contentArr {
            if let type = item["type"] as? String, type == "text",
               let text = item["text"] as? String {
                sb += text
            }
        }
        return sb.isEmpty ? "\(result)" : sb
    }

    static let systemPrompt = """
    你是一名资深的 Android 逆向分析助手，可通过 MT 管理器提供的 MCP 工具分析编译后的 APK。

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
    - 本工具只做只读分析，不修改 APK。
    """
}

class AgentError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
    init(_ message: String) { self.message = message }
}
