package io.github.vrcmteam.vrcm.mobilecore

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.github.vrcmteam.vrcm.network.api.attributes.AuthState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Client for the in-process VRCX-0 core.
 *
 * Speaks the same envelope as the desktop IPC and the cloud server:
 * `POST /api/invoke { cmd, args }` -> `{ ok: true, result }` or
 * `{ ok: false, error: { code, message } }`. One generic invoke serves every
 * command, so adding commands never touches this class.
 */
class VrcxCoreClient(
    val baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun invoke(cmd: String, args: JsonElement = buildJsonObject {}): JsonElement {
        val payload = buildJsonObject {
            put("cmd", cmd)
            put("args", args)
        }
        val response = http.post("$baseUrl/api/invoke") {
            contentType(ContentType.Application.Json)
            header("x-vrcx-token", token)
            setBody(payload.toString())
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (body["ok"]?.jsonPrimitive?.content != "true") {
            val error = body["error"]?.jsonObject
            throw VrcxCoreException(
                error?.get("code")?.jsonPrimitive?.content ?: "commandFailed",
                error?.get("message")?.jsonPrimitive?.content ?: "unknown error"
            )
        }
        return body["result"] ?: JsonNull
    }
}

class VrcxCoreException(val code: String, override val message: String) : Exception(message)

/** Feed rows for a user. Args are camelCase — the Rust side renames them. */
suspend fun VrcxCoreClient.feedRows(
    userId: String,
    maxEntries: Int = 50,
    mode: String = "lookup"
): JsonElement = invoke(
    "app__feed_rows_query",
    // Canonical envelope: the input is wrapped in the command's parameter name.
    buildJsonObject {
        put("query", buildJsonObject {
            put("userId", userId)
            put("mode", mode)
            put("maxEntries", maxEntries)
        })
    }
)

/**
 * Password login through the core.
 *
 * The core owns the HTTP call, the cookie jar and the 2FA state machine, so the
 * app never talks to VRChat directly. `mode` is the enum tag: the core's
 * `LoginSessionStartInput` is internally tagged with `mode` and camelCase fields.
 *
 * Note the args are the input struct itself — `register_typed` deserialises the
 * whole `args` object — so there is no `input` wrapper here. (Wrapping it produces
 * `missing field \`mode\`` from serde.)
 */
suspend fun VrcxCoreClient.loginBasic(
    username: String,
    password: String,
    saveCredentials: Boolean = true
): CoreLogin = invoke(
    "app__vrchat_auth_session_start",
    buildJsonObject {
        put("mode", "basic")
        put("username", username)
        put("password", password)
        put("saveCredentials", saveCredentials)
    }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}

/**
 * Answers a 2FA challenge raised by [loginBasic].
 *
 * `attemptId` comes from the login result: the core tracks the attempt, the UI only
 * carries the id. `method` is the wire value of the challenge mode
 * (`emailOtp` / `totp` / `otp`).
 */
suspend fun VrcxCoreClient.loginRespond(
    attemptId: String,
    method: String,
    code: String
): CoreLogin = invoke(
    "app__vrchat_auth_session_respond",
    buildJsonObject {
        put("attemptId", attemptId)
        put("method", method)
        put("code", code)
    }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}



/**
 * Ends the core session.
 *
 * The UI logs out locally anyway (cookie + in-memory session), but the core keeps
 * its own authenticated runtime — without this call it would still believe it is
 * logged in after a logout.
 *
 * `LoginSessionEnd` is an externally tagged enum, so the plain `Logout` variant
 * serialises as a bare string.
 */
suspend fun VrcxCoreClient.logout() {
    invoke(
        "app__vrchat_auth_session_end",
        // `LoginSessionEnd` is externally tagged: the `Logout` variant is the bare
        // string, and it *is* the args object.
        JsonPrimitive("Logout")
    )
}

/**
 * Restores a session from stored credentials.
 *
 * The core owns the saved credentials and the cookie jar, so this replaces the
 * "read AccountDto, re-apply cookies, ask VRChat for the user" dance. An empty
 * userId restores the most recent session.
 *
 * The core's `AutoLoginOutcome` is untagged: either a `LoginSessionState` (carrying
 * the user JSON) or a terminal outcome such as throttled/expired. Only the former
 * maps to a logged-in state.
 */
suspend fun VrcxCoreClient.autoLoginStart(userId: String = ""): CoreLogin = invoke(
    "app__vrchat_auth_auto_login_start",
    // Cloud wraps this command's argument in `input`, and the fork now matches that shape
    // (the bare form fails with "missing field `input`").
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).let {
    CoreLogin(
        state = authStateFromCore(it),
        userJson = currentUserJsonFromCore(it),
        attemptId = attemptIdFromCore(it)
    )
}

/** Raw VRChat GET through the core. `path` is relative to `/api/1/`. */
suspend fun VrcxCoreClient.vrchatGet(path: String): JsonElement {
    val trimmed = path.trimStart('/')
    val result = invoke(
        "app__web_execute",
        buildJsonObject {
            put("url", "https://api.vrchat.cloud/api/1/$trimmed")
            put("method", "GET")
        }
    )
    val status = result.jsonObject["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    if (status !in 200..299) {
        throw VrcxCoreException("http$status", "GET $trimmed failed with $status")
    }
    return Json.parseToJsonElement(result.jsonObject["data"]?.jsonPrimitive?.content ?: "null")
}


/** Diagnostic: publishes an event through the core so the push path can be checked. */
suspend fun VrcxCoreClient.debugEmitEvent(name: String, payload: JsonElement = JsonNull) {
    invoke(
        "app__debug_emit_event",
        buildJsonObject {
            put("name", name)
            put("payload", payload)
        }
    )
}


/**
 * Reads VRCX's feed read model from the backend.
 *
 * This is the whole point of the new topology: the rows were recorded by a node
 * that was online when the events happened (desktop or server), synced to the
 * backend's SQLite, and the phone merely displays them. No VRChat request is made
 * by this app for it.
 *
 * `mode`: "lookup" (the account's feed), "search" (with `search`), "instance".
 * `userId` is required - the feed tables are per-user prefixed.
 */
suspend fun VrcxCoreClient.feedQuery(
    userId: String,
    mode: String = "lookup",
    maxEntries: Int = 60,
    search: String = "",
    cursor: JsonObject? = null,
    filters: List<String> = emptyList(),
): JsonElement = invoke(
    "app__feed_rows_query",
    // Canonical envelope: the input is wrapped in a key named after the command's
    // parameter, exactly as the desktop and cloud frontends send it
    // (`arg(&args, "query")`). Sending it bare fails with `missing field query`.
    buildJsonObject {
        put("query", buildJsonObject {
            put("userId", userId)
            put("mode", mode)
            put("maxEntries", maxEntries)
            if (search.isNotEmpty()) put("search", search)
            if (cursor != null) put("cursor", cursor)
            // Filters belong to the query: filtering an unfiltered page client-side only ever
            // shows the few rows of that type that happen to fall inside the page.
            if (filters.isNotEmpty()) {
                put("filters", JsonArray(filters.map { JsonPrimitive(it) }))
            }
        })
    }
)


/**
 * Favourites read model from the backend.
 *
 * `kind` is one of `avatar`, `world`, `friend` - read out of the command's own error
 * message rather than guessed. The rows are the recording node's favourites, not the
 * phone's: the phone never asks VRChat for them.
 */
suspend fun VrcxCoreClient.favoriteList(kind: String): JsonElement = invoke(
    "app__favorite_list",
    buildJsonObject { put("kind", kind) }
)

/** Local favourite snapshot; same `kind` argument as [favoriteList]. */
suspend fun VrcxCoreClient.favoriteLocalSnapshot(kind: String): JsonElement = invoke(
    "app__favorite_local_snapshot",
    buildJsonObject { put("kind", kind) }
)

/**
 * Removes a favourite.
 *
 * `objectId` is the ENTITY id (world/avatar/user) - the same value VRChat's own delete takes
 * and the same thing VRCM's `FavoriteData.id` already holds, so no semantics change.
 */
suspend fun VrcxCoreClient.favoriteDelete(objectId: String): VrchatResult = invoke(
    "app__vrchat_favorite_delete",
    buildJsonObject { put("input", buildJsonObject { put("objectId", objectId) }) }
).toVrchatResult()

/** Adds a favourite; `favoriteId` is likewise the entity id. */
suspend fun VrcxCoreClient.favoriteAdd(
    type: String,
    entityId: String,
    tags: String,
): VrchatResult = invoke(
    "app__vrchat_favorite_add",
    buildJsonObject {
        put("input", buildJsonObject {
            put("type", type)
            put("favoriteId", entityId)
            put("tags", tags)
        })
    }
).toVrchatResult()

/**
 * Favourite groups (`GET /favorite/groups`).
 *
 * The command takes no `type`, so callers filter the returned list themselves.
 */
suspend fun VrcxCoreClient.favoriteGroupsGet(
    n: Int = 50,
    offset: Int = 0,
    ownerId: String = "",
): VrchatResult = invoke(
    "app__vrchat_favorite_groups_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("n", n)
            put("offset", offset)
            if (ownerId.isNotEmpty()) put("ownerId", ownerId)
        })
    }
).toVrchatResult()

