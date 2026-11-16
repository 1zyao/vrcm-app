package io.github.vrcmteam.vrcm.mobilecore

/**
 * Access to the in-process VRCX-0 core.
 *
 * Implemented per platform because starting the core is platform work (on
 * Android it is a JNI call into a shared library). The rest of the app only ever
 * sees a [VrcxCoreClient], so screens and services stay platform-free.
 */
expect fun vrcxCoreClient(): VrcxCoreClient

/** True once the core has started and commands can be issued. */
expect fun isVrcxCoreReady(): Boolean
