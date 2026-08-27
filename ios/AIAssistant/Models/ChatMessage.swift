import Foundation
import SwiftUI

enum ConnectionState {
    case disconnected
    case connecting
    case connected
}

struct ChatMessage: Identifiable {
    let id = UUID()
    let role: String        // "user" / "assistant" / "tool"
    var content: String
    var isToolCall: Bool = false
    var toolName: String = ""
    var images: [Data] = []  // 图片缩略图数据
}

struct PendingAttachment: Identifiable {
    let id = UUID()
    let url: URL
    let name: String
    let mimeType: String
    let type: AttachmentType
}

enum AttachmentType {
    case file
    case image
    case video
}

struct WorkFile: Identifiable {
    let id = UUID()
    let name: String
    let path: String
    let sizeBytes: Int64
    let lastModified: Date
    var isText: Bool = false
}
