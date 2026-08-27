import SwiftUI

struct ProfileView: View {
    @ObservedObject var viewModel: ChatViewModel
    var onBack: () -> Void

    var body: some View {
        Form {
            // MARK: - 连接状态
            Section("连接状态") {
                HStack {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 10, height: 10)

                    Text(viewModel.status)
                        .font(.subheadline)

                    Spacer()

                    Button("重连") {
                        viewModel.connect()
                    }
                    .font(.caption)
                }

                if !viewModel.availableTools.isEmpty {
                    DisclosureGroup("可用工具 (\(viewModel.availableTools.count))") {
                        ForEach(viewModel.availableTools, id: \.self) { tool in
                            Text(tool)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            // MARK: - 额度查询
            Section("额度查询") {
                Button {
                    viewModel.queryBalance()
                } label: {
                    HStack {
                        Image(systemName: "creditcard")
                        Text("查询余额")
                        if viewModel.isQuerying { ProgressView() }
                    }
                }

                if !viewModel.balanceResult.isEmpty {
                    Text(viewModel.balanceResult)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            // MARK: - 关于
            Section("关于") {
                HStack {
                    Text("应用名称")
                    Spacer()
                    Text("AI 助手")
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text("版本")
                    Spacer()
                    Text("1.0")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("我的")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("返回") { onBack() }
            }
        }
    }

    private var statusColor: Color {
        switch viewModel.connectionState {
        case .connected: return .green
        case .connecting: return .yellow
        case .disconnected: return .red
        }
    }
}
