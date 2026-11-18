import SwiftUI
import ComposeApp
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(ScanAppDelegate.self) private var appDelegate
    init(){
        MainViewControllerKt.StartKoin()
        // The core has to be up before any screen asks for a client. Without this call `isVrcxCoreReady()` is
        // false, login silently takes the plain Ktor path, and that path fails with "the backend did not
        // return a user" because it expects the core's user payload. Android does the same call synchronously
        // in MainActivity.onCreate; this line is what was missing on iOS.
        let coreReady = MainViewControllerKt.StartVrcxCore()
        print("[VrcxCore] started: \(coreReady)")
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

final class ScanAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: session.role)
        configuration.delegateClass = ScanSceneDelegate.self
        return configuration
    }
}

final class ScanSceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        if options.shortcutItem?.type == "io.github.vrcmteam.vrcm.scan" {
            MainViewControllerKt.RequestQrScanner()
        }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        let handled = shortcutItem.type == "io.github.vrcmteam.vrcm.scan"
        if handled { MainViewControllerKt.RequestQrScanner() }
        completionHandler(handled)
    }
}
