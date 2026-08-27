import Foundation
import SwiftUI
import Combine

@MainActor
class ChatViewModel: ObservableObject {
    // MARK: - UI 状态
    @Published var messages: [ChatMessage] = []
    @Published var input: String = ""
    @Published var isBusy: Bool = false
    @Published var status: String = "连接中..."
    @Published var connectionState: ConnectionState = .connecting
    @Published var streamingText: String = ""
    @Published var availableTools: [String] = []
    @Published var pendingAttachments: [PendingAttachment] = []
    @Published var snackbarMessage: String = ""

    // 设置状态
    @Published var currentProvider: String = ""
    @Published var providers: [String] = []
    @Published var apiKey: String = ""
    @Published var baseUrl: String = ""
    @Published var model: String = ""
    @Published var mcpUrl: String = ""
    @Published var mcpEnabled: Bool = true
    @Published var maxIterations: Int = 25
    @Published var temperature: Double = 0.2
    @Published var balanceResult: String = ""
    @Published var modelsResult: String = ""
    @Published var isQuerying: Bool = false

    // 文件管理状态
    @Published var workFiles: [WorkFile] = []
    @Published var selectMode: Bool = false
    @Published var selectedNames: Set<String> = []

    // MARK: - 私有
    private var config: AppConfig = .load()
    private var mcp: McpStreamableHttpClient?
    private var agent: Agent?
    private var llm: LlmClient?
    private var currentTask: Task<Void, Never>?
    private var healthCheckTask: Task<Void, Never>?

    init() {
        loadSettings()
        connect()
        startHealthCheck()
    }

    // MARK: - 设置加载

    func loadSettings() {
        currentProvider = config.llm.provider
        providers = Array(config.llm.providers.keys).sorted()
        if let pc = config.llm.providers[config.llm.provider] {
            apiKey = pc.api_key
            baseUrl = pc.base_url
            model = pc.model
        }
        mcpUrl = config.mcp.url
        mcpEnabled = config.mcp.enabled
        maxIterations = config.agent.max_iterations
        temperature = config.agent.temperature
    }

    // MARK: - 连接

    func connect() {
        status = "连接中..."
        connectionState = .connecting

        Task {
            do {
                let client = LlmClient(config: config.llm) { [weak self] delta in
                    Task { @MainActor in
                        self?.streamingText += delta
                    }
                }
                self.llm = client

                let llmOk = await client.testConnection()
                if !llmOk {
                    self.status = "连接失败: 无法连接到 AI 服务"
                    self.connectionState = .disconnected
                    return
                }

                var toolNames: [String] = []
                var mcpOk = false

                if config.mcp.enabled && !config.mcp.url.isEmpty {
                    do {
                        let m = McpStreamableHttpClient(endpoint: config.mcp.url)
                        _ = try await m.initialize()
                        self.mcp = m
                        let a = Agent(llm: client, mcp: m,
                                      maxIter: config.agent.max_iterations,
                                      temperature: config.agent.temperature)
                        toolNames = try await a.refreshTools()
                        self.agent = a
                        mcpOk = true
                    } catch {
                        // MCP 连接失败，继续仅 AI 模式
                    }
                }

                self.status = mcpOk ? "已连接" : "已连接（仅 AI 模式）"
                self.connectionState = .connected
                self.availableTools = toolNames
            } catch {
                self.status = "连接失败: \(error.localizedDescription)"
                self.connectionState = .disconnected
            }
        }
    }

    // MARK: - 健康检查

