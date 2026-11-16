package io.github.vrcmteam.vrcm.mobilecore

/**
 * The VRCX-0 core is a phone-only backend: on desktop the app is the core.
 *
 * These actuals exist so `commonMain` still compiles for every target; asking for
 * the core here is a programming error, not a runtime fallback.
 */
actual fun vrcxCoreClient(): VrcxCoreClient =
    error("The VRCX-0 core runs inside the phone app only")

actual fun isVrcxCoreReady(): Boolean = false
