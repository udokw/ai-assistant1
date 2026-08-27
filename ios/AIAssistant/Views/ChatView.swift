import SwiftUI
import PhotosUI

struct ChatView: View {
    @ObservedObject var viewModel: ChatViewModel
    var onOpenSettings: () -> Void
    var onOpenWorkDir: () -> Void
    var onOpenProfile: () -> Void

    @State private var photosPickerItem: PhotosPickerItem?

    var body: some View {
        VStack(spacing: 0) {
            // 顶栏
            topBar

            // 消息列表
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.messages) { msg in
                            MessageBubble(message: msg)
                                .id(msg.id)
                        }

                        // 流式输出
                        if viewModel.isBusy && !viewModel.streamingText.isEmpty {
                            HStack {
                                Text(viewModel.streamingText)
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(12)
                                    .background(Color(.systemGray6))
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                Spacer()
                            }
                        }

                        if viewModel.isBusy {
                            HStack {
                                ProgressView()
                                    .scaleEffect(0.8)
                                Text("正在思考...")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(.leading, 12)
                        }
                    }
                    .padding()
                }
                .onChange(of: viewModel.messages.count) { _ in
                    if let last = viewModel.messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
                .onChange(of: viewModel.streamingText) { _ in
                    withAnimation { proxy.scrollTo(viewModel.messages.last?.id ?? UUID(), anchor: .bottom) }
                }
            }

            // 附件预览
            if !viewModel.pendingAttachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(viewModel.pendingAttachments.enumerated()), id: \.element.id) { index, att in
                            HStack(spacing: 4) {
                                Image(systemName: att.type == .image ? "photo" : "doc")
                                    .font(.caption)
                                Text(att.name)
                                    .font(.caption)
                                    .lineLimit(1)
                                Button {
                                    viewModel.removeAttachment(at: index)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color(.systemGray5))
                            .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.bottom, 4)
            }

            // 输入栏
            inputBar
        }
        .background(Color(.systemBackground))
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: 12) {
            // 连接状态光点
            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
                .overlay {
                    if viewModel.connectionState == .connecting {
                        Circle()
                            .stroke(statusColor.opacity(0.5), lineWidth: 4)
                            .scaleEffect(1.3)
                            .animation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true), value: viewModel.connectionState)
                    }
                }

            Text(viewModel.status)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Spacer()

            Button(action: onOpenWorkDir) {
                Image(systemName: "folder")
                    .font(.body)
            }

            Button(action: onOpenProfile) {
                Image(systemName: "person.circle")
                    .font(.body)
            }

            Button(action: onOpenSettings) {
                Image(systemName: "gearshape")
                    .font(.body)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
        .overlay(alignment: .bottom) {
            Divider()
        }
    }

    private var statusColor: Color {
        switch viewModel.connectionState {
        case .connected: return .green
        case .connecting: return .yellow
        case .disconnected: return .red
        }
    }

    // MARK: - 输入栏

    private var inputBar: some View {
        HStack(spacing: 8) {
            // 附件按钮
            Menu {
                Button {
                    // 文件选择
                } label: {
                    Label("选择文件", systemImage: "doc")
                }

                PhotosPicker(selection: $photosPickerItem, matching: .images) {
                    Label("选择图片", systemImage: "photo")
                }
            } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.tint)
            }
            .onChange(of: photosPickerItem) { _, newValue in
                guard let item = newValue else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        // 保存临时文件
                        let url = FileManager.default.temporaryDirectory
                            .appendingPathComponent("img_\(UUID().uuidString).jpg")
                        try? data.write(to: url)
                        viewModel.addAttachment(PendingAttachment(
                            url: url,
                            name: "image.jpg",
                            mimeType: "image/jpeg",
                            type: .image
                        ))
                    }
                }
            }

            // 输入框
            TextField("输入消息...", text: $viewModel.input, axis: .vertical)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Color(.systemGray6))
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .lineLimit(1...5)

            // 发送/停止
            if viewModel.isBusy {
                Button(action: viewModel.stopGeneration) {
                    Image(systemName: "stop.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red)
                }
            } else {
                Button(action: viewModel.send) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                        .foregroundStyle(viewModel.input.trimmingCharacters(in: .whitespaces).isEmpty ? .gray : .tint)
                }
                .disabled(viewModel.input.trimmingCharacters(in: .whitespaces).isEmpty && viewModel.pendingAttachments.isEmpty)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
        .overlay(alignment: .top) {
            Divider()
        }
    }
}

// MARK: - 消息气泡

struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == "user" {
                Spacer()
            }

            VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 4) {
                Text(message.content)
                    .font(.body)
                    .textSelection(.enabled)
                    .padding(12)
                    .background(message.role == "user" ? Color.accentColor.opacity(0.15) : Color(.systemGray6))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            if message.role == "assistant" {
                Spacer()
            }
        }
    }
}
