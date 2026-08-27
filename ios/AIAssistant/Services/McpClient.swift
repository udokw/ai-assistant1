import Foundation

// MARK: - MCP 客户端

/// MCP streamable-http 客户端（iOS / Swift）
/// 协议参考：MCP 2025-06-18 streamable-http
class McpStreamableHttpClient {
    private let endpoint: String
    private let clientName: String
    private let clientVersion: String
    private let protocolVersion: String
    private var sessionId: String?
    private var nextId = 1

    init(endpoint: String,
         clientName: String = "ios-mcp-client",
         clientVersion: String = "1.0.0",
         protocolVersion: String = "2025-06-18") {
        self.endpoint = endpoint
        self.clientName = clientName
        self.clientVersion = clientVersion
        self.protocolVersion = protocolVersion
    }

    // MARK: - 1. 初始化握手

    func initialize() async throws -> [String: Any] {
        let id = nextId
        nextId += 1
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": "initialize",
            "params": [
                "protocolVersion": protocolVersion,
                "capabilities": [:],
                "clientInfo": ["name": clientName, "version": clientVersion]
            ]
        ]

        let (data, response) = try await postRequest(body: body)
        guard let http = response as? HTTPURLResponse else {
            throw McpError("无效响应")
        }

        // 抓取 session id
        if let sid = http.value(forHTTPHeaderField: "Mcp-Session-Id") {
            sessionId = sid
        }

        guard http.statusCode == 200 else {
            let errText = String(data: data, encoding: .utf8)?.prefix(500) ?? ""
            throw McpError("initialize -> HTTP \(http.statusCode): \(errText)")
        }

        let result = try parseJsonResponse(data: data, expectedId: id)

        // 发送 initialized 通知
        try? await sendNotification("notifications/initialized")

        return result
    }

    // MARK: - 2. 列出工具

    func listTools() async throws -> [String: Any] {
        let id = nextId
        nextId += 1
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/list",
            "params": [:]
        ]
        return try await callMethod(id: id, body: body)
    }

    // MARK: - 3. 调用工具

    func callTool(name: String, arguments: [String: Any]) async throws -> [String: Any] {
        let id = nextId
        nextId += 1
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/call",
            "params": [
                "name": name,
                "arguments": arguments
            ]
        ]
        return try await callMethod(id: id, body: body)
    }

    // MARK: - 连接测试

    func testConnection() async -> Bool {
        do {
            let body: [String: Any] = [
                "jsonrpc": "2.0",
                "id": 9999,
                "method": "ping",
                "params": [:]
            ]
            let (_, response) = try await postRequest(body: body)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            return code >= 200 && code < 300
        } catch {
            return false
        }
    }

    // MARK: - 私有方法

    private func callMethod(id: Int, body: [String: Any]) async throws -> [String: Any] {
        let (data, response) = try await postRequest(body: body)
        guard let http = response as? HTTPURLResponse else {
            throw McpError("无效响应")
        }

        if http.statusCode < 200 || http.statusCode >= 300 {
            let errText = String(data: data, encoding: .utf8)?.prefix(500) ?? ""
            throw McpError("HTTP \(http.statusCode): \(errText)")
        }

        let ct = (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        if ct.contains("text/event-stream") {
            // SSE 响应：解析流，找匹配 id 的 response
            return try parseSSEStream(data: data, expectedId: id)
        } else {
            // 单条 JSON
            return try parseJsonResponse(data: data, expectedId: id)
        }
    }

    private func sendNotification(_ method: String, params: [String: Any] = [:]) async throws {
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "method": method,
            "params": params
        ]
        var req = URLRequest(url: URL(string: endpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        if let sid = sessionId {
            req.setValue(sid, forHTTPHeaderField: "Mcp-Session-Id")
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 30

        let (_, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code != 200 && code != 202 {
            throw McpError("notification \(method) failed: HTTP \(code)")
        }
    }

    private func postRequest(body: [String: Any]) async throws -> (Data, URLResponse) {
        var req = URLRequest(url: URL(string: endpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        if let sid = sessionId {
            req.setValue(sid, forHTTPHeaderField: "Mcp-Session-Id")
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 120

        return try await URLSession.shared.data(for: req)
    }

    private func parseJsonResponse(data: Data, expectedId: Int) throws -> [String: Any] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw McpError("无法解析 JSON 响应: \(String(data: data, encoding: .utf8) ?? "")")
        }
        guard let respId = json["id"] as? Int else {
            throw McpError("响应缺少 id: \(json)")
        }
        if respId != expectedId {
            throw McpError("id 不匹配: expected=\(expectedId) got=\(respId)")
        }
        if let error = json["error"] {
            throw McpError("JSON-RPC error: \(error)")
        }
        return json
    }

    private func parseSSEStream(data: Data, expectedId: Int) throws -> [String: Any] {
        let lines = String(data: data, encoding: .utf8)?.components(separatedBy: "\n") ?? []
        for line in lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload.isEmpty { continue }

            guard let jsonData = payload.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else { continue }

            // 跳过无 id 的通知
            guard let idField = json["id"] as? Int else { continue }
            if idField == expectedId {
                if let error = json["error"] {
                    throw McpError("JSON-RPC error: \(error)")
                }
                return json
            }
        }
        throw McpError("SSE 流中未找到 id=\(expectedId) 的响应")
    }
}

class McpError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
    init(_ message: String) { self.message = message }
}
