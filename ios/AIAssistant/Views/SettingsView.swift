import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: ChatViewModel
    var onBack: () -> Void

    var body: some View {
        Form {
            // MARK: - LLM 配置
            Section("AI 模型配置") {
                Picker("供应商", selection: $viewModel.currentProvider) {
                    ForEach(viewModel.providers, id: \.self) { provider in
                        Text(provider).tag(provider)
                    }
                }

                SecureField("API Key", text: $viewModel.apiKey)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                TextField("Base URL", text: $viewModel.baseUrl)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                TextField("模型", text: $viewModel.model)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }

            // MARK: - MCP 配置
            Section("MCP 配置") {
                Toggle("启用 MCP", isOn: $viewModel.mcpEnabled)

                if viewModel.mcpEnabled {
                    TextField("MCP 服务地址", text: $viewModel.mcpUrl)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
            }

            // MARK: - Agent 配置
            Section("Agent 配置") {
                Stepper("最大迭代次数: \(viewModel.maxIterations)", value: $viewModel.maxIterations, in: 1...50)

                HStack {
                    Text("采样温度")
                    Spacer()
                    Text(String(format: "%.1f", viewModel.temperature))
                }
                Slider(value: $viewModel.temperature, in: 0...1, step: 0.1)
            }

            // MARK: - 额度/模型查询
            Section("查询") {
                Button {
                    viewModel.queryBalance()
                } label: {
                    HStack {
                        Image(systemName: "creditcard")
                        Text("查询余额")
                    }
                }

                if !viewModel.balanceResult.isEmpty {
                    Text(viewModel.balanceResult)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button {
                    viewModel.queryModels()
                } label: {
                    HStack {
                        Image(systemName: "list.bullet")
                        Text("查询可用模型")
                    }
                }

                if !viewModel.modelsResult.isEmpty {
                    Text(viewModel.modelsResult)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            // MARK: - 保存
            Section {
                Button {
                    viewModel.saveSettings()
                    onBack()
                } label: {
                    HStack {
                        Spacer()
                        Text("保存并重新连接")
                            .fontWeight(.medium)
                        Spacer()
                    }
                }
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("返回") { onBack() }
            }
        }
    }
}
