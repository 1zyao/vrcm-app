package io.github.vrcmteam.vrcm.presentation.screens.home.pager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vrcmteam.vrcm.presentation.compoments.AImage
import io.github.vrcmteam.vrcm.presentation.designsystem.AppShapes
import io.github.vrcmteam.vrcm.presentation.designsystem.AppText
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTheme
import io.github.vrcmteam.vrcm.presentation.screens.search.HotWorldVo

/**
 * 探索 rows: worlds that have people in them right now.
 *
 * Deliberately not the search-result card: that one answers "does this match my query", while this one
 * answers "is anyone in there", so the head count and the friend badge lead.
 */
internal fun LazyListScope.renderHotWorldItems(
    worlds: List<HotWorldVo>,
    onOpen: (String) -> Unit,
) {
    items(worlds, key = { it.worldId }) { world ->
        HotWorldRow(world = world, onOpen = onOpen)
    }
}

@Composable
private fun HotWorldRow(world: HotWorldVo, onOpen: (String) -> Unit) {
    val worldJson = world.worldJson
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (worldJson != null) Modifier.clickable { onOpen(worldJson) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 64.dp)
                .clip(AppShapes.m)
                .background(AppTheme.colors.fill),
            contentAlignment = Alignment.Center,
        ) {
            AImage(
                modifier = Modifier.size(width = 96.dp, height = 64.dp).clip(AppShapes.m),
                imageData = world.coverUrl,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                text = world.name,
                style = AppTheme.type.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    text = if (world.capacity > 0) {
                        "${world.userCount}/${world.capacity}"
                    } else {
                        world.userCount.toString()
                    },
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                )
                if (world.friendCount > 0) {
                    AppText(
                        text = "${world.friendCount} 位好友去过",
                        style = AppTheme.type.footnote,
                        color = AppTheme.colors.label,
                    )
                }
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 124.dp, end = 16.dp)
            .height(0.5.dp)
            .background(AppTheme.colors.separator),
    )
}
