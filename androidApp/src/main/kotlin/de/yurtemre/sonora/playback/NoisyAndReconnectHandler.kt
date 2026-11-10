package de.yurtemre.sonora.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import de.yurtemre.sonora.shared.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * PLAY-18/19. Becoming noisy honours `pause_on_disconnect` (FIX D-06: if false keep playing). Resume on
 * reconnect when `resume_on_connect` ∧ paused by disconnect ∧ a non-builtin output appears.
 * The player must not use Media3's own noisy handling (`setHandleAudioBecomingNoisy(false)`).
 */
class NoisyAndReconnectHandler(
    private val context: Context,
    private val player: Player,
    private val settings: Flow<AppSettings>,
    private val scope: CoroutineScope,
    /** Optional faded play (PLAY-19); defaults to plain play(). */
    private val resume: () -> Unit = { player.play() },
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var current = AppSettings()
    var wasPlayingBeforeDisconnect = false
        private set
    private var job: Job? = null
    private var registered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!current.pauseOnDisconnect || !player.isPlaying) return
            wasPlayingBeforeDisconnect = true
            player.pause()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!current.resumeOnConnect || !wasPlayingBeforeDisconnect || player.isPlaying) return
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val external = outputs.any {
                it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            if (external) { wasPlayingBeforeDisconnect = false; resume() }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        job = scope.launch { settings.collect { current = it } }
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    fun stop() {
        if (!registered) return
        registered = false
        job?.cancel(); job = null
        try { context.unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /** Clear the flag, e.g. when the user starts playback manually. */
    fun clearDisconnectFlag() { wasPlayingBeforeDisconnect = false }
}
