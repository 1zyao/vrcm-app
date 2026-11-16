package io.github.vrcmteam.vrcm.network.api.instances


import io.github.vrcmteam.vrcm.network.api.attributes.INSTANCES_API_SUFFIX
import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceCreationOptions
import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceCloseResponse
import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceData
import io.github.vrcmteam.vrcm.network.api.instances.data.toCreateInstanceRequest
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.instanceGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*


class InstancesApi(private val client: HttpClient) {
    /**
     * 通过位置获取单个实例信息
     */
    suspend fun instanceByLocation(location: String): InstanceData {
        // The command takes the identity, not the joined location string.
        val worldId = location.substringBefore(':', location)
        val instanceId = location.substringAfter(':', "")
        return CoreJson.decodeFromString(
            InstanceData.serializer(),
            vrcxCoreClient().instanceGet(worldId, instanceId).bodyOrThrow(),
        )
    }

    /** 获取关闭流程使用的实例快照，保留协议中可选字段的缺失状态。 */
    suspend fun closeStatus(worldId: String, instanceId: String): InstanceCloseResponse {
        val location = "$worldId:$instanceId"
        return client.get { url { path(INSTANCES_API_SUFFIX, location) } }
            .checkSuccess()
    }

    /**
     * 立即关闭实例。
     */
    suspend fun closeInstance(worldId: String, instanceId: String): InstanceCloseResponse {
        val location = "$worldId:$instanceId"
        return client.delete { url { path(INSTANCES_API_SUFFIX, location) } }
            .checkSuccess()
    }

    suspend fun createInstance(options: InstanceCreationOptions): InstanceData {
        return client.post(INSTANCES_API_SUFFIX) {
            contentType(ContentType.Application.Json)
            setBody(options.toCreateInstanceRequest())
        }.checkSuccess()
    }
}