/**
 * A VRChat response as the backend relays it: HTTP status plus the untouched body.
 *
 * The `app__vrchat_*` family answers with VRChat's own JSON, so the DTOs the app already
 * has decode straight out of [data] - no parallel schema is introduced.
 */
data class VrchatResult(val status: Int, val data: String) {
    /** Body, or a readable failure when the backend could not reach VRChat. */
    fun bodyOrThrow(): String {
        if (status !in 200..299) {
            throw VrcxCoreException("http$status", "backend answered $status: ${data.take(200)}")
        }
        return data
    }
}

private fun JsonElement.toVrchatResult(): VrchatResult {
    val obj = jsonObject
    return VrchatResult(
        status = obj["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        data = obj["data"]?.jsonPrimitive?.content ?: "",
    )
}

/** Current user, canonical name `app__vrchat_auth_current_user_get`. */
suspend fun VrcxCoreClient.currentUserGet(): VrchatResult =
    invoke("app__vrchat_auth_current_user_get", buildJsonObject { }).toVrchatResult()

/** Relationship to a user; the argument key is `input`, the way cloud wraps it. */
suspend fun VrcxCoreClient.friendStatusGet(userId: String): VrchatResult = invoke(
    "app__vrchat_friend_status_get",
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).toVrchatResult()

/** User search; `params` is the backend's own search-parameter object. */
suspend fun VrcxCoreClient.searchUsersGet(params: JsonObject): VrchatResult = invoke(
    "app__vrchat_search_users_get",
    buildJsonObject { put("input", buildJsonObject { put("params", params) }) }
).toVrchatResult()

/** Group search; same envelope as [searchUsersGet]. */
suspend fun VrcxCoreClient.searchGroupsGet(params: JsonObject): VrchatResult = invoke(
    "app__vrchat_search_groups_get",
    buildJsonObject { put("input", buildJsonObject { put("params", params) }) }
).toVrchatResult()

/** A single user by id (`GET users/{id}`). */
suspend fun VrcxCoreClient.userGet(userId: String): VrchatResult = invoke(
    "app__vrchat_user_get",
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).toVrchatResult()

/** A user profile (`GET profile/{id}`); `asSelf` asks for the owner view. */
suspend fun VrcxCoreClient.userProfileGet(
    userId: String,
    asSelf: Boolean = false,
): VrchatResult = invoke(
    "app__vrchat_user_profile_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("userId", userId)
            put("asSelf", asSelf)
        })
    }
).toVrchatResult()

