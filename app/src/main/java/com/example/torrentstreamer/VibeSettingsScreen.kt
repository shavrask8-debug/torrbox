package com.example.torrentstreamer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
    val isImeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isImeVisible, isFocused) {
        if (isFocused) {
            if (isImeVisible) {
                keyboardAppearedSinceLastFocused = true
            } else if (keyboardAppearedSinceLastFocused) {
                focusManager.clearFocus()
                keyboardAppearedSinceLastFocused = false
            }
        } else {
            keyboardAppearedSinceLastFocused = false
        }
    }

    onFocusEvent {
        isFocused = it.isFocused
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibeSettingsScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpenWebAdmin: (String) -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val view = LocalView.current

    val sharedPrefs = remember { context.getSharedPreferences("vibe_prefs", Context.MODE_PRIVATE) }
    var promptVlc by remember { mutableStateOf(sharedPrefs.getBoolean("prompt_vlc", false)) }
    var localSettings by remember(settings) { mutableStateOf(settings) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("MAIN", "SEARCH", "APPLICATION")

    var newName by remember { mutableStateOf("") }
    var newHost by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }

    // Отримуємо стан міні-плеєра для динамічного відступу списку
    val currentUrl by viewModel.currentPlayingUrl.collectAsState()
    val latestSession by viewModel.latestSession.collectAsState()
    val isMiniPlayerActive = latestSession != null || currentUrl != null

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val physicalPath = getPhysicalPathFromUri(context, uri)
            if (physicalPath != null) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                localSettings = localSettings.copy(torrentsSavePath = physicalPath)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(text = "Settings", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onOpenWebAdmin("http://127.0.0.1:8090")
                    }) {
                        Icon(Icons.Default.Language, contentDescription = "Web UI")
                    }

                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            sharedPrefs.edit()
                                .putBoolean("prompt_vlc", promptVlc)
                                .apply()
                            viewModel.saveSettings(localSettings)
                        },
                        modifier = Modifier.padding(end = 12.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text(text = "SAVE")
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                for (index in tabTitles.indices) {
                    val title = tabTitles[index]
                    SegmentedButton(
                        selected = selectedTab == index,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selectedTab = index
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabTitles.size)
                    ) {
                        Text(text = title, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .overscroll(rememberOverscrollEffect())
                    .padding(vertical = 12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        SettingsGroup(title = "Cache Settings") {
                            val currentCacheMb = localSettings.cacheSize / 1024 / 1024

                            SliderSetting(
                                label = "Cache Size",
                                value = currentCacheMb.toFloat(),
                                range = 32f..1024f,
                                unitLabel = "MB"
                            ) {
                                localSettings = localSettings.copy(cacheSize = it.toLong() * 1024 * 1024)
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            SliderSetting(
                                label = "Readahead Cache (5-100%, rec. 95%)",
                                value = localSettings.readAhead.toFloat(),
                                range = 40f..95f,
                                unitLabel = "%"
                            ) { localSettings = localSettings.copy(readAhead = it.toInt()) }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            val preloadPercentage = localSettings.preloadCache
                            val calculatedPreloadMb = (currentCacheMb * (preloadPercentage / 100f)).toInt()
                            SliderSetting(
                                label = "Preload Cache Before Play - $preloadPercentage% ($calculatedPreloadMb MB)",
                                value = preloadPercentage.toFloat(),
                                range = 0f..100f,
                                unitLabel = "%"
                            ) { localSettings = localSettings.copy(preloadCache = it.toInt()) }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Cache Storage Location",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Better use RAM or external storage on flash-based devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        localSettings = localSettings.copy(useDisk = false)
                                    },
                                    color = if (!localSettings.useDisk) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                        Text("RAM", fontWeight = FontWeight.Bold, color = if (!localSettings.useDisk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                Surface(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        val safeExtDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                                        localSettings = localSettings.copy(
                                            useDisk = true,
                                            torrentsSavePath = if (localSettings.torrentsSavePath.isNullOrBlank()) safeExtDir else localSettings.torrentsSavePath
                                        )
                                    },
                                    color = if (localSettings.useDisk) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                        Text("Disk", fontWeight = FontWeight.Bold, color = if (localSettings.useDisk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }

                            if (localSettings.useDisk) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = localSettings.torrentsSavePath ?: "",
                                        onValueChange = { localSettings = localSettings.copy(torrentsSavePath = it) },
                                        label = { Text("Torrents Save Path") },
                                        modifier = Modifier.weight(1f).clearFocusOnKeyboardDismiss(),
                                        shape = MaterialTheme.shapes.medium
                                    )
                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            directoryPickerLauncher.launch(null)
                                        },
                                        shape = MaterialTheme.shapes.medium,
                                        contentPadding = PaddingValues(12.dp),
                                        modifier = Modifier.height(56.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Select Folder")
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                SwitchSetting(
                                    label = "Remove Cache On Drop",
                                    checked = localSettings.removeCacheOnDrop
                                ) { localSettings = localSettings.copy(removeCacheOnDrop = it) }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                promptVlc = false
                                sharedPrefs.edit()
                                    .putBoolean("prompt_vlc", false)
                                    .apply()
                                val defaults = viewModel.resetSettingsToDefault()
                                localSettings = defaults
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text("RESET TO DEFAULT", fontWeight = FontWeight.ExtraBold)
                        }

                        // Компенсаційний відступ для вільного скролу кнопки Reset над плаваючим міні-плеєром
                        if (isMiniPlayerActive) {
                            Spacer(modifier = Modifier.height(100.dp))
                        }
                    }

                    1 -> {
                        SettingsGroup(title = "Search") {
                            SwitchSetting(
                                label = "Turn on torrents search by RuTor",
                                checked = localSettings.enableRutorSearch
                            ) { localSettings = localSettings.copy(enableRutorSearch = it) }
                            if (localSettings.enableRutorSearch) {
                                Text(
                                    text = "NOTE: The database takes about 500 MB of RAM.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        SettingsGroup(title = "Torznab Search") {
                            SwitchSetting(
                                label = "Enable Torznab Search",
                                checked = localSettings.enableTorznabSearch
                            ) { localSettings = localSettings.copy(enableTorznabSearch = it) }

                            if (localSettings.enableTorznabSearch) {
                                Spacer(Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("Name (Optional)") },
                                    placeholder = { Text("My Tracker") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newHost,
                                    onValueChange = { newHost = it },
                                    label = { Text("Torznab Host URL") },
                                    placeholder = { Text("http://localhost:9117") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newKey,
                                    onValueChange = { newKey = it },
                                    label = { Text("API Key") },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss()
                                )
                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            Toast.makeText(context, "Перевірка підключення до Torznab...", Toast.LENGTH_SHORT).show()
                                        },
                                        enabled = newHost.isNotBlank(),
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    ) {
                                        Text("TEST")
                                    }

                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            val safeTorznabList = localSettings.torznabUrls ?: emptyList()
                                            val mutableTorznabList = safeTorznabList.toMutableList()
                                            mutableTorznabList.add(TorznabConfig(newHost, newKey, name = newName.ifBlank { "Torznab Server" }))
                                            localSettings = localSettings.copy(torznabUrls = mutableTorznabList)

                                            newName = ""
                                            newHost = ""
                                            newKey = ""
                                        },
                                        enabled = newHost.isNotBlank(),
                                        modifier = Modifier.weight(1.5f),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Icon(Icons.Default.Add, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("ADD SERVER")
                                    }
                                }

                                val safeTorznabList = localSettings.torznabUrls ?: emptyList()
                                if (safeTorznabList.isNotEmpty()) {
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Підключені індексатори:",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    val mutableTorznabList = safeTorznabList.toMutableList()
                                    mutableTorznabList.forEachIndexed { index, config ->
                                        Card(
                                            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = config.name ?: "Torznab Server", fontWeight = FontWeight.Bold)
                                                    Text(text = config.host ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                IconButton(onClick = {
                                                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                                    mutableTorznabList.removeAt(index)
                                                    localSettings = localSettings.copy(torznabUrls = mutableTorznabList)
                                                }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Видалити", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Компенсаційний відступ для вільного скролу пошуку над плаваючим міні-плеєром
                        if (isMiniPlayerActive) {
                            Spacer(modifier = Modifier.height(100.dp))
                        }
                    }

                    2 -> {
                        val safeTmdbSettings = localSettings.tmdbSettings ?: TMDBConfig()

                        SettingsGroup(title = "TMDB") {
                            OutlinedTextField(
                                value = safeTmdbSettings.apiKey ?: "",
                                onValueChange = {
                                    localSettings = localSettings.copy(tmdbSettings = safeTmdbSettings.copy(apiKey = it))
                                },
                                label = { Text("TMDB API Key") },
                                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                                shape = MaterialTheme.shapes.medium
                            )
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = safeTmdbSettings.apiUrl ?: "https://api.themoviedb.org",
                                onValueChange = {
                                    localSettings = localSettings.copy(tmdbSettings = safeTmdbSettings.copy(apiUrl = it))
                                },
                                label = { Text("TMDB API URL") },
                                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                                shape = MaterialTheme.shapes.medium
                            )
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = safeTmdbSettings.imageUrl ?: "https://image.tmdb.org",
                                onValueChange = {
                                    localSettings = localSettings.copy(tmdbSettings = safeTmdbSettings.copy(imageUrl = it))
                                },
                                label = { Text("TMDB Image URL") },
                                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                                shape = MaterialTheme.shapes.medium
                            )
                        }

                        SettingsGroup(title = "Players") {
                            // ОНОВЛЕНО: Повністю видалено налаштування ембієнт підсвічування для збереження чистоти коду та фокусу на продуктивності!
                            SwitchSetting(
                                label = "Prompt to open video in VLC",
                                checked = promptVlc
                            ) { promptVlc = it }
                        }

                        Spacer(Modifier.height(16.dp))

                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onOpenWebAdmin("http://127.0.0.1:8090/#/settings")
                            },
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(text = "Advanced Web-інтерфейс (PRO Mode)", fontWeight = FontWeight.ExtraBold)
                                    Text(
                                        text = "Перейти у повну веб-адмінку на порту 8090",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Компенсаційний відступ для вільного скролу кнопок налаштувань та карти Advanced Web UI над плаваючим міні-плеєром
                        if (isMiniPlayerActive) {
                            Spacer(modifier = Modifier.height(100.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun getPhysicalPathFromUri(context: Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val split = docId.split(":")
        val type = split[0]
        if ("primary".equals(type, ignoreCase = true)) {
            val baseDir = Environment.getExternalStorageDirectory().absolutePath
            if (split.size > 1) "$baseDir/${split[1]}" else baseDir
        } else {
            val baseDir = "/storage/$type"
            if (split.size > 1) "$baseDir/${split[1]}" else baseDir
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unitLabel: String,
    onValueChange: (Float) -> Unit
) {
    var temp by remember(value) { mutableFloatStateOf(value) }
    val view = LocalView.current

    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = "${temp.toInt()} $unitLabel",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Slider(
            value = temp,
            onValueChange = {
                if (it.toInt() != temp.toInt()) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                temp = it
            },
            onValueChangeFinished = { onValueChange(temp) },
            valueRange = range
        )
    }
}

@Composable
fun SwitchSetting(label: String, subtext: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val view = LocalView.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label, color = MaterialTheme.colorScheme.onSurface)
            if (subtext != null) {
                Text(text = subtext, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onCheckedChange(it)
            }
        )
    }
}