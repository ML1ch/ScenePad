package com.marco.scenepad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class JsonStore(private val context: Context) {
    private val file = File(context.filesDir, "scenepad-data.json")

    fun load(): AppData {
        if (!file.exists()) return seed()
        return runCatching { decode(JSONObject(file.readText())) }.getOrElse { seed() }
    }

    fun save(data: AppData) {
        file.writeText(encode(data).toString(2))
    }

    private fun seed(): AppData {
        val profile = Profile(id = java.util.UUID.randomUUID().toString(), name = "Meu primeiro perfil")
        return AppData(profiles = listOf(profile), activeProfileId = profile.id)
    }

    private fun encode(data: AppData): JSONObject = JSONObject().apply {
        put("activeProfileId", data.activeProfileId)
        put("masterVolume", data.masterVolume.toDouble())
        put("audios", JSONArray().apply {
            data.audios.forEach { a -> put(JSONObject().apply {
                put("id", a.id); put("name", a.name); put("path", a.path); put("durationMs", a.durationMs)
            }) }
        })
        put("profiles", JSONArray().apply {
            data.profiles.forEach { p -> put(JSONObject().apply {
                put("id", p.id); put("name", p.name)
                put("pages", JSONArray().apply {
                    p.pages.forEach { pg -> put(JSONObject().apply {
                        put("id", pg.id); put("name", pg.name); put("rows", pg.rows); put("columns", pg.columns)
                        put("buttons", JSONArray().apply {
                            pg.buttons.forEach { b -> put(JSONObject().apply {
                                put("id", b.id); put("label", b.label); put("icon", b.icon); put("colorHex", b.colorHex)
                                put("imagePath", b.imagePath); put("audioId", b.audioId)
                                put("startMs", b.startMs); put("endMs", b.endMs)
                                put("fadeInMs", b.fadeInMs); put("fadeOutMs", b.fadeOutMs)
                                put("volume", b.volume.toDouble()); put("loop", b.loop); put("retriggerMode", b.retriggerMode.name)
                                put("light", JSONObject().apply {
                                    put("enabled", b.light.enabled); put("deviceName", b.light.deviceName)
                                    put("colorHex", b.light.colorHex); put("brightness", b.light.brightness)
                                    put("effect", b.light.effect.name); put("durationSec", b.light.durationSec)
                                })
                            }) }
                        })
                    }) }
                })
            }) }
        })
    }

    private fun decode(root: JSONObject): AppData {
        val audios = root.optJSONArray("audios").toList { j ->
            AudioAsset(j.getString("id"), j.getString("name"), j.getString("path"), j.optLong("durationMs", 0))
        }
        val profiles = root.optJSONArray("profiles").toList { p ->
            Profile(
                id = p.getString("id"), name = p.getString("name"),
                pages = p.optJSONArray("pages").toList { pg ->
                    PadPage(
                        id = pg.getString("id"), name = pg.optString("name", "Página"),
                        rows = pg.optInt("rows", 4).coerceIn(1, 10), columns = pg.optInt("columns", 3).coerceIn(1, 8),
                        buttons = pg.optJSONArray("buttons").toList { b ->
                            val l = b.optJSONObject("light") ?: JSONObject()
                            PadButton(
                                id = b.getString("id"), label = b.optString("label", "Novo"), icon = b.optString("icon", "🔊"),
                                colorHex = b.optString("colorHex", "#3949AB"),
                                imagePath = b.optString("imagePath").takeIf { it.isNotBlank() && it != "null" },
                                audioId = b.optString("audioId").takeIf { it.isNotBlank() && it != "null" },
                                startMs = b.optLong("startMs", 0), endMs = b.optLong("endMs", 0),
                                fadeInMs = b.optLong("fadeInMs", 0), fadeOutMs = b.optLong("fadeOutMs", 0),
                                volume = b.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f), loop = b.optBoolean("loop", false),
                                retriggerMode = runCatching { RetriggerMode.valueOf(b.optString("retriggerMode", "RESTART")) }.getOrDefault(RetriggerMode.RESTART),
                                light = LightAction(
                                    enabled = l.optBoolean("enabled", false), deviceName = l.optString("deviceName", ""),
                                    colorHex = l.optString("colorHex", "#FF7A18"), brightness = l.optInt("brightness", 50).coerceIn(0, 100),
                                    effect = runCatching { LightEffect.valueOf(l.optString("effect", "STATIC")) }.getOrDefault(LightEffect.STATIC),
                                    durationSec = l.optInt("durationSec", 30).coerceAtLeast(1)
                                )
                            )
                        }
                    )
                }
            )
        }
        return AppData(
            audios = audios,
            profiles = profiles.ifEmpty { seed().profiles },
            activeProfileId = root.optString("activeProfileId").takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id,
            masterVolume = root.optDouble("masterVolume", 1.0).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun <T> JSONArray?.toList(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
    }
}
