package io.github.vrcmteam.vrcm.network.api.files

import io.github.vrcmteam.vrcm.network.api.attributes.FILES_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.attributes.FILE_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.files.data.FileData
import io.github.vrcmteam.vrcm.network.api.files.data.FileDetailsData
import io.github.vrcmteam.vrcm.network.api.files.data.FileResponse
import io.github.vrcmteam.vrcm.mobilecore.CoreJson
import io.github.vrcmteam.vrcm.mobilecore.mediaFilesGet
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.api.files.data.FileTagType
import kotlinx.serialization.builtins.ListSerializer
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.github.vrcmteam.vrcm.network.extensions.checkSuccessResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.plugins.onUpload
import io.ktor.http.*


import io.github.vrcmteam.vrcm.mobilecore.mediaFileDelete
class FileApi(private val client: HttpClient) {

    companion object {
        private val regex = Regex("file_[\\w-]+")
        private val versionRegex = Regex("/[0-9]+/")
        fun findFileId(fileUrl: String): String {
            val match = regex.find(fileUrl)
            return match?.groupValues?.get(0).orEmpty()
        }

        fun findFileVersion(fileUrl: String): String {
            val versionMatch = versionRegex.find(fileUrl, 30)
            return versionMatch?.groupValues?.last()?.replace("/", "") ?: "1"
        }
        fun imageUrl(fileId: String, fileVersion: Int, fileSize: Int = 1024): String {
            if (fileId.isBlank() || fileVersion < 1) return ""
            return "https://api.vrchat.cloud/api/1/image/$fileId/$fileVersion/$fileSize"
        }
        fun originalFileUrl(fileId: String, fileVersion: Int): String {
            if (fileId.isBlank() || fileVersion < 1) return ""
            return "https://api.vrchat.cloud/api/1/file/$fileId/$fileVersion/file"
        }
        fun convertFileUrl(fileUrl: String, fileSize: Int = 1024): String {
            if (fileUrl.isEmpty()) return ""
            val fileId = findFileId(fileUrl)
            val fileVersion = findFileVersion(fileUrl).toIntOrNull() ?: 1
            return imageUrl(fileId, fileVersion, fileSize)
        }

        fun convertFileUrlToOriginal(fileUrl: String): String {
            if (fileUrl.isEmpty()) return ""
            val fileId = findFileId(fileUrl)
            val fileVersion = findFileVersion(fileUrl)
            return "https://api.vrchat.cloud/api/1/file/$fileId/$fileVersion/file"
        }
    }

    suspend fun findImageFileLocal(fileUrl: String, fileSize: Int = 128): String {
        return runCatching {
            val response = client.get {
                val findFileId = findFileId(fileUrl)
                val findFileVersion = findFileVersion(fileUrl)
                url("https://api.vrchat.cloud/api/1/image/$findFileId/$findFileVersion/$fileSize")
            }
            return response.headers["Location"] ?: fileUrl
        } .getOrDefault(fileUrl)
    }

    /**
     * 获取文件列表
     * @param tag 标签，例如"icon"、"emoji"、"sticker"或"gallery"
     * @param userId 用户ID
     * @param n 每页数量，最大100
     * @param offset 偏移量
     */
    suspend fun getFiles(
        tag: FileTagType? = null,
        userId: String? = null,
        n: Int = 60,
        offset: Int = 0
    ): List<FileData> {
        // Served by the backend. Two of this API's parameters have no equivalent there, and both are
        // rejected instead of dropped: `userId` (the command lists the signed-in account's files)
        // and the `worldimage` / `print` tags, which are not in the backend's tag enum - prints have
        // their own list command, which is why `print` is not a file tag there.
        require(userId == null) { "the media files command lists the signed-in account only" }
        val backendTag = tag?.let {
            when (it) {
                FileTagType.Icon -> "icon"
                FileTagType.Emoji -> "emoji"
                FileTagType.Sticker -> "sticker"
                FileTagType.Gallery -> "gallery"
                FileTagType.AvatarImage -> "avatargallery"
                FileTagType.WorldImage, FileTagType.Print ->
                    throw IllegalArgumentException("the media files command has no ${it.value} tag")
            }
        }

        return CoreJson.decodeFromString(
            ListSerializer(FileData.serializer()),
            vrcxCoreClient().mediaFilesGet(
                n = n.coerceIn(1, 100),
                offset = offset.coerceAtLeast(0),
                tag = backendTag,
            ).bodyOrThrow(),
        )
    }

    /** 获取指定模型关联的只读 Gallery 文件。 */
    suspend fun getAvatarGalleryFiles(
        avatarId: String,
        n: Int = 100,
        offset: Int = 0,
    ) = client.get(FILES_API_PREFIX) {
        parameter("tag", "avatargallery")
        parameter("galleryId", avatarId)
        parameter("n", n.coerceIn(1, 100))
        parameter("offset", offset.coerceAtLeast(0))
    }.checkSuccess<List<FileData>>()

