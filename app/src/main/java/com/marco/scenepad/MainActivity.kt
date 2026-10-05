package com.marco.scenepad

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val vm by viewModels<AppViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScenePadTheme { ScenePadApp(vm) } }
    }
}

@Composable
fun ScenePadTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF8EA2FF),
        secondary = Color(0xFFFFB86B),
        background = Color(0xFF111318),
        surface = Color(0xFF1B1E25)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Screen { PAD, LIBRARY, PROFILES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenePadApp(vm: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.PAD) }
    var editButton by remember { mutableStateOf<PadButton?>(null) }
    var arrangeMode by remember { mutableStateOf(false) }
    var pageSettings by remember { mutableStateOf(false) }
    val profile = vm.activeProfile
    val page = vm.activePage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(profile?.name ?: "ScenePad", fontWeight = FontWeight.Bold); Text(page?.name ?: "", fontSize = 12.sp) } },
                actions = {
                    if (screen == Screen.PAD) {
                        IconButton(onClick = { arrangeMode = !arrangeMode; vm.arrangeSourceButtonId = null }) { Icon(Icons.Default.SwapHoriz, "Organizar") }
                        IconButton(onClick = { pageSettings = true }) { Icon(Icons.Default.GridView, "Grade") }
                        IconButton(onClick = { vm.audioEngine.stopAll() }) { Icon(Icons.Default.StopCircle, "Parar tudo") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(screen == Screen.PAD, { screen = Screen.PAD }, { Icon(Icons.Default.Apps, null) }, label = { Text("Pad") })
                NavigationBarItem(screen == Screen.LIBRARY, { screen = Screen.LIBRARY }, { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Biblioteca") })
                NavigationBarItem(screen == Screen.PROFILES, { screen = Screen.PROFILES }, { Icon(Icons.Default.Person, null) }, label = { Text("Perfis") })
                NavigationBarItem(screen == Screen.SETTINGS, { screen = Screen.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") })
            }
        },
        floatingActionButton = {
            if (screen == Screen.PAD) FloatingActionButton(onClick = { editButton = PadButton(UUID.randomUUID().toString(), label = "Novo botão") }) { Icon(Icons.Default.Add, null) }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.PAD -> PadScreen(vm, arrangeMode, onEdit = { editButton = it })
                Screen.LIBRARY -> LibraryScreen(vm)
                Screen.PROFILES -> ProfilesScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
            if (arrangeMode) AssistChip(
                onClick = { arrangeMode = false; vm.arrangeSourceButtonId = null },
                label = { Text(if (vm.arrangeSourceButtonId == null) "Organizar: toque em 2 botões para trocar" else "Agora toque no destino") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }

    editButton?.let { initial -> ButtonEditorDialog(vm, initial, { editButton = null }, { saved -> vm.saveButton(saved); editButton = null }) }
    if (pageSettings && page != null) PageSettingsDialog(vm, page) { pageSettings = false }
}

@Composable
private fun PadScreen(vm: AppViewModel, arrangeMode: Boolean, onEdit: (PadButton) -> Unit) {
    val p = vm.activeProfile ?: return
    val page = vm.activePage ?: return
    Column(Modifier.fillMaxSize()) {
        if (p.pages.size > 1) {
            ScrollableTabRow(selectedTabIndex = vm.selectedPageIndex, edgePadding = 8.dp) {
                p.pages.forEachIndexed { i, pg -> Tab(selected = i == vm.selectedPageIndex, onClick = { vm.selectedPageIndex = i }, text = { Text(pg.name) }) }
            }
        }
        if (page.buttons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TouchApp, null, Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp)); Text("Crie seu primeiro botão no +")
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(page.columns),
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(page.buttons.size, key = { page.buttons[it].id }) { index ->
                    val b = page.buttons[index]
                    PadButtonCard(
                        b,
                        selected = vm.arrangeSourceButtonId == b.id,
                        onTap = { if (arrangeMode) vm.arrangeTap(b.id) else vm.play(b) },
                        onLong = { onEdit(b) },
                        modifier = Modifier.padding(5.dp).height((620f / page.rows.coerceAtLeast(2)).coerceIn(90f, 190f).dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PadButtonCard(b: PadButton, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier) {
    val c = parseColor(b.colorHex)
    Card(
        onClick = onTap,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primary else c)
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onTap).padding(10.dp)) {
            b.imagePath?.let { path ->
                remember(path) { BitmapFactory.decodeFile(path) }?.let { bm ->
                    Image(bm.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(b.icon, fontSize = 30.sp)
                Spacer(Modifier.height(6.dp))
                Text(b.label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                if (b.light.enabled) Text("💡", fontSize = 13.sp)
            }
            IconButton(onClick = onLong, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) { Icon(Icons.Default.Edit, "Editar", Modifier.size(17.dp)) }
        }
    }
}

@Composable
private fun LibraryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importAudio(context, it) } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { launcher.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Adicionar áudio") }
        Spacer(Modifier.height(12.dp))
        if (vm.data.audios.isEmpty()) Text("Sua biblioteca está vazia.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.data.audios, key = { it.id }) { a ->
                Card { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(a.name, fontWeight = FontWeight.Bold); Text(formatTime(a.durationMs), fontSize = 12.sp) }
                    IconButton(onClick = { vm.deleteAudio(a.id) }) { Icon(Icons.Default.Delete, "Excluir") }
                } }
            }
        }
    }
}

@Composable
private fun ProfilesScreen(vm: AppViewModel) {
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(newName, { newName = it }, label = { Text("Novo perfil") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.createProfile(newName); newName = "" }, enabled = newName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Criar perfil") }
        Spacer(Modifier.height(16.dp))
        vm.data.profiles.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.selectProfile(p.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == vm.data.activeProfileId, onClick = { vm.selectProfile(p.id) })
                    Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${p.pages.size} pág.")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Volume geral", style = MaterialTheme.typography.titleMedium)
        Slider(vm.data.masterVolume, vm::setMasterVolume)
        Text("${(vm.data.masterVolume * 100).toInt()}%")
        HorizontalDivider()
        Text("Iluminação", style = MaterialTheme.typography.titleMedium)
        Text("Status: ${vm.lightStatus}")
        Button(onClick = vm::connectLights) { Icon(Icons.Default.Lightbulb, null); Spacer(Modifier.width(8.dp)); Text("Conectar (demonstração)") }
        Text("A estrutura para cenas de luz já está no app. A conexão real com Google Home precisa do cadastro do projeto Home APIs e autorização da sua casa.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Páginas", style = MaterialTheme.typography.titleMedium)
        Button(onClick = vm::addPage) { Text("Adicionar página") }
        if ((vm.activeProfile?.pages?.size ?: 0) > 1) OutlinedButton(onClick = vm::deleteCurrentPage) { Text("Excluir página atual") }
        HorizontalDivider()
        OutlinedButton(onClick = vm::deleteActiveProfile) { Text("Excluir perfil atual") }
    }
}

@Composable
private fun PageSettingsDialog(vm: AppViewModel, page: PadPage, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(page.name) }
    var rows by remember { mutableFloatStateOf(page.rows.toFloat()) }
    var cols by remember { mutableFloatStateOf(page.columns.toFloat()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Configurar página") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") })
            Spacer(Modifier.height(12.dp)); Text("Linhas: ${rows.toInt()}"); Slider(rows, { rows = it }, valueRange = 1f..10f, steps = 8)
            Text("Colunas: ${cols.toInt()}"); Slider(cols, { cols = it }, valueRange = 1f..8f, steps = 6)
        } },
        confirmButton = { TextButton(onClick = { vm.updatePage(rows.toInt(), cols.toInt(), name); dismiss() }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ButtonEditorDialog(vm: AppViewModel, initial: PadButton, dismiss: () -> Unit, save: (PadButton) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var b by remember { mutableStateOf(initial) }
    var chooseAudio by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(formatEditable(b.startMs)) }
    var end by remember { mutableStateOf(if (b.endMs <= 0) "" else formatEditable(b.endMs)) }
    var fadeIn by remember { mutableStateOf((b.fadeInMs / 1000.0).toString().trimEnd('0').trimEnd('.')) }
    var fadeOut by remember { mutableStateOf((b.fadeOutMs / 1000.0).toString().trimEnd('0').trimEnd('.')) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importButtonImage(context, uri)?.let { b = b.copy(imagePath = it) } }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Editar botão") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(b.label, { b = b.copy(label = it) }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(b.icon, { b = b.copy(icon = it) }, label = { Text("Ícone / emoji") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(b.colorHex, { b = b.copy(colorHex = it) }, label = { Text("Cor (#RRGGBB)") }, modifier = Modifier.fillMaxWidth()) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { imageLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Text("Imagem") }
                    if (b.imagePath != null) OutlinedButton(onClick = { b = b.copy(imagePath = null) }) { Text("Remover") }
                } }
                item { Button(onClick = { chooseAudio = true }, modifier = Modifier.fillMaxWidth()) { Text(vm.data.audios.firstOrNull { it.id == b.audioId }?.name ?: "Escolher áudio") } }
                item { OutlinedTextField(start, { start = it }, label = { Text("Começar em (HH:MM:SS)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(end, { end = it }, label = { Text("Terminar em (vazio = fim)") }, modifier = Modifier.fillMaxWidth()) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fadeIn, { fadeIn = it }, label = { Text("Fade in (s)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(fadeOut, { fadeOut = it }, label = { Text("Fade out (s)") }, modifier = Modifier.weight(1f))
                } }
                item { Text("Volume: ${(b.volume * 100).toInt()}%"); Slider(b.volume, { b = b.copy(volume = it) }) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(b.loop, { b = b.copy(loop = it) }); Spacer(Modifier.width(8.dp)); Text("Repetir em loop") } }
                item { HorizontalDivider(); Text("Ação de luz", fontWeight = FontWeight.Bold) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(b.light.enabled, { b = b.copy(light = b.light.copy(enabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Disparar luz junto") } }
                if (b.light.enabled) {
                    item { OutlinedTextField(b.light.deviceName, { b = b.copy(light = b.light.copy(deviceName = it)) }, label = { Text("Lâmpada / grupo") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(b.light.colorHex, { b = b.copy(light = b.light.copy(colorHex = it)) }, label = { Text("Cor da luz") }, modifier = Modifier.fillMaxWidth()) }
                    item { Text("Brilho: ${b.light.brightness}%"); Slider(b.light.brightness / 100f, { b = b.copy(light = b.light.copy(brightness = (it * 100).toInt())) }) }
                    item { Text("Efeito: ${b.light.effect.name}"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LightEffect.entries.forEach { e -> FilterChip(selected = b.light.effect == e, onClick = { b = b.copy(light = b.light.copy(effect = e)) }, label = { Text(e.name) }) }
                    } }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            save(b.copy(
                startMs = parseTime(start), endMs = end.takeIf { it.isNotBlank() }?.let(::parseTime) ?: 0,
                fadeInMs = ((fadeIn.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0),
                fadeOutMs = ((fadeOut.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0)
            ))
        }) { Text("Salvar") } },
        dismissButton = { Row {
            if (vm.activePage?.buttons?.any { it.id == initial.id } == true) TextButton(onClick = { vm.deleteButton(initial.id); dismiss() }) { Text("Excluir") }
            TextButton(onClick = dismiss) { Text("Cancelar") }
        } }
    )

    if (chooseAudio) AlertDialog(
        onDismissRequest = { chooseAudio = false },
        title = { Text("Escolher áudio") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { ListItem(headlineContent = { Text("Sem áudio") }, modifier = Modifier.clickable { b = b.copy(audioId = null); chooseAudio = false }) }
            items(vm.data.audios, key = { it.id }) { a -> ListItem(
                headlineContent = { Text(a.name) }, supportingContent = { Text(formatTime(a.durationMs)) },
                modifier = Modifier.clickable { b = b.copy(audioId = a.id, endMs = 0); end = ""; chooseAudio = false }
            ) }
        } },
        confirmButton = { TextButton(onClick = { chooseAudio = false }) { Text("Fechar") } }
    )
}

private fun parseColor(hex: String): Color = runCatching {
    val v = android.graphics.Color.parseColor(hex)
    Color(v)
}.getOrElse { Color(0xFF3949AB) }

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
private fun formatEditable(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}
private fun parseTime(text: String): Long {
    val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
    val seconds = when (parts.size) { 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; 2 -> parts[0] * 60 + parts[1]; 1 -> parts[0]; else -> 0 }
    return seconds.coerceAtLeast(0) * 1000
}
