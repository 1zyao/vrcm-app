package io.github.vrcmteam.vrcm.network.api.economy

import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.economyBalanceGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.api.economy.data.CreditsBalanceData
import io.ktor.client.HttpClient

/**
 * Retrieves the authenticated user's VRChat economy data.
 *
 * Served by the backend (`app__vrchat_economy_balance_get`). The command name is ours - cloud has no
 * economy command - while the request behind it is the protocol's own `balance_get_input`.
 */
class EconomyApi(@Suppress("unused") private val client: HttpClient) {
    suspend fun getCreditsBalance(userId: String): CreditsBalanceData {
        require(ID_PATTERN.matches(userId)) { "Invalid user ID" }

        return CoreJson.decodeFromString(
            CreditsBalanceData.serializer(),
            vrcxCoreClient().economyBalanceGet(userId).bodyOrThrow(),
        )
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}
