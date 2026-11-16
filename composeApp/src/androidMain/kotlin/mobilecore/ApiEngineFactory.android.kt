package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.engine.HttpClientEngine

/**
 * Phone side: always transport through the in-process core.
 *
 * Deliberately not gated on `isVrcxCoreReady()`: the shared client is built during
 * DI, which runs before the core starts, so a readiness check here would pick the
 * platform engine for the whole process lifetime and silently bypass the core.
 * Requests issued in the narrow window before the core is up get a 503 from
 * CoreHttpEngine, which is visible and honest rather than a hidden fallback.
 */
actual fun createApiEngine(): HttpClientEngine? = CoreHttpEngine()
