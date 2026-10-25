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

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.R
import com.kododake.aabrowser.ui.compose.theme.AABrowserTheme

@Composable
fun BrowserMediaControls(
    stateHolder: MenuStateHolder,
    onMediaKey: (KeyEvent) -> Unit,
    onPlayPause: () -> Unit
) {
    AABrowserTheme {
        AnimatedVisibility(
            visible = stateHolder.mediaState.isAvailable &&
                (stateHolder.isFabVisible || stateHolder.isFullscreenMediaControlsVisible),
            enter = scaleIn(tween(180)) + fadeIn(tween(180)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaControlSlot(
                    visible = stateHolder.mediaState.canSkipPrevious,
                    icon = Icons.Rounded.SkipPrevious,
                    description = stringResource(R.string.media_previous),
                    keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    onMediaKey = onMediaKey
                )
                MediaControlButton(
                    icon = if (stateHolder.mediaState.isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    description = stringResource(
                        if (stateHolder.mediaState.isPlaying) R.string.media_pause else R.string.media_play
                    ),
                    onClick = onPlayPause
                )
                MediaControlSlot(
                    visible = stateHolder.mediaState.canSkipNext,
                    icon = Icons.Rounded.SkipNext,
                    description = stringResource(R.string.media_next),
                    keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                    onMediaKey = onMediaKey
                )
            }
        }
    }
}

@Composable
fun BrowserFullscreenControl(
    stateHolder: MenuStateHolder,
    onClick: () -> Unit
) {
    AABrowserTheme {
        AnimatedVisibility(
            visible = stateHolder.mediaState.hasFullscreenButton &&
                (stateHolder.isFabVisible || stateHolder.isFullscreenMediaControlsVisible),
            enter = scaleIn(tween(180)) + fadeIn(tween(180)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120))
        ) {
            MediaControlButton(
                icon = Icons.Rounded.Fullscreen,
                description = stringResource(R.string.media_fullscreen),
                onClick = onClick
            )
        }
    }
}

@Composable
fun MediaRestoreCue(visible: Boolean) {
    AABrowserTheme {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 4.dp
            ) {
                Text(
                    text = stringResource(R.string.media_restoring_position),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaControlSlot(
    visible: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    keyCode: Int,
    onMediaKey: (KeyEvent) -> Unit
) {
    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        if (visible) {
            MediaKeyButton(
                icon = icon,
                description = description,
                keyCode = keyCode,
                onMediaKey = onMediaKey
            )
        }
    }
}

// Emits the same KeyEvent sequence as a hardware media key: DOWN on press, UP on release.
@Composable
private fun MediaKeyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    keyCode: Int,
    onMediaKey: (KeyEvent) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnMediaKey by rememberUpdatedState(onMediaKey)
    Surface(
        modifier = Modifier.size(64.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .indication(interactionSource, ripple())
                .semantics {
                    role = Role.Button
                    onClick {
                        val now = SystemClock.uptimeMillis()
                        currentOnMediaKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                        currentOnMediaKey(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                        true
                    }
                }
                .pointerInput(keyCode) {
                    detectTapGestures(onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        val downTime = SystemClock.uptimeMillis()
                        currentOnMediaKey(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                        val released = tryAwaitRelease()
                        val upTime = SystemClock.uptimeMillis()
                        val flags = if (released) 0 else KeyEvent.FLAG_CANCELED
                        currentOnMediaKey(
                            KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, flags)
                        )
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                        )
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun MediaControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 64.dp,
    iconSize: Dp = 32.dp
) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 2.dp
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(size)
        ) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
