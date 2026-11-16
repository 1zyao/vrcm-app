package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.engine.HttpClientEngine

/**
 * Transport engine for the shared API client.
 *
 * On the phone the request must go through the in-process core (that is the whole
 * point of this fork: one VRChat client, owned by Rust). Everywhere else there is
 * no core, so the platform default engine must be used - returning the core engine
 * unconditionally would leave desktop/iOS with a client that answers 503 to every
 * request.
 *
 * `null` means "use the platform default engine".
 */
expect fun createApiEngine(): HttpClientEngine?