/** A group by id (`GET groups/{id}`). */
suspend fun VrcxCoreClient.groupGet(
    groupId: String,
    includeRoles: Boolean = false,
): VrchatResult = invoke(
    "app__vrchat_group_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("groupId", groupId)
            put("includeRoles", includeRoles)
        })
    }
).toVrchatResult()

/** An instance by identity (`GET instances/{worldId}:{instanceId}`). */
suspend fun VrcxCoreClient.instanceGet(
    worldId: String,
    instanceId: String,
): VrchatResult = invoke(    "app__vrchat_instance_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("worldId", worldId)
            put("instanceId", instanceId)
        })
    }
).toVrchatResult()

/**
 * A world by id, through the core's world cache (`app__world_get`).
 *
 * `full = false` is the cache-first summary the core keeps locally; `full = true` asks for the
 * complete live record (capacity, occupants, instances), which is what the world page shows.
 */
suspend fun VrcxCoreClient.worldGet(
    worldId: String,
    force: Boolean = false,
    full: Boolean = true,
): VrchatResult = invoke(
    "app__world_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("worldId", worldId)
            put("force", force)
            put("full", full)
        })
    }
).toVrchatResult()

/** Wears an avatar (`app__vrchat_avatar_select`). */
suspend fun VrcxCoreClient.avatarSelect(avatarId: String): VrchatResult = invoke(
    "app__vrchat_avatar_select",
    buildJsonObject { put("input", buildJsonObject { put("avatarId", avatarId) }) }
).toVrchatResult()

