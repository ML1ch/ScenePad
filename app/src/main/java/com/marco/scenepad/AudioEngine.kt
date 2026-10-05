package com.marco.scenepad

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import kotlin.math.max

/** Main-thread audio mixer. Every pad can have one or more independent MediaPlayers. */
class AudioEngine(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val channels = linkedMapOf<String, MutableList<Instance>>()
    private var suppressIdleCallback = false

    var onPlayingChanged: ((Set<String>) -> Unit)? = null
    var onIdle: (() -> Unit)? = null

    private data class Instance(
        val buttonId: String,
        val player: MediaPlayer,
        val asset: AudioAsset,
        var button: PadButton,
        val masterVolume: Float,
        var targetGain: Float,
        var envelope: Float,
        var prepared: Boolean = false,
        var stopping: Boolean = false,
        var released: Boolean = false,
        val tasks: MutableList<Runnable> = mutableListOf()
    )

    fun isPlaying(buttonId: String): Boolean = channels[buttonId]?.isNotEmpty() == true
    fun playingButtonIds(): Set<String> = channels.filterValues { it.isNotEmpty() }.keys.toSet()

    fun trigger(button: PadButton, asset: AudioAsset, masterVolume: Float) {
        val existing = channels[button.id]?.toList().orEmpty()
        if (existing.isNotEmpty()) {
            when (button.retriggerMode) {
                RetriggerMode.FADE_STOP -> { stopButton(button.id, 700); return }
                RetriggerMode.OVERLAP -> Unit
                RetriggerMode.RESTART -> {
                    suppressIdleCallback = true
                    stopButton(button.id, 0)
                    suppressIdleCallback = false
                }
            }
        }
        startInstance(button, asset, masterVolume)
    }

    fun stopButton(buttonId: String, fadeMs: Long = 0) {
        channels[buttonId]?.toList()?.forEach { stopInstance(it, fadeMs) }
    }

    fun stopAll() = channels.keys.toList().forEach { stopButton(it) }

    fun setButtonVolume(buttonId: String, volume: Float, masterVolume: Float) {
        channels[buttonId]?.forEach { instance ->
            instance.button = instance.button.copy(volume = volume.coerceIn(0f, 1f))
            instance.targetGain = (volume * masterVolume).coerceIn(0f, 1f)
            applyVolume(instance)
        }
    }

    fun setMasterVolume(volume: Float) {
        channels.values.flatten().forEach { instance ->
            instance.targetGain = (instance.button.volume * volume).coerceIn(0f, 1f)
            applyVolume(instance)
        }
    }

    private fun startInstance(button: PadButton, asset: AudioAsset, masterVolume: Float) {
        if (!File(asset.path).isFile) return
        val player = MediaPlayer()
        val instance = Instance(
            buttonId = button.id,
            player = player,
            asset = asset,
            button = button,
            masterVolume = masterVolume,
            targetGain = (button.volume * masterVolume).coerceIn(0f, 1f),
            envelope = if (button.fadeInMs > 0) 0f else 1f
        )
        channels.getOrPut(button.id) { mutableListOf() }.add(instance)
        notifyPlayingChanged()
        try {
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(asset.path)
            player.setOnPreparedListener {
                if (instance.released) return@setOnPreparedListener
                instance.prepared = true
                val start = button.startMs.coerceIn(0, max(0, asset.durationMs - 1)).toInt()
                player.seekTo(start)
                applyVolume(instance)
                player.start()
                scheduleSegment(instance)
            }
            player.setOnCompletionListener {
                if (instance.released || instance.stopping) return@setOnCompletionListener
                if (button.loop) restartSegment(instance) else finishInstance(instance)
            }
            player.setOnErrorListener { _, _, _ -> finishInstance(instance); true }
            player.prepareAsync()
        } catch (_: Exception) {
            finishInstance(instance)
        }
    }

    private fun scheduleSegment(instance: Instance) {
        val button = instance.button
        val start = button.startMs.coerceIn(0, max(0, instance.asset.durationMs - 1))
        val requestedEnd = if (button.endMs <= 0) instance.asset.durationMs else button.endMs
        val end = requestedEnd.coerceIn(start + 1, max(start + 1, instance.asset.durationMs))
        val length = end - start
        if (button.fadeInMs > 0) ramp(instance, instance.envelope, 1f, button.fadeInMs, releaseAtEnd = false)
        if (button.fadeOutMs > 0 && button.fadeOutMs < length) {
            schedule(instance, length - button.fadeOutMs) { ramp(instance, instance.envelope, 0f, button.fadeOutMs, releaseAtEnd = false) }
        }
        if (end < instance.asset.durationMs) {
            schedule(instance, length) {
                if (instance.released || instance.stopping) return@schedule
                if (button.loop) restartSegment(instance) else finishInstance(instance)
            }
        }
    }

    private fun restartSegment(instance: Instance) {
        if (instance.released || instance.stopping) return
        cancelTasks(instance)
        val start = instance.button.startMs.coerceIn(0, max(0, instance.asset.durationMs - 1)).toInt()
        runCatching {
            if (instance.player.isPlaying) instance.player.pause()
            instance.player.seekTo(start)
            instance.envelope = if (instance.button.fadeInMs > 0) 0f else 1f
            applyVolume(instance)
            instance.player.start()
            scheduleSegment(instance)
        }.onFailure { finishInstance(instance) }
    }

    private fun stopInstance(instance: Instance, fadeMs: Long) {
        if (instance.released || instance.stopping) return
        if (fadeMs <= 0 || !instance.prepared || !runCatching { instance.player.isPlaying }.getOrDefault(false)) {
            finishInstance(instance)
            return
        }
        instance.stopping = true
        cancelTasks(instance)
        ramp(instance, instance.envelope, 0f, fadeMs, releaseAtEnd = true)
    }

    private fun ramp(instance: Instance, from: Float, to: Float, durationMs: Long, releaseAtEnd: Boolean) {
        val steps = 20
        for (i in 1..steps) {
            schedule(instance, (durationMs * i / steps).coerceAtLeast(1)) {
                if (instance.released) return@schedule
                instance.envelope = from + (to - from) * (i.toFloat() / steps)
                applyVolume(instance)
                if (i == steps) {
                    instance.envelope = to
                    if (releaseAtEnd) finishInstance(instance)
                }
            }
        }
    }

    private fun applyVolume(instance: Instance) {
        if (instance.released) return
        val gain = (instance.targetGain * instance.envelope).coerceIn(0f, 1f)
        runCatching { instance.player.setVolume(gain, gain) }
    }

    private fun schedule(instance: Instance, delayMs: Long, action: () -> Unit) {
        val task = Runnable(action)
        instance.tasks += task
        handler.postDelayed(task, delayMs)
    }

    private fun cancelTasks(instance: Instance) {
        instance.tasks.forEach(handler::removeCallbacks)
        instance.tasks.clear()
    }

    private fun finishInstance(instance: Instance) {
        if (instance.released) return
        instance.released = true
        cancelTasks(instance)
        runCatching { instance.player.setOnPreparedListener(null) }
        runCatching { instance.player.setOnCompletionListener(null) }
        runCatching { instance.player.setOnErrorListener(null) }
        runCatching { instance.player.stop() }
        runCatching { instance.player.release() }
        val remaining = channels[instance.buttonId]
        remaining?.remove(instance)
        if (remaining.isNullOrEmpty()) channels.remove(instance.buttonId)
        notifyPlayingChanged()
        if (channels.isEmpty() && !suppressIdleCallback) onIdle?.invoke()
    }

    private fun notifyPlayingChanged() {
        onPlayingChanged?.invoke(playingButtonIds())
    }
}
