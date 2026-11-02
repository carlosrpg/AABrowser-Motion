/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.ui.compose.screens.menu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.R
import com.kododake.aabrowser.model.YouTubeMix
import com.kododake.aabrowser.ui.compose.components.ExpressiveLoadingIndicator
import com.kododake.aabrowser.ui.compose.components.ExpressiveSheetHeader
import com.kododake.aabrowser.ui.compose.components.bouncyClickable
import com.kododake.aabrowser.ui.compose.theme.AABrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Pill shown with the FAB on YouTube pages; opens the mixes grid. */
@Composable
fun BrowserMixesTab(
    stateHolder: MenuStateHolder,
    onClick: () -> Unit
) {
    AABrowserTheme {
        AnimatedVisibility(
            visible = stateHolder.isYouTubePage && !stateHolder.isMixesPanelVisible &&
                (stateHolder.isFabVisible || stateHolder.isFullscreenMediaControlsVisible),
            enter = scaleIn(tween(180)) + fadeIn(tween(180)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            modifier = Modifier.padding(6.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = onClick,
                modifier = Modifier.loadingBorder(active = stateHolder.isMixesLoading, cornerRadius = 18.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                text = { Text(stringResource(R.string.mixes_tab)) }
            )
        }
    }
}

/** A highlight that sweeps around the edge while [active], the "still loading" cue for the Mixes tab. */
@Composable
private fun Modifier.loadingBorder(active: Boolean, cornerRadius: androidx.compose.ui.unit.Dp): Modifier {
    if (!active) return this
    val phase by rememberInfiniteTransition(label = "mixesLoading").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "mixesLoadingPhase"
    )
    val highlight = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        // Sweep gradient stops cannot rotate, so slide a highlight window along the sweep instead.
        val width = 0.3f
        val start = phase
        val end = (phase + width) % 1f
        val stops = if (start < end) {
            arrayOf(0f to Color.Transparent, start to Color.Transparent, (start + end) / 2f to highlight,
                end to Color.Transparent, 1f to Color.Transparent)
        } else {
            arrayOf(0f to highlight.copy(alpha = (end / width)), end to Color.Transparent,
                start to Color.Transparent, 1f to highlight.copy(alpha = (1f - start) / width))
        }
        val stroke = 2.5.dp.toPx()
        drawRoundRect(
            brush = Brush.sweepGradient(colorStops = stops, center = center),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(width = stroke)
        )
    }
}

/** Full-screen grid of the user's mixes: thumbnail plus title, tap to play. */
@Composable
fun BrowserMixesPanel(
    stateHolder: MenuStateHolder,
    onMixSelected: (YouTubeMix) -> Unit,
    onDismiss: () -> Unit
) {
    AABrowserTheme {
        if (!stateHolder.isMixesPanelVisible) return@AABrowserTheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExpressiveSheetHeader(
                        title = stringResource(R.string.mixes_title),
                        onBack = onDismiss,
                        icon = Icons.Rounded.QueueMusic
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        stateHolder.isMixesLoading && stateHolder.mixes.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ExpressiveLoadingIndicator()
                        }
                        stateHolder.mixes.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                text = stringResource(R.string.mixes_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(stateHolder.mixes, key = { it.watchUrl }) { mix ->
                                MixCard(mix) { onMixSelected(mix) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixCard(mix: YouTubeMix, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .bouncyClickable(shape = shape, onClick = onClick)
    ) {
        MixThumbnail(
            url = mix.thumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = mix.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private val thumbnailCache = LruCache<String, Bitmap>(48)

@Composable
private fun MixThumbnail(url: String, modifier: Modifier) {
    val bitmap by produceState(thumbnailCache.get(url), url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
                    ?.also { thumbnailCache.put(url, it) }
            }
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
