package com.marco.scenepad

data class AudioAsset(
    val id: String,
    val name: String,
    val path: String,
    val durationMs: Long
)

enum class LightEffect { STATIC, FIRE, PULSE, SUNSET }
enum class RetriggerMode { FADE_STOP, OVERLAP, RESTART }

data class LightAction(
    val enabled: Boolean = false,
    val deviceName: String = "",
    val colorHex: String = "#FF7A18",
    val brightness: Int = 50,
    val effect: LightEffect = LightEffect.STATIC,
    val durationSec: Int = 30
)

data class PadButton(
    val id: String,
    val label: String = "Novo",
    val icon: String = "🔊",
    val colorHex: String = "#3949AB",
    val imagePath: String? = null,
    val audioId: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val volume: Float = 1f,
    val loop: Boolean = false,
    val retriggerMode: RetriggerMode = RetriggerMode.RESTART,
    val light: LightAction = LightAction()
)

data class PadPage(
    val id: String,
    val name: String = "Página 1",
    val rows: Int = 4,
    val columns: Int = 3,
    val buttons: List<PadButton> = emptyList()
)

data class Profile(
    val id: String,
    val name: String,
    val pages: List<PadPage> = listOf(PadPage(id = java.util.UUID.randomUUID().toString()))
)

data class AppData(
    val audios: List<AudioAsset> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val masterVolume: Float = 1f
)
