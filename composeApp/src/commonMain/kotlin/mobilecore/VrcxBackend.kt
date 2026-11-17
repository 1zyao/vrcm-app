package io.github.vrcmteam.vrcm.mobilecore

import kotlin.concurrent.Volatile

/**
 * Which VRCX backend the app talks to.
 *
 * With an override set, the app is a plain frontend of a standalone VRCX backend
 * (server or desktop on the LAN) and does not need the in-process core at all -
 * that is the intended topology. The in-process core remains a fallback so the
 * same build still works offline.
 *
 * Nothing in the UI reads this directly; [vrcxCoreClient] resolves it.
 */
object VrcxBackend {
    /** e.g. "http://192.168.1.10:8891". Null means "use the in-process core". */
    @Volatile
    var baseUrlOverride: String? = null

    val isRemote: Boolean get() = !baseUrlOverride.isNullOrBlank()
}
