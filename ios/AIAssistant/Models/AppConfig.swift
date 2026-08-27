import Foundation

// MARK: - 配置模型

struct McpConfig: Codable {
    var transport: String = "http"
    var url: String = ""
    var command: [String] = []
    var enabled: Bool = true
}

struct AgentConfig: Codable {
    var max_iterations: Int = 25
    var temperature: Double = 0.2
}

struct ProviderConfig: Codable {
    var api_key: String
    var base_url: String
    var model: String
    var api_mode: String = "chat"
}

struct LlmConfig: Codable {
    var provider: String
    var providers: [String: ProviderConfig]
}

struct AppConfig: Codable {
    var llm: LlmConfig
    var mcp: McpConfig = McpConfig()
    var agent: AgentConfig = AgentConfig()
    var workDirUri: String = ""
    var floatingEnabled: Bool = false

    // MARK: - 加载/保存

    static func load() -> AppConfig {
        let fm = FileManager.default
        let fileURL = configURL

        // 优先从应用私有目录读
        if fm.fileExists(atPath: fileURL.path),
           let data = try? Data(contentsOf: fileURL),
           let config = try? JSONDecoder().decode(AppConfig.self, from: data) {
            return config
        }

        // fallback: Bundle 中的默认配置
        if let url = Bundle.main.url(forResource: "config", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let config = try? JSONDecoder().decode(AppConfig.self, from: data) {
            return config
        }

        // 最终 fallback：硬编码默认值
        return .default
    }

    func save() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? encoder.encode(self) {
            try? data.write(to: Self.configURL, options: .atomic)
        }
    }

    static var configURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return dir.appendingPathComponent("config.json")
    }

    static let `default` = AppConfig(
        llm: LlmConfig(
            provider: "deepseek",
            providers: [
                "deepseek": ProviderConfig(
                    api_key: "",
                    base_url: "https://api.deepseek.com",
                    model: "deepseek-chat",
                    api_mode: "chat"
                ),
                "doubao": ProviderConfig(
                    api_key: "",
                    base_url: "https://ark.cn-beijing.volces.com/api/v3",
                    model: "doubao-seed-2-1-pro-260628",
                    api_mode: "chat"
                )
            ]
        ),
        mcp: McpConfig(
            transport: "http",
            url: "http://192.168.5.30:8787/mcp",
            command: [],
            enabled: true
        ),
        agent: AgentConfig(max_iterations: 25, temperature: 0.2),
        workDirUri: "",
        floatingEnabled: false
    )
}
