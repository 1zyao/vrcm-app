package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.engine.HttpClientEngine

/** The core only exists in the phone app; see ApiEngineFactory.kt. */
actual fun createApiEngine(): HttpClientEngine? = null
