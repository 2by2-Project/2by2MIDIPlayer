import SwiftUI
import PlayerKit

/// The iOS shell: every screen is Compose Multiplatform (PlayerKit, built from :iosApp by Gradle).
@main
struct MIDIPlayerApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose draws edge to edge and applies the safe area itself, as on Android.
                .ignoresSafeArea()
                .onOpenURL { url in MainViewControllerKt.openUrl(url: url) }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
