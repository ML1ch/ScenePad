package com.marco.scenepad

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class AudioEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<String, MediaPlayer>()
    private val scheduled = ConcurrentHashMap<String, MutableList<Runnable>>()

    fun play(button: PadButton, asset: AudioAsset, masterVolume: Float) {
        stop(button.id)
        if (!File(asset.path).exists()) return

        val p = MediaPlayer()
        p.setDataSource(asset.path)
        p.setOnPreparedListener { player ->
            val start = button.startMs.coerceIn(0, max(0, asset.durationMs - 1))
            val requestedEnd = if (button.endMs <= 0) asset.durationMs else button.endMs
            val end = requestedEnd.coerceIn(start + 1, max(start + 1, asset.durationMs))
            val target = (button.volume * masterVolume).coerceIn(0f, 1f)
            val fadeIn = button.fadeInMs.coerceAtMost(end - start)
            val fadeOut = button.fadeOutMs.coerceAtMost(end - start)

            player.isLooping = false
            player.seekTo(start.toInt())
            player.setVolume(if (fadeIn > 0) 0f else target, if (fadeIn > 0) 0f else target)
            player.start()
            active[button.id] = player

            if (fadeIn > 0) scheduleRamp(button.id, player, 0f, target, fadeIn)

            val segmentMs = end - start
            if (fadeOut > 0) {
                schedule(button.id, (segmentMs - fadeOut).coerceAtLeast(0)) {
                    scheduleRamp(button.id, player, target, 0f, fadeOut)
                }
            }
            schedule(button.id, segmentMs) {
                if (button.loop && active[button.id] === player) {
                    stop(button.id)
                    play(button, asset, masterVolume)
                } else stop(button.id)
            }
        }
        p.setOnErrorListener { _, _, _ -> stop(button.id); true }
        p.prepareAsync()
    }

    fun stop(key: String, fadeMs: Long = 0) {
        val p = active.remove(key) ?: return
        cancelScheduled(key)
        if (fadeMs <= 0 || !p.isPlaying) {
            runCatching { p.stop() }; p.release(); return
        }
        scheduleRamp(key, p, 1f, 0f, fadeMs, releaseAtEnd = true)
    }

    fun stopAll() {
        active.keys.toList().forEach { stop(it) }
    }

    private fun schedule(key: String, delayMs: Long, action: () -> Unit) {
        val r = Runnable(action)
        scheduled.computeIfAbsent(key) { mutableListOf() }.add(r)
        handler.postDelayed(r, delayMs)
    }

    private fun scheduleRamp(key: String, p: MediaPlayer, from: Float, to: Float, durationMs: Long, releaseAtEnd: Boolean = false) {
        val steps = 20
        for (i in 1..steps) {
            schedule(key, durationMs * i / steps) {
                if (releaseAtEnd && i == steps) {
                    runCatching { p.stop() }; p.release(); return@schedule
                }
                if (active[key] !== p && !releaseAtEnd) return@schedule
                val v = from + (to - from) * (i.toFloat() / steps)
                runCatching { p.setVolume(v.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) }
            }
        }
    }

    private fun cancelScheduled(key: String) {
        scheduled.remove(key)?.forEach(handler::removeCallbacks)
    }
}
