/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import top.hasiy.rawviewer.R
import java.io.File

@Composable
fun LivePhotoPlayer(
    videoFile: File,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val player = remember(videoFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(player, isMuted) {
        player.volume = if (isMuted) MUTED_VOLUME else NORMAL_VOLUME
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnPlaybackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlaybackError()
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (player.playbackState != Player.STATE_ENDED) player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                contentDescription = viewContext.getString(R.string.live_video_description)
                this.player = player
            }
        },
        update = { playerView -> playerView.player = player },
    )
}

private const val MUTED_VOLUME = 0f
private const val NORMAL_VOLUME = 1f