    private func startHealthCheck() {
        healthCheckTask?.cancel()
        healthCheckTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10秒
                if Task.isCancelled { break }

                if connectionState == .connecting { continue }
                if isBusy { continue }

                let mcpEnabled = config.mcp.enabled && !config.mcp.url.isEmpty
                if !mcpEnabled { continue }

                guard let currentLlm = llm else { continue }

                let llmOk = await currentLlm.testConnection()
                if !llmOk {
                    connectionState = .disconnected
                    status = "连接已断开"
                    continue
                }

                if let currentMcp = mcp {
                    let mcpOk = await currentMcp.testConnection()
                    if !mcpOk {
                        mcp = nil
                        agent = nil
                        availableTools = []
                        status = "已连接（仅 AI 模式）"
                    } else {
                        connectionState = .connected
                        status = "已连接"
                    }
                } else {
                    // MCP 断开，尝试重连
                    let mcpOk = await reconnectMcp()
                    connectionState = .connected
                    status = mcpOk ? "已连接" : "已连接（仅 AI 模式）"
                }
            }
        }
    }

    private func reconnectMcp() async -> Bool {
        guard let currentLlm = llm else { return false }
        do {
            let m = McpStreamableHttpClient(endpoint: config.mcp.url)
            _ = try await m.initialize()
            mcp = m
            let a = Agent(llm: currentLlm, mcp: m,
                          maxIter: config.agent.max_iterations,
                          temperature: config.agent.temperature)
            let tools = try await a.refreshTools()
            agent = a
            availableTools = tools
            return true
        } catch {
            return false
        }
    }

    // MARK: - 发送消息

    func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let attachments = pendingAttachments
        guard !text.isEmpty || !attachments.isEmpty else { return }
        guard !isBusy else { return }
        guard llm != nil else {
            messages.append(ChatMessage(role: "assistant", content: "未连接 AI 模型，请先在设置中配置"))
            return
        }

        let hasImageAttachments = attachments.contains { $0.type == .image }
        let hasAgent = agent != nil

        var userMessageContent = text
        if !attachments.isEmpty {
            if !text.isEmpty { userMessageContent += "\n\n" }
            userMessageContent += "[附件: \(attachments.map { $0.name }.joined(separator: ", "))]"
        }

        messages.append(ChatMessage(role: "user", content: userMessageContent))
        input = ""
        isBusy = true
        streamingText = ""
        pendingAttachments = []

        currentTask = Task {
            do {
                let history = buildMessageHistory()
                let llmClient = self.llm!

                if hasImageAttachments && !hasAgent {
                    var imagesBase64: [(String, String)] = []
                    var textPrompt = text.isEmpty ? "请分析这些图片" : text

                    for att in attachments {
                        switch att.type {
                        case .image:
                            if let data = try? Data(contentsOf: att.url),
                               data.count < 4 * 1024 * 1024 {
                                let base64 = data.base64EncodedString()
                                imagesBase64.append((att.mimeType, base64))
                            } else {
                                textPrompt += "\n\n[图片 \(att.name) 太大，已跳过]"
                            }
                        case .file:
                            if let data = try? Data(contentsOf: att.url),
                               let content = String(data: data, encoding: .utf8) {
                                textPrompt += "\n\n[文件: \(att.name)]\n\(content.prefix(8000))"
                            }
                        case .video:
                            textPrompt += "\n\n[视频: \(att.name) (\(att.mimeType))]"
                        }
                    }

                    if !imagesBase64.isEmpty {
                        _ = try await llmClient.chatStreamMultimodal(history: history, text: textPrompt, images: imagesBase64)
                    } else {
                        _ = try await llmClient.chatStream(history: history)
                    }
                } else if let a = agent {
                    try await a.run(userInput: userMessageContent)
                } else {
                    _ = try await llmClient.chatStream(history: history)
                }

                let finalText = streamingText
                if !finalText.isEmpty {
                    messages.append(ChatMessage(role: "assistant", content: finalText))
                    streamingText = ""
                }
            } catch {
                let msg = error.localizedDescription
                messages.append(ChatMessage(role: "assistant", content: "[错误] \(msg)"))
                snackbarMessage = "发送失败: \(msg)"
            }

            isBusy = false
            currentTask = nil
        }
    }

    private func buildMessageHistory() -> [Message] {
        var result: [Message] = [Message(role: "system", content: "你是AI助力助手，回答要准确、简洁、实用。")]
        let recent = messages.suffix(10)
        for msg in recent {
            switch msg.role {
            case "user":
                result.append(Message(role: "user", content: msg.content))
            case "assistant":
                result.append(Message(role: "assistant", content: msg.content))
            default: break
            }
        }
        return result
    }

    // MARK: - 停止生成

    func stopGeneration() {
        currentTask?.cancel()
        currentTask = nil
        let streamed = streamingText
        if !streamed.isEmpty {
            messages.append(ChatMessage(role: "assistant", content: streamed + "\n\n[已停止]"))
        }
        isBusy = false
        streamingText = ""
    }

    // MARK: - 附件管理

    func addAttachment(_ attachment: PendingAttachment) {
        pendingAttachments.append(attachment)
    }

    func removeAttachment(at index: Int) {
        pendingAttachments.remove(at: index)
    }

    func clearAttachments() {
        pendingAttachments.removeAll()
    }

    // MARK: - 设置保存

    func saveSettings() {
        if let pc = config.llm.providers[currentProvider] {
            var newProviders = config.llm.providers
            newProviders[currentProvider] = ProviderConfig(
                api_key: apiKey,
                base_url: baseUrl,
                model: model,
                api_mode: pc.api_mode
            )
            config.llm = LlmConfig(provider: currentProvider, providers: newProviders)
        }
        config.mcp = McpConfig(
            transport: "http",
            url: mcpUrl,
            command: [],
            enabled: mcpEnabled
        )
        config.agent = AgentConfig(max_iterations: maxIterations, temperature: temperature)
        config.save()

        // 重新连接
        connect()
    }

    // MARK: - 余额/模型查询

    func queryBalance() {
        guard let llmClient = llm else { return }
        isQuerying = true
        Task {
            do {
                balanceResult = try await llmClient.balance()
            } catch {
                balanceResult = "查询失败: \(error.localizedDescription)"
            }
            isQuerying = false
        }
    }

    func queryModels() {
        guard let llmClient = llm else { return }
        isQuerying = true
        Task {
            do {
                let models = try await llmClient.listModels()
                modelsResult = models.joined(separator: "\n")
            } catch {
                modelsResult = "查询失败: \(error.localizedDescription)"
            }
            isQuerying = false
        }
    }

    // MARK: - 文件选择

    func toggleSelect(_ name: String) {
        if selectedNames.contains(name) {
            selectedNames.remove(name)
        } else {
            selectedNames.insert(name)
        }
    }

    // MARK: - Snackbar

    func clearSnackbar() {
        snackbarMessage = ""
    }
}
