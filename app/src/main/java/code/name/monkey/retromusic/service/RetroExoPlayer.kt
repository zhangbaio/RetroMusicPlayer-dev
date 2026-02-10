package code.name.monkey.retromusic.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.playback.Playback.PlaybackCallbacks
import code.name.monkey.retromusic.util.PreferenceUtil.playbackPitch
import code.name.monkey.retromusic.util.PreferenceUtil.playbackSpeed
import code.name.monkey.retromusic.util.logE
import code.name.monkey.retromusic.network.ServerDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RetroExoPlayer(context: Context) : AudioManagerPlayback(context), Player.Listener, KoinComponent {
    private val serverRepository: code.name.monkey.retromusic.repository.ServerRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer = ExoPlayer.Builder(context).build()
    override var callbacks: PlaybackCallbacks? = null

    /**
     * @return True if the player is ready to go, false otherwise
     */
    override var isInitialized = false
        private set

    init {
        player.setWakeMode(C.WAKE_MODE_LOCAL)
    }

    /**
     * @param song The song object you want to play
     * @return True if the `player` has been prepared and is ready to play, false otherwise
     */
    private var pendingCompletion: ((Boolean) -> Unit)? = null

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        isInitialized = false
        pendingCompletion = completion

        scope.launch {
            try {
                val mediaSource = createMediaSource(song)
                Handler(Looper.getMainLooper()).post {
                    player.setMediaSource(mediaSource)
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        false  // Don't let ExoPlayer handle audio focus - parent class handles it
                    )
                    player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)

                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                player.removeListener(this)
                                isInitialized = true
                                pendingCompletion?.invoke(true)
                                pendingCompletion = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            player.removeListener(this)
                            pendingCompletion?.invoke(false)
                            pendingCompletion = null
                        }
                    })
                    player.addListener(this@RetroExoPlayer)
                    player.prepare()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                pendingCompletion?.invoke(false)
                pendingCompletion = null
            }
        }
    }

    /**
     * Create a MediaSource for the song, handling server authentication if needed
     */
    private suspend fun createMediaSource(song: Song): MediaSource {
        val uriString = song.uri.toString()
        return if (song.sourceType == SourceType.SERVER || song.sourceType == SourceType.WEBDAV
            || uriString.startsWith("http://") || uriString.startsWith("https://")) {
            createServerMediaSource(song)
        } else {
            createDefaultMediaSource(song)
        }
    }

    /**
     * Create a MediaSource with server Bearer Token authentication
     */
    private suspend fun createServerMediaSource(song: Song): MediaSource {
        val configId = song.webDavConfigId ?: resolveServerConfigIdFromUrl(song.uri.toString())
            ?: return createDefaultMediaSource(song)

        val config = serverRepository.getConfigById(configId)
            ?: return createDefaultMediaSource(song)

        // Create custom data source factory with Bearer Token authentication
        val dataSourceFactory = ServerDataSourceFactory(config.apiToken)

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(song.uri))
    }

    private suspend fun resolveServerConfigIdFromUrl(url: String): Long? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null
        }
        return serverRepository.getEnabledConfigs()
            .firstOrNull { config ->
                url.startsWith(config.serverUrl.trimEnd('/'), ignoreCase = true)
            }
            ?.id
    }

    /**
     * Create a default MediaSource for local files
     */
    private fun createDefaultMediaSource(song: Song): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(song.uri))
    }

    /**
     * Set the MediaPlayer to start when this MediaPlayer finishes playback.
     *
     * @param path The path of the file, or the http/rtsp URL of the stream you want to play
     */
    override fun setNextDataSource(path: Uri?) {}

    /**
     * Starts or resumes playback.
     */
    override fun start(): Boolean {
        super.start()
        return try {
            player.play()
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Resets the MediaPlayer to its uninitialized state.
     */
    override fun stop() {
        super.stop()
        player.stop()
        isInitialized = false
    }

    /**
     * Releases resources associated with this MediaPlayer object.
     */
    override fun release() {
        stop()
        player.release()
    }

    /**
     * Pauses playback. Call start() to resume.
     */
    override fun pause(): Boolean {
        super.pause()
        return try {
            player.pause()
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Checks whether the MultiPlayer is playing.
     */
    override val isPlaying: Boolean
        get() = isInitialized && (player.isPlaying || player.playbackState == Player.STATE_ENDED)

    /**
     * Gets the duration of the file.
     *
     * @return The duration in milliseconds
     */
    override fun duration(): Int {
        return if (!this.isInitialized) {
            -1
        } else try {
            player.duration.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Gets the current playback position.
     *
     * @return The current position in milliseconds
     */
    override fun position(): Int {
        return if (!this.isInitialized) {
            -1
        } else try {
            player.currentPosition.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Gets the current playback position.
     *
     * @param whereto The offset in milliseconds from the start to seek to
     * @return The offset in milliseconds from the start to seek to
     */
    override fun seek(whereto: Int, force: Boolean): Int {
        return try {
            player.seekTo(whereto.toLong())
            whereto
        } catch (e: Exception) {
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        return try {
            player.volume = vol
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sets the audio session ID.
     *
     * @param sessionId The audio session ID
     */
    @OptIn(UnstableApi::class)
    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            player.audioSessionId = sessionId
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the audio session ID.
     *
     * @return The current audio session ID.
     */
    override val audioSessionId: Int
        @OptIn(UnstableApi::class)
        get() = player.audioSessionId

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) {
            callbacks?.onTrackEnded()
        } else {
            callbacks?.onPlayStateChanged()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        logE(error)
        isInitialized = false
        player.release()
        player = ExoPlayer.Builder(context).build()
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        context.showToast(R.string.unplayable_file)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            callbacks?.onTrackWentToNext()
            return
        }
    }

    override fun setCrossFadeDuration(duration: Int) {}

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    companion object {
        val TAG: String = RetroExoPlayer::class.java.simpleName
    }
}
