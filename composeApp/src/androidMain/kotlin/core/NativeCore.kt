package io.github.vrcmteam.vrcm.core

/**
 * VRCX-0 core, loaded in-process.
 *
 * The core is a Rust shared library: it opens the local SQLite database and
 * starts a loopback command bus (`POST /api/invoke`, `GET /api/events`) that the
 * UI talks to over HTTP. Only one call crosses the JNI boundary — at startup.
 */
object NativeCore {
    init {
        System.loadLibrary("vrcx_0_mobile")
    }

    /**
     * @param filesDir the app's data directory (the database lives here)
     * @param deviceName what this device reports to remote-sync presence, so the other machines see
     * a name instead of a bare id
     * @return `{"port":12345,"token":"..."}` or `{"error":"..."}`
     */
    @JvmStatic
    external fun start(filesDir: String, deviceName: String): String
}
