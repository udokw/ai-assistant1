import SwiftUI

struct ContentView: View {
    @ObservedObject var viewModel: ChatViewModel
    @State private var currentPage: String = "chat"

    var body: some View {
        NavigationStack {
            ZStack {
                switch currentPage {
                case "settings":
                    SettingsView(viewModel: viewModel, onBack: { currentPage = "chat" })
                case "workdir":
                    WorkDirView(viewModel: viewModel, onBack: { currentPage = "chat" })
                case "profile":
                    ProfileView(viewModel: viewModel, onBack: { currentPage = "chat" })
                default:
                    ChatView(viewModel: viewModel,
                             onOpenSettings: { currentPage = "settings" },
                             onOpenWorkDir: { currentPage = "workdir" },
                             onOpenProfile: { currentPage = "profile" })
                }
            }
            .animation(.easeInOut(duration: 0.2), value: currentPage)
        }
    }
}
