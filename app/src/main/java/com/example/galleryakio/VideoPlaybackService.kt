package com.example.galleryakio

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps a video's sound going after the user leaves the app, with the usual media notification.
 * It holds no player of its own: the viewer owns the player and runs this only while away.
 */
class VideoPlaybackService : MediaSessionService() {
    private var added: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        added = session?.also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Not always still there: removing a session the service has dropped itself throws.
        added?.let { if (isSessionAdded(it)) removeSession(it) }
        added = null
        super.onDestroy()
    }

    companion object {
        /** The viewer's current session, set before [start]. */
        var session: MediaSession? = null

        /** Only while the app is still in front: Android refuses it a moment later. */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, VideoPlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VideoPlaybackService::class.java))
        }
    }
}