    /**
     * 上传文件
     * @param fileBytes 文件字节数组
     * @param fileName 文件名
     * @param mimeType 文件MIME类型
     * @param tagType 文件标签类型
     * @return 上传结果
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        tagType: FileTagType
    ): Result<FileDetailsData> {
        return try {
            val response = client.submitFormWithBinaryData(
                url = "$FILES_API_PREFIX/create",
                formData = formData {
                    append("file", fileBytes, Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                    append("tag", tagType.value)
                }
            )
            val result = response.checkSuccess<FileDetailsData>()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 上传图片文件
     * @param fileBytes 图片文件字节数组
     * @param fileName 文件名
     * @param mimeType 文件MIME类型
     * @return 上传结果
     */
    suspend fun uploadImageFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        tagType: FileTagType
    ): Result<FileData> {
        val parameters = vrcxImageUploadParameters(tagType, fileName)
        val uploadFileName = if (tagType == FileTagType.AvatarImage) fileName else "blob"
        return client.submitFormWithBinaryData(
            url = "${FILE_API_PREFIX}/image",
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$uploadFileName\"")
                })
                append("tag", parameters.tag)
                parameters.animationStyle?.let { append("animationStyle", it) }
                parameters.maskTag?.let { append("maskTag", it) }
                parameters.frames?.let { append("frames", it.toString()) }
                parameters.framesOverTime?.let { append("framesOverTime", it.toString()) }
                parameters.loopStyle?.let { append("loopStyle", it) }
            }
        ).checkSuccessResult<FileData>()
    }

    /** Uploads an image to an avatar's private Gallery and reports actual request progress. */
    suspend fun uploadAvatarGalleryImage(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        avatarId: String,
        onProgress: suspend (bytesSent: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<FileData> = client.submitFormWithBinaryData(
        url = "${FILE_API_PREFIX}/image",
        formData = formData {
            append("file", fileBytes, Headers.build {
                append(HttpHeaders.ContentType, mimeType)
                append(HttpHeaders.ContentDisposition, "filename=\"blob\"")
            })
            append("tag", "avatargallery")
            append("galleryId", avatarId)
        },
    ) {
        onUpload { bytesSent, contentLength -> onProgress(bytesSent, contentLength) }
    }.checkSuccessResult()

    /**
     * 获取特定文件ID的文件信息
     * @param fileId 文件ID
     * @return 文件信息
     */
    suspend fun getFileInfo(fileId: String): Result<FileResponse> = runCatching {
        client.get("$FILE_API_PREFIX/$fileId").checkSuccess<FileResponse>()
    }
    /**
     * 删除指定文件
     * @param fileId 文件ID
     */
    suspend fun deleteFile(fileId: String) {
        // Through the core (`app__vrchat_media_file_delete`).
        vrcxCoreClient().mediaFileDelete(fileId).bodyOrThrow()
    }
}

internal data class FileImageUploadParameters(
    val tag: String,
    val animationStyle: String? = null,
    val maskTag: String? = null,
    val frames: Int? = null,
    val framesOverTime: Int? = null,
    val loopStyle: String? = null,
)

internal fun vrcxImageUploadParameters(
    tagType: FileTagType,
    sourceFileName: String,
): FileImageUploadParameters = when (tagType) {
    FileTagType.Gallery,
    FileTagType.Icon,
    FileTagType.AvatarImage,
    FileTagType.WorldImage -> FileImageUploadParameters(tag = tagType.value)

    FileTagType.Sticker -> FileImageUploadParameters(
        tag = tagType.value,
        maskTag = "square",
    )

    FileTagType.Emoji -> parseVrcxEmojiUploadParameters(sourceFileName)
    FileTagType.Print -> error("Unsupported /file/image upload tag: $tagType")
}

private fun parseVrcxEmojiUploadParameters(fileName: String): FileImageUploadParameters {
    var isAnimated = false
    var animationStyle = "stop"
    var frames = 4
    var framesOverTime = 15
    var loopStyle: String? = null

    fileName.substringBeforeLast('.').split('_').forEach { token ->
        when {
            token.endsWith(ANIMATION_STYLE_SUFFIX) -> {
                isAnimated = false
                animationStyle = token.removeSuffix(ANIMATION_STYLE_SUFFIX).lowercase()
            }
            token.endsWith(FRAMES_SUFFIX) -> {
                token.removeSuffix(FRAMES_SUFFIX).toIntOrNull()?.let { parsed ->
                    if (parsed > 0) {
                        isAnimated = true
                        frames = parsed
                    }
                }
            }
            token.endsWith(FPS_SUFFIX) -> {
                token.removeSuffix(FPS_SUFFIX).toIntOrNull()?.let { parsed ->
                    if (parsed > 0) framesOverTime = parsed
                }
            }
            token.endsWith(LOOP_STYLE_SUFFIX) -> {
                if (token.removeSuffix(LOOP_STYLE_SUFFIX).isNotBlank()) {
                    loopStyle = "pingpong"
                }
            }
        }
    }

    return FileImageUploadParameters(
        tag = if (isAnimated) "emojianimated" else FileTagType.Emoji.value,
        animationStyle = animationStyle,
        maskTag = "square",
        frames = frames.takeIf { isAnimated },
        framesOverTime = framesOverTime.takeIf { isAnimated },
        loopStyle = loopStyle,
    )
}

private const val ANIMATION_STYLE_SUFFIX = "animationStyle"
private const val FRAMES_SUFFIX = "frames"
private const val FPS_SUFFIX = "fps"
private const val LOOP_STYLE_SUFFIX = "loopStyle"


