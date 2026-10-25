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

package com.kododake.aabrowser.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.kododake.aabrowser.MainActivity
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.BrowserMediaState

class BrowserMediaBrowserService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private var isForeground = false
    private var lastNotificationKey: String? = null
    private var lastBrowseKey: String? = null
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = BrowserMediaSessionRegistry.getSession(this)
        sessionToken = mediaSession.sessionToken
        updateForState(BrowserMediaSessionRegistry.registerService(this))
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        if (!isTrustedClient(clientPackageName, clientUid)) {
            Log.w(TAG, "Rejected media browser client: $clientPackageName")
            return null
        }
        // Media resumption (EXTRA_RECENT): offer the last played item, or nothing if there is none.
        // Observed on Android Auto (Oct 2026): it never queries this at connect; it picks the startup source
        // from its own last-used record, and only binds this service once the app's icon is opened.
        // Kept because it is the documented resumption contract (system media controls, other hosts).
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) {
            if (nowPlayingItems().isEmpty()) return null
            return BrowserRoot(RECENT_ROOT_ID, Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) })
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId == ROOT_ID) {
            result.sendResult(
                mutableListOf(
                    MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(NOW_PLAYING_ID)
                            .setTitle(getString(R.string.media_browser_now_playing))
                            .setSubtitle(getString(R.string.app_name))
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                )
            )
            return
        }

        result.sendResult(
            if (parentId == NOW_PLAYING_ID || parentId == RECENT_ROOT_ID) nowPlayingItems() else mutableListOf()
        )
    }

    // The live item when a page is playing, otherwise the last played one saved in the media checkpoint.
    private fun nowPlayingItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val state = BrowserMediaSessionRegistry.currentState()
        if (state.isAvailable) return mutableListOf(createPlayableMediaItem(createMediaDescription(state)))
        val checkpoint = BrowserPreferences.getMediaCheckpoint(this) ?: return mutableListOf()
        return mutableListOf(
            createPlayableMediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(checkpoint.mediaId)
                    .setTitle(checkpoint.title.ifBlank { Uri.parse(checkpoint.pageUrl).host })
                    .setSubtitle(checkpoint.artist)
                    .setMediaUri(Uri.parse(checkpoint.pageUrl))
                    .apply { checkpoint.artworkUrl?.let { setIconUri(Uri.parse(it)) } }
                    .build()
            )
        )
    }

    override fun onLoadItem(
        itemId: String,
        result: Result<MediaBrowserCompat.MediaItem>
    ) {
        val state = BrowserMediaSessionRegistry.currentState()
        if (!state.isAvailable || itemId != state.mediaId) {
            result.sendResult(null)
            return
        }
        result.sendResult(createPlayableMediaItem(createMediaDescription(state)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> mediaSession.controller.transportControls.play()
            ACTION_PAUSE -> mediaSession.controller.transportControls.pause()
            ACTION_PREVIOUS -> mediaSession.controller.transportControls.skipToPrevious()
            ACTION_NEXT -> mediaSession.controller.transportControls.skipToNext()
        }
        updateForState(BrowserMediaSessionRegistry.currentState())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationManager.cancel(NOTIFICATION_ID)
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        BrowserMediaSessionRegistry.unregisterService(this)
        super.onDestroy()
    }

    internal fun updateForState(state: BrowserMediaState) {
        if (!state.isAvailable) {
            lastNotificationKey = null
            lastBrowseKey = null
            notificationManager.cancel(NOTIFICATION_ID)
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            stopSelf()
        } else {
            val notificationKey = listOf(
                state.title,
                state.artist,
                state.album,
                state.isPlaying,
                state.canSkipPrevious,
                state.canSkipNext,
                state.artworkUrl.orEmpty(),
                System.identityHashCode(
                    BrowserMediaSessionRegistry.currentMetadata()
                        ?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
                )
            ).joinToString("\u0000")
            if (state.isPlaying) {
                val notification = createMediaNotification(state)
                if (!isForeground) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                    isForeground = true
                } else if (notificationKey != lastNotificationKey) {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
                lastNotificationKey = notificationKey
            } else {
                if (isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                }
                notificationManager.cancel(NOTIFICATION_ID)
            }

            val browseKey = listOf(
                state.mediaId,
                state.title,
                state.artist,
                state.album,
                state.artworkUrl.orEmpty()
            ).joinToString("\u0000")
            if (browseKey != lastBrowseKey) {
                lastBrowseKey = browseKey
                notifyChildrenChanged(NOW_PLAYING_ID)
            }
        }
    }

    private fun createMediaNotification(state: BrowserMediaState): android.app.Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(
                BrowserMediaSessionRegistry.currentMetadata()
                    ?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            )
            .setContentTitle(state.title.ifBlank { getString(R.string.app_name) })
            .setContentText(state.artist.ifBlank { state.album })
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN_APP,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
        val compactIndices = mutableListOf<Int>()
        var actionIndex = 0
        if (state.canSkipPrevious) {
            compactIndices += actionIndex++
            builder.addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.media_previous),
                actionPendingIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS)
            )
        }
        compactIndices += actionIndex++
        builder.addAction(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (state.isPlaying) R.string.media_pause else R.string.media_play),
            actionPendingIntent(
                if (state.isPlaying) ACTION_PAUSE else ACTION_PLAY,
                REQUEST_PLAY_PAUSE
            )
        )
        if (state.canSkipNext) {
            compactIndices += actionIndex
            builder.addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.media_next),
                actionPendingIntent(ACTION_NEXT, REQUEST_NEXT)
            )
        }
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(*compactIndices.toIntArray())
        )
        return builder.build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BrowserMediaBrowserService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createMediaDescription(state: BrowserMediaState): MediaDescriptionCompat =
        MediaDescriptionCompat.Builder()
            .setMediaId(state.mediaId)
            .setTitle(state.title.ifBlank { getString(R.string.app_name) })
            .setSubtitle(state.artist)
            .setDescription(state.album)
            .apply {
                if (state.mediaUri.isNotBlank()) setMediaUri(Uri.parse(state.mediaUri))
                state.artworkUrl?.let { setIconUri(Uri.parse(it)) }
            }
            .build()

    private fun createPlayableMediaItem(
        description: MediaDescriptionCompat
    ): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )

    private fun isTrustedClient(clientPackageName: String, clientUid: Int): Boolean {
        val clientPackages = packageManager.getPackagesForUid(clientUid) ?: return false
        if (clientPackageName !in clientPackages) return false
        if (clientUid == Process.SYSTEM_UID || clientUid == Process.ROOT_UID) return true

        return try {
            val flags = packageManager.getApplicationInfo(clientPackageName, 0).flags
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
                clientPackageName in TRUSTED_CLIENT_PACKAGES
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Media browser client package disappeared during validation", error)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.media_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.media_notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val ROOT_ID = "browser_media_root"
        private const val NOW_PLAYING_ID = "browser_media_now_playing"
        private const val RECENT_ROOT_ID = "browser_media_recent"
        private const val NOTIFICATION_CHANNEL_ID = "browser_media_playback"
        private const val NOTIFICATION_ID = 2048
        private const val REQUEST_OPEN_APP = 2049
        private const val REQUEST_PREVIOUS = 2050
        private const val REQUEST_PLAY_PAUSE = 2051
        private const val REQUEST_NEXT = 2052
        internal const val ACTION_UPDATE = "com.carlosrpg.aabrowser.action.MEDIA_UPDATE"
        private const val ACTION_PLAY = "com.carlosrpg.aabrowser.action.MEDIA_PLAY"
        private const val ACTION_PAUSE = "com.carlosrpg.aabrowser.action.MEDIA_PAUSE"
        private const val ACTION_PREVIOUS = "com.carlosrpg.aabrowser.action.MEDIA_PREVIOUS"
        private const val ACTION_NEXT = "com.carlosrpg.aabrowser.action.MEDIA_NEXT"
        private const val TAG = "BrowserMediaService"
        private val TRUSTED_CLIENT_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox"
        )
    }
}
