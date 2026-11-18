package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.core.shared.AccountSessionToken
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.di.supports.PersistentCookiesStorage
import io.github.vrcmteam.vrcm.network.api.attributes.AUTH_COOKIE
import io.github.vrcmteam.vrcm.mobilecore.isVrcxCoreReady
import io.github.vrcmteam.vrcm.mobilecore.VrcxCoreException
import io.github.vrcmteam.vrcm.mobilecore.autoLoginStart
import io.github.vrcmteam.vrcm.mobilecore.loginBasic
import io.github.vrcmteam.vrcm.mobilecore.loginRespond
import io.github.vrcmteam.vrcm.mobilecore.logout
import io.github.vrcmteam.vrcm.mobilecore.vrcxCoreClient
import io.github.vrcmteam.vrcm.network.api.attributes.AuthState
import io.github.vrcmteam.vrcm.network.api.attributes.AuthType
import io.github.vrcmteam.vrcm.network.api.attributes.TWO_FACTOR_AUTH_COOKIE
import io.github.vrcmteam.vrcm.network.api.auth.AuthApi
import io.github.vrcmteam.vrcm.network.api.auth.data.CurrentUserData
import kotlinx.serialization.json.Json
import io.github.vrcmteam.vrcm.network.api.auth.data.Presence
import io.github.vrcmteam.vrcm.network.api.profile.ProfileAppearanceApi
import io.github.vrcmteam.vrcm.network.api.profile.data.ProfileAppearanceData
import io.github.vrcmteam.vrcm.network.api.users.data.UserData
import io.github.vrcmteam.vrcm.network.api.users.data.CurrentUpdateUserData
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.github.vrcmteam.vrcm.network.supports.VRCApiException
import io.github.vrcmteam.vrcm.network.websocket.data.content.UserContent
import io.github.vrcmteam.vrcm.network.websocket.WebSocketSessionRecovery
import io.github.vrcmteam.vrcm.presentation.screens.auth.data.AuthCardPage
import io.github.vrcmteam.vrcm.service.data.AccountDto
import io.github.vrcmteam.vrcm.storage.AccountCacheManager
import io.github.vrcmteam.vrcm.storage.AccountDao
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SessionBoundResponse<T>(
    val result: Result<T>,
    val sessionToken: AccountSessionToken,
)

internal enum class FallbackAvatarUpdateResult {
    Applied,
    Stale,
    InvalidResponse,
}

internal sealed interface HomeWorldMutationState {
    data class Ready(val revision: Long) : HomeWorldMutationState
    data object SessionChanged : HomeWorldMutationState
    data object HomeWorldChanged : HomeWorldMutationState
}

internal enum class HomeWorldMutationCommitResult {
    Applied,
    SessionChanged,
    StateChanged,
}

/**
 * 负责辅助登录验证的类
 * 主要作用是统一验证失效时的重试逻辑
 * @author kamosama
 */
