package com.marco.scenepad

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = JsonStore(app)
    val audioEngine = AudioEngine()
    val lightController: LightController = DemoLightController()

    var data by mutableStateOf(store.load())
        private set
    var selectedPageIndex by mutableStateOf(0)
    var arrangeSourceButtonId by mutableStateOf<String?>(null)
    var lightStatus by mutableStateOf("Não conectado")

    val activeProfile: Profile? get() = data.profiles.firstOrNull { it.id == data.activeProfileId }
    val activePage: PadPage? get() = activeProfile?.pages?.getOrNull(selectedPageIndex)

    fun setMasterVolume(v: Float) = update(data.copy(masterVolume = v.coerceIn(0f, 1f)))

    fun selectProfile(id: String) {
        selectedPageIndex = 0
        update(data.copy(activeProfileId = id))
    }

    fun createProfile(name: String) {
        val p = Profile(id = UUID.randomUUID().toString(), name = name.ifBlank { "Novo perfil" })
        update(data.copy(profiles = data.profiles + p, activeProfileId = p.id))
        selectedPageIndex = 0
    }

    fun deleteActiveProfile() {
        val id = data.activeProfileId ?: return
        val next = data.profiles.filterNot { it.id == id }
        val safe = if (next.isEmpty()) listOf(Profile(UUID.randomUUID().toString(), "Meu perfil")) else next
        update(data.copy(profiles = safe, activeProfileId = safe.first().id))
        selectedPageIndex = 0
    }

    fun renameActiveProfile(name: String) = mutateActiveProfile { it.copy(name = name.ifBlank { it.name }) }

    fun addPage() = mutateActiveProfile { p ->
        p.copy(pages = p.pages + PadPage(UUID.randomUUID().toString(), "Página ${p.pages.size + 1}"))
    }.also { selectedPageIndex = (activeProfile?.pages?.lastIndex ?: 0) }

    fun updatePage(rows: Int, columns: Int, name: String) = mutateActivePage { pg ->
        pg.copy(rows = rows.coerceIn(1, 10), columns = columns.coerceIn(1, 8), name = name.ifBlank { pg.name })
    }

    fun deleteCurrentPage() {
        val p = activeProfile ?: return
        if (p.pages.size <= 1) return
        val pages = p.pages.toMutableList().apply { removeAt(selectedPageIndex) }
        mutateActiveProfile { it.copy(pages = pages) }
        selectedPageIndex = selectedPageIndex.coerceAtMost(pages.lastIndex)
    }

    fun addButton() = mutateActivePage { pg ->
        pg.copy(buttons = pg.buttons + PadButton(id = UUID.randomUUID().toString(), label = "Botão ${pg.buttons.size + 1}"))
    }

    fun saveButton(button: PadButton) = mutateActivePage { pg ->
        val exists = pg.buttons.any { it.id == button.id }
        pg.copy(buttons = if (exists) pg.buttons.map { if (it.id == button.id) button else it } else pg.buttons + button)
    }

    fun deleteButton(id: String) {
        audioEngine.stop(id)
        mutateActivePage { pg -> pg.copy(buttons = pg.buttons.filterNot { it.id == id }) }
    }

    fun arrangeTap(id: String) {
        val source = arrangeSourceButtonId
        if (source == null) { arrangeSourceButtonId = id; return }
        if (source == id) { arrangeSourceButtonId = null; return }
        mutateActivePage { pg ->
            val list = pg.buttons.toMutableList()
            val a = list.indexOfFirst { it.id == source }
            val b = list.indexOfFirst { it.id == id }
            if (a >= 0 && b >= 0) {
                val tmp = list[a]; list[a] = list[b]; list[b] = tmp
            }
            pg.copy(buttons = list)
        }
        arrangeSourceButtonId = null
    }

    fun play(button: PadButton) {
        button.audioId?.let { id -> data.audios.firstOrNull { it.id == id }?.let { audioEngine.play(button, it, data.masterVolume) } }
        if (button.light.enabled) viewModelScope.launch {
            val result = lightController.execute(button.light)
            if (result.isFailure) lightStatus = result.exceptionOrNull()?.message ?: "Falha na luz"
        }
    }

    fun connectLights() = viewModelScope.launch {
        val r = lightController.connect(getApplication())
        lightStatus = if (r.isSuccess) "Modo demonstração conectado" else "Falha ao conectar"
    }

    fun importAudio(context: Context, uri: Uri) = viewModelScope.launch {
        val asset = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "audio-${System.currentTimeMillis()}"
            val ext = display.substringAfterLast('.', "bin")
            val dir = File(context.filesDir, "audio").apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            val mmr = MediaMetadataRetriever()
            val duration = runCatching {
                mmr.setDataSource(out.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            runCatching { mmr.release() }
            AudioAsset(UUID.randomUUID().toString(), display.substringBeforeLast('.'), out.absolutePath, duration)
        }
        if (asset != null) update(data.copy(audios = data.audios + asset))
    }

    suspend fun importButtonImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.img")
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            if (BitmapFactory.decodeFile(out.absolutePath) == null) { out.delete(); null } else out.absolutePath
        }.getOrNull()
    }

    fun renameAudio(id: String, name: String) = update(data.copy(audios = data.audios.map { if (it.id == id) it.copy(name = name) else it }))

    fun deleteAudio(id: String) {
        data.audios.firstOrNull { it.id == id }?.let { runCatching { File(it.path).delete() } }
        update(data.copy(
            audios = data.audios.filterNot { it.id == id },
            profiles = data.profiles.map { p -> p.copy(pages = p.pages.map { pg -> pg.copy(buttons = pg.buttons.map { b -> if (b.audioId == id) b.copy(audioId = null) else b }) }) }
        ))
    }

    override fun onCleared() { audioEngine.stopAll(); super.onCleared() }

    private fun mutateActivePage(f: (PadPage) -> PadPage) {
        val p = activeProfile ?: return
        val pages = p.pages.mapIndexed { i, pg -> if (i == selectedPageIndex) f(pg) else pg }
        mutateActiveProfile { it.copy(pages = pages) }
    }

    private fun mutateActiveProfile(f: (Profile) -> Profile) {
        val id = data.activeProfileId ?: return
        update(data.copy(profiles = data.profiles.map { if (it.id == id) f(it) else it }))
    }

    private fun update(newData: AppData) {
        data = newData
        store.save(newData)
    }
}