/** Sets the fallback avatar (`app__vrchat_avatar_select_fallback`). */
suspend fun VrcxCoreClient.avatarSelectFallback(avatarId: String): VrchatResult = invoke(
    "app__vrchat_avatar_select_fallback",
    buildJsonObject { put("input", buildJsonObject { put("avatarId", avatarId) }) }
).toVrchatResult()

/** The available avatar styles (`app__vrchat_avatar_styles_get`). */
suspend fun VrcxCoreClient.avatarStylesGet(): VrchatResult =
    invoke("app__vrchat_avatar_styles_get").toVrchatResult()

/**
 * One avatar by id (`app__vrchat_avatar_get`).
 *
 * This command name is ours, not cloud's: cloud's `app__avatar_get` is a local cache read, and its
 * per-avatar paths go through the list or gallery commands. The request behind it is the protocol's
 * own `avatar_get_input`, so the wire shape is still VRCX's.
 */
suspend fun VrcxCoreClient.avatarGet(avatarId: String): VrchatResult = invoke(
    "app__vrchat_avatar_get",
    buildJsonObject { put("input", buildJsonObject { put("avatarId", avatarId) }) }
).toVrchatResult()

/**
 * An avatar list, optionally scoped to one owner (`app__vrchat_avatar_list_by_user_get`).
 *
 * `user = "me"` means the signed-in account. `sort`, `order` and `releaseStatus` are validated
 * against the backend's enums, so an unexpected value fails with a readable error instead of
 * silently returning the wrong page.
 */
suspend fun VrcxCoreClient.avatarListByUserGet(
    userId: String = "",
    user: String = "",
    n: Int = 50,
    offset: Int = 0,
    sort: String = "updated",
    order: String = "descending",
    releaseStatus: String = "all",
): VrchatResult = invoke(
    "app__vrchat_avatar_list_by_user_get",
    buildJsonObject {
        put("input", buildJsonObject {
            if (userId.isNotEmpty()) put("userId", userId)
            if (user.isNotEmpty()) put("user", user)
            put("n", n)
            put("offset", offset)
            put("sort", sort)
            put("order", order)
            put("releaseStatus", releaseStatus)
        })
    }
).toVrchatResult()

/** One notification to mark seen, in the backend's own shape. */
data class NotificationSeenItem(
    val id: String,
    /** 1 = the legacy `/auth/user/notifications` item, 2 = the pipeline notification. */
    val version: Int,
    val type: String,
)

/**
 * Marks notifications seen (`app__notification_mark_seen_batch`).
 *
 * The backend settles its local rows first and only then calls VRChat, so a failure leaves the
 * phone's own state correct instead of diverging from the server.
 */
suspend fun VrcxCoreClient.notificationMarkSeenBatch(
    items: List<NotificationSeenItem>,
): JsonElement = invoke(
    "app__notification_mark_seen_batch",
    buildJsonObject {
        put("input", buildJsonObject {
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("id", item.id)
                            put("version", item.version)
                            put("type", item.type)
                        }
                    },
                ),
            )
        })
    },
)

/**
 * VRCX's shared-database sync (`sync__*` commands).
 *
 * The names are `sync__`, not `app__`: cloud registers them that way and its frontend calls them by
 * that name, so the phone keeps the same surface.
 */
suspend fun VrcxCoreClient.syncStatus(): JsonElement = invoke("sync__status")

/** The configured connection, without the password (only `hasPassword`). */
suspend fun VrcxCoreClient.syncConnection(): JsonElement = invoke("sync__get_connection")

/** Runs one sync cycle now and answers with the resulting status. */
suspend fun VrcxCoreClient.syncTriggerNow(): JsonElement = invoke("sync__trigger_now")

/**
 * File analysis for one asset variant (`app__vrchat_auth_file_analysis_get`).
 *
 * Measured against a real world asset: `variant = "standalonewindows"` answers 404 while the
 * variant named in the asset URL (here `security`) answers 200 with a numeric `fileSize` and
 * `uncompressedSize`. Callers must therefore pass the variant the URL carries.
 */
