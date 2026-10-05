package com.marco.scenepad

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

data class YoutubeImportProgress(val percent: Int = 0, val message: String = "Preparando…", val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = JsonStore(app)
    val audioEngine = (app as ScenePadApplication).audioEngine
    val lightController: LightController = DemoLightController()
    private val appContext = app.applicationContext
    private var youtubeProcessId: String? = null
    private var youtubeWorkDir: File? = null
    private val volumeSaveHandler = Handler(Looper.getMainLooper())
    private val saveVolumeRunnable = Runnable { store.save(data) }

    var data by mutableStateOf(store.load())
        private set
    var playingButtonIds by mutableStateOf(audioEngine.playingButtonIds())
        private set
    var pendingAudioImport by mutableStateOf<AudioAsset?>(null)
        private set
    var youtubeImportProgress by mutableStateOf<YoutubeImportProgress?>(null)
        private set
    var selectedPageIndex by mutableStateOf(0)
    var arrangeSourceButtonId by mutableStateOf<String?>(null)
    var lightStatus by mutableStateOf("Não conectado")

    init {
        audioEngine.onPlayingChanged = { playingButtonIds = it }
    }

    val activeProfile: Profile? get() = data.profiles.firstOrNull { it.id == data.activeProfileId }
    val activePage: PadPage? get() = activeProfile?.pages?.getOrNull(selectedPageIndex)

    fun setMasterVolume(v: Float) {
        val volume = v.coerceIn(0f, 1f)
        update(data.copy(masterVolume = volume))
        audioEngine.setMasterVolume(volume)
    }

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
        audioEngine.stopButton(id)
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
        button.audioId?.let { id -> data.audios.firstOrNull { it.id == id }?.let { AudioPlaybackService.play(appContext, button, it, data.masterVolume) } }
        if (button.light.enabled) viewModelScope.launch {
            val result = lightController.execute(button.light)
            if (result.isFailure) lightStatus = result.exceptionOrNull()?.message ?: "Falha na luz"
        }
    }

    fun connectLights() = viewModelScope.launch {
        val r = lightController.connect(getApplication())
        lightStatus = if (r.isSuccess) "Modo demonstração conectado" else "Falha ao conectar"
    }

    fun prepareAudioImport(context: Context, uri: Uri) = viewModelScope.launch {
        val asset = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "audio-${System.currentTimeMillis()}"
            val ext = display.substringAfterLast('.', "audio").takeIf { it.length <= 8 } ?: "audio"
            val dir = File(context.cacheDir, "audio-imports").apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            val duration = readDuration(out)
            if (duration <= 0L) { out.delete(); return@withContext null }
            AudioAsset(UUID.randomUUID().toString(), File(display).nameWithoutExtension, out.absolutePath, duration)
        }
        if (asset != null) pendingAudioImport = asset
    }

    fun downloadYoutubeAudio(rawUrl: String) {
        val url = rawUrl.trim()
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        val host = parsed?.host?.lowercase()
        if (parsed?.scheme !in listOf("http", "https") || host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")) {
            youtubeImportProgress = YoutubeImportProgress(error = "Cole um link válido do YouTube.")
            return
        }
        if (youtubeImportProgress?.error == null && youtubeImportProgress != null) return
        youtubeImportProgress = YoutubeImportProgress(message = "Lendo o vídeo…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    initializeYoutubeTools()
                    val info = YoutubeDL.getInstance().getInfo(url)
                    val workDir = File(appContext.cacheDir, "youtube-import-${UUID.randomUUID()}").apply { mkdirs() }
                    youtubeWorkDir = workDir
                    val processId = UUID.randomUUID().toString()
                    youtubeProcessId = processId
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "5")
                        addOption("--no-part")
                        addOption("-o", File(workDir, "audio.%(ext)s").absolutePath)
                    }
                    YoutubeDL.getInstance().execute(request, processId) { progress, eta, _ ->
                        val percent = progress.toInt().coerceIn(0, 100)
                        viewModelScope.launch {
                            if (youtubeProcessId == processId) youtubeImportProgress = YoutubeImportProgress(percent, "Baixando áudio — ${percent}%${if (eta > 0) " · ${eta}s restantes" else ""}")
                        }
                    }
                    val output = File(workDir, "audio.mp3")
                    check(output.isFile && output.length() > 0) { "O YouTube não retornou um arquivo de áudio." }
                    val duration = readDuration(output)
                    check(duration > 0) { "Não consegui ler a duração do áudio baixado." }
                    AudioAsset(UUID.randomUUID().toString(), info.title?.takeIf { it.isNotBlank() } ?: "Áudio do YouTube", output.absolutePath, duration)
                }
            }
            if (result.isSuccess) {
                pendingAudioImport = result.getOrThrow()
                youtubeImportProgress = null
                youtubeProcessId = null
                youtubeWorkDir = null
            } else {
                val message = result.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() } ?: "Falha ao baixar o áudio. Tente outro link."
                youtubeImportProgress = YoutubeImportProgress(error = message)
                youtubeProcessId = null
                youtubeWorkDir?.deleteRecursively()
                youtubeWorkDir = null
            }
        }
    }

    fun cancelYoutubeDownload() {
        youtubeProcessId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        youtubeWorkDir?.deleteRecursively()
        youtubeProcessId = null
        youtubeWorkDir = null
        youtubeImportProgress = null
    }

    fun finishAudioImport(name: String) {
        val pending = pendingAudioImport ?: return
        val source = File(pending.path)
        val destinationDir = File(appContext.filesDir, "audio").apply { mkdirs() }
        val extension = source.extension.ifBlank { "mp3" }
        val destination = File(destinationDir, "${pending.id}.$extension")
        if (!source.renameTo(destination)) {
            runCatching { source.copyTo(destination, overwrite = true); source.delete() }.onFailure { return }
        }
        val added = pending.copy(name = name.trim().ifBlank { pending.name }, path = destination.absolutePath)
        pendingAudioImport = null
        update(data.copy(audios = data.audios + added))
    }

    fun cancelPendingAudioImport() {
        pendingAudioImport?.let { runCatching { File(it.path).delete() } }
        pendingAudioImport = null
    }

    private fun initializeYoutubeTools() {
        synchronized(youtubeInitialized) {
            if (youtubeInitialized.get()) return
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            youtubeInitialized.set(true)
        }
    }

    private fun readDuration(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { mmr.release() } }
    }

    suspend fun importButtonImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.img")
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            if (BitmapFactory.decodeFile(out.absolutePath) == null) { out.delete(); null } else out.absolutePath
        }.getOrNull()
    }

    fun renameAudio(id: String, name: String) = update(data.copy(audios = data.audios.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }))

    fun isPlaying(buttonId: String) = buttonId in playingButtonIds

    fun setButtonVolume(buttonId: String, volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        val profileId = data.activeProfileId ?: return
        val profile = activeProfile ?: return
        val currentPage = profile.pages.getOrNull(selectedPageIndex) ?: return
        val button = currentPage.buttons.firstOrNull { it.id == buttonId } ?: return
        if (!isPlaying(buttonId)) return
        val updated = button.copy(volume = level)
        audioEngine.setButtonVolume(buttonId, level, data.masterVolume)
        data = data.copy(profiles = data.profiles.map { p ->
            if (p.id != profileId) p else p.copy(pages = p.pages.mapIndexed { index, page ->
                if (index != selectedPageIndex) page else page.copy(buttons = page.buttons.map { if (it.id == buttonId) updated else it })
            })
        })
        volumeSaveHandler.removeCallbacks(saveVolumeRunnable)
        volumeSaveHandler.postDelayed(saveVolumeRunnable, 350)
    }

    fun commitVolumeChanges() {
        volumeSaveHandler.removeCallbacks(saveVolumeRunnable)
        store.save(data)
    }

    fun deleteAudio(id: String) {
        val buttonIds = data.profiles.flatMap { it.pages }.flatMap { it.buttons }.filter { it.audioId == id }.map { it.id }
        buttonIds.forEach { audioEngine.stopButton(it) }
        data.audios.firstOrNull { it.id == id }?.let { runCatching { File(it.path).delete() } }
        update(data.copy(
            audios = data.audios.filterNot { it.id == id },
            profiles = data.profiles.map { p -> p.copy(pages = p.pages.map { pg -> pg.copy(buttons = pg.buttons.map { b -> if (b.audioId == id) b.copy(audioId = null) else b }) }) }
        ))
    }

    override fun onCleared() {
        volumeSaveHandler.removeCallbacks(saveVolumeRunnable)
        store.save(data)
        audioEngine.onPlayingChanged = null
        super.onCleared()
    }

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

    companion object { private val youtubeInitialized = AtomicBoolean(false) }
}