class AuthService(
    private val authApi: AuthApi,
    private val accountDao: AccountDao,
    private val cookiesStorage: PersistentCookiesStorage,
    private val accountCacheManager: AccountCacheManager,
    private val profileAppearanceApi: ProfileAppearanceApi? = null,
) : WebSocketSessionRecovery, AutoCloseable {
    private val serviceJob = Job()
    private val scope = CoroutineScope(serviceJob)
    private val authMutex = Mutex()
    private val homeWorldMutationGate = Mutex()
    private val currentUserLock = SynchronizedObject()

    private var currentUser: CurrentUserData? = null
    private var homeWorldRevision = 0L
    private var socketPresence: Presence? = null
    private var socketPresenceRevision = 0L
    private val _currentUserState = MutableStateFlow<CurrentUserData?>(null)
    val currentUserState: StateFlow<CurrentUserData?> = _currentUserState.asStateFlow()

    private var currentAccountDto: AccountDto? = null

    init {
        scope.launch {
            SharedFlowCentre.authed.collect { session ->
                val accountDto = session.account
                homeWorldMutationGate.withLock {
                    synchronized(currentUserLock) {
                        if (currentUser?.id != accountDto.userId) clearCurrentUserLocked()
                    }
                }
                currentAccountDto = accountDto
                accountDao.saveAccountInfo(accountDto)
            }
        }
    }

    fun accountDto(): AccountDto = currentAccountDto ?: accountDao.currentAccountDto()

    fun accountDtoList(): List<AccountDto> = accountDao.accountDtoList()

    fun accountDtoOrNull(): AccountDto? = accountDao.currentAccountDtoOrNull()

    /** 停止旧会话但保留各账号凭据；已保存账号优先用 Cookie 快捷登录。 */
    suspend fun beginAccountLogin(userId: String? = null): AuthState? = authMutex.withLock {
        val account = userId?.let { id ->
            requireNotNull(accountDao.accountDtoList().firstOrNull { it.userId == id }) {
                "Saved account is unavailable"
            }
        }
        invalidateCurrentSessionLocked()
        cookiesStorage.removeCookie(AUTH_COOKIE)
        cookiesStorage.removeCookie(TWO_FACTOR_AUTH_COOKIE)
        currentAccountDto = account ?: AccountDto()
        if (account == null) return@withLock null

        if (!account.authCookie.isNullOrBlank()) {
            applyAuthCookie(account.username)
            val response = authApi.userRes()
            when (response.status) {
                HttpStatusCode.OK -> {
                    emitAuthed(account.password, expectedUserId = account.userId).getOrThrow()
                    return@withLock AuthState.Authed
                }
                HttpStatusCode.Unauthorized -> clearAuthCookie(account.userId)
                else -> response.checkSuccess<CurrentUserData>().let { error("Unexpected response: $it") }
            }
        }
        val password = account.password?.takeIf(String::isNotEmpty) ?: return@withLock null
        loginLocked(account.username, password, expectedUserId = account.userId)
    }

    suspend fun restoreAuth(): AuthState? = authMutex.withLock {
        restoreAuthLocked()
    }

    private suspend fun restoreAuthLocked(): AuthState? {
        // The core owns the saved credentials and the cookie jar, so restoring is
        // one command instead of "read AccountDto, re-apply cookies, ask VRChat".
        if (isVrcxCoreReady()) {
            pendingCoreUserJson = null
            pendingCoreAttemptId = null
            val core = runCatching { vrcxCoreClient().autoLoginStart() }
                .onFailure { println("[VrcxCore] restore failed: ${it::class.simpleName}: ${it.message}") }
                .getOrNull()
            println("[VrcxCore] restore -> ${core?.state?.let { it::class.simpleName }} hasUser=${core?.userJson != null}")
            if (core != null) {
                if (core.state !is AuthState.Authed) return null
                pendingCoreUserJson = core.userJson
                pendingCoreAttemptId = core.attemptId
                return try {
                    emitAuthed(null).getOrThrow()
                    AuthState.Authed
                } catch (error: Throwable) {
                    println(
                        "[VrcxCore] emitAuthed failed: ${error::class.simpleName}: ${error.message}"
                    )
                    null
                } finally {
                    pendingCoreUserJson = null
                }
            }
        }

        val account = accountDao.currentAccountDtoOrNull() ?: return null
        // No auth cookie means the user explicitly logged out (or has never logged in).
        // In that state we must not silently recreate a session from the saved password.
        if (account.authCookie.isNullOrBlank()) return null

        applyAuthCookie(account.username)
        val response = authApi.userRes()
        return when (response.status) {
            HttpStatusCode.OK -> {
                emitAuthed(account.password).getOrThrow()
                AuthState.Authed
            }

            HttpStatusCode.Unauthorized -> {
                clearAuthCookie(account.userId)
                val password = account.password
                    ?: return AuthState.Unauthorized(response.bodyAsText())
                loginLocked(account.username, password)
            }

            else -> response.checkSuccess<CurrentUserData>().let { error("Unexpected response: $it") }
        }
    }

    private fun applyAuthCookie(username: String) {
        cookiesStorage.removeCookie(AUTH_COOKIE)
        cookiesStorage.removeCookie(TWO_FACTOR_AUTH_COOKIE)
        accountDao.accountDtoByUserName(username)
            ?.let {
                cookiesStorage.addCookie(AUTH_COOKIE, it.authCookie)
                cookiesStorage.addCookie(TWO_FACTOR_AUTH_COOKIE, it.twoFactorAuthCookie)
            }
    }

    suspend fun currentUser(isRefresh: Boolean = false): CurrentUserData {
        return homeWorldMutationGate.withLock {
            val cached = synchronized(currentUserLock) { currentUser }
            if (cached != null && !isRefresh) return@withLock cached
            val refreshed = enrichCurrentUser(authApi.currentUser())
            synchronized(currentUserLock) {
                publishCurrentUserLocked(
                    refreshed.copy(
                        presence = selectCurrentPresence(refreshed.presence, socketPresence),
                    )
                )
            }
        }
    }

    internal suspend fun refreshCurrentUserPresence(
        sessionToken: AccountSessionToken,
    ): CurrentUserData? = homeWorldMutationGate.withLock {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return@withLock null
        val requestSocketRevision = synchronized(currentUserLock) { socketPresenceRevision }
        val refreshed = enrichCurrentUser(authApi.currentUser())
        synchronized(currentUserLock) {
            if (!SharedFlowCentre.isCurrentSession(sessionToken) ||
                refreshed.id != sessionToken.userId
            ) {
                return@synchronized null
            }
            // A socket event received during this request is newer than its HTTP response.
            val presence = if (socketPresenceRevision == requestSocketRevision) {
                socketPresence = null
                refreshed.presence
            } else {
                selectCurrentPresence(refreshed.presence, socketPresence)
            }
            publishCurrentUserLocked(refreshed.copy(presence = presence))
        }
    }

    internal fun applyCurrentAvatarUpdate(avatarId: String) {
        synchronized(currentUserLock) {
            val existing = currentUser ?: return@synchronized
            publishCurrentUserLocked(existing.copy(currentAvatar = avatarId))
        }
    }

    internal fun applyBoopPrivacyUpdate(
        sessionToken: AccountSessionToken,
        userId: String,
        isEnabled: Boolean,
    ): Boolean {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return false
        return synchronized(currentUserLock) {
            if (!SharedFlowCentre.isCurrentSession(sessionToken)) return@synchronized false
            val existing = currentUser ?: return@synchronized false
            if (existing.id != userId) return@synchronized false
            publishCurrentUserLocked(existing.copy(isBoopingEnabled = isEnabled))
            true
        }
    }

    internal suspend fun applyFallbackAvatarUpdate(
        sessionToken: AccountSessionToken,
        avatarId: String,
        response: CurrentUserData,
        commitIfCurrent: (update: () -> Unit) -> Boolean,
    ): FallbackAvatarUpdateResult = authMutex.withLock {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) {
            return@withLock FallbackAvatarUpdateResult.Stale
        }
        synchronized(currentUserLock) {
            val existing = currentUser ?: return@synchronized FallbackAvatarUpdateResult.Stale
            if (existing.id != sessionToken.userId) {
                return@synchronized FallbackAvatarUpdateResult.Stale
            }
            if (sessionToken.userId != response.id || response.fallbackAvatar != avatarId) {
                return@synchronized FallbackAvatarUpdateResult.InvalidResponse
            }
            val updated = existing.copy(fallbackAvatar = avatarId)
            // The caller keeps its page-target lock until this update has been published.
            if (!commitIfCurrent { publishCurrentUserLocked(updated) }) {
                return@synchronized FallbackAvatarUpdateResult.Stale
            }
            FallbackAvatarUpdateResult.Applied
        }
    }

    internal suspend fun applyAvatarCopyingUpdate(
        sessionToken: AccountSessionToken,
        allowAvatarCopying: Boolean,
    ): Boolean = authMutex.withLock {
        synchronized(currentUserLock) {
            if (!SharedFlowCentre.isCurrentSession(sessionToken)) return@synchronized false
            val existing = currentUser?.takeIf { it.id == sessionToken.userId }
                ?: return@synchronized false
            publishCurrentUserLocked(
                existing.copy(allowAvatarCopying = allowAvatarCopying),
            )
            true
        }
    }

    internal suspend fun applyCurrentUserHomeLocation(
        sessionToken: AccountSessionToken,
        userId: String,
        homeLocation: String,
    ): Boolean = homeWorldMutationGate.withLock {
        applyCurrentUserHomeLocationLocked(sessionToken, userId, homeLocation)
    }

    internal fun applyCurrentUserHomeLocationLocked(
        sessionToken: AccountSessionToken,
        userId: String,
        homeLocation: String,
    ): Boolean = synchronized(currentUserLock) {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return@synchronized false
        val existing = currentUser?.takeIf { it.id == userId } ?: return@synchronized false
        publishCurrentUserLocked(existing.copy(homeLocation = homeLocation))
        true
    }

    internal fun homeWorldMutationState(
        sessionToken: AccountSessionToken,
        expectedWorldId: String?,
    ): HomeWorldMutationState = synchronized(currentUserLock) {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) {
            return@synchronized HomeWorldMutationState.SessionChanged
        }
        val existing = currentUser?.takeIf { it.id == sessionToken.userId }
            ?: return@synchronized HomeWorldMutationState.SessionChanged
        if (expectedWorldId != null && existing.homeLocation != expectedWorldId) {
            return@synchronized HomeWorldMutationState.HomeWorldChanged
        }
        HomeWorldMutationState.Ready(homeWorldRevision)
    }

    internal fun commitHomeWorldMutationLocked(
        sessionToken: AccountSessionToken,
        userId: String,
        expectedRevision: Long,
        homeLocation: String,
    ): HomeWorldMutationCommitResult = synchronized(currentUserLock) {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) {
            return@synchronized HomeWorldMutationCommitResult.SessionChanged
        }
        val existing = currentUser?.takeIf { it.id == userId }
            ?: return@synchronized HomeWorldMutationCommitResult.SessionChanged
        if (homeWorldRevision != expectedRevision) {
            return@synchronized HomeWorldMutationCommitResult.StateChanged
        }
        publishCurrentUserLocked(existing.copy(homeLocation = homeLocation))
        HomeWorldMutationCommitResult.Applied
    }

    /** Runs a Home World check and the associated request as one serializable mutation. */
    internal suspend fun <T> withHomeWorldMutation(action: suspend () -> T): T =
        homeWorldMutationGate.withLock { action() }

    fun applySocketUserLocation(location: String, travelingToLocation: String) {
        synchronized(currentUserLock) {
            val existing = currentUser ?: return@synchronized
            val (world, instance) = socketLocationToPresenceParts(location)
            val (travelingToWorld, travelingToInstance) =
                socketLocationToPresenceParts(travelingToLocation)
            val updatedPresence = existing.presence.copy(
                world = world,
                instance = instance,
                travelingToWorld = travelingToWorld,
                travelingToInstance = travelingToInstance,
            )
            socketPresence = updatedPresence
            socketPresenceRevision++
            publishCurrentUserLocked(existing.copy(presence = updatedPresence))
        }
    }

    suspend fun applyOwnProfileRefresh(
        user: UserData,
        publicProfile: ProfileAppearanceData? = null,
    ): CurrentUserData? =
        homeWorldMutationGate.withLock {
            synchronized(currentUserLock) {
                val existing = currentUser ?: return@synchronized null
                if (existing.id != user.id) return@synchronized null
                val (world, instance) = socketLocationToPresenceParts(user.location)
                val (travelingToWorld, travelingToInstance) =
                    socketLocationToPresenceParts(user.travelingToLocation.orEmpty())
                val updatedPresence = existing.presence.copy(
                    world = world,
                    instance = instance,
                    travelingToWorld = travelingToWorld,
                    travelingToInstance = travelingToInstance,
                    platform = user.lastPlatform.ifBlank { existing.presence.platform },
                )
                socketPresence = updatedPresence
                socketPresenceRevision++
                publishCurrentUserLocked(
                    existing.copy(
                        allowAvatarCopying = user.allowAvatarCopying,
                        bio = user.bio ?: existing.bio,
                        bioLinks = user.bioLinks.ifEmpty { existing.bioLinks },
                        currentAvatarImageUrl = user.currentAvatarImageUrl
                            .ifBlank { existing.currentAvatarImageUrl },
                        currentAvatarTags = user.currentAvatarTags.ifEmpty { existing.currentAvatarTags },
                        currentAvatarThumbnailImageUrl = user.currentAvatarThumbnailImageUrl
                            ?: existing.currentAvatarThumbnailImageUrl,
                        displayName = user.displayName.ifBlank { existing.displayName },
                        lastActivity = user.lastActivity.ifBlank { existing.lastActivity },
                        lastLogin = user.lastLogin.ifBlank { existing.lastLogin },
                        lastPlatform = user.lastPlatform.ifBlank { existing.lastPlatform },
                        profilePicOverride = user.profilePicOverride
                            .ifBlank { existing.profilePicOverride },
                        profileIconUrl = user.profileIconUrl
                            .ifBlank { existing.profileIconUrl },
                        state = user.state.value,
                        status = user.status,
                        statusDescription = user.statusDescription,
                        tags = user.tags,
                        userIcon = user.userIcon.ifBlank { existing.userIcon },
                        pronouns = user.pronouns ?: existing.pronouns,
                        bannerType = user.bannerType ?: existing.bannerType,
                        bannerUrl = user.bannerUrl ?: existing.bannerUrl,
                        presence = updatedPresence,
                    ).let { merged -> publicProfile?.let(merged::mergePublicProfile) ?: merged }
                )
            }
        }

    /** Publishes accepted profile edits only to the account that made the request. */
    internal suspend fun applyProfileUpdate(
        sessionToken: AccountSessionToken,
        profile: ProfileAppearanceData?,
        user: CurrentUpdateUserData?,
    ): CurrentUserData? = authMutex.withLock {
        synchronized(currentUserLock) {
            if (!SharedFlowCentre.isCurrentSession(sessionToken)) return@synchronized null
            val existing = currentUser?.takeIf { it.id == sessionToken.userId }
                ?: return@synchronized null
            if ((profile != null && profile.id != existing.id) || (user != null && user.id != existing.id)) {
                return@synchronized null
            }
            val merged = profile?.let(existing::mergePublicProfile) ?: existing
            publishCurrentUserLocked(
                if (user == null) merged else merged.copy(
                    status = user.status,
                    statusDescription = user.statusDescription,
                    pronouns = user.pronouns ?: merged.pronouns,
                ),
            )
        }
    }

    fun applySocketUserUpdate(user: UserContent) {
        synchronized(currentUserLock) {
            val existing = currentUser ?: return@synchronized
            if (existing.id != user.id) return@synchronized
            publishCurrentUserLocked(
                existing.copy(
                    allowAvatarCopying = user.allowAvatarCopying,
                    bio = user.bio ?: existing.bio,
                    bioLinks = user.bioLinks.ifEmpty { existing.bioLinks },
                    currentAvatarImageUrl = user.currentAvatarImageUrl
                        .ifBlank { existing.currentAvatarImageUrl },
                    currentAvatarTags = user.currentAvatarTags.ifEmpty { existing.currentAvatarTags },
                    currentAvatarThumbnailImageUrl = user.currentAvatarThumbnailImageUrl
                        ?: existing.currentAvatarThumbnailImageUrl,
                    displayName = user.displayName.ifBlank { existing.displayName },
                    lastActivity = user.lastActivity.ifBlank { existing.lastActivity },
                    lastLogin = user.lastLogin.ifBlank { existing.lastLogin },
                    lastPlatform = user.lastPlatform.ifBlank { existing.lastPlatform },
                    profilePicOverride = user.profilePicOverride
                        .ifBlank { existing.profilePicOverride },
                    profileIconUrl = user.profileIconUrl
                        .ifBlank { existing.profileIconUrl },
                    state = user.state,
                    status = user.status,
                    statusDescription = user.statusDescription,
                    tags = user.tags,
                    userIcon = user.userIcon.ifBlank { existing.userIcon },
                    pronouns = user.pronouns ?: existing.pronouns,
                )
            )
        }
    }

    suspend fun verify(
        password: String,
        verifyCode: String,
        authCardPage: AuthCardPage,
    ): Result<Unit> = authMutex.withLock {
        val authType = when (authCardPage) {
            AuthCardPage.EmailCode -> AuthType.Email
            AuthCardPage.TFACode -> AuthType.TFA
            AuthCardPage.TTFACode -> AuthType.TTFA
            else -> error("not supported")
        }
        // Same split as login: the core owns the 2FA exchange when it is up, so no
        // verification code ever travels through this app's own VRChat client.
        if (isVrcxCoreReady() && pendingCoreAttemptId != null) {
            val method = when (authCardPage) {
                AuthCardPage.EmailCode -> "emailOtp"
                AuthCardPage.TFACode -> "totp"
                AuthCardPage.TTFACode -> "otp"
                else -> error("not supported")
            }
            println("[VrcxCore] verify -> attempt=${pendingCoreAttemptId} method=$method")
            val core = runCatching {
                vrcxCoreClient().loginRespond(pendingCoreAttemptId!!, method, verifyCode)
            }.onFailure { println("[VrcxCore] verify failed: ${it::class.simpleName}: ${it.message}") }
                .getOrThrow()
            println("[VrcxCore] verify -> ${core.state::class.simpleName} hasUser=${core.userJson != null}")
            pendingCoreUserJson = core.userJson
            return try {
                if (core.state is AuthState.Authed) {
                    emitAuthed(password)
                } else {
                    Result.failure(
                        IllegalStateException(
                            (core.state as? AuthState.Unauthorized)?.message
                                ?: "verification failed"
                        )
                    )
                }
            } finally {
                pendingCoreUserJson = null
            }
        }
        return authApi.verify(verifyCode, authType)
            .let { if (it.isSuccess) emitAuthed(password) else it }
    }

    private suspend fun emitAuthed(
        password: String? = null,
        expectedUserId: String? = null,
    ): Result<Unit> = runCatching {
        // The backend returns the user with the login result, so there is no second
        // request here - and no fallback to asking VRChat directly.
        println("[VrcxCore] emitAuthed coreUser=${pendingCoreUserJson != null}")
        val userData = pendingCoreUserJson?.let { json ->
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<CurrentUserData>(json)
            }.getOrElse { error ->
                println("[VrcxCore] user decode failed: ${error::class.simpleName}: ${error.message}")
                null
            }
        } ?: throw VrcxCoreException(
            "noUser",
            // Naming the readiness makes this diagnosable from the screen alone: this exact error appeared on
            // iOS while the real cause was that nothing ever called `StartVrcxCore()`.
            "the backend did not return a user for this login (core ready=${isVrcxCoreReady()})"
        )
        // Used as-is: the backend's user payload is already complete, and enriching it
        // would issue extra requests that this layer is supposed to no longer make.
        userData.let {
            val userData = it
            require(expectedUserId == null || userData.id == expectedUserId) {
                "Authenticated account does not match the selected account"
            }
            val accountDto = AccountDto(
                userId = userData.id,
                username = userData.username,
                password = password,
                iconUrl = userData.iconUrl,
                authCookie = cookiesStorage.cookieValue(AUTH_COOKIE),
                twoFactorAuthCookie = cookiesStorage.cookieValue(TWO_FACTOR_AUTH_COOKIE),
            )
            homeWorldMutationGate.withLock {
                synchronized(currentUserLock) {
                    publishCurrentUserLocked(
                        userData.copy(
                            presence = selectCurrentPresence(userData.presence, socketPresence),
                        )
                    )
                }
            }
            currentAccountDto = accountDto
            accountDao.saveAccountInfo(accountDto)
            SharedFlowCentre.emitAuthenticated(accountDto)
        }
    }

    /**
     * User JSON handed over by the core for the login in flight. When set,
     * emitAuthed uses it instead of asking VRChat for the current user again.
     */
    private var pendingCoreUserJson: String? = null

    /** Attempt id of the challenge in flight, handed back to the core on verify. */
    private var pendingCoreAttemptId: String? = null

    suspend fun login(username: String, password: String): AuthState = authMutex.withLock {
        loginLocked(username, password)
    }

    private suspend fun loginLocked(
        username: String,
        password: String,
        restoreStoredCookies: Boolean = true,
        invalidateOnIncompleteAuth: Boolean = true,
        expectedUserId: String? = null,
    ): AuthState {
        val activeUserId = SharedFlowCentre.currentSession.value?.account?.userId
        val targetUserId = accountDao.accountDtoByUserName(username)?.userId
        val isAccountSwitch = activeUserId != null && activeUserId != targetUserId
        if (restoreStoredCookies) applyAuthCookie(username)
        return try {
            // Login goes through the in-process VRCX-0 core, which owns the HTTP call, the cookie jar and the
            // 2FA state machine. There is deliberately no fallback any more: the old path used to reach VRChat
            // directly for the first factor, which "worked" far enough to show a 2FA screen and then failed
            // when `emitAuthed` asked for the user payload only the core returns. Failing here, before the
            // user types a password, is the difference between a clear error and a mystery two screens later.
            if (!isVrcxCoreReady()) {
                throw VrcxCoreException(
                    "coreNotReady",
                    "the in-process core is not running, so login is unavailable " +
                        "(core ready=${isVrcxCoreReady()})"
                )
            }
            val state = run {
                val core = runCatching { vrcxCoreClient().loginBasic(username, password) }
                    .onFailure { println("[VrcxCore] login failed: ${it::class.simpleName}: ${it.message}") }
                    .getOrThrow()
                println("[VrcxCore] login -> ${core.state::class.simpleName} attempt=${core.attemptId} hasUser=${core.userJson != null}")
                // The core's session already carries the full user JSON, so
                // emitAuthed must not fetch it again from VRChat.
                pendingCoreUserJson = core.userJson
                pendingCoreAttemptId = core.attemptId
                core.state
            }
            try {
                state.also {
                    if (it is AuthState.Authed) {
                        emitAuthed(password, expectedUserId = expectedUserId).getOrThrow()
                    } else if (invalidateOnIncompleteAuth) {
                        invalidateCurrentSessionLocked()
                    }
                }
            } finally {
                pendingCoreUserJson = null
            }
        } catch (error: Throwable) {
            if (isAccountSwitch) invalidateCurrentSessionLocked()
            throw error
        }
    }

    private suspend fun invalidateCurrentSessionLocked() {
        if (SharedFlowCentre.currentSession.value == null) return
        homeWorldMutationGate.withLock {
            synchronized(currentUserLock) { clearCurrentUserLocked() }
        }
        currentAccountDto = accountDao.currentAccountDtoOrNull()
        SharedFlowCentre.emitLogout()
    }


    /**
     * 如果是失败的结果则会判断是否是验证失效了
     * 如果是则尝试重新登陆
     */
    private suspend fun <T> Result<T>.recoverLogin(callback: suspend () -> Result<T>): Result<T> {
        return when (val exception = exceptionOrNull()) {
            null -> this
            else -> if (exception is VRCApiException && exception.code == HttpStatusCode.Unauthorized.value && doReTryAuth()) {
                callback()
            } else {
                this
            }
        }
    }

    suspend fun doReTryAuth(): Boolean = authMutex.withLock {
        doReTryAuthLocked()
    }

    private suspend fun doReTryAuthLocked(expectedUserId: String? = null): Boolean {
        val sessionToken = SharedFlowCentre.currentSession.value?.token ?: return false
        if (expectedUserId != null && sessionToken.userId != expectedUserId) return false
        return recoverExpiredSessionLocked(sessionToken)
    }

    override suspend fun recoverExpiredSession(sessionToken: AccountSessionToken) {
        authMutex.withLock { recoverExpiredSessionLocked(sessionToken) }
    }

    private suspend fun recoverExpiredSessionLocked(sessionToken: AccountSessionToken): Boolean {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return false

        val account = accountDao.currentAccountDtoOrNull()
            ?.takeIf { it.userId == sessionToken.userId }
        val password = account?.password
        if (account == null || password == null) {
            clearAuthCookie(sessionToken.userId)
            invalidateCurrentSessionLocked()
            return false
        }

        // The server rejected this cookie. Keep the two-factor cookie available for the
        // credential request, but do not send the rejected auth cookie again.
        val rejectedAuthCookie = cookiesStorage.cookieValue(AUTH_COOKIE)
        cookiesStorage.removeCookie(AUTH_COOKIE)
        val authState = try {
            loginLocked(
                username = account.username,
                password = password,
                restoreStoredCookies = false,
                invalidateOnIncompleteAuth = false,
            )
        } catch (cancellation: CancellationException) {
            // Successful auth publishes a new session before the old connection job is
            // cancelled. Do not roll the rejected cookie back after that session change.
            if (SharedFlowCentre.isCurrentSession(sessionToken)) {
                rejectedAuthCookie?.let { cookiesStorage.addCookie(AUTH_COOKIE, it) }
            }
            throw cancellation
        } catch (error: Throwable) {
            // A temporary HTTP or I/O failure must leave the current session intact and
            // allow the next WebSocket attempt to use the existing authenticated state.
            if (SharedFlowCentre.isCurrentSession(sessionToken)) {
                rejectedAuthCookie?.let { cookiesStorage.addCookie(AUTH_COOKIE, it) }
            }
            throw error
        }

        if (authState !is AuthState.Authed && SharedFlowCentre.isCurrentSession(sessionToken)) {
            clearAuthCookie(account.userId)
            invalidateCurrentSessionLocked()
        }
        return authState is AuthState.Authed
    }

    internal suspend fun <T> runSessionBoundCatching(
        sessionToken: AccountSessionToken,
        callback: suspend () -> T,
    ): SessionBoundResponse<T>? = runSessionBoundCatchingWithToken(
        sessionToken = sessionToken,
        callback = { _: AccountSessionToken -> callback() },
    )

    internal suspend fun <T> runSessionBoundCatchingWithReauthentication(
        sessionToken: AccountSessionToken,
        onReauthentication: (() -> Unit)?,
        callback: suspend () -> T,
    ): SessionBoundResponse<T>? = authMutex.withLock {
        runSessionBoundCatchingLocked(
            sessionToken = sessionToken,
            callback = { _: AccountSessionToken -> callback() },
            onReauthentication = onReauthentication,
        )
    }

    internal suspend fun <T> runSessionBoundCatchingForUser(
        userId: String,
        callback: suspend (AccountSessionToken) -> T,
    ): SessionBoundResponse<T>? = authMutex.withLock {
        val sessionToken = SharedFlowCentre.currentSession.value
            ?.takeIf { it.account.userId == userId }
            ?.token
            ?: return@withLock null
        runSessionBoundCatchingLocked(sessionToken, callback)
    }

    internal suspend fun <T> runSessionBoundCatchingWithToken(
        sessionToken: AccountSessionToken,
        callback: suspend (AccountSessionToken) -> T,
    ): SessionBoundResponse<T>? = authMutex.withLock {
        runSessionBoundCatchingLocked(sessionToken, callback)
    }

    private suspend fun <T> runSessionBoundCatchingLocked(
        sessionToken: AccountSessionToken,
        callback: suspend (AccountSessionToken) -> T,
        onReauthentication: (() -> Unit)? = null,
    ): SessionBoundResponse<T>? {
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return null

        val first = runRequestCatching { callback(sessionToken) }
        val firstError = first.exceptionOrNull()
        if (firstError !is VRCApiException ||
            firstError.code != HttpStatusCode.Unauthorized.value
        ) {
            return SessionBoundResponse(first, sessionToken)
        }
        if (!SharedFlowCentre.isCurrentSession(sessionToken)) return null
        onReauthentication?.invoke()
        val reauthenticated = runRequestCatching {
            doReTryAuthLocked(sessionToken.userId)
        }
        reauthenticated.exceptionOrNull()?.let { error ->
            return SessionBoundResponse(Result.failure(error), sessionToken)
        }
        if (!reauthenticated.getOrThrow()) {
            return SessionBoundResponse(first, sessionToken)
        }

        val refreshedSession = SharedFlowCentre.currentSession.value
        if (refreshedSession?.account?.userId != sessionToken.userId ||
            !SharedFlowCentre.isCurrentSession(refreshedSession.token)
        ) {
            return null
        }
        val retried = runRequestCatching { callback(refreshedSession.token) }
        if (!SharedFlowCentre.isCurrentSession(refreshedSession.token)) return null
        return SessionBoundResponse(retried, refreshedSession.token)
    }

    private suspend fun <T> runRequestCatching(callback: suspend () -> T): Result<T> = try {
        Result.success(callback())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    /**
     * 如果验证过期了尝试登陆后再请求一次
     */
    suspend fun <T> reTryAuth(callback: suspend () -> Result<T>): Result<T> =
        callback().recoverLogin(callback)

    suspend fun <T> reTryAuthCatching(callback: suspend () -> T): Result<T> =
        reTryAuth {
            runCatching { callback() }
        }

    suspend fun logout() = authMutex.withLock {
        val userId = SharedFlowCentre.currentSession.value?.account?.userId
            ?: synchronized(currentUserLock) { currentUser?.id }
            ?: accountDto().userId
        clearAuthCookie(userId)
        homeWorldMutationGate.withLock {
            synchronized(currentUserLock) { clearCurrentUserLocked() }
        }
        currentAccountDto = accountDao.currentAccountDtoOrNull()
        // The core keeps its own authenticated runtime; without telling it, it
        // would still consider itself logged in after a logout.
        if (isVrcxCoreReady()) {
            // Deliberately swallowed: local logout must succeed even when the core is in
            // a bad state, and commonMain has no platform logger.
            runCatching { vrcxCoreClient().logout() }
        }
        SharedFlowCentre.emitLogout()
    }

    private fun clearAuthCookie(userId: String) {
        cookiesStorage.removeCookie(AUTH_COOKIE)
        cookiesStorage.removeCookie(TWO_FACTOR_AUTH_COOKIE)
        if (userId.isNotEmpty()) accountDao.logout(userId)
    }

    suspend fun removeAccount(userId: String) = runCatching {
        accountCacheManager.clearAccount(userId)
        accountDao.removeAccount(userId)
    }

    override fun close() {
        serviceJob.cancel()
    }

    internal suspend fun closeAndJoin() {
        serviceJob.cancelAndJoin()
    }

    private fun publishCurrentUserLocked(user: CurrentUserData): CurrentUserData {
        if (currentUser?.id != user.id || currentUser?.homeLocation != user.homeLocation) {
            homeWorldRevision++
        }
        currentUser = user
        _currentUserState.value = user
        return user
    }

    private fun clearCurrentUserLocked() {
        if (currentUser != null) homeWorldRevision++
        currentUser = null
        socketPresence = null
        socketPresenceRevision++
        _currentUserState.value = null
    }

    private suspend fun enrichCurrentUser(user: CurrentUserData): CurrentUserData {
        val profileApi = profileAppearanceApi ?: return user
        val profile = try {
            profileApi.getPublicProfile(user.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return user
        }
        return user.mergePublicProfile(profile)
    }

}

/** Merges fields moved from the user endpoint to the public profile endpoint. */
internal fun CurrentUserData.mergePublicProfile(profile: ProfileAppearanceData): CurrentUserData = copy(
    bio = profile.bio ?: bio,
    bioLinks = profile.bioLinks ?: bioLinks,
    tags = profile.languages?.let { languages ->
        tags.filterNot { it.startsWith("language_") } + languages.map { "language_$it" }
    } ?: tags,
    displayName = profile.displayName?.takeIf(String::isNotBlank) ?: displayName,
    profileIconUrl = profile.iconUrl?.takeIf(String::isNotBlank) ?: profileIconUrl,
    userIcon = profile.iconUrl?.takeIf(String::isNotBlank) ?: userIcon,
    pronouns = profile.pronouns ?: pronouns,
)

internal fun socketLocationToPresenceParts(location: String): Pair<String, String> =
    if (location.startsWith("wrld_") && location.contains(':')) {
        location.substringBefore(':') to location.substringAfter(':')
    } else {
        "" to location
    }

internal fun selectCurrentPresence(restPresence: Presence, socketPresence: Presence?): Presence =
    socketPresence ?: restPresence








