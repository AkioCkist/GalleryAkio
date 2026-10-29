package com.appslab.gallery.ui.components

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appslab.gallery.data.MediaItem

/** Remembers playback positions per video id for the "remember video position" setting. */
object VideoPositions {
    private const val PREFS = "video_positions"

    fun get(context: Context, id: Long): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(id.toString(), 0L)

    fun put(context: Context, id: Long, position: Long, duration: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        // Finished (or nearly) videos start from the beginning next time.
        if (duration > 0 && position > duration - 2000) prefs.remove(id.toString())
        else prefs.putLong(id.toString(), position)
        prefs.apply()
    }
}

/**
 * ExoPlayer for the current pager page only; other pages show the thumbnail,
 * so swiping through a mixed album never holds more than one decoder.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: MediaItem,
    isCurrentPage: Boolean,
    rememberPosition: Boolean,
    onControllerVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            if (rememberPosition) seekTo(VideoPositions.get(context, item.id))
            prepare()
        }
    }

    LaunchedEffect(isCurrentPage) {
        player.playWhenReady = isCurrentPage
        if (!isCurrentPage) player.pause()
    }

    DisposableEffect(player) {
        onDispose {
            if (rememberPosition) VideoPositions.put(context, item.id, player.currentPosition, player.duration)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                    onControllerVisibility(v == android.view.View.VISIBLE)
                })
            }
        },
        update = { it.player = player },
        modifier = modifier.fillMaxSize(),
    )
}
