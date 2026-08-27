import SwiftUI

struct WorkDirView: View {
    @ObservedObject var viewModel: ChatViewModel
    var onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            if viewModel.workFiles.isEmpty {
                ContentUnavailableView(
                    "暂无文件",
                    systemImage: "folder",
                    description: Text("工作目录为空或未设置")
                )
            } else {
                List {
                    ForEach(viewModel.workFiles) { file in
                        WorkFileRow(
                            file: file,
                            isSelectMode: viewModel.selectMode,
                            isSelected: viewModel.selectedNames.contains(file.name),
                            onToggle: { viewModel.toggleSelect(file.name) }
                        )
                    }
                    .onDelete { offsets in
                        // 删除文件
                    }
                }
            }
        }
        .navigationTitle("工作目录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("返回") { onBack() }
            }

            ToolbarItem(placement: .navigationBarTrailing) {
                Button(viewModel.selectMode ? "完成" : "管理") {
                    viewModel.selectMode.toggle()
                    if !viewModel.selectMode { viewModel.selectedNames.removeAll() }
                }
            }
        }
    }
}

struct WorkFileRow: View {
    let file: WorkFile
    let isSelectMode: Bool
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack {
            if isSelectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? .tint : .secondary)
            }

            Image(systemName: file.isText ? "doc.text" : "doc")
                .foregroundStyle(.secondary)

            VStack(alignment: .leading) {
                Text(file.name)
                    .font(.body)
                Text(formatSize(file.sizeBytes))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if isSelectMode { onToggle() }
        }
    }

    private func formatSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
