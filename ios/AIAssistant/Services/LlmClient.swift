import Foundation

// MARK: - LLM 协议数据结构

struct Message: Codable {
    let role: String
    let content: String?
    let tool_calls: [ToolCall]?
    let tool_call_id: String?

    init(role: String, content: String? = nil, toolCalls: [ToolCall]? = nil, toolCallId: String? = nil) {
        self.role = role
        self.content = content
        self.tool_calls = toolCalls
        self.tool_call_id = toolCallId
    }

    enum CodingKeys: String, CodingKey {
        case role, content
        case tool_calls
        case tool_call_id
    }
}

struct ToolCall: Codable {
    let id: String
    let type: String
    let function: FunctionCall

    init(id: String, type: String = "function", function: FunctionCall) {
        self.id = id
        self.type = type
        self.function = function
    }
}

struct FunctionCall: Codable {
    let name: String
    let arguments: String
}

struct Tool: Codable {
    let type: String
    let function: FunctionDef
}

struct FunctionDef: Codable {
    let name: String
    let description: String
    let parameters: [String: AnyJSON]
}

/// 简易 JSON 值包装（替代 JsonObject）
enum AnyJSON: Codable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
    case array([AnyJSON])
    case object([String: AnyJSON])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let v = try? c.decode(String.self) { self = .string(v); return }
        if let v = try? c.decode(Double.self) { self = .number(v); return }
        if let v = try? c.decode(Bool.self) { self = .bool(v); return }
        if let v = try? c.decode([AnyJSON].self) { self = .array(v); return }
        if let v = try? c.decode([String: AnyJSON].self) { self = .object(v); return }
        self = .null
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    func rawValue() -> Any {
        switch self {
        case .string(let v): return v
        case .number(let v): return v
        case .bool(let v): return v
        case .null: return NSNull()
        case .array(let v): return v.map { $0.rawValue() }
        case .object(let v): return v.mapValues { $0.rawValue() }
        }
    }

    static func from(_ value: Any) -> AnyJSON {
        if let v = value as? String { return .string(v) }
        if let v = value as? Double { return .number(v) }
        if let v = value as? Int { return .number(Double(v)) }
        if let v = value as? Bool { return .bool(v) }
        if let v = value as? [Any] { return .array(v.map { .from($0) }) }
        if let v = value as? [String: Any] { return .object(v.mapValues { .from($0) }) }
        return .null
    }
}

struct AssistantMessage {
    let content: String?
    let toolCalls: [ToolCall]?
}

class LlmError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
    init(_ message: String) { self.message = message }
}

// MARK: - LLM 客户端

class LlmClient {
    let config: LlmConfig
    private let onDelta: (String) -> Void

    init(config: LlmConfig, onDelta: @escaping (String) -> Void) {
        self.config = config
        self.onDelta = onDelta
    }

    var provider: String { config.provider }
    var model: String { current().model }

    private func current() -> ProviderConfig {
        guard let pc = config.providers[config.provider] else {
            fatalError("provider '\(config.provider)' 未在 providers 中配置")
        }
        return pc
    }

    // MARK: - 流式对话（带工具调用）

    func chat(messages: [Message], tools: [Tool]? = nil, temperature: Double = 0.2) async throws -> AssistantMessage {
        let pc = current()
        let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/chat/completions"

        var body: [String: Any] = [
            "model": pc.model,
            "messages": messages.map { msg -> [String: Any] in
                var dict: [String: Any] = ["role": msg.role]
                if let c = msg.content { dict["content"] = c }
                if let tc = msg.tool_calls { dict["tool_calls"] = tc }
                if let tid = msg.tool_call_id { dict["tool_call_id"] = tid }
                return dict
            },
            "temperature": temperature,
            "stream": true
        ]
        if let tools = tools, !tools.isEmpty {
            body["tools"] = tools.map { t -> [String: Any] in
                ["type": t.type, "function": [
                    "name": t.function.name,
                    "description": t.function.description,
                    "parameters": t.function.parameters.rawValue()
                ]]
            }
            body["tool_choice"] = "auto"
        }

        let req = try buildRequest(url: url, body: body, apiKey: pc.api_key)
        let (bytes, response) = try await URLSession.shared.bytes(for: req)

        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LlmError("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        var contentBuilder = ""
        var toolCallParts: [Int: ToolCallAccumulator] = [:]

        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload.isEmpty || payload == "[DONE]" { continue }

            guard let jsonData = payload.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                  let choices = json["choices"] as? [[String: Any]],
                  let choice = choices.first,
                  let delta = choice["delta"] as? [String: Any] else { continue }

            if let content = delta["content"] as? String, !content.isEmpty {
                contentBuilder += content
                onDelta(content)
            }

            if let toolCalls = delta["tool_calls"] as? [[String: Any]] {
                for tc in toolCalls {
                    let idx = (tc["index"] as? Int) ?? 0
                    var slot = toolCallParts[idx] ?? ToolCallAccumulator()
                    if let id = tc["id"] as? String { slot.id = id }
                    if let fn = tc["function"] as? [String: Any] {
                        if let name = fn["name"] as? String { slot.name += name }
                        if let args = fn["arguments"] as? String { slot.arguments += args }
                    }
                    toolCallParts[idx] = slot
                }
            }
        }

        let content = contentBuilder.isEmpty ? nil : contentBuilder
        let toolCalls: [ToolCall]? = toolCallParts.isEmpty ? nil :
            toolCallParts.sorted(by: { $0.key < $1.key }).map { (idx, acc) in
                ToolCall(id: acc.id.isEmpty ? "call_\(idx)" : acc.id,
                         function: FunctionCall(name: acc.name, arguments: acc.arguments))
            }
        return AssistantMessage(content: content, toolCalls: toolCalls)
    }

