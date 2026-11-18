package io.github.vrcmteam.vrcm.presentation.screens.search

/**
 * One row of the 探索 tab: a world that people are in right now.
 *
 * Aggregated from the raw instance rows (`instances/active` + `instances/discover`) by world id, because
 * the API answers per instance and the tab reads per world: several instances of the same world become
 * one row with the combined head count.
 */
data class HotWorldVo(
    val worldId: String,
    val name: String,
    val coverUrl: String,
    /** Players across every listed instance of this world. */
    val userCount: Int,
    /** Capacity of the busiest instance - what the "x/y" badge describes. */
    val capacity: Int,
    /** Full location of the busiest instance, for opening it. */
    val location: String,
    /** How many of the signed-in user's friends are in any instance of this world (last 7 days). */
    val friendCount: Int = 0,
    /** The raw `world` object from the instance row, kept so a tap can open the world profile. */
    val worldJson: String? = null,
)
