package io.github.vrcmteam.vrcm.network.api.prints

import io.github.vrcmteam.vrcm.network.api.attributes.PRINTS_API_PREFIX
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.printGet
import io.github.vrcmteam.vrcm.mobilecore.printsGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.api.prints.data.PrintData
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PrintsApi(private val client: HttpClient) {

    suspend fun getUserPrints(
        userId: String,
        n: Int = 100,
        offset: Int = 0,
    ): List<PrintData> {
        // Served by the backend, which takes only `n` (no offset), so paging asks for more and drops
        // what is already shown. The page size stays capped at 100, the API's own limit.
        val size = n.coerceIn(1, 100)
        val skip = offset.coerceAtLeast(0)
        val rows = CoreJson.decodeFromString(
            ListSerializer(PrintData.serializer()),
            vrcxCoreClient().printsGet(userId, n = size + skip).bodyOrThrow(),
        )
        return rows.drop(skip).take(size)
    }

    suspend fun getPrint(printId: String): PrintData =
        CoreJson.decodeFromString(
            PrintData.serializer(),
            vrcxCoreClient().printGet(printId).bodyOrThrow(),
        )

    suspend fun uploadPrint(
        imageBytes: ByteArray,
        fileName: String,
        note: String = "",
        worldId: String? = null,
        worldName: String? = null,
    ): PrintData {
        require(fileName.endsWith(".png", ignoreCase = true) && imageBytes.hasPngSignature()) {
            "Print image must be a PNG file"
        }
        return client.submitFormWithBinaryData(
            url = PRINTS_API_PREFIX,
            formData = formData {
                append("image", imageBytes, Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"image\"")
                })
                append("note", note)
                append("timestamp", vrcPrintTimestamp())
                worldId?.let { append("worldId", it) }
                worldName?.let { append("worldName", it) }
            }
        ).checkSuccess()
    }

    /**
     * 删除指定拍立得
     * @param printId 拍立得ID
     */
    suspend fun deletePrint(printId: String) {
        client.delete("$PRINTS_API_PREFIX/$printId").checkSuccess<Unit>()
    }

    private fun ByteArray.hasPngSignature(): Boolean = size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}

@OptIn(ExperimentalTime::class)
internal fun vrcPrintTimestamp(
    instant: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = instant.toLocalDateTime(timeZone).toString().take(VRCX_TIMESTAMP_LENGTH)

private const val VRCX_TIMESTAMP_LENGTH = 19


