package io.github.vrcmteam.vrcm.mobilecore

/**
 * Apple targets without the core static library.
 *
 * Compiled when `src/nativeInterop/cinterop/vrcx_core.a` is absent - i.e. on any machine that has not
 * built the iOS core (Windows cannot: `aws-lc-sys` and `ring` need an Apple clang and the iphoneos SDK).
 * The real implementation lives in `src/iosCore` and is selected by the same flag that registers the
 * cinterop, so the two can never disagree.
 *
 * A binary built this way still launches and shows what it can without a core, instead of failing to
 * link.
 */
actual fun vrcxCoreClient(): VrcxCoreClient =
    error(
        "The VRCX-0 core is not built into this iOS binary: " +
            "run the iOS CI, or build src/nativeInterop/cinterop/vrcx_core.a on a Mac.",
    )

actual fun isVrcxCoreReady(): Boolean = false

/** Same signature as the real one, so the app entry point compiles either way. Null: there is no core. */
fun startVrcxCore(): VrcxCoreClient? = null
