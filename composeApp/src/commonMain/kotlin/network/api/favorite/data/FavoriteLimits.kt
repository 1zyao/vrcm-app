package io.github.vrcmteam.vrcm.network.api.favorite.data

import kotlinx.serialization.Serializable

/**
 * Per-type favourite limits.
 *
 * Fields carry defaults because the payload is the backend's stored copy of VRChat's limits,
 * which may omit a type this app does not model (VRC+ adds `vrcPlusWorld`); a missing key is
 * otherwise a hard decode error even when the type is nullable.
 */
@Serializable
data class FavoriteGroupLimits(
    val avatar: Int = 0,
    val friend: Int = 0,
    val world: Int = 0
)

/**
 * Favourite quota as recorded by the backend.
 *
 * `defaultMaxFavoriteGroups` / `defaultMaxFavoritesPerGroup` are this app's own concept and are
 * not part of the stored limits, so they default to zero; callers should prefer the per-type
 * values.
 */
@Serializable
data class FavoriteLimits(
    val maxFavoriteGroups: FavoriteGroupLimits = FavoriteGroupLimits(),
    val maxFavoritesPerGroup: FavoriteGroupLimits = FavoriteGroupLimits(),
    val defaultMaxFavoriteGroups: Int = 0,
    val defaultMaxFavoritesPerGroup: Int = 0
)
