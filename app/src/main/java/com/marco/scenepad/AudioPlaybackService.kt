package com.marco.scenepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Keeps long or overlapping pad sounds alive while ScenePad is in the background. */
class AudioPlaybackService : Service() {
    private val engine: AudioEngine get() = (application as ScenePadApplication).audioEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine.onIdle = {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                startForeground(NOTIFICATION_ID, makeNotification())
                val button = intent.toButton()
                val asset = AudioAsset(
                    id = intent.getStringExtra(EXTRA_AUDIO_ID).orEmpty(),
                    name = intent.getStringExtra(EXTRA_AUDIO_NAME).orEmpty(),
                    path = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty(),
                    durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)
                )
                engine.trigger(button, asset, intent.getFloatExtra(EXTRA_MASTER_VOLUME, 1f))
            }
            ACTION_STOP -> engine.stopButton(intent.getStringExtra(EXTRA_BUTTON_ID).orEmpty(), intent.getLongExtra(EXTRA_FADE_MS, 0))
            ACTION_STOP_ALL -> engine.stopAll()
            ACTION_SET_VOLUME -> engine.setButtonVolume(
                intent.getStringExtra(EXTRA_BUTTON_ID).orEmpty(),
                intent.getFloatExtra(EXTRA_VOLUME, 1f),
                intent.getFloatExtra(EXTRA_MASTER_VOLUME, 1f)
            )
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.toButton() = PadButton(
        id = getStringExtra(EXTRA_BUTTON_ID).orEmpty(),
        label = getStringExtra(EXTRA_BUTTON_LABEL).orEmpty(),
        audioId = getStringExtra(EXTRA_AUDIO_ID),
        startMs = getLongExtra(EXTRA_START, 0L),
        endMs = getLongExtra(EXTRA_END, 0L),
        fadeInMs = getLongExtra(EXTRA_FADE_IN, 0L),
        fadeOutMs = getLongExtra(EXTRA_FADE_OUT, 0L),
        volume = getFloatExtra(EXTRA_VOLUME, 1f),
        loop = getBooleanExtra(EXTRA_LOOP, false),
        retriggerMode = runCatching { RetriggerMode.valueOf(getStringExtra(EXTRA_RETRIGGER).orEmpty()) }.getOrDefault(RetriggerMode.RESTART)
    )

    private fun makeNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScenePad está tocando")
            .setContentText("Áudio em reprodução")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reprodução do ScenePad", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "scenepad-playback"
        private const val NOTIFICATION_ID = 3107
        private const val ACTION_PLAY = "com.marco.scenepad.action.PLAY"
        private const val ACTION_STOP = "com.marco.scenepad.action.STOP"
        private const val ACTION_STOP_ALL = "com.marco.scenepad.action.STOP_ALL"
        private const val ACTION_SET_VOLUME = "com.marco.scenepad.action.SET_VOLUME"
        private const val EXTRA_BUTTON_ID = "button_id"
        private const val EXTRA_BUTTON_LABEL = "button_label"
        private const val EXTRA_AUDIO_ID = "audio_id"
        private const val EXTRA_AUDIO_NAME = "audio_name"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"
        private const val EXTRA_FADE_IN = "fade_in"
        private const val EXTRA_FADE_OUT = "fade_out"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_MASTER_VOLUME = "master_volume"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_RETRIGGER = "retrigger"
        private const val EXTRA_FADE_MS = "fade_ms"

        fun play(context: Context, button: PadButton, asset: AudioAsset, masterVolume: Float) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_BUTTON_ID, button.id)
                putExtra(EXTRA_BUTTON_LABEL, button.label)
                putExtra(EXTRA_AUDIO_ID, asset.id)
                putExtra(EXTRA_AUDIO_NAME, asset.name)
                putExtra(EXTRA_AUDIO_PATH, asset.path)
                putExtra(EXTRA_DURATION, asset.durationMs)
                putExtra(EXTRA_START, button.startMs)
                putExtra(EXTRA_END, button.endMs)
                putExtra(EXTRA_FADE_IN, button.fadeInMs)
                putExtra(EXTRA_FADE_OUT, button.fadeOutMs)
                putExtra(EXTRA_VOLUME, button.volume)
                putExtra(EXTRA_MASTER_VOLUME, masterVolume)
                putExtra(EXTRA_LOOP, button.loop)
                putExtra(EXTRA_RETRIGGER, button.retriggerMode.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context, buttonId: String, fadeMs: Long = 0) {
            context.startService(Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_BUTTON_ID, buttonId).putExtra(EXTRA_FADE_MS, fadeMs))
        }

        fun stopAll(context: Context) {
            context.startService(Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP_ALL))
        }

        fun setVolume(context: Context, buttonId: String, volume: Float, masterVolume: Float) {
            context.startService(Intent(context, AudioPlaybackService::class.java).setAction(ACTION_SET_VOLUME)
                .putExtra(EXTRA_BUTTON_ID, buttonId).putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_MASTER_VOLUME, masterVolume))
        }
    }
}