    // MARK: - 纯文本流式对话

    func chatStream(history: [Message]) async throws -> String {
        let pc = current()
        let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/chat/completions"
        let body: [String: Any] = [
            "model": pc.model,
            "messages": history.map { msg -> [String: Any] in
                var dict: [String: Any] = ["role": msg.role]
                dict["content"] = msg.content ?? ""
                if let tid = msg.tool_call_id { dict["tool_call_id"] = tid }
                return dict
            },
            "stream": true
        ]

        let req = try buildRequest(url: url, body: body, apiKey: pc.api_key)
        let (bytes, response) = try await URLSession.shared.bytes(for: req)

        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LlmError("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        var content = ""
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload.isEmpty || payload == "[DONE]" { continue }

            if let jsonData = payload.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
               let choices = json["choices"] as? [[String: Any]],
               let choice = choices.first,
               let delta = choice["delta"] as? [String: Any],
               let c = delta["content"] as? String, !c.isEmpty {
                content += c
                onDelta(c)
            }
        }
        return content
    }

    // MARK: - 多模态流式对话

    func chatStreamMultimodal(history: [Message], text: String, images: [(String, String)]) async throws -> String {
        let pc = current()
        let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/chat/completions"

        var messagesArray: [[String: Any]] = []
        let historyWithoutLast = history.dropLast()
        for msg in historyWithoutLast {
            var dict: [String: Any] = ["role": msg.role]
            dict["content"] = msg.content ?? ""
            if let tid = msg.tool_call_id { dict["tool_call_id"] = tid }
            messagesArray.append(dict)
        }

        var contentArray: [[String: Any]] = [["type": "text", "text": text]]
        for (mimeType, base64) in images {
            contentArray.append([
                "type": "image_url",
                "image_url": ["url": "data:\(mimeType);base64,\(base64)"]
            ])
        }
        messagesArray.append(["role": "user", "content": contentArray])

        let body: [String: Any] = [
            "model": pc.model,
            "stream": true,
            "messages": messagesArray
        ]

        let req = try buildRequest(url: url, body: body, apiKey: pc.api_key)
        let (bytes, response) = try await URLSession.shared.bytes(for: req)

        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LlmError("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        var content = ""
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload.isEmpty || payload == "[DONE]" { continue }

            if let jsonData = payload.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
               let choices = json["choices"] as? [[String: Any]],
               let choice = choices.first,
               let delta = choice["delta"] as? [String: Any],
               let c = delta["content"] as? String, !c.isEmpty {
                content += c
                onDelta(c)
            }
        }
        return content
    }

    // MARK: - 余额查询

    func balance() async throws -> String {
        let pc = current()
        if config.provider == "doubao" {
            return "豆包暂不支持余额查询，请在火山方舟控制台查看"
        }
        let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/user/balance"
        var req = URLRequest(url: URL(string: url)!)
        req.setValue("Bearer \(pc.api_key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LlmError("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let infos = json["balance_infos"] as? [[String: Any]] else {
            return String(data: data, encoding: .utf8) ?? ""
        }

        var sb = ""
        for (i, info) in infos.enumerated() {
            let currency = info["currency"] as? String ?? ""
            let total = info["total_balance"] as? String ?? ""
            let granted = info["granted_balance"] as? String ?? ""
            let tipped = info["tipped_balance"] as? String ?? ""
            sb += "账户\(i) (\(currency)):\n  总余额: \(total)\n  赠送余额: \(granted)\n  充值余额: \(tipped)\n"
        }
        return sb.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - 模型列表

    func listModels() async throws -> [String] {
        let pc = current()
        let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/models"
        var req = URLRequest(url: URL(string: url)!)
        req.setValue("Bearer \(pc.api_key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LlmError("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dataArray = json["data"] as? [[String: Any]] else { return [] }
        return dataArray.compactMap { $0["id"] as? String }.filter { !$0.isEmpty }
    }

    // MARK: - 连接测试

    func testConnection() async -> Bool {
        do {
            let pc = current()
            let url = pc.base_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/models"
            var req = URLRequest(url: URL(string: url)!)
            req.setValue("Bearer \(pc.api_key)", forHTTPHeaderField: "Authorization")
            let (_, response) = try await URLSession.shared.data(for: req)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    // MARK: - 私有辅助

    private func buildRequest(url: String, body: [String: Any], apiKey: String) throws -> URLRequest {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 120
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return req
    }

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
    }
}
