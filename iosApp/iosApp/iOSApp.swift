import SwiftUI
import ComposeApp
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(ScanAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    init(){
        MainViewControllerKt.StartKoin()
        // The core has to be up before any screen asks for a client. Without this call `isVrcxCoreReady()` is
        // false, login silently takes the plain Ktor path, and that path fails with "the backend did not
        // return a user" because it expects the core's user payload. Android does the same call synchronously
        // in MainActivity.onCreate; this line is what was missing on iOS.
        let coreReady = MainViewControllerKt.StartVrcxCore()
        print("[VrcxCore] started: \(coreReady)")
        CoreLogExport.copyOnLaunch()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        // iOS freezes the process in the background. On the way back the core's loopback connection pool can
        // be stale, which surfaces as `-1004 cannot connect to 127.0.0.1:<port>` on every command until the
        // app is killed. Telling the client to drop its pool once per activation is enough to avoid that.
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                MainViewControllerKt.OnAppBecameActive()
            }
        }
    }
}

/**
 * Copies the core's own log into the app's Documents folder on every launch.
 *
 * The core writes `error-log.txt` (panics, failed commands) next to its database in Application Support, which
 * is not reachable without a Mac. With `UIFileSharingEnabled` in Info.plist, Documents *is* reachable from the
 * Files app on the device, so bringing the file there turns "guess what the core did" into reading it.
 */
enum CoreLogExport {
    static func copyOnLaunch() {
        let fm = FileManager.default
        guard let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
              let documents = fm.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let source = support.appendingPathComponent("VRCX-0/error-log.txt")
        guard fm.fileExists(atPath: source.path) else { return }
        let destination = documents.appendingPathComponent("core-error-log.txt")
        try? fm.removeItem(at: destination)
        try? fm.copyItem(at: source, to: destination)
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