suspend fun VrcxCoreClient.fileAnalysisGet(
    fileId: String,
    version: Int,
    variant: String,
): VrchatResult = invoke(
    "app__vrchat_auth_file_analysis_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("fileId", fileId)
            put("version", version)
            put("variant", variant)
        })
    }
).toVrchatResult()

/** Favourite worlds, optionally for another owner (`app__vrchat_favorite_worlds_get`). */
suspend fun VrcxCoreClient.favoriteWorldsGet(
    n: Int = 50,
    offset: Int = 0,
    ownerId: String = "",
    userId: String = "",
    tag: String = "",
): VrchatResult = invoke(
    "app__vrchat_favorite_worlds_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("n", n)
            put("offset", offset)
            put("ownerId", ownerId)
            put("userId", userId)
            put("tag", tag)
        })
    }
).toVrchatResult()

/**
 * Details for favourite entities (`app__favorite_details_hydrate`).
 *
 * The field that matters is `requestedIds`: measured on a cold database, a call carrying
 * `requestedIds` returns details for those ids (and warms the backend's cache), while the same call
 * carrying only `favoriteIds` returns none. So callers pass the ids they want back.
 */
suspend fun VrcxCoreClient.favoriteDetailsHydrate(
    kind: String,
    requestedIds: List<String>,
): Map<String, JsonObject> {
    if (requestedIds.isEmpty()) return emptyMap()
    val response = invoke(
        "app__favorite_details_hydrate",
        buildJsonObject {
            put("input", buildJsonObject {
                put("kind", kind)
                put("requestedIds", JsonArray(requestedIds.map { JsonPrimitive(it) }))
            })
        },
    )
    val details = runCatching { response.jsonObject["detailsById"] as? JsonObject }
        .getOrNull() ?: return emptyMap()
    return details.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }.toMap()
}

/** World search (`app__vrchat_search_worlds_get`). */
/**
 * VRCX's recorded sessions (`app__game_log_sessions_query`).
 *
 * This is the local game log, i.e. what the desktop recorded, not a VRChat endpoint. Fields come
 * back in camelCase (`worldName`, `playerDurationRows`, …); `created_at` is the exception, kept in
 * snake_case by an explicit serde rename.
 */
suspend fun VrcxCoreClient.gameLogSessionsQuery(
    limit: Int = 20,
    search: String = "",
    dateFrom: String = "",
    dateTo: String = "",
): JsonElement = invoke(
    "app__game_log_sessions_query",
    buildJsonObject {
        put("input", buildJsonObject {
            put("limit", limit)
            if (search.isNotEmpty()) put("search", search)
            if (dateFrom.isNotEmpty()) put("dateFrom", dateFrom)
            if (dateTo.isNotEmpty()) put("dateTo", dateTo)
        })
    },
)

/**
 * `params` is the backend's own search parameter object. Note what the core measured: sending
 * `releaseStatus: "all"` on a search is answered with 403, so callers must leave it unset.
 */
suspend fun VrcxCoreClient.searchWorldsGet(
    params: JsonObject,
    option: String? = null,
): VrchatResult = invoke(
    "app__vrchat_search_worlds_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("params", params)
            if (option != null) put("option", option)
        })
    }
).toVrchatResult()

/**
 * The images of one group gallery (`app__vrchat_group_gallery_get`).
 *
 * The backend answers a plain array of image records; there is no `approved` filter on it.
 */
suspend fun VrcxCoreClient.groupGalleryGet(
    groupId: String,
    galleryId: String,
    n: Int = 20,
    offset: Int = 0,
): VrchatResult = invoke(
    "app__vrchat_group_gallery_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("groupId", groupId)
            put("galleryId", galleryId)
            put("n", n)
            put("offset", offset)
        })
    }
).toVrchatResult()

/** A page of group posts (`app__vrchat_group_posts_get`). */
suspend fun VrcxCoreClient.groupPostsGet(
    groupId: String,
    n: Int = 100,
    offset: Int = 0,
): VrchatResult = invoke(
    "app__vrchat_group_posts_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("groupId", groupId)
            put("n", n)
            put("offset", offset)
        })
    }
).toVrchatResult()

/**
 * Every group instance a user is in (`app__vrchat_group_user_instances_get`).
 *
 * VRCX's own command answers all groups at once; a caller that wants one group filters on the
 * instance's location.
 */
suspend fun VrcxCoreClient.groupUserInstancesGet(userId: String): VrchatResult = invoke(
    "app__vrchat_group_user_instances_get",
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).toVrchatResult()

