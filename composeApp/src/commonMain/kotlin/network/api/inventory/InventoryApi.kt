package io.github.vrcmteam.vrcm.network.api.inventory

import io.github.vrcmteam.vrcm.network.api.inventory.data.InventoryData
import io.github.vrcmteam.vrcm.network.api.inventory.data.InventoryTemplateData
import io.github.vrcmteam.vrcm.network.api.inventory.data.RewardRedemptionRequestData
import io.github.vrcmteam.vrcm.network.api.inventory.data.RewardRedemptionResult
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.inventoryGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import kotlinx.serialization.builtins.ListSerializer
import io.github.vrcmteam.vrcm.mobilecore.rewardRedeem
import io.github.vrcmteam.vrcm.mobilecore.inventoryTemplateGet
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

enum class InventoryItemType(val value: String) {
    Bundle("bundle"),
    DroneSkin("droneskin"),
    Emoji("emoji"),
    PortalSkin("portalskin"),
    Prop("prop"),
    Sticker("sticker"),
    WarpEffect("warpeffect"),
}

enum class InventorySortOrder(val value: String) {
    NewestUpdated("newest"),
    NewestCreated("newest_created"),
    OldestUpdated("oldest"),
    OldestCreated("oldest_created"),
}

/** Retrieves inventory entries and template definitions, and redeems reward codes. */
class InventoryApi(private val client: HttpClient) {
    suspend fun getInventory(
        n: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        type: InventoryItemType? = null,
        archived: Boolean? = null,
        order: InventorySortOrder = InventorySortOrder.NewestUpdated,
    ): InventoryData {
        require(n in 1..MAX_PAGE_SIZE) { "Inventory page size must be between 1 and 100" }
        require(offset >= 0) { "Inventory offset must not be negative" }
        // The backend's inventory read has no `archived` filter, but omitting it already means
        // "not archived" - which is exactly what `archived = false` asks for, so that case maps to
        // leaving the parameter out. Only `archived = true` (list the archive) has no equivalent,
        // and it fails loudly instead of returning the wrong list.
        require(archived != true) { "the inventory command cannot list archived items" }
        require(order == InventorySortOrder.NewestUpdated) {
            "the inventory command only sorts by newest-updated, not ${order.value}"
        }

        return CoreJson.decodeFromString(
            InventoryData.serializer(),
            vrcxCoreClient().inventoryGet(
                n = n,
                offset = offset,
                types = type?.value,
            ).bodyOrThrow(),
        )
    }

    suspend fun getTemplate(templateId: String): InventoryTemplateData {
        require(ID_PATTERN.matches(templateId)) { "Invalid inventory template ID" }

        // Through the core (`app__vrchat_media_inventory_template_get`).
        return CoreJson.decodeFromString(
            InventoryTemplateData.serializer(),
            vrcxCoreClient().inventoryTemplateGet(templateId).bodyOrThrow(),
        )
    }

    suspend fun redeemReward(code: String): List<RewardRedemptionResult> {
        // Through the core (`app__vrchat_media_reward_redeem`).
        return CoreJson.decodeFromString(
            ListSerializer(RewardRedemptionResult.serializer()),
            vrcxCoreClient().rewardRedeem(code).bodyOrThrow(),
        )
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 60
        const val MAX_PAGE_SIZE = 100
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}


