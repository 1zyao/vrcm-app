@file:Suppress("unused", "FunctionName")
package io.github.vrcmteam.vrcm

import androidx.compose.ui.window.ComposeUIViewController
import io.github.vrcmteam.vrcm.di.commonModules
import io.github.vrcmteam.vrcm.di.modules.platformModule
import io.github.vrcmteam.vrcm.mobilecore.startVrcxCore
import io.github.vrcmteam.vrcm.presentation.screens.meetup.display.MeetupPresentationRootViewController
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController
import io.github.vrcmteam.vrcm.service.ScanLaunchInbox

private val scanLaunchInbox = ScanLaunchInbox()

/** Called by the iOS home-screen quick action, including before Compose is created. */
fun RequestQrScanner() = scanLaunchInbox.requestScan()

/** 用薄 root wrapper 承载 Compose，供身份牌展示页切换状态栏/home indicator 偏好。 */
fun MainViewController(): UIViewController =
    MeetupPresentationRootViewController(ComposeUIViewController { App(scanLaunchInbox = scanLaunchInbox) })

fun StartKoin() = startKoin {
    printLogger()
    modules(commonModules + platformModule)
}

/**
 * Starts the in-process core once, before any screen asks for a client.
 *
 * Mirrors the Android entry point, where `startVrcxCore` is called synchronously in `MainActivity.onCreate`:
 * it blocks until the core has bound its loopback port, so `isVrcxCoreReady()` is already true when the login
 * screen is built. Returns whether the core is up, because the Swift side cannot otherwise tell a failed
 * start from a successful one - the first symptom of a missing call here was a login that fell back to the
 * plain Ktor path and failed with "the backend did not return a user".
 */
fun StartVrcxCore(): Boolean = startVrcxCore() != null
