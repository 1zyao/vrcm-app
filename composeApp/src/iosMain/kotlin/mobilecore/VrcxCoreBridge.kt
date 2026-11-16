package io.github.vrcmteam.vrcm.mobilecore

/** See VrcxCoreBridge.desktop.kt - the core is phone-only. */
actual fun vrcxCoreClient(): VrcxCoreClient =
    error("The VRCX-0 core runs inside the phone app only")

actual fun isVrcxCoreReady(): Boolean = false