/**
 * A page of group members (`app__vrchat_group_members_get`).
 *
 * `sort` is one of `joinedAt:asc` / `joinedAt:desc` - the backend validates it.
 */
suspend fun VrcxCoreClient.groupMembersGet(
    groupId: String,
    n: Int = 20,
    offset: Int = 0,
    sort: String = "joinedAt:desc",
    roleId: String = "",
): VrchatResult = invoke(
    "app__vrchat_group_members_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("groupId", groupId)
            put("n", n)
            put("offset", offset)
            put("sort", sort)
            if (roleId.isNotEmpty()) put("roleId", roleId)
        })
    }
).toVrchatResult()

/** A page of group members (`app__vrchat_group_members_get`). */
suspend fun VrcxCoreClient.groupJoin(groupId: String): VrchatResult = invoke(
    "app__vrchat_group_join",
    buildJsonObject { put("input", buildJsonObject { put("groupId", groupId) }) }
).toVrchatResult()

/** Leaves a group (`app__vrchat_group_leave`). */
suspend fun VrcxCoreClient.groupLeave(groupId: String): VrchatResult = invoke(
    "app__vrchat_group_leave",
    buildJsonObject { put("input", buildJsonObject { put("groupId", groupId) }) }
).toVrchatResult()

/** The groups a user belongs to (`app__vrchat_group_user_groups_get`). */
suspend fun VrcxCoreClient.groupUserGroupsGet(userId: String): VrchatResult = invoke(
    "app__vrchat_group_user_groups_get",
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).toVrchatResult()

/** A user's represented group (`app__vrchat_user_represented_group_get`). */
suspend fun VrcxCoreClient.userRepresentedGroupGet(userId: String): VrchatResult = invoke(
    "app__vrchat_user_represented_group_get",
    buildJsonObject { put("input", buildJsonObject { put("userId", userId) }) }
).toVrchatResult()

/**
 * Blocked/muted users from the core's own database (`app__local_moderation_list`).
 *
 * Cloud reads `ownerUserId` as a bare top-level argument, so this is the one command here that is
 * not wrapped in `input` or `query`.
 */
suspend fun VrcxCoreClient.localModerationList(ownerUserId: String): JsonElement = invoke(
    "app__local_moderation_list",
    buildJsonObject { put("ownerUserId", ownerUserId) }
)

/**
 * Notification list from the core's own database (cloud's `app__notification_list_query`).
 *
 * `filters` are the notification type names ("friendRequest", …); an empty list means all types.
 */
suspend fun VrcxCoreClient.notificationListQuery(
    userId: String,
    limit: Int = 100,
    filters: List<String> = emptyList(),
    includeUnseen: Boolean = true,
): JsonElement = invoke(    "app__notification_list_query",
    buildJsonObject {
        put("query", buildJsonObject {
            put("userId", userId)
            put("limit", limit)
            put("includeUnseen", includeUnseen)
            if (filters.isNotEmpty()) {
                put("filters", JsonArray(filters.map { JsonPrimitive(it) }))
            }
        })
    }
)

/**
 * The favourites baseline: groups with capacity/count, plus the stored favourite limits.
 *
 * This is the source of the quota the favourites panel shows. The response is `stale` when the
 * backend has no active auth scope, so a caller must treat `stale: true` as "no data" rather
 * than as zero favourites.
 */
suspend fun VrcxCoreClient.socialFavoritesBaseline(
    userId: String,
    endpoint: String = "https://api.vrchat.cloud/api/1",
): JsonElement = invoke(
    "app__social_favorites_baseline_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("userId", userId)
            put("endpoint", endpoint)
        })
    }
)

/**
 * The friend roster baseline: every friend the backend knows, with presence.
 *
 * One snapshot serves both the online and the offline list this app asks for separately.
 */
suspend fun VrcxCoreClient.socialFriendRosterBaseline(
    userId: String,
    endpoint: String = "https://api.vrchat.cloud/api/1",
    websocket: String = "wss://pipeline.vrchat.cloud",
    isFirstLoad: Boolean = true,
): JsonElement = invoke(
    "app__social_friend_roster_baseline_get",
    buildJsonObject {
        put("input", buildJsonObject {
            put("userId", userId)
            put("endpoint", endpoint)
            put("websocket", websocket)
            put("isFirstLoad", isFirstLoad)
        })
    }
)










